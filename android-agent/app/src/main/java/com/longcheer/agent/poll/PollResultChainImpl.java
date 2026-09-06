package com.longcheer.agent.poll;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.config.ConditionConfig;
import com.longcheer.agent.config.DeviceConfig;
import com.longcheer.agent.config.FieldMappingConfig;
import com.longcheer.agent.config.PollRuleConfig;
import com.longcheer.agent.config.RuleActionConfig;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.FieldMapping;
import com.longcheer.agent.model.PollRule;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 轮询结果处理链实现（SDD §7.3.2 / §7.3.3）。
 *
 * <p>持有每台设备的 FieldMapping 与 PollRule（配置随 REGISTER_ACK / SET_POLL_RULES
 * 下发），轮询与通知共用同一条 decode → evaluate → execute 链。</p>
 */
public class PollResultChainImpl implements PollResultChain {

    private static final String TAG = "PollResultChain";

    private final ActionExecutor actionExecutor;
    private final StateReporter stateReporter;
    private final DeviceRegistry deviceRegistry;
    private final AgentConfig config;
    private final LongSupplier clock;

    private final Map<String, List<FieldMapping>> fieldMappings = new ConcurrentHashMap<>();
    private final Map<String, List<PollRule>> deviceRules = new ConcurrentHashMap<>();
    private final Map<String, String> deviceTypes = new ConcurrentHashMap<>();
    private final Map<String, PollResultHandler> handlers = new ConcurrentHashMap<>();
    /** 设备 profile：char → service 映射（§16.4，供 ServiceResolver 解析）。 */
    private final Map<String, Map<UUID, UUID>> deviceProfiles = new ConcurrentHashMap<>();
    /** 通知节流：每设备最近一次通知上报时间（§7.3.3）。 */
    private final Map<String, Long> lastNotifyReportAt = new ConcurrentHashMap<>();

    public PollResultChainImpl(ActionExecutor actionExecutor,
                               StateReporter stateReporter,
                               DeviceRegistry deviceRegistry,
                               AgentConfig config,
                               LongSupplier clock) {
        this.actionExecutor = actionExecutor;
        this.stateReporter = stateReporter;
        this.deviceRegistry = deviceRegistry;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public Map<String, Object> decode(String mac, Map<UUID, byte[]> raw) {
        return Decoder.decode(fieldMappings.get(mac), raw);
    }

    @Override
    public List<PollRule.RuleAction> evaluate(String mac, Map<String, Object> fields) {
        return RuleEngine.evaluate(deviceRules.get(mac), fields);
    }

    @Override
    public void execute(String mac, List<PollRule.RuleAction> actions) {
        actionExecutor.execute(mac, actions);
    }

    @Override
    public void registerHandler(String deviceType, PollResultHandler handler) {
        handlers.put(deviceType, handler);
    }

    /**
     * 装载设备配置（不含 profile 的便捷重载）。
     */
    public boolean updateDeviceConfig(String mac, String deviceType,
                                      List<FieldMapping> fields, List<PollRule> rules) {
        return updateDeviceConfig(mac, deviceType, fields, rules, null);
    }

    /**
     * 装载设备配置（含 profile char→service 映射）。
     */
    public boolean updateDeviceConfig(String mac, String deviceType,
                                      List<FieldMapping> fields, List<PollRule> rules,
                                      Map<UUID, UUID> profile) {
        if (mac == null) {
            return false;
        }
        List<FieldMapping> safeFields = fields == null ? new ArrayList<>() : fields;
        if (!validateRules(mac, safeFields, rules)) {
            return false;
        }
        fieldMappings.put(mac, new ArrayList<>(safeFields));
        deviceTypes.put(mac, deviceType == null ? "" : deviceType);
        deviceRules.put(mac, rules == null ? new ArrayList<>() : new ArrayList<>(rules));
        if (profile != null) {
            deviceProfiles.put(mac, new HashMap<>(profile));
        }
        return true;
    }

    /**
     * 解析特征的所属服务（§16.4 profile）：step 未显式给 service 时使用；
     * 无映射返回 null（执行侧按配置错误回 1003 特征不存在）。
     */
    public UUID resolveService(String mac, UUID charUuid) {
        Map<UUID, UUID> profile = deviceProfiles.get(mac);
        return profile == null ? null : profile.get(charUuid);
    }

    @Override
    public boolean setPollRules(String mac, List<PollRule> rules) {
        List<FieldMapping> fields = fieldMappings.get(mac);
        if (!validateRules(mac, fields == null ? new ArrayList<>() : fields, rules)) {
            return false;
        }
        deviceRules.put(mac, rules == null ? new ArrayList<>() : new ArrayList<>(rules));
        return true;
    }

    /**
     * 加载时校验（§7.3.2 / §16.4）：规则 conditions[].field 必须存在字段映射，
     * 缺失则拒绝装载并上报配置错误。
     */
    private boolean validateRules(String mac, List<FieldMapping> fields, List<PollRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        Map<String, Boolean> known = new HashMap<>();
        for (FieldMapping f : fields) {
            known.put(f.getField(), Boolean.TRUE);
        }
        for (PollRule rule : rules) {
            if (rule.conditions == null) {
                continue;
            }
            for (PollRule.Condition c : rule.conditions) {
                if (c.field != null && !known.containsKey(c.field)) {
                    AgentLog.w(TAG, "reject rules for " + mac + ": rule " + rule.ruleId
                            + " references unmapped field " + c.field);
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("deviceMac", mac);
                    payload.put("message", "rule " + rule.ruleId
                            + " references unmapped field: " + c.field);
                    // 配置错误属协议参数错误段（§12.9 2xxx）。
                    stateReporter.report("ERROR", withErrorCode(payload, 2001));
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public Map<String, Object> process(String mac, Map<UUID, byte[]> raw) {
        Map<String, Object> fields = decode(mac, raw);
        execute(mac, evaluate(mac, fields));
        PollResultHandler handler = handlers.get(deviceTypes.get(mac));
        if (handler != null) {
            try {
                handler.handle(mac, fields);
            } catch (RuntimeException e) {
                AgentLog.w(TAG, "handler failed for " + mac + ": " + e.getMessage());
            }
        }
        return fields;
    }

    @Override
    public void onNotification(String mac, UUID charUuid, byte[] value) {
        long now = clock.getAsLong();
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            return;
        }
        // §7.3.3：持槽期间收到通知 → 通知活跃期内不被 selectVictim 抢占，过期自动回落。
        controller.setNotifyBoostUntil(now + config.getTimeSliceMs());

        Map<UUID, byte[]> raw = new HashMap<>();
        raw.put(charUuid, value);
        Map<String, Object> fields = process(mac, raw);

        // 高频通知节流：最小上报间隔内的通知照常求值/执行动作，但不重复上报（防 TCP 拥塞）。
        Long last = lastNotifyReportAt.get(mac);
        if (last != null && now - last < config.getNotifyMinReportIntervalMs()) {
            AgentLog.d(TAG, "notify throttled: " + mac);
            return;
        }
        lastNotifyReportAt.put(mac, now);
        stateReporter.reportPollResult(mac, fields, controller.snapshot().isPollDataStale());
    }

    private static Map<String, Object> withErrorCode(Map<String, Object> payload, int errorCode) {
        payload.put("errorCode", errorCode);
        return payload;
    }

    // ---- config 包 → model 包转换（§16.4 devices[] → 运行时模型） ----

    public static List<FieldMapping> toFieldMappings(DeviceConfig device) {
        List<FieldMapping> out = new ArrayList<>();
        for (Map.Entry<String, FieldMappingConfig> e : device.getFields().entrySet()) {
            FieldMappingConfig c = e.getValue();
            UUID charUuid = parseUuid(c.getCharUuid());
            if (charUuid == null) {
                AgentLog.w(TAG, "skip field " + e.getKey() + ": invalid char uuid " + c.getCharUuid());
                continue;
            }
            FieldMapping.Format format;
            try {
                format = FieldMapping.Format.valueOf(c.getFormat().toUpperCase());
            } catch (IllegalArgumentException ex) {
                format = FieldMapping.Format.HEX;
            }
            ByteOrder order = "BE".equalsIgnoreCase(c.getByteOrder())
                    ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
            out.add(new FieldMapping(e.getKey(), charUuid, format, order,
                    c.getScale(), c.getByteOffset()));
        }
        return out;
    }

    public static List<PollRule> toPollRules(DeviceConfig device) {
        List<PollRule> out = new ArrayList<>();
        for (PollRuleConfig rc : device.getRules()) {
            out.add(toPollRule(rc));
        }
        return out;
    }

    /** 设备 profile（char→service，短/完整 UUID 均可）→ 运行时映射。 */
    public static Map<UUID, UUID> toProfile(DeviceConfig device) {
        Map<UUID, UUID> out = new HashMap<>();
        for (Map.Entry<String, String> e : device.getProfile().entrySet()) {
            UUID charUuid = parseUuid(e.getKey());
            UUID serviceUuid = parseUuid(e.getValue());
            if (charUuid != null && serviceUuid != null) {
                out.put(charUuid, serviceUuid);
            } else {
                AgentLog.w(TAG, "skip invalid profile entry: " + e.getKey() + " -> " + e.getValue());
            }
        }
        return out;
    }

    /** SET_POLL_RULES 报文（rules 数组元素为 Map）→ 运行时规则；非法元素跳过。 */
    @SuppressWarnings("unchecked")
    public static List<PollRule> toPollRulesFromMaps(List<Object> ruleMaps) {
        List<PollRule> out = new ArrayList<>();
        if (ruleMaps == null) {
            return out;
        }
        for (Object item : ruleMaps) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) item;
            List<PollRule.Condition> conditions = new ArrayList<>();
            Object conds = m.get("conditions");
            if (conds instanceof List) {
                for (Object c : (List<Object>) conds) {
                    if (!(c instanceof Map)) continue;
                    Map<String, Object> cm = (Map<String, Object>) c;
                    PollRule.Condition condition = toCondition(
                            stringValue(cm.get("field")), stringValue(cm.get("op")), cm.get("value"));
                    if (condition != null) {
                        conditions.add(condition);
                    }
                }
            }
            List<PollRule.RuleAction> actions = new ArrayList<>();
            Object acts = m.get("actions");
            if (acts instanceof List) {
                for (Object a : (List<Object>) acts) {
                    if (!(a instanceof Map)) continue;
                    Map<String, Object> am = (Map<String, Object>) a;
                    PollRule.RuleAction action = toRuleAction(
                            stringValue(am.get("type")),
                            am.get("params") instanceof Map
                                    ? (Map<String, Object>) am.get("params") : null);
                    if (action != null) {
                        actions.add(action);
                    }
                }
            }
            Object priority = m.get("priority");
            out.add(new PollRule(stringValue(m.get("ruleId")), conditions, actions,
                    priority instanceof Number ? ((Number) priority).intValue() : 0,
                    Boolean.TRUE.equals(m.get("stopOnMatch"))));
        }
        return out;
    }

    private static PollRule toPollRule(PollRuleConfig rc) {
        List<PollRule.Condition> conditions = new ArrayList<>();
        for (ConditionConfig cc : rc.getConditions()) {
            PollRule.Condition c = toCondition(cc.getField(), cc.getOp(),
                    cc.isBetween() ? new double[]{cc.getValue(), cc.getValueMax()} : cc.getValue());
            if (c != null) {
                conditions.add(c);
            }
        }
        List<PollRule.RuleAction> actions = new ArrayList<>();
        for (RuleActionConfig ac : rc.getActions()) {
            PollRule.RuleAction a = toRuleAction(ac.getType(), ac.getParams());
            if (a != null) {
                actions.add(a);
            }
        }
        return new PollRule(rc.getRuleId(), conditions, actions, rc.getPriority(), rc.isStopOnMatch());
    }

    private static PollRule.Condition toCondition(String field, String op, Object value) {
        if (field == null || op == null) {
            return null;
        }
        PollRule.Condition.Operator operator;
        try {
            operator = PollRule.Condition.Operator.valueOf(op.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        double val = 0;
        double valMax = Double.NaN;
        if (value instanceof double[]) {
            double[] range = (double[]) value;
            val = range[0];
            valMax = range[1];
        } else if (value instanceof Number) {
            val = ((Number) value).doubleValue();
        } else if (value instanceof List && ((List<?>) value).size() >= 2
                && ((List<?>) value).get(0) instanceof Number
                && ((List<?>) value).get(1) instanceof Number) {
            // BETWEEN：value 为 [min, max]（§16.4）
            val = ((Number) ((List<?>) value).get(0)).doubleValue();
            valMax = ((Number) ((List<?>) value).get(1)).doubleValue();
            operator = PollRule.Condition.Operator.BETWEEN;
        } else {
            return null;
        }
        return new PollRule.Condition(field, operator, val, valMax);
    }

    private static PollRule.RuleAction toRuleAction(String type, Map<String, Object> params) {
        if (type == null) {
            return null;
        }
        try {
            return new PollRule.RuleAction(
                    PollRule.RuleAction.RuleActionType.valueOf(type.toUpperCase()), params);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            String trimmed = s.trim();
            if (trimmed.length() <= 8) {
                return UUID.fromString(String.format("0000%s-0000-1000-8000-00805f9b34fb", trimmed));
            }
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
