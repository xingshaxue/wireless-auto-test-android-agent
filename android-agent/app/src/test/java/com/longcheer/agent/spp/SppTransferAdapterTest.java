package com.longcheer.agent.spp;

import com.longcheer.agent.ble.GattClient;
import com.longcheer.agent.ble.GattClientProvider;
import com.longcheer.agent.ble.GattResponseBus;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.FileTransferTask;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.transfer.LcProtoTransferAdapter;
import com.longcheer.agent.transfer.TransferAdapter;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SppTransferAdapter 单测（SPP 加速通道，RFCOMM 承载 33x 协议）。
 *
 * <p>FakeBleClient：BLE LC 通道假固件——AT 写命令即时经 GattResponseBus 回 Notify
 * （开经典蓝牙两步），setNotification 同步回 onNotifySubscribed（CCCD 写完成）。
 * FakeSppChannel：内存 RFCOMM 通道——命令按脚本同步回 ASCII 应答（可选 \r\n 尾缀），
 * 数据累积满一帧（39600 数据 + 4 CRC）后回 "310"/"311"；应答经真实 SppClient
 * 读线程与邮箱路径到达适配器。</p>
 */
public class SppTransferAdapterTest {

    private static final String MAC = "AA:BB:CC:DD:EE:02";

    /** BLE 侧假固件（开经典蓝牙 AT 序列）。 */
    static class FakeBleClient implements GattClient {
        final List<byte[]> writes = new ArrayList<>();
        final GattResponseBus bus;
        final String mac;
        boolean rejectBtEnable;   // BT_ENABLE 回 "ERROR"
        boolean silentBtEnable;   // BT_ENABLE 不回包（超时场景）

        FakeBleClient(GattResponseBus bus, String mac) {
            this.bus = bus;
            this.mac = mac;
        }

        private void respond(String ascii) {
            bus.onNotify(mac, LcProtoTransferAdapter.LC_CHAR_UUID,
                    ascii.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public boolean writeCharacteristic(UUID serviceUuid, UUID charUuid, byte[] payload,
                                           boolean noResponse) {
            assertEquals(LcProtoTransferAdapter.LC_SERVICE_UUID, serviceUuid);
            assertEquals(LcProtoTransferAdapter.LC_CHAR_UUID, charUuid);
            assertTrue("AT 写入一律 WRITE_NR", noResponse);
            writes.add(payload.clone());
            String cmd = new String(payload, StandardCharsets.US_ASCII);
            if (cmd.equals("00AT^BT_ENABLE")) {
                if (rejectBtEnable) {
                    respond("ERROR");
                } else if (!silentBtEnable) {
                    respond("ENABLE_BT=OK\r\n");
                }
            } else if (cmd.equals("00AT^BT_ACCESS_SET=3")) {
                respond("OK=BT_SCAN, 3");
            }
            return true;
        }

        @Override
        public void setNotification(UUID serviceUuid, UUID charUuid, boolean enable) {
            bus.onNotifySubscribed(mac, charUuid, 0);
        }

        @Override public void connect() { }
        @Override public void disconnectAndClose() { }
        @Override public void discoverServices() { }
        @Override public void requestMtu(int mtu) { }
        @Override public void readCharacteristic(UUID serviceUuid, UUID charUuid) { }
    }

    /** 内存 RFCOMM 通道：write 触发脚本应答投入读缓冲，read 阻塞取（-1 = 关闭）。 */
    static class FakeSppChannel implements SppClient.SocketChannel {
        final List<byte[]> writes = new ArrayList<>();
        final List<byte[]> blocks = new ArrayList<>();
        long totalSize;
        long remaining;
        int expectedFrameLen;
        boolean dataMode;
        final ArrayDeque<String> blockAckScript = new ArrayDeque<>(); // 空 = 恒 "310"
        String openReply = "330";
        boolean failFinish;   // "32" → "321"
        boolean muteAcks;     // 数据块不回 ACK（超时场景）
        boolean crlfSuffix;   // 应答带 \r\n 尾缀
        private final Object lock = new Object();
        private final ByteArrayOutputStream readBuf = new ByteArrayOutputStream();
        private final ByteArrayOutputStream blockBuf = new ByteArrayOutputStream();
        private boolean closedFlag;

        private void respond(String ascii) {
            if (crlfSuffix) {
                ascii = ascii + "\r\n";
            }
            byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
            synchronized (lock) {
                readBuf.write(bytes, 0, bytes.length);
                lock.notifyAll();
            }
        }

        @Override
        public void write(byte[] data) throws IOException {
            synchronized (lock) {
                if (closedFlag) {
                    throw new IOException("channel closed");
                }
                writes.add(data.clone());
            }
            // "34" 停止命令（best-effort，可能出现在数据传输态）
            if (data.length == 2 && data[0] == '3' && data[1] == '4') {
                respond("340");
                return;
            }
            if (!dataMode) {
                String cmd = new String(data, StandardCharsets.US_ASCII);
                if (cmd.startsWith("30")) {
                    respond("300");
                } else if (cmd.startsWith("33")) {
                    respond(openReply);
                    dataMode = true;
                    remaining = totalSize;
                    expectedFrameLen = (int) Math.min(SppTransferAdapter.SPP_BLOCK_DATA_SIZE,
                            remaining) + 4;
                } else if (cmd.equals("32")) {
                    respond(failFinish ? "321" : "320");
                }
                return;
            }
            synchronized (lock) {
                blockBuf.write(data, 0, data.length);
                if (blockBuf.size() >= expectedFrameLen) {
                    byte[] frame = blockBuf.toByteArray();
                    blockBuf.reset();
                    blocks.add(frame);
                    String ack = blockAckScript.isEmpty() ? "310" : blockAckScript.poll();
                    if ("310".equals(ack)) {
                        remaining -= frame.length - 4;
                        expectedFrameLen = (int) Math.min(SppTransferAdapter.SPP_BLOCK_DATA_SIZE,
                                remaining) + 4;
                        if (remaining <= 0) {
                            dataMode = false; // 后续 "32" 是命令帧
                        }
                    }
                    if (!muteAcks) {
                        respond(ack);
                    }
                }
            }
        }

        @Override
        public int read(byte[] buf) throws IOException {
            synchronized (lock) {
                while (readBuf.size() == 0) {
                    if (closedFlag) {
                        return -1;
                    }
                    try {
                        lock.wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                byte[] pending = readBuf.toByteArray();
                readBuf.reset();
                System.arraycopy(pending, 0, buf, 0, pending.length);
                return pending.length;
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                closedFlag = true;
                lock.notifyAll();
            }
        }
    }

    /** 假连接器：记录连接次数，可脚本化失败。 */
    static class FakeConnector implements SppClient.Connector {
        final FakeSppChannel channel = new FakeSppChannel();
        int connectCount;
        boolean failConnect;

        @Override
        public SppClient.SocketChannel connect(String mac, long timeoutMs) throws IOException {
            connectCount++;
            if (failConnect) {
                throw new IOException("simulated connect failure");
            }
            return channel;
        }
    }

    private GattResponseBus bus;
    private FakeBleClient bleClient;
    private FakeConnector connector;
    private SppClient spp;
    private DeviceController controller;
    private SppTransferAdapter adapter;

    @Before
    public void setUp() {
        bus = new GattResponseBus();
        bleClient = new FakeBleClient(bus, MAC);
        GattClientProvider clientProvider = mock(GattClientProvider.class);
        when(clientProvider.getActiveClient(MAC)).thenReturn(bleClient);
        connector = new FakeConnector();
        spp = new SppClient(connector);
        controller = mock(DeviceController.class);
        ManagedDeviceInfo info = new ManagedDeviceInfo("dut-1", MAC);
        info.setState(DeviceState.READY);
        when(controller.snapshot()).thenReturn(info);
        adapter = new SppTransferAdapter(clientProvider, bus, spp);
    }

    private static FileTransferTask newTask(long totalSize, long offset) {
        FileTransferTask task = new FileTransferTask("t-unit", MAC, "ota.bin", totalSize, 509, 64);
        task.setTransferredOffset(offset);
        return task;
    }

    private static byte[] blockData(int len) {
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    private static String ascii(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }

    private FileTransferTask handshake(long totalSize, long offset) throws Exception {
        connector.channel.totalSize = totalSize;
        FileTransferTask task = newTask(totalSize, offset);
        adapter.handshake(task, controller);
        return task;
    }

    // ---------- 握手（开 BT / 协商 / 续传） ----------

    @Test
    public void handshakeFreshEnablesBtThenNegotiates() throws Exception {
        FileTransferTask task = handshake(80000, 0);

        // 先经 BLE 开经典蓝牙（真机校准序列），再走 SPP 33x 协商
        assertEquals(2, bleClient.writes.size());
        assertEquals("00AT^BT_ENABLE", ascii(bleClient.writes.get(0)));
        assertEquals("00AT^BT_ACCESS_SET=3", ascii(bleClient.writes.get(1)));
        assertEquals(1, connector.connectCount);
        assertEquals("300", ascii(connector.channel.writes.get(0)));
        assertEquals("33bin,/data/ota.bin,80000", ascii(connector.channel.writes.get(1)));
        // SPP 承载参数：块 39600B、窗口恒 1
        assertEquals(39600, task.getChunkSize());
        assertEquals(1, task.getWindowSize());
        assertTrue(adapter.supportsOffsetWrite());
    }

    @Test
    public void handshakeResumeUsesFirmwareAuthoritativeOffset() throws Exception {
        // 续传：task 偏移与固件不一致时以固件回执为准并回写（39600 对齐）。
        connector.channel.totalSize = 80000;
        connector.channel.openReply = "open file success:retransmission start length:39600";
        FileTransferTask task = newTask(80000, 79200);

        adapter.handshake(task, controller);

        assertEquals("301", ascii(connector.channel.writes.get(0)));
        assertEquals(39600, task.getTransferredOffset());
    }

    @Test
    public void handshakeResumeMisalignedOffsetRestartsFromZero() throws Exception {
        // 固件回执偏移非 39600 整块倍数 → 引擎 startSeq 整除截断会错位，回退 0 重传。
        connector.channel.totalSize = 80000;
        connector.channel.openReply = "open file success:retransmission start length:40000";
        FileTransferTask task = newTask(80000, 79200);

        adapter.handshake(task, controller);

        assertEquals("301", ascii(connector.channel.writes.get(0)));
        assertEquals(0, task.getTransferredOffset());
    }

    @Test
    public void handshakeFailsWhenBtEnableRejected() {
        // 开经典蓝牙被拒（回包不含 OK）→ TransferException，不尝试 RFCOMM 连接。
        bleClient.rejectBtEnable = true;
        try {
            adapter.handshake(newTask(100, 0), controller);
            fail("BT_ENABLE rejected should throw");
        } catch (TransferAdapter.TransferException e) {
            assertTrue(e.getMessage().contains("AT"));
        }
        assertEquals(0, connector.connectCount);
    }

    @Test
    public void handshakeFailsWhenBtEnableSilent() {
        // AT 无回包 → 有界等待后拒绝（单测调小超时），不尝试 RFCOMM 连接。
        bleClient.silentBtEnable = true;
        adapter.atTimeoutMs = 200;
        try {
            adapter.handshake(newTask(100, 0), controller);
            fail("BT_ENABLE timeout should throw");
        } catch (TransferAdapter.TransferException e) {
            assertTrue(e.getMessage().contains("AT"));
        }
        assertEquals(0, connector.connectCount);
    }

    @Test
    public void handshakeFailsWhenSppConnectFails() {
        connector.failConnect = true;
        try {
            adapter.handshake(newTask(100, 0), controller);
            fail("spp connect failure should throw");
        } catch (TransferAdapter.TransferException e) {
            assertTrue(e.getMessage().contains("spp connect failed"));
        }
    }

    // ---------- 分块写入（960 包切片 / CRC32-LE / ACK） ----------

    @Test
    public void sendChunkSlicesBy960AndAppendsCrc32LittleEndian() throws Exception {
        handshake(39600, 0);
        connector.channel.writes.clear();

        byte[] data = blockData(39600);
        adapter.sendChunk(data, 1);

        // 39604B 帧按 960B/包切片 → 42 包（41×960 + 244）
        assertEquals(42, connector.channel.writes.size());
        for (byte[] w : connector.channel.writes) {
            assertTrue(w.length <= 960);
        }
        assertEquals(1, connector.channel.blocks.size());
        byte[] frame = connector.channel.blocks.get(0);
        assertEquals(39604, frame.length);
        assertArrayEquals(data, Arrays.copyOf(frame, 39600));
        // CRC-32/ISO-HDLC（java.util.zip.CRC32）4 字节小端，对块数据计算
        CRC32 crc = new CRC32();
        crc.update(data);
        long expected = crc.getValue();
        long actual = ((long) frame[39600] & 0xFF)
                | (((long) frame[39601] & 0xFF) << 8)
                | (((long) frame[39602] & 0xFF) << 16)
                | (((long) frame[39603] & 0xFF) << 24);
        assertEquals(expected, actual);
        assertEquals(TransferAdapter.WindowAck.OK, adapter.waitWindowAck(0, 2000));
    }

    @Test
    public void blockNak311ReturnsNak() throws Exception {
        handshake(39600, 0);
        connector.channel.blockAckScript.add("311");

        adapter.sendChunk(blockData(39600), 1);

        assertEquals(TransferAdapter.WindowAck.NAK, adapter.waitWindowAck(0, 2000));
    }

    @Test
    public void waitWindowAckTimesOutWithoutReply() throws Exception {
        handshake(39600, 0);
        connector.channel.muteAcks = true;

        adapter.sendChunk(blockData(39600), 1);

        assertEquals(TransferAdapter.WindowAck.TIMEOUT, adapter.waitWindowAck(0, 200));
    }

    @Test
    public void tailChunkFrameLengthFollowsRemaining() throws Exception {
        // 尾块 100B 数据：帧 = 100 + 4 CRC，按 960 切片仅 1 包。
        handshake(100, 0);
        connector.channel.writes.clear();

        adapter.sendChunk(blockData(100), 1);

        assertEquals(1, connector.channel.writes.size());
        assertEquals(104, connector.channel.writes.get(0).length);
        assertEquals(TransferAdapter.WindowAck.OK, adapter.waitWindowAck(0, 2000));
    }

    // ---------- 回包匹配（\r\n 尾缀） ----------

    @Test
    public void repliesWithCrlfSuffixAreAccepted() throws Exception {
        connector.channel.crlfSuffix = true;
        FileTransferTask task = handshake(39600, 0);
        connector.channel.writes.clear();

        adapter.sendChunk(blockData(39600), 1);
        assertEquals(TransferAdapter.WindowAck.OK, adapter.waitWindowAck(0, 2000));

        adapter.finish(task, controller);
        assertEquals("32", ascii(connector.channel.writes.get(connector.channel.writes.size() - 1)));
    }

    // ---------- 收尾校验（32 → 320/321） ----------

    @Test
    public void finishOkOn320() throws Exception {
        FileTransferTask task = handshake(39600, 0);
        adapter.sendChunk(blockData(39600), 1);
        adapter.waitWindowAck(0, 2000);

        adapter.finish(task, controller);
    }

    @Test
    public void finish321Throws() throws Exception {
        FileTransferTask task = handshake(39600, 0);
        connector.channel.failFinish = true;
        adapter.sendChunk(blockData(39600), 1);
        adapter.waitWindowAck(0, 2000);

        try {
            adapter.finish(task, controller);
            fail("321 should throw");
        } catch (TransferAdapter.TransferException e) {
            assertTrue(e.getMessage().contains("321"));
        }
    }

    // ---------- close 发 "34"（取消/暂停/失败） ----------

    @Test
    public void closeAbortsWith34WhenTransferIncomplete() throws Exception {
        handshake(39600, 0);
        connector.channel.writes.clear();

        adapter.close();

        assertEquals(1, connector.channel.writes.size());
        assertEquals("34", ascii(connector.channel.writes.get(0)));
        adapter.close(); // 幂等：不再发
        assertEquals(1, connector.channel.writes.size());
    }

    @Test
    public void closeSkips34AfterSuccessfulFinish() throws Exception {
        FileTransferTask task = handshake(39600, 0);
        adapter.sendChunk(blockData(39600), 1);
        adapter.waitWindowAck(0, 2000);
        adapter.finish(task, controller);
        connector.channel.writes.clear();

        adapter.close();

        assertTrue(connector.channel.writes.isEmpty());
    }
}
