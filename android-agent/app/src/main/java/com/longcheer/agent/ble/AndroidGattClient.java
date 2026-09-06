package com.longcheer.agent.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;

import com.longcheer.agent.log.AgentLog;

import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * {@link GattClient} 的 Android 真实实现：BluetoothDevice.connectGatt 薄封装。
 *
 * <p>不含业务逻辑（状态机/重试/调度全在 DeviceControllerImpl 侧），仅做
 * Android 回调 → {@link Callback} 的映射。属真机验证层，JVM 单测不覆盖。</p>
 */
@SuppressLint("MissingPermission") // 权限检查在 AgentService.checkSystemConstraints / isBleAvailable
public class AndroidGattClient implements GattClient {

    private static final String TAG = "AndroidGattClient";

    /** CCC descriptor 标准 UUID（通知订阅开关）。 */
    private static final UUID CCC_DESCRIPTOR =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BluetoothDevice device;
    private final Callback callback;
    private final Executor callbackExecutor;
    private final String mac;

    private volatile BluetoothGatt gatt;
    private volatile UUID pendingNotifyChar;

    public AndroidGattClient(Context context, BluetoothDevice device, Callback callback,
                             Executor callbackExecutor) {
        this.context = context;
        this.device = device;
        this.callback = callback;
        this.callbackExecutor = callbackExecutor;
        this.mac = device.getAddress();
    }

    @Override
    public void connect() {
        if (gatt != null) {
            return;
        }
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    @Override
    public void disconnectAndClose() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            g.disconnect();
            g.close();
        }
    }

    @Override
    public void discoverServices() {
        BluetoothGatt g = gatt;
        if (g != null) {
            g.discoverServices();
        }
    }

    @Override
    public void requestMtu(int mtu) {
        BluetoothGatt g = gatt;
        if (g != null) {
            g.requestMtu(mtu);
        }
    }

    @Override
    public void readCharacteristic(UUID serviceUuid, UUID charUuid) {
        BluetoothGattCharacteristic c = findChar(serviceUuid, charUuid);
        BluetoothGatt g = gatt;
        if (g == null || c == null) {
            post(() -> callback.onRead(charUuid, null, -1));
            return;
        }
        g.readCharacteristic(c);
    }

    @Override
    public void writeCharacteristic(UUID serviceUuid, UUID charUuid, byte[] payload, boolean noResponse) {
        BluetoothGattCharacteristic c = findChar(serviceUuid, charUuid);
        BluetoothGatt g = gatt;
        if (g == null || c == null) {
            post(() -> callback.onWrite(charUuid, -1));
            return;
        }
        c.setValue(payload == null ? new byte[0] : payload);
        c.setWriteType(noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        g.writeCharacteristic(c);
    }

    @Override
    public void setNotification(UUID serviceUuid, UUID charUuid, boolean enable) {
        BluetoothGattCharacteristic c = findChar(serviceUuid, charUuid);
        BluetoothGatt g = gatt;
        if (g == null || c == null) {
            post(() -> callback.onNotifySubscribed(charUuid, -1));
            return;
        }
        g.setCharacteristicNotification(c, enable);
        BluetoothGattDescriptor ccc = c.getDescriptor(CCC_DESCRIPTOR);
        if (ccc == null) {
            post(() -> callback.onNotifySubscribed(charUuid, -1));
            return;
        }
        pendingNotifyChar = charUuid;
        ccc.setValue(enable ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        g.writeDescriptor(ccc);
    }

    private BluetoothGattCharacteristic findChar(UUID serviceUuid, UUID charUuid) {
        BluetoothGatt g = gatt;
        if (g == null) {
            return null;
        }
        BluetoothGattService service = serviceUuid == null ? null : g.getService(serviceUuid);
        if (service != null) {
            return service.getCharacteristic(charUuid);
        }
        // serviceUuid 为空时遍历服务兜底（profile 缺失场景由上层先按 16.4 解析）。
        for (BluetoothGattService s : g.getServices()) {
            BluetoothGattCharacteristic c = s.getCharacteristic(charUuid);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private void post(Runnable r) {
        callbackExecutor.execute(r);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            AgentLog.d(TAG, mac + " connState status=" + status + " newState=" + newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                post(callback::onConnected);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                post(() -> callback.onDisconnected(status));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            post(() -> callback.onServicesDiscovered(status));
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            post(() -> callback.onMtuChanged(mtu, status));
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            byte[] value = c.getValue();
            post(() -> callback.onRead(c.getUuid(), value, status));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            post(() -> callback.onWrite(c.getUuid(), status));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            post(() -> callback.onNotify(c.getUuid(), value));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            UUID ch = pendingNotifyChar;
            post(() -> callback.onNotifySubscribed(ch, status));
        }
    };
}
