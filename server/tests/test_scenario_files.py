"""场景文件管理 API 测试（GET/PUT/DELETE /api/scenarios/{path}）。

用 monkeypatch 把 routes.SCENARIOS_DIR 指向 tmp_path 下的临时目录，
不污染仓库里的真实样例文件；真实样例 basic_connect_poll.json 拷入
临时目录作为"已存在场景"用例的素材。
"""

import json
import shutil
from pathlib import Path

import httpx
import pytest

from wireless_server.api import routes
from wireless_server.runtime import Runtime
from wireless_server.settings import Settings

REPO_SCENARIOS = Path(__file__).resolve().parents[1] / "scenarios"

VALID_SCENARIO = {
    "name": "编辑器新建场景",
    "description": "PUT 创建",
    "steps": [
        {"name": "暂停", "action": "delay", "ms": 100},
        {"name": "断言槽位", "action": "assertView",
         "path": "slots.slotsUsed", "expect": {"lte": 3}},
    ],
}


@pytest.fixture
def scenarios_dir(tmp_path, monkeypatch):
    """临时 scenarios 目录（含真实样例副本），替换 routes 模块内全局定位。"""
    d = tmp_path / "scenarios"
    d.mkdir()
    shutil.copy(REPO_SCENARIOS / "basic_connect_poll.json",
                d / "basic_connect_poll.json")
    monkeypatch.setattr(routes, "SCENARIOS_DIR", d)
    return d


@pytest.fixture
async def runtime(tmp_path, scenarios_dir):
    settings = Settings()
    settings.gateway.host = "127.0.0.1"
    settings.gateway.port = 0
    settings.api.host = "127.0.0.1"
    settings.api.port = 0
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
        trust_env=False,
    ) as client:
        yield client


# ---------------- GET 单文件 ----------------

async def test_get_scenario_ok(api):
    r = await api.get("/api/scenarios/basic_connect_poll.json")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["path"] == "basic_connect_poll.json"
    assert body["name"] == "基本连接与轮询"
    content = body["content"]
    assert content["name"] == "基本连接与轮询"
    assert isinstance(content["steps"], list) and len(content["steps"]) == 6
    assert content["steps"][0]["action"] == "command"


async def test_get_scenario_not_found(api):
    r = await api.get("/api/scenarios/nope.json")
    assert r.status_code == 404
    # 不带 .json 后缀同样 404
    r = await api.get("/api/scenarios/basic_connect_poll")
    assert r.status_code == 404


async def test_get_invalid_file_422(api, scenarios_dir):
    (scenarios_dir / "broken.json").write_text("{not json", encoding="utf-8")
    r = await api.get("/api/scenarios/broken.json")
    assert r.status_code == 422
    (scenarios_dir / "badmodel.json").write_text(
        json.dumps({"steps": []}), encoding="utf-8")  # 缺 name
    r = await api.get("/api/scenarios/badmodel.json")
    assert r.status_code == 422


async def test_path_traversal_blocked(api, scenarios_dir, tmp_path):
    """../、绝对路径、嵌套路径一律 404/400，文件系统无影响。"""
    secret = tmp_path / "secret.json"
    secret.write_text(json.dumps(VALID_SCENARIO), encoding="utf-8")
    outside = {p.name for p in tmp_path.iterdir()}

    for bad in ("..%2Fsecret.json", "..%2F..%2Fetc%2Fpasswd",
                "%2Fetc%2Fpasswd", "sub%2Fx.json"):
        assert (await api.get(f"/api/scenarios/{bad}")).status_code in (400, 404)
        r = await api.put(f"/api/scenarios/{bad}", json=VALID_SCENARIO)
        assert r.status_code in (400, 404), bad
        assert (await api.delete(f"/api/scenarios/{bad}")).status_code in (400, 404)

    # 目录外无新增文件，临时目录内也未写入任何逃逸副本；secret 未被改写
    assert {p.name for p in tmp_path.iterdir()} == outside
    assert [p.name for p in scenarios_dir.iterdir()] == ["basic_connect_poll.json"]
    assert json.loads(secret.read_text(encoding="utf-8")) == VALID_SCENARIO


# ---------------- PUT 创建/覆盖 ----------------

async def test_put_new_scenario_roundtrip(api, scenarios_dir):
    # path 不带 .json 后缀 → 自动补上
    r = await api.put("/api/scenarios/new_case", json=VALID_SCENARIO)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body == {"ok": True, "path": "new_case.json",
                    "name": "编辑器新建场景"}
    # 落盘：缩进 2、键序保持
    raw = (scenarios_dir / "new_case.json").read_text(encoding="utf-8")
    assert raw.startswith('{\n  "name"')
    assert json.loads(raw) == VALID_SCENARIO

    # GET 读回一致
    r = await api.get("/api/scenarios/new_case.json")
    assert r.status_code == 200
    assert r.json()["content"] == VALID_SCENARIO
    assert r.json()["name"] == "编辑器新建场景"

    # 列表出现
    listing = (await api.get("/api/scenarios")).json()
    assert {"name": "编辑器新建场景", "path": "new_case.json"} in listing


async def test_put_invalid_scenario_422_no_write(api, scenarios_dir):
    cases = {
        "bad_action.json": {"name": "x", "steps": [{"action": "explode"}]},
        "missing_field.json": {"name": "x", "steps": [{"action": "command"}]},
        "negative_timeout.json": {"name": "x", "steps": [
            {"action": "expect", "event": "E", "timeoutMs": -5}]},
        "missing_name.json": {"steps": []},
    }
    for fname, payload in cases.items():
        r = await api.put(f"/api/scenarios/{fname}", json=payload)
        assert r.status_code == 422, (fname, r.text)
        assert r.json()["detail"]
        assert not (scenarios_dir / fname).exists(), fname
    # body 不是 JSON 对象 → 422
    r = await api.put("/api/scenarios/list.json",
                      content=b"[1,2,3]",
                      headers={"Content-Type": "application/json"})
    assert r.status_code == 422
    assert not (scenarios_dir / "list.json").exists()


async def test_put_overwrite_existing(api, scenarios_dir):
    assert (await api.put("/api/scenarios/overwrite.json",
                          json=VALID_SCENARIO)).status_code == 200
    updated = dict(VALID_SCENARIO, name="覆盖后的场景",
                   steps=[{"action": "delay", "ms": 50}])
    r = await api.put("/api/scenarios/overwrite.json", json=updated)
    assert r.status_code == 200 and r.json()["name"] == "覆盖后的场景"
    content = (await api.get("/api/scenarios/overwrite.json")).json()["content"]
    assert content == updated
    assert json.loads((scenarios_dir / "overwrite.json")
                      .read_text(encoding="utf-8")) == updated


# ---------------- DELETE ----------------

async def test_delete_scenario(api, scenarios_dir):
    assert (await api.put("/api/scenarios/to_delete.json",
                          json=VALID_SCENARIO)).status_code == 200
    r = await api.delete("/api/scenarios/to_delete.json")
    assert r.status_code == 200
    assert r.json() == {"ok": True, "path": "to_delete.json"}
    assert not (scenarios_dir / "to_delete.json").exists()
    # 再 GET → 404；重复 DELETE → 404
    assert (await api.get("/api/scenarios/to_delete.json")).status_code == 404
    assert (await api.delete("/api/scenarios/to_delete.json")).status_code == 404


async def test_delete_not_found(api):
    assert (await api.delete("/api/scenarios/ghost.json")).status_code == 404
