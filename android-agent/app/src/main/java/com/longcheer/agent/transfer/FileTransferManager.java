package com.longcheer.agent.transfer;

import android.os.SystemClock;

import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.FileTransferTask;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingScheduler;
import com.longcheer.agent.tcp.FileFrameCodec;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/**
 * 文件传输管理器（SDD §7.6 / §8.8 / §12.8）。
 *
 * <p>两段式流程：TCP 二进制帧下载落盘（逐帧 crc16、整体 SHA-256、断连 lastSeq 续传）
 * → 下载完成后申请 pinned 槽位进入 BLE 分块传输（WRITE_NO_RESPONSE + 窗口 ACK +
 * NAK 重发 + 断点续传）。</p>
 *
 * <p>注意：§16.3 帧格式无 taskId 字段，因此同一条 TCP 连接同一时刻只有一个
 * 处于下载阶段的任务（下载串行）；BLE 会话并发受 maxConcurrentTransfers 限制，
 * 超额排队（FIFO，按到达顺序）。</p>
 */
public class FileTransferManager {

    private static final String TAG = "FileTransferManager";

    /** 窗口连续失败上限（§7.6：连续失败 N 次任务失败）。 */
    static final int MAX_WINDOW_RETRY = 3;
    /** 分块无响应超时（§7.6：30s 暂停并上报）。 */
    static final long CHUNK_ACK_TIMEOUT_MS = 30000L;

    /** TransferAdapter 工厂（生产走 GATT，测试注入 fake）。 */
    public interface TransferAdapterFactory {
        TransferAdapter create(FileTransferTask task, DeviceController device);
    }

    /** 内部任务上下文。 */
    static final class TransferContext {
        final FileTransferTask task;
        final File file;
        final byte[] expectedSha256;
        final Set<Integer> receivedSeqs = ConcurrentHashMap.newKeySet();
        final Set<Integer> corruptSeqs = ConcurrentHashMap.newKeySet();
        volatile int downloadChunkSize = 0;   // 从首个 FILE_FRAME 学习
        volatile boolean downloadComplete = false;
        volatile boolean downloadSuspended = false;  // TCP 断开挂起
        volatile boolean cancelRequested = false;
        volatile boolean pauseRequested = false;
        final Object monitor = new Object();

        TransferContext(FileTransferTask task, File file, byte[] expectedSha256) {
            this.task = task;
            this.file = file;
            this.expectedSha256 = expectedSha256;
        }
    }

    private final DeviceRegistry deviceRegistry;
    private final ConnectionScheduler connectionScheduler;
    private final PollingScheduler pollingScheduler;
    private final StateReporter stateReporter;
    private final com.longcheer.agent.config.AgentConfig config;
    private final File transferDir;
    private final TransferAdapterFactory adapterFactory;
    private final LongSupplier clock;
    private final ExecutorService sessionExecutor;
    private final Semaphore sessionSlots;

    private final Map<String, TransferContext> tasks = new ConcurrentHashMap<>();
    /** §16.3 帧无 taskId：同连接同时只允许一个下载中的任务。 */
    private volatile String activeDownloadTaskId;

    public FileTransferManager(DeviceRegistry deviceRegistry,
                               ConnectionScheduler connectionScheduler,
                               PollingScheduler pollingScheduler,
                               StateReporter stateReporter,
                               com.longcheer.agent.config.AgentConfig config,
                               File transferDir,
                               TransferAdapterFactory adapterFactory) {
        this(deviceRegistry, connectionScheduler, pollingScheduler, stateReporter, config,
                transferDir, adapterFactory, SystemClock::elapsedRealtime);
    }

    FileTransferManager(DeviceRegistry deviceRegistry,
                        ConnectionScheduler connectionScheduler,
                        PollingScheduler pollingScheduler,
                        StateReporter stateReporter,
                        com.longcheer.agent.config.AgentConfig config,
                        File transferDir,
                        TransferAdapterFactory adapterFactory,
                        LongSupplier clock) {
        this.deviceRegistry = deviceRegistry;
        this.connectionScheduler = connectionScheduler;
        this.pollingScheduler = pollingScheduler;
        this.stateReporter = stateReporter;
        this.config = config;
        this.transferDir = transferDir;
        this.adapterFactory = adapterFactory;
        this.clock = clock;
        this.sessionSlots = new Semaphore(Math.max(1, config.getMaxConcurrentTransfers()));
        this.sessionExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "FileTransfer");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== M4-1 TCP 侧下载 ====================

    /**
     * 受理 FILE_TRANSFER 任务。
     *
     * @return 0 受理；3004 磁盘配额/空间不足（§12.9）；2003 设备不存在
     */
    public synchronized int startTransfer(String taskId, String fileId, String mac,
                                          long totalSize, byte[] sha256,
                                          int chunkSize, int windowSize) {
        TransferContext existing = tasks.get(taskId);
        if (existing != null && !isTerminal(existing.task.getState())) {
            return 0; // 幂等：重复下发视为已受理
        }
        if (deviceRegistry.findByMac(mac) == null) {
            return 2003;
        }
        cleanupRetainedFiles();
        long quotaBytes = config.getDiskQuotaMb() * 1024L * 1024L;
        if (dirSize() + totalSize > quotaBytes) {
            reportError(3004, "disk quota exceeded", mac);
            return 3004;
        }
        if (transferDir.getUsableSpace() < totalSize) {
            reportError(3004, "insufficient free space, pause accepting new tasks", mac);
            return 3004;
        }
        if (!transferDir.isDirectory() && !transferDir.mkdirs()) {
            reportError(3004, "cannot create transfer dir", mac);
            return 3004;
        }

        FileTransferTask task = new FileTransferTask(taskId, mac, fileId, totalSize,
                chunkSize, windowSize);
        TransferContext ctx = new TransferContext(task, new File(transferDir, taskId + ".bin"),
                sha256);
        tasks.put(taskId, ctx);
        AgentLog.i(TAG, "task accepted: " + taskId + " file=" + fileId + " size=" + totalSize
                + " mac=" + mac);

        if (activeDownloadTaskId == null) {
            beginDownload(ctx);
        } else {
            AgentLog.i(TAG, "another download active, queued: " + taskId);
        }
        return 0;
    }

    private void beginDownload(TransferContext ctx) {
        activeDownloadTaskId = ctx.task.getTaskId();
        ctx.downloadSuspended = false;
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", ctx.task.getTaskId());
        payload.put("fileId", ctx.task.getFileId());
        stateReporter.report("FILE_DOWNLOAD_READY", payload);
    }

    /** TCP 二进制帧入口（AgentService 的 TcpListener.onFrame 路由至此）。 */
    public void onFrame(byte[] frameBytes) {
        String taskId = activeDownloadTaskId;
        TransferContext ctx = taskId == null ? null : tasks.get(taskId);
        if (ctx == null) {
            AgentLog.w(TAG, "frame with no active download, dropped");
            return;
        }
        FileFrameCodec.Frame frame;
        try {
            frame = FileFrameCodec.decode(frameBytes);
        } catch (IllegalArgumentException e) {
            // crc16 失败（§16.3）：尽最大努力取 seq 记入重传清单。
            Integer seq = tryReadSeq(frameBytes);
            if (seq != null) {
                ctx.corruptSeqs.add(seq);
            }
            AgentLog.w(TAG, "frame decode failed (" + e.getMessage() + "), seq=" + seq);
            return;
        }
        switch (frame.type) {
            case FILE_FRAME:
                onFileFrame(ctx, frame);
                break;
            case FILE_END:
                onFileEnd(ctx, frame);
                break;
            default:
                AgentLog.d(TAG, "ignore frame type " + frame.type);
                break;
        }
    }

    private void onFileFrame(TransferContext ctx, FileFrameCodec.Frame frame) {
        if (frame.payload.length == 0) {
            return;
        }
        if (ctx.downloadChunkSize == 0) {
            ctx.downloadChunkSize = frame.payload.length;
        }
        long offset = (long) (frame.seq - 1) * ctx.downloadChunkSize;
        try (RandomAccessFile raf = new RandomAccessFile(ctx.file, "rw")) {
            raf.seek(offset);
            raf.write(frame.payload);
            ctx.receivedSeqs.add(frame.seq);
        } catch (java.io.IOException e) {
            AgentLog.w(TAG, "write chunk failed: " + e.getMessage());
            ctx.corruptSeqs.add(frame.seq);
        }
    }

    private void onFileEnd(TransferContext ctx, FileFrameCodec.Frame frame) {
        List<Integer> missing = computeMissingSeqs(ctx);
        if (!missing.isEmpty()) {
            reportDownloadAck(ctx.task.getTaskId(), missing);
            return;
        }
        byte[] actual = sha256Of(ctx.file);
        boolean hashOk = actual != null
                && Arrays.equals(actual, frame.payload)
                && (ctx.expectedSha256 == null || Arrays.equals(actual, ctx.expectedSha256));
        if (!hashOk) {
            failTask(ctx, 4001, "sha256 mismatch"); // §12.9 4xxx：哈希校验失败
            return;
        }
        reportDownloadAck(ctx.task.getTaskId(), new ArrayList<>());
        ctx.downloadComplete = true;
        activeDownloadTaskId = null;
        promoteNextDownload();
        // 下载完成才进入 BLE 阶段（§7.6：未下载完不申请 pinned 槽位）。
        launchBleSession(ctx);
    }

    /** 启动 BLE 会话（包可见，测试可覆盖为同步执行）。 */
    void launchBleSession(TransferContext ctx) {
        sessionExecutor.execute(() -> runBleSession(ctx));
    }

    /** 已确认的最大连续块号（FILE_DOWNLOAD_RESUME 的 lastSeq，§7.6/§16.3 口径）。 */
    int maxContiguousSeq(TransferContext ctx) {
        int seq = 0;
        while (ctx.receivedSeqs.contains(seq + 1) && !ctx.corruptSeqs.contains(seq + 1)) {
            seq++;
        }
        return seq;
    }

    private List<Integer> computeMissingSeqs(TransferContext ctx) {
        List<Integer> missing = new ArrayList<>();
        if (ctx.downloadChunkSize <= 0) {
            return missing;
        }
        int totalChunks = (int) ((ctx.task.getTotalSize() + ctx.downloadChunkSize - 1)
                / ctx.downloadChunkSize);
        for (int seq = 1; seq <= totalChunks; seq++) {
            if (!ctx.receivedSeqs.contains(seq) || ctx.corruptSeqs.contains(seq)) {
                missing.add(seq);
            }
        }
        return missing;
    }

    /** TCP 断开：下载中任务挂起（§7.6）。 */
    public void onTcpDisconnected() {
        TransferContext ctx = activeDownloadTaskId == null ? null : tasks.get(activeDownloadTaskId);
        if (ctx != null && !ctx.downloadComplete) {
            ctx.downloadSuspended = true;
            AgentLog.i(TAG, "tcp disconnected, download suspended: " + ctx.task.getTaskId());
        }
    }

    /** TCP 重连：挂起任务发 FILE_DOWNLOAD_RESUME 续传（lastSeq 口径 = 最大连续确认块号）。 */
    public void onTcpReconnected() {
        for (TransferContext ctx : tasks.values()) {
            if (!ctx.downloadComplete && ctx.downloadSuspended && !ctx.cancelRequested) {
                ctx.downloadSuspended = false;
                Map<String, Object> payload = new HashMap<>();
                payload.put("taskId", ctx.task.getTaskId());
                payload.put("lastSeq", maxContiguousSeq(ctx));
                stateReporter.report("FILE_DOWNLOAD_RESUME", payload);
                AgentLog.i(TAG, "resume download: " + ctx.task.getTaskId()
                        + " lastSeq=" + maxContiguousSeq(ctx));
            }
        }
    }

    // ==================== M4-3/4/5 BLE 分块会话 ====================

    /** BLE 会话主流程（包可见，单测可同步驱动）。 */
    void runBleSession(TransferContext ctx) {
        FileTransferTask task = ctx.task;
        String mac = task.getDeviceMac();
        try {
            sessionSlots.acquire(); // maxConcurrentTransfers 并发上限，超额排队（§7.6）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        pollingScheduler.suspendPolling(mac);   // 传输期间暂停轮询（§7.6）
        connectionScheduler.pin(mac, "FILE_TRANSFER"); // pinned 占槽，不被时间片/抢占踢出
        try {
            TransferAdapter adapter = null;
            while (true) {
                if (ctx.cancelRequested) {
                    cancelTask(ctx);
                    return;
                }
                if (ctx.pauseRequested) {
                    doPause(ctx);
                    if (awaitResumeOrCancel(ctx)) {
                        cancelTask(ctx);
                        return;
                    }
                    adapter = null; // 恢复后重新建连握手
                }
                DeviceController controller = deviceRegistry.findByMac(mac);
                if (controller == null) {
                    failTask(ctx, 2003, "device removed");
                    return;
                }
                if (!ensureConnected(ctx, controller)) {
                    failTask(ctx, 1001, "connect timeout"); // §12.9 1xxx 超时
                    return;
                }
                if (adapter == null) {
                    adapter = adapterFactory.create(task, controller);
                    // 断点续传：DUT 不支持偏移写入时从头重传（§7.6）。
                    if (task.getTransferredOffset() > 0 && !adapter.supportsOffsetWrite()) {
                        AgentLog.w(TAG, "device does not support offset write, restart: " + mac);
                        task.setTransferredOffset(0);
                    }
                    task.setState(FileTransferTask.FileTransferState.HANDSHAKE);
                    try {
                        adapter.handshake(task, controller);
                    } catch (TransferAdapter.TransferException e) {
                        failTask(ctx, 4003, "handshake rejected: " + e.getMessage());
                        return;
                    }
                    task.setState(FileTransferTask.FileTransferState.TRANSFERRING);
                }
                int outcome = transferWindows(ctx, adapter, controller);
                if (outcome == 0) {
                    completeTask(ctx);
                    return;
                } else if (outcome == 1) {
                    adapter = null; // 暂停/断线后连接已重建，必须重新握手（断点续传由 offset 保证）
                    continue;
                } else {
                    return; // 失败/取消已处理
                }
            }
        } finally {
            connectionScheduler.unpin(mac);
            pollingScheduler.resumePolling(mac);
            sessionSlots.release();
        }
    }

    /**
     * 窗口推进循环（§16.5 伪代码）。
     *
     * @return 0 = 传输完成；1 = 需回到外层循环（暂停/断线）；2 = 失败或取消（已处理）
     */
    private int transferWindows(TransferContext ctx, TransferAdapter adapter,
                                DeviceController controller) {
        FileTransferTask task = ctx.task;
        long lastProgressTime = clock.getAsLong();
        long lastProgressOffset = task.getTransferredOffset();
        while (task.getTransferredOffset() < task.getTotalSize()) {
            if (ctx.cancelRequested || ctx.pauseRequested) {
                return 1;
            }
            if (!controller.isReady()) {
                // 传输中断线（§12.8）：重连后从 transferredOffset 续传。
                AgentLog.w(TAG, "device disconnected mid-transfer: " + task.getDeviceMac()
                        + " offset=" + task.getTransferredOffset());
                return 1;
            }
            int chunkSize = task.getChunkSize();
            int startSeq = (int) (task.getTransferredOffset() / chunkSize) + 1;
            int totalChunks = (int) ((task.getTotalSize() + chunkSize - 1) / chunkSize);
            int endSeq = Math.min(startSeq + task.getWindowSize() - 1, totalChunks);
            int windowSeq = (startSeq - 1) / task.getWindowSize();
            try {
                for (int seq = startSeq; seq <= endSeq; seq++) {
                    adapter.sendChunk(readChunk(ctx.file, seq, chunkSize, task.getTotalSize()), seq);
                }
            } catch (TransferAdapter.TransferException | java.io.IOException e) {
                AgentLog.w(TAG, "send chunk failed: " + e.getMessage());
                return 1; // 写入失败按断线处理：重连后续传
            }
            TransferAdapter.WindowAck ack = adapter.waitWindowAck(windowSeq, CHUNK_ACK_TIMEOUT_MS);
            if (ack == TransferAdapter.WindowAck.OK) {
                task.setTransferredOffset(Math.min((long) endSeq * chunkSize, task.getTotalSize()));
                task.setRetryCount(0);
                lastProgressTime = reportProgress(ctx, lastProgressTime, lastProgressOffset);
                lastProgressOffset = task.getTransferredOffset();
            } else if (ack == TransferAdapter.WindowAck.NAK) {
                task.setRetryCount(task.getRetryCount() + 1);
                AgentLog.w(TAG, "window NAK, resend (retry " + task.getRetryCount() + ")");
                if (task.getRetryCount() > MAX_WINDOW_RETRY) {
                    failTask(ctx, 4002, "window crc failed after retries"); // §12.9 4002
                    return 2;
                }
            } else {
                // 分块无响应超时（§12.8）：暂停、放槽、上报，等待服务器决策（RESUME/cancel）。
                ctx.pauseRequested = true;
                task.setState(FileTransferTask.FileTransferState.PAUSED);
                reportError(1001, "chunk ack timeout, transfer paused", task.getDeviceMac());
                doPause(ctx);
                if (awaitResumeOrCancel(ctx)) {
                    cancelTask(ctx);
                    return 2;
                }
                return 1;
            }
        }
        return 0;
    }

    private long reportProgress(TransferContext ctx, long lastTime, long lastOffset) {
        long now = clock.getAsLong();
        FileTransferTask task = ctx.task;
        long elapsed = Math.max(1, now - lastTime);
        long bytesPerSec = (task.getTransferredOffset() - lastOffset) * 1000L / elapsed;
        double percent = task.getTotalSize() == 0 ? 100.0
                : task.getTransferredOffset() * 100.0 / task.getTotalSize();
        stateReporter.reportFileProgress(task.getTaskId(), percent, bytesPerSec);
        return now;
    }

    /** 申请 pinned 槽位并等待设备 READY（超时 = connectTimeout + setupBudget + 余量）。 */
    private boolean ensureConnected(TransferContext ctx, DeviceController controller) {
        String mac = ctx.task.getDeviceMac();
        connectionScheduler.requestSlot(ConnectionRequest.now(mac,
                ConnectionRequest.PRIORITY_HIGH, ConnectionRequest.Reason.FILE_TRANSFER));
        long deadline = clock.getAsLong()
                + config.getConnectTimeoutMs() + config.getSetupBudgetMs() + 2000L;
        while (!controller.isReady()) {
            if (ctx.cancelRequested || clock.getAsLong() > deadline) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 暂停：放槽让其他设备使用，保留 transferredOffset（§7.7）。 */
    private void doPause(TransferContext ctx) {
        ctx.task.setState(FileTransferTask.FileTransferState.PAUSED);
        String mac = ctx.task.getDeviceMac();
        connectionScheduler.unpin(mac);
        connectionScheduler.releaseSlot(mac);
        AgentLog.i(TAG, "transfer paused: " + ctx.task.getTaskId()
                + " offset=" + ctx.task.getTransferredOffset());
    }

    /** 阻塞等待恢复或取消；返回 true = 收到取消。恢复后重新 pin 占槽。 */
    private boolean awaitResumeOrCancel(TransferContext ctx) {
        synchronized (ctx.monitor) {
            while (ctx.pauseRequested && !ctx.cancelRequested) {
                try {
                    ctx.monitor.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
        }
        if (!ctx.cancelRequested) {
            // 恢复：重新 pinned 占槽（外层循环会重建连与握手）。
            ctx.task.setState(FileTransferTask.FileTransferState.PENDING);
            connectionScheduler.pin(ctx.task.getDeviceMac(), "FILE_TRANSFER");
        }
        return ctx.cancelRequested;
    }

    // ==================== 取消 / 暂停恢复（FILE_CANCEL / PAUSE_DEVICE，§7.6/§7.7） ====================

    /** FILE_CANCEL：任意时刻可取消（§7.6）。幂等。 */
    public void cancelTransfer(String taskId) {
        TransferContext ctx = tasks.get(taskId);
        if (ctx == null || isTerminal(ctx.task.getState())) {
            return;
        }
        ctx.cancelRequested = true;
        if (activeDownloadTaskId != null && activeDownloadTaskId.equals(taskId)) {
            activeDownloadTaskId = null;
            ctx.task.setState(FileTransferTask.FileTransferState.CANCELLED);
            reportFileResult(ctx, 2004, "cancelled");
            deleteQuietly(ctx.file);
            promoteNextDownload();
            return;
        }
        synchronized (ctx.monitor) {
            ctx.monitor.notifyAll();
        }
    }

    /** PAUSE_DEVICE 交互（§7.7）：默认挂起保留偏移；abortTransfer=true 中止。 */
    public void pauseTransferForDevice(String mac, boolean abortTransfer) {
        for (TransferContext ctx : tasks.values()) {
            if (!ctx.task.getDeviceMac().equals(mac) || isTerminal(ctx.task.getState())) {
                continue;
            }
            if (abortTransfer) {
                ctx.cancelRequested = true;
            } else {
                ctx.pauseRequested = true;
            }
            synchronized (ctx.monitor) {
                ctx.monitor.notifyAll();
            }
        }
    }

    /** 中止全部进行中任务（§7.8 RESET）：逐任务上报 FILE_RESULT（cancelled/2004）。 */
    public void cancelAll() {
        for (TransferContext ctx : tasks.values()) {
            if (!isTerminal(ctx.task.getState())) {
                ctx.cancelRequested = true;
                if (activeDownloadTaskId != null
                        && activeDownloadTaskId.equals(ctx.task.getTaskId())) {
                    activeDownloadTaskId = null;
                    ctx.task.setState(FileTransferTask.FileTransferState.CANCELLED);
                    reportFileResult(ctx, 2004, "cancelled");
                    deleteQuietly(ctx.file);
                }
                synchronized (ctx.monitor) {
                    ctx.monitor.notifyAll();
                }
            }
        }
    }

    /** RESUME_DEVICE 交互（§7.7）：挂起的传输按断点续传恢复。 */
    public void resumeTransferForDevice(String mac) {
        for (TransferContext ctx : tasks.values()) {
            if (!ctx.task.getDeviceMac().equals(mac)
                    || ctx.task.getState() != FileTransferTask.FileTransferState.PAUSED) {
                continue;
            }
            ctx.pauseRequested = false;
            synchronized (ctx.monitor) {
                ctx.monitor.notifyAll();
            }
        }
    }

    // ==================== 终态处理与存储管理（M4-2） ====================

    private void completeTask(TransferContext ctx) {
        ctx.task.setState(FileTransferTask.FileTransferState.COMPLETED);
        reportFileResult(ctx, 0, null);
        deleteQuietly(ctx.file); // 完成后立即删除（§7.6 清理策略）
        AgentLog.i(TAG, "transfer completed: " + ctx.task.getTaskId());
    }

    private void failTask(TransferContext ctx, int errorCode, String detail) {
        ctx.task.setState(FileTransferTask.FileTransferState.FAILED);
        reportFileResult(ctx, errorCode, detail);
        // 失败任务文件保留 failedTaskRetentionDays（§7.6），由 cleanupRetainedFiles 清理。
        if (activeDownloadTaskId != null && activeDownloadTaskId.equals(ctx.task.getTaskId())) {
            activeDownloadTaskId = null;
            promoteNextDownload();
        }
        AgentLog.w(TAG, "transfer failed: " + ctx.task.getTaskId()
                + " errorCode=" + errorCode + " detail=" + detail);
    }

    private void cancelTask(TransferContext ctx) {
        ctx.task.setState(FileTransferTask.FileTransferState.CANCELLED);
        reportFileResult(ctx, 2004, "cancelled"); // §12.9：2004 命令已取消
        deleteQuietly(ctx.file);
        AgentLog.i(TAG, "transfer cancelled: " + ctx.task.getTaskId());
    }

    /** 失败任务文件超保留期清理；孤儿文件（进程重启残留）同策略处理。 */
    void cleanupRetainedFiles() {
        long retentionMs = config.getFailedTaskRetentionDays() * 24L * 3600 * 1000;
        File[] files = transferDir.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis(); // 墙钟：文件 mtime 比较，非调度计时
        for (File f : files) {
            String taskId = f.getName().endsWith(".bin")
                    ? f.getName().substring(0, f.getName().length() - 4) : f.getName();
            TransferContext ctx = tasks.get(taskId);
            boolean active = ctx != null && !isTerminal(ctx.task.getState());
            if (!active && now - f.lastModified() > retentionMs) {
                AgentLog.i(TAG, "cleanup retained file: " + f.getName());
                deleteQuietly(f);
            }
        }
    }

    private long dirSize() {
        long total = 0;
        File[] files = transferDir.listFiles();
        if (files != null) {
            for (File f : files) {
                total += f.length();
            }
        }
        return total;
    }

    /** 下载阶段结束（完成/失败/取消）后提升下一个排队任务。 */
    private void promoteNextDownload() {
        TransferContext oldest = null;
        for (TransferContext ctx : tasks.values()) {
            if (!ctx.downloadComplete && !isTerminal(ctx.task.getState()) && !ctx.cancelRequested) {
                if (oldest == null || ctx.task.getStartTime() < oldest.task.getStartTime()) {
                    oldest = ctx;
                }
            }
        }
        if (oldest != null) {
            beginDownload(oldest);
        }
    }

    // ==================== 上报与工具 ====================

    private void reportDownloadAck(String taskId, List<Integer> resendSeqs) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("resendSeqs", resendSeqs);
        stateReporter.report("FILE_DOWNLOAD_ACK", payload);
    }

    private void reportFileResult(TransferContext ctx, int errorCode, String detail) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", ctx.task.getTaskId());
        payload.put("errorCode", errorCode);
        if (detail != null) {
            payload.put("detail", detail);
        }
        stateReporter.report("FILE_RESULT", payload);
    }

    private void reportError(int errorCode, String message, String mac) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("errorCode", errorCode);
        payload.put("message", message);
        if (mac != null) {
            payload.put("deviceMac", mac);
        }
        stateReporter.report("ERROR", payload);
    }

    private static byte[] readChunk(File file, int seq, int chunkSize, long totalSize)
            throws java.io.IOException {
        long offset = (long) (seq - 1) * chunkSize;
        int len = (int) Math.min(chunkSize, totalSize - offset);
        byte[] chunk = new byte[len];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            raf.readFully(chunk);
        }
        return chunk;
    }

    private static byte[] sha256Of(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return digest.digest();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer tryReadSeq(byte[] frame) {
        if (frame == null || frame.length < 7) {
            return null;
        }
        return ((frame[3] & 0xFF) << 24) | ((frame[4] & 0xFF) << 16)
                | ((frame[5] & 0xFF) << 8) | (frame[6] & 0xFF);
    }

    private static boolean isTerminal(FileTransferTask.FileTransferState state) {
        return state == FileTransferTask.FileTransferState.COMPLETED
                || state == FileTransferTask.FileTransferState.FAILED
                || state == FileTransferTask.FileTransferState.CANCELLED;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            AgentLog.w(TAG, "cannot delete " + file);
        }
    }

    // ==================== 测试/观测接口 ====================

    public FileTransferTask.FileTransferState getTaskState(String taskId) {
        TransferContext ctx = tasks.get(taskId);
        return ctx == null ? null : ctx.task.getState();
    }

    public long getTransferredOffset(String taskId) {
        TransferContext ctx = tasks.get(taskId);
        return ctx == null ? -1 : ctx.task.getTransferredOffset();
    }

    public int getTaskCount() {
        return tasks.size();
    }

    public void shutdown() {
        sessionExecutor.shutdownNow();
    }
}
