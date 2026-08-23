package com.longcheer.agent.ble;

/**
 * BLE 中心管理器接口（SDD §3.2 / §16.1）。
 */
public interface BleCentralManager {

    void init();

    boolean isBleAvailable();

    DeviceController createController(String mac, String deviceId);

    void destroyController(String mac);

    int supportedMaxConnections();
}
