package com.tilepad;

import com.tilepad.KeepWarm.Decision;
import com.tilepad.KeepWarm.Why;
import com.tilepad.PreconditioningSnapshot.State;
import java.util.TreeMap;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Keep warm judged from the car's own READY and setting values, with the real controller in the
 * loop the way the service's tick drives it: refresh, snapshot, KeepWarm.tick. After a session the
 * controller's state stays ENDED whatever the car says, so only the raw values can show that READY
 * was lost or the setting switched off.
 */
public class KeepWarmCarValuesTest {
    private static final long T0 = 1000000L;
    private static final int COLD = 15, WARM_C = 25;

    private static VehiclePort.Reading reading(int setting, int indicator, int ready) {
        return new VehiclePort.Reading(setting, indicator, ready, -1);
    }

    private static final class Rig {
        final PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        final PreconditioningControllerTest.Journal journal = new PreconditioningControllerTest.Journal();
        final PreconditioningController c = new PreconditioningController(car, journal);
        final KeepWarm kw = new KeepWarm();
        long now = T0;
        PreconditioningSnapshot snap;
        Decision last = Decision.NONE;
        // The battery reader: one reading every sampleEvery ms, delivered just before the tick that follows it.
        long nextSample = T0 + 500, sampleEvery = 20000;
        int sampleC = WARM_C;
        // Readings put in by hand: time -> minimum temperature, delivered with the first tick at or after their time.
        final TreeMap<Long, Integer> manual = new TreeMap<>();

        Rig() { c.refresh(now); snap = c.snapshot(now); }

        /** The driver arms and Start goes out with it, the way the service does. */
        Rig armAndStart(int minutes) {
            assertTrue(kw.arm(now, minutes, snap));
            boolean sent = c.start(now, null);
            kw.manualStart(now, sent);
            assertTrue(sent);
            snap = c.snapshot(now);
            return this;
        }
        Decision tickAt(long at) {
            now = at;
            while (!manual.isEmpty() && manual.firstKey() <= now) {
                java.util.Map.Entry<Long, Integer> e = manual.pollFirstEntry();
                kw.sample(e.getKey(), e.getValue(), car.warmThresholdC());
            }
            while (nextSample <= now) { kw.sample(nextSample, sampleC, car.warmThresholdC()); nextSample += sampleEvery; }
            c.refresh(now);
            snap = c.snapshot(now);
            last = kw.tick(now, snap, false);
            return last;
        }
        /** One tick a second up to {@code to}, all of which must be NONE. */
        void quietUntil(long to) {
            while (now < to) {
                Decision d = tickAt(Math.min(now + 1000, to));
                assertEquals("at +" + (now - T0) + " ms, state " + snap.state + ", why " + kw.why(), Decision.NONE, d);
            }
        }
        /** A first session that heats and ends by itself: the Start at arming, Active at +30 s, off at +60 s (the controller cancels at +61 s), ENDED at +62 s. */
        Rig sessionThatEnds() {
            armAndStart(120);
            quietUntil(T0 + 30000);
            car.reading = reading(2, 1, 1);
            quietUntil(T0 + 60000);
            assertEquals(State.ACTIVE, snap.state);
            car.reading = reading(2, 0, 1);
            quietUntil(T0 + 62000);
            assertEquals(State.ENDED, snap.state);
            assertTrue(kw.armed()); assertEquals(Why.NONE, kw.why());
            return this;
        }
    }

    private static String where(Rig r) { return "at +" + (r.now - T0) + " ms, state " + r.snap.state + ", why " + r.kw.why(); }

    // ---- READY lost / setting switched off, during a session (1, 2) ----

    private static void lostDuringASession(boolean readyLost) {
        Rig r = new Rig().armAndStart(120);
        r.quietUntil(T0 + 30000);
        r.car.reading = reading(2, 1, 1);
        r.quietUntil(T0 + 60000);
        assertEquals(State.ACTIVE, r.snap.state);
        assertEquals(0, r.car.stops);
        // The first tick that reads the lost value: the controller ends the session, keep warm lets go on that very tick.
        r.car.reading = readyLost ? reading(2, 1, 0) : reading(1, 1, 1);
        assertEquals(Decision.DISARM, r.tickAt(T0 + 61000));
        assertFalse(r.kw.armed());
        assertEquals(Why.NOT_READY, r.kw.why());
        assertTrue(r.kw.ended(), r.kw.ended().contains(readyLost ? "no longer ready" : "conditioning is off"));
        assertEquals(State.STOPPING, r.snap.state);
        assertEquals(1, r.car.stops);
        // The car reports conditioning off, and the controller releases the session.
        r.car.reading = readyLost ? reading(2, 0, 0) : reading(1, 0, 1);
        assertEquals(Decision.NONE, r.tickAt(T0 + 62000));
        assertEquals(State.ENDED, r.snap.state);
        assertFalse(r.journal.pending);
        assertEquals(1, r.car.stops);
        // READY comes back (or the setting is switched on again) and the battery stays cold for a long time.
        r.car.reading = reading(2, 0, 1);
        r.sampleC = COLD;
        r.quietUntil(T0 + 62000 + 1800000L);
        assertTrue(where(r), r.snap.canStart);
        assertFalse(r.kw.armed());
        assertEquals(Why.NOT_READY, r.kw.why());
        assertEquals(1, r.car.starts);
        assertEquals(0, r.kw.automaticStarts());
    }
    @Test public void readyLostDuringASessionDisarmsAtOnceAndNeverStartsAgain() { lostDuringASession(true); }
    @Test public void theSettingSwitchedOffDuringASessionDisarmsAtOnceAndNeverStartsAgain() { lostDuringASession(false); }

    // ---- READY lost / setting switched off, between sessions (3) ----

    private static void lostBetweenSessions(boolean readyLost) {
        Rig r = new Rig().sessionThatEnds();
        r.quietUntil(T0 + 90000);
        assertEquals(State.ENDED, r.snap.state);
        r.car.reading = readyLost ? reading(2, 0, 0) : reading(1, 0, 1);
        assertEquals(Decision.DISARM, r.tickAt(T0 + 91000));
        // The state alone still says ENDED: only the car's own value shows it.
        assertEquals(State.ENDED, r.snap.state);
        if (readyLost) assertEquals(0, r.snap.readyRaw); else assertEquals(1, r.snap.enabledRaw);
        assertFalse(r.kw.armed());
        assertEquals(Why.NOT_READY, r.kw.why());
        assertTrue(r.kw.ended(), r.kw.ended().contains(readyLost ? "no longer ready" : "conditioning is off"));
        r.car.reading = reading(2, 0, 1);
        r.sampleC = COLD;
        r.quietUntil(T0 + 91000 + 1800000L);
        assertEquals(State.ENDED, r.snap.state);
        assertTrue(where(r), r.snap.canStart);
        assertEquals(Why.NOT_READY, r.kw.why());
        assertEquals(1, r.car.starts);
        assertEquals(0, r.kw.automaticStarts());
    }
    @Test public void readyLostBetweenSessionsDisarmsEvenThoughTheStateStaysEnded() { lostBetweenSessions(true); }
    @Test public void theSettingSwitchedOffBetweenSessionsDisarmsEvenThoughTheStateStaysEnded() { lostBetweenSessions(false); }

    @Test public void readyLostWhileTheStateIsTimedOutDisarmsToo() {
        Rig r = new Rig().armAndStart(120);
        r.quietUntil(T0 + PreconditioningController.ACTIVATION_TIMEOUT_MS - 1000);
        assertEquals(State.WAITING, r.snap.state);
        r.quietUntil(T0 + PreconditioningController.ACTIVATION_TIMEOUT_MS + 2000);
        assertEquals(State.TIMED_OUT, r.snap.state);
        assertTrue(r.kw.armed());
        r.car.reading = reading(2, 0, 0);
        assertEquals(Decision.DISARM, r.tickAt(r.now + 1000));
        assertEquals(State.TIMED_OUT, r.snap.state);
        assertEquals(Why.NOT_READY, r.kw.why());
    }

    // ---- values that are neither lost nor valid (4) ----

    private static VehiclePort.Reading odd(int kind) {
        // kind 0: ready=2. kind 1: setting=0. kind 2: setting=3. Never ready=0 and never setting=1.
        return kind == 0 ? reading(2, 0, 2) : reading(kind == 1 ? 0 : 3, 0, 1);
    }

    @Test public void anUnknownValueForFiftyNineSecondsNineHundredNinetyNineDoesNotDisarmAndAtSixtySecondsItDoes() {
        for (int kind = 0; kind < 3; kind++) {
            Rig r = new Rig().sessionThatEnds();
            long u = T0 + 70000;
            r.quietUntil(u - 1000);
            r.car.reading = odd(kind);
            assertEquals(Decision.NONE, r.tickAt(u));
            assertEquals("kind " + kind, State.ENDED, r.snap.state);    // the state alone shows nothing
            r.quietUntil(u + 59000);
            assertEquals(Decision.NONE, r.tickAt(u + 59999));
            assertTrue("kind " + kind, r.kw.armed());
            assertEquals(Decision.DISARM, r.tickAt(u + 60000));
            assertFalse(r.kw.armed());
            assertEquals("kind " + kind, Why.STATUS_UNKNOWN, r.kw.why());
            assertTrue(r.kw.ended(), r.kw.ended().contains("couldn’t be read"));
        }
    }
    @Test public void aValidReadingInBetweenStartsTheMinuteOver() {
        for (int kind = 0; kind < 3; kind++) {
            Rig r = new Rig().sessionThatEnds();
            long u = T0 + 70000;
            r.quietUntil(u - 1000);
            r.car.reading = odd(kind);
            r.quietUntil(u + 30000);
            r.car.reading = reading(2, 0, 1);                    // one good reading
            assertEquals(Decision.NONE, r.tickAt(u + 31000));
            r.car.reading = odd(kind);
            long again = u + 32000;
            assertEquals(Decision.NONE, r.tickAt(again));
            // The first minute would have run out at u + 60000: it didn't, because of the good reading.
            r.quietUntil(again + 59000);
            assertTrue(r.kw.armed());
            assertEquals(Decision.NONE, r.tickAt(again + 59999));
            assertEquals(Decision.DISARM, r.tickAt(again + 60000));
            assertEquals("kind " + kind, Why.STATUS_UNKNOWN, r.kw.why());
        }
    }
    @Test public void anUnknownValueDuringARunningSessionAlsoCountsAMinute() {
        Rig r = new Rig().armAndStart(120);
        r.quietUntil(T0 + 30000);
        r.car.reading = reading(2, 1, 1);
        r.quietUntil(T0 + 40000);
        r.car.reading = reading(2, 1, 2);   // the controller says UNKNOWN and keeps Stop available
        long u = T0 + 41000;
        assertEquals(Decision.NONE, r.tickAt(u));
        assertEquals(State.UNKNOWN, r.snap.state);
        r.quietUntil(u + 59000);
        assertEquals(Decision.NONE, r.tickAt(u + 59999));
        assertEquals(Decision.DISARM, r.tickAt(u + 60000));
        assertEquals(Why.STATUS_UNKNOWN, r.kw.why());
        assertEquals(0, r.car.stops);   // keep warm lets go; it never stops the driver's session
    }

    // ---- nothing read is neutral (5) ----

    private static PreconditioningSnapshot unread(State state, boolean requested, boolean canStart, int indicator) {
        return new PreconditioningSnapshot(state, requested, canStart, requested, indicator, -1, 0, "", 0, -1, -1);
    }
    @Test public void snapshotsWithNothingReadNeverDisarmByThemselves() {
        PreconditioningSnapshot[] unreadOnes = {
            unread(State.CHECKING, false, false, -1), unread(State.IDLE, false, true, 0), unread(State.ENDED, false, true, 0),
            unread(State.TIMED_OUT, false, true, 0), unread(State.WAITING, true, false, 0), unread(State.ACTIVE, true, false, 1),
            unread(State.STOPPING, true, false, 0), unread(State.WARM, false, true, 0), PreconditioningSnapshot.checking()};
        for (PreconditioningSnapshot s : unreadOnes) {
            KeepWarm kw = new KeepWarm();
            assertTrue(kw.arm(T0, 120, s));
            long samples = T0 + 500;
            for (long t = T0 + 1000; t <= T0 + 600000; t += 1000) {
                while (samples <= t) { kw.sample(samples, WARM_C, 20); samples += 20000; }
                assertEquals(s.state + " at +" + (t - T0), Decision.NONE, kw.tick(t, s, false));
            }
            assertTrue(s.state.name(), kw.armed());
            assertEquals(Why.NONE, kw.why());
        }
    }
    @Test public void aControllerThatNeverReadTheCarStaysArmed() {
        Rig r = new Rig();
        PreconditioningController fresh = new PreconditioningController(r.car, r.journal);
        PreconditioningSnapshot s = fresh.snapshot(T0);
        assertEquals(-1, s.readyRaw); assertEquals(-1, s.enabledRaw);
        assertTrue(r.kw.arm(T0, 30, s));
        for (long t = T0 + 1000; t <= T0 + 240000; t += 1000) {
            while (r.nextSample <= t) { r.kw.sample(r.nextSample, WARM_C, 20); r.nextSample += 20000; }
            assertEquals(Decision.NONE, r.kw.tick(t, fresh.snapshot(t), false));
        }
        assertTrue(r.kw.armed());
    }

    // ---- the cold run is measured between readings (6) ----

    @Test public void startGoesOutOnTheFirstTickAtOrAfterTheSampleThatCompletesThreeMinutesOfColdReadings() {
        // Readings every 20 s from +0.5 s: the 10th one, at +180.5 s, is the first 180 s after the first.
        // Every 25 s: the 9th, at +200.5 s. Every 45 s (the largest gap that still counts): the 5th, at +180.5 s.
        long[][] cases = {{20000, 181000}, {25000, 201000}, {45000, 181000}};
        for (long[] c : cases) {
            Rig r = new Rig();
            r.sampleC = COLD; r.sampleEvery = c[0];
            assertTrue(r.kw.arm(T0, 120, r.snap));
            r.quietUntil(T0 + c[1] - 1000);
            assertEquals("every " + c[0] + " ms", Decision.START, r.tickAt(T0 + c[1]));
            // The service's own steps for this Start: the same ordinary Start as the button, then the report.
            assertTrue(r.c.start(r.now, COLD));
            r.kw.automaticStart(r.now, true);
            assertEquals(1, r.car.starts);
            assertEquals(1, r.kw.automaticStarts());
        }
    }
    @Test public void aSampleOneMillisecondShortOfThreeMinutesDoesNotCompleteTheRunButThreeMinutesExactlyDoes() {
        for (long shortBy : new long[]{1, 0}) {
            Rig r = new Rig();
            r.nextSample = Long.MAX_VALUE;
            assertTrue(r.kw.arm(T0, 120, r.snap));
            long first = T0 + 1000;
            for (int k = 0; k < 6; k++) r.manual.put(first + 30000L * k, COLD);            // +1 s ... +151 s
            long last = first + 180000 - shortBy;
            r.manual.put(last, COLD);                                                       // +181 s, or 1 ms before
            r.quietUntil(T0 + 180000);
            Decision d = r.tickAt(T0 + 181000);
            assertEquals("short by " + shortBy, shortBy == 0 ? Decision.START : Decision.NONE, d);
            if (shortBy == 1) {
                // 179.999 s of readings: nothing, however long the tick waits, until the next reading.
                r.manual.put(last + 30000, COLD);
                r.quietUntil(last + 29000);
                assertEquals(Decision.START, r.tickAt(last + 30001));
            }
        }
    }
    @Test public void readingsEveryTwentySecondsToOneHundredSixtyAndATickAtOneEightyWithoutANewReadingGivesNothing() {
        Rig r = new Rig();
        r.sampleC = COLD;
        r.nextSample = T0;                      // readings at +0, +20, ..., each delivered with the next tick
        assertTrue(r.kw.arm(T0, 120, r.snap));
        r.quietUntil(T0 + 160000);              // the reading at +160 s is in
        r.nextSample = Long.MAX_VALUE;          // and the reader goes quiet
        r.quietUntil(T0 + 180000);              // 180 s since the first cold reading, but only 160 s of readings
        assertEquals(Decision.NONE, r.last);
        r.quietUntil(T0 + 205000);              // the newest reading is exactly 45 s old: still fresh, still not enough
        // The reading at +180 s turns up late: it completes the run, and the next tick sends the Start.
        r.manual.put(T0 + 180000, COLD);
        assertEquals(Decision.START, r.tickAt(T0 + 206000));
    }
    @Test public void aWarmReadingRestartsTheColdRunAndItIsAgainMeasuredBetweenReadings() {
        Rig r = new Rig();
        r.nextSample = Long.MAX_VALUE;
        assertTrue(r.kw.arm(T0, 120, r.snap));
        r.manual.put(T0 + 1000, COLD); r.manual.put(T0 + 21000, COLD); r.manual.put(T0 + 41000, 19);
        for (int k = 0; k < 9; k++) r.manual.put(T0 + 61000 + 20000L * k, COLD);          // +61 s ... +221 s
        // The run restarted at +61 s; +221 s is only 160 s later, and +241 s (3 minutes on the clock) has no reading.
        r.quietUntil(T0 + 250000);
        r.manual.put(T0 + 251000, COLD);
        assertEquals(Decision.START, r.tickAt(T0 + 251000));
    }

    // ---- acknowledge (7) ----

    @Test public void acknowledgeClearsTheReasonOnlyOnceDisarmedAndNeverArmsAgain() {
        for (Why why : Why.values()) {
            if (why == Why.NONE) continue;
            KeepWarm kw = new KeepWarm();
            assertTrue(kw.arm(T0, 60, PreconditioningSnapshot.checking()));
            kw.acknowledge();                                     // armed: nothing to clear
            assertTrue(kw.armed()); assertEquals(Why.NONE, kw.why());
            assertEquals(60, kw.minutes()); assertEquals(60 * 60000L, kw.remainingMs(T0));
            kw.disarm(why);
            assertEquals(why, kw.why()); assertFalse(kw.ended().isEmpty());
            assertEquals(kw.ended(), kw.status(T0 + 1000, 20).ended);
            kw.acknowledge();
            assertFalse(why.name(), kw.armed());
            assertEquals(Why.NONE, kw.why());
            assertEquals("", kw.ended());
            assertEquals("", kw.status(T0 + 1000, 20).ended);
            assertFalse(kw.status(T0 + 1000, 20).armed);
            kw.acknowledge();                                     // again: still nothing, still off
            assertFalse(kw.armed()); assertEquals("", kw.ended());
            assertEquals(Decision.NONE, kw.tick(T0 + 2000, PreconditioningSnapshot.checking(), false));
            assertFalse(kw.armed());
        }
    }
    @Test public void acknowledgeWhileArmedLeavesTheRunAlone() {
        Rig r = new Rig().armAndStart(30);
        r.quietUntil(T0 + 10000);
        r.kw.acknowledge();
        assertTrue(r.kw.armed()); assertEquals(Why.NONE, r.kw.why());
        assertEquals(30, r.kw.minutes());
        assertEquals(30 * 60000L - 10000, r.kw.remainingMs(T0 + 10000));
        assertEquals(0, r.kw.automaticStarts());
        r.quietUntil(T0 + 20000);
        assertTrue(r.kw.armed());
    }
    @Test public void aReasonSeenAndAcknowledgedAfterANotReadyDisarmStaysGoneEvenAsTicksContinue() {
        Rig r = new Rig().sessionThatEnds();
        r.car.reading = reading(2, 0, 0);
        assertEquals(Decision.DISARM, r.tickAt(T0 + 63000));
        assertEquals(Why.NOT_READY, r.kw.why());
        r.kw.acknowledge();
        r.car.reading = reading(2, 0, 0);
        r.quietUntil(T0 + 123000);              // READY is still lost: nothing says it again, nothing arms
        assertFalse(r.kw.armed());
        assertEquals(Why.NONE, r.kw.why());
        assertEquals("", r.kw.ended());
    }
}
