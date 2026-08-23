package com.longcheer.agent.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 轮询配置（config 包），对应 SDD §16.4 {@code devices[].polling}。
 *
 * <p>用于 JSON 解析/配置层；运行时等价结构见 {@link com.longcheer.agent.model.PollingConfig}。</p>
 */
public final class PollingConfig {

    private final long intervalMs;
    private final List<String> readCharacteristics;
    private final List<String> notifyCharacteristics;
    private final boolean reportOnlyChanged;

    public PollingConfig(long intervalMs,
                         List<String> readCharacteristics,
                         List<String> notifyCharacteristics,
                         boolean reportOnlyChanged) {
        this.intervalMs = intervalMs;
        this.readCharacteristics = copyStringList(readCharacteristics);
        this.notifyCharacteristics = copyStringList(notifyCharacteristics);
        this.reportOnlyChanged = reportOnlyChanged;
    }

    public static PollingConfig fromJson(JSONObject json) {
        if (json == null) {
            return defaults();
        }
        return new PollingConfig(
                json.optLong("intervalMs", 60000L),
                parseStringArray(json.optJSONArray("readCharacteristics")),
                parseStringArray(json.optJSONArray("notifyCharacteristics")),
                json.optBoolean("reportOnlyChanged", true)
        );
    }

    public static PollingConfig defaults() {
        return new PollingConfig(60000L,
                Collections.emptyList(), Collections.emptyList(), true);
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public List<String> getReadCharacteristics() {
        return readCharacteristics;
    }

    public List<String> getNotifyCharacteristics() {
        return notifyCharacteristics;
    }

    public boolean isReportOnlyChanged() {
        return reportOnlyChanged;
    }

    private static List<String> copyStringList(List<String> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(src));
    }

    private static List<String> parseStringArray(JSONArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (item != null) {
                list.add(item.toString());
            }
        }
        return Collections.unmodifiableList(list);
    }
}
