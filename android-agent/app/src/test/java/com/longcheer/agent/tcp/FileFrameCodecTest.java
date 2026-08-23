package com.longcheer.agent.tcp;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileFrameCodecTest {

    @Test
    public void encodeAndDecodeFileFrame() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] frame = FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_FRAME, 42, payload);

        assertEquals(2 + 1 + 4 + 4 + payload.length + 2, frame.length);
        assertEquals(0xAC, frame[0] & 0xFF);
        assertEquals(0x42, frame[1] & 0xFF);
        assertEquals(FileFrameCodec.FrameType.FILE_FRAME.getCode(), frame[2] & 0xFF);

        FileFrameCodec.Frame decoded = FileFrameCodec.decode(frame);
        assertEquals(FileFrameCodec.FrameType.FILE_FRAME, decoded.type);
        assertEquals(42, decoded.seq);
        assertArrayEquals(payload, decoded.payload);
    }

    @Test
    public void allFrameTypesRoundTrip() {
        for (FileFrameCodec.FrameType type : FileFrameCodec.FrameType.values()) {
            byte[] payload = type.name().getBytes();
            byte[] frame = FileFrameCodec.encode(type, type.ordinal() + 1, payload);
            FileFrameCodec.Frame decoded = FileFrameCodec.decode(frame);
            assertEquals(type, decoded.type);
            assertEquals(type.ordinal() + 1, decoded.seq);
            assertArrayEquals(payload, decoded.payload);
        }
    }

    @Test
    public void crcErrorDetectedWhenPayloadCorrupted() {
        byte[] payload = new byte[]{0x11, 0x22, 0x33};
        byte[] frame = FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_FRAME, 7, payload);
        // 篡改 payload 的第一个字节
        frame[FileFrameCodec.HEADER_SIZE] ^= 0xFF;

        try {
            FileFrameCodec.decode(frame);
            fail("Expected IllegalArgumentException for CRC mismatch");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("CRC"));
        }
    }

    @Test
    public void crcErrorDetectedWhenCrcCorrupted() {
        byte[] payload = new byte[]{0x11, 0x22, 0x33};
        byte[] frame = FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_FRAME, 7, payload);
        // 篡改末尾 CRC 的一个字节
        frame[frame.length - 1] ^= 0xFF;

        try {
            FileFrameCodec.decode(frame);
            fail("Expected IllegalArgumentException for CRC mismatch");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("CRC"));
        }
    }

    @Test
    public void emptyPayloadAllowed() {
        byte[] frame = FileFrameCodec.encode(FileFrameCodec.FrameType.FILE_END, 99, null);
        FileFrameCodec.Frame decoded = FileFrameCodec.decode(frame);
        assertEquals(FileFrameCodec.FrameType.FILE_END, decoded.type);
        assertEquals(99, decoded.seq);
        assertEquals(0, decoded.payload.length);
    }

    @Test
    public void payloadLargerThan64KRejected() {
        byte[] big = new byte[FileFrameCodec.MAX_PAYLOAD_SIZE + 1];
        Arrays.fill(big, (byte) 0xAA);
        try {
            FileFrameCodec.encode(FileFrameCodec.FrameType.LOG_FRAME, 1, big);
            fail("Expected IllegalArgumentException for oversized payload");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("too large"));
        }
    }
}
