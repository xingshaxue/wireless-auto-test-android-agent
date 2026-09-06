package com.longcheer.agent.ble;

import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.GattResult;
import com.longcheer.agent.model.PollStep;
import com.longcheer.agent.model.PollingTask;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * GattExecutorImpl 单测（SDD §7.3.1 / §12.2）：动作序列顺序执行、失败重试一次后中止。
 *
 * <p>直接调用包可见的 runTask() 同步驱动，transport 用脚本化假实现。</p>
 */
public class GattExecutorImplTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final UUID CHAR_A = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_B = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");

    /** 脚本化 GattTransport：按队列返回结果，记录调用。 */
    private static class FakeTransport implements GattTransport {
        final List<GattResult> script = new ArrayList<>();
        final List<PollStep> calls = new ArrayList<>();

        @Override
        public GattResult execute(String deviceMac, PollStep step) {
            calls.add(step);
            if (script.isEmpty()) {
                return GattResult.ok(new byte[]{0x01});
            }
            return script.remove(0);
        }
    }

    private FakeTransport transport;
    private long[] now;
    private Map<UUID, byte[]> lastResults;
    private int lastErrorCode;
    private int lastRawStatus;
    private GattExecutorImpl executor;

    @Before
    public void setUp() {
        transport = new FakeTransport();
        now = new long[]{0L};
        lastResults = null;
        lastErrorCode = -1;
        executor = new GattExecutorImpl(transport, new GattExecutorImpl.TaskCallback() {
            @Override
            public void onTaskResult(String deviceMac, Map<UUID, byte[]> results,
                                     int errorCode, int rawStatus) {
                lastResults = results;
                lastErrorCode = errorCode;
                lastRawStatus = rawStatus;
            }

            @Override
            public void onCommandResult(GattCommand command, GattResult result) {
            }
        }, () -> now[0]);
    }

    @Test
    public void testWriteThenReadSequenceExecutesInOrder() {
        // 先写后读场景（§7.3.1）：WRITE 请求码 → READ 响应。
        PollStep write = new PollStep(GattCommand.Type.WRITE, null, CHAR_A, new byte[]{0x01}, 3000, 1);
        PollStep read = new PollStep(GattCommand.Type.READ, null, CHAR_B, null, 3000, 1);
        transport.script.add(GattResult.ok(null));
        transport.script.add(GattResult.ok(new byte[]{0x42}));

        executor.runTask(PollingTask.simple(MAC, Arrays.asList(write, read)));

        assertEquals(2, transport.calls.size());
        assertEquals(GattCommand.Type.WRITE, transport.calls.get(0).type);
        assertEquals(GattCommand.Type.READ, transport.calls.get(1).type);
        assertEquals(0, lastErrorCode);
        assertTrue(lastResults.containsKey(CHAR_B));
        assertEquals(0x42, lastResults.get(CHAR_B)[0]);
    }

    @Test
    public void testMultiStepSequenceCollectsAllReads() {
        // 多步序列：状态 → 错误码 → 固件版本。
        PollStep r1 = new PollStep(GattCommand.Type.READ, null, CHAR_A, null, 3000, 1);
        PollStep r2 = new PollStep(GattCommand.Type.READ, null, CHAR_B, null, 3000, 1);
        transport.script.add(GattResult.ok(new byte[]{0x0A}));
        transport.script.add(GattResult.ok(new byte[]{0x0B}));

        executor.runTask(PollingTask.simple(MAC, Arrays.asList(r1, r2)));

        assertEquals(0, lastErrorCode);
        assertEquals(2, lastResults.size());
    }

    @Test
    public void testStepFailureRetriedOnceThenSucceeded() {
        PollStep read = new PollStep(GattCommand.Type.READ, null, CHAR_A, null, 3000, 1);
        transport.script.add(GattResult.fail(133)); // 首次失败
        transport.script.add(GattResult.ok(new byte[]{0x07})); // 重试成功

        executor.runTask(PollingTask.simple(MAC, java.util.Collections.singletonList(read)));

        assertEquals(2, transport.calls.size()); // 1 次原始 + 1 次重试
        assertEquals(0, lastErrorCode);
    }

    @Test
    public void testStepFailureAfterRetryAbortsTask() {
        // §7.3.1：任一步失败重试一次，仍失败 → 任务中止，后续步骤不执行。
        PollStep failing = new PollStep(GattCommand.Type.READ, null, CHAR_A, null, 3000, 1);
        PollStep never = new PollStep(GattCommand.Type.READ, null, CHAR_B, null, 3000, 1);
        transport.script.add(GattResult.fail(133));
        transport.script.add(GattResult.fail(133));

        executor.runTask(PollingTask.simple(MAC, Arrays.asList(failing, never)));

        assertEquals(2, transport.calls.size()); // 第二步未执行
        assertTrue(lastErrorCode != 0);
        assertEquals(133, lastRawStatus); // rawStatus 透传（§12.9）
    }

    @Test
    public void testTaskTimeoutAborts() {
        PollStep read = new PollStep(GattCommand.Type.READ, null, CHAR_A, null, 3000, 1);
        PollingTask task = PollingTask.simple(MAC, java.util.Collections.singletonList(read));
        task.timeoutMs = 100;
        // transport 执行期间时钟越过整任务超时。
        transport.script.add(GattResult.ok(new byte[]{1}));
        transport.script.add(GattResult.ok(new byte[]{2}));
        PollingTask twoStep = PollingTask.simple(MAC, Arrays.asList(read,
                new PollStep(GattCommand.Type.READ, null, CHAR_B, null, 3000, 1)));
        twoStep.timeoutMs = 100;
        GattExecutorImpl timed = new GattExecutorImpl(new GattTransport() {
            @Override
            public GattResult execute(String deviceMac, PollStep step) {
                now[0] += 200; // 每步耗时 200ms > 任务 100ms 预算
                return GattResult.ok(new byte[]{1});
            }
        }, new GattExecutorImpl.TaskCallback() {
            @Override
            public void onTaskResult(String deviceMac, Map<UUID, byte[]> results,
                                     int errorCode, int rawStatus) {
                lastErrorCode = errorCode;
            }

            @Override
            public void onCommandResult(GattCommand command, GattResult result) {
            }
        }, () -> now[0]);

        timed.runTask(twoStep);

        assertEquals(1001, lastErrorCode); // 超时中止
    }
}
