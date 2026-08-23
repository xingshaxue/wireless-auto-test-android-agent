package com.longcheer.agent;

import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollStep;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.model.PollingTask;
import com.longcheer.agent.model.QueuedTask;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class ManagedDeviceInfoTest {

    private static final String MAC = "AA:BB:CC:DD:EE:FF";
    private static final String DID = "dut-001";

    @Test
    public void snapshot_isIndependentCopy() {
        ManagedDeviceInfo info = new ManagedDeviceInfo(DID, MAC);
        info.setPriority(5);
        info.setPersistent(true);
        info.setStateFlag("FLAG");
        info.setNextPollTime(12345L);
        info.setPollDataStale(true);

        ManagedDeviceInfo snap = info.snapshot();

        // 基础字段复制
        assertEquals(info.getDeviceId(), snap.getDeviceId());
        assertEquals(info.getMac(), snap.getMac());
        assertEquals(info.getPriority(), snap.getPriority());
        assertEquals(info.isPersistent(), snap.isPersistent());
        assertEquals(info.getStateFlag(), snap.getStateFlag());
        assertEquals(info.getNextPollTime(), snap.getNextPollTime());
        assertEquals(info.isPollDataStale(), snap.isPollDataStale());

        // 修改原对象不影响快照
        info.setPriority(99);
        info.setStateFlag("CHANGED");
        info.setNextPollTime(99999L);
        assertEquals(5, snap.getPriority());
        assertEquals("FLAG", snap.getStateFlag());
        assertEquals(12345L, snap.getNextPollTime());
    }

    @Test
    public void snapshot_deepCopiesPollingConfig() {
        ManagedDeviceInfo info = new ManagedDeviceInfo(DID, MAC);
        UUID char1 = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
        PollingConfig original = new PollingConfig(30000L,
                Arrays.asList(char1), Arrays.asList(char1), true);
        info.setPollingConfig(original);

        ManagedDeviceInfo snap = info.snapshot();
        assertEquals(original, snap.getPollingConfig());
        assertNotSame(original, snap.getPollingConfig());

        // 通过 getter 拿到的列表与原列表应独立
        assertNotSame(original.getReadCharacteristics(), snap.getPollingConfig().getReadCharacteristics());
    }

    @Test
    public void snapshot_deepCopiesLastPollResult() {
        ManagedDeviceInfo info = new ManagedDeviceInfo(DID, MAC);
        UUID charUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
        Map<UUID, byte[]> result = new HashMap<>();
        result.put(charUuid, new byte[]{0x01, 0x02, 0x03});
        info.setLastPollResult(result);

        ManagedDeviceInfo snap = info.snapshot();
        assertEquals(1, snap.getLastPollResult().size());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, snap.getLastPollResult().get(charUuid));

        // 修改原 Map 与 byte[] 不应影响快照
        result.clear();
        assertEquals(1, snap.getLastPollResult().size());

        byte[] originalArray = info.getLastPollResult().get(charUuid);
        originalArray[0] = (byte) 0xFF;
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, snap.getLastPollResult().get(charUuid));
    }

    @Test
    public void snapshot_deepCopiesPendingCommands() {
        ManagedDeviceInfo info = new ManagedDeviceInfo(DID, MAC);
        UUID svc = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
        UUID chr = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

        GattCommand cmd = GattCommand.simple(MAC, "req-1", GattCommand.Type.READ, svc, chr, null, GattCommand.Priority.HIGH);
        PollStep step = new PollStep(GattCommand.Type.READ, svc, chr, null, 3000, 1);
        PollingTask task = PollingTask.simple(MAC, Arrays.asList(step));

        info.getPendingCommands().offer(cmd);
        info.getPendingCommands().offer(task);

        ManagedDeviceInfo snap = info.snapshot();
        PriorityQueue<QueuedTask> snapQueue = snap.getPendingCommands();
        assertEquals(2, snapQueue.size());

        // 清空原队列不影响快照
        info.getPendingCommands().clear();
        assertEquals(2, snapQueue.size());

        // 快照中的任务应为深拷贝对象
        GattCommand snapCmd = (GattCommand) snapQueue.poll();
        assertNotSame(cmd, snapCmd);
        assertEquals(cmd, snapCmd);
    }

    @Test
    public void pendingCommands_ordersByPriorityThenEnqueueTime() {
        ManagedDeviceInfo info = new ManagedDeviceInfo(DID, MAC);
        UUID svc = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
        UUID chr = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

        long base = android.os.SystemClock.elapsedRealtime();
        GattCommand low1 = new GattCommand(MAC, "low1", GattCommand.Type.READ, svc, chr, null,
                3000, 1, GattCommand.Priority.LOW, base, Long.MAX_VALUE);
        GattCommand low2 = new GattCommand(MAC, "low2", GattCommand.Type.READ, svc, chr, null,
                3000, 1, GattCommand.Priority.LOW, base + 1, Long.MAX_VALUE);
        GattCommand normal = new GattCommand(MAC, "normal", GattCommand.Type.READ, svc, chr, null,
                3000, 1, GattCommand.Priority.NORMAL, base, Long.MAX_VALUE);
        GattCommand high = new GattCommand(MAC, "high", GattCommand.Type.READ, svc, chr, null,
                3000, 1, GattCommand.Priority.HIGH, base, Long.MAX_VALUE);

        info.getPendingCommands().offer(low2);
        info.getPendingCommands().offer(high);
        info.getPendingCommands().offer(low1);
        info.getPendingCommands().offer(normal);

        assertEquals("high", ((GattCommand) info.getPendingCommands().poll()).getRequestId());
        assertEquals("normal", ((GattCommand) info.getPendingCommands().poll()).getRequestId());
        assertEquals("low1", ((GattCommand) info.getPendingCommands().poll()).getRequestId());
        assertEquals("low2", ((GattCommand) info.getPendingCommands().poll()).getRequestId());
    }
}
