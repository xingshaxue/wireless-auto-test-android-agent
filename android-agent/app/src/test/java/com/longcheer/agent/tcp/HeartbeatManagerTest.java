package com.longcheer.agent.tcp;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class HeartbeatManagerTest {

    @Test
    public void periodicHeartbeatScheduling() throws Exception {
        TcpClient client = mock(TcpClient.class);
        HeartbeatManager manager = new HeartbeatManager(client, 100L);

        manager.start();
        assertTrue(manager.isRunning());

        // 100ms 周期，350ms 内至少触发 3 次
        Thread.sleep(350L);
        verify(client, atLeast(3)).sendJson(anyMap());

        manager.stop();
        assertFalse(manager.isRunning());
    }

    @Test
    public void stopPreventsFurtherHeartbeats() throws Exception {
        TcpClient client = mock(TcpClient.class);
        HeartbeatManager manager = new HeartbeatManager(client, 100L);

        manager.start();
        Thread.sleep(150L);
        manager.stop();
        reset(client);

        // 停止后再等一段时间，应不再有新的心跳
        Thread.sleep(200L);
        verifyNoMoreInteractions(client);
    }

    @Test
    public void heartbeatPayloadContainsRequiredFields() throws Exception {
        TcpClient client = mock(TcpClient.class);
        HeartbeatManager.DataProvider provider = () -> {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("cpuPercent", 12);
            map.put("memAvailMb", 1024);
            map.put("slotsUsed", 2);
            map.put("slotsTotal", 5);
            map.put("devicesManaged", 20);
            map.put("devicesReady", 3);
            return map;
        };
        HeartbeatManager manager = new HeartbeatManager(client, 100L, provider);

        manager.start();
        // 等待至少一次心跳
        verify(client, timeout(500L).atLeast(1)).sendJson(anyMap());
        manager.stop();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client, atLeast(1)).sendJson(captor.capture());

        Map<String, Object> heartbeat = captor.getValue();
        assertEquals("HEARTBEAT", heartbeat.get("type"));
        assertTrue(heartbeat.containsKey("timestamp"));
        assertEquals(12, heartbeat.get("cpuPercent"));
        assertEquals(1024, heartbeat.get("memAvailMb"));
        assertEquals(2, heartbeat.get("slotsUsed"));
        assertEquals(5, heartbeat.get("slotsTotal"));
        assertEquals(20, heartbeat.get("devicesManaged"));
        assertEquals(3, heartbeat.get("devicesReady"));
    }

    @Test
    public void defaultProviderFillsZeros() throws Exception {
        TcpClient client = mock(TcpClient.class);
        HeartbeatManager manager = new HeartbeatManager(client, 100L);

        manager.start();
        verify(client, timeout(500L).atLeast(1)).sendJson(anyMap());
        manager.stop();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client, atLeast(1)).sendJson(captor.capture());

        Map<String, Object> heartbeat = captor.getValue();
        assertEquals(0, heartbeat.get("slotsUsed"));
        assertEquals(0, heartbeat.get("slotsTotal"));
        assertEquals(0, heartbeat.get("devicesManaged"));
        assertEquals(0, heartbeat.get("devicesReady"));
    }
}
