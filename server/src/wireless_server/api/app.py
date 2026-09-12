"""FastAPI app 工厂。依赖注入：create_app(runtime) 持有 Runtime 装配对象。

纯内网部署，REST 与 WebSocket 均无鉴权。
"""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING

from fastapi import FastAPI, WebSocket

from .routes import build_router

if TYPE_CHECKING:
    from ..runtime import Runtime

logger = logging.getLogger(__name__)

# WS 事件推送队列上限（慢消费者丢弃，不阻塞 ingest 同步回调）
_WS_QUEUE_MAX = 1000


def create_app(runtime: "Runtime") -> FastAPI:
    app = FastAPI(title="wireless-server", version="1.0.0")
    app.include_router(build_router(runtime), prefix="/api")

    @app.websocket("/ws/events")
    async def ws_events(ws: WebSocket, type: str = "") -> None:
        """实时事件流：注册 ingest listener，事件以 JSON 推送。

        报文：{"agentId", "type", "ts", "payload"}；query 参数 type 支持
        逗号分隔过滤（如 type=DEVICE_STATE,POLL_RESULT）。断开即清理 listener。
        """
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

    # 前端静态托管：web/dist 存在时挂载到 /（SPA fallback 到 index.html）；
    # 不存在则纯 API 模式。/api 与 /ws 路由已在上方注册，优先级高于静态挂载。
    _mount_web_console(app)

    return app


def _mount_web_console(app: FastAPI) -> None:
    from pathlib import Path

    from fastapi.responses import FileResponse

    dist = Path(__file__).resolve().parents[4] / "web" / "dist"
    if not (dist / "index.html").is_file():
        logger.info("web/dist 不存在，纯 API 模式（前端未构建）")
        return

    @app.get("/{full_path:path}", include_in_schema=False)
    async def spa(full_path: str) -> FileResponse:
        # 命中静态资源直接返回；其余路径回退 index.html（SPA 前端路由）
        candidate = dist / full_path
        if full_path and candidate.is_file():
            return FileResponse(candidate)
        return FileResponse(dist / "index.html")

    logger.info("Web 控制台已挂载: %s", dist)
