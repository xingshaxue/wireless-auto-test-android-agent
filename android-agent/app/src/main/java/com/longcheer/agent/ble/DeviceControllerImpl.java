package com.longcheer.agent.ble;

import android.os.SystemClock;

import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollRule;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.model.PollingTask;

import java.util.Collections;
import java.util.List;

/**
 * DeviceController 基础实现。
 * M1 阶段：维护 ManagedDeviceInfo、状态机与命令队列；真实 BLE 连接与 GATT 操作留 TODO。
 */
public class DeviceControllerImpl implements DeviceController {

    private final ManagedDeviceInfo info;
    private final Object lock = new Object();
    private volatile boolean pendingPause = false;
    private volatile boolean abortTransferOnPause = false;
    private List<PollRule> pollRules = Collections.emptyList();

    public DeviceControllerImpl(String deviceId, String mac) {
        this.info = new ManagedDeviceInfo(deviceId, mac);
    }

    @Override
    public void pause(boolean abortTransfer) {
        synchronized (lock) {
            DeviceState state = info.getState();
            switch (state) {
                case REGISTERED:
                case WAITING_SLOT:
                case READY:
                case DISCONNECTED:
                case ERROR:
                    transitionTo(DeviceState.PAUSED);
                    break;
                case CONNECTING:
                case SERVICE_DISCOVERING:
                case CONFIGURING:
                case POLLING:
                case COMMANDING:
                case RECONNECTING:
                    pendingPause = true;
                    abortTransferOnPause = abortTransfer;
                    break;
                case PAUSED:
                case TERMINATED:
                    // no-op
                    break;
            }
        }
    }

    @Override
    public void resume() {
        synchronized (lock) {
            if (info.getState() != DeviceState.PAUSED) {
                return;
            }
            if (!info.getPendingCommands().isEmpty()) {
                transitionTo(DeviceState.WAITING_SLOT);
            } else {
                transitionTo(DeviceState.REGISTERED);
            }
            pendingPause = false;
            abortTransferOnPause = false;
        }
    }

    @Override
    public void terminate() {
        synchronized (lock) {
            info.getPendingCommands().clear();
            transitionTo(DeviceState.TERMINATED);
        }
    }

    @Override
    public void onSlotAcquired() {
        synchronized (lock) {
            if (info.getState() == DeviceState.WAITING_SLOT || info.getState() == DeviceState.REGISTERED) {
                // §4.1：REGISTERED 不能直达 CONNECTING，须经 WAITING_SLOT。
                if (info.getState() == DeviceState.REGISTERED) {
                    transitionTo(DeviceState.WAITING_SLOT);
                }
                transitionTo(DeviceState.CONNECTING);
                // TODO M1: 真实 connectGatt + 服务发现 + MTU 协商 + 通知订阅。
                // 当前骨架：模拟建连成功进入 READY。
                transitionTo(DeviceState.SERVICE_DISCOVERING);
                transitionTo(DeviceState.CONFIGURING);
                transitionTo(DeviceState.READY);
            }
        }
    }

    @Override
    public void onSlotReleased() {
        synchronized (lock) {
            DeviceState state = info.getState();
            switch (state) {
                case CONNECTING:
                case SERVICE_DISCOVERING:
                case CONFIGURING:
                case READY:
                case POLLING:
                case COMMANDING:
                    // §4.1：以上状态 → DISCONNECTED 均为合法迁移（主动断开/被踢/超预算强释放）。
                    info.setDisconnectedTime(SystemClock.elapsedRealtime());
                    transitionTo(DeviceState.DISCONNECTED);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void enqueueCommand(GattCommand cmd) {
        synchronized (lock) {
            if (info.getState() == DeviceState.TERMINATED) {
                return;
            }
            if (info.getPendingCommands().size() >= info.getMaxPendingCommands()) {
                // TODO M1: 通过 StateReporter 回 3001 QUEUE_FULL。
                return;
            }
            info.getPendingCommands().offer(cmd);
            if (info.getState() == DeviceState.REGISTERED || info.getState() == DeviceState.DISCONNECTED) {
                transitionTo(DeviceState.WAITING_SLOT);
            }
        }
    }

    @Override
    public void enqueuePollTask(PollingTask task) {
        synchronized (lock) {
            if (info.getState() == DeviceState.TERMINATED || info.getState() == DeviceState.PAUSED) {
                return;
            }
            if (info.getPendingCommands().size() >= info.getMaxPendingCommands()) {
                return;
            }
            info.getPendingCommands().offer(task);
            if (info.getState() == DeviceState.REGISTERED || info.getState() == DeviceState.DISCONNECTED) {
                transitionTo(DeviceState.WAITING_SLOT);
            }
        }
    }

    @Override
    public void setPollingConfig(PollingConfig config) {
        synchronized (lock) {
            info.setPollingConfig(config);
        }
    }

    @Override
    public void setPollRules(List<PollRule> rules) {
        synchronized (lock) {
            this.pollRules = rules == null ? Collections.emptyList() : Collections.unmodifiableList(rules);
        }
    }

    @Override
    public boolean isReady() {
        synchronized (lock) {
            return info.getState() == DeviceState.READY;
        }
    }

    @Override
    public DeviceState getState() {
        synchronized (lock) {
            return info.getState();
        }
    }

    @Override
    public ManagedDeviceInfo snapshot() {
        synchronized (lock) {
            // M1: 返回内部引用（骨架阶段），后续改为深拷贝不可变快照。
            return info;
        }
    }

    public ManagedDeviceInfo getInfo() {
        synchronized (lock) {
            return info;
        }
    }

    Object getLock() {
        return lock;
    }

    private void transitionTo(DeviceState newState) {
        // M1: 简化状态迁移，不严格校验迁移表；M2/M3 补充状态机校验。
        DeviceState old = info.getState();
        info.setState(newState);
        if (newState == DeviceState.READY) {
            info.setLastConnectedTime(SystemClock.elapsedRealtime());
            drainPendingTasks();
        }
        // TODO M1: 通过 StateReporter 上报 DEVICE_STATE。
    }

    private void drainPendingTasks() {
        // M1: 留待 GattExecutor 串行执行；当前不取出队列，避免任务丢失。
        // TODO M1: 从 pendingCommands 按优先级取任务，逐个提交给 GattExecutor。
    }
}
