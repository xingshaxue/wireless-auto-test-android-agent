package com.longcheer.agent.report;

import com.longcheer.agent.tcp.TcpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StateReporterTest {

    private TcpClient tcpClient;
    private StateReporterImpl reporter;

    @Before
    public void setUp() {
        tcpClient = mock(TcpClient.class);
        reporter = new StateReporterImpl(tcpClient, 5);
    }

    @After
    public void tearDown() {
        reporter.shutdown();
    }

    @Test
    public void testReportSendsWhenConnected() throws InterruptedException {
        when(tcpClient.isConnected()).thenReturn(true);

        reporter.report("DEVICE_STATE", singlePayload("deviceMac", "AA:BB:CC:DD:EE:FF"));
        Thread.sleep(100);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tcpClient).sendJson(captor.capture());
        assertEquals("DEVICE_STATE", captor.getValue().get("type"));
        assertEquals("AA:BB:CC:DD:EE:FF", captor.getValue().get("deviceMac"));
    }

    @Test
    public void testBuffersWhenDisconnected() throws InterruptedException {
        when(tcpClient.isConnected()).thenReturn(false);

        reporter.report("POLL_RESULT", singlePayload("deviceMac", "AA:BB:CC:DD:EE:FF"));
        Thread.sleep(100);

        verify(tcpClient, never()).sendJson(any());
        assertEquals(1, reporter.bufferedCount());
    }

    @Test
    public void testBufferMaxDropsOldest() throws InterruptedException {
        when(tcpClient.isConnected()).thenReturn(false);

        for (int i = 0; i < 7; i++) {
            reporter.report("HEARTBEAT", singlePayload("seq", i));
        }
        Thread.sleep(100);

        assertEquals(5, reporter.bufferedCount());
    }

    @Test
    public void testFlushDrainsBuffer() throws InterruptedException {
        when(tcpClient.isConnected()).thenReturn(false);
        reporter.report("A", singlePayload("k", "v1"));
        reporter.report("B", singlePayload("k", "v2"));
        Thread.sleep(50);
        assertEquals(2, reporter.bufferedCount());

        when(tcpClient.isConnected()).thenReturn(true);
        reporter.flush();
        Thread.sleep(100);

        verify(tcpClient, times(2)).sendJson(any());
        assertEquals(0, reporter.bufferedCount());
    }

    @Test
    public void testReportCommandAck() throws InterruptedException {
        when(tcpClient.isConnected()).thenReturn(true);

        reporter.reportCommandAck("req-1", 0, "ok");
        Thread.sleep(100);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tcpClient).sendJson(captor.capture());
        assertEquals("CMD_ACK", captor.getValue().get("type"));
        assertEquals("req-1", captor.getValue().get("requestId"));
        assertEquals(0, captor.getValue().get("errorCode"));
        assertEquals("ok", captor.getValue().get("result"));
    }

    private Map<String, Object> singlePayload(String key, Object value) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put(key, value);
        return map;
    }
}
