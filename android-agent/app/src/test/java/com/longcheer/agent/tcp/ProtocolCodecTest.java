package com.longcheer.agent.tcp;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProtocolCodecTest {

    private ProtocolCodec codec;

    @Before
    public void setUp() {
        codec = new ProtocolCodec();
    }

    @Test
    public void encodeJsonThenDecode() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "REGISTER");
        msg.put("timestamp", 1720000000000L);
        msg.put("deviceId", "agent-001");

        byte[] encoded = codec.encodeJson(msg);
        assertTrue(encoded.length > 4);
        assertEquals(0x7B, encoded[4] & 0xFF); // '{'

        List<ProtocolCodec.DecodedMessage> decoded = codec.decodeStream(encoded);
        assertEquals(1, decoded.size());
        assertTrue(decoded.get(0).isJson());
        assertEquals(msg, decoded.get(0).getJson());
    }

    @Test
    public void binaryFrameDemux() {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", "FILE_TRANSFER");
        cmd.put("requestId", "r1");
        byte[] jsonFrame = codec.encodeJson(cmd);

        byte[] payload = new byte[]{0x0A, 0x0B, 0x0C, 0x0D};
        byte[] fileFrame = FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_FRAME, 1, payload);

        byte[] combined = concat(jsonFrame, fileFrame);
        // 故意在文件帧中间切开，验证流解复用
        int split = jsonFrame.length + 5;

        List<ProtocolCodec.DecodedMessage> first = codec.decodeStream(Arrays.copyOfRange(combined, 0, split));
        assertEquals(1, first.size());
        assertTrue(first.get(0).isJson());
        assertEquals(cmd, first.get(0).getJson());

        List<ProtocolCodec.DecodedMessage> second = codec.decodeStream(Arrays.copyOfRange(combined, split, combined.length));
        assertEquals(1, second.size());
        assertTrue(second.get(0).isBinary());
        assertArrayEquals(fileFrame, second.get(0).getFrame());
    }

    @Test
    public void halfPacketBuffering() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "HEARTBEAT");
        msg.put("timestamp", 1720000000001L);
        byte[] full = codec.encodeJson(msg);

        // 只发前 2 字节（长度前缀的一半）
        List<ProtocolCodec.DecodedMessage> part1 = codec.decodeStream(Arrays.copyOfRange(full, 0, 2));
        assertTrue(part1.isEmpty());

        // 补齐剩余字节
        List<ProtocolCodec.DecodedMessage> part2 = codec.decodeStream(Arrays.copyOfRange(full, 2, full.length));
        assertEquals(1, part2.size());
        assertEquals(msg, part2.get(0).getJson());
    }

    @Test
    public void rejectPayloadExceedingOneMegabyte() {
        char[] big = new char[ProtocolCodec.MAX_PAYLOAD_SIZE + 1];
        Arrays.fill(big, 'x');
        Map<String, Object> msg = Collections.singletonMap("data", new String(big));
        try {
            codec.encodeJson(msg);
            fail("Expected IllegalArgumentException for oversized payload");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("too large"));
        }
    }

    @Test
    public void decodeStreamWithoutNewDataConsumesExistingBuffer() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "CMD_ACK");
        msg.put("requestId", "r2");
        msg.put("errorCode", 0);
        byte[] full = codec.encodeJson(msg);

        // 先给一半
        codec.decodeStream(Arrays.copyOfRange(full, 0, 3));
        // 不给新数据，仅消费已有缓存，应仍然无法得到完整报文
        List<ProtocolCodec.DecodedMessage> result = codec.decodeStream(null);
        assertTrue(result.isEmpty());

        // 补齐剩余字节，本次调用应直接消费缓存并返回完整报文
        List<ProtocolCodec.DecodedMessage> result2 = codec.decodeStream(Arrays.copyOfRange(full, 3, full.length));
        assertEquals(1, result2.size());
        assertEquals(msg, result2.get(0).getJson());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
