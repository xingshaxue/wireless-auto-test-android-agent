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

    /**
     * 命令入队（HIGH，受背压上限约束，§7.4.1）。
     *
     * @return true = 已受理；false = 背压拒绝（队列满）或设备已终止，
     *         调用方应立即回 CMD_ACK 3001，避免 requestId 悬挂
     */
    boolean enqueueCommand(GattCommand cmd);

    void enqueuePollTask(PollingTask task);

    void setPollingConfig(PollingConfig config);

    void setPollRules(List<PollRule> rules);

    boolean isReady();

    DeviceState getState();

    /**
     * 实际协商的 MTU（§3.2；未实现/未知返回 0，调用方按协议默认包长处理）。
     * 文件传输适配器据此联动包长（LC 通道需 MTU≥227）。
     */
    default int getNegotiatedMtu() {
        return 0;
    }

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

    // ---- 断线重连与生命周期（§7.5 / §7.7 / §7.8） ----

    /**
     * 异常断开（§7.5）：调用前调度器已释放槽位（设备处于 DISCONNECTED），
     * 本方法迁移 RECONNECTING（不持槽的退避计时状态）。
     */
    void onAbnormalDisconnect();

    /**
     * 退避到期（§7.5）：RECONNECTING → WAITING_SLOT（随后按欠账最高优先级重新申请槽位）；
     * 若 pendingPause 挂起中则补迁 PAUSED（§7.7）。
     */
    void onReconnectBackoffExpired();

    /** 放弃重连（超 maxReconnectAttempts）：RECONNECTING → ERROR（§7.5 第 6 条）。 */
    void onReconnectGiveUp();

    /** ERROR 慢速自愈（§7.5 扩展）：ERROR → REGISTERED，仅 ERROR 态生效。 */
    void recoverFromError();

    /** 清空待执行队列并返回全部任务（REMOVE/RESET 逐条回 2004 用，§7.7/§7.8）。 */
    java.util.List<QueuedTask> drainPendingCommands();

    /**
     * 软重置（§7.8）：清队列与欠账上下文、状态机回 REGISTERED、重连计数清零。
     * 轮询计划重置由 PollingScheduler 另行处理。
     */
    void reset();
}
