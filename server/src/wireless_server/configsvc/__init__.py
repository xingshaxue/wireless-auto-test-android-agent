"""配置与持久化层：SQLite Store + ConfigManager（SDD §16.4 / §6.4）。"""

from .manager import DEFAULT_GLOBALS, ConfigManager
from .store import Store
from . import validation

__all__ = ["ConfigManager", "DEFAULT_GLOBALS", "Store", "validation"]
