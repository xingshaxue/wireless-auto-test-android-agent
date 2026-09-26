"""文件传输模块端到端测试（SDD §7.6 TCP 侧下载协议 / §13 日志上传 / §16.3 帧格式）。

起完整 Runtime（:memory: DB、随机端口）+ 模拟 agent 客户端（asyncio + codec）：
完整推送闭环 / ACK 重传 / 断连 RESUME 续传 / FILE_REQUEST 自动传输 /
LOG_FRAME 日志接收 / FILE_CANCEL 取消 / 登记配额检查。
"""

import asyncio
import base64
import collections
import hashlib

import pytest

from wireless_server.codec import StreamDemuxer, encode_frame, encode_json
from wireless_server.runtime import Runtime
from wireless_server.settings import Settings

MAC = "AA:BB:CC:DD:EE:FF"
AGENT = "agent-tr-01"

DATA = bytes(range(256)) * 40  # 10240B → chunk 4096 下 3 帧（4096+4096+1808）
SHA256 = hashlib.sha256(DATA).digest()


class FakeAgent:
    """模拟 Android agent：asyncio TCP + codec 编解码。"""

    def __init__(self, reader: asyncio.StreamReader,
                 writer: asyncio.StreamWriter) -> None:
        self.reader = reader
        self.writer = writer
        self.demuxer = StreamDemuxer()
        self.inbox: collections.deque = collections.deque()

    @classmethod
    async def connect(cls, port: int) -> "FakeAgent":
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        return cls(reader, writer)

    async def send(self, msg: dict) -> None:
        self.writer.write(encode_json(msg))
        await self.writer.drain()

    async def send_frame(self, ftype: int, seq: int, payload: bytes) -> None:
        self.writer.write(encode_frame(ftype, seq, payload))
        await self.writer.drain()

    async def recv(self, timeout: float = 5.0):
        if self.inbox:
            return self.inbox.popleft()
        data = await asyncio.wait_for(self.reader.read(65536), timeout)
        if not data:
            raise EOFError("服务器已断开")
        self.inbox.extend(self.demuxer.feed(data))
        return self.inbox.popleft()

    async def recv_json(self, timeout: float = 5.0) -> dict:
        kind, item = await self.recv(timeout)
        assert kind == "json", f"期望 JSON 报文，实收 {kind}"
        return item

    async def recv_frame(self, timeout: float = 5.0):
        kind, item = await self.recv(timeout)
        assert kind == "frame", f"期望二进制帧，实收 {kind}: {item}"
        return item  # (ftype, seq, payload)

    async def ack_cmd(self, cmd: dict) -> None:
        """对命令回 CMD_ACK 成功（§8.4 对账）。"""
        await self.send({"type": "CMD_ACK", "timestamp": 1,
                         "requestId": cmd["requestId"], "errorCode": 0})

    async def close(self) -> None:
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except ConnectionError:
            pass


def _register_msg() -> dict:
    return {
        "type": "REGISTER", "timestamp": 1, "deviceId": AGENT,
        "ip": "10.0.0.1", "port": 0, "androidSdk": 34, "bleSupported": True,
        "maxConnections": 5, "agentVersion": "1.0",
    }


async def _connect_registered(port: int) -> FakeAgent:
    agent = await FakeAgent.connect(port)
    await agent.send(_register_msg())
    ack = await agent.recv_json()
    assert ack["type"] == "REGISTER_ACK" and ack["errorCode"] == 0
    return agent


def _make_settings(tmp_path) -> Settings:
    settings = Settings()
    settings.gateway.host = "127.0.0.1"
    settings.gateway.port = 0
    settings.gateway.ack_timeout_ms = 60000  # 测试内 agent 均回 ACK，不触发重发
    settings.api.host = "127.0.0.1"
    settings.api.port = 0
    settings.storage.db_path = ":memory:"
    settings.storage.files_dir = str(tmp_path / "files")
    settings.storage.logs_dir = str(tmp_path / "agent_logs")
    return settings


@pytest.fixture
async def runtime(tmp_path):
    rt = Runtime(_make_settings(tmp_path))
    await rt.start()
    yield rt
    await rt.stop()


async def _until(fn, timeout: float = 5.0):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while True:
        value = fn()
        if value:
            return value
        assert loop.time() < deadline, "等待条件超时"
        await asyncio.sleep(0.05)


async def _register_file(runtime, tmp_path) -> str:
    fpath = tmp_path / "files" / "fw.bin"
    fpath.parent.mkdir(parents=True, exist_ok=True)
    fpath.write_bytes(DATA)
    return await runtime.transfer.register_file(fpath)


async def _recv_until_end(agent: FakeAgent):
    """收 FILE_FRAME 序列直到 FILE_END，返回 (frames dict seq→payload, end_payload)。"""
    frames: dict[int, bytes] = {}
    while True:
        ftype, seq, payload = await agent.recv_frame()
        if ftype == 0x01:
            frames[seq] = payload
        elif ftype == 0x02:
            return frames, payload
        else:
            raise AssertionError(f"意外帧类型 {ftype:#04x}")


async def _start_and_ready(runtime, agent, file_id) -> str:
    """start_transfer → agent 校验 FILE_TRANSFER 并回 READY，返回 taskId。"""
    task_id = await runtime.transfer.start_transfer(AGENT, file_id, MAC)
    cmd = await agent.recv_json()
    assert cmd["type"] == "FILE_TRANSFER"
    assert cmd["taskId"] == task_id
    assert cmd["fileId"] == file_id
    assert cmd["deviceMac"] == MAC
    assert cmd["size"] == len(DATA)
    assert cmd["sha256"] == base64.b64encode(SHA256).decode()  # A.2 base64
    assert cmd["windowSize"] == 64
    await agent.ack_cmd(cmd)
    await agent.send({"type": "FILE_DOWNLOAD_READY", "timestamp": 2,
                      "taskId": task_id, "fileId": file_id})
    return task_id


async def test_push_full_cycle(runtime, tmp_path):
    """§7.6 完整闭环：登记 → READY → 帧×3 + FILE_END → ACK 空 → DOWNLOADED。"""
    agent = await _connect_registered(runtime.gateway_port)
    file_id = await _register_file(runtime, tmp_path)

    rec = await runtime.store.get_file(file_id)
    assert rec["size"] == len(DATA) and rec["sha256"] == base64.b64encode(SHA256).decode()

    task_id = await _start_and_ready(runtime, agent, file_id)
    assert runtime.transfer.get_task(task_id)["state"] == "WAIT_READY"

    frames, end_payload = await _recv_until_end(agent)
    assert sorted(frames) == [1, 2, 3]  # seq 从 1 连续编号（§16.3）
    assert b"".join(frames[s] for s in sorted(frames)) == DATA
    assert end_payload == SHA256  # FILE_END payload = 整体 sha256 32 字节

    await agent.send({"type": "FILE_DOWNLOAD_ACK", "timestamp": 3,
                      "taskId": task_id, "resendSeqs": []})
    task = await _until(lambda: runtime.transfer.get_task(task_id)
                        if runtime.transfer.get_task(task_id)["state"] == "DOWNLOADED"
                        else None)
    assert task["ackedSeq"] == 3 and task["totalChunks"] == 3
    await agent.close()


async def test_download_ack_from_wait_ready_skips_push(runtime, tmp_path):
    """agent 缓存去重：不发 READY 直接回空 ACK → WAIT_READY 直转 DOWNLOADED，零下载帧。"""
    agent = await _connect_registered(runtime.gateway_port)
    file_id = await _register_file(runtime, tmp_path)

    task_id = await runtime.transfer.start_transfer(AGENT, file_id, MAC)
    cmd = await agent.recv_json()
    assert cmd["type"] == "FILE_TRANSFER" and cmd["taskId"] == task_id
    await agent.ack_cmd(cmd)
    assert runtime.transfer.get_task(task_id)["state"] == "WAIT_READY"

    # 缓存命中场景：agent 本地已有完整文件，直接 ACK（resendSeqs 空）表示无需推帧
    await agent.send({"type": "FILE_DOWNLOAD_ACK", "timestamp": 2,
                      "taskId": task_id, "resendSeqs": []})
    task = await _until(lambda: runtime.transfer.get_task(task_id)
                        if runtime.transfer.get_task(task_id)["state"] == "DOWNLOADED"
                        else None)
    assert task["ackedSeq"] == task["totalChunks"] == 3
    assert task["downloadPercent"] == 100.0

    # 服务端未推任何二进制下载帧
    with pytest.raises(asyncio.TimeoutError):
        await agent.recv_frame(timeout=0.3)
    await agent.close()


async def test_resend_on_ack(runtime, tmp_path):
    """ACK 带 resendSeqs=[2] → 重发 seq=2 帧 + FILE_END；再 ACK 空 → DOWNLOADED。"""
    agent = await _connect_registered(runtime.gateway_port)
    file_id = await _register_file(runtime, tmp_path)
    task_id = await _start_and_ready(runtime, agent, file_id)
    await _recv_until_end(agent)

    await agent.send({"type": "FILE_DOWNLOAD_ACK", "timestamp": 3,
                      "taskId": task_id, "resendSeqs": [2]})
    frames, end_payload = await _recv_until_end(agent)
    assert list(frames) == [2]
    assert frames[2] == DATA[4096:8192]
    assert end_payload == SHA256

    await agent.send({"type": "FILE_DOWNLOAD_ACK", "timestamp": 4,
                      "taskId": task_id, "resendSeqs": []})
    await _until(lambda: runtime.transfer.get_task(task_id)["state"] == "DOWNLOADED")
    await agent.close()


async def test_resume_after_disconnect(runtime, tmp_path):
    """推送中途断连 → PAUSED；重连发 RESUME lastSeq=2 → 从 seq=3 续推（§7.6 第 4 步）。"""
    agent = await _connect_registered(runtime.gateway_port)
    file_id = await _register_file(runtime, tmp_path)
    task_id = await _start_and_ready(runtime, agent, file_id)

    # 只取前 2 帧即断开（模拟 TCP 中断，第 3 帧是否已发出不影响续推口径）
    got = {}
    for _ in range(2):
        ftype, seq, payload = await agent.recv_frame()
        assert ftype == 0x01
        got[seq] = payload
    assert sorted(got) == [1, 2]
    await agent.close()

    await _until(lambda: runtime.transfer.get_task(task_id)["state"] == "PAUSED")

    # 重连 → RESUME lastSeq=2 → 字节偏移 = 2 × 4096（§16.3 口径一致）
    agent2 = await _connect_registered(runtime.gateway_port)
    await agent2.send({"type": "FILE_DOWNLOAD_RESUME", "timestamp": 5,
                       "taskId": task_id, "lastSeq": 2})
    frames, end_payload = await _recv_until_end(agent2)
    assert list(frames) == [3]
    assert frames[3] == DATA[8192:]
    assert end_payload == SHA256

    await agent2.send({"type": "FILE_DOWNLOAD_ACK", "timestamp": 6,
                       "taskId": task_id, "resendSeqs": []})
    await _until(lambda: runtime.transfer.get_task(task_id)["state"] == "DOWNLOADED")
    await agent2.close()


async def test_file_request_triggers_transfer(runtime, tmp_path):
    """FILE_REQUEST（A.3 规则触发取文件）→ 自动补发 FILE_TRANSFER，taskId 一致。"""
    agent = await _connect_registered(runtime.gateway_port)
    file_id = await _register_file(runtime, tmp_path)

    await agent.send({"type": "FILE_REQUEST", "timestamp": 2,
                      "fileId": file_id, "taskId": "task-rule-01",
                      "deviceMac": MAC})
    cmd = await agent.recv_json()
    assert cmd["type"] == "FILE_TRANSFER"
    assert cmd["taskId"] == "task-rule-01"  # 沿用事件里的 taskId
    assert cmd["fileId"] == file_id and cmd["deviceMac"] == MAC
    assert cmd["size"] == len(DATA)
    await agent.ack_cmd(cmd)
    assert runtime.transfer.get_task("task-rule-01")["state"] == "WAIT_READY"
    await agent.close()


async def test_log_upload(runtime, tmp_path):
    """§13：UPLOAD_LOG → LOG_FRAME×3 → LOG_UPLOAD_DONE → 落盘内容一致。"""
    agent = await _connect_registered(runtime.gateway_port)

    request_id = await runtime.transfer.request_log_upload(
        AGENT, since_ts=123456, min_level="INFO")
    cmd = await agent.recv_json()
    assert cmd["type"] == "UPLOAD_LOG"
    assert cmd["requestId"] == request_id
    assert cmd["sinceTs"] == 123456 and cmd["minLevel"] == "INFO"
    await agent.ack_cmd(cmd)

    chunks = [b"log-line-1\n", b"log-line-2\n", b"log-line-3\n"]
    for i, piece in enumerate(chunks, start=1):
        await agent.send_frame(0x04, i, piece)
    total = sum(len(c) for c in chunks)
    await agent.send({"type": "LOG_UPLOAD_DONE", "timestamp": 3,
                      "requestId": request_id, "errorCode": 0, "size": total})

    log_path = tmp_path / "agent_logs" / AGENT / f"{request_id}.log"
    content = await _until(lambda: log_path.read_bytes()
                           if log_path.exists()
                           and log_path.stat().st_size == total else None)
    assert content == b"".join(chunks)
    await agent.close()


async def test_cancel(runtime, tmp_path):
    """FILE_CANCEL：下发取消命令，本地任务 CANCELLED；重复取消返回 False。"""
    agent = await _connect_registered(runtime.gateway_port)
    file_id = await _register_file(runtime, tmp_path)
    task_id = await _start_and_ready(runtime, agent, file_id)

    assert await runtime.transfer.cancel(task_id) is True
    cmd = await agent.recv_json()
    assert cmd["type"] == "FILE_CANCEL" and cmd["taskId"] == task_id
    await agent.ack_cmd(cmd)
    assert runtime.transfer.get_task(task_id)["state"] == "CANCELLED"
    assert await runtime.transfer.cancel(task_id) is False  # 终态不可再取消
    await agent.close()


async def test_register_file_quota(tmp_path):
    """配额检查（§7.6 本地存储管理）：files_dir 总量超 files_quota_mb 拒绝登记。"""
    settings = _make_settings(tmp_path)
    settings.storage.files_quota_mb = 1  # 1MB 配额
    rt = Runtime(settings)
    await rt.start()
    try:
        big = tmp_path / "big.bin"  # 放在 files_dir 外，按新文件全额计入配额
        big.write_bytes(b"\x00" * (1024 * 1024 + 1))
        with pytest.raises(ValueError, match="配额"):
            await rt.transfer.register_file(big)

        ok = tmp_path / "files" / "ok.bin"
        ok.parent.mkdir(parents=True, exist_ok=True)
        ok.write_bytes(b"\x01" * 1024)
        file_id = await rt.transfer.register_file(ok)
        assert (await rt.store.get_file(file_id))["name"] == "ok.bin"
    finally:
        await rt.stop()
