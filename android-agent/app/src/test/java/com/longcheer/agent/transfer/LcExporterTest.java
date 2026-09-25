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
        int cccdStatus = 0;

        private byte[] current;
        private int offset;
        private byte[] lastGoodFrame;
        private boolean corruptPending;

        FakeExportFirmware(GattResponseBus bus, String mac) {
            this.bus = bus;
            this.mac = mac;
        }

        private void notifyBinary(byte[] frame) {
            if (splitFrameAt > 0 && frame.length > splitFrameAt + 1) {
                bus.onNotify(mac, LcExporter.LC_CHAR_UUID,
                        Arrays.copyOfRange(frame, 0, splitFrameAt));
                bus.onNotify(mac, LcExporter.LC_CHAR_UUID,
                        Arrays.copyOfRange(frame, splitFrameAt, frame.length));
            } else {
                bus.onNotify(mac, LcExporter.LC_CHAR_UUID, frame);
            }
        }

        private void notifyAscii(String line) {
            bus.onNotify(mac, LcExporter.LC_CHAR_UUID,
                    (line + "\r\n").getBytes(StandardCharsets.US_ASCII));
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
                    return; // 已无剩余（粘连首块场景多发的 062）
                }
                notifyBinary(buildBlock());
            }
            if (offset >= current.length && !noOver) {
                notifyAscii("FILE_EXPORT_OVER");
            }
        }

        @Override
        public boolean writeCharacteristic(UUID serviceUuid, UUID charUuid, byte[] payload,
                                           boolean noResponse) {
            assertEquals(LcExporter.LC_SERVICE_UUID, serviceUuid);
            assertEquals(LcExporter.LC_CHAR_UUID, charUuid);
            assertTrue("LC 通道写入一律 WRITE_NR", noResponse);
            writes.add(payload.clone());
            bus.onWrite(mac, charUuid, 0);
            String cmd = new String(payload, StandardCharsets.US_ASCII);
            if (cmd.startsWith("00AT^LS=")) {
                List<String> names = dirs.get(cmd.substring("00AT^LS=".length()));
                if (names != null) {
                    for (String name : names) {
                        notifyAscii("F " + files.get(cmd.substring("00AT^LS=".length())
                                + name).length + " " + name);
                    }
                }
                notifyAscii("OK");
            } else if (cmd.startsWith("061")) {
                open(cmd.substring(3));
            } else if (cmd.equals("062")) {
                serve(false);
            } else if (cmd.equals("063")) {
                serve(true);
            }
            return true;
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
