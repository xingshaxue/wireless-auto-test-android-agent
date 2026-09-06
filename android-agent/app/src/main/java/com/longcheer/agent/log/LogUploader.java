package com.longcheer.agent.log;

import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.tcp.FileFrameCodec;
import com.longcheer.agent.tcp.TcpClient;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 日志打包上传（SDD §13）：UPLOAD_LOG → 过滤打包 → 经 TCP 二进制帧（LOG_FRAME，
 * 复用 §16.3 帧格式）上传 → 上报 LOG_UPLOAD_DONE。
 */
public class LogUploader {

    private static final String TAG = "LogUploader";

    /** 单帧 payload 上限（§16.3 为 64KB，留余量取 60KB）。 */
    static final int FRAME_PAYLOAD_MAX = 60 * 1024;

    private static final SimpleDateFormat TS_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private final File logDir;
    private final TcpClient tcpClient;
    private final StateReporter stateReporter;

    public LogUploader(File logDir, TcpClient tcpClient, StateReporter stateReporter) {
        this.logDir = logDir;
        this.tcpClient = tcpClient;
        this.stateReporter = stateReporter;
    }

    /**
     * 执行一次上传。同步执行（调用方需在工作线程调用）。
     *
     * @param requestId UPLOAD_LOG 命令的 requestId（LOG_UPLOAD_DONE 对账）
     * @param sinceTs   只打包该墙钟时间之后的日志；0 = 全部
     * @param minLevel  最低级别：DEBUG/INFO/WARN/ERROR，默认 DEBUG 全量
     */
    public void upload(String requestId, long sinceTs, String minLevel) {
        long totalBytes = 0;
        int errorCode = 0;
        try {
            List<String> lines = collectLines(sinceTs, minLevel);
            totalBytes = sendFrames(lines);
        } catch (Exception e) {
            AgentLog.e(TAG, "log upload failed: " + e.getMessage());
            errorCode = 2001;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", requestId);
        payload.put("errorCode", errorCode);
        payload.put("size", totalBytes);
        stateReporter.report("LOG_UPLOAD_DONE", payload);
    }

    /** 收集过滤后的日志行（包可见供单测）。从旧到新：轮转文件序号倒序，最后当前文件。 */
    List<String> collectLines(long sinceTs, String minLevel) throws Exception {
        List<File> files = logFiles();
        List<String> out = new ArrayList<>();
        int minLevelRank = levelRank(minLevel);
        for (File file : files) {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.length() < 26) {
                    out.add(line); // 非标准行（如堆栈）原样保留
                    continue;
                }
                long ts = parseTs(line);
                if (sinceTs > 0 && ts > 0 && ts < sinceTs) {
                    continue;
                }
                char level = line.charAt(24); // "yyyy-MM-dd HH:mm:ss.SSS L/TAG: ..."
                if (levelRank(String.valueOf(level)) < minLevelRank) {
                    continue;
                }
                out.add(line);
            }
        }
        return out;
    }

    /** 打包成 LOG_FRAME 帧发送；返回实际上传字节数（帧总长）。 */
    long sendFrames(List<String> lines) {
        StringBuilder buffer = new StringBuilder();
        long totalBytes = 0;
        int seq = 1;
        for (String line : lines) {
            if (buffer.length() + line.length() + 1 > FRAME_PAYLOAD_MAX && buffer.length() > 0) {
                totalBytes += sendFrame(seq++, buffer.toString());
                buffer.setLength(0);
            }
            buffer.append(line).append('\n');
        }
        if (buffer.length() > 0 || seq == 1) {
            totalBytes += sendFrame(seq, buffer.toString());
        }
        return totalBytes;
    }

    private long sendFrame(int seq, String payload) {
        byte[] frame = FileFrameCodec.encode(FileFrameCodec.FrameType.LOG_FRAME, seq,
                payload.getBytes(StandardCharsets.UTF_8));
        tcpClient.sendFrame(frame);
        return frame.length;
    }

    /** 日志文件列表：crash-*.log 在前（时间序），随后 agent.log.N（最老）→ agent.log（最新）。 */
    private List<File> logFiles() {
        List<File> out = new ArrayList<>();
        File[] all = logDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith("crash-") || name.startsWith("agent.log");
            }
        });
        if (all == null) {
            return out;
        }
        Arrays.sort(all, Comparator.comparing(File::getName));
        for (File f : all) {
            if (f.getName().startsWith("crash-")) {
                out.add(f);
            }
        }
        for (int i = 4; i >= 1; i--) {
            File rotated = new File(logDir, "agent.log." + i);
            if (rotated.exists()) {
                out.add(rotated);
            }
        }
        File current = new File(logDir, "agent.log");
        if (current.exists()) {
            out.add(current);
        }
        return out;
    }

    private static long parseTs(String line) {
        try {
            // "yyyy-MM-dd HH:mm:ss.SSS" 共 23 字符。
            Date parsed = TS_FORMAT.parse(line.substring(0, 23));
            return parsed == null ? -1 : parsed.getTime();
        } catch (ParseException | StringIndexOutOfBoundsException e) {
            return -1;
        }
    }

    private static int levelRank(String level) {
        if (level == null) {
            return 0;
        }
        switch (level.toUpperCase(Locale.US)) {
            case "D":
            case "DEBUG":
                return 0;
            case "I":
            case "INFO":
                return 1;
            case "W":
            case "WARN":
                return 2;
            case "E":
            case "ERROR":
                return 3;
            default:
                return 0;
        }
    }
}
