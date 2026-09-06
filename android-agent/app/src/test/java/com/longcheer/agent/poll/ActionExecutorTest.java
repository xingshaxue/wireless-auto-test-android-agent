package com.longcheer.agent.poll;

import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollRule;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingScheduler;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ActionExecutor 单测（SDD §7.3.2）：六类动作全部可执行。
 */
public class ActionExecutorTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";

    private DeviceRegistry registry;
    private ConnectionScheduler connectionScheduler;
    private PollingScheduler pollingScheduler;
    private StateReporter reporter;
    private long[] now;
    private ActionExecutor executor;
    private DeviceController controller;
    private ManagedDeviceInfo info;

    @Before
    public void setUp() {
        registry = mock(DeviceRegistry.class);
        connectionScheduler = mock(ConnectionScheduler.class);
        pollingScheduler = mock(PollingScheduler.class);
        reporter = mock(StateReporter.class);
        now = new long[]{1000L};
        executor = new ActionExecutor(registry, connectionScheduler, pollingScheduler, reporter,
                () -> now[0]);

        controller = mock(DeviceController.class);
        info = new ManagedDeviceInfo("dut-1", MAC);
        info.setState(DeviceState.READY);
        info.setPollingConfig(PollingConfig.defaults());
        when(controller.snapshot()).thenReturn(info);
        when(controller.isReady()).thenReturn(true);
        when(registry.findByMac(MAC)).thenReturn(controller);
    }

    private static PollRule.RuleAction action(PollRule.RuleAction.RuleActionType type,
                                              Map<String, Object> params) {
        return new PollRule.RuleAction(type, params);
    }

    @Test
    public void testReportEvent() {
        executor.execute(MAC, Collections.singletonList(
                action(PollRule.RuleAction.RuleActionType.REPORT_EVENT,
                        Collections.singletonMap("event", "TEMP_CRITICAL"))));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("TEMP_CRITICAL"), captor.capture());
        assertEquals(MAC, captor.getValue().get("deviceMac"));
    }

    @Test
    public void testExecuteGattOnReadyDevice() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "WRITE");
        params.put("service", "180F");
        params.put("char", "2A19");
        params.put("payload", "AQID"); // base64

        executor.execute(MAC, Collections.singletonList(
                action(PollRule.RuleAction.RuleActionType.EXECUTE_GATT, params)));

        ArgumentCaptor<GattCommand> captor = ArgumentCaptor.forClass(GattCommand.class);
        verify(controller).enqueueCommand(captor.capture());
        assertEquals(GattCommand.Type.WRITE, captor.getValue().getType());
        assertEquals(GattCommand.Priority.HIGH, captor.getValue().getPriority());
        assertEquals(3, captor.getValue().getPayload().length);
        verify(connectionScheduler, never()).requestSlot(any()); // READY 设备直接执行
    }

    @Test
    public void testExecuteGattOnOfflineDeviceRequestsSlot() {
        info.setState(DeviceState.REGISTERED);
        when(controller.isReady()).thenReturn(false);
        Map<String, Object> params = new HashMap<>();
        params.put("type", "WRITE");
        params.put("char", "2A19");

        executor.execute(MAC, Collections.singletonList(
                action(PollRule.RuleAction.RuleActionType.EXECUTE_GATT, params)));

        ArgumentCaptor<ConnectionRequest> captor = ArgumentCaptor.forClass(ConnectionRequest.class);
        verify(connectionScheduler).requestSlot(captor.capture());
        assertEquals(ConnectionRequest.Reason.EVENT, captor.getValue().getReason());
    }

    @Test
    public void testSetInterval() {
        executor.execute(MAC, Collections.singletonList(
                action(PollRule.RuleAction.RuleActionType.SET_INTERVAL,
                        Collections.singletonMap("intervalMs", 5000))));

        ArgumentCaptor<PollingConfig> captor = ArgumentCaptor.forClass(PollingConfig.class);
        verify(controller).setPollingConfig(captor.capture());
        assertEquals(5000L, captor.getValue().getIntervalMs());
        verify(pollingScheduler).updateConfig(eq(MAC), any());
    }

    @Test
    public void testSetIntervalBelowMinimumRejected() {
        executor.execute(MAC, Collections.singletonList(
                action(PollRule.RuleAction.RuleActionType.SET_INTERVAL,
                        Collections.singletonMap("intervalMs", 100))));

        verify(controller, never()).setPollingConfig(any());
        verify(pollingScheduler, never()).updateConfig(any(), any());
    }

    @Test
    public void testSetDeviceStateWritesStateFlagOnly() {
        executor.execute(MAC, Collections.singletonList(
                action(PollRule.RuleAction.RuleActionType.SET_DEVICE_STATE,
                        Collections.singletonMap("state", "OVERTEMP"))));

        verify(controller).setStateFlag("OVERTEMP");
        // 不得触碰状态机（§7.3.2）。
        assertEquals(DeviceState.READY, info.getState());
    }

    @Test
    public void testReleaseSlot() {
        executor.execute(MAC, Collections.singletonList(
                action(PollRule.RuleAction.RuleActionType.RELEASE_SLOT, Collections.emptyMap())));

        verify(connectionScheduler).releaseSlot(MAC);
    }

    @Test
    public void testFileTransferEmitsFileRequest() {
        executor.execute(MAC, Collections.singletonList(
                action(PollRule.RuleAction.RuleActionType.FILE_TRANSFER,
                        Collections.singletonMap("fileId", "fw-v2.bin"))));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("FILE_REQUEST"), captor.capture());
        assertEquals("fw-v2.bin", captor.getValue().get("fileId"));
        assertEquals(MAC, captor.getValue().get("deviceMac"));
        assertTrue(((String) captor.getValue().get("taskId")).startsWith("auto-"));
    }

    @Test
    public void testOneActionFailureDoesNotBlockOthers() {
        when(registry.findByMac(MAC)).thenReturn(null); // EXECUTE_GATT 找不到设备仅告警
        executor.execute(MAC, Arrays.asList(
                action(PollRule.RuleAction.RuleActionType.EXECUTE_GATT,
                        Collections.singletonMap("char", "2A19")),
                action(PollRule.RuleAction.RuleActionType.RELEASE_SLOT, Collections.emptyMap())));

        verify(connectionScheduler).releaseSlot(MAC);
    }
}
