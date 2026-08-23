package com.longcheer.agent.model;

/**
 * 统一队列任务接口。GattCommand 与 PollingTask 均实现本接口，可混排入 DeviceController 待执行队列。
 * M1 阶段仅作标记接口，后续可扩展优先级/TTL 等通用行为。
 */
public interface QueuedTask {

    /** @return 所属设备 MAC */
    String getDeviceMac();

    /**
     * @return 任务优先级：HIGH > NORMAL > LOW
     */
    GattCommand.Priority getPriority();

    /**
     * @return 任务入队时间（单调时钟，SystemClock.elapsedRealtime()）
     */
    default long getEnqueueTime() {
        return 0L;
    }

    /**
     * @return 任务过期时间（单调时钟），超过后应被丢弃
     */
    default long getExpireTime() {
        return Long.MAX_VALUE;
    }
}
