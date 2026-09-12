"""TransferManager：transfer 模块门面，组合 FilePusher + LogReceiver。

attach(runtime) 挂载：LOG_FRAME 帧处理器 → gateway.on_frame_handlers；
FILE_*/LOG_UPLOAD_DONE 事件 → ingest listener；agent 断连通知包装进
gateway.on_disconnect（§7.6 断点续传前提）。
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import logging
import uuid
from pathlib import Path
from typing import TYPE_CHECKING, Any

from .logrecv import LogReceiver
from .pusher import FilePusher

if TYPE_CHECKING:
    from ..runtime import Runtime

logger = logging.getLogger(__name__)


class TransferManager:
    """文件传输门面。runtime 装配时创建并 attach。"""

    def __init__(self, runtime: "Runtime") -> None:
        self._settings = runtime.settings
        self._store = runtime.store
        self.pusher = FilePusher(
            runtime.settings, runtime.store, runtime.ledger, runtime.registry)
        self.logrecv = LogReceiver(
            runtime.settings, runtime.ledger, runtime.registry)

    # ---------------- 挂载 ----------------

    def attach(self, runtime: "Runtime") -> None:
        runtime.gateway.on_frame_handlers.append(self.logrecv.handle_frame)
        runtime.ingest.add_listener(self._on_event)
        prev = runtime.gateway.on_disconnect

        async def _on_disconnect(session) -> None:
            if prev is not None:
                await prev(session)
            if session.agent_id:
                self.pusher.on_agent_disconnect(session.agent_id)

        runtime.gateway.on_disconnect = _on_disconnect

    def _on_event(self, agent_id: str, etype: str, raw: dict) -> None:
        self.pusher.on_event(agent_id, etype, raw)
        self.logrecv.on_event(agent_id, etype, raw)

    # ---------------- 文件登记（含配额检查，§7.6 本地存储管理） ----------------

    async def register_file(self, path: str | Path,
                            file_id: str | None = None,
                            name: str | None = None) -> str:
        """计算 size/SHA-256 并登记入 files 表；files_dir 总量超 files_quota_mb 拒绝。"""
        p = Path(path)
        size, sha256_b64 = await asyncio.to_thread(self._hash_file, p)
        quota = self._settings.storage.files_quota_mb * 1024 * 1024
        used, inside = await asyncio.to_thread(self._dir_usage, p)
        if used + (0 if inside else size) > quota:
            raise ValueError(
                f"files_dir 配额超限: 已用 {used}B + 新文件 {size}B"
                f" > 配额 {quota}B（files_quota_mb）")
        file_id = file_id or "file-" + uuid.uuid4().hex[:8]
        await self._store.register_file(
            file_id, name or p.name, size, sha256_b64, str(p))
        logger.info("登记文件 %s name=%s size=%d path=%s",
                    file_id, name or p.name, size, p)
        return file_id

    @staticmethod
    def _hash_file(p: Path) -> tuple[int, str]:
        h = hashlib.sha256()
        size = 0
        with open(p, "rb") as f:
            for blk in iter(lambda: f.read(1 << 20), b""):
                h.update(blk)
                size += len(blk)
        return size, base64.b64encode(h.digest()).decode()

    def _dir_usage(self, new_file: Path) -> tuple[int, bool]:
        """返回 (files_dir 现存文件总字节, 新文件是否已在 files_dir 内)。"""
        root = Path(self._settings.storage.files_dir).resolve()
        used = 0
        if root.is_dir():
            for f in root.rglob("*"):
                if f.is_file():
                    used += f.stat().st_size
        try:
            inside = new_file.resolve().is_relative_to(root)
        except OSError:
            inside = False
        return used, inside

    # ---------------- 委派：推送 / 日志 ----------------

    async def start_transfer(self, agent_id: str, file_id: str,
                             device_mac: str, window_size: int = 64) -> str:
        return await self.pusher.start_transfer(
            agent_id, file_id, device_mac, window_size)

    async def cancel(self, task_id: str) -> bool:
        return await self.pusher.cancel(task_id)

    async def request_log_upload(self, agent_id: str,
                                 since_ts: int | None = None,
                                 min_level: str | None = None) -> str:
        return await self.logrecv.request_log_upload(agent_id, since_ts, min_level)

    # ---------------- 查询 ----------------

    def list_tasks(self) -> list[dict[str, Any]]:
        return self.pusher.list_tasks()

    def get_task(self, task_id: str) -> dict[str, Any] | None:
        return self.pusher.get_task(task_id)

    # ---------------- 清理 ----------------

    async def close(self) -> None:
        await self.pusher.close()
        await self.logrecv.close()
