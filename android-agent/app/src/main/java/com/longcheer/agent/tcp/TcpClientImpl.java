package com.longcheer.agent.tcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.longcheer.agent.log.AgentLog;

/**
 * {@link TcpClient} 的基础实现（SDD §3.1 / §16.1）。
 *
 * <p>特性：
 * <ul>
 *   <li>使用 {@link Socket} 建立 TCP 长连接。</li>
 *   <li>读、写、连接/重连均在独立线程执行，不阻塞主线程。</li>
 *   <li>断线后指数退避重连（1s 起步，封顶 60s）。</li>
 *   <li>收到数据后交给 {@link ProtocolCodec} 解析，再回调 {@link TcpListener}。</li>
 * </ul>
 */
public class TcpClientImpl implements TcpClient {

    private static final String TAG = "TcpClient";

    private static final int READ_BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final int MAX_RECONNECT_DELAY_MS = 60000;

    private final ProtocolCodec codec = new ProtocolCodec();
    // 断连期间写请求不丢弃（修复 REGISTER 丢包）：排队到重连后按序发出。
    private final BlockingDeque<byte[]> writeQueue = new LinkedBlockingDeque<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private volatile TcpListener listener;
    private volatile Socket socket;
    private volatile OutputStream outputStream;

    private String host;
    private int port;
    /** 可选 TLS（§11.2）：跨网络部署时启用；握手失败不降级为明文。 */
    private volatile boolean tlsEnabled = false;

    private ExecutorService connectExecutor;
    private ExecutorService readerExecutor;
    private ExecutorService writerExecutor;

    private final Object stateLock = new Object();

    @Override
    public void connect(String host, int port) {
        this.host = host;
        this.port = port;
        if (started.compareAndSet(false, true)) {
            startThreads();
        }
    }

    private void startThreads() {
        connectExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "TcpConnect"));
        readerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "TcpReader"));
        writerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "TcpWriter"));
        writerExecutor.submit(this::writeLoop);
        connectExecutor.submit(this::connectLoop);
    }

    private void connectLoop() {
        int reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
        while (!stopped.get()) {
            Socket currentSocket = null;
            try {
                currentSocket = new Socket();
                currentSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                if (tlsEnabled) {
                    // §11.2：TLS 握手失败直接走重连，不降级明文。
                    javax.net.ssl.SSLSocket tlsSocket = (javax.net.ssl.SSLSocket)
                            ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                            .createSocket(currentSocket, host, port, true);
                    tlsSocket.startHandshake();
                    currentSocket = tlsSocket;
                    AgentLog.i(TAG, "TLS handshake done: " + tlsSocket.getSession().getCipherSuite());
                }
                socket = currentSocket;
                outputStream = currentSocket.getOutputStream();
                connected.set(true);
                reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
                AgentLog.i(TAG, "TCP connected to " + host + ":" + port);
                notifyConnected();

                final Socket readerSocket = currentSocket;
                readerExecutor.submit(() -> readLoop(readerSocket));

                synchronized (stateLock) {
                    while (connected.get() && !stopped.get()) {
                        stateLock.wait(1000L);
                    }
                }
            } catch (Exception e) {
                if (stopped.get()) {
                    break;
                }
                AgentLog.w(TAG, "TCP connect failed: " + e.getMessage()
                        + ", retry in " + reconnectDelay + "ms");
            } finally {
                if (connected.compareAndSet(true, false)) {
                    notifyDisconnected();
                }
                closeSocket(currentSocket);
            }

            if (stopped.get()) {
                break;
            }
            try {
                Thread.sleep(reconnectDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS);
        }
    }

    private void readLoop(Socket readerSocket) {
        try (InputStream in = readerSocket.getInputStream()) {
            byte[] buf = new byte[READ_BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n > 0) {
                    List<ProtocolCodec.DecodedMessage> messages = codec.decodeStream(Arrays.copyOf(buf, n));
                    dispatch(messages);
                }
            }
        } catch (Exception e) {
            if (!stopped.get()) {
                AgentLog.w(TAG, "TCP read error: " + e.getMessage());
            }
        } finally {
            connected.set(false);
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
        }
    }

    private void writeLoop() {
        while (!stopped.get() || !writeQueue.isEmpty()) {
            byte[] data;
            try {
                data = writeQueue.poll(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (data == null) {
                continue;
            }
            OutputStream out = outputStream;
            if (out == null) {
                // 尚未连接：放回队列头部等待重连，不丢弃（§3.1 断线恢复语义）。
                writeQueue.offerFirst(data);
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            try {
                out.write(data);
                out.flush();
            } catch (Exception e) {
                if (!stopped.get()) {
                    AgentLog.w(TAG, "TCP write error: " + e.getMessage());
                }
                closeSocket(socket);
            }
        }
    }

    private void dispatch(List<ProtocolCodec.DecodedMessage> messages) {
        TcpListener l = listener;
        if (l == null) {
            return;
        }
        for (ProtocolCodec.DecodedMessage msg : messages) {
            try {
                if (msg.isJson()) {
                    l.onCommand(msg.getJson());
                } else {
                    l.onFrame(msg.getFrame());
                }
            } catch (Exception e) {
                AgentLog.w(TAG, "TcpListener callback error: " + e.getMessage());
            }
        }
    }

    @Override
    public void sendJson(Map<String, Object> msg) {
        if (msg == null) {
            throw new IllegalArgumentException("msg is null");
        }
        sendBytes(codec.encodeJson(msg));
    }

    @Override
    public void sendFrame(byte[] frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame is null");
        }
        sendBytes(frame);
    }

    private void sendBytes(byte[] data) {
        writeQueue.offer(data);
    }

    @Override
    public void setListener(TcpListener listener) {
        this.listener = listener;
    }

    /** 启用/关闭 TLS（§11.2 可选；默认内网明文）。 */
    public void setTlsEnabled(boolean enabled) {
        this.tlsEnabled = enabled;
    }

    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void disconnect() {
        close();
    }

    /**
     * 关闭连接并清理所有线程资源。
     */
    public void close() {
        if (stopped.compareAndSet(false, true)) {
            closeSocket(socket);
            shutdown(connectExecutor);
            shutdown(readerExecutor);
            shutdown(writerExecutor);
        }
    }

    private void closeSocket(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
        if (socket == s) {
            socket = null;
            outputStream = null;
        }
    }

    private void shutdown(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void notifyDisconnected() {
        AgentLog.w(TAG, "TCP disconnected from " + host + ":" + port);
        TcpListener l = listener;
        if (l != null) {
            try {
                l.onDisconnected();
            } catch (Exception ignored) {
                // listener 异常不影响重连
            }
        }
    }

    private void notifyConnected() {
        TcpListener l = listener;
        if (l != null) {
            try {
                l.onConnected();
            } catch (Exception ignored) {
                // listener 异常不影响连接流程
            }
        }
    }
}
