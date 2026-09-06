package com.longcheer.agent.schedule;

import com.longcheer.agent.model.PollingConfig;

import java.util.Map;
import java.util.UUID;

/**
 * 轮询调度器接口（SDD §3.9 / §16.1）。
 */
public interface PollingScheduler {

    void start();

    void stop();

    void updateConfig(String mac, PollingConfig config);

    /**
     * 暂停某设备的轮询调度（§7.6：文件传输期间避免 GATT 操作冲突）。
     * 在途轮询任务自然完成，不再产生新欠账。
     */
    void suspendPolling(String mac);

    /** 恢复轮询调度（传输完成/取消/中止后）。 */
    void resumePolling(String mac);

    void onPollCompleted(String mac, Map<UUID, byte[]> rawResults);

    /**
     * 轮询任务失败/中止回调（§7.3.1）：清理欠账防重入标记，
     * 超 staleThresholdMs 时上报 POLL_DATA_STALE（reason=CONNECT_FAILED，§12.9）。
     */
    void onPollFailed(String mac, int errorCode, int rawStatus);

    long estimateFullRoundMs();
}
