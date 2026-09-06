package com.longcheer.agent.schedule;

import android.os.SystemClock;
import android.util.Log;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.ConnectionSlot;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.QueuedTask;
import com.longcheer.agent.registry.ActiveConnectionPool;
import com.longcheer.agent.registry.DeviceRegistry;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * ConnectionScheduler 实现（SDD §3.6 / §5 / §6 / §16.5）。
 *
 * <p>调度循环（每 tick）：
 * 1) 清理过期请求；2) 常驻设备自愈补登记；3) 建连预算 / 时间片 / 空闲释放管理；
 * 4) 槽位泄漏巡检；5) 空闲槽分配（冷却过滤 + 欠账老化），无空闲槽且最高有效优先级
 * 达 HIGH 时按 selectVictim 抢占。</p>
 */
public class ConnectionSchedulerImpl implements ConnectionScheduler {

    private static final String TAG = "ConnectionScheduler";

    /** 可参与授槽的设备状态白名单（§16.5：其余状态绝不申请/授予槽位）。 */
    private static final Set<DeviceState> GRANTABLE_STATES = EnumSet.of(
            DeviceState.REGISTERED, DeviceState.DISCONNECTED, DeviceState.WAITING_SLOT);

    /** 建连中状态集合：建连超 setupBudgetMs 未就绪则强制释放（§5.2 第 3 条 / §12.5）。 */
    private static final Set<DeviceState> CONNECTING_STATES = EnumSet.of(
            DeviceState.CONNECTING, DeviceState.SERVICE_DISCOVERING, DeviceState.CONFIGURING);

    private final ConnectionSlotManager slotManager;
    private final DeviceRegistry deviceRegistry;
    private final ActiveConnectionPool activePool;
    private final AgentConfig config;
    private final LongSupplier clock;

    private final Map<String, ConnectionRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, Boolean> persistentDevices = new ConcurrentHashMap<>();
    private final Map<String, String> pinReasons = new ConcurrentHashMap<>();
    /** 被踢/被强释放设备的冷却截止时间（§5.2 第 5 条，分配侧过滤）。 */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    /** 设备进入"READY 且无任务"空闲态的起始时间（空闲超时释放与 selectVictim 用）。 */
    private final Map<String, Long> idleSince = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile boolean suspended = false;
    private volatile int maxSlots;

    public ConnectionSchedulerImpl(ConnectionSlotManager slotManager,
                                   DeviceRegistry deviceRegistry,
                                   ActiveConnectionPool activePool,
                                   AgentConfig config) {
        this(slotManager, deviceRegistry, activePool, config, SystemClock::elapsedRealtime);
    }

    /** 测试用构造：注入单调时钟。 */
    ConnectionSchedulerImpl(ConnectionSlotManager slotManager,
                            DeviceRegistry deviceRegistry,
                            ActiveConnectionPool activePool,
                            AgentConfig config,
                            LongSupplier clock) {
        this.slotManager = slotManager;
        this.deviceRegistry = deviceRegistry;
        this.activePool = activePool;
        this.config = config;
        this.clock = clock;
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
        scheduler.scheduleAtFixedRate(() -> {
            if (running) {
                tick();
            }
        }, config.getTickIntervalMs(), config.getTickIntervalMs(), TimeUnit.MILLISECONDS);
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
        // 幂等 upsert（§3.6）：同一 deviceMac 不重复排队；
        // 优先级取大、请求时间取新、过期时间取大。
        pendingRequests.merge(request.getDeviceMac(), request, (old, neu) -> {
            int newPriority = Math.max(old.getPriority(), neu.getPriority());
            long newExpireTime = Math.max(old.getExpireTime(), neu.getExpireTime());
            return new ConnectionRequest(neu.getDeviceMac(), newPriority, neu.getReason(),
                    neu.getRequestTime(), newExpireTime);
        });
    }

    @Override
    public void cancelRequest(String deviceMac) {
        pendingRequests.remove(deviceMac);
    }

    @Override
    public boolean setMaxSlots(int max) {
        // §5.4：越界属协议参数错误，由 CommandDispatcher 校验回 3003；此处兜底防误用。
        if (max < 2 || max > 5) {
            throw new IllegalArgumentException("maxSlots must be in [2,5]");
        }
        if (max < maxSlots) {
            // 调小：新上限低于常驻 + pinned 数量时拒绝调整（§5.4）。
            int required = requiredSlots();
            if (max < required) {
                Log.w(TAG, "setMaxSlots refuse: max=" + max + " < required=" + required);
                return false;
            }
            // 按 5.4 驱逐顺序（selectVictim）释放超出上限的槽位。
            while (activePool.size() > max) {
                String victim = selectVictim(clock.getAsLong());
                if (victim == null) {
                    Log.w(TAG, "setMaxSlots: no evictable device, refuse");
                    return false;
                }
                evict(victim, clock.getAsLong(), false);
            }
        }
        maxSlots = max;
        slotManager.setLimit(max);
        return true;
    }

    @Override
    public void setPersistent(String mac, boolean on) {
        persistentDevices.put(mac, on);
        if (on) {
            // 常驻请求永不过期；已存在请求时保留原请求时间，不影响欠账老化。
            pendingRequests.putIfAbsent(mac, ConnectionRequest.persistent(mac));
        } else {
            pendingRequests.remove(mac);
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

    @Override
    public void releaseSlot(String deviceMac) {
        pendingRequests.remove(deviceMac);
        releaseOccupied(deviceMac, false, 0);
    }

    @Override
    public void suspendScheduling() {
        suspended = true;
        // §12.10：释放全部槽位，连接态设备置 DISCONNECTED；欠账请求保留，恢复后继续调度。
        for (ConnectionSlot slot : slotManager.occupiedSlots()) {
            releaseOccupied(slot.getCurrentDeviceMac(), false, 0);
        }
        idleSince.clear();
    }

    @Override
    public void resumeScheduling() {
        suspended = false;
    }

    @Override
    public int requiredSlots() {
        Set<String> required = new HashSet<>();
        for (Map.Entry<String, Boolean> e : persistentDevices.entrySet()) {
            if (e.getValue()) required.add(e.getKey());
        }
        for (DeviceController c : deviceRegistry.allControllers()) {
            if (c.snapshot().isPersistent()) required.add(c.snapshot().getMac());
        }
        required.addAll(pinReasons.keySet());
        for (ConnectionSlot slot : slotManager.occupiedSlots()) {
            if (slot.isPinned()) required.add(slot.getCurrentDeviceMac());
        }
        return required.size();
    }

    /**
     * @return 当前待处理请求数
     */
    public int pendingRequestCount() {
        return pendingRequests.size();
    }

    /**
     * 调度主循环（包可见，供单测直接驱动；周期任务入口在 start() 中按 running 门控）。
     */
    void tick() {
        long now = clock.getAsLong();

        // 1) 清理过期请求（常驻请求 NEVER_EXPIRE 不过期）。
        pendingRequests.values().removeIf(
                req -> req.getExpireTime() != ConnectionRequest.NEVER_EXPIRE
                        && now > req.getExpireTime());

        // 2) 常驻设备自愈（§5.2 第 1 条）：常驻设备掉线/请求丢失后持续补登记。
        for (Map.Entry<String, Boolean> e : persistentDevices.entrySet()) {
            if (!e.getValue()) continue;
            String mac = e.getKey();
            if (activePool.contains(mac) || pendingRequests.containsKey(mac)) continue;
            DeviceController controller = deviceRegistry.findByMac(mac);
            if (controller == null || !GRANTABLE_STATES.contains(controller.getState())) continue;
            // putIfAbsent：不与并发的新请求竞争，避免覆盖更高优先级/更新的请求。
            pendingRequests.putIfAbsent(mac, ConnectionRequest.persistent(mac));
        }

        // 3) 建连预算 / 时间片 / 空闲释放（pinned 与常驻设备不回收，§5.2 第 1/3/4 条）。
        for (DeviceController controller : activePool.all()) {
            String mac = controller.snapshot().getMac();
            if (isPinned(mac) || isPersistent(mac)) {
                continue;
            }
            DeviceState state = controller.getState();
            if (CONNECTING_STATES.contains(state)) {
                ConnectionSlot slot = slotManager.slotOf(mac);
                if (slot != null && now > slot.getAcquireTime() + config.getSetupBudgetMs()) {
                    Log.w(TAG, "setup budget exceeded, force release " + mac);
                    evict(mac, now, true); // §12.5：超预算强释放并计一次连接失败（重连计数属 BLE 里程碑）
                }
            } else if (state == DeviceState.READY) {
                ManagedDeviceInfo info = controller.snapshot();
                if (info.getPendingCommands().isEmpty()) {
                    idleSince.putIfAbsent(mac, now);
                    long readySince = info.getLastConnectedTime();
                    if (now > readySince + config.getTimeSliceMs()) {
                        releaseOccupied(mac, false, 0); // 时间片到期且队列清空（§5.2 第 3 条）
                    } else if (now - idleSince.get(mac) > config.getIdleReleaseMs()) {
                        releaseOccupied(mac, false, 0); // 空闲超时释放（§5.2 第 4 条）
                    }
                } else {
                    idleSince.remove(mac);
                }
            } else {
                idleSince.remove(mac);
            }
        }

        // 4) 槽位泄漏巡检（§12.5 兜底）。
        for (String mac : slotManager.releaseLeakedSlots()) {
            Log.w(TAG, "leaked slot force released: " + mac);
            releaseOccupied(mac, true, now + config.getCooldownMs());
        }

        // 5) 空闲槽分配与抢占（挂起期间不授槽，§12.10）。
        if (suspended) {
            return;
        }
        while (true) {
            ConnectionRequest best = pickBestRequest(now);
            if (best == null) {
                return;
            }
            String mac = best.getDeviceMac();
            ConnectionSlot slot = slotManager.acquire(mac, isPinRequest(best));
            if (slot == null) {
                // 无空闲槽：仅高有效优先级（服务器命令或老化升级）允许抢占（§5.2 第 2 条 / §12.4）。
                if (effectivePriority(best, now) < ConnectionRequest.PRIORITY_HIGH) {
                    return;
                }
                String victim = selectVictim(now);
                if (victim == null) {
                    return; // 全部不可踢 → 排队等待，老化机制兜底（§5.2 第 6 条）
                }
                evict(victim, now, false);
                continue;
            }
            grant(best);
        }
    }

    /**
     * 选择当前最高有效优先级的可授槽请求；顺带清理无效请求（设备不存在 /
     * 已在池中 / 状态不可授槽）。冷却期设备跳过但保留请求（§5.2 第 5 条）。
     */
    private ConnectionRequest pickBestRequest(long now) {
        ConnectionRequest best = null;
        int bestEffective = Integer.MIN_VALUE;
        for (ConnectionRequest req : pendingRequests.values()) {
            String mac = req.getDeviceMac();
            DeviceController controller = deviceRegistry.findByMac(mac);
            if (controller == null || activePool.contains(mac)
                    || !GRANTABLE_STATES.contains(controller.getState())) {
                // 需求已失效或已在池（槽位使命已完成），清除请求。
                pendingRequests.remove(mac);
                continue;
            }
            Long cool = cooldownUntil.get(mac);
            if (cool != null && now < cool) {
                continue;
            }
            int effective = effectivePriority(req, now);
            if (best == null
                    || effective > bestEffective
                    || (effective == bestEffective && req.getRequestTime() < best.getRequestTime())) {
                best = req;
                bestEffective = effective;
            }
        }
        return best;
    }

    /**
     * 欠账老化（§5.2 第 6 条）：每等待一个 agingThresholdMs 有效优先级升一级，封顶 HIGH。
     */
    private int effectivePriority(ConnectionRequest req, long now) {
        long threshold = config.getAgingThresholdMs();
        if (threshold <= 0) {
            return req.getPriority();
        }
        long steps = Math.max(0, (now - req.getRequestTime()) / threshold);
        long effective = req.getPriority() + steps;
        return (int) Math.min(effective, ConnectionRequest.PRIORITY_HIGH);
    }

    /**
     * 选择牺牲者（§16.5 selectVictim）：pinned 与常驻绝不踢；通知活跃期（notifyBoostUntil）
     * 内不踢；先挑最久空闲且无任务者，再挑任务可延迟（无 HIGH 命令）中静态优先级最低者；
     * 全不可踢返回 null。
     */
    private String selectVictim(long now) {
        List<DeviceController> candidates = new ArrayList<>();
        for (DeviceController controller : activePool.all()) {
            String mac = controller.snapshot().getMac();
            if (isPinned(mac) || isPersistent(mac)) {
                continue;
            }
            if (now < controller.snapshot().getNotifyBoostUntil()) {
                continue; // 通知活跃期内不被抢占（§7.3.3）
            }
            candidates.add(controller);
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // 1) 空闲设备优先：无待执行任务，空闲时间最长者。
        String bestIdle = null;
        long bestIdleDuration = -1;
        for (DeviceController controller : candidates) {
            ManagedDeviceInfo info = controller.snapshot();
            if (!info.getPendingCommands().isEmpty()) {
                continue;
            }
            long duration = idleDurationMs(info.getMac(), info, now);
            if (duration > bestIdleDuration) {
                bestIdleDuration = duration;
                bestIdle = info.getMac();
            }
        }
        if (bestIdle != null) {
            return bestIdle;
        }

        // 2) 任务可延迟设备：队列无 HIGH 命令，静态优先级最低、空闲最久。
        String bestDeferrable = null;
        int bestPriority = Integer.MAX_VALUE;
        long bestDuration = -1;
        for (DeviceController controller : candidates) {
            ManagedDeviceInfo info = controller.snapshot();
            if (hasHighPriorityTask(info)) {
                continue;
            }
            long duration = idleDurationMs(info.getMac(), info, now);
            if (info.getPriority() < bestPriority
                    || (info.getPriority() == bestPriority && duration > bestDuration)) {
                bestPriority = info.getPriority();
                bestDuration = duration;
                bestDeferrable = info.getMac();
            }
        }
        return bestDeferrable;
    }

    private long idleDurationMs(String mac, ManagedDeviceInfo info, long now) {
        Long since = idleSince.get(mac);
        // 无空闲记录时以进入 READY 的时间（lastConnectedTime）近似。
        return now - (since != null ? since : info.getLastConnectedTime());
    }

    private static boolean hasHighPriorityTask(ManagedDeviceInfo info) {
        for (QueuedTask task : info.getPendingCommands()) {
            if (task.getPriority() == GattCommand.Priority.HIGH) {
                return true;
            }
        }
        return false;
    }

    /** 授槽：请求出队、设备入池、触发连接。 */
    private void grant(ConnectionRequest req) {
        String mac = req.getDeviceMac();
        pendingRequests.remove(mac);
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            slotManager.release(mac);
            return;
        }
        idleSince.remove(mac);
        activePool.put(mac, controller);
        controller.onSlotAcquired();
    }

    /**
     * 驱逐设备（抢占/建连超预算）：释放槽位、移出连接池、设备转 DISCONNECTED（保留任务队列），
     * 并记录冷却时间防止立即抢回槽位（§5.2 第 5 条 / §5.3）。
     */
    private void evict(String mac, long now, boolean force) {
        cooldownUntil.put(mac, now + config.getCooldownMs());
        releaseOccupied(mac, force, 0);
    }

    /**
     * 释放占用中的槽位并做三方联动：SlotManager 归还槽位、ActiveConnectionPool 移除、
     * DeviceController.onSlotReleased() 状态迁移。
     *
     * @param coolDownUntilTs 若非 0，同时写入冷却截止时间（泄漏强释放路径）
     */
    private void releaseOccupied(String mac, boolean force, long coolDownUntilTs) {
        if (force) {
            slotManager.forceRelease(mac);
        } else {
            slotManager.release(mac);
        }
        if (coolDownUntilTs != 0) {
            cooldownUntil.put(mac, coolDownUntilTs);
        }
        idleSince.remove(mac);
        DeviceController controller = activePool.get(mac);
        activePool.remove(mac);
        if (controller == null) {
            controller = deviceRegistry.findByMac(mac);
        }
        if (controller != null) {
            controller.onSlotReleased();
        }
    }

    private boolean isPinned(String mac) {
        if (pinReasons.containsKey(mac)) {
            return true;
        }
        ConnectionSlot slot = slotManager.slotOf(mac);
        return slot != null && slot.isPinned();
    }

    private boolean isPinRequest(ConnectionRequest req) {
        return req.getReason() == ConnectionRequest.Reason.FILE_TRANSFER
                || pinReasons.containsKey(req.getDeviceMac());
    }

    private boolean isPersistent(String mac) {
        if (Boolean.TRUE.equals(persistentDevices.get(mac))) {
            return true;
        }
        DeviceController controller = deviceRegistry.findByMac(mac);
        return controller != null && controller.snapshot().isPersistent();
    }
}
