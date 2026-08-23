package com.longcheer.agent.tcp;

import java.util.Map;

/**
 * TCP 客户端接口（SDD §3.1 / §16.1）。
 */
public interface TcpClient {

    void connect(String host, int port);

    void disconnect();

    void sendJson(Map<String, Object> msg);

    void sendFrame(byte[] frame);

    void setListener(TcpListener listener);

    boolean isConnected();
}
