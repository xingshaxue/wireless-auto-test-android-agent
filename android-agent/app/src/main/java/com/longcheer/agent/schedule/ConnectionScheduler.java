package com.longcheer.agent.schedule;

import com.longcheer.agent.model.ConnectionRequest;

/**
 * 连接调度器接口（SDD §3.6 / §16.1）。
 *
 * <p>相对 §16.1 签名的扩展（M2）：{@link #setMaxSlots} 返回是否受理（§5.4 驱逐失败回 3003）；
 * 新增 {@link #releaseSlot}（主动释放的槽位/连接池/设备状态联动）、
 * {@link #suspendScheduling} / {@link #resumeScheduling}（§12.10 蓝牙关闭/恢复）、
 * {@link #requiredSlots()}（5.4 拒绝调整时报文携带的 required 字段）。</p>
 */
public interface ConnectionScheduler {

    void start();

    void stop();

    void requestSlot(ConnectionRequest request);

    void cancelRequest(String deviceMac);

    /**
     * 动态调整槽位上限（§5.4）。
     *
     * @return true = 已受理（调大立即生效；调小已按 5.4 顺序驱逐超出部分）；
     *         false = 新上限低于常驻 + pinned 数量，拒绝调整（调用方回 3003，
     *         报文体携带 required={@link #requiredSlots()} / max=请求值）
     * @throws IllegalArgumentException max 越界（&lt;2 或 &gt;5）
     */
    boolean setMaxSlots(int max);

    void setPersistent(String mac, boolean on);

    void pin(String mac, String reason);

    void unpin(String mac);

    /**
     * 主动释放指定设备的槽位：归还槽位、移出活动连接池、通知设备 onSlotReleased。
     * 供 DISCONNECT_DEVICE / REMOVE_DEVICE / 驱逐等路径调用。
     */
    void releaseSlot(String deviceMac);

    /**
     * 暂停授槽（§12.10 蓝牙关闭/飞行模式）：释放全部已占用槽位并停止新的分配；
     * 待处理连接请求保留（欠账不清零），恢复后继续调度。
     */
    void suspendScheduling();

    /**
     * 恢复授槽（蓝牙恢复）：欠账请求按优先级重新竞争槽位。
     */
    void resumeScheduling();

    /**
     * @return 常驻 + pinned 设备数（5.4 拒绝调整时的 required 值）
     */
    int requiredSlots();
}
