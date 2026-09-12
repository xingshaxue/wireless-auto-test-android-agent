package com.longcheer.agent.schedule;

import android.os.SystemClock;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.ble.GattExecutorImpl;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.GattResult;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollStep;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.model.PollingTask;
import com.longcheer.agent.poll.PollResultChain;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * PollingScheduler 实现（SDD §3.9 / §6 / §7.3）。
 *
 * <p>虚拟轮询计划：每台设备独立 nextPollTime，与连接状态无关照常推进；到期且离线
 * 则申请连接槽（欠账），READY 则直接入队轮询任务。轮询结果走完处理链
 * （Decoder → RuleEngine → ActionExecutor）后回写缓存、清 stale 标记（§6.1 唯一时机）
 * 并按 reportOnlyChanged 策略上报（§6.3）。</p>
 */
public class PollingSchedulerImpl implements PollingScheduler, GattExecutorImpl.TaskCallback {

    private static final String TAG = "PollingScheduler";

    /** 可发起轮询欠账的设备状态白名单（§16.5）。 */
    private static final java.util.Set<DeviceState> POLL_REQUEST_STATES = java.util.EnumSet.of(
            DeviceState.REGISTERED, DeviceState.DISCONNECTED, DeviceState.WAITING_SLOT);

    private final DeviceRegistry deviceRegistry;
    private final ConnectionScheduler connectionScheduler;
    private final AgentConfig config;
    private final PollResultChain resultChain;
    private final StateReporter stateReporter;
    private final LongSupplier clock;

    private final Map<String, PollingConfig> configs = new ConcurrentHashMap<>();
    /** 欠账防重入（§6.1）：任务仍在队列/执行中未回调时只标记不入队。 */
    private final Map<String, Boolean> pendingPollFlags = new ConcurrentHashMap<>();
    /** 轮询暂停中的设备（§7.6 文件传输期间，§7.7 语义由 DeviceController.PAUSED 覆盖）。 */
    private final java.util.Set<String> suspendedPolls =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** 轮询计时：入队时间（onPollCompleted 计算耗时，§13 avgPollMs）。 */
    private final Map<String, Long> pollStartAt = new ConcurrentHashMap<>();
    /** 运行时统计（§13，可选注入）。 */
    private volatile com.longcheer.agent.report.StatsCollector statsCollector;

    /** 注入统计收集器（装配层调用，可为 null）。 */
    public void setStatsCollector(com.longcheer.agent.report.StatsCollector collector) {
        this.statsCollector = collector;
    }
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    /** 钳制后的实际 tick 周期（§6.4 硬约束）。 */
    private volatile long effectiveTickMs;

    public PollingSchedulerImpl(DeviceRegistry deviceRegistry,
                                ConnectionScheduler connectionScheduler,
                                AgentConfig config,
                                PollResultChain resultChain,
                                StateReporter stateReporter) {
        this(deviceRegistry, connectionScheduler, config, resultChain, stateReporter,
                SystemClock::elapsedRealtime);
    }

    /** 测试用构造：注入单调时钟。 */
    PollingSchedulerImpl(DeviceRegistry deviceRegistry,
                         ConnectionScheduler connectionScheduler,
                         AgentConfig config,
                         PollResultChain resultChain,
                         StateReporter stateReporter,
                         LongSupplier clock) {
        this.deviceRegistry = deviceRegistry;
        this.connectionScheduler = connectionScheduler;
        this.config = config;
        this.resultChain = resultChain;
        this.stateReporter = stateReporter;
        this.clock = clock;
        this.effectiveTickMs = config.getTickIntervalMs();
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        effectiveTickMs = effectiveTickIntervalMs(configs.values());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PollingScheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }
            try {
                tick();
            } catch (Throwable t) {
                // 周期任务异常会被 ScheduledExecutorService 静默终止（不再触发），
                // 必须兜底保活：记录后继续下一拍（真机联调暴露的调度器静默死亡）。
                AgentLog.w(TAG, "scheduler tick error (kept alive): " + t);
            }
        }, effectiveTickMs, effectiveTickMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public void updateConfig(String mac, PollingConfig config) {        if (config != null) {
            configs.put(mac, config);
        }
        // 设备配置变化可能影响全局最小轮询间隔 → 重校验 tick 钳制（§6.4）。
        long clamped = effectiveTickIntervalMs(configs.values());
        if (clamped != effectiveTickMs) {
            effectiveTickMs = clamped;
            if (running) {
                AgentLog.i(TAG, "tick interval adjusted to " + clamped + "ms, restarting");
                stop();
                start();
            }
        }
    }

    /**
     * tick 周期钳制（§6.4 / §16.4）：tickIntervalMs 不得大于全局最小 intervalMs 的一半，
     * 超过则告警并钳制。
     */
    long effectiveTickIntervalMs(Iterable<PollingConfig> pollingConfigs) {
        long configured = config.getTickIntervalMs();
        long minInterval = Long.MAX_VALUE;
        for (PollingConfig cfg : pollingConfigs) {
            if (cfg != null && cfg.getIntervalMs() > 0 && !cfg.getReadCharacteristics().isEmpty()) {
                minInterval = Math.min(minInterval, cfg.getIntervalMs());
            }
        }
        if (minInterval == Long.MAX_VALUE) {
            return configured;
        }
        long cap = minInterval / 2;
        if (configured > cap) {
            AgentLog.w(TAG, "tickIntervalMs " + configured + " > min intervalMs/2 (" + cap
                    + "), clamped（§6.4）");
            return Math.max(1, cap);
        }
        return configured;
    }

    @Override
    public void suspendPolling(String mac) {
        suspendedPolls.add(mac);
        AgentLog.i(TAG, "polling suspended: " + mac);
    }

    @Override
    public void resumePolling(String mac) {
        suspendedPolls.remove(mac);
        AgentLog.i(TAG, "polling resumed: " + mac);
    }

    @Override
    public void resetAll() {
        // §7.8：轮询计划重置（nextPollTime = now + interval），欠账标记清空。
        long now = clock.getAsLong();
        pendingPollFlags.clear();
        for (DeviceController controller : deviceRegistry.allControllers()) {
            PollingConfig cfg = resolveConfig(controller);
            if (cfg != null && cfg.getIntervalMs() > 0) {
                controller.updatePollTimes(0, now + cfg.getIntervalMs());
            }
        }
        AgentLog.i(TAG, "all polling plans reset");
    }

    @Override
    public void onPollCompleted(String mac, Map<UUID, byte[]> rawResults) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            pendingPollFlags.remove(mac);
            return;
        }
        long now = clock.getAsLong();
        PollingConfig cfg = resolveConfig(controller);

        // §13：平均轮询耗时统计。
        Long startedAt = pollStartAt.remove(mac);
        if (statsCollector != null) {
            if (startedAt != null) {
                statsCollector.onPollDuration(now - startedAt);
            }
            statsCollector.onGattResult(true);
        }

        // §7.3 处理链：Decoder 解析 → RuleEngine 求值 → ActionExecutor 执行。
        Map<String, Object> fields = resultChain.process(mac, rawResults);

        // 缓存与计时回写（显式写接口，§8.1）；stale 清除的唯一时机（§6.1）。
        Map<UUID, byte[]> previousRaw = controller.snapshot().getLastPollResult();
        controller.updatePollResult(rawResults);
        long interval = cfg == null ? 60000 : cfg.getIntervalMs();
        controller.updatePollTimes(now, now + interval);
        controller.setPollDataStale(false);
        pendingPollFlags.remove(mac);

        // §6.3 上报策略：reportOnlyChanged=true 时仅数据变化才上报。
        boolean reportOnlyChanged = cfg == null || cfg.isReportOnlyChanged();
        if (!reportOnlyChanged || !rawEquals(previousRaw, rawResults)) {
            stateReporter.reportPollResult(mac, fields, false);
        } else {
            AgentLog.d(TAG, "poll unchanged, skip report: " + mac);
        }
    }

    @Override
    public void onPollFailed(String mac, int errorCode, int rawStatus) {
        // §7.3.1：任务中止 → 清防重入标记（否则该设备轮询永久停摆）；周期从完成时起算，
        // 失败轮不推进 nextPollTime，下一 tick 自然重新申请。
        pendingPollFlags.remove(mac);
        pollStartAt.remove(mac);
        if (statsCollector != null) {
            statsCollector.onGattResult(false);
        }
        AgentLog.w(TAG, "poll failed: " + mac + " errorCode=" + errorCode + " rawStatus=" + rawStatus);
        maybeReportStale(mac, "CONNECT_FAILED");
    }

    // ---- GattExecutorImpl.TaskCallback：GATT 执行结果汇入轮询调度 ----

    @Override
    public void onTaskResult(String deviceMac, Map<UUID, byte[]> results, int errorCode, int rawStatus) {
        if (errorCode == 0) {
            onPollCompleted(deviceMac, results);
        } else {
            onPollFailed(deviceMac, errorCode, rawStatus);
        }
    }

    @Override
    public void onCommandResult(GattCommand command, GattResult result) {
        // §7.4 第 8 步：命令执行结果回 CMD_ACK（requestId 对账；rawStatus 透传，§12.9）。
        if (command.getRequestId() == null) {
            return; // 本地自治动作（规则触发）无 requestId
        }
        if (result.isSuccess()) {
            Map<String, Object> ack = new HashMap<>();
            if (command.getType() == GattCommand.Type.READ && result.getValue() != null) {
                ack.put("value", java.util.Base64.getEncoder().encodeToString(result.getValue()));
            }
            stateReporter.reportCommandAck(command.getRequestId(), 0, ack);
        } else {
            stateReporter.reportCommandAck(command.getRequestId(), 1001, result.getStatus(), null);
        }
    }

    @Override
    public long estimateFullRoundMs() {
        int dynamicSlots = Math.max(1, config.getMaxSlots());
        int deviceCount = Math.max(1, deviceRegistry.size());
        int groups = (int) Math.ceil((double) deviceCount / dynamicSlots);
        return groups * (config.getSetupBudgetMs() + config.getTimeSliceMs());
    }

    /** 当前有效 tick 周期（钳制后，§6.4）。 */
    public long getEffectiveTickMs() {
        return effectiveTickMs;
    }

    /**
     * 调度主循环（包可见，供单测直接驱动；周期任务入口在 start() 中按 running 门控）。
     */
    void tick() {
        long now = clock.getAsLong();

        for (DeviceController controller : deviceRegistry.allControllers()) {
            ManagedDeviceInfo info = controller.snapshot();
            String mac = info.getMac();
            if (suspendedPolls.contains(mac)) {
                continue; // §7.6：文件传输期间暂停该设备轮询
            }
            PollingConfig cfg = configs.containsKey(mac) ? configs.get(mac) : info.getPollingConfig();
            if (cfg == null || cfg.getIntervalMs() <= 0) {
                continue;
            }

            long nextPoll = info.getNextPollTime();
            if (nextPoll == 0) {
                controller.updatePollTimes(info.getLastPollTime(), now + cfg.getIntervalMs());
                continue;
            }
            if (now < nextPoll) {
                continue;
            }

            DeviceState state = controller.getState();
            if (state == DeviceState.READY) {
                // 欠账防重入：仅在真正入队轮询任务时置标记；上一轮未完成则跳过（§6.1）。
                // 申请槽位阶段不置标记——否则设备经 POLL 通道获槽到 READY 后，
                // 标记会永远挡住任务入队（真机联调暴露：READY 空转 2s 被释放，再不轮询）。
                if (pendingPollFlags.putIfAbsent(mac, Boolean.TRUE) != null) {
                    continue;
                }
                controller.enqueuePollTask(createSimpleTask(mac, cfg));
                pollStartAt.put(mac, now); // §13 avgPollMs 计时起点
            } else if (POLL_REQUEST_STATES.contains(state)) {
                connectionScheduler.requestSlot(
                        ConnectionRequest.now(mac, ConnectionRequest.PRIORITY_NORMAL,
                                ConnectionRequest.Reason.POLL));
                pollStartAt.put(mac, now);
            } else {
                // CONNECTING/RECONNECTING/ERROR/PAUSED/TERMINATED 不在白名单，清标记等下轮。
                pendingPollFlags.remove(mac);
                continue;
            }
            maybeReportStale(mac, "NO_SLOT");
        }
    }

    /**
     * 数据过期标记与上报（§6.1 / §12.9）：超 staleThresholdMs 未采到新数据 → 置 stale 并
     * 上报一次 POLL_DATA_STALE（带 lastPollTime + reason，不带 errorCode）；
     * stale 清除唯一时机在 onPollCompleted。
     */
    private void maybeReportStale(String mac, String reason) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            return;
        }
        ManagedDeviceInfo info = controller.snapshot();
        long now = clock.getAsLong();
        if (info.isPollDataStale() || info.getLastPollTime() <= 0) {
            return;
        }
        if (now - info.getLastPollTime() <= config.getStaleThresholdMs()) {
            return;
        }
        controller.setPollDataStale(true);
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceMac", mac);
        payload.put("lastPollTime", info.getLastPollTime());
        payload.put("reason", reason);
        stateReporter.report("POLL_DATA_STALE", payload);
    }

    /** readCharacteristics 简写 → 全 READ 的 PollStep 序列（§7.3.1 退化形式）。 */
    private PollingTask createSimpleTask(String mac, PollingConfig cfg) {
        List<PollStep> steps = new ArrayList<>();
        for (UUID charUuid : cfg.getReadCharacteristics()) {
            // service 留空，由执行侧经 profile 解析（§16.4 ServiceResolver）。
            steps.add(new PollStep(com.longcheer.agent.model.GattCommand.Type.READ,
                    null, charUuid));
        }
        return PollingTask.simple(mac, steps);
    }

    private PollingConfig resolveConfig(DeviceController controller) {
        String mac = controller.snapshot().getMac();
        return configs.containsKey(mac) ? configs.get(mac) : controller.snapshot().getPollingConfig();
    }

    private static boolean rawEquals(Map<UUID, byte[]> a, Map<UUID, byte[]> b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<UUID, byte[]> e : a.entrySet()) {
            byte[] other = b.get(e.getKey());
            if (!java.util.Arrays.equals(e.getValue(), other)) {
                return false;
            }
        }
        return true;
    }
}
