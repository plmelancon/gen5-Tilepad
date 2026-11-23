package com.tilepad;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventTrailTest {
    private static final class Lines implements EventTrail.Sink {
        final List<String> seen = new ArrayList<>();
        @Override public void line(String text) { seen.add(text); }
    }
    @Test public void capacityIsFifty() { assertEquals(50, EventTrail.CAPACITY); }
    @Test public void emptyTrailHasNoLines() { assertTrue(new EventTrail(null).lines().isEmpty()); }
    @Test public void linesComeOldestFirst() {
        EventTrail trail = new EventTrail(null);
        trail.add(1000, "one"); trail.add(2000, "two"); trail.add(3000, "three");
        List<String> lines = trail.lines();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).endsWith("one")); assertTrue(lines.get(1).endsWith("two")); assertTrue(lines.get(2).endsWith("three"));
    }
    @Test public void exactlyFiftyLinesAreAllKept() {
        EventTrail trail = new EventTrail(null);
        for (int i = 0; i < 50; i++) trail.add(i * 1000L, "line " + i);
        List<String> lines = trail.lines();
        assertEquals(50, lines.size());
        assertTrue(lines.get(0).endsWith("line 0")); assertTrue(lines.get(49).endsWith("line 49"));
    }
    @Test public void theFiftyFirstLineDropsTheOldest() {
        EventTrail trail = new EventTrail(null);
        for (int i = 0; i < 51; i++) trail.add(i * 1000L, "line " + i);
        List<String> lines = trail.lines();
        assertEquals(50, lines.size());
        assertTrue(lines.get(0).endsWith("line 1")); assertTrue(lines.get(49).endsWith("line 50"));
    }
    @Test public void ringKeepsTheLastFiftyInOrderAfterManyWraps() {
        EventTrail trail = new EventTrail(null);
        for (int i = 0; i < 237; i++) trail.add(i * 1000L, "line " + i);
        List<String> lines = trail.lines();
        assertEquals(50, lines.size());
        for (int i = 0; i < 50; i++) assertTrue(lines.get(i), lines.get(i).endsWith("line " + (187 + i)));
    }
    @Test public void timePrefixIsHoursMinutesSeconds() {
        EventTrail trail = new EventTrail(null);
        trail.add(3723000, "text");
        assertTrue(trail.lines().get(0), trail.lines().get(0).matches("1:02:03\\s+text"));
    }
    @Test public void timePrefixAtZeroAndJustBeforeASecond() {
        EventTrail trail = new EventTrail(null);
        trail.add(0, "a"); trail.add(999, "b"); trail.add(1000, "c"); trail.add(59999, "d"); trail.add(60000, "e");
        List<String> lines = trail.lines();
        assertTrue(lines.get(0).matches("0:00:00\\s+a"));
        assertTrue(lines.get(1).matches("0:00:00\\s+b"));
        assertTrue(lines.get(2).matches("0:00:01\\s+c"));
        assertTrue(lines.get(3).matches("0:00:59\\s+d"));
        assertTrue(lines.get(4).matches("0:01:00\\s+e"));
    }
    @Test public void hoursHaveNoLeadingZeroAndCanExceedTwoDigits() {
        EventTrail trail = new EventTrail(null);
        trail.add(9 * 3600000L + 5 * 60000L + 7000L, "a");
        trail.add(36000000L, "b");
        trail.add(100 * 3600000L, "c");
        List<String> lines = trail.lines();
        assertTrue(lines.get(0).matches("9:05:07\\s+a"));
        assertTrue(lines.get(1).matches("10:00:00\\s+b"));
        assertTrue(lines.get(2).matches("100:00:00\\s+c"));
    }
    @Test public void stateRecordsTheFirstValue() {
        EventTrail trail = new EventTrail(null);
        trail.state(1000, "ready=1 enabled=2");
        assertEquals(1, trail.lines().size());
        assertTrue(trail.lines().get(0).endsWith("ready=1 enabled=2"));
    }
    @Test public void stateIgnoresAnUnchangedValue() {
        EventTrail trail = new EventTrail(null);
        trail.state(1000, "a"); trail.state(2000, "a"); trail.state(3000, "a");
        assertEquals(1, trail.lines().size());
    }
    @Test public void stateRecordsEachChangeIncludingAReturnToAnEarlierValue() {
        EventTrail trail = new EventTrail(null);
        trail.state(1000, "a"); trail.state(2000, "b"); trail.state(3000, "b"); trail.state(4000, "a");
        List<String> lines = trail.lines();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).endsWith("a")); assertTrue(lines.get(1).endsWith("b")); assertTrue(lines.get(2).endsWith("a"));
    }
    @Test public void stateAndAddAreIndependentRecords() {
        EventTrail trail = new EventTrail(null);
        trail.state(1000, "a"); trail.add(2000, "Start sent"); trail.state(3000, "a");
        assertEquals(2, trail.lines().size());
    }
    @Test public void sinkReceivesEveryRecordedLineTextWithoutThePrefix() {
        Lines sink = new Lines(); EventTrail trail = new EventTrail(sink);
        trail.add(3723000, "Start sent"); trail.add(3724000, "Stop sent");
        assertEquals(2, sink.seen.size());
        assertEquals("Start sent", sink.seen.get(0)); assertEquals("Stop sent", sink.seen.get(1));
    }
    @Test public void sinkSeesStateOnlyWhenItWasRecorded() {
        Lines sink = new Lines(); EventTrail trail = new EventTrail(sink);
        trail.state(1000, "a"); trail.state(2000, "a"); trail.state(3000, "b");
        assertEquals(2, sink.seen.size());
        assertEquals("a", sink.seen.get(0)); assertEquals("b", sink.seen.get(1));
    }
    @Test public void sinkKeepsReceivingAfterTheRingIsFull() {
        Lines sink = new Lines(); EventTrail trail = new EventTrail(sink);
        for (int i = 0; i < 80; i++) trail.add(i * 1000L, "line " + i);
        assertEquals(80, sink.seen.size());
        assertEquals("line 79", sink.seen.get(79));
    }
    @Test public void worksWithoutASink() {
        EventTrail trail = new EventTrail(null);
        trail.add(1000, "a"); trail.state(2000, "b");
        assertEquals(2, trail.lines().size());
    }
    @Test public void returnedListCannotBeChanged() {
        EventTrail trail = new EventTrail(null);
        trail.add(1000, "a");
        try { trail.lines().add("x"); fail("add must be refused"); }
        catch (UnsupportedOperationException expected) { }
        assertEquals(1, trail.lines().size());
    }
    @Test public void returnedListIsACopyThatLaterLinesDoNotChange() {
        EventTrail trail = new EventTrail(null);
        trail.add(1000, "a");
        List<String> before = trail.lines();
        trail.add(2000, "b");
        assertEquals(1, before.size()); assertEquals(2, trail.lines().size());
    }
}
