package com.longcheer.agent.schedule;

import com.longcheer.agent.model.ConnectionSlot;

/**
 * 连接槽管理器接口（SDD §3.4 / §16.1）。
 */
public interface ConnectionSlotManager {

    /**
     * @return 当前可用槽位上限（动态调整后以 setLimit 为准，§5.4）
     */
    int slotCount();

    ConnectionSlot acquire(String deviceMac, boolean pinned);

    void release(String deviceMac);

    void forceRelease(String deviceMac);

    /**
     * 动态调整可用槽位上限（SDD §5.4）：调大立即生效；调小仅阻止新的 acquire，
     * 已占用槽位由调度器按 5.4 驱逐后自然回落。
     *
     * @param maxSlots 新上限，取值 [0, 构造容量]
     */
    void setLimit(int maxSlots);

    /**
     * @return 指定设备当前占用的槽位；未占用返回 null（调度器读取 acquireTime 用）
     */
    ConnectionSlot slotOf(String deviceMac);

    /**
     * 泄漏巡检（§12.5）：释放超期占用的槽位。
     *
     * @return 被强制释放的设备 MAC 列表，供调度器清理连接池与设备上下文
     */
    java.util.List<String> releaseLeakedSlots();

    /**
     * @return 当前已占用槽位列表（快照，调度器巡检用）
     */
    java.util.List<ConnectionSlot> occupiedSlots();
}
