package com.longcheer.agent.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 复杂轮询任务：有序动作序列（SDD §8.6）。
 *
 * <p>实现 {@link QueuedTask}，可与 {@link GattCommand} 混排入设备待执行队列。
 */
public class PollingTask implements QueuedTask {

    public String deviceMac;
    public List<PollStep> steps = new ArrayList<>();
    public long timeoutMs = 5000L;
    public GattCommand.Priority priority = GattCommand.Priority.NORMAL;
    public long enqueueTime = android.os.SystemClock.elapsedRealtime();
    public long expireTime = Long.MAX_VALUE;

    public PollingTask() {
    }

    public PollingTask(String deviceMac) {
        this.deviceMac = deviceMac;
    }

    public PollingTask(String deviceMac, List<PollStep> steps, long timeoutMs,
                       GattCommand.Priority priority, long enqueueTime, long expireTime) {
        this.deviceMac = deviceMac;
        if (steps != null) {
            this.steps.addAll(steps);
        }
        this.timeoutMs = timeoutMs;
        this.priority = priority;
        this.enqueueTime = enqueueTime;
        this.expireTime = expireTime;
    }

    /**
     * 深拷贝构造。
     */
    public PollingTask(PollingTask other) {
        this.deviceMac = other.deviceMac;
        this.timeoutMs = other.timeoutMs;
        this.priority = other.priority;
        this.enqueueTime = other.enqueueTime;
        this.expireTime = other.expireTime;
        if (other.steps != null) {
            for (PollStep step : other.steps) {
                this.steps.add(new PollStep(step.type, step.serviceUuid, step.charUuid,
                        step.payload == null ? null : step.payload.clone(),
                        step.timeoutMs, step.maxRetry));
            }
        }
    }

    /**
     * M1 辅助构造：构造一条简单轮询任务。
     */
    public static PollingTask simple(String deviceMac, List<PollStep> steps) {
        PollingTask task = new PollingTask(deviceMac);
        if (steps != null) {
            task.steps.addAll(steps);
        }
        return task;
    }

    @Override
    public String getDeviceMac() {
        return deviceMac;
    }

    @Override
    public GattCommand.Priority getPriority() {
        return priority;
    }

    @Override
    public long getEnqueueTime() {
        return enqueueTime;
    }

    @Override
    public long getExpireTime() {
        return expireTime;
    }
}
