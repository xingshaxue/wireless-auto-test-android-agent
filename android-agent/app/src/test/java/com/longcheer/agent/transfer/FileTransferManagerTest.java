package com.longcheer.agent.transfer;

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

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileTransferManager 单测（SDD §7.6 / §12.8 / §16.3）。
 *
 * <p>默认同步驱动 BLE 会话（launchBleSession 直接调 runBleSession）；
 * 暂停/取消用例改用线程异步驱动。</p>
 */
public class FileTransferManagerTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final int DL_CHUNK = 4;   // 下载帧块大小（首帧学习）
    private static final int BLE_CHUNK = 8;  // BLE 侧分块
    private static final int WINDOW = 2;

    private DeviceRegistryImpl registry;
    private ConnectionScheduler connectionScheduler;
    private PollingScheduler pollingScheduler;
    private StateReporter reporter;
    private AgentConfig config;
    private long[] now;
    private File dir;
    private FakeAdapter adapter;
    private DeviceController controller;
    private FileTransferManager manager;

    /** 脚本化 TransferAdapter。 */
    static class FakeAdapter implements TransferAdapter {
        final List<WindowAck> ackScript = new ArrayList<>();
        final List<Integer> sentSeqs = new ArrayList<>();
        final AtomicInteger handshakes = new AtomicInteger(0);
        boolean offsetWrite = true;

        @Override
        public void handshake(FileTransferTask task, DeviceController device) {
            handshakes.incrementAndGet();
        }

        @Override
        public void sendChunk(byte[] chunk, int seq) {
            sentSeqs.add(seq);
        }

        @Override
        public synchronized WindowAck waitWindowAck(int windowSeq, long timeoutMs) {
            if (ackScript.isEmpty()) {
                return WindowAck.OK;
            }
            return ackScript.remove(0);
        }

        @Override
        public boolean supportsOffsetWrite() {
            return offsetWrite;
        }
    }

    @Before
    public void setUp() throws Exception {
        registry = new DeviceRegistryImpl();
        connectionScheduler = mock(ConnectionScheduler.class);
        pollingScheduler = mock(PollingScheduler.class);
        reporter = mock(StateReporter.class);
        config = mock(AgentConfig.class);
        when(config.getDiskQuotaMb()).thenReturn(1);
        when(config.getFailedTaskRetentionDays()).thenReturn(7);
        when(config.getMaxConcurrentTransfers()).thenReturn(1);
        when(config.getConnectTimeoutMs()).thenReturn(10000L);
        when(config.getSetupBudgetMs()).thenReturn(4000L);
        now = new long[]{100_000L};
        dir = new java.io.File(java.nio.file.Files.createTempDirectory("xfer").toFile().getPath());
        adapter = new FakeAdapter();

        controller = mock(DeviceController.class);
        ManagedDeviceInfo info = new ManagedDeviceInfo("dut-1", MAC);
        info.setState(DeviceState.READY);
        when(controller.snapshot()).thenReturn(info);
        when(controller.isReady()).thenReturn(true);
        when(controller.getState()).thenReturn(DeviceState.READY);
        registry.register(controller);

        manager = newManager(true);
    }

    /** syncSession=true 时 BLE 会话在调用线程同步执行，否则走线程池。 */
    private FileTransferManager newManager(boolean syncSession) {
        return new FileTransferManager(registry, connectionScheduler, pollingScheduler,
                reporter, config, dir, (task, device) -> adapter, () -> now[0]) {
            @Override
            void launchBleSession(TransferContext ctx) {
                if (syncSession) {
                    runBleSession(ctx);
                } else {
                    super.launchBleSession(ctx);
                }
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

    private int start(String taskId, byte[] content) {
        return manager.startTransfer(taskId, "fw.bin", MAC, content.length,
                sha256(content), BLE_CHUNK, WINDOW);
    }

    private void feedDownload(byte[] content, int... skipSeqs) {
        int total = (content.length + DL_CHUNK - 1) / DL_CHUNK;
        for (int seq = 1; seq <= total; seq++) {
            boolean skip = false;
            for (int s : skipSeqs) {
                if (s == seq) {
                    skip = true;
                }
            }
            if (skip) {
                continue;
            }
            int off = (seq - 1) * DL_CHUNK;
            byte[] chunk = Arrays.copyOfRange(content, off, Math.min(off + DL_CHUNK, content.length));
            manager.onFrame(FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_FRAME, seq, chunk));
        }
        manager.onFrame(FileFrameCodec.encode(
                FileFrameCodec.FrameType.FILE_END, 0, sha256(content)));
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> resendSeqsOf(Map<String, Object> payload) {
        return (List<Integer>) payload.get("resendSeqs");
    }

    @SuppressWarnings("unchecked")
    private static Integer errorCodeOf(Map<String, Object> payload) {
        return (Integer) payload.get("errorCode");
    }

    // ---------- M4-1 下载协议 ----------

    @Test
    public void downloadReadyThenAckThenTransferCompletes() {
        byte[] content = fileContent(10);
        assertEquals(0, start("t1", content));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("FILE_DOWNLOAD_READY"), captor.capture());
        assertEquals("t1", captor.getValue().get("taskId"));

        feedDownload(content); // 同步跑完 BLE 会话

        verify(reporter).report(eq("FILE_DOWNLOAD_ACK"), org.mockito.ArgumentMatchers.argThat(
                p -> p instanceof Map && ((List<?>) ((Map<String, Object>) p).get("resendSeqs")).isEmpty()));
        assertEquals(FileTransferTask.FileTransferState.COMPLETED, manager.getTaskState("t1"));
        verify(reporter).report(eq("FILE_RESULT"), org.mockito.ArgumentMatchers.argThat(
                p -> p instanceof Map && Integer.valueOf(0).equals(errorCodeOf((Map<String, Object>) p))));
        // pinned 占槽与轮询暂停/恢复（§7.6）
        verify(connectionScheduler).pin(MAC, "FILE_TRANSFER");
        verify(connectionScheduler).unpin(MAC);
        verify(pollingScheduler).suspendPolling(MAC);
        verify(pollingScheduler).resumePolling(MAC);
        // 完成后文件已删除（§7.6 清理策略）
        assertFalse(new File(dir, "t1.bin").exists());
    }

    @Test
    public void missingFrameListedInDownloadAck() {
        byte[] content = fileContent(12); // 3 帧
        start("t2", content);
        feedDownload(content, 2); // 跳过 seq=2

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("FILE_DOWNLOAD_ACK"), captor.capture());
        assertEquals(java.util.Collections.singletonList(2), resendSeqsOf(captor.getValue()));
        // 未下载完不进入 BLE 阶段（§7.6）
        assertEquals(FileTransferTask.FileTransferState.PENDING, manager.getTaskState("t2"));
        verify(connectionScheduler, never()).pin(any(), any());
    }

    @Test
    public void corruptFrameCrcListedForResend() {
        byte[] content = fileContent(8); // 2 帧
        start("t3", content);
        byte[] frame1 = FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_FRAME, 1,
                Arrays.copyOfRange(content, 0, 4));
        frame1[frame1.length - 1] ^= 0xFF; // 破坏 CRC
        manager.onFrame(frame1);
        manager.onFrame(FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_FRAME, 2,
                Arrays.copyOfRange(content, 4, 8)));
        manager.onFrame(FileFrameCodec.encode(
                FileFrameCodec.FrameType.FILE_END, 0, sha256(content)));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("FILE_DOWNLOAD_ACK"), captor.capture());
        assertEquals(java.util.Collections.singletonList(1), resendSeqsOf(captor.getValue()));
    }

    @Test
    public void hashMismatchFailsWith4001() {
        byte[] content = fileContent(8);
        manager.startTransfer("t4", "fw.bin", MAC, content.length, new byte[32], BLE_CHUNK, WINDOW);
        feedDownload(content); // FILE_END 哈希正确，但与命令下发的哈希不一致

        assertEquals(FileTransferTask.FileTransferState.FAILED, manager.getTaskState("t4"));
        verify(reporter).report(eq("FILE_RESULT"), org.mockito.ArgumentMatchers.argThat(
                p -> p instanceof Map && Integer.valueOf(4001).equals(errorCodeOf((Map<String, Object>) p))));
    }

    @Test
    public void resumeAfterTcpDisconnectReportsLastContiguousSeq() {
        byte[] content = fileContent(16); // 4 帧
        start("t5", content);
        for (int seq : new int[]{1, 2, 4}) { // 缺 3 → 最大连续 = 2
            int off = (seq - 1) * DL_CHUNK;
            manager.onFrame(FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_FRAME, seq,
                    Arrays.copyOfRange(content, off, off + DL_CHUNK)));
        }
        manager.onTcpDisconnected();
        manager.onTcpReconnected();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("FILE_DOWNLOAD_RESUME"), captor.capture());
        assertEquals(2, captor.getValue().get("lastSeq"));
    }

    // ---------- M4-2 存储管理 ----------

    @Test
    public void quotaExceededRejects3004() {
        byte[] big = fileContent(2 * 1024 * 1024); // 超 1MB 配额
        int code = manager.startTransfer("t6", "fw.bin", MAC, big.length,
                sha256(big), BLE_CHUNK, WINDOW);

        assertEquals(3004, code);
        verify(reporter).report(eq("ERROR"), org.mockito.ArgumentMatchers.argThat(
                p -> p instanceof Map && Integer.valueOf(3004).equals(errorCodeOf((Map<String, Object>) p))));
    }

    @Test
    public void unknownDeviceRejected2003() {
        int code = manager.startTransfer("t7", "fw.bin", "00:00:00:00:00:00",
                100, null, BLE_CHUNK, WINDOW);
        assertEquals(2003, code);
    }

    @Test
    public void retainedFailedFilesCleanedAfterRetention() throws Exception {
        File old = new File(dir, "old-task.bin");
        assertTrue(old.createNewFile());
        assertTrue(old.setLastModified(System.currentTimeMillis() - 8L * 24 * 3600 * 1000));
        start("t8", fileContent(4)); // startTransfer 内触发清理

        assertFalse(old.exists());
    }

    // ---------- M4-3 窗口 ACK / NAK / 超时 ----------

    @Test
    public void nakWindowIsResentThenCompletes() {
        byte[] content = fileContent(16); // 2 chunks = 1 window
        adapter.ackScript.add(TransferAdapter.WindowAck.NAK);
        start("t9", content);
        feedDownload(content);

        assertEquals(FileTransferTask.FileTransferState.COMPLETED, manager.getTaskState("t9"));
        assertTrue("窗口内块应重发", adapter.sentSeqs.size() >= 4);
    }

    @Test
    public void persistentNakFailsWith4002() {
        byte[] content = fileContent(16);
        for (int i = 0; i < 5; i++) {
            adapter.ackScript.add(TransferAdapter.WindowAck.NAK);
        }
        start("t10", content);
        feedDownload(content);

        assertEquals(FileTransferTask.FileTransferState.FAILED, manager.getTaskState("t10"));
        verify(reporter).report(eq("FILE_RESULT"), org.mockito.ArgumentMatchers.argThat(
                p -> p instanceof Map && Integer.valueOf(4002).equals(errorCodeOf((Map<String, Object>) p))));
    }

    @Test
    public void chunkAckTimeoutPausesThenResumeContinues() throws Exception {
        manager = newManager(false); // 异步会话
        byte[] content = fileContent(32); // 4 chunks = 2 windows
        adapter.ackScript.add(TransferAdapter.WindowAck.TIMEOUT); // 第一窗口超时
        start("t11", content);
        feedDownload(content);

        // 超时暂停：state=PAUSED 并上报 ERROR（§12.8）
        verify(reporter, timeout(5000)).report(eq("ERROR"), org.mockito.ArgumentMatchers.argThat(
                p -> p instanceof Map
                        && Integer.valueOf(1001).equals(errorCodeOf((Map<String, Object>) p))
                        && String.valueOf(((Map<String, Object>) p).get("message")).contains("timeout")));
        assertEquals(FileTransferTask.FileTransferState.PAUSED, manager.getTaskState("t11"));
        // 暂停期间放槽（§7.7）
        verify(connectionScheduler, timeout(5000)).releaseSlot(MAC);

        manager.resumeTransferForDevice(MAC);

        // 恢复后重新握手并续传完成
        verify(reporter, timeout(5000)).report(eq("FILE_RESULT"), org.mockito.ArgumentMatchers.argThat(
                p -> p instanceof Map && Integer.valueOf(0).equals(errorCodeOf((Map<String, Object>) p))));
        assertEquals(2, adapter.handshakes.get()); // 恢复后重新握手
    }

    // ---------- M4-4 FILE_PROGRESS 节流上报（§7.6） ----------

    @Test
    public void throttledProgressReportedMidWindow() {
        // sendChunk 每块推进假时钟 100ms → 大窗口内每 4 块（400ms）触发一次节流上报。
        adapter = new FakeAdapter() {
            @Override
            public void sendChunk(byte[] chunk, int seq) {
                super.sendChunk(chunk, seq);
                now[0] += 100L;
            }
        };
        byte[] content = fileContent(64); // 8 chunks，单窗口（window=64）
        assertEquals(0, manager.startTransfer("t20", "fw.bin", MAC, content.length,
                sha256(content), BLE_CHUNK, 64));
        feedDownload(content);

        assertEquals(FileTransferTask.FileTransferState.COMPLETED, manager.getTaskState("t20"));
        // 2 次窗口内节流上报（50%、100%）+ 1 次窗口 ACK 兜底上报（100%）
        ArgumentCaptor<Double> percent = ArgumentCaptor.forClass(Double.class);
        verify(reporter, times(3)).reportFileProgress(eq("t20"), percent.capture(), anyLong());
        List<Double> values = percent.getAllValues();
        assertEquals(50.0, values.get(0), 0.001);
        assertEquals(100.0, values.get(1), 0.001);
        assertEquals(100.0, values.get(2), 0.001);
    }

    @Test
    public void rapidChunksReportOnlyAtWindowAck() {
        // 假时钟不前进：窗口内不触发节流上报，仅每窗口 ACK 上报一次。
        byte[] content = fileContent(32); // 4 chunks = 2 windows
        start("t21", content);
        feedDownload(content);

        assertEquals(FileTransferTask.FileTransferState.COMPLETED, manager.getTaskState("t21"));
        verify(reporter, times(2)).reportFileProgress(eq("t21"), anyDouble(), anyLong());
    }

    // ---------- M4-5 续传与取消 ----------

    @Test
    public void offsetWriteUnsupportedRestartsFromZero() {
        adapter.offsetWrite = false;
        byte[] content = fileContent(16);
        start("t12", content);
        feedDownload(content);

        assertEquals(FileTransferTask.FileTransferState.COMPLETED, manager.getTaskState("t12"));
        assertEquals(Integer.valueOf(1), adapter.sentSeqs.get(0));
    }

    @Test
    public void resumeFromTransferredOffsetWhenSupported() {
        byte[] content = fileContent(32); // 4 chunks，2 windows
        start("t13", content);
        feedDownload(content);

        assertEquals(FileTransferTask.FileTransferState.COMPLETED, manager.getTaskState("t13"));
        assertEquals(1, adapter.handshakes.get());
        assertEquals(Integer.valueOf(1), adapter.sentSeqs.get(0));
    }

    @Test
    public void cancelDuringDownloadReports2004() {
        byte[] content = fileContent(8);
        start("t14", content);
        manager.cancelTransfer("t14");

        assertEquals(FileTransferTask.FileTransferState.CANCELLED, manager.getTaskState("t14"));
        verify(reporter).report(eq("FILE_RESULT"), org.mockito.ArgumentMatchers.argThat(
                p -> p instanceof Map && Integer.valueOf(2004).equals(errorCodeOf((Map<String, Object>) p))));
    }

    @Test
    public void cancelDuringBleTransferReports2004() throws Exception {
        manager = newManager(false);
        byte[] content = fileContent(32); // 2 windows
        adapter.ackScript.add(TransferAdapter.WindowAck.TIMEOUT); // 第一窗口超时 → 暂停等待
        start("t15", content);
        feedDownload(content);

        verify(reporter, timeout(5000)).report(eq("ERROR"), any()); // 等暂停生效
        manager.cancelTransfer("t15");

        verify(reporter, timeout(5000)).report(eq("FILE_RESULT"), org.mockito.ArgumentMatchers.argThat(
                p -> p instanceof Map && Integer.valueOf(2004).equals(errorCodeOf((Map<String, Object>) p))));
        assertEquals(FileTransferTask.FileTransferState.CANCELLED, manager.getTaskState("t15"));
    }
}
