package com.longcheer.agent.ble;

import java.util.UUID;

/**
 * 单设备 GATT 客户端抽象（SDD §3.2）：生产实现为 {@link AndroidGattClient}
 * （BluetoothDevice.connectGatt 薄封装），单元测试用 fake。
 *
 * <p>所有方法均为异步（结果经 {@link Callback} 返回）；回调在构造时注入的
 * Executor 线程上派发，调用方不得在回调中做耗时操作（§9）。</p>
 */
public interface GattClient {

    /** GATT 事件回调。status 为底层原始 GATT status（§12.1/§12.9 rawStatus）。 */
    interface Callback {
        /** 物理连接建立（尚未就绪，需服务发现与配置）。 */
        void onConnected();

        /** 断开（含异常断开；status 如 133 按 §12.1 区分来源）。 */
        void onDisconnected(int status);

        void onServicesDiscovered(int status);

        void onMtuChanged(int mtu, int status);

        void onRead(UUID charUuid, byte[] value, int status);

        void onWrite(UUID charUuid, int status);

        /** 通知/Indicate 数据到达。 */
        void onNotify(UUID charUuid, byte[] value);

        /** 通知订阅（CCC descriptor 写）完成。 */
        void onNotifySubscribed(UUID charUuid, int status);
    }

    /** 发起连接（connectGatt）。重复调用幂等。 */
    void connect();

    /** 主动断开并释放资源。 */
    void disconnectAndClose();

    void discoverServices();

    void requestMtu(int mtu);

    void readCharacteristic(UUID serviceUuid, UUID charUuid);

    /**
     * 写特征。
     *
     * @param noResponse true = WRITE_NO_RESPONSE（文件分块吞吐前提 §7.6）
     * @return false = 协议栈未受理（Tx 队列打满等），调用方可短重试（§7.6）
     */
    boolean writeCharacteristic(UUID serviceUuid, UUID charUuid, byte[] payload, boolean noResponse);

    /** 订阅/退订通知（CONFIGURING 阶段按 notifyCharacteristics 调用，§7.3.3）。 */
    void setNotification(UUID serviceUuid, UUID charUuid, boolean enable);
}
