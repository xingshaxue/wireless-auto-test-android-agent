package com.longcheer.agent.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * 受管设备状态信息（SDD §8.1）。
 *
 * <p>当前为可变对象，由 DeviceController 同步访问；M1 阶段 snapshot 返回内部引用，
 * 后续演进为深拷贝不可变快照。</p>
 */
public class ManagedDeviceInfo {

    private String deviceId;
    private String mac;
    private DeviceState state = DeviceState.REGISTERED;
    private PollingConfig pollingConfig;
    private long nextPollTime;
    private long lastPollTime;
    private Map<UUID, byte[]> lastPollResult;
    private boolean pollDataStale;
    private PriorityQueue<QueuedTask> pendingCommands = new PriorityQueue<>(
            Comparator.comparingInt((QueuedTask t) -> t.getPriority().ordinal())
                    .thenComparingLong(QueuedTask::getEnqueueTime));
    private int maxPendingCommands = 64;
    private int priority;
    private boolean persistent;
    private long lastConnectedTime;
    private long disconnectedTime;
    private long coolDownUntil;
    private int reconnectCount;
    private String stateFlag;
    private long notifyBoostUntil;
    /** 文件/OTA 传输通道（"ble"/"spp"/"auto"），由 applyConfig 随 DeviceConfig 下发。 */
    private String transferChannel = "ble";

    public ManagedDeviceInfo(String deviceId, String mac) {
        this.deviceId = deviceId;
        this.mac = mac;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public DeviceState getState() {
        return state;
    }

    public void setState(DeviceState state) {
        this.state = state;
    }

    public PollingConfig getPollingConfig() {
        return pollingConfig;
    }

    public void setPollingConfig(PollingConfig pollingConfig) {
        this.pollingConfig = pollingConfig;
    }

    public long getNextPollTime() {
        return nextPollTime;
    }

    public void setNextPollTime(long nextPollTime) {
        this.nextPollTime = nextPollTime;
    }

    public long getLastPollTime() {
        return lastPollTime;
    }

    public void setLastPollTime(long lastPollTime) {
        this.lastPollTime = lastPollTime;
    }

    public Map<UUID, byte[]> getLastPollResult() {
        return lastPollResult;
    }

    public void setLastPollResult(Map<UUID, byte[]> lastPollResult) {
        this.lastPollResult = copyLastPollResult(lastPollResult);
    }

    public boolean isPollDataStale() {
        return pollDataStale;
    }

    public void setPollDataStale(boolean pollDataStale) {
        this.pollDataStale = pollDataStale;
    }

    public PriorityQueue<QueuedTask> getPendingCommands() {
        return pendingCommands;
    }

    public void setPendingCommands(PriorityQueue<QueuedTask> pendingCommands) {
        this.pendingCommands = pendingCommands;
    }

    public int getMaxPendingCommands() {
        return maxPendingCommands;
    }

    public void setMaxPendingCommands(int maxPendingCommands) {
        this.maxPendingCommands = maxPendingCommands;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    public long getLastConnectedTime() {
        return lastConnectedTime;
    }

    public void setLastConnectedTime(long lastConnectedTime) {
        this.lastConnectedTime = lastConnectedTime;
    }

    public long getDisconnectedTime() {
        return disconnectedTime;
    }

    public void setDisconnectedTime(long disconnectedTime) {
        this.disconnectedTime = disconnectedTime;
    }

    public long getCoolDownUntil() {
        return coolDownUntil;
    }

    public void setCoolDownUntil(long coolDownUntil) {
        this.coolDownUntil = coolDownUntil;
    }

    public int getReconnectCount() {
        return reconnectCount;
    }

    public void setReconnectCount(int reconnectCount) {
        this.reconnectCount = reconnectCount;
    }

    public String getStateFlag() {
        return stateFlag;
    }

    public void setStateFlag(String stateFlag) {
        this.stateFlag = stateFlag;
    }

    public long getNotifyBoostUntil() {
        return notifyBoostUntil;
    }

    public void setNotifyBoostUntil(long notifyBoostUntil) {
        this.notifyBoostUntil = notifyBoostUntil;
    }

    public String getTransferChannel() {
        return transferChannel;
    }

    public void setTransferChannel(String transferChannel) {
        this.transferChannel = transferChannel == null || transferChannel.isEmpty()
                ? "ble" : transferChannel;
    }

    /**
     * 返回当前状态的深拷贝快照。
     */
    public ManagedDeviceInfo snapshot() {
        ManagedDeviceInfo copy = new ManagedDeviceInfo(deviceId, mac);
        copy.state = state;
        copy.pollingConfig = pollingConfig == null ? null : new PollingConfig(pollingConfig);
        copy.nextPollTime = nextPollTime;
        copy.lastPollTime = lastPollTime;
        copy.lastPollResult = copyLastPollResult(lastPollResult);
        copy.pollDataStale = pollDataStale;
        copy.pendingCommands = copyPendingCommands(pendingCommands);
        copy.maxPendingCommands = maxPendingCommands;
        copy.priority = priority;
        copy.persistent = persistent;
        copy.lastConnectedTime = lastConnectedTime;
        copy.disconnectedTime = disconnectedTime;
        copy.coolDownUntil = coolDownUntil;
        copy.reconnectCount = reconnectCount;
        copy.stateFlag = stateFlag;
        copy.notifyBoostUntil = notifyBoostUntil;
        copy.transferChannel = transferChannel;
        return copy;
    }

    private static Map<UUID, byte[]> copyLastPollResult(Map<UUID, byte[]> src) {
        if (src == null) {
            return null;
        }
        Map<UUID, byte[]> copy = new HashMap<>();
        for (Map.Entry<UUID, byte[]> entry : src.entrySet()) {
            byte[] value = entry.getValue();
            copy.put(entry.getKey(), value == null ? null : value.clone());
        }
        return copy;
    }

    private static PriorityQueue<QueuedTask> copyPendingCommands(PriorityQueue<QueuedTask> src) {
        PriorityQueue<QueuedTask> copy = new PriorityQueue<>(
                Comparator.comparingInt((QueuedTask t) -> t.getPriority().ordinal())
                        .thenComparingLong(QueuedTask::getEnqueueTime));
        if (src == null) {
            return copy;
        }
        for (QueuedTask task : src) {
            if (task instanceof GattCommand) {
                copy.offer(new GattCommand((GattCommand) task));
            } else if (task instanceof PollingTask) {
                copy.offer(new PollingTask((PollingTask) task));
            } else {
                copy.offer(task);
            }
        }
        return copy;
    }
}
