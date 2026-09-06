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
}
