package com.longcheer.agent.log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 未捕获异常处理器（SDD §13）：崩溃写独立文件（crash-时间戳.log），
 * 随后交给原默认处理器（保持系统崩溃上报链路不变）。下次启动随注册上报属 M5。
 */
public class CrashLogHandler implements Thread.UncaughtExceptionHandler {

    private static final SimpleDateFormat NAME_FORMAT =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private final File dir;
    private final Thread.UncaughtExceptionHandler previous;

    public CrashLogHandler(File dir, Thread.UncaughtExceptionHandler previous) {
        this.dir = dir;
        this.previous = previous;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            writeCrash(thread, throwable);
        } catch (IOException ignored) {
            // 崩溃日志写失败不能再抛
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
        }
    }

    private void writeCrash(Thread thread, Throwable throwable) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create crash dir: " + dir);
        }
        File file = new File(dir, "crash-" + NAME_FORMAT.format(new Date()) + ".log");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("thread: " + thread.getName());
        throwable.printStackTrace(pw);
        pw.flush();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(sw.toString());
        }
    }
}
