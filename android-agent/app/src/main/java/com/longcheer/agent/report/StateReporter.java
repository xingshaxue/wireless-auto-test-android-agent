package com.longcheer.agent.report;

import com.longcheer.agent.model.DeviceState;

import java.util.Map;

/**
 * 状态上报器接口（SDD §3.10 / §16.1）。
 */
public interface StateReporter {

    void report(String event, Map<String, Object> payload);

    void reportCommandAck(String requestId, int errorCode, Object result);

    void reportDeviceState(String mac, DeviceState state);

    void reportPollResult(String mac, Map<String, Object> fields, boolean stale);

    void reportFileProgress(String taskId, double percent, long bytesPerSec);

    void flush();
}
