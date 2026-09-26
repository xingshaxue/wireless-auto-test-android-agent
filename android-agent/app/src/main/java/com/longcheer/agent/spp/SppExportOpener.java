package com.longcheer.agent.spp;

import com.longcheer.agent.ble.GattClient;
import com.longcheer.agent.ble.GattClientProvider;
import com.longcheer.agent.ble.GattResponseBus;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.GattResult;
import com.longcheer.agent.transfer.LcProtoTransferAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * SPP 导出通道开启器：与 {@link SppTransferAdapter} 同一前置序列——经 BLE LC
 * 通道开经典蓝牙（{@code 00AT^BT_ENABLE} / {@code 00AT^BT_ACCESS_SET=3}，已开
 * 过时固件幂等回 OK）→ insecure RFCOMM 建连 → 产出 {@link SppByteStream}
 * （二进制块通道，承载 061/062/063 导出协议）。
 *
 * <p>每次 open 全新实例使用；BLE Notify 分接在开通道完成后解除。</p>
 */
public class SppExportOpener {

    private static final String TAG = "SppExportOpener";

    static final long AT_TIMEOUT_MS = 5000L;
    static final long CCCD_TIMEOUT_MS = 3000L;

    /** 开经典蓝牙 AT 命令（P67 FACTEST 真机校准序列）。 */
    static final String AT_BT_ENABLE = "00AT^BT_ENABLE";
    static final String AT_BT_ACCESS_SET = "00AT^BT_ACCESS_SET=3";

    public static final class OpenException extends Exception {
        public OpenException(String message) {
            super(message);
        }
    }

    private final GattClientProvider clientProvider;
    private final GattResponseBus responseBus;
    private final SppClient.Connector connector;

    /** BLE AT 回包邮箱（LC 特征 Notify，已 trim）。 */
    private final BlockingQueue<String> bleMailbox = new LinkedBlockingQueue<>();
    private GattResponseBus.NotifyListener notifyListener;
    private String mac;

    long atTimeoutMs = AT_TIMEOUT_MS;
    long cccdTimeoutMs = CCCD_TIMEOUT_MS;

    public SppExportOpener(GattClientProvider clientProvider, GattResponseBus responseBus,
                           SppClient.Connector connector) {
        this.clientProvider = clientProvider;
        this.responseBus = responseBus;
        this.connector = connector;
    }

    /** 开经典蓝牙 + RFCOMM 建连，返回已连接的 SPP 字节流通道。 */
    public SppByteStream open(String deviceMac) throws OpenException {
        this.mac = deviceMac;
        GattClient bleClient = clientProvider.getActiveClient(deviceMac);
        if (bleClient == null) {
            throw new OpenException("no active gatt client for " + deviceMac);
        }
        registerNotifyTap();
        try {
            CompletableFuture<GattResult> cccd = responseBus.begin(
                    deviceMac, LcProtoTransferAdapter.LC_CHAR_UUID, "CCCD");
            bleClient.setNotification(LcProtoTransferAdapter.LC_SERVICE_UUID,
                    LcProtoTransferAdapter.LC_CHAR_UUID, true);
            GattResult sub = await(cccd, cccdTimeoutMs);
            if (sub == null || !sub.isSuccess()) {
                throw new OpenException("CCCD subscribe failed: "
                        + (sub == null ? "timeout" : "status=" + sub.getStatus()));
            }
            enableClassicBt(bleClient, AT_BT_ENABLE);
            enableClassicBt(bleClient, AT_BT_ACCESS_SET);
        } finally {
            unregisterNotifyTap();
        }
        SppByteStream stream = new SppByteStream(connector);
        try {
            stream.connect(deviceMac);
        } catch (IOException e) {
            throw new OpenException("spp connect failed: " + e.getMessage());
        }
        return stream;
    }

    private void enableClassicBt(GattClient bleClient, String atCommand) throws OpenException {
        bleMailbox.clear();
        bleClient.writeCharacteristic(LcProtoTransferAdapter.LC_SERVICE_UUID,
                LcProtoTransferAdapter.LC_CHAR_UUID,
                atCommand.getBytes(StandardCharsets.US_ASCII), true);
        // 过滤非 AT 回包：BLE LC 通道与导出/LS 共用，固件可能在应答窗口内
        // 推来 LS 条目/导出残留（真机实测 "F /data/..." 被误读为 AT 应答）。
        long deadline = System.currentTimeMillis() + atTimeoutMs;
        while (true) {
            String resp = awaitBle(Math.max(1, deadline - System.currentTimeMillis()));
            if (resp == null) {
                throw new OpenException("AT timeout: " + atCommand);
            }
            if (resp.contains("OK")) {
                AgentLog.i(TAG, atCommand + " -> " + resp);
                return;
            }
            if (resp.contains("ERROR") || resp.contains("error")) {
                throw new OpenException("AT rejected: " + atCommand + " -> " + resp);
            }
            AgentLog.d(TAG, "ignore non-AT reply while waiting " + atCommand + ": " + resp);
        }
    }

    private void registerNotifyTap() {
        notifyListener = (notifyMac, charUuid, value) -> {
            if (!LcProtoTransferAdapter.LC_CHAR_UUID.equals(charUuid)
                    || !notifyMac.equals(mac)) {
                return;
            }
            if (value != null && value.length > 0) {
                bleMailbox.offer(new String(value, StandardCharsets.US_ASCII).trim());
            }
        };
        responseBus.addNotifyListener(notifyListener);
    }

    private void unregisterNotifyTap() {
        if (notifyListener != null) {
            responseBus.removeNotifyListener(notifyListener);
            notifyListener = null;
        }
    }

    private String awaitBle(long timeoutMs) {
        try {
            return bleMailbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static GattResult await(CompletableFuture<GattResult> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
