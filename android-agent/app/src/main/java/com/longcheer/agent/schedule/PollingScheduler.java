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

    void onPollCompleted(String mac, Map<UUID, byte[]> rawResults);

    /**
     * 轮询任务失败/中止回调（§7.3.1）：清理欠账防重入标记，
     * 超 staleThresholdMs 时上报 POLL_DATA_STALE（reason=CONNECT_FAILED，§12.9）。
     */
    void onPollFailed(String mac, int errorCode, int rawStatus);

    long estimateFullRoundMs();
}
