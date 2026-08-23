package com.longcheer.agent.model;

/**
 * DUT 生命周期状态机枚举（SDD §4.1）。
 */
public enum DeviceState {
    REGISTERED,
    WAITING_SLOT,
    CONNECTING,
    SERVICE_DISCOVERING,
    CONFIGURING,
    READY,
    POLLING,
    COMMANDING,
    DISCONNECTED,
    RECONNECTING,
    ERROR,
    PAUSED,
    TERMINATED
}
