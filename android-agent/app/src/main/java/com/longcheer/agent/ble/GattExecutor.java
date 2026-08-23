package com.longcheer.agent.ble;

import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.PollingTask;

/**
 * GATT 操作执行器接口。串行或按 MAC 分片执行 GATT 命令/轮询序列。
 */
public interface GattExecutor {

    /**
     * 执行单条 GATT 命令。
     */
    void execute(GattCommand command);

    /**
     * 执行轮询任务（有序动作序列）。
     */
    void executeTask(PollingTask task);

    /**
     * 取消指定 MAC 的待执行 GATT 任务。
     */
    void cancelPending(String deviceMac);
}
