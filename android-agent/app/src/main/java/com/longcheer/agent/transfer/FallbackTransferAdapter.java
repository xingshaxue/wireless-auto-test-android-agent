package com.longcheer.agent.transfer;

import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.FileTransferTask;

import java.util.function.Supplier;

/**
 * 通道自动回退适配器（DeviceConfig.transferChannel = "auto"）：先跑主通道
 * （SPP）握手，握手抛 {@link TransferException} 则关闭主通道、改用回退通道
 * （BLE LC）重新握手；握手成功后全部调用委托给活跃适配器。
 *
 * <p>回退只发生在握手阶段：数据传输中的失败由引擎按断线语义重建适配器重跑
 * 握手，届时仍从主通道开始尝试。</p>
 */
public class FallbackTransferAdapter implements TransferAdapter {

    private static final String TAG = "FallbackTransfer";

    private final TransferAdapter primary;
    private final Supplier<TransferAdapter> fallbackFactory;
    private TransferAdapter active;

    public FallbackTransferAdapter(TransferAdapter primary, Supplier<TransferAdapter> fallbackFactory) {
        this.primary = primary;
        this.fallbackFactory = fallbackFactory;
        this.active = primary;
    }

    @Override
    public void handshake(FileTransferTask task, DeviceController device) throws TransferException {
        try {
            primary.handshake(task, device);
            active = primary;
            return;
        } catch (TransferException e) {
            AgentLog.w(TAG, "primary channel handshake failed: " + e.getMessage()
                    + ", fallback to ble");
        }
        try {
            primary.close();
        } catch (RuntimeException e) {
            AgentLog.w(TAG, "primary close failed: " + e.getMessage());
        }
        // 主通道握手可能已改写 task 分块参数/偏移，回退通道握手会按其协议重新改写。
        TransferAdapter fallback = fallbackFactory.get();
        fallback.handshake(task, device);
        active = fallback;
    }

    @Override
    public void sendChunk(byte[] chunk, int seq) throws TransferException {
        active.sendChunk(chunk, seq);
    }

    @Override
    public WindowAck waitWindowAck(int windowSeq, long timeoutMs) {
        return active.waitWindowAck(windowSeq, timeoutMs);
    }

    @Override
    public boolean supportsOffsetWrite() {
        // 引擎在握手前查询；SPP/LC 均支持偏移写入，恒 true。
        return true;
    }

    @Override
    public void finish(FileTransferTask task, DeviceController device) throws TransferException {
        active.finish(task, device);
    }

    @Override
    public void close() {
        try {
            primary.close();
        } catch (RuntimeException e) {
            AgentLog.w(TAG, "primary close failed: " + e.getMessage());
        }
        if (active != primary) {
            try {
                active.close();
            } catch (RuntimeException e) {
                AgentLog.w(TAG, "fallback close failed: " + e.getMessage());
            }
        }
    }
}
