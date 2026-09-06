#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""mock_server.py — 无限自动化框架 mock 服务器（SDD 附录 A / §16.3）。

用途：真机联调时替代真实服务器，与 Android Agent 跑通协议链路。

- 传输：长度前缀 JSON（4 字节大端 + UTF-8）与 0xAC42 二进制帧混跑（§16.3 解复用）
- 收 REGISTER → 回 REGISTER_ACK（内置 §16.4 示例配置）
- 交互命令（stdin）：
    connect <mac>            CONNECT_DEVICE
    read <mac> <svc> <char>  READ_CHAR
    write <mac> <svc> <char> <hex>  WRITE_CHAR
    rules <mac>              SET_POLL_RULES（温度示例规则）
    file <mac> <path>        FILE_TRANSFER（推送文件）
    cancel <taskId>          FILE_CANCEL
    status                   GET_STATUS
    reset                    RESET
    quit
- 事件落日志；CMD_ACK 与 requestId 自动对账；FILE_DOWNLOAD_* 自动续传/重传。

自测：python3 mock_server.py --selftest
"""

import argparse
import base64
import binascii
import hashlib
import json
import os
import socket
import struct
import sys
import tempfile
import threading
import time
import uuid

MAGIC = 0xAC42
FRAME_TYPES = {0x01: "FILE_FRAME", 0x02: "FILE_END", 0x03: "FILE_ACK", 0x04: "LOG_FRAME"}


def crc16_ccitt(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if (crc & 0x8000) else (crc << 1) & 0xFFFF
    return crc


def encode_frame(ftype: int, seq: int, payload: bytes) -> bytes:
    header = struct.pack(">HBII", MAGIC, ftype, seq, len(payload))
    crc = crc16_ccitt(header[2:] + payload)
    return header + payload + struct.pack(">H", crc)


def decode_frame(buf: bytes):
    """buf 为完整帧字节。返回 (type, seq, payload)。CRC 校验。"""
    magic, ftype, seq, length = struct.unpack(">HBII", buf[:11])
    assert magic == MAGIC, "bad magic"
    payload = buf[11:11 + length]
    crc_expect = struct.unpack(">H", buf[11 + length:13 + length])[0]
    assert crc16_ccitt(buf[2:11] + payload) == crc_expect, "crc mismatch"
    return ftype, seq, payload


def encode_json(msg: dict) -> bytes:
    body = json.dumps(msg).encode("utf-8")
    return struct.pack(">I", len(body)) + body


# §16.4 示例配置（单设备 watch，battery/temperature/status 字段 + 温度规则）
def sample_config(dut_mac: str) -> dict:
    return {
        "configVersion": 1,
        "maxSlots": 3,
        "timeSliceMs": 2000,
        "tickIntervalMs": 100,
        "devices": [{
            "deviceId": "dut-001",
            "mac": dut_mac,
            "type": "watch",
            "priority": 5,
            "persistent": False,
            "profile": {"2A19": "180F", "2A21": "180A", "2A24": "180A"},
            "fields": {
                "battery": {"char": "2A19", "format": "uint8"},
                "temperature": {"char": "2A21", "format": "sint16", "byteOrder": "LE", "scale": 0.1},
                "status": {"char": "2A24", "format": "utf8"},
            },
            "polling": {
                "intervalMs": 60000,
                "readCharacteristics": ["2A19", "2A24"],
                "notifyCharacteristics": ["2A19"],
                "reportOnlyChanged": True,
            },
            "rules": [{
                "ruleId": "r1", "priority": 10, "stopOnMatch": False,
                "conditions": [{"field": "temperature", "op": "GT", "value": 40}],
                "actions": [{"type": "REPORT_EVENT", "params": {"event": "TEMP_CRITICAL"}}],
            }],
        }],
    }


class MockServer:
    def __init__(self, host="0.0.0.0", port=10086, dut_mac="AA:BB:CC:DD:EE:FF"):
        self.host = host
        self.port = port
        self.dut_mac = dut_mac
        self.conn = None
        self.lock = threading.Lock()
        self.pending_acks = {}          # requestId -> cmd type（对账）
        self.downloading = None         # 当前下载任务 dict(taskId, data, chunk, acked, event)
        self.events_log = []
        self.running = True

    # ---------------- 连接与收发 ----------------

    def serve_forever(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.host, self.port))
        srv.listen(1)
        print(f"[mock] listening on {self.host}:{self.port}", flush=True)
        while self.running:
            try:
                conn, addr = srv.accept()
            except OSError:
                break
            print(f"[mock] agent connected: {addr}", flush=True)
            with self.lock:
                self.conn = conn
            try:
                self._read_loop(conn)
            except (ConnectionError, OSError) as e:
                print(f"[mock] connection lost: {e}", flush=True)
            finally:
                with self.lock:
                    self.conn = None
                if self.downloading:
                    self.downloading["paused"] = True
                    print("[mock] download paused, wait RESUME", flush=True)
        srv.close()

    def _read_loop(self, conn):
        buf = b""
        while self.running:
            data = conn.recv(8192)
            if not data:
                raise ConnectionError("eof")
            buf += data
            while True:
                if len(buf) < 2:
                    break
                if buf[:2] == struct.pack(">H", MAGIC):
                    if len(buf) < 11:
                        break
                    length = struct.unpack(">I", buf[7:11])[0]
                    total = 11 + length + 2
                    if len(buf) < total:
                        break
                    frame, buf = buf[:total], buf[total:]
                    self._on_frame(frame)
                else:
                    if len(buf) < 4:
                        break
                    length = struct.unpack(">I", buf[:4])[0]
                    if len(buf) < 4 + length:
                        break
                    body, buf = buf[4:4 + length], buf[4 + length:]
                    self._on_json(json.loads(body.decode("utf-8")))

    # ---------------- 事件处理 ----------------

    def _on_json(self, msg: dict):
        mtype = msg.get("type")
        self.events_log.append(msg)
        if mtype == "REGISTER":
            print(f"[mock] REGISTER deviceId={msg.get('deviceId')} hasCrashLog={msg.get('hasCrashLog')}", flush=True)
            self._send({
                "type": "REGISTER_ACK", "timestamp": int(time.time() * 1000),
                "requestId": str(uuid.uuid4()), "errorCode": 0,
                "config": sample_config(self.dut_mac),
            })
        elif mtype == "CMD_ACK":
            rid = msg.get("requestId")
            cmd = self.pending_acks.pop(rid, None)
            code = msg.get("errorCode")
            mark = "OK" if code == 0 else f"ERR {code}"
            print(f"[mock] CMD_ACK {cmd} ({rid}) -> {mark}"
                  + (f" rawStatus={msg.get('rawStatus')}" if msg.get("rawStatus") else "")
                  + (f" result={msg.get('result')}" if msg.get("result") else ""), flush=True)
        elif mtype == "FILE_DOWNLOAD_READY":
            print(f"[mock] FILE_DOWNLOAD_READY {msg.get('taskId')}", flush=True)
            self._push_file(msg.get("taskId"))
        elif mtype == "FILE_DOWNLOAD_ACK":
            resend = msg.get("resendSeqs", [])
            print(f"[mock] FILE_DOWNLOAD_ACK resend={resend}", flush=True)
            if resend and self.downloading:
                self._resend(resend)
        elif mtype == "FILE_DOWNLOAD_RESUME":
            last = msg.get("lastSeq", 0)
            print(f"[mock] FILE_DOWNLOAD_RESUME lastSeq={last}", flush=True)
            if self.downloading:
                self._push_file(self.downloading["taskId"], from_seq=last + 1)
        elif mtype in ("FILE_PROGRESS", "FILE_RESULT"):
            print(f"[mock] {mtype} {msg}", flush=True)
        else:
            print(f"[mock] event {mtype}: {json.dumps(msg, ensure_ascii=False)[:300]}", flush=True)

    def _on_frame(self, frame: bytes):
        ftype, seq, payload = decode_frame(frame)
        name = FRAME_TYPES.get(ftype, hex(ftype))
        print(f"[mock] frame {name} seq={seq} len={len(payload)}", flush=True)
        # FILE_ACK(0x03)/日志帧只记录；payload 不回读

    # ---------------- 命令下发 ----------------

    def _send(self, msg: dict):
        with self.lock:
            conn = self.conn
        if conn is None:
            print("[mock] not connected, drop", msg.get("type"), flush=True)
            return False
        conn.sendall(encode_json(msg))
        return True

    def send_command(self, ctype: str, **fields) -> str:
        rid = fields.pop("requestId", None) or str(uuid.uuid4())
        msg = {"type": ctype, "timestamp": int(time.time() * 1000), "requestId": rid}
        msg.update(fields)
        if self._send(msg):
            self.pending_acks[rid] = ctype
            print(f"[mock] sent {ctype} ({rid})", flush=True)
        return rid

    # ---------------- 文件推送（§7.6 下载协议） ----------------

    def start_file_transfer(self, mac: str, path: str):
        data = open(path, "rb").read()
        task_id = "task-" + uuid.uuid4().hex[:8]
        self.downloading = {
            "taskId": task_id, "data": data, "chunk": 4096,
            "paused": False,
        }
        self.send_command("FILE_TRANSFER", taskId=task_id, fileId=os.path.basename(path),
                          deviceMac=mac, size=len(data),
                          sha256=base64.b64encode(hashlib.sha256(data).digest()).decode())

    def _push_file(self, task_id: str, from_seq: int = 1):
        dl = self.downloading
        if dl is None or dl["taskId"] != task_id:
            return
        with self.lock:
            conn = self.conn
        if conn is None:
            return
        data, chunk = dl["data"], dl["chunk"]
        total = (len(data) + chunk - 1) // chunk
        for seq in range(from_seq, total + 1):
            if dl.get("paused"):
                return
            piece = data[(seq - 1) * chunk: seq * chunk]
            conn.sendall(encode_frame(0x01, seq, piece))
        conn.sendall(encode_frame(0x02, 0, hashlib.sha256(data).digest()))
        print(f"[mock] file pushed from seq {from_seq}, total {total} chunks", flush=True)

    def _resend(self, seqs):
        dl = self.downloading
        if dl is None:
            return
        with self.lock:
            conn = self.conn
        if conn is None:
            return
        for seq in seqs:
            piece = dl["data"][(seq - 1) * dl["chunk"]: seq * dl["chunk"]]
            conn.sendall(encode_frame(0x01, seq, piece))
        conn.sendall(encode_frame(0x02, 0, hashlib.sha256(dl["data"]).digest()))
        print(f"[mock] resent {seqs}", flush=True)


# ---------------- 交互 CLI ----------------

def cli(server: MockServer):
    print("[mock] commands: connect/read/write/rules/file/cancel/status/reset/quit", flush=True)
    for line in sys.stdin:
        parts = line.strip().split()
        if not parts:
            continue
        cmd = parts[0]
        try:
            if cmd == "connect" and len(parts) == 2:
                server.send_command("CONNECT_DEVICE", deviceMac=parts[1])
            elif cmd == "read" and len(parts) == 4:
                server.send_command("READ_CHAR", deviceMac=parts[1], service=parts[2], char=parts[3])
            elif cmd == "write" and len(parts) == 5:
                server.send_command("WRITE_CHAR", deviceMac=parts[1], service=parts[2],
                                    char=parts[3],
                                    payload=base64.b64encode(binascii.unhexlify(parts[4])).decode())
            elif cmd == "rules" and len(parts) == 2:
                server.send_command("SET_POLL_RULES", deviceMac=parts[1],
                                    rules=sample_config(server.dut_mac)["devices"][0]["rules"])
            elif cmd == "file" and len(parts) == 3:
                server.start_file_transfer(parts[1], parts[2])
            elif cmd == "cancel" and len(parts) == 2:
                server.send_command("FILE_CANCEL", taskId=parts[1])
            elif cmd == "status":
                server.send_command("GET_STATUS")
            elif cmd == "reset":
                server.send_command("RESET")
            elif cmd == "quit":
                break
            else:
                print("[mock] unknown/arg-count: " + line.strip(), flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"[mock] cmd error: {e}", flush=True)
    server.running = False


# ---------------- 自测 ----------------

def selftest():
    """起服务 + 模拟 agent 客户端，跑通 REGISTER/命令/ACK/文件帧闭环。"""
    port = 18086
    server = MockServer(port=port, dut_mac="AA:BB:CC:DD:EE:FF")
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    time.sleep(0.3)

    cli_sock = socket.create_connection(("127.0.0.1", port), timeout=5)

    def read_json(sock):
        hdr = sock.recv(4)
        length = struct.unpack(">I", hdr)[0]
        body = b""
        while len(body) < length:
            body += sock.recv(length - len(body))
        return json.loads(body.decode("utf-8"))

    # 1) REGISTER → REGISTER_ACK（含 config）
    cli_sock.sendall(encode_json({
        "type": "REGISTER", "timestamp": int(time.time() * 1000),
        "deviceId": "phone-ut", "ip": "127.0.0.1", "port": port,
        "androidSdk": 34, "bleSupported": True, "maxConnections": 5,
        "agentVersion": "1.0", "token": "ut-token",
    }))
    ack = read_json(cli_sock)
    assert ack["type"] == "REGISTER_ACK" and ack["errorCode"] == 0, ack
    cfg = ack["config"]
    assert cfg["devices"][0]["mac"] == "AA:BB:CC:DD:EE:FF"
    assert cfg["devices"][0]["fields"]["battery"]["char"] == "2A19"
    print("[selftest] REGISTER/REGISTER_ACK OK", flush=True)

    # 2) CONNECT_DEVICE → 回 CMD_ACK → 对账
    rid = server.send_command("CONNECT_DEVICE", deviceMac="AA:BB:CC:DD:EE:FF")
    time.sleep(0.2)
    cmd = read_json(cli_sock)
    assert cmd["type"] == "CONNECT_DEVICE" and cmd["requestId"] == rid
    cli_sock.sendall(encode_json({"type": "CMD_ACK", "timestamp": int(time.time() * 1000),
                                  "requestId": rid, "errorCode": 0}))
    time.sleep(0.3)
    assert rid not in server.pending_acks, "ACK 对账失败"
    print("[selftest] CONNECT_DEVICE/CMD_ACK 对账 OK", flush=True)

    # 3) 文件推送闭环：agent 收 FILE_TRANSFER → 回 READY → 收帧 → ACK
    fd, fpath = tempfile.mkstemp(suffix=".bin")
    os.write(fd, bytes(range(256)) * 40)  # 10240B
    os.close(fd)
    server.start_file_transfer("AA:BB:CC:DD:EE:FF", fpath)
    cmd = read_json(cli_sock)
    assert cmd["type"] == "FILE_TRANSFER"
    task_id = cmd["taskId"]
    cli_sock.sendall(encode_json({"type": "CMD_ACK", "timestamp": int(time.time() * 1000),
                                  "requestId": cmd["requestId"], "errorCode": 0}))
    cli_sock.sendall(encode_json({"type": "FILE_DOWNLOAD_READY", "timestamp": 0,
                                  "taskId": task_id, "fileId": "fw.bin"}))
    # 收 FILE_FRAME*4 + FILE_END（chunk=4096 → 3 数据帧 + 1 结束帧）
    received = b""
    got_end = False
    buf = b""
    cli_sock.settimeout(5)
    while not got_end:
        buf += cli_sock.recv(8192)
        while len(buf) >= 11:
            assert buf[:2] == struct.pack(">H", MAGIC), "帧同步丢失"
            length = struct.unpack(">I", buf[7:11])[0]
            total = 11 + length + 2
            if len(buf) < total:
                break
            ftype, seq, payload = decode_frame(buf[:total])
            buf = buf[total:]
            if ftype == 0x01:
                received += payload
            elif ftype == 0x02:
                got_end = True
                assert payload == hashlib.sha256(received).digest(), "sha256 不一致"
    assert len(received) == 10240
    cli_sock.sendall(encode_json({"type": "FILE_DOWNLOAD_ACK", "timestamp": 0,
                                  "taskId": task_id, "resendSeqs": []}))
    time.sleep(0.2)
    print("[selftest] 文件帧推送 + CRC + SHA-256 OK", flush=True)

    cli_sock.close()
    server.running = False
    os.unlink(fpath)
    print("[selftest] ALL PASS", flush=True)


# ---------------- 冒烟模式（emulator_smoke.sh 调用） ----------------

def smoke(server: MockServer):
    """脚本化冒烟场景：CONNECT → READY → POLL_RESULT → 规则触发 → 文件传输。"""
    def expect(desc, pred, timeout_s):
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            for m in server.events_log:
                if pred(m):
                    print(f"[smoke] PASS: {desc}", flush=True)
                    return True
            time.sleep(0.2)
        print(f"[smoke] FAIL timeout: {desc}", flush=True)
        return False

    ok = True
    ok &= expect("REGISTER 收到", lambda m: m.get("type") == "REGISTER", 30)
    if not ok:
        print("[smoke] FAIL: no REGISTER, abort", flush=True)
        print("SMOKE FAIL", flush=True)
        return 1

    # 加快轮询节奏（示例配置 60s 太慢），再连接虚拟 DUT。
    server.send_command("SET_POLLING_INTERVAL", deviceMac=server.dut_mac, intervalMs=5000)
    server.send_command("CONNECT_DEVICE", deviceMac=server.dut_mac)
    ok &= expect("DEVICE_STATE READY", lambda m: m.get("type") == "DEVICE_STATE"
                 and m.get("state") == "READY", 60)
    ok &= expect("POLL_RESULT battery=85", lambda m: m.get("type") == "POLL_RESULT"
                 and (m.get("values") or {}).get("battery") == 85, 120)
    ok &= expect("TEMP_CRITICAL 规则触发", lambda m: m.get("type") == "TEMP_CRITICAL", 60)

    # 文件传输闭环（10KB，虚拟 TransferAdapter 窗口恒 ACK）。
    fd, fpath = tempfile.mkstemp(suffix=".bin")
    os.write(fd, bytes(range(256)) * 40)
    os.close(fd)
    server.start_file_transfer(server.dut_mac, fpath)
    ok &= expect("FILE_RESULT errorCode=0", lambda m: m.get("type") == "FILE_RESULT"
                 and m.get("errorCode") == 0, 120)
    os.unlink(fpath)

    print("SMOKE PASS" if ok else "SMOKE FAIL", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="mock server for wireless-auto-test agent")
    parser.add_argument("--port", type=int, default=10086)
    parser.add_argument("--dut-mac", default="AA:BB:CC:DD:EE:FF")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--smoke", action="store_true",
                        help="脚本化冒烟场景（配合模拟器），按结果退出 0/1")
    args = parser.parse_args()

    if args.selftest:
        selftest()
    elif args.smoke:
        srv = MockServer(port=args.port, dut_mac=args.dut_mac)
        th = threading.Thread(target=srv.serve_forever, daemon=True)
        th.start()
        code = smoke(srv)
        srv.running = False
        sys.exit(code)
    else:
        srv = MockServer(port=args.port, dut_mac=args.dut_mac)
        th = threading.Thread(target=srv.serve_forever, daemon=True)
        th.start()
        cli(srv)
