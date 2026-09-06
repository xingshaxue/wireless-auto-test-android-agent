package com.longcheer.agent.schedule;

import android.os.SystemClock;

import com.longcheer.agent.model.ConnectionSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * ConnectionSlotManager 实现（SDD §3.4 / §12.5）。
 *
 * <p>槽位数组容量在构造时固定；{@link #setLimit(int)} 在容量范围内动态调整可用上限，
 * 支持 SET_MAX_CONNECTIONS 的调大立即生效与调小驱逐回落（§5.4）。</p>
 */
public class ConnectionSlotManagerImpl implements ConnectionSlotManager {

    private final List<ConnectionSlot> slots;
    private final long maxHoldTimeMs;
    private int limit;

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
        this.limit = slotCount;
        this.maxHoldTimeMs = maxHoldTimeMs;
    }

    @Override
    public int slotCount() {
        return limit;
    }

    /**
     * @return 槽位数组容量（可调整上限的最大值）
     */
    public int capacity() {
        return slots.size();
    }

    @Override
    public void setLimit(int maxSlots) {
        synchronized (slots) {
            if (maxSlots < 0 || maxSlots > slots.size()) {
                throw new IllegalArgumentException(
                        "maxSlots must be in [0," + slots.size() + "], got " + maxSlots);
            }
            this.limit = maxSlots;
        }
    }

    @Override
    public ConnectionSlot acquire(String deviceMac, boolean pinned) {
        synchronized (slots) {
            int occupied = 0;
            ConnectionSlot firstFree = null;
            for (ConnectionSlot slot : slots) {
                if (slot.isFree()) {
                    if (firstFree == null) {
                        firstFree = slot;
                    }
                } else {
                    occupied++;
                }
            }
            if (firstFree == null || occupied >= limit) {
                return null;
            }
            firstFree.setCurrentDeviceMac(deviceMac);
            firstFree.setAcquireTime(SystemClock.elapsedRealtime());
            firstFree.setPinned(pinned);
            return firstFree;
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
     * 检查并释放超期占用的槽位（泄漏防护，§12.5）。
     *
     * @return 被强制释放的设备 MAC 列表，供调度器清理连接池与设备上下文
     */
    public List<String> releaseLeakedSlots() {
        List<String> released = new ArrayList<>();
        if (maxHoldTimeMs == Long.MAX_VALUE) {
            return released;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (slots) {
            for (ConnectionSlot slot : slots) {
                if (!slot.isFree() && now - slot.getAcquireTime() > maxHoldTimeMs) {
                    released.add(slot.getCurrentDeviceMac());
                    slot.clear();
                }
            }
        }
        return released;
    }

    /**
     * @return 当前空闲槽位数（不超过剩余可用额度）
     */
    public int freeSlotCount() {
        synchronized (slots) {
            int free = 0;
            int occupied = 0;
            for (ConnectionSlot slot : slots) {
                if (slot.isFree()) {
                    free++;
                } else {
                    occupied++;
                }
            }
            return Math.min(free, Math.max(0, limit - occupied));
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

    /**
     * @return 指定设备当前占用的槽位；未占用返回 null
     */
    public ConnectionSlot slotOf(String deviceMac) {
        synchronized (slots) {
            for (ConnectionSlot slot : slots) {
                if (deviceMac.equals(slot.getCurrentDeviceMac())) {
                    return slot;
                }
            }
            return null;
        }
    }
}
