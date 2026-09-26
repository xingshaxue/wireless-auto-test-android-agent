"""BatchOrchestrator：批量 OTA 编排（SDD §7.6 传输链路 + docs/04 p67 OTA 流程）。

把一个 OTA 包推送到 N 台设备，每台独立跑子流程：
传输（复用 FILE_TRANSFER，§7.6）→ 升级命令（WRITE_CHAR）→ 黑窗等待 →
回连（CONNECT_DEVICE + 等 DEVICE_STATE READY）→ 版本验证（WRITE_CHAR +
等 POLL_RESULT 子串）。失败隔离：单台失败不影响其余设备，批次终态出
逐设备汇总。

并发模型（§16.4 maxConcurrentTransfers 的调度侧落地）：同一 agent 内同时
进行的设备数 ≤ perAgentConcurrency（缺省 1，BLE 带宽瓶颈），per-agent
asyncio.Semaphore 实现；跨 agent 天然并行。批次/设备状态驻内存（上限 50
批次环形保留），server 重启不恢复。计时用 loop.time() 单调时钟（§9）。
"""

from __future__ import annotations

import asyncio
import base64
import collections
import json
import logging
import time
import uuid
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from ..runtime import Runtime

logger = logging.getLogger(__name__)

# 批次状态机
BATCH_RUNNING = "RUNNING"
BATCH_DONE = "DONE"
BATCH_CANCELLED = "CANCELLED"

# 设备相位机：QUEUED → TRANSFERRING → UPGRADE_CMD → BLACKOUT →
# RECONNECTING → VERIFYING → DONE / FAILED / CANCELLED
PHASE_QUEUED = "QUEUED"
PHASE_TRANSFERRING = "TRANSFERRING"
PHASE_UPGRADE_CMD = "UPGRADE_CMD"
PHASE_BLACKOUT = "BLACKOUT"
PHASE_RECONNECTING = "RECONNECTING"
PHASE_VERIFYING = "VERIFYING"
PHASE_DONE = "DONE"
PHASE_FAILED = "FAILED"
PHASE_CANCELLED = "CANCELLED"

_PHASE_TERMINAL = {PHASE_DONE, PHASE_FAILED, PHASE_CANCELLED}

# 已完成批次保留上限（防内存膨胀，对齐 exportrecv 的 _done 环形）
_DONE_KEEP = 50

# p67 手环 OTA 默认参数（docs/04 §2/§7 实测校准；LC 通道特征 props=0x14，
# 硬性要求 Write No Response，带响应写被固件 0xFC 拒绝）
P67_SERVICE = "1b7e8251-2877-41c3-b46e-cf057c562023"
P67_CHAR = "8ac32d3f-5cb9-4d44-bec2-ee689169f626"
_DEFAULT_UPGRADE_PAYLOAD = base64.b64encode(b"00AT^OTA_UPDATE").decode()
_DEFAULT_VERSION_PAYLOAD = base64.b64encode(b"00AT^SWVER=APP").decode()

DEFAULT_BLACKOUT_MS = 30_000
DEFAULT_RECONNECT_TIMEOUT_MS = 600_000  # p67 黑窗约 4~6 分钟，留足余量
DEFAULT_VERIFY_TIMEOUT_MS = 60_000
DEFAULT_PER_AGENT_CONCURRENCY = 1


class DeviceFailed(Exception):
    """设备子流程失败（携带 error/detail 记入设备记录，不中断批次）。"""

    def __init__(self, error: Any, detail: str) -> None:
        super().__init__(detail)
        self.error = error
        self.detail = detail


class BatchOrchestrator:
    """批量 OTA 编排器。runtime 装配时创建并 attach（挂 ingest 事件监听）。"""

    def __init__(self, runtime: "Runtime") -> None:
        self._settings = runtime.settings
        self._store = runtime.store
        self._ledger = runtime.ledger
        self._registry = runtime.registry
        self._ingest = runtime.ingest
        self._config = runtime.config_manager
        self._transfer = runtime.transfer
        self._batches: dict[str, dict[str, Any]] = {}  # batchId → 批次记录
        self._done_order: list[str] = []  # 终态批次 FIFO（环形保留 _DONE_KEEP）
        self._waiters: list[dict[str, Any]] = []  # 事件等待者（READY / POLL_RESULT）
        # 近期 POLL_RESULT 环形缓冲（(agent, mac) → [(loop.time(), raw)]）：
        # 应答可能先于 _wait_poll 挂等到达，挂等后按 since 扫描缓冲补漏
        self._poll_log: dict[tuple[str, str], collections.deque] = {}
        # 可测性旋钮（生产取默认；测试可覆写缩短等待）
        self._task_poll_s = 1.0  # 传输任务轮询间隔
        self._verify_timeout_ms = DEFAULT_VERIFY_TIMEOUT_MS

    # ---------------- 挂载 ----------------

    def attach(self, runtime: "Runtime") -> None:
        runtime.ingest.add_listener(self.on_event)

    def on_event(self, agent_id: str, etype: str, raw: dict) -> None:
        """ingest 同步回调：唤醒 READY / POLL_RESULT 等待者。"""
        for w in list(self._waiters):
            if w["agentId"] != agent_id or w["fut"].done():
                continue
            if w["kind"] == "ready":
                if etype == "DEVICE_STATE" and raw.get("deviceMac") == w["mac"] \
                        and raw.get("state") == "READY":
                    w["fut"].set_result(raw)
            elif w["kind"] == "poll":
                if etype == "POLL_RESULT" and raw.get("deviceMac") == w["mac"]:
                    w["last"] = raw
                    if w["expect"] in json.dumps(raw, ensure_ascii=False):
                        w["fut"].set_result(raw)
        if etype == "POLL_RESULT":
            key = (agent_id, raw.get("deviceMac", ""))
            buf = self._poll_log.setdefault(key, collections.deque(maxlen=20))
            buf.append((asyncio.get_running_loop().time(), raw))

    # ---------------- 批次发起 ----------------

    async def start(self, body: dict[str, Any]) -> str:
        """校验请求 → 建档 → 异步启动设备子任务，返回 batchId。

        ValueError → 422（字段缺失/非法）；KeyError → 404（fileId 未登记）。
        """
        if not isinstance(body, dict):
            raise ValueError("请求体必须为对象")
        file_id = body.get("fileId")
        if not isinstance(file_id, str) or not file_id:
            raise ValueError("缺少 fileId")
        targets = body.get("targets")
        if not isinstance(targets, list) or not targets:
            raise ValueError("targets 必须为非空数组")
        for t in targets:
            if not isinstance(t, dict) \
                    or not isinstance(t.get("agentId"), str) or not t.get("agentId") \
                    or not isinstance(t.get("deviceMac"), str) or not t.get("deviceMac"):
                raise ValueError("targets 项必须含 agentId / deviceMac 字符串")
        if await self._store.get_file(file_id) is None:
            raise KeyError(f"文件未登记: {file_id}")

        upgrade_write = self._norm_write(
            body.get("upgradeWrite"), _DEFAULT_UPGRADE_PAYLOAD, "upgradeWrite")
        version_check = self._norm_write(
            body.get("versionCheck"), _DEFAULT_VERSION_PAYLOAD, "versionCheck",
            payload_key="writePayloadBase64")
        if body.get("versionCheck") is not None:
            expect = body["versionCheck"].get("expectContains")
            if expect is not None and not isinstance(expect, str):
                raise ValueError("versionCheck.expectContains 必须为字符串")
            version_check["expectContains"] = expect
        else:
            version_check["expectContains"] = None

        blackout_ms = self._norm_int(body, "blackoutMs", DEFAULT_BLACKOUT_MS, 0)
        per_agent = self._norm_int(
            body, "perAgentConcurrency", DEFAULT_PER_AGENT_CONCURRENCY, 1)
        reconnect_ms = self._norm_int(
            body, "reconnectTimeoutMs", DEFAULT_RECONNECT_TIMEOUT_MS, 1)
        # 全局 maxConcurrentTransfers（§16.4，默认 1）是 per-agent 并发的上限
        globals_ = await self._config.get_global_params()
        cap = int(globals_.get("maxConcurrentTransfers", 1) or 1)
        per_agent = max(1, min(per_agent, cap))

        batch_id = "batch-" + uuid.uuid4().hex[:8]
        sems: dict[str, asyncio.Semaphore] = {}
        devices: list[dict[str, Any]] = []
        for t in targets:
            sems.setdefault(t["agentId"], asyncio.Semaphore(per_agent))
            devices.append({
                "agentId": t["agentId"], "deviceMac": t["deviceMac"],
                "phase": PHASE_QUEUED, "taskId": None,
                "error": None, "detail": None, "version": None,
                "_task": None,
            })
        batch: dict[str, Any] = {
            "batchId": batch_id, "state": BATCH_RUNNING, "fileId": file_id,
            "createdTs": int(time.time() * 1000), "finishedTs": None,
            "devices": devices, "_sems": sems,
            "_params": {
                "upgradeWrite": upgrade_write, "versionCheck": version_check,
                "blackoutMs": blackout_ms, "perAgentConcurrency": per_agent,
                "reconnectTimeoutMs": reconnect_ms,
            },
            "_supervisor": None,
        }
        self._batches[batch_id] = batch
        for dev in devices:
            dev["_task"] = asyncio.create_task(
                self._run_device(batch, dev),
                name=f"batch-{batch_id}-{dev['deviceMac']}")
        batch["_supervisor"] = asyncio.create_task(
            self._supervise(batch), name=f"batch-{batch_id}-supervisor")
        logger.info("批次 %s 启动：file=%s 设备 %d 台 perAgentConcurrency=%d",
                    batch_id, file_id, len(devices), per_agent)
        return batch_id

    @staticmethod
    def _norm_write(spec: Any, default_payload: str, label: str,
                    payload_key: str = "payloadBase64") -> dict[str, Any]:
        """WRITE_CHAR 参数归一化：缺省 = p67 LC 通道（docs/04 §2）。"""
        out = {"service": P67_SERVICE, "char": P67_CHAR,
               payload_key: default_payload, "writeType": "NO_RESPONSE"}
        if spec is None:
            return out
        if not isinstance(spec, dict):
            raise ValueError(f"{label} 必须为对象")
        for key in ("service", "char", payload_key, "writeType"):
            if key in spec:
                if not isinstance(spec[key], str):
                    raise ValueError(f"{label}.{key} 必须为字符串")
                out[key] = spec[key]
        return out

    @staticmethod
    def _norm_int(body: dict[str, Any], key: str, default: int, minimum: int) -> int:
        v = body.get(key, default)
        if not isinstance(v, int) or isinstance(v, bool) or v < minimum:
            raise ValueError(f"{key} 必须为 ≥{minimum} 的整数")
        return v

    # ---------------- 设备子流程 ----------------

    async def _run_device(self, batch: dict[str, Any], dev: dict[str, Any]) -> None:
        """单设备子任务：per-agent 信号量限流，失败隔离（异常只落本设备）。"""
        try:
            sem = batch["_sems"][dev["agentId"]]
            async with sem:
                if batch["state"] != BATCH_RUNNING:
                    dev["phase"] = PHASE_CANCELLED
                    return
                await self._flow(batch, dev)
        except asyncio.CancelledError:
            if dev["phase"] not in _PHASE_TERMINAL:
                dev["phase"] = PHASE_CANCELLED
        except DeviceFailed as e:
            dev["phase"] = PHASE_FAILED
            dev["error"] = e.error
            dev["detail"] = e.detail
            logger.warning("批次 %s 设备 %s 失败: %s",
                           batch["batchId"], dev["deviceMac"], e.detail)
        except Exception as e:
            dev["phase"] = PHASE_FAILED
            dev["error"] = "INTERNAL"
            dev["detail"] = str(e)
            logger.exception("批次 %s 设备 %s 子流程异常",
                             batch["batchId"], dev["deviceMac"])

    async def _flow(self, batch: dict[str, Any], dev: dict[str, Any]) -> None:
        p = batch["_params"]
        agent_id, mac = dev["agentId"], dev["deviceMac"]

        # 1. TRANSFERRING：复用 §7.6 推送链路，轮询任务快照直到终态
        dev["phase"] = PHASE_TRANSFERRING
        try:
            task_id = await self._transfer.start_transfer(agent_id,
                                                          batch["fileId"], mac)
        except (ValueError, KeyError) as e:
            raise DeviceFailed("TRANSFER_START_FAILED", str(e))
        dev["taskId"] = task_id
        while True:
            task = self._transfer.get_task(task_id)
            if task is None:
                raise DeviceFailed("TRANSFER_LOST", f"传输任务消失: {task_id}")
            state = task["state"]
            if state == "COMPLETED":
                break
            if state in ("FAILED", "CANCELLED"):
                raise DeviceFailed(
                    task.get("errorCode"),
                    task.get("detail") or f"传输任务 {state}")
            await asyncio.sleep(self._task_poll_s)

        # 2. UPGRADE_CMD：写升级命令（默认 p67 "00AT^OTA_UPDATE"）
        dev["phase"] = PHASE_UPGRADE_CMD
        await self._write_char(agent_id, mac, p["upgradeWrite"], "payloadBase64",
                               "升级命令")

        # 3. BLACKOUT：recovery 刷写黑窗（p67 实测约 4~6 分钟）
        dev["phase"] = PHASE_BLACKOUT
        await asyncio.sleep(p["blackoutMs"] / 1000.0)

        # 4. RECONNECTING：黑窗期设备反复 ERROR/DISCONNECTED 由 agent
        #    errorRetryMs 自愈（docs/04 §10），server 只需等 READY
        dev["phase"] = PHASE_RECONNECTING
        session = self._registry.get(agent_id)
        if session is None or session.closed:
            raise DeviceFailed("AGENT_OFFLINE", f"agent 不在线: {agent_id}")
        ack = await self._send_and_wait(session, "CONNECT_DEVICE", deviceMac=mac)
        self._check_ack(ack, "回连命令")
        await self._wait_ready(agent_id, mac, p["reconnectTimeoutMs"] / 1000.0)

        # 5. VERIFYING：写查版本命令；给了 expectContains 才等 POLL_RESULT 内容
        dev["phase"] = PHASE_VERIFYING
        vc = p["versionCheck"]
        since = asyncio.get_running_loop().time()  # 只认写命令之后到达的应答
        await self._write_char(agent_id, mac, vc, "writePayloadBase64", "查版本命令")
        if vc.get("expectContains"):
            raw = await self._wait_poll(agent_id, mac, vc["expectContains"], since)
            dev["version"] = json.dumps(raw.get("values") or {},
                                        ensure_ascii=False)
        dev["phase"] = PHASE_DONE

    # ---------------- 子流程原语 ----------------

    async def _send_and_wait(self, session, cmd_type: str,
                             **fields: Any) -> dict[str, Any]:
        """台账下发 + 同步等 ACK（§8.4）；超时抛 DeviceFailed。"""
        request_id = await self._ledger.send_command(session, cmd_type, **fields)
        gw = self._settings.gateway
        wait_sec = gw.ack_timeout_ms * (gw.ack_max_retries + 1) / 1000.0 + 1.0
        try:
            return await self._ledger.wait_ack(request_id, wait_sec)
        except asyncio.TimeoutError:
            raise DeviceFailed("ACK_TIMEOUT", f"{cmd_type} 等待 CMD_ACK 超时")

    @staticmethod
    def _check_ack(ack: dict[str, Any], label: str) -> None:
        if ack["ledgerStatus"] != "acked" or ack.get("errorCode"):
            raise DeviceFailed(
                ack.get("errorCode"),
                f"{label}失败: ledgerStatus={ack['ledgerStatus']} "
                f"errorCode={ack.get('errorCode')}")

    async def _write_char(self, agent_id: str, mac: str, spec: dict[str, Any],
                          payload_key: str, label: str) -> None:
        session = self._registry.get(agent_id)
        if session is None or session.closed:
            raise DeviceFailed("AGENT_OFFLINE", f"agent 不在线: {agent_id}")
        ack = await self._send_and_wait(
            session, "WRITE_CHAR", deviceMac=mac, service=spec["service"],
            char=spec["char"], payload=spec[payload_key],
            writeType=spec["writeType"])
        self._check_ack(ack, label)

    async def _wait_ready(self, agent_id: str, mac: str, timeout_s: float) -> None:
        """等 DEVICE_STATE state=READY。先挂等再查当前视图，防事件先于等待到达。"""
        fut = asyncio.get_running_loop().create_future()
        waiter = {"kind": "ready", "agentId": agent_id, "mac": mac, "fut": fut}
        self._waiters.append(waiter)
        try:
            view = self._ingest.agent_view(agent_id)
            if view["devices"].get(mac, {}).get("state") == "READY":
                return
            await asyncio.wait_for(fut, timeout_s)
        except asyncio.TimeoutError:
            raise DeviceFailed("RECONNECT_TIMEOUT",
                               f"回连等待 READY 超时（{timeout_s:.0f}s）")
        finally:
            self._waiters.remove(waiter)

    async def _wait_poll(self, agent_id: str, mac: str, expect: str,
                         since: float) -> dict:
        """等该设备 POLL_RESULT raw 中出现 expect 子串。先挂等再扫到达缓冲
        （只认 since 之后的事件），防应答先于等待到达的竞态。"""
        fut = asyncio.get_running_loop().create_future()
        waiter = {"kind": "poll", "agentId": agent_id, "mac": mac,
                  "expect": expect, "fut": fut, "last": None}
        self._waiters.append(waiter)
        try:
            for ts, raw in self._poll_log.get((agent_id, mac), ()):
                if ts >= since:
                    waiter["last"] = raw
                    if expect in json.dumps(raw, ensure_ascii=False):
                        return raw
            return await asyncio.wait_for(
                fut, self._verify_timeout_ms / 1000.0)
        except asyncio.TimeoutError:
            last = waiter.get("last")
            raise DeviceFailed(
                "VERIFY_TIMEOUT",
                "版本验证超时（%ds），最后收到: %s" % (
                    self._verify_timeout_ms // 1000,
                    json.dumps(last.get("values"), ensure_ascii=False)
                    if last else "无"))
        finally:
            self._waiters.remove(waiter)

    # ---------------- 批次收尾与取消 ----------------

    async def _supervise(self, batch: dict[str, Any]) -> None:
        """等全部设备子任务终态 → 批次收尾（RUNNING→DONE）→ 环形保留。"""
        await asyncio.gather(
            *(dev["_task"] for dev in batch["devices"]), return_exceptions=True)
        if batch["state"] == BATCH_RUNNING:
            batch["state"] = BATCH_DONE
        batch["finishedTs"] = int(time.time() * 1000)
        self._done_order.append(batch["batchId"])
        while len(self._done_order) > _DONE_KEEP:
            old = self._done_order.pop(0)
            self._batches.pop(old, None)
        s = self._summary(batch)
        logger.info("批次 %s 终态 %s：成功 %d / 失败 %d / 共 %d",
                    batch["batchId"], batch["state"],
                    s["succeeded"], s["failed"], s["total"])

    async def cancel(self, batch_id: str) -> bool:
        """取消批次（幂等；仅 RUNNING 可取消）：在途传输 FILE_CANCEL，其余设备
        子任务取消（QUEUED 设备随信号量入口判批次态直接落 CANCELLED）。"""
        batch = self._batches.get(batch_id)
        if batch is None or batch["state"] != BATCH_RUNNING:
            return False
        batch["state"] = BATCH_CANCELLED
        for dev in batch["devices"]:
            if dev["phase"] == PHASE_TRANSFERRING and dev["taskId"]:
                await self._transfer.cancel(dev["taskId"])
        for dev in batch["devices"]:
            task = dev.get("_task")
            if task is not None and not task.done():
                task.cancel()
        logger.info("批次 %s 已取消", batch_id)
        return True

    # ---------------- 查询 ----------------

    @staticmethod
    def _summary(batch: dict[str, Any]) -> dict[str, int]:
        succeeded = sum(1 for d in batch["devices"] if d["phase"] == PHASE_DONE)
        failed = sum(1 for d in batch["devices"]
                     if d["phase"] in (PHASE_FAILED, PHASE_CANCELLED))
        total = len(batch["devices"])
        return {"total": total, "succeeded": succeeded, "failed": failed,
                "remaining": total - succeeded - failed}

    @staticmethod
    def _brief(batch: dict[str, Any]) -> dict[str, Any]:
        return {"batchId": batch["batchId"], "state": batch["state"],
                "fileId": batch["fileId"], "createdTs": batch["createdTs"],
                "finishedTs": batch["finishedTs"],
                "summary": BatchOrchestrator._summary(batch)}

    def list_batches(self) -> dict[str, Any]:
        running, done = [], []
        for batch in self._batches.values():
            brief = self._brief(batch)
            (running if batch["state"] == BATCH_RUNNING else done).append(brief)
        return {"running": running, "done": done}

    def get_batch(self, batch_id: str) -> dict[str, Any] | None:
        batch = self._batches.get(batch_id)
        if batch is None:
            return None
        detail = self._brief(batch)
        detail["devices"] = [
            {k: v for k, v in dev.items() if not k.startswith("_")}
            for dev in batch["devices"]]
        return detail

    async def close(self) -> None:
        for batch in self._batches.values():
            if batch["state"] == BATCH_RUNNING:
                batch["state"] = BATCH_CANCELLED
                for dev in batch["devices"]:
                    task = dev.get("_task")
                    if task is not None and not task.done():
                        task.cancel()
        await asyncio.gather(
            *(b["_supervisor"] for b in self._batches.values()
              if b.get("_supervisor") is not None),
            return_exceptions=True)
