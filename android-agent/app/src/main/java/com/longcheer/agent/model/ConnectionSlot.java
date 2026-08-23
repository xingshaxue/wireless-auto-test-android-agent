package com.longcheer.agent.model;

/**
 * BLE 连接槽（SDD §8.2）。
 */
public class ConnectionSlot {

    private final int slotId;
    private String currentDeviceMac;
    private long acquireTime;
    private boolean pinned;

    public ConnectionSlot(int slotId) {
        this.slotId = slotId;
    }

    public int getSlotId() {
        return slotId;
    }

    public String getCurrentDeviceMac() {
        return currentDeviceMac;
    }

    public void setCurrentDeviceMac(String currentDeviceMac) {
        this.currentDeviceMac = currentDeviceMac;
    }

    public long getAcquireTime() {
        return acquireTime;
    }

    public void setAcquireTime(long acquireTime) {
        this.acquireTime = acquireTime;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean isFree() {
        return currentDeviceMac == null || currentDeviceMac.isEmpty();
    }

    public void clear() {
        this.currentDeviceMac = null;
        this.acquireTime = 0;
        this.pinned = false;
    }
}
