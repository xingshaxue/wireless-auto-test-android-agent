package com.longcheer.agent.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

/**
 * 受管设备状态机（SDD §4.1）。
 *
 * <p>维护当前状态并校验状态迁移合法性；非法迁移抛出 {@link IllegalStateException}。
 */
public final class DeviceStateMachine {

    private static final EnumMap<DeviceState, Set<DeviceState>> TRANSITIONS = new EnumMap<>(DeviceState.class);

    static {
        // SDD §4.1 合法迁移表
        TRANSITIONS.put(DeviceState.REGISTERED, EnumSet.of(
                DeviceState.WAITING_SLOT,
                DeviceState.ERROR,
                DeviceState.PAUSED,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.WAITING_SLOT, EnumSet.of(
                DeviceState.WAITING_SLOT,      // 幂等/刷新请求
                DeviceState.CONNECTING,
                DeviceState.ERROR,
                DeviceState.PAUSED,
                DeviceState.REGISTERED,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.CONNECTING, EnumSet.of(
                DeviceState.SERVICE_DISCOVERING,
                DeviceState.DISCONNECTED,
                DeviceState.ERROR,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.SERVICE_DISCOVERING, EnumSet.of(
                DeviceState.CONFIGURING,
                DeviceState.DISCONNECTED,
                DeviceState.ERROR,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.CONFIGURING, EnumSet.of(
                DeviceState.READY,
                DeviceState.DISCONNECTED,
                DeviceState.ERROR,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.READY, EnumSet.of(
                DeviceState.POLLING,
                DeviceState.COMMANDING,
                DeviceState.DISCONNECTED,
                DeviceState.PAUSED,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.POLLING, EnumSet.of(
                DeviceState.READY,
                DeviceState.DISCONNECTED,
                DeviceState.ERROR,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.COMMANDING, EnumSet.of(
                DeviceState.READY,
                DeviceState.DISCONNECTED,
                DeviceState.ERROR,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.DISCONNECTED, EnumSet.of(
                DeviceState.WAITING_SLOT,
                DeviceState.RECONNECTING,
                DeviceState.REGISTERED,
                DeviceState.TERMINATED));

        // RECONNECTING -> CONNECTING 为非法迁移（SDD §4.1 关键约束）
        TRANSITIONS.put(DeviceState.RECONNECTING, EnumSet.of(
                DeviceState.WAITING_SLOT,
                DeviceState.DISCONNECTED,
                DeviceState.ERROR,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.ERROR, EnumSet.of(
                DeviceState.PAUSED,
                DeviceState.REGISTERED,
                DeviceState.TERMINATED));

        TRANSITIONS.put(DeviceState.PAUSED, EnumSet.of(
                DeviceState.WAITING_SLOT,
                DeviceState.REGISTERED,
                DeviceState.TERMINATED));

        // TERMINATED 为终态，不允许迁出
        TRANSITIONS.put(DeviceState.TERMINATED, EnumSet.noneOf(DeviceState.class));
    }

    private DeviceState current;

    public DeviceStateMachine() {
        this(DeviceState.REGISTERED);
    }

    public DeviceStateMachine(DeviceState initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial state cannot be null");
        }
        this.current = initial;
    }

    public DeviceState getCurrentState() {
        return current;
    }

    /**
     * 判断从当前状态是否可以迁移到目标状态。
     */
    public boolean canTransition(DeviceState target) {
        if (target == null) {
            return false;
        }
        Set<DeviceState> allowed = TRANSITIONS.get(current);
        return allowed != null && allowed.contains(target);
    }

    /**
     * 执行状态迁移；非法迁移抛出 {@link IllegalStateException}。
     */
    public void transition(DeviceState target) {
        if (!canTransition(target)) {
            throw new IllegalStateException(
                    "Illegal state transition: " + current + " -> " + target);
        }
        this.current = target;
    }
}
