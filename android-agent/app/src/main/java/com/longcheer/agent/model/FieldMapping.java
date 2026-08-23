package com.longcheer.agent.model;

import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Decoder 字段映射。M1 阶段仅作数据结构占位。
 */
public class FieldMapping {

    public enum Format {
        UINT8, UINT16, UINT32, SINT8, SINT16, SINT32, UTF8, BOOL, HEX
    }

    private final String field;
    private final UUID charUuid;
    private final Format format;
    private final ByteOrder order;
    private final double scale;
    private final int byteOffset;

    public FieldMapping(String field, UUID charUuid, Format format,
                        ByteOrder order, double scale, int byteOffset) {
        this.field = field;
        this.charUuid = charUuid;
        this.format = format;
        this.order = order == null ? ByteOrder.LITTLE_ENDIAN : order;
        this.scale = scale;
        this.byteOffset = byteOffset;
    }

    public String getField() { return field; }
    public UUID getCharUuid() { return charUuid; }
    public Format getFormat() { return format; }
    public ByteOrder getOrder() { return order; }
    public double getScale() { return scale; }
    public int getByteOffset() { return byteOffset; }
}
