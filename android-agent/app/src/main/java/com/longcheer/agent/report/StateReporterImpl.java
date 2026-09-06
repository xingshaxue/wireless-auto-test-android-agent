package com.longcheer.agent.report;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.tcp.TcpClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StateReporter 基础实现。
 * M1 阶段：通过 TcpClient 发送事件；上报失败时本地缓存，缓存上限默认 1000；持久化留 TODO。
 */
public class StateReporterImpl implements StateReporter {

    private static final int DEFAULT_BUFFER_MAX = 1000;

    private final TcpClient tcpClient;
    private final ExecutorService executor;
    private final List<Map<String, Object>> buffer;
    private final int bufferMax;

    public StateReporterImpl(TcpClient tcpClient) {
        this(tcpClient, DEFAULT_BUFFER_MAX);
    }

    public StateReporterImpl(TcpClient tcpClient, AgentConfig config) {
        this(tcpClient, config == null ? DEFAULT_BUFFER_MAX : config.getReportBufferMax());
    }

    public StateReporterImpl(TcpClient tcpClient, int bufferMax) {
        this.tcpClient = tcpClient;
        this.bufferMax = Math.max(1, bufferMax);
        this.buffer = new ArrayList<>();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StateReporter");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void report(String event, Map<String, Object> payload) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", event);
        msg.put("timestamp", System.currentTimeMillis());
        if (payload != null) {
            msg.putAll(payload);
        }
        executor.execute(() -> sendOrBuffer(msg));
    }

    @Override
    public void reportCommandAck(String requestId, int errorCode, Object result) {
        reportCommandAck(requestId, errorCode, 0, result);
    }

    @Override
    public void reportCommandAck(String requestId, int errorCode, int rawStatus, Object result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("errorCode", errorCode);
        if (rawStatus != 0) {
            payload.put("rawStatus", rawStatus);
        }
        if (result != null) {
            payload.put("result", result);
        }
        report("CMD_ACK", payload);
    }

    @Override
    public void reportDeviceState(String mac, DeviceState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceMac", mac);
        payload.put("state", state.name());
        report("DEVICE_STATE", payload);
    }

    @Override
    public void reportPollResult(String mac, Map<String, Object> fields, boolean stale) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceMac", mac);
        payload.put("stale", stale);
        payload.put("values", fields);
        report("POLL_RESULT", payload);
    }

    @Override
    public void reportFileProgress(String taskId, double percent, long bytesPerSec) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("percent", percent);
        payload.put("bytesPerSec", bytesPerSec);
        report("FILE_PROGRESS", payload);
    }

    @Override
    public void flush() {
        executor.execute(this::drainBuffer);
    }

    /**
     * 停止上报线程（测试/退出使用）。
     */
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * @return 当前缓存事件数（测试用）
     */
    public synchronized int bufferedCount() {
        return buffer.size();
    }

    private synchronized void sendOrBuffer(Map<String, Object> msg) {
        if (tcpClient != null && tcpClient.isConnected()) {
            try {
                tcpClient.sendJson(msg);
                // 连接可用时顺带清空缓存。
                drainBuffer();
                return;
            } catch (Exception ignored) {
                // 发送失败则落入缓存。
            }
        }

        if (buffer.size() >= bufferMax) {
            buffer.remove(0);
            // TODO M1: 上报 ERROR 提示服务器缓存溢出。
        }
        buffer.add(msg);
    }

    private synchronized void drainBuffer() {
        if (tcpClient == null || !tcpClient.isConnected()) {
            return;
        }
        List<Map<String, Object>> toSend = new ArrayList<>(buffer);
        buffer.clear();
        for (Map<String, Object> msg : toSend) {
            try {
                tcpClient.sendJson(msg);
            } catch (Exception ignored) {
                // 补报失败不再无限积压；后续可持久化到磁盘。
            }
        }
    }
}
