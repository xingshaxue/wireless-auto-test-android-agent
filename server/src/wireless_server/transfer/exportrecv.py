"""ExportReceiver：设备文件导出接收（DUT→手机→server，docs/02 B.6）。

POST /api/exports 下发 FILE_EXPORT → agent 侧 LcExporter 跑 061/062/063 拉文件
→ 拉齐后经 TCP 二进制帧 EXPORT_FRAME(0x05)/EXPORT_END(0x06) 回传（复用 §16.3
帧格式，序列化在 agent 单工作线程内，同 agent 同时只有一个导出会话）→
EXPORT_END 校验 SHA-256 后登记 files 表（origin="device"，meta 带
exportId/deviceMac/agentId）→ EXPORT_RESULT 事件收尾。

帧 payload 固定布局（每文件独立 seq 序列，从 1 连续编号）：
  EXPORT_FRAME = u16BE 文件名长度 + 文件名 UTF-8 + 文件数据
  EXPORT_END   = u16BE 文件名长度 + 文件名 UTF-8 + 32B SHA-256
"""

from __future__ import annotations

import hashlib
import logging
import struct
import time
import uuid
from pathlib import Path
from typing import TYPE_CHECKING, Any, Awaitable, Callable, BinaryIO

if TYPE_CHECKING:
    from ..gateway.registry import AgentRegistry
    from ..gateway.session import AgentSession
    from ..ledger import CommandLedger
    from ..settings import Settings

logger = logging.getLogger(__name__)

FT_EXPORT_FRAME = 0x05  # §16.3 扩展
FT_EXPORT_END = 0x06

_U16 = struct.Struct(">H")

# 已完成导出记录保留上限（防内存膨胀）
_DONE_KEEP = 200

# 导出会话僵尸 TTL：agent 中途死亡留下的 RUNNING 会话超过此时长允许被取代
# （BLE 大目录导出实测 40+ 分钟，取 2 小时余量）。
EXPORT_SESSION_TTL_S = 2 * 3600.0

# register_file 回调（TransferManager.register_file，含配额检查）
RegisterFileFn = Callable[..., Awaitable[str]]


def _split_name(payload: bytes) -> tuple[str, bytes] | None:
    """解析 u16BE 文件名长度 + 文件名 + 数据；非法返回 None。"""
    if len(payload) < 2:
        return None
    nlen = _U16.unpack(payload[:2])[0]
    if len(payload) < 2 + nlen or nlen == 0:
        return None
    name = payload[2:2 + nlen].decode("utf-8", errors="replace")
    return name, payload[2 + nlen:]


class ExportReceiver:
    """导出接收器。挂 gateway.on_frame_handlers 接管 EXPORT_FRAME/EXPORT_END。"""

    def __init__(self, settings: "Settings", ledger: "CommandLedger",
                 registry: "AgentRegistry", register_file: RegisterFileFn) -> None:
        self._settings = settings
        self._ledger = ledger
        self._registry = registry
        self._register_file = register_file
        self._exports: dict[str, dict[str, Any]] = {}  # agent_id → 当前导出会话
        self._done: list[dict[str, Any]] = []

    # ---------------- 触发入口 ----------------

    async def request_export(self, agent_id: str, device_mac: str,
                             remote_path: str) -> str:
        """下发 FILE_EXPORT（exportId 服务端生成），返回 exportId。"""
        session = self._registry.get(agent_id)
        if session is None or session.closed:
            raise ValueError(f"agent 不在线: {agent_id}")
        if not remote_path:
            raise ValueError("remotePath 不能为空")
        export_id = "export-" + uuid.uuid4().hex[:8]
        old = self._exports.get(agent_id)
        if old is not None:
            # agent 导出单线程串行：上一会话未闭环时新请求一律 409，
            # 不允许顶替——顶替会让旧任务的结果错记到新会话、新任务的帧
            # 在旧会话关闭后被丢弃（真机实测目录导出与单文件导出互相污染）。
            # 例外：会话超过 TTL（agent 中途死亡的僵尸会话）允许取代。
            if time.monotonic() - old.get("_t0", 0.0) < EXPORT_SESSION_TTL_S:
                raise ValueError(f"agent {agent_id} 有进行中的导出 {old['exportId']}，"
                                 "待其闭环后再下发")
            self._finish(old, "ERROR")
            logger.warning("agent %s 僵尸导出会话 %s 超 TTL 被 %s 取代",
                           agent_id, old["exportId"], export_id)
        dest_dir = (Path(self._settings.storage.files_dir)
                    / "exports" / export_id)
        dest_dir.mkdir(parents=True, exist_ok=True)
        await self._ledger.send_command(
            session, "FILE_EXPORT",
            deviceMac=device_mac, remotePath=remote_path, exportId=export_id)
        self._exports[agent_id] = {
            "exportId": export_id, "agentId": agent_id, "deviceMac": device_mac,
            "remotePath": remote_path, "dir": str(dest_dir),
            "files": [], "errors": [], "state": "RUNNING",
            "_handles": {}, "_hashers": {}, "_t0": time.monotonic(),
        }
        logger.info("agent %s 请求设备导出 exportId=%s %s:%s → %s",
                    agent_id, export_id, device_mac, remote_path, dest_dir)
        return export_id

    # ---------------- 帧与事件 ----------------

    async def handle_frame(self, session: "AgentSession", ftype: int,
                           seq: int, payload: bytes) -> None:
        """gateway.on_frame_handlers 钩子：只接管 EXPORT_FRAME/EXPORT_END。"""
        if ftype not in (FT_EXPORT_FRAME, FT_EXPORT_END):
            return
        agent_id = session.agent_id
        exp = self._exports.get(agent_id) if agent_id else None
        if exp is None:
            logger.warning("session %s 收到无导出会话的帧 type=%#04x seq=%d，丢弃",
                           session.session_id, ftype, seq)
            return
        parsed = _split_name(payload)
        if parsed is None:
            logger.warning("agent %s 导出帧 payload 非法 seq=%d，丢弃", agent_id, seq)
            return
        name, data = parsed
        safe = Path(name).name  # 防路径穿越：只取 basename
        if ftype == FT_EXPORT_FRAME:
            self._on_export_frame(exp, safe, data)
        else:
            await self._on_export_end(exp, safe, data)

    def _on_export_frame(self, exp: dict[str, Any], name: str, data: bytes) -> None:
        handles: dict[str, BinaryIO] = exp["_handles"]
        fh = handles.get(name)
        if fh is None:
            fh = open(Path(exp["dir"]) / name, "wb")
            handles[name] = fh
            exp["_hashers"][name] = hashlib.sha256()
        fh.write(data)
        fh.flush()
        exp["_hashers"][name].update(data)

    async def _on_export_end(self, exp: dict[str, Any], name: str,
                             sha256: bytes) -> None:
        fh = exp["_handles"].pop(name, None)
        if fh is not None:
            fh.close()
        hasher = exp["_hashers"].pop(name, None)
        path = Path(exp["dir"]) / name
        if hasher is None:
            # 零字节文件：无 EXPORT_FRAME，直接落空文件
            if not path.exists():
                path.touch()
            hasher = hashlib.sha256()
        actual = hasher.digest()
        size = path.stat().st_size
        if actual != sha256:
            logger.warning("agent %s 导出文件 %s SHA-256 不符（实收 %dB），不登记",
                           exp["agentId"], name, size)
            exp["errors"].append({"name": name, "reason": "sha256 mismatch"})
            return
        file_id = await self._register_file(
            path, name=name, origin="device",
            meta={"exportId": exp["exportId"], "deviceMac": exp["deviceMac"],
                  "agentId": exp["agentId"]})
        exp["files"].append({"name": name, "size": size, "fileId": file_id})
        logger.info("agent %s 导出文件 %s（%dB）登记为 %s",
                    exp["agentId"], name, size, file_id)

    def on_event(self, agent_id: str, etype: str, raw: dict) -> None:
        """EXPORT_PROGRESS：进行中去抖动进度（agent 节流上报，覆盖式更新）；
        EXPORT_RESULT：导出收尾（errorCode=0 成功），关闭残留句柄归档会话。"""
        if etype == "EXPORT_PROGRESS":
            exp = self._exports.get(agent_id)
            if exp is None or exp["exportId"] != raw.get("exportId"):
                return
            exp["progress"] = {
                "file": raw.get("file"),
                "fileReceived": raw.get("fileReceived"),
                "fileSize": raw.get("fileSize"),
                "filesDone": raw.get("filesDone"),
                "filesTotal": raw.get("filesTotal"),
                "channel": raw.get("channel"),
            }
            return
        if etype != "EXPORT_RESULT":
            return
        exp = self._exports.pop(agent_id, None)
        if exp is None:
            logger.warning("agent %s EXPORT_RESULT exportId=%s 无对应导出会话",
                           agent_id, raw.get("exportId"))
            return
        if exp["exportId"] != raw.get("exportId"):
            logger.warning("agent %s EXPORT_RESULT exportId=%s 与当前会话 %s 不符",
                           agent_id, raw.get("exportId"), exp["exportId"])
        code = int(raw.get("errorCode", 0))
        exp["errorCode"] = code
        exp["detail"] = raw.get("detail")
        self._finish(exp, "DONE" if code == 0 else "ERROR")
        logger.info("agent %s 导出 %s 结束 errorCode=%d files=%d errors=%d",
                    agent_id, exp["exportId"], code,
                    len(exp["files"]), len(exp["errors"]))

    # ---------------- 查询与清理 ----------------

    def _finish(self, exp: dict[str, Any], state: str) -> None:
        for fh in exp.pop("_handles", {}).values():
            fh.close()
        exp.pop("_hashers", None)
        exp["state"] = state
        self._done.append(dict(exp))
        if len(self._done) > _DONE_KEEP:
            del self._done[: len(self._done) - _DONE_KEEP]

    def get_export(self, agent_id: str) -> dict[str, Any] | None:
        exp = self._exports.get(agent_id)
        return {k: v for k, v in exp.items() if not k.startswith("_")} if exp else None

    def list_exports(self) -> dict[str, Any]:
        """进行中会话（含 EXPORT_PROGRESS 实时进度）+ 最近完成记录（新在前）。"""
        running = [{k: v for k, v in exp.items() if not k.startswith("_")}
                   for exp in self._exports.values()]
        done = [{k: v for k, v in exp.items() if not k.startswith("_")}
                for exp in reversed(self._done[-50:])]
        return {"running": running, "done": done}

    async def close(self) -> None:
        for exp in self._exports.values():
            for fh in exp.pop("_handles", {}).values():
                fh.close()
        self._exports.clear()
