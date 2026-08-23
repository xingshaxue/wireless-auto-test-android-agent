package com.longcheer.agent;

import com.longcheer.agent.model.DeviceState;
import com.longcheer.agent.model.DeviceStateMachine;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeviceStateMachineTest {

    private static final Set<DeviceState> ALL_STATES = EnumSet.allOf(DeviceState.class);

    @Test
    public void initialState_isRegistered() {
        DeviceStateMachine sm = new DeviceStateMachine();
        assertEquals(DeviceState.REGISTERED, sm.getCurrentState());
    }

    @Test
    public void legalTransitions_allPass() {
        // SDD §4.1 合法迁移表
        assertTransitions(DeviceState.REGISTERED,
                DeviceState.WAITING_SLOT, DeviceState.ERROR, DeviceState.PAUSED, DeviceState.TERMINATED);

        assertTransitions(DeviceState.WAITING_SLOT,
                DeviceState.WAITING_SLOT, DeviceState.CONNECTING, DeviceState.ERROR,
                DeviceState.PAUSED, DeviceState.REGISTERED, DeviceState.TERMINATED);

        assertTransitions(DeviceState.CONNECTING,
                DeviceState.SERVICE_DISCOVERING, DeviceState.DISCONNECTED, DeviceState.ERROR, DeviceState.TERMINATED);

        assertTransitions(DeviceState.SERVICE_DISCOVERING,
                DeviceState.CONFIGURING, DeviceState.DISCONNECTED, DeviceState.ERROR, DeviceState.TERMINATED);

        assertTransitions(DeviceState.CONFIGURING,
                DeviceState.READY, DeviceState.DISCONNECTED, DeviceState.ERROR, DeviceState.TERMINATED);

        assertTransitions(DeviceState.READY,
                DeviceState.POLLING, DeviceState.COMMANDING, DeviceState.DISCONNECTED,
                DeviceState.PAUSED, DeviceState.TERMINATED);

        assertTransitions(DeviceState.POLLING,
                DeviceState.READY, DeviceState.DISCONNECTED, DeviceState.ERROR, DeviceState.TERMINATED);

        assertTransitions(DeviceState.COMMANDING,
                DeviceState.READY, DeviceState.DISCONNECTED, DeviceState.ERROR, DeviceState.TERMINATED);

        assertTransitions(DeviceState.DISCONNECTED,
                DeviceState.WAITING_SLOT, DeviceState.RECONNECTING,
                DeviceState.REGISTERED, DeviceState.TERMINATED);

        assertTransitions(DeviceState.RECONNECTING,
                DeviceState.WAITING_SLOT, DeviceState.DISCONNECTED, DeviceState.ERROR, DeviceState.TERMINATED);

        assertTransitions(DeviceState.ERROR,
                DeviceState.PAUSED, DeviceState.REGISTERED, DeviceState.TERMINATED);

        assertTransitions(DeviceState.PAUSED,
                DeviceState.WAITING_SLOT, DeviceState.REGISTERED, DeviceState.TERMINATED);

        assertTransitions(DeviceState.TERMINATED); // 终态无迁出
    }

    @Test
    public void illegalTransitions_allThrow() {
        for (DeviceState from : ALL_STATES) {
            Set<DeviceState> legal = legalTargets(from);
            for (DeviceState to : ALL_STATES) {
                if (from == to) {
                    // 自环：仅 WAITING_SLOT 与 TERMINATED 明确允许；TERMINATED 已在 legalTransitions 覆盖
                    if (from == DeviceState.WAITING_SLOT) {
                        continue; // 合法
                    }
                }
                if (legal.contains(to)) {
                    continue;
                }
                DeviceStateMachine sm = new DeviceStateMachine(from);
                assertFalse("Expected illegal: " + from + " -> " + to, sm.canTransition(to));
                try {
                    sm.transition(to);
                    throw new AssertionError("Expected IllegalStateException for " + from + " -> " + to);
                } catch (IllegalStateException expected) {
                    // pass
                }
            }
        }
    }

    @Test
    public void reconnectingToConnecting_isIllegal() {
        DeviceStateMachine sm = new DeviceStateMachine(DeviceState.RECONNECTING);
        assertFalse(sm.canTransition(DeviceState.CONNECTING));
        try {
            sm.transition(DeviceState.CONNECTING);
            throw new AssertionError("RECONNECTING -> CONNECTING must be illegal");
        } catch (IllegalStateException expected) {
            // pass
        }
    }

    @Test
    public void terminated_cannotTransitionOut() {
        DeviceStateMachine sm = new DeviceStateMachine(DeviceState.TERMINATED);
        for (DeviceState target : ALL_STATES) {
            assertFalse(sm.canTransition(target));
        }
    }

    private void assertTransitions(DeviceState from, DeviceState... targets) {
        Set<DeviceState> expected = targets.length == 0
                ? Collections.emptySet()
                : new HashSet<>(Arrays.asList(targets));
        DeviceStateMachine sm = new DeviceStateMachine(from);
        for (DeviceState target : expected) {
            assertTrue("Expected legal: " + from + " -> " + target, sm.canTransition(target));
            sm.transition(target);
            assertEquals(target, sm.getCurrentState());
            sm = new DeviceStateMachine(from); // fresh for next target
        }

        Set<DeviceState> legal = legalTargets(from);
        assertEquals(expected, legal);
    }

    private Set<DeviceState> legalTargets(DeviceState from) {
        DeviceStateMachine sm = new DeviceStateMachine(from);
        Set<DeviceState> legal = EnumSet.noneOf(DeviceState.class);
        for (DeviceState target : ALL_STATES) {
            if (sm.canTransition(target)) {
                legal.add(target);
            }
        }
        return legal;
    }
}
