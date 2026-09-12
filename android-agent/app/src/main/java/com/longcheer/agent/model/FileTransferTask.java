package com.longcheer.agent.model;

/**
 * 文件传输任务状态。M1 阶段仅作数据结构占位。
 */
public class FileTransferTask {

    public enum FileTransferState {
        PENDING, HANDSHAKE, TRANSFERRING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    private final String taskId;
    private final String deviceMac;
    private final String fileId;
    private final long totalSize;
    private long transferredOffset;
    // 传输协议适配器可在握手时改写（LC 通道 B.3：4480B 块、窗口恒 1）。
    private int chunkSize;
    private int windowSize;
    private FileTransferState state;
    private final long startTime;
    private int retryCount;

    public FileTransferTask(String taskId, String deviceMac, String fileId,
                            long totalSize, int chunkSize, int windowSize) {
        this.taskId = taskId;
        this.deviceMac = deviceMac;
        this.fileId = fileId;
        this.totalSize = totalSize;
        this.chunkSize = chunkSize;
        this.windowSize = windowSize;
        this.state = FileTransferState.PENDING;
        this.startTime = android.os.SystemClock.elapsedRealtime();
        this.retryCount = 0;
    }

    public String getTaskId() { return taskId; }
    public String getDeviceMac() { return deviceMac; }
    public String getFileId() { return fileId; }
    public long getTotalSize() { return totalSize; }
    public long getTransferredOffset() { return transferredOffset; }
    public void setTransferredOffset(long transferredOffset) { this.transferredOffset = transferredOffset; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getWindowSize() { return windowSize; }
    public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
    public FileTransferState getState() { return state; }
    public void setState(FileTransferState state) { this.state = state; }
    public long getStartTime() { return startTime; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
}
