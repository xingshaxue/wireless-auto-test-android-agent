package com.longcheer.agent.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 轮询结果处理规则配置（config 包），对应 SDD §16.4 {@code devices[].rules[]}。
 *
 * <p>运行时等价结构见 {@link com.longcheer.agent.model.PollRule}。</p>
 */
public final class PollRuleConfig {

    private final String ruleId;
    private final int priority;
    private final boolean stopOnMatch;
    private final List<ConditionConfig> conditions;
    private final List<RuleActionConfig> actions;

    public PollRuleConfig(String ruleId,
                          int priority,
                          boolean stopOnMatch,
                          List<ConditionConfig> conditions,
                          List<RuleActionConfig> actions) {
        this.ruleId = ruleId;
        this.priority = priority;
        this.stopOnMatch = stopOnMatch;
        this.conditions = conditions == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(conditions));
        this.actions = actions == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(actions));
    }

    public static PollRuleConfig fromJson(JSONObject json) {
        if (json == null) {
            json = new JSONObject();
        }
        List<ConditionConfig> conditions = new ArrayList<>();
        JSONArray conditionsArray = json.optJSONArray("conditions");
        if (conditionsArray != null) {
            for (int i = 0; i < conditionsArray.length(); i++) {
                JSONObject condObj = conditionsArray.optJSONObject(i);
                if (condObj != null) {
                    conditions.add(ConditionConfig.fromJson(condObj));
                }
            }
        }

        List<RuleActionConfig> actions = new ArrayList<>();
        JSONArray actionsArray = json.optJSONArray("actions");
        if (actionsArray != null) {
            for (int i = 0; i < actionsArray.length(); i++) {
                JSONObject actionObj = actionsArray.optJSONObject(i);
                if (actionObj != null) {
                    actions.add(RuleActionConfig.fromJson(actionObj));
                }
            }
        }

        return new PollRuleConfig(
                json.optString("ruleId", null),
                json.optInt("priority", 0),
                json.optBoolean("stopOnMatch", false),
                conditions,
                actions
        );
    }

    public String getRuleId() {
        return ruleId;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isStopOnMatch() {
        return stopOnMatch;
    }

    public List<ConditionConfig> getConditions() {
        return conditions;
    }

    public List<RuleActionConfig> getActions() {
        return actions;
    }
}
