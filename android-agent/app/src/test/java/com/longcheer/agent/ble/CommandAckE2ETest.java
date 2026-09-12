package com.longcheer.agent.ble;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.poll.PollResultChain;
import com.longcheer.agent.registry.DeviceRegistryImpl;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingSchedulerImpl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * READ_CHAR/WRITE_CHAR 的 CMD_ACK 端到端闭环（SDD §7.4 第 8 步 / §12.9 / §A.3）：
 * 入队 → 获槽 → READY → GATT 执行 → 执行完成才回 CMD_ACK（requestId 对账）。
 *
 * <p>全真实组件链：DeviceControllerImpl + GattExecutorImpl + GattTransportImpl +
 * GattResponseBus + PollingSchedulerImpl（ACK 决策点）；仅 GattClient 为同步假实现、
 * StateReporter/PollResultChain/ConnectionScheduler 为 mock。执行器异步，
 * 断言用 Mockito timeout 等待。</p>
 */
public class CommandAckE2ETest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final UUID SERVICE_180F = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_2A19 = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    /** 同步应答的 GATT 客户端：建连链全自动成功；读/写状态可脚本化。 */
    static class FakeGattClient implements GattClient {
        Callback callback;
        byte[] readValue = new byte[]{0x55};
        int readStatus = 0;
        int writeStatus = 0;

        @Override
        public void connect() {
            callback.onConnected();
        }

        @Override
        public void disconnectAndClose() {
        }

        @Override
        public void discoverServices() {
            callback.onServicesDiscovered(0);
        }

        @Override
        public void requestMtu(int mtu) {
            callback.onMtuChanged(mtu, 0);
        }

        @Override
        public void readCharacteristic(UUID serviceUuid, UUID charUuid) {
            callback.onRead(charUuid, readValue == null ? null : readValue.clone(), readStatus);
        }

        @Override
        public void writeCharacteristic(UUID serviceUuid, UUID charUuid, byte[] payload,
                                        boolean noResponse) {
            callback.onWrite(charUuid, writeStatus);
        }

        @Override
        public void setNotification(UUID serviceUuid, UUID charUuid, boolean enable) {
            callback.onNotifySubscribed(charUuid, 0);
        }
    }

    private FakeGattClient fakeClient;
    private StateReporter reporter;
    private DeviceControllerImpl controller;
    private GattExecutorImpl gattExecutor;
    private ScheduledExecutorService watchdog;

    @Before
    public void setUp() {
        fakeClient = new FakeGattClient();
        reporter = mock(StateReporter.class);
        PollResultChain chain = mock(PollResultChain.class);
        // fields 映射命中 2A19 → battery（uint8），供 decoded 断言。
        when(chain.decode(eq(MAC), anyMap())).thenReturn(Collections.singletonMap("battery", 85));
        ConnectionScheduler connectionScheduler = mock(ConnectionScheduler.class);
        AgentConfig config = mock(AgentConfig.class);
        when(config.getTickIntervalMs()).thenReturn(100L);
        when(config.getMaxSlots()).thenReturn(3);
        when(config.getSetupBudgetMs()).thenReturn(4000L);
        when(config.getTimeSliceMs()).thenReturn(2000L);
        when(config.getStaleThresholdMs()).thenReturn(120000L);
        when(config.getConnectTimeoutMs()).thenReturn(10000L);

        DeviceRegistryImpl registry = new DeviceRegistryImpl();
        PollingSchedulerImpl pollingScheduler = new PollingSchedulerImpl(registry,
                connectionScheduler, config, chain, reporter);
        GattResponseBus responseBus = new GattResponseBus();
        GattTransportImpl transport = new GattTransportImpl(mac -> fakeClient, responseBus,
                chain::resolveService);
        gattExecutor = new GattExecutorImpl(transport, pollingScheduler);
        watchdog = Executors.newSingleThreadScheduledExecutor();

        DeviceControllerDeps deps = new DeviceControllerDeps(
                new DeviceControllerDeps.GattClientFactory() {
                    @Override
                    public GattClient create(String mac, GattClient.Callback callback) {
                        fakeClient.callback = callback;
                        return fakeClient;
                    }

                    @Override
                    public void removeClient(String mac) {
                    }
                },
                responseBus, gattExecutor, reporter, chain, connectionScheduler,
                config, android.os.SystemClock::elapsedRealtime, watchdog);
        controller = new DeviceControllerImpl("dut-1", MAC, deps);
        registry.register(controller);
    }

    @After
    public void tearDown() {
        gattExecutor.shutdown();
        watchdog.shutdownNow();
    }

    @Test
    public void offlineDeviceAcksAfterExecutionWithValue() {
        // 设备未连接：入队 → WAITING_SLOT，此时不得回 ACK（延迟 ACK，§7.4 第 5 步）。
        GattCommand cmd = GattCommand.simple(MAC, "cmd-1", GattCommand.Type.READ,
                SERVICE_180F, CHAR_2A19, null, GattCommand.Priority.HIGH);
        assertTrue(controller.enqueueCommand(cmd));
        assertEquals(DeviceState.WAITING_SLOT, controller.getState());
        verify(reporter, never()).reportCommandAck(eq("cmd-1"), anyInt(), any());

        controller.onSlotAcquired(); // 建连链同步走通 → READY → drain 执行

        assertEquals(DeviceState.READY, controller.getState());
        // 执行完成后回 ACK：value(base64)/valueHex/decoded 三形态（§A.3）。
        verify(reporter, timeout(3000)).reportCommandAck(eq("cmd-1"), eq(0), argThat(r ->
                r instanceof Map
                        && "VQ==".equals(((Map<?, ?>) r).get("value"))
                        && "55".equals(((Map<?, ?>) r).get("valueHex"))
                        && Integer.valueOf(85).equals(((Map<?, ?>) r).get("decoded"))));
    }

    @Test
    public void readFailureAcks1xxxWithRawStatus() {
        controller.onSlotAcquired();
        assertEquals(DeviceState.READY, controller.getState());
        fakeClient.readStatus = 133; // §12.1：GATT 失败透传

        controller.enqueueCommand(GattCommand.simple(MAC, "cmd-2", GattCommand.Type.READ,
                SERVICE_180F, CHAR_2A19, null, GattCommand.Priority.HIGH));

        verify(reporter, timeout(3000)).reportCommandAck(eq("cmd-2"), eq(1001), eq(133), isNull());
    }

    @Test
    public void writeSuccessAcksWrittenTrue() {
        controller.onSlotAcquired();
        assertEquals(DeviceState.READY, controller.getState());

        controller.enqueueCommand(GattCommand.simple(MAC, "cmd-3", GattCommand.Type.WRITE,
                SERVICE_180F, CHAR_2A19, new byte[]{1}, GattCommand.Priority.HIGH));

        verify(reporter, timeout(3000)).reportCommandAck(eq("cmd-3"), eq(0), argThat(r ->
                r instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) r).get("written"))));
    }

    @Test
    public void writeFailureAcks1xxxWithRawStatus() {
        controller.onSlotAcquired();
        assertEquals(DeviceState.READY, controller.getState());
        fakeClient.writeStatus = 8; // GATT_INSUFFICIENT_AUTHORIZATION

        controller.enqueueCommand(GattCommand.simple(MAC, "cmd-4", GattCommand.Type.WRITE,
                SERVICE_180F, CHAR_2A19, new byte[]{1}, GattCommand.Priority.HIGH));

        verify(reporter, timeout(3000)).reportCommandAck(eq("cmd-4"), eq(1001), eq(8), isNull());
    }
}
