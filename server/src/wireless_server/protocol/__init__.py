"""协议消息模型（SDD 附录 A）：命令（A.2）、事件（A.3）、错误码（§12.9）。"""

from wireless_server.protocol import commands, errors, events
from wireless_server.protocol.commands import COMMAND_TYPES, Envelope, build_command
from wireless_server.protocol.events import EVENT_TYPES, UnknownEvent, parse_event

__all__ = [
    "COMMAND_TYPES",
    "EVENT_TYPES",
    "Envelope",
    "UnknownEvent",
    "build_command",
    "commands",
    "errors",
    "events",
    "parse_event",
]
