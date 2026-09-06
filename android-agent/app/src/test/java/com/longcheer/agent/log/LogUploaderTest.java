package com.longcheer.agent.log;

import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.tcp.FileFrameCodec;
import com.longcheer.agent.tcp.TcpClient;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * LogUploader 单测（SDD §13）：过滤打包 → LOG_FRAME 上传 → LOG_UPLOAD_DONE。
 */
public class LogUploaderTest {

    private File logDir;
    private TcpClient tcpClient;
    private StateReporter reporter;
    private LogUploader uploader;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static String line(long ts, char level, String tag, String msg) {
        return TS.format(new Date(ts)) + " " + level + "/" + tag + ": " + msg;
    }

    @Before
    public void setUp() throws Exception {
        logDir = Files.createTempDirectory("logs").toFile();
        tcpClient = mock(TcpClient.class);
        reporter = mock(StateReporter.class);
        uploader = new LogUploader(logDir, tcpClient, reporter);
    }

    private List<FileFrameCodec.Frame> capturedFrames() {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(tcpClient, org.mockito.Mockito.atLeast(0)).sendFrame(captor.capture());
        List<FileFrameCodec.Frame> frames = new ArrayList<>();
        for (byte[] raw : captor.getAllValues()) {
            frames.add(FileFrameCodec.decode(raw)); // CRC 校验应通过
        }
        return frames;
    }

    private static String payloadText(List<FileFrameCodec.Frame> frames) {
        StringBuilder sb = new StringBuilder();
        for (FileFrameCodec.Frame f : frames) {
            sb.append(new String(f.payload, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    @Test
    public void uploadsAllLinesAsLogFrames() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(line(1000, 'I', "T", "hello"));
        lines.add(line(2000, 'W', "T", "warn"));
        Files.write(new File(logDir, "agent.log").toPath(), lines, StandardCharsets.UTF_8);

        uploader.upload("req-1", 0, "DEBUG");

        List<FileFrameCodec.Frame> frames = capturedFrames();
        assertFalse(frames.isEmpty());
        for (FileFrameCodec.Frame f : frames) {
            assertEquals(FileFrameCodec.FrameType.LOG_FRAME, f.type);
        }
        String text = payloadText(frames);
        assertTrue(text.contains("hello"));
        assertTrue(text.contains("warn"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(eq("LOG_UPLOAD_DONE"), captor.capture());
        assertEquals("req-1", captor.getValue().get("requestId"));
        assertEquals(0, captor.getValue().get("errorCode"));
        assertTrue(((Long) captor.getValue().get("size")) > 0);
    }

    @Test
    public void minLevelFiltersLowerLevels() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(line(1000, 'I', "T", "info-line"));
        lines.add(line(2000, 'E', "T", "error-line"));
        Files.write(new File(logDir, "agent.log").toPath(), lines, StandardCharsets.UTF_8);

        uploader.upload("req-2", 0, "WARN");

        String text = payloadText(capturedFrames());
        assertFalse(text.contains("info-line"));
        assertTrue(text.contains("error-line"));
    }

    @Test
    public void sinceTsFiltersOlderLines() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(line(1000, 'I', "T", "old-line"));
        lines.add(line(5000, 'I', "T", "new-line"));
        Files.write(new File(logDir, "agent.log").toPath(), lines, StandardCharsets.UTF_8);

        uploader.upload("req-3", 3000, "DEBUG");

        String text = payloadText(capturedFrames());
        assertFalse(text.contains("old-line"));
        assertTrue(text.contains("new-line"));
    }

    @Test
    public void crashAndRotatedFilesIncluded() throws Exception {
        Files.write(new File(logDir, "crash-20260906-120000.log").toPath(),
                java.util.Collections.singletonList("thread: main\njava.lang.IllegalStateException"),
                StandardCharsets.UTF_8);
        Files.write(new File(logDir, "agent.log.1").toPath(),
                java.util.Collections.singletonList(line(500, 'I', "T", "rotated-line")),
                StandardCharsets.UTF_8);
        Files.write(new File(logDir, "agent.log").toPath(),
                java.util.Collections.singletonList(line(1000, 'I', "T", "current-line")),
                StandardCharsets.UTF_8);

        uploader.upload("req-4", 0, "DEBUG");

        String text = payloadText(capturedFrames());
        assertTrue(text.contains("IllegalStateException"));
        assertTrue(text.contains("rotated-line"));
        assertTrue(text.contains("current-line"));
    }
}
