package com.longcheer.agent.schedule;

import com.longcheer.agent.model.ConnectionSlot;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConnectionSlotManagerTest {

    private ConnectionSlotManagerImpl slotManager;

    @Before
    public void setUp() {
        slotManager = new ConnectionSlotManagerImpl(3);
    }

    @Test
    public void testSlotCount() {
        assertEquals(3, slotManager.slotCount());
    }

    @Test
    public void testAcquireUpToLimit() {
        ConnectionSlot slot1 = slotManager.acquire("AA:BB:CC:DD:EE:01", false);
        ConnectionSlot slot2 = slotManager.acquire("AA:BB:CC:DD:EE:02", false);
        ConnectionSlot slot3 = slotManager.acquire("AA:BB:CC:DD:EE:03", false);

        assertNotNull(slot1);
        assertNotNull(slot2);
        assertNotNull(slot3);
        assertEquals("AA:BB:CC:DD:EE:01", slot1.getCurrentDeviceMac());

        ConnectionSlot slot4 = slotManager.acquire("AA:BB:CC:DD:EE:04", false);
        assertNull(slot4);
        assertEquals(0, slotManager.freeSlotCount());
    }

    @Test
    public void testRelease() {
        slotManager.acquire("AA:BB:CC:DD:EE:01", false);
        assertEquals(2, slotManager.freeSlotCount());

        slotManager.release("AA:BB:CC:DD:EE:01");
        assertEquals(3, slotManager.freeSlotCount());
        assertTrue(slotManager.occupiedSlots().isEmpty());
    }

    @Test
    public void testForceRelease() {
        slotManager.acquire("AA:BB:CC:DD:EE:01", true);
        assertEquals(1, slotManager.occupiedSlots().size());
        assertTrue(slotManager.occupiedSlots().get(0).isPinned());

        slotManager.forceRelease("AA:BB:CC:DD:EE:01");
        assertEquals(3, slotManager.freeSlotCount());
    }

    @Test
    public void testReleaseUnknownDevice() {
        // 释放不存在的设备不应抛异常。
        slotManager.release("00:00:00:00:00:00");
        assertEquals(3, slotManager.freeSlotCount());
    }

    @Test
    public void testAcquirePinned() {
        ConnectionSlot slot = slotManager.acquire("AA:BB:CC:DD:EE:01", true);
        assertNotNull(slot);
        assertTrue(slot.isPinned());
    }

    @Test
    public void testLeakRelease() {
        ConnectionSlotManagerImpl leaky = new ConnectionSlotManagerImpl(1, 50);
        ConnectionSlot slot = leaky.acquire("AA:BB:CC:DD:EE:01", false);
        assertNotNull(slot);
        assertFalse(slot.isFree());

        // 等待超期后由泄漏检查释放。
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        leaky.releaseLeakedSlots();
        assertTrue(slot.isFree());
    }
}
