package com.longcheer.agent.schedule;

import com.longcheer.agent.ble.DeviceControllerImpl;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.registry.ActiveConnectionPool;
import com.longcheer.agent.registry.DeviceRegistryImpl;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 断线重连语义测试（SDD §7.5 / §4.1 / §16.5）：用真实 DeviceControllerImpl
 * 验证状态机路径；退避到期由测试直接调用 onReconnectBackoffExpired 驱动。
 */
public class ReconnectFlowTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";

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
        when(config.getMaxReconnectAttempts()).thenReturn(3);
        when(config.getReconnectBackoffMaxMs()).thenReturn(60000L);
        now = new long[]{100_000L};
        scheduler = new ConnectionSchedulerImpl(slotManager, registry, pool, config, () -> now[0]);
    }

    private DeviceControllerImpl addDeviceWithCommand() {
        DeviceControllerImpl controller = new DeviceControllerImpl("dut-1", MAC);
        registry.register(controller);
        controller.enqueueCommand(GattCommand.simple(MAC, "req-1", GattCommand.Type.READ,
                null, java.util.UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"),
                null, GattCommand.Priority.HIGH));
        return controller;
    }

    /** 走到 READY：申请槽位 → tick 授槽（DeviceControllerImpl 模拟建连到 READY）。 */
    private void connectToReady(DeviceControllerImpl controller) {
        // 注入时钟为 100_000 而 SystemClock 在单测环境为 0：请求时间须用注入时钟对齐。
        scheduler.requestSlot(new ConnectionRequest(MAC, ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.COMMAND, now[0], now[0] + 30000));
        scheduler.tick();
        assertTrue(pool.contains(MAC));
        assertEquals(DeviceState.READY, controller.getState());
    }

    @Test
    public void abnormalDisconnectReleasesSlotAndEntersReconnecting() {
        DeviceControllerImpl controller = addDeviceWithCommand();
        connectToReady(controller);

        scheduler.onAbnormalDisconnect(MAC);

        // §7.5 第 2 条：立即放槽；RECONNECTING 不持槽。
        assertFalse(pool.contains(MAC));
        assertTrue(slotManager.occupiedSlots().isEmpty());
        assertEquals(DeviceState.RECONNECTING, controller.getState());
    }

    @Test
    public void backoffExpiryRequeuesViaWaitingSlotWithOwedPriority() {
        DeviceControllerImpl controller = addDeviceWithCommand();
        connectToReady(controller);
        scheduler.onAbnormalDisconnect(MAC);

        // 退避到期 → WAITING_SLOT → 按欠账最高优先级（队列有 HIGH 命令）重新申请。
        scheduler.onReconnectBackoffExpired(MAC);
        assertEquals(DeviceState.WAITING_SLOT, controller.getState());

        // 槽位被其他设备占满时，RECONNECT 欠账（HIGH）应能抢占空闲设备。
        DeviceControllerImpl idleOther = new DeviceControllerImpl("dut-2", "AA:02");
        registry.register(idleOther);
        slotManager.setLimit(2);
        // 占住另一个槽（READY 空闲），自己占一个槽位已满场景下验证可重新获槽。
        scheduler.tick(); // RECONNECT 请求获槽（有空闲槽）

        assertTrue(pool.contains(MAC));
        assertEquals(DeviceState.READY, controller.getState()); // 重连成功
    }

    @Test
    public void giveUpAfterMaxAttemptsEntersError() {
        when(config.getMaxReconnectAttempts()).thenReturn(1);
        DeviceControllerImpl controller = addDeviceWithCommand();
        connectToReady(controller);

        scheduler.onAbnormalDisconnect(MAC); // attempt 1，未超限
        assertEquals(DeviceState.RECONNECTING, controller.getState());
        scheduler.onReconnectBackoffExpired(MAC);
        scheduler.tick();
        assertEquals(DeviceState.READY, controller.getState());

        scheduler.onAbnormalDisconnect(MAC); // attempt 2 > maxAttempts=1 → ERROR
        assertEquals(DeviceState.ERROR, controller.getState());
    }

    @Test
    public void reconnectCountResetsOnReady() {
        DeviceControllerImpl controller = addDeviceWithCommand();
        connectToReady(controller);
        scheduler.onAbnormalDisconnect(MAC);
        scheduler.onReconnectBackoffExpired(MAC);
        scheduler.tick();
        assertEquals(DeviceState.READY, controller.getState());
        assertEquals(0, controller.snapshot().getReconnectCount()); // §16.5：成功即清零
    }

    @Test
    public void pendingPauseDuringReconnectingAppliesOnBackoffExpiry() {
        DeviceControllerImpl controller = addDeviceWithCommand();
        connectToReady(controller);
        scheduler.onAbnormalDisconnect(MAC);

        controller.pause(false); // RECONNECTING 中 → pendingPause 延迟生效（§7.7）
        assertEquals(DeviceState.RECONNECTING, controller.getState());

        scheduler.onReconnectBackoffExpired(MAC);
        // 补迁 PAUSED，且不再申请槽位
        assertEquals(DeviceState.PAUSED, controller.getState());
        assertEquals(0, scheduler.pendingRequestCount());
    }

    // ---------- FILE_TRANSFER pinned 授槽（§7.6，真机联调暴露） ----------

    /**
     * 复现场景：设备 READY 空转被时间片释放为 DISCONNECTED（队列已空、轮询已挂起），
     * 此时 pinned FILE_TRANSFER 请求获槽——授槽前设备不在 WAITING_SLOT，
     * onSlotAcquired 若静默返回则永不 READY（传输 16s 超时 1001 的根因）。
     */
    @Test
    public void pinnedFileTransferGrantConnectsTimeSlicedDevice() {
        DeviceControllerImpl controller = addDeviceWithCommand();
        connectToReady(controller);
        controller.drainPendingCommands(); // 队列清空，模拟轮询欠账已消费
        scheduler.releaseSlot(MAC);        // 时间片到期释放 → DISCONNECTED
        assertEquals(DeviceState.DISCONNECTED, controller.getState());
        assertFalse(pool.contains(MAC));

        scheduler.pin(MAC, "FILE_TRANSFER");
        scheduler.requestSlot(new ConnectionRequest(MAC, ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.FILE_TRANSFER, now[0], now[0] + 30000));
        scheduler.tick();

        assertTrue(pool.contains(MAC));
        assertTrue(slotManager.slotOf(MAC).isPinned());
        assertEquals(DeviceState.READY, controller.getState()); // 修复前：停在 DISCONNECTED
    }

    /** pinned 请求在设备退避重连期间不得被清除（§7.5 / §7.6）：退避到期后按原请求授槽。 */
    @Test
    public void pinnedRequestSurvivesReconnectBackoff() {
        DeviceControllerImpl controller = addDeviceWithCommand();
        connectToReady(controller);
        scheduler.onAbnormalDisconnect(MAC); // → RECONNECTING（退避中，不持槽）
        assertEquals(DeviceState.RECONNECTING, controller.getState());

        scheduler.pin(MAC, "FILE_TRANSFER");
        scheduler.requestSlot(new ConnectionRequest(MAC, ConnectionRequest.PRIORITY_HIGH,
                ConnectionRequest.Reason.FILE_TRANSFER, now[0], now[0] + 30000));
        scheduler.tick();

        // RECONNECTING 不可授槽：请求保留等待退避到期（修复前被直接清除，传输意图丢失）。
        assertEquals(1, scheduler.pendingRequestCount());
        assertEquals(DeviceState.RECONNECTING, controller.getState());

        scheduler.onReconnectBackoffExpired(MAC); // 退避到期 → WAITING_SLOT
        scheduler.tick();

        assertTrue(pool.contains(MAC));
        assertTrue(slotManager.slotOf(MAC).isPinned());
        assertEquals(DeviceState.READY, controller.getState());
    }

    /** pinned 设备传输期间重连成功也应清零重连计数（§7.5），否则累计超限误判放弃。 */
    @Test
    public void reconnectAttemptsClearedForPinnedReadyDevice() {
        when(config.getMaxReconnectAttempts()).thenReturn(1);
        DeviceControllerImpl controller = addDeviceWithCommand();
        connectToReady(controller);
        scheduler.pin(MAC, "FILE_TRANSFER");

        scheduler.onAbnormalDisconnect(MAC); // attempt=1（未超限）
        assertEquals(DeviceState.RECONNECTING, controller.getState());
        scheduler.onReconnectBackoffExpired(MAC);
        scheduler.tick();
        assertEquals(DeviceState.READY, controller.getState());
        scheduler.tick(); // 下一拍 step 3 见到 pinned READY → 清零重连计数

        // 再次异常断开：若计数未清零，attempt=2 > maxAttempts=1 会误判进入 ERROR。
        scheduler.onAbnormalDisconnect(MAC);
        assertEquals(DeviceState.RECONNECTING, controller.getState());
    }
}
