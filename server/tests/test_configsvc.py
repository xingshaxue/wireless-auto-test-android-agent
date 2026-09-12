"""configsvc 单测：Store 持久化 / ConfigManager 装配 / validation 规则（SDD §16.4 / §6.4）。"""

import pytest

from wireless_server.configsvc import DEFAULT_GLOBALS, ConfigManager, Store, validation

MAC = "AA:BB:CC:DD:EE:FF"
MAC2 = "11:22:33:44:55:66"
AGENT = "agent-01"


def _device(mac: str = MAC, **overrides) -> dict:
    """合法的最小设备配置样例（§16.4）。"""
    device = {
        "deviceId": "dut-001",
        "mac": mac,
        "type": "watch",
        "priority": 5,
        "persistent": False,
        "profile": {"2A19": "180F", "2A21": "180A"},
        "fields": {
            "battery": {"char": "2A19", "format": "uint8"},
            "temperature": {"char": "2A21", "format": "sint16",
                            "byteOrder": "LE", "scale": 0.1},
        },
        "polling": {
            "intervalMs": 60000,
            "readCharacteristics": ["2A19"],
            "notifyCharacteristics": ["2A19"],
            "reportOnlyChanged": True,
        },
        "rules": [
            {
                "ruleId": "r1",
                "priority": 10,
                "stopOnMatch": False,
                "conditions": [{"field": "temperature", "op": "GT", "value": 40}],
                "actions": [{"type": "REPORT_EVENT", "params": {"event": "TEMP_CRITICAL"}}],
            }
        ],
    }
    device.update(overrides)
    return device


@pytest.fixture
async def store(tmp_path):
    s = Store(tmp_path / "test.db")
    await s.init()
    yield s
    await s.close()


@pytest.fixture
async def manager(store):
    return ConfigManager(store)


# ---------------- Store：设备 CRUD 往返 ----------------

async def test_device_crud_roundtrip(store):
    assert await store.list_devices() == []
    assert await store.get_device(MAC) is None

    device = _device()
    await store.upsert_device(device)
    assert await store.get_device(MAC) == device
    assert await store.list_devices() == [device]

    # upsert 同 mac 整项替换
    updated = _device(priority=9, persistent=True)
    await store.upsert_device(updated)
    assert await store.get_device(MAC) == updated
    assert len(await store.list_devices()) == 1

    await store.upsert_device(_device(mac=MAC2))
    assert len(await store.list_devices()) == 2

    assert await store.delete_device(MAC) is True
    assert await store.delete_device(MAC) is False
    assert await store.get_device(MAC) is None


async def test_store_persistent_roundtrip(store, tmp_path):
    """关库重开后数据仍在（WAL 落盘）。"""
    path = tmp_path / "persist.db"
    s1 = Store(path)
    await s1.init()
    await s1.upsert_device(_device())
    await s1.set_global_params({"maxSlots": 5})
    await s1.next_config_version(AGENT)
    await s1.close()

    s2 = Store(path)
    await s2.init()
    assert await s2.get_device(MAC) == _device()
    assert await s2.get_global_params() == {"maxSlots": 5}
    assert await s2.current_config_version(AGENT) == 1
    await s2.close()


async def test_global_params_empty(store):
    assert await store.get_global_params() == {}


async def test_config_version_atomic_bump(store):
    # 首值为 1，逐次 +1；未 bump 前 current 为 0
    assert await store.current_config_version(AGENT) == 0
    assert await store.next_config_version(AGENT) == 1
    assert await store.next_config_version(AGENT) == 2
    assert await store.current_config_version(AGENT) == 2
    # agent 之间互不影响
    assert await store.next_config_version("agent-02") == 1


async def test_store_requires_init():
    s = Store()
    with pytest.raises(RuntimeError):
        await s.list_devices()


# ---------------- ConfigManager：build_config 装配 ----------------

async def test_build_config_defaults_and_version(manager):
    """缺省项按 §16.4 默认值补齐；configVersion 连续 build 单调递增 1,2,3…。"""
    c1 = await manager.build_config(AGENT)
    assert c1["configVersion"] == 1
    for key, default in DEFAULT_GLOBALS.items():
        assert c1[key] == default, key
    assert c1["devices"] == []

    c2 = await manager.build_config(AGENT)
    c3 = await manager.build_config(AGENT)
    assert (c2["configVersion"], c3["configVersion"]) == (2, 3)
    assert await manager.current_version(AGENT) == 3


async def test_build_config_stored_params_override_defaults(manager):
    await manager.set_global_params({"maxSlots": 4, "timeSliceMs": 3000})
    config = await manager.build_config(AGENT)
    assert config["maxSlots"] == 4
    assert config["timeSliceMs"] == 3000
    assert config["cooldownMs"] == 5000  # 未覆盖项仍是默认值


async def test_build_config_includes_devices(manager):
    await manager.upsert_device(_device())
    config = await manager.build_config(AGENT)
    assert config["devices"] == [_device()]


async def test_build_config_clamps_tick(manager):
    """tickIntervalMs > 最小 intervalMs/2 → 下发配置中钳制（§16.4 硬约束）。"""
    await manager.upsert_device(_device())  # intervalMs=60000 → 上限 30000
    await manager.set_global_params({"tickIntervalMs": 60000})
    config = await manager.build_config(AGENT)
    assert config["tickIntervalMs"] == 30000
    # 钳制只影响下发，不回写存储
    assert (await manager.get_global_params())["tickIntervalMs"] == 60000


async def test_upsert_device_rejects_invalid(manager):
    with pytest.raises(ValueError, match="mac 格式非法"):
        await manager.upsert_device(_device(mac="aa-bb-cc-dd-ee-ff"))
    assert await manager.get_device(MAC) is None


# ---------------- validation：设备规则 ----------------

def test_validate_device_ok():
    assert validation.validate_device(_device()) == []


def test_validate_device_mac_format():
    for bad in ["aa:bb:cc:dd:ee:ff", "AABBCCDDEEFF", "AA:BB:CC:DD:EE", ""]:
        errors = validation.validate_device(_device(mac=bad))
        assert any("mac" in e for e in errors), bad


def test_validate_device_required_fields():
    errors = validation.validate_device({"mac": MAC})
    assert any("deviceId" in e for e in errors)


def test_validate_device_format_vocab():
    device = _device(fields={"x": {"char": "2A19", "format": "float64"}})
    assert any("format 非法" in e for e in validation.validate_device(device))
    for ok in ["uint8", "uint16", "uint32", "sint8", "sint16", "sint32", "utf8", "bool", "hex"]:
        device = _device(fields={"x": {"char": "2A19", "format": ok}}, rules=[])
        assert validation.validate_device(device) == []


def test_validate_device_interval_floor():
    # readCharacteristics 非空时 intervalMs ≥ 200（§16.4）
    device = _device(polling={"intervalMs": 199, "readCharacteristics": ["2A19"]})
    assert any("intervalMs" in e for e in validation.validate_device(device))
    device = _device(polling={"intervalMs": 200, "readCharacteristics": ["2A19"]})
    assert validation.validate_device(device) == []
    # readCharacteristics 为空时不受 200ms 下限约束
    device = _device(polling={"intervalMs": 100, "readCharacteristics": []})
    assert validation.validate_device(device) == []


def test_validate_device_rule_op_vocab():
    device = _device(rules=[{"ruleId": "r1", "conditions": [
        {"field": "battery", "op": "LIKE", "value": 1}], "actions": []}])
    assert any("op 非法" in e for e in validation.validate_device(device))


def test_validate_device_rule_field_mapping_required():
    """conditions[].field 必须在 fields 有映射，缺失 = 配置错误拒绝（§16.4/8.9）。"""
    device = _device(rules=[{"ruleId": "r1", "conditions": [
        {"field": "ghost", "op": "GT", "value": 1}], "actions": []}])
    errors = validation.validate_device(device)
    assert any("无映射" in e and "ghost" in e for e in errors)


def test_validate_device_between_value():
    rules = [{"ruleId": "r1", "actions": [], "conditions": [
        {"field": "temperature", "op": "BETWEEN", "value": [10, 40]}]}]
    assert validation.validate_device(_device(rules=rules)) == []
    for bad in [[10], [10, 20, 30], "10-40", [10, "x"]]:
        rules[0]["conditions"][0]["value"] = bad
        errors = validation.validate_device(_device(rules=rules))
        assert any("BETWEEN" in e for e in errors), bad


# ---------------- validation：全局参数 ----------------

def test_validate_globals_ranges():
    assert validation.validate_globals(dict(DEFAULT_GLOBALS)) == []
    assert any("maxSlots" in e for e in validation.validate_globals({"maxSlots": 1}))
    assert any("maxSlots" in e for e in validation.validate_globals({"maxSlots": 6}))
    assert validation.validate_globals({"maxSlots": 2}) == []
    assert validation.validate_globals({"maxSlots": 5}) == []
    assert any("timeSliceMs" in e for e in validation.validate_globals({"timeSliceMs": 0}))
    assert any("必须是整数" in e for e in validation.validate_globals({"maxSlots": 3.5}))
    assert any("未知" in e for e in validation.validate_globals({"foo": 1}))


async def test_set_global_params_rejects_invalid(manager):
    with pytest.raises(ValueError, match="maxSlots"):
        await manager.set_global_params({"maxSlots": 9})
    assert await manager.get_global_params() == DEFAULT_GLOBALS  # 未写入


# ---------------- validation：tick 钳制与一轮时长 ----------------

def test_clamp_tick_interval():
    devices = [_device(polling={"intervalMs": 400, "readCharacteristics": ["2A19"]})]
    params, warnings = validation.clamp_tick_interval({"tickIntervalMs": 100}, devices)
    assert params["tickIntervalMs"] == 100 and warnings == []

    params, warnings = validation.clamp_tick_interval({"tickIntervalMs": 500}, devices)
    assert params["tickIntervalMs"] == 200  # 钳制到 最小 intervalMs/2
    assert len(warnings) == 1 and "钳制" in warnings[0]

    # 无设备 → 不钳制
    params, warnings = validation.clamp_tick_interval({"tickIntervalMs": 9999}, [])
    assert params["tickIntervalMs"] == 9999 and warnings == []


def test_estimate_full_round_ms():
    """§6.4 公式：ceil((总数−常驻)/动态槽) × (setupBudgetMs + timeSliceMs)。"""
    params = {"maxSlots": 3, "setupBudgetMs": 4000, "timeSliceMs": 2000}
    # 20 台非常驻 → ceil(20/3) × 6000 = 7 × 6000
    devices = [_device(mac=f"AA:BB:CC:DD:EE:{i:02X}") for i in range(20)]
    assert validation.estimate_full_round_ms(params, devices) == 7 * 6000
    # 含 2 台常驻 → 动态 18 台、动态槽 1 → 18 × 6000
    devices[0]["persistent"] = True
    devices[1]["persistent"] = True
    assert validation.estimate_full_round_ms(params, devices) == 18 * 6000
    # 全部常驻 → 无动态轮询，0
    for d in devices:
        d["persistent"] = True
    assert validation.estimate_full_round_ms(params, devices) == 0


def test_check_interval_warnings():
    params = {"maxSlots": 2, "setupBudgetMs": 4000, "timeSliceMs": 2000}
    # 3 台非常驻、2 槽 → 一轮 = ceil(3/2) × 6000 = 12000
    devices = [
        _device(mac=MAC, polling={"intervalMs": 5000, "readCharacteristics": ["2A19"]}),
        _device(mac=MAC2, polling={"intervalMs": 60000, "readCharacteristics": ["2A19"]}),
        _device(mac="00:11:22:33:44:55"),
    ]
    devices[2]["polling"] = {"intervalMs": 30000, "readCharacteristics": ["2A19"]}
    warnings = validation.check_interval_warnings(params, devices)
    assert len(warnings) == 1 and MAC in warnings[0]  # 仅 5000 < 12000 的设备


# ---------------- 运行中更新：返回命令字段 dict ----------------

async def test_set_polling_interval(manager):
    await manager.upsert_device(_device())
    fields = await manager.set_polling_interval(MAC, 5000)
    assert fields == {"deviceMac": MAC, "intervalMs": 5000}  # SET_POLLING_INTERVAL（A.2）
    assert (await manager.get_device(MAC))["polling"]["intervalMs"] == 5000
    # 越下限拒绝且不写库
    with pytest.raises(ValueError):
        await manager.set_polling_interval(MAC, 100)
    assert (await manager.get_device(MAC))["polling"]["intervalMs"] == 5000
    with pytest.raises(KeyError):
        await manager.set_polling_interval(MAC2, 5000)


async def test_set_poll_rules(manager):
    await manager.upsert_device(_device())
    new_rules = [{"ruleId": "r2", "priority": 1, "stopOnMatch": True,
                  "conditions": [{"field": "battery", "op": "LT", "value": 20}],
                  "actions": [{"type": "SET_INTERVAL", "params": {"intervalMs": 1000}}]}]
    fields = await manager.set_poll_rules(MAC, new_rules)
    assert fields == {"deviceMac": MAC, "rules": new_rules}  # SET_POLL_RULES（A.2）
    assert (await manager.get_device(MAC))["rules"] == new_rules
    # 引用无映射字段的规则集整集拒绝（§16.4）
    bad_rules = [{"ruleId": "r3", "conditions": [{"field": "nope", "op": "EQ", "value": 1}],
                  "actions": []}]
    with pytest.raises(ValueError, match="无映射"):
        await manager.set_poll_rules(MAC, bad_rules)
    assert (await manager.get_device(MAC))["rules"] == new_rules


async def test_set_persistent(manager):
    await manager.upsert_device(_device())
    fields = await manager.set_persistent(MAC, True)
    assert fields == {"deviceMac": MAC, "on": True}  # SET_PERSISTENT_DEVICE（A.2）
    assert (await manager.get_device(MAC))["persistent"] is True
    fields = await manager.set_persistent(MAC, False)
    assert fields == {"deviceMac": MAC, "on": False}


async def test_set_max_slots(manager):
    fields = await manager.set_max_slots(5)
    assert fields == {"maxSlots": 5}  # SET_MAX_CONNECTIONS（A.2）
    assert (await manager.get_global_params())["maxSlots"] == 5
    with pytest.raises(ValueError, match="maxSlots"):
        await manager.set_max_slots(6)
    assert (await manager.get_global_params())["maxSlots"] == 5


async def test_runtime_update_bumps_version_on_next_build(manager):
    """运行中更新是全局的：版本号在下次 build_config 时自然递增（§16.4）。"""
    await manager.upsert_device(_device())
    assert (await manager.build_config(AGENT))["configVersion"] == 1
    await manager.set_polling_interval(MAC, 30000)
    assert await manager.current_version(AGENT) == 1  # 更新本身不 bump
    config = await manager.build_config(AGENT)
    assert config["configVersion"] == 2
    assert config["devices"][0]["polling"]["intervalMs"] == 30000
