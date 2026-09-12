"""REST API 层：FastAPI app 工厂（SDD §10 / §11.2 Bearer 鉴权）。"""

from .app import create_app

__all__ = ["create_app"]
