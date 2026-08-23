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

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
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
    private CommandDispatcher dispatcher;

    @Before
    public void setUp() {
        bleCentralManager = mock(BleCentralManager.class);
        deviceRegistry = mock(DeviceRegistry.class);
        connectionScheduler = mock(ConnectionScheduler.class);
        pollingScheduler = mock(PollingScheduler.class);
        stateReporter = mock(StateReporter.class);
        config = mock(AgentConfig.class);
        when(config.getMaxSlots()).thenReturn(3);

        dispatcher = new CommandDispatcherImpl(bleCentralManager, deviceRegistry,
                connectionScheduler, pollingScheduler, stateReporter, config);
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
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("type", "SET_MAX_CONNECTIONS");
        cmd.put("requestId", "req-5");
        cmd.put("maxSlots", 4);

        dispatcher.dispatch(cmd);

        verify(connectionScheduler).setMaxSlots(4);
        verify(stateReporter).reportCommandAck("req-5", 0, null);
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

        verify(stateReporter).reportCommandAck("req-7", 2002, any());
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
        verify(controller).terminate();
        verify(bleCentralManager).destroyController("AA:BB:CC:DD:EE:FF");
        verify(stateReporter, times(1)).reportCommandAck("req-8", 0, null);
    }

    private DeviceController mockController(String mac, DeviceState state) {
        DeviceController controller = mock(DeviceController.class);
        ManagedDeviceInfo info = new ManagedDeviceInfo("dut-001", mac);
        info.setState(state);
        info.setPollingConfig(PollingConfig.defaults());
        when(controller.snapshot()).thenReturn(info);
        when(controller.getState()).thenReturn(state);
        return controller;
    }
}
