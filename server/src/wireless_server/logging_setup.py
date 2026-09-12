"""日志：控制台 + RotatingFileHandler 滚动落盘（对齐 SDD §13 可观测性要求）。"""

from __future__ import annotations

import logging
import logging.handlers
from pathlib import Path

from .settings import LogSettings

_FMT = "%(asctime)s %(levelname)-5s %(name)s: %(message)s"


def setup_logging(cfg: LogSettings) -> None:
    root = logging.getLogger()
    root.setLevel(cfg.level.upper())
    root.handlers.clear()

    console = logging.StreamHandler()
    console.setFormatter(logging.Formatter(_FMT))
    root.addHandler(console)

    log_dir = Path(cfg.dir)
    log_dir.mkdir(parents=True, exist_ok=True)
    file_handler = logging.handlers.RotatingFileHandler(
        log_dir / "server.log",
        maxBytes=cfg.max_bytes,
        backupCount=cfg.backup_count,
        encoding="utf-8",
    )
    file_handler.setFormatter(logging.Formatter(_FMT))
    root.addHandler(file_handler)
