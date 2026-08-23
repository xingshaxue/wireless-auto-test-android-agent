package com.longcheer.agent.model;

import android.os.SystemClock;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * GATT 操作命令（SDD §8.4）。
 */
public class GattCommand implements QueuedTask {

    public enum Type {
        READ, WRITE, ENABLE_NOTIFY, DISABLE_NOTIFY, SET_MTU
    }

    public enum Priority {
        HIGH, NORMAL, LOW
    }

    public String deviceMac;
    public String requestId;
    public Type type;
    public UUID serviceUuid;
    public UUID charUuid;
    public byte[] payload;
    public int timeoutMs = 3000;
    public int maxRetry = 1;
    public Priority priority = Priority.NORMAL;
    public long enqueueTime = SystemClock.elapsedRealtime();
    public long expireTime = Long.MAX_VALUE;

    public GattCommand() {
    }

    public GattCommand(String deviceMac, Priority priority) {
        this.deviceMac = deviceMac;
        this.priority = priority;
    }

    public GattCommand(String deviceMac, String requestId, Type type,
                       UUID serviceUuid, UUID charUuid, byte[] payload,
                       int timeoutMs, int maxRetry, Priority priority,
                       long enqueueTime, long expireTime) {
        this.deviceMac = deviceMac;
        this.requestId = requestId;
        this.type = type;
        this.serviceUuid = serviceUuid;
        this.charUuid = charUuid;
        this.payload = payload;
        this.timeoutMs = timeoutMs;
        this.maxRetry = maxRetry;
        this.priority = priority;
        this.enqueueTime = enqueueTime;
        this.expireTime = expireTime;
    }

    /**
     * 深拷贝构造。
     */
    public GattCommand(GattCommand other) {
        this(other.deviceMac, other.requestId, other.type, other.serviceUuid, other.charUuid,
                other.payload == null ? null : other.payload.clone(),
                other.timeoutMs, other.maxRetry, other.priority,
                other.enqueueTime, other.expireTime);
    }

    /**
     * M1 辅助构造：构造一条 GATT 命令。
     */
    public static GattCommand simple(String deviceMac, String requestId, Type type,
                                     UUID serviceUuid, UUID charUuid, byte[] payload,
                                     Priority priority) {
        GattCommand cmd = new GattCommand(deviceMac, priority);
        cmd.requestId = requestId;
        cmd.type = type;
        cmd.serviceUuid = serviceUuid;
        cmd.charUuid = charUuid;
        cmd.payload = payload;
        return cmd;
    }

    public String getRequestId() {
        return requestId;
    }

    public Type getType() {
        return type;
    }

    public UUID getServiceUuid() {
        return serviceUuid;
    }

    public UUID getCharUuid() {
        return charUuid;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    @Override
    public String getDeviceMac() {
        return deviceMac;
    }

    @Override
    public Priority getPriority() {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GattCommand)) return false;
        GattCommand that = (GattCommand) o;
        return timeoutMs == that.timeoutMs
                && maxRetry == that.maxRetry
                && enqueueTime == that.enqueueTime
                && expireTime == that.expireTime
                && Objects.equals(deviceMac, that.deviceMac)
                && Objects.equals(requestId, that.requestId)
                && type == that.type
                && Objects.equals(serviceUuid, that.serviceUuid)
                && Objects.equals(charUuid, that.charUuid)
                && Arrays.equals(payload, that.payload)
                && priority == that.priority;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(deviceMac, requestId, type, serviceUuid, charUuid,
                timeoutMs, maxRetry, priority, enqueueTime, expireTime);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
