package com.longcheer.agent.poll;

import com.longcheer.agent.model.PollRule;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RuleEngine 单测（SDD §7.3.2 / §16.5 / §14.1）：阈值边界、priority 降序、stopOnMatch。
 */
public class RuleEngineTest {

    private static PollRule rule(String id, int priority, boolean stopOnMatch,
                                 PollRule.Condition condition, String event) {
        PollRule.RuleAction action = new PollRule.RuleAction(
                PollRule.RuleAction.RuleActionType.REPORT_EVENT,
                Collections.singletonMap("event", event));
        return new PollRule(id, Collections.singletonList(condition),
                Collections.singletonList(action), priority, stopOnMatch);
    }

    private static PollRule.Condition cond(String field, PollRule.Condition.Operator op,
                                           double value, double valueMax) {
        return new PollRule.Condition(field, op, value, valueMax);
    }

    private static Map<String, Object> fields(Object temperature, Object battery) {
        Map<String, Object> m = new HashMap<>();
        if (temperature != null) m.put("temperature", temperature);
        if (battery != null) m.put("battery", battery);
        return m;
    }

    private static List<String> events(List<PollRule.RuleAction> actions) {
        List<String> out = new java.util.ArrayList<>();
        for (PollRule.RuleAction a : actions) {
            out.add((String) a.params.get("event"));
        }
        return out;
    }

    @Test
    public void testThresholdBoundaries() {
        assertTrue(RuleEngine.match(cond("t", PollRule.Condition.Operator.GT, 40, 0), 41));
        assertTrue(RuleEngine.match(cond("t", PollRule.Condition.Operator.GT, 40, 0), 40.0001));
        org.junit.Assert.assertFalse(RuleEngine.match(cond("t", PollRule.Condition.Operator.GT, 40, 0), 40));
        assertTrue(RuleEngine.match(cond("t", PollRule.Condition.Operator.GE, 40, 0), 40));
        assertTrue(RuleEngine.match(cond("t", PollRule.Condition.Operator.LT, 40, 0), 39));
        org.junit.Assert.assertFalse(RuleEngine.match(cond("t", PollRule.Condition.Operator.LT, 40, 0), 40));
        assertTrue(RuleEngine.match(cond("t", PollRule.Condition.Operator.LE, 40, 0), 40));
        assertTrue(RuleEngine.match(cond("t", PollRule.Condition.Operator.EQ, 85, 0), 85));
        org.junit.Assert.assertFalse(RuleEngine.match(cond("t", PollRule.Condition.Operator.EQ, 85, 0), 84));
        assertTrue(RuleEngine.match(cond("t", PollRule.Condition.Operator.NE, 85, 0), 84));
    }

    @Test
    public void testBetweenClosedInterval() {
        PollRule.Condition between = cond("t", PollRule.Condition.Operator.BETWEEN, 20, 40);
        assertTrue(RuleEngine.match(between, 20));
        assertTrue(RuleEngine.match(between, 40));
        assertTrue(RuleEngine.match(between, 30));
        org.junit.Assert.assertFalse(RuleEngine.match(between, 19.9));
        org.junit.Assert.assertFalse(RuleEngine.match(between, 40.1));
    }

    @Test
    public void testMissingFieldAndNonNumericNotMatched() {
        org.junit.Assert.assertFalse(RuleEngine.match(cond("x", PollRule.Condition.Operator.GT, 1, 0), null));
        org.junit.Assert.assertFalse(RuleEngine.match(cond("t", PollRule.Condition.Operator.GT, 1, 0), "not-a-number"));
        assertTrue(RuleEngine.match(cond("s", PollRule.Condition.Operator.EQ, 1, 0), Boolean.TRUE));
    }

    @Test
    public void testPriorityOrderAndMultipleMatches() {
        // 温度 42 时两条规则都命中，动作按 priority 降序排列（§7.3.2 示例）。
        PollRule low = rule("r2", 5, false, cond("temperature", PollRule.Condition.Operator.GT, 20, 0), "TEMP_WARN");
        PollRule high = rule("r1", 10, false, cond("temperature", PollRule.Condition.Operator.GT, 40, 0), "TEMP_CRITICAL");

        List<PollRule.RuleAction> actions = RuleEngine.evaluate(Arrays.asList(low, high), fields(42, 85));

        assertEquals(Arrays.asList("TEMP_CRITICAL", "TEMP_WARN"), events(actions));
    }

    @Test
    public void testStopOnMatchBlocksLowerPriority() {
        PollRule low = rule("r2", 5, false, cond("temperature", PollRule.Condition.Operator.GT, 20, 0), "TEMP_WARN");
        PollRule high = rule("r1", 10, true, cond("temperature", PollRule.Condition.Operator.GT, 40, 0), "TEMP_CRITICAL");

        List<PollRule.RuleAction> actions = RuleEngine.evaluate(Arrays.asList(low, high), fields(42, 85));

        assertEquals(Collections.singletonList("TEMP_CRITICAL"), events(actions));
    }

    @Test
    public void testConditionsAreAndCombined() {
        PollRule two = new PollRule("r", Arrays.asList(
                cond("temperature", PollRule.Condition.Operator.GT, 20, 0),
                cond("battery", PollRule.Condition.Operator.GE, 20, 0)),
                Collections.emptyList(), 0, false);

        assertTrue(RuleEngine.evaluate(Collections.singletonList(two), fields(30, 50)).isEmpty());
        assertTrue(RuleEngine.evaluate(Collections.singletonList(two), fields(30, 10)).isEmpty());

        PollRule withAction = rule("r", 0, false, cond("temperature", PollRule.Condition.Operator.GT, 20, 0), "E");
        withAction.conditions = Arrays.asList(
                cond("temperature", PollRule.Condition.Operator.GT, 20, 0),
                cond("battery", PollRule.Condition.Operator.GE, 20, 0));
        assertEquals(1, RuleEngine.evaluate(Collections.singletonList(withAction), fields(30, 50)).size());
        assertTrue(RuleEngine.evaluate(Collections.singletonList(withAction), fields(10, 50)).isEmpty());
    }
}
