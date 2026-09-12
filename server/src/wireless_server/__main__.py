"""入口：python -m wireless_server [--config server.toml]

装配顺序：settings → logging → store → configsvc → ingest → ledger →
gateway(TCP) → transfer → engine → api(FastAPI/uvicorn)。
"""

from __future__ import annotations

import argparse
import asyncio
import logging

from .logging_setup import setup_logging
from .settings import load_settings

log = logging.getLogger(__name__)


def main() -> None:
    parser = argparse.ArgumentParser(prog="wireless_server", description=__doc__)
    parser.add_argument("--config", default=None, help="TOML 配置文件路径")
    parser.add_argument("--check-config", action="store_true",
                        help="仅校验配置后退出")
    args = parser.parse_args()

    settings = load_settings(args.config)
    setup_logging(settings.log)
    if args.check_config:
        log.info("配置校验通过")
        return

    from .runtime import run  # 延迟导入，便于 --check-config 在无依赖场景运行

    asyncio.run(run(settings))


if __name__ == "__main__":
    main()
