package com.longcheer.agent.report;

import com.longcheer.agent.model.DeviceState;

import java.util.Map;

/**
 * 状态上报器接口（SDD §3.10 / §16.1）。
 */
public interface StateReporter {

    void report(String event, Map<String, Object> payload);

    void reportCommandAck(String requestId, int errorCode, Object result);

    /**
     * CMD_ACK 带 rawStatus 透传（§12.9：errorCode 供逻辑判断，rawStatus 供排障）。
     */
    default void reportCommandAck(String requestId, int errorCode, int rawStatus, Object result) {
        reportCommandAck(requestId, errorCode, result);
    }

    void reportDeviceState(String mac, DeviceState state);

    void reportPollResult(String mac, Map<String, Object> fields, boolean stale);

    void reportFileProgress(String taskId, double percent, long bytesPerSec);

    void flush();
}
