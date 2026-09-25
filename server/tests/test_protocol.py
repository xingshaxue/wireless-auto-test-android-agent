"""protocol 包单测：19 条命令序列化 / 18 类事件解析 / 错误码表。"""

import base64

import pytest

from wireless_server.protocol import (
    COMMAND_TYPES,
    EVENT_TYPES,
    UnknownEvent,
    build_command,
    errors,
    parse_event,
)
from wireless_server.protocol.commands import Envelope
from wireless_server.protocol.events import EventEnvelope

MAC = "AA:BB:CC:DD:EE:FF"
ENVELOPE_KEYS = {"type", "timestamp", "requestId"}


def _wire(cmd: Envelope) -> dict:
    w = cmd.to_wire()
    # 公共信封三件套必须齐全（附录 A.1）
    assert ENVELOPE_KEYS <= set(w)
    assert isinstance(w["timestamp"], int) and w["timestamp"] > 0
    assert isinstance(w["requestId"], str) and w["requestId"]
    return w


# ---------------- 19 条命令逐字段序列化 ----------------

def test_all_19_commands_registered():
    assert len(COMMAND_TYPES) == 19
    assert set(COMMAND_TYPES) == {
        "REGISTER_ACK", "CONNECT_DEVICE", "DISCONNECT_DEVICE", "REMOVE_DEVICE",
        "RESUME_DEVICE", "START_POLLING", "STOP_POLLING", "READ_CHAR",
        "WRITE_CHAR", "SET_POLLING_INTERVAL", "SET_POLL_RULES", "FILE_TRANSFER",
        "FILE_CANCEL", "SET_MAX_CONNECTIONS", "SET_PERSISTENT_DEVICE",
        "PAUSE_DEVICE", "UPLOAD_LOG", "GET_STATUS", "RESET",
    }


def test_register_ack():
    w = _wire(build_command("REGISTER_ACK", errorCode=0, config={"configVersion": 3}))
    assert w["type"] == "REGISTER_ACK"
    assert w["errorCode"] == 0
    assert w["config"] == {"configVersion": 3}
    # 拒绝时 config 可缺省，None 不出现在线上报文
    w2 = _wire(build_command("REGISTER_ACK", errorCode=2001))
    assert "config" not in w2


def test_connect_device_defaults_lazy_connect():
    w = _wire(build_command("CONNECT_DEVICE", deviceMac=MAC))
    assert w["type"] == "CONNECT_DEVICE"
    assert w["deviceMac"] == MAC
    assert w["lazyConnect"] is False  # 默认值生效
    assert "deviceId" not in w
    w2 = _wire(build_command("CONNECT_DEVICE", deviceMac=MAC, deviceId="dut-001",
                             lazyConnect=True))
    assert w2["deviceId"] == "dut-001" and w2["lazyConnect"] is True


@pytest.mark.parametrize("ctype", [
    "DISCONNECT_DEVICE", "REMOVE_DEVICE", "RESUME_DEVICE",
    "START_POLLING", "STOP_POLLING",
])
def test_simple_mac_commands(ctype):
    w = _wire(build_command(ctype, deviceMac=MAC))
    assert w["type"] == ctype
    assert set(w) == ENVELOPE_KEYS | {"deviceMac"}


def test_read_char():
    w = _wire(build_command("READ_CHAR", deviceMac=MAC, service="180F", char="2A19"))
    assert set(w) == ENVELOPE_KEYS | {"deviceMac", "service", "char"}
    w2 = _wire(build_command("READ_CHAR", deviceMac=MAC, service="180F", char="2A19",
                             timeoutMs=5000))
    assert w2["timeoutMs"] == 5000


def test_write_char_base64_and_default_write_type():
    payload = base64.b64encode(b"\x01\x02").decode()
    w = _wire(build_command("WRITE_CHAR", deviceMac=MAC, service="180F", char="2A19",
                            payload=payload))
    assert w["payload"] == payload
    assert base64.b64decode(w["payload"]) == b"\x01\x02"  # byte[] → base64（附录 A.1）
    assert w["writeType"] == "WITH_RESPONSE"  # 默认值生效
    w2 = _wire(build_command("WRITE_CHAR", deviceMac=MAC, service="180F", char="2A19",
                             payload=payload, writeType="NO_RESPONSE"))
    assert w2["writeType"] == "NO_RESPONSE"


def test_set_polling_interval():
    w = _wire(build_command("SET_POLLING_INTERVAL", deviceMac=MAC, intervalMs=5000))
    assert set(w) == ENVELOPE_KEYS | {"deviceMac", "intervalMs"}
    assert w["intervalMs"] == 5000


def test_set_poll_rules():
    rules = [{"ruleId": "r1", "priority": 10, "stopOnMatch": False,
              "conditions": [{"field": "temperature", "op": "GT", "value": 40}],
              "actions": [{"type": "REPORT_EVENT", "params": {"event": "TEMP_CRITICAL"}}]}]
    w = _wire(build_command("SET_POLL_RULES", deviceMac=MAC, rules=rules))
    assert w["rules"] == rules


def test_file_transfer_default_window_size():
    sha = base64.b64encode(b"\x00" * 32).decode()
    w = _wire(build_command("FILE_TRANSFER", taskId="t1", fileId="fw.bin",
                            deviceMac=MAC, size=10240, sha256=sha))
    assert w["windowSize"] == 64  # 默认值生效
    assert "chunkSize" not in w   # 缺省 = MTU-3，由 Agent 决定
    assert base64.b64decode(w["sha256"]) == b"\x00" * 32
    w2 = _wire(build_command("FILE_TRANSFER", taskId="t1", fileId="fw.bin",
                             deviceMac=MAC, size=1, sha256=sha,
                             windowSize=8, chunkSize=244))
    assert w2["windowSize"] == 8 and w2["chunkSize"] == 244


def test_file_cancel():
    w = _wire(build_command("FILE_CANCEL", taskId="task-abc"))
    assert set(w) == ENVELOPE_KEYS | {"taskId"}


def test_set_max_connections():
    w = _wire(build_command("SET_MAX_CONNECTIONS", maxSlots=5))
    assert set(w) == ENVELOPE_KEYS | {"maxSlots"}


def test_set_persistent_device():
    w = _wire(build_command("SET_PERSISTENT_DEVICE", deviceMac=MAC, on=True))
    assert w["on"] is True


def test_pause_device_default_abort_transfer():
    w = _wire(build_command("PAUSE_DEVICE", deviceMac=MAC))
    assert w["abortTransfer"] is False  # 默认值生效（false = 挂起传输，7.7）


def test_upload_log():
    w = _wire(build_command("UPLOAD_LOG"))
    assert set(w) == ENVELOPE_KEYS
    w2 = _wire(build_command("UPLOAD_LOG", sinceTs=1700000000000, minLevel="WARN"))
    assert w2["sinceTs"] == 1700000000000 and w2["minLevel"] == "WARN"


def test_get_status_optional_mac():
    w = _wire(build_command("GET_STATUS"))
    assert "deviceMac" not in w  # 缺省 = 整机 + 全部设备
    w2 = _wire(build_command("GET_STATUS", deviceMac=MAC))
    assert w2["deviceMac"] == MAC


def test_reset_no_extra_fields():
    w = _wire(build_command("RESET"))
    assert set(w) == ENVELOPE_KEYS


def test_build_command_unknown_type():
    with pytest.raises(ValueError, match="未知命令"):
        build_command("NO_SUCH_COMMAND")


def test_build_command_explicit_request_id():
    w = _wire(build_command("GET_STATUS", requestId="fixed-rid", timestamp=123))
    assert w["requestId"] == "fixed-rid" and w["timestamp"] == 123


def test_command_rejects_extra_fields():
    with pytest.raises(Exception):
        build_command("GET_STATUS", typoField=1)


# ---------------- 18 类事件 parse_event 往返 ----------------

def test_all_20_event_types_registered():
    assert len(EVENT_TYPES) == 20  # 18 类，SLOT_ACQUIRED/RELEASED 与 PAUSED/RESUMED 为独立 type


def _roundtrip(raw: dict) -> dict:
    ev = parse_event(raw)
    assert isinstance(ev, EventEnvelope)
    assert ev.type == raw["type"]
    return ev.model_dump(exclude_none=True)


def test_parse_register():
    raw = {"type": "REGISTER", "timestamp": 1, "deviceId": "phone-1",
           "ip": "192.168.1.2", "port": 10409, "androidSdk": 34,
           "bleSupported": True, "maxConnections": 5, "agentVersion": "1.0",
           "token": "tok"}
    assert _roundtrip(raw) == raw


def test_parse_register_without_token():
    # token 已改为可选历史字段（A.3）：新 agent 不再携带
    raw = {"type": "REGISTER", "timestamp": 1, "deviceId": "phone-1",
           "ip": "192.168.1.2", "port": 10409, "androidSdk": 34,
           "bleSupported": True, "maxConnections": 5, "agentVersion": "1.0"}
    assert _roundtrip(raw) == raw


def test_parse_heartbeat():
    raw = {"type": "HEARTBEAT", "timestamp": 1, "cpuPercent": 12, "memAvailMb": 512,
           "slotsUsed": 2, "slotsTotal": 3, "devicesManaged": 10, "devicesReady": 8}
    assert _roundtrip(raw) == raw
    minimal = {"type": "HEARTBEAT", "timestamp": 1, "slotsUsed": 0, "slotsTotal": 3,
               "devicesManaged": 0, "devicesReady": 0}
    assert _roundtrip(minimal) == minimal  # cpuPercent/memAvailMb 可选


def test_parse_device_state():
    raw = {"type": "DEVICE_STATE", "timestamp": 1, "deviceMac": MAC,
           "state": "READY", "errorCode": 1001, "rawStatus": 133}
    assert _roundtrip(raw) == raw
    minimal = {"type": "DEVICE_STATE", "timestamp": 1, "deviceMac": MAC, "state": "POLLING"}
    assert _roundtrip(minimal) == minimal


@pytest.mark.parametrize("etype", ["CONNECTION_SLOT_ACQUIRED", "CONNECTION_SLOT_RELEASED"])
def test_parse_slot_events(etype):
    raw = {"type": etype, "timestamp": 1, "deviceMac": MAC, "slotId": 1, "reason": "POLL"}
    assert _roundtrip(raw) == raw


def test_parse_poll_result():
    raw = {"type": "POLL_RESULT", "timestamp": 1, "deviceMac": MAC, "stale": False,
           "values": {"battery": 85, "temperature": 36.5, "status": "OK"}}
    assert _roundtrip(raw) == raw


def test_parse_poll_data_stale_no_error_code():
    """POLL_DATA_STALE 属状态提示而非错误，不携带 errorCode（§12.9）。"""
    raw = {"type": "POLL_DATA_STALE", "timestamp": 1, "deviceMac": MAC,
           "lastPollTime": 999, "reason": "NO_SLOT"}
    assert _roundtrip(raw) == raw
    assert "errorCode" not in EVENT_TYPES["POLL_DATA_STALE"].model_fields


def test_parse_cmd_ack():
    raw = {"type": "CMD_ACK", "timestamp": 1, "requestId": "rid-1", "errorCode": 0,
           "rawStatus": 0,
           "result": {"slotsUsed": 2, "slotsTotal": 3, "devices": [
               {"deviceMac": MAC, "state": "READY", "lastPollTime": 1,
                "pollDataStale": False, "queueDepth": 0, "stateFlag": 0}]}}
    assert _roundtrip(raw) == raw
    minimal = {"type": "CMD_ACK", "timestamp": 1, "requestId": "rid-1", "errorCode": 3001}
    assert _roundtrip(minimal) == minimal


@pytest.mark.parametrize("etype", ["DEVICE_PAUSED", "DEVICE_RESUMED"])
def test_parse_pause_resume(etype):
    raw = {"type": etype, "timestamp": 1, "deviceMac": MAC}
    assert _roundtrip(raw) == raw


def test_parse_connection_statistics():
    raw = {"type": "CONNECTION_STATISTICS", "timestamp": 1, "slotsUsed": 2,
           "slotsTotal": 3, "switchCount": 12345, "gattFailureRate": 0.05,
           "avgPollMs": 800}
    assert _roundtrip(raw) == raw


def test_parse_file_progress():
    raw = {"type": "FILE_PROGRESS", "timestamp": 1, "taskId": "t1",
           "percent": 55.5, "bytesPerSec": 204800}
    assert _roundtrip(raw) == raw


def test_parse_file_result():
    raw = {"type": "FILE_RESULT", "timestamp": 1, "taskId": "t1", "errorCode": 4001,
           "rawStatus": 7, "detail": "hash mismatch"}
    assert _roundtrip(raw) == raw


def test_parse_error_event():
    raw = {"type": "ERROR", "timestamp": 1, "errorCode": 3004, "message": "磁盘不足",
           "rawStatus": 28, "deviceMac": MAC}
    assert _roundtrip(raw) == raw
    minimal = {"type": "ERROR", "timestamp": 1, "errorCode": 0, "message": "m"}
    assert _roundtrip(minimal) == minimal  # deviceMac/rawStatus 可选


def test_parse_topology():
    raw = {"type": "TOPOLOGY", "timestamp": 1, "connections": [
        {"deviceMac": MAC, "slotId": 0, "state": "READY", "persistent": True,
         "pinned": False},
        {"deviceMac": "11:22:33:44:55:66", "slotId": 1, "state": "POLLING",
         "persistent": False, "pinned": True},
    ]}
    assert _roundtrip(raw) == raw


def test_parse_file_request():
    raw = {"type": "FILE_REQUEST", "timestamp": 1, "fileId": "fw.bin", "taskId": "t1",
           "deviceMac": MAC}
    assert _roundtrip(raw) == raw


def test_parse_file_download_ready():
    raw = {"type": "FILE_DOWNLOAD_READY", "timestamp": 1, "taskId": "t1", "fileId": "fw.bin"}
    assert _roundtrip(raw) == raw


def test_parse_file_download_ack():
    raw = {"type": "FILE_DOWNLOAD_ACK", "timestamp": 1, "taskId": "t1",
           "resendSeqs": [3, 7]}
    assert _roundtrip(raw) == raw
    raw_ok = {"type": "FILE_DOWNLOAD_ACK", "timestamp": 1, "taskId": "t1", "resendSeqs": []}
    assert _roundtrip(raw_ok) == raw_ok  # 空数组 = 全部成功（16.3）


def test_parse_file_download_resume():
    raw = {"type": "FILE_DOWNLOAD_RESUME", "timestamp": 1, "taskId": "t1", "lastSeq": 42}
    assert _roundtrip(raw) == raw


def test_parse_log_upload_done():
    raw = {"type": "LOG_UPLOAD_DONE", "timestamp": 1, "requestId": "rid-9",
           "errorCode": 0, "size": 1048576}
    assert _roundtrip(raw) == raw


def test_parse_unknown_event_returns_unknown_not_raise():
    raw = {"type": "FUTURE_EVENT_V2", "timestamp": 5, "newField": {"nested": [1, 2]},
           "another": "x"}
    ev = parse_event(raw)
    assert isinstance(ev, UnknownEvent)
    assert ev.type == "FUTURE_EVENT_V2"
    assert ev.newField == {"nested": [1, 2]}  # 未知字段完整保留
    assert ev.another == "x"


def test_parse_event_ignores_agent_extra_fields():
    """Agent 未来在已知事件上新增字段 → 忽略不抛错。"""
    raw = {"type": "HEARTBEAT", "timestamp": 1, "slotsUsed": 0, "slotsTotal": 3,
           "devicesManaged": 0, "devicesReady": 0, "futureField": True}
    ev = parse_event(raw)
    assert ev.type == "HEARTBEAT"


# ---------------- 错误码 ----------------

def test_error_code_values():
    assert errors.SUCCESS == 0
    assert errors.GattErrorCode.CONNECT_TIMEOUT == 1001
    assert errors.GattErrorCode.SERVICE_DISCOVERY_FAILED == 1002
    assert errors.GattErrorCode.CHAR_NOT_FOUND == 1003
    assert errors.ProtocolErrorCode.BAD_MESSAGE == 2001
    assert errors.ProtocolErrorCode.UNKNOWN_COMMAND == 2002
    assert errors.ProtocolErrorCode.DEVICE_NOT_FOUND == 2003
    assert errors.ProtocolErrorCode.COMMAND_CANCELLED == 2004
    assert errors.ResourceErrorCode.QUEUE_FULL == 3001
    assert errors.ResourceErrorCode.COMMAND_EXPIRED == 3002
    assert errors.ResourceErrorCode.SLOT_INSUFFICIENT == 3003
    assert errors.ResourceErrorCode.DISK_FULL == 3004
    assert errors.FileErrorCode.HASH_MISMATCH == 4001
    assert errors.FileErrorCode.CRC_FAILED == 4002
    assert errors.FileErrorCode.DUT_WRITE_REJECTED == 4003
    assert errors.FileErrorCode.OFFSET_WRITE_UNSUPPORTED == 4004


def test_is_throttle_error():
    assert errors.is_throttle_error(3001)
    assert errors.is_throttle_error(3002)
    assert errors.is_throttle_error(errors.ResourceErrorCode.QUEUE_FULL)
    for code in (0, 1001, 2004, 3003, 3004, 4001):
        assert not errors.is_throttle_error(code)


def test_error_category():
    assert errors.error_category(0) == "SUCCESS"
    assert errors.error_category(1333) == "GATT"
    assert errors.error_category(2002) == "PROTOCOL"
    assert errors.error_category(3003) == "RESOURCE"
    assert errors.error_category(4004) == "FILE"
    assert errors.error_category(9999) == "UNKNOWN"


def test_topology_without_slot_id_and_pinned():
    """真机 agent 对无槽位设备省略 slotId/pinned（联调发现），按可选解析。"""
    from wireless_server.protocol.events import Topology, parse_event

    raw = {"type": "TOPOLOGY", "timestamp": 1, "connections": [
        {"deviceMac": "AA:BB:CC:DD:EE:01", "state": "READY", "persistent": False}]}
    evt = parse_event(raw)
    assert isinstance(evt, Topology)
    assert evt.connections[0].slotId is None and evt.connections[0].pinned is None


def test_ingest_bad_event_does_not_raise():
    """坏消息不拆会话：校验失败记告警、原文入库、视图不受影响（真机联调回归）。"""
    import asyncio

    from wireless_server.configsvc.store import Store
    from wireless_server.ingest import EventIngest

    async def main():
        store = Store(":memory:")
        await store.init()
        ing = EventIngest(store)
        bad = {"type": "TOPOLOGY", "timestamp": 1,
               "connections": [{"state": "READY"}]}  # 缺 deviceMac/persistent
        await ing.handle_event("a1", bad)  # 不应抛异常
        events = await store.query_events(agent_id="a1")
        assert len(events) == 1 and events[0]["type"] == "TOPOLOGY"
        await ing.handle_event("a1", {"type": "HEARTBEAT", "timestamp": 2,
                                      "slotsUsed": 1, "slotsTotal": 3,
                                      "devicesManaged": 1, "devicesReady": 1})
        assert ing.agent_view("a1")["slots"]["slotsUsed"] == 1
        await store.close()

    asyncio.run(main())
