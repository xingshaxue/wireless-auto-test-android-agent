package com.longcheer.agent.tcp;

import java.util.Map;

/**
 * TCP 事件监听（SDD §16.1）。
 */
public interface TcpListener {

    void onCommand(Map<String, Object> command);

    void onFrame(byte[] frame);

    void onDisconnected();

    /** 每次（重）连成功时回调；AgentService 借此重新 REGISTER（断线重连后重新注册）。 */
    default void onConnected() {
    }
}
