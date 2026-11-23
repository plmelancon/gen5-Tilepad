package com.tilepad;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TemperatureLogTest {
    @Test public void capacityIs360AndBreakIsFiftySeconds() {
        assertEquals(360, TemperatureLog.CAPACITY);
        assertEquals(50000L, TemperatureLog.BREAK_MS);
    }
    @Test public void emptyLogHasNoPointsAndNoLatest() {
        TemperatureLog log = new TemperatureLog();
        assertTrue(log.points().isEmpty());
        assertNull(log.latest());
    }
    @Test public void pointsComeOldestFirstWithTheirFields() {
        TemperatureLog log = new TemperatureLog();
        log.add(1000, 14, false); log.add(2000, -3, true); log.add(3500, 22, false);
        List<TemperatureLog.Point> points = log.points();
        assertEquals(3, points.size());
        assertEquals(1000, points.get(0).at); assertEquals(14, points.get(0).minimumC); assertFalse(points.get(0).heating);
        assertEquals(2000, points.get(1).at); assertEquals(-3, points.get(1).minimumC); assertTrue(points.get(1).heating);
        assertEquals(3500, points.get(2).at); assertEquals(22, points.get(2).minimumC);
    }
    @Test public void latestIsTheNewestPoint() {
        TemperatureLog log = new TemperatureLog();
        log.add(1000, 14, false);
        assertEquals(1000, log.latest().at);
        log.add(2000, 15, true);
        assertEquals(2000, log.latest().at); assertEquals(15, log.latest().minimumC); assertTrue(log.latest().heating);
    }
    @Test public void equalTimeIsIgnored() {
        TemperatureLog log = new TemperatureLog();
        log.add(1000, 14, false); log.add(1000, 30, true);
        assertEquals(1, log.points().size());
        assertEquals(14, log.latest().minimumC); assertFalse(log.latest().heating);
    }
    @Test public void earlierTimeIsIgnoredAndLaterOneStillAccepted() {
        TemperatureLog log = new TemperatureLog();
        log.add(5000, 14, false); log.add(4999, 99, true);
        assertEquals(1, log.points().size());
        assertEquals(5000, log.latest().at);
        log.add(5001, 15, false);
        assertEquals(2, log.points().size());
        assertEquals(5001, log.latest().at);
    }
    @Test public void firstPointMayHaveTimeZero() {
        TemperatureLog log = new TemperatureLog();
        log.add(0, 10, false);
        assertEquals(1, log.points().size());
    }
    @Test public void exactlyCapacityKeepsEverything() {
        TemperatureLog log = new TemperatureLog();
        for (int i = 1; i <= TemperatureLog.CAPACITY; i++) log.add(i * 1000L, i % 40, false);
        List<TemperatureLog.Point> points = log.points();
        assertEquals(360, points.size());
        assertEquals(1000, points.get(0).at);
        assertEquals(360000, points.get(359).at);
    }
    @Test public void oneOverCapacityDropsOnlyTheOldest() {
        TemperatureLog log = new TemperatureLog();
        for (int i = 1; i <= TemperatureLog.CAPACITY + 1; i++) log.add(i * 1000L, 10, false);
        List<TemperatureLog.Point> points = log.points();
        assertEquals(360, points.size());
        assertEquals(2000, points.get(0).at);
        assertEquals(361000, points.get(359).at);
        assertEquals(361000, log.latest().at);
    }
    @Test public void wrappingSeveralTimesKeepsTheNewest360InOrder() {
        TemperatureLog log = new TemperatureLog();
        for (int i = 1; i <= 1000; i++) log.add(i * 1000L, i % 50, i % 2 == 0);
        List<TemperatureLog.Point> points = log.points();
        assertEquals(360, points.size());
        for (int i = 0; i < points.size(); i++) {
            long expected = (641 + i) * 1000L;
            assertEquals(expected, points.get(i).at);
            assertEquals((641 + i) % 50, points.get(i).minimumC);
            assertEquals((641 + i) % 2 == 0, points.get(i).heating);
        }
        assertEquals(1000000, log.latest().at);
    }
    @Test public void ignoredPointOnAFullLogEvictsNothing() {
        TemperatureLog log = new TemperatureLog();
        for (int i = 1; i <= 400; i++) log.add(i * 1000L, 10, false);
        long oldest = log.points().get(0).at;
        log.add(400000, 11, false); log.add(100, 12, false);
        assertEquals(360, log.points().size());
        assertEquals(oldest, log.points().get(0).at);
        assertEquals(10, log.latest().minimumC);
    }
    @Test public void returnedListCannotBeChanged() {
        TemperatureLog log = new TemperatureLog();
        log.add(1000, 14, false);
        List<TemperatureLog.Point> points = log.points();
        try { points.add(new TemperatureLog.Point(2000, 1, false)); fail("add must be refused"); }
        catch (UnsupportedOperationException expected) { }
        try { points.remove(0); fail("remove must be refused"); }
        catch (UnsupportedOperationException expected) { }
        try { points.clear(); fail("clear must be refused"); }
        catch (UnsupportedOperationException expected) { }
        assertEquals(1, log.points().size());
    }
    @Test public void returnedListIsACopyThatLaterAddsDoNotChange() {
        TemperatureLog log = new TemperatureLog();
        log.add(1000, 14, false);
        List<TemperatureLog.Point> before = log.points();
        log.add(2000, 15, false);
        assertEquals(1, before.size());
        assertEquals(2, log.points().size());
    }
}
