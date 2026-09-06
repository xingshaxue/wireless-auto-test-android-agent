package com.longcheer.agent.log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RollingLogWriterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void writesToCurrentFile() throws Exception {
        File dir = folder.newFolder("logs");
        RollingLogWriter writer = new RollingLogWriter(dir, "agent.log", 1024, 3);
        writer.write("line-1");
        writer.write("line-2");
        writer.close();

        File current = new File(dir, "agent.log");
        assertTrue(current.exists());
        String content = new String(java.nio.file.Files.readAllBytes(current.toPath()));
        assertTrue(content.contains("line-1"));
        assertTrue(content.contains("line-2"));
        assertFalse(new File(dir, "agent.log.1").exists());
    }

    @Test
    public void rotatesWhenFull() throws Exception {
        File dir = folder.newFolder("logs");
        // 每行 10 字节，上限 25 字节 → 写第 3 行时滚动。
        RollingLogWriter writer = new RollingLogWriter(dir, "agent.log", 25, 3);
        writer.write("aaaaaaaaa"); // +换行 = 10B
        writer.write("bbbbbbbbb");
        writer.write("ccccccccc"); // 触发滚动
        writer.close();

        assertTrue(new File(dir, "agent.log").exists());
        assertTrue(new File(dir, "agent.log.1").exists());
        String current = new String(java.nio.file.Files.readAllBytes(new File(dir, "agent.log").toPath()));
        String rotated = new String(java.nio.file.Files.readAllBytes(new File(dir, "agent.log.1").toPath()));
        assertTrue(current.contains("ccccccccc"));
        assertTrue(rotated.contains("aaaaaaaaa"));
    }

    @Test
    public void dropsOldestFileBeyondMaxFiles() throws Exception {
        File dir = folder.newFolder("logs");
        RollingLogWriter writer = new RollingLogWriter(dir, "agent.log", 11, 2);
        for (int i = 0; i < 5; i++) {
            writer.write("line-" + i + "xxxxx"); // 每条 > 11B，每条触发一次滚动
        }
        writer.close();

        // maxFiles=2 → 最多 agent.log + agent.log.1
        assertTrue(new File(dir, "agent.log").exists());
        assertTrue(new File(dir, "agent.log.1").exists());
        assertFalse(new File(dir, "agent.log.2").exists());
        String oldest = new String(java.nio.file.Files.readAllBytes(new File(dir, "agent.log.1").toPath()));
        assertTrue(oldest.contains("line-3"));
        assertFalse(oldest.contains("line-2"));
    }

    @Test
    public void crashHandlerWritesFileAndChains() {
        File dir = new File(folder.getRoot(), "crash");
        boolean[] chained = {false};
        CrashLogHandler handler = new CrashLogHandler(dir,
                (t, e) -> chained[0] = true);

        handler.uncaughtException(Thread.currentThread(), new IllegalStateException("boom"));

        File[] files = dir.listFiles();
        assertTrue(files != null && files.length == 1);
        assertTrue(files[0].getName().startsWith("crash-"));
        assertTrue(chained[0]);
    }

    @Test
    public void agentLogWithoutInitDoesNotCrash() {
        AgentLog.setDebugEnabled(false);
        AgentLog.d("T", "debug off, dropped");
        AgentLog.i("T", "info only logcat");
        AgentLog.setDebugEnabled(true);
        AgentLog.d("T", "debug on");
        AgentLog.setDebugEnabled(false);
    }
}
