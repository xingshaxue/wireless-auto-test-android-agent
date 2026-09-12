"""配置校验（SDD §16.4 字段约束 / §6.4 一轮时长估算）。

所有函数返回错误/告警文案列表，空列表 = 通过；不抛异常，由调用方决定拒绝或告警。
"""

from __future__ import annotations

import math
import re
from typing import Any

# §16.4：op / format 词表
OPS = {"GT", "GE", "LT", "LE", "EQ", "NE", "BETWEEN"}
FORMATS = {"uint8", "uint16", "uint32", "sint8", "sint16", "sint32", "utf8", "bool", "hex"}
BYTE_ORDERS = {"LE", "BE"}

# 附录 A.1：MAC 大写冒号格式
MAC_RE = re.compile(r"^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

MIN_INTERVAL_MS = 200  # §16.4：readCharacteristics 非空时 intervalMs 下限


def _is_number(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def validate_device(device: dict) -> list[str]:
    """校验单台设备配置（§16.4 devices[]），返回错误列表，空 = 通过。"""
    errors: list[str] = []
    if not isinstance(device, dict):
        return ["设备配置必须是对象"]

    mac = device.get("mac")
    if not device.get("deviceId"):
        errors.append("缺少必填字段 deviceId")
    if not mac:
        errors.append("缺少必填字段 mac")
    elif not isinstance(mac, str) or not MAC_RE.match(mac):
        errors.append(f"mac 格式非法（须大写冒号，附录 A.1）: {mac!r}")

    # fields：字段名 → {char, format, byteOrder?, scale?, byteOffset?}
    fields = device.get("fields", {})
    if not isinstance(fields, dict):
        errors.append("fields 必须是对象")
        fields = {}
    for name, f in fields.items():
        if not isinstance(f, dict):
            errors.append(f"fields.{name} 必须是对象")
            continue
        if not f.get("char"):
            errors.append(f"fields.{name} 缺少 char")
        fmt = f.get("format")
        if fmt not in FORMATS:
            errors.append(f"fields.{name}.format 非法（须 ∈ {sorted(FORMATS)}）: {fmt!r}")
        if f.get("byteOrder", "LE") not in BYTE_ORDERS:
            errors.append(f"fields.{name}.byteOrder 非法（LE/BE）: {f.get('byteOrder')!r}")
        if not _is_number(f.get("scale", 1.0)):
            errors.append(f"fields.{name}.scale 必须是数值")
        bo = f.get("byteOffset", 0)
        if not isinstance(bo, int) or isinstance(bo, bool) or bo < 0:
            errors.append(f"fields.{name}.byteOffset 必须是非负整数")

    # polling：readCharacteristics 非空时 intervalMs ≥ 200（§16.4）
    polling = device.get("polling", {})
    if not isinstance(polling, dict):
        errors.append("polling 必须是对象")
        polling = {}
    read_chars = polling.get("readCharacteristics", [])
    interval = polling.get("intervalMs")
    if read_chars:
        if not isinstance(interval, int) or isinstance(interval, bool):
            errors.append("polling.intervalMs 必须是整数（readCharacteristics 非空时必填）")
        elif interval < MIN_INTERVAL_MS:
            errors.append(
                f"polling.intervalMs={interval} < {MIN_INTERVAL_MS}ms"
                "（readCharacteristics 非空时下限，§16.4）"
            )

    # rules：op 词表 / BETWEEN 二元数组 / conditions[].field 必须在 fields 有映射（§16.4）
    rules = device.get("rules", [])
    if not isinstance(rules, list):
        errors.append("rules 必须是数组")
        rules = []
    for i, rule in enumerate(rules):
        if not isinstance(rule, dict):
            errors.append(f"rules[{i}] 必须是对象")
            continue
        rid = rule.get("ruleId", f"#{i}")
        conditions = rule.get("conditions", [])
        if not isinstance(conditions, list):
            errors.append(f"rules[{i}].conditions 必须是数组")
            continue
        for j, cond in enumerate(conditions):
            if not isinstance(cond, dict):
                errors.append(f"rules[{i}].conditions[{j}] 必须是对象")
                continue
            field = cond.get("field")
            if field not in fields:
                errors.append(
                    f"规则 {rid} conditions[{j}].field={field!r} 在 fields 中无映射"
                    "（配置错误，拒绝加载，§16.4/8.9）"
                )
            op = cond.get("op")
            if op not in OPS:
                errors.append(f"规则 {rid} conditions[{j}].op 非法（须 ∈ {sorted(OPS)}）: {op!r}")
            elif op == "BETWEEN":
                value = cond.get("value")
                if (
                    not isinstance(value, list)
                    or len(value) != 2
                    or not all(_is_number(v) for v in value)
                ):
                    errors.append(
                        f"规则 {rid} conditions[{j}] BETWEEN 的 value 必须是 [min, max] 二元数组"
                    )
    return errors


# §16.4 顶层旋钮的取值约束：(最小值, 最大值)；None = 不限
_GLOBAL_RANGES: dict[str, tuple[float | None, float | None]] = {
    "maxSlots": (2, 5),  # §5.4 / SET_MAX_CONNECTIONS 合法区间 2~5
    "timeSliceMs": (1, None),
    "idleReleaseMs": (1, None),
    "commandTtlMs": (1, None),
    "maxPendingCommands": (1, None),
    "tickIntervalMs": (1, None),
    "cooldownMs": (0, None),
    "setupBudgetMs": (1, None),
    "agingThresholdMs": (1, None),
    "heartbeatIntervalMs": (1, None),
    "connectTimeoutMs": (1, None),
    "gattTimeoutMs": (1, None),
    "maxReconnectAttempts": (0, None),
    "reconnectBackoffMaxMs": (1, None),
    "notifyMinReportIntervalMs": (1, None),
    "maxConcurrentTransfers": (1, 2),  # §16.4 建议 1~2
    "diskQuotaMb": (1, None),
    "failedTaskRetentionDays": (1, None),
    "reportBufferMax": (1, None),
    "staleThresholdMs": (1, None),
}


def validate_globals(params: dict) -> list[str]:
    """校验顶层旋钮数值范围（§16.4），返回错误列表，空 = 通过。"""
    errors: list[str] = []
    if not isinstance(params, dict):
        return ["全局参数必须是对象"]
    for key, value in params.items():
        bounds = _GLOBAL_RANGES.get(key)
        if bounds is None:
            errors.append(f"未知全局参数: {key!r}")
            continue
        if not isinstance(value, int) or isinstance(value, bool):
            errors.append(f"{key} 必须是整数: {value!r}")
            continue
        lo, hi = bounds
        if lo is not None and value < lo:
            errors.append(f"{key}={value} 小于下限 {lo}")
        if hi is not None and value > hi:
            errors.append(f"{key}={value} 大于上限 {hi}")
    return errors


def _min_interval_ms(devices: list[dict]) -> int | None:
    """全局最小 intervalMs（仅统计配了 polling.intervalMs 的设备）。"""
    intervals = [
        d["polling"]["intervalMs"]
        for d in devices
        if isinstance(d.get("polling"), dict)
        and isinstance(d["polling"].get("intervalMs"), int)
    ]
    return min(intervals) if intervals else None


def clamp_tick_interval(
    params: dict, devices: list[dict]
) -> tuple[dict, list[str]]:
    """tickIntervalMs > 最小 intervalMs/2 时钳制并告警（§16.4 / §6.4 硬约束）。

    返回 (可能钳制后的 params 副本, 告警列表)；无设备或不越界时原样返回。
    """
    params = dict(params)
    warnings: list[str] = []
    min_interval = _min_interval_ms(devices)
    tick = params.get("tickIntervalMs")
    if min_interval is not None and isinstance(tick, int):
        limit = min_interval // 2
        if tick > limit:
            warnings.append(
                f"tickIntervalMs={tick} > 最小 intervalMs({min_interval})/2，"
                f"已钳制到 {limit}（§16.4 / §6.4）"
            )
            params["tickIntervalMs"] = limit
    return params, warnings


def estimate_full_round_ms(params: dict, devices: list[dict]) -> int:
    """一轮完整轮询时长（§6.4）：

    ceil((设备总数 − 常驻设备数) / 动态槽数) × (setupBudgetMs + timeSliceMs)
    动态槽数 = maxSlots − 常驻数（至少 1）；无动态设备时为 0。
    """
    total = len(devices)
    persistent = sum(1 for d in devices if d.get("persistent"))
    dynamic = total - persistent
    if dynamic <= 0:
        return 0
    dynamic_slots = max(int(params.get("maxSlots", 3)) - persistent, 1)
    per_group = int(params.get("setupBudgetMs", 4000)) + int(params.get("timeSliceMs", 2000))
    return math.ceil(dynamic / dynamic_slots) * per_group


def check_interval_warnings(params: dict, devices: list[dict]) -> list[str]:
    """intervalMs 小于一轮时长的设备给告警（§6.4：欠账还不完，数据持续过期）。"""
    round_ms = estimate_full_round_ms(params, devices)
    if round_ms <= 0:
        return []
    warnings: list[str] = []
    for d in devices:
        polling = d.get("polling")
        if not isinstance(polling, dict):
            continue
        interval = polling.get("intervalMs")
        if isinstance(interval, int) and interval < round_ms:
            warnings.append(
                f"设备 {d.get('mac', '?')} intervalMs={interval} < 一轮时长 {round_ms}ms，"
                "实际轮询频率将退化为一轮一次（§6.4）"
            )
    return warnings
