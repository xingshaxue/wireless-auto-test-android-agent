package com.longcheer.agent.config;

import org.json.JSONObject;

/**
 * Decoder 字段映射配置（config 包），对应 SDD §16.4 {@code devices[].fields[]}。
 *
 * <p>运行时等价结构见 {@link com.longcheer.agent.model.FieldMapping}。</p>
 */
public final class FieldMappingConfig {

    private final String field;
    private final String charUuid;
    private final String format;
    private final String byteOrder;
    private final double scale;
    private final int byteOffset;

    public FieldMappingConfig(String field,
                              String charUuid,
                              String format,
                              String byteOrder,
                              double scale,
                              int byteOffset) {
        this.field = field;
        this.charUuid = charUuid;
        this.format = format;
        this.byteOrder = byteOrder;
        this.scale = scale;
        this.byteOffset = byteOffset;
    }

    public static FieldMappingConfig fromJson(String fieldName, JSONObject json) {
        if (json == null) {
            json = new JSONObject();
        }
        return new FieldMappingConfig(
                fieldName,
                json.optString("char", null),
                json.optString("format", "hex"),
                json.optString("byteOrder", "LE"),
                json.optDouble("scale", 1.0),
                json.optInt("byteOffset", 0)
        );
    }

    public String getField() {
        return field;
    }

    public String getCharUuid() {
        return charUuid;
    }

    public String getFormat() {
        return format;
    }

    public String getByteOrder() {
        return byteOrder;
    }

    public double getScale() {
        return scale;
    }

    public int getByteOffset() {
        return byteOffset;
    }
}
