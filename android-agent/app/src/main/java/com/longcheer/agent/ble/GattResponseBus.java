package com.longcheer.agent.ble;

import com.longcheer.agent.model.GattResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * GATT 读/写响应总线：DeviceController 的 GattClient 回调把结果投进来，
 * {@link GattTransportImpl} 阻塞等待。同一设备同一特征同一操作一次只挂一笔
 * （GATT 操作天然串行，§3.8）。
 */
public class GattResponseBus {

    private final Map<String, CompletableFuture<GattResult>> pending = new ConcurrentHashMap<>();

    private static String key(String mac, UUID charUuid, String op) {
        return mac + "|" + charUuid + "|" + op;
    }

    /** 登记一笔待完成的操作。 */
    public CompletableFuture<GattResult> begin(String mac, UUID charUuid, String op) {
        CompletableFuture<GattResult> future = new CompletableFuture<>();
        pending.put(key(mac, charUuid, op), future);
        return future;
    }

    public void onRead(String mac, UUID charUuid, byte[] value, int status) {
        complete(key(mac, charUuid, "READ"),
                status == 0 ? GattResult.ok(value) : GattResult.fail(status));
    }

    public void onWrite(String mac, UUID charUuid, int status) {
        complete(key(mac, charUuid, "WRITE"),
                status == 0 ? GattResult.ok(null) : GattResult.fail(status));
    }

    /** 设备断开/移除时取消全部挂起操作。 */
    public void cancelAll(String mac) {
        pending.keySet().removeIf(k -> {
            if (k.startsWith(mac + "|")) {
                CompletableFuture<GattResult> f = pending.remove(k);
                if (f != null) {
                    f.complete(GattResult.fail(133)); // 连接已断开
                }
                return true;
            }
            return false;
        });
    }

    // ==================== Notify 分接（文件传输等带外通道，§7.6） ====================

    /** 原始 Notify 监听（不经轮询链解码，如 LC 工厂通道的 ASCII 回包）。 */
    public interface NotifyListener {
        void onNotify(String mac, UUID charUuid, byte[] value);
    }

    private final CopyOnWriteArrayList<NotifyListener> notifyListeners = new CopyOnWriteArrayList<>();

    public void addNotifyListener(NotifyListener listener) {
        if (listener != null) {
            notifyListeners.addIfAbsent(listener);
        }
    }

    public void removeNotifyListener(NotifyListener listener) {
        notifyListeners.remove(listener);
    }

    /** 由 DeviceController 的 GattClient 回调注入；不影响既有 READ/WRITE 完成语义。 */
    public void onNotify(String mac, UUID charUuid, byte[] value) {
        for (NotifyListener listener : notifyListeners) {
            listener.onNotify(mac, charUuid, value);
        }
    }

    /**
     * Notify 订阅（CCCD descriptor 写）结果通路（§7.6：LC 传输握手等订阅完成再协商，
     * 防 CCCD 竞态）。复用完成槽机制，op 键为 "CCCD"。
     */
    public void onNotifySubscribed(String mac, UUID charUuid, int status) {
        complete(key(mac, charUuid, "CCCD"),
                status == 0 ? GattResult.ok(null) : GattResult.fail(status));
    }

    private void complete(String key, GattResult result) {
        CompletableFuture<GattResult> future = pending.remove(key);
        if (future != null) {
            future.complete(result);
        }
    }
}
