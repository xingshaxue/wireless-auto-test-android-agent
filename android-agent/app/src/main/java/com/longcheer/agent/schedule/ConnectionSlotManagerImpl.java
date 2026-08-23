package com.longcheer.agent.schedule;

import android.os.SystemClock;

import com.longcheer.agent.model.ConnectionSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * ConnectionSlotManager 基础实现。
 * M1 阶段：维护固定数量槽位，支持 acquire/release/forceRelease 与泄漏防护检查。
 */
public class ConnectionSlotManagerImpl implements ConnectionSlotManager {

    private final List<ConnectionSlot> slots;
    private final long maxHoldTimeMs;

    public ConnectionSlotManagerImpl(int slotCount) {
        this(slotCount, Long.MAX_VALUE);
    }

    public ConnectionSlotManagerImpl(int slotCount, long maxHoldTimeMs) {
        if (slotCount < 0) {
            throw new IllegalArgumentException("slotCount must be >= 0");
        }
        this.slots = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            this.slots.add(new ConnectionSlot(i));
        }
        this.maxHoldTimeMs = maxHoldTimeMs;
    }

    @Override
    public int slotCount() {
        return slots.size();
    }

    @Override
    public ConnectionSlot acquire(String deviceMac, boolean pinned) {
        synchronized (slots) {
            for (ConnectionSlot slot : slots) {
                if (slot.isFree()) {
                    slot.setCurrentDeviceMac(deviceMac);
                    slot.setAcquireTime(SystemClock.elapsedRealtime());
                    slot.setPinned(pinned);
                    return slot;
                }
            }
            return null;
        }
    }

    @Override
    public void release(String deviceMac) {
        synchronized (slots) {
            for (ConnectionSlot slot : slots) {
                if (deviceMac.equals(slot.getCurrentDeviceMac())) {
                    slot.clear();
                    return;
                }
            }
        }
    }

    @Override
    public void forceRelease(String deviceMac) {
        // M1: 与 release 行为相同；后续可加入强制断开 Gatt 与事件上报。
        release(deviceMac);
    }

    /**
     * 检查并释放超期占用的槽位（泄漏防护）。
     */
    public void releaseLeakedSlots() {
        if (maxHoldTimeMs == Long.MAX_VALUE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (slots) {
            for (ConnectionSlot slot : slots) {
                if (!slot.isFree() && now - slot.getAcquireTime() > maxHoldTimeMs) {
                    slot.clear();
                }
            }
        }
    }

    /**
     * @return 当前空闲槽位数
     */
    public int freeSlotCount() {
        synchronized (slots) {
            int count = 0;
            for (ConnectionSlot slot : slots) {
                if (slot.isFree()) count++;
            }
            return count;
        }
    }

    /**
     * @return 当前已占用槽位列表（快照）
     */
    public List<ConnectionSlot> occupiedSlots() {
        synchronized (slots) {
            List<ConnectionSlot> list = new ArrayList<>();
            for (ConnectionSlot slot : slots) {
                if (!slot.isFree()) list.add(slot);
            }
            return list;
        }
    }
}
