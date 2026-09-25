package com.longcheer.agent.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Agent 全量配置（SDD §16.4）。
 *
 * <p>对应 REGISTER_ACK 下发的顶层 JSON 配置，运行中整项替换、下一 tick 原子生效。</p>
 */
public final class AgentConfig {

    private final int configVersion;
    private final int maxSlots;
    private final long timeSliceMs;
    private final long idleReleaseMs;
    private final long commandTtlMs;
    private final int maxPendingCommands;
    private final long tickIntervalMs;
    private final long cooldownMs;
    private final long setupBudgetMs;
    private final long agingThresholdMs;
    private final long heartbeatIntervalMs;
    private final long connectTimeoutMs;
    private final long gattTimeoutMs;
    private final int maxReconnectAttempts;
    private final long reconnectBackoffMaxMs;
    /** ERROR 后的慢速自愈重试间隔（§7.5 扩展：ERROR 非终态，可穿戴离场/休眠是常态）。 */
    private final long errorRetryMs;
    private final long notifyMinReportIntervalMs;
    private final int maxConcurrentTransfers;
    private final int diskQuotaMb;
    private final int failedTaskRetentionDays;
    private final int reportBufferMax;
    private final long staleThresholdMs;
    private final List<DeviceConfig> devices;

    public AgentConfig(int configVersion,
                       int maxSlots,
                       long timeSliceMs,
                       long idleReleaseMs,
                       long commandTtlMs,
                       int maxPendingCommands,
                       long tickIntervalMs,
                       long cooldownMs,
                       long setupBudgetMs,
                       long agingThresholdMs,
                       long heartbeatIntervalMs,
                       long connectTimeoutMs,
                       long gattTimeoutMs,
                       int maxReconnectAttempts,
                       long reconnectBackoffMaxMs,
                       long errorRetryMs,
                       long notifyMinReportIntervalMs,
                       int maxConcurrentTransfers,
                       int diskQuotaMb,
                       int failedTaskRetentionDays,
                       int reportBufferMax,
                       long staleThresholdMs,
                       List<DeviceConfig> devices) {
        this.configVersion = configVersion;
        this.maxSlots = maxSlots;
        this.timeSliceMs = timeSliceMs;
        this.idleReleaseMs = idleReleaseMs;
        this.commandTtlMs = commandTtlMs;
        this.maxPendingCommands = maxPendingCommands;
        this.tickIntervalMs = tickIntervalMs;
        this.cooldownMs = cooldownMs;
        this.setupBudgetMs = setupBudgetMs;
        this.agingThresholdMs = agingThresholdMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.gattTimeoutMs = gattTimeoutMs;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectBackoffMaxMs = reconnectBackoffMaxMs;
        this.errorRetryMs = errorRetryMs;
        this.notifyMinReportIntervalMs = notifyMinReportIntervalMs;
        this.maxConcurrentTransfers = maxConcurrentTransfers;
        this.diskQuotaMb = diskQuotaMb;
        this.failedTaskRetentionDays = failedTaskRetentionDays;
        this.reportBufferMax = reportBufferMax;
        this.staleThresholdMs = staleThresholdMs;
        this.devices = devices == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(devices));
    }

    /**
     * 从 JSONObject 解析全量配置；缺失字段使用 SDD §16.4 默认值兜底。
     */
    public static AgentConfig fromJson(JSONObject json) {
        if (json == null) {
            json = new JSONObject();
        }
        List<DeviceConfig> deviceList = new ArrayList<>();
        JSONArray devicesArray = json.optJSONArray("devices");
        if (devicesArray != null) {
            for (int i = 0; i < devicesArray.length(); i++) {
                JSONObject deviceObj = devicesArray.optJSONObject(i);
                if (deviceObj != null) {
                    deviceList.add(DeviceConfig.fromJson(deviceObj));
                }
            }
        }

        return new AgentConfig(
                json.optInt("configVersion", 0),
                json.optInt("maxSlots", 3),
                json.optLong("timeSliceMs", 2000L),
                json.optLong("idleReleaseMs", 30000L),
                json.optLong("commandTtlMs", 300000L),
                json.optInt("maxPendingCommands", 64),
                json.optLong("tickIntervalMs", 100L),
                json.optLong("cooldownMs", 5000L),
                json.optLong("setupBudgetMs", 4000L),
                json.optLong("agingThresholdMs", 30000L),
                json.optLong("heartbeatIntervalMs", 5000L),
                json.optLong("connectTimeoutMs", 10000L),
                json.optLong("gattTimeoutMs", 3000L),
                json.optInt("maxReconnectAttempts", 5),
                json.optLong("reconnectBackoffMaxMs", 60000L),
                json.optLong("errorRetryMs", 60000L),
                json.optLong("notifyMinReportIntervalMs", 200L),
                json.optInt("maxConcurrentTransfers", 1),
                json.optInt("diskQuotaMb", 1024),
                json.optInt("failedTaskRetentionDays", 7),
                json.optInt("reportBufferMax", 1000),
                json.optLong("staleThresholdMs", 120000L),
                deviceList
        );
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public int getMaxSlots() {
        return maxSlots;
    }

    public long getTimeSliceMs() {
        return timeSliceMs;
    }

    public long getIdleReleaseMs() {
        return idleReleaseMs;
    }

    public long getCommandTtlMs() {
        return commandTtlMs;
    }

    public int getMaxPendingCommands() {
        return maxPendingCommands;
    }

    public long getTickIntervalMs() {
        return tickIntervalMs;
    }

    public long getCooldownMs() {
        return cooldownMs;
    }

    public long getSetupBudgetMs() {
        return setupBudgetMs;
    }

    public long getAgingThresholdMs() {
        return agingThresholdMs;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public long getGattTimeoutMs() {
        return gattTimeoutMs;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public long getReconnectBackoffMaxMs() {
        return reconnectBackoffMaxMs;
    }

    public long getErrorRetryMs() {
        return errorRetryMs;
    }

    public long getNotifyMinReportIntervalMs() {
        return notifyMinReportIntervalMs;
    }

    public int getMaxConcurrentTransfers() {
        return maxConcurrentTransfers;
    }

    public int getDiskQuotaMb() {
        return diskQuotaMb;
    }

    public int getFailedTaskRetentionDays() {
        return failedTaskRetentionDays;
    }

    public int getReportBufferMax() {
        return reportBufferMax;
    }

    public long getStaleThresholdMs() {
        return staleThresholdMs;
    }

    public List<DeviceConfig> getDevices() {
        return devices;
    }
}
