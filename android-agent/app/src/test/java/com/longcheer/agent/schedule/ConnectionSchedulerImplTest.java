package com.longcheer.agent.schedule;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.registry.ActiveConnectionPool;
import com.longcheer.agent.registry.DeviceRegistryImpl;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConnectionSchedulerImpl 单测（SDD §5 / §6 / §16.5）。
 *
 * <p>时钟通过 LongSupplier 注入，测试直接推进 now[0] 并调用包可见的 tick()。</p>
 */
public class ConnectionSchedulerImplTest {

    private static final long T0 = 100_000L;

    private ConnectionSlotManagerImpl slotManager;
    private DeviceRegistryImpl registry;
    private ActiveConnectionPool pool;
    private AgentConfig config;
    private long[] now;
    private ConnectionSchedulerImpl scheduler;

    @Before
    public void setUp() {
        slotManager = new ConnectionSlotManagerImpl(3);
        registry = new DeviceRegistryImpl();
        pool = new ActiveConnectionPool();
        config = mock(AgentConfig.class);
        when(config.getMaxSlots()).thenReturn(3);
        when(config.getTickIntervalMs()).thenReturn(100L);
        when(config.getCooldownMs()).thenReturn(5000L);
        when(config.getSetupBudgetMs()).thenReturn(4000L);
        when(config.getAgingThresholdMs()).thenReturn(30000L);
        when(config.getTimeSliceMs()).thenReturn(2000L);
        when(config.getIdleReleaseMs()).thenReturn(30000L);
        now = new long[]{T0};
        scheduler = new ConnectionSchedulerImpl(slotManager, registry, pool, config, () -> now[0]);
    }

    // ---------- 工具 ----------

    private DeviceController addDevice(String mac, DeviceState state) {
        DeviceController controller = mock(DeviceController.class);
        ManagedDeviceInfo info = new ManagedDeviceInfo("id-" + mac, mac);
        info.setState(state);
        when(controller.snapshot()).thenReturn(info);
        when(controller.getState()).thenReturn(state);
        when(controller.isReady()).thenReturn(state == DeviceState.READY);
        registry.register(controller);
        return controller;
    }

    private ConnectionRequest request(String mac, int priority, ConnectionRequest.Reason reason,
                                      long requestTime, long expireTime) {
        return new ConnectionRequest(mac, priority, reason, requestTime, expireTime);
    }

    /** 让设备直接占用槽位并入池（模拟已连接）；lastConnectedTime 取当前测试时钟。 */
    private void occupy(DeviceController controller, DeviceState state) {
        String mac = controller.snapshot().getMac();
        slotManager.acquire(mac, false);
        pool.put(mac, controller);
        when(controller.getState()).thenReturn(state);
        when(controller.isReady()).thenReturn(state == DeviceState.READY);
        controller.snapshot().setState(state);
        controller.snapshot().setLastConnectedTime(now[0]);
    }

    // ---------- M2-2 请求队列 ----------

    @Test
    public void upsertSameMacKeepsSingleRequestWithMaxPriority() {
        addDevice("AA:01", DeviceState.REGISTERED);
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_NORMAL,
                ConnectionRequest.Reason.POLL, T0, T0 + 30000));
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));
        assertEquals(1, scheduler.pendingRequestCount());
    }

    @Test
    public void commandBeatsPollWhenCompetingForSingleSlot() {
        addDevice("AA:01", DeviceState.REGISTERED);
        addDevice("AA:02", DeviceState.REGISTERED);
        slotManager.setLimit(1);
        // 轮询先登记，命令后登记：命令必须优先获槽（修复 M1 优先级方向问题）。
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_NORMAL,
                ConnectionRequest.Reason.POLL, T0, T0 + 30000));
        scheduler.requestSlot(request("AA:02", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0 + 1, T0 + 30001));

        scheduler.tick();

        assertTrue(pool.contains("AA:02"));
        assertFalse(pool.contains("AA:01"));
        assertEquals(1, scheduler.pendingRequestCount());
    }

    @Test
    public void expiredRequestIsDropped() {
        addDevice("AA:01", DeviceState.REGISTERED);
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_NORMAL,
                ConnectionRequest.Reason.POLL, T0 - 60000, T0 - 1));

        scheduler.tick();

        assertEquals(0, scheduler.pendingRequestCount());
        assertFalse(pool.contains("AA:01"));
    }

    @Test
    public void requestForPausedDeviceIsDropped() {
        addDevice("AA:01", DeviceState.PAUSED);
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));

        scheduler.tick();

        assertEquals(0, scheduler.pendingRequestCount());
        assertFalse(pool.contains("AA:01"));
    }

    @Test
    public void grantPutsDeviceInPoolAndTriggersConnect() {
        DeviceController controller = addDevice("AA:01", DeviceState.WAITING_SLOT);
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));

        scheduler.tick();

        assertTrue(pool.contains("AA:01"));
        verify(controller).onSlotAcquired();
        assertEquals(0, scheduler.pendingRequestCount());
    }

    // ---------- M2-3 selectVictim 抢占 ----------

    @Test
    public void highPriorityCommandPreemptsIdleDevice() {
        DeviceController idle = addDevice("AA:01", DeviceState.READY);
        DeviceController busy = addDevice("AA:02", DeviceState.READY);
        occupy(idle, DeviceState.READY);
        occupy(busy, DeviceState.READY);
        busy.snapshot().getPendingCommands().offer(new GattCommand("AA:02", GattCommand.Priority.HIGH));
        slotManager.setLimit(2);
        DeviceController newcomer = addDevice("AA:03", DeviceState.WAITING_SLOT);
        scheduler.requestSlot(request("AA:03", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));

        scheduler.tick();

        // 空闲且无任务的 AA:01 被踢，新设备获槽。
        assertFalse(pool.contains("AA:01"));
        assertTrue(pool.contains("AA:02"));
        assertTrue(pool.contains("AA:03"));
        verify(idle).onSlotReleased();
        verify(newcomer).onSlotAcquired();
    }

    @Test
    public void pinnedAndPersistentDevicesAreNeverEvicted() {
        DeviceController pinned = addDevice("AA:01", DeviceState.READY);
        DeviceController persistent = addDevice("AA:02", DeviceState.READY);
        occupy(pinned, DeviceState.READY);
        occupy(persistent, DeviceState.READY);
        scheduler.pin("AA:01", "FILE_TRANSFER");
        scheduler.setPersistent("AA:02", true);
        slotManager.setLimit(2);
        addDevice("AA:03", DeviceState.WAITING_SLOT);
        scheduler.requestSlot(request("AA:03", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));

        scheduler.tick();

        // 无可踢对象 → 新命令排队等待。
        assertFalse(pool.contains("AA:03"));
        assertTrue(pool.contains("AA:01"));
        assertTrue(pool.contains("AA:02"));
        assertEquals(1, scheduler.pendingRequestCount());
    }

    @Test
    public void notifyBoostedDeviceIsNotEvicted() {
        DeviceController boosted = addDevice("AA:01", DeviceState.READY);
        occupy(boosted, DeviceState.READY);
        // 通知活跃期未过期（§7.3.3）。
        boosted.snapshot().setNotifyBoostUntil(T0 + 60000);
        slotManager.setLimit(1);
        addDevice("AA:02", DeviceState.WAITING_SLOT);
        scheduler.requestSlot(request("AA:02", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));

        scheduler.tick();

        assertTrue(pool.contains("AA:01"));
        assertFalse(pool.contains("AA:02"));
    }

    // ---------- M2-4 时间片与建连预算 ----------

    @Test
    public void readyDeviceReleasedAfterTimeSliceWhenQueueEmpty() {
        DeviceController controller = addDevice("AA:01", DeviceState.REGISTERED);
        occupy(controller, DeviceState.READY);
        controller.snapshot().setLastConnectedTime(T0);
        // 时间片 2000ms；推进到到期之后。
        now[0] = T0 + 2001;

        scheduler.tick();

        assertFalse(pool.contains("AA:01"));
        verify(controller).onSlotReleased();
        assertTrue(slotManager.occupiedSlots().isEmpty());
    }

    @Test
    public void readyDeviceWithPendingTasksSurvivesTimeSlice() {
        DeviceController controller = addDevice("AA:01", DeviceState.REGISTERED);
        occupy(controller, DeviceState.READY);
        controller.snapshot().setLastConnectedTime(T0);
        controller.snapshot().getPendingCommands().offer(new GattCommand("AA:01", GattCommand.Priority.NORMAL));
        now[0] = T0 + 5000;

        scheduler.tick();

        assertTrue(pool.contains("AA:01"));
    }

    @Test
    public void connectingDeviceForceReleasedAfterSetupBudget() {
        DeviceController controller = addDevice("AA:01", DeviceState.REGISTERED);
        occupy(controller, DeviceState.CONNECTING);
        // 槽位 acquireTime 为 SystemClock（单测环境 = 0），推进到超预算。
        now[0] = 4001;

        scheduler.tick();

        assertFalse(pool.contains("AA:01"));
        verify(controller).onSlotReleased();
        assertTrue(slotManager.occupiedSlots().isEmpty());
    }

    @Test
    public void connectingDeviceWithinBudgetIsKept() {
        DeviceController controller = addDevice("AA:01", DeviceState.REGISTERED);
        occupy(controller, DeviceState.CONNECTING);
        now[0] = 3000;

        scheduler.tick();

        assertTrue(pool.contains("AA:01"));
    }

    @Test
    public void idleDeviceReleasedAfterIdleTimeout() {
        when(config.getTimeSliceMs()).thenReturn(60000L); // 排除时间片干扰
        DeviceController controller = addDevice("AA:01", DeviceState.REGISTERED);
        occupy(controller, DeviceState.READY);
        controller.snapshot().setLastConnectedTime(T0);

        scheduler.tick();          // 首次发现空闲，记录 idleSince
        assertTrue(pool.contains("AA:01"));
        now[0] = T0 + 30001;       // 超过 idleReleaseMs(30000)
        scheduler.tick();

        assertFalse(pool.contains("AA:01"));
    }

    // ---------- M2-5 冷却过滤与欠账老化 ----------

    @Test
    public void evictedDeviceBlockedDuringCooldown() {
        DeviceController evicted = addDevice("AA:01", DeviceState.READY);
        occupy(evicted, DeviceState.READY);
        slotManager.setLimit(1);
        addDevice("AA:02", DeviceState.WAITING_SLOT);
        scheduler.requestSlot(request("AA:02", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));

        scheduler.tick(); // AA:01 被踢，AA:02 获槽
        assertFalse(pool.contains("AA:01"));

        // 被踢设备立即凭欠账重新请求：冷却期内不得获槽（§5.2 第 5 条）。
        when(evicted.getState()).thenReturn(DeviceState.DISCONNECTED);
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 60000));
        scheduler.tick();
        assertFalse(pool.contains("AA:01"));

        // 冷却结束后（AA:02 先让出槽位）可以重新获槽。
        now[0] = T0 + 5001;
        scheduler.releaseSlot("AA:02");
        scheduler.tick();
        assertTrue(pool.contains("AA:01"));
    }

    @Test
    public void agedPollRequestGainsEffectivePriorityUpToHigh() {
        // 槽满且占用者空闲可踢：新轮询（NORMAL）不能抢占，老化到 HIGH 后可以（§5.2 第 6 条）。
        DeviceController idle = addDevice("AA:01", DeviceState.READY);
        occupy(idle, DeviceState.READY);
        slotManager.setLimit(1);
        addDevice("AA:02", DeviceState.REGISTERED);

        scheduler.requestSlot(request("AA:02", ConnectionRequest.PRIORITY_NORMAL,
                ConnectionRequest.Reason.POLL, T0, T0 + 120000));
        scheduler.tick();
        assertFalse(pool.contains("AA:02")); // 新轮询不抢占

        now[0] = T0 + 60000; // 等待 2 × agingThresholdMs → 1 + 2 = HIGH
        scheduler.tick();
        assertTrue(pool.contains("AA:02"));  // 老化升级后抢占成功
        assertFalse(pool.contains("AA:01"));
    }

    // ---------- M2-6 动态槽数 ----------

    @Test
    public void shrinkSlotsEvictsIdleDeviceFirst() {
        DeviceController a = addDevice("AA:01", DeviceState.READY);
        DeviceController b = addDevice("AA:02", DeviceState.READY);
        DeviceController c = addDevice("AA:03", DeviceState.READY);
        occupy(a, DeviceState.READY);
        occupy(b, DeviceState.READY);
        occupy(c, DeviceState.READY);
        b.snapshot().getPendingCommands().offer(new GattCommand("AA:02", GattCommand.Priority.HIGH));
        // AA:01 空闲时间最长（更早进入 READY），应被优先驱逐。
        a.snapshot().setLastConnectedTime(T0 - 1000);

        boolean accepted = scheduler.setMaxSlots(2);

        assertTrue(accepted);
        assertEquals(2, slotManager.slotCount());
        assertEquals(2, pool.size());
        assertFalse(pool.contains("AA:01")); // 最空闲者被驱逐
        verify(a).onSlotReleased();
    }

    @Test
    public void shrinkBelowPersistentPlusPinnedIsRefused() {
        DeviceController a = addDevice("AA:01", DeviceState.READY);
        DeviceController b = addDevice("AA:02", DeviceState.READY);
        DeviceController c = addDevice("AA:03", DeviceState.READY);
        occupy(a, DeviceState.READY);
        occupy(b, DeviceState.READY);
        occupy(c, DeviceState.READY);
        scheduler.setPersistent("AA:01", true);
        scheduler.setPersistent("AA:02", true);
        scheduler.setPersistent("AA:03", true); // required = 3

        boolean accepted = scheduler.setMaxSlots(2); // required=3 > 2

        assertFalse(accepted);
        assertEquals(3, pool.size()); // 不改变任何已连状态
        assertEquals(3, scheduler.requiredSlots());
        assertEquals(3, slotManager.slotCount());
    }

    @Test
    public void growSlotsTakesEffectImmediately() {
        assertTrue(scheduler.setMaxSlots(2));
        assertEquals(2, slotManager.slotCount());
        assertTrue(scheduler.setMaxSlots(3));
        assertEquals(3, slotManager.slotCount());
        addDevice("AA:01", DeviceState.REGISTERED);
        addDevice("AA:02", DeviceState.REGISTERED);
        addDevice("AA:03", DeviceState.REGISTERED);
        for (String mac : new String[]{"AA:01", "AA:02", "AA:03"}) {
            scheduler.requestSlot(request(mac, ConnectionRequest.PRIORITY_NORMAL,
                    ConnectionRequest.Reason.POLL, T0, T0 + 30000));
        }
        scheduler.tick();
        assertEquals(3, pool.size()); // 等待队列按优先级补位
    }

    // ---------- M2-7 常驻设备 ----------

    @Test
    public void persistentRequestNeverExpiresAndSelfHeals() {
        DeviceController controller = addDevice("AA:01", DeviceState.REGISTERED);
        scheduler.setPersistent("AA:01", true);

        now[0] = T0 + 120000; // 远超默认 30s 过期
        scheduler.tick();
        assertTrue(pool.contains("AA:01")); // 常驻请求不过期，仍获槽

        // 掉线后（释放槽位 + 状态回落）下一 tick 自动重新登记并获槽。
        scheduler.releaseSlot("AA:01");
        when(controller.getState()).thenReturn(DeviceState.DISCONNECTED);
        now[0] += 1000;
        scheduler.tick();
        assertTrue(pool.contains("AA:01"));
    }

    @Test
    public void unsetPersistentStopsSelfHeal() {
        DeviceController controller = addDevice("AA:01", DeviceState.REGISTERED);
        scheduler.setPersistent("AA:01", true);
        scheduler.tick();
        assertTrue(pool.contains("AA:01"));

        scheduler.setPersistent("AA:02", true); // 另一个常驻占位无关
        scheduler.setPersistent("AA:01", false);
        scheduler.releaseSlot("AA:01");
        when(controller.getState()).thenReturn(DeviceState.DISCONNECTED);
        scheduler.tick();
        assertFalse(pool.contains("AA:01")); // 不再自愈
    }

    // ---------- 挂起/恢复（§12.10）与主动释放 ----------

    @Test
    public void suspendReleasesAllSlotsAndBlocksGranting() {
        DeviceController a = addDevice("AA:01", DeviceState.WAITING_SLOT);
        DeviceController b = addDevice("AA:02", DeviceState.WAITING_SLOT);
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));
        scheduler.requestSlot(request("AA:02", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));
        scheduler.tick();
        assertEquals(2, pool.size());

        scheduler.suspendScheduling();
        assertEquals(0, pool.size());
        assertTrue(slotManager.occupiedSlots().isEmpty());
        verify(a).onSlotReleased();
        verify(b).onSlotReleased();

        // 挂起期间不授槽。
        when(a.getState()).thenReturn(DeviceState.DISCONNECTED);
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0 + 1, T0 + 60000));
        scheduler.tick();
        assertFalse(pool.contains("AA:01"));

        // 恢复后欠账重新获槽。
        scheduler.resumeScheduling();
        scheduler.tick();
        assertTrue(pool.contains("AA:01"));
    }

    @Test
    public void releaseSlotFreesSlotAndPool() {
        DeviceController controller = addDevice("AA:01", DeviceState.WAITING_SLOT);
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, T0, T0 + 30000));
        scheduler.tick();
        assertTrue(pool.contains("AA:01"));

        scheduler.releaseSlot("AA:01");
        assertFalse(pool.contains("AA:01"));
        assertTrue(slotManager.occupiedSlots().isEmpty());
        verify(controller).onSlotReleased();
    }

    @Test
    public void fileTransferRequestAcquiresPinnedSlot() {
        addDevice("AA:01", DeviceState.REGISTERED);
        scheduler.requestSlot(request("AA:01", ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.FILE_TRANSFER, T0, T0 + 30000));
        scheduler.tick();
        assertTrue(slotManager.slotOf("AA:01").isPinned());
    }
}
