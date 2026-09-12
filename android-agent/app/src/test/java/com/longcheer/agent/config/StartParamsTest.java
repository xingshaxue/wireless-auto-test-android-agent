package com.longcheer.agent.config;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * StartParams 单测：extras > 本地已存 > 内置默认 的优先级与持久化映射。
 */
public class StartParamsTest {

    @Test
    public void defaultsWhenNothingProvided() {
        StartParams p = StartParams.resolve(null, null);
        assertEquals(StartParams.DEFAULT_HOST, p.serverHost);
        assertEquals(StartParams.DEFAULT_PORT, p.serverPort);
        assertFalse(p.simulateDut);
        assertFalse(p.autoStart);
        assertFalse(p.isConfigured()); // 默认回环不算已配置
    }

    @Test
    public void extrasOverrideStored() {
        Map<String, String> stored = new HashMap<>();
        stored.put(StartParams.KEY_SERVER_HOST, "10.0.0.5");
        stored.put(StartParams.KEY_SERVER_PORT, "20000");
        stored.put(StartParams.KEY_DEVICE_ID, "stored-device");

        Map<String, String> extras = new HashMap<>();
        extras.put(StartParams.KEY_SERVER_HOST, "192.168.1.10");

        StartParams p = StartParams.resolve(extras, stored);
        assertEquals("192.168.1.10", p.serverHost); // extras 优先
        assertEquals(20000, p.serverPort);          // 无 extra → 用已存
        assertEquals("stored-device", p.deviceId);
        assertTrue(p.isConfigured());
    }

    @Test
    public void storedUsedWhenExtrasAbsent() {
        Map<String, String> stored = new HashMap<>();
        stored.put(StartParams.KEY_SERVER_HOST, "10.0.0.8");
        stored.put(StartParams.KEY_SIMULATE_DUT, "true");
        stored.put(StartParams.KEY_AUTO_START, "1");

        StartParams p = StartParams.resolve(new HashMap<>(), stored);
        assertEquals("10.0.0.8", p.serverHost);
        assertTrue(p.simulateDut);
        assertTrue(p.autoStart);
    }

    @Test
    public void invalidPortFallsBackToDefault() {
        Map<String, String> extras = new HashMap<>();
        extras.put(StartParams.KEY_SERVER_PORT, "not-a-number");
        StartParams p = StartParams.resolve(extras, null);
        assertEquals(StartParams.DEFAULT_PORT, p.serverPort);
    }

    @Test
    public void toMapRoundTrips() {
        StartParams original = new StartParams("10.1.2.3", 9999, "phone-7", true, true);
        StartParams restored = StartParams.resolve(null, original.toMap());
        assertEquals(original.serverHost, restored.serverHost);
        assertEquals(original.serverPort, restored.serverPort);
        assertEquals(original.deviceId, restored.deviceId);
        assertEquals(original.simulateDut, restored.simulateDut);
        assertEquals(original.autoStart, restored.autoStart);
    }
}
