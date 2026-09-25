"""协议编解码层：TCP 二进制帧 + 长度前缀 JSON 的编解码与流解复用（SDD §16.3）。"""

from __future__ import annotations

import json
import struct

MAGIC = 0xAC42
FRAME_TYPES = {0x01: "FILE_FRAME", 0x02: "FILE_END", 0x03: "FILE_ACK", 0x04: "LOG_FRAME",
               0x05: "EXPORT_FRAME", 0x06: "EXPORT_END"}

# §16.3：单帧 payload ≤ 64KB；单条 JSON ≤ 1MB
MAX_FRAME_PAYLOAD = 64 * 1024
MAX_JSON_BYTES = 1024 * 1024

_HEADER = struct.Struct(">HBII")  # magic(2) type(1) seq(4) length(4)，共 11 字节
_U32 = struct.Struct(">I")
_U16 = struct.Struct(">H")


class ProtocolError(Exception):
    """协议层错误：帧格式非法 / CRC 失败 / JSON 超长或非法等。坏数据一律抛错，不静默吞掉。"""


def crc16_ccitt(data: bytes) -> int:
    """CRC-16/CCITT（初值 0xFFFF，多项式 0x1021），与 mock_server.py 逐位一致。"""
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if (crc & 0x8000) else (crc << 1) & 0xFFFF
    return crc


def encode_frame(ftype: int, seq: int, payload: bytes) -> bytes:
    """编码二进制帧：11 字节头 + payload + 2 字节 crc16（§16.3）。

    crc16 覆盖 type + seq + length + payload（即帧头去掉 magic 后加 payload）。
    """
    if ftype not in FRAME_TYPES:
        raise ProtocolError(f"未知帧类型: {ftype:#04x}")
    if not 0 <= seq <= 0xFFFFFFFF:
        raise ProtocolError(f"seq 越界: {seq}")
    if len(payload) > MAX_FRAME_PAYLOAD:
        raise ProtocolError(f"帧 payload 超过 64KB: {len(payload)}")
    header = _HEADER.pack(MAGIC, ftype, seq, len(payload))
    crc = crc16_ccitt(header[2:] + payload)
    return header + payload + _U16.pack(crc)


def decode_frame(buf: bytes) -> tuple[int, int, bytes]:
    """解码完整帧字节，返回 (ftype, seq, payload)。magic/CRC 校验失败抛 ProtocolError。"""
    if len(buf) < _HEADER.size + 2:
        raise ProtocolError(f"帧长度过短: {len(buf)}")
    magic, ftype, seq, length = _HEADER.unpack(buf[:_HEADER.size])
    if magic != MAGIC:
        raise ProtocolError(f"帧 magic 不符: {magic:#06x}")
    if length > MAX_FRAME_PAYLOAD:
        raise ProtocolError(f"帧 payload 超过 64KB: {length}")
    total = _HEADER.size + length + 2
    if len(buf) < total:
        raise ProtocolError(f"帧不完整: 期望 {total} 字节，实收 {len(buf)}")
    payload = buf[_HEADER.size:_HEADER.size + length]
    crc_expect = _U16.unpack(buf[_HEADER.size + length:total])[0]
    if crc16_ccitt(buf[2:_HEADER.size] + payload) != crc_expect:
        raise ProtocolError("帧 CRC 校验失败")
    return ftype, seq, payload


def encode_json(msg: dict) -> bytes:
    """编码 JSON 报文：4 字节大端长度前缀（不含自身）+ UTF-8 文本（§16.3 / 附录 A.1）。"""
    body = json.dumps(msg, ensure_ascii=False).encode("utf-8")
    if len(body) > MAX_JSON_BYTES:
        raise ProtocolError(f"JSON 报文超过 1MB: {len(body)}")
    return _U32.pack(len(body)) + body


def decode_json(body: bytes) -> dict:
    """解码 JSON 报文体（不含长度前缀）。非法 JSON 或非对象一律抛 ProtocolError。"""
    if len(body) > MAX_JSON_BYTES:
        raise ProtocolError(f"JSON 报文超过 1MB: {len(body)}")
    try:
        msg = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise ProtocolError(f"JSON 解析失败: {e}") from e
    if not isinstance(msg, dict):
        raise ProtocolError(f"JSON 报文必须是对象: {type(msg).__name__}")
    return msg


class StreamDemuxer:
    """供 asyncio 读循环使用的增量流解复用器（§16.3 peek 前 2 字节规则）。

    前 2 字节 == 0xAC42 → 按二进制帧解析；否则按 4 字节长度前缀读 JSON。
    坏数据（超长 / 坏 CRC / 非法 JSON）一律抛 ProtocolError，不静默吞掉。
    """

    def __init__(self) -> None:
        self._buf = bytearray()

    def feed(self, data: bytes) -> list[tuple[str, object]]:
        """喂入 TCP 字节流，返回 [('json', dict) | ('frame', (ftype, seq, payload))]。"""
        self._buf += data
        out: list[tuple[str, object]] = []
        buf = self._buf
        while True:
            if len(buf) < 2:
                break
            if buf[0] == 0xAC and buf[1] == 0x42:
                # 二进制帧路径
                if len(buf) < _HEADER.size:
                    break
                length = _U32.unpack(buf[7:11])[0]
                if length > MAX_FRAME_PAYLOAD:
                    raise ProtocolError(f"帧 payload 超过 64KB: {length}")
                total = _HEADER.size + length + 2
                if len(buf) < total:
                    break
                ftype, seq, payload = decode_frame(bytes(buf[:total]))
                del buf[:total]
                out.append(("frame", (ftype, seq, payload)))
            else:
                # 长度前缀 JSON 路径（magic 不符一律走这里，§16.3 无歧义）
                if len(buf) < 4:
                    break
                length = _U32.unpack(buf[:4])[0]
                if length > MAX_JSON_BYTES:
                    raise ProtocolError(f"JSON 报文超过 1MB: {length}")
                if len(buf) < 4 + length:
                    break
                msg = decode_json(bytes(buf[4:4 + length]))
                del buf[:4 + length]
                out.append(("json", msg))
        return out
