package com.longcheer.agent.poll;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.config.DeviceConfig;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.FieldMapping;
import com.longcheer.agent.model.PollRule;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PollResultChainImpl 单测（SDD §7.3.2 / §7.3.3 / §16.4）。
 */
public class PollResultChainImplTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final UUID CHAR_BATTERY = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_TEMP = UUID.fromString("00002a21-0000-1000-8000-00805f9b34fb");

    private ActionExecutor actionExecutor;
    private StateReporter reporter;
    private DeviceRegistry registry;
    private AgentConfig config;
    private long[] now;
    private PollResultChainImpl chain;
    private DeviceController controller;

    private static List<FieldMapping> defaultFields() {
        return java.util.Arrays.asList(
                new FieldMapping("battery", CHAR_BATTERY, FieldMapping.Format.UINT8,
                        ByteOrder.LITTLE_ENDIAN, 1.0, 0),
                new FieldMapping("temperature", CHAR_TEMP, FieldMapping.Format.SINT16,
                        ByteOrder.LITTLE_ENDIAN, 0.1, 0));
    }

    @Before
    public void setUp() {
        actionExecutor = mock(ActionExecutor.class);
        reporter = mock(StateReporter.class);
        registry = mock(DeviceRegistry.class);
        config = mock(AgentConfig.class);
        when(config.getTimeSliceMs()).thenReturn(2000L);
        when(config.getNotifyMinReportIntervalMs()).thenReturn(200L);
        now = new long[]{10_000L};
        chain = new PollResultChainImpl(actionExecutor, reporter, registry, config, () -> now[0]);

        controller = mock(DeviceController.class);
        when(registry.findByMac(MAC)).thenReturn(controller);
    }

    @Test
    public void testRuleWithUnmappedFieldRejected() {
        // §7.3.2 / §16.4：规则引用无映射字段 → 加载时拒绝并上报配置错误。
        PollRule bad = new PollRule("r1",
                Collections.singletonList(new PollRule.Condition(
                        "humidity", PollRule.Condition.Operator.GT, 80, 0)),
                Collections.emptyList(), 0, false);

        boolean ok = chain.updateDeviceConfig(MAC, "watch", defaultFields(),
                Collections.singletonList(bad));

        assertFalse(ok);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("ERROR"), captor.capture());
        assertEquals(2001, captor.getValue().get("errorCode"));
    }

    @Test
    public void testSetPollRulesValidatesAgainstExistingMappings() {
        assertTrue(chain.updateDeviceConfig(MAC, "watch", defaultFields(), Collections.emptyList()));
        PollRule bad = new PollRule("r1",
                Collections.singletonList(new PollRule.Condition(
                        "unknown", PollRule.Condition.Operator.EQ, 1, 0)),
                Collections.emptyList(), 0, false);
        assertFalse(chain.setPollRules(MAC, Collections.singletonList(bad)));

        PollRule good = new PollRule("r2",
                Collections.singletonList(new PollRule.Condition(
                        "battery", PollRule.Condition.Operator.GE, 20, 0)),
                Collections.emptyList(), 0, false);
        assertTrue(chain.setPollRules(MAC, Collections.singletonList(good)));
    }

    @Test
    public void testProcessDecodesEvaluatesExecutes() {
        chain.updateDeviceConfig(MAC, "watch", defaultFields(), Collections.emptyList());
        Map<UUID, byte[]> raw = new HashMap<>();
        raw.put(CHAR_BATTERY, new byte[]{85});
        raw.put(CHAR_TEMP, new byte[]{(byte) 0xA4, 0x01}); // 420 × 0.1 = 42.0

        Map<String, Object> fields = chain.process(MAC, raw);

        assertEquals(85L, fields.get("battery"));
        assertEquals(42.0d, ((Number) fields.get("temperature")).doubleValue(), 0.0001);
        verify(actionExecutor).execute(eq(MAC), any());
    }

    @Test
    public void testHandlerInvokedByDeviceType() {
        chain.updateDeviceConfig(MAC, "watch", defaultFields(), Collections.emptyList());
        boolean[] called = {false};
        chain.registerHandler("watch", (mac, fields) -> called[0] = true);

        chain.process(MAC, Collections.singletonMap(CHAR_BATTERY, new byte[]{1}));

        assertTrue(called[0]);
    }

    @Test
    public void testNotificationBoostsAndThrottles() {
        chain.updateDeviceConfig(MAC, "watch", defaultFields(), Collections.emptyList());
        when(controller.snapshot()).thenReturn(new com.longcheer.agent.model.ManagedDeviceInfo("d", MAC));

        chain.onNotification(MAC, CHAR_BATTERY, new byte[]{90});
        // §7.3.3：通知活跃期 = now + timeSliceMs。
        verify(controller).setNotifyBoostUntil(10_000L + 2000L);
        verify(reporter, times(1)).reportPollResult(eq(MAC), any(), eq(false));

        // 节流窗口内第二次通知：照常走处理链但不重复上报。
        now[0] += 100;
        chain.onNotification(MAC, CHAR_BATTERY, new byte[]{91});
        verify(controller, times(2)).setNotifyBoostUntil(any(Long.class));
        verify(reporter, times(1)).reportPollResult(eq(MAC), any(), eq(false));

        // 窗口过后恢复上报。
        now[0] += 200;
        chain.onNotification(MAC, CHAR_BATTERY, new byte[]{92});
        verify(reporter, times(2)).reportPollResult(eq(MAC), any(), eq(false));
    }

    @Test
    public void testNotificationForUnknownDeviceIgnored() {
        when(registry.findByMac("00:00:00:00:00:00")).thenReturn(null);
        chain.onNotification("00:00:00:00:00:00", CHAR_BATTERY, new byte[]{1});
        verify(reporter, never()).reportPollResult(any(), any(), any(Boolean.class));
    }

    @Test
    public void testDeviceConfigConversion() throws Exception {
        // §16.4 示例结构：profile 短 UUID、fields、BETWEEN 数组值规则。
        JSONObject json = new JSONObject("{"
                + "\"deviceId\":\"dut-001\",\"mac\":\"AA:BB:CC:DD:EE:FF\",\"type\":\"watch\","
                + "\"profile\":{\"2A19\":\"180F\"},"
                + "\"fields\":{"
                + "  \"battery\":{\"char\":\"2A19\",\"format\":\"uint8\"},"
                + "  \"temperature\":{\"char\":\"2A21\",\"format\":\"sint16\",\"byteOrder\":\"LE\",\"scale\":0.1}"
                + "},"
                + "\"rules\":[{"
                + "  \"ruleId\":\"r1\",\"priority\":10,\"stopOnMatch\":false,"
                + "  \"conditions\":[{\"field\":\"temperature\",\"op\":\"BETWEEN\",\"value\":[20,40]}],"
                + "  \"actions\":[{\"type\":\"REPORT_EVENT\",\"params\":{\"event\":\"TEMP_WARN\"}}]"
                + "}]}"
        );
        DeviceConfig device = DeviceConfig.fromJson(json);

        List<FieldMapping> fields = PollResultChainImpl.toFieldMappings(device);
        assertEquals(2, fields.size());
        FieldMapping battery = null;
        for (FieldMapping f : fields) {
            if ("battery".equals(f.getField())) {
                battery = f;
            }
        }
        assertTrue(battery != null);
        assertEquals(CHAR_BATTERY, battery.getCharUuid());
        assertEquals(FieldMapping.Format.UINT8, battery.getFormat());

        List<PollRule> rules = PollResultChainImpl.toPollRules(device);
        assertEquals(1, rules.size());
        assertEquals(PollRule.Condition.Operator.BETWEEN, rules.get(0).conditions.get(0).op);
        assertEquals(20.0, rules.get(0).conditions.get(0).value, 0.0001);
        assertEquals(40.0, rules.get(0).conditions.get(0).valueMax, 0.0001);
        assertEquals(PollRule.RuleAction.RuleActionType.REPORT_EVENT, rules.get(0).actions.get(0).type);

        Map<UUID, UUID> profile = PollResultChainImpl.toProfile(device);
        assertEquals(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), profile.get(CHAR_BATTERY));
    }
}
