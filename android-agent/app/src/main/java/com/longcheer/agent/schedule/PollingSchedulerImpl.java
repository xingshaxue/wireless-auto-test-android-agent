package com.longcheer.agent.schedule;

import android.os.SystemClock;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.model.PollStep;
import com.longcheer.agent.model.PollingTask;
import com.longcheer.agent.registry.DeviceRegistry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PollingScheduler 基础实现。
 * M1 阶段：维护 nextPollTime 虚拟时钟，tick 循环简单实现；真实轮询入队与处理链留 TODO。
 */
public class PollingSchedulerImpl implements PollingScheduler {

    private final DeviceRegistry deviceRegistry;
    private final ConnectionScheduler connectionScheduler;
    private final AgentConfig config;
    private final Map<String, PollingConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pendingPollFlags = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile long startTick = 0;

    public PollingSchedulerImpl(DeviceRegistry deviceRegistry,
                                ConnectionScheduler connectionScheduler,
                                AgentConfig config) {
        this.deviceRegistry = deviceRegistry;
        this.connectionScheduler = connectionScheduler;
        this.config = config;
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        startTick = SystemClock.elapsedRealtime();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PollingScheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick,
                config.getTickIntervalMs(), config.getTickIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public void updateConfig(String mac, PollingConfig config) {
        configs.put(mac, config);
    }

    @Override
    public void onPollCompleted(String mac, Map<UUID, byte[]> rawResults) {
        com.longcheer.agent.ble.DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            pendingPollFlags.remove(mac);
            return;
        }
        PollingConfig cfg = configs.get(mac);
        long interval = cfg == null ? 60000 : cfg.getIntervalMs();
        long now = SystemClock.elapsedRealtime();
        // TODO M1: 将 rawResults 送入 Decoder/RuleEngine/ActionExecutor 处理链。
        ManagedDeviceInfo info = controller.snapshot();
        info.setLastPollTime(now);
        info.setNextPollTime(now + interval);
        info.setPollDataStale(false);
        pendingPollFlags.remove(mac);
    }

    @Override
    public long estimateFullRoundMs() {
        int dynamicSlots = Math.max(1, config.getMaxSlots());
        int deviceCount = Math.max(1, deviceRegistry.size());
        int groups = (int) Math.ceil((double) deviceCount / dynamicSlots);
        return groups * (config.getSetupBudgetMs() + config.getTimeSliceMs());
    }

    private void tick() {
        if (!running) return;

        long now = SystemClock.elapsedRealtime();

        for (com.longcheer.agent.ble.DeviceController controller : deviceRegistry.allControllers()) {
            String mac = controller.snapshot().getMac();
            PollingConfig cfg = configs.getOrDefault(mac, controller.snapshot().getPollingConfig());
            if (cfg == null || cfg.getIntervalMs() <= 0) {
                continue;
            }

            long nextPoll = controller.snapshot().getNextPollTime();
            if (nextPoll == 0) {
                controller.snapshot().setNextPollTime(now + cfg.getIntervalMs());
                continue;
            }

            if (now < nextPoll) {
                continue;
            }

            // 欠账防重入：上一轮尚未完成则只标记，不入队。
            if (pendingPollFlags.putIfAbsent(mac, Boolean.TRUE) != null) {
                continue;
            }

            DeviceState state = controller.getState();
            if (state == DeviceState.READY) {
                controller.enqueuePollTask(createSimpleTask(mac, cfg));
            } else if (state == DeviceState.REGISTERED
                    || state == DeviceState.DISCONNECTED
                    || state == DeviceState.WAITING_SLOT) {
                connectionScheduler.requestSlot(
                        ConnectionRequest.now(mac, 5, ConnectionRequest.Reason.POLL));
            }
            // CONNECTING/SERVICE_DISCOVERING/CONFIGURING/RECONNECTING/ERROR/PAUSED/TERMINATED 不处理
        }
    }

    private PollingTask createSimpleTask(String mac, PollingConfig cfg) {
        List<PollStep> steps = Collections.emptyList();
        // TODO M1: 将 readCharacteristics 转换为 PollStep 序列。
        return PollingTask.simple(mac, steps);
    }
}
