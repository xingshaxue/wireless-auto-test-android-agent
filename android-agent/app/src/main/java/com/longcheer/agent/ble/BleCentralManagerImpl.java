package com.longcheer.agent.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.registry.DeviceRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * BleCentralManager 实现（SDD §3.2）：按 MAC 直连 DUT（不依赖扫描）、
 * 活动 GATT 客户端注册表、手机 BLE 能力检查。
 *
 * <p>两种构造：带 {@link DeviceControllerDeps} 模板（真实模式，createController 产出
 * 真实控制器）；不带则产出模拟模式控制器（M1 骨架，兼容既有测试）。</p>
 */
public class BleCentralManagerImpl implements BleCentralManager, DeviceControllerDeps.GattClientFactory,
        GattClientProvider {

    private static final String TAG = "BleCentralManager";

    private final Context context;           // 真实模式必需；模拟模式可为 null
    private final BluetoothAdapter bluetoothAdapter;
    private final DeviceRegistry deviceRegistry;
    private volatile DeviceControllerDeps depsTemplate; // null = 模拟模式
    private final Map<String, DeviceController> controllers = new ConcurrentHashMap<>();
    private final Map<String, GattClient> activeClients = new ConcurrentHashMap<>();
    /** BLE 回调派发线程（§9：回调不落在 Binder 线程）。 */
    private final Executor callbackExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "BleCallback");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean initialized = false;

    public BleCentralManagerImpl(BluetoothAdapter bluetoothAdapter, DeviceRegistry deviceRegistry) {
        this(null, bluetoothAdapter, deviceRegistry, null);
    }

    public BleCentralManagerImpl(Context context, BluetoothAdapter bluetoothAdapter,
                                 DeviceRegistry deviceRegistry, DeviceControllerDeps depsTemplate) {
        this.context = context;
        this.bluetoothAdapter = bluetoothAdapter;
        this.deviceRegistry = deviceRegistry;
        this.depsTemplate = depsTemplate;
    }

    @Override
    public void init() {
        initialized = true;
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
        // §11.2 MAC 白名单：只接受服务器下发的 MAC（本方法仅被命令/配置路径调用）；
        // 不合法格式直接拒绝，防御扫描结果误入。
        if (!mac.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) {
            throw new IllegalArgumentException("invalid MAC format: " + mac);
        }
        DeviceControllerImpl controller = new DeviceControllerImpl(deviceId, mac, depsTemplate);
        controllers.put(mac, controller);
        deviceRegistry.register(controller);
        AgentLog.i(TAG, "controller created: " + mac + " (" + deviceId + ")");
        return controller;
    }

    @Override
    public void destroyController(String mac) {
        DeviceController controller = controllers.remove(mac);
        if (controller != null) {
            controller.terminate();
            deviceRegistry.unregister(mac);
        }
        GattClient client = activeClients.remove(mac);
        if (client != null) {
            client.disconnectAndClose();
        }
    }

    @Override
    public int supportedMaxConnections() {
        // 按机型实测校准（§15）；当前返回保守上限。
        return isBleAvailable() ? 5 : 0;
    }

    /** 装配层补注依赖模板（打破 bleManager↔deps 构造环）。 */
    public void setDepsTemplate(DeviceControllerDeps deps) {
        this.depsTemplate = deps;
    }

    // ==================== GattClientFactory / GattClientProvider ====================

    @Override
    public GattClient create(String mac, GattClient.Callback callback) {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mac);
        GattClient client = new AndroidGattClient(context, device, callback, callbackExecutor);
        activeClients.put(mac, client);
        return client;
    }

    @Override
    public void removeClient(String mac) {
        activeClients.remove(mac);
    }

    @Override
    public GattClient getActiveClient(String mac) {
        return activeClients.get(mac);
    }

    /**
     * 按 MAC 查找已创建的 Controller（包内使用）。
     */
    DeviceController getController(String mac) {
        return controllers.get(mac);
    }
}
