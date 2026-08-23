package com.longcheer.agent.registry;

import com.longcheer.agent.ble.DeviceController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动连接池：持有当前占用连接槽的 DeviceController 实例。
 */
public class ActiveConnectionPool {

    private final Map<String, DeviceController> activeControllers = new ConcurrentHashMap<>();

    /**
     * 将设备加入活动连接池。
     */
    public void put(String mac, DeviceController controller) {
        activeControllers.put(mac, controller);
    }

    /**
     * 将设备从活动连接池移除。
     */
    public void remove(String mac) {
        activeControllers.remove(mac);
    }

    /**
     * 按 MAC 查找活动 Controller。
     */
    public DeviceController get(String mac) {
        return activeControllers.get(mac);
    }

    /**
     * @return 当前池中设备数
     */
    public int size() {
        return activeControllers.size();
    }

    /**
     * @return 是否包含该 MAC
     */
    public boolean contains(String mac) {
        return activeControllers.containsKey(mac);
    }

    /**
     * @return 池中所有 Controller 列表（不可变）
     */
    public List<DeviceController> all() {
        return Collections.unmodifiableList(new ArrayList<>(activeControllers.values()));
    }

    /**
     * 清空连接池。
     */
    public void clear() {
        activeControllers.clear();
    }
}
