"""错误码体系（SDD §12.9）。

原则：Android 端不自行扩展码表；未知错误归入对应类别，底层原始状态码
（如 GATT status 133）放报文体 `rawStatus` 字段透传，服务器逻辑判断一律用
`errorCode`，`rawStatus` 仅供排障。
"""

from __future__ import annotations

from enum import IntEnum

SUCCESS = 0


class GattErrorCode(IntEnum):
    """1xxx：GATT/蓝牙错误。"""

    CONNECT_TIMEOUT = 1001      # 连接超时
    SERVICE_DISCOVERY_FAILED = 1002  # 服务发现失败
    CHAR_NOT_FOUND = 1003       # 特征不存在


class ProtocolErrorCode(IntEnum):
    """2xxx：协议错误。"""

    BAD_MESSAGE = 2001          # 报文格式错误
    UNKNOWN_COMMAND = 2002      # 未知命令
    DEVICE_NOT_FOUND = 2003     # 设备不存在
    COMMAND_CANCELLED = 2004    # 命令已取消（REMOVE/RESET 清理队列，7.7/7.8）


class ResourceErrorCode(IntEnum):
    """3xxx：资源与配置错误。"""

    QUEUE_FULL = 3001           # 队列已满
    COMMAND_EXPIRED = 3002      # 命令过期
    SLOT_INSUFFICIENT = 3003    # 槽位不足（含 SET_MAX_CONNECTIONS 越界/驱逐失败，5.4）
    DISK_FULL = 3004            # 磁盘不足


class FileErrorCode(IntEnum):
    """4xxx：文件传输错误。"""

    HASH_MISMATCH = 4001        # 哈希校验失败
    CRC_FAILED = 4002           # CRC 失败
    DUT_WRITE_REJECTED = 4003   # DUT 写入拒绝
    OFFSET_WRITE_UNSUPPORTED = 4004  # 不支持偏移写入


def error_category(code: int) -> str:
    """按码段归类：SUCCESS / GATT / PROTOCOL / RESOURCE / FILE / UNKNOWN。"""
    if code == SUCCESS:
        return "SUCCESS"
    if 1000 <= code < 2000:
        return "GATT"
    if 2000 <= code < 3000:
        return "PROTOCOL"
    if 3000 <= code < 4000:
        return "RESOURCE"
    if 4000 <= code < 5000:
        return "FILE"
    return "UNKNOWN"


def is_throttle_error(code: int) -> bool:
    """背压类错误（队列已满 3001 / 命令过期 3002）：服务器应放缓下发而非重试。"""
    return code in (ResourceErrorCode.QUEUE_FULL, ResourceErrorCode.COMMAND_EXPIRED)
