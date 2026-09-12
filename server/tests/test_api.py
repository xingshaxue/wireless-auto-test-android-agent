"""API 层生产接口测试：新路由 happy path + 错误码 + WebSocket 实时事件流。

真实端口模式（同 test_integration）：Runtime 全装配 + FakeAgent(TCP 模拟
Android agent) + httpx 调 REST + websockets 客户端连 /ws/events。
"""

import asyncio
import collections
import json

import httpx
import pytest
import websockets

from wireless_server.codec import StreamDemuxer, encode_json
from wireless_server.runtime import Runtime
from wireless_server.settings import Settings

MAC = "AA:BB:CC:DD:EE:FF"
AGENT = "agent-api-01"


class FakeAgent:
    """模拟 Android agent：asyncio TCP + codec 编解码（同 test_integration）。"""

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
        "maxConnections": 5, "agentVersion": "1.0", "token": "gw-token",
    }


def _device() -> dict:
    return {
        "deviceId": "dut-001", "mac": MAC, "type": "watch", "priority": 5,
        "persistent": False,
        "profile": {"2A19": "180F"},
        "fields": {"battery": {"char": "2A19", "format": "uint8"}},
        "polling": {"intervalMs": 60000, "readCharacteristics": ["2A19"],
                    "notifyCharacteristics": [], "reportOnlyChanged": True},
        "rules": [],
    }


@pytest.fixture
async def runtime(tmp_path):
    settings = Settings()
    settings.gateway.host = "127.0.0.1"
    settings.gateway.port = 0
    settings.gateway.agent_token = "gw-token"
    settings.gateway.ack_timeout_ms = 300  # 504 用例快超时
    settings.gateway.ack_max_retries = 0
    settings.api.host = "127.0.0.1"
    settings.api.port = 0
    settings.api.token = "api-token"
    settings.storage.db_path = ":memory:"
    settings.storage.files_dir = str(tmp_path / "files")
    settings.storage.logs_dir = str(tmp_path / "logs")
    rt = Runtime(settings)
    await rt.start()
    yield rt
    await rt.stop()


@pytest.fixture
async def api(runtime):
    async with httpx.AsyncClient(
        base_url=f"http://127.0.0.1:{runtime.api_port}",
        headers={"Authorization": "Bearer api-token"},
        trust_env=False,
    ) as client:
        yield client


@pytest.fixture
async def agent(runtime, api):
    """注册上线并上报一次 DEVICE_STATE（建立设备归属视图）的 agent。"""
    await api.put("/api/devices", json=_device())
    a = await FakeAgent.connect(runtime.gateway_port)
    await a.send(_register_msg())
    ack = await a.recv_json()
    assert ack["type"] == "REGISTER_ACK" and ack["errorCode"] == 0
    await a.send({"type": "DEVICE_STATE", "timestamp": 2,
                  "deviceMac": MAC, "state": "READY"})
    yield a
    await a.close()


async def _until(fn, timeout: float = 5.0):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while True:
        value = await fn()
        if value:
            return value
        assert loop.time() < deadline, "等待条件超时"
        await asyncio.sleep(0.05)


# ---------------- 鉴权 ----------------

async def test_auth_required(runtime):
    base = f"http://127.0.0.1:{runtime.api_port}"
    async with httpx.AsyncClient(base_url=base, trust_env=False) as anon:
        assert (await anon.get("/api/ledger")).status_code == 401
    async with httpx.AsyncClient(
            base_url=base, headers={"Authorization": "Bearer wrong"},
            trust_env=False) as bad:
        assert (await bad.get("/api/ledger")).status_code == 401


# ---------------- 台账 ----------------

async def test_ledger(api):
    r = await api.get("/api/ledger")
    assert r.status_code == 200
    body = r.json()
    assert body["pending"] == []
    assert body["stats"] == {"pending": 0, "acked": 0,
                             "failed": 0, "throttled": 0}


# ---------------- 设备运行中配置更新 ----------------

async def test_polling_interval_without_agent(api):
    """无归属 agent：仅配置生效，dispatched=False。"""
    await api.put("/api/devices", json=_device())
    r = await api.post(f"/api/devices/{MAC}/polling-interval",
                       json={"intervalMs": 5000})
    assert r.status_code == 200, r.text
    assert r.json()["dispatched"] is False
    devices = (await api.get("/api/devices")).json()
    assert devices[0]["polling"]["intervalMs"] == 5000


async def test_device_config_errors(api):
    await api.put("/api/devices", json=_device())
    # 设备不存在 → 404（KeyError）
    r = await api.post("/api/devices/00:00:00:00:00:00/polling-interval",
                       json={"intervalMs": 5000})
    assert r.status_code == 404
    # 越界 intervalMs → 400（ValueError）
    r = await api.post(f"/api/devices/{MAC}/polling-interval",
                       json={"intervalMs": 100})
    assert r.status_code == 400
    r = await api.post("/api/devices/00:00:00:00:00:00/rules",
                       json={"rules": []})
    assert r.status_code == 404
    r = await api.post("/api/devices/00:00:00:00:00:00/persistent",
                       json={"on": True})
    assert r.status_code == 404


async def test_rules_and_persistent_no_owner(api):
    """设备未归属任何 agent：rules/persistent 仅配置生效。"""
    await api.put("/api/devices", json=_device())
    r = await api.post(f"/api/devices/{MAC}/rules", json={"rules": []})
    assert r.status_code == 200 and r.json()["dispatched"] is False
    r = await api.post(f"/api/devices/{MAC}/persistent", json={"on": True})
    assert r.status_code == 200 and r.json()["dispatched"] is False
    devices = (await api.get("/api/devices")).json()
    assert devices[0]["persistent"] is True


async def test_offline_owner_409(runtime, api, agent):
    """agent 断开后视图仍持有设备归属 → 配置下发 409。"""
    await agent.close()
    await _until(lambda: _agent_offline(api))
    r = await api.post(f"/api/devices/{MAC}/polling-interval",
                       json={"intervalMs": 5000})
    assert r.status_code == 409


async def _agent_offline(api):
    r = await api.get(f"/api/agents/{AGENT}/status")
    return r.json()["state"] == "offline" or None


async def test_max_connections(api):
    r = await api.post("/api/config/max-connections", json={"maxSlots": 4})
    assert r.status_code == 200, r.text
    assert r.json()["maxSlots"] == 4
    globals_ = (await api.get("/api/config/globals")).json()
    assert globals_["maxSlots"] == 4
    # 越界（合法区间 2~5，§5.4）→ 400
    r = await api.post("/api/config/max-connections", json={"maxSlots": 9})
    assert r.status_code == 400


# ---------------- 端到端：配置下发 + ACK 对账 ----------------

async def test_polling_interval_dispatch_and_ack(api, agent):
    """在线归属 agent：下发 SET_POLLING_INTERVAL → agent 回 ACK → 200。"""
    req_task = asyncio.create_task(api.post(
        f"/api/devices/{MAC}/polling-interval", json={"intervalMs": 5000}))
    cmd = await agent.recv_json()
    assert cmd["type"] == "SET_POLLING_INTERVAL"
    assert cmd["deviceMac"] == MAC and cmd["intervalMs"] == 5000
    await agent.send({"type": "CMD_ACK", "timestamp": 3,
                      "requestId": cmd["requestId"], "errorCode": 0})
    resp = await req_task
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["dispatched"] is True
    assert body["result"]["errorCode"] == 0
    assert body["result"]["ledgerStatus"] == "acked"
    devices = (await api.get("/api/devices")).json()
    assert devices[0]["polling"]["intervalMs"] == 5000


async def test_ack_timeout_504(api, agent):
    """agent 不回 ACK → 504。"""
    req_task = asyncio.create_task(api.post(
        f"/api/devices/{MAC}/rules", json={"rules": []}))
    cmd = await agent.recv_json()
    assert cmd["type"] == "SET_POLL_RULES"
    resp = await req_task  # 不回 ACK
    assert resp.status_code == 504


# ---------------- 文件与传输 ----------------

async def test_files_flow(api, agent):
    # 上传（multipart）
    r = await api.post("/api/files",
                       files={"file": ("ota.bin", b"firmware-bytes" * 100)})
    assert r.status_code == 200, r.text
    rec = r.json()
    assert rec["name"] == "ota.bin"
    assert rec["size"] == len(b"firmware-bytes" * 100)
    file_id = rec["fileId"]

    # 列表
    files = (await api.get("/api/files")).json()
    assert [f["fileId"] for f in files] == [file_id]

    # 发起传输 → agent 收到 FILE_TRANSFER
    r = await api.post(f"/api/files/{file_id}/transfer",
                       json={"agentId": AGENT, "deviceMac": MAC})
    assert r.status_code == 200, r.text
    task_id = r.json()["taskId"]
    cmd = await agent.recv_json()
    assert cmd["type"] == "FILE_TRANSFER" and cmd["taskId"] == task_id

    # 任务查询
    tasks = (await api.get("/api/transfers")).json()
    assert tasks[0]["taskId"] == task_id
    task = (await api.get(f"/api/transfers/{task_id}")).json()
    assert task["state"] == "WAIT_READY" and task["deviceMac"] == MAC

    # 取消 → agent 收到 FILE_CANCEL
    r = await api.post(f"/api/transfers/{task_id}/cancel")
    assert r.status_code == 200
    cancel = await agent.recv_json()
    assert cancel["type"] == "FILE_CANCEL" and cancel["taskId"] == task_id

    # 删除文件
    r = await api.delete(f"/api/files/{file_id}")
    assert r.status_code == 200
    assert (await api.get("/api/files")).json() == []


async def test_file_errors(api, agent):
    r = await api.delete("/api/files/file-ghost")
    assert r.status_code == 404
    r = await api.post("/api/files/file-ghost/transfer",
                       json={"agentId": AGENT, "deviceMac": MAC})
    assert r.status_code == 404
    # agent 不在线 → 409
    r = await api.post("/api/files",
                       files={"file": ("a.bin", b"x")})
    file_id = r.json()["fileId"]
    r = await api.post(f"/api/files/{file_id}/transfer",
                       json={"agentId": "ghost", "deviceMac": MAC})
    assert r.status_code == 409
    r = await api.get("/api/transfers/task-ghost")
    assert r.status_code == 404
    r = await api.post("/api/transfers/task-ghost/cancel")
    assert r.status_code == 404


async def test_log_upload(api, agent):
    r = await api.post(f"/api/agents/{AGENT}/log-upload",
                       json={"sinceTs": 1000, "minLevel": "WARN"})
    assert r.status_code == 200, r.text
    request_id = r.json()["requestId"]
    cmd = await agent.recv_json()
    assert cmd["type"] == "UPLOAD_LOG" and cmd["requestId"] == request_id
    assert cmd["sinceTs"] == 1000 and cmd["minLevel"] == "WARN"
    # agent 不在线 → 409
    r = await api.post("/api/agents/ghost/log-upload", json={})
    assert r.status_code == 409


# ---------------- 测试编排 ----------------

async def test_list_scenarios(api):
    r = await api.get("/api/scenarios")
    assert r.status_code == 200
    by_path = {s["path"]: s["name"] for s in r.json()}
    assert by_path.get("basic_connect_poll.json") == "基本连接与轮询"


async def test_run_scenario_inline(api, agent):
    scenario = {
        "name": "api-smoke",
        "steps": [
            {"name": "短暂停", "action": "delay", "ms": 100},
            {"name": "断言设备就绪", "action": "assertView",
             "path": f"devices.{MAC}.state", "expect": "READY"},
        ],
    }
    r = await api.post("/api/tests/run",
                       json={"agentId": AGENT, "scenario": scenario})
    assert r.status_code == 200, r.text
    run_id = r.json()["runId"]

    # 轮询至结束
    detail = await _until(lambda: _finished_run(api, run_id))
    assert detail["status"] == "PASS"
    assert [s["status"] for s in detail["results"]] == ["PASS", "PASS"]
    assert detail["report"]["runId"] == run_id

    # 列表含该 run
    runs = (await api.get("/api/tests/runs")).json()["runs"]
    assert any(run["runId"] == run_id for run in runs)


async def _finished_run(api, run_id):
    r = await api.get(f"/api/tests/runs/{run_id}")
    if r.status_code != 200:
        return None
    body = r.json()
    return body if body["status"] != "RUNNING" else None


async def test_run_busy_409_and_stop(api):
    slow = {"name": "slow", "steps": [{"action": "delay", "ms": 30000}]}
    r = await api.post("/api/tests/run",
                       json={"agentId": "agent-x", "scenario": slow})
    assert r.status_code == 200
    run_id = r.json()["runId"]
    # 同 agent 并发 → 409（AgentBusyError）
    r = await api.post("/api/tests/run",
                       json={"agentId": "agent-x", "scenario": slow})
    assert r.status_code == 409
    # 在跑快照可见
    running = (await api.get("/api/tests/runs")).json()["running"]
    assert any(run["runId"] == run_id for run in running)
    # 停止 → 报告落库为 ERROR（已取消）
    r = await api.post(f"/api/tests/runs/{run_id}/stop")
    assert r.status_code == 200
    detail = await _until(lambda: _finished_run(api, run_id))
    assert detail["status"] == "ERROR"
    # 重复停止 → 404
    r = await api.post(f"/api/tests/runs/{run_id}/stop")
    assert r.status_code == 404


async def test_run_errors(api):
    # 场景文件不存在 → 404
    r = await api.post("/api/tests/run",
                       json={"agentId": AGENT,
                             "scenario": {"path": "nope.json"}})
    assert r.status_code == 404
    # 内联场景非法 → 422
    r = await api.post("/api/tests/run",
                       json={"agentId": AGENT, "scenario": {"steps": []}})
    assert r.status_code == 422
    # 未知 run → 404
    assert (await api.get("/api/tests/runs/run-ghost")).status_code == 404
    assert (await api.post("/api/tests/runs/run-ghost/stop")).status_code == 404


# ---------------- WebSocket 实时事件流 ----------------

def _ws_url(runtime, **params) -> str:
    qs = "&".join(f"{k}={v}" for k, v in params.items())
    return f"ws://127.0.0.1:{runtime.api_port}/ws/events?{qs}"


async def test_ws_events_stream(runtime, agent):
    # proxy=None：环境里有 http_proxy，直连本地必须绕过
    uri = _ws_url(runtime, token="api-token", type="DEVICE_STATE,POLL_RESULT")
    async with websockets.connect(uri, proxy=None) as ws:
        # HEARTBEAT 被过滤；DEVICE_STATE 推送到达
        await agent.send({"type": "HEARTBEAT", "timestamp": 10,
                          "slotsUsed": 1, "slotsTotal": 3,
                          "devicesManaged": 1, "devicesReady": 1})
        await agent.send({"type": "DEVICE_STATE", "timestamp": 11,
                          "deviceMac": MAC, "state": "CONNECTED"})
        msg = json.loads(await asyncio.wait_for(ws.recv(), 5))
        assert msg["agentId"] == AGENT
        assert msg["type"] == "DEVICE_STATE"
        assert msg["ts"] == 11
        assert msg["payload"]["state"] == "CONNECTED"


async def test_ws_events_all_types(runtime, agent):
    uri = _ws_url(runtime, token="api-token")
    async with websockets.connect(uri, proxy=None) as ws:
        await agent.send({"type": "POLL_RESULT", "timestamp": 12,
                          "deviceMac": MAC, "stale": False,
                          "values": {"battery": 66}})
        # 无过滤流：fixture 建归属的 DEVICE_STATE 可能在途，按类型排空至目标事件
        deadline = asyncio.get_running_loop().time() + 5
        while True:
            msg = json.loads(await asyncio.wait_for(
                ws.recv(), max(0.1, deadline - asyncio.get_running_loop().time())))
            if msg["type"] == "POLL_RESULT":
                break
        assert msg["payload"]["values"]["battery"] == 66


async def test_ws_bad_token_rejected(runtime):
    with pytest.raises(websockets.exceptions.InvalidStatus):
        async with websockets.connect(_ws_url(runtime, token="wrong"), proxy=None):
            pass
    with pytest.raises(websockets.exceptions.InvalidStatus):
        async with websockets.connect(_ws_url(runtime), proxy=None):
            pass
