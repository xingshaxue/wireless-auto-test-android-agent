package com.longcheer.agent.ble;

import android.os.SystemClock;

import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.DeviceStateMachine;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.model.PollRule;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.model.PollingTask;
import com.longcheer.agent.model.QueuedTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DeviceController 实现（SDD §3.7 / §7.2 / §12.1）。
 *
 * <p>两种模式：2 参构造为模拟模式（M1 骨架，单测用）；注入 {@link DeviceControllerDeps}
 * 后走真实 BLE 建连流程——CONNECTING（connectGatt + 看门狗）→ SERVICE_DISCOVERING →
 * CONFIGURING（MTU 阶梯 + 通知订阅）→ READY。回调只抛事件，所有迁移经状态机校验（§4.1）。</p>
 */
public class DeviceControllerImpl implements DeviceController {

    private static final String TAG = "DeviceController";

    /** MTU 协商阶梯（§3.2）：逐级失败后保持当前值（默认 23）。 */
    static final int[] MTU_LADDER = {512, 247, 185};
    /** 服务发现失败重试上限（§12.1：137 重试 3 次）。 */
    static final int SERVICE_DISCOVERY_MAX_RETRY = 3;

    private final ManagedDeviceInfo info;
    private final DeviceControllerDeps deps; // null = 模拟模式
    private DeviceStateMachine stateMachine = new DeviceStateMachine();
    private final Object lock = new Object();
    private volatile boolean pendingPause = false;
    private volatile boolean abortTransferOnPause = false;
    private List<PollRule> pollRules = Collections.emptyList();

    // ---- 真实连接会话状态（仅 deps != null 时使用） ----
    private GattClient client;
    private volatile boolean intentionalDisconnect = false;
    private String pendingConnectRequestId;
    private int mtuLadderIndex = 0;
    private int negotiatedMtu = 23;
    private List<UUID> pendingNotifyChars = Collections.emptyList();
    private int notifySubscribeIndex = 0;
    private int serviceDiscoveryRetries = 0;
    private int lastGattStatus = 0;
    /** 建连代数：每次 startRealConnect 自增，用于作废上一轮残留的看门狗。 */
    private int connectGeneration = 0;

    public DeviceControllerImpl(String deviceId, String mac) {
        this(deviceId, mac, null);
    }

    public DeviceControllerImpl(String deviceId, String mac, DeviceControllerDeps deps) {
        this.info = new ManagedDeviceInfo(deviceId, mac);
        this.deps = deps;
    }

    // ==================== 生命周期 ====================

    @Override
    public void pause(boolean abortTransfer) {
        synchronized (lock) {
            DeviceState state = info.getState();
            switch (state) {
                case REGISTERED:
                case WAITING_SLOT:
                case READY:
                case DISCONNECTED:
                case ERROR:
                    transitionTo(DeviceState.PAUSED);
                    break;
                case CONNECTING:
                case SERVICE_DISCOVERING:
                case CONFIGURING:
                case POLLING:
                case COMMANDING:
                case RECONNECTING:
                    pendingPause = true;
                    abortTransferOnPause = abortTransfer;
                    break;
                case PAUSED:
                case TERMINATED:
                    // no-op
                    break;
            }
        }
    }

    @Override
    public void resume() {
        synchronized (lock) {
            if (info.getState() != DeviceState.PAUSED) {
                return;
            }
            if (!info.getPendingCommands().isEmpty()) {
                transitionTo(DeviceState.WAITING_SLOT);
            } else {
                transitionTo(DeviceState.REGISTERED);
            }
            pendingPause = false;
            abortTransferOnPause = false;
        }
    }

    @Override
    public void terminate() {
        synchronized (lock) {
            info.getPendingCommands().clear();
            closeClient();
            transitionTo(DeviceState.TERMINATED);
        }
    }

    @Override
    public void onSlotAcquired() {
        synchronized (lock) {
            DeviceState state = info.getState();
            switch (state) {
                case WAITING_SLOT:
                    break;
                case REGISTERED:
                case DISCONNECTED:
                    // §4.1：REGISTERED/DISCONNECTED 不能直达 CONNECTING，须经 WAITING_SLOT。
                    // DISCONNECTED 必须受理：调度器授槽白名单（§16.5 GRANTABLE_STATES）含
                    // DISCONNECTED，而 FILE_TRANSFER 不在控制器队列挂任务（§7.6），设备可能
                    // 刚被时间片释放——若静默返回，pinned 槽位悬挂、设备永不 READY（真机联调暴露）。
                    transitionTo(DeviceState.WAITING_SLOT);
                    break;
                case RECONNECTING:
                    // 授槽与 GATT 回调线程的异常断开存在竞态：槽已授予时设备可能刚进入
                    // 退避。pinned 高优请求（§7.6）应使其尽快重排——经 WAITING_SLOT 转
                    // CONNECTING（§4.1：RECONNECTING→CONNECTING 非法）；未决退避计时到期后
                    // 见非 RECONNECTING 状态自动失效。
                    transitionTo(DeviceState.WAITING_SLOT);
                    break;
                default:
                    // READY/CONNECTING 等：已在池中或建连在飞，等待自然就绪即可。
                    return;
            }
            transitionTo(DeviceState.CONNECTING);
            if (deps == null) {
                // 模拟模式（M1 骨架，单测用）：直接迁移到 READY。
                transitionTo(DeviceState.SERVICE_DISCOVERING);
                transitionTo(DeviceState.CONFIGURING);
                transitionTo(DeviceState.READY);
                return;
            }
        }
        startRealConnect();
    }

    @Override
    public void onSlotReleased() {
        synchronized (lock) {
            DeviceState state = info.getState();
            switch (state) {
                case CONNECTING:
                case SERVICE_DISCOVERING:
                case CONFIGURING:
                case READY:
                case POLLING:
                case COMMANDING:
                    // §4.1：以上状态 → DISCONNECTED 均为合法迁移（主动断开/被踢/超预算强释放）。
                    info.setDisconnectedTime(nowMs());
                    closeClient();
                    transitionTo(DeviceState.DISCONNECTED);
                    break;
                default:
                    break;
            }
        }
    }

    // ==================== 真实建连流程（§7.2 / §12.1） ====================

    private void startRealConnect() {
        intentionalDisconnect = false;
        mtuLadderIndex = 0;
        negotiatedMtu = 23;
        serviceDiscoveryRetries = 0;
        AgentLog.i(TAG, "connecting " + info.getMac());
        client = deps.clientFactory.create(info.getMac(), gattCallback);
        client.connect();
        final int generation = ++connectGeneration;
        deps.watchdogExecutor.schedule(() -> onConnectWatchdog(generation),
                deps.config.getConnectTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 建连硬超时看门狗（§7.2 connectTimeoutMs，包可见供单测）：按当前代数驱动。 */
    void onConnectWatchdog() {
        onConnectWatchdog(connectGeneration);
    }

    private void onConnectWatchdog(int generation) {
        synchronized (lock) {
            if (generation != connectGeneration) {
                return; // 上一轮建连残留的看门狗，避免误杀新一轮 CONNECTING
            }
            DeviceState state = info.getState();
            if (state != DeviceState.CONNECTING && state != DeviceState.SERVICE_DISCOVERING
                    && state != DeviceState.CONFIGURING) {
                return; // 已 READY 或已离开建连流程
            }
        }
        AgentLog.w(TAG, "connect watchdog timeout: " + info.getMac());
        handleAbnormalDisconnect(-1);
    }

    private final GattClient.Callback gattCallback = new GattClient.Callback() {
        @Override
        public void onConnected() {
            synchronized (lock) {
                if (info.getState() != DeviceState.CONNECTING) {
                    return;
                }
                transitionTo(DeviceState.SERVICE_DISCOVERING);
            }
            client.discoverServices();
        }

        @Override
        public void onDisconnected(int status) {
            synchronized (lock) {
                if (intentionalDisconnect) {
                    return;
                }
            }
            // §12.1：onConnectionStateChange 的 133 = 连接失败/断开，走 §7.5 重连。
            handleAbnormalDisconnect(status);
        }

        @Override
        public void onServicesDiscovered(int status) {
            synchronized (lock) {
                if (info.getState() != DeviceState.SERVICE_DISCOVERING) {
                    return;
                }
                if (status != 0) {
                    serviceDiscoveryRetries++;
                    if (serviceDiscoveryRetries >= SERVICE_DISCOVERY_MAX_RETRY) {
                        AgentLog.w(TAG, "service discovery failed after retries: " + info.getMac());
                        handleAbnormalDisconnectLocked(status);
                        return;
                    }
                    AgentLog.w(TAG, "service discovery retry " + serviceDiscoveryRetries
                            + ": " + info.getMac());
                    client.discoverServices();
                    return;
                }
                transitionTo(DeviceState.CONFIGURING);
            }
            requestNextMtu();
        }

        @Override
        public void onMtuChanged(int mtu, int status) {
            boolean readyToFinish = false;
            synchronized (lock) {
                if (info.getState() != DeviceState.CONFIGURING) {
                    return;
                }
                if (status == 0) {
                    negotiatedMtu = mtu; // 记录实际协商值（§3.2）
                    AgentLog.i(TAG, info.getMac() + " MTU=" + mtu);
                    readyToFinish = startNotifySubscribeLocked();
                } else {
                    mtuLadderIndex++;
                }
            }
            if (readyToFinish) {
                onReady();
            } else if (status != 0) {
                requestNextMtu(); // 阶梯降级（512→247→185→默认 23）
            }
        }

        @Override
        public void onRead(UUID charUuid, byte[] value, int status) {
            deps.responseBus.onRead(info.getMac(), charUuid, value, status);
        }

        @Override
        public void onWrite(UUID charUuid, int status) {
            deps.responseBus.onWrite(info.getMac(), charUuid, status);
        }

        @Override
        public void onNotify(UUID charUuid, byte[] value) {
            // §7.3.3：通知进处理链（保槽与节流在链内）。
            deps.pollResultChain.onNotification(info.getMac(), charUuid, value);
            // §7.6：带外通道（LC 文件传输等）的原始 Notify 分接。
            deps.responseBus.onNotify(info.getMac(), charUuid, value);
        }

        @Override
        public void onNotifySubscribed(UUID charUuid, int status) {
            // §7.6：带外通道（LC 传输握手等 CCCD 写结果）的等待通路，与状态机无关先行投递。
            deps.responseBus.onNotifySubscribed(info.getMac(), charUuid, status);
            boolean readyToFinish = false;
            synchronized (lock) {
                if (info.getState() != DeviceState.CONFIGURING) {
                    return;
                }
                if (status != 0) {
                    AgentLog.w(TAG, "notify subscribe failed: " + charUuid + " status=" + status);
                }
                notifySubscribeIndex++;
                if (notifySubscribeIndex < pendingNotifyChars.size()) {
                    UUID next = pendingNotifyChars.get(notifySubscribeIndex);
                    UUID serviceUuid = deps.pollResultChain.resolveService(info.getMac(), next);
                    if (serviceUuid != null) {
                        client.setNotification(serviceUuid, next, true);
                    } else {
                        AgentLog.w(TAG, "no service mapping for notify char " + next + " (1003)");
                    }
                    return;
                }
                readyToFinish = true;
            }
            if (readyToFinish) {
                onReady();
            }
        }
    };

    private void requestNextMtu() {
        GattClient c = client;
        if (c == null) {
            return;
        }
        boolean readyToFinish = false;
        int mtuToRequest = -1;
        synchronized (lock) {
            if (mtuLadderIndex >= MTU_LADDER.length) {
                // 阶梯全部失败：保持默认 23（§3.2）。
                AgentLog.w(TAG, info.getMac() + " MTU ladder exhausted, keep default 23");
                readyToFinish = startNotifySubscribeLocked();
            } else {
                mtuToRequest = MTU_LADDER[mtuLadderIndex];
            }
        }
        if (readyToFinish) {
            onReady();
        } else if (mtuToRequest > 0) {
            c.requestMtu(mtuToRequest);
        }
    }

    /**
     * 按 notifyCharacteristics 逐个订阅（§7.3.3 CONFIGURING 阶段）。须在锁内调用。
     *
     * @return true = 无订阅项（或全部因 profile 缺失被跳过），调用方应在锁外收口 READY
     */
    private boolean startNotifySubscribeLocked() {
        PollingConfig cfg = info.getPollingConfig();
        List<UUID> chars = cfg == null ? Collections.emptyList() : cfg.getNotifyCharacteristics();
        pendingNotifyChars = chars == null ? Collections.emptyList() : new ArrayList<>(chars);
        notifySubscribeIndex = 0;
        while (notifySubscribeIndex < pendingNotifyChars.size()) {
            UUID charUuid = pendingNotifyChars.get(notifySubscribeIndex);
            UUID serviceUuid = deps.pollResultChain.resolveService(info.getMac(), charUuid);
            if (serviceUuid == null) {
                // §16.4：profile 缺失 → 按 1003 配置错误告警并跳过该特征。
                AgentLog.w(TAG, "no service mapping for notify char " + charUuid + " on "
                        + info.getMac() + " (1003)");
                notifySubscribeIndex++;
                continue;
            }
            client.setNotification(serviceUuid, charUuid, true);
            return false; // 等待 onNotifySubscribed 推进
        }
        return true;
    }

    /** 配置完成 → READY（唯一决策点，§7.2.1）：回 CONNECT_DEVICE ACK、drain 队列。 */
    private void onReady() {
        synchronized (lock) {
            if (info.getState() != DeviceState.CONFIGURING) {
                return;
            }
            transitionTo(DeviceState.READY);
        }
        String requestId;
        synchronized (lock) {
            requestId = pendingConnectRequestId;
            pendingConnectRequestId = null;
        }
        if (requestId != null) {
            // §7.2：CONNECT_DEVICE 的 ACK 语义为"设备已就绪"。
            deps.stateReporter.reportCommandAck(requestId, 0, null);
        }
    }

    /** 异常断开统一入口（回调线程）：清客户端、走 §7.5 重连。 */
    private void handleAbnormalDisconnect(int status) {
        synchronized (lock) {
            handleAbnormalDisconnectLocked(status);
        }
    }

    private void handleAbnormalDisconnectLocked(int status) {
        DeviceState state = info.getState();
        if (state != DeviceState.CONNECTING && state != DeviceState.SERVICE_DISCOVERING
                && state != DeviceState.CONFIGURING && state != DeviceState.READY
                && state != DeviceState.POLLING && state != DeviceState.COMMANDING) {
            return;
        }
        lastGattStatus = status;
        AgentLog.w(TAG, "abnormal disconnect: " + info.getMac() + " status=" + status);
        closeClient();
        // 立即放槽 + RECONNECTING 退避由调度器驱动（§7.5）。
        deps.connectionScheduler.onAbnormalDisconnect(info.getMac());
    }

    private void closeClient() {
        intentionalDisconnect = true;
        GattClient c = client;
        client = null;
        if (c != null) {
            c.disconnectAndClose();
        }
        if (deps != null) {
            deps.responseBus.cancelAll(info.getMac());
            deps.clientFactory.removeClient(info.getMac());
        }
    }

    // ==================== 队列与配置 ====================

    @Override
    public boolean enqueueCommand(GattCommand cmd) {
        boolean executeNow;
        synchronized (lock) {
            if (info.getState() == DeviceState.TERMINATED) {
                return false;
            }
            if (info.getPendingCommands().size() >= info.getMaxPendingCommands()) {
                // §7.4.1：队列满不入队，由调用方立即回 3001 QUEUE_FULL。
                return false;
            }
            info.getPendingCommands().offer(cmd);
            if (info.getState() == DeviceState.REGISTERED || info.getState() == DeviceState.DISCONNECTED) {
                transitionTo(DeviceState.WAITING_SLOT);
            }
            executeNow = info.getState() == DeviceState.READY;
        }
        // §7.4 第 4 步：设备已在池中且 READY → 立即执行（drain 为原子出队，重复调用无害）。
        if (executeNow) {
            drainPendingTasks();
        }
        return true;
    }

    @Override
    public void enqueuePollTask(PollingTask task) {
        boolean executeNow;
        synchronized (lock) {
            if (info.getState() == DeviceState.TERMINATED || info.getState() == DeviceState.PAUSED) {
                return;
            }
            if (info.getPendingCommands().size() >= info.getMaxPendingCommands()) {
                return;
            }
            info.getPendingCommands().offer(task);
            if (info.getState() == DeviceState.REGISTERED || info.getState() == DeviceState.DISCONNECTED) {
                transitionTo(DeviceState.WAITING_SLOT);
            }
            executeNow = info.getState() == DeviceState.READY;
        }
        // §7.3：READY 期间到期的轮询立即执行——drain 不只发生在 READY 迁移瞬间，
        // 否则持槽设备上后到的任务会滞留队列（真机联调暴露）。
        if (executeNow) {
            drainPendingTasks();
        }
    }

    @Override
    public void setPollingConfig(PollingConfig config) {
        synchronized (lock) {
            info.setPollingConfig(config);
        }
    }

    @Override
    public void setPollRules(List<PollRule> rules) {
        synchronized (lock) {
            this.pollRules = rules == null ? Collections.emptyList() : Collections.unmodifiableList(rules);
        }
    }

    /**
     * 挂起 CONNECT_DEVICE 的 requestId（§7.2 ACK 语义 = 已就绪）：
     * READY 时回 0；最终失败（ERROR）时回 1xxx + rawStatus。
     */
    public void setPendingConnectAck(String requestId) {
        synchronized (lock) {
            this.pendingConnectRequestId = requestId;
        }
    }

    // ==================== 断线重连与软重置（§7.5 / §7.8） ====================

    @Override
    public void onAbnormalDisconnect() {
        synchronized (lock) {
            // §7.5：异常断开 → RECONNECTING（退避计时不持槽）。调用方须先释放槽位
            // （onSlotReleased 已将设备置 DISCONNECTED）。
            if (info.getState() == DeviceState.DISCONNECTED) {
                transitionTo(DeviceState.RECONNECTING);
            }
        }
    }

    @Override
    public void onReconnectBackoffExpired() {
        synchronized (lock) {
            if (info.getState() != DeviceState.RECONNECTING) {
                return;
            }
            transitionTo(DeviceState.WAITING_SLOT);
            // §7.7：暂停期间不申请槽位，补迁 PAUSED。
            if (pendingPause) {
                pendingPause = false;
                transitionTo(DeviceState.PAUSED);
            }
        }
    }

    @Override
    public void onReconnectGiveUp() {
        synchronized (lock) {
            // §4.1：放弃重连只在 RECONNECTING 发生（DISCONNECTED→ERROR 非法）。
            if (info.getState() == DeviceState.RECONNECTING) {
                transitionTo(DeviceState.ERROR);
            }
        }
        // CONNECT_DEVICE 挂起 ACK 的最终失败回报（§7.2 第 8 条）。
        String requestId;
        synchronized (lock) {
            requestId = pendingConnectRequestId;
            if (info.getState() == DeviceState.ERROR) {
                pendingConnectRequestId = null;
            }
        }
        if (requestId != null && info.getState() == DeviceState.ERROR && deps != null) {
            deps.stateReporter.reportCommandAck(requestId, 1001, lastGattStatus, null);
        }
    }

    @Override
    public List<QueuedTask> drainPendingCommands() {
        synchronized (lock) {
            List<QueuedTask> drained = new ArrayList<>(info.getPendingCommands());
            info.getPendingCommands().clear();
            return drained;
        }
    }

    @Override
    public void reset() {
        synchronized (lock) {
            // §7.8 软重置：状态机重建回 REGISTERED（RESET 不走 4.1 常规迁移）。
            info.getPendingCommands().clear();
            closeClient();
            stateMachine = new DeviceStateMachine();
            info.setState(DeviceState.REGISTERED);
            info.setReconnectCount(0);
            info.setStateFlag(null);
            pendingPause = false;
            abortTransferOnPause = false;
            pendingConnectRequestId = null;
            AgentLog.i(TAG, "device " + info.getMac() + " reset to REGISTERED");
        }
    }

    // ==================== 显式写接口（§8.1） ====================

    @Override
    public boolean isReady() {
        synchronized (lock) {
            return info.getState() == DeviceState.READY;
        }
    }

    @Override
    public DeviceState getState() {
        synchronized (lock) {
            return info.getState();
        }
    }

    @Override
    public ManagedDeviceInfo snapshot() {
        synchronized (lock) {
            // M1: 返回内部引用（骨架阶段），后续改为深拷贝不可变快照。
            return info;
        }
    }

    @Override
    public void setStateFlag(String stateFlag) {
        synchronized (lock) {
            info.setStateFlag(stateFlag);
        }
    }

    @Override
    public void updatePollTimes(long lastPollTime, long nextPollTime) {
        synchronized (lock) {
            info.setLastPollTime(lastPollTime);
            info.setNextPollTime(nextPollTime);
        }
    }

    @Override
    public void setPollDataStale(boolean stale) {
        synchronized (lock) {
            info.setPollDataStale(stale);
        }
    }

    @Override
    public void updatePollResult(Map<UUID, byte[]> lastPollResult) {
        synchronized (lock) {
            info.setLastPollResult(lastPollResult);
        }
    }

    @Override
    public void setNotifyBoostUntil(long notifyBoostUntil) {
        synchronized (lock) {
            info.setNotifyBoostUntil(notifyBoostUntil);
        }
    }

    public ManagedDeviceInfo getInfo() {
        synchronized (lock) {
            return info;
        }
    }

    /** @return 实际协商的 MTU（§3.2 记录值），模拟模式返回默认 23。 */
    @Override
    public int getNegotiatedMtu() {
        return negotiatedMtu;
    }

    Object getLock() {
        return lock;
    }

    private long nowMs() {
        return deps == null ? SystemClock.elapsedRealtime() : deps.clock.getAsLong();
    }

    private void transitionTo(DeviceState newState) {
        // §4.1：迁移合法性由状态机拦截，非法迁移抛 IllegalStateException。
        DeviceState old = info.getState();
        stateMachine.transition(newState);
        info.setState(newState);
        AgentLog.i(TAG, "device " + info.getMac() + " state " + old + " -> " + newState);
        reportDeviceState(newState);
        if (newState == DeviceState.READY) {
            info.setLastConnectedTime(nowMs());
            info.setReconnectCount(0); // §7.5：重连成功清零计数
            drainPendingTasks();
        }
        // §7.7 pendingPause：连接中/活动中的暂停延迟到 READY 或 DISCONNECTED 补迁。
        if (pendingPause && (newState == DeviceState.READY || newState == DeviceState.DISCONNECTED)) {
            pendingPause = false;
            AgentLog.i(TAG, "device " + info.getMac() + " apply pendingPause -> PAUSED");
            transitionTo(DeviceState.PAUSED);
        }
    }

    /** DEVICE_STATE 事件上报（§10.2：state 取枚举名；ERROR 带 errorCode/rawStatus）。 */
    private void reportDeviceState(DeviceState newState) {
        if (deps == null || deps.stateReporter == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceMac", info.getMac());
        payload.put("state", newState.name());
        if (newState == DeviceState.ERROR) {
            payload.put("errorCode", 1001);
            if (lastGattStatus != 0) {
                payload.put("rawStatus", lastGattStatus);
            }
        }
        deps.stateReporter.report("DEVICE_STATE", payload);
    }

    /** READY 时刻消费欠账（§7.2.1 唯一决策点）：按优先级 drain 提交 GattExecutor 串行执行。 */
    private void drainPendingTasks() {
        if (deps == null || deps.gattExecutor == null) {
            return; // 模拟模式不消费，保持 M1 行为
        }
        List<QueuedTask> tasks = drainPendingCommands();
        for (QueuedTask task : tasks) {
            if (task instanceof GattCommand) {
                deps.gattExecutor.execute((GattCommand) task);
            } else if (task instanceof PollingTask) {
                deps.gattExecutor.executeTask((PollingTask) task);
            }
        }
        if (!tasks.isEmpty()) {
            AgentLog.i(TAG, "device " + info.getMac() + " drained " + tasks.size() + " tasks");
        }
    }
}
