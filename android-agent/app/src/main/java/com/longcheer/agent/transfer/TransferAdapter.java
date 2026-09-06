package com.longcheer.agent.transfer;

import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.FileTransferTask;

/**
 * DUT 传输协议适配器（SDD §7.6 / §16.1）：分块、CRC、窗口 ACK、续传逻辑与具体
 * DUT 协议解耦。实现侧对接 GATT 写入（真机 BLE 里程碑）；引擎按同步语义调用。
 *
 * <p>相对 §16.1 的调整：窗口 ACK 由引擎侧 {@link #waitWindowAck} 阻塞等待
 * （对齐 §16.5 伪代码的 waitAck(timeout)）；真实 BLE 实现内部用通知回调桥接。</p>
 */
public interface TransferAdapter {

    /** 窗口 ACK 结果。 */
    enum WindowAck {
        OK,      // CRC 通过，窗口内块全部确认
        NAK,     // CRC 错误，需重发窗口内块
        TIMEOUT  // 超时无响应
    }

    /** 传输协议异常（写入失败/握手拒绝等）。 */
    class TransferException extends Exception {
        public TransferException(String message) {
            super(message);
        }
    }

    /**
     * 传输前握手（带响应写）：文件大小、总块数、CRC 类型、写入特征、起始偏移。
     * 起始偏移 = task.getTransferredOffset()（断点续传，前提 {@link #supportsOffsetWrite()}）。
     */
    void handshake(FileTransferTask task, DeviceController device) throws TransferException;

    /**
     * 发送一块（WRITE_NO_RESPONSE，吞吐前提 §7.6）。
     *
     * @param seq 块序号，从 1 起连续编号（与 §16.3 帧编号口径一致）
     */
    void sendChunk(byte[] chunk, int seq) throws TransferException;

    /**
     * 阻塞等待窗口 ACK（超时上限由参数给定，如 30s）。
     */
    WindowAck waitWindowAck(int windowSeq, long timeoutMs);

    /** DUT 是否支持偏移写入（决定断点续传还是从头重传）。 */
    boolean supportsOffsetWrite();
}
