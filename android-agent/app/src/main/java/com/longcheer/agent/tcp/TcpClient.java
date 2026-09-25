package com.longcheer.agent.tcp;

import java.util.Map;

/**
 * TCP 客户端接口（SDD §3.1 / §16.1）。
 */
public interface TcpClient {

    void connect(String host, int port);

    /** 断开当前连接并触发立即重连（线程保留）；彻底释放用 {@link #close()}。 */
    void disconnect();

    /** 彻底关闭：停止所有线程，之后不可再用。 */
    void close();

    void sendJson(Map<String, Object> msg);

    void sendFrame(byte[] frame);

    void setListener(TcpListener listener);

    boolean isConnected();
}
