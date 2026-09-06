package com.longcheer.agent;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.SystemClock;

import com.longcheer.agent.ble.BleCentralManagerImpl;
import com.longcheer.agent.ble.DeviceControllerDeps;
import com.longcheer.agent.ble.GattExecutorImpl;
import com.longcheer.agent.ble.GattResponseBus;
import com.longcheer.agent.ble.GattTransportImpl;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.dispatch.CommandDispatcher;
import com.longcheer.agent.dispatch.CommandDispatcherImpl;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.FileTransferTask;
import com.longcheer.agent.poll.ActionExecutor;
import com.longcheer.agent.poll.PollResultChain;
import com.longcheer.agent.poll.PollResultChainImpl;
import com.longcheer.agent.registry.ActiveConnectionPool;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.registry.DeviceRegistryImpl;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.report.StateReporterImpl;
import com.longcheer.agent.report.StatsCollector;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.ConnectionSchedulerImpl;
import com.longcheer.agent.schedule.ConnectionSlotManager;
import com.longcheer.agent.schedule.ConnectionSlotManagerImpl;
import com.longcheer.agent.schedule.PollingScheduler;
import com.longcheer.agent.schedule.PollingSchedulerImpl;
import com.longcheer.agent.tcp.TcpClient;
import com.longcheer.agent.tcp.TcpClientImpl;
import com.longcheer.agent.transfer.FileTransferManager;
import com.longcheer.agent.transfer.TransferAdapter;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 生产装配器：把真实模块组装成依赖图（替代 AgentService 的 Stub）。
 *
 * <p>BLE 不可用（无 BluetoothAdapter）时返回 null，由 AgentService 回退 Stub 并上报。
 * 装配顺序即依赖顺序；两个构造环（ActionExecutor↔PollingScheduler、
 * BleCentralManager↔DeviceControllerDeps）经 setter 补注打破。</p>
 */
public final class AgentAssembler {

    private static final String TAG = "AgentAssembler";

    /** 装配产物。 */
    public static final class Components {
        public TcpClient tcpClient;
        public BleCentralManagerImpl bleCentralManager;
        public DeviceRegistry deviceRegistry;
        public ConnectionSlotManager connectionSlotManager;
        public ConnectionScheduler connectionScheduler;
        public PollingScheduler pollingScheduler;
        public StateReporter stateReporter;
        public CommandDispatcher commandDispatcher;
        public PollResultChain pollResultChain;
        public FileTransferManager fileTransferManager;
        public StatsCollector statsCollector;
    }

    private AgentAssembler() {
    }

    /**
     * 装配真实组件。
     *
     * @return 组件集；BLE 不可用（无蓝牙硬件）返回 null
     */
    /**
     * 装配真实组件。
     *
     * @param simulateDut 虚拟 DUT 模式（模拟器/CI）：无蓝牙硬件也装配真实组件图，
     *                    GATT 客户端委托 {@link SimulatedGattClient}，
     *                    传输适配器用 {@link SimulatedTransferAdapter}
     * @return 组件集；BLE 不可用（无蓝牙硬件且未开虚拟 DUT）返回 null
     */
    public static Components assemble(Context context, boolean simulateDut) {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null && !simulateDut) {
            AgentLog.e(TAG, "no BluetoothAdapter, assembly aborted");
            return null;
        }

        Components c = new Components();
        // 内置默认配置兜底（§16.4：未下发字段用默认值）；REGISTER_ACK 后整项替换生效。
        AgentConfig config = AgentConfig.fromJson(null);

        c.deviceRegistry = new DeviceRegistryImpl();
        // 槽位容量 = SDD 硬上限 5；可用上限由 applyConfig 的 setMaxSlots 调整（§5.4）。
        c.connectionSlotManager = new ConnectionSlotManagerImpl(5);
        ActiveConnectionPool activePool = new ActiveConnectionPool();
        c.connectionScheduler = new ConnectionSchedulerImpl(c.connectionSlotManager,
                c.deviceRegistry, activePool, config);
        c.tcpClient = new TcpClientImpl();
        c.stateReporter = new StateReporterImpl(c.tcpClient, config);

        GattResponseBus responseBus = new GattResponseBus();
        ActionExecutor actionExecutor = new ActionExecutor(c.deviceRegistry, c.connectionScheduler,
                null, c.stateReporter, SystemClock::elapsedRealtime);
        PollResultChainImpl chain = new PollResultChainImpl(actionExecutor, c.stateReporter,
                c.deviceRegistry, config, SystemClock::elapsedRealtime);
        c.pollResultChain = chain;
        PollingSchedulerImpl pollingScheduler = new PollingSchedulerImpl(c.deviceRegistry,
                c.connectionScheduler, config, chain, c.stateReporter);
        c.pollingScheduler = pollingScheduler;
        actionExecutor.setPollingScheduler(pollingScheduler); // 补注，破环

        c.bleCentralManager = new BleCentralManagerImpl(context, adapter, c.deviceRegistry, null);
        if (simulateDut) {
            // 虚拟 DUT：GattClient 工厂委托模拟实现（无 BluetoothAdapter 依赖）。
            java.util.concurrent.Executor callbackExecutor = java.util.concurrent.Executors
                    .newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "SimDutCallback");
                        t.setDaemon(true);
                        return t;
                    });
            c.bleCentralManager.setClientFactoryDelegate(
                    new com.longcheer.agent.ble.DeviceControllerDeps.GattClientFactory() {
                        @Override
                        public com.longcheer.agent.ble.GattClient create(
                                String mac, com.longcheer.agent.ble.GattClient.Callback cb) {
                            return new com.longcheer.agent.ble.SimulatedGattClient(mac, cb,
                                    callbackExecutor);
                        }

                        @Override
                        public void removeClient(String mac) {
                            // 虚拟 DUT 无资源可清
                        }
                    });
            AgentLog.i(TAG, "simulateDut mode: virtual DUT active");
        }
        GattTransportImpl transport = new GattTransportImpl(c.bleCentralManager, responseBus,
                chain::resolveService);
        GattExecutorImpl gattExecutor = new GattExecutorImpl(transport, pollingScheduler);
        ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "BleWatchdog");
            t.setDaemon(true);
            return t;
        });
        c.bleCentralManager.setDepsTemplate(new DeviceControllerDeps(c.bleCentralManager,
                responseBus, gattExecutor, c.stateReporter, chain, c.connectionScheduler,
                config, SystemClock::elapsedRealtime, watchdog)); // 补注，破环

        c.statsCollector = new StatsCollector(c.connectionSlotManager);
        ((ConnectionSchedulerImpl) c.connectionScheduler).setStatsCollector(c.statsCollector);
        pollingScheduler.setStatsCollector(c.statsCollector);

        File transferDir = new File(context.getFilesDir(), "transfer");
        if (simulateDut) {
            c.fileTransferManager = new FileTransferManager(c.deviceRegistry, c.connectionScheduler,
                    pollingScheduler, c.stateReporter, config, transferDir,
                    (task, device) -> new com.longcheer.agent.transfer.SimulatedTransferAdapter());
        } else {
            // DUT 传输协议适配器：真机协议（标准 OTA 或自定义）确定后实现并注入（§7.6）。
            c.fileTransferManager = new FileTransferManager(c.deviceRegistry, c.connectionScheduler,
                    pollingScheduler, c.stateReporter, config, transferDir,
                    (task, device) -> new UnsupportedTransferAdapter());
        }

        c.commandDispatcher = new CommandDispatcherImpl(c.bleCentralManager, c.deviceRegistry,
                c.connectionScheduler, c.pollingScheduler, c.stateReporter, config, chain,
                c.fileTransferManager);
        AgentLog.i(TAG, "real components assembled" + (simulateDut ? " (simulateDut)" : ""));
        return c;
    }

    /** 真实模式装配（模拟器开关关闭）。 */
    public static Components assemble(Context context) {
        return assemble(context, false);
    }

    /** 默认 TransferAdapter：未配置 DUT 协议时明确失败（不静默），任务报 4003。 */
    private static final class UnsupportedTransferAdapter implements TransferAdapter {
        @Override
        public void handshake(FileTransferTask task, DeviceController device) throws TransferException {
            throw new TransferException("DUT transfer adapter not configured");
        }

        @Override
        public void sendChunk(byte[] chunk, int seq) throws TransferException {
            throw new TransferException("DUT transfer adapter not configured");
        }

        @Override
        public WindowAck waitWindowAck(int windowSeq, long timeoutMs) {
            return WindowAck.TIMEOUT;
        }

        @Override
        public boolean supportsOffsetWrite() {
            return false;
        }
    }
}
