package com.longcheer.agent.model;

import android.os.SystemClock;

/**
 * 连接槽申请请求（SDD §8.3）。
 */
public class ConnectionRequest {

    public enum Reason {
        COMMAND, POLL, PERSISTENT, EVENT, FILE_TRANSFER, RECONNECT
    }

    // 需求来源优先级（SDD §5.1）：数值越大优先级越高；
    // 欠账老化每等待一个 agingThresholdMs 升一级，封顶 PRIORITY_HIGH（§5.2 第 6 条）。
    /** 保持连接（常驻） */
    public static final int PRIORITY_LOW = 0;
    /** 定时轮询 */
    public static final int PRIORITY_NORMAL = 1;
    /** 设备事件上报（中高） */
    public static final int PRIORITY_EVENT = 2;
    /** 服务器即时命令 */
    public static final int PRIORITY_HIGH = 3;

    /** 永不过期请求的 expireTime 取值（常驻设备请求；过期清理跳过该值）。 */
    public static final long NEVER_EXPIRE = Long.MAX_VALUE;

    /** 默认请求有效期：30s（命令/轮询等一次性需求）。 */
    private static final long DEFAULT_EXPIRE_MS = 30000L;

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
                SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime() + DEFAULT_EXPIRE_MS);
    }

    /**
     * 常驻设备连接请求：低优先级、永不过期，由调度器自愈机制保证持续重试（§5.2 第 1 条）。
     */
    public static ConnectionRequest persistent(String deviceMac) {
        return new ConnectionRequest(deviceMac, PRIORITY_LOW, Reason.PERSISTENT,
                SystemClock.elapsedRealtime(), NEVER_EXPIRE);
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
