"""FilePusher：服务器 → agent 文件推送（SDD §7.6 "TCP 侧文件下载协议"）。

流程：FILE_TRANSFER → 等 FILE_DOWNLOAD_READY → 二进制帧推送
（FILE_FRAME seq 从 1 连续编号 + FILE_END payload=整体 SHA-256，§16.3）→
FILE_DOWNLOAD_ACK（resendSeqs 非空重发对应帧 + FILE_END；空 = 下载完成）。
断连 → 任务 PAUSED，agent 重连发 FILE_DOWNLOAD_RESUME（lastSeq，字节偏移
= seq × chunkSize）从 lastSeq+1 续推。任务状态机驻内存。计时用 loop.time()（§9）。
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import time
import uuid
from pathlib import Path
from typing import TYPE_CHECKING, Any

from ..protocol.errors import ProtocolErrorCode

if TYPE_CHECKING:
    from ..configsvc.store import Store
    from ..gateway.registry import AgentRegistry
    from ..ledger import CommandLedger
    from ..settings import Settings

logger = logging.getLogger(__name__)

# 任务状态机（§7.6）
WAIT_READY = "WAIT_READY"    # FILE_TRANSFER 已下发，等 READY
PUSHING = "PUSHING"          # 推送中 / 已推完等 ACK
PAUSED = "PAUSED"            # 断连挂起，等 RESUME
DOWNLOADED = "DOWNLOADED"    # ACK 空 = TCP 侧下载完成（之后进入 BLE 阶段）
COMPLETED = "COMPLETED"      # FILE_RESULT errorCode=0
FAILED = "FAILED"            # FILE_RESULT 4xxx / 本地读盘失败
CANCELLED = "CANCELLED"      # FILE_CANCEL / FILE_RESULT 2004

_TERMINAL = {COMPLETED, FAILED, CANCELLED}

FT_FILE_FRAME = 0x01  # §16.3
FT_FILE_END = 0x02


class FilePusher:
    """文件推送器。settings 取全量 Settings（storage.chunk_size 等）。"""

    def __init__(self, settings: "Settings", store: "Store",
                 ledger: "CommandLedger", registry: "AgentRegistry") -> None:
        self._settings = settings
        self._store = store
        self._ledger = ledger
        self._registry = registry
        self._tasks: dict[str, dict[str, Any]] = {}  # task_id → 任务记录

    # ---------------- 任务发起 ----------------

    async def start_transfer(self, agent_id: str, file_id: str,
                             device_mac: str, window_size: int = 64,
                             task_id: str | None = None) -> str:
        """下发 FILE_TRANSFER（A.2），返回 taskId；READY 到达后开始推帧。"""
        session = self._registry.get(agent_id)
        if session is None or session.closed:
            raise ValueError(f"agent 不在线: {agent_id}")
        rec = await self._store.get_file(file_id)
        if rec is None:
            raise KeyError(f"文件未登记: {file_id}")
        task_id = task_id or "task-" + uuid.uuid4().hex[:8]
        chunk = self._settings.storage.chunk_size
        total = (rec["size"] + chunk - 1) // chunk  # 空文件 = 0 帧，只发 FILE_END
        self._tasks[task_id] = {
            "taskId": task_id, "fileId": file_id, "agentId": agent_id,
            "deviceMac": device_mac, "state": WAIT_READY,
            "ackedSeq": 0, "totalChunks": total, "size": rec["size"],
            "percent": 0.0, "downloadPercent": 0.0, "errorCode": None,
            "detail": None,
            "createdTs": int(time.time() * 1000),
            "_path": rec["path"], "_push": None,
        }
        await self._ledger.send_command(
            session, "FILE_TRANSFER",
            taskId=task_id, fileId=file_id, deviceMac=device_mac,
            size=rec["size"], sha256=rec["sha256"], windowSize=window_size,
            fileName=rec.get("name"))
        logger.info("任务 %s 下发 FILE_TRANSFER agent=%s file=%s size=%d",
                    task_id, agent_id, file_id, rec["size"])
        return task_id

    async def cancel(self, task_id: str) -> bool:
        """FILE_CANCEL（A.2）：下发取消命令并终结本地任务。"""
        task = self._tasks.get(task_id)
        if task is None or task["state"] in _TERMINAL:
            return False
        task["state"] = CANCELLED
        push = task.get("_push")
        if push is not None:
            push.cancel()
        session = self._registry.get(task["agentId"])
        if session is not None and not session.closed:
            await self._ledger.send_command(session, "FILE_CANCEL", taskId=task_id)
        logger.info("任务 %s 已取消", task_id)
        return True

    # ---------------- 事件入口（ingest listener，同步回调内调度协程） ----------------

    def on_event(self, agent_id: str, etype: str, raw: dict) -> None:
        if etype == "FILE_DOWNLOAD_READY":
            task = self._tasks.get(raw.get("taskId", ""))
            if task is not None and task["state"] in (WAIT_READY, PAUSED):
                self._spawn_push(task, 1)
        elif etype == "FILE_DOWNLOAD_ACK":
            task = self._tasks.get(raw.get("taskId", ""))
            if task is not None:
                asyncio.create_task(self._on_ack(task, raw.get("resendSeqs") or []))
        elif etype == "FILE_DOWNLOAD_RESUME":
            # lastSeq = 已确认最大连续块号（§7.6 / §16.3），从 lastSeq+1 续推
            task = self._tasks.get(raw.get("taskId", ""))
            if task is not None and task["state"] not in _TERMINAL:
                last = int(raw.get("lastSeq", 0))
                task["ackedSeq"] = last
                self._spawn_push(task, last + 1)
        elif etype == "FILE_PROGRESS":
            task = self._tasks.get(raw.get("taskId", ""))
            if task is not None:
                task["percent"] = float(raw.get("percent", 0.0))
        elif etype == "FILE_RESULT":
            task = self._tasks.get(raw.get("taskId", ""))
            if task is not None:
                code = int(raw.get("errorCode", 0))
                task["errorCode"] = code
                task["detail"] = raw.get("detail")
                if code == 0:
                    task["state"] = COMPLETED
                    task["percent"] = 100.0
                elif code == int(ProtocolErrorCode.COMMAND_CANCELLED):
                    task["state"] = CANCELLED  # 2004（A.3）
                else:
                    task["state"] = FAILED
                logger.info("任务 %s FILE_RESULT errorCode=%d → %s",
                            task["taskId"], code, task["state"])
        elif etype == "FILE_REQUEST":
            # 规则动作触发的取文件入口（A.3 / §7.3.2），流程并入 §7.6
            asyncio.create_task(self._on_file_request(agent_id, raw))

    def on_agent_disconnect(self, agent_id: str) -> None:
        """TCP 中断 → 在途任务挂起（§7.6 第 4 步），重连后等 RESUME。"""
        for task in self._tasks.values():
            if task["agentId"] == agent_id and task["state"] in (WAIT_READY, PUSHING):
                task["state"] = PAUSED
                logger.info("agent %s 断连，任务 %s 挂起（PAUSED）",
                            agent_id, task["taskId"])

    # ---------------- 推送协程 ----------------

    def _spawn_push(self, task: dict[str, Any], from_seq: int) -> None:
        old = task.get("_push")
        if old is not None and not old.done():
            old.cancel()
        task["_push"] = asyncio.create_task(
            self._push(task, from_seq), name=f"push-{task['taskId']}")

    async def _push(self, task: dict[str, Any], from_seq: int) -> None:
        """流式读盘分帧推送；每 file_push_yield_frames 帧让出事件循环（§9）。"""
        task["state"] = PUSHING
        data = await self._read_file(task)
        if data is None:
            return
        chunk = self._settings.storage.chunk_size
        digest = hashlib.sha256(data).digest()
        total = task["totalChunks"]
        yield_every = max(1, self._settings.storage.file_push_yield_frames)
        for seq in range(from_seq, total + 1):
            if task["state"] != PUSHING:  # 被暂停 / 取消
                return
            session = self._registry.get(task["agentId"])
            if session is None or session.closed:
                task["state"] = PAUSED
                return
            await session.send_frame(
                FT_FILE_FRAME, seq, data[(seq - 1) * chunk: seq * chunk])
            # 下载段实时进度（推送侧），供前端进度条（percent 由 agent FILE_PROGRESS 管）
            task["downloadPercent"] = round(seq / total * 100, 1) if total else 100.0
            if seq % yield_every == 0:
                await asyncio.sleep(0)
        if task["state"] != PUSHING:
            return
        session = self._registry.get(task["agentId"])
        if session is None or session.closed:
            task["state"] = PAUSED
            return
        # FILE_END：payload = 整体 SHA-256 32 字节（§16.3）
        await session.send_frame(FT_FILE_END, 0, digest)
        logger.info("任务 %s 推送完成 seq %d~%d，等 ACK",
                    task["taskId"], from_seq, total)

    async def _on_ack(self, task: dict[str, Any], resend_seqs: list[int]) -> None:
        """FILE_DOWNLOAD_ACK：resendSeqs 非空 → 重发这些帧 + FILE_END；空 → DOWNLOADED。"""
        if task["state"] in _TERMINAL:
            return
        if resend_seqs:
            data = await self._read_file(task)
            if data is None:
                return
            chunk = self._settings.storage.chunk_size
            session = self._registry.get(task["agentId"])
            if session is None or session.closed:
                task["state"] = PAUSED
                return
            for seq in resend_seqs:
                await session.send_frame(
                    FT_FILE_FRAME, seq, data[(seq - 1) * chunk: seq * chunk])
            await session.send_frame(FT_FILE_END, 0, hashlib.sha256(data).digest())
            logger.info("任务 %s 重发帧 %s", task["taskId"], resend_seqs)
        else:
            task["ackedSeq"] = task["totalChunks"]
            task["downloadPercent"] = 100.0
            task["state"] = DOWNLOADED
            logger.info("任务 %s TCP 侧下载完成（DOWNLOADED）", task["taskId"])

    async def _on_file_request(self, agent_id: str, raw: dict) -> None:
        file_id = raw.get("fileId", "")
        rec = await self._store.get_file(file_id)
        if rec is None:
            logger.warning("agent %s FILE_REQUEST 未知 fileId=%s，忽略",
                           agent_id, file_id)
            return
        try:
            await self.start_transfer(agent_id, file_id,
                                      raw.get("deviceMac", ""),
                                      task_id=raw.get("taskId"))
        except ValueError:
            logger.warning("agent %s FILE_REQUEST taskId=%s 下发失败（agent 不在线）",
                           agent_id, raw.get("taskId"))

    async def _read_file(self, task: dict[str, Any]) -> bytes | None:
        try:
            return await asyncio.to_thread(Path(task["_path"]).read_bytes)
        except OSError as e:
            task["state"] = FAILED
            task["detail"] = f"读文件失败: {e}"
            logger.error("任务 %s 读文件失败: %s", task["taskId"], e)
            return None

    # ---------------- 查询与清理 ----------------

    @staticmethod
    def _snapshot(task: dict[str, Any]) -> dict[str, Any]:
        return {k: v for k, v in task.items() if not k.startswith("_")}

    def list_tasks(self) -> list[dict[str, Any]]:
        return [self._snapshot(t) for t in self._tasks.values()]

    def get_task(self, task_id: str) -> dict[str, Any] | None:
        task = self._tasks.get(task_id)
        return self._snapshot(task) if task is not None else None

    async def close(self) -> None:
        for task in self._tasks.values():
            push = task.get("_push")
            if push is not None and not push.done():
                push.cancel()
        await asyncio.gather(
            *(t["_push"] for t in self._tasks.values()
              if t.get("_push") is not None and not t["_push"].done()),
            return_exceptions=True)
