package com.longcheer.agent.ble;

import com.longcheer.agent.log.AgentLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 虚拟 DUT（模拟器/CI 测试用，仅 simulateDut=true 时激活；不影响生产路径）。
 *
 * <p>内置 §16.4 示例 GATT 档案：</p>
 * <ul>
 *   <li>180F/2A19 battery：uint8，初始 85；</li>
 *   <li>180A/2A21 temperature：sint16 LE（值为 ℃×10），订阅后每秒在 39.0↔42.0 间振荡
 *       （跨越示例规则 40℃ 阈值，可观测 TEMP_CRITICAL 规则触发）；</li>
 *   <li>180A/2A24 status：utf8 "IDLE"，可写。</li>
 * </ul>
 *
 * <p>行为模拟真机主流表现：MTU 请求接受 247（不走 512）；连接/发现/订阅均成功；
 * 断连回调正常触发。</p>
 */
public class SimulatedGattClient implements GattClient {

    private static final String TAG = "SimulatedGattClient";

    public static final UUID SERVICE_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID SERVICE_INFO = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_BATTERY = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_TEMPERATURE = UUID.fromString("00002a21-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_STATUS = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");

    /** 通知周期（温度振荡）。 */
    static final long NOTIFY_PERIOD_MS = 1000L;

    private static final ScheduledExecutorService NOTIFY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SimDutNotify");
                t.setDaemon(true);
                return t;
            });

    private final String mac;
    private final Callback callback;
    private final Executor callbackExecutor;

    private byte[] battery = new byte[]{85};
    private byte[] temperature = sint16Le(390);   // 39.0 ℃
    private byte[] status = "IDLE".getBytes(StandardCharsets.UTF_8);
    private boolean connected = false;
    private boolean tempNotifyOn = false;
    private boolean tempHigh = false;
    private ScheduledFuture<?> notifyTask;

    public SimulatedGattClient(String mac, Callback callback, Executor callbackExecutor) {
        this.mac = mac;
        this.callback = callback;
        this.callbackExecutor = callbackExecutor;
    }

    @Override
    public void connect() {
        callbackExecutor.execute(() -> {
            connected = true;
            callback.onConnected();
        });
    }

    @Override
    public void disconnectAndClose() {
        stopNotifyTask();
        callbackExecutor.execute(() -> {
            if (connected) {
                connected = false;
                callback.onDisconnected(0);
            }
        });
    }

    @Override
    public void discoverServices() {
        callbackExecutor.execute(() -> callback.onServicesDiscovered(0));
    }

    @Override
    public void requestMtu(int mtu) {
        // 模拟中端机型：一律协商到 247（§3.2 阶梯的中间档）。
        callbackExecutor.execute(() -> callback.onMtuChanged(Math.min(mtu, 247), 0));
    }

    @Override
    public void readCharacteristic(UUID serviceUuid, UUID charUuid) {
        callbackExecutor.execute(() -> {
            byte[] value = valueOf(charUuid);
            callback.onRead(charUuid, value == null ? null : value.clone(), value == null ? -1 : 0);
        });
    }

    @Override
    public void writeCharacteristic(UUID serviceUuid, UUID charUuid, byte[] payload, boolean noResponse) {
        callbackExecutor.execute(() -> {
            if (CHAR_STATUS.equals(charUuid) && payload != null) {
                status = payload.clone();
                AgentLog.i(TAG, mac + " status written: " + new String(payload, StandardCharsets.UTF_8));
            } else if (CHAR_BATTERY.equals(charUuid) && payload != null && payload.length > 0) {
                battery = payload.clone();
            }
            callback.onWrite(charUuid, 0);
        });
    }

    @Override
    public void setNotification(UUID serviceUuid, UUID charUuid, boolean enable) {
        callbackExecutor.execute(() -> {
            if (CHAR_TEMPERATURE.equals(charUuid)) {
                tempNotifyOn = enable;
                if (enable) {
                    startNotifyTask();
                } else {
                    stopNotifyTask();
                }
            }
            callback.onNotifySubscribed(charUuid, 0);
        });
    }

    /** 温度通知：每秒在 39.0 ↔ 42.0 间振荡（触发 20/40 两档规则）。 */
    private void startNotifyTask() {
        stopNotifyTask();
        notifyTask = NOTIFY_EXECUTOR.scheduleAtFixedRate(() -> {
            tempHigh = !tempHigh;
            temperature = sint16Le(tempHigh ? 420 : 390);
            callbackExecutor.execute(() -> callback.onNotify(CHAR_TEMPERATURE, temperature.clone()));
        }, NOTIFY_PERIOD_MS, NOTIFY_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void stopNotifyTask() {
        if (notifyTask != null) {
            notifyTask.cancel(false);
            notifyTask = null;
        }
    }

    private byte[] valueOf(UUID charUuid) {
        if (CHAR_BATTERY.equals(charUuid)) {
            return battery;
        }
        if (CHAR_TEMPERATURE.equals(charUuid)) {
            return temperature;
        }
        if (CHAR_STATUS.equals(charUuid)) {
            return status;
        }
        return null;
    }

    private static byte[] sint16Le(int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
    }

    /** 测试观测用。 */
    public boolean isTempNotifyOn() {
        return tempNotifyOn;
    }
}
