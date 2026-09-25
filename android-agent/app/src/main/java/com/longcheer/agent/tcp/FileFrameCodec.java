package com.longcheer.agent.tcp;

import java.util.Arrays;

/**
 * TCP 二进制帧编解码器（SDD §16.3）。
 *
 * <p>帧结构（大端序）：
 * <pre>
 * magic(2B) + type(1B) + seq(4B) + length(4B) + payload + crc16(2B)
 * </pre>
 * <ul>
 *   <li>magic = 0xAC42</li>
 *   <li>crc16 覆盖 type + seq + length + payload</li>
 *   <li>单帧 payload ≤ 64KB</li>
 * </ul>
 */
public class FileFrameCodec {

    /** 帧魔数。 */
    public static final int MAGIC = 0xAC42;

    /** 头部字节数。 */
    public static final int HEADER_SIZE = 11;

    /** CRC16 字节数。 */
    public static final int CRC_SIZE = 2;

    /** 单帧 payload 上限。 */
    public static final int MAX_PAYLOAD_SIZE = 64 * 1024;

    /** CRC16-CCITT 多项式。 */
    private static final int CRC16_POLYNOMIAL = 0x1021;

    /** CRC16 初始值。 */
    private static final int CRC16_INIT = 0xFFFF;

    /**
     * 二进制帧类型。
     */
    public enum FrameType {
        FILE_FRAME(0x01),
        FILE_END(0x02),
        FILE_ACK(0x03),
        LOG_FRAME(0x04),
        EXPORT_FRAME(0x05),
        EXPORT_END(0x06);

        private final int code;

        FrameType(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static FrameType fromCode(int code) {
            for (FrameType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown frame type: 0x" + Integer.toHexString(code));
        }
    }

    /**
     * 解码后的二进制帧。
     */
    public static final class Frame {
        public final FrameType type;
        public final int seq;
        public final byte[] payload;

        public Frame(FrameType type, int seq, byte[] payload) {
            this.type = type;
            this.seq = seq;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return "Frame{type=" + type + ", seq=" + seq + ", payloadLen=" + payload.length + '}';
        }
    }

    /**
     * 编码一帧。
     *
     * @param type    帧类型
     * @param seq     序号（文件帧从 1 起连续编号）
     * @param payload 载荷（可为 {@code null}，按空数组处理）
     * @return 完整帧字节数组
     * @throws IllegalArgumentException payload 超过 {@link #MAX_PAYLOAD_SIZE}
     */
    public static byte[] encode(FrameType type, int seq, byte[] payload) {
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        byte[] data = payload != null ? payload : new byte[0];
        if (data.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException(
                    "Payload too large: " + data.length + " > " + MAX_PAYLOAD_SIZE);
        }

        byte[] frame = new byte[HEADER_SIZE + data.length + CRC_SIZE];
        int offset = 0;
        writeInt16BigEndian(frame, offset, MAGIC);
        offset += 2;
        frame[offset++] = (byte) (type.getCode() & 0xFF);
        writeInt32BigEndian(frame, offset, seq);
        offset += 4;
        writeInt32BigEndian(frame, offset, data.length);
        offset += 4;
        System.arraycopy(data, 0, frame, offset, data.length);
        offset += data.length;

        int crc = crc16(frame, 2, HEADER_SIZE - 2 + data.length);
        writeInt16BigEndian(frame, offset, crc);

        return frame;
    }

    /**
     * 解码完整帧。
     *
     * @param frame 原始帧字节
     * @return 解码后的 {@link Frame}
     * @throws IllegalArgumentException 魔数、长度或 CRC 校验失败
     */
    public static Frame decode(byte[] frame) {
        if (frame == null || frame.length < HEADER_SIZE + CRC_SIZE) {
            throw new IllegalArgumentException("Frame too short");
        }
        int magic = readInt16BigEndian(frame, 0);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                    "Invalid magic: 0x" + Integer.toHexString(magic));
        }

        FrameType type = FrameType.fromCode(frame[2] & 0xFF);
        int seq = readInt32BigEndian(frame, 3);
        int length = readInt32BigEndian(frame, 7);
        if (length < 0) {
            throw new IllegalArgumentException("Invalid payload length: " + length);
        }
        if (frame.length != HEADER_SIZE + length + CRC_SIZE) {
            throw new IllegalArgumentException("Frame length mismatch");
        }

        int expectedCrc = readInt16BigEndian(frame, HEADER_SIZE + length);
        int actualCrc = crc16(frame, 2, HEADER_SIZE - 2 + length);
        if (expectedCrc != actualCrc) {
            throw new IllegalArgumentException(
                    "CRC mismatch: expected=" + expectedCrc + ", actual=" + actualCrc);
        }

        byte[] payload = Arrays.copyOfRange(frame, HEADER_SIZE, HEADER_SIZE + length);
        return new Frame(type, seq, payload);
    }

    /**
     * 计算 CRC16-CCITT（多项式 0x1021，初始值 0xFFFF，结果无反转）。
     */
    public static int crc16(byte[] data, int offset, int length) {
        int crc = CRC16_INIT;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ CRC16_POLYNOMIAL) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static void writeInt16BigEndian(byte[] dst, int offset, int value) {
        dst[offset] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeInt32BigEndian(byte[] dst, int offset, int value) {
        dst[offset] = (byte) ((value >>> 24) & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        dst[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 3] = (byte) (value & 0xFF);
    }

    private static int readInt16BigEndian(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 8) | (src[offset + 1] & 0xFF);
    }

    private static int readInt32BigEndian(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24)
                | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8)
                | (src[offset + 3] & 0xFF);
    }
}
