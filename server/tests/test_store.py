"""Store 补充方法单测：upsert_agent / insert_event / query_events。"""

import pytest

from wireless_server.configsvc import Store


@pytest.fixture
async def store(tmp_path):
    s = Store(tmp_path / "test.db")
    await s.init()
    yield s
    await s.close()


async def test_upsert_agent_roundtrip(store):
    await store.upsert_agent("agent-1", {"ip": "10.0.0.1", "agentVersion": "1.0"})
    profile = await store.get_agent("agent-1")
    assert profile["agentId"] == "agent-1"
    assert profile["info"]["ip"] == "10.0.0.1"
    assert profile["registeredTs"] > 0

    # 重复注册整项替换
    await store.upsert_agent("agent-1", {"ip": "10.0.0.2", "agentVersion": "1.1"})
    profile = await store.get_agent("agent-1")
    assert profile["info"]["ip"] == "10.0.0.2"

    assert await store.get_agent("nope") is None


async def test_insert_and_query_events(store):
    for i in range(5):
        await store.insert_event("agent-1", "HEARTBEAT", 1000 + i,
                                 {"slotsUsed": i})
    await store.insert_event("agent-1", "DEVICE_STATE", 2000,
                             {"deviceMac": "AA:BB:CC:DD:EE:FF", "state": "READY"})
    await store.insert_event("agent-2", "HEARTBEAT", 3000, {"slotsUsed": 9})

    # 默认：id 倒序取新
    rows = await store.query_events()
    assert len(rows) == 7
    assert rows[0]["type"] == "HEARTBEAT" and rows[0]["agentId"] == "agent-2"
    assert [r["id"] for r in rows] == sorted((r["id"] for r in rows), reverse=True)

    # agent_id 过滤
    rows = await store.query_events(agent_id="agent-1")
    assert len(rows) == 6 and all(r["agentId"] == "agent-1" for r in rows)

    # type 过滤 + limit
    rows = await store.query_events(agent_id="agent-1", type="HEARTBEAT", limit=2)
    assert len(rows) == 2
    assert rows[0]["payload"]["slotsUsed"] == 4  # 最新在前
    assert rows[1]["payload"]["slotsUsed"] == 3


async def test_query_events_empty(store):
    assert await store.query_events(agent_id="ghost") == []
