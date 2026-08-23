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

    long estimateFullRoundMs();
}
