package com.longcheer.agent.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 单台受管设备配置（config 包），对应 SDD §16.4 {@code devices[]}。
 */
public final class DeviceConfig {

    private final String deviceId;
    private final String mac;
    private final String type;
    private final int priority;
    private final boolean persistent;
    private final Map<String, String> profile;
    private final Map<String, FieldMappingConfig> fields;
    private final PollingConfig polling;
    private final List<PollRuleConfig> rules;

    public DeviceConfig(String deviceId,
                        String mac,
                        String type,
                        int priority,
                        boolean persistent,
                        Map<String, String> profile,
                        Map<String, FieldMappingConfig> fields,
                        PollingConfig polling,
                        List<PollRuleConfig> rules) {
        this.deviceId = deviceId;
        this.mac = mac;
        this.type = type;
        this.priority = priority;
        this.persistent = persistent;
        this.profile = copyStringMap(profile);
        this.fields = copyFieldMap(fields);
        this.polling = polling == null ? PollingConfig.defaults() : polling;
        this.rules = rules == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(rules));
    }

    public static DeviceConfig fromJson(JSONObject json) {
        if (json == null) {
            json = new JSONObject();
        }

        Map<String, String> profile = new HashMap<>();
        JSONObject profileObj = json.optJSONObject("profile");
        if (profileObj != null) {
            Iterator<String> keys = profileObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = profileObj.opt(key);
                profile.put(key, value == null ? null : value.toString());
            }
        }

        Map<String, FieldMappingConfig> fields = new HashMap<>();
        JSONObject fieldsObj = json.optJSONObject("fields");
        if (fieldsObj != null) {
            Iterator<String> keys = fieldsObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject fieldObj = fieldsObj.optJSONObject(key);
                if (fieldObj != null) {
                    fields.put(key, FieldMappingConfig.fromJson(key, fieldObj));
                }
            }
        }

        List<PollRuleConfig> rules = new ArrayList<>();
        JSONArray rulesArray = json.optJSONArray("rules");
        if (rulesArray != null) {
            for (int i = 0; i < rulesArray.length(); i++) {
                JSONObject ruleObj = rulesArray.optJSONObject(i);
                if (ruleObj != null) {
                    rules.add(PollRuleConfig.fromJson(ruleObj));
                }
            }
        }

        return new DeviceConfig(
                json.optString("deviceId", null),
                json.optString("mac", null),
                json.optString("type", null),
                json.optInt("priority", 0),
                json.optBoolean("persistent", false),
                profile,
                fields,
                PollingConfig.fromJson(json.optJSONObject("polling")),
                rules
        );
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getMac() {
        return mac;
    }

    public String getType() {
        return type;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public Map<String, String> getProfile() {
        return profile;
    }

    public Map<String, FieldMappingConfig> getFields() {
        return fields;
    }

    public PollingConfig getPolling() {
        return polling;
    }

    public List<PollRuleConfig> getRules() {
        return rules;
    }

    private static Map<String, String> copyStringMap(Map<String, String> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<>(src));
    }

    private static Map<String, FieldMappingConfig> copyFieldMap(Map<String, FieldMappingConfig> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<>(src));
    }
}
