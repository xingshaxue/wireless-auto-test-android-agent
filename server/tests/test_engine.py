"""engine 测试编排引擎测试（SDD §14）。

覆盖：场景模型校验（未知 action / 缺必填 / 负超时 / 未知匹配操作符）、
匹配器全操作符与点号路径、runner 全 PASS 链路（CONNECT_DEVICE→ACK→
DEVICE_STATE→POLL_RESULT→assertView）、expect 超时 FAIL + 后续 SKIP +
teardown 恒执行、expectAck errorCode 不匹配、assertView 失败、报告落库、
同 agent 并发互斥 + stop 取消、P95 统计。
"""

import asyncio
import collections
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from wireless_server.codec import StreamDemuxer, encode_json
from wireless_server.engine import (
    AgentBusyError, Scenario, TestReport, load_scenario, percentile,
)
from wireless_server.engine.scenario import (
    MISSING, get_path, match_fields, match_value,
)
from wireless_server.runtime import Runtime
from wireless_server.settings import Settings

MAC = "AA:BB:CC:DD:EE:FF"
AGENT = "agent-engine-01"

SCENARIOS_DIR = Path(__file__).resolve().parent.parent / "scenarios"


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


@pytest.fixture
async def runtime():
    settings = Settings()
    settings.gateway.host = "127.0.0.1"
    settings.gateway.port = 0
    settings.gateway.agent_token = "gw-token"
    settings.gateway.ack_timeout_ms = 1000
    settings.api.host = "127.0.0.1"
    settings.api.port = 0
    settings.api.token = "api-token"
    settings.storage.db_path = ":memory:"
    rt = Runtime(settings)
    await rt.start()
    yield rt
    await rt.stop()


@pytest.fixture
async def agent(runtime):
    a = await FakeAgent.connect(runtime.gateway_port)
    await a.send({
        "type": "REGISTER", "timestamp": 1, "deviceId": AGENT,
        "ip": "10.0.0.1", "port": 0, "androidSdk": 34, "bleSupported": True,
        "maxConnections": 5, "agentVersion": "1.0", "token": "gw-token",
    })
    ack = await a.recv_json()
    assert ack["type"] == "REGISTER_ACK" and ack["errorCode"] == 0
    yield a
    await a.close()


# ==================== 场景模型 ====================

def test_scenario_parse_valid():
    sc = Scenario.model_validate({
        "name": "s1",
        "steps": [
            {"name": "连接", "action": "command", "command": "CONNECT_DEVICE",
             "params": {"deviceMac": MAC}, "expectAck": {"errorCode": 0}},
            {"action": "expect", "event": "DEVICE_STATE",
             "match": {"state": "READY"}, "timeoutMs": 5000},
            {"action": "delay", "ms": 100},
            {"action": "assertView", "path": "slots.slotsUsed",
             "expect": {"lte": 3}},
            {"action": "set_polling_interval", "deviceMac": MAC,
             "intervalMs": 5000},
            {"action": "set_poll_rules", "deviceMac": MAC, "rules": []},
            {"action": "file_transfer", "deviceMac": MAC, "fileId": "fw.bin"},
        ],
    })
    assert sc.name == "s1" and len(sc.steps) == 7
    # 未命名步骤自动生成 display_name
    assert sc.steps[1].display_name == "expect:DEVICE_STATE"


def test_scenario_sample_file_loads():
    sc = load_scenario(SCENARIOS_DIR / "basic_connect_poll.json")
    assert sc.name == "基本连接与轮询"
    assert sc.setup[0].action == "set_polling_interval"
    assert sc.teardown[0].optional is True


def test_scenario_unknown_action_rejected():
    with pytest.raises(ValidationError):
        Scenario.model_validate({"name": "x", "steps": [{"action": "fly"}]})


def test_scenario_missing_required_rejected():
    with pytest.raises(ValidationError):  # command 缺 command 字段
        Scenario.model_validate({"name": "x", "steps": [{"action": "command"}]})
    with pytest.raises(ValidationError):  # expect 缺 event
        Scenario.model_validate({"name": "x", "steps": [{"action": "expect"}]})


def test_scenario_negative_timeout_rejected():
    with pytest.raises(ValidationError):
        Scenario.model_validate({"name": "x", "steps": [
            {"action": "expect", "event": "E", "timeoutMs": -1}]})
    with pytest.raises(ValidationError):
        Scenario.model_validate({"name": "x", "steps": [
            {"action": "delay", "ms": 0}]})


def test_scenario_unknown_match_op_rejected():
    with pytest.raises(ValidationError):
        Scenario.model_validate({"name": "x", "steps": [
            {"action": "expect", "event": "E",
             "match": {"a": {"between": 1}}}]})


def test_matcher_operators():
    assert match_value(5, 5)                      # 标量相等
    assert not match_value(5, 6)
    assert match_value(5, {"eq": 5})
    assert match_value(5, {"ne": 6})
    assert match_value(5, {"gt": 4, "lte": 5})    # 多操作符全部成立
    assert match_value(5, {"gte": 5})
    assert match_value(5, {"lt": 6})
    assert not match_value(5, {"gt": 5})
    assert match_value("hello world", {"contains": "world"})
    assert match_value([1, 2], {"contains": 2})
    assert match_value({"k": 1}, {"exists": True})
    assert match_value(MISSING, {"exists": False})
    assert not match_value(MISSING, {"eq": 1})
    assert not match_value("abc", {"gt": 1})      # 类型不符不抛错


def test_dotted_path():
    raw = {"values": {"battery": 87}, "state": "READY"}
    assert get_path(raw, "values.battery") == 87
    assert get_path(raw, "values.missing.deep") is MISSING
    assert match_fields(raw, {"values.battery": {"gte": 80},
                              "state": "READY"}) is None
    err = match_fields(raw, {"values.battery": {"lt": 80}})
    assert err and "values.battery" in err


# ==================== runner ====================

def _full_scenario() -> dict:
    return {
        "name": "基本连接与轮询",
        "steps": [
            {"name": "连接设备", "action": "command", "command": "CONNECT_DEVICE",
             "params": {"deviceMac": MAC},
             "expectAck": {"errorCode": 0}, "ackTimeoutMs": 2000},
            {"name": "等待 READY", "action": "expect", "event": "DEVICE_STATE",
             "match": {"deviceMac": MAC, "state": "READY"}, "timeoutMs": 2000},
            {"name": "等待轮询结果", "action": "expect", "event": "POLL_RESULT",
             "match": {"values.battery": {"gte": 0}}, "timeoutMs": 2000},
            {"name": "等待心跳", "action": "expect", "event": "HEARTBEAT",
             "match": {"slotsUsed": {"gte": 0}}, "timeoutMs": 2000},
            {"name": "短暂停", "action": "delay", "ms": 10},
            {"name": "断言槽位", "action": "assertView",
             "path": "slots.slotsUsed", "expect": {"lte": 3}},
        ],
        "teardown": [
            {"name": "断开设备", "action": "command",
             "command": "DISCONNECT_DEVICE",
             "params": {"deviceMac": MAC}, "optional": True},
        ],
    }


async def test_runner_full_pass(runtime, agent):
    task = asyncio.create_task(
        runtime.engine.run_scenario(_full_scenario(), AGENT))

    cmd = await agent.recv_json()
    assert cmd["type"] == "CONNECT_DEVICE" and cmd["deviceMac"] == MAC
    await agent.send({"type": "CMD_ACK", "timestamp": 2,
                      "requestId": cmd["requestId"], "errorCode": 0})
    await agent.send({"type": "DEVICE_STATE", "timestamp": 3,
                      "deviceMac": MAC, "state": "READY"})
    await agent.send({"type": "POLL_RESULT", "timestamp": 4,
                      "deviceMac": MAC, "stale": False,
                      "values": {"battery": 85}})
    await agent.send({"type": "HEARTBEAT", "timestamp": 5,
                      "slotsUsed": 1, "slotsTotal": 3,
                      "devicesManaged": 1, "devicesReady": 1})

    report = await asyncio.wait_for(task, 5)
    assert report.status == "PASS"
    assert [s.status for s in report.steps] == ["PASS"] * 7
    assert all(s.elapsed_ms >= 0 for s in report.steps)
    stats = report.stats()
    assert stats["ackCount"] == 1 and stats["ackP95Ms"] is not None
    assert stats["eventWaitCount"] == 3

    # teardown 恒执行：DISCONNECT_DEVICE 已下发
    disc = await agent.recv_json()
    assert disc["type"] == "DISCONNECT_DEVICE"

    # 报告落库（test_runs + test_results）
    row = await runtime.store.get_test_run(report.run_id)
    assert row is not None and row["status"] == "PASS"
    assert row["scenario"] == "基本连接与轮询"
    assert len(row["results"]) == 7
    assert row["results"][0]["name"] == "连接设备"
    assert row["report"]["stats"]["ackCount"] == 1
    runs = await runtime.store.list_test_runs()
    assert runs[0]["runId"] == report.run_id


async def test_runner_expect_timeout_fail_skip_teardown(runtime, agent):
    scenario = {
        "name": "超时场景",
        "steps": [
            {"name": "等不到的事件", "action": "expect", "event": "DEVICE_STATE",
             "match": {"state": "READY"}, "timeoutMs": 300},
            {"name": "不应执行", "action": "command", "command": "RESET",
             "expectAck": {"errorCode": 0}, "ackTimeoutMs": 500},
        ],
        "teardown": [
            {"name": "断开设备", "action": "command",
             "command": "DISCONNECT_DEVICE",
             "params": {"deviceMac": MAC}, "optional": True},
        ],
    }
    report = await runtime.engine.run_scenario(scenario, AGENT)
    assert report.status == "FAIL"
    s0, s1, s2 = report.steps
    assert s0.status == "FAIL" and "超时" in s0.detail
    assert s1.status == "SKIP"
    assert s2.status == "PASS" and s2.phase == "teardown"
    disc = await agent.recv_json()
    assert disc["type"] == "DISCONNECT_DEVICE"  # teardown 恒执行


async def test_runner_expect_ack_mismatch(runtime, agent):
    scenario = {
        "name": "ACK 不符",
        "steps": [
            {"name": "连接设备", "action": "command", "command": "CONNECT_DEVICE",
             "params": {"deviceMac": MAC},
             "expectAck": {"errorCode": 0}, "ackTimeoutMs": 2000},
            {"name": "应跳过", "action": "delay", "ms": 10},
        ],
    }
    task = asyncio.create_task(
        runtime.engine.run_scenario(scenario, AGENT))
    cmd = await agent.recv_json()
    await agent.send({"type": "CMD_ACK", "timestamp": 2,
                      "requestId": cmd["requestId"], "errorCode": 2004})
    report = await asyncio.wait_for(task, 5)
    assert report.status == "FAIL"
    assert report.steps[0].status == "FAIL"
    assert "ACK 不符" in report.steps[0].detail
    assert report.steps[1].status == "SKIP"


async def test_runner_assert_view_fail(runtime, agent):
    scenario = {
        "name": "断言失败",
        "steps": [
            {"name": "等轮询", "action": "expect", "event": "POLL_RESULT",
             "match": {"deviceMac": MAC}, "timeoutMs": 2000},
            {"name": "断言电量", "action": "assertView",
             "path": f"devices.{MAC}.values.battery", "expect": {"lt": 50}},
        ],
    }
    task = asyncio.create_task(
        runtime.engine.run_scenario(scenario, AGENT))
    await asyncio.sleep(0.05)  # 等 expect 步骤就绪
    await agent.send({"type": "POLL_RESULT", "timestamp": 2,
                      "deviceMac": MAC, "stale": False,
                      "values": {"battery": 87}})
    report = await asyncio.wait_for(task, 5)
    assert report.status == "FAIL"
    assert report.steps[0].status == "PASS"
    assert report.steps[1].status == "FAIL"
    assert "devices." in report.steps[1].detail


async def test_runner_file_transfer_unknown_file(runtime, agent):
    # transfer 已装配（§7.6）：未注册文件 → 步骤 FAIL；未装配则 SKIP
    scenario = {
        "name": "文件传输",
        "steps": [{"name": "传固件", "action": "file_transfer",
                   "deviceMac": MAC, "fileId": "no-such-file"}],
    }
    report = await runtime.engine.run_scenario(scenario, AGENT)
    step = report.steps[0]
    if getattr(runtime, "transfer", None) is not None:
        assert step.status == "FAIL" and "文件传输下发失败" in step.detail
    else:
        assert step.status == "SKIP"


async def test_runner_concurrency_and_stop(runtime, agent):
    scenario = {
        "name": "阻塞场景",
        "steps": [{"name": "长等待", "action": "expect", "event": "HEARTBEAT",
                   "match": {}, "timeoutMs": 10000}],
    }
    task = asyncio.create_task(
        runtime.engine.run_scenario(scenario, AGENT))
    # 等同 agent 互斥锁就位
    deadline = asyncio.get_running_loop().time() + 2
    while not runtime.engine.running():
        assert asyncio.get_running_loop().time() < deadline
        await asyncio.sleep(0.01)
    # 同 agent 第二个场景立即被拒
    with pytest.raises(AgentBusyError):
        await runtime.engine.run_scenario(scenario, AGENT)
    # stop 取消：总状态 ERROR，当前步骤 FAIL，报告照常落库
    run_id = runtime.engine.running()[0]["runId"]
    assert await runtime.engine.stop(run_id) is True
    report = await asyncio.wait_for(task, 5)
    assert report.status == "ERROR" and "取消" in report.detail
    assert report.steps[0].status == "FAIL" and "取消" in report.steps[0].detail
    assert runtime.engine.running() == []
    row = await runtime.store.get_test_run(run_id)
    assert row["status"] == "ERROR"
    assert await runtime.engine.stop("run-nonexistent") is False


async def test_runner_setup_failure_skips_steps(runtime, agent):
    scenario = {
        "name": "setup 失败",
        "setup": [{"name": "前置等待", "action": "expect", "event": "NOPE",
                   "timeoutMs": 200}],
        "steps": [{"name": "主步骤", "action": "delay", "ms": 10}],
        "teardown": [{"name": "清理", "action": "delay", "ms": 10}],
    }
    report = await runtime.engine.run_scenario(scenario, AGENT)
    assert report.status == "FAIL"
    assert [s.status for s in report.steps] == ["FAIL", "SKIP", "PASS"]
    assert report.steps[1].detail == "setup 失败"


# ==================== P95 统计 ====================

def test_percentile():
    assert percentile([], 95) is None
    vals = [float(i) for i in range(1, 21)]  # 1..20
    assert percentile(vals, 95) == 19.0   # 最近秩：ceil(0.95*20)=19
    assert percentile(vals, 50) == 10.0
    assert percentile([7.0], 95) == 7.0
    report = TestReport(run_id="r", scenario="s", agent_id="a",
                        ack_latencies_ms=vals,
                        event_wait_latencies_ms=[1.0, 2.0])
    stats = report.stats()
    assert stats["ackP95Ms"] == 19.0 and stats["ackP50Ms"] == 10.0
    assert stats["eventWaitCount"] == 2
    d = report.to_dict()
    json.dumps(d, ensure_ascii=False)  # 可序列化（落库前提）
    assert d["stats"]["ackP95Ms"] == 19.0
