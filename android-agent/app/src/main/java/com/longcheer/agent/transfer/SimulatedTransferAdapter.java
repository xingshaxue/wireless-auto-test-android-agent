package com.longcheer.agent.transfer;

import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.FileTransferTask;

import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟 DUT 的传输适配器（模拟器/CI 用，仅 simulateDut=true 时激活）：
 * 握手即过、分块全收、窗口 ACK 恒 OK。收到的数据可供日志校验。
 */
public class SimulatedTransferAdapter implements TransferAdapter {

    private static final String TAG = "SimTransferAdapter";

    private final List<byte[]> chunks = new ArrayList<>();
    private long handshakenOffset = 0;

    @Override
    public void handshake(FileTransferTask task, DeviceController device) {
        handshakenOffset = task.getTransferredOffset();
        AgentLog.i(TAG, "handshake ok: task=" + task.getTaskId()
                + " size=" + task.getTotalSize() + " resumeOffset=" + handshakenOffset);
    }

    @Override
    public void sendChunk(byte[] chunk, int seq) {
        chunks.add(chunk == null ? new byte[0] : chunk.clone());
    }

    @Override
    public WindowAck waitWindowAck(int windowSeq, long timeoutMs) {
        return WindowAck.OK;
    }

    @Override
    public boolean supportsOffsetWrite() {
        return true;
    }

    /** 测试/日志观测：累计收到的字节数。 */
    public long receivedBytes() {
        long total = 0;
        for (byte[] c : chunks) {
            total += c.length;
        }
        return total;
    }
}
