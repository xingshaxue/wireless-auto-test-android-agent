package com.longcheer.agent.ble;

import android.os.SystemClock;

import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.GattResult;
import com.longcheer.agent.model.PollStep;
import com.longcheer.agent.model.PollingTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * GattExecutor 实现（SDD §3.8 / §7.3.1 / §12.2）。
 *
 * <p>单线程串行执行；轮询任务为有序动作序列：任一步失败按 maxRetry 重试
 * （默认重试一次），仍失败则任务中止并回调错误；READ 步结果按 charUuid 汇总，
 * 任务成功后经 {@link TaskCallback} 进入轮询处理链。</p>
 */
public class GattExecutorImpl implements GattExecutor {

    private static final String TAG = "GattExecutor";

    /**
     * 任务执行结果回调（PollingScheduler 实现：成功进处理链，失败清理欠账标记）。
     */
    public interface TaskCallback {
        /** 轮询任务完成：errorCode=0 成功；否则中止，results 为已采集部分。 */
        void onTaskResult(String deviceMac, Map<UUID, byte[]> results, int errorCode, int rawStatus);

        /** 单命令执行完成（CMD_ACK 结果回报在 BLE 里程碑接通）。 */
        void onCommandResult(GattCommand command, GattResult result);
    }

    private final GattTransport transport;
    private final TaskCallback callback;
    private final LongSupplier clock;
    private final ExecutorService executor;

    public GattExecutorImpl(GattTransport transport, TaskCallback callback) {
        this(transport, callback, SystemClock::elapsedRealtime);
    }

    /** 测试用构造：注入单调时钟。 */
    GattExecutorImpl(GattTransport transport, TaskCallback callback, LongSupplier clock) {
        this.transport = transport;
        this.callback = callback;
        this.clock = clock;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GattExecutor");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void execute(GattCommand command) {
        executor.execute(() -> {
            PollStep step = new PollStep(command.getType(), command.getServiceUuid(),
                    command.getCharUuid(), command.getPayload(),
                    command.getTimeoutMs(), command.getMaxRetry());
            GattResult result = executeStepWithRetry(command.getDeviceMac(), step);
            AgentLog.d(TAG, "command " + command.getType() + " mac=" + command.getDeviceMac()
                    + " -> " + result);
            if (callback != null) {
                callback.onCommandResult(command, result);
            }
        });
    }

    @Override
    public void executeTask(PollingTask task) {
        executor.execute(() -> runTask(task));
    }

    /** 同步执行任务序列（测试可直接驱动的核心逻辑）。 */
    void runTask(PollingTask task) {
        String mac = task.getDeviceMac();
        long deadline = clock.getAsLong() + task.timeoutMs;
        Map<UUID, byte[]> results = new HashMap<>();
        for (PollStep step : task.steps) {
            if (clock.getAsLong() > deadline) {
                AgentLog.w(TAG, "poll task timeout, abort: " + mac);
                notifyTaskResult(mac, results, 1001, 0); // 1001 超时类（§12.9 1xxx）
                return;
            }
            GattResult result = executeStepWithRetry(mac, step);
            if (!result.isSuccess()) {
                // §7.3.1：任一步重试后仍失败 → 任务中止并上报错误。
                AgentLog.w(TAG, "poll task aborted at step " + step.type + " char="
                        + step.charUuid + " status=" + result.getStatus() + ", mac=" + mac);
                notifyTaskResult(mac, results, 1001, result.getStatus());
                return;
            }
            if (step.type == GattCommand.Type.READ && step.charUuid != null) {
                results.put(step.charUuid, result.getValue());
            }
        }
        notifyTaskResult(mac, results, 0, 0);
    }

    /** 单步执行 + 失败重试（§12.2：超时/失败后重试 maxRetry 次，默认一次）。 */
    private GattResult executeStepWithRetry(String mac, PollStep step) {
        int attempts = 1 + Math.max(0, step.maxRetry);
        GattResult result = GattResult.fail(-1);
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                result = transport.execute(mac, step);
            } catch (RuntimeException e) {
                AgentLog.w(TAG, "transport threw: " + e.getMessage());
                result = GattResult.fail(-1);
            }
            if (result.isSuccess()) {
                return result;
            }
            AgentLog.w(TAG, "step failed (attempt " + (attempt + 1) + "/" + attempts + ") mac="
                    + mac + " char=" + step.charUuid + " status=" + result.getStatus());
        }
        return result;
    }

    private void notifyTaskResult(String mac, Map<UUID, byte[]> results, int errorCode, int rawStatus) {
        if (callback != null) {
            callback.onTaskResult(mac, results, errorCode, rawStatus);
        }
    }

    @Override
    public void cancelPending(String deviceMac) {
        // 同步执行模型下单设备任务在队列中自然串行；取消语义在 BLE 里程碑接通真实取消。
    }

    /**
     * 停止 Executor（测试/退出时使用）。
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}
