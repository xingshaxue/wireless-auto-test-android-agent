"""Android → 服务器事件模型（SDD 附录 A.3，共 18 类 / 20 个 type）。

公共字段 type/timestamp 由 EventEnvelope 承载；requestId 仅 CMD_ACK /
LOG_UPLOAD_DONE 携带（对账回填，A.1 / 8.4）。解析对 Agent 未来扩展容忍：
多余字段忽略，未知 type 返回 UnknownEvent 而不抛错。
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict


class EventEnvelope(BaseModel):
    """事件公共信封（附录 A.1）：type/timestamp；主动上报不携带 requestId。"""

    model_config = ConfigDict(extra="ignore")  # 容忍 Agent 新增字段


class Register(EventEnvelope):
    """REGISTER — 注册上报（7.1）。"""

    type: Literal["REGISTER"]
    timestamp: int
    deviceId: str
    ip: str
    port: int
    androidSdk: int
    bleSupported: bool
    maxConnections: int
    agentVersion: str
    token: str


class Heartbeat(EventEnvelope):
    type: Literal["HEARTBEAT"]
    timestamp: int
    cpuPercent: int | None = None
    memAvailMb: int | None = None
    slotsUsed: int
    slotsTotal: int
    devicesManaged: int
    devicesReady: int


class DeviceState(EventEnvelope):
    """DEVICE_STATE — state 取 DeviceState 枚举名；rawStatus 透传底层原始码（12.9）。"""

    type: Literal["DEVICE_STATE"]
    timestamp: int
    deviceMac: str
    state: str
    errorCode: int | None = None
    rawStatus: int | None = None


class ConnectionSlotAcquired(EventEnvelope):
    """reason：COMMAND / POLL / PERSISTENT / EVENT / FILE_TRANSFER / RECONNECT。"""

    type: Literal["CONNECTION_SLOT_ACQUIRED"]
    timestamp: int
    deviceMac: str
    slotId: int
    reason: str


class ConnectionSlotReleased(EventEnvelope):
    type: Literal["CONNECTION_SLOT_RELEASED"]
    timestamp: int
    deviceMac: str
    slotId: int
    reason: str


class PollResult(EventEnvelope):
    """POLL_RESULT — values 键 = 16.4 fields 的字段名，值 = 解析后字段值。"""

    type: Literal["POLL_RESULT"]
    timestamp: int
    deviceMac: str
    stale: bool
    values: dict[str, Any]


class PollDataStale(EventEnvelope):
    """POLL_DATA_STALE — 状态提示而非错误，不携带 errorCode（12.9）。"""

    type: Literal["POLL_DATA_STALE"]
    timestamp: int
    deviceMac: str
    lastPollTime: int
    reason: str  # NO_SLOT / CONNECT_FAILED


class CmdAck(EventEnvelope):
    """CMD_ACK — requestId 原样回填对账（8.4）；result 按命令而异（A.3）。"""

    type: Literal["CMD_ACK"]
    timestamp: int
    requestId: str
    errorCode: int
    rawStatus: int | None = None
    result: dict[str, Any] | None = None


class DevicePaused(EventEnvelope):
    type: Literal["DEVICE_PAUSED"]
    timestamp: int
    deviceMac: str


class DeviceResumed(EventEnvelope):
    type: Literal["DEVICE_RESUMED"]
    timestamp: int
    deviceMac: str


class ConnectionStatistics(EventEnvelope):
    type: Literal["CONNECTION_STATISTICS"]
    timestamp: int
    slotsUsed: int
    slotsTotal: int
    switchCount: int
    gattFailureRate: float  # 0~1
    avgPollMs: int


class FileProgress(EventEnvelope):
    type: Literal["FILE_PROGRESS"]
    timestamp: int
    taskId: str
    percent: float  # 0~100
    bytesPerSec: int


class FileResult(EventEnvelope):
    """FILE_RESULT — errorCode：0 / 4xxx / 2004（取消）。"""

    type: Literal["FILE_RESULT"]
    timestamp: int
    taskId: str
    errorCode: int
    rawStatus: int | None = None
    detail: str | None = None


class ErrorEvent(EventEnvelope):
    """ERROR — message 必填；deviceMac 可选（整机级错误不携带）。"""

    type: Literal["ERROR"]
    timestamp: int
    errorCode: int
    message: str
    rawStatus: int | None = None
    deviceMac: str | None = None


class TopologyConnection(BaseModel):
    model_config = ConfigDict(extra="ignore")

    deviceMac: str
    # A.3 定义了全字段，但 agent 实现对无槽位设备省略 slotId/pinned，按可选处理
    slotId: int | None = None
    state: str
    persistent: bool
    pinned: bool | None = None


class Topology(EventEnvelope):
    type: Literal["TOPOLOGY"]
    timestamp: int
    connections: list[TopologyConnection]


class FileRequest(EventEnvelope):
    """FILE_REQUEST — 规则动作触发传输的取文件入口（7.3.2 / 7.6）。"""

    type: Literal["FILE_REQUEST"]
    timestamp: int
    fileId: str
    taskId: str
    deviceMac: str


class FileDownloadReady(EventEnvelope):
    type: Literal["FILE_DOWNLOAD_READY"]
    timestamp: int
    taskId: str
    fileId: str


class FileDownloadAck(EventEnvelope):
    """FILE_DOWNLOAD_ACK — resendSeqs 空数组 = 全部成功（16.3）。"""

    type: Literal["FILE_DOWNLOAD_ACK"]
    timestamp: int
    taskId: str
    resendSeqs: list[int]


class FileDownloadResume(EventEnvelope):
    """FILE_DOWNLOAD_RESUME — lastSeq = 已确认最大连续块号（7.6 / 16.3）。"""

    type: Literal["FILE_DOWNLOAD_RESUME"]
    timestamp: int
    taskId: str
    lastSeq: int


class LogUploadDone(EventEnvelope):
    type: Literal["LOG_UPLOAD_DONE"]
    timestamp: int
    requestId: str
    errorCode: int
    size: int  # 实际上传字节数


class UnknownEvent(BaseModel):
    """未知事件类型：Agent 未来扩展的占位模型，完整保留原始字段，不抛错。"""

    model_config = ConfigDict(extra="allow")

    type: str
    timestamp: int = 0


EVENT_TYPES: dict[str, type[EventEnvelope]] = {
    cls.model_fields["type"].annotation.__args__[0]: cls
    for cls in (
        Register, Heartbeat, DeviceState, ConnectionSlotAcquired,
        ConnectionSlotReleased, PollResult, PollDataStale, CmdAck,
        DevicePaused, DeviceResumed, ConnectionStatistics, FileProgress,
        FileResult, ErrorEvent, Topology, FileRequest, FileDownloadReady,
        FileDownloadAck, FileDownloadResume, LogUploadDone,
    )
}


def parse_event(raw: dict[str, Any]) -> EventEnvelope | UnknownEvent:
    """按 type 分发到具体事件模型；未知 type 返回 UnknownEvent（容忍 Agent 扩展）。"""
    cls = EVENT_TYPES.get(raw.get("type"))
    if cls is None:
        return UnknownEvent.model_validate(raw)
    return cls.model_validate(raw)
