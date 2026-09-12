"""AgentSession：一条 agent TCP 连接的全生命周期（SDD §16.3）。

读循环经 StreamDemuxer 解复用后回调编排层；写出走有界队列 + 独立 writer
协程，队列满触发背压保护——记告警并断开连接（§9 背压原则：宁可断开不阻塞）。
所有计时用 loop.time() 单调时钟（§9）。
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from typing import TYPE_CHECKING, Any, Awaitable, Callable

from ..codec import ProtocolError, StreamDemuxer, encode_frame, encode_json

if TYPE_CHECKING:
    from ..settings import GatewaySettings

logger = logging.getLogger(__name__)

OnJson = Callable[["AgentSession", dict], Awaitable[None]]
OnFrame = Callable[["AgentSession", int, int, bytes], Awaitable[None]]
OnClose = Callable[["AgentSession"], Awaitable[None]]


class AgentSession:
    """单条 agent 连接。agent_id 在 REGISTER 校验通过后赋值（§7.1 / §11.2）。"""

    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        settings: "GatewaySettings",
        on_json: OnJson | None = None,
        on_frame: OnFrame | None = None,
        on_close: OnClose | None = None,
    ) -> None:
        self.reader = reader
        self.writer = writer
        self._settings = settings
        self.session_id = uuid.uuid4().hex[:12]
        self.agent_id: str | None = None
        loop = asyncio.get_running_loop()
        self.connected_ts = loop.time()  # 单调时钟（§9）
        self.last_seen_ts = self.connected_ts
        self.demuxer = StreamDemuxer()
        self._write_queue: asyncio.Queue[bytes] = asyncio.Queue(
            maxsize=settings.write_queue_max
        )
        self._writer_task: asyncio.Task | None = None
        self._reader_task: asyncio.Task | None = None
        self._closed = False
        self._on_json = on_json
        self._on_frame = on_frame
        self._on_close = on_close

    # ---------------- 属性 ----------------

    @property
    def closed(self) -> bool:
        return self._closed

    @property
    def peer(self) -> str:
        addr = self.writer.get_extra_info("peername")
        return f"{addr[0]}:{addr[1]}" if addr else "?"

    # ---------------- 生命周期 ----------------

    def start(self) -> None:
        """启动读循环与 writer 协程（须在运行中的事件循环内调用）。"""
        self._writer_task = asyncio.create_task(
            self._writer_loop(), name=f"gw-writer-{self.session_id}"
        )
        self._reader_task = asyncio.create_task(
            self._read_loop(), name=f"gw-reader-{self.session_id}"
        )

    async def send_json(self, msg: dict[str, Any]) -> None:
        """编码并入写队列；队列满 → 记告警并断开（背压保护）。"""
        await self._enqueue(encode_json(msg))

    async def send_frame(self, ftype: int, seq: int, payload: bytes) -> None:
        await self._enqueue(encode_frame(ftype, seq, payload))

    async def _enqueue(self, data: bytes) -> None:
        if self._closed:
            return
        try:
            self._write_queue.put_nowait(data)
        except asyncio.QueueFull:
            logger.warning(
                "session %s (agent=%s) 写队列已满(%d)，断开连接（背压保护）",
                self.session_id, self.agent_id, self._settings.write_queue_max,
            )
            await self.close()

    async def close(self, *, flush: bool = False) -> None:
        """幂等关闭：停 writer、关 socket、回调 on_close（仅一次）。

        flush=True 时先等写队列排空（有界等待 2s），用于"先回包再断开"
        场景（如 REGISTER 拒绝须先把 REGISTER_ACK 发出去，§7.1）。
        """
        if self._closed:
            return
        self._closed = True
        if flush and self._writer_task is not None:
            try:
                await asyncio.wait_for(self._write_queue.join(), timeout=2.0)
            except asyncio.TimeoutError:
                logger.warning("session %s 关闭前队列未排空，丢弃剩余报文",
                               self.session_id)
        current = asyncio.current_task()
        if self._writer_task is not None and self._writer_task is not current:
            self._writer_task.cancel()
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except (ConnectionError, RuntimeError):
            pass
        if self._on_close is not None:
            try:
                await self._on_close(self)
            except Exception:
                logger.exception("session %s on_close 回调异常", self.session_id)

    # ---------------- 读写循环 ----------------

    async def _writer_loop(self) -> None:
        try:
            while True:
                data = await self._write_queue.get()
                try:
                    self.writer.write(data)
                    await self.writer.drain()
                finally:
                    self._write_queue.task_done()
        except asyncio.CancelledError:
            pass
        except (ConnectionError, RuntimeError) as e:
            logger.warning("session %s 写出失败: %s", self.session_id, e)
            await self.close()

    async def _read_loop(self) -> None:
        try:
            while True:
                data = await self.reader.read(65536)
                if not data:  # EOF：对端关闭
                    break
                for kind, item in self.demuxer.feed(data):
                    self.last_seen_ts = asyncio.get_running_loop().time()
                    try:
                        if kind == "json":
                            if self._on_json is not None:
                                await self._on_json(self, item)  # type: ignore[arg-type]
                        else:
                            ftype, seq, payload = item  # type: ignore[misc]
                            if self._on_frame is not None:
                                await self._on_frame(self, ftype, seq, payload)
                    except Exception:
                        # 单条消息处理失败（如事件校验不通过）不拆会话，记日志后继续
                        logger.warning("session %s 消息处理异常（已跳过该条）",
                                       self.session_id, exc_info=True)
        except ProtocolError as e:
            # 坏数据一律断连，不静默吞掉（§16.3）
            logger.warning("session %s 协议错误，断开: %s", self.session_id, e)
        except (ConnectionResetError, asyncio.IncompleteReadError):
            pass
        except asyncio.CancelledError:
            pass
        finally:
            await self.close()
