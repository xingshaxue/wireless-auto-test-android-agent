package com.longcheer.agent.tcp;

import java.util.Map;

/**
 * TCP 事件监听（SDD §16.1）。
 */
public interface TcpListener {

    void onCommand(Map<String, Object> command);

    void onFrame(byte[] frame);

    void onDisconnected();
}
