package com.longcheer.agent.transfer;

import com.longcheer.agent.ble.GattClient;
import com.longcheer.agent.ble.GattClientProvider;
import com.longcheer.agent.ble.GattResponseBus;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.GattResult;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * LC 产测通道文件导出器（DUT→手机，docs/02-ota-file-transfer.md B.6 + B319 产测工具实证）。
 *
 * <p>协议要点（061/062/063，写入一律 WRITE_NO_RESPONSE）：</p>
 * <ul>
 *   <li>目录列举：写 ASCII {@code 00AT^LS=/<目录>} → Notify 多行回包，
 *       含 "F" 的行按空格切分最后一个 token 是文件名，含 "OK" 的行收尾；</li>
 *   <li>开始导出：写 {@code 061<设备侧路径>} → 回二进制帧 {@code @}+u32BE(文件总大小)，
 *       大小帧可能与首数据帧粘连（061 应答窗口内字节流消费，天然兼容）；</li>
 *   <li>拉数据：写 {@code 062} → 回 {@code @}+u32BE(块长)+数据+4B 大端无符号字节累加和；
 *       校验过 → 追加落盘再发 062 拉下一块；校验失败/丢包 → 写 {@code 063} 重传本块；</li>
 *   <li>结束：设备回 ASCII {@code FILE_EXPORT_OVER}（逐文件）。</li>
 * </ul>
 *
 * <p>超时与上限（B319 无限重发是坑，一律封顶）：061 无响应 5s 重发、上限 3 次；
 * 062 发出后 8s 无响应重发 062；063 重传上限 10 次（062 超时与 063 共用每块上限）。
 * 无断点续传：任何失败整文件重来。</p>
 *
 * <p>Notify 分流：导出会话期间（061~数据收齐）一律进二进制字节流缓冲
 * （帧跨 Notify 包拆分/粘连均无感）；会话外按行蓄积分行入邮箱，
 * 首字节 {@code @} 的行视作二进制兜底。实例单任务一次性使用，{@link #close()} 幂等。</p>
 */
public class LcExporter {

    private static final String TAG = "LcExporter";

    /** LC 通道 GATT 承载（与 LcProtoTransferAdapter 同一通道）。 */
    public static final UUID LC_SERVICE_UUID = LcProtoTransferAdapter.LC_SERVICE_UUID;
    public static final UUID LC_CHAR_UUID = LcProtoTransferAdapter.LC_CHAR_UUID;

    /** 061 无响应重发上限（B319 无限重发是坑，封顶 3 次）。 */
    static final int OPEN_RETRY_MAX = 3;
    /** 每块 062 超时重发 + 063 重传的合计上限。 */
    static final int RETRANS_MAX = 10;
    /** 单块长度防御上限（固件块 4480B，留足余量防坏头撑爆内存）。 */
    static final int MAX_BLOCK_SIZE = 1 << 20;

    static final long OPEN_TIMEOUT_MS = 5000L;   // 061 应答
    static final long DATA_TIMEOUT_MS = 8000L;   // 062/063 数据帧
    static final long OVER_TIMEOUT_MS = 5000L;   // FILE_EXPORT_OVER 收尾
    static final long LS_TIMEOUT_MS = 5000L;     // AT^LS 列举收尾
    /** 拉块前短等缓冲（固件自动推的块已在途就不用发 062）。 */
    static final long QUICK_POLL_MS = 500L;
    /** 建连后残留流排放静默期（固件跨 BLE 连接续推上次导出，真机实测）。 */
    static final long DRAIN_QUIET_MS = 300L;
    /** 总大小防御上限（512MB），扫描误判防护。 */
    static final long MAX_FILE_SIZE = 512L * 1024 * 1024;
    /** 固件 BLE 导出块长上限（B.6：4480）。 */
    static final long LC_BLOCK_MAX = 4480L;

    /** 导出失败（协议拒绝/超时/校验超限）。 */
    public static final class ExportException extends Exception {
        public ExportException(String message) {
            super(message);
        }
    }

    /** 单文件导出产物（本地已落盘）。 */
    public static final class ExportedFile {
        public final String name;        // 文件名（设备侧路径 basename）
        public final String remotePath;  // 设备侧完整路径
        public final File file;          // 本地落盘文件
        public final long size;
        public final byte[] sha256;

        ExportedFile(String name, String remotePath, File file, long size, byte[] sha256) {
            this.name = name;
            this.remotePath = remotePath;
            this.file = file;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** 数据帧读取结果（校验失败时 data 非空、checksumOk=false）。 */
    private static final class DataFrame {
        final byte[] data;
        final boolean checksumOk;

        DataFrame(byte[] data, boolean checksumOk) {
            this.data = data;
            this.checksumOk = checksumOk;
        }
    }

    private final GattClientProvider clientProvider;
    private final GattResponseBus responseBus;

    /** CCCD 订阅等待上限（单测可调小）。 */
    long cccdTimeoutMs = 3000L;
    long openTimeoutMs = OPEN_TIMEOUT_MS;
    long dataTimeoutMs = DATA_TIMEOUT_MS;
    long overTimeoutMs = OVER_TIMEOUT_MS;
    long lsTimeoutMs = LS_TIMEOUT_MS;

    private String mac;
    private GattClient client;
    private GattResponseBus.NotifyListener notifyListener;
    private volatile boolean closed = false;

    /** ASCII 回包邮箱（Notify 回调线程投入，会话线程阻塞取；已 trim）。 */
    private final BlockingQueue<String> mailbox = new LinkedBlockingQueue<>();

    /** 二进制字节流缓冲：'@' 帧按字节消费，跨包拆分/粘连无感。 */
    private final Object binLock = new Object();
    private byte[] binBuf = new byte[8192];
    private int binStart = 0;
    /** 诊断计数：累计收/消费字节（定位流错位用）。 */
    private int binRecvTotal = 0;
    private int binConsumedTotal = 0;
    /** 大小帧扫描时预读的块帧头（awaitDataFrame 优先消费）。 */
    private byte[] pendingBlockHeader;
    private int binEnd = 0;
    /** true = 061~数据收齐的导出窗口，窗口内 Notify 一律按二进制处理。 */
    private volatile boolean binaryPhase = false;

    /** ASCII 行蓄积（行可能跨 Notify 包拆分，'\n' 或 flushPendingAscii 切行）。 */
    private final StringBuilder asciiBuf = new StringBuilder();
    private final Object asciiLock = new Object();

    public LcExporter(GattClientProvider clientProvider, GattResponseBus responseBus) {
        this.clientProvider = clientProvider;
        this.responseBus = responseBus;
    }

    /**
     * 执行一次导出。remotePath 以 "/" 结尾 = 目录模式（AT^LS 列举 + 逐文件导出），
     * 否则单文件模式。
     *
     * @param exportDir 本地落盘目录（不含文件名，按设备侧 basename 落盘）
     * @return 已落盘文件列表
     */
    public List<ExportedFile> export(DeviceController device, String remotePath, File exportDir)
            throws ExportException {
        mac = device.snapshot().getMac();
        client = clientProvider.getActiveClient(mac);
        if (client == null) {
            throw new ExportException("no active gatt client for " + mac);
        }
        registerNotifyTap();
        subscribeCccd();
        drainStaleData();
        if (!exportDir.isDirectory() && !exportDir.mkdirs()) {
            throw new ExportException("cannot create export dir: " + exportDir);
        }
        List<ExportedFile> out = new ArrayList<>();
        if (remotePath.endsWith("/")) {
            List<String> names = listDir(remotePath);
            if (names.isEmpty()) {
                throw new ExportException("目录为空或列举无文件: " + remotePath);
            }
            for (String name : names) {
                out.add(exportOne(remotePath + name, exportDir));
            }
        } else {
            out.add(exportOne(remotePath, exportDir));
        }
        return out;
    }

    /** 解除 Notify 分接（幂等）。 */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (notifyListener != null) {
            responseBus.removeNotifyListener(notifyListener);
            notifyListener = null;
        }
    }

    // ==================== 目录列举（AT^LS） ====================

    private List<String> listDir(String dir) throws ExportException {
        writeAscii("00AT^LS=" + dir);
        List<String> names = new ArrayList<>();
        long deadline = nowMs() + lsTimeoutMs;
        while (true) {
            String line = awaitAscii(Math.max(1, deadline - nowMs()));
            if (line == null) {
                throw new ExportException("AT^LS timeout: " + dir);
            }
            if (line.contains("OK")) {
                return names;
            }
            // 含 F 的行为文件条目：按空格切分，最后一个 token 是文件名。
            if (line.contains("F")) {
                String[] tokens = line.trim().split("\\s+");
                String name = tokens[tokens.length - 1];
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
    }

    // ==================== 单文件导出（061/062/063） ====================

    private ExportedFile exportOne(String remotePath, File exportDir) throws ExportException {
        String name = basename(remotePath);
        if (name.isEmpty()) {
            throw new ExportException("bad remote path: " + remotePath);
        }
        // 二进制窗口从 061 起持续到数据收齐：固件会在总大小帧后**立即自动推第 1 块**
        // （真机抓包实证），若中途退出二进制相，自动推的块会被误归类为 ASCII 丢弃。
        enterBinaryPhase();
        long totalSize;
        try {
            totalSize = openExport(remotePath);
        } catch (ExportException e) {
            exitBinaryPhase();
            throw e;
        }
        AgentLog.i(TAG, "export start: " + remotePath + " size=" + totalSize);

        File dest = new File(exportDir, name);
        MessageDigest sha = newSha256();
        long received = 0;
        try (RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {
            raf.setLength(0); // 无断点续传：残留文件一律清空重来
            while (received < totalSize) {
                byte[] data = pullBlock(remotePath);
                if (received + data.length > totalSize) {
                    throw new ExportException("数据越界: " + remotePath
                            + " received=" + received + " + " + data.length + " > " + totalSize);
                }
                raf.write(data); // 校验通过才追加落盘
                sha.update(data);
                received += data.length;
            }
        } catch (java.io.IOException e) {
            throw new ExportException("落盘失败: " + e.getMessage());
        } finally {
            exitBinaryPhase();
        }
        // B.6/B319 语义：062 是"拉下一块"，数据耗尽时再发一次 062 固件才回 FILE_EXPORT_OVER。
        writeAscii("062");
        awaitExportOver(remotePath);
        return new ExportedFile(name, remotePath, dest, totalSize, sha.digest());
    }

    /** 061 开始导出：回 '@'+u32BE 总大小；无响应 5s 重发，上限 {@link #OPEN_RETRY_MAX} 次。
     *  调用方须已 enterBinaryPhase（本方法不退出，留给数据拉取阶段）。
     *  固件跨会话续推残留数据（真机实测），须扫描定位合法大小帧而非取流头 5 字节。 */
    private long openExport(String remotePath) throws ExportException {
        for (int attempt = 1; attempt <= OPEN_RETRY_MAX; attempt++) {
            writeAscii("061" + remotePath);
            Long size = awaitSizeFrame(openTimeoutMs);
            if (size != null) {
                return size;
            }
            AgentLog.w(TAG, "061 无响应，重发 (" + attempt + "/" + OPEN_RETRY_MAX + "): "
                    + remotePath);
        }
        throw new ExportException("061 无响应（重发 " + OPEN_RETRY_MAX + " 次仍失败）: "
                + remotePath);
    }

    /**
     * 扫描字节流定位"大小帧"：'@'+u32BE(总大小) 且紧随块帧头('@'+u32BE 块长≤4480)。
     * 残留块帧（'@'+4480 开头但后面不是块帧头）会被识别并跳过。块帧头暂存
     * {@link #pendingBlockHeader} 供 {@link #awaitDataFrame} 优先消费。
     */
    private Long awaitSizeFrame(long timeoutMs) {
        long deadline = nowMs() + timeoutMs;
        while (true) {
            long remain = deadline - nowMs();
            if (remain <= 0) {
                return null;
            }
            byte[] b = awaitBinary(1, remain);
            if (b == null) {
                return null;
            }
            if (b[0] != '@') {
                continue; // 残留数据字节，丢弃
            }
            byte[] sizeBytes = awaitBinary(4, Math.max(1, deadline - nowMs()));
            if (sizeBytes == null) {
                return null;
            }
            long size = u32be(sizeBytes, 0);
            if (size <= 0 || size > MAX_FILE_SIZE) {
                continue; // 不像总大小，继续扫
            }
            byte[] next = awaitBinary(5, Math.max(1, deadline - nowMs()));
            if (next == null) {
                return null;
            }
            if (next[0] == '@') {
                long blen = u32be(next, 1);
                if (blen > 0 && blen <= LC_BLOCK_MAX && blen <= size) {
                    pendingBlockHeader = next; // 首个块帧头留给数据拉取
                    return size;
                }
            }
            // 误判（残留的块帧）：回灌 5 字节继续扫
            unreadBinary(next);
        }
    }

    /**
     * 拉一块数据：固件在 061 后会自动推第 1 块（真机抓包实证），因此先尝试直接读帧，
     * 读不到才写 062 拉取下一块——预防式发 062 会让固件提前推下一块/到 EOF 后行为异常，
     * 把字节流打乱（真机实测校验失败根源）。校验失败写 063 重传，每块上限 {@link #RETRANS_MAX} 次。
     */
    private byte[] pullBlock(String remotePath) throws ExportException {
        // 先短等读缓冲：自动推/已在途的帧直接消费；固件在 061 后立即自动推第 1 块。
        DataFrame frame = awaitDataFrame(QUICK_POLL_MS);
        for (int resends = 0; ; resends++) {
            if (frame != null && frame.checksumOk) {
                return frame.data;
            }
            if (resends >= RETRANS_MAX) {
                throw new ExportException("块拉取超限（062 超时/063 重传 "
                        + RETRANS_MAX + " 次）: " + remotePath);
            }
            if (frame == null) {
                AgentLog.w(TAG, "062 无响应，重发 062 (" + (resends + 1) + "): " + remotePath);
                writeAscii("062");
            } else {
                // 校验失败只发 063——先 062 再 063 会让固件连推两块，缓冲错位（真机实测）。
                AgentLog.w(TAG, "块校验失败，发 063 重传 (" + (resends + 1) + "): " + remotePath);
                writeAscii("063");
            }
            frame = awaitDataFrame(dataTimeoutMs);
        }
    }

    /** 读一帧数据：'@'+u32BE(块长)+数据+4B 大端累加和；超时返回 null。 */
    private DataFrame awaitDataFrame(long timeoutMs) throws ExportException {
        long deadline = nowMs() + timeoutMs;
        byte[] header;
        if (pendingBlockHeader != null) {
            header = pendingBlockHeader; // 大小帧扫描时预读的合法块帧头
            pendingBlockHeader = null;
        } else {
            header = awaitBinary(5, timeoutMs);
        }
        if (header == null) {
            return null;
        }
        if (header[0] != '@') {
            AgentLog.w(TAG, "frame head illegal: " + Integer.toHexString(header[0] & 0xFF)
                    + " headerHex=" + bytesToHex(header) + " binPending=" + binPending()
                    + " binConsumed=" + binConsumedTotal);
            throw new ExportException("数据帧头非法: " + Integer.toHexString(header[0] & 0xFF));
        }
        long len = u32be(header, 1);
        if (len > MAX_BLOCK_SIZE) {
            throw new ExportException("数据块长度越界: " + len);
        }
        byte[] rest = awaitBinary((int) len + 4, Math.max(1, deadline - nowMs()));
        if (rest == null) {
            return null;
        }
        byte[] data = Arrays.copyOfRange(rest, 0, (int) len);
        long expected = u32be(rest, (int) len);
        long actual = 0;
        for (byte b : data) {
            actual += b & 0xFF; // 逐字节无符号累加（B.6）
        }
        if (actual != expected) {
            AgentLog.w(TAG, "frame checksum mismatch: blockLen=" + len
                    + " expected=" + Long.toHexString(expected)
                    + " actual=" + Long.toHexString(actual)
                    + " dataHead=" + bytesToHex(Arrays.copyOfRange(data, 0, Math.min(8, data.length)))
                    + " binPending=" + binPending());
        }
        return new DataFrame(data, actual == expected);
    }

    /** 数据收齐后等 ASCII FILE_EXPORT_OVER 收尾（逐文件）。 */
    private void awaitExportOver(String remotePath) throws ExportException {
        long deadline = nowMs() + overTimeoutMs;
        while (true) {
            String line = awaitAscii(Math.max(1, deadline - nowMs()));
            if (line == null) {
                throw new ExportException("FILE_EXPORT_OVER 超时: " + remotePath);
            }
            // 真机校准：固件实际回 "AT^FILE_EXPORT_OVER"（带 AT^ 前缀）。
            if (line.contains("FILE_EXPORT_OVER")) {
                return;
            }
            AgentLog.d(TAG, "non-over notify ignored while waiting export over: " + line);
        }
    }

    // ==================== Notify 分接与字节流 ====================

    private void registerNotifyTap() {
        if (notifyListener != null) {
            return;
        }
        notifyListener = (notifyMac, charUuid, value) -> {
            if (closed || !LC_CHAR_UUID.equals(charUuid) || !notifyMac.equals(mac)) {
                return;
            }
            if (value == null || value.length == 0) {
                return;
            }
            if (binaryPhase || value[0] == '@') {
                offerBinary(value);
            } else {
                offerAscii(value);
            }
        };
        responseBus.addNotifyListener(notifyListener);
    }

    /** CCCD 竞态（同 LcProtoTransferAdapter）：等 descriptor 写完成回调再发命令。 */
    private void subscribeCccd() throws ExportException {
        CompletableFuture<GattResult> cccd = responseBus.begin(mac, LC_CHAR_UUID, "CCCD");
        client.setNotification(LC_SERVICE_UUID, LC_CHAR_UUID, true);
        GattResult sub = await(cccd, cccdTimeoutMs);
        if (sub == null || !sub.isSuccess()) {
            throw new ExportException("CCCD subscribe failed: "
                    + (sub == null ? "timeout" : "status=" + sub.getStatus()));
        }
    }

    private void offerBinary(byte[] value) {
        synchronized (binLock) {
            int pending = binEnd - binStart;
            int need = pending + value.length;
            if (need > binBuf.length) {
                // 扩容（压实后仍放不下）
                byte[] next = new byte[Math.max(need, binBuf.length * 2)];
                System.arraycopy(binBuf, binStart, next, 0, pending);
                binBuf = next;
                binStart = 0;
                binEnd = pending;
            } else if (binEnd + value.length > binBuf.length) {
                // 尾部空间不足但总量放得下：压实
                System.arraycopy(binBuf, binStart, binBuf, 0, pending);
                binStart = 0;
                binEnd = pending;
            }
            System.arraycopy(value, 0, binBuf, binEnd, value.length);
            binEnd += value.length;
            binRecvTotal += value.length;
            binLock.notifyAll();
        }
    }

    /** 诊断：当前缓冲区积压字节数。 */
    private int binPending() {
        synchronized (binLock) {
            return binEnd - binStart;
        }
    }

    /** 回灌字节到缓冲头部（大小帧扫描误判时退回预读内容）。 */
    private void unreadBinary(byte[] value) {
        synchronized (binLock) {
            int pending = binEnd - binStart;
            byte[] next = new byte[pending + value.length];
            System.arraycopy(value, 0, next, 0, value.length);
            System.arraycopy(binBuf, binStart, next, value.length, pending);
            binBuf = next;
            binStart = 0;
            binEnd = next.length;
            binLock.notifyAll();
        }
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private void offerAscii(byte[] value) {
        synchronized (asciiLock) {
            String chunk = new String(value, StandardCharsets.US_ASCII);
            asciiBuf.append(chunk);
            flushAsciiLines(false);
        }
    }

    /** 切出完整行入邮箱；'\n' 与 '\0' 都算行尾（固件 OVER 收尾带 C 字符串终止符，
     *  真机实测）；force=true 时残余半行也入邮箱（导出窗口结束兜底）。 */
    private void flushAsciiLines(boolean force) {
        synchronized (asciiLock) {
            while (true) {
                int nl = asciiBuf.indexOf("\n");
                int nul = asciiBuf.indexOf("\0");
                int cut;
                if (nl < 0) {
                    cut = nul;
                } else if (nul < 0) {
                    cut = nl;
                } else {
                    cut = Math.min(nl, nul);
                }
                if (cut < 0) {
                    break;
                }
                String line = asciiBuf.substring(0, cut).trim();
                asciiBuf.delete(0, cut + 1);
                if (!line.isEmpty()) {
                    mailbox.offer(line);
                }
            }
            if (force && asciiBuf.length() > 0) {
                String line = asciiBuf.toString().trim();
                asciiBuf.setLength(0);
                if (!line.isEmpty()) {
                    mailbox.offer(line);
                }
            }
        }
    }

    /**
     * 建连后排放残留数据：固件会在新连接 CCCD 订阅后**续推上一次未完成的导出流**
     * （真机实测：残留块帧被误读成总大小帧/数据帧头导致校验连锁失败）。
     * 二进制相内按静默期排空字节流与 ASCII 邮箱，结束后恢复非二进制相。
     */
    private void drainStaleData() {
        enterBinaryPhase();
        try {
            int drained = 0;
            while (true) {
                byte[] b = awaitBinary(1, DRAIN_QUIET_MS);
                if (b == null) {
                    break;
                }
                drained++;
            }
            if (drained > 0) {
                AgentLog.i(TAG, "drained stale export bytes: " + drained);
            }
        } finally {
            synchronized (binLock) {
                binStart = binEnd = 0;
            }
            mailbox.clear();
            binaryPhase = false; // 直接复位，残余已清，无需 exitBinaryPhase 的回灌
        }
    }

    /** 进入二进制导出窗口：窗口内 Notify 一律进字节流缓冲。 */
    private void enterBinaryPhase() {
        binaryPhase = true;
    }
    /**
     * 退出二进制导出窗口：缓冲残余按首字节归类——'@' 开头（如粘连的首数据帧）
     * 保留在字节流缓冲，否则（如粘连的 FILE_EXPORT_OVER）回灌 ASCII 行通道。
     */
    private void exitBinaryPhase() {
        binaryPhase = false;
        byte[] leftover;
        synchronized (binLock) {
            leftover = Arrays.copyOfRange(binBuf, binStart, binEnd);
            binStart = binEnd = 0;
        }
        if (leftover.length > 0) {
            if (leftover[0] == '@') {
                offerBinary(leftover);
            } else {
                offerAscii(leftover);
            }
        }
        flushAsciiLines(true);
    }

    /** 阻塞取 n 字节二进制数据；超时/中断返回 null。 */
    private byte[] awaitBinary(int n, long timeoutMs) {
        long deadline = nowMs() + timeoutMs;
        synchronized (binLock) {
            while (binEnd - binStart < n) {
                long remain = deadline - nowMs();
                if (remain <= 0) {
                    return null;
                }
                try {
                    binLock.wait(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            byte[] out = Arrays.copyOfRange(binBuf, binStart, binStart + n);
            binStart += n;
            binConsumedTotal += n;
            if (binStart == binEnd) {
                binStart = binEnd = 0;
            }
            return out;
        }
    }

    /** 写 ASCII 命令帧（WRITE_NR）。 */
    private void writeAscii(String command) throws ExportException {
        if (closed || client == null) {
            throw new ExportException("exporter closed or not connected");
        }
        client.writeCharacteristic(LC_SERVICE_UUID, LC_CHAR_UUID,
                command.getBytes(StandardCharsets.US_ASCII), true);
    }

    /** 阻塞取一条 ASCII 回包（已 trim）；超时返回 null。 */
    private String awaitAscii(long timeoutMs) {
        try {
            return mailbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String basename(String remotePath) {
        int idx = remotePath.lastIndexOf('/');
        return idx >= 0 ? remotePath.substring(idx + 1) : remotePath;
    }

    private static long u32be(byte[] buf, int offset) {
        return ((long) (buf[offset] & 0xFF) << 24)
                | ((long) (buf[offset + 1] & 0xFF) << 16)
                | ((long) (buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }

    private static MessageDigest newSha256() throws ExportException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new ExportException("SHA-256 unavailable");
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

    private static long nowMs() {
        return System.currentTimeMillis();
    }
}
