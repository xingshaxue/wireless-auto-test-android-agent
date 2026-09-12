package com.longcheer.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.longcheer.agent.ble.BleCentralManager;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.log.CrashLogHandler;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.config.DeviceConfig;
import com.longcheer.agent.config.StartParams;
import com.longcheer.agent.dispatch.CommandDispatcher;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.poll.PollResultChain;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.ConnectionSlotManager;
import com.longcheer.agent.schedule.PollingScheduler;
import com.longcheer.agent.tcp.TcpClient;
import com.longcheer.agent.tcp.TcpListener;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无限自动化测试框架 Android Agent 前台 Service 入口（SDD §7.1 / §12.10）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>以前台服务形态保活，持有 PARTIAL_WAKE_LOCK；</li>
 *   <li>监听系统蓝牙/网络广播，在蓝牙关闭等异常时保护连接状态；</li>
 *   <li>初始化 TCP、BLE、调度、上报、命令分发等模块；</li>
 *   <li>向服务器发送 REGISTER 事件，并在 REGISTER_ACK 后加载配置、创建设备 Controller；</li>
 *   <li>在 {@code onDestroy()} 中释放全部资源。</li>
 * </ul>
 *
 * <p><b>线程约定</b>：Service 生命周期方法运行在主线程；所有可能阻塞的 IO/网络/BLE
 * 操作均委托给对应模块的独立线程，本类只负责编排。</p>
 */
public class AgentService extends Service {

    private static final String TAG = "AgentService";

    /** 启动 Service 的 Intent action。 */
    public static final String ACTION_START = "com.longcheer.agent.ACTION_START";
    /** 停止 Service 的 Intent action。 */
    public static final String ACTION_STOP = "com.longcheer.agent.ACTION_STOP";

    /** 服务器地址 Intent extra（可选，默认 127.0.0.1）。 */
    public static final String EXTRA_SERVER_HOST = "server_host";
    /** 服务器端口 Intent extra（可选，默认 10086）。 */
    public static final String EXTRA_SERVER_PORT = "server_port";
    /** 手机业务 deviceId Intent extra（可选，默认使用 ANDROID_ID）。 */
    public static final String EXTRA_DEVICE_ID = "device_id";

    public static final String NOTIFICATION_CHANNEL_ID = "agent_foreground_channel";
    public static final int NOTIFICATION_ID = 1;

    private static final String DEFAULT_AGENT_VERSION = "1.0.0-M1";

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver bluetoothReceiver;
    private BroadcastReceiver networkReceiver;

    // 跨模块依赖；默认构造使用 Stub，测试/生产可通过注入构造替换为真实实现。
    private TcpClient tcpClient;
    private BleCentralManager bleCentralManager;
    private DeviceRegistry deviceRegistry;
    private ConnectionSlotManager connectionSlotManager;
    private ConnectionScheduler connectionScheduler;
    private PollingScheduler pollingScheduler;
    private StateReporter stateReporter;
    private CommandDispatcher commandDispatcher;
    private PollResultChain pollResultChain;

    /** 虚拟 DUT 开关（模拟器/CI 测试，默认 false）。 */
    public static final String EXTRA_SIMULATE_DUT = "simulateDut";

    // 启动参数
    private String serverHost = "127.0.0.1";
    private int serverPort = 10086;
    private String deviceId = "";
    private boolean simulateDut = false;
    /** 启动参数是否已解析（onStartCommand 或 bind 路径兜底解析后置 true）。 */
    private volatile boolean paramsResolved = false;

    // 运行期配置
    private AgentConfig agentConfig;

    // 文件传输管理器（M4）：由装配层注入；未注入时帧仅记日志。
    private com.longcheer.agent.transfer.FileTransferManager fileTransferManager;

    // 运行时统计（M5，§13）：随心跳周期上报 CONNECTION_STATISTICS。
    private com.longcheer.agent.report.StatsCollector statsCollector;

    // 心跳管理器（§3.1）：注册成功后启动。
    private com.longcheer.agent.tcp.HeartbeatManager heartbeatManager;

    private void startHeartbeat() {
        if (heartbeatManager == null) {
            long interval = agentConfig == null
                    ? com.longcheer.agent.tcp.HeartbeatManager.DEFAULT_INTERVAL_MS
                    : agentConfig.getHeartbeatIntervalMs();
            heartbeatManager = new com.longcheer.agent.tcp.HeartbeatManager(tcpClient, interval,
                    () -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("slotsUsed", connectionSlotManager.occupiedSlots().size());
                        m.put("slotsTotal", connectionSlotManager.slotCount());
                        m.put("devicesManaged", deviceRegistry.size());
                        m.put("devicesReady", deviceRegistry.findByState(DeviceState.READY).size());
                        return m;
                    });
        }
        heartbeatManager.start();
    }

    public void setFileTransferManager(com.longcheer.agent.transfer.FileTransferManager manager) {
        this.fileTransferManager = manager;
    }

    public void setStatsCollector(com.longcheer.agent.report.StatsCollector collector) {
        this.statsCollector = collector;
    }

    /**
     * §12.10 系统约束检查：省电白名单与蓝牙运行时权限。
     * 缺权限进入受限模式（仅上报，不操作 BLE）；检查结果仅记录与上报，不阻断启动。
     */
    private void checkSystemConstraints() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                AgentLog.w(TAG, "not in battery optimization whitelist");
                stateReporter.report("ERROR",
                        buildErrorPayload(0, "battery optimization whitelist missing", null));
            }
        } catch (Exception e) {
            AgentLog.w(TAG, "battery whitelist check failed: " + e.getMessage());
        }
        if (Build.VERSION.SDK_INT >= 31) {
            boolean scanGranted = checkSelfPermission("android.permission.BLUETOOTH_SCAN")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean connectGranted = checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!scanGranted || !connectGranted) {
                // 受限模式：仅上报，不操作 BLE（§12.10）。
                AgentLog.w(TAG, "BLE runtime permission missing, restricted mode");
                stateReporter.report("ERROR",
                        buildErrorPayload(0, "BLE runtime permission missing, restricted mode", null));
            }
        }
    }

    public AgentService() {
        // 默认使用占位实现；真实实现由对应模块通过注入构造传入。
        this.tcpClient = new StubTcpClient();
        this.bleCentralManager = new StubBleCentralManager();
        this.deviceRegistry = new StubDeviceRegistry();
        this.connectionSlotManager = new StubConnectionSlotManager();
        this.connectionScheduler = new StubConnectionScheduler();
        this.pollingScheduler = new StubPollingScheduler();
        this.stateReporter = new StubStateReporter();
        this.commandDispatcher = new StubCommandDispatcher();
        this.pollResultChain = new StubPollResultChain();
    }

    /**
     * 包可见的注入构造，便于测试与后续用真实模块替换 Stub。
     */
    AgentService(TcpClient tcpClient, BleCentralManager bleCentralManager,
                 DeviceRegistry deviceRegistry, ConnectionSlotManager connectionSlotManager,
                 ConnectionScheduler connectionScheduler, PollingScheduler pollingScheduler,
                 StateReporter stateReporter, CommandDispatcher commandDispatcher,
                 PollResultChain pollResultChain) {
        this.tcpClient = tcpClient;
        this.bleCentralManager = bleCentralManager;
        this.deviceRegistry = deviceRegistry;
        this.connectionSlotManager = connectionSlotManager;
        this.connectionScheduler = connectionScheduler;
        this.pollingScheduler = pollingScheduler;
        this.stateReporter = stateReporter;
        this.commandDispatcher = commandDispatcher;
        this.pollResultChain = pollResultChain;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // SDD §13：日志核心先行初始化——滚动落盘 + 崩溃捕获（DEBUG 默认关闭）。
        AgentLog.init(new java.io.File(getFilesDir(), "logs"), false);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashLogHandler(new java.io.File(getFilesDir(), "logs"), previous));
        AgentLog.i(TAG, "onCreate");
        createNotificationChannel();
        startForegroundInternal();
        acquireWakeLock();
        registerBluetoothReceiver();
        registerNetworkReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand action=" + (intent == null ? null : intent.getAction()));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String prevHost = serverHost;
        int prevPort = serverPort;
        parseStartExtras(intent);
        if (!initialized.get()) {
            initializeComponents();
        } else if (!serverHost.equals(prevHost) || serverPort != prevPort) {
            // 目标服务器变更：断开旧连接按新参数重连注册（重复 ACTION_START 不再静默忽略）
            AgentLog.i(TAG, "server target changed -> reconnect "
                    + serverHost + ":" + serverPort);
            tcpClient.disconnect();
            connectAndRegister();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (!initialized.get()) {
            initializeComponents();
        }
        return new AgentBinder();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        releaseResources();
        super.onDestroy();
    }

    /**
     * 外部/内部均可调用的初始化入口：初始化模块、连接服务器、发送 REGISTER。
     */
    synchronized void initializeComponents() {
        if (initialized.getAndSet(true)) {
            return;
        }

        // 生产装配：当前仍是 Stub 时尝试装配真实组件；BLE 不可用/装配失败回退 Stub 并上报。
        if (tcpClient instanceof StubTcpClient) {
            try {
                AgentAssembler.Components components = AgentAssembler.assemble(this, simulateDut);
                if (components != null) {
                    this.tcpClient = components.tcpClient;
                    this.bleCentralManager = components.bleCentralManager;
                    this.deviceRegistry = components.deviceRegistry;
                    this.connectionSlotManager = components.connectionSlotManager;
                    this.connectionScheduler = components.connectionScheduler;
                    this.pollingScheduler = components.pollingScheduler;
                    this.stateReporter = components.stateReporter;
                    this.commandDispatcher = components.commandDispatcher;
                    this.pollResultChain = components.pollResultChain;
                    setFileTransferManager(components.fileTransferManager);
                    setStatsCollector(components.statsCollector);
                    AgentLog.i(TAG, "real components assembled and installed");
                } else {
                    stateReporter.report("ERROR",
                            buildErrorPayload(0, "BLE unavailable, running with stubs", null));
                }
            } catch (RuntimeException e) {
                AgentLog.e(TAG, "assembly failed, keep stubs: " + e.getMessage());
                stateReporter.report("ERROR",
                        buildErrorPayload(0, "assembly failed: " + e.getMessage(), null));
            }
        }

        // 初始化 BLE 中心；实现内部负责在主线程完成 BluetoothAdapter 初始化。
        checkSystemConstraints();
        bleCentralManager.init();

        // 装配 TCP 监听：REGISTER_ACK 由 Service 消费，其余命令交给 CommandDispatcher。
        tcpClient.setListener(new AgentTcpListener());

        // 启动调度器。
        connectionScheduler.start();
        pollingScheduler.start();
        if (statsCollector != null) {
            long interval = agentConfig == null ? 5000L : agentConfig.getHeartbeatIntervalMs();
            statsCollector.start(stateReporter, interval);
        }

        // 连接服务器并发送 REGISTER。bind 路径（控制台拉起服务）不会经过
        // onStartCommand，先按本地已存配置解析参数，避免用默认 127.0.0.1 直连。
        if (!paramsResolved) {
            parseStartExtras(null);
        }
        connectAndRegister();
    }

    /**
     * 供测试/外部读取当前是否已完成初始化。
     */
    boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 控制台状态快照（ConsoleActivity 经 Binder 读取；全部为只读摘要）。
     */
    public Map<String, Object> statusSummary() {
        Map<String, Object> s = new HashMap<>();
        s.put("initialized", initialized.get());
        s.put("tcpConnected", tcpClient != null && tcpClient.isConnected());
        s.put("devicesManaged", deviceRegistry == null ? 0 : deviceRegistry.size());
        s.put("devicesReady", deviceRegistry == null ? 0
                : deviceRegistry.findByState(DeviceState.READY).size());
        s.put("configVersion", agentConfig == null ? -1 : agentConfig.getConfigVersion());
        s.put("simulateDut", simulateDut);
        s.put("server", serverHost + ":" + serverPort);
        return s;
    }

    /**
     * 控制台逐设备状态列表（ConsoleActivity 经 Binder 读取；每台一个只读摘要，
     * 遍历走控制器既有 snapshot() 深拷贝机制，不触碰控制器内部状态）。
     */
    public List<Map<String, Object>> deviceStates() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (deviceRegistry == null) {
            return list;
        }
        for (DeviceController controller : deviceRegistry.allControllers()) {
            ManagedDeviceInfo info = controller.snapshot();
            Map<String, Object> item = new HashMap<>();
            item.put("mac", info.getMac());
            item.put("deviceId", info.getDeviceId());
            DeviceState state = info.getState();
            item.put("state", state == null ? "" : state.name());
            item.put("lastPollTime", info.getLastPollTime());
            item.put("pollDataStale", info.isPollDataStale());
            item.put("lastPollSummary", summarizePollResult(info.getLastPollResult()));
            list.add(item);
        }
        return list;
    }

    /** 轮询值摘要：特征数 + 首个特征值 hex 预览（最多 8 字节）。 */
    private static String summarizePollResult(Map<UUID, byte[]> pollResult) {
        if (pollResult == null || pollResult.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pollResult.size()).append(" 项特征值");
        Map.Entry<UUID, byte[]> first = pollResult.entrySet().iterator().next();
        byte[] value = first.getValue();
        if (value != null && value.length > 0) {
            sb.append("，").append(first.getKey().toString(), 0, 8).append("=0x");
            int len = Math.min(value.length, 8);
            for (int i = 0; i < len; i++) {
                sb.append(String.format(java.util.Locale.US, "%02X", value[i]));
            }
            if (value.length > len) {
                sb.append("…");
            }
        }
        return sb.toString();
    }

    private void parseStartExtras(Intent intent) {
        // 脱网独立运营：启动参数 = Intent extras > 本地已存配置 > 内置默认（StartParams）。
        Map<String, String> extras = new HashMap<>();
        if (intent != null) {
            if (intent.hasExtra(EXTRA_SERVER_HOST)) {
                extras.put(StartParams.KEY_SERVER_HOST, intent.getStringExtra(EXTRA_SERVER_HOST));
            }
            if (intent.hasExtra(EXTRA_SERVER_PORT)) {
                extras.put(StartParams.KEY_SERVER_PORT,
                        String.valueOf(intent.getIntExtra(EXTRA_SERVER_PORT, 0)));
            }
            if (intent.hasExtra(EXTRA_DEVICE_ID)) {
                extras.put(StartParams.KEY_DEVICE_ID, intent.getStringExtra(EXTRA_DEVICE_ID));
            }
            if (intent.hasExtra(EXTRA_SIMULATE_DUT)) {
                extras.put(StartParams.KEY_SIMULATE_DUT,
                        String.valueOf(intent.getBooleanExtra(EXTRA_SIMULATE_DUT, false)));
            }
        }
        android.content.SharedPreferences prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE);
        Map<String, String> stored = new HashMap<>();
        for (String key : new String[]{StartParams.KEY_SERVER_HOST, StartParams.KEY_SERVER_PORT,
                StartParams.KEY_DEVICE_ID}) {
            String v = prefs.getString(key, null);
            if (v != null) {
                stored.put(key, v);
            }
        }
        // 布尔键按 boolean 类型存取（与 ConsoleActivity 写入保持一致）。
        stored.put(StartParams.KEY_SIMULATE_DUT,
                String.valueOf(prefs.getBoolean(StartParams.KEY_SIMULATE_DUT, false)));
        stored.put(StartParams.KEY_AUTO_START,
                String.valueOf(prefs.getBoolean(StartParams.KEY_AUTO_START, false)));
        StartParams params = StartParams.resolve(extras, stored);

        // extras 携带的新值持久化：下次开机/进程重启免配。
        if (!extras.isEmpty()) {
            android.content.SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, String> e : extras.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                if (StartParams.KEY_SIMULATE_DUT.equals(e.getKey())
                        || StartParams.KEY_AUTO_START.equals(e.getKey())) {
                    editor.putBoolean(e.getKey(), Boolean.parseBoolean(e.getValue()));
                } else {
                    editor.putString(e.getKey(), e.getValue());
                }
            }
            editor.apply();
        }

        serverHost = params.serverHost;
        serverPort = params.serverPort;
        simulateDut = params.simulateDut;
        deviceId = params.deviceId;
        if (deviceId == null || deviceId.isEmpty()) {
            // 缺省设备 ID：ANDROID_ID 派生并固化，避免每次重启换身份。
            deviceId = "phone-" + Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            prefs.edit().putString(StartParams.KEY_DEVICE_ID, deviceId).apply();
        }
        AgentLog.i(TAG, "start params: host=" + serverHost + " port=" + serverPort
                + " deviceId=" + deviceId + " simulateDut=" + simulateDut);
        paramsResolved = true;
    }

    private void connectAndRegister() {
        Log.i(TAG, "connecting to " + serverHost + ":" + serverPort);
        tcpClient.connect(serverHost, serverPort);

        Map<String, Object> register = buildRegisterPayload();
        stateReporter.report("REGISTER", register);
        // 同时通过 TcpClient 直接发送，保证 REGISTER 即使 reporter 尚未就绪也能发出。
        tcpClient.sendJson(wrapEvent("REGISTER", register));
    }

    private Map<String, Object> buildRegisterPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", resolveDeviceId());
        payload.put("ip", resolveLocalIp());
        payload.put("port", serverPort);
        payload.put("androidSdk", Build.VERSION.SDK_INT);
        payload.put("bleSupported", isBleSupported());
        payload.put("maxConnections", bleCentralManager.supportedMaxConnections());
        payload.put("agentVersion", DEFAULT_AGENT_VERSION);
        // §13：崩溃日志下次启动随注册上报（可选标记字段，服务器可忽略；文件本体经 UPLOAD_LOG 上传）。
        payload.put("hasCrashLog", hasCrashLogs());
        return payload;
    }

    /** 是否存在未上报的崩溃日志文件（crash-*.log）。 */
    boolean hasCrashLogs() {
        java.io.File logDir = new java.io.File(getFilesDir(), "logs");
        java.io.File[] crashes = logDir.listFiles((dir, name) -> name.startsWith("crash-"));
        return crashes != null && crashes.length > 0;
    }

    private Map<String, Object> wrapEvent(String type, Map<String, Object> payload) {
        Map<String, Object> event = new HashMap<>(payload);
        event.put("type", type);
        event.put("timestamp", System.currentTimeMillis());
        return event;
    }

    private String resolveDeviceId() {
        if (deviceId != null && !deviceId.isEmpty()) {
            return deviceId;
        }
        try {
            return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            Log.w(TAG, "resolve ANDROID_ID failed", e);
            return "unknown-" + SystemClock.elapsedRealtime();
        }
    }

    private boolean isBleSupported() {
        return getPackageManager() != null
                && getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)
                && bleCentralManager.isBleAvailable();
    }

    private String resolveLocalIp() {
        // 优先尝试 Wi-Fi Manager
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null) {
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        return String.format(java.util.Locale.US, "%d.%d.%d.%d",
                                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolve WiFi ip failed", e);
        }

        // 兜底遍历 NetworkInterface
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (ni.isLoopback() || !ni.isUp()) {
                        continue;
                    }
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolve NetworkInterface ip failed", e);
        }
        return "127.0.0.1";
    }

    private void handleRegisterAck(Map<String, Object> command) {
        int errorCode = getInt(command, "errorCode", 0);
        if (errorCode != 0) {
            Log.e(TAG, "REGISTER_ACK rejected, errorCode=" + errorCode);
            stateReporter.report("ERROR", buildErrorPayload(2001, "register rejected: " + errorCode, null));
            return;
        }

        Object configRaw = command.get("config");
        if (!(configRaw instanceof Map)) {
            Log.w(TAG, "REGISTER_ACK missing config");
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> configMap = (Map<String, Object>) configRaw;
        AgentConfig config = AgentConfig.fromJson(new JSONObject(configMap));
        applyConfig(config);

        // 注册/重连成功：挂起的文件下载任务发 FILE_DOWNLOAD_RESUME 续传（§7.6）。
        if (fileTransferManager != null) {
            fileTransferManager.onTcpReconnected();
        }
    }

    /** UPLOAD_LOG（§13 / §A.2）：sinceTs + minLevel 过滤打包，LOG_FRAME 上传。 */
    private void handleUploadLog(Map<String, Object> command) {
        String requestId = getString(command, "requestId", null);
        long sinceTs = getLong(command, "sinceTs", 0);
        String minLevel = getString(command, "minLevel", "DEBUG");
        java.io.File logDir = new java.io.File(getFilesDir(), "logs");
        new Thread(() -> new com.longcheer.agent.log.LogUploader(logDir, tcpClient, stateReporter)
                .upload(requestId, sinceTs, minLevel), "LogUploader").start();
    }

    private void applyConfig(AgentConfig config) {
        // §16.4 configVersion：按版本号单调应用，低版本晚到直接丢弃。
        if (agentConfig != null && config.getConfigVersion() < agentConfig.getConfigVersion()) {
            AgentLog.w(TAG, "drop stale config version " + config.getConfigVersion()
                    + " (applied " + agentConfig.getConfigVersion() + ")");
            return;
        }
        this.agentConfig = config;
        AgentLog.i(TAG, "applyConfig version=" + config.getConfigVersion()
                + " maxSlots=" + config.getMaxSlots());

        // 旋钮校验（§16.4/§6.4）：仅告警不阻断。
        if (config.getConnectTimeoutMs() < config.getSetupBudgetMs()) {
            AgentLog.w(TAG, "connectTimeoutMs(" + config.getConnectTimeoutMs()
                    + ") < setupBudgetMs(" + config.getSetupBudgetMs() + "), 建连预算将被硬超时截断");
        }

        // 调整连接槽上限。
        if (!connectionScheduler.setMaxSlots(config.getMaxSlots())) {
            AgentLog.w(TAG, "applyConfig: setMaxSlots refused, keep "
                    + connectionScheduler.requiredSlots() + " persistent+pinned");
        }

        // 为 REGISTER_ACK 下发的每台 DUT 创建 Controller，初始状态 REGISTERED，不主动连接。
        List<DeviceConfig> devices = config.getDevices();
        for (DeviceConfig device : devices) {
            String mac = device.getMac();
            String devId = device.getDeviceId();
            if (mac == null || mac.isEmpty() || devId == null || devId.isEmpty()) {
                AgentLog.w(TAG, "skip invalid device config: deviceId=" + devId + " mac=" + mac);
                continue;
            }

            DeviceController controller = bleCentralManager.createController(mac, devId);
            deviceRegistry.register(controller);

            com.longcheer.agent.config.PollingConfig cfg = device.getPolling();
            if (cfg != null) {
                PollingConfig pollingConfig = toModelPollingConfig(cfg);
                controller.setPollingConfig(pollingConfig);
                pollingScheduler.updateConfig(mac, pollingConfig);
                // §6.4：intervalMs 小于一轮轮询时间 → 欠账永远还不完，给出告警。
                if (!pollingConfig.getReadCharacteristics().isEmpty()
                        && pollingConfig.getIntervalMs() < pollingScheduler.estimateFullRoundMs()) {
                    AgentLog.w(TAG, "intervalMs(" + pollingConfig.getIntervalMs() + ") < 一轮估算("
                            + pollingScheduler.estimateFullRoundMs() + ")，数据将持续过期: " + mac);
                }
            }

            // 装载 Decoder 字段映射 + 规则集 + profile 到轮询处理链（§16.4 / §7.3.2）；
            // 校验失败（规则引用未映射字段）由链路拒绝并上报配置错误。
            boolean chainOk = pollResultChain.updateDeviceConfig(mac, device.getType(),
                    com.longcheer.agent.poll.PollResultChainImpl.toFieldMappings(device),
                    com.longcheer.agent.poll.PollResultChainImpl.toPollRules(device),
                    com.longcheer.agent.poll.PollResultChainImpl.toProfile(device));
            if (!chainOk) {
                Log.w(TAG, "device chain config rejected: " + mac);
            }
            controller.setPollRules(com.longcheer.agent.poll.PollResultChainImpl.toPollRules(device));

            if (device.isPersistent()) {
                connectionScheduler.setPersistent(mac, true);
            }
        }

        // §16.4 整项替换：注册表中已存在但新配置不存在的设备，按 §7.7 移除流程下线
        // （与 REMOVE_DEVICE 命令路径一致；destroyController 内含 terminate → TERMINATED
        // 并上报 DEVICE_STATE、注册表注销、GATT 关闭，模拟/真实模式一致生效）。
        Set<String> configured = new java.util.HashSet<>();
        for (DeviceConfig device : devices) {
            if (device.getMac() != null) {
                configured.add(device.getMac());
            }
        }
        for (String existingMac : new ArrayList<>(deviceRegistry.allMacs())) {
            if (configured.contains(existingMac)) {
                continue;
            }
            AgentLog.i(TAG, "config replaced: remove device " + existingMac);
            DeviceController controller = deviceRegistry.findByMac(existingMac);
            connectionScheduler.cancelRequest(existingMac);
            connectionScheduler.releaseSlot(existingMac);
            if (controller != null) {
                // §7.7 第 4 条：丢弃命令前逐条回 CMD_ACK 2004，服务器 requestId 对账不悬挂。
                for (com.longcheer.agent.model.QueuedTask task : controller.drainPendingCommands()) {
                    if (task instanceof com.longcheer.agent.model.GattCommand) {
                        String cmdRequestId = ((com.longcheer.agent.model.GattCommand) task).getRequestId();
                        if (cmdRequestId != null) {
                            stateReporter.reportCommandAck(cmdRequestId, 2004,
                                    "command cancelled by config replacement");
                        }
                    }
                }
            }
            // §7.7 第 5 条：进行中文件传输中止并上报 FILE_RESULT（cancelled）。
            if (fileTransferManager != null) {
                fileTransferManager.pauseTransferForDevice(existingMac, true);
            }
            bleCentralManager.destroyController(existingMac);
            pollingScheduler.removeConfig(existingMac);
        }

        // 通知服务器当前拓扑。
        reportTopology();
    }

    private PollingConfig toModelPollingConfig(com.longcheer.agent.config.PollingConfig cfg) {
        return new PollingConfig(
                cfg.getIntervalMs(),
                parseUuidList(cfg.getReadCharacteristics()),
                parseUuidList(cfg.getNotifyCharacteristics()),
                cfg.isReportOnlyChanged()
        );
    }

    private List<UUID> parseUuidList(List<String> raw) {
        List<UUID> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String item : raw) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            try {
                String trimmed = item.trim();
                // A.1 编码约定：4/8 位短 UUID 扩展为蓝牙基础 UUID（真机联调暴露：
                // 直接 UUID.fromString("2A19") 静默丢弃导致轮询序列为空）。
                if (trimmed.length() <= 8) {
                    trimmed = String.format("0000%s-0000-1000-8000-00805f9b34fb", trimmed);
                }
                result.add(UUID.fromString(trimmed));
            } catch (IllegalArgumentException e) {
                AgentLog.w(TAG, "invalid UUID in config skipped: " + item);
            }
        }
        return result;
    }

    private void reportTopology() {
        List<Map<String, Object>> connections = new ArrayList<>();
        for (DeviceController controller : deviceRegistry.allControllers()) {
            Map<String, Object> item = new HashMap<>();
            ManagedDeviceInfo info = controller.snapshot();
            item.put("deviceMac", info.getMac());
            DeviceState state = info.getState();
            item.put("state", state == null ? null : state.name());
            item.put("persistent", info.isPersistent());
            connections.add(item);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("connections", connections);
        stateReporter.report("TOPOLOGY", payload);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("无限自动化测试 Agent 前台服务");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundInternal() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, AgentService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("无限自动化测试 Agent")
                .setContentText("前台服务运行中，BLE 调度与服务器长连接保持活跃")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setOngoing(true);
        return builder.build();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            Log.w(TAG, "PowerManager not available");
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgentService::KeepAlive");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        Log.i(TAG, "PARTIAL_WAKE_LOCK acquired");
    }

    private void registerBluetoothReceiver() {
        bluetoothReceiver = new BluetoothStateReceiver(new BluetoothStateReceiver.Callback() {
            @Override
            public void onBluetoothDisabled() {
                Log.w(TAG, "Bluetooth disabled, release slots and pause scheduler");
                // SDD §12.10：蓝牙关闭 → 暂停授槽、释放全部槽位，连接态设备由
                // onSlotReleased 置 DISCONNECTED（保留欠账）；REGISTERED/WAITING_SLOT 不受影响。
                if (connectionScheduler != null) {
                    connectionScheduler.suspendScheduling();
                }
                stateReporter.report("ERROR", buildErrorPayload(1001, "bluetooth disabled", null));
            }

            @Override
            public void onBluetoothEnabled() {
                Log.i(TAG, "Bluetooth enabled, resume scheduler");
                // 恢复授槽，欠账请求按优先级重新竞争槽位（§12.10）。
                if (connectionScheduler != null) {
                    connectionScheduler.resumeScheduling();
                }
            }
        });
        registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    private void registerNetworkReceiver() {
        // M1 可选：监听网络变化，用于日志与异常提示；不阻塞核心流程。
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                    ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
                    boolean connected = info != null && info.isConnected();
                    Log.i(TAG, "network changed, connected=" + connected);
                    if (!connected) {
                        // TCP 心跳/重连由 TcpClient 负责；Service 仅做日志标记。
                        stateReporter.report("ERROR", buildErrorPayload(2002, "network disconnected", null));
                    }
                }
            }
        };
        registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    private void releaseResources() {
        initialized.set(false);

        if (heartbeatManager != null) {
            heartbeatManager.stop();
            heartbeatManager = null;
        }
        if (statsCollector != null) {
            statsCollector.stop();
        }
        if (connectionScheduler != null) {
            connectionScheduler.stop();
        }
        if (pollingScheduler != null) {
            pollingScheduler.stop();
        }
        if (tcpClient != null) {
            tcpClient.disconnect();
        }
        if (bleCentralManager != null) {
            // TODO：后续由 BleCentralManager 提供 cleanup() 接口释放底层 BluetoothGatt。
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "PARTIAL_WAKE_LOCK released");
        }
        if (bluetoothReceiver != null) {
            unregisterReceiver(bluetoothReceiver);
            bluetoothReceiver = null;
        }
        if (networkReceiver != null) {
            unregisterReceiver(networkReceiver);
            networkReceiver = null;
        }
        stopForeground(true);
    }

    private Map<String, Object> buildErrorPayload(int errorCode, String message, String deviceMac) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("errorCode", errorCode);
        payload.put("message", message);
        if (deviceMac != null) {
            payload.put("deviceMac", deviceMac);
        }
        return payload;
    }

    private static int getInt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static long getLong(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static String getString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    /**
     * 给绑定端提供的简单 Binder。
     */
    public class AgentBinder extends Binder {

        public AgentService getService() {
            return AgentService.this;
        }
    }

    /**
     * TCP 监听：优先处理 REGISTER_ACK，其余命令分发给 CommandDispatcher。
     */
    private class AgentTcpListener implements TcpListener {

        @Override
        public void onCommand(Map<String, Object> command) {
            String type = getString(command, "type", "");
            if ("REGISTER_ACK".equals(type)) {
                handleRegisterAck(command);
            } else if ("UPLOAD_LOG".equals(type)) {
                // §13：日志打包经 LOG_FRAME 二进制帧上传（Service 持有 TcpClient，就地处理）。
                handleUploadLog(command);
            } else {
                commandDispatcher.dispatch(command);
            }
        }

        @Override
        public void onFrame(byte[] frame) {
            // 文件/日志二进制帧（§16.3）路由到 FileTransferManager。
            if (frame == null) {
                return;
            }
            if (fileTransferManager != null) {
                fileTransferManager.onFrame(frame);
            } else {
                Log.d(TAG, "onFrame length=" + frame.length + " (no transfer manager)");
            }
        }

        @Override
        public void onDisconnected() {
            Log.w(TAG, "tcp disconnected");
            // 下载中任务挂起，重连后经 FILE_DOWNLOAD_RESUME 续传（§7.6）。
            if (fileTransferManager != null) {
                fileTransferManager.onTcpDisconnected();
            }
        }

        @Override
        public void onConnected() {
            // 断线重连后重新注册（§7.1）；写队列已保证 REGISTER 不丢（TcpClientImpl）。
            AgentLog.i(TAG, "tcp (re)connected, re-register");
            connectAndRegister();
            startHeartbeat();
        }
    }

    // ==================== Stub 实现（M1 占位，由对应模块替换） ====================

    private static class StubTcpClient implements TcpClient {
        private TcpListener listener;

        @Override
        public void connect(String host, int port) {
            Log.d(TAG, "StubTcpClient.connect " + host + ":" + port);
        }

        @Override
        public void disconnect() {
            Log.d(TAG, "StubTcpClient.disconnect");
        }

        @Override
        public void sendJson(Map<String, Object> msg) {
            // §11.3 脱敏：报文可能携带敏感载荷，只记类型不记内容。
            Log.d(TAG, "StubTcpClient.sendJson type=" + (msg == null ? null : msg.get("type")));
        }

        @Override
        public void sendFrame(byte[] frame) {
            Log.d(TAG, "StubTcpClient.sendFrame length=" + (frame == null ? 0 : frame.length));
        }

        @Override
        public void setListener(TcpListener listener) {
            this.listener = listener;
        }

        @Override
        public boolean isConnected() {
            return false;
        }
    }

    private static class StubBleCentralManager implements BleCentralManager {
        @Override
        public void init() {
            Log.d(TAG, "StubBleCentralManager.init");
        }

        @Override
        public boolean isBleAvailable() {
            return BluetoothAdapter.getDefaultAdapter() != null
                    && BluetoothAdapter.getDefaultAdapter().isEnabled();
        }

        @Override
        public DeviceController createController(String mac, String deviceId) {
            return new StubDeviceController(mac, deviceId);
        }

        @Override
        public void destroyController(String mac) {
            Log.d(TAG, "StubBleCentralManager.destroyController " + mac);
        }

        @Override
        public int supportedMaxConnections() {
            return 5;
        }
    }

    private static class StubDeviceController implements DeviceController {
        private final String mac;
        private final String deviceId;
        private DeviceState state = DeviceState.REGISTERED;
        private PollingConfig pollingConfig;

        StubDeviceController(String mac, String deviceId) {
            this.mac = mac;
            this.deviceId = deviceId;
        }

        @Override
        public void pause(boolean abortTransfer) {
            state = DeviceState.PAUSED;
        }

        @Override
        public void resume() {
            state = DeviceState.REGISTERED;
        }

        @Override
        public void terminate() {
            state = DeviceState.TERMINATED;
        }

        @Override
        public void onSlotAcquired() {
            state = DeviceState.CONNECTING;
        }

        @Override
        public void onSlotReleased() {
            state = DeviceState.DISCONNECTED;
        }

        @Override
        public boolean enqueueCommand(com.longcheer.agent.model.GattCommand cmd) {
            // no-op
            return true;
        }

        @Override
        public void enqueuePollTask(com.longcheer.agent.model.PollingTask task) {
            // no-op
        }

        @Override
        public void setPollingConfig(PollingConfig config) {
            this.pollingConfig = config;
        }

        @Override
        public void setPollRules(List<com.longcheer.agent.model.PollRule> rules) {
            // no-op
        }

        @Override
        public boolean isReady() {
            return state == DeviceState.READY;
        }

        @Override
        public DeviceState getState() {
            return state;
        }

        @Override
        public ManagedDeviceInfo snapshot() {
            ManagedDeviceInfo info = new ManagedDeviceInfo(deviceId, mac);
            info.setState(state);
            info.setPollingConfig(pollingConfig);
            return info;
        }

        @Override
        public void setStateFlag(String stateFlag) {
            // no-op
        }

        @Override
        public void updatePollTimes(long lastPollTime, long nextPollTime) {
            // no-op
        }

        @Override
        public void setPollDataStale(boolean stale) {
            // no-op
        }

        @Override
        public void updatePollResult(Map<UUID, byte[]> lastPollResult) {
            // no-op
        }

        @Override
        public void setNotifyBoostUntil(long notifyBoostUntil) {
            // no-op
        }

        @Override
        public void onAbnormalDisconnect() {
            if (state == DeviceState.DISCONNECTED) {
                state = DeviceState.RECONNECTING;
            }
        }

        @Override
        public void onReconnectBackoffExpired() {
            if (state == DeviceState.RECONNECTING) {
                state = DeviceState.WAITING_SLOT;
            }
        }

        @Override
        public void onReconnectGiveUp() {
            if (state == DeviceState.RECONNECTING || state == DeviceState.DISCONNECTED) {
                state = DeviceState.ERROR;
            }
        }

        @Override
        public List<com.longcheer.agent.model.QueuedTask> drainPendingCommands() {
            return new ArrayList<>();
        }

        @Override
        public void reset() {
            state = DeviceState.REGISTERED;
        }
    }

    private static class StubDeviceRegistry implements DeviceRegistry {
        private final Map<String, DeviceController> controllers = new HashMap<>();

        @Override
        public void register(DeviceController controller) {
            controllers.put(controller.snapshot().getMac(), controller);
        }

        @Override
        public void unregister(String mac) {
            controllers.remove(mac);
        }

        @Override
        public DeviceController findByMac(String mac) {
            return controllers.get(mac);
        }

        @Override
        public DeviceController findByDeviceId(String deviceId) {
            return null;
        }

        @Override
        public List<DeviceController> findByState(DeviceState state) {
            return new ArrayList<>();
        }

        @Override
        public List<DeviceController> findAllOrderByPriorityDesc() {
            return new ArrayList<>(controllers.values());
        }

        @Override
        public List<String> allMacs() {
            return new ArrayList<>(controllers.keySet());
        }

        @Override
        public List<DeviceController> allControllers() {
            return new ArrayList<>(controllers.values());
        }

        @Override
        public int size() {
            return controllers.size();
        }

        @Override
        public void clear() {
            controllers.clear();
        }
    }

    private static class StubConnectionSlotManager implements ConnectionSlotManager {
        @Override
        public int slotCount() {
            return 0;
        }

        @Override
        public com.longcheer.agent.model.ConnectionSlot acquire(String deviceMac, boolean pinned) {
            return null;
        }

        @Override
        public void release(String deviceMac) {
            // no-op
        }

        @Override
        public void forceRelease(String deviceMac) {
            // no-op
        }

        @Override
        public void setLimit(int maxSlots) {
            // no-op
        }

        @Override
        public com.longcheer.agent.model.ConnectionSlot slotOf(String deviceMac) {
            return null;
        }

        @Override
        public List<String> releaseLeakedSlots() {
            return new ArrayList<>();
        }

        @Override
        public List<com.longcheer.agent.model.ConnectionSlot> occupiedSlots() {
            return new ArrayList<>();
        }
    }

    private static class StubConnectionScheduler implements ConnectionScheduler {
        @Override
        public void start() {
            Log.d(TAG, "StubConnectionScheduler.start");
        }

        @Override
        public void stop() {
            Log.d(TAG, "StubConnectionScheduler.stop");
        }

        @Override
        public void requestSlot(ConnectionRequest request) {
            Log.d(TAG, "StubConnectionScheduler.requestSlot " + request.getDeviceMac());
        }

        @Override
        public void cancelRequest(String deviceMac) {
            Log.d(TAG, "StubConnectionScheduler.cancelRequest " + deviceMac);
        }

        @Override
        public boolean setMaxSlots(int max) {
            Log.d(TAG, "StubConnectionScheduler.setMaxSlots " + max);
            return true;
        }

        @Override
        public void setPersistent(String mac, boolean on) {
            Log.d(TAG, "StubConnectionScheduler.setPersistent " + mac + "=" + on);
        }

        @Override
        public void pin(String mac, String reason) {
            Log.d(TAG, "StubConnectionScheduler.pin " + mac + " reason=" + reason);
        }

        @Override
        public void unpin(String mac) {
            Log.d(TAG, "StubConnectionScheduler.unpin " + mac);
        }

        @Override
        public void releaseSlot(String deviceMac) {
            Log.d(TAG, "StubConnectionScheduler.releaseSlot " + deviceMac);
        }

        @Override
        public void suspendScheduling() {
            Log.d(TAG, "StubConnectionScheduler.suspendScheduling");
        }

        @Override
        public void resumeScheduling() {
            Log.d(TAG, "StubConnectionScheduler.resumeScheduling");
        }

        @Override
        public int requiredSlots() {
            return 0;
        }

        @Override
        public void onAbnormalDisconnect(String deviceMac) {
            Log.d(TAG, "StubConnectionScheduler.onAbnormalDisconnect " + deviceMac);
        }

        @Override
        public void cancelAllRequests() {
            Log.d(TAG, "StubConnectionScheduler.cancelAllRequests");
        }
    }

    private static class StubPollingScheduler implements PollingScheduler {
        @Override
        public void start() {
            Log.d(TAG, "StubPollingScheduler.start");
        }

        @Override
        public void stop() {
            Log.d(TAG, "StubPollingScheduler.stop");
        }

        @Override
        public void updateConfig(String mac, PollingConfig config) {
            Log.d(TAG, "StubPollingScheduler.updateConfig " + mac);
        }

        @Override
        public void removeConfig(String mac) {
            Log.d(TAG, "StubPollingScheduler.removeConfig " + mac);
        }

        @Override
        public void suspendPolling(String mac) {
            Log.d(TAG, "StubPollingScheduler.suspendPolling " + mac);
        }

        @Override
        public void resumePolling(String mac) {
            Log.d(TAG, "StubPollingScheduler.resumePolling " + mac);
        }

        @Override
        public void resetAll() {
            Log.d(TAG, "StubPollingScheduler.resetAll");
        }

        @Override
        public void onPollCompleted(String mac, Map<UUID, byte[]> rawResults) {
            Log.d(TAG, "StubPollingScheduler.onPollCompleted " + mac);
        }

        @Override
        public void onPollFailed(String mac, int errorCode, int rawStatus) {
            Log.d(TAG, "StubPollingScheduler.onPollFailed " + mac);
        }

        @Override
        public long estimateFullRoundMs() {
            return 0;
        }
    }

    private static class StubStateReporter implements StateReporter {
        @Override
        public void report(String event, Map<String, Object> payload) {
            // §11.3 脱敏：只记事件名，不记 payload（可能含敏感字段）。
            Log.d(TAG, "StubStateReporter.report " + event);
        }

        @Override
        public void reportCommandAck(String requestId, int errorCode, Object result) {
            Log.d(TAG, "StubStateReporter.reportCommandAck requestId=" + requestId);
        }

        @Override
        public void reportDeviceState(String mac, DeviceState state) {
            Log.d(TAG, "StubStateReporter.reportDeviceState " + mac + " " + state);
        }

        @Override
        public void reportPollResult(String mac, Map<String, Object> fields, boolean stale) {
            Log.d(TAG, "StubStateReporter.reportPollResult " + mac);
        }

        @Override
        public void reportFileProgress(String taskId, double percent, long bytesPerSec) {
            Log.d(TAG, "StubStateReporter.reportFileProgress " + taskId);
        }

        @Override
        public void flush() {
            Log.d(TAG, "StubStateReporter.flush");
        }
    }

    private static class StubCommandDispatcher implements CommandDispatcher {
        @Override
        public void dispatch(Map<String, Object> command) {
            // §11.3 脱敏：只记命令类型，不记完整命令（可能含 WRITE_CHAR payload）。
            Log.d(TAG, "StubCommandDispatcher.dispatch type=" + command.get("type"));
        }
    }

    private static class StubPollResultChain implements PollResultChain {
        @Override
        public Map<String, Object> decode(String mac, Map<UUID, byte[]> raw) {
            return new HashMap<>();
        }

        @Override
        public List<com.longcheer.agent.model.PollRule.RuleAction> evaluate(String mac, Map<String, Object> fields) {
            return new ArrayList<>();
        }

        @Override
        public void execute(String mac, List<com.longcheer.agent.model.PollRule.RuleAction> actions) {
            // no-op
        }

        @Override
        public void registerHandler(String deviceType, com.longcheer.agent.poll.PollResultHandler handler) {
            // no-op
        }

        @Override
        public boolean updateDeviceConfig(String mac, String deviceType,
                                          List<com.longcheer.agent.model.FieldMapping> fields,
                                          List<com.longcheer.agent.model.PollRule> rules,
                                          Map<UUID, UUID> profile) {
            return true;
        }

        @Override
        public boolean setPollRules(String mac, List<com.longcheer.agent.model.PollRule> rules) {
            return true;
        }

        @Override
        public Map<String, Object> process(String mac, Map<UUID, byte[]> raw) {
            return new HashMap<>();
        }

        @Override
        public void onNotification(String mac, UUID charUuid, byte[] value) {
            // no-op
        }

        @Override
        public UUID resolveService(String mac, UUID charUuid) {
            return null;
        }
    }
}
