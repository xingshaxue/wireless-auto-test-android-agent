package com.longcheer.agent.dispatch;

import com.longcheer.agent.ble.BleCentralManager;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingScheduler;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CommandDispatcherTest {

    private BleCentralManager bleCentralManager;
    private DeviceRegistry deviceRegistry;
    private ConnectionScheduler connectionScheduler;
    private PollingScheduler pollingScheduler;
    private StateReporter stateReporter;
    private AgentConfig config;
    private com.longcheer.agent.poll.PollResultChain pollResultChain;
    private CommandDispatcher dispatcher;

    @Before
    public void setUp() {
        bleCentralManager = mock(BleCentralManager.class);
        deviceRegistry = mock(DeviceRegistry.class);
        connectionScheduler = mock(ConnectionScheduler.class);
        pollingScheduler = mock(PollingScheduler.class);
        stateReporter = mock(StateReporter.class);
        config = mock(AgentConfig.class);
        pollResultChain = mock(com.longcheer.agent.poll.PollResultChain.class);
        when(config.getMaxSlots()).thenReturn(3);

        dispatcher = new CommandDispatcherImpl(bleCentralManager, deviceRegistry,
                connectionScheduler, pollingScheduler, stateReporter, config, pollResultChain);
    }

    @Test
    public void testConnectDeviceCreatesController() {
        DeviceController controller = mock(DeviceController.class);
        when(bleCentralManager.createController("AA:BB:CC:DD:EE:FF", "dut-001"))
                .thenReturn(controller);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "CONNECT_DEVICE");
        cmd.put("requestId", "req-1");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");
        cmd.put("deviceId", "dut-001");

        dispatcher.dispatch(cmd);

        verify(bleCentralManager).createController("AA:BB:CC:DD:EE:FF", "dut-001");
        verify(connectionScheduler).requestSlot(any());
        verify(stateReporter).reportCommandAck("req-1", 0, null);
    }

    @Test
    public void testReadCharQueuesCommand() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", DeviceState.READY);
        when(deviceRegistry.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(controller);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "READ_CHAR");
        cmd.put("requestId", "req-2");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");
        cmd.put("service", "180F");
        cmd.put("char", "2A19");

        dispatcher.dispatch(cmd);

        ArgumentCaptor<GattCommand> captor = ArgumentCaptor.forClass(GattCommand.class);
        verify(controller).enqueueCommand(captor.capture());
        assertEquals("AA:BB:CC:DD:EE:FF", captor.getValue().getDeviceMac());
        assertEquals("req-2", captor.getValue().getRequestId());
        assertEquals(GattCommand.Type.READ, captor.getValue().getType());
        verify(stateReporter).reportCommandAck("req-2", 0, "queued");
    }

    @Test
    public void testWriteCharQueuesCommand() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", DeviceState.READY);
        when(deviceRegistry.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(controller);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "WRITE_CHAR");
        cmd.put("requestId", "req-3");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");
        cmd.put("char", "2A19");
        cmd.put("payload", "AQID");

        dispatcher.dispatch(cmd);

        ArgumentCaptor<GattCommand> captor = ArgumentCaptor.forClass(GattCommand.class);
        verify(controller).enqueueCommand(captor.capture());
        assertEquals(GattCommand.Type.WRITE, captor.getValue().getType());
        assertNotNull(captor.getValue().getPayload());
        verify(stateReporter).reportCommandAck("req-3", 0, "queued");
    }

    @Test
    public void testDeviceNotFoundReturns2003() {
        when(deviceRegistry.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(null);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "READ_CHAR");
        cmd.put("requestId", "req-4");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");
        cmd.put("char", "2A19");

        dispatcher.dispatch(cmd);

        verify(stateReporter).reportCommandAck("req-4", 2003, "device not found");
    }

    @Test
    public void testSetMaxConnectionsValid() {
        when(connectionScheduler.setMaxSlots(4)).thenReturn(true);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "SET_MAX_CONNECTIONS");
        cmd.put("requestId", "req-5");
        cmd.put("maxSlots", 4);

        dispatcher.dispatch(cmd);

        verify(connectionScheduler).setMaxSlots(4);
        verify(stateReporter).reportCommandAck("req-5", 0, null);
    }

    @Test
    public void testSetMaxConnectionsEvictionRefusedReturns3003() {
        // §5.4：新上限低于常驻 + pinned 数 → 拒绝调整，回 3003 且报文体带 required/max。
        when(connectionScheduler.setMaxSlots(2)).thenReturn(false);
        when(connectionScheduler.requiredSlots()).thenReturn(3);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "SET_MAX_CONNECTIONS");
        cmd.put("requestId", "req-5b");
        cmd.put("maxSlots", 2);

        dispatcher.dispatch(cmd);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(stateReporter).reportCommandAck(eq("req-5b"), eq(3003), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) captor.getValue();
        assertEquals(3, result.get("required"));
        assertEquals(2, result.get("max"));
    }

    @Test
    public void testSetMaxConnectionsInvalid() {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "SET_MAX_CONNECTIONS");
        cmd.put("requestId", "req-6");
        cmd.put("maxSlots", 10);

        dispatcher.dispatch(cmd);

        verify(connectionScheduler, never()).setMaxSlots(10);
        verify(stateReporter).reportCommandAck("req-6", 3003, "maxSlots must be in [2,5]");
    }

    @Test
    public void testUnknownCommandReturns2002() {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "NOT_A_COMMAND");
        cmd.put("requestId", "req-7");

        dispatcher.dispatch(cmd);

        verify(stateReporter).reportCommandAck(eq("req-7"), eq(2002), any());
    }

    @Test
    public void testRemoveDeviceDestroysController() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", DeviceState.READY);
        when(deviceRegistry.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(controller);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "REMOVE_DEVICE");
        cmd.put("requestId", "req-8");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");

        dispatcher.dispatch(cmd);

        verify(connectionScheduler).cancelRequest("AA:BB:CC:DD:EE:FF");
        verify(connectionScheduler).releaseSlot("AA:BB:CC:DD:EE:FF");
        verify(controller).terminate();
        verify(bleCentralManager).destroyController("AA:BB:CC:DD:EE:FF");
        verify(stateReporter, times(1)).reportCommandAck("req-8", 0, null);
    }

    @Test
    public void testDisconnectDeviceReleasesSlot() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", DeviceState.READY);
        when(deviceRegistry.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(controller);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "DISCONNECT_DEVICE");
        cmd.put("requestId", "req-9");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");

        dispatcher.dispatch(cmd);

        // 修复 M1 槽位泄漏：断开必须经调度器归还槽位并移出活动池。
        verify(connectionScheduler).cancelRequest("AA:BB:CC:DD:EE:FF");
        verify(connectionScheduler).releaseSlot("AA:BB:CC:DD:EE:FF");
        verify(controller, never()).onSlotReleased();
        verify(stateReporter).reportCommandAck("req-9", 0, null);
    }

    @Test
    public void testReadCharToOfflineDeviceRequestsSlot() {
        // §7.4：设备未连接时命令入队并申请连接槽（HIGH）。
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", DeviceState.REGISTERED);
        when(deviceRegistry.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(controller);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "READ_CHAR");
        cmd.put("requestId", "req-10");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");
        cmd.put("char", "2A19");

        dispatcher.dispatch(cmd);

        verify(controller).enqueueCommand(any());
        verify(connectionScheduler).requestSlot(any());
        verify(stateReporter).reportCommandAck("req-10", 0, "queued");
    }

    @Test
    public void testReadCharToReadyDeviceDoesNotRequestSlot() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", DeviceState.READY);
        when(deviceRegistry.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(controller);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "READ_CHAR");
        cmd.put("requestId", "req-11");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");
        cmd.put("char", "2A19");

        dispatcher.dispatch(cmd);

        verify(controller).enqueueCommand(any());
        verify(connectionScheduler, never()).requestSlot(any());
    }

    @Test
    public void testSetPollRulesAccepted() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", DeviceState.READY);
        when(deviceRegistry.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(controller);
        when(pollResultChain.setPollRules(eq("AA:BB:CC:DD:EE:FF"), any())).thenReturn(true);

        Map<String, Object> rule = new HashMap<>();
        rule.put("ruleId", "r1");
        rule.put("priority", 10);
        List<Map<String, Object>> conditions = new ArrayList<>();
        Map<String, Object> cond = new HashMap<>();
        cond.put("field", "temperature");
        cond.put("op", "GT");
        cond.put("value", 40);
        conditions.add(cond);
        rule.put("conditions", conditions);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "SET_POLL_RULES");
        cmd.put("requestId", "req-12");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");
        cmd.put("rules", new ArrayList<>(java.util.Collections.singletonList(rule)));

        dispatcher.dispatch(cmd);

        verify(pollResultChain).setPollRules(eq("AA:BB:CC:DD:EE:FF"), any());
        verify(controller).setPollRules(any());
        verify(stateReporter).reportCommandAck("req-12", 0, null);
    }

    @Test
    public void testSetPollRulesRejectedByChainReturns2001() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", DeviceState.READY);
        when(deviceRegistry.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(controller);
        when(pollResultChain.setPollRules(any(), any())).thenReturn(false);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "SET_POLL_RULES");
        cmd.put("requestId", "req-13");
        cmd.put("deviceMac", "AA:BB:CC:DD:EE:FF");
        cmd.put("rules", new ArrayList<>());

        dispatcher.dispatch(cmd);

        verify(stateReporter).reportCommandAck(eq("req-13"), eq(2001), any());
        verify(controller, never()).setPollRules(any());
    }

    private DeviceController mockController(String mac, DeviceState state) {
        DeviceController controller = mock(DeviceController.class);
        ManagedDeviceInfo info = new ManagedDeviceInfo("dut-001", mac);
        info.setState(state);
        info.setPollingConfig(PollingConfig.defaults());
        when(controller.snapshot()).thenReturn(info);
        when(controller.getState()).thenReturn(state);
        when(controller.isReady()).thenReturn(state == DeviceState.READY);
        return controller;
    }
}
