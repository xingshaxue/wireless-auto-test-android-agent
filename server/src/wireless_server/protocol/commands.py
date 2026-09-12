"""服务器 → Android 命令模型（SDD 附录 A.2，共 19 条）。

编码约定（附录 A.1）：byte[] 一律 base64 字符串；MAC 大写冒号格式；
枚举字段取枚举名字符串；公共信封 type/timestamp/requestId 由 Envelope 基类承载。
"""

from __future__ import annotations

import time
import uuid
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


def _now_ms() -> int:
    return int(time.time() * 1000)


def _new_request_id() -> str:
    return str(uuid.uuid4())


class Envelope(BaseModel):
    """公共信封（附录 A.1）：type 由子类固定，timestamp/requestId 默认自动生成。"""

    model_config = ConfigDict(extra="forbid")

    timestamp: int = Field(default_factory=_now_ms)
    requestId: str = Field(default_factory=_new_request_id)

    def to_wire(self) -> dict:
        """生成含 type/timestamp/requestId 的完整 JSON dict（None 值的可选字段不发出）。"""
        return self.model_dump(exclude_none=True)


class RegisterAck(Envelope):
    """REGISTER_ACK — 注册确认（7.1）。"""

    type: Literal["REGISTER_ACK"] = "REGISTER_ACK"
    errorCode: int
    config: dict[str, Any] | None = None  # 成功时必填（16.4 AgentConfig 全量）


class ConnectDevice(Envelope):
    """CONNECT_DEVICE — 注册并连接 DUT（7.2，幂等）。"""

    type: Literal["CONNECT_DEVICE"] = "CONNECT_DEVICE"
    deviceMac: str
    deviceId: str | None = None
    lazyConnect: bool = False  # true = 仅注册（冷启动批量恢复）


class DisconnectDevice(Envelope):
    type: Literal["DISCONNECT_DEVICE"] = "DISCONNECT_DEVICE"
    deviceMac: str


class RemoveDevice(Envelope):
    type: Literal["REMOVE_DEVICE"] = "REMOVE_DEVICE"
    deviceMac: str


class ResumeDevice(Envelope):
    type: Literal["RESUME_DEVICE"] = "RESUME_DEVICE"
    deviceMac: str


class StartPolling(Envelope):
    type: Literal["START_POLLING"] = "START_POLLING"
    deviceMac: str


class StopPolling(Envelope):
    type: Literal["STOP_POLLING"] = "STOP_POLLING"
    deviceMac: str


class ReadChar(Envelope):
    """READ_CHAR — 读取特征。service/char 为 4/8 位短格式或 128 位 UUID（附录 A.1）。"""

    type: Literal["READ_CHAR"] = "READ_CHAR"
    deviceMac: str
    service: str
    char: str
    timeoutMs: int | None = None  # 缺省 = gattTimeoutMs（3000）


class WriteChar(Envelope):
    """WRITE_CHAR — 写入特征。payload 为 base64（附录 A.1）。"""

    type: Literal["WRITE_CHAR"] = "WRITE_CHAR"
    deviceMac: str
    service: str
    char: str
    payload: str  # base64
    writeType: Literal["WITH_RESPONSE", "NO_RESPONSE"] = "WITH_RESPONSE"
    timeoutMs: int | None = None


class SetPollingInterval(Envelope):
    """SET_POLLING_INTERVAL — intervalMs ≥ 200，且应满足 6.4 一轮时长约束。"""

    type: Literal["SET_POLLING_INTERVAL"] = "SET_POLLING_INTERVAL"
    deviceMac: str
    intervalMs: int


class SetPollRules(Envelope):
    """SET_POLL_RULES — 整集替换，元素结构 = 16.4 devices[].rules（7.3.2）。"""

    type: Literal["SET_POLL_RULES"] = "SET_POLL_RULES"
    deviceMac: str
    rules: list[dict[str, Any]]


class FileTransfer(Envelope):
    """FILE_TRANSFER — 文件传输任务（7.6）。sha256 = base64 的 32 字节整体哈希（16.3）。"""

    type: Literal["FILE_TRANSFER"] = "FILE_TRANSFER"
    taskId: str
    fileId: str
    deviceMac: str
    size: int
    sha256: str  # base64
    windowSize: int = 64
    chunkSize: int | None = None  # 缺省 = MTU - 3


class FileCancel(Envelope):
    type: Literal["FILE_CANCEL"] = "FILE_CANCEL"
    taskId: str


class SetMaxConnections(Envelope):
    """SET_MAX_CONNECTIONS — maxSlots 合法区间 2~5；越界或驱逐失败由 Agent 回 3003（5.4）。"""

    type: Literal["SET_MAX_CONNECTIONS"] = "SET_MAX_CONNECTIONS"
    maxSlots: int


class SetPersistentDevice(Envelope):
    type: Literal["SET_PERSISTENT_DEVICE"] = "SET_PERSISTENT_DEVICE"
    deviceMac: str
    on: bool


class PauseDevice(Envelope):
    """PAUSE_DEVICE — abortTransfer=false（默认）= 挂起传输，true = 中止（7.7）。"""

    type: Literal["PAUSE_DEVICE"] = "PAUSE_DEVICE"
    deviceMac: str
    abortTransfer: bool = False


class UploadLog(Envelope):
    """UPLOAD_LOG — sinceTs 之后日志；minLevel 缺省 DEBUG 全量（13）。"""

    type: Literal["UPLOAD_LOG"] = "UPLOAD_LOG"
    sinceTs: int | None = None
    minLevel: str | None = None


class GetStatus(Envelope):
    """GET_STATUS — deviceMac 缺省 = 整机 + 全部设备（返回结构见 A.3 CMD_ACK.result）。"""

    type: Literal["GET_STATUS"] = "GET_STATUS"
    deviceMac: str | None = None


class Reset(Envelope):
    """RESET — 无特有字段（7.8）。"""

    type: Literal["RESET"] = "RESET"


COMMAND_TYPES: dict[str, type[Envelope]] = {
    cls.model_fields["type"].default: cls
    for cls in (
        RegisterAck, ConnectDevice, DisconnectDevice, RemoveDevice, ResumeDevice,
        StartPolling, StopPolling, ReadChar, WriteChar, SetPollingInterval,
        SetPollRules, FileTransfer, FileCancel, SetMaxConnections,
        SetPersistentDevice, PauseDevice, UploadLog, GetStatus, Reset,
    )
}


def build_command(cmd_type: str, **fields: Any) -> Envelope:
    """按命令名构造模型；timestamp/requestId 缺省自动生成。"""
    cls = COMMAND_TYPES.get(cmd_type)
    if cls is None:
        raise ValueError(f"未知命令类型: {cmd_type}")
    return cls(**fields)
