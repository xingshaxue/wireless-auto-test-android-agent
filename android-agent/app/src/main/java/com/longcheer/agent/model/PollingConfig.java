package com.longcheer.agent.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行时轮询配置（model 包），对应 SDD §8.5。
 */
public final class PollingConfig {

    private final long intervalMs;
    private final List<UUID> readCharacteristics;
    private final List<UUID> notifyCharacteristics;
    private final boolean reportOnlyChanged;

    public PollingConfig(long intervalMs,
                         List<UUID> readCharacteristics,
                         List<UUID> notifyCharacteristics,
                         boolean reportOnlyChanged) {
        this.intervalMs = intervalMs;
        this.readCharacteristics = copyUuidList(readCharacteristics);
        this.notifyCharacteristics = copyUuidList(notifyCharacteristics);
        this.reportOnlyChanged = reportOnlyChanged;
    }

    public PollingConfig(PollingConfig other) {
        this(other.intervalMs,
             other.readCharacteristics,
             other.notifyCharacteristics,
             other.reportOnlyChanged);
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public List<UUID> getReadCharacteristics() {
        return Collections.unmodifiableList(readCharacteristics);
    }

    public List<UUID> getNotifyCharacteristics() {
        return Collections.unmodifiableList(notifyCharacteristics);
    }

    public boolean isReportOnlyChanged() {
        return reportOnlyChanged;
    }

    public static PollingConfig defaults() {
        return new PollingConfig(60000L,
                Collections.emptyList(), Collections.emptyList(), true);
    }

    @SuppressWarnings("unchecked")
    public static PollingConfig fromJson(Map<String, Object> json) {
        if (json == null) {
            return defaults();
        }
        Object interval = json.get("intervalMs");
        long intervalMs = interval instanceof Number ? ((Number) interval).longValue() : 60000L;

        List<UUID> reads = parseUuidList(json.get("readCharacteristics"));
        List<UUID> notifies = parseUuidList(json.get("notifyCharacteristics"));

        Object reportOnlyChanged = json.get("reportOnlyChanged");
        boolean reportOnlyChangedValue = reportOnlyChanged instanceof Boolean
                ? (Boolean) reportOnlyChanged : true;

        return new PollingConfig(intervalMs, reads, notifies, reportOnlyChangedValue);
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> parseUuidList(Object raw) {
        if (!(raw instanceof List)) {
            return Collections.emptyList();
        }
        List<UUID> list = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            if (item == null) continue;
            try {
                list.add(UUID.fromString(item.toString()));
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID 跳过
            }
        }
        return list.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    private static List<UUID> copyUuidList(List<UUID> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(src));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PollingConfig)) return false;
        PollingConfig that = (PollingConfig) o;
        return intervalMs == that.intervalMs
                && reportOnlyChanged == that.reportOnlyChanged
                && readCharacteristics.equals(that.readCharacteristics)
                && notifyCharacteristics.equals(that.notifyCharacteristics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(intervalMs, readCharacteristics,
                notifyCharacteristics, reportOnlyChanged);
    }
}
