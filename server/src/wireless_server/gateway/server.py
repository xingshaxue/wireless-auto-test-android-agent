"""GatewayServer：TCP 接入层（SDD §16.3 / §11.2）。

asyncio.start_server 起服务；settings.gateway.tls_cert 非空时启用 TLS，
握手失败不降级明文（§11.2）。编排层通过 on_json / on_disconnect 回调与
on_frame_handlers 钩子列表接管业务逻辑（transfer 模块后续挂 on_frame_handlers）。
"""

from __future__ import annotations

import asyncio
import logging
import ssl
from typing import TYPE_CHECKING, Any, Awaitable, Callable

from .registry import AgentRegistry
from .session import AgentSession

if TYPE_CHECKING:
    from ..settings import GatewaySettings

logger = logging.getLogger(__name__)

OnJson = Callable[[AgentSession, dict], Awaitable[None]]
OnDisconnect = Callable[[AgentSession], Awaitable[None]]
OnFrame = Callable[[AgentSession, int, int, bytes], Awaitable[None]]


class GatewayServer:
    """TCP 网关。accept → AgentSession → 编排回调。"""

    def __init__(self, settings: "GatewaySettings", registry: AgentRegistry) -> None:
        self._settings = settings
        self._registry = registry
        self._server: asyncio.AbstractServer | None = None
        # 编排回调（runtime 装配时赋值）
        self.on_json: OnJson | None = None
        self.on_disconnect: OnDisconnect | None = None
        # 二进制帧处理器钩子：transfer 模块后续 append 接管；本阶段仅记日志
        self.on_frame_handlers: list[OnFrame] = []
        self._sessions: set[AgentSession] = set()

    @property
    def port(self) -> int:
        """实际监听端口（配置 port=0 时取系统分配值）。"""
        if self._server and self._server.sockets:
            return int(self._server.sockets[0].getsockname()[1])
        return self._settings.port

    def _build_ssl_context(self) -> ssl.SSLContext | None:
        if not self._settings.tls_cert:
            return None
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(self._settings.tls_cert, self._settings.tls_key)
        return ctx

    async def start(self) -> None:
        ssl_ctx = self._build_ssl_context()
        self._server = await asyncio.start_server(
            self._accept, self._settings.host, self._settings.port, ssl=ssl_ctx
        )
        scheme = "TLS" if ssl_ctx else "plain"
        logger.info("网关监听 %s:%d (%s)",
                    self._settings.host, self.port, scheme)

    async def stop(self) -> None:
        """优雅关闭：停止 accept，关闭全部 session。"""
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
        await asyncio.gather(
            *(s.close() for s in list(self._sessions)), return_exceptions=True
        )

    def _accept(self, reader: asyncio.StreamReader,
                writer: asyncio.StreamWriter) -> None:
        session = AgentSession(
            reader, writer, self._settings,
            on_json=self._handle_json,
            on_frame=self._handle_frame,
            on_close=self._handle_close,
        )
        self._sessions.add(session)
        session.start()
        logger.info("接受连接 session=%s peer=%s", session.session_id, session.peer)

    async def _handle_json(self, session: AgentSession, raw: dict) -> None:
        if self.on_json is not None:
            await self.on_json(session, raw)

    async def _handle_frame(self, session: AgentSession, ftype: int,
                            seq: int, payload: bytes) -> None:
        # transfer 模块接管前仅记日志（§16.3 帧类型见 codec.FRAME_TYPES）
        logger.debug("session %s 收帧 type=%#04x seq=%d %dB",
                     session.session_id, ftype, seq, len(payload))
        for handler in self.on_frame_handlers:
            await handler(session, ftype, seq, payload)

    async def _handle_close(self, session: AgentSession) -> None:
        self._sessions.discard(session)
        self._registry.unregister(session)
        if self.on_disconnect is not None:
            await self.on_disconnect(session)
        logger.info("连接关闭 session=%s agent=%s",
                    session.session_id, session.agent_id)
