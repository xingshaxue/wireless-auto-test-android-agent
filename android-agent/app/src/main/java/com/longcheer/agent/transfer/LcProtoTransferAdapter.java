package com.longcheer.agent.transfer;

import com.longcheer.agent.ble.GattClient;
import com.longcheer.agent.ble.GattClientProvider;
import com.longcheer.agent.ble.GattResponseBus;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.FileTransferTask;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * LC 工厂通道（lc_proto）文件传输/OTA 适配器——小米手环 10 Pro（p67/BES1503，Vela）。
 * 协议事实来源：docs/02-ota-file-transfer.md 通道 B（B.1~B.5、B.7）。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>GATT 承载（B.1）：service/char 见常量，写入一律 WRITE_NO_RESPONSE，
 *       回包一律 Notify ASCII；</li>
 *   <li>分块（B.3）：4480B 数据 + 4B CRC32 小端（CRC-32/ISO-HDLC，即 java.util.zip.CRC32），
 *       每块独立确认（"310"/"311"），窗口恒为 1——握手时改写 task 的 chunkSize/windowSize；</li>
 *   <li>断点续传（B.4）：协商 retrans='1' 时固件回执
 *       "open file success:retransmission start length:%lld" 的数字为权威偏移，回写 task；</li>
 *   <li>收尾校验（B.2）：数据全部确认后写 `32` 等 `"320"`（由 {@link #finish} 完成）；</li>
 *   <li>OTA 触发命令 `00AT^OTA_UPDATE`（B.5）不在本适配器内，属场景层 WRITE_CHAR 步骤。</li>
 * </ul>
 *
 * <p>实例为单任务一次性使用：引擎在暂停/断线后丢弃并重建适配器（重新握手续传）；
 * Notify 监听在 {@link #close()} 解除（引擎会话结束/废弃时调用，幂等）。</p>
 */
public class LcProtoTransferAdapter implements TransferAdapter {

    private static final String TAG = "LcProtoTransfer";

    /** LC 通道 GATT 承载（B.1）。 */
    public static final UUID LC_SERVICE_UUID =
            UUID.fromString("1b7e8251-2877-41c3-b46e-cf057c562023");
    public static final UUID LC_CHAR_UUID =
            UUID.fromString("8ac32d3f-5cb9-4d44-bec2-ee689169f626");

    /** 块数据长度（B.3：4480 数据 + 4 CRC = 4484 一帧）。 */
    public static final int LC_BLOCK_DATA_SIZE = 4480;
    /** 单包写入长度（B.3：224 字节/包，按 MTU≈227 设计）。 */
    static final int LC_WRITE_PACKET_SIZE = 224;

    /** 协商/打开/收尾应答等待上限（块 ACK 用引擎传入的更长超时，§12.8）。 */
    static final long HANDSHAKE_TIMEOUT_MS = 5000L;
    static final long FINISH_TIMEOUT_MS = 10000L;

    /** 续传回执前缀（B.4），数字为固件侧已收字节数。 */
    private static final String RETRANS_PREFIX = "open file success:retransmission start length:";

    private final GattClientProvider clientProvider;
    private final GattResponseBus responseBus;

    private String mac;
    private GattClient client;
    private GattResponseBus.NotifyListener notifyListener;
    private volatile boolean closed = false;

    /** 固件 ASCII 回包邮箱（Notify 回调线程投入，会话线程阻塞取）。 */
    private final BlockingQueue<String> mailbox = new LinkedBlockingQueue<>();

    public LcProtoTransferAdapter(GattClientProvider clientProvider, GattResponseBus responseBus) {
        this.clientProvider = clientProvider;
        this.responseBus = responseBus;
    }

    @Override
    public synchronized void handshake(FileTransferTask task, DeviceController device)
            throws TransferException {
        mac = device.snapshot().getMac();
        client = clientProvider.getActiveClient(mac);
        if (client == null) {
            throw new TransferException("no active gatt client for " + mac);
        }
        registerNotifyTap();
        // B.1：回包走 Notify，先订阅 CCCD（0x2902，由 GattClient 实现内部处理）。
        client.setNotification(LC_SERVICE_UUID, LC_CHAR_UUID, true);

        // LC 通道每块独立 CRC+确认：块 4480B、窗口恒 1（B.3）。
        task.setChunkSize(LC_BLOCK_DATA_SIZE);
        task.setWindowSize(1);

        // B.2 参数协商：retrans='1' 支持断点续传（有既有偏移时）。
        boolean resume = task.getTransferredOffset() > 0;
        writeAscii("30" + (resume ? "1" : "0"));
        String resp = awaitAscii(HANDSHAKE_TIMEOUT_MS);
        if (!"300".equals(resp)) {
            throw new TransferException("negotiate rejected: " + resp);
        }

        // B.2/B.7：通知开始传输；filePath 带 .bin 后缀被固件标记为 OTA 文件。
        writeAscii("33 OTA,/data/" + task.getFileId() + "," + task.getTotalSize());
        long deadline = nowMs() + HANDSHAKE_TIMEOUT_MS;
        while (true) {
            resp = awaitAscii(Math.max(1, deadline - nowMs()));
            if (resp == null) {
                throw new TransferException("open file timeout");
            }
            if ("330".equals(resp)) {
                return; // 打开成功
            }
            if (resp.startsWith(RETRANS_PREFIX)) {
                // B.4：固件回执的 length 为权威续传偏移（应为整块倍数），回写 task。
                long offset = parseLong(resp.substring(RETRANS_PREFIX.length()), -1);
                if (offset < 0) {
                    throw new TransferException("bad retransmission offset: " + resp);
                }
                if (offset != task.getTransferredOffset()) {
                    AgentLog.i(TAG, "retransmission offset override: " + task.getTransferredOffset()
                            + " -> " + offset);
                    task.setTransferredOffset(offset);
                }
                return;
            }
            if ("331".equals(resp) || resp.startsWith("open error")) {
                throw new TransferException("open file rejected: " + resp);
            }
            AgentLog.w(TAG, "unexpected handshake reply ignored: " + resp);
        }
    }

    @Override
    public void sendChunk(byte[] chunk, int seq) throws TransferException {
        if (closed || client == null) {
            throw new TransferException("adapter closed or not connected");
        }
        // B.3：帧 = 数据 + 4B CRC32 小端（CRC-32/ISO-HDLC = java.util.zip.CRC32）。
        byte[] frame = new byte[chunk.length + 4];
        System.arraycopy(chunk, 0, frame, 0, chunk.length);
        CRC32 crc = new CRC32();
        crc.update(chunk);
        long value = crc.getValue();
        for (int i = 0; i < 4; i++) {
            frame[chunk.length + i] = (byte) ((value >> (8 * i)) & 0xFF);
        }
        // 按 MTU 切片 WRITE_NR 写入（B.1/B.3：224B/包）。
        for (int off = 0; off < frame.length; off += LC_WRITE_PACKET_SIZE) {
            int len = Math.min(LC_WRITE_PACKET_SIZE, frame.length - off);
            byte[] packet = new byte[len];
            System.arraycopy(frame, off, packet, 0, len);
            client.writeCharacteristic(LC_SERVICE_UUID, LC_CHAR_UUID, packet, true);
        }
    }

    @Override
    public WindowAck waitWindowAck(int windowSeq, long timeoutMs) {
        // LC 窗口恒 1：等待该块的确认回包（B.2/B.3）。
        long deadline = nowMs() + timeoutMs;
        while (true) {
            String resp = awaitAscii(Math.max(1, deadline - nowMs()));
            if (resp == null) {
                return WindowAck.TIMEOUT; // §12.8：分块无响应
            }
            if ("310".equals(resp)) {
                return WindowAck.OK;
            }
            if ("311".equals(resp)) {
                return WindowAck.NAK; // 固件 CRC 错/块超时，重发该块（B.3）
            }
            if ("340".equals(resp)) {
                // 固件写文件失败并终止（B.3）：按无响应暂停语义上交引擎，等服务器决策。
                AgentLog.w(TAG, "firmware aborted transfer (340)");
                return WindowAck.TIMEOUT;
            }
            AgentLog.d(TAG, "non-ack notify ignored while waiting block ack: " + resp);
        }
    }

    @Override
    public void finish(FileTransferTask task, DeviceController device) throws TransferException {
        // B.2：传输结果校验（固件核对已收大小 == fileSize）。
        if (closed || client == null) {
            throw new TransferException("adapter closed before finish");
        }
        writeAscii("32");
        String resp = awaitAscii(FINISH_TIMEOUT_MS);
        if ("320".equals(resp)) {
            return;
        }
        if ("321".equals(resp)) {
            throw new TransferException("firmware verify failed (321)");
        }
        throw new TransferException("finish timeout or unexpected reply: " + resp);
    }

    @Override
    public boolean supportsOffsetWrite() {
        return true; // B.4：retrans='1' 断点续传
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (notifyListener != null) {
            responseBus.removeNotifyListener(notifyListener);
            notifyListener = null;
        }
    }

    // ==================== 内部 ====================

    private void registerNotifyTap() {
        if (notifyListener != null) {
            return;
        }
        notifyListener = (notifyMac, charUuid, value) -> {
            if (closed || !LC_CHAR_UUID.equals(charUuid) || !notifyMac.equals(mac)) {
                return;
            }
            if (value != null && value.length > 0) {
                mailbox.offer(new String(value, StandardCharsets.US_ASCII));
            }
        };
        responseBus.addNotifyListener(notifyListener);
    }

    /** 写 ASCII 命令帧（WRITE_NR，B.1）。 */
    private void writeAscii(String command) throws TransferException {
        if (closed || client == null) {
            throw new TransferException("adapter closed or not connected");
        }
        client.writeCharacteristic(LC_SERVICE_UUID, LC_CHAR_UUID,
                command.getBytes(StandardCharsets.US_ASCII), true);
    }

    /** 阻塞取一条 ASCII 回包；超时返回 null。 */
    private String awaitAscii(long timeoutMs) {
        try {
            return mailbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }
}
