package com.longcheer.agent.ble;

import com.longcheer.agent.model.GattResult;
import com.longcheer.agent.model.PollStep;

/**
 * 单步 GATT 操作的传输抽象：生产实现走 BluetoothGatt（真机 BLE 里程碑），
 * 单元测试用 mock。同步执行，自带超时（PollStep.timeoutMs）。
 */
public interface GattTransport {

    /**
     * 同步执行一步 GATT 操作（READ/WRITE）。
     *
     * @return 操作结果；失败时 status 透传原始 GATT status
     */
    GattResult execute(String deviceMac, PollStep step);
}
