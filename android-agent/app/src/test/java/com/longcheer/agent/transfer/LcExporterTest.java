package com.longcheer.agent.transfer;

import com.longcheer.agent.ble.GattClient;
import com.longcheer.agent.ble.GattClientProvider;
import com.longcheer.agent.ble.GattResponseBus;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.ManagedDeviceInfo;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LcExporter 单测（LC 产测通道 061/062/063 文件导出，docs/02 B.6）。
 *
 * <p>FakeExportFirmware 同步假固件：写命令即时经 GattResponseBus 回 Notify
 * （生产路径为 GattClient.Callback → DeviceControllerImpl → responseBus.onNotify，
 * 单测直接驱动 responseBus 等价）；setNotification 同步回 onNotifySubscribed。
 * 062 按 blockSize 切块回 {@code @}+u32BE(块长)+数据+4B 大端累加和。</p>
 */
public class LcExporterTest {

    private static final String MAC = "AA:BB:CC:DD:EE:02";

    /** 同步假固件 GattClient（061/062/063 导出协议）。 */
    static class FakeExportFirmware implements GattClient {
        final GattResponseBus bus;
        final String mac;
        final Map<String, byte[]> files = new HashMap<>();      // 设备侧路径 → 内容
        final Map<String, List<String>> dirs = new HashMap<>(); // 目录 → 文件名列表
        final List<byte[]> writes = new ArrayList<>();
        int blockSize = 128;
        boolean glueSizeWithFirstBlock; // 061 大小帧与首数据帧粘连（同一 Notify）
        boolean corruptFirstBlockSum;   // 首数据块校验和故意错一次（走 063）
        int silentOpens;                // 前 N 次 061 不应答（超时重发场景）
        boolean noOver;                 // 不发 FILE_EXPORT_OVER（收尾超时场景）
        int splitFrameAt = -1;          // ≥0 时数据帧在该偏移切成两个 Notify 包
        int truncateBlockIndex = -1;    // ≥0 时第 N 块（0 起）截断发送（模拟下行 notify 丢包）
        int truncateBlockTo = 9;        // 截断后只发前 N 字节
        int junkAfterBlockIndex = -1;   // ≥0 时第 N 块后插入一个幽灵垃圾包（真机实测帧间插入）
        boolean dropNext062;            // 吞掉下一个 062（模拟上行 062 丢失）
        boolean freeRun;                // SPP 模式：061 后连续推完所有块 + 自动 OVER
        int cccdStatus = 0;

        private byte[] current;
        private int offset;
        private byte[] lastGoodFrame;
        private boolean corruptPending;
        private int blockCount;

        FakeExportFirmware(GattResponseBus bus, String mac) {
            this.bus = bus;
            this.mac = mac;
        }

        private void notifyBinary(byte[] frame) {
            if (splitFrameAt > 0 && frame.length > splitFrameAt + 1) {
                emit(Arrays.copyOfRange(frame, 0, splitFrameAt));
                emit(Arrays.copyOfRange(frame, splitFrameAt, frame.length));
            } else {
                emit(frame);
            }
        }

        private void notifyAscii(String line) {
            emit((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }

        /** 回包出口（默认 BLE notify；SPP fake 覆盖为入队）。 */
        void emit(byte[] data) {
            bus.onNotify(mac, LcExporter.LC_CHAR_UUID, data);
        }

        private static void putU32(byte[] buf, int off, long v) {
            buf[off] = (byte) ((v >>> 24) & 0xFF);
            buf[off + 1] = (byte) ((v >>> 16) & 0xFF);
            buf[off + 2] = (byte) ((v >>> 8) & 0xFF);
            buf[off + 3] = (byte) (v & 0xFF);
        }

        private byte[] buildBlock() {
            int len = (int) Math.min(blockSize, current.length - offset);
            byte[] data = Arrays.copyOfRange(current, offset, offset + len);
            offset += len;
            long sum = 0;
            for (byte b : data) {
                sum += b & 0xFF;
            }
            byte[] frame = new byte[5 + len + 4];
            frame[0] = '@';
            putU32(frame, 1, len);
            System.arraycopy(data, 0, frame, 5, len);
            putU32(frame, 5 + len, sum);
            lastGoodFrame = frame;
            if (corruptPending) {
                corruptPending = false;
                byte[] bad = frame.clone();
                bad[bad.length - 1] ^= 0xFF; // 破坏累加和
                return bad;
            }
            if (blockCount++ == truncateBlockIndex) {
                // 只发帧的前段（尾部 notify 丢失）；lastGoodFrame 保留完整帧供 063 重传
                return Arrays.copyOfRange(frame, 0, Math.min(truncateBlockTo, frame.length));
            }
            return frame;
        }

        private void open(String path) {
            if (silentOpens > 0) {
                silentOpens--;
                return; // 不应答：061 超时重发场景
            }
            current = files.get(path);
            offset = 0;
            lastGoodFrame = null;
            if (current == null) {
                notifyAscii("open error:-1");
                return;
            }
            corruptPending = corruptFirstBlockSum;
            byte[] sizeFrame = new byte[5];
            sizeFrame[0] = '@';
            putU32(sizeFrame, 1, current.length);
            if (glueSizeWithFirstBlock && current.length > 0) {
                // B319 实证：大小帧与首数据帧粘连（同一 Notify 包）
                byte[] first = buildBlock();
                byte[] glued = new byte[sizeFrame.length + first.length];
                System.arraycopy(sizeFrame, 0, glued, 0, sizeFrame.length);
                System.arraycopy(first, 0, glued, sizeFrame.length, first.length);
                notifyBinary(glued);
            } else {
                notifyBinary(sizeFrame);
                // 真机实证（2026-09-25 p67）：大小帧后固件立即自动推第 1 块，不等 062
                if (current.length > 0) {
                    notifyBinary(buildBlock());
                    // SPP 自由流（真机抓包）：061 后连续推完所有块 + 自动 OVER，无需 062
                    while (freeRun && offset < current.length) {
                        notifyBinary(buildBlock());
                    }
                    if (freeRun && !noOver) {
                        notifyAscii("AT^FILE_EXPORT_OVER\0");
                    }
                }
            }
        }

        private void serve(boolean retrans) {
            if (current == null) {
                return;
            }
            if (retrans) {
                if (lastGoodFrame == null) {
                    return;
                }
                notifyBinary(lastGoodFrame);
            } else {
                if (offset >= current.length) {
                    // 真机语义（B319）：062 拉到 EOF 时固件回 FILE_EXPORT_OVER。
                    if (!noOver) {
                        notifyAscii("AT^FILE_EXPORT_OVER\0");
                    }
                    return;
                }
                notifyBinary(buildBlock());
                if (blockCount - 1 == junkAfterBlockIndex) {
                    // 真机实测：帧间被插入一个 224B 非帧幽灵包（帧本身校验完好）
                    byte[] junk = new byte[224];
                    for (int i = 0; i < junk.length; i++) {
                        junk[i] = (byte) (i * 37 + 11);
                    }
                    notifyBinary(junk);
                }
            }
            if (offset >= current.length && !noOver) {
                notifyAscii("AT^FILE_EXPORT_OVER\0");
            }
        }

        @Override
        public boolean writeCharacteristic(UUID serviceUuid, UUID charUuid, byte[] payload,
                                           boolean noResponse) {
            assertEquals(LcExporter.LC_SERVICE_UUID, serviceUuid);
            assertEquals(LcExporter.LC_CHAR_UUID, charUuid);
            assertTrue("LC 通道写入一律 WRITE_NR", noResponse);
            bus.onWrite(mac, charUuid, 0);
            handleWrite(payload);
            return true;
        }

        /** 命令处理（BLE writeCharacteristic 与 SPP fake channel 共用）。 */
        void handleWrite(byte[] payload) {
            writes.add(payload.clone());
            String cmd = new String(payload, StandardCharsets.US_ASCII);
            if (cmd.endsWith("\0")) {
                cmd = cmd.substring(0, cmd.length() - 1); // SPP 061 的 C 字符串终止符
            }
            if (cmd.startsWith("00AT^LS=")) {
                List<String> names = dirs.get(cmd.substring("00AT^LS=".length()));
                if (names != null) {
                    String dir = cmd.substring("00AT^LS=".length());
                    for (String name : names) {
                        String key = name.startsWith("/") ? name : dir + name;
                        notifyAscii("F " + files.get(key).length + " " + name);
                    }
                }
                notifyAscii("OK");
            } else if (cmd.startsWith("061")) {
                open(cmd.substring(3));
            } else if (cmd.equals("062")) {
                if (dropNext062) {
                    dropNext062 = false; // 上行丢失：不应答
                } else {
                    serve(false);
                }
            } else if (cmd.equals("063")) {
                serve(true);
            }
        }

        @Override
        public void setNotification(UUID serviceUuid, UUID charUuid, boolean enable) {
            bus.onNotifySubscribed(mac, charUuid, cccdStatus);
        }

        @Override public void connect() { }
        @Override public void disconnectAndClose() { }
        @Override public void discoverServices() { }
        @Override public void requestMtu(int mtu) { }
        @Override public void readCharacteristic(UUID serviceUuid, UUID charUuid) { }
    }

    /** SPP 内存固件通道：write=命令处理（复用 FakeExportFirmware），read=阻塞队列取响应块。 */
    static class FakeSppChannel implements com.longcheer.agent.spp.SppClient.SocketChannel {
        final FakeExportFirmware fw;
        final java.util.concurrent.BlockingQueue<byte[]> incoming =
                new java.util.concurrent.LinkedBlockingQueue<>();
        volatile boolean closed;

        FakeSppChannel(GattResponseBus bus, String mac) {
            fw = new FakeExportFirmware(bus, mac) {
                @Override
                void emit(byte[] data) {
                    incoming.offer(data);
                }
            };
            // SPP 与 BLE 同为 062 拉取式（真机抓包：SPP 不会自由流，062 一发一块，
            // 区别只是块长 40960 级），mock 用默认拉取语义即可。
        }

        @Override
        public void write(byte[] data) {
            fw.handleWrite(data);
        }

        @Override
        public int read(byte[] buf) {
            try {
                byte[] c = incoming.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                if (c == null || closed) {
                    return -1;
                }
                System.arraycopy(c, 0, buf, 0, c.length);
                return c.length;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private GattResponseBus bus;
    private FakeExportFirmware firmware;
    private LcExporter exporter;
    private DeviceController controller;
    private File exportDir;

    @Before
    public void setUp() {
        bus = new GattResponseBus();
        firmware = new FakeExportFirmware(bus, MAC);
        GattClientProvider clientProvider = mock(GattClientProvider.class);
        when(clientProvider.getActiveClient(MAC)).thenReturn(firmware);
        controller = mock(DeviceController.class);
        ManagedDeviceInfo info = new ManagedDeviceInfo("dut-1", MAC);
        info.setState(DeviceState.READY);
        when(controller.snapshot()).thenReturn(info);
        when(controller.isReady()).thenReturn(true);
        exporter = new LcExporter(clientProvider, bus);
        exporter.cccdTimeoutMs = 500;
        exportDir = new File(System.getProperty("java.io.tmpdir"),
                "lc-export-test-" + System.nanoTime());
    }

    private static byte[] content(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> commands() {
        List<String> out = new ArrayList<>();
        for (byte[] w : firmware.writes) {
            out.add(new String(w, StandardCharsets.US_ASCII));
        }
        return out;
    }

    private int countCommand(String prefix) {
        int n = 0;
        for (String c : commands()) {
            if (c.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void progressCallbacksFireInOrder() throws Exception {
        // 进度回调：onFileStart → onFileProgress（末次收到=总大小）→ onFileDone；
        // 目录模式先触发 onDirectoryListed。
        byte[] data = content(300);
        firmware.files.put("/data/a.bin", data);
        List<String> events = new ArrayList<>();
        exporter.setProgressListener(new LcExporter.ProgressListener() {
            @Override public void onDirectoryListed(int fileCount) {
                events.add("ls:" + fileCount);
            }
            @Override public void onFileStart(String name, long totalBytes) {
                events.add("start:" + name + ":" + totalBytes);
            }
            @Override public void onFileProgress(String name, long received, long totalBytes) {
                events.add("progress:" + received + "/" + totalBytes);
            }
            @Override public void onFileDone(String name, long totalBytes) {
                events.add("done:" + name + ":" + totalBytes);
            }
        });

        exporter.export(controller, "/data/a.bin", exportDir);

        assertEquals("start:a.bin:300", events.get(0));
        assertEquals("done:a.bin:300", events.get(events.size() - 1));
        assertTrue(events.contains("progress:300/300"));
        assertFalse(events.stream().anyMatch(e -> e.startsWith("ls:")));
    }

    @Test
    public void progressDirectoryListedCallback() throws Exception {
        firmware.files.put("/logs/a.txt", content(10));
        firmware.files.put("/logs/b.txt", content(20));
        firmware.dirs.put("/logs/", Arrays.asList("a.txt", "b.txt"));
        List<String> events = new ArrayList<>();
        exporter.setProgressListener(new LcExporter.ProgressListener() {
            @Override public void onDirectoryListed(int fileCount) {
                events.add("ls:" + fileCount);
            }
            @Override public void onFileStart(String name, long totalBytes) { }
            @Override public void onFileProgress(String name, long received, long totalBytes) { }
            @Override public void onFileDone(String name, long totalBytes) {
                events.add("done:" + name);
            }
        });

        exporter.export(controller, "/logs/", exportDir);

        assertEquals("ls:2", events.get(0));
        assertTrue(events.contains("done:a.txt"));
        assertTrue(events.contains("done:b.txt"));
    }

    @Test
    public void singleFileFlowCompletes() throws Exception {
        // 单文件：061→大小帧，062×3 拉齐（128+128+44），FILE_EXPORT_OVER 收尾。
        byte[] data = content(300);
        firmware.files.put("/data/a.bin", data);

        List<LcExporter.ExportedFile> result = exporter.export(controller, "/data/a.bin", exportDir);

        assertEquals(1, result.size());
        LcExporter.ExportedFile f = result.get(0);
        assertEquals("a.bin", f.name);
        assertEquals(300, f.size);
        assertArrayEquals(data, Files.readAllBytes(f.file.toPath()));
        assertArrayEquals(sha256(data), f.sha256);
        List<String> cmds = commands();
        assertEquals("061/data/a.bin", cmds.get(0));
        assertEquals(3, countCommand("062"));
    }

    @Test
    public void directoryModeListsAndExportsEachFile() throws Exception {
        // 目录模式：AT^LS 列举（含 F 行最后 token 为文件名，OK 收尾）→ 逐文件 061 循环。
        firmware.files.put("/logs/a.txt", content(10));
        firmware.files.put("/logs/b.txt", content(20));
        firmware.dirs.put("/logs/", Arrays.asList("a.txt", "b.txt"));

        List<LcExporter.ExportedFile> result = exporter.export(controller, "/logs/", exportDir);

        assertEquals(2, result.size());
        assertEquals("a.txt", result.get(0).name);
        assertEquals("b.txt", result.get(1).name);
        assertArrayEquals(firmware.files.get("/logs/b.txt"),
                Files.readAllBytes(result.get(1).file.toPath()));
        assertTrue(commands().contains("00AT^LS=/logs/"));
        assertTrue(commands().contains("061/logs/a.txt"));
        assertTrue(commands().contains("061/logs/b.txt"));
    }

    @Test
    public void directoryModeAcceptsAbsoluteLsEntries() throws Exception {
        // 真机实测：固件 LS 条目回全路径（"F <size> /data/offlinelog//log4.gz"），
        // 绝对路径直接用，不再拼目录前缀。
        firmware.files.put("/logs/a.txt", content(10));
        firmware.files.put("/logs/b.txt", content(20));
        firmware.dirs.put("/logs/", Arrays.asList("/logs/a.txt", "/logs/b.txt"));

        List<LcExporter.ExportedFile> result = exporter.export(controller, "/logs/", exportDir);

        assertEquals(2, result.size());
        assertTrue(commands().contains("061/logs/a.txt"));
        assertTrue(commands().contains("061/logs/b.txt"));
        for (String cmd : commands()) {
            assertFalse("绝对路径不得再拼前缀: " + cmd, cmd.contains("/logs//logs/"));
        }
    }

    @Test
    public void sizeFrameGluedWithFirstDataFrame() throws Exception {
        // B319 实证：061 大小帧与首数据帧粘连，字节流消费须正确切分。
        byte[] data = content(300);
        firmware.files.put("/data/a.bin", data);
        firmware.glueSizeWithFirstBlock = true;

        List<LcExporter.ExportedFile> result = exporter.export(controller, "/data/a.bin", exportDir);

        assertArrayEquals(data, Files.readAllBytes(result.get(0).file.toPath()));
        assertArrayEquals(sha256(data), result.get(0).sha256);
    }

    @Test
    public void checksumFailureTriggers063Retrans() throws Exception {
        // 首块校验和错误 → 不落盘、发 063 重传 → 好块接续完成。
        byte[] data = content(300);
        firmware.files.put("/data/a.bin", data);
        firmware.corruptFirstBlockSum = true;

        List<LcExporter.ExportedFile> result = exporter.export(controller, "/data/a.bin", exportDir);

        assertArrayEquals(data, Files.readAllBytes(result.get(0).file.toPath()));
        assertEquals(1, countCommand("063"));
    }

    @Test
    public void openTimeoutResendCappedAt3() {
        // 061 无响应 5s 重发、上限 3 次（B319 无限重发是坑）。
        firmware.files.put("/data/a.bin", content(10));
        firmware.silentOpens = 10;
        exporter.openTimeoutMs = 120;

        long start = System.currentTimeMillis();
        try {
            exporter.export(controller, "/data/a.bin", exportDir);
            fail("061 无响应应抛 ExportException");
        } catch (LcExporter.ExportException e) {
            assertTrue(e.getMessage().contains("061"));
        }
        assertEquals("061 重发上限 3 次", 3, countCommand("061"));
        assertTrue("应在有界时间内返回", System.currentTimeMillis() - start < 3000);
    }

    @Test
    public void missingFileExportOverFails() {
        // 数据收齐但无 FILE_EXPORT_OVER → 有界等待后报错，不悬挂。
        firmware.files.put("/data/a.bin", content(10));
        firmware.noOver = true;
        exporter.overTimeoutMs = 150;

        try {
            exporter.export(controller, "/data/a.bin", exportDir);
            fail("缺 FILE_EXPORT_OVER 应抛 ExportException");
        } catch (LcExporter.ExportException e) {
            assertTrue(e.getMessage().contains("FILE_EXPORT_OVER"));
        }
    }

    @Test
    public void midBlockNotifyLossRecoversVia063() throws Exception {
        // 真机实测：长传输中 BLE notify 丢包 → 块收不全。此时绝不能发 062
        // （固件会推下一块，流永久错位），必须排干残块 + 063 重传当前块。
        byte[] data = content(400); // blockSize=128 → 4 块
        firmware.files.put("/data/big.bin", data);
        firmware.truncateBlockIndex = 1; // 第 2 块尾部 notify 丢失
        exporter.dataTimeoutMs = 600;

        List<LcExporter.ExportedFile> result = exporter.export(controller, "/data/big.bin", exportDir);

        assertArrayEquals(data, Files.readAllBytes(result.get(0).file.toPath()));
        assertArrayEquals(sha256(data), result.get(0).sha256);
        assertTrue("下行丢包须走 063 重传", countCommand("063") >= 1);
        // 块 1..3 各一次 062 + EOF 收尾一次 = 4；多发说明锁步被打乱
        assertEquals(4, countCommand("062"));
    }

    @Test
    public void interFrameJunkIsSkippedWithout063() throws Exception {
        // 真机实测（2026-09-26 抓包）：帧间被插入 224B 幽灵垃圾包，帧本身校验完好。
        // 期望：扫描重同步跳过垃圾，不走 063，文件逐字节正确。
        byte[] data = content(400); // blockSize=128 → 4 块
        firmware.files.put("/data/big.bin", data);
        firmware.junkAfterBlockIndex = 1; // 第 2 块后插幽灵包

        List<LcExporter.ExportedFile> result = exporter.export(controller, "/data/big.bin", exportDir);

        assertArrayEquals(data, Files.readAllBytes(result.get(0).file.toPath()));
        assertArrayEquals(sha256(data), result.get(0).sha256);
        assertEquals("帧间垃圾不得触发 063", 0, countCommand("063"));
        assertEquals(4, countCommand("062")); // 块 1..3 + EOF 收尾
    }

    @Test
    public void uplink062LossRecoversVia063AndReplayDiscard() throws Exception {
        // 上行 062 丢失场景（锁步核心）：agent 发 062 被吞 → 块未到 → 063。
        // 固件"当前块"是 agent 已写入的上一块 → 重播 → agent 识别丢弃 → 062 前进。
        byte[] data = content(400); // blockSize=128 → 4 块
        firmware.files.put("/data/big.bin", data);
        firmware.dropNext062 = true; // 吞掉块 1 的 062

        List<LcExporter.ExportedFile> result = exporter.export(controller, "/data/big.bin", exportDir);

        assertArrayEquals(data, Files.readAllBytes(result.get(0).file.toPath()));
        assertArrayEquals(sha256(data), result.get(0).sha256);
        assertEquals(data.length, result.get(0).size);
        assertTrue(countCommand("063") >= 1);
    }

    @Test
    public void sppExportCompletes() throws Exception {
        // SPP 承载（RFCOMM 字节流）跑同一套 061/062/063：单文件逐字节正确。
        FakeSppChannel channel = new FakeSppChannel(bus, MAC);
        byte[] data = content(300);
        channel.fw.files.put("/data/a.bin", data);
        com.longcheer.agent.spp.SppByteStream stream =
                new com.longcheer.agent.spp.SppByteStream((mac, timeoutMs) -> channel);
        stream.connect(MAC);
        try {
            List<LcExporter.ExportedFile> result =
                    exporter.exportOverSpp(controller, "/data/a.bin", exportDir, stream);
            assertArrayEquals(data, Files.readAllBytes(result.get(0).file.toPath()));
            assertArrayEquals(sha256(data), result.get(0).sha256);
        } finally {
            stream.close();
        }
    }

    @Test
    public void sppDirectoryExportCompletes() throws Exception {
        // SPP 承载目录模式：LS 列举 + 逐文件导出。
        FakeSppChannel channel = new FakeSppChannel(bus, MAC);
        channel.fw.files.put("/logs/a.txt", content(10));
        channel.fw.files.put("/logs/b.txt", content(20));
        channel.fw.dirs.put("/logs/", Arrays.asList("a.txt", "b.txt"));
        com.longcheer.agent.spp.SppByteStream stream =
                new com.longcheer.agent.spp.SppByteStream((mac, timeoutMs) -> channel);
        stream.connect(MAC);
        try {
            List<LcExporter.ExportedFile> result =
                    exporter.exportOverSpp(controller, "/logs/", exportDir, stream);
            assertEquals(2, result.size());
            assertEquals("a.txt", result.get(0).name);
            assertEquals("b.txt", result.get(1).name);
        } finally {
            stream.close();
        }
    }

    @Test
    public void splitNotifyFramesAreReassembled() throws Exception {
        // 数据帧跨两个 Notify 包拆分（帧头 3 字节 + 剩余），字节流消费无感。
        byte[] data = content(300);
        firmware.files.put("/data/a.bin", data);
        firmware.splitFrameAt = 3;

        List<LcExporter.ExportedFile> result = exporter.export(controller, "/data/a.bin", exportDir);

        assertArrayEquals(data, Files.readAllBytes(result.get(0).file.toPath()));
    }

    @Test
    public void cccdFailureRejectsExport() {
        // CCCD 订阅失败（同 LcProto 竞态防护）→ 直接拒绝，不发任何 061。
        firmware.files.put("/data/a.bin", content(10));
        firmware.cccdStatus = -1;

        try {
            exporter.export(controller, "/data/a.bin", exportDir);
            fail("CCCD 失败应抛 ExportException");
        } catch (LcExporter.ExportException e) {
            assertTrue(e.getMessage().contains("CCCD"));
        }
        assertEquals(0, countCommand("061"));
    }
}
