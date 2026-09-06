package com.longcheer.agent.schedule;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.model.PollingTask;
import com.longcheer.agent.poll.PollResultChain;
import com.longcheer.agent.registry.DeviceRegistryImpl;
import com.longcheer.agent.report.StateReporter;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PollingSchedulerImpl 单测（SDD §6.1 / §6.3 / §6.4 / §7.3 / §12.9）。
 */
public class PollingSchedulerImplTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final UUID CHAR_A = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final long T0 = 100_000L;

    private DeviceRegistryImpl registry;
    private ConnectionScheduler connectionScheduler;
    private PollResultChain chain;
    private StateReporter reporter;
    private AgentConfig config;
    private long[] now;
    private PollingSchedulerImpl scheduler;

    @Before
    public void setUp() {
        registry = new DeviceRegistryImpl();
        connectionScheduler = mock(ConnectionScheduler.class);
        chain = mock(PollResultChain.class);
        reporter = mock(StateReporter.class);
        config = mock(AgentConfig.class);
        when(config.getTickIntervalMs()).thenReturn(100L);
        when(config.getMaxSlots()).thenReturn(3);
        when(config.getSetupBudgetMs()).thenReturn(4000L);
        when(config.getTimeSliceMs()).thenReturn(2000L);
        when(config.getStaleThresholdMs()).thenReturn(120000L);
        now = new long[]{T0};
        scheduler = new PollingSchedulerImpl(registry, connectionScheduler, config, chain, reporter,
                () -> now[0]);
    }

    private DeviceController addDevice(String mac, DeviceState state, PollingConfig cfg) {
        DeviceController controller = mock(DeviceController.class);
        ManagedDeviceInfo info = new ManagedDeviceInfo("id-" + mac, mac);
        info.setState(state);
        info.setPollingConfig(cfg);
        when(controller.snapshot()).thenReturn(info);
        when(controller.getState()).thenReturn(state);
        when(controller.isReady()).thenReturn(state == DeviceState.READY);
        // 显式写接口（§8.1）回写到同一份 info，保持与真实实现一致的可观测行为。
        org.mockito.Mockito.doAnswer(inv -> {
            info.setLastPollTime(inv.getArgument(0));
            info.setNextPollTime(inv.getArgument(1));
            return null;
        }).when(controller).updatePollTimes(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doAnswer(inv -> {
            info.setPollDataStale(inv.getArgument(0));
            return null;
        }).when(controller).setPollDataStale(anyBoolean());
        org.mockito.Mockito.doAnswer(inv -> {
            info.setLastPollResult(inv.getArgument(0));
            return null;
        }).when(controller).updatePollResult(any());
        registry.register(controller);
        return controller;
    }

    private static PollingConfig pollCfg(long intervalMs, boolean reportOnlyChanged) {
        return new PollingConfig(intervalMs, Collections.singletonList(CHAR_A),
                Collections.emptyList(), reportOnlyChanged);
    }

    // ---------- M3-1 虚拟轮询计划 ----------

    @Test
    public void offlineDeviceDueRequestsSlot() {
        DeviceController controller = addDevice(MAC, DeviceState.REGISTERED, pollCfg(60000, true));
        scheduler.updateConfig(MAC, pollCfg(60000, true));

        scheduler.tick(); // 初始化 nextPollTime
        verify(connectionScheduler, never()).requestSlot(any());

        now[0] += 60001; // 到期
        scheduler.tick();

        ArgumentCaptor<ConnectionRequest> captor = ArgumentCaptor.forClass(ConnectionRequest.class);
        verify(connectionScheduler).requestSlot(captor.capture());
        assertEquals(MAC, captor.getValue().getDeviceMac());
        assertEquals(ConnectionRequest.Reason.POLL, captor.getValue().getReason());
        assertEquals(ConnectionRequest.PRIORITY_NORMAL, captor.getValue().getPriority());
    }

    @Test
    public void antiReentrySkipsWhilePreviousPollPending() {
        DeviceController controller = addDevice(MAC, DeviceState.REGISTERED, pollCfg(60000, true));
        scheduler.updateConfig(MAC, pollCfg(60000, true));
        scheduler.tick();
        now[0] += 60001;

        scheduler.tick(); // 第一次到期 → 申请槽位
        scheduler.tick(); // 上一轮未完成 → 只标记不重复申请（§6.1）
        scheduler.tick();

        verify(connectionScheduler, times(1)).requestSlot(any());
    }

    @Test
    public void readyDeviceDueEnqueuesReadStepsTask() {
        DeviceController controller = addDevice(MAC, DeviceState.READY, pollCfg(60000, true));
        scheduler.updateConfig(MAC, pollCfg(60000, true));
        scheduler.tick();
        now[0] += 60001;

        scheduler.tick();

        ArgumentCaptor<PollingTask> captor = ArgumentCaptor.forClass(PollingTask.class);
        verify(controller).enqueuePollTask(captor.capture());
        // readCharacteristics 简写 → 全 READ 序列（§7.3.1 退化形式）。
        assertEquals(1, captor.getValue().steps.size());
        assertEquals(com.longcheer.agent.model.GattCommand.Type.READ, captor.getValue().steps.get(0).type);
        assertEquals(CHAR_A, captor.getValue().steps.get(0).charUuid);
    }

    @Test
    public void tickIntervalClampedToHalfOfMinInterval() {
        when(config.getTickIntervalMs()).thenReturn(1000L); // 远超 200/2
        scheduler.updateConfig(MAC, pollCfg(200, true));

        assertEquals(100L, scheduler.getEffectiveTickMs()); // 钳制到 200/2（§6.4）
    }

    @Test
    public void tickIntervalWithinConstraintNotClamped() {
        when(config.getTickIntervalMs()).thenReturn(80L);
        scheduler.updateConfig(MAC, pollCfg(200, true));

        assertEquals(80L, scheduler.getEffectiveTickMs());
    }

    // ---------- M3-5 结果缓存与上报 ----------

    @Test
    public void pollCompletedRunsChainAndClearsStale() {
        DeviceController controller = addDevice(MAC, DeviceState.READY, pollCfg(60000, true));
        scheduler.updateConfig(MAC, pollCfg(60000, true));
        controller.setPollDataStale(true);
        Map<UUID, byte[]> raw = Collections.singletonMap(CHAR_A, new byte[]{85});
        Map<String, Object> decoded = Collections.singletonMap("battery", (Object) 85L);
        when(chain.process(eq(MAC), any())).thenReturn(decoded);

        scheduler.onPollCompleted(MAC, raw);

        verify(chain).process(MAC, raw);
        // stale 唯一清除时机（§6.1）：走完处理链后清 false。
        assertFalse(controller.snapshot().isPollDataStale());
        assertEquals(now[0], controller.snapshot().getLastPollTime());
        assertEquals(now[0] + 60000, controller.snapshot().getNextPollTime());
        verify(reporter).reportPollResult(MAC, decoded, false);
    }

    @Test
    public void reportOnlyChangedSuppressesUnchangedResult() {
        DeviceController controller = addDevice(MAC, DeviceState.READY, pollCfg(60000, true));
        scheduler.updateConfig(MAC, pollCfg(60000, true));
        Map<UUID, byte[]> raw = Collections.singletonMap(CHAR_A, new byte[]{85});
        when(chain.process(eq(MAC), any())).thenReturn(Collections.singletonMap("battery", (Object) 85L));

        scheduler.onPollCompleted(MAC, raw); // 首次：无缓存 → 上报
        verify(reporter, times(1)).reportPollResult(anyString(), anyMap(), anyBoolean());

        now[0] += 60000;
        scheduler.onPollCompleted(MAC, raw); // 相同数据 → 不上报（§6.3）
        verify(reporter, times(1)).reportPollResult(anyString(), anyMap(), anyBoolean());

        now[0] += 60000;
        scheduler.onPollCompleted(MAC, Collections.singletonMap(CHAR_A, new byte[]{86})); // 变化 → 上报
        verify(reporter, times(2)).reportPollResult(anyString(), anyMap(), anyBoolean());
    }

    @Test
    public void reportOnlyChangedFalseAlwaysReports() {
        addDevice(MAC, DeviceState.READY, pollCfg(60000, false));
        scheduler.updateConfig(MAC, pollCfg(60000, false));
        Map<UUID, byte[]> raw = Collections.singletonMap(CHAR_A, new byte[]{85});
        when(chain.process(eq(MAC), any())).thenReturn(Collections.emptyMap());

        scheduler.onPollCompleted(MAC, raw);
        scheduler.onPollCompleted(MAC, raw);

        verify(reporter, times(2)).reportPollResult(anyString(), anyMap(), anyBoolean());
    }

    @Test
    public void staleMarkedOnceWithLastPollTimeAndReason() {
        DeviceController controller = addDevice(MAC, DeviceState.REGISTERED, pollCfg(60000, true));
        scheduler.updateConfig(MAC, pollCfg(60000, true));
        controller.snapshot().setLastPollTime(T0 - 10_000);
        controller.snapshot().setNextPollTime(T0 - 5_000); // 已到期
        now[0] = T0 + 120_001; // 超 staleThresholdMs(120s)

        scheduler.tick();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("POLL_DATA_STALE"), captor.capture());
        assertEquals(MAC, captor.getValue().get("deviceMac"));
        assertEquals(T0 - 10_000, captor.getValue().get("lastPollTime"));
        assertEquals("NO_SLOT", captor.getValue().get("reason"));
        assertFalse(captor.getValue().containsKey("errorCode")); // §12.9：不带 errorCode
        assertTrue(controller.snapshot().isPollDataStale());

        scheduler.tick(); // 已 stale → 不重复上报
        verify(reporter, times(1)).report(eq("POLL_DATA_STALE"), anyMap());
    }

    @Test
    public void pollFailedClearsAntiReentryFlag() {
        DeviceController controller = addDevice(MAC, DeviceState.REGISTERED, pollCfg(60000, true));
        scheduler.updateConfig(MAC, pollCfg(60000, true));
        scheduler.tick();
        now[0] += 60001;
        scheduler.tick(); // 到期 → 申请
        scheduler.onPollFailed(MAC, 1001, 133); // 任务失败 → 清标记

        scheduler.tick(); // 标记已清 → 可再次申请
        verify(connectionScheduler, times(2)).requestSlot(any());
    }

    // ---------- 命令结果 ACK 闭环（§7.4 / §12.9） ----------

    @Test
    public void commandResultAcksWithValueAndRawStatus() {
        GattCommand read = GattCommand.simple(MAC, "req-r1", GattCommand.Type.READ,
                null, CHAR_A, null, GattCommand.Priority.HIGH);
        scheduler.onCommandResult(read, com.longcheer.agent.model.GattResult.ok(new byte[]{0x55}));
        verify(reporter).reportCommandAck(eq("req-r1"), eq(0),
                org.mockito.ArgumentMatchers.argThat(r ->
                        r instanceof Map && "VQ==".equals(((Map<?, ?>) r).get("value"))));

        GattCommand write = GattCommand.simple(MAC, "req-w1", GattCommand.Type.WRITE,
                null, CHAR_A, new byte[]{1}, GattCommand.Priority.HIGH);
        scheduler.onCommandResult(write, com.longcheer.agent.model.GattResult.fail(133));
        verify(reporter).reportCommandAck(eq("req-w1"), eq(1001), eq(133),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    public void commandResultWithoutRequestIdIsSkipped() {
        // 规则触发的本地自治动作无 requestId，不产生 CMD_ACK（§10.3 对账口径）。
        GattCommand cmd = GattCommand.simple(MAC, null, GattCommand.Type.WRITE,
                null, CHAR_A, new byte[]{1}, GattCommand.Priority.HIGH);
        scheduler.onCommandResult(cmd, com.longcheer.agent.model.GattResult.ok(null));
        verify(reporter, never()).reportCommandAck(any(), anyInt(), any());
    }
}
