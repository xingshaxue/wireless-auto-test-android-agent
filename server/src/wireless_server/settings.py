"""服务器配置：TOML 文件（stdlib tomllib）+ pydantic 校验。

样例见 deploy/server.toml.example。所有字段有默认值，生产部署必须显式设置
gateway.agent_token 与 api.token。
"""

from __future__ import annotations

import tomllib
from pathlib import Path

from pydantic import BaseModel, Field


class GatewaySettings(BaseModel):
    host: str = "0.0.0.0"
    port: int = 10086
    agent_token: str = "change-me"  # REGISTER 鉴权（SDD §11.2），生产必须修改
    tls_cert: str = ""  # 可选；配置后启用 TLS，握手失败不降级
    tls_key: str = ""
    write_queue_max: int = 1000  # 每 session 写队列上限，超限断开（背压）
    ack_timeout_ms: int = 30_000  # CMD_ACK 等待超时
    ack_max_retries: int = 1  # 超时后重发次数
    heartbeat_timeout_factor: int = 3  # 超过 factor × heartbeatIntervalMs 无消息判离线
    default_heartbeat_interval_ms: int = 5000  # agent 未上报时的兜底心跳间隔


class ApiSettings(BaseModel):
    host: str = "0.0.0.0"
    port: int = 8080
    token: str = "change-me-api"  # REST/WS Bearer 鉴权，生产必须修改
    tls_cert: str = ""
    tls_key: str = ""


class StorageSettings(BaseModel):
    db_path: str = "data/server.db"
    files_dir: str = "data/files"  # 待下发文件登记目录
    logs_dir: str = "data/agent_logs"  # agent 上传日志接收目录
    files_quota_mb: int = 10240
    chunk_size: int = 4096  # 二进制帧分块（≤64KB，§16.3）
    file_push_yield_frames: int = 16  # 每推送 N 帧让出事件循环，避免阻塞命令下发


class LogSettings(BaseModel):
    level: str = "INFO"
    dir: str = "logs"
    max_bytes: int = 10 * 1024 * 1024
    backup_count: int = 5


class Settings(BaseModel):
    gateway: GatewaySettings = Field(default_factory=GatewaySettings)
    api: ApiSettings = Field(default_factory=ApiSettings)
    storage: StorageSettings = Field(default_factory=StorageSettings)
    log: LogSettings = Field(default_factory=LogSettings)


def load_settings(path: str | Path | None) -> Settings:
    if path is None:
        return Settings()
    with open(path, "rb") as f:
        data = tomllib.load(f)
    return Settings.model_validate(data)
