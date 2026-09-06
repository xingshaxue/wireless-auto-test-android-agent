package com.longcheer.agent.ble;

import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.QueuedTask;

import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * DeviceControllerImpl 生命周期测试（SDD §7.7 / §7.8 / §4.1）。
 */
public class DeviceControllerImplTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final UUID CHAR = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    private static DeviceControllerImpl newController() {
        return new DeviceControllerImpl("dut-1", MAC);
    }

    @Test
    public void pauseOnReadyIsImmediate() {
        DeviceControllerImpl c = newController();
        c.onSlotAcquired(); // 模拟建连 → READY
        assertEquals(DeviceState.READY, c.getState());

        c.pause(false);
        assertEquals(DeviceState.PAUSED, c.getState());
    }

    @Test
    public void pauseDuringReconnectingIsDeferred() {
        DeviceControllerImpl c = newController();
        c.enqueueCommand(GattCommand.simple(MAC, "r1", GattCommand.Type.READ, null, CHAR, null,
                GattCommand.Priority.HIGH));
        c.onSlotAcquired();
        c.onSlotReleased();       // → DISCONNECTED
        c.onAbnormalDisconnect(); // → RECONNECTING
        assertEquals(DeviceState.RECONNECTING, c.getState());

        c.pause(false); // 连接/重连中 → 延迟生效
        assertEquals(DeviceState.RECONNECTING, c.getState());

        c.onReconnectBackoffExpired(); // 退避到期 → WAITING_SLOT → 补迁 PAUSED
        assertEquals(DeviceState.PAUSED, c.getState());
    }

    @Test
    public void resumeFromPausedWithDebtGoesWaitingSlot() {
        DeviceControllerImpl c = newController();
        c.enqueueCommand(GattCommand.simple(MAC, "r1", GattCommand.Type.READ, null, CHAR, null,
                GattCommand.Priority.HIGH));
        c.pause(false);
        assertEquals(DeviceState.PAUSED, c.getState());

        c.resume();
        // §7.7：有欠账 → WAITING_SLOT
        assertEquals(DeviceState.WAITING_SLOT, c.getState());
    }

    @Test
    public void drainPendingCommandsReturnsAndClears() {
        DeviceControllerImpl c = newController();
        c.enqueueCommand(GattCommand.simple(MAC, "r1", GattCommand.Type.READ, null, CHAR, null,
                GattCommand.Priority.HIGH));
        c.enqueueCommand(GattCommand.simple(MAC, "r2", GattCommand.Type.WRITE, null, CHAR,
                new byte[]{1}, GattCommand.Priority.HIGH));

        List<QueuedTask> drained = c.drainPendingCommands();

        assertEquals(2, drained.size());
        assertTrue(c.snapshot().getPendingCommands().isEmpty());
    }

    @Test
    public void resetReturnsToRegisteredAndClearsContext() {
        DeviceControllerImpl c = newController();
        c.enqueueCommand(GattCommand.simple(MAC, "r1", GattCommand.Type.READ, null, CHAR, null,
                GattCommand.Priority.HIGH));
        c.setStateFlag("OVERTEMP");
        c.onSlotAcquired(); // READY

        c.reset();

        assertEquals(DeviceState.REGISTERED, c.getState());
        assertTrue(c.snapshot().getPendingCommands().isEmpty());
        assertEquals(0, c.snapshot().getReconnectCount());
        assertEquals(null, c.snapshot().getStateFlag());
    }

    @Test(expected = IllegalStateException.class)
    public void illegalTransitionRejectedByStateMachine() {
        DeviceControllerImpl c = newController();
        // REGISTERED → READY 非法（§4.1 须经 CONNECTING 链）：经暂停恢复路径制造。
        c.pause(false); // REGISTERED → PAUSED（合法）
        c.onSlotAcquired(); // PAUSED → 不应发生迁移
        c.resume(); // PAUSED → REGISTERED（无欠账）
        c.onReconnectBackoffExpired(); // 非 RECONNECTING → no-op
        c.onAbnormalDisconnect(); // 非 DISCONNECTED → no-op
        // 直接触发一个非法迁移：terminate 两次（TERMINATED 终态不可再迁）
        c.terminate();
        c.terminate(); // TERMINATED → TERMINATED 非法
    }
}
