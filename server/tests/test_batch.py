"""批量 OTA 编排器测试（engine/batch.py，复用 §7.6 传输链路 + docs/04 p67 流程）。

起完整 Runtime（:memory: DB、随机端口、ack_timeout_ms=300）+ FakeAgent
（asyncio TCP + codec）+ 后台 AgentDriver 按 p67 OTA 剧本应答：
FILE_TRANSFER→ACK+READY→收帧→FILE_DOWNLOAD_ACK 空+FILE_RESULT；
WRITE_CHAR→ACK（SWVER 命令额外回 POLL_RESULT）；CONNECT_DEVICE→ACK+READY。
覆盖：请求校验 / 单设备 happy path 全相位 / 失败隔离 / per-agent 并发上限 /
版本内容校验失败 / 批次取消 / 列表与详情结构。
"""

import asyncio
import base64
import collections

import httpx
import pytest

from wireless_server.codec import StreamDemuxer, encode_frame, encode_json
from wireless_server.runtime import Runtime
from wireless_server.settings import Settings

AGENT = "agent-batch-01"
MAC1 = "AA:BB:CC:DD:EE:01"
MAC2 = "AA:BB:CC:DD:EE:02"

DATA = b"ota-payload-" * 400  # 4.8KB，chunk 4096 下 2 帧
OTA_PAYLOAD_B64 = base64.b64encode(b"00AT^OTA_UPDATE").decode()
SWVER_PAYLOAD_B64 = base64.b64encode(b"00AT^SWVER=APP").decode()


class FakeAgent:
    """模拟 Android agent：asyncio TCP + codec 编解码（同 test_transfer）。"""

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


class AgentDriver:
    """p67 OTA 剧本驱动：按 server 下发的报文自动应答，记录事件日志供断言。

    - fail_transfer_macs：这些设备传输终态回 FILE_RESULT errorCode=4001
    - no_ready_macs：这些设备收 FILE_TRANSFER 只回 ACK 不回 READY（传输挂起）
    - version：SWVER 查询应答 POLL_RESULT 里的版本串；None 则不应答
    """

    def __init__(self, agent: FakeAgent, *,
                 fail_transfer_macs: tuple = (), no_ready_macs: tuple = (),
                 version: str | None = "3.101.042monkey") -> None:
        self.agent = agent
        self.fail_transfer_macs = fail_transfer_macs
        self.no_ready_macs = no_ready_macs
        self.version = version
        self.log: list[tuple] = []  # (事件名, deviceMac)
        self._tasks: dict[str, str] = {}  # taskId → deviceMac
        self._stopped = False

    async def run(self) -> None:
        while not self._stopped:
            try:
                kind, item = await self.agent.recv(timeout=0.2)
            except asyncio.TimeoutError:
                continue
            except (EOFError, ConnectionError):
                return
            try:
                if kind == "json":
                    await self._on_json(item)
                else:
                    await self._on_frame(item)
            except (EOFError, ConnectionError):
                return

    def stop(self) -> None:
        self._stopped = True

    async def _on_json(self, cmd: dict) -> None:
        ctype = cmd.get("type")
        if ctype == "FILE_TRANSFER":
            mac = cmd["deviceMac"]
            self._tasks[cmd["taskId"]] = mac
            self.log.append(("FILE_TRANSFER", mac))
            await self.agent.ack_cmd(cmd)
            if mac not in self.no_ready_macs:
                await self.agent.send({
                    "type": "FILE_DOWNLOAD_READY", "timestamp": 2,
                    "taskId": cmd["taskId"], "fileId": cmd["fileId"]})
        elif ctype == "WRITE_CHAR":
            mac = cmd["deviceMac"]
            self.log.append(("WRITE_CHAR", mac))
            await self.agent.ack_cmd(cmd)
            payload = base64.b64decode(cmd.get("payload", ""))
            if b"SWVER" in payload and self.version is not None:
                await self.agent.send({
                    "type": "POLL_RESULT", "timestamp": 3, "deviceMac": mac,
                    "stale": False,
                    "values": {"swver": f"SWVER=OK,{self.version}\r\n"}})
        elif ctype == "CONNECT_DEVICE":
            mac = cmd["deviceMac"]
            self.log.append(("CONNECT_DEVICE", mac))
            await self.agent.ack_cmd(cmd)
            await self.agent.send({"type": "DEVICE_STATE", "timestamp": 4,
                                   "deviceMac": mac, "state": "READY"})
        elif ctype == "FILE_CANCEL":
            self.log.append(("FILE_CANCEL", self._tasks.get(cmd["taskId"])))
            await self.agent.ack_cmd(cmd)

    async def _on_frame(self, frame) -> None:
        ftype, seq, payload = frame
        if ftype == 0x02:  # FILE_END → 下载完成 + 传输终态
            # 当前只有一个在途任务（perAgentConcurrency=1 剧本）
            task_id = next(reversed(self._tasks))
            mac = self._tasks[task_id]
            await self.agent.send({"type": "FILE_DOWNLOAD_ACK", "timestamp": 3,
                                   "taskId": task_id, "resendSeqs": []})
            failed = mac in self.fail_transfer_macs
            self.log.append(("FILE_RESULT", mac))
            await self.agent.send({
                "type": "FILE_RESULT", "timestamp": 4, "taskId": task_id,
                "errorCode": 4001 if failed else 0,
                "detail": "BLE 写入失败" if failed else None})


def _make_settings(tmp_path) -> Settings:
    settings = Settings()
    settings.gateway.host = "127.0.0.1"
    settings.gateway.port = 0
    settings.gateway.ack_timeout_ms = 300
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
    rt.batch._task_poll_s = 0.05  # 测试加速：传输轮询 1s → 50ms
    rt.batch._verify_timeout_ms = 2000
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
    await a.send({
        "type": "REGISTER", "timestamp": 1, "deviceId": AGENT,
        "ip": "10.0.0.1", "port": 0, "androidSdk": 34, "bleSupported": True,
        "maxConnections": 5, "agentVersion": "1.0",
    })
    ack = await a.recv_json()
    assert ack["type"] == "REGISTER_ACK" and ack["errorCode"] == 0
    yield a
    await a.close()


@pytest.fixture
async def driver(agent):
    d = AgentDriver(agent)
    task = asyncio.create_task(d.run())
    yield d
    d.stop()
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)


async def _until(fn, timeout: float = 8.0):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while True:
        value = fn() if not asyncio.iscoroutinefunction(fn) else await fn()
        if value:
            return value
        assert loop.time() < deadline, "等待条件超时"
        await asyncio.sleep(0.05)


async def _register_file(runtime, tmp_path) -> str:
    fpath = tmp_path / "files" / "ota.zip"
    fpath.parent.mkdir(parents=True, exist_ok=True)
    fpath.write_bytes(DATA)
    return await runtime.transfer.register_file(fpath)


def _body(file_id: str, *macs: str, **extra) -> dict:
    return {"fileId": file_id,
            "targets": [{"agentId": AGENT, "deviceMac": m} for m in macs],
            "blackoutMs": 50, "reconnectTimeoutMs": 5000, **extra}


async def _get_batch(api, batch_id: str) -> dict:
    resp = await api.get(f"/api/batch/{batch_id}")
    assert resp.status_code == 200
    return resp.json()


async def _wait_batch_done(api, batch_id: str) -> dict:
    async def _done():
        b = await _get_batch(api, batch_id)
        return b if b["state"] in ("DONE", "CANCELLED") else None
    return await _until(_done)


# ---------------- 请求校验 ----------------

async def test_validation(runtime, api, tmp_path):
    """缺字段 422 / fileId 不存在 404 / targets 空 422 / 数值非法 422。"""
    file_id = await _register_file(runtime, tmp_path)

    resp = await api.post("/api/batch/ota", json={"targets": [{"agentId": AGENT, "deviceMac": MAC1}]})
    assert resp.status_code == 422  # 缺 fileId

    resp = await api.post("/api/batch/ota",
                          json={"fileId": file_id, "targets": []})
    assert resp.status_code == 422  # targets 空

    resp = await api.post("/api/batch/ota",
                          json={"fileId": file_id, "targets": [{"agentId": AGENT}]})
    assert resp.status_code == 422  # targets 项缺 deviceMac

    resp = await api.post("/api/batch/ota",
                          json=_body("file-ghost", MAC1))
    assert resp.status_code == 404  # fileId 不存在

    resp = await api.post("/api/batch/ota",
                          json=_body(file_id, MAC1, perAgentConcurrency=0))
    assert resp.status_code == 422  # 并发数越界


# ---------------- 单设备 happy path ----------------

async def test_happy_path_full_phases(runtime, api, tmp_path, driver):
    """单设备全相位走通到 DONE：默认 p67 参数 + 版本内容校验。"""
    file_id = await _register_file(runtime, tmp_path)
    resp = await api.post("/api/batch/ota", json=_body(
        file_id, MAC1, versionCheck={"expectContains": "3.101"}))
    assert resp.status_code == 200
    batch_id = resp.json()["batchId"]
    assert batch_id.startswith("batch-")

    batch = await _wait_batch_done(api, batch_id)
    assert batch["state"] == "DONE"
    assert batch["summary"] == {"total": 1, "succeeded": 1,
                                "failed": 0, "remaining": 0}
    assert batch["finishedTs"] is not None
    dev = batch["devices"][0]
    assert dev["agentId"] == AGENT and dev["deviceMac"] == MAC1
    assert dev["phase"] == "DONE"
    assert dev["taskId"] and dev["taskId"].startswith("task-")
    assert dev["error"] is None and dev["detail"] is None
    assert "3.101.042monkey" in dev["version"]
    # 剧本时序：传输 → 升级写 → 回连 → 查版本写
    kinds = [e[0] for e in driver.log]
    assert kinds == ["FILE_TRANSFER", "FILE_RESULT", "WRITE_CHAR",
                     "CONNECT_DEVICE", "WRITE_CHAR"]


# ---------------- 失败隔离 ----------------

async def test_failure_isolation(runtime, api, tmp_path):
    """第一台传输失败不影响第二台：批次 DONE，summary 1 成功 1 失败。"""
    file_id = await _register_file(runtime, tmp_path)
    a = await FakeAgent.connect(runtime.gateway_port)
    await a.send({"type": "REGISTER", "timestamp": 1, "deviceId": AGENT,
                  "ip": "10.0.0.1", "port": 0, "androidSdk": 34,
                  "bleSupported": True, "maxConnections": 5,
                  "agentVersion": "1.0"})
    await a.recv_json()
    d = AgentDriver(a, fail_transfer_macs=(MAC1,))
    task = asyncio.create_task(d.run())
    try:
        resp = await api.post("/api/batch/ota", json=_body(file_id, MAC1, MAC2))
        batch_id = resp.json()["batchId"]
        batch = await _wait_batch_done(api, batch_id)
        assert batch["state"] == "DONE"
        assert batch["summary"] == {"total": 2, "succeeded": 1,
                                    "failed": 1, "remaining": 0}
        dev1, dev2 = batch["devices"]
        assert dev1["deviceMac"] == MAC1 and dev1["phase"] == "FAILED"
        assert dev1["error"] == 4001 and "BLE 写入失败" in dev1["detail"]
        assert dev2["deviceMac"] == MAC2 and dev2["phase"] == "DONE"
        assert dev2["version"] is None  # 默认只写不校验
    finally:
        d.stop()
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        await a.close()


# ---------------- per-agent 并发上限 ----------------

async def test_per_agent_concurrency_serial(runtime, api, tmp_path, driver):
    """perAgentConcurrency=1：同 agent 第二台设备须等第一台传输结束才下发。"""
    file_id = await _register_file(runtime, tmp_path)
    resp = await api.post("/api/batch/ota", json=_body(
        file_id, MAC1, MAC2, perAgentConcurrency=1))
    batch_id = resp.json()["batchId"]
    batch = await _wait_batch_done(api, batch_id)
    assert batch["summary"]["succeeded"] == 2

    transfers = [(i, e[1]) for i, e in enumerate(driver.log)
                 if e[0] == "FILE_TRANSFER"]
    results = [(i, e[1]) for i, e in enumerate(driver.log)
               if e[0] == "FILE_RESULT"]
    assert [m for _, m in transfers] == [MAC1, MAC2]
    # 第二台的 FILE_TRANSFER 出现在第一台 FILE_RESULT 之后（不并发传输）
    first_result_idx = next(i for i, m in results if m == MAC1)
    second_transfer_idx = next(i for i, m in transfers if m == MAC2)
    assert second_transfer_idx > first_result_idx


# ---------------- 版本内容校验失败 ----------------

async def test_verify_content_mismatch(runtime, api, tmp_path, agent):
    """POLL_RESULT 无 expectContains 子串 → VERIFY_TIMEOUT，detail 记实际值。"""
    d = AgentDriver(agent, version="9.999.000")
    task = asyncio.create_task(d.run())
    try:
        file_id = await _register_file(runtime, tmp_path)
        resp = await api.post("/api/batch/ota", json=_body(
            file_id, MAC1, versionCheck={"expectContains": "3.101"}))
        batch = await _wait_batch_done(api, resp.json()["batchId"])
        assert batch["state"] == "DONE"  # 单台失败批次仍收尾 DONE
        dev = batch["devices"][0]
        assert dev["phase"] == "FAILED"
        assert dev["error"] == "VERIFY_TIMEOUT"
        assert "9.999.000" in dev["detail"]  # 记实际最后收到的值
        assert dev["version"] is None
    finally:
        d.stop()
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


# ---------------- 批次取消 ----------------

async def test_cancel_running_batch(runtime, api, tmp_path, agent):
    """RUNNING 中取消：在途传输 FILE_CANCEL、排队设备 CANCELLED、批次终态。"""
    d = AgentDriver(agent, no_ready_macs=(MAC1,))  # MAC1 传输挂起在 WAIT_READY
    task = asyncio.create_task(d.run())
    try:
        file_id = await _register_file(runtime, tmp_path)
        resp = await api.post("/api/batch/ota", json=_body(file_id, MAC1, MAC2))
        batch_id = resp.json()["batchId"]
        # 等第一台进入 TRANSFERRING（挂在传输等待）
        await _until(lambda: ("FILE_TRANSFER", MAC1) in d.log or None)

        resp = await api.post(f"/api/batch/{batch_id}/cancel")
        assert resp.status_code == 200
        batch = await _wait_batch_done(api, batch_id)
        assert batch["state"] == "CANCELLED"
        assert batch["finishedTs"] is not None
        dev1, dev2 = batch["devices"]
        assert dev1["phase"] == "CANCELLED"
        assert dev2["phase"] == "CANCELLED"  # 排队设备同取消
        assert batch["summary"]["failed"] == 2
        assert ("FILE_CANCEL", MAC1) in d.log  # 在途传输已下发取消

        resp = await api.post(f"/api/batch/{batch_id}/cancel")
        assert resp.status_code == 409  # 终态不可再取消
    finally:
        d.stop()
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


# ---------------- 列表与详情结构 ----------------

async def test_list_and_get_structure(runtime, api, tmp_path, driver):
    """GET /api/batch 与 /api/batch/{id} 响应结构；未知批次 404。"""
    file_id = await _register_file(runtime, tmp_path)
    resp = await api.post("/api/batch/ota", json=_body(file_id, MAC1))
    batch_id = resp.json()["batchId"]
    await _wait_batch_done(api, batch_id)

    body = (await api.get("/api/batch")).json()
    assert set(body) == {"running", "done"}
    assert all(b["batchId"] != batch_id for b in body["running"])
    done = [b for b in body["done"] if b["batchId"] == batch_id]
    assert len(done) == 1
    brief = done[0]
    assert set(brief) == {"batchId", "state", "fileId", "createdTs",
                          "finishedTs", "summary"}
    assert brief["fileId"] == file_id
    assert set(brief["summary"]) == {"total", "succeeded", "failed", "remaining"}

    detail = await _get_batch(api, batch_id)
    assert set(detail) == {"batchId", "state", "fileId", "createdTs",
                           "finishedTs", "summary", "devices"}
    assert set(detail["devices"][0]) == {"agentId", "deviceMac", "phase",
                                         "taskId", "error", "detail", "version"}

    resp = await api.get("/api/batch/batch-ghost")
    assert resp.status_code == 404
