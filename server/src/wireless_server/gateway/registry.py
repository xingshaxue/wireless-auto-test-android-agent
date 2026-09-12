"""AgentRegistry：在线 agent 台账 + 心跳看门狗。

同 agent_id 重连踢旧连接（§7.1）；看门狗按
heartbeat_timeout_factor × max(agent 实际上报间隔, default_heartbeat_interval_ms)
判定离线并 close（§9 计时一律单调时钟）。
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import TYPE_CHECKING, Any

from .session import AgentSession

if TYPE_CHECKING:
    from ..settings import GatewaySettings

logger = logging.getLogger(__name__)


class AgentRegistry:
    """agent_id → AgentSession。watchdog 由 start()/stop() 管理。"""

    def __init__(self, settings: "GatewaySettings") -> None:
        self._settings = settings
        self._sessions: dict[str, AgentSession] = {}
        # agent 实际上报心跳间隔（REGISTER 时取配置 heartbeatIntervalMs）
        self._hb_interval_ms: dict[str, int] = {}
        self._last_offline: dict[str, int] = {}  # agent_id → 离线墙钟毫秒
        self._watchdog_task: asyncio.Task | None = None

    # ---------------- 登记 / 注销 ----------------

    async def register(self, session: AgentSession, heartbeat_interval_ms: int | None = None) -> None:
        """登记在线 agent；同 agent_id 旧连接踢掉（§7.1 重连语义）。"""
        assert session.agent_id is not None
        old = self._sessions.get(session.agent_id)
        if old is not None and old is not session:
            logger.info("agent %s 重连，踢掉旧 session %s",
                        session.agent_id, old.session_id)
            await old.close()
        self._sessions[session.agent_id] = session
        self._hb_interval_ms[session.agent_id] = (
            heartbeat_interval_ms or self._settings.default_heartbeat_interval_ms
        )
        self._last_offline.pop(session.agent_id, None)

    def unregister(self, session: AgentSession) -> None:
        """注销（仅当登记在册的仍是该 session，避免误删重连后的新连接）。"""
        if session.agent_id is None:
            return
        if self._sessions.get(session.agent_id) is session:
            del self._sessions[session.agent_id]
            self._last_offline[session.agent_id] = int(time.time() * 1000)

    def note_heartbeat_interval(self, agent_id: str, interval_ms: int) -> None:
        """更新 agent 实际上报心跳间隔（看门狗超时基数）。"""
        if interval_ms > 0:
            self._hb_interval_ms[agent_id] = interval_ms

    # ---------------- 查询 ----------------

    def get(self, agent_id: str) -> AgentSession | None:
        return self._sessions.get(agent_id)

    def online_ids(self) -> list[str]:
        return sorted(self._sessions)

    def list_agents(self) -> list[dict[str, Any]]:
        """在线 agent 状态快照（单调时钟差值换算为秒）。"""
        now = asyncio.get_running_loop().time()
        return [
            {
                "agentId": aid,
                "sessionId": s.session_id,
                "peer": s.peer,
                "connectedSec": round(now - s.connected_ts, 3),
                "lastSeenSec": round(now - s.last_seen_ts, 3),
                "heartbeatIntervalMs": self._hb_interval_ms.get(
                    aid, self._settings.default_heartbeat_interval_ms),
                "state": "online",
            }
            for aid, s in sorted(self._sessions.items())
        ]

    def last_offline_ts(self, agent_id: str) -> int | None:
        return self._last_offline.get(agent_id)

    # ---------------- 看门狗 ----------------

    async def start(self) -> None:
        self._watchdog_task = asyncio.create_task(
            self._watchdog_loop(), name="gw-heartbeat-watchdog"
        )

    async def stop(self) -> None:
        if self._watchdog_task is not None:
            self._watchdog_task.cancel()
            try:
                await self._watchdog_task
            except asyncio.CancelledError:
                pass
            self._watchdog_task = None

    async def _watchdog_loop(self) -> None:
        s = self._settings
        check_sec = max(s.default_heartbeat_interval_ms / 1000.0, 0.2)
        while True:
            await asyncio.sleep(check_sec)
            now = asyncio.get_running_loop().time()
            for aid, session in list(self._sessions.items()):
                interval = max(
                    self._hb_interval_ms.get(aid, s.default_heartbeat_interval_ms),
                    s.default_heartbeat_interval_ms,
                )
                timeout_sec = s.heartbeat_timeout_factor * interval / 1000.0
                if now - session.last_seen_ts > timeout_sec:
                    logger.warning(
                        "agent %s 心跳超时（%.1fs 无消息，阈值 %.1fs），标记离线并断开",
                        aid, now - session.last_seen_ts, timeout_sec,
                    )
                    await session.close()  # close → on_close → unregister
