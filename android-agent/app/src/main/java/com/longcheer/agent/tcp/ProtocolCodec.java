package com.longcheer.agent.tcp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TCP 流编解码器（SDD §16.3）。
 *
 * <p>职责：
 * <ul>
 *   <li>将 JSON 报文编码为“4 字节大端长度前缀 + UTF-8 JSON”。</li>
 *   <li>从字节流中切分出完整 JSON 报文或二进制帧；未完整数据保留在内部 buffer。</li>
 *   <li>流解复用：前 2 字节为 {@code 0xAC42} 时按二进制帧解析，否则按 4 字节长度前缀读 JSON。</li>
 * </ul>
 */
public class ProtocolCodec {

    /** 单条 JSON 报文最大字节数（不含 4 字节长度前缀）。 */
    public static final int MAX_PAYLOAD_SIZE = 1024 * 1024;

    /** 二进制帧魔数（大端 0xAC42）。 */
    public static final int BINARY_MAGIC = 0xAC42;

    /** 二进制帧固定头部长度：magic(2) + type(1) + seq(4) + length(4)。 */
    public static final int BINARY_HEADER_SIZE = 11;

    /** CRC16 长度。 */
    public static final int BINARY_CRC_SIZE = 2;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /**
     * 编码 JSON 报文。
     *
     * @param msg JSON 报文体
     * @return 带 4 字节大端长度前缀的字节数组
     * @throws IllegalArgumentException 单条报文超过 {@link #MAX_PAYLOAD_SIZE}
     */
    public byte[] encodeJson(Map<String, Object> msg) {
        if (msg == null) {
            throw new IllegalArgumentException("msg is null");
        }
        String json = JsonCodec.encode(msg);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException(
                    "JSON payload too large: " + payload.length + " > " + MAX_PAYLOAD_SIZE);
        }
        byte[] frame = new byte[4 + payload.length];
        writeInt32BigEndian(frame, 0, payload.length);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        return frame;
    }

    /**
     * 向内部缓存追加字节流并尝试切分出完整消息。
     *
     * @param data 新收到的字节，可为 {@code null}（仅尝试消费已有缓存）
     * @return 本次切分出的完整消息列表；未完整数据保留在内部 buffer
     */
    public synchronized List<DecodedMessage> decodeStream(byte[] data) {
        if (data != null && data.length > 0) {
            buffer.write(data, 0, data.length);
        }

        List<DecodedMessage> result = new ArrayList<>();
        byte[] buf = buffer.toByteArray();
        int pos = 0;
        int remaining;

        while ((remaining = buf.length - pos) > 0) {
            if (remaining < 2) {
                break;
            }
            int b0 = buf[pos] & 0xFF;
            int b1 = buf[pos + 1] & 0xFF;
            int magic = (b0 << 8) | b1;

            if (magic == BINARY_MAGIC) {
                // 二进制帧：magic(2) + type(1) + seq(4) + length(4) + payload + crc(2)
                if (remaining < BINARY_HEADER_SIZE) {
                    break;
                }
                int payloadLength = readInt32BigEndian(buf, pos + 7);
                if (payloadLength < 0) {
                    throw new IllegalStateException("Invalid binary frame length: " + payloadLength);
                }
                int totalFrameSize = BINARY_HEADER_SIZE + payloadLength + BINARY_CRC_SIZE;
                if (remaining < totalFrameSize) {
                    break;
                }
                byte[] frame = Arrays.copyOfRange(buf, pos, pos + totalFrameSize);
                result.add(new DecodedMessage(frame));
                pos += totalFrameSize;
            } else {
                // JSON 报文：4 字节大端长度前缀 + UTF-8 JSON
                if (remaining < 4) {
                    break;
                }
                int jsonLength = readInt32BigEndian(buf, pos);
                if (jsonLength < 0 || jsonLength > MAX_PAYLOAD_SIZE) {
                    throw new IllegalStateException("Invalid JSON length: " + jsonLength);
                }
                if (remaining < 4 + jsonLength) {
                    break;
                }
                byte[] jsonBytes = Arrays.copyOfRange(buf, pos + 4, pos + 4 + jsonLength);
                Map<String, Object> json = JsonCodec.decodeObject(jsonBytes);
                result.add(new DecodedMessage(json));
                pos += 4 + jsonLength;
            }
        }

        if (pos > 0) {
            buffer.reset();
            if (pos < buf.length) {
                buffer.write(buf, pos, buf.length - pos);
            }
        }
        return result;
    }

    private static void writeInt32BigEndian(byte[] dst, int offset, int value) {
        dst[offset] = (byte) ((value >>> 24) & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        dst[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 3] = (byte) (value & 0xFF);
    }

    private static int readInt32BigEndian(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24)
                | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8)
                | (src[offset + 3] & 0xFF);
    }

    /**
     * 解码后的消息。
     */
    public static final class DecodedMessage {

        public enum Type {
            JSON, BINARY
        }

        private final Type type;
        private final Map<String, Object> json;
        private final byte[] frame;

        private DecodedMessage(Map<String, Object> json) {
            this.type = Type.JSON;
            this.json = json;
            this.frame = null;
        }

        private DecodedMessage(byte[] frame) {
            this.type = Type.BINARY;
            this.json = null;
            this.frame = frame;
        }

        public Type getType() {
            return type;
        }

        public boolean isJson() {
            return type == Type.JSON;
        }

        public boolean isBinary() {
            return type == Type.BINARY;
        }

        /**
         * JSON 报文内容，仅当 {@link #isJson()} 为 true 时有效。
         */
        public Map<String, Object> getJson() {
            if (type != Type.JSON) {
                throw new IllegalStateException("Not a JSON message");
            }
            return json;
        }

        /**
         * 二进制帧原始字节（含帧头与 CRC），仅当 {@link #isBinary()} 为 true 时有效。
         */
        public byte[] getFrame() {
            if (type != Type.BINARY) {
                throw new IllegalStateException("Not a binary frame");
            }
            return frame;
        }
    }

    /**
     * 最小化 JSON 编解码器。
     *
     * <p>不依赖 org.json 等外部库，满足 tcp 包对 Map&lt;String,Object&gt; 的编解码需求。</p>
     */
    private static final class JsonCodec {

        static String encode(Map<String, Object> map) {
            StringBuilder sb = new StringBuilder();
            encodeObject(map, sb);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void encodeValue(Object value, StringBuilder sb) {
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Boolean) {
                sb.append(value.toString());
            } else if (value instanceof Number) {
                sb.append(value.toString());
            } else if (value instanceof String) {
                encodeString((String) value, sb);
            } else if (value instanceof Map) {
                encodeObject((Map<String, Object>) value, sb);
            } else if (value instanceof List) {
                encodeArray((List<?>) value, sb);
            } else if (value instanceof Object[]) {
                encodeArray(Arrays.asList((Object[]) value), sb);
            } else {
                // 未知类型按字符串兜底
                encodeString(value.toString(), sb);
            }
        }

        private static void encodeObject(Map<String, Object> map, StringBuilder sb) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                encodeString(entry.getKey(), sb);
                sb.append(':');
                encodeValue(entry.getValue(), sb);
            }
            sb.append('}');
        }

        private static void encodeArray(List<?> list, StringBuilder sb) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                encodeValue(item, sb);
            }
            sb.append(']');
        }

        private static void encodeString(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    default:
                        if (c < 0x20 || (c >= 0x7F && c < 0xA0)) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }

        static Map<String, Object> decodeObject(byte[] bytes) {
            String s = new String(bytes, StandardCharsets.UTF_8);
            Parser parser = new Parser(s);
            Object value = parser.parseValue();
            if (!(value instanceof Map)) {
                throw new IllegalStateException("Top-level JSON value is not an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return map;
        }

        private static final class Parser {
            private final String s;
            private int pos;

            Parser(String s) {
                this.s = s;
                this.pos = 0;
            }

            private Object parseValue() {
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new IllegalStateException("Unexpected end of JSON");
                }
                char c = s.charAt(pos);
                switch (c) {
                    case '{':
                        return parseObject();
                    case '[':
                        return parseArray();
                    case '"':
                        return parseString();
                    case 't':
                    case 'f':
                        return parseBoolean();
                    case 'n':
                        return parseNull();
                    default:
                        return parseNumber();
                }
            }

            private Map<String, Object> parseObject() {
                expect('{');
                skipWhitespace();
                Map<String, Object> map = new LinkedHashMap<>();
                if (peek() == '}') {
                    pos++;
                    return map;
                }
                while (true) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    Object value = parseValue();
                    map.put(key, value);
                    skipWhitespace();
                    char c = peek();
                    if (c == ',') {
                        pos++;
                        continue;
                    } else if (c == '}') {
                        pos++;
                        return map;
                    } else {
                        throw new IllegalStateException("Expected ',' or '}' at position " + pos);
                    }
                }
            }

            private List<Object> parseArray() {
                expect('[');
                skipWhitespace();
                List<Object> list = new ArrayList<>();
                if (peek() == ']') {
                    pos++;
                    return list;
                }
                while (true) {
                    Object value = parseValue();
                    list.add(value);
                    skipWhitespace();
                    char c = peek();
                    if (c == ',') {
                        pos++;
                        continue;
                    } else if (c == ']') {
                        pos++;
                        return list;
                    } else {
                        throw new IllegalStateException("Expected ',' or ']' at position " + pos);
                    }
                }
            }

            private String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (pos < s.length()) {
                    char c = s.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        if (pos >= s.length()) {
                            throw new IllegalStateException("Invalid escape at end of string");
                        }
                        char esc = s.charAt(pos++);
                        switch (esc) {
                            case '"':
                            case '\\':
                            case '/':
                                sb.append(esc);
                                break;
                            case 'b':
                                sb.append('\b');
                                break;
                            case 'f':
                                sb.append('\f');
                                break;
                            case 'n':
                                sb.append('\n');
                                break;
                            case 'r':
                                sb.append('\r');
                                break;
                            case 't':
                                sb.append('\t');
                                break;
                            case 'u':
                                if (pos + 4 > s.length()) {
                                    throw new IllegalStateException("Invalid unicode escape");
                                }
                                String hex = s.substring(pos, pos + 4);
                                try {
                                    int code = Integer.parseInt(hex, 16);
                                    sb.append((char) code);
                                } catch (NumberFormatException e) {
                                    throw new IllegalStateException("Invalid unicode escape: " + hex);
                                }
                                pos += 4;
                                break;
                            default:
                                throw new IllegalStateException("Invalid escape character: \\" + esc);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new IllegalStateException("Unterminated string");
            }

            private Boolean parseBoolean() {
                if (s.startsWith("true", pos)) {
                    pos += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", pos)) {
                    pos += 5;
                    return Boolean.FALSE;
                }
                throw new IllegalStateException("Invalid boolean literal at position " + pos);
            }

            private Object parseNull() {
                if (s.startsWith("null", pos)) {
                    pos += 4;
                    return null;
                }
                throw new IllegalStateException("Invalid null literal at position " + pos);
            }

            private Number parseNumber() {
                int start = pos;
                if (peek() == '-') {
                    pos++;
                }
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                boolean isFloating = false;
                if (pos < s.length() && s.charAt(pos) == '.') {
                    isFloating = true;
                    pos++;
                    while (pos < s.length() && isDigit(s.charAt(pos))) {
                        pos++;
                    }
                }
                if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                    isFloating = true;
                    pos++;
                    if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                        pos++;
                    }
                    while (pos < s.length() && isDigit(s.charAt(pos))) {
                        pos++;
                    }
                }
                String num = s.substring(start, pos);
                if (isFloating) {
                    return Double.parseDouble(num);
                }
                try {
                    return Integer.parseInt(num);
                } catch (NumberFormatException e) {
                    return Long.parseLong(num);
                }
            }

            private void expect(char expected) {
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != expected) {
                    throw new IllegalStateException(
                            "Expected '" + expected + "' at position " + pos);
                }
                pos++;
            }

            private char peek() {
                skipWhitespace();
                return pos < s.length() ? s.charAt(pos) : '\0';
            }

            private void skipWhitespace() {
                while (pos < s.length()) {
                    char c = s.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            private static boolean isDigit(char c) {
                return c >= '0' && c <= '9';
            }
        }
    }
}
