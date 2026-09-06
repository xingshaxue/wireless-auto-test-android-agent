package com.longcheer.agent.poll;

import com.longcheer.agent.model.PollRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * RuleEngine（SDD §7.3.2 / §16.5）：声明式规则求值。
 *
 * <p>按 priority 降序求值；命中且 stopOnMatch=true 停止后续；BETWEEN 为闭区间。
 * 本地自治：求值在 Android 端完成，不走服务器回路。</p>
 */
public final class RuleEngine {

    private RuleEngine() {
    }

    /**
     * 求值规则集，返回命中规则的动作列表（按命中顺序展平）。
     */
    public static List<PollRule.RuleAction> evaluate(List<PollRule> rules, Map<String, Object> fields) {
        List<PollRule.RuleAction> matched = new ArrayList<>();
        if (rules == null || rules.isEmpty() || fields == null) {
            return matched;
        }
        List<PollRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt((PollRule r) -> r.priority).reversed());
        for (PollRule rule : sorted) {
            if (rule.conditions == null || !allMatch(rule.conditions, fields)) {
                continue;
            }
            if (rule.actions != null) {
                matched.addAll(rule.actions);
            }
            if (rule.stopOnMatch) {
                break;
            }
        }
        return matched;
    }

    private static boolean allMatch(List<PollRule.Condition> conditions, Map<String, Object> fields) {
        for (PollRule.Condition c : conditions) {
            if (!match(c, fields.get(c.field))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单条件匹配。数值比较要求字段值为 Number；EQ/NE 额外支持 Boolean/String 相等比较。
     * 字段缺失或类型不匹配视为不命中。
     */
    static boolean match(PollRule.Condition c, Object fieldValue) {
        if (c == null || c.op == null || fieldValue == null) {
            return false;
        }
        if (c.op == PollRule.Condition.Operator.EQ || c.op == PollRule.Condition.Operator.NE) {
            boolean eq = equalsValue(fieldValue, c.value);
            return c.op == PollRule.Condition.Operator.EQ ? eq : !eq;
        }
        if (!(fieldValue instanceof Number)) {
            return false;
        }
        double v = ((Number) fieldValue).doubleValue();
        switch (c.op) {
            case GT: return v > c.value;
            case GE: return v >= c.value;
            case LT: return v < c.value;
            case LE: return v <= c.value;
            case BETWEEN: return v >= c.value && v <= c.valueMax; // 闭区间 [value, valueMax]
            default: return false;
        }
    }

    private static boolean equalsValue(Object fieldValue, double ruleValue) {
        if (fieldValue instanceof Number) {
            return ((Number) fieldValue).doubleValue() == ruleValue;
        }
        if (fieldValue instanceof Boolean) {
            return ((Boolean) fieldValue) == (ruleValue != 0);
        }
        return false;
    }
}
