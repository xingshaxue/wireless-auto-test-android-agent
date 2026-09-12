package com.longcheer.agent;

import android.os.Binder;

import com.longcheer.agent.ble.BleCentralManager;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.dispatch.CommandDispatcher;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.ManagedDeviceInfo;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.registry.DeviceRegistryImpl;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.ConnectionSlotManager;
import com.longcheer.agent.schedule.PollingScheduler;
import com.longcheer.agent.tcp.TcpClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentService 基础契约测试。
 *
 * <p>M1 阶段不引入 Robolectric：生命周期/Binder 等用反射验证签名；applyConfig 的
 * §16.4 整项替换语义经注入构造 + 全 mock 依赖实例化驱动（unitTests.returnDefaultValues
 * 使 android.jar 方法返回默认值，applyConfig 本身不触碰 Android 运行时）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class AgentServiceTest {

    @Mock
    private TcpClient mockTcpClient;
    @Mock
    private BleCentralManager mockBleCentralManager;
    @Mock
    private DeviceRegistry mockDeviceRegistry;
    @Mock
    private ConnectionSlotManager mockConnectionSlotManager;
    @Mock
    private ConnectionScheduler mockConnectionScheduler;
    @Mock
    private PollingScheduler mockPollingScheduler;
    @Mock
    private StateReporter mockStateReporter;
    @Mock
    private CommandDispatcher mockCommandDispatcher;

    @Test
    public void actionConstantsAreDefined() {
        assertEquals("com.longcheer.agent.ACTION_START", AgentService.ACTION_START);
        assertEquals("com.longcheer.agent.ACTION_STOP", AgentService.ACTION_STOP);
    }

    @Test
    public void notificationConstantsAreDefined() {
        assertEquals("agent_foreground_channel", AgentService.NOTIFICATION_CHANNEL_ID);
        assertEquals(1, AgentService.NOTIFICATION_ID);
    }

    @Test
    public void startExtrasAreDefined() {
        assertEquals("server_host", AgentService.EXTRA_SERVER_HOST);
        assertEquals("server_port", AgentService.EXTRA_SERVER_PORT);
        assertEquals("device_id", AgentService.EXTRA_DEVICE_ID);
    }

    @Test
    public void lifecycleMethodsAreDeclared() throws Exception {
        Class<?> clazz = AgentService.class;
        assertNotNull(clazz.getDeclaredMethod("onCreate"));
        assertNotNull(clazz.getDeclaredMethod("onStartCommand",
                android.content.Intent.class, int.class, int.class));
        assertNotNull(clazz.getDeclaredMethod("onBind", android.content.Intent.class));
        assertNotNull(clazz.getDeclaredMethod("onDestroy"));
    }

    @Test
    public void binderInnerClassExtendsBinder() throws Exception {
        Class<?> binderClass = Class.forName("com.longcheer.agent.AgentService$AgentBinder");
        assertTrue(Binder.class.isAssignableFrom(binderClass));
        assertTrue(Modifier.isPublic(binderClass.getModifiers()));

        Method getService = binderClass.getDeclaredMethod("getService");
        assertNotNull(getService);
        assertEquals(AgentService.class, getService.getReturnType());
    }

    @Test
    public void injectionConstructorExistsAndIsPackagePrivate() throws Exception {
        Class<?> clazz = AgentService.class;
        Constructor<?> ctor = clazz.getDeclaredConstructor(
                TcpClient.class,
                BleCentralManager.class,
                DeviceRegistry.class,
                ConnectionSlotManager.class,
                ConnectionScheduler.class,
                PollingScheduler.class,
                StateReporter.class,
                CommandDispatcher.class,
                com.longcheer.agent.poll.PollResultChain.class);
        assertNotNull(ctor);
        assertFalse("injection constructor should not be public", Modifier.isPublic(ctor.getModifiers()));
        assertFalse("injection constructor should not be private", Modifier.isPrivate(ctor.getModifiers()));
    }

    @Test
    public void deviceStatesMethodIsPublicListReturning() throws Exception {
        Method m = AgentService.class.getDeclaredMethod("deviceStates");
        assertTrue(Modifier.isPublic(m.getModifiers()));
        assertEquals(java.util.List.class, m.getReturnType());
    }

    @Test
    public void agentConfigFromJsonProvidesDefaults() {
        AgentConfig config = AgentConfig.fromJson(null);
        assertNotNull(config);
        assertEquals(0, config.getConfigVersion());
        assertEquals(3, config.getMaxSlots());
        assertEquals(100L, config.getTickIntervalMs());
        assertEquals(5000L, config.getHeartbeatIntervalMs());
        assertEquals(10000L, config.getConnectTimeoutMs());
        assertEquals(3000L, config.getGattTimeoutMs());
        assertNotNull(config.getDevices());
        assertTrue(config.getDevices().isEmpty());
    }

    // ---------- §16.4 整项替换：服务器删设备后 agent 同步移除 ----------

    private static final String MAC_A = "AA:BB:CC:DD:EE:01";
    private static final String MAC_B = "AA:BB:CC:DD:EE:02";
    private static final String MAC_C = "AA:BB:CC:DD:EE:03";

    /**
     * 装配 applyConfig 测试环境：真实 DeviceRegistryImpl + 模拟 BleCentralManager
     * （destroyController 复刻真实实现契约：terminate + 注册表注销）。
     */
    private Object[] newApplyConfigHarness() {
        DeviceRegistryImpl registry = new DeviceRegistryImpl();
        Map<String, DeviceController> created = new HashMap<>();
        BleCentralManager ble = mock(BleCentralManager.class);
        when(ble.createController(anyString(), anyString())).thenAnswer(inv -> {
            String mac = inv.getArgument(0);
            DeviceController controller = mock(DeviceController.class);
            ManagedDeviceInfo info = new ManagedDeviceInfo(inv.getArgument(1), mac);
            when(controller.snapshot()).thenReturn(info);
            created.put(mac, controller);
            return controller;
        });
        doAnswer(inv -> {
            String mac = inv.getArgument(0);
            DeviceController controller = created.get(mac);
            if (controller != null) {
                controller.terminate();
                registry.unregister(mac);
            }
            return null;
        }).when(ble).destroyController(anyString());
        AgentService service = new AgentService(mockTcpClient, ble, registry,
                mockConnectionSlotManager, mockConnectionScheduler, mockPollingScheduler,
                mockStateReporter, mockCommandDispatcher,
                mock(com.longcheer.agent.poll.PollResultChain.class));
        return new Object[]{service, registry, created};
    }

    private static void applyConfig(AgentService service, int version, String... macs) throws Exception {
        JSONArray arr = new JSONArray();
        int i = 0;
        for (String mac : macs) {
            JSONObject d = new JSONObject();
            d.put("deviceId", "dut-" + (++i));
            d.put("mac", mac);
            arr.put(d);
        }
        JSONObject json = new JSONObject();
        json.put("configVersion", version);
        json.put("devices", arr);
        Method m = AgentService.class.getDeclaredMethod("applyConfig", AgentConfig.class);
        m.setAccessible(true);
        m.invoke(service, AgentConfig.fromJson(json));
    }

    @Test
    public void applyConfigRemovesDeviceDroppedFromNewConfig() throws Exception {
        Object[] h = newApplyConfigHarness();
        AgentService service = (AgentService) h[0];
        DeviceRegistryImpl registry = (DeviceRegistryImpl) h[1];
        @SuppressWarnings("unchecked")
        Map<String, DeviceController> created = (Map<String, DeviceController>) h[2];

        applyConfig(service, 1, MAC_A, MAC_B);
        assertNotNull(registry.findByMac(MAC_A));
        assertNotNull(registry.findByMac(MAC_B));

        applyConfig(service, 2, MAC_B); // v2 比 v1 少一台

        // 被删设备：从注册表消失，按 §7.7 移除路径处理（terminate → TERMINATED，
        // DEVICE_STATE 事件由真实 terminate 路径上报，此处验证 terminate 被触发）。
        assertNull("被服务器删除的设备应从注册表移除", registry.findByMac(MAC_A));
        verify(created.get(MAC_A)).terminate();
        verify(created.get(MAC_A)).drainPendingCommands();
        verify(mockConnectionScheduler).cancelRequest(MAC_A);
        verify(mockConnectionScheduler).releaseSlot(MAC_A);
        verify(mockPollingScheduler).removeConfig(MAC_A);

        // 保留设备不受影响。
        assertNotNull(registry.findByMac(MAC_B));
        verify(created.get(MAC_B), never()).terminate();
        verify(mockConnectionScheduler, never()).cancelRequest(MAC_B);
        verify(mockConnectionScheduler, never()).releaseSlot(MAC_B);
        verify(mockPollingScheduler, never()).removeConfig(MAC_B);
    }

    @Test
    public void applyConfigMixedAddKeepRemove() throws Exception {
        Object[] h = newApplyConfigHarness();
        AgentService service = (AgentService) h[0];
        DeviceRegistryImpl registry = (DeviceRegistryImpl) h[1];
        @SuppressWarnings("unchecked")
        Map<String, DeviceController> created = (Map<String, DeviceController>) h[2];

        applyConfig(service, 1, MAC_A, MAC_B);
        applyConfig(service, 2, MAC_B, MAC_C); // 移除 A、保留 B、新增 C

        assertNull(registry.findByMac(MAC_A));
        assertNotNull(registry.findByMac(MAC_B));
        assertNotNull(registry.findByMac(MAC_C));
        verify(created.get(MAC_A)).terminate();
        verify(mockPollingScheduler).removeConfig(MAC_A);
        // 保留的 B 与新增的 C 均不走移除路径。
        verify(created.get(MAC_B), never()).terminate();
        verify(created.get(MAC_C), never()).terminate();
        verify(mockPollingScheduler, never()).removeConfig(MAC_B);
        verify(mockPollingScheduler, never()).removeConfig(MAC_C);
    }
}
