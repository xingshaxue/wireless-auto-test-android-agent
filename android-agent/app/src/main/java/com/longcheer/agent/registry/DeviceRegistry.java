package com.longcheer.agent.registry;

import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;

import java.util.List;

/**
 * 设备注册表接口（SDD §3.3 / §16.1）。
 */
public interface DeviceRegistry {

    void register(DeviceController controller);

    void unregister(String mac);

    DeviceController findByMac(String mac);

    DeviceController findByDeviceId(String deviceId);

    List<DeviceController> findByState(DeviceState state);

    List<DeviceController> findAllOrderByPriorityDesc();

    List<String> allMacs();

    List<DeviceController> allControllers();

    int size();

    void clear();
}
