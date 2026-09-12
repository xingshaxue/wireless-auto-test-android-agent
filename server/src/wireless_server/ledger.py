"""CommandLedger：命令台账与 CMD_ACK 对账（SDD §8.4 / §12.9）。

每条下发命令按 requestId 登记；ACK 到达销账，超时按 ack_max_retries 重发，
仍失败标记 failed。限流类错误（3001/3002，§12.9）单独计数，提示调用方放缓
下发而非盲目重试。计时一律 loop.time() 单调时钟（§9）。
"""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING, Any, Awaitable, Callable

from .protocol import build_command
from .protocol.errors import is_throttle_error

if TYPE_CHECKING:
    from .gateway.session import AgentSession
    from .settings import GatewaySettings

logger = logging.getLogger(__name__)

# wait_ack 等待者收到的失败占位码（本地台账判超时，非协议错误码）
ACK_TIMEOUT_CODE = -1

# 已完成台账保留上限（防内存膨胀）
_DONE_KEEP = 1000

AckListener = Callable[[str, dict[str, Any]], Awaitable[None]]


class CommandLedger:
    """命令台账。settings 取 GatewaySettings（ack_timeout_ms / ack_max_retries）。"""

    def __init__(self, settings: "GatewaySettings") -> None:
        self._settings = settings
        self._records: dict[str, dict[str, Any]] = {}  # requestId → 在途记录
        self._done: list[dict[str, Any]] = []  # 最近完成（acked/failed）
        self._throttled_count = 0
        self._acked_count = 0
        self._failed_count = 0
        self._ack_listeners: list[AckListener] = []

    # ---------------- 登记与下发 ----------------

    def track(self, request_id: str, cmd_type: str, agent_id: str,
              target_mac: str | None = None) -> dict[str, Any]:
        """登记台账记录（send_command 内部调用；外部预登记也可用）。"""
        record = {
            "requestId": request_id,
            "type": cmd_type,
            "agentId": agent_id,
            "targetMac": target_mac,
            "status": "pending",  # pending / acked / failed
            "attempts": 0,
            "sentTs": None,  # loop.time() 单调时钟
            "errorCode": None,
            "rawStatus": None,
            "result": None,
            "session": None,
            "wire": None,
            "timer": None,
            "waiter": None,
        }
        self._records[request_id] = record
        return record

    async def send_command(self, session: "AgentSession", cmd_type: str,
                           **fields: Any) -> str:
        """组包 → 下发 → 登记台账 → 启动 ACK 超时定时器，返回 requestId。"""
        wire = build_command(cmd_type, **fields).to_wire()
        request_id = wire["requestId"]
        agent_id = session.agent_id or ""
        record = self.track(request_id, cmd_type, agent_id,
                            target_mac=fields.get("deviceMac"))
        record["session"] = session
        record["wire"] = wire
        record["waiter"] = asyncio.get_running_loop().create_future()
        await self._send(record)
        return request_id

    async def _send(self, record: dict[str, Any]) -> None:
        record["attempts"] += 1
        record["sentTs"] = asyncio.get_running_loop().time()
        await record["session"].send_json(record["wire"])
        self._arm_timer(record)

    def _arm_timer(self, record: dict[str, Any]) -> None:
        timeout = self._settings.ack_timeout_ms / 1000.0
        record["timer"] = asyncio.get_running_loop().call_later(
            timeout, self._on_timeout, record["requestId"]
        )

    # ---------------- ACK 对账 ----------------

    def on_cmd_ack(self, agent_id: str, raw: dict[str, Any]) -> None:
        """按 requestId 销账（§8.4）；未知 requestId 记告警丢弃。"""
        request_id = raw.get("requestId")
        record = self._records.pop(request_id, None)
        if record is None:
            logger.warning("agent %s 收到未知 requestId=%s 的 CMD_ACK，丢弃",
                           agent_id, request_id)
            return
        if record["timer"] is not None:
            record["timer"].cancel()
        code = int(raw.get("errorCode", 0))
        record.update(
            status="acked",
            errorCode=code,
            rawStatus=raw.get("rawStatus"),
            result=raw.get("result"),
        )
        self._acked_count += 1
        if is_throttle_error(code):
            # 背压类错误（§12.9）：调用方应放缓下发而非重试
            self._throttled_count += 1
            logger.warning("agent %s 限流: %s errorCode=%d（累计 %d 次）",
                           agent_id, record["type"], code, self._throttled_count)
        self._finish(record, raw)

    def _on_timeout(self, request_id: str) -> None:
        record = self._records.get(request_id)
        if record is None:
            return
        if record["attempts"] <= self._settings.ack_max_retries:
            logger.warning("agent %s 命令 %s requestId=%s ACK 超时，重发（第 %d 次）",
                           record["agentId"], record["type"], request_id,
                           record["attempts"])
            asyncio.create_task(self._resend(record))
        else:
            self._records.pop(request_id, None)
            record.update(status="failed", errorCode=ACK_TIMEOUT_CODE)
            self._failed_count += 1
            logger.error("agent %s 命令 %s requestId=%s 重发 %d 次仍无 ACK，标记 failed",
                         record["agentId"], record["type"], request_id,
                         self._settings.ack_max_retries)
            self._finish(record, None)

    async def _resend(self, record: dict[str, Any]) -> None:
        session: "AgentSession" = record["session"]
        if session.closed:
            # 连接已断，重发无意义，直接走失败路径
            self._records.pop(record["requestId"], None)
            record.update(status="failed", errorCode=ACK_TIMEOUT_CODE)
            self._failed_count += 1
            self._finish(record, None)
            return
        await self._send(record)

    def _finish(self, record: dict[str, Any], ack_raw: dict[str, Any] | None) -> None:
        waiter = record.get("waiter")
        if waiter is not None and not waiter.done():
            waiter.set_result({
                "requestId": record["requestId"],
                "type": record["type"],
                "errorCode": record["errorCode"],
                "rawStatus": record["rawStatus"],
                "result": record["result"],
                "ledgerStatus": record["status"],
            })
        self._done.append({k: v for k, v in record.items()
                           if k not in ("session", "wire", "timer", "waiter")})
        if len(self._done) > _DONE_KEEP:
            del self._done[: len(self._done) - _DONE_KEEP]
        for listener in self._ack_listeners:
            asyncio.create_task(self._notify(listener, record, ack_raw))

    async def _notify(self, listener: AckListener, record: dict[str, Any],
                      ack_raw: dict[str, Any] | None) -> None:
        try:
            await listener(record["agentId"], {
                "requestId": record["requestId"],
                "type": record["type"],
                "ledgerStatus": record["status"],
                "errorCode": record["errorCode"],
                "rawStatus": record["rawStatus"],
                "result": record["result"],
                "raw": ack_raw,
            })
        except Exception:
            logger.exception("ACK 回调钩子异常")

    # ---------------- 等待与查询 ----------------

    def add_ack_listener(self, fn: AckListener) -> None:
        """注册 ACK 回调钩子（engine/API 订阅结果用）：fn(agent_id, ack_info)。"""
        self._ack_listeners.append(fn)

    async def wait_ack(self, request_id: str, timeout: float) -> dict[str, Any]:
        """同步等待 ACK 结果；超时抛 asyncio.TimeoutError。"""
        record = self._records.get(request_id)
        if record is None or record["waiter"] is None:
            raise KeyError(f"无此在途命令: {request_id}")
        return await asyncio.wait_for(asyncio.shield(record["waiter"]), timeout)

    def pending(self) -> list[dict[str, Any]]:
        """在途命令快照（不含内部句柄）。"""
        return [
            {k: v for k, v in r.items()
             if k not in ("session", "wire", "timer", "waiter")}
            for r in self._records.values()
        ]

    def stats(self) -> dict[str, int]:
        return {
            "pending": len(self._records),
            "acked": self._acked_count,
            "failed": self._failed_count,
            "throttled": self._throttled_count,
        }
