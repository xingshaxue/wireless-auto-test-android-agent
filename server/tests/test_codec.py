"""codec.py 单测：crc16 / 帧编解码 / JSON 编解码 / StreamDemuxer 解复用。"""

import importlib.util
import json
import struct
import sys
from pathlib import Path

import pytest

from wireless_server.codec import (
    FRAME_TYPES,
    MAGIC,
    MAX_FRAME_PAYLOAD,
    MAX_JSON_BYTES,
    ProtocolError,
    StreamDemuxer,
    crc16_ccitt,
    decode_frame,
    decode_json,
    encode_frame,
    encode_json,
)

# 加载 mock_server.py 做逐位对照（参考实现，不修改；禁写字节码缓存以免污染 tools/）
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location(
    "mock_server", Path(__file__).resolve().parents[2] / "tools" / "mock_server.py"
)
mock_server = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mock_server)


# ---------------- crc16 ----------------

def test_crc16_known_value():
    # CRC-16/CCITT-FALSE 标准校验值
    assert crc16_ccitt(b"123456789") == 0x29B1
    assert crc16_ccitt(b"") == 0xFFFF


def test_crc16_matches_mock():
    for data in (b"", b"\x00", b"123456789", bytes(range(256)), b"\xac\x42" * 100):
        assert crc16_ccitt(data) == mock_server.crc16_ccitt(data)


# ---------------- 帧编解码 ----------------

def test_frame_roundtrip():
    payload = b"\x01\x02\x03" * 100
    raw = encode_frame(0x01, 42, payload)
    assert len(raw) == 11 + len(payload) + 2
    ftype, seq, got = decode_frame(raw)
    assert (ftype, seq, got) == (0x01, 42, payload)


def test_frame_roundtrip_all_types():
    for ftype in FRAME_TYPES:
        raw = encode_frame(ftype, 0xFFFFFFFF, b"")
        assert decode_frame(raw) == (ftype, 0xFFFFFFFF, b"")


def test_frame_matches_mock_encoding():
    for ftype, seq, payload in ((0x01, 1, b"abc"), (0x02, 0, b"\x00" * 32), (0x04, 7, b"")):
        assert encode_frame(ftype, seq, payload) == mock_server.encode_frame(ftype, seq, payload)


def test_encode_frame_rejects_oversize_payload():
    with pytest.raises(ProtocolError):
        encode_frame(0x01, 1, b"x" * (MAX_FRAME_PAYLOAD + 1))
    encode_frame(0x01, 1, b"x" * MAX_FRAME_PAYLOAD)  # 恰好 64KB 合法


def test_encode_frame_rejects_unknown_type():
    with pytest.raises(ProtocolError):
        encode_frame(0x7F, 1, b"")


def test_decode_frame_bad_magic():
    raw = b"\x00\x00" + encode_frame(0x01, 1, b"x")[2:]
    with pytest.raises(ProtocolError, match="magic"):
        decode_frame(raw)


def test_decode_frame_bad_crc():
    raw = bytearray(encode_frame(0x01, 1, b"hello"))
    raw[-1] ^= 0xFF  # 翻转 CRC 末位
    with pytest.raises(ProtocolError, match="CRC"):
        decode_frame(bytes(raw))


def test_decode_frame_truncated():
    raw = encode_frame(0x01, 1, b"hello")
    with pytest.raises(ProtocolError):
        decode_frame(raw[:-3])


# ---------------- JSON 编解码 ----------------

def test_json_roundtrip():
    msg = {"type": "GET_STATUS", "timestamp": 123, "requestId": "r-1", "中文": True}
    raw = encode_json(msg)
    length = struct.unpack(">I", raw[:4])[0]
    assert length == len(raw) - 4
    assert decode_json(raw[4:]) == msg


def test_decode_json_rejects_non_object():
    with pytest.raises(ProtocolError):
        decode_json(b"[1,2,3]")
    with pytest.raises(ProtocolError):
        decode_json(b"{not json")


# ---------------- StreamDemuxer ----------------

def _json_bytes(msg: dict) -> bytes:
    return encode_json(msg)


def test_demux_mixed_stream():
    """JSON + 帧混合流按序解出。"""
    j1 = {"type": "CMD_ACK", "requestId": "r1"}
    f1 = encode_frame(0x01, 1, b"chunk-1")
    j2 = {"type": "FILE_DOWNLOAD_READY", "taskId": "t1"}
    f2 = encode_frame(0x02, 0, b"\xaa" * 32)
    dm = StreamDemuxer()
    out = dm.feed(_json_bytes(j1) + f1 + _json_bytes(j2) + f2)
    assert out == [
        ("json", j1),
        ("frame", (0x01, 1, b"chunk-1")),
        ("json", j2),
        ("frame", (0x02, 0, b"\xaa" * 32)),
    ]


def test_demux_sticky_packets():
    """粘包：多条报文一次 feed 全部解出。"""
    msgs = [{"type": "HEARTBEAT", "n": i} for i in range(5)]
    frames = [encode_frame(0x03, i, struct.pack(">I", i)) for i in range(3)]
    blob = b"".join(_json_bytes(m) for m in msgs) + b"".join(frames)
    out = StreamDemuxer().feed(blob)
    assert [m for kind, m in out if kind == "json"] == msgs
    assert [m for kind, m in out if kind == "frame"] == [
        (0x03, i, struct.pack(">I", i)) for i in range(3)
    ]


def test_demux_split_packets_byte_by_byte():
    """半包：逐字节 feed，结果与整包一致。"""
    j1 = {"type": "REGISTER", "deviceId": "phone-1"}
    f1 = encode_frame(0x01, 9, b"payload-bytes")
    blob = _json_bytes(j1) + f1
    dm = StreamDemuxer()
    out = []
    for i in range(len(blob)):
        out.extend(dm.feed(blob[i:i + 1]))
    assert out == [("json", j1), ("frame", (0x01, 9, b"payload-bytes"))]


def test_demux_partial_feed_then_rest():
    """半包：先喂帧头一半，再喂剩余部分。"""
    raw = encode_frame(0x04, 3, b"log-data")
    dm = StreamDemuxer()
    assert dm.feed(raw[:5]) == []
    assert dm.feed(raw[5:]) == [("frame", (0x04, 3, b"log-data"))]


def test_demux_bad_magic_treated_as_json():
    """前 2 字节非 magic → 按 4 字节长度前缀 JSON 处理（§16.3）。"""
    # 0xAC43 开头的数据不是帧，被当作 JSON 长度前缀（巨大长度 → 超 1MB 拒绝）
    dm = StreamDemuxer()
    with pytest.raises(ProtocolError, match="1MB"):
        dm.feed(b"\xac\x43\x00\x01" + b"x" * 8)
    # 正常的非 magic JSON 流正常解析
    dm = StreamDemuxer()
    out = dm.feed(_json_bytes({"type": "X"}))
    assert out == [("json", {"type": "X"})]


def test_demux_rejects_oversize_json():
    dm = StreamDemuxer()
    with pytest.raises(ProtocolError, match="1MB"):
        dm.feed(struct.pack(">I", MAX_JSON_BYTES + 1))
    with pytest.raises(ProtocolError, match="1MB"):
        encode_json({"pad": "x" * MAX_JSON_BYTES})


def test_demux_rejects_oversize_frame_payload():
    """帧头声明 payload > 64KB → 立即拒绝，不等数据收齐。"""
    header = struct.pack(">HBII", MAGIC, 0x01, 1, MAX_FRAME_PAYLOAD + 1)
    dm = StreamDemuxer()
    with pytest.raises(ProtocolError, match="64KB"):
        dm.feed(header)


def test_demux_bad_crc_raises():
    raw = bytearray(encode_frame(0x01, 1, b"data"))
    raw[11] ^= 0x01  # 翻转 payload 一字节，CRC 必失败
    dm = StreamDemuxer()
    with pytest.raises(ProtocolError, match="CRC"):
        dm.feed(bytes(raw))


def test_demux_bad_json_raises():
    dm = StreamDemuxer()
    body = b"{broken"
    with pytest.raises(ProtocolError, match="JSON"):
        dm.feed(struct.pack(">I", len(body)) + body)


def test_demux_max_json_boundary():
    """恰好 1MB 的 JSON 合法。"""
    msg = {"pad": "x" * (MAX_JSON_BYTES - 20)}
    body = json.dumps(msg, ensure_ascii=False).encode("utf-8")
    msg["pad"] = "x" * (MAX_JSON_BYTES - len(body) + len(msg["pad"]))
    raw = encode_json(msg)
    out = StreamDemuxer().feed(raw)
    assert out == [("json", msg)]
