package com.longcheer.agent;

import android.os.Binder;

import com.longcheer.agent.ble.BleCentralManager;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.dispatch.CommandDispatcher;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.ConnectionSlotManager;
import com.longcheer.agent.schedule.PollingScheduler;
import com.longcheer.agent.tcp.TcpClient;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * AgentService 基础契约测试。
 *
 * <p>M1 阶段不引入 Robolectric，因此本测试不实例化 Service（Android 类在 JVM 单元测试下
 * 为 stub），而是通过反射验证公开常量、生命周期方法签名、Binder 类型以及注入构造存在性。
 * 若后续接入 Robolectric，可在此基础上实例化 Service 并对注入的 mock 依赖做行为断言。</p>
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
}
