package com.longcheer.agent.model;

import android.os.SystemClock;

/**
 * 连接槽申请请求（SDD §8.3）。
 */
public class ConnectionRequest {

    public enum Reason {
        COMMAND, POLL, PERSISTENT, EVENT, FILE_TRANSFER, RECONNECT
    }

    private final String deviceMac;
    private final int priority;
    private final Reason reason;
    private final long requestTime;
    private final long expireTime;

    public ConnectionRequest(String deviceMac, int priority, Reason reason,
                             long requestTime, long expireTime) {
        this.deviceMac = deviceMac;
        this.priority = priority;
        this.reason = reason;
        this.requestTime = requestTime;
        this.expireTime = expireTime;
    }

    public static ConnectionRequest now(String deviceMac, int priority, Reason reason) {
        return new ConnectionRequest(deviceMac, priority, reason,
                SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime() + 30000L);
    }

    public String getDeviceMac() {
        return deviceMac;
    }

    public int getPriority() {
        return priority;
    }

    public Reason getReason() {
        return reason;
    }

    public long getRequestTime() {
        return requestTime;
    }

    public long getExpireTime() {
        return expireTime;
    }
}
