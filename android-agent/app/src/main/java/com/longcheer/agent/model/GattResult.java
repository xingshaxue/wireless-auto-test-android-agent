package com.longcheer.agent.model;

import java.util.Arrays;

/**
 * 单步 GATT 操作结果（SDD §12.2）：成功时携带读取值，失败时透传原始 status（§12.9 rawStatus）。
 */
public final class GattResult {

    private final boolean success;
    private final byte[] value;
    private final int status;

    private GattResult(boolean success, byte[] value, int status) {
        this.success = success;
        this.value = value;
        this.status = status;
    }

    public static GattResult ok(byte[] value) {
        return new GattResult(true, value, 0);
    }

    public static GattResult fail(int status) {
        return new GattResult(false, null, status);
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

    @Override
    public String toString() {
        return "GattResult{success=" + success + ", status=" + status
                + ", value=" + (value == null ? "null" : Arrays.toString(value)) + '}';
    }
}
