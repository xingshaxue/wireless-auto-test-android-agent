package com.longcheer.agent.spp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import com.longcheer.agent.log.AgentLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * SPP（经典蓝牙 RFCOMM）socket 封装：P67 手环 FACTEST 模式的 SPP 加速通道
 * （真机实测：insecure RFCOMM 直连、免配对，约 3s 连上；ASCII 应答按行到达）。
 *
 * <p>读线程把对端 ASCII 应答按行推入邮箱（trim \r\n，空行丢弃），会话线程经
 * {@link #awaitLine} 阻塞取；二进制数据只写不读（33x 协议的回包全是 ASCII 行）。</p>
 *
 * <p>单测通过注入 fake {@link Connector}/{@link SocketChannel} 走真实读线程与邮箱路径。</p>
 */
public class SppClient {

    private static final String TAG = "SppClient";

    /** P67 FACTEST SPP 服务 UUID（真机实测免配对）。 */
    public static final UUID SPP_SERVICE_UUID =
            UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3");

    /** RFCOMM 建连超时（真机实测约 3s，留足余量）。 */
    public static final long CONNECT_TIMEOUT_MS = 15000L;

    /** RFCOMM 字节通道抽象（生产 = BluetoothSocket 包装；单测 = 内存管道）。 */
    public interface SocketChannel {
        void write(byte[] data) throws IOException;

        /** 阻塞读，返回读取字节数；-1 = 对端关闭。 */
        int read(byte[] buf) throws IOException;

        void close() throws IOException;
    }

    /** 连接抽象：按 MAC 建 RFCOMM 通道。 */
    public interface Connector {
        SocketChannel connect(String mac, long timeoutMs) throws IOException;
    }

    /**
     * 生产连接器：insecure RFCOMM 直连。BluetoothSocket.connect 自身无超时，
     * 用独立线程 + Future.get(timeout) 包一层，超时即关 socket。
     */
    public static class BluetoothConnector implements Connector {

        private final BluetoothAdapter adapter;

        public BluetoothConnector(BluetoothAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public SocketChannel connect(String mac, long timeoutMs) throws IOException {
            if (adapter == null) {
                throw new IOException("no BluetoothAdapter");
            }
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(mac);
            } catch (IllegalArgumentException e) {
                throw new IOException("bad mac: " + mac, e);
            }
            final BluetoothSocket socket;
            try {
                socket = device.createInsecureRfcommSocketToServiceRecord(SPP_SERVICE_UUID);
            } catch (IOException | RuntimeException e) {
                throw new IOException("create rfcomm socket failed: " + e.getMessage(), e);
            }
            java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "SppConnect");
                        t.setDaemon(true);
                        return t;
                    });
            java.util.concurrent.Future<?> connectTask = executor.submit(() -> {
                try {
                    socket.connect();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                connectTask.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                throw new IOException("rfcomm connect timeout (" + timeoutMs + "ms): " + mac);
            } catch (Exception e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                Throwable cause = e instanceof java.util.concurrent.ExecutionException
                        ? e.getCause() : e;
                if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                    throw (IOException) cause.getCause();
                }
                throw new IOException("rfcomm connect failed: " + cause);
            } finally {
                executor.shutdownNow();
            }
            return new BluetoothSocketChannel(socket);
        }
    }

    /** BluetoothSocket → SocketChannel 薄包装。 */
    private static final class BluetoothSocketChannel implements SocketChannel {
        private final BluetoothSocket socket;
        private final InputStream in;
        private final OutputStream out;

        BluetoothSocketChannel(BluetoothSocket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        @Override
        public void write(byte[] data) throws IOException {
            out.write(data);
            out.flush();
        }

        @Override
        public int read(byte[] buf) throws IOException {
            return in.read(buf);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private final Connector connector;

    private SocketChannel channel;
    private Thread readerThread;
    private volatile boolean closed = true;

    /** 对端 ASCII 应答邮箱（读线程投入，已按行 trim；与 LC 适配器 mailbox 同模式）。 */
    private final BlockingQueue<String> mailbox = new LinkedBlockingQueue<>();

    public SppClient(Connector connector) {
        this.connector = connector;
    }

    /** 建立 RFCOMM 连接并启动读线程；重复调用先关旧连接。 */
    public synchronized void connect(String mac) throws IOException {
        close();
        closed = false;
        SocketChannel ch = connector.connect(mac, CONNECT_TIMEOUT_MS);
        this.channel = ch;
        readerThread = new Thread(() -> readLoop(ch), "SppReader");
        readerThread.setDaemon(true);
        readerThread.start();
        AgentLog.i(TAG, "spp connected: " + mac);
    }

    /** 写裸字节（命令 ASCII 或数据包）；未连接/已关闭抛 IOException。 */
    public void write(byte[] data) throws IOException {
        SocketChannel ch = channel;
        if (closed || ch == null) {
            throw new IOException("spp not connected");
        }
        ch.write(data);
    }

    /** 阻塞取一行 ASCII 应答（已 trim）；超时返回 null。 */
    public String awaitLine(long timeoutMs) {
        try {
            return mailbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public boolean isConnected() {
        return !closed && channel != null;
    }

    /** 关闭 socket 与读线程；幂等。 */
    public synchronized void close() {
        closed = true;
        SocketChannel ch = channel;
        channel = null;
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException e) {
                AgentLog.w(TAG, "spp close failed: " + e.getMessage());
            }
        }
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
            readerThread = null;
        }
    }

    /**
     * 读线程：含 \n 时按行切（trim \r\n）；整段无换行则把本段（含未切残留）整体作为
     * 一条应答——固件 SPP 应答未确认带换行尾（真机 "300"→"300"），对齐 LC 通道
     * per-notify 即一条消息的口径。通道关闭/异常即退出。
     */
    private void readLoop(SocketChannel ch) {
        byte[] buf = new byte[1024];
        StringBuilder pending = new StringBuilder();
        while (!closed) {
            int n;
            try {
                n = ch.read(buf);
            } catch (IOException e) {
                if (!closed) {
                    AgentLog.w(TAG, "spp read failed: " + e.getMessage());
                }
                return;
            }
            if (n < 0) {
                return; // 对端关闭
            }
            pending.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
            if (indexOf(pending, '\n') < 0) {
                String line = pending.toString().trim();
                pending.setLength(0);
                if (!line.isEmpty()) {
                    mailbox.offer(line);
                }
                continue;
            }
            int idx;
            while ((idx = indexOf(pending, '\n')) >= 0) {
                String line = pending.substring(0, idx).trim();
                pending.delete(0, idx + 1);
                if (!line.isEmpty()) {
                    mailbox.offer(line);
                }
            }
        }
    }

    private static int indexOf(StringBuilder sb, char c) {
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }
}
