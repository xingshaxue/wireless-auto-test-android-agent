"""LogReceiver：agent 日志上传接收（SDD §13 上传通道）。

服务器下发 UPLOAD_LOG → agent 打包 → 经 TCP 二进制帧 LOG_FRAME（0x04，复用
§16.3 帧格式）上传 → 完成上报 LOG_UPLOAD_DONE（A.3：requestId+errorCode+size）。
落盘到 logs_dir/{agent_id}/{upload_id}.log，upload_id 取 UPLOAD_LOG 的 requestId。
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import TYPE_CHECKING, Any, BinaryIO

if TYPE_CHECKING:
    from ..gateway.registry import AgentRegistry
    from ..gateway.session import AgentSession
    from ..ledger import CommandLedger
    from ..settings import Settings

logger = logging.getLogger(__name__)

FT_LOG_FRAME = 0x04  # §16.3

# 已完成上传记录保留上限（防内存膨胀）
_DONE_KEEP = 200


class LogReceiver:
    """日志接收器。挂 gateway.on_frame_handlers 接管 LOG_FRAME。"""

    def __init__(self, settings: "Settings", ledger: "CommandLedger",
                 registry: "AgentRegistry") -> None:
        self._settings = settings
        self._ledger = ledger
        self._registry = registry
        self._uploads: dict[str, dict[str, Any]] = {}  # agent_id → 当前上传会话
        self._done: list[dict[str, Any]] = []

    # ---------------- 触发入口 ----------------

    async def request_log_upload(self, agent_id: str,
                                 since_ts: int | None = None,
                                 min_level: str | None = None) -> str:
        """下发 UPLOAD_LOG（A.2：sinceTs/minLevel 可选），返回 requestId。"""
        session = self._registry.get(agent_id)
        if session is None or session.closed:
            raise ValueError(f"agent 不在线: {agent_id}")
        fields: dict[str, Any] = {}
        if since_ts is not None:
            fields["sinceTs"] = since_ts
        if min_level is not None:
            fields["minLevel"] = min_level
        request_id = await self._ledger.send_command(
            session, "UPLOAD_LOG", **fields)
        path = Path(self._settings.storage.logs_dir) / agent_id / f"{request_id}.log"
        path.parent.mkdir(parents=True, exist_ok=True)
        old = self._uploads.get(agent_id)
        if old is not None:
            self._finish(old, "ERROR")  # 上一会话未闭环即被新请求取代
            logger.warning("agent %s 上一日志上传 %s 未闭环，被 %s 取代",
                           agent_id, old["uploadId"], request_id)
        self._uploads[agent_id] = {
            "uploadId": request_id, "agentId": agent_id, "path": str(path),
            "bytes": 0, "state": "RECEIVING",
            "_fh": open(path, "wb"),
        }
        logger.info("agent %s 请求日志上传 requestId=%s → %s",
                    agent_id, request_id, path)
        return request_id

    # ---------------- 帧与事件 ----------------

    async def handle_frame(self, session: "AgentSession", ftype: int,
                           seq: int, payload: bytes) -> None:
        """gateway.on_frame_handlers 钩子：只接管 LOG_FRAME，其余直接返回。"""
        if ftype != FT_LOG_FRAME:
            return
        agent_id = session.agent_id
        up = self._uploads.get(agent_id) if agent_id else None
        if up is None:
            logger.warning("session %s 收到无上传会话的 LOG_FRAME seq=%d，丢弃",
                           session.session_id, seq)
            return
        fh: BinaryIO = up["_fh"]
        fh.write(payload)
        fh.flush()
        up["bytes"] += len(payload)

    def on_event(self, agent_id: str, etype: str, raw: dict) -> None:
        """LOG_UPLOAD_DONE（A.3）：关闭文件并校验累计字节数与 size 一致。"""
        if etype != "LOG_UPLOAD_DONE":
            return
        up = self._uploads.pop(agent_id, None)
        if up is None:
            logger.warning("agent %s LOG_UPLOAD_DONE requestId=%s 无对应上传会话",
                           agent_id, raw.get("requestId"))
            return
        if up["uploadId"] != raw.get("requestId"):
            logger.warning("agent %s LOG_UPLOAD_DONE requestId=%s 与当前会话 %s 不符",
                           agent_id, raw.get("requestId"), up["uploadId"])
        code = int(raw.get("errorCode", 0))
        size = int(raw.get("size", -1))
        if size != up["bytes"]:
            logger.warning("agent %s 日志上传 %s 字节数不符: 上报 %d，实收 %d",
                           agent_id, up["uploadId"], size, up["bytes"])
        self._finish(up, "DONE" if code == 0 else "ERROR")
        logger.info("agent %s 日志上传 %s 结束 errorCode=%d %dB → %s",
                    agent_id, up["uploadId"], code, up["bytes"], up["path"])

    # ---------------- 查询与清理 ----------------

    def _finish(self, up: dict[str, Any], state: str) -> None:
        fh = up.pop("_fh", None)
        if fh is not None:
            fh.close()
        up["state"] = state
        self._done.append(dict(up))
        if len(self._done) > _DONE_KEEP:
            del self._done[: len(self._done) - _DONE_KEEP]

    def get_upload(self, agent_id: str) -> dict[str, Any] | None:
        up = self._uploads.get(agent_id)
        return {k: v for k, v in up.items() if not k.startswith("_")} if up else None

    async def close(self) -> None:
        for up in self._uploads.values():
            fh = up.pop("_fh", None)
            if fh is not None:
                fh.close()
        self._uploads.clear()
