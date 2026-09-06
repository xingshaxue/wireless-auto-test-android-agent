package com.longcheer.agent.log;

import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Agent 统一日志入口（SDD §13）。
 *
 * <p>双通道：镜像到 logcat + 滚动文件落盘。DEBUG 级（GATT 原始交互等）由单独开关控制，
 * 默认关闭。未初始化时仅镜像 logcat，保证单元测试与早期启动阶段可用。</p>
 */
public final class AgentLog {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10MB
    private static final int MAX_FILES = 5;
    private static final SimpleDateFormat TS_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile RollingLogWriter writer;
    private static volatile boolean debugEnabled = false;

    private AgentLog() {
    }

    /**
     * 初始化落盘通道（AgentService.onCreate 调用）。
     *
     * @param logDir       日志目录（filesDir/logs）
     * @param enableDebug  是否开启 DEBUG 级（GATT 原始交互排障开关）
     */
    public static synchronized void init(File logDir, boolean enableDebug) {
        debugEnabled = enableDebug;
        if (writer != null) {
            writer.close();
        }
        writer = new RollingLogWriter(logDir, "agent.log", MAX_FILE_SIZE, MAX_FILES);
    }

    public static synchronized void shutdown() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void d(String tag, String msg) {
        if (!debugEnabled) {
            return;
        }
        log('D', tag, msg, null);
    }

    public static void i(String tag, String msg) {
        log('I', tag, msg, null);
    }

    public static void w(String tag, String msg) {
        log('W', tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable tr) {
        log('W', tag, msg, tr);
    }

    public static void e(String tag, String msg) {
        log('E', tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        log('E', tag, msg, tr);
    }

    private static void log(char level, String tag, String msg, Throwable tr) {
        switch (level) {
            case 'D': Log.d(tag, msg); break;
            case 'I': Log.i(tag, msg); break;
            case 'W': Log.w(tag, msg, tr); break;
            case 'E': Log.e(tag, msg, tr); break;
            default: break;
        }
        RollingLogWriter w = writer;
        if (w == null) {
            return;
        }
        StringBuilder sb = new StringBuilder()
                .append(TS_FORMAT.format(new Date()))
                .append(' ').append(level).append('/').append(tag).append(": ")
                .append(msg);
        if (tr != null) {
            sb.append('\n').append(stackTrace(tr));
        }
        w.write(sb.toString());
    }

    static String stackTrace(Throwable tr) {
        StringWriter sw = new StringWriter();
        tr.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
