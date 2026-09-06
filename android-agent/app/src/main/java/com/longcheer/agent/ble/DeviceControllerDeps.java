package com.longcheer.agent.ble;

import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.poll.PollResultChain;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;

/**
 * DeviceControllerImpl 真实模式的依赖包（装配层注入）。为 null 时控制器工作在
 * 模拟模式（M1 骨架行为，供既有单测）。
 */
public final class DeviceControllerDeps {

    /** 按 MAC 创建 GATT 客户端（回调归属发起方）。 */
    public interface GattClientFactory {
        GattClient create(String mac, GattClient.Callback callback);

        /** 连接关闭/失败后从活动注册表移除。 */
        void removeClient(String mac);
    }

    public final GattClientFactory clientFactory;
    public final GattResponseBus responseBus;
    public final GattExecutor gattExecutor;
    public final StateReporter stateReporter;
    public final PollResultChain pollResultChain;
    public final ConnectionScheduler connectionScheduler;
    public final AgentConfig config;
    public final LongSupplier clock;
    /** 建连看门狗线程池（共享）。 */
    public final ScheduledExecutorService watchdogExecutor;

    public DeviceControllerDeps(GattClientFactory clientFactory,
                                GattResponseBus responseBus,
                                GattExecutor gattExecutor,
                                StateReporter stateReporter,
                                PollResultChain pollResultChain,
                                ConnectionScheduler connectionScheduler,
                                AgentConfig config,
                                LongSupplier clock,
                                ScheduledExecutorService watchdogExecutor) {
        this.clientFactory = clientFactory;
        this.responseBus = responseBus;
        this.gattExecutor = gattExecutor;
        this.stateReporter = stateReporter;
        this.pollResultChain = pollResultChain;
        this.connectionScheduler = connectionScheduler;
        this.config = config;
        this.clock = clock;
        this.watchdogExecutor = watchdogExecutor;
    }
}
