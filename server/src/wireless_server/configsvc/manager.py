"""ConfigManager：装配 §16.4 全量配置，供 REGISTER_ACK 下发与运行中更新。

写入前一律过 validation；校验失败抛 ValueError（错误列表换行拼接）。
运行中更新方法返回对应命令（附录 A.2）的字段 dict，供网关组包下发。
"""

from __future__ import annotations

import logging
from typing import Any

from . import validation
from .store import Store

logger = logging.getLogger(__name__)

# §16.4 顶层旋钮默认值
DEFAULT_GLOBALS: dict[str, int] = {
    "maxSlots": 3,
    "timeSliceMs": 2000,
    "idleReleaseMs": 30000,
    "commandTtlMs": 300000,
    "maxPendingCommands": 64,
    "tickIntervalMs": 100,
    "cooldownMs": 5000,
    "setupBudgetMs": 4000,
    "agingThresholdMs": 30000,
    "heartbeatIntervalMs": 5000,
    "connectTimeoutMs": 10000,
    "gattTimeoutMs": 3000,
    "maxReconnectAttempts": 5,
    "reconnectBackoffMaxMs": 60000,
    "notifyMinReportIntervalMs": 200,
    "maxConcurrentTransfers": 1,
    "diskQuotaMb": 1024,
    "failedTaskRetentionDays": 7,
    "reportBufferMax": 1000,
    "staleThresholdMs": 120000,
}


def _check(errors: list[str]) -> None:
    if errors:
        raise ValueError("配置校验失败:\n" + "\n".join(f"- {e}" for e in errors))


class ConfigManager:
    """配置装配与运行中更新入口。store 须已 init()。"""

    def __init__(self, store: Store):
        self._store = store

    # ---------------- 配置装配 ----------------

    async def build_config(self, agent_id: str) -> dict[str, Any]:
        """装配 §16.4 全量配置：默认值补齐 + devices 全量 + configVersion 原子自增。

        tickIntervalMs 超界时按 §16.4 钳制（只影响下发内容，不回写存储）；
        intervalMs 小于一轮时长的设备产生告警日志（§6.4）。
        """
        stored = await self._store.get_global_params()
        params = {**DEFAULT_GLOBALS, **stored}
        devices = await self._store.list_devices()

        params, warnings = validation.clamp_tick_interval(params, devices)
        warnings += validation.check_interval_warnings(params, devices)
        for w in warnings:
            logger.warning("配置告警: %s", w)

        version = await self._store.next_config_version(agent_id)
        return {"configVersion": version, **params, "devices": devices}

    async def current_version(self, agent_id: str) -> int:
        """当前配置版本，无记录返回 0（§16.4 低版本晚到丢弃依据）。"""
        return await self._store.current_config_version(agent_id)

    # ---------------- 设备 CRUD（API 层直通，写入前校验） ----------------

    async def upsert_device(self, device: dict) -> None:
        _check(validation.validate_device(device))
        await self._store.upsert_device(device)

    async def get_device(self, mac: str) -> dict | None:
        return await self._store.get_device(mac)

    async def list_devices(self) -> list[dict]:
        return await self._store.list_devices()

    async def delete_device(self, mac: str) -> bool:
        return await self._store.delete_device(mac)

    # ---------------- 全局参数 ----------------

    async def get_global_params(self) -> dict[str, Any]:
        """缺省项已用 §16.4 默认值补齐后的视图。"""
        stored = await self._store.get_global_params()
        return {**DEFAULT_GLOBALS, **stored}

    async def set_global_params(self, params: dict) -> None:
        """合并写入（只覆盖传入的键），写入前校验合并结果。"""
        stored = await self._store.get_global_params()
        merged = {**stored, **params}
        _check(validation.validate_globals(merged))
        await self._store.set_global_params(merged)

    # ---------------- 运行中更新（§16.4 整项替换、下一 tick 原子生效） ----------------
    # 均为全局配置项，configVersion 在下次 build_config 时自然递增；
    # 返回值 = 对应命令（附录 A.2）的字段 dict，供网关组包下发。

    async def _require_device(self, mac: str) -> dict:
        device = await self._store.get_device(mac)
        if device is None:
            raise KeyError(f"设备不存在: {mac}")
        return device

    async def set_polling_interval(self, mac: str, interval_ms: int) -> dict[str, Any]:
        """SET_POLLING_INTERVAL：更新设备轮询间隔。"""
        device = await self._require_device(mac)
        polling = {**device.get("polling", {}), "intervalMs": interval_ms}
        updated = {**device, "polling": polling}
        _check(validation.validate_device(updated))
        await self._store.upsert_device(updated)
        return {"deviceMac": mac, "intervalMs": interval_ms}

    async def set_poll_rules(self, mac: str, rules: list[dict]) -> dict[str, Any]:
        """SET_POLL_RULES：规则集整集替换（§7.3.2）。"""
        device = await self._require_device(mac)
        updated = {**device, "rules": rules}
        _check(validation.validate_device(updated))
        await self._store.upsert_device(updated)
        return {"deviceMac": mac, "rules": rules}

    async def set_persistent(self, mac: str, on: bool) -> dict[str, Any]:
        """SET_PERSISTENT_DEVICE：常驻标记整项替换。"""
        device = await self._require_device(mac)
        updated = {**device, "persistent": bool(on)}
        _check(validation.validate_device(updated))
        await self._store.upsert_device(updated)
        return {"deviceMac": mac, "on": bool(on)}

    async def set_max_slots(self, n: int) -> dict[str, Any]:
        """SET_MAX_CONNECTIONS：连接槽上限（合法区间 2~5，§5.4）。"""
        await self.set_global_params({"maxSlots": n})
        return {"maxSlots": n}
