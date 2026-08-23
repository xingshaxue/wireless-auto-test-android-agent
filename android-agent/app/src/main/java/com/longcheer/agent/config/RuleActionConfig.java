package com.longcheer.agent.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/**
 * 规则动作配置（config 包），对应 SDD §16.4 {@code devices[].rules[].actions[]}。
 *
 * <p>运行时等价结构见 {@link com.longcheer.agent.model.RuleAction}。</p>
 */
public final class RuleActionConfig {

    private final String type;                 // REPORT_EVENT / EXECUTE_GATT / SET_INTERVAL / SET_DEVICE_STATE / RELEASE_SLOT / FILE_TRANSFER
    private final Map<String, Object> params;

    public RuleActionConfig(String type, Map<String, Object> params) {
        this.type = type;
        this.params = params == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(params));
    }

    public static RuleActionConfig fromJson(JSONObject json) {
        if (json == null) {
            json = new JSONObject();
        }
        String type = json.optString("type", null);
        Map<String, Object> params = new HashMap<>();
        JSONObject paramsObj = json.optJSONObject("params");
        if (paramsObj != null) {
            Iterator<String> keys = paramsObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                params.put(key, paramsObj.opt(key));
            }
        }
        return new RuleActionConfig(type, params);
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getParams() {
        return params;
    }
}
