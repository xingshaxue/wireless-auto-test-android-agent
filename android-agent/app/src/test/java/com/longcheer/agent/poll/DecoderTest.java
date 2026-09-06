package com.longcheer.agent.poll;

import com.longcheer.agent.model.FieldMapping;

import org.junit.Test;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Decoder 单测（SDD §8.9 / §16.4）：9 种 format + scale/byteOrder/byteOffset。
 */
public class DecoderTest {

    private static final UUID CHAR_A = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_B = UUID.fromString("00002a21-0000-1000-8000-00805f9b34fb");

    private static FieldMapping mapping(String field, UUID charUuid, FieldMapping.Format format) {
        return new FieldMapping(field, charUuid, format, ByteOrder.LITTLE_ENDIAN, 1.0, 0);
    }

    private static Object parse(byte[] data, FieldMapping.Format format, ByteOrder order,
                                double scale, int offset) {
        return Decoder.parse(data, new FieldMapping("f", CHAR_A, format, order, scale, offset));
    }

    @Test
    public void testUint8() {
        assertEquals(85L, parse(new byte[]{0x55}, FieldMapping.Format.UINT8, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
    }

    @Test
    public void testUint16ByteOrder() {
        byte[] data = {0x01, 0x02};
        assertEquals(513L, parse(data, FieldMapping.Format.UINT16, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
        assertEquals(258L, parse(data, FieldMapping.Format.UINT16, ByteOrder.BIG_ENDIAN, 1.0, 0));
    }

    @Test
    public void testUint32MaxValue() {
        byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertEquals(4294967295L, parse(data, FieldMapping.Format.UINT32, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
    }

    @Test
    public void testSignedFormats() {
        assertEquals(-1L, parse(new byte[]{(byte) 0xFF}, FieldMapping.Format.SINT8, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
        assertEquals(-1L, parse(new byte[]{(byte) 0xFF, (byte) 0xFF}, FieldMapping.Format.SINT16, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
        assertEquals(-2L, parse(new byte[]{(byte) 0xFE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                FieldMapping.Format.SINT32, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
    }

    @Test
    public void testUtf8BoolHex() {
        assertEquals("IDLE", parse("IDLE".getBytes(), FieldMapping.Format.UTF8, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
        assertEquals(true, parse(new byte[]{0x01}, FieldMapping.Format.BOOL, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
        assertEquals(false, parse(new byte[]{0x00}, FieldMapping.Format.BOOL, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
        assertEquals("dead", parse(new byte[]{(byte) 0xDE, (byte) 0xAD}, FieldMapping.Format.HEX, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
    }

    @Test
    public void testScaleAndByteOffset() {
        // 温度 42.0：sint16 原始值 420 × 0.1，从偏移 1 开始。
        byte[] data = {0x00, (byte) 0xA4, 0x01};
        Object v = parse(data, FieldMapping.Format.SINT16, ByteOrder.LITTLE_ENDIAN, 0.1, 1);
        assertEquals(42.0d, ((Number) v).doubleValue(), 0.0001);
    }

    @Test
    public void testShortDataYieldsNull() {
        assertNull(parse(new byte[]{0x01}, FieldMapping.Format.UINT16, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
        assertNull(parse(new byte[0], FieldMapping.Format.UINT8, ByteOrder.LITTLE_ENDIAN, 1.0, 0));
    }

    @Test
    public void testDecodeMapsFieldsAndUnmappedToHex() {
        Map<UUID, byte[]> raw = new HashMap<>();
        raw.put(CHAR_A, new byte[]{0x55});
        raw.put(CHAR_B, new byte[]{0x01, 0x02});

        Map<String, Object> out = Decoder.decode(
                Collections.singletonList(mapping("battery", CHAR_A, FieldMapping.Format.UINT8)), raw);

        assertEquals(85L, out.get("battery"));
        // 未映射特征按 char UUID 原样上报 hex（§16.4），不参与规则求值。
        assertEquals("0102", out.get("char:" + CHAR_B));
        assertFalse(out.containsKey("temperature"));
    }

    @Test
    public void testDecodeEmpty() {
        assertTrue(Decoder.decode(null, null).isEmpty());
        assertTrue(Decoder.decode(Arrays.asList(mapping("a", CHAR_A, FieldMapping.Format.UINT8)),
                Collections.emptyMap()).isEmpty());
    }
}
