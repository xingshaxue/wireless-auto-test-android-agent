package com.longcheer.agent.report;

import com.longcheer.agent.schedule.ConnectionSlotManagerImpl;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * StatsCollector 单测（SDD §13 / §10.2 CONNECTION_STATISTICS）。
 */
public class StatsCollectorTest {

    private ConnectionSlotManagerImpl slotManager;
    private StateReporter reporter;
    private StatsCollector collector;

    @Before
    public void setUp() {
        slotManager = new ConnectionSlotManagerImpl(3);
        reporter = mock(StateReporter.class);
        collector = new StatsCollector(slotManager);
    }

    @Test
    public void reportContainsAllMetrics() {
        slotManager.acquire("AA:01", false); // slotsUsed=1
        collector.onSlotSwitch();
        collector.onSlotSwitch();
        collector.onGattResult(true);
        collector.onGattResult(true);
        collector.onGattResult(true);
        collector.onGattResult(false);
        collector.onPollDuration(100);
        collector.onPollDuration(300);

        collector.report(reporter);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(org.mockito.ArgumentMatchers.eq("CONNECTION_STATISTICS"),
                captor.capture());
        Map<String, Object> p = captor.getValue();
        assertEquals(1, p.get("slotsUsed"));
        assertEquals(3, p.get("slotsTotal"));
        assertEquals(2L, p.get("switchCount"));
        assertEquals(0.25, (Double) p.get("gattFailureRate"), 0.0001);
        assertEquals(200L, p.get("avgPollMs"));
    }

    @Test
    public void emptyStatsReportZeros() {
        collector.report(reporter);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reporter).report(org.mockito.ArgumentMatchers.eq("CONNECTION_STATISTICS"),
                captor.capture());
        assertEquals(0L, captor.getValue().get("switchCount"));
        assertEquals(0.0, (Double) captor.getValue().get("gattFailureRate"), 0.0001);
        assertEquals(0L, captor.getValue().get("avgPollMs"));
    }
}
