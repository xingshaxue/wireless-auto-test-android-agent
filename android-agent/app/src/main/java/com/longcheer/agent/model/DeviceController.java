package com.longcheer.agent.model;

import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollRule;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.model.PollingTask;

import java.util.List;

/**
 * 受管设备对象接口（SDD §3.7 / §16.1）。
 */
public interface DeviceController {

    void pause(boolean abortTransfer);

    void resume();

    void terminate();

    void onSlotAcquired();

    void onSlotReleased();

    void enqueueCommand(GattCommand cmd);

    void enqueuePollTask(PollingTask task);

    void setPollingConfig(PollingConfig config);

    void setPollRules(List<PollRule> rules);

    boolean isReady();

    DeviceState getState();

    ManagedDeviceInfo snapshot();

    // ---- 显式写接口（SDD §8.1 并发与回写约定：写入一律经控制器在锁内完成） ----

    /** 写入业务标记（SET_DEVICE_STATE 动作，§7.3.2；不得修改状态机状态）。 */
    void setStateFlag(String stateFlag);

    /** 回写轮询时间（pollDataStale 仅在 PollingScheduler.onPollCompleted 清除，§6.1）。 */
    void updatePollTimes(long lastPollTime, long nextPollTime);

    /** 设置轮询数据过期标记。 */
    void setPollDataStale(boolean stale);

    /** 缓存一轮轮询的原始结果（§6.3）。 */
    void updatePollResult(java.util.Map<java.util.UUID, byte[]> lastPollResult);

    /** 设置通知活跃期截止时间：此前 selectVictim 不踢该设备（§7.3.3）。 */
    void setNotifyBoostUntil(long notifyBoostUntil);
}
