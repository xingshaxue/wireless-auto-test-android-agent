package com.longcheer.agent.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 启动参数解析（脱网独立运营）：优先级 Intent extras > 本地已存配置 > 内置默认。
 *
 * <p>纯逻辑层（不依赖 Android 类，可 JVM 单测）。extras/stored 均以字符串归一化。</p>
 */
public final class StartParams {

    public static final String KEY_SERVER_HOST = "server_host";
    public static final String KEY_SERVER_PORT = "server_port";
    public static final String KEY_DEVICE_ID = "device_id";
    public static final String KEY_SIMULATE_DUT = "simulateDut";
    public static final String KEY_AUTO_START = "auto_start";

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 10086;

    public final String serverHost;
    public final int serverPort;
    public final String deviceId;
    public final boolean simulateDut;
    public final boolean autoStart;

    public StartParams(String serverHost, int serverPort, String deviceId,
                       boolean simulateDut, boolean autoStart) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.deviceId = deviceId;
        this.simulateDut = simulateDut;
        this.autoStart = autoStart;
    }

    public static StartParams defaults() {
        return new StartParams(DEFAULT_HOST, DEFAULT_PORT, "", false, false);
    }

    /**
     * 合并启动参数。
     *
     * @param extras Intent extras（归一化为字符串；null 或无某键 = 未携带）
     * @param stored 本地已存配置（同上约定）
     */
    public static StartParams resolve(Map<String, String> extras, Map<String, String> stored) {
        Map<String, String> e = extras == null ? new HashMap<>() : extras;
        Map<String, String> s = stored == null ? new HashMap<>() : stored;

        String host = firstNonEmpty(e.get(KEY_SERVER_HOST), s.get(KEY_SERVER_HOST), DEFAULT_HOST);
        int port = parseInt(firstNonEmpty(e.get(KEY_SERVER_PORT), s.get(KEY_SERVER_PORT), null),
                DEFAULT_PORT);
        String deviceId = firstNonEmpty(e.get(KEY_DEVICE_ID), s.get(KEY_DEVICE_ID), "");
        boolean simulate = parseBool(firstNonEmpty(e.get(KEY_SIMULATE_DUT), s.get(KEY_SIMULATE_DUT), null));
        boolean autoStart = parseBool(firstNonEmpty(e.get(KEY_AUTO_START), s.get(KEY_AUTO_START), null));
        return new StartParams(host, port, deviceId, simulate, autoStart);
    }

    /** 序列化为可持久化的字符串映射。 */
    public Map<String, String> toMap() {
        Map<String, String> m = new HashMap<>();
        m.put(KEY_SERVER_HOST, serverHost);
        m.put(KEY_SERVER_PORT, String.valueOf(serverPort));
        m.put(KEY_DEVICE_ID, deviceId);
        m.put(KEY_SIMULATE_DUT, String.valueOf(simulateDut));
        m.put(KEY_AUTO_START, String.valueOf(autoStart));
        return m;
    }

    /**
     * 是否已完成必要配置（开机自启门槛）：服务器地址显式配置过（非回环默认）且端口合法。
     */
    public boolean isConfigured() {
        return serverHost != null && !serverHost.isEmpty()
                && !DEFAULT_HOST.equals(serverHost)
                && serverPort > 0 && serverPort <= 65535;
    }

    private static String firstNonEmpty(String... candidates) {
        String last = null;
        for (String c : candidates) {
            if (c != null && !c.isEmpty()) {
                return c;
            }
            last = c; // 记录末位默认值（空字符串也是有效默认值）
        }
        return last;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBool(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }
}
