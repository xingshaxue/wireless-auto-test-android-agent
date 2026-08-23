package com.longcheer.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 轮询结果规则（SDD §8.7）。
 */
public class PollRule {

    public String ruleId;
    public List<Condition> conditions = new ArrayList<>();
    public List<RuleAction> actions = new ArrayList<>();
    public int priority = 0;
    public boolean stopOnMatch = false;

    public PollRule() {
    }

    public PollRule(String ruleId, List<Condition> conditions, List<RuleAction> actions,
                    int priority, boolean stopOnMatch) {
        this.ruleId = ruleId;
        this.conditions = conditions;
        this.actions = actions;
        this.priority = priority;
        this.stopOnMatch = stopOnMatch;
    }

    public static class Condition {
        public String field;
        public Operator op;
        public double value;
        public double valueMax;

        public Condition() {
        }

        public Condition(String field, Operator op, double value, double valueMax) {
            this.field = field;
            this.op = op;
            this.value = value;
            this.valueMax = valueMax;
        }

        public enum Operator {
            GT, GE, LT, LE, EQ, NE, BETWEEN
        }
    }

    public static class RuleAction {
        public RuleActionType type;
        public Map<String, Object> params;

        public RuleAction() {
        }

        public RuleAction(RuleActionType type, Map<String, Object> params) {
            this.type = type;
            this.params = params;
        }

        public enum RuleActionType {
            REPORT_EVENT, EXECUTE_GATT, SET_INTERVAL, SET_DEVICE_STATE, RELEASE_SLOT, FILE_TRANSFER
        }
    }
}
