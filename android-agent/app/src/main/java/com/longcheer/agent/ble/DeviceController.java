package com.longcheer.agent.ble;

import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollRule;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.model.PollingTask;

import java.util.List;

/**
 * 受管设备对象接口（SDD §3.7 / §16.1）。
 */
public interface DeviceController {

    void pause(boolean abortTransfer);

    void resume();

    void terminate();

    void onSlotAcquired();

    void onSlotReleased();

    void enqueueCommand(GattCommand cmd);

    void enqueuePollTask(PollingTask task);

    void setPollingConfig(PollingConfig config);

    void setPollRules(List<PollRule> rules);

    boolean isReady();

    DeviceState getState();

    ManagedDeviceInfo snapshot();
}
