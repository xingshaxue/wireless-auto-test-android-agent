package com.longcheer.agent.ble;

import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.PollingTask;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GattExecutor 基础实现。
 * M1 阶段使用单线程 Executor 串行执行 GATT 命令；真实 Android GATT API 调用留 TODO。
 */
public class GattExecutorImpl implements GattExecutor {

    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Runnable> pendingTasks = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public GattExecutorImpl() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GattExecutor");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void execute(GattCommand command) {
        ensureRunning();
        Runnable task = () -> {
            // TODO M1: 真实 GATT 操作（BluetoothGatt.readCharacteristic/writeCharacteristic 等）。
            // 当前仅作为占位执行，完成后需回调 DeviceController / StateReporter。
        };
        pendingTasks.put(command.getDeviceMac() + "/" + command.getRequestId(), task);
        executor.execute(task);
    }

    @Override
    public void executeTask(PollingTask task) {
        ensureRunning();
        Runnable runnable = () -> {
            // TODO M1: 按 steps 顺序执行真实 GATT 操作。
        };
        pendingTasks.put(task.getDeviceMac() + "/poll", runnable);
        executor.execute(runnable);
    }

    @Override
    public void cancelPending(String deviceMac) {
        // M1: 简单移除内存中的待执行任务；无法中断已提交到 Executor 的线程。
        pendingTasks.keySet().removeIf(key -> key.startsWith(deviceMac + "/"));
    }

    /**
     * 停止 Executor（测试/退出时使用）。
     */
    public void shutdown() {
        running = false;
        executor.shutdownNow();
    }

    private void ensureRunning() {
        if (!running) {
            running = true;
        }
    }
}
