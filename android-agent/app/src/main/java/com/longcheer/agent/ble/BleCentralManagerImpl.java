package com.longcheer.agent.ble;

import android.bluetooth.BluetoothAdapter;

import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.registry.DeviceRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BleCentralManager 基础实现。
 * M1 阶段：持有 BluetoothAdapter 引用，管理 DeviceController 映射；真实 GATT 连接留 TODO。
 */
public class BleCentralManagerImpl implements BleCentralManager {

    private final BluetoothAdapter bluetoothAdapter;
    private final DeviceRegistry deviceRegistry;
    private final Map<String, DeviceController> controllers = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    public BleCentralManagerImpl(BluetoothAdapter bluetoothAdapter, DeviceRegistry deviceRegistry) {
        this.bluetoothAdapter = bluetoothAdapter;
        this.deviceRegistry = deviceRegistry;
    }

    @Override
    public void init() {
        initialized = true;
        // TODO M1: 初始化 GattExecutor、扫描过滤器等；真实蓝牙权限检查在 isBleAvailable。
    }

    @Override
    public boolean isBleAvailable() {
        return initialized && bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @Override
    public DeviceController createController(String mac, String deviceId) {
        if (mac == null || mac.isEmpty()) {
            throw new IllegalArgumentException("MAC must not be empty");
        }
        DeviceController existing = controllers.get(mac);
        if (existing != null) {
            return existing;
        }
        DeviceController controller = new DeviceControllerImpl(deviceId, mac);
        controllers.put(mac, controller);
        deviceRegistry.register(controller);
        return controller;
    }

    @Override
    public void destroyController(String mac) {
        DeviceController controller = controllers.remove(mac);
        if (controller != null) {
            controller.terminate();
            deviceRegistry.unregister(mac);
        }
    }

    @Override
    public int supportedMaxConnections() {
        // M1: 返回保守默认值；后续按机型实测校准。
        return isBleAvailable() ? 5 : 0;
    }

    /**
     * 按 MAC 查找已创建的 Controller（包内使用）。
     */
    DeviceController getController(String mac) {
        return controllers.get(mac);
    }
}
