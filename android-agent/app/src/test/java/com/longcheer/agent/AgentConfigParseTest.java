package com.longcheer.agent;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.config.ConditionConfig;
import com.longcheer.agent.config.DeviceConfig;
import com.longcheer.agent.config.FieldMappingConfig;
import com.longcheer.agent.config.PollRuleConfig;
import com.longcheer.agent.config.PollingConfig;
import com.longcheer.agent.config.RuleActionConfig;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AgentConfigParseTest {

    @Test
    public void parseFullConfig_allFieldsSet() {
        JSONObject json = new JSONObject()
                .put("configVersion", 3)
                .put("maxSlots", 3)
                .put("timeSliceMs", 2000L)
                .put("idleReleaseMs", 30000L)
                .put("commandTtlMs", 300000L)
                .put("maxPendingCommands", 64)
                .put("tickIntervalMs", 100L)
                .put("cooldownMs", 5000L)
                .put("setupBudgetMs", 4000L)
                .put("agingThresholdMs", 30000L)
                .put("heartbeatIntervalMs", 5000L)
                .put("connectTimeoutMs", 10000L)
                .put("gattTimeoutMs", 3000L)
                .put("maxReconnectAttempts", 5)
                .put("reconnectBackoffMaxMs", 60000L)
                .put("notifyMinReportIntervalMs", 200L)
                .put("maxConcurrentTransfers", 1)
                .put("diskQuotaMb", 1024)
                .put("failedTaskRetentionDays", 7)
                .put("reportBufferMax", 1000)
                .put("staleThresholdMs", 120000L);

        JSONObject device = new JSONObject()
                .put("deviceId", "dut-001")
                .put("mac", "AA:BB:CC:DD:EE:FF")
                .put("type", "watch")
                .put("priority", 5)
                .put("persistent", false)
                .put("profile", new JSONObject()
                        .put("2A19", "180F")
                        .put("2A21", "180A"));

        device.put("fields", new JSONObject()
                .put("battery", new JSONObject().put("char", "2A19").put("format", "uint8"))
                .put("temperature", new JSONObject()
                        .put("char", "2A21")
                        .put("format", "sint16")
                        .put("byteOrder", "LE")
                        .put("scale", 0.1)
                        .put("byteOffset", 0)));

        device.put("polling", new JSONObject()
                .put("intervalMs", 60000L)
                .put("readCharacteristics", new JSONArray().put("2A19").put("2A24"))
                .put("notifyCharacteristics", new JSONArray().put("2A19"))
                .put("reportOnlyChanged", true));

        JSONObject rule = new JSONObject()
                .put("ruleId", "r1")
                .put("priority", 10)
                .put("stopOnMatch", false)
                .put("conditions", new JSONArray()
                        .put(new JSONObject().put("field", "temperature").put("op", "GT").put("value", 40)))
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "REPORT_EVENT")
                                .put("params", new JSONObject().put("event", "TEMP_CRITICAL")))
                        .put(new JSONObject()
                                .put("type", "SET_INTERVAL")
                                .put("params", new JSONObject().put("intervalMs", 5000))));

        device.put("rules", new JSONArray().put(rule));
        json.put("devices", new JSONArray().put(device));

        AgentConfig config = AgentConfig.fromJson(json);

        assertEquals(3, config.getConfigVersion());
        assertEquals(3, config.getMaxSlots());
        assertEquals(2000L, config.getTimeSliceMs());
        assertEquals(30000L, config.getIdleReleaseMs());
        assertEquals(300000L, config.getCommandTtlMs());
        assertEquals(64, config.getMaxPendingCommands());
        assertEquals(100L, config.getTickIntervalMs());
        assertEquals(5000L, config.getCooldownMs());
        assertEquals(4000L, config.getSetupBudgetMs());
        assertEquals(30000L, config.getAgingThresholdMs());
        assertEquals(5000L, config.getHeartbeatIntervalMs());
        assertEquals(10000L, config.getConnectTimeoutMs());
        assertEquals(3000L, config.getGattTimeoutMs());
        assertEquals(5, config.getMaxReconnectAttempts());
        assertEquals(60000L, config.getReconnectBackoffMaxMs());
        assertEquals(200L, config.getNotifyMinReportIntervalMs());
        assertEquals(1, config.getMaxConcurrentTransfers());
        assertEquals(1024, config.getDiskQuotaMb());
        assertEquals(7, config.getFailedTaskRetentionDays());
        assertEquals(1000, config.getReportBufferMax());
        assertEquals(120000L, config.getStaleThresholdMs());

        assertEquals(1, config.getDevices().size());
        DeviceConfig dc = config.getDevices().get(0);
        assertEquals("dut-001", dc.getDeviceId());
        assertEquals("AA:BB:CC:DD:EE:FF", dc.getMac());
        assertEquals("watch", dc.getType());
        assertEquals(5, dc.getPriority());
        assertFalse(dc.isPersistent());
        assertEquals("180F", dc.getProfile().get("2A19"));

        FieldMappingConfig battery = dc.getFields().get("battery");
        assertNotNull(battery);
        assertEquals("2A19", battery.getCharUuid());
        assertEquals("uint8", battery.getFormat());

        FieldMappingConfig temp = dc.getFields().get("temperature");
        assertNotNull(temp);
        assertEquals("2A21", temp.getCharUuid());
        assertEquals("sint16", temp.getFormat());
        assertEquals("LE", temp.getByteOrder());
        assertEquals(0.1, temp.getScale(), 1e-9);
        assertEquals(0, temp.getByteOffset());

        PollingConfig pc = dc.getPolling();
        assertNotNull(pc);
        assertEquals(60000L, pc.getIntervalMs());
        assertEquals(2, pc.getReadCharacteristics().size());
        assertEquals("2A19", pc.getReadCharacteristics().get(0));
        assertEquals(1, pc.getNotifyCharacteristics().size());
        assertTrue(pc.isReportOnlyChanged());

        assertEquals(1, dc.getRules().size());
        PollRuleConfig rc = dc.getRules().get(0);
        assertEquals("r1", rc.getRuleId());
        assertEquals(10, rc.getPriority());
        assertFalse(rc.isStopOnMatch());

        ConditionConfig cc = rc.getConditions().get(0);
        assertEquals("temperature", cc.getField());
        assertEquals("GT", cc.getOp());
        assertEquals(40.0, cc.getValue(), 1e-9);

        RuleActionConfig ac = rc.getActions().get(0);
        assertEquals("REPORT_EVENT", ac.getType());
        assertEquals("TEMP_CRITICAL", ac.getParams().get("event"));

        RuleActionConfig ac2 = rc.getActions().get(1);
        assertEquals("SET_INTERVAL", ac2.getType());
        assertEquals(5000, ac2.getParams().get("intervalMs"));
    }

    @Test
    public void parseDefaults_usedForMissingFields() {
        AgentConfig config = AgentConfig.fromJson(new JSONObject());
        assertEquals(0, config.getConfigVersion());
        assertEquals(3, config.getMaxSlots());
        assertEquals(5000L, config.getHeartbeatIntervalMs());
        assertEquals(4000L, config.getSetupBudgetMs());
        assertTrue(config.getDevices().isEmpty());
    }
}
