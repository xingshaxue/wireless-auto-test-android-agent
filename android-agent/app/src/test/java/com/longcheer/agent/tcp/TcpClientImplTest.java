package com.longcheer.agent.tcp;

import org.junit.After;
import org.junit.Test;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * TcpClientImpl loopback 集成测试（JVM 上真实 socket）。
 *
 * <p>覆盖：断连期间写排队不丢、连接成功 onConnected 回调、重连后按序补发、
 * 二进制帧透传。</p>
 */
public class TcpClientImplTest {

    private TcpClientImpl client;

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    public void queuedWhileDisconnectedThenFlushedAfterConnect() throws Exception {
        int port = freePort();
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        CountDownLatch gotJson = new CountDownLatch(1);

        client = new TcpClientImpl();
        client.setListener(new TcpListener() {
            @Override
            public void onCommand(Map<String, Object> command) {
            }

            @Override
            public void onFrame(byte[] frame) {
            }

            @Override
            public void onDisconnected() {
            }

            @Override
            public void onConnected() {
                connected.countDown();
            }
        });
        client.connect("127.0.0.1", port); // 服务器未起 → 连不上，走重连

        // 断连期间发送：不得丢弃（修复 REGISTER 丢包）。
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "REGISTER");
        msg.put("deviceId", "phone-1");
        client.sendJson(msg);

        // 服务器后起：客户端重连成功后应收到排队报文。
        try (ServerSocket server = new ServerSocket(port)) {
            new Thread(() -> {
                try (Socket conn = server.accept()) {
                    DataInputStream in = new DataInputStream(conn.getInputStream());
                    int len = in.readInt();
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    ProtocolCodec codec = new ProtocolCodec();
                    byte[] withLen = new byte[len + 4];
                    System.arraycopy(buf, 0, withLen, 4, len);
                    withLen[3] = (byte) len;
                    List<ProtocolCodec.DecodedMessage> decoded = codec.decodeStream(withLen);
                    if (!decoded.isEmpty()) {
                        received.set(decoded.get(0).getJson());
                        gotJson.countDown();
                    }
                } catch (Exception ignored) {
                }
            }, "test-server").start();

            assertTrue("client should reconnect", connected.await(15, TimeUnit.SECONDS));
            assertTrue("queued REGISTER should arrive", gotJson.await(15, TimeUnit.SECONDS));
        }
        assertEquals("REGISTER", received.get().get("type"));
        assertEquals("phone-1", received.get().get("deviceId"));
    }

    @Test
    public void binaryFramePassesThrough() throws Exception {
        int port = freePort();
        CountDownLatch gotFrame = new CountDownLatch(1);
        AtomicReference<FileFrameCodec.Frame> received = new AtomicReference<>();

        try (ServerSocket server = new ServerSocket(port)) {
            new Thread(() -> {
                try (Socket conn = server.accept()) {
                    DataInputStream in = new DataInputStream(conn.getInputStream());
                    // 读 magic(2)+type(1)+seq(4)+len(4) 头
                    byte[] header = new byte[FileFrameCodec.HEADER_SIZE];
                    in.readFully(header);
                    int len = ((header[7] & 0xFF) << 24) | ((header[8] & 0xFF) << 16)
                            | ((header[9] & 0xFF) << 8) | (header[10] & 0xFF);
                    byte[] rest = new byte[len + FileFrameCodec.CRC_SIZE];
                    in.readFully(rest);
                    byte[] frameBytes = new byte[FileFrameCodec.HEADER_SIZE + rest.length];
                    System.arraycopy(header, 0, frameBytes, 0, header.length);
                    System.arraycopy(rest, 0, frameBytes, header.length, rest.length);
                    received.set(FileFrameCodec.decode(frameBytes));
                    gotFrame.countDown();
                } catch (Exception ignored) {
                }
            }, "test-server").start();

            client = new TcpClientImpl();
            client.connect("127.0.0.1", port);
            client.sendFrame(FileFrameCodec.encode(FileFrameCodec.FrameType.LOG_FRAME, 1,
                    "hello-log".getBytes()));

            assertTrue(gotFrame.await(15, TimeUnit.SECONDS));
        }
        assertEquals(FileFrameCodec.FrameType.LOG_FRAME, received.get().type);
        assertEquals("hello-log", new String(received.get().payload));
    }
}
