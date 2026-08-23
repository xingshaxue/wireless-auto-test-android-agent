package com.longcheer.agent.config;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 规则条件配置（config 包），对应 SDD §16.4 {@code devices[].rules[].conditions[]}。
 *
 * <p>运行时等价结构见 {@link com.longcheer.agent.model.Condition}。</p>
 */
public final class ConditionConfig {

    private final String field;
    private final String op;       // GT / GE / LT / LE / EQ / NE / BETWEEN
    private final double value;
    private final double valueMax; // 仅 BETWEEN 使用：闭区间 [value, valueMax]

    public ConditionConfig(String field, String op, double value, double valueMax) {
        this.field = field;
        this.op = op;
        this.value = value;
        this.valueMax = valueMax;
    }

    public static ConditionConfig fromJson(JSONObject json) {
        if (json == null) {
            json = new JSONObject();
        }
        String field = json.optString("field", null);
        String op = json.optString("op", "GT");
        double value;
        double valueMax = Double.NaN;

        Object valueRaw = json.opt("value");
        if (valueRaw instanceof JSONArray) {
            // BETWEEN 时 value 为 [min, max]
            JSONArray arr = (JSONArray) valueRaw;
            value = arr.optDouble(0, 0.0);
            valueMax = arr.optDouble(1, 0.0);
            op = "BETWEEN";
        } else if (valueRaw instanceof Number) {
            value = ((Number) valueRaw).doubleValue();
        } else {
            value = 0.0;
        }

        return new ConditionConfig(field, op, value, valueMax);
    }

    public String getField() {
        return field;
    }

    public String getOp() {
        return op;
    }

    public double getValue() {
        return value;
    }

    public double getValueMax() {
        return valueMax;
    }

    public boolean isBetween() {
        return "BETWEEN".equalsIgnoreCase(op);
    }
}
