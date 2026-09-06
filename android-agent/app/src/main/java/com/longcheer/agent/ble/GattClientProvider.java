package com.longcheer.agent.ble;

/**
 * 活动 GATT 客户端提供方（由 BleCentralManagerImpl 实现）：GattTransport 据此
 * 找到设备当前连接。设备未连接返回 null。
 */
public interface GattClientProvider {

    GattClient getActiveClient(String mac);
}
