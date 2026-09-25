package com.longcheer.agent.model;

import java.util.UUID;

/**
 * 复杂轮询中的单步动作（SDD §8.6）。
 */
public class PollStep {

    public GattCommand.Type type;
    public UUID serviceUuid;
    public UUID charUuid;
    public byte[] payload;
    public int timeoutMs = 3000;
    public int maxRetry = 1;
    /** WRITE 时是否用 Write No Response（LC 产测等只支持 WRITE_NR 的特征必须置 true）。 */
    public boolean writeNoResponse = false;

    public PollStep() {
    }

    public PollStep(GattCommand.Type type, UUID serviceUuid, UUID charUuid) {
        this.type = type;
        this.serviceUuid = serviceUuid;
        this.charUuid = charUuid;
    }

    public PollStep(GattCommand.Type type, UUID serviceUuid, UUID charUuid,
                    byte[] payload, int timeoutMs, int maxRetry) {
        this.type = type;
        this.serviceUuid = serviceUuid;
        this.charUuid = charUuid;
        this.payload = payload;
        this.timeoutMs = timeoutMs;
        this.maxRetry = maxRetry;
    }
}
