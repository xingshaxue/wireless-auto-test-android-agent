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

        // 在单元测试环境中 SystemClock.elapsedRealtime() 返回 0，
        // 因此手动将 acquireTime 设为过去的时间点以模拟超期。
        slot.setAcquireTime(-100);
        assertEquals(java.util.Collections.singletonList("AA:BB:CC:DD:EE:01"), leaky.releaseLeakedSlots());
        assertTrue(slot.isFree());
    }

    @Test
    public void testShrinkLimitBlocksNewAcquire() {
        // §5.4 调小：占用不强制清除，但阻止新的 acquire，直到占用回落到上限内。
        slotManager.acquire("AA:BB:CC:DD:EE:01", false);
        slotManager.acquire("AA:BB:CC:DD:EE:02", false);
        slotManager.acquire("AA:BB:CC:DD:EE:03", false);

        slotManager.setLimit(2);
        assertEquals(2, slotManager.slotCount());
        assertEquals(0, slotManager.freeSlotCount());

        slotManager.release("AA:BB:CC:DD:EE:03"); // 占用 3→2，仍达上限
        assertNull(slotManager.acquire("AA:BB:CC:DD:EE:04", false));

        slotManager.release("AA:BB:CC:DD:EE:02"); // 占用 2→1，可新分配
        assertNotNull(slotManager.acquire("AA:BB:CC:DD:EE:04", false));
    }

    @Test
    public void testGrowLimitEnablesAcquireImmediately() {
        slotManager.setLimit(1);
        slotManager.acquire("AA:BB:CC:DD:EE:01", false);
        assertNull(slotManager.acquire("AA:BB:CC:DD:EE:02", false));

        slotManager.setLimit(3); // §5.4 调大立即生效
        assertNotNull(slotManager.acquire("AA:BB:CC:DD:EE:02", false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetLimitBeyondCapacityRejected() {
        slotManager.setLimit(4); // 容量 3
    }

    @Test
    public void testSlotOf() {
        slotManager.acquire("AA:BB:CC:DD:EE:01", false);
        assertNotNull(slotManager.slotOf("AA:BB:CC:DD:EE:01"));
        assertNull(slotManager.slotOf("AA:BB:CC:DD:EE:02"));
    }
}
