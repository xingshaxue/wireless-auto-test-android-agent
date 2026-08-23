package com.longcheer.agent.registry;

import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.ManagedDeviceInfo;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DeviceRegistryTest {

    private DeviceRegistry registry;

    @Before
    public void setUp() {
        registry = new DeviceRegistryImpl();
    }

    @Test
    public void testRegisterAndFindByMac() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", "dut-001", DeviceState.REGISTERED, 5);
        registry.register(controller);

        assertEquals(1, registry.size());
        assertNotNull(registry.findByMac("AA:BB:CC:DD:EE:FF"));
        assertEquals("dut-001", registry.findByMac("AA:BB:CC:DD:EE:FF").snapshot().getDeviceId());
    }

    @Test
    public void testFindByDeviceId() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", "dut-001", DeviceState.REGISTERED, 5);
        registry.register(controller);

        assertNotNull(registry.findByDeviceId("dut-001"));
        assertNull(registry.findByDeviceId("dut-999"));
    }

    @Test
    public void testFindByState() {
        DeviceController ready = mockController("00:00:00:00:00:01", "dut-001", DeviceState.READY, 5);
        DeviceController registered = mockController("00:00:00:00:00:02", "dut-002", DeviceState.REGISTERED, 3);
        registry.register(ready);
        registry.register(registered);

        assertEquals(1, registry.findByState(DeviceState.READY).size());
        assertEquals("dut-001", registry.findByState(DeviceState.READY).get(0).snapshot().getDeviceId());
    }

    @Test
    public void testFindAllOrderByPriorityDesc() {
        DeviceController low = mockController("00:00:00:00:00:01", "dut-low", DeviceState.REGISTERED, 1);
        DeviceController high = mockController("00:00:00:00:00:02", "dut-high", DeviceState.REGISTERED, 10);
        DeviceController mid = mockController("00:00:00:00:00:03", "dut-mid", DeviceState.REGISTERED, 5);
        registry.register(low);
        registry.register(high);
        registry.register(mid);

        assertEquals(3, registry.findAllOrderByPriorityDesc().size());
        assertEquals("dut-high", registry.findAllOrderByPriorityDesc().get(0).snapshot().getDeviceId());
        assertEquals("dut-low", registry.findAllOrderByPriorityDesc().get(2).snapshot().getDeviceId());
    }

    @Test
    public void testUnregister() {
        DeviceController controller = mockController("AA:BB:CC:DD:EE:FF", "dut-001", DeviceState.REGISTERED, 5);
        registry.register(controller);
        registry.unregister("AA:BB:CC:DD:EE:FF");

        assertEquals(0, registry.size());
        assertNull(registry.findByMac("AA:BB:CC:DD:EE:FF"));
        assertNull(registry.findByDeviceId("dut-001"));
    }

    @Test
    public void testClear() {
        registry.register(mockController("00:00:00:00:00:01", "dut-001", DeviceState.REGISTERED, 5));
        registry.register(mockController("00:00:00:00:00:02", "dut-002", DeviceState.REGISTERED, 5));
        registry.clear();

        assertEquals(0, registry.size());
    }

    private DeviceController mockController(String mac, String deviceId, DeviceState state, int priority) {
        DeviceController controller = mock(DeviceController.class);
        ManagedDeviceInfo info = new ManagedDeviceInfo(deviceId, mac);
        info.setState(state);
        info.setPriority(priority);
        when(controller.snapshot()).thenReturn(info);
        when(controller.getState()).thenReturn(state);
        return controller;
    }
}
