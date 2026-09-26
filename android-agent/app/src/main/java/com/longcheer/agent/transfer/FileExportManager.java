package com.longcheer.agent.transfer;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingScheduler;
import com.longcheer.agent.tcp.FileFrameCodec;
import com.longcheer.agent.tcp.TcpClient;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设备文件导出管理器（DUT→手机→server，docs/02 B.6）。
 *
 * <p>受理 FILE_EXPORT 命令（同 deviceMac 串行：单工作线程 FIFO；exportId 幂等），
 * 工作线程 pin 住设备驱动 {@link LcExporter} 跑 061/062/063 协议拉文件落盘，
 * 完成后每个文件经 TCP 二进制帧（EXPORT_FRAME/EXPORT_END，复用 §16.3 帧格式）
 * 上传 server 入库，最终上报 EXPORT_RESULT 事件。</p>
 *
 * <p>帧 payload 固定布局（每文件独立 seq 序列，从 1 连续编号）：</p>
 * <ul>
 *   <li>EXPORT_FRAME(0x05)：u16BE 文件名长度 + 文件名 UTF-8 + 文件数据；</li>
 *   <li>EXPORT_END(0x06)：u16BE 文件名长度 + 文件名 UTF-8 + 32B SHA-256。</li>
 * </ul>
 */
public class FileExportManager {

    private static final String TAG = "FileExportManager";

    /** 单帧 payload 上限（§16.3 为 64KB，留余量取 60KB，对齐 LogUploader）。 */
    static final int FRAME_PAYLOAD_MAX = 60 * 1024;

    /** LcExporter 工厂（生产走 GATT，测试注入 fake）。 */
    public interface ExporterFactory {
        LcExporter create();
    }

    /** 内部任务上下文。 */
    static final class ExportContext {
        final String exportId;
        final String mac;
        final String remotePath;
        volatile String state = "RUNNING"; // RUNNING / DONE / FAILED

        ExportContext(String exportId, String mac, String remotePath) {
            this.exportId = exportId;
            this.mac = mac;
            this.remotePath = remotePath;
        }
    }

    private final DeviceRegistry deviceRegistry;
    private final ConnectionScheduler connectionScheduler;
    private final PollingScheduler pollingScheduler;
    private final StateReporter stateReporter;
    private final AgentConfig config;
    private final File exportRootDir;
    private final TcpClient tcpClient;
    private final ExporterFactory exporterFactory;
    private final ExecutorService worker; // 单线程：同设备/整机导出串行

    private final Map<String, ExportContext> exports = new ConcurrentHashMap<>();

    /** SPP 导出通道开启器（生产 = SppExportOpener；null = 仅 BLE 通道）。 */
    public interface SppOpener {
        com.longcheer.agent.spp.SppByteStream open(String mac) throws Exception;
    }

    private static final String CHANNEL_SPP =
            com.longcheer.agent.config.DeviceConfig.TRANSFER_CHANNEL_SPP;
    private static final String CHANNEL_AUTO =
            com.longcheer.agent.config.DeviceConfig.TRANSFER_CHANNEL_AUTO;

    private final SppOpener sppOpener;

    public FileExportManager(DeviceRegistry deviceRegistry,
                             ConnectionScheduler connectionScheduler,
                             PollingScheduler pollingScheduler,
                             StateReporter stateReporter,
                             AgentConfig config,
                             File exportRootDir,
                             TcpClient tcpClient,
                             ExporterFactory exporterFactory,
                             SppOpener sppOpener) {
        this.deviceRegistry = deviceRegistry;
        this.connectionScheduler = connectionScheduler;
        this.pollingScheduler = pollingScheduler;
        this.stateReporter = stateReporter;
        this.config = config;
        this.exportRootDir = exportRootDir;
        this.tcpClient = tcpClient;
        this.exporterFactory = exporterFactory;
        this.sppOpener = sppOpener;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FileExport");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 受理导出命令。
     *
     * @return 0 受理（重复 exportId 幂等视为已受理）；2003 设备不存在
     */
    public synchronized int startExport(String exportId, String mac, String remotePath) {
        ExportContext existing = exports.get(exportId);
        if (existing != null && "RUNNING".equals(existing.state)) {
            return 0; // 幂等：重复下发视为已受理
        }
        if (deviceRegistry.findByMac(mac) == null) {
            return 2003;
        }
        ExportContext ctx = new ExportContext(exportId, mac, remotePath);
        exports.put(exportId, ctx);
        AgentLog.i(TAG, "export accepted: " + exportId + " mac=" + mac + " path=" + remotePath);
        launchExport(ctx);
        return 0;
    }

    /** 启动导出工作线程（包可见，测试可覆盖为同步执行）。 */
    void launchExport(ExportContext ctx) {
        worker.execute(() -> runExport(ctx));
    }

    /** 导出主流程：pin 占槽建连 → LC 拉文件 → TCP 回传 → EXPORT_RESULT。 */
    void runExport(ExportContext ctx) {
        String mac = ctx.mac;
        int errorCode = 0;
        String detail = null;
        List<Map<String, Object>> files = new ArrayList<>();
        pollingScheduler.suspendPolling(mac);      // 导出期间暂停轮询（对齐文件传输）
        connectionScheduler.pin(mac, "FILE_EXPORT"); // pinned 占槽，不被时间片/抢占踢出
        LcExporter exporter = null;
        try {
            DeviceController controller = deviceRegistry.findByMac(mac);
            if (controller == null) {
                errorCode = 2003;
                detail = "device removed";
            } else if (!ensureConnected(controller)) {
                errorCode = 1001;
                detail = "connect timeout";
            } else {
                File dir = new File(exportRootDir, ctx.exportId);
                List<LcExporter.ExportedFile> exported = null;
                // 通道选择（DeviceConfig.transferChannel，与文件传输同一配置项）：
                // "ble" = LC 通道；"spp" = RFCOMM 承载；"auto" = 先 SPP 失败回退 BLE。
                String channel = controller.snapshot().getTransferChannel();
                boolean wantSpp = sppOpener != null && (CHANNEL_SPP.equals(channel)
                        || CHANNEL_AUTO.equals(channel));
                if (wantSpp) {
                    try {
                        exported = exportViaSpp(controller, ctx.remotePath, dir);
                        AgentLog.i(TAG, "SPP 通道导出成功: " + exported.size() + " 个文件");
                    } catch (Exception e) {
                        if (CHANNEL_SPP.equals(channel)) {
                            throw e;
                        }
                        AgentLog.w(TAG, "SPP 导出失败，回退 BLE: " + e.getMessage());
                    }
                }
                if (exported == null) {
                    exporter = exporterFactory.create();
                    exported = exporter.export(controller, ctx.remotePath, dir);
                }
                for (LcExporter.ExportedFile f : exported) {
                    uploadFile(f);
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", f.name);
                    item.put("size", f.size);
                    files.add(item);
                }
            }
        } catch (LcExporter.ExportException e) {
            errorCode = 4003; // 导出协议失败（对齐握手拒绝码段，detail 携原因）
            detail = e.getMessage();
        } catch (Exception e) {
            errorCode = 2001;
            detail = String.valueOf(e.getMessage());
        } finally {
            closeQuietly(exporter);
            connectionScheduler.unpin(mac);
            pollingScheduler.resumePolling(mac);
        }
        ctx.state = errorCode == 0 ? "DONE" : "FAILED";
        reportResult(ctx, files, errorCode, detail);
        // 本地暂存清理：成功已上传、失败无断点续传价值，一律删除
        deleteDirQuietly(new File(exportRootDir, ctx.exportId));
    }

    /**
     * 经 SPP 通道导出。目录模式拆成"一次 LS + 每文件独立 SPP 会话"
     * （真机实证：多文件共用一条 SPP 会话时固件导出流路由不稳定、061 死信；
     * 而每文件新开一条 SPP 会话直接 061 稳定且 ~40KB/s）。
     */
    private List<LcExporter.ExportedFile> exportViaSpp(DeviceController controller,
                                                       String remotePath, File dir)
            throws Exception {
        String macAddr = controller.snapshot().getMac();
        if (remotePath.endsWith("/")) {
            List<String> names;
            com.longcheer.agent.spp.SppByteStream lsStream = sppOpener.open(macAddr);
            try {
                LcExporter lsExporter = exporterFactory.create();
                try {
                    names = lsExporter.listRemoteDirOverSpp(controller, remotePath, lsStream);
                } finally {
                    closeQuietly(lsExporter);
                }
            } finally {
                lsStream.close();
            }
            List<LcExporter.ExportedFile> out = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            boolean first = true;
            for (String name : names) {
                String path = name.startsWith("/") ? name : remotePath + name;
                try {
                    out.addAll(exportViaSpp(controller, path, dir)); // 单文件=独立新会话
                    first = false;
                } catch (Exception e) {
                    if (first) {
                        throw e; // 首文件失败 = 通道未就绪，整体快速中止
                    }
                    AgentLog.w(TAG, "SPP 目录单文件失败跳过: " + path + " - " + e.getMessage());
                    failures.add(path);
                }
            }
            if (out.isEmpty()) {
                throw new LcExporter.ExportException("SPP 目录导出全部失败: " + failures);
            }
            return out;
        }
        com.longcheer.agent.spp.SppByteStream stream = sppOpener.open(macAddr);
        try {
            LcExporter exporter = exporterFactory.create();
            try {
                return exporter.exportOverSpp(controller, remotePath, dir, stream);
            } finally {
                closeQuietly(exporter);
            }
        } finally {
            stream.close();
        }
    }

    /** 单文件经 EXPORT_FRAME/EXPORT_END 帧上传（seq 从 1 连续编号）。 */
    private void uploadFile(LcExporter.ExportedFile f) throws java.io.IOException {
        byte[] nameBytes = f.name.getBytes(StandardCharsets.UTF_8);
        int dataCap = FRAME_PAYLOAD_MAX - 2 - nameBytes.length;
        int seq = 1;
        byte[] buf = new byte[Math.max(1, dataCap)];
        try (FileInputStream in = new FileInputStream(f.file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                byte[] payload = new byte[2 + nameBytes.length + n];
                writeNameHeader(payload, nameBytes);
                System.arraycopy(buf, 0, payload, 2 + nameBytes.length, n);
                tcpClient.sendFrame(FileFrameCodec.encode(
                        FileFrameCodec.FrameType.EXPORT_FRAME, seq++, payload));
            }
        }
        byte[] end = new byte[2 + nameBytes.length + 32];
        writeNameHeader(end, nameBytes);
        System.arraycopy(f.sha256, 0, end, 2 + nameBytes.length, 32);
        tcpClient.sendFrame(FileFrameCodec.encode(
                FileFrameCodec.FrameType.EXPORT_END, seq, end));
    }

    private static void writeNameHeader(byte[] payload, byte[] nameBytes) {
        payload[0] = (byte) ((nameBytes.length >>> 8) & 0xFF);
        payload[1] = (byte) (nameBytes.length & 0xFF);
        System.arraycopy(nameBytes, 0, payload, 2, nameBytes.length);
    }

    /** 申请 pinned 槽位并等待设备 READY（超时 = connectTimeout + setupBudget + 余量）。 */
    private boolean ensureConnected(DeviceController controller) {
        String mac = controller.snapshot().getMac();
        connectionScheduler.requestSlot(ConnectionRequest.now(mac,
                ConnectionRequest.PRIORITY_HIGH, ConnectionRequest.Reason.FILE_TRANSFER));
        long deadline = System.currentTimeMillis()
                + config.getConnectTimeoutMs() + config.getSetupBudgetMs() + 2000L;
        while (!controller.isReady()) {
            if (System.currentTimeMillis() > deadline) {
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

    private void reportResult(ExportContext ctx, List<Map<String, Object>> files,
                              int errorCode, String detail) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("exportId", ctx.exportId);
        payload.put("deviceMac", ctx.mac);
        payload.put("files", files);
        payload.put("errorCode", errorCode);
        if (detail != null) {
            payload.put("detail", detail);
        }
        stateReporter.report("EXPORT_RESULT", payload);
    }

    /** 释放导出器资源（幂等、不抛出）。 */
    private static void closeQuietly(LcExporter exporter) {
        if (exporter == null) {
            return;
        }
        try {
            exporter.close();
        } catch (RuntimeException e) {
            AgentLog.w(TAG, "exporter close failed: " + e.getMessage());
        }
    }

    private static void deleteDirQuietly(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) {
                    AgentLog.w(TAG, "cannot delete " + f);
                }
            }
        }
        if (dir.exists() && !dir.delete()) {
            AgentLog.w(TAG, "cannot delete " + dir);
        }
    }

    // ==================== 测试/观测接口 ====================

    public String getExportState(String exportId) {
        ExportContext ctx = exports.get(exportId);
        return ctx == null ? null : ctx.state;
    }

    public int getExportCount() {
        return exports.size();
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
