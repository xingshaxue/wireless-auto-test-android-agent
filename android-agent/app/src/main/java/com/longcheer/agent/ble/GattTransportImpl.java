package com.longcheer.agent.ble;

import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.GattResult;
import com.longcheer.agent.model.PollStep;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * {@link GattTransport} 实现：把异步 GATT 桥接为同步 {@link GattResult}
 * （CompletableFuture + 按 PollStep.timeoutMs 超时，§12.2）。
 */
public class GattTransportImpl implements GattTransport {

    private static final String TAG = "GattTransport";

    private final GattClientProvider clientProvider;
    private final GattResponseBus responseBus;
    /** profile 解析（§16.4）：step 未显式给 service 时按设备 profile 解析 char→service。 */
    private final BiFunction<String, UUID, UUID> serviceResolver;

    public GattTransportImpl(GattClientProvider clientProvider, GattResponseBus responseBus,
                             BiFunction<String, UUID, UUID> serviceResolver) {
        this.clientProvider = clientProvider;
        this.responseBus = responseBus;
        this.serviceResolver = serviceResolver;
    }

    @Override
    public GattResult execute(String deviceMac, PollStep step) {
        GattClient client = clientProvider.getActiveClient(deviceMac);
        if (client == null) {
            return GattResult.fail(133); // 设备未连接
        }
        UUID service = step.serviceUuid;
        if (service == null && serviceResolver != null) {
            service = serviceResolver.apply(deviceMac, step.charUuid);
        }
        if (service == null) {
            // §16.4：profile 缺失且 step 未给 service → 配置错误（1003 特征不存在）。
            AgentLog.w(TAG, "no service mapping for " + step.charUuid + " on " + deviceMac);
            return GattResult.fail(143);
        }
        String op = step.type == GattCommand.Type.WRITE ? "WRITE" : "READ";
        CompletableFuture<GattResult> future = responseBus.begin(deviceMac, step.charUuid, op);
        try {
            if (step.type == GattCommand.Type.WRITE) {
                // §7.6：writeType 由命令指定——WRITE_NR 特征（如 LC 产测通道）必须
                // NO_RESPONSE，否则对端按 ATT 0xFC 拒绝（真机实测）。
                client.writeCharacteristic(service, step.charUuid, step.payload,
                        step.writeNoResponse);
            } else {
                client.readCharacteristic(service, step.charUuid);
            }
            return future.get(Math.max(500, step.timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            AgentLog.w(TAG, op + " timeout/error: " + e.getMessage());
            return GattResult.fail(-1); // 超时（§12.2）
        }
    }
}
