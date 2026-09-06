package com.longcheer.agent.ble;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.poll.PollResultChain;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeviceControllerImpl 真实建连流程测试（SDD §7.2 / §3.2 / §12.1）。
 *
 * <p>FakeGattClient 记录调用并由测试手动触发回调（同步在测试线程执行，
 * 控制器锁可重入，流程确定）。</p>
 */
public class BleConnectFlowTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final UUID SERVICE_180F = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_2A19 = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    /** 手动驱动的 GattClient。 */
    static class FakeGattClient implements GattClient {
        Callback callback;
        final List<Integer> mtuRequests = new ArrayList<>();
        final List<UUID> notifySubscriptions = new ArrayList<>();
        boolean autoNothing = false; // true = connect 后不发任何回调（看门狗用例）

        @Override
        public void connect() {
            if (!autoNothing) {
                callback.onConnected();
            }
        }

        @Override
        public void disconnectAndClose() {
        }

        @Override
        public void discoverServices() {
            callback.onServicesDiscovered(discoveryStatus);
        }

        int discoveryStatus = 0;
        boolean acceptMtu = true;
        int acceptMtuFromIndex = 0; // 前 N 个 MTU 请求失败（阶梯降级用例）

        @Override
        public void requestMtu(int mtu) {
            int index = mtuRequests.size();
            mtuRequests.add(mtu);
            if (index < acceptMtuFromIndex) {
                callback.onMtuChanged(mtu, 133); // 失败
            } else {
                callback.onMtuChanged(mtu, acceptMtu ? 0 : 133);
            }
        }

        @Override
        public void readCharacteristic(UUID serviceUuid, UUID charUuid) {
        }

        @Override
        public void writeCharacteristic(UUID serviceUuid, UUID charUuid, byte[] payload,
                                        boolean noResponse) {
        }

        @Override
        public void setNotification(UUID serviceUuid, UUID charUuid, boolean enable) {
            notifySubscriptions.add(charUuid);
            callback.onNotifySubscribed(charUuid, 0);
        }

        void simulateDisconnect(int status) {
            callback.onDisconnected(status);
        }
    }

    private FakeGattClient fakeClient;
    private GattExecutorImpl gattExecutor;
    private StateReporter reporter;
    private PollResultChain chain;
    private ConnectionScheduler connectionScheduler;
    private AgentConfig config;
    private ScheduledExecutorService watchdog;
    private DeviceControllerImpl controller;

    @Before
    public void setUp() {
        fakeClient = new FakeGattClient();
        gattExecutor = mock(GattExecutorImpl.class);
        reporter = mock(StateReporter.class);
        chain = mock(PollResultChain.class);
        when(chain.resolveService(MAC, CHAR_2A19)).thenReturn(SERVICE_180F);
        connectionScheduler = mock(ConnectionScheduler.class);
        config = mock(AgentConfig.class);
        when(config.getConnectTimeoutMs()).thenReturn(10000L);
        when(config.getTimeSliceMs()).thenReturn(2000L);
        when(config.getNotifyMinReportIntervalMs()).thenReturn(200L);
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
                new GattResponseBus(), gattExecutor, reporter, chain, connectionScheduler,
                config, android.os.SystemClock::elapsedRealtime, watchdog);
        controller = new DeviceControllerImpl("dut-1", MAC, deps);
    }

    @Test
    public void happyPathReachesReadyAndAcksConnect() {
        controller.setPendingConnectAck("req-1");
        controller.enqueueCommand(GattCommand.simple(MAC, "cmd-1", GattCommand.Type.READ,
                SERVICE_180F, CHAR_2A19, null, GattCommand.Priority.HIGH));

        controller.onSlotAcquired(); // 同步走完整建连链（fake 立即回调）

        assertEquals(DeviceState.READY, controller.getState());
        assertEquals(512, controller.getNegotiatedMtu()); // 首档即成功
        assertEquals(Collections.singletonList(512), fakeClient.mtuRequests);
        // §7.2：CONNECT_DEVICE 挂起的 requestId 在 READY 时回 ACK 0。
        verify(reporter).reportCommandAck("req-1", 0, null);
        // READY 后欠账命令提交执行器（§7.2.1）。
        verify(gattExecutor).execute(any(GattCommand.class));
        // DEVICE_STATE 迁移链上报（§10.2）。
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter, atLeastOnce()).report(eq("DEVICE_STATE"), captor.capture());
        List<String> states = new ArrayList<>();
        for (Map<String, Object> p : captor.getAllValues()) {
            states.add((String) p.get("state"));
        }
        assertTrue(states.containsAll(Arrays.asList(
                "WAITING_SLOT", "CONNECTING", "SERVICE_DISCOVERING", "CONFIGURING", "READY")));
    }

    @Test
    public void mtuLadderDegradesOnFailure() {
        fakeClient.acceptMtuFromIndex = 2; // 512、247 失败，185 成功
        controller.onSlotAcquired();

        assertEquals(Arrays.asList(512, 247, 185), fakeClient.mtuRequests);
        assertEquals(185, controller.getNegotiatedMtu());
        assertEquals(DeviceState.READY, controller.getState());
    }

    @Test
    public void mtuLadderExhaustedKeepsDefault23() {
        fakeClient.acceptMtu = false; // 全部失败
        controller.onSlotAcquired();

        assertEquals(3, fakeClient.mtuRequests.size());
        assertEquals(23, controller.getNegotiatedMtu());
        assertEquals(DeviceState.READY, controller.getState()); // 降级后继续配置流程
    }

    @Test
    public void notifyCharacteristicsSubscribedBeforeReady() {
        controller.setPollingConfig(new PollingConfig(60000, Collections.emptyList(),
                Collections.singletonList(CHAR_2A19), true));

        controller.onSlotAcquired();

        assertEquals(Collections.singletonList(CHAR_2A19), fakeClient.notifySubscriptions);
        assertEquals(DeviceState.READY, controller.getState());
    }

    @Test
    public void connectWatchdogTimeoutTriggersReconnectFlow() {
        fakeClient.autoNothing = true; // connect 后无回调
        controller.onSlotAcquired();
        assertEquals(DeviceState.CONNECTING, controller.getState());

        controller.onConnectWatchdog(); // 直接驱动看门狗（§7.2 connectTimeoutMs）

        verify(connectionScheduler).onAbnormalDisconnect(MAC); // §7.5 入口
    }

    @Test
    public void abnormalDisconnectMidSessionDelegatesToScheduler() {
        controller.onSlotAcquired();
        assertEquals(DeviceState.READY, controller.getState());

        fakeClient.simulateDisconnect(133); // §12.1：连接状态回调的 133 = 断开

        verify(connectionScheduler).onAbnormalDisconnect(MAC);
    }

    @Test
    public void serviceDiscoveryRetriesThenFails() {
        fakeClient.discoveryStatus = 137; // §12.1：服务发现失败重试 3 次

        controller.onSlotAcquired();

        verify(connectionScheduler).onAbnormalDisconnect(MAC);
    }

    @Test
    public void connectAckFailsWith1001OnGiveUp() {
        controller.setPendingConnectAck("req-x");
        fakeClient.autoNothing = true;
        controller.onSlotAcquired();
        controller.onConnectWatchdog(); // → scheduler.onAbnormalDisconnect（mock 不驱动状态机）

        // 模拟调度器放弃路径（§7.5）：放槽 → DISCONNECTED → RECONNECTING → ERROR。
        controller.onSlotReleased();
        controller.onAbnormalDisconnect();
        assertEquals(DeviceState.RECONNECTING, controller.getState());
        controller.onReconnectGiveUp();

        assertEquals(DeviceState.ERROR, controller.getState());
        verify(reporter).reportCommandAck(eq("req-x"), eq(1001), any(Integer.class), any());
    }
}
