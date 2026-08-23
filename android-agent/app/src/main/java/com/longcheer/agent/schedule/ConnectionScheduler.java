package com.longcheer.agent.schedule;

import com.longcheer.agent.model.ConnectionRequest;

/**
 * 连接调度器接口（SDD §3.6 / §16.1）。
 */
public interface ConnectionScheduler {

    void start();

    void stop();

    void requestSlot(ConnectionRequest request);

    void cancelRequest(String deviceMac);

    void setMaxSlots(int max);

    void setPersistent(String mac, boolean on);

    void pin(String mac, String reason);

    void unpin(String mac);
}
