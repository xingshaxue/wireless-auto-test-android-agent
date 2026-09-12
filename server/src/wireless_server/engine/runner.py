"""ScenarioRunner：场景执行器（SDD §14 测试编排核心）。

声明式场景 → 命令下发（CommandLedger 对账）→ 事件断言（EventIngest 实时流，
asyncio.Event 即时唤醒，loop.time() 单调时钟，§9）→ 报告落库（test_runs /
test_results）。mock_server.py smoke() 的 expect() 轮询断言在此生产化。

并发语义：同一 agent_id 同时只允许一个场景（第二个立即抛 AgentBusyError）；
不同 agent 可并行。stop(run_id) 取消在跑场景：当前步骤记 FAIL(已取消)、
剩余步骤不执行、teardown 跳过、总状态 ERROR，报告照常落库。
"""

from __future__ import annotations

import asyncio
import collections
import logging
import time
import uuid
from typing import TYPE_CHECKING, Any

from .report import ERROR, FAIL, PASS, SKIP, StepResult, TestReport
from .scenario import Scenario, Step, get_path, match_fields, match_value

if TYPE_CHECKING:
    from ..runtime import Runtime

logger = logging.getLogger(__name__)

# 事件缓冲上限（ring buffer，防长时间运行内存膨胀）
_EVENT_BUF_MAX = 1000


class AgentBusyError(RuntimeError):
    """同一 agent 已有场景在跑（§14 并发互斥）。"""


class _StepFail(Exception):
    """步骤断言类失败（超时 / 匹配不符 / agent 离线）→ 步骤 FAIL。"""


class _StepSkip(Exception):
    """步骤跳过（如 file_transfer 无 transfer 实现）→ 步骤 SKIP。"""


class _RunContext:
    """单次运行的实时状态：事件 ring buffer + 唤醒事件 + 所属任务。"""

    def __init__(self, run_id: str, agent_id: str, scenario: str) -> None:
        self.run_id = run_id
        self.agent_id = agent_id
        self.scenario = scenario
        self.buffer: collections.deque[tuple[str, dict]] = collections.deque(
            maxlen=_EVENT_BUF_MAX)
        self.wake = asyncio.Event()
        self.task: asyncio.Task | None = None


class ScenarioRunner:
    """场景执行器。runtime 装配时创建（runtime.engine），start()/stop() 挂接生命周期。"""

    def __init__(self, runtime: "Runtime") -> None:
        self._rt = runtime
        self._locks: dict[str, asyncio.Lock] = {}
        self._runs: dict[str, _RunContext] = {}

    # ---------------- 生命周期 ----------------

    async def start(self) -> None:
        """订阅 ingest 实时事件流（同步回调，同事件循环内分发）。"""
        self._rt.ingest.add_listener(self._on_event)

    async def stop(self, run_id: str | None = None) -> bool:
        """run_id 为空：取消全部在跑场景（runtime.stop 挂接）；
        指定 run_id：取消该运行，无此 id 返回 False。"""
        if run_id is not None:
            ctx = self._runs.get(run_id)
            if ctx is None or ctx.task is None:
                return False
            ctx.task.cancel()
            return True
        tasks = [ctx.task for ctx in self._runs.values() if ctx.task]
        for t in tasks:
            t.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        return True

    def _on_event(self, agent_id: str, event_type: str, raw: dict) -> None:
        for ctx in self._runs.values():
            if ctx.agent_id == agent_id:
                ctx.buffer.append((event_type, raw))
                ctx.wake.set()

    def running(self) -> list[dict[str, str]]:
        """在跑场景快照（供 stop(run_id) 与 API 展示）。"""
        return [
            {"runId": c.run_id, "agentId": c.agent_id, "scenario": c.scenario}
            for c in self._runs.values()
        ]

    # ---------------- 主入口 ----------------

    async def run_scenario(self, scenario: dict | Scenario,
                           agent_id: str,
                           run_id: str | None = None) -> TestReport:
        """执行场景并落库，返回 TestReport。同 agent 已在跑时抛 AgentBusyError。

        run_id 可选：API 层预生成传入（异步启动需立即可知 runId）；
        缺省内部生成。"""
        sc = scenario if isinstance(scenario, Scenario) else Scenario.model_validate(scenario)
        lock = self._locks.setdefault(agent_id, asyncio.Lock())
        if lock.locked():
            raise AgentBusyError(f"agent {agent_id} 已有场景在跑")
        async with lock:
            return await self._run(sc, agent_id, run_id)

    async def _run(self, sc: Scenario, agent_id: str,
                   run_id: str | None = None) -> TestReport:
        rt = self._rt
        run_id = run_id or f"run-{uuid.uuid4().hex[:12]}"
        report = TestReport(
            run_id=run_id, scenario=sc.name, agent_id=agent_id,
            started_ts=int(time.time() * 1000),
        )
        ctx = _RunContext(run_id, agent_id, sc.name)
        ctx.task = asyncio.current_task()
        self._runs[run_id] = ctx
        await rt.store.insert_test_run(run_id, sc.name, report.started_ts)
        logger.info("run %s 场景 %r 开始，agent=%s", run_id, sc.name, agent_id)
        try:
            await self._exec_all(ctx, sc, agent_id, report)
        except asyncio.CancelledError:
            # stop() 取消：teardown 不再执行，报告标记 ERROR 后照常落库
            report.status = ERROR
            report.detail = "已取消(stop)"
        finally:
            self._runs.pop(run_id, None)
            report.finished_ts = int(time.time() * 1000)
            for idx, step in enumerate(report.steps):
                await rt.store.insert_test_result(
                    run_id, idx, step.name, step.status,
                    step.detail or None, int(step.elapsed_ms))
            await rt.store.finish_test_run(
                run_id, report.finished_ts, report.status, report.to_dict())
        logger.info("run %s 结束: %s", run_id, report.status)
        return report

    # ---------------- 阶段执行 ----------------

    async def _exec_all(self, ctx: _RunContext, sc: Scenario,
                        agent_id: str, report: TestReport) -> None:
        outcomes: list[tuple[Step, StepResult]] = []
        setup_ok = await self._exec_phase(
            ctx, sc.setup, agent_id, report, "setup", True, outcomes)
        if setup_ok:
            await self._exec_phase(
                ctx, sc.steps, agent_id, report, "steps", True, outcomes)
        else:
            # setup 失败不进入 steps（§14 前置条件不满足）
            for step in sc.steps:
                r = StepResult("steps", step.display_name, SKIP,
                               detail="setup 失败")
                report.steps.append(r)
                outcomes.append((step, r))
        await self._exec_phase(
            ctx, sc.teardown, agent_id, report, "teardown", False, outcomes)

        # 总结果：非 optional 失败拉低（teardown 的 optional 失败豁免）
        failed = [(st, r) for st, r in outcomes
                  if r.status in (FAIL, ERROR)
                  and not (r.phase == "teardown" and st.optional)]
        if any(r.status == ERROR for _, r in failed):
            report.status = ERROR
        elif failed:
            report.status = FAIL
        if failed:
            report.detail = f"失败步骤: {failed[0][1].name}"

    async def _exec_phase(self, ctx: _RunContext, steps: list[Step],
                          agent_id: str, report: TestReport, phase: str,
                          abort_on_fail: bool,
                          outcomes: list[tuple[Step, StepResult]]) -> bool:
        """顺序执行一段步骤；abort_on_fail 时首个非 optional 失败 → 剩余 SKIP。"""
        aborted = False
        for step in steps:
            if aborted:
                r = StepResult(phase, step.display_name, SKIP,
                               detail="前序步骤失败")
                report.steps.append(r)
                outcomes.append((step, r))
                continue
            r = await self._exec_step(ctx, step, agent_id, report, phase)
            report.steps.append(r)
            outcomes.append((step, r))
            if abort_on_fail and r.status in (FAIL, ERROR) and not step.optional:
                aborted = True
        return not aborted

    async def _exec_step(self, ctx: _RunContext, step: Step, agent_id: str,
                         report: TestReport, phase: str) -> StepResult:
        loop = asyncio.get_running_loop()
        t0 = loop.time()
        status, detail = PASS, ""
        try:
            await self._do_action(ctx, step, agent_id, report, t0)
        except _StepSkip as e:
            status, detail = SKIP, str(e)
        except _StepFail as e:
            status, detail = FAIL, str(e)
        except asyncio.CancelledError:
            # stop() 取消：记 FAIL 后向外抛，由 _run 统一收尾
            report.steps.append(StepResult(
                phase, step.display_name, FAIL,
                (loop.time() - t0) * 1000, "已取消(stop)"))
            raise
        except Exception as e:  # 意外异常 → ERROR（区别于断言 FAIL）
            logger.exception("run %s 步骤 %s 异常", ctx.run_id, step.display_name)
            status, detail = ERROR, f"{type(e).__name__}: {e}"
        return StepResult(phase, step.display_name, status,
                          (loop.time() - t0) * 1000, detail)

    # ---------------- 动作实现 ----------------

    async def _do_action(self, ctx: _RunContext, step: Step, agent_id: str,
                         report: TestReport, t0: float) -> None:
        action = step.action
        if action == "command":
            await self._send_and_ack(agent_id, step.command, step.params,
                                     step, report, t0)
        elif action == "expect":
            await self._wait_event(ctx, step, report, t0)
        elif action == "delay":
            await asyncio.sleep(step.ms / 1000.0)
        elif action == "assertView":
            self._do_assert_view(step, agent_id)
        elif action == "set_polling_interval":
            fields = await self._config_update(
                "set_polling_interval", step.deviceMac, step.intervalMs)
            await self._send_and_ack(agent_id, "SET_POLLING_INTERVAL",
                                     fields, step, report, t0)
        elif action == "set_poll_rules":
            fields = await self._config_update(
                "set_poll_rules", step.deviceMac, step.rules)
            await self._send_and_ack(agent_id, "SET_POLL_RULES",
                                     fields, step, report, t0)
        elif action == "file_transfer":
            # §7.6 桩：transfer 未装配则跳过；装配后调 TransferManager 下发任务
            transfer = getattr(self._rt, "transfer", None)
            start = getattr(transfer, "start_transfer", None)
            if start is None:
                raise _StepSkip("transfer 未实现，跳过")
            try:
                task_id = await start(agent_id, step.fileId, step.deviceMac)
            except (KeyError, ValueError) as e:
                raise _StepFail(f"文件传输下发失败: {e}") from e
            logger.info("run %s 文件传输任务 %s 已下发", ctx.run_id, task_id)
        else:  # pragma: no cover - scenario 模型已拦截
            raise _StepFail(f"未知 action: {action}")

    async def _config_update(self, method: str, *args: Any) -> dict:
        """ConfigManager 运行中更新（§16.4）；设备不存在等校验失败 → FAIL。"""
        try:
            return await getattr(self._rt.config_manager, method)(*args)
        except (KeyError, ValueError) as e:
            raise _StepFail(f"配置更新失败: {e}") from e

    async def _send_and_ack(self, agent_id: str, cmd_type: str,
                            fields: dict, step: Step, report: TestReport,
                            t0: float) -> None:
        """下发命令；expectAck 非空时等 ACK 并按匹配器对账（§8.4）。"""
        session = self._rt.registry.get(agent_id)
        if session is None:
            raise _StepFail(f"agent {agent_id} 不在线")
        request_id = await self._rt.ledger.send_command(
            session, cmd_type, **fields)
        if step.expectAck is None:
            return
        try:
            info = await self._rt.ledger.wait_ack(
                request_id, step.ackTimeoutMs / 1000.0)
        except asyncio.TimeoutError:
            raise _StepFail(
                f"命令 {cmd_type} ACK 超时({step.ackTimeoutMs}ms)") from None
        except KeyError as e:
            raise _StepFail(f"命令 {cmd_type} 台账异常: {e}") from None
        loop = asyncio.get_running_loop()
        report.ack_latencies_ms.append((loop.time() - t0) * 1000)
        err = match_fields(info, step.expectAck)
        if err:
            raise _StepFail(f"命令 {cmd_type} ACK 不符: {err}")

    async def _wait_event(self, ctx: _RunContext, step: Step,
                          report: TestReport, t0: float) -> None:
        """expect：缓冲扫描 + 即时唤醒等待；匹配事件从缓冲消费（防重复命中）。"""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + step.timeoutMs / 1000.0
        while True:
            hit = -1
            for i, (etype, raw) in enumerate(ctx.buffer):
                if etype == step.event and match_fields(raw, step.match) is None:
                    hit = i
                    break
            if hit >= 0:
                del ctx.buffer[hit]  # 只消费匹配事件，免误吞乱序到达的其他类型
                report.event_wait_latencies_ms.append(
                    (loop.time() - t0) * 1000)
                return
            remaining = deadline - loop.time()
            if remaining <= 0:
                raise _StepFail(
                    f"等待事件 {step.event} 超时({step.timeoutMs}ms)")
            ctx.wake.clear()
            try:
                await asyncio.wait_for(ctx.wake.wait(), remaining)
            except asyncio.TimeoutError:
                pass

    def _do_assert_view(self, step: Step, agent_id: str) -> None:
        """assertView：对 ingest 最后已知状态视图做点号路径断言（A.3）。"""
        view = self._rt.ingest.agent_view(agent_id)
        actual = get_path(view, step.path)
        if not match_value(actual, step.expect):
            raise _StepFail(
                f"视图 {step.path} 值 {actual!r} 不满足 {step.expect!r}")
