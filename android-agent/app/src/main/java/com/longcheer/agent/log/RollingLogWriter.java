package com.longcheer.agent.log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 按大小切分的滚动日志文件写入器（SDD §13：如 10MB × 5 个文件环形覆盖）。
 *
 * <p>当前文件写满 maxFileSizeBytes 后滚动：agent.log → agent.log.1 → … → agent.log.(N-1)，
 * 最老的文件被丢弃。线程安全。</p>
 */
public class RollingLogWriter {

    private final File dir;
    private final String baseName;
    private final long maxFileSizeBytes;
    private final int maxFiles;

    private Writer currentWriter;
    private long currentSize;
    private boolean ioFailed = false;

    public RollingLogWriter(File dir, String baseName, long maxFileSizeBytes, int maxFiles) {
        if (maxFileSizeBytes <= 0 || maxFiles < 1) {
            throw new IllegalArgumentException("maxFileSizeBytes must be > 0 and maxFiles >= 1");
        }
        this.dir = dir;
        this.baseName = baseName;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxFiles = maxFiles;
    }

    /**
     * 追加一行日志（自动换行）。IO 失败时静默降级（日志系统不能再搞挂业务）。
     */
    public synchronized void write(String line) {
        if (ioFailed) {
            return;
        }
        try {
            ensureOpen();
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (currentSize + bytes.length > maxFileSizeBytes && currentSize > 0) {
                rotate();
            }
            currentWriter.write(line + "\n");
            currentWriter.flush();
            currentSize += bytes.length;
        } catch (IOException e) {
            ioFailed = true;
            closeQuietly();
        }
    }

    public synchronized void close() {
        closeQuietly();
    }

    /** @return 当前是否处于 IO 失败降级状态 */
    public synchronized boolean isIoFailed() {
        return ioFailed;
    }

    private void ensureOpen() throws IOException {
        if (currentWriter != null) {
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create log dir: " + dir);
        }
        File file = new File(dir, baseName);
        currentSize = file.length();
        currentWriter = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
    }

    /** 滚动：删掉最老文件，其余序号 +1，开启新当前文件。 */
    private void rotate() throws IOException {
        closeQuietly();
        File oldest = new File(dir, baseName + "." + (maxFiles - 1));
        if (maxFiles > 1 && oldest.exists() && !oldest.delete()) {
            throw new IOException("cannot delete oldest log: " + oldest);
        }
        for (int i = maxFiles - 2; i >= 1; i--) {
            File from = new File(dir, baseName + "." + i);
            if (from.exists() && !from.renameTo(new File(dir, baseName + "." + (i + 1)))) {
                throw new IOException("cannot rotate log: " + from);
            }
        }
        if (maxFiles > 1) {
            File current = new File(dir, baseName);
            if (current.exists() && !current.renameTo(new File(dir, baseName + ".1"))) {
                throw new IOException("cannot rotate current log");
            }
        } else {
            File current = new File(dir, baseName);
            if (current.exists() && !current.delete()) {
                throw new IOException("cannot truncate current log");
            }
        }
        currentWriter = null;
        currentSize = 0;
        ensureOpen();
    }

    private void closeQuietly() {
        if (currentWriter != null) {
            try {
                currentWriter.close();
            } catch (IOException ignored) {
                // 关闭失败无需上报
            }
            currentWriter = null;
        }
    }
}
