package com.longcheer.agent.report;

import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.schedule.ConnectionSlotManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行时统计收集器（SDD §13）：随 CONNECTION_STATISTICS 上报槽位切换次数、
 * GATT 失败率、平均轮询耗时，供服务器侧观测整机健康度。
 */
public class StatsCollector {

    private static final String TAG = "StatsCollector";

    private final ConnectionSlotManager slotManager;
    private final AtomicLong slotSwitches = new AtomicLong();
    private final AtomicLong gattSuccess = new AtomicLong();
    private final AtomicLong gattFailure = new AtomicLong();
    private final AtomicLong pollCount = new AtomicLong();
    private final AtomicLong pollTotalMs = new AtomicLong();

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public StatsCollector(ConnectionSlotManager slotManager) {
        this.slotManager = slotManager;
    }

    /** 槽位切换（授槽/驱逐/强释放）计数。 */
    public void onSlotSwitch() {
        slotSwitches.incrementAndGet();
    }

    /** GATT 操作结果（成功/失败）。 */
    public void onGattResult(boolean success) {
        if (success) {
            gattSuccess.incrementAndGet();
        } else {
            gattFailure.incrementAndGet();
        }
    }

    /** 一轮轮询耗时（入队 → 处理链完成）。 */
    public void onPollDuration(long durationMs) {
        if (durationMs >= 0) {
            pollCount.incrementAndGet();
            pollTotalMs.addAndGet(durationMs);
        }
    }

    /** 周期性上报 CONNECTION_STATISTICS（§10.2）。 */
    public synchronized void start(StateReporter reporter, long intervalMs) {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StatsCollector");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> report(reporter), intervalMs, intervalMs,
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** 组装并上报一次统计（包可见，单测直接调用）。 */
    void report(StateReporter reporter) {
        long success = gattSuccess.get();
        long failure = gattFailure.get();
        double failureRate = success + failure == 0 ? 0.0
                : (double) failure / (success + failure);
        long avgPollMs = pollCount.get() == 0 ? 0 : pollTotalMs.get() / pollCount.get();

        Map<String, Object> payload = new HashMap<>();
        payload.put("slotsUsed", slotManager.occupiedSlots().size());
        payload.put("slotsTotal", slotManager.slotCount());
        payload.put("switchCount", slotSwitches.get());
        payload.put("gattFailureRate", failureRate);
        payload.put("avgPollMs", avgPollMs);
        AgentLog.d(TAG, "stats: " + payload);
        reporter.report("CONNECTION_STATISTICS", payload);
    }

    // ---- 测试观测 ----

    public long getSlotSwitches() {
        return slotSwitches.get();
    }

    public long getGattFailureCount() {
        return gattFailure.get();
    }
}
