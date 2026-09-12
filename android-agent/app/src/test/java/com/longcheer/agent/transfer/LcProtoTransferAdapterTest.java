package com.longcheer.agent.transfer;

import com.longcheer.agent.ble.GattClient;
import com.longcheer.agent.ble.GattClientProvider;
import com.longcheer.agent.ble.GattResponseBus;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.FileTransferTask;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.registry.DeviceRegistryImpl;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingScheduler;
import com.longcheer.agent.tcp.FileFrameCodec;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LcProtoTransferAdapter 单测（LC 工厂通道，docs/02-ota-file-transfer.md 通道 B）。
 *
 * <p>FakeLcClient 同步假固件：写命令即时经 GattResponseBus 回 Notify（生产路径为
 * GattClient.Callback → DeviceControllerImpl → responseBus.onNotify，单测直接驱动
 * responseBus 等价）；块数据累积满一帧（数据+4B CRC）后按脚本回 "310"/"311"。</p>
 */
public class LcProtoTransferAdapterTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final int DL_CHUNK = 4; // 下载帧块大小（首帧学习）

    /** 同步假固件 GattClient（lc_proto 通道 B）。 */
    static class FakeLcClient implements GattClient {
        final List<byte[]> writes = new ArrayList<>();
        final List<byte[]> blocks = new ArrayList<>();
        final GattResponseBus bus;
        final String mac;
        long totalSize;
        long remaining;
        int expectedFrameLen;
        boolean dataMode;
        final ArrayDeque<String> blockAckScript = new ArrayDeque<>(); // 空 = 恒 "310"
        boolean failOpen;     // "33" → "331"
        boolean failFinish;   // "32" → "321"
        String openReply = "330"; // "33" 的应答（续传时替换为 retransmission 文本）
        private final ByteArrayOutputStream blockBuf = new ByteArrayOutputStream();

        FakeLcClient(GattResponseBus bus, String mac) {
            this.bus = bus;
            this.mac = mac;
        }

        private void respond(String ascii) {
            bus.onNotify(mac, LcProtoTransferAdapter.LC_CHAR_UUID,
                    ascii.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void writeCharacteristic(UUID serviceUuid, UUID charUuid, byte[] payload,
                                        boolean noResponse) {
            assertEquals(LcProtoTransferAdapter.LC_SERVICE_UUID, serviceUuid);
            assertEquals(LcProtoTransferAdapter.LC_CHAR_UUID, charUuid);
            assertTrue("LC 通道写入一律 WRITE_NR（B.1）", noResponse);
            writes.add(payload.clone());
            if (!dataMode) {
                String cmd = new String(payload, StandardCharsets.US_ASCII);
                if (cmd.startsWith("30")) {
                    respond("300");
                } else if (cmd.startsWith("33")) {
                    if (failOpen) {
                        respond("331");
                        return;
                    }
                    respond(openReply);
                    dataMode = true;
                    remaining = totalSize;
                    expectedFrameLen = (int) Math.min(LcProtoTransferAdapter.LC_BLOCK_DATA_SIZE,
                            remaining) + 4;
                } else if (cmd.equals("32")) {
                    respond(failFinish ? "321" : "320");
                }
                return;
            }
            blockBuf.write(payload, 0, payload.length);
            if (blockBuf.size() >= expectedFrameLen) {
                byte[] frame = blockBuf.toByteArray();
                blockBuf.reset();
                blocks.add(frame);
                String ack = blockAckScript.isEmpty() ? "310" : blockAckScript.poll();
                if ("310".equals(ack)) {
                    remaining -= frame.length - 4;
                    expectedFrameLen = (int) Math.min(LcProtoTransferAdapter.LC_BLOCK_DATA_SIZE,
                            remaining) + 4;
                    if (remaining <= 0) {
                        dataMode = false; // 后续 "32" 是命令帧
                    }
                }
                respond(ack);
            }
        }

        @Override public void connect() { }
        @Override public void disconnectAndClose() { }
        @Override public void discoverServices() { }
        @Override public void requestMtu(int mtu) { }
        @Override public void readCharacteristic(UUID serviceUuid, UUID charUuid) { }
        @Override public void setNotification(UUID serviceUuid, UUID charUuid, boolean enable) { }
    }

    private GattResponseBus bus;
    private FakeLcClient client;
    private GattClientProvider clientProvider;
    private DeviceController controller;
    private LcProtoTransferAdapter adapter;

    @Before
    public void setUp() {
        bus = new GattResponseBus();
        client = new FakeLcClient(bus, MAC);
        clientProvider = mock(GattClientProvider.class);
        when(clientProvider.getActiveClient(MAC)).thenReturn(client);
        controller = mock(DeviceController.class);
        ManagedDeviceInfo info = new ManagedDeviceInfo("dut-1", MAC);
        info.setState(DeviceState.READY);
        when(controller.snapshot()).thenReturn(info);
        when(controller.isReady()).thenReturn(true);
        when(controller.getState()).thenReturn(DeviceState.READY);
        adapter = new LcProtoTransferAdapter(clientProvider, bus);
    }

    private static FileTransferTask newTask(long totalSize, long offset) {
        FileTransferTask task = new FileTransferTask("t-unit", MAC, "ota.bin", totalSize, 509, 64);
        task.setTransferredOffset(offset);
        return task;
    }

    // ---------- 适配器级 ----------

    @Test
    public void handshakeFreshUsesNoRetransAndRewritesChunking() throws Exception {
        client.totalSize = 10000;
        FileTransferTask task = newTask(10000, 0);

        adapter.handshake(task, controller);

        // B.2：协商 retrans='0'（全新传输）
        assertEquals("300", new String(client.writes.get(0), StandardCharsets.US_ASCII));
        assertEquals("33 OTA,/data/ota.bin,10000",
                new String(client.writes.get(1), StandardCharsets.US_ASCII));
        // B.3：握手改写分块参数（4480B 块、窗口恒 1）
        assertEquals(4480, task.getChunkSize());
        assertEquals(1, task.getWindowSize());
        assertTrue(adapter.supportsOffsetWrite());
    }

    @Test
    public void handshakeResumeUsesFirmwareAuthoritativeOffset() throws Exception {
        // B.4：task 偏移与固件不一致时以固件回执为准并回写。
        client.totalSize = 10000;
        client.openReply = "open file success:retransmission start length:4480";
        FileTransferTask task = newTask(10000, 8960);

        adapter.handshake(task, controller);

        assertEquals("301", new String(client.writes.get(0), StandardCharsets.US_ASCII));
        assertEquals(4480, task.getTransferredOffset());
    }

    @Test
    public void handshakeOpenFailureThrows() {
        // B.2："331" = 打开失败 → TransferException（引擎报 4003）。
        client.totalSize = 100;
        client.failOpen = true;
        try {
            adapter.handshake(newTask(100, 0), controller);
            fail("open rejected should throw");
        } catch (TransferAdapter.TransferException e) {
            assertTrue(e.getMessage().contains("331"));
        }
    }

    @Test
    public void sendChunkAppendsCrc32LittleEndianAndSlicesBy224() throws Exception {
        client.totalSize = 4480;
        FileTransferTask task = newTask(4480, 0);
        adapter.handshake(task, controller);
        client.writes.clear();

        byte[] data = new byte[4480];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        adapter.sendChunk(data, 1);

        // B.3：4484B 帧按 224B/包切片 → 21 包 WRITE_NR
        assertEquals(21, client.writes.size());
        for (byte[] w : client.writes) {
            assertTrue(w.length <= 224);
        }
        assertEquals(1, client.blocks.size());
        byte[] frame = client.blocks.get(0);
        assertEquals(4484, frame.length);
        assertArrayEquals(data, Arrays.copyOf(frame, 4480));
        // CRC-32/ISO-HDLC（java.util.zip.CRC32）4 字节小端
        CRC32 crc = new CRC32();
        crc.update(data);
        long expected = crc.getValue();
        long actual = ((long) frame[4480] & 0xFF)
                | (((long) frame[4481] & 0xFF) << 8)
                | (((long) frame[4482] & 0xFF) << 16)
                | (((long) frame[4483] & 0xFF) << 24);
        assertEquals(expected, actual);
    }

    // ---------- 引擎端到端（FileTransferManager + 真实 LC 适配器 + 假固件） ----------

    private DeviceRegistryImpl registry;
    private StateReporter reporter;
    private FileTransferManager manager;

    private void setUpEngine() {
        registry = new DeviceRegistryImpl();
        registry.register(controller);
        reporter = mock(StateReporter.class);
        AgentConfig config = mock(AgentConfig.class);
        when(config.getDiskQuotaMb()).thenReturn(1);
        when(config.getFailedTaskRetentionDays()).thenReturn(7);
        when(config.getMaxConcurrentTransfers()).thenReturn(1);
        when(config.getConnectTimeoutMs()).thenReturn(10000L);
        when(config.getSetupBudgetMs()).thenReturn(4000L);
        manager = new FileTransferManager(registry, mock(ConnectionScheduler.class),
                mock(PollingScheduler.class), reporter, config,
                new File(System.getProperty("java.io.tmpdir"), "lc-test-" + System.nanoTime()),
                (task, device) -> adapter, System::currentTimeMillis) {
            @Override
            void launchBleSession(TransferContext ctx) {
                runBleSession(ctx); // 同步驱动
            }
        };
    }

    private static byte[] fileContent(int totalSize) {
        byte[] content = new byte[totalSize];
        for (int i = 0; i < totalSize; i++) {
            content[i] = (byte) (i % 251);
        }
        return content;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void startAndFeed(String taskId, byte[] content) {
        client.totalSize = content.length;
        assertEquals(0, manager.startTransfer(taskId, "ota.bin", MAC, content.length,
                sha256(content), 509, 64));
        int total = (content.length + DL_CHUNK - 1) / DL_CHUNK;
        for (int seq = 1; seq <= total; seq++) {
            int off = (seq - 1) * DL_CHUNK;
            byte[] chunk = Arrays.copyOfRange(content, off, Math.min(off + DL_CHUNK, content.length));
            manager.onFrame(FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_FRAME, seq, chunk));
        }
        manager.onFrame(FileFrameCodec.encode(
                FileFrameCodec.FrameType.FILE_END, 0, sha256(content)));
    }

    @Test
    public void fullTransferFlowCompletesWithFinishVerify() {
        // B.7：协商→开始→3 块（2 整 + 1 尾块 100B）→32 校验→完成。
        setUpEngine();
        byte[] content = fileContent(4480 * 2 + 100);

        startAndFeed("t-lc1", content);

        assertEquals(FileTransferTask.FileTransferState.COMPLETED, manager.getTaskState("t-lc1"));
        verify(reporter).report(eq("FILE_RESULT"), argThat(
                p -> p instanceof Map && Integer.valueOf(0).equals(((Map<?, ?>) p).get("errorCode"))));
        // 3 帧块：4484 / 4484 / 104（尾块 = 100 数据 + 4 CRC）
        assertEquals(3, client.blocks.size());
        assertEquals(4484, client.blocks.get(0).length);
        assertEquals(4484, client.blocks.get(1).length);
        assertEquals(104, client.blocks.get(2).length);
        assertArrayEquals(Arrays.copyOfRange(content, 8960, 9060),
                Arrays.copyOf(client.blocks.get(2), 100));
        // 收尾：写 "32" 并收到 "320"（B.2）
        List<String> cmds = new ArrayList<>();
        for (byte[] w : client.writes) {
            if (w.length < 100) {
                cmds.add(new String(w, StandardCharsets.US_ASCII));
            }
        }
        assertTrue(cmds.contains("32"));
    }

    @Test
    public void blockNak311TriggersResend() {
        // B.3："311" → 重发该块（首块脚本 NAK，其后 OK）。
        setUpEngine();
        client.blockAckScript.add("311");
        byte[] content = fileContent(4480);

        startAndFeed("t-lc2", content);

        assertEquals(FileTransferTask.FileTransferState.COMPLETED, manager.getTaskState("t-lc2"));
        assertEquals("NAK 后同块应重发", 2, client.blocks.size());
        assertArrayEquals(client.blocks.get(0), client.blocks.get(1));
    }

    @Test
    public void finishVerify321FailsTaskWith4002() {
        // B.2：finish 收 "321" → 任务失败 4002（§12.9 校验失败）。
        setUpEngine();
        client.failFinish = true;
        byte[] content = fileContent(4480);

        startAndFeed("t-lc3", content);

        assertEquals(FileTransferTask.FileTransferState.FAILED, manager.getTaskState("t-lc3"));
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("FILE_RESULT"), captor.capture());
        assertEquals(Integer.valueOf(4002), captor.getValue().get("errorCode"));
    }
}
