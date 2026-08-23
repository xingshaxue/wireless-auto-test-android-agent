package com.longcheer.agent.tcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP 心跳管理器（SDD §3.1 / §10.2 / 附录 A.3）。
 *
 * <p>按配置周期定时发送 {@code HEARTBEAT} 事件；可通过 {@link #start()} / {@link #stop()} 控制生命周期。
 * 心跳报文包含手机资源与槽位统计字段。</p>
 */
public class HeartbeatManager {

    /** 默认心跳周期（毫秒）。 */
    public static final long DEFAULT_INTERVAL_MS = 5000L;

    /**
     * 心跳动态数据提供接口。由上层业务注入，用于在发送前补充实时字段（如 CPU、内存、槽位占用）。
     */
    public interface DataProvider {
        /**
         * @return 需要合并到心跳报文中的字段映射；可为 {@code null}
         */
        Map<String, Object> provide();
    }

    private final TcpClient client;
    private final long intervalMs;
    private final DataProvider dataProvider;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 使用默认周期（5s）与空数据提供者构造。
     */
    public HeartbeatManager(TcpClient client) {
        this(client, DEFAULT_INTERVAL_MS, null);
    }

    /**
     * 使用指定周期与空数据提供者构造。
     */
    public HeartbeatManager(TcpClient client, long intervalMs) {
        this(client, intervalMs, null);
    }

    /**
     * 完整构造。
     *
     * @param client       TCP 客户端
     * @param intervalMs   心跳周期，必须大于 0
     * @param dataProvider 动态数据提供者，可为 {@code null}
     */
    public HeartbeatManager(TcpClient client, long intervalMs, DataProvider dataProvider) {
        if (client == null) {
            throw new IllegalArgumentException("client is null");
        }
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be > 0");
        }
        this.client = client;
        this.intervalMs = intervalMs;
        this.dataProvider = dataProvider;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动心跳定时发送。重复调用幂等。
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::sendHeartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 停止心跳。重复调用幂等。
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdownNow();
        }
    }

    private void sendHeartbeat() {
        try {
            Map<String, Object> heartbeat = buildHeartbeat();
            client.sendJson(heartbeat);
        } catch (Exception e) {
            // 心跳失败不应中断调度器，由 TcpClientImpl 负责重连
            e.printStackTrace();
        }
    }

    /**
     * 构造心跳报文（SDD 附录 A.3）。
     */
    protected Map<String, Object> buildHeartbeat() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "HEARTBEAT");
        msg.put("timestamp", System.currentTimeMillis());

        Map<String, Object> provided = dataProvider != null ? dataProvider.provide() : null;
        if (provided != null) {
            msg.putAll(provided);
        }

        // 确保必填字段存在，缺省时给 0 兜底
        msg.putIfAbsent("slotsUsed", 0);
        msg.putIfAbsent("slotsTotal", 0);
        msg.putIfAbsent("devicesManaged", 0);
        msg.putIfAbsent("devicesReady", 0);
        return msg;
    }

    /**
     * @return 当前是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 构造一个仅含 0 值必填字段的默认数据提供者，便于测试或简单场景使用。
     */
    public static DataProvider defaultProvider() {
        return Collections::emptyMap;
    }
}
