"""CommandLedger 单测：ACK 对账 / 超时重发 / failed / 限流归类 / wait_ack（SDD §8.4 / §12.9）。"""

import asyncio

import pytest

from wireless_server.ledger import ACK_TIMEOUT_CODE, CommandLedger
from wireless_server.protocol.errors import ResourceErrorCode
from wireless_server.settings import GatewaySettings


class FakeSession:
    """假 session：只记录 send_json 下发的报文。"""

    def __init__(self, agent_id: str = "agent-1") -> None:
        self.agent_id = agent_id
        self.sent: list[dict] = []
        self.closed = False

    async def send_json(self, msg: dict) -> None:
        self.sent.append(msg)


@pytest.fixture
def settings() -> GatewaySettings:
    # 小超时加速测试；重发 1 次（超时 100ms，测试内 sleep 150ms 错开两次超时点）
    return GatewaySettings(ack_timeout_ms=100, ack_max_retries=1)


@pytest.fixture
def ledger(settings) -> CommandLedger:
    return CommandLedger(settings)


@pytest.fixture
def session() -> FakeSession:
    return FakeSession()


async def test_ack_reconcile(ledger, session):
    rid = await ledger.send_command(session, "READ_CHAR",
                                    deviceMac="AA:BB:CC:DD:EE:FF",
                                    service="180F", char="2A19")
    assert len(session.sent) == 1
    wire = session.sent[0]
    assert wire["type"] == "READ_CHAR" and wire["requestId"] == rid
    assert ledger.stats()["pending"] == 1

    ledger.on_cmd_ack("agent-1", {"type": "CMD_ACK", "timestamp": 1,
                                  "requestId": rid, "errorCode": 0,
                                  "result": {"value": "AQ=="}})
    stats = ledger.stats()
    assert stats["pending"] == 0 and stats["acked"] == 1
    record = ledger.pending()
    assert record == []


async def test_ack_unknown_request_id(ledger):
    # 未知 requestId 只记日志，不影响台账
    ledger.on_cmd_ack("agent-1", {"requestId": "nope", "errorCode": 0})
    assert ledger.stats() == {"pending": 0, "acked": 0, "failed": 0, "throttled": 0}


async def test_timeout_retry_then_failed(ledger, session):
    rid = await ledger.send_command(session, "RESET")
    assert len(session.sent) == 1

    await asyncio.sleep(0.15)  # 第一次超时 → 重发（attempts=2）
    assert len(session.sent) == 2
    assert session.sent[1]["requestId"] == rid
    assert ledger.stats()["pending"] == 1

    await asyncio.sleep(0.15)  # 第二次超时 → failed
    stats = ledger.stats()
    assert stats["pending"] == 0 and stats["failed"] == 1


async def test_retry_then_ack_succeeds(ledger, session):
    rid = await ledger.send_command(session, "RESET")
    await asyncio.sleep(0.15)  # 超时重发
    assert len(session.sent) == 2
    ledger.on_cmd_ack("agent-1", {"requestId": rid, "errorCode": 0})
    await asyncio.sleep(0.15)  # 定时器已取消，不再 failed
    stats = ledger.stats()
    assert stats["acked"] == 1 and stats["failed"] == 0


async def test_throttle_classification(ledger, session):
    rid1 = await ledger.send_command(session, "READ_CHAR",
                                     deviceMac="AA:BB:CC:DD:EE:FF",
                                     service="180F", char="2A19")
    ledger.on_cmd_ack("agent-1", {"requestId": rid1,
                                  "errorCode": int(ResourceErrorCode.QUEUE_FULL)})
    rid2 = await ledger.send_command(session, "READ_CHAR",
                                     deviceMac="AA:BB:CC:DD:EE:FF",
                                     service="180F", char="2A19")
    ledger.on_cmd_ack("agent-1", {"requestId": rid2,
                                  "errorCode": int(ResourceErrorCode.COMMAND_EXPIRED)})
    stats = ledger.stats()
    assert stats["throttled"] == 2 and stats["acked"] == 2

    # 非限流错误不计入
    rid3 = await ledger.send_command(session, "READ_CHAR",
                                     deviceMac="AA:BB:CC:DD:EE:FF",
                                     service="180F", char="2A19")
    ledger.on_cmd_ack("agent-1", {"requestId": rid3, "errorCode": 1003})
    assert ledger.stats()["throttled"] == 2


async def test_wait_ack_success(ledger, session):
    rid = await ledger.send_command(session, "GET_STATUS")

    async def reply() -> None:
        await asyncio.sleep(0.02)
        ledger.on_cmd_ack("agent-1", {"requestId": rid, "errorCode": 0,
                                      "result": {"slotsUsed": 1}})

    asyncio.create_task(reply())
    result = await ledger.wait_ack(rid, timeout=1.0)
    assert result["errorCode"] == 0
    assert result["ledgerStatus"] == "acked"
    assert result["result"] == {"slotsUsed": 1}


async def test_wait_ack_timeout_raises(ledger, session):
    await ledger.send_command(session, "GET_STATUS")
    with pytest.raises(asyncio.TimeoutError):
        # 等 30ms，小于 ack_timeout_ms=50ms，wait_for 先超时
        await ledger.wait_ack(
            session.sent[0]["requestId"], timeout=0.03)


async def test_wait_ack_unknown_id(ledger):
    with pytest.raises(KeyError):
        await ledger.wait_ack("no-such-id", timeout=0.01)


async def test_wait_ack_resolves_failed_after_retries(ledger, session):
    rid = await ledger.send_command(session, "RESET")
    result = await ledger.wait_ack(rid, timeout=2.0)
    assert result["ledgerStatus"] == "failed"
    assert result["errorCode"] == ACK_TIMEOUT_CODE


async def test_ack_listener_hook(ledger, session):
    seen: list[tuple[str, dict]] = []

    async def listener(agent_id: str, info: dict) -> None:
        seen.append((agent_id, info))

    ledger.add_ack_listener(listener)
    rid = await ledger.send_command(session, "GET_STATUS")
    ledger.on_cmd_ack("agent-1", {"requestId": rid, "errorCode": 0,
                                  "result": {"a": 1}})
    await asyncio.sleep(0.05)  # listener 以 task 异步通知
    assert len(seen) == 1
    agent_id, info = seen[0]
    assert agent_id == "agent-1"
    assert info["type"] == "GET_STATUS" and info["result"] == {"a": 1}
