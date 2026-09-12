"""EventIngest：事件入库 + 内存"最后已知状态"视图（SDD 附录 A.3 / §12.9）。

所有 agent 事件全量入 events 表（排障/报告回放），同时维护 per-agent 内存
视图供 API 查询。POLL_DATA_STALE / ERROR 记 warning 日志；listener 钩子供
engine / WebSocket 后续订阅实时事件流。
"""

from __future__ import annotations

import logging
import time
from typing import TYPE_CHECKING, Any, Callable

from .protocol.events import parse_event

if TYPE_CHECKING:
    from .configsvc.store import Store

logger = logging.getLogger(__name__)

EventListener = Callable[[str, str, dict], None]


def _device_entry() -> dict[str, Any]:
    return {
        "state": None,          # DEVICE_STATE 枚举名（12.9）
        "stateFlag": "unknown",  # ok / error / unknown
        "errorCode": None,
        "rawStatus": None,
        "lastPollTime": None,
        "pollDataStale": False,
        "values": {},
    }


class EventIngest:
    """事件入库与最后已知状态。store 须已 init()。"""

    def __init__(self, store: "Store") -> None:
        self._store = store
        self._listeners: list[EventListener] = []
        self._views: dict[str, dict[str, Any]] = {}

    # ---------------- 订阅 ----------------

    def add_listener(self, fn: EventListener) -> None:
        """注册事件监听器：fn(agent_id, event_type, raw)，handle_event 时同步调用。"""
        self._listeners.append(fn)

    def remove_listener(self, fn: EventListener) -> None:
        """注销事件监听器（WS 断开等场景清理用）。"""
        if fn in self._listeners:
            self._listeners.remove(fn)

    # ---------------- 入库与视图更新 ----------------

    async def handle_event(self, agent_id: str, raw: dict) -> None:
        """parse_event 校验 → 全量入库 → 更新内存视图 → 通知 listener。

        校验失败（agent 字段缺漏/类型不符）不抛出：记告警、原始报文照常入库、
        跳过视图更新——单条坏消息不允许影响会话与其余事件。
        """
        try:
            event = parse_event(raw)
        except Exception as e:
            logger.warning("agent %s 事件校验失败 type=%s: %s",
                           agent_id, raw.get("type"), e)
            event = None
        etype = raw.get("type", "?")
        ts = int(raw.get("timestamp", 0))
        await self._store.insert_event(agent_id, etype, ts, raw)

        view = self._views.setdefault(agent_id, self._new_view())
        view["updated_ts"] = int(time.time() * 1000)

        if etype == "DEVICE_STATE":
            dev = view["devices"].setdefault(raw["deviceMac"], _device_entry())
            dev["state"] = raw.get("state")
            dev["errorCode"] = raw.get("errorCode")
            dev["rawStatus"] = raw.get("rawStatus")
            dev["stateFlag"] = "error" if raw.get("errorCode") else "ok"
        elif etype == "POLL_RESULT":
            dev = view["devices"].setdefault(raw["deviceMac"], _device_entry())
            dev["lastPollTime"] = ts
            dev["pollDataStale"] = bool(raw.get("stale"))
            dev["values"].update(raw.get("values") or {})
        elif etype == "POLL_DATA_STALE":
            # 状态提示而非错误，不携带 errorCode（12.9）
            dev = view["devices"].setdefault(raw["deviceMac"], _device_entry())
            dev["lastPollTime"] = raw.get("lastPollTime")
            dev["pollDataStale"] = True
            logger.warning("agent %s 设备 %s 轮询数据过期: %s",
                           agent_id, raw.get("deviceMac"), raw.get("reason"))
        elif etype == "HEARTBEAT":
            view["slots"] = {
                "slotsUsed": raw.get("slotsUsed"),
                "slotsTotal": raw.get("slotsTotal"),
            }
            view["devicesManaged"] = raw.get("devicesManaged")
            view["devicesReady"] = raw.get("devicesReady")
            view["cpuPercent"] = raw.get("cpuPercent")
            view["memAvailMb"] = raw.get("memAvailMb")
        elif etype == "ERROR":
            logger.warning("agent %s ERROR errorCode=%s message=%s device=%s",
                           agent_id, raw.get("errorCode"), raw.get("message"),
                           raw.get("deviceMac"))

        for listener in self._listeners:
            listener(agent_id, etype, raw)

    def handle_status_result(self, agent_id: str, result: dict | None) -> None:
        """GET_STATUS 的 CMD_ACK result 合并进视图（A.3 result 结构）。"""
        if not result:
            return
        view = self._views.setdefault(agent_id, self._new_view())
        view["updated_ts"] = int(time.time() * 1000)
        view["status"] = result

    @staticmethod
    def _new_view() -> dict[str, Any]:
        return {"devices": {}, "slots": {}, "updated_ts": 0}

    # ---------------- 查询 ----------------

    def reset_view(self, agent_id: str) -> None:
        """注册/重注册时重置视图：REGISTER_ACK 全量配置是 agent 状态的新事实来源，
        旧视图的设备缓存（可能已从配置删除）不应残留。"""
        self._views[agent_id] = self._new_view()

    def agent_view(self, agent_id: str) -> dict[str, Any]:
        """单 agent 最后已知状态（无记录返回空视图）。"""
        view = self._views.get(agent_id)
        if view is None:
            return self._new_view()
        return view

    def all_views(self) -> dict[str, dict[str, Any]]:
        return dict(self._views)
