"""TCP 网关：AgentSession / AgentRegistry / GatewayServer（SDD §16.3 / §11.2）。"""

from .registry import AgentRegistry
from .server import GatewayServer
from .session import AgentSession

__all__ = ["AgentRegistry", "AgentSession", "GatewayServer"]
