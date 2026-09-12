"""FastAPI app 工厂。依赖注入：create_app(runtime) 持有 Runtime 装配对象。

鉴权：HTTP Bearer，token 取 settings.api.token（§11.2），不符一律 401；
WebSocket 不便用 header，走 /ws/events?token=xxx query 参数（不符拒绝握手）。
"""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING

from fastapi import Depends, FastAPI, WebSocket
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .routes import build_router

if TYPE_CHECKING:
    from ..runtime import Runtime

logger = logging.getLogger(__name__)

_bearer = HTTPBearer(auto_error=False)

# WS 事件推送队列上限（慢消费者丢弃，不阻塞 ingest 同步回调）
_WS_QUEUE_MAX = 1000


def create_app(runtime: "Runtime") -> FastAPI:
    token = runtime.settings.api.token

    async def auth(
        cred: HTTPAuthorizationCredentials | None = Depends(_bearer),
    ) -> None:
        from fastapi import HTTPException

        if cred is None or cred.credentials != token:
            raise HTTPException(status_code=401, detail="鉴权失败")

    app = FastAPI(title="wireless-server", version="1.0.0")
    app.include_router(build_router(runtime), prefix="/api",
                       dependencies=[Depends(auth)])

    @app.websocket("/ws/events")
    async def ws_events(ws: WebSocket, token: str = "", type: str = "") -> None:
        """实时事件流：注册 ingest listener，事件以 JSON 推送。

        报文：{"agentId", "type", "ts", "payload"}；query 参数 type 支持
        逗号分隔过滤（如 type=DEVICE_STATE,POLL_RESULT）。断开即清理 listener。
        """
        if token != runtime.settings.api.token:
            await ws.close(code=4401)  # 握手拒绝（未 accept，客户端见 403）
            return
        types = {t for t in type.split(",") if t} or None
        queue: asyncio.Queue[dict] = asyncio.Queue(maxsize=_WS_QUEUE_MAX)

        def listener(agent_id: str, etype: str, raw: dict) -> None:
            if types is not None and etype not in types:
                return
            msg = {"agentId": agent_id, "type": etype,
                   "ts": int(raw.get("timestamp", 0) or 0), "payload": raw}
            try:
                queue.put_nowait(msg)
            except asyncio.QueueFull:
                pass  # 慢消费者丢帧，绝不阻塞 ingest 热路径

        # 先注册 listener 再 accept：握手完成即保证订阅生效，不丢 accept 后立即到达的事件
        runtime.ingest.add_listener(listener)
        try:
            await ws.accept()
        except Exception:
            runtime.ingest.remove_listener(listener)
            raise
        recv_task = asyncio.create_task(ws.receive())  # 断连探针
        try:
            while True:
                get_task = asyncio.ensure_future(queue.get())
                done, _ = await asyncio.wait(
                    {get_task, recv_task}, return_when=asyncio.FIRST_COMPLETED)
                if recv_task in done:
                    get_task.cancel()
                    break  # 客户端断开（或发来消息，本协议纯推送，直接收尾）
                await ws.send_json(get_task.result())
        finally:
            recv_task.cancel()
            runtime.ingest.remove_listener(listener)  # 断开清理

    return app
