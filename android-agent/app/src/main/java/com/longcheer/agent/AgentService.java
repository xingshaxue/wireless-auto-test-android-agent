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
    /** 注册认证 token Intent extra。 */
    public static final String EXTRA_TOKEN = "token";
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

    // 启动参数
    private String serverHost = "127.0.0.1";
    private int serverPort = 10086;
    private String token = "";
    private String deviceId = "";

    // 运行期配置
    private AgentConfig agentConfig;

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

        parseStartExtras(intent);
        if (!initialized.get()) {
            initializeComponents();
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

        // 初始化 BLE 中心；实现内部负责在主线程完成 BluetoothAdapter 初始化。
        bleCentralManager.init();

        // 装配 TCP 监听：REGISTER_ACK 由 Service 消费，其余命令交给 CommandDispatcher。
        tcpClient.setListener(new AgentTcpListener());

        // 启动调度器。
        connectionScheduler.start();
        pollingScheduler.start();

        // 连接服务器并发送 REGISTER。
        connectAndRegister();
    }

    /**
     * 供测试/外部读取当前是否已完成初始化。
     */
    boolean isInitialized() {
        return initialized.get();
    }

    private void parseStartExtras(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.hasExtra(EXTRA_SERVER_HOST)) {
            serverHost = intent.getStringExtra(EXTRA_SERVER_HOST);
        }
        if (intent.hasExtra(EXTRA_SERVER_PORT)) {
            serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, serverPort);
        }
        if (intent.hasExtra(EXTRA_TOKEN)) {
            token = intent.getStringExtra(EXTRA_TOKEN);
        }
        if (intent.hasExtra(EXTRA_DEVICE_ID)) {
            deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);
        }
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
        payload.put("token", token == null ? "" : token);
        return payload;
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
    }

    private void applyConfig(AgentConfig config) {
        this.agentConfig = config;
        Log.i(TAG, "applyConfig version=" + config.getConfigVersion() + " maxSlots=" + config.getMaxSlots());

        // 调整连接槽上限。
        connectionScheduler.setMaxSlots(config.getMaxSlots());

        // 为 REGISTER_ACK 下发的每台 DUT 创建 Controller，初始状态 REGISTERED，不主动连接。
        List<DeviceConfig> devices = config.getDevices();
        for (DeviceConfig device : devices) {
            String mac = device.getMac();
            String devId = device.getDeviceId();
            if (mac == null || mac.isEmpty() || devId == null || devId.isEmpty()) {
                Log.w(TAG, "skip invalid device config: deviceId=" + devId + " mac=" + mac);
                continue;
            }

            DeviceController controller = bleCentralManager.createController(mac, devId);
            deviceRegistry.register(controller);

            com.longcheer.agent.config.PollingConfig cfg = device.getPolling();
            if (cfg != null) {
                PollingConfig pollingConfig = toModelPollingConfig(cfg);
                controller.setPollingConfig(pollingConfig);
                pollingScheduler.updateConfig(mac, pollingConfig);
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
                result.add(UUID.fromString(item));
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID 跳过，配置校验层应集中告警。
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
            } else {
                commandDispatcher.dispatch(command);
            }
        }

        @Override
        public void onFrame(byte[] frame) {
            // 文件/日志帧当前版本由底层模块自行消费；Service 仅做日志。
            Log.d(TAG, "onFrame length=" + (frame == null ? 0 : frame.length));
        }

        @Override
        public void onDisconnected() {
            Log.w(TAG, "tcp disconnected");
            // TcpClient 内部负责重连；Service 保持前台与调度器运行。
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
            Log.d(TAG, "StubTcpClient.sendJson " + msg);
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
        public void enqueueCommand(com.longcheer.agent.model.GattCommand cmd) {
            // no-op
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
            Log.d(TAG, "StubStateReporter.report " + event + " " + payload);
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
            Log.d(TAG, "StubCommandDispatcher.dispatch " + command);
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
    }
}
