"""端到端集成测试：完整 runtime + 模拟 agent 客户端 + httpx API 调用。

覆盖（SDD §7.1 / §8.4 / §16.4）：
REGISTER 任意 token 均接受（旧 agent 残留 token 字段忽略）→ 注册
（configVersion=1，含 devices）→ HEARTBEAT 上线 → DEVICE_STATE/POLL_RESULT
入库与视图 → POST /api/commands 下发 + CMD_ACK 对账 → 二次注册 configVersion
递增 → API 免鉴权。
"""

import asyncio
import collections
import json

import httpx
import pytest

from wireless_server.codec import StreamDemuxer, encode_json
from wireless_server.runtime import Runtime
from wireless_server.settings import Settings

MAC = "AA:BB:CC:DD:EE:FF"
AGENT = "agent-it-01"


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

    async def recv(self, timeout: float = 5.0):
        """取下一条入站报文（json dict 或 ('frame', ...)）。"""
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

    async def read_eof(self, timeout: float = 5.0) -> None:
        data = await asyncio.wait_for(self.reader.read(65536), timeout)
        assert data == b"", f"期望连接关闭，实收 {data!r}"

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
async def runtime():
    settings = Settings()
    settings.gateway.host = "127.0.0.1"
    settings.gateway.port = 0
    settings.gateway.ack_timeout_ms = 1000
    settings.api.host = "127.0.0.1"
    settings.api.port = 0
    settings.storage.db_path = ":memory:"
    rt = Runtime(settings)
    await rt.start()
    yield rt
    await rt.stop()


@pytest.fixture
async def api(runtime):
    async with httpx.AsyncClient(
        base_url=f"http://127.0.0.1:{runtime.api_port}",
        trust_env=False,  # 不走环境代理，直连本地
    ) as client:
        yield client


async def _until(fn, timeout: float = 5.0):
    """轮询直到 fn() 返回真值。"""
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while True:
        value = await fn()
        if value:
            return value
        assert loop.time() < deadline, "等待条件超时"
        await asyncio.sleep(0.05)


async def test_register_any_token_accepted(runtime):
    # 内网免鉴权：无 token 注册成功
    agent = await FakeAgent.connect(runtime.gateway_port)
    await agent.send(_register_msg())
    ack = await agent.recv_json()
    assert ack["type"] == "REGISTER_ACK" and ack["errorCode"] == 0
    await agent.close()

    # 旧 agent 残留 token 字段（含错误值）同样接受
    agent2 = await FakeAgent.connect(runtime.gateway_port)
    msg = _register_msg()
    msg["token"] = "bad-token"
    await agent2.send(msg)
    ack2 = await agent2.recv_json()
    assert ack2["type"] == "REGISTER_ACK" and ack2["errorCode"] == 0
    await agent2.close()


async def test_full_flow(runtime, api):
    # 预置设备配置（§16.4），注册后随 REGISTER_ACK 下发
    r = await api.put("/api/devices", json=_device())
    assert r.status_code == 200, r.text

    # ---- REGISTER → REGISTER_ACK 含 configVersion=1 与 devices ----
    agent = await FakeAgent.connect(runtime.gateway_port)
    await agent.send(_register_msg())
    ack = await agent.recv_json()
    assert ack["type"] == "REGISTER_ACK" and ack["errorCode"] == 0
    config = ack["config"]
    assert config["configVersion"] == 1
    assert [d["mac"] for d in config["devices"]] == [MAC]

    # ---- HEARTBEAT → GET /api/agents 可见在线 ----
    await agent.send({"type": "HEARTBEAT", "timestamp": 2,
                      "slotsUsed": 1, "slotsTotal": 3,
                      "devicesManaged": 1, "devicesReady": 0})
    agents = await _until(lambda: _find_agent(api))
    assert agents["state"] == "online"

    # ---- DEVICE_STATE / POLL_RESULT → events 入库 + status 视图 ----
    await agent.send({"type": "DEVICE_STATE", "timestamp": 3,
                      "deviceMac": MAC, "state": "READY"})
    await agent.send({"type": "POLL_RESULT", "timestamp": 4,
                      "deviceMac": MAC, "stale": False,
                      "values": {"battery": 87}})
    events = await _until(lambda: _get_events(api, want=3))
    types = {e["type"] for e in events}
    assert {"DEVICE_STATE", "POLL_RESULT", "HEARTBEAT"} <= types

    r = await api.get(f"/api/agents/{AGENT}/status")
    assert r.status_code == 200
    body = r.json()
    assert body["state"] == "online" and body["configVersion"] == 1
    dev = body["view"]["devices"][MAC]
    assert dev["state"] == "READY" and dev["stateFlag"] == "ok"
    assert dev["lastPollTime"] == 4 and dev["values"]["battery"] == 87
    assert body["view"]["slots"] == {"slotsUsed": 1, "slotsTotal": 3}

    # ---- POST /api/commands 下发 READ_CHAR → agent 校验 → CMD_ACK 对账 ----
    cmd_task = asyncio.create_task(api.post(
        f"/api/commands/{AGENT}",
        json={"type": "READ_CHAR", "deviceMac": MAC,
              "service": "180F", "char": "2A19"},
    ))
    cmd = await agent.recv_json()
    assert cmd["type"] == "READ_CHAR" and cmd["deviceMac"] == MAC
    assert cmd["requestId"]  # 对账字段非空（A.1）
    await agent.send({"type": "CMD_ACK", "timestamp": 5,
                      "requestId": cmd["requestId"], "errorCode": 0,
                      "result": {"value": "Vw=="}})
    resp = await cmd_task
    assert resp.status_code == 200, resp.text
    result = resp.json()
    assert result["errorCode"] == 0 and result["ledgerStatus"] == "acked"
    assert result["requestId"] == cmd["requestId"]
    assert result["result"] == {"value": "Vw=="}

    # ---- 二次注册（重连踢旧连接）→ configVersion 递增为 2 ----
    agent2 = await FakeAgent.connect(runtime.gateway_port)
    await agent2.send(_register_msg())
    ack2 = await agent2.recv_json()
    assert ack2["config"]["configVersion"] == 2
    await agent.read_eof()  # 旧连接被踢
    await agent.close()
    await agent2.close()


async def _find_agent(api: httpx.AsyncClient):
    r = await api.get("/api/agents")
    assert r.status_code == 200
    for a in r.json():
        if a["agentId"] == AGENT and a["state"] == "online":
            return a
    return None


async def _get_events(api: httpx.AsyncClient, want: int):
    r = await api.get("/api/events", params={"agent_id": AGENT})
    assert r.status_code == 200
    rows = r.json()
    return rows if len(rows) >= want else None


async def test_api_no_auth_required(runtime):
    # 内网免鉴权：无 token 200；残留 Bearer 头被忽略
    async with httpx.AsyncClient(
            base_url=f"http://127.0.0.1:{runtime.api_port}",
            trust_env=False) as anon:
        assert (await anon.get("/api/agents")).status_code == 200
        bad = httpx.AsyncClient(
            base_url=f"http://127.0.0.1:{runtime.api_port}",
            headers={"Authorization": "Bearer wrong"},
            trust_env=False)
        assert (await bad.get("/api/agents")).status_code == 200
        await bad.aclose()


async def test_command_unknown_agent_404(api):
    r = await api.post("/api/commands/ghost",
                       json={"type": "RESET"})
    assert r.status_code == 404
