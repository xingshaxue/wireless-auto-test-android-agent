"""REST 路由。全部挂在 /api 前缀下，鉴权由 app 层依赖注入。

错误码约定（新端点统一）：设备/文件/run 不存在 KeyError → 404；
校验/越界 ValueError → 400；ACK 超时 → 504；agent 不在线 → 409。
既有路由（agents/devices/globals/commands/events）保持原语义不变。
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from pathlib import Path
from typing import TYPE_CHECKING, Any

from fastapi import APIRouter, HTTPException, UploadFile
from pydantic import ValidationError

from ..engine.runner import AgentBusyError
from ..engine.scenario import Scenario, load_scenario

if TYPE_CHECKING:
    from ..runtime import Runtime

logger = logging.getLogger(__name__)

# 场景目录：<repo>/server/scenarios（本文件位于 src/wireless_server/api/ 下）
# 测试可 monkeypatch 本变量做目录隔离；函数内均按全局名延迟查找
SCENARIOS_DIR = Path(__file__).resolve().parents[3] / "scenarios"


def _scenario_file(path: str, *, append_suffix: bool = False) -> Path:
    """URL 路径参数 → scenarios 目录内的文件路径（防路径穿越）。

    仅允许目录内单层的 .json 文件：basename 必须与原始参数一致（拒绝
    ../、绝对路径、嵌套子目录），resolve 后再确认仍在 scenarios 目录内
    （防符号链接逃逸）。非法一律 404。
    """
    name = Path(path).name
    if not name or name != path:
        raise HTTPException(status_code=404, detail="非法场景路径")
    if append_suffix and not name.endswith(".json"):
        name += ".json"
    if not name.endswith(".json"):
        raise HTTPException(status_code=404, detail="仅支持 .json 场景文件")
    root = SCENARIOS_DIR.resolve()
    p = (root / name).resolve()
    if not p.is_relative_to(root):
        raise HTTPException(status_code=404, detail="非法场景路径")
    return p


def build_router(runtime: "Runtime") -> APIRouter:
    router = APIRouter()
    registry = runtime.registry
    ingest = runtime.ingest
    config = runtime.config_manager
    ledger = runtime.ledger
    store = runtime.store

    def _transfer():
        if runtime.transfer is None:
            raise HTTPException(status_code=503, detail="transfer 未装配")
        return runtime.transfer

    async def _send_and_wait(session, cmd_type: str, **fields: Any) -> dict:
        """下发命令并同步等待 ACK；超时/失败 → 504。"""
        request_id = await ledger.send_command(session, cmd_type, **fields)
        gw = runtime.settings.gateway
        wait_sec = gw.ack_timeout_ms * (gw.ack_max_retries + 1) / 1000.0 + 1.0
        try:
            result = await ledger.wait_ack(request_id, wait_sec)
        except asyncio.TimeoutError:
            raise HTTPException(status_code=504,
                                detail={"requestId": request_id,
                                        "error": "等待 CMD_ACK 超时"})
        if result["ledgerStatus"] != "acked":
            raise HTTPException(status_code=504, detail=result)
        return result

    def _owner_session(mac: str):
        """设备归属的在线 agent session（按 ingest 视图 devices 判定）。"""
        for aid in registry.online_ids():
            if mac in ingest.agent_view(aid).get("devices", {}):
                return registry.get(aid)
        return None

    def _known_offline_owner(mac: str) -> str | None:
        """视图里有该设备但当前不在线的 agent（用于 409 语义）。"""
        for aid, view in ingest.all_views().items():
            if mac in view.get("devices", {}) and registry.get(aid) is None:
                return aid
        return None

    async def _device_config_command(mac: str, method: str, cmd_type: str,
                                     *args: Any) -> dict[str, Any]:
        """运行中配置更新（§16.4）：写库 → 归属 agent 在线则下发并等 ACK。"""
        try:
            fields = await getattr(config, method)(*args)
        except KeyError as e:
            raise HTTPException(status_code=404, detail=str(e))
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        session = _owner_session(mac)
        if session is None:
            offline = _known_offline_owner(mac)
            if offline is not None:
                raise HTTPException(status_code=409,
                                    detail=f"设备归属 agent 不在线: {offline}")
            # 尚未归属任何 agent：仅配置生效，重连后随全量配置下发
            return {"ok": True, "dispatched": False, "fields": fields}
        result = await _send_and_wait(session, cmd_type, **fields)
        return {"ok": True, "dispatched": True, "result": result}

    # ---------------- Agent 状态 ----------------

    @router.get("/agents")
    async def list_agents() -> list[dict[str, Any]]:
        """registry 在线列表 + ingest 最后已知视图合并。"""
        views = ingest.all_views()
        agents: dict[str, dict[str, Any]] = {}
        for aid in set(views) | set(registry.online_ids()):
            agents[aid] = {"agentId": aid, "state": "offline", "view": views.get(aid)}
        for item in registry.list_agents():
            entry = agents.setdefault(item["agentId"], {"agentId": item["agentId"]})
            entry.update(item)
            entry.setdefault("view", views.get(item["agentId"]))
        return sorted(agents.values(), key=lambda a: a["agentId"])

    @router.get("/agents/{agent_id}/status")
    async def agent_status(agent_id: str) -> dict[str, Any]:
        session = registry.get(agent_id)
        profile = await store.get_agent(agent_id)
        view = ingest.agent_view(agent_id)
        if session is None and profile is None and not view["devices"] \
                and view["updated_ts"] == 0:
            raise HTTPException(status_code=404, detail="未知 agent")
        return {
            "agentId": agent_id,
            "state": "online" if session else "offline",
            "sessionId": session.session_id if session else None,
            "profile": profile,
            "configVersion": await config.current_version(agent_id),
            "view": view,
            "lastOfflineTs": registry.last_offline_ts(agent_id),
        }

    # ---------------- 命令台账 ----------------

    @router.get("/ledger")
    async def get_ledger() -> dict[str, Any]:
        """在途命令快照 + 累计统计（§8.4）。"""
        return {"pending": ledger.pending(), "stats": ledger.stats()}

    # ---------------- 设备配置（§16.4） ----------------

    @router.get("/devices")
    async def list_devices() -> list[dict[str, Any]]:
        return await config.list_devices()

    @router.put("/devices")
    async def put_device(device: dict[str, Any]) -> dict[str, Any]:
        try:
            await config.upsert_device(device)
        except ValueError as e:
            raise HTTPException(status_code=422, detail=str(e))
        return {"ok": True, "mac": device.get("mac")}

    @router.delete("/devices/{mac}")
    async def delete_device(mac: str) -> dict[str, Any]:
        # 先查归属（删库前），在线归属 agent 需同步 REMOVE_DEVICE（§7.7）
        session = _owner_session(mac)
        if not await config.delete_device(mac):
            raise HTTPException(status_code=404, detail="设备不存在")
        if session is None:
            # 归属 agent 离线或无归属：仅删配置
            return {"ok": True, "mac": mac, "dispatched": False, "result": None}
        try:
            result = await _send_and_wait(session, "REMOVE_DEVICE", deviceMac=mac)
        except HTTPException as e:
            # ACK 超时/失败（504）不阻断删除结果，错误透传在 result 里
            return {"ok": True, "mac": mac, "dispatched": True,
                    "result": {"error": e.detail}}
        return {"ok": True, "mac": mac, "dispatched": True, "result": result}

    @router.post("/devices/{mac}/polling-interval")
    async def set_polling_interval(mac: str, body: dict[str, Any]) -> dict[str, Any]:
        interval = body.get("intervalMs")
        if not isinstance(interval, int):
            raise HTTPException(status_code=422, detail="intervalMs 必须为整数")
        return await _device_config_command(
            mac, "set_polling_interval", "SET_POLLING_INTERVAL", mac, interval)

    @router.post("/devices/{mac}/rules")
    async def set_poll_rules(mac: str, body: dict[str, Any]) -> dict[str, Any]:
        rules = body.get("rules")
        if not isinstance(rules, list):
            raise HTTPException(status_code=422, detail="rules 必须为数组")
        return await _device_config_command(
            mac, "set_poll_rules", "SET_POLL_RULES", mac, rules)

    @router.post("/devices/{mac}/persistent")
    async def set_persistent(mac: str, body: dict[str, Any]) -> dict[str, Any]:
        on = body.get("on")
        if not isinstance(on, bool):
            raise HTTPException(status_code=422, detail="on 必须为布尔值")
        return await _device_config_command(
            mac, "set_persistent", "SET_PERSISTENT_DEVICE", mac, on)

    # ---------------- 全局参数（§16.4 顶层旋钮） ----------------

    @router.get("/config/globals")
    async def get_globals() -> dict[str, Any]:
        return await config.get_global_params()

    @router.put("/config/globals")
    async def put_globals(params: dict[str, Any]) -> dict[str, Any]:
        try:
            await config.set_global_params(params)
        except ValueError as e:
            raise HTTPException(status_code=422, detail=str(e))
        return {"ok": True}

    @router.post("/config/max-connections")
    async def set_max_connections(body: dict[str, Any]) -> dict[str, Any]:
        """SET_MAX_CONNECTIONS：写全局参数并广播全部在线 agent（等各自 ACK）。"""
        n = body.get("maxSlots")
        if not isinstance(n, int) or isinstance(n, bool):
            raise HTTPException(status_code=422, detail="maxSlots 必须为整数")
        try:
            fields = await config.set_max_slots(n)
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        acks = []
        for aid in registry.online_ids():
            session = registry.get(aid)
            if session is not None:
                acks.append(await _send_and_wait(
                    session, "SET_MAX_CONNECTIONS", **fields))
        return {"ok": True, "maxSlots": n, "acks": acks}

    # ---------------- 命令下发（台账 + 同步等待 ACK，§8.4） ----------------

    @router.post("/commands/{agent_id}")
    async def send_command(agent_id: str, body: dict[str, Any]) -> dict[str, Any]:
        session = registry.get(agent_id)
        if session is None:
            raise HTTPException(status_code=404, detail="agent 不在线")
        cmd_type = body.pop("type", None)
        if not cmd_type:
            raise HTTPException(status_code=422, detail="缺少 type 字段")
        try:
            request_id = await ledger.send_command(session, cmd_type, **body)
        except (ValueError, TypeError) as e:
            raise HTTPException(status_code=422, detail=f"命令字段非法: {e}")
        gw = runtime.settings.gateway
        wait_sec = gw.ack_timeout_ms * (gw.ack_max_retries + 1) / 1000.0 + 1.0
        try:
            result = await ledger.wait_ack(request_id, wait_sec)
        except asyncio.TimeoutError:
            raise HTTPException(status_code=504,
                                detail={"requestId": request_id,
                                        "error": "等待 CMD_ACK 超时"})
        if result["ledgerStatus"] != "acked":
            raise HTTPException(status_code=504, detail=result)
        return result

    # ---------------- 文件与传输（§7.6） ----------------

    @router.post("/files")
    async def upload_file(file: UploadFile) -> dict[str, Any]:
        """multipart 上传：落 files_dir（磁盘名加随机前缀防碰撞）并登记。"""
        transfer = _transfer()
        files_dir = Path(runtime.settings.storage.files_dir)
        files_dir.mkdir(parents=True, exist_ok=True)
        name = Path(file.filename or "upload.bin").name
        dest = files_dir / f"{uuid.uuid4().hex[:8]}_{name}"
        try:
            with open(dest, "wb") as fh:
                while chunk := await file.read(1 << 20):
                    fh.write(chunk)
            file_id = await transfer.register_file(dest, name=name)
        except ValueError as e:  # 配额超限等
            dest.unlink(missing_ok=True)
            raise HTTPException(status_code=400, detail=str(e))
        return await store.get_file(file_id)

    @router.get("/files")
    async def list_files() -> list[dict[str, Any]]:
        return await store.list_files()

    @router.delete("/files/{file_id}")
    async def delete_file(file_id: str) -> dict[str, Any]:
        record = await store.get_file(file_id)
        if record is None or not await store.delete_file(file_id):
            raise HTTPException(status_code=404, detail="文件不存在")
        # 磁盘文件在 files_dir 内则一并清理（登记目录外的不动）
        try:
            p = Path(record["path"]).resolve()
            root = Path(runtime.settings.storage.files_dir).resolve()
            if p.is_relative_to(root):
                p.unlink(missing_ok=True)
        except OSError:
            logger.warning("清理文件 %s 磁盘副本失败", file_id)
        return {"ok": True, "fileId": file_id}

    @router.post("/files/{file_id}/transfer")
    async def start_transfer(file_id: str, body: dict[str, Any]) -> dict[str, Any]:
        transfer = _transfer()
        agent_id = body.get("agentId")
        device_mac = body.get("deviceMac")
        if not agent_id or not device_mac:
            raise HTTPException(status_code=422,
                                detail="缺少 agentId / deviceMac")
        try:
            task_id = await transfer.start_transfer(agent_id, file_id, device_mac)
        except KeyError as e:
            raise HTTPException(status_code=404, detail=str(e))
        except ValueError as e:
            raise HTTPException(status_code=409, detail=str(e))
        return {"taskId": task_id}

    @router.get("/transfers")
    async def list_transfers() -> list[dict[str, Any]]:
        return _transfer().list_tasks()

    @router.get("/transfers/{task_id}")
    async def get_transfer(task_id: str) -> dict[str, Any]:
        task = _transfer().get_task(task_id)
        if task is None:
            raise HTTPException(status_code=404, detail="任务不存在")
        return task

    @router.post("/transfers/{task_id}/cancel")
    async def cancel_transfer(task_id: str) -> dict[str, Any]:
        if not await _transfer().cancel(task_id):
            raise HTTPException(status_code=404, detail="任务不存在或已结束")
        return {"ok": True, "taskId": task_id}

    @router.post("/agents/{agent_id}/log-upload")
    async def request_log_upload(agent_id: str,
                                 body: dict[str, Any] | None = None
                                 ) -> dict[str, Any]:
        transfer = _transfer()
        body = body or {}
        try:
            request_id = await transfer.request_log_upload(
                agent_id, body.get("sinceTs"), body.get("minLevel"))
        except ValueError as e:
            raise HTTPException(status_code=409, detail=str(e))
        return {"requestId": request_id}

    # ---------------- 测试编排（§14） ----------------

    def _load_scenario_arg(arg: Any) -> Scenario:
        """内联场景对象 或 {"path": "xxx.json"}（限 scenarios 目录内）。"""
        if not isinstance(arg, dict):
            raise HTTPException(status_code=422, detail="scenario 必须为对象")
        if "path" in arg:
            p = SCENARIOS_DIR / Path(str(arg["path"])).name
            if not p.is_file():
                raise HTTPException(status_code=404,
                                    detail=f"场景文件不存在: {arg['path']}")
            try:
                return load_scenario(p)
            except (ValidationError, ValueError) as e:
                raise HTTPException(status_code=422, detail=f"场景非法: {e}")
        try:
            return Scenario.model_validate(arg)
        except ValidationError as e:
            raise HTTPException(status_code=422, detail=f"场景非法: {e}")

    @router.get("/scenarios")
    async def list_scenarios() -> list[dict[str, Any]]:
        """列出 scenarios 目录下的场景文件（name 取文件内字段，缺失用文件名）。"""
        out = []
        if SCENARIOS_DIR.is_dir():
            for p in sorted(SCENARIOS_DIR.glob("*.json")):
                try:
                    name = load_scenario(p).name
                except Exception:
                    logger.warning("场景文件 %s 解析失败，跳过", p)
                    continue
                out.append({"name": name, "path": p.name})
        return out

    @router.get("/scenarios/{path:path}")
    async def get_scenario(path: str) -> dict[str, Any]:
        """读取单个场景文件：{"path", "name", "content": 解析后的 JSON 对象}。"""
        p = _scenario_file(path)
        if not p.is_file():
            raise HTTPException(status_code=404,
                                detail=f"场景文件不存在: {p.name}")
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            raise HTTPException(status_code=422,
                                detail=f"场景文件 JSON 非法: {e}")
        try:
            sc = Scenario.model_validate(data)
        except ValidationError as e:
            raise HTTPException(status_code=422,
                                detail=f"场景不符合模型: {e}")
        return {"path": p.name, "name": sc.name, "content": data}

    @router.put("/scenarios/{path:path}")
    async def put_scenario(path: str, body: dict[str, Any]) -> dict[str, Any]:
        """创建/整文件覆盖场景：先过 Scenario 校验，合法才落盘（缩进 2）。"""
        p = _scenario_file(path, append_suffix=True)
        try:
            sc = Scenario.model_validate(body)
        except ValidationError as e:
            raise HTTPException(status_code=422, detail=f"场景非法: {e}")
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n",
                     encoding="utf-8")
        return {"ok": True, "path": p.name, "name": sc.name}

    @router.delete("/scenarios/{path:path}")
    async def delete_scenario(path: str) -> dict[str, Any]:
        p = _scenario_file(path)
        if not p.is_file():
            raise HTTPException(status_code=404,
                                detail=f"场景文件不存在: {p.name}")
        p.unlink()
        return {"ok": True, "path": p.name}

    @router.post("/tests/run")
    async def run_test(body: dict[str, Any]) -> dict[str, Any]:
        """异步启动场景：立即返回 runId；AgentBusyError → 409。"""
        agent_id = body.get("agentId")
        if not agent_id or "scenario" not in body:
            raise HTTPException(status_code=422,
                                detail="缺少 agentId / scenario")
        sc = _load_scenario_arg(body["scenario"])
        run_id = f"run-{uuid.uuid4().hex[:12]}"
        task = asyncio.create_task(
            runtime.engine.run_scenario(sc, agent_id, run_id=run_id),
            name=f"scenario-{run_id}")
        # 让任务跑完互斥检查：同 agent 在跑时此处同步拿到 AgentBusyError
        await asyncio.sleep(0)
        if task.done():
            exc = task.exception()
            if isinstance(exc, AgentBusyError):
                raise HTTPException(status_code=409, detail=str(exc))
            if exc is not None:
                raise HTTPException(status_code=500, detail=str(exc))
        else:
            task.add_done_callback(_log_run_failure)
        return {"runId": run_id}

    @router.get("/tests/runs")
    async def list_test_runs(limit: int = 50) -> dict[str, Any]:
        return {"runs": await store.list_test_runs(limit),
                "running": runtime.engine.running()}

    @router.get("/tests/runs/{run_id}")
    async def get_test_run(run_id: str) -> dict[str, Any]:
        run = await store.get_test_run(run_id)
        if run is None:
            raise HTTPException(status_code=404, detail="run 不存在")
        return run

    @router.post("/tests/runs/{run_id}/stop")
    async def stop_test_run(run_id: str) -> dict[str, Any]:
        if not await runtime.engine.stop(run_id):
            raise HTTPException(status_code=404,
                                detail="run 不存在或已结束")
        return {"ok": True, "runId": run_id}

    # ---------------- 事件流水查询 ----------------

    @router.get("/events")
    async def query_events(agent_id: str | None = None,
                           type: str | None = None,
                           limit: int = 200) -> list[dict[str, Any]]:
        return await store.query_events(agent_id=agent_id, type=type, limit=limit)

    return router


def _log_run_failure(task: asyncio.Task) -> None:
    """后台场景任务异常兜底（报告在 runner 内已落库，此处仅记日志防丢失）。"""
    if task.cancelled():
        return
    exc = task.exception()
    if exc is not None:
        logger.error("场景任务异常结束: %r", exc)
