"""声明式测试场景模型（SDD §14，JSON 格式，pydantic v2）。

场景结构：setup / steps / teardown 三段，均为 Step 列表。动作词表：
command / expect / delay / assertView / set_polling_interval /
set_poll_rules / file_transfer（桩，runner 探测 runtime.transfer）。

match 匹配器语义：标量 → 相等；dict → 操作符子集
{"eq","ne","gt","gte","lt","lte","contains","exists"}，多操作符须全部成立；
字段路径支持点号嵌套（如 "values.battery"）。匹配器同用于 expect.match、
command.expectAck、assertView.expect。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

# 匹配器支持的操作符（§14 断言语义）
MATCH_OPS = {"eq", "ne", "gt", "gte", "lt", "lte", "contains", "exists"}

Matcher = Any  # 标量或 {"op": value} dict，由 _check_matcher 校验


def _check_matcher(v: Any, loc: str) -> Any:
    """校验单个匹配器：dict 时键必须全为已知操作符。"""
    if isinstance(v, dict):
        unknown = set(v) - MATCH_OPS
        if unknown:
            raise ValueError(f"{loc} 含未知匹配操作符: {sorted(unknown)}")
    return v


def _check_match_dict(v: Any, loc: str) -> Any:
    if not isinstance(v, dict):
        raise ValueError(f"{loc} 必须是对象（字段路径 → 匹配器）")
    for key, matcher in v.items():
        _check_matcher(matcher, f"{loc}.{key}")
    return v


class Step(BaseModel):
    """单步。action 决定必填字段；optional=true 失败不中止、不影响总结果。"""

    model_config = ConfigDict(extra="forbid")

    name: str | None = None
    action: Literal[
        "command", "expect", "delay", "assertView",
        "set_polling_interval", "set_poll_rules", "file_transfer",
    ]
    optional: bool = False
    # command
    command: str | None = None
    params: dict[str, Any] = Field(default_factory=dict)
    expectAck: dict[str, Matcher] | None = None  # 对 CMD_ACK 对账结果的匹配器
    ackTimeoutMs: int = Field(default=30000, gt=0)
    # expect
    event: str | None = None
    match: dict[str, Matcher] = Field(default_factory=dict)
    timeoutMs: int = Field(default=30000, gt=0)
    # delay
    ms: int | None = Field(default=None, gt=0)
    # assertView
    path: str | None = None
    expect: Matcher = None
    # set_polling_interval / set_poll_rules / file_transfer
    deviceMac: str | None = None
    intervalMs: int | None = Field(default=None, gt=0)
    rules: list[dict[str, Any]] | None = None
    fileId: str | None = None

    @field_validator("expect")
    @classmethod
    def _v_expect(cls, v: Any) -> Any:
        return _check_matcher(v, "expect")

    @field_validator("match")
    @classmethod
    def _v_match(cls, v: Any) -> Any:
        return _check_match_dict(v, "match")

    @field_validator("expectAck")
    @classmethod
    def _v_expect_ack(cls, v: Any) -> Any:
        if v is not None:
            _check_match_dict(v, "expectAck")
        return v

    @model_validator(mode="after")
    def _check_required(self) -> "Step":
        required: dict[str, tuple[str, ...]] = {
            "command": ("command",),
            "expect": ("event",),
            "delay": ("ms",),
            "assertView": ("path", "expect"),
            "set_polling_interval": ("deviceMac", "intervalMs"),
            "set_poll_rules": ("deviceMac", "rules"),
            "file_transfer": ("deviceMac", "fileId"),
        }
        for field in required[self.action]:
            if getattr(self, field) is None:
                raise ValueError(f"action={self.action} 缺必填字段: {field}")
        return self

    @property
    def display_name(self) -> str:
        """报告用步骤名：未命名时由 action + 关键字段生成。"""
        if self.name:
            return self.name
        key = self.command or self.event or self.path or self.action
        return f"{self.action}:{key}"


class Scenario(BaseModel):
    """场景文件模型（§14 用例库中可自动化部分的声明式表达）。"""

    model_config = ConfigDict(extra="forbid")

    name: str
    description: str = ""
    setup: list[Step] = Field(default_factory=list)
    steps: list[Step] = Field(default_factory=list)
    teardown: list[Step] = Field(default_factory=list)


def load_scenario(path: str | Path) -> Scenario:
    """从 JSON 文件加载场景。"""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return Scenario.model_validate(data)


# ---------------- 匹配器求值（runner / assertView 共用） ----------------

class _Missing:
    def __repr__(self) -> str:  # pragma: no cover
        return "<MISSING>"


MISSING = _Missing()


def get_path(obj: Any, dotted: str) -> Any:
    """按点号路径取值；任一级缺失返回 MISSING。"""
    cur = obj
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return MISSING
        cur = cur[part]
    return cur


def match_value(actual: Any, matcher: Any) -> bool:
    """匹配器求值：标量 → 相等；dict → 操作符全部成立。"""
    if isinstance(matcher, dict):
        for op, want in matcher.items():
            if not _apply_op(actual, op, want):
                return False
        return True
    if actual is MISSING:
        return False
    return actual == matcher


def _apply_op(actual: Any, op: str, want: Any) -> bool:
    if op == "exists":
        return (actual is not MISSING) == bool(want)
    if actual is MISSING:
        return False
    try:
        if op == "eq":
            return actual == want
        if op == "ne":
            return actual != want
        if op == "gt":
            return actual > want
        if op == "gte":
            return actual >= want
        if op == "lt":
            return actual < want
        if op == "lte":
            return actual <= want
        if op == "contains":
            return want in actual
    except TypeError:
        return False
    return False


def match_fields(raw: dict, match: dict[str, Any]) -> str | None:
    """对报文/视图按 match dict 逐字段断言；全部通过返回 None，否则返回首条不符描述。"""
    for path, matcher in match.items():
        actual = get_path(raw, path)
        if not match_value(actual, matcher):
            return f"字段 {path} 值 {actual!r} 不满足 {matcher!r}"
    return None
