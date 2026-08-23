package com.longcheer.agent.schedule;

import com.longcheer.agent.model.ConnectionSlot;

/**
 * 连接槽管理器接口（SDD §3.4 / §16.1）。
 */
public interface ConnectionSlotManager {

    int slotCount();

    ConnectionSlot acquire(String deviceMac, boolean pinned);

    void release(String deviceMac);

    void forceRelease(String deviceMac);
}
