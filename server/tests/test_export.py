"""设备文件导出测试（docs/02 B.6，DUT→手机→server）。

起完整 Runtime（:memory: DB、随机端口）+ 模拟 agent 客户端（asyncio + codec）：
FILE_EXPORT 下发 / EXPORT_FRAME+EXPORT_END 组帧落盘入库（origin="device"）/
SHA-256 不符不登记 / POST /api/exports 端点 / GET /api/files/{id}/download 下载与防穿越。
"""

import asyncio
import base64
import collections
import hashlib
import struct

import httpx
import pytest

from wireless_server.codec import StreamDemuxer, encode_frame, encode_json
from wireless_server.runtime import Runtime
from wireless_server.settings import Settings

MAC = "AA:BB:CC:DD:EE:FF"
AGENT = "agent-ex-01"

CONTENT1 = b"export-payload-" * 40  # 600B，两帧回传
CONTENT2 = bytes(range(64))


def _payload(name: str, data: bytes) -> bytes:
    """EXPORT_FRAME/EXPORT_END payload：u16BE 文件名长度 + 文件名 UTF-8 + 数据。"""
    nb = name.encode("utf-8")
    return struct.pack(">H", len(nb)) + nb + data


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

    async def ack_cmd(self, cmd: dict) -> None:
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


@pytest.fixture
async def api(runtime):
    async with httpx.AsyncClient(
        base_url=f"http://127.0.0.1:{runtime.api_port}",
        trust_env=False,
    ) as client:
        yield client


@pytest.fixture
async def agent(runtime):
    a = await FakeAgent.connect(runtime.gateway_port)
    await a.send(_register_msg())
    ack = await a.recv_json()
    assert ack["type"] == "REGISTER_ACK" and ack["errorCode"] == 0
    yield a
    await a.close()


async def _until(fn, timeout: float = 5.0):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while True:
        value = fn() if not asyncio.iscoroutinefunction(fn) else await fn()
        if value:
            return value
        assert loop.time() < deadline, "等待条件超时"
        await asyncio.sleep(0.05)


async def _request_and_ack(runtime, agent) -> str:
    """request_export → agent 校验 FILE_EXPORT 并回 ACK，返回 exportId。"""
    export_id = await runtime.transfer.request_export(AGENT, MAC, "/logs/")
    cmd = await agent.recv_json()
    assert cmd["type"] == "FILE_EXPORT"
    assert cmd["exportId"] == export_id
    assert cmd["deviceMac"] == MAC and cmd["remotePath"] == "/logs/"
    await agent.ack_cmd(cmd)
    return export_id


async def test_export_frames_full_cycle(runtime, agent):
    """EXPORT_FRAME×2 + EXPORT_END 逐文件组帧 → 落盘 files_dir/exports/<id>/ 并登记。"""
    export_id = await _request_and_ack(runtime, agent)

    sha1 = hashlib.sha256(CONTENT1).digest()
    await agent.send_frame(0x05, 1, _payload("a.log", CONTENT1[:400]))
    await agent.send_frame(0x05, 2, _payload("a.log", CONTENT1[400:]))
    await agent.send_frame(0x06, 3, _payload("a.log", sha1))
    sha2 = hashlib.sha256(CONTENT2).digest()
    await agent.send_frame(0x05, 1, _payload("b.log", CONTENT2))  # 每文件 seq 独立从 1 起
    await agent.send_frame(0x06, 2, _payload("b.log", sha2))
    await agent.send({"type": "EXPORT_RESULT", "timestamp": 3, "exportId": export_id,
                      "deviceMac": MAC, "errorCode": 0,
                      "files": [{"name": "a.log", "size": len(CONTENT1)},
                                {"name": "b.log", "size": len(CONTENT2)}]})

    async def _registered():
        files = await runtime.store.list_files()
        return files if len(files) == 2 else None

    files = await _until(_registered)
    by_name = {f["name"]: f for f in files}
    for name, content in (("a.log", CONTENT1), ("b.log", CONTENT2)):
        rec = by_name[name]
        assert rec["origin"] == "device"
        assert rec["size"] == len(content)
        assert rec["sha256"] == base64.b64encode(
            hashlib.sha256(content).digest()).decode()
        assert rec["meta"]["exportId"] == export_id
        assert rec["meta"]["deviceMac"] == MAC
        assert rec["meta"]["agentId"] == AGENT
        with open(rec["path"], "rb") as fh:
            assert fh.read() == content
        assert f"exports/{export_id}" in rec["path"].replace("\\", "/")
    # 会话已归档
    assert runtime.transfer.exportrecv.get_export(AGENT) is None


async def test_export_sha_mismatch_not_registered(runtime, agent):
    """EXPORT_END 携带 SHA-256 与实收不符 → 不登记 files 表，错误记入会话。"""
    export_id = await _request_and_ack(runtime, agent)

    await agent.send_frame(0x05, 1, _payload("bad.log", CONTENT1))
    await agent.send_frame(0x06, 2, _payload("bad.log", b"\x00" * 32))
    await agent.send({"type": "EXPORT_RESULT", "timestamp": 3, "exportId": export_id,
                      "deviceMac": MAC, "errorCode": 0,
                      "files": [{"name": "bad.log", "size": len(CONTENT1)}]})

    await _until(lambda: runtime.transfer.exportrecv._done or None)
    assert await runtime.store.list_files() == []
    done = runtime.transfer.exportrecv._done[-1]
    assert done["state"] == "DONE"  # 导出本身成功，单文件校验失败
    assert done["files"] == []
    assert done["errors"] == [{"name": "bad.log", "reason": "sha256 mismatch"}]


async def test_export_error_result(runtime, agent):
    """agent 拉取失败（061 无响应等）→ EXPORT_RESULT errorCode≠0，会话归档 ERROR。"""
    export_id = await _request_and_ack(runtime, agent)

    await agent.send({"type": "EXPORT_RESULT", "timestamp": 3, "exportId": export_id,
                      "deviceMac": MAC, "errorCode": 4003,
                      "detail": "061 无响应（重发 3 次仍失败）", "files": []})

    await _until(lambda: runtime.transfer.exportrecv._done or None)
    done = runtime.transfer.exportrecv._done[-1]
    assert done["state"] == "ERROR"
    assert done["errorCode"] == 4003
    assert await runtime.store.list_files() == []


async def test_export_rejects_while_running(runtime, agent):
    """同 agent 有进行中导出时新请求 409（真机实证：顶替会让结果错记/帧丢弃）；
    僵尸会话（超 TTL）允许取代。"""
    from wireless_server.transfer.exportrecv import EXPORT_SESSION_TTL_S
    first = await _request_and_ack(runtime, agent)
    with pytest.raises(ValueError, match="进行中的导出"):
        await _request_and_ack(runtime, agent)
    # 僵尸化后允许取代（get_export 返回公开视图，直接改内部会话时间戳）
    runtime.transfer.exportrecv._exports[AGENT]["_t0"] -= EXPORT_SESSION_TTL_S + 1
    second = await _request_and_ack(runtime, agent)
    assert first != second
    exp = runtime.transfer.exportrecv.get_export(AGENT)
    assert exp["exportId"] == second


# ---------------- API 端点 ----------------

async def test_export_progress_event(runtime, agent):
    """EXPORT_PROGRESS：进行中会话覆盖式更新进度快照；exportId 不符忽略。"""
    export_id = await _request_and_ack(runtime, agent)

    await agent.send({"type": "EXPORT_PROGRESS", "timestamp": 2,
                      "exportId": export_id, "deviceMac": MAC, "channel": "spp",
                      "file": "a.log", "fileReceived": 2048, "fileSize": 4096,
                      "filesDone": 1, "filesTotal": 3})

    def _progress():
        exp = runtime.transfer.exportrecv.get_export(AGENT)
        return exp.get("progress") if exp else None

    progress = await _until(_progress)
    assert progress == {"file": "a.log", "fileReceived": 2048, "fileSize": 4096,
                        "filesDone": 1, "filesTotal": 3, "channel": "spp"}
    # 后到覆盖先到
    await agent.send({"type": "EXPORT_PROGRESS", "timestamp": 3,
                      "exportId": export_id, "deviceMac": MAC, "channel": "spp",
                      "file": "a.log", "fileReceived": 4096, "fileSize": 4096,
                      "filesDone": 1, "filesTotal": 3})
    await _until(lambda: (runtime.transfer.exportrecv.get_export(AGENT) or {})
                 .get("progress", {}).get("fileReceived") == 4096 or None)
    # exportId 不符的进度不影响当前会话
    await agent.send({"type": "EXPORT_PROGRESS", "timestamp": 4,
                      "exportId": "export-deadbeef", "file": "x.log",
                      "fileReceived": 1, "fileSize": 1,
                      "filesDone": 0, "filesTotal": 0})
    await asyncio.sleep(0.2)
    assert runtime.transfer.exportrecv.get_export(AGENT)["progress"]["file"] == "a.log"


async def test_api_list_exports(runtime, api, agent):
    """GET /api/exports：进行中（含进度）+ 归档记录。"""
    resp = await api.post("/api/exports", json={
        "agentId": AGENT, "deviceMac": MAC, "remotePath": "/logs/"})
    export_id = resp.json()["exportId"]
    cmd = await agent.recv_json()
    await agent.ack_cmd(cmd)

    r = await api.get("/api/exports")
    assert r.status_code == 200
    body = r.json()
    running = [e for e in body["running"] if e["exportId"] == export_id]
    assert len(running) == 1 and running[0]["state"] == "RUNNING"
    assert all(not k.startswith("_") for e in body["running"] for k in e)

    await agent.send({"type": "EXPORT_RESULT", "timestamp": 3, "exportId": export_id,
                      "deviceMac": MAC, "errorCode": 0, "files": []})
    await _until(lambda: runtime.transfer.exportrecv._done or None)
    body = (await api.get("/api/exports")).json()
    assert all(e["exportId"] != export_id for e in body["running"])
    done = [e for e in body["done"] if e["exportId"] == export_id]
    assert len(done) == 1 and done[0]["state"] == "DONE"


async def test_api_start_export(runtime, api, agent):
    """POST /api/exports → 下发 FILE_EXPORT，exportId 服务端生成返回。"""
    resp = await api.post("/api/exports", json={
        "agentId": AGENT, "deviceMac": MAC, "remotePath": "/logs/"})
    assert resp.status_code == 200
    export_id = resp.json()["exportId"]
    assert export_id.startswith("export-")
    cmd = await agent.recv_json()
    assert cmd["type"] == "FILE_EXPORT" and cmd["exportId"] == export_id
    await agent.ack_cmd(cmd)


async def test_api_start_export_validation(runtime, api):
    """缺字段 → 422；agent 不在线 → 409。"""
    resp = await api.post("/api/exports", json={"agentId": AGENT})
    assert resp.status_code == 422
    resp = await api.post("/api/exports", json={
        "agentId": "ghost", "deviceMac": MAC, "remotePath": "/logs/"})
    assert resp.status_code == 409


async def test_api_download_file(runtime, api, tmp_path):
    """GET /api/files/{id}/download：登记文件可下载，内容一致；列表带 origin。"""
    fpath = tmp_path / "files" / "fw.bin"
    fpath.parent.mkdir(parents=True, exist_ok=True)
    fpath.write_bytes(CONTENT1)
    file_id = await runtime.transfer.register_file(fpath)

    resp = await api.get(f"/api/files/{file_id}/download")
    assert resp.status_code == 200
    assert resp.content == CONTENT1

    files = (await api.get("/api/files")).json()
    assert files[0]["fileId"] == file_id
    assert files[0]["origin"] == "upload"

    resp = await api.get("/api/files/no-such-file/download")
    assert resp.status_code == 404


async def test_api_download_rejects_outside_files_dir(runtime, api, tmp_path):
    """防穿越/防越界：登记路径在 files_dir 外的记录拒绝下载（404）。"""
    outside = tmp_path / "outside.bin"
    outside.write_bytes(b"x")
    await runtime.store.register_file(
        "file-outside", "outside.bin", 1,
        base64.b64encode(b"\x00" * 32).decode(), str(outside))

    resp = await api.get("/api/files/file-outside/download")
    assert resp.status_code == 404
