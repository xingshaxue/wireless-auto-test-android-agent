"""测试报告模型（SDD §14.3：ACK / 事件等待时延 P50/P95 基线回填）。"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any

# 步骤/总体状态
PASS = "PASS"
FAIL = "FAIL"
ERROR = "ERROR"
SKIP = "SKIP"


def percentile(values: list[float], p: float) -> float | None:
    """最近秩百分位；空列表返回 None。"""
    if not values:
        return None
    s = sorted(values)
    rank = max(math.ceil(p / 100.0 * len(s)), 1)
    return s[rank - 1]


@dataclass
class StepResult:
    phase: str            # setup / steps / teardown
    name: str
    status: str           # PASS / FAIL / ERROR / SKIP
    elapsed_ms: float = 0.0
    detail: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "phase": self.phase, "name": self.name, "status": self.status,
            "elapsedMs": round(self.elapsed_ms, 1), "detail": self.detail,
        }


@dataclass
class TestReport:
    __test__ = False  # 非 pytest 测试类，避免被收集
    run_id: str
    scenario: str
    agent_id: str
    started_ts: int = 0    # 墙钟毫秒
    finished_ts: int = 0
    status: str = PASS
    detail: str = ""
    steps: list[StepResult] = field(default_factory=list)
    ack_latencies_ms: list[float] = field(default_factory=list)
    event_wait_latencies_ms: list[float] = field(default_factory=list)

    def stats(self) -> dict[str, Any]:
        """§14.3 基线统计：命令 ACK 时延与事件等待时延的 P50/P95。"""
        return {
            "ackCount": len(self.ack_latencies_ms),
            "ackP50Ms": percentile(self.ack_latencies_ms, 50),
            "ackP95Ms": percentile(self.ack_latencies_ms, 95),
            "eventWaitCount": len(self.event_wait_latencies_ms),
            "eventWaitP50Ms": percentile(self.event_wait_latencies_ms, 50),
            "eventWaitP95Ms": percentile(self.event_wait_latencies_ms, 95),
        }

    def to_dict(self) -> dict[str, Any]:
        return {
            "runId": self.run_id,
            "scenario": self.scenario,
            "agentId": self.agent_id,
            "startedTs": self.started_ts,
            "finishedTs": self.finished_ts,
            "status": self.status,
            "detail": self.detail,
            "steps": [s.to_dict() for s in self.steps],
            "stats": self.stats(),
        }
