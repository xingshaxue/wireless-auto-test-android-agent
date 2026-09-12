package com.longcheer.agent.model;

import java.util.Arrays;

/**
 * 单步 GATT 操作结果（SDD §12.2）：成功时携带读取值，失败时透传原始 status（§12.9 rawStatus）。
 */
public final class GattResult {

    private final boolean success;
    private final byte[] value;
    private final int status;
    /** §7.4.1：TTL 过期未执行的标记（非 GATT 结果，调用侧回 3002）。 */
    private final boolean expired;

    private GattResult(boolean success, byte[] value, int status, boolean expired) {
        this.success = success;
        this.value = value;
        this.status = status;
        this.expired = expired;
    }

    public static GattResult ok(byte[] value) {
        return new GattResult(true, value, 0, false);
    }

    public static GattResult fail(int status) {
        return new GattResult(false, null, status, false);
    }

    /** 命令 TTL 过期、未上 GATT 执行（§7.4.1 → CMD_ACK 3002）。 */
    public static GattResult expired() {
        return new GattResult(false, null, 0, true);
    }

    public boolean isSuccess() {
        return success;
    }

    public byte[] getValue() {
        return value;
    }

    /** 底层原始 GATT status（透传用，§12.9） */
    public int getStatus() {
        return status;
    }

    /** true = 命令 TTL 过期未执行（§7.4.1），调用侧回 3002 而非 1xxx。 */
    public boolean isExpired() {
        return expired;
    }

    @Override
    public String toString() {
        return "GattResult{success=" + success + ", status=" + status
                + ", value=" + (value == null ? "null" : Arrays.toString(value)) + '}';
    }
}
