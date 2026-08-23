package com.longcheer.agent.registry;

import com.longcheer.agent.ble.DeviceController;
import com.longcheer.agent.model.DeviceState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * DeviceRegistry 默认实现。
 */
public class DeviceRegistryImpl implements DeviceRegistry {

    private final Map<String, DeviceController> controllersByMac = new ConcurrentHashMap<>();
    private final Map<String, String> deviceIdToMac = new ConcurrentHashMap<>();

    @Override
    public void register(DeviceController controller) {
        String mac = controller.snapshot().getMac();
        String deviceId = controller.snapshot().getDeviceId();
        controllersByMac.put(mac, controller);
        if (deviceId != null && !deviceId.isEmpty()) {
            deviceIdToMac.put(deviceId, mac);
        }
    }

    @Override
    public void unregister(String mac) {
        DeviceController removed = controllersByMac.remove(mac);
        if (removed != null) {
            String deviceId = removed.snapshot().getDeviceId();
            if (deviceId != null) {
                deviceIdToMac.remove(deviceId);
            }
        }
    }

    @Override
    public DeviceController findByMac(String mac) {
        return controllersByMac.get(mac);
    }

    @Override
    public DeviceController findByDeviceId(String deviceId) {
        String mac = deviceIdToMac.get(deviceId);
        return mac == null ? null : controllersByMac.get(mac);
    }

    @Override
    public List<DeviceController> findByState(DeviceState state) {
        return controllersByMac.values().stream()
                .filter(c -> c.getState() == state)
                .collect(Collectors.toList());
    }

    @Override
    public List<DeviceController> findAllOrderByPriorityDesc() {
        List<DeviceController> list = new ArrayList<>(controllersByMac.values());
        list.sort(Comparator.comparingInt((DeviceController c) -> c.snapshot().getPriority()).reversed());
        return list;
    }

    @Override
    public List<String> allMacs() {
        return Collections.unmodifiableList(new ArrayList<>(controllersByMac.keySet()));
    }

    @Override
    public List<DeviceController> allControllers() {
        return Collections.unmodifiableList(new ArrayList<>(controllersByMac.values()));
    }

    @Override
    public int size() {
        return controllersByMac.size();
    }

    @Override
    public void clear() {
        controllersByMac.clear();
        deviceIdToMac.clear();
    }
}
