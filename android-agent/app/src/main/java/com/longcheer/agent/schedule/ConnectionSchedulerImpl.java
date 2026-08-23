package com.longcheer.agent.schedule;

import android.os.SystemClock;

import com.longcheer.agent.ble.DeviceController;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.ConnectionSlot;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.registry.ActiveConnectionPool;

import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ConnectionScheduler 基础实现。
 * M1 阶段：维护请求队列、幂等 upsert、简单分配；selectVictim、时间片、老化机制留 TODO。
 */
public class ConnectionSchedulerImpl implements ConnectionScheduler {

    private final ConnectionSlotManager slotManager;
    private final DeviceRegistry deviceRegistry;
    private final ActiveConnectionPool activePool;
    private final AgentConfig config;
    private final Map<String, ConnectionRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, Boolean> persistentDevices = new ConcurrentHashMap<>();
    private final Map<String, String> pinReasons = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile int maxSlots;

    public ConnectionSchedulerImpl(ConnectionSlotManager slotManager,
                                   DeviceRegistry deviceRegistry,
                                   ActiveConnectionPool activePool,
                                   AgentConfig config) {
        this.slotManager = slotManager;
        this.deviceRegistry = deviceRegistry;
        this.activePool = activePool;
        this.config = config;
        this.maxSlots = config.getMaxSlots();
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConnectionScheduler");
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
    public void requestSlot(ConnectionRequest request) {
        // 幂等 upsert：同一 deviceMac 不重复排队，更新优先级/请求时间/过期时间。
        pendingRequests.merge(request.getDeviceMac(), request, (old, neu) -> {
            int newPriority = Math.max(old.getPriority(), neu.getPriority());
            long newRequestTime = neu.getRequestTime();
            long newExpireTime = Math.max(old.getExpireTime(), neu.getExpireTime());
            return new ConnectionRequest(neu.getDeviceMac(), newPriority, neu.getReason(), newRequestTime, newExpireTime);
        });
    }

    @Override
    public void cancelRequest(String deviceMac) {
        pendingRequests.remove(deviceMac);
    }

    @Override
    public void setMaxSlots(int max) {
        // TODO M1: 动态调整槽位数（含驱逐策略 5.4）；当前仅保存参数。
        if (max < 2 || max > 5) {
            throw new IllegalArgumentException("maxSlots must be in [2,5]");
        }
        this.maxSlots = max;
    }

    @Override
    public void setPersistent(String mac, boolean on) {
        persistentDevices.put(mac, on);
        if (on) {
            requestSlot(ConnectionRequest.now(mac, 0, ConnectionRequest.Reason.PERSISTENT));
        }
    }

    @Override
    public void pin(String mac, String reason) {
        pinReasons.put(mac, reason);
    }

    @Override
    public void unpin(String mac) {
        pinReasons.remove(mac);
    }

    /**
     * @return 当前待处理请求数
     */
    public int pendingRequestCount() {
        return pendingRequests.size();
    }

    private void tick() {
        if (!running) return;

        long now = SystemClock.elapsedRealtime();

        // 1) 清理过期请求。
        pendingRequests.values().removeIf(req -> req.getExpireTime() > 0 && now > req.getExpireTime());

        // 2) 简单分配：每次 tick 尝试把高优先级请求放入空闲槽。
        // TODO M1: 补全 selectVictim、冷却过滤、欠账老化、时间片释放。
        PriorityQueue<ConnectionRequest> queue = new PriorityQueue<>(
                Comparator.comparingInt(ConnectionRequest::getPriority).reversed()
                        .thenComparingLong(ConnectionRequest::getRequestTime));
        queue.addAll(pendingRequests.values());

        while (!queue.isEmpty()) {
            ConnectionRequest req = queue.poll();
            if (activePool.contains(req.getDeviceMac())) {
                pendingRequests.remove(req.getDeviceMac());
                continue;
            }
            boolean pinned = req.getReason() == ConnectionRequest.Reason.FILE_TRANSFER
                    || pinReasons.containsKey(req.getDeviceMac());
            ConnectionSlot slot = slotManager.acquire(req.getDeviceMac(), pinned);
            if (slot == null) {
                break; // 无空闲槽，等待下一轮或抢占策略
            }
            pendingRequests.remove(req.getDeviceMac());
            DeviceController controller = deviceRegistry.findByMac(req.getDeviceMac());
            if (controller != null) {
                activePool.put(req.getDeviceMac(), controller);
                controller.onSlotAcquired();
            }
        }
    }
}
