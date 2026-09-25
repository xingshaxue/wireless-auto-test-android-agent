package com.longcheer.agent.spp;

import com.longcheer.agent.ble.GattClient;
import com.longcheer.agent.ble.GattClientProvider;
import com.longcheer.agent.ble.GattResponseBus;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.FileTransferTask;
import com.longcheer.agent.model.GattResult;
import com.longcheer.agent.transfer.LcProtoTransferAdapter;
import com.longcheer.agent.transfer.TransferAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * SPP 加速通道文件传输/OTA 适配器——经典蓝牙 RFCOMM 承载 33x 协议
 * （与 {@link LcProtoTransferAdapter} 同一套协议，不同块尺寸与承载；
 * 真机实测 P67/FACTEST：BLE 12.5KB/s → SPP 100KB/s+）。
 *
 * <p>握手三段：</p>
 * <ol>
 *   <li>经 BLE LC 通道开经典蓝牙（WRITE_NR 发 AT，Notify 收回包，含 OK 即过）：
 *       {@code 00AT^BT_ENABLE} → "ENABLE_BT=OK"；{@code 00AT^BT_ACCESS_SET=3}
 *       → "OK=BT_SCAN, 3"（=1 实测连不上，必须 3；已开过时幂等）；</li>
 *   <li>{@link SppClient} insecure RFCOMM 直连（免配对，真机约 3s）；</li>
 *   <li>33x 协商走 SPP：{@code 30}+retrans → "300"；{@code 33bin,/data/<fileId>.bin,<size>}
 *       → "330" 或续传回执（权威偏移回写 task，非块对齐回退 0 从头）。</li>
 * </ol>
 *
 * <p>数据：块 39600B 切 960B 包连续写（SPP 流可靠，无包级 ACK/节流），块尾追加
 * 4B CRC32 小端（CRC-32/ISO-HDLC，对该块数据字节计算）；每块等 "310"/"311"
 * （窗口恒 1）。收尾/中止同 LC 通道：{@code 32}→"320"/"321"，{@code 34}→"340"。</p>
 *
 * <p>实例为单任务一次性使用（语义同 LC 适配器）；BLE 连接在整个传输期间保持
 * （设备管理/回退通道），{@link #close} 只关 SPP socket 与 BLE Notify 分接。</p>
 */
public class SppTransferAdapter implements TransferAdapter {

    private static final String TAG = "SppTransfer";

    /** SPP 承载块数据长度（33x 协议 SPP 参数）。 */
    public static final int SPP_BLOCK_DATA_SIZE = 39600;
    /** SPP 块内写包长。 */
    public static final int SPP_PACKET_SIZE = 960;

    /** BLE 侧 AT 应答等待上限（开经典蓝牙两步各一次）。 */
    static final long AT_TIMEOUT_MS = 5000L;
    /** 协商/打开应答等待上限（SPP 侧）。 */
    static final long HANDSHAKE_TIMEOUT_MS = 5000L;
    static final long FINISH_TIMEOUT_MS = 10000L;
    /** "34" 停止应答等待上限（best-effort）。 */
    static final long ABORT_ACK_TIMEOUT_MS = 500L;

    /** 开经典蓝牙 AT 命令（P67 FACTEST 真机校准序列）。 */
    static final String AT_BT_ENABLE = "00AT^BT_ENABLE";
    static final String AT_BT_ACCESS_SET = "00AT^BT_ACCESS_SET=3";

    /** 续传回执前缀（同 LC 通道 B.4），数字为固件侧已收字节数。 */
    private static final String RETRANS_PREFIX = "open file success:retransmission start length:";

    private final GattClientProvider clientProvider;
    private final GattResponseBus responseBus;
    private final SppClient spp;

    /** CCCD 订阅等待上限（单测可调小）。 */
    long cccdTimeoutMs = 3000L;
    /** BLE 侧 AT 应答等待上限（单测可调小）。 */
    long atTimeoutMs = AT_TIMEOUT_MS;

    private String mac;
    private GattClient bleClient;
    private GattResponseBus.NotifyListener notifyListener;
    private volatile boolean closed = false;
    private boolean handshaken = false;
    private boolean transferComplete = false;

    /** BLE AT 回包邮箱（LC 特征 Notify，已 trim；仅握手开 BT 阶段使用）。 */
    private final BlockingQueue<String> bleMailbox = new LinkedBlockingQueue<>();

    public SppTransferAdapter(GattClientProvider clientProvider, GattResponseBus responseBus,
                              SppClient spp) {
        this.clientProvider = clientProvider;
        this.responseBus = responseBus;
        this.spp = spp;
    }

    @Override
    public synchronized void handshake(FileTransferTask task, DeviceController device)
            throws TransferException {
        mac = device.snapshot().getMac();
        bleClient = clientProvider.getActiveClient(mac);
        if (bleClient == null) {
            throw new TransferException("no active gatt client for " + mac);
        }
        registerNotifyTap();
        // CCCD 竞态（同 LC 通道 B.1）：等订阅完成再发 AT，防丢回包。
        CompletableFuture<GattResult> cccd =
                responseBus.begin(mac, LcProtoTransferAdapter.LC_CHAR_UUID, "CCCD");
        bleClient.setNotification(LcProtoTransferAdapter.LC_SERVICE_UUID,
                LcProtoTransferAdapter.LC_CHAR_UUID, true);
        GattResult sub = await(cccd, cccdTimeoutMs);
        if (sub == null || !sub.isSuccess()) {
            throw new TransferException("CCCD subscribe failed: "
                    + (sub == null ? "timeout" : "status=" + sub.getStatus()));
        }

        // 开经典蓝牙（真机校准序列；已开过时固件幂等回 OK）。
        enableClassicBt(AT_BT_ENABLE);
        enableClassicBt(AT_BT_ACCESS_SET);

        // RFCOMM 直连（insecure 免配对）。
        try {
            spp.connect(mac);
        } catch (IOException e) {
            throw new TransferException("spp connect failed: " + e.getMessage());
        }

        // 33x 协商（SPP 承载）：块 39600B、窗口恒 1。
        task.setChunkSize(SPP_BLOCK_DATA_SIZE);
        task.setWindowSize(1);

        boolean resume = task.getTransferredOffset() > 0;
        writeSppAscii("30" + (resume ? "1" : "0"));
        String resp = awaitSpp(HANDSHAKE_TIMEOUT_MS);
        if (resp == null || !resp.startsWith("300")) {
            throw new TransferException("spp negotiate rejected: " + resp);
        }

        writeSppAscii(com.longcheer.agent.transfer.LcProtoTransferAdapter.buildOpenCommand(
                task.getFileId(), task.getFileName(), task.getTotalSize()));
        long deadline = nowMs() + HANDSHAKE_TIMEOUT_MS;
        while (true) {
            resp = awaitSpp(Math.max(1, deadline - nowMs()));
            if (resp == null) {
                throw new TransferException("spp open file timeout");
            }
            if (resp.startsWith("330")) {
                handshaken = true;
                return; // 打开成功
            }
            if (resp.startsWith(RETRANS_PREFIX)) {
                // 固件回执的 length 为权威续传偏移，回写 task（同 LC 通道 B.4）。
                long offset = parseLong(resp.substring(RETRANS_PREFIX.length()), -1);
                if (offset < 0) {
                    throw new TransferException("bad retransmission offset: " + resp);
                }
                if (offset % SPP_BLOCK_DATA_SIZE != 0) {
                    // 非整块倍数：引擎 startSeq 整除截断会错位，回退从头重传。
                    AgentLog.w(TAG, "retransmission offset " + offset
                            + " not block-aligned, restart from 0");
                    offset = 0;
                }
                if (offset != task.getTransferredOffset()) {
                    AgentLog.i(TAG, "retransmission offset override: "
                            + task.getTransferredOffset() + " -> " + offset);
                    task.setTransferredOffset(offset);
                }
                handshaken = true;
                return;
            }
            if (resp.startsWith("331") || resp.startsWith("open error")) {
                throw new TransferException("spp open file rejected: " + resp);
            }
            AgentLog.w(TAG, "unexpected handshake reply ignored: " + resp);
        }
    }

    @Override
    public void sendChunk(byte[] chunk, int seq) throws TransferException {
        // 帧 = 数据 + 4B CRC32 小端（CRC-32/ISO-HDLC = java.util.zip.CRC32），按 960B 包连续写。
        byte[] frame = new byte[chunk.length + 4];
        System.arraycopy(chunk, 0, frame, 0, chunk.length);
        CRC32 crc = new CRC32();
        crc.update(chunk);
        long value = crc.getValue();
        for (int i = 0; i < 4; i++) {
            frame[chunk.length + i] = (byte) ((value >> (8 * i)) & 0xFF);
        }
        for (int off = 0; off < frame.length; off += SPP_PACKET_SIZE) {
            int len = Math.min(SPP_PACKET_SIZE, frame.length - off);
            try {
                spp.write(java.util.Arrays.copyOfRange(frame, off, off + len));
            } catch (IOException e) {
                throw new TransferException("spp write failed (seq=" + seq + "): " + e.getMessage());
            }
        }
    }

    @Override
    public WindowAck waitWindowAck(int windowSeq, long timeoutMs) {
        // SPP 窗口恒 1：等待该块的确认回包。
        long deadline = nowMs() + timeoutMs;
        while (true) {
            String resp = awaitSpp(Math.max(1, deadline - nowMs()));
            if (resp == null) {
                return WindowAck.TIMEOUT; // §12.8：分块无响应
            }
            if (resp.startsWith("310")) {
                return WindowAck.OK;
            }
            if (resp.startsWith("311")) {
                return WindowAck.NAK; // 固件 CRC 错/块超时，重发该块
            }
            if (resp.startsWith("340")) {
                // 固件写文件失败并终止：按无响应暂停语义上交引擎，等服务器决策。
                AgentLog.w(TAG, "firmware aborted transfer (340)");
                return WindowAck.TIMEOUT;
            }
            AgentLog.d(TAG, "non-ack reply ignored while waiting block ack: " + resp);
        }
    }

    @Override
    public void finish(FileTransferTask task, DeviceController device) throws TransferException {
        if (closed || !spp.isConnected()) {
            throw new TransferException("adapter closed before finish");
        }
        writeSppAscii("32");
        String resp = awaitSpp(FINISH_TIMEOUT_MS);
        if (resp != null && resp.startsWith("320")) {
            transferComplete = true;
            return;
        }
        if (resp != null && resp.startsWith("321")) {
            throw new TransferException("firmware verify failed (321)");
        }
        throw new TransferException("finish timeout or unexpected reply: " + resp);
    }

    @Override
    public boolean supportsOffsetWrite() {
        return true; // retrans='1' 断点续传（同 LC 通道 B.4）
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return; // 幂等
        }
        closed = true;
        // 取消/暂停/失败时通知固件停止（"34"→"340"）；成功完成或未完成握手不发（同 LC）。
        if (spp.isConnected() && handshaken && !transferComplete) {
            try {
                spp.write("34".getBytes(StandardCharsets.US_ASCII));
                awaitSpp(ABORT_ACK_TIMEOUT_MS); // best-effort 等 "340"
            } catch (IOException e) {
                AgentLog.w(TAG, "abort (34) write failed: " + e.getMessage());
            }
        }
        spp.close();
        if (notifyListener != null) {
            responseBus.removeNotifyListener(notifyListener);
            notifyListener = null;
        }
    }

    // ==================== 内部 ====================

    /** 经 BLE LC 通道发 AT 开经典蓝牙；回包含 OK 即过（"ENABLE_BT=OK"/"OK=BT_SCAN, 3"）。 */
    private void enableClassicBt(String atCommand) throws TransferException {
        bleMailbox.clear(); // 丢弃上一步残留回包
        bleClient.writeCharacteristic(LcProtoTransferAdapter.LC_SERVICE_UUID,
                LcProtoTransferAdapter.LC_CHAR_UUID,
                atCommand.getBytes(StandardCharsets.US_ASCII), true);
        String resp = awaitBle(atTimeoutMs);
        if (resp == null) {
            throw new TransferException("AT timeout: " + atCommand);
        }
        if (!resp.contains("OK")) {
            throw new TransferException("AT rejected: " + atCommand + " -> " + resp);
        }
        AgentLog.i(TAG, atCommand + " -> " + resp);
    }

    private void registerNotifyTap() {
        if (notifyListener != null) {
            return;
        }
        notifyListener = (notifyMac, charUuid, value) -> {
            if (closed || !LcProtoTransferAdapter.LC_CHAR_UUID.equals(charUuid)
                    || !notifyMac.equals(mac)) {
                return;
            }
            if (value != null && value.length > 0) {
                // 固件 Notify 可能带 \r\n：统一 trim 后匹配。
                bleMailbox.offer(new String(value, StandardCharsets.US_ASCII).trim());
            }
        };
        responseBus.addNotifyListener(notifyListener);
    }

    /** 写 ASCII 命令帧到 SPP。 */
    private void writeSppAscii(String command) throws TransferException {
        try {
            spp.write(command.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new TransferException("spp write failed: " + e.getMessage());
        }
    }

    /** 阻塞取一条 SPP ASCII 回包（已 trim）；超时返回 null。 */
    private String awaitSpp(long timeoutMs) {
        return spp.awaitLine(timeoutMs);
    }

    /** 阻塞取一条 BLE AT 回包（已 trim）；超时返回 null。 */
    private String awaitBle(long timeoutMs) {
        try {
            return bleMailbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 阻塞等一笔 GATT 操作结果；超时/中断返回 null。 */
    private static GattResult await(CompletableFuture<GattResult> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
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
