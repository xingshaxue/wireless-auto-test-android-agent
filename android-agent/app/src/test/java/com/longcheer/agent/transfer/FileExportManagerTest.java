package com.longcheer.agent.transfer;

import com.longcheer.agent.ble.GattClientProvider;
import com.longcheer.agent.ble.GattResponseBus;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.registry.DeviceRegistryImpl;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingScheduler;
import com.longcheer.agent.tcp.FileFrameCodec;
import com.longcheer.agent.tcp.TcpClient;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileExportManager 单测：受理（2003/幂等）→ pin 占槽 → fake 导出器落盘 →
 * TCP EXPORT_FRAME/EXPORT_END 帧回传 → EXPORT_RESULT 事件。
 */
public class FileExportManagerTest {

    private static final String MAC = "AA:BB:CC:DD:EE:03";

    /** 捕获 sendFrame 字节的 fake TCP 客户端。 */
    static class CapturingTcpClient implements TcpClient {
        final List<byte[]> frames = new ArrayList<>();

        @Override public void connect(String host, int port) { }
        @Override public void disconnect() { }
        @Override public void close() { }
        @Override public void sendJson(Map<String, Object> msg) { }
        @Override public void sendFrame(byte[] frame) { frames.add(frame.clone()); }
        @Override public void setListener(com.longcheer.agent.tcp.TcpListener listener) { }
        @Override public boolean isConnected() { return true; }
    }

    private DeviceRegistryImpl registry;
    private ConnectionScheduler connectionScheduler;
    private PollingScheduler pollingScheduler;
    private StateReporter reporter;
    private CapturingTcpClient tcpClient;
    private File exportRoot;
    private DeviceController controller;
    private ManagedDeviceInfo info;

    /** 测试用导出器：跳过 BLE，直接落盘给定内容。 */
    private static LcExporter fakeExporter(Map<String, byte[]> files) {
        return new LcExporter(mock(GattClientProvider.class), new GattResponseBus()) {
            @Override
            public List<ExportedFile> export(DeviceController device, String remotePath,
                                             File exportDir) throws ExportException {
                if (!exportDir.isDirectory() && !exportDir.mkdirs()) {
                    throw new ExportException("fake mkdirs failed");
                }
                List<ExportedFile> out = new ArrayList<>();
                for (Map.Entry<String, byte[]> e : files.entrySet()) {
                    File f = new File(exportDir, e.getKey());
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(e.getValue());
                    } catch (java.io.IOException ex) {
                        throw new ExportException("fake write failed");
                    }
                    out.add(new ExportedFile(e.getKey(), remotePath + e.getKey(), f,
                            e.getValue().length, sha256(e.getValue())));
                }
                return out;
            }
        };
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private FileExportManager newManager(LcExporter exporter) {
        return newManager(exporter, null);
    }

    private FileExportManager newManager(LcExporter exporter,
                                         FileExportManager.SppOpener sppOpener) {
        return new FileExportManager(registry, connectionScheduler, pollingScheduler,
                reporter, mockConfig(), exportRoot, tcpClient, () -> exporter, sppOpener) {
            @Override
            void launchExport(ExportContext ctx) {
                runExport(ctx); // 同步驱动
            }
        };
    }

    private static AgentConfig mockConfig() {
        AgentConfig config = mock(AgentConfig.class);
        when(config.getConnectTimeoutMs()).thenReturn(10000L);
        when(config.getSetupBudgetMs()).thenReturn(4000L);
        return config;
    }

    @Before
    public void setUp() {
        registry = new DeviceRegistryImpl();
        controller = mock(DeviceController.class);
        info = new ManagedDeviceInfo("dut-1", MAC);
        info.setState(DeviceState.READY);
        when(controller.snapshot()).thenReturn(info);
        when(controller.isReady()).thenReturn(true);
        registry.register(controller);
        connectionScheduler = mock(ConnectionScheduler.class);
        pollingScheduler = mock(PollingScheduler.class);
        reporter = mock(StateReporter.class);
        tcpClient = new CapturingTcpClient();
        exportRoot = new File(System.getProperty("java.io.tmpdir"),
                "export-mgr-test-" + System.nanoTime());
    }

    private static byte[] content(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    @Test
    public void sppDirectoryOpensFreshSessionPerFile() throws Exception {
        // 真机实证固件在"一条 SPP 会话跑多文件"时导出流路由不稳定（061 死信），
        // 生产路径 = 一次 LS + 每文件独立 SPP 会话。
        info.setTransferChannel("spp");
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        files.put("a.txt", new byte[]{1, 2, 3});
        files.put("b.txt", new byte[]{4, 5, 6, 7});
        LcExporter exporter = new LcExporter(mock(GattClientProvider.class),
                new GattResponseBus()) {
            @Override
            public List<ExportedFile> export(DeviceController device, String remotePath,
                                             File exportDir) throws ExportException {
                // 单文件模式：只落盘 remotePath 指定的那个文件
                String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
                byte[] data = files.get(name);
                if (data == null) {
                    throw new ExportException("no such file: " + remotePath);
                }
                if (!exportDir.isDirectory() && !exportDir.mkdirs()) {
                    throw new ExportException("fake mkdirs failed");
                }
                File f = new File(exportDir, name);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(data);
                } catch (java.io.IOException ex) {
                    throw new ExportException("fake write failed");
                }
                List<ExportedFile> out = new ArrayList<>();
                out.add(new ExportedFile(name, remotePath, f, data.length, sha256(data)));
                return out;
            }

            @Override
            public List<String> listRemoteDirOverSpp(DeviceController device, String dirPath,
                    com.longcheer.agent.spp.SppByteStream channel) {
                return new ArrayList<>(files.keySet());
            }
        };
        java.util.concurrent.atomic.AtomicInteger opens =
                new java.util.concurrent.atomic.AtomicInteger();
        FileExportManager.SppOpener opener = mac -> {
            opens.incrementAndGet();
            return mock(com.longcheer.agent.spp.SppByteStream.class);
        };
        FileExportManager manager = newManager(exporter, opener);

        assertEquals(0, manager.startExport("export-spp1", MAC, "/logs/"));

        assertEquals("DONE", manager.getExportState("export-spp1"));
        assertEquals("LS 一次 + 每文件一条会话", 1 + files.size(), opens.get());
        // 两文件均经 EXPORT_END 上报（各带 sha）
        long endCount = tcpClient.frames.stream()
                .map(FileFrameCodec::decode)
                .filter(f -> f.type == FileFrameCodec.FrameType.EXPORT_END)
                .count();
        assertEquals(files.size(), endCount);
    }

    @Test
    public void happyPathUploadsFramesAndReportsResult() {
        // 130000B → EXPORT_FRAME×3（60KB 上限减文件名头）+ EXPORT_END。
        byte[] data = content(130000);
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        files.put("a.bin", data);
        FileExportManager manager = newManager(fakeExporter(files));

        assertEquals(0, manager.startExport("export-t1", MAC, "/data/"));

        assertEquals("DONE", manager.getExportState("export-t1"));
        // pin/unpin 与轮询暂停/恢复
        verify(connectionScheduler).pin(MAC, "FILE_EXPORT");
        verify(connectionScheduler).unpin(MAC);
        verify(pollingScheduler).suspendPolling(MAC);
        verify(pollingScheduler).resumePolling(MAC);

        // 帧序列：EXPORT_FRAME seq 1..3 + EXPORT_END seq 4（每文件独立编号）
        List<FileFrameCodec.Frame> decoded = new ArrayList<>();
        for (byte[] raw : tcpClient.frames) {
            decoded.add(FileFrameCodec.decode(raw));
        }
        assertEquals(4, decoded.size());
        byte[] nameBytes = "a.bin".getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream reassembled = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) {
            FileFrameCodec.Frame f = decoded.get(i);
            assertEquals(FileFrameCodec.FrameType.EXPORT_FRAME, f.type);
            assertEquals(i + 1, f.seq);
            // payload = u16BE 文件名长度 + 文件名 UTF-8 + 数据
            int nameLen = ((f.payload[0] & 0xFF) << 8) | (f.payload[1] & 0xFF);
            assertEquals(nameBytes.length, nameLen);
            assertArrayEquals(nameBytes,
                    java.util.Arrays.copyOfRange(f.payload, 2, 2 + nameLen));
            reassembled.write(f.payload, 2 + nameLen, f.payload.length - 2 - nameLen);
        }
        assertArrayEquals(data, reassembled.toByteArray());
        FileFrameCodec.Frame end = decoded.get(3);
        assertEquals(FileFrameCodec.FrameType.EXPORT_END, end.type);
        assertEquals(4, end.seq);
        assertArrayEquals(sha256(data), java.util.Arrays.copyOfRange(
                end.payload, 2 + nameBytes.length, end.payload.length));

        // EXPORT_RESULT：errorCode=0，files 携 name/size
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("EXPORT_RESULT"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals("export-t1", payload.get("exportId"));
        assertEquals(MAC, payload.get("deviceMac"));
        assertEquals(Integer.valueOf(0), payload.get("errorCode"));
        List<?> items = (List<?>) payload.get("files");
        assertEquals(1, items.size());
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("a.bin", item.get("name"));
        assertEquals(130000L, item.get("size"));

        // 本地暂存已清理
        assertTrue(!new File(exportRoot, "export-t1").exists());
    }

    @Test
    public void unknownDeviceReturns2003() {
        FileExportManager manager = newManager(fakeExporter(new java.util.HashMap<>()));
        assertEquals(2003, manager.startExport("export-t2", "FF:FF:FF:FF:FF:FF", "/data/a.bin"));
    }

    @Test
    public void exportFailureReportsErrorCode() {
        LcExporter failing = new LcExporter(mock(GattClientProvider.class),
                new GattResponseBus()) {
            @Override
            public List<ExportedFile> export(DeviceController device, String remotePath,
                                             File exportDir) throws ExportException {
                throw new ExportException("061 无响应（重发 3 次仍失败）: /data/a.bin");
            }
        };
        FileExportManager manager = newManager(failing);

        assertEquals(0, manager.startExport("export-t3", MAC, "/data/a.bin"));

        assertEquals("FAILED", manager.getExportState("export-t3"));
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("EXPORT_RESULT"), captor.capture());
        assertEquals(Integer.valueOf(4003), captor.getValue().get("errorCode"));
        assertTrue(String.valueOf(captor.getValue().get("detail")).contains("061"));
        assertTrue(tcpClient.frames.isEmpty());
    }

    @Test
    public void duplicateExportIdAfterTerminalIsAcceptedAgain() {
        // exportId 幂等仅限进行中；终态后同 id 可再次受理（重试语义）。
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        files.put("a.bin", content(10));
        FileExportManager manager = newManager(fakeExporter(files));

        assertEquals(0, manager.startExport("export-t4", MAC, "/data/a.bin"));
        assertEquals("DONE", manager.getExportState("export-t4"));
        assertEquals(0, manager.startExport("export-t4", MAC, "/data/a.bin"));
        assertEquals(1, manager.getExportCount());
    }
}
