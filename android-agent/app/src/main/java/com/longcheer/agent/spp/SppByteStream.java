package com.longcheer.agent.spp;

import com.longcheer.agent.log.AgentLog;

import java.io.IOException;
import java.util.Arrays;

/**
 * SPP 二进制块通道：RFCOMM 承载 061/062/063 导出协议（{@link SppClient} 是
 * ASCII 行通道，服务 33x 传输；导出回包是二进制帧，按原始块分接）。
 *
 * <p>读线程把每次 socket read 的字节原样回调给 {@link ChunkListener}（不解析、
 * 不切行），帧切分/ASCII 归类全部由上层（LcExporter 字节流机）处理——SPP 是
 * 可靠流式承载，无 BLE notify 丢包/幽灵插入问题。</p>
 */
public class SppByteStream {

    private static final String TAG = "SppByteStream";
    private static final int READ_BUF_SIZE = 4096;

    /** 原始字节块监听（读线程回调；SPP 无消息边界，块大小由驱动决定）。 */
    public interface ChunkListener {
        void onChunk(byte[] chunk);
    }

    private final SppClient.Connector connector;

    private SppClient.SocketChannel channel;
    private Thread readerThread;
    private volatile boolean closed = true;
    private volatile ChunkListener listener;

    public SppByteStream(SppClient.Connector connector) {
        this.connector = connector;
    }

    /** 建立 RFCOMM 连接并启动读线程；重复调用先关旧连接。 */
    public synchronized void connect(String mac) throws IOException {
        close();
        closed = false;
        SppClient.SocketChannel ch = connector.connect(mac, SppClient.CONNECT_TIMEOUT_MS);
        this.channel = ch;
        readerThread = new Thread(() -> readLoop(ch), "SppByteReader");
        readerThread.setDaemon(true);
        readerThread.start();
        AgentLog.i(TAG, "spp byte stream connected: " + mac);
    }

    /** 写裸字节（061/062/063 ASCII 命令）；未连接/已关闭抛 IOException。 */
    public void write(byte[] data) throws IOException {
        SppClient.SocketChannel ch = channel;
        if (closed || ch == null) {
            throw new IOException("spp byte stream not connected");
        }
        ch.write(data);
    }

    public void setChunkListener(ChunkListener l) {
        this.listener = l;
    }

    public boolean isConnected() {
        return !closed && channel != null;
    }

    /** 关闭 socket 与读线程；幂等。 */
    public synchronized void close() {
        closed = true;
        SppClient.SocketChannel ch = channel;
        channel = null;
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException e) {
                AgentLog.w(TAG, "spp byte stream close failed: " + e.getMessage());
            }
        }
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
            readerThread = null;
        }
    }

    /** 读线程：每次 read 的字节精确拷贝后回调；通道关闭/异常即退出。 */
    private void readLoop(SppClient.SocketChannel ch) {
        byte[] buf = new byte[READ_BUF_SIZE];
        while (!closed) {
            int n;
            try {
                n = ch.read(buf);
            } catch (IOException e) {
                if (!closed) {
                    AgentLog.w(TAG, "spp byte stream read failed: " + e.getMessage());
                }
                return;
            }
            if (n < 0) {
                return; // 对端关闭
            }
            if (n == 0) {
                continue;
            }
            ChunkListener l = listener;
            if (l != null) {
                l.onChunk(Arrays.copyOf(buf, n));
            }
        }
    }
}
