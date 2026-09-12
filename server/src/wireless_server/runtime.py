"""runtime：装配与编排（__main__.py 入口）。

装配顺序（对齐 __main__ docstring）：store → configsvc → ingest → ledger →
registry → gateway(TCP) → api(FastAPI/uvicorn)；transfer / engine 后续挂接
（gateway.on_frame_handlers 与 ingest.add_listener / ledger.add_ack_listener）。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

import uvicorn

from .api import create_app
from .configsvc import ConfigManager, Store
from .engine import ScenarioRunner
from .gateway import AgentRegistry, AgentSession, GatewayServer
from .ingest import EventIngest
from .ledger import CommandLedger
from .protocol import build_command
from .protocol.errors import ProtocolErrorCode
from .settings import Settings
from .transfer import TransferManager

logger = logging.getLogger(__name__)


class Runtime:
    """装配对象：供 API 层依赖注入，engine / transfer 后续复用同一实例。"""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.store = Store(settings.storage.db_path)
        self.config_manager = ConfigManager(self.store)
        self.ingest = EventIngest(self.store)
        self.ledger = CommandLedger(settings.gateway)
        self.registry = AgentRegistry(settings.gateway)
        self.gateway = GatewayServer(settings.gateway, self.registry)
        self.transfer: TransferManager | None = None
        self.engine = ScenarioRunner(self)  # §14 测试编排引擎
        self._uvicorn: uvicorn.Server | None = None
        self._api_task: asyncio.Task | None = None

    # ---------------- 装配 ----------------

    async def start(self) -> None:
        await self.store.init()
        # 编排回调挂接
        self.gateway.on_json = self._handle_json
        self.gateway.on_disconnect = self._handle_disconnect
        self.ledger.add_ack_listener(self._on_ack)
        # transfer 模块装配（§7.6 / §13）：挂帧处理器、事件 listener 与断连通知
        self.transfer = TransferManager(self)
        self.transfer.attach(self)
        await self.engine.start()  # §14：订阅 ingest 实时事件流
        await self.registry.start()  # 心跳看门狗
        await self.gateway.start()
        await self._start_api()

    async def stop(self) -> None:
        if self._uvicorn is not None:
            self._uvicorn.should_exit = True
            if self._api_task is not None:
                await self._api_task
            self._uvicorn = None
            self._api_task = None
        await self.engine.stop()  # 取消全部在跑场景并等其落库收尾
        await self.gateway.stop()
        await self.registry.stop()
        if self.transfer is not None:
            await self.transfer.close()
            self.transfer = None
        await self.store.close()

    @property
    def gateway_port(self) -> int:
        return self.gateway.port

    @property
    def api_port(self) -> int:
        if self._uvicorn and self._uvicorn.servers:
            sock = self._uvicorn.servers[0].sockets[0]
            return int(sock.getsockname()[1])
        return self.settings.api.port

    async def _start_api(self) -> None:
        api = self.settings.api
        kwargs: dict[str, Any] = {}
        if api.tls_cert:
            kwargs["ssl_certfile"] = api.tls_cert
            kwargs["ssl_keyfile"] = api.tls_key
        config = uvicorn.Config(
            create_app(self), host=api.host, port=api.port,
            log_level="warning", **kwargs,
        )
        self._uvicorn = uvicorn.Server(config)
        self._api_task = asyncio.create_task(self._uvicorn.serve(), name="api-uvicorn")
        while not self._uvicorn.started:
            await asyncio.sleep(0.01)
        logger.info("API 监听 %s:%d", api.host, self.api_port)

    # ---------------- 编排回调 ----------------

    async def _handle_json(self, session: AgentSession, raw: dict) -> None:
        etype = raw.get("type")
        if session.agent_id is None:
            # 未注册连接只接受 REGISTER（§7.1 / §11.2）
            if etype == "REGISTER":
                await self._handle_register(session, raw)
            else:
                logger.warning("session %s 未注册先收 %s，忽略",
                               session.session_id, etype)
            return
        if etype == "CMD_ACK":
            self.ledger.on_cmd_ack(session.agent_id, raw)
        elif etype == "REGISTER":
            await self._handle_register(session, raw)  # 重注册（换 deviceId 等）
        else:
            await self.ingest.handle_event(session.agent_id, raw)

    async def _handle_register(self, session: AgentSession, raw: dict) -> None:
        # token 鉴权（§11.2）：不符回 2xxx 协议错误并断开
        if raw.get("token") != self.settings.gateway.agent_token:
            logger.warning("session %s REGISTER token 校验失败，拒绝", session.session_id)
            await session.send_json(build_command(
                "REGISTER_ACK", errorCode=int(ProtocolErrorCode.BAD_MESSAGE)
            ).to_wire())
            await session.close(flush=True)  # 先把拒绝回包发出去再断开
            return
        agent_id = raw.get("deviceId")
        if not agent_id:
            await session.send_json(build_command(
                "REGISTER_ACK", errorCode=int(ProtocolErrorCode.BAD_MESSAGE)
            ).to_wire())
            await session.close(flush=True)
            return

        session.agent_id = agent_id
        info = {k: v for k, v in raw.items() if k != "token"}
        await self.store.upsert_agent(agent_id, info)
        config = await self.config_manager.build_config(agent_id)
        await self.registry.register(
            session,
            heartbeat_interval_ms=config.get("heartbeatIntervalMs"),
        )
        await session.send_json(build_command(
            "REGISTER_ACK", errorCode=0, config=config
        ).to_wire())
        logger.info("agent %s 注册成功，configVersion=%d",
                    agent_id, config["configVersion"])

    async def _handle_disconnect(self, session: AgentSession) -> None:
        # registry 注销已由 gateway._handle_close 完成；此处留扩展点
        pass

    async def _on_ack(self, agent_id: str, info: dict[str, Any]) -> None:
        """ACK 钩子：GET_STATUS 的 result 合并进 ingest 视图（A.3）。"""
        if info["type"] == "GET_STATUS" and info["ledgerStatus"] == "acked":
            self.ingest.handle_status_result(agent_id, info["result"])


async def run(settings: Settings) -> None:
    """启动全部组件并常驻；CancelledError → 优雅 stop。"""
    runtime = Runtime(settings)
    await runtime.start()
    try:
        await asyncio.Event().wait()  # 常驻，直至被取消
    except asyncio.CancelledError:
        pass
    finally:
        await runtime.stop()
