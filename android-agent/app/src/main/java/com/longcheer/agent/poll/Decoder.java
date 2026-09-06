package com.longcheer.agent.poll;

import com.longcheer.agent.model.FieldMapping;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decoder（SDD §7.3.2 / §8.9）：按 FieldMapping 把特征原始 bytes 解析为字段值。
 *
 * <p>纯函数实现，轮询与通知处理链共用。未配置映射的特征按 char UUID 原样上报
 * 小写 hex 字符串（§16.4），不参与规则求值。</p>
 */
public final class Decoder {

    private Decoder() {
    }

    /**
     * 解析一轮原始特征值。
     *
     * @param mappings 设备的字段映射（可空）
     * @param raw      char UUID → 原始 bytes
     * @return 字段名 → 解析值；未映射特征的键为 "char:<uuid>"、值为 hex
     */
    public static Map<String, Object> decode(List<FieldMapping> mappings, Map<UUID, byte[]> raw) {
        Map<String, Object> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        Map<UUID, FieldMapping> byChar = new HashMap<>();
        if (mappings != null) {
            for (FieldMapping m : mappings) {
                if (m.getCharUuid() != null) {
                    byChar.put(m.getCharUuid(), m);
                }
            }
        }
        for (Map.Entry<UUID, byte[]> e : raw.entrySet()) {
            FieldMapping m = byChar.get(e.getKey());
            if (m == null) {
                out.put("char:" + e.getKey(), toHex(e.getValue()));
            } else {
                Object value = parse(e.getValue(), m);
                if (value != null) {
                    out.put(m.getField(), value);
                }
            }
        }
        return out;
    }

    /**
     * 按映射解析单个特征值；数据不足返回 null（该字段本轮缺失）。
     */
    public static Object parse(byte[] data, FieldMapping m) {
        if (data == null) {
            return null;
        }
        int offset = Math.max(0, m.getByteOffset());
        ByteOrder order = m.getOrder() == null ? ByteOrder.LITTLE_ENDIAN : m.getOrder();
        switch (m.getFormat()) {
            case UINT8:
                if (data.length - offset < 1) return null;
                return scaled(data[offset] & 0xFF, m.getScale());
            case UINT16:
                if (data.length - offset < 2) return null;
                return scaled(ByteBuffer.wrap(data, offset, 2).order(order).getShort() & 0xFFFF, m.getScale());
            case UINT32:
                if (data.length - offset < 4) return null;
                return scaled(ByteBuffer.wrap(data, offset, 4).order(order).getInt() & 0xFFFFFFFFL, m.getScale());
            case SINT8:
                if (data.length - offset < 1) return null;
                return scaled(data[offset], m.getScale());
            case SINT16:
                if (data.length - offset < 2) return null;
                return scaled(ByteBuffer.wrap(data, offset, 2).order(order).getShort(), m.getScale());
            case SINT32:
                if (data.length - offset < 4) return null;
                return scaled(ByteBuffer.wrap(data, offset, 4).order(order).getInt(), m.getScale());
            case UTF8:
                if (offset >= data.length) return null;
                return new String(data, offset, data.length - offset, StandardCharsets.UTF_8);
            case BOOL:
                if (data.length - offset < 1) return null;
                return data[offset] != 0;
            case HEX:
                if (offset >= data.length) return null;
                byte[] slice = new byte[data.length - offset];
                System.arraycopy(data, offset, slice, 0, slice.length);
                return toHex(slice);
            default:
                return null;
        }
    }

    /** 解析值 = 原始值 × scale（§8.9）；scale 为 1 时保持整数类型。 */
    private static Object scaled(long raw, double scale) {
        if (scale == 1.0d) {
            return raw;
        }
        return raw * scale;
    }

    public static String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
