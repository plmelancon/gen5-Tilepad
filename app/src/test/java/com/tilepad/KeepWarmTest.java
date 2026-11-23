package com.tilepad;

import com.tilepad.KeepWarm.Why;
import com.tilepad.PreconditioningSnapshot.State;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeepWarmTest {
    private static final KeepWarm.Decision NONE = KeepWarm.Decision.NONE;
    private static final KeepWarm.Decision START = KeepWarm.Decision.START;
    private static final KeepWarm.Decision DISARM = KeepWarm.Decision.DISARM;
    private static final long T0 = 1000000L;
    private static final int THRESHOLD = 20;
    private static final int COLD = 15;
    private static final int WARM_C = 25;

    private static PreconditioningSnapshot snap(State state, boolean requested, boolean canStart, boolean canStop, int indicator) {
        return new PreconditioningSnapshot(state, requested, canStart, canStop, indicator, -1, 0, "");
    }
    private static PreconditioningSnapshot idle() { return snap(State.IDLE, false, true, false, 0); }
    private static PreconditioningSnapshot idleCannotStart() { return snap(State.IDLE, false, false, false, 0); }
    private static PreconditioningSnapshot waiting() { return snap(State.WAITING, true, false, true, 0); }
    private static PreconditioningSnapshot active() { return snap(State.ACTIVE, true, false, true, 1); }
    private static PreconditioningSnapshot ended() { return snap(State.ENDED, false, true, false, 0); }
    /** The controller still lets Start through in this state, so only the WARM rule can stop it. */
    private static PreconditioningSnapshot warm() { return snap(State.WARM, false, true, false, 0); }
    private static PreconditioningSnapshot error() { return snap(State.ERROR, false, false, false, 0); }
    private static PreconditioningSnapshot unavailable() { return snap(State.UNAVAILABLE, false, false, false, -1); }
    private static PreconditioningSnapshot unknown() { return snap(State.UNKNOWN, false, false, false, -1); }

    private static void assertSentence(String text) {
        assertFalse("empty", text.isEmpty());
        assertTrue(text, text.endsWith("."));
        assertTrue(text, Character.isUpperCase(text.charAt(0)));
        assertTrue(text, text.indexOf(' ') > 0);
    }

    /** Drives KeepWarm the way the service does: a tick about every second, battery samples in between. */
    private static final class Rig {
        final KeepWarm k = new KeepWarm();
        long now = T0;
        PreconditioningSnapshot snap = idle();
        boolean readerStopped;
        int threshold = THRESHOLD;
        KeepWarm.Decision last = NONE;
        long lastEnd;
        private final TreeMap<Long, Integer> pending = new TreeMap<>();

        Rig arm(int minutes) { assertTrue(k.arm(now, minutes, snap)); return this; }
        Rig sample(long at, int minimumC) { pending.put(at, minimumC); return this; }
        Rig samples(long from, long to, long every, int minimumC) {
            for (long t = from; t <= to; t += every) pending.put(t, minimumC);
            return this;
        }
        /** Samples every 30 s from arming for the whole of a two-hour arming. */
        Rig coldAllTheWay() { return samples(T0, T0 + 7200000L, 30000, COLD); }
        KeepWarm.Decision tickNow() {
            while (!pending.isEmpty() && pending.firstKey() <= now) {
                Map.Entry<Long, Integer> e = pending.pollFirstEntry();
                k.sample(e.getKey(), e.getValue(), threshold);
            }
            last = k.tick(now, snap, readerStopped);
            return last;
        }
        KeepWarm.Decision tickAt(long at) { now = at; return tickNow(); }
        /** Ticks every second (less for the last step) up to {@code to}; returns the first decision that wasn't NONE. */
        KeepWarm.Decision run(long to) {
            KeepWarm.Decision first = NONE;
            while (now < to) {
                now = Math.min(now + 1000, to);
                KeepWarm.Decision d = tickNow();
                if (first == NONE && d != NONE) first = d;
            }
            return first;
        }
        KeepWarm.Decision runUntilEvent(long limit) {
            while (now < limit) {
                now += 1000;
                KeepWarm.Decision d = tickNow();
                if (d != NONE) return d;
            }
            return NONE;
        }
        /** A session after a Start was sent: waits, optionally heats, ends. Leaves the snapshot idle. */
        KeepWarm.Decision session(long waitMs, long heatMs) {
            KeepWarm.Decision first = NONE, d;
            snap = waiting(); d = run(now + waitMs); if (first == NONE) first = d;
            if (heatMs > 0) { snap = active(); d = run(now + heatMs); if (first == NONE) first = d; }
            snap = ended(); d = run(now + 1000); if (first == NONE) first = d;
            lastEnd = now;
            snap = idle();
            return first;
        }
        /** Waits for the next START, spends it as an automatic Start, and runs a session when it was sent. */
        KeepWarm.Decision spend(boolean sent, long heatMs) {
            assertEquals(START, runUntilEvent(T0 + 7200000L));
            k.automaticStart(now, sent);
            return sent ? session(10000, heatMs) : NONE;
        }
    }

    // ---- arming ----
    @Test public void aNewKeepWarmIsNotArmedAndSaysNothing() {
        KeepWarm k = new KeepWarm();
        assertFalse(k.armed()); assertEquals(Why.NONE, k.why()); assertEquals("", k.ended());
        assertEquals(NONE, k.tick(T0, idle(), false));
    }
    @Test public void armAcceptsFifteenMinuteStepsToTwoHoursThenThirtyMinuteStepsToSixHours() {
        java.util.List<Integer> ok = new java.util.ArrayList<>();
        for (int m = 15; m <= 120; m += 15) ok.add(m);
        for (int m = 150; m <= 360; m += 30) ok.add(m);
        assertEquals(16, ok.size());
        for (int minutes : ok) {
            KeepWarm k = new KeepWarm();
            assertTrue("" + minutes, k.arm(T0, minutes, idle()));
            assertTrue(k.armed()); assertEquals(minutes, k.minutes());
        }
    }
    @Test public void armRefusesEveryOtherNumberOfMinutes() {
        for (int minutes : new int[]{-30, -15, -1, 0, 1, 14, 16, 29, 31, 44, 46, 59, 61, 89, 91, 119, 121, 135, 165, 195, 345, 359, 361, 375, 390, 1800, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            KeepWarm k = new KeepWarm();
            assertFalse("" + minutes, k.arm(T0, minutes, idle()));
            assertFalse(k.armed()); assertEquals(Why.NONE, k.why());
        }
    }
    @Test public void aRefusedArmLeavesNothingBehind() {
        KeepWarm k = new KeepWarm();
        assertFalse(k.arm(T0, 50, idle()));
        assertEquals(NONE, k.tick(T0 + 1000, idle(), false));
        assertEquals(0, k.remainingMs(T0 + 1000)); assertEquals("", k.ended());
    }
    @Test public void armingTwiceIsRefused() {
        KeepWarm k = new KeepWarm();
        assertTrue(k.arm(T0, 30, idle()));
        assertFalse(k.arm(T0 + 1000, 30, idle()));
        assertFalse(k.arm(T0 + 1000, 60, idle()));
        assertTrue(k.armed());
    }
    @Test public void armingTwiceKeepsTheFirstDurationAndStartTime() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle()); k.arm(T0 + 600000, 120, idle());
        assertEquals(30, k.minutes());
        assertEquals(1800000L - 600000L, k.remainingMs(T0 + 600000));
    }
    @Test public void armingAgainAfterItEndedIsAllowedAndStartsClean() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle()); k.automaticStart(T0 + 1000, true); k.disarm(Why.STOPPED);
        assertTrue(k.arm(T0 + 5000, 60, idle()));
        assertTrue(k.armed()); assertEquals(Why.NONE, k.why()); assertEquals("", k.ended());
        assertEquals(0, k.automaticStarts()); assertEquals(60, k.minutes());
    }
    @Test public void aTickBeforeArmingAndAfterDisarmingIsAlwaysNone() {
        KeepWarm k = new KeepWarm();
        k.sample(T0, COLD, THRESHOLD);
        assertEquals(NONE, k.tick(T0 + 200000, idle(), false));
        assertEquals(NONE, k.tick(T0 + 200000, error(), true));
    }

    // ---- tick gap ----
    @Test public void aTickNineSecondsNineHundredNinetyNineLaterIsFine() {
        Rig r = new Rig().arm(30);
        assertEquals(NONE, r.tickAt(T0 + 9999)); assertTrue(r.k.armed());
    }
    @Test public void aTickExactlyTenSecondsLaterIsFine() {
        Rig r = new Rig().arm(30);
        assertEquals(NONE, r.tickAt(T0 + 10000)); assertTrue(r.k.armed());
    }
    @Test public void aTickTenSecondsAndOneMillisecondLaterDisarmsWithSlept() {
        Rig r = new Rig().arm(30);
        assertEquals(DISARM, r.tickAt(T0 + 10001));
        assertFalse(r.k.armed()); assertEquals(Why.SLEPT, r.k.why());
    }
    @Test public void theGapIsMeasuredFromThePreviousTickNotFromArming() {
        Rig r = new Rig().arm(30);
        assertEquals(NONE, r.tickAt(T0 + 9000));
        assertEquals(NONE, r.tickAt(T0 + 19000));
        assertEquals(NONE, r.tickAt(T0 + 29000));
        assertEquals(DISARM, r.tickAt(T0 + 39001));
        assertEquals(Why.SLEPT, r.k.why());
    }
    @Test public void sleptWinsOverTimeUp() {
        Rig r = new Rig().arm(30);
        assertEquals(DISARM, r.tickAt(T0 + 1800000L + 5000));
        assertEquals(Why.SLEPT, r.k.why());
    }
    @Test public void sleptWinsOverACarError() {
        Rig r = new Rig().arm(30);
        r.snap = error();
        assertEquals(DISARM, r.tickAt(T0 + 10001));
        assertEquals(Why.SLEPT, r.k.why());
    }
    @Test public void aTickThatSleptNeverReturnsStartEvenWhenEverythingElseIsReady() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        assertEquals(NONE, r.run(T0 + 179999));
        assertEquals(DISARM, r.tickAt(T0 + 179999 + 10001));
        assertEquals(Why.SLEPT, r.k.why());
    }

    // ---- time used up ----
    private static void assertTimeUpAt(int minutes) {
        long duration = minutes * 60000L;
        Rig r = new Rig().arm(minutes).samples(T0, T0 + duration + 60000, 30000, WARM_C);
        assertEquals(NONE, r.run(T0 + duration - 1));
        assertTrue(r.k.armed()); assertEquals(1L, r.k.remainingMs(r.now));
        assertEquals(DISARM, r.run(T0 + duration));
        assertFalse(r.k.armed()); assertEquals(Why.TIME_UP, r.k.why());
        assertEquals(0L, r.k.remainingMs(r.now));
    }
    @Test public void thirtyMinutesEndAtExactlyThirtyMinutes() { assertTimeUpAt(30); }
    @Test public void sixtyMinutesEndAtExactlySixtyMinutes() { assertTimeUpAt(60); }
    @Test public void oneTwentyMinutesEndAtExactlyOneTwentyMinutes() { assertTimeUpAt(120); }
    @Test public void timeUpWinsOverACarError() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 1900000, 30000, WARM_C);
        r.run(T0 + 1800000L - 1000);
        r.snap = error();
        assertEquals(DISARM, r.run(T0 + 1800000L));
        assertEquals(Why.TIME_UP, r.k.why());
    }
    @Test public void timeUpWinsOverNoTemperatureOnTheSameTick() {
        // The last sample is exactly five minutes older than the end of the arming.
        Rig r = new Rig().arm(30).samples(T0, T0 + 1500000, 30000, WARM_C);
        assertEquals(NONE, r.run(T0 + 1800000L - 1));
        assertEquals(DISARM, r.run(T0 + 1800000L));
        assertEquals(Why.TIME_UP, r.k.why());
    }

    // ---- car state ----
    @Test public void errorStateDisarmsAtOnceWithCarError() {
        Rig r = new Rig().arm(30).coldAllTheWay();
        r.snap = error();
        assertEquals(DISARM, r.run(T0 + 1000));
        assertEquals(Why.CAR_ERROR, r.k.why());
    }
    @Test public void unavailableStateDisarmsAtOnceWithNotReady() {
        Rig r = new Rig().arm(30).coldAllTheWay();
        r.snap = unavailable();
        assertEquals(DISARM, r.run(T0 + 1000));
        assertEquals(Why.NOT_READY, r.k.why());
    }
    @Test public void statesThatAreNotErrorsKeepItArmed() {
        PreconditioningSnapshot[] harmless = {idle(), idleCannotStart(), waiting(), active(), ended(), warm(),
            snap(State.CHECKING, false, false, false, -1), snap(State.STOPPING, true, false, false, 0), snap(State.TIMED_OUT, false, true, false, 0)};
        for (PreconditioningSnapshot s : harmless) {
            Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, WARM_C);
            r.snap = s;
            r.run(T0 + 120000);
            assertTrue(s.state.name(), r.k.armed());
        }
    }
    @Test public void carErrorWinsOverNoTemperature() {
        Rig r = new Rig().arm(30);
        r.run(T0 + 299000);
        r.snap = error(); r.readerStopped = true;
        assertEquals(DISARM, r.run(T0 + 300000));
        assertEquals(Why.CAR_ERROR, r.k.why());
    }

    // ---- unknown ----
    @Test public void unknownForFiftyNineSecondsNineHundredNinetyNineIsStillFine() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, WARM_C);
        r.run(T0 + 10000);
        r.snap = unknown(); r.run(r.now + 1000);
        long u = r.now;
        assertEquals(NONE, r.run(u + 59999));
        assertTrue(r.k.armed());
    }
    @Test public void unknownForSixtySecondsDisarmsWithStatusUnknown() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, WARM_C);
        r.run(T0 + 10000);
        r.snap = unknown(); r.run(r.now + 1000);
        long u = r.now;
        assertEquals(NONE, r.run(u + 59999));
        assertEquals(DISARM, r.run(u + 60000));
        assertFalse(r.k.armed()); assertEquals(Why.STATUS_UNKNOWN, r.k.why());
    }
    @Test public void oneTickThatIsNotUnknownRestartsTheSixtySeconds() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, WARM_C);
        r.run(T0 + 10000);
        r.snap = unknown(); r.run(r.now + 1000);
        long u = r.now;
        r.run(u + 58000);
        r.snap = idle(); r.run(u + 59000);
        r.snap = unknown();
        assertEquals(NONE, r.run(u + 119999));
        assertTrue(r.k.armed());
        assertEquals(DISARM, r.run(u + 120000));
        assertEquals(Why.STATUS_UNKNOWN, r.k.why());
    }
    @Test public void unknownDoesNotBlockAnythingBeforeTheMinuteIsUp() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, WARM_C);
        r.snap = unknown();
        assertEquals(NONE, r.run(T0 + 30000));
        assertTrue(r.k.armed());
    }

    // ---- no temperature ----
    @Test public void noSampleEverEndsAtExactlyFiveMinutesAfterArming() {
        Rig r = new Rig().arm(30);
        assertEquals(NONE, r.run(T0 + 299999));
        assertEquals(DISARM, r.run(T0 + 300000));
        assertEquals(Why.NO_TEMPERATURE, r.k.why());
    }
    @Test public void theFiveMinutesRunFromTheLastSample() {
        Rig r = new Rig().arm(30).sample(T0 + 60000, WARM_C);
        assertEquals(NONE, r.run(T0 + 359999));
        assertEquals(DISARM, r.run(T0 + 360000));
        assertEquals(Why.NO_TEMPERATURE, r.k.why());
    }
    @Test public void aSampleJustBeforeTheDeadlineRestartsTheFiveMinutes() {
        Rig r = new Rig().arm(30).sample(T0 + 299000, WARM_C);
        assertEquals(NONE, r.run(T0 + 300000));
        assertEquals(NONE, r.run(T0 + 598999));
        assertEquals(DISARM, r.run(T0 + 599000));
        assertEquals(Why.NO_TEMPERATURE, r.k.why());
    }
    @Test public void aStoppedReaderDisarmsAtOnceEvenWithFreshSamples() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 10000, WARM_C);
        r.run(T0 + 20000);
        r.readerStopped = true;
        assertEquals(DISARM, r.run(T0 + 21000));
        assertEquals(Why.NO_TEMPERATURE, r.k.why());
    }
    @Test public void aStoppedReaderOnTheVeryFirstTickDisarms() {
        Rig r = new Rig().arm(30);
        r.readerStopped = true;
        assertEquals(DISARM, r.tickAt(T0 + 1000));
        assertEquals(Why.NO_TEMPERATURE, r.k.why());
    }
    @Test public void samplesTakenBeforeArmingDoNotCountAsReadings() {
        KeepWarm k = new KeepWarm();
        k.sample(T0 - 1000, COLD, THRESHOLD);
        k.arm(T0, 30, idle());
        assertNull(k.minimumC(T0));
        assertEquals(Long.MAX_VALUE, k.nextStartAt(T0));
    }

    // ---- cold run ----
    @Test public void coldRunOfOneMillisecondLessThanThreeMinutesDoesNotStart() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, 18);
        assertEquals(NONE, r.run(T0 + 179999));
    }
    @Test public void coldRunOfExactlyThreeMinutesStarts() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, 18);
        assertEquals(NONE, r.run(T0 + 179999));
        assertEquals(START, r.run(T0 + 180000));
    }
    @Test public void theColdRunIsMeasuredFromTheFirstSampleAfterArmingNotFromArming() {
        Rig r = new Rig().arm(30).samples(T0 + 10000, T0 + 600000, 30000, COLD);
        assertEquals(NONE, r.run(T0 + 189999));
        assertEquals(START, r.run(T0 + 190000));
    }
    @Test public void eighteenIsColdWithThresholdTwenty() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, 18);
        assertEquals(START, r.run(T0 + 180000));
    }
    @Test public void nineteenIsNotColdWithThresholdTwenty() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 1500000, 30000, 19);
        assertEquals(NONE, r.run(T0 + 1000000));
        assertTrue(r.k.armed());
    }
    @Test public void nineteenIsColdWithThresholdTwentyOne() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, 19);
        r.threshold = 21;
        assertEquals(NONE, r.run(T0 + 179999));
        assertEquals(START, r.run(T0 + 180000));
    }
    @Test public void twentyIsNotColdWithThresholdTwentyOne() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 1500000, 30000, 20);
        r.threshold = 21;
        assertEquals(NONE, r.run(T0 + 1000000));
    }
    @Test public void veryColdCountsAsCold() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, -25);
        assertEquals(START, r.run(T0 + 180000));
    }
    @Test public void aWarmerSampleRestartsTheColdRun() {
        Rig r = new Rig().arm(120)
            .samples(T0, T0 + 120000, 30000, COLD).sample(T0 + 150000, 19)
            .samples(T0 + 180000, T0 + 900000, 30000, COLD);
        assertEquals(NONE, r.run(T0 + 359999));
        assertEquals(START, r.run(T0 + 360000));
    }
    @Test public void aSampleOneDegreeOverTheColdLimitIsEnoughToRestartTheRun() {
        Rig r = new Rig().arm(120)
            .samples(T0, T0 + 150000, 30000, 18).sample(T0 + 170000, 19).sample(T0 + 175000, 18)
            .samples(T0 + 205000, T0 + 900000, 30000, 18);
        // The run restarts at the 18 taken at T0+175000.
        assertEquals(NONE, r.run(T0 + 354999));
        assertEquals(START, r.run(T0 + 355000));
    }
    @Test public void aGapOfFortyFiveSecondsBetweenSamplesKeepsTheRun() {
        Rig r = new Rig().arm(30)
            .samples(T0, T0 + 60000, 30000, COLD)
            .samples(T0 + 105000, T0 + 600000, 30000, COLD);
        // The cold run is measured between readings: the first reading after the 45 s gap is at
        // +105 s, and the sample that completes 180 s of cold readings is the one at +195 s.
        assertEquals(NONE, r.run(T0 + 194999));
        assertEquals(START, r.run(T0 + 195000));
    }
    @Test public void aGapOfFortyFiveSecondsAndOneMillisecondRestartsTheRun() {
        Rig r = new Rig().arm(30)
            .samples(T0, T0 + 60000, 30000, COLD)
            .samples(T0 + 105001, T0 + 700000, 30000, COLD);
        assertEquals(NONE, r.run(T0 + 285000));
        assertEquals(START, r.run(T0 + 285001));
    }
    @Test public void samplesFortyFiveSecondsApartThroughoutStillMakeAColdRun() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 45000, COLD);
        assertEquals(NONE, r.run(T0 + 179999));
        assertEquals(START, r.run(T0 + 180000));
    }
    @Test public void samplesFortyFiveSecondsAndOneMillisecondApartNeverMakeAColdRun() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 1700000, 45001, COLD);
        assertEquals(NONE, r.run(T0 + 1500000));
        assertTrue(r.k.armed());
    }
    @Test public void aNewestSampleExactlyFortyFiveSecondsOldStillAllowsStart() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 180000, 30000, COLD);
        r.run(T0 + 224999);
        assertEquals(START, r.last);
        r.run(T0 + 225000);
        assertEquals(START, r.last);
    }
    @Test public void aNewestSampleFortyFiveSecondsAndOneMillisecondOldBlocksStart() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 180000, 30000, COLD);
        r.run(T0 + 225000);
        assertEquals(START, r.last);
        r.run(T0 + 225001);
        assertEquals(NONE, r.last);
    }
    @Test public void aColdRunSurvivesOnlyIfTheNextSampleComesWithinFortyFiveSeconds() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 180000, 30000, COLD).sample(T0 + 225000, COLD);
        r.run(T0 + 225000);
        assertEquals(START, r.last);
        r.run(T0 + 260000);
        assertEquals(START, r.last);
    }
    @Test public void aColdRunDoesNotSurviveASampleThatArrivesAfterTheGap() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 180000, 30000, COLD).samples(T0 + 225001, T0 + 700000, 30000, COLD);
        r.run(T0 + 225001);
        // The first sample after a gap of 45.001 s starts the run over.
        assertEquals(NONE, r.last);
        assertEquals(NONE, r.run(T0 + 225001 + 179999));
        assertEquals(START, r.run(T0 + 225001 + 180000));
    }
    @Test public void samplesTakenBeforeArmingGiveNoHeadStart() {
        Rig r = new Rig();
        for (long t = T0 - 300000; t < T0; t += 30000) r.k.sample(t, COLD, THRESHOLD);
        r.arm(30).samples(T0 + 10000, T0 + 600000, 30000, COLD);
        assertEquals(NONE, r.run(T0 + 189999));
        assertEquals(START, r.run(T0 + 190000));
    }

    // ---- everything at once ----
    @Test public void whenEveryConditionHoldsTheTickReturnsStart() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, COLD);
        assertEquals(START, r.run(T0 + 180000));
        assertTrue(r.k.armed());
    }
    @Test public void startIsStillOfferedOnTheNextTickUntilItIsAnswered() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, COLD);
        r.run(T0 + 180000);
        assertEquals(START, r.tickAt(T0 + 181000));
        assertEquals(0, r.k.automaticStarts());
    }
    @Test public void anEndedSessionStateDoesNotBlockStart() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, COLD);
        r.snap = ended();
        assertEquals(START, r.run(T0 + 180000));
    }
    @Test public void warmStateBlocksStartUntilItGoesAway() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, COLD);
        r.snap = warm();
        assertEquals(NONE, r.run(T0 + 300000));
        r.snap = idle();
        assertEquals(START, r.run(T0 + 301000));
    }
    @Test public void aSnapshotThatCannotStartBlocksStartUntilItCan() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 600000, 30000, COLD);
        r.snap = idleCannotStart();
        assertEquals(NONE, r.run(T0 + 300000));
        r.snap = idle();
        assertEquals(START, r.run(T0 + 301000));
    }
    @Test public void anOpenSessionBlocksStartAndItsEndOnlyReleasesItAfterTheRest() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.snap = waiting();
        assertEquals(NONE, r.run(T0 + 300000));
        r.snap = ended(); r.run(r.now + 1000);
        long end = r.now;
        assertEquals(NONE, r.run(end + 299999));
        assertEquals(START, r.run(end + 300000));
    }
    @Test public void aSessionOpenAtArmingBlocksStartLikeAnyOther() {
        Rig r = new Rig(); r.snap = waiting(); r.arm(120).coldAllTheWay();
        assertEquals(NONE, r.run(T0 + 600000));
    }

    // ---- waits between Starts ----
    private static Rig afterFirstSessionEndingAt(long heatMs) {
        Rig r = new Rig().arm(120).coldAllTheWay();
        assertEquals(START, r.run(T0 + 180000));
        r.k.automaticStart(r.now, true);
        r.session(10000, heatMs);
        return r;
    }
    @Test public void theEightMinuteSpacingAloneBindsWhenTheSessionWasShort() {
        Rig r = afterFirstSessionEndingAt(20000);   // ends 31 s after the Start
        long s1 = T0 + 180000;
        assertEquals(s1 + 480000, r.k.nextStartAt(r.now));
        assertEquals(NONE, r.run(s1 + 479999));
        assertEquals(START, r.run(s1 + 480000));
    }
    @Test public void theFiveMinuteRestAloneBindsWhenTheSessionWasLong() {
        Rig r = afterFirstSessionEndingAt(389000);   // ends 400 s after the Start
        long s1 = T0 + 180000, end = r.lastEnd;
        assertEquals(s1 + 400000, end);
        assertEquals(end + 300000, r.k.nextStartAt(r.now));
        assertEquals(NONE, r.run(end + 299999));
        assertEquals(START, r.run(end + 300000));
    }
    @Test public void aLongSessionPushesTheNextStartPastTheEightMinutes() {
        // Ends 470 s after the Start: spacing is over at +480 s but the rest only at +770 s.
        Rig r = afterFirstSessionEndingAt(459000);
        long s1 = T0 + 180000;
        assertEquals(NONE, r.run(s1 + 769999));
        assertEquals(START, r.run(s1 + 770000));
    }
    @Test public void aFreshColdRunCanBeTheLongestWait() {
        Rig r = new Rig().arm(120)
            .samples(T0, T0 + 240000, 30000, COLD)
            .samples(T0 + 270000, T0 + 1470000, 30000, WARM_C)
            .samples(T0 + 1500000, T0 + 2400000, 30000, COLD);
        assertEquals(START, r.run(T0 + 180000));
        r.k.automaticStart(r.now, true);
        r.session(10000, 20000);
        assertEquals(NONE, r.run(T0 + 1679999));
        assertEquals(START, r.run(T0 + 1680000));
    }

    // ---- the limit of four ----
    @Test public void fourAutomaticStartsThenNoMoreStartButTheLimitOnlyHitsOnceNoSessionIsOpen() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        for (int i = 1; i <= 3; i++) {
            assertEquals(NONE, r.spend(true, 60000));
            assertEquals(i, r.k.automaticStarts());
            assertTrue(r.k.armed());
        }
        assertEquals(START, r.runUntilEvent(T0 + 7200000L));
        r.k.automaticStart(r.now, true);
        assertEquals(4, r.k.automaticStarts());
        r.snap = waiting(); assertEquals(NONE, r.run(r.now + 10000));
        r.snap = active(); assertEquals(NONE, r.run(r.now + 600000));
        assertTrue(r.k.armed());
        r.snap = ended();
        assertEquals(DISARM, r.run(r.now + 1000));
        assertFalse(r.k.armed()); assertEquals(Why.START_LIMIT, r.k.why());
        assertEquals(4, r.k.automaticStarts());
    }
    @Test public void afterTheLimitNothingIsEverOfferedAgain() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        for (int i = 0; i < 4; i++) r.spend(true, 60000);
        assertEquals(Why.START_LIMIT, r.k.why());
        r.snap = idle();
        assertEquals(NONE, r.run(T0 + 7000000));
        assertEquals(4, r.k.automaticStarts());
    }
    @Test public void fourNotSentAutomaticAttemptsAlsoUseUpTheLimit() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        for (int i = 1; i <= 4; i++) {
            assertEquals(START, r.runUntilEvent(T0 + 7200000L));
            r.k.automaticStart(r.now, false);
            assertEquals(i, r.k.automaticStarts());
            assertTrue("attempt " + i, r.k.armed());
        }
        assertEquals(DISARM, r.runUntilEvent(r.now + 3600000L));
        assertEquals(Why.START_LIMIT, r.k.why());
    }
    @Test public void aMixOfSentAndNotSentAttemptsCountsEachOne() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.spend(true, 60000); r.spend(false, 0); r.spend(true, 60000);
        assertEquals(3, r.k.automaticStarts());
        assertTrue(r.k.armed());
        assertEquals(START, r.runUntilEvent(T0 + 7200000L));
        r.k.automaticStart(r.now, false);
        assertEquals(DISARM, r.runUntilEvent(r.now + 3600000L));
        assertEquals(Why.START_LIMIT, r.k.why());
    }
    @Test public void aNotSentAttemptIsCountedButOpensNoSession() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        assertEquals(START, r.run(T0 + 180000));
        r.k.automaticStart(r.now, false);
        r.run(r.now + 1000);
        // No session was opened, so nothing ended and no 5-minute rest applies; only the Start spacing could.
        assertEquals(1, r.k.automaticStarts());
        assertTrue(r.k.armed());
    }
    // An attempt that met the warm notice sent nothing, but it still spaces the next one: keep warm
    // must not retry every second against a battery the pre-check reads as warm.
    @Test public void aNotSentAttemptStillSetsTheEightMinuteSpacing() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        assertEquals(START, r.run(T0 + 180000));
        long attempt = r.now;
        r.k.automaticStart(attempt, false);
        assertEquals(NONE, r.run(attempt + 480000 - 1000));
        assertEquals(START, r.run(attempt + 480000));
    }
    @Test public void noTemperatureWinsOverTheLimit() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        for (int i = 0; i < 4; i++) { assertEquals(START, r.runUntilEvent(T0 + 7200000L)); r.k.automaticStart(r.now, false); }
        r.readerStopped = true;
        assertEquals(DISARM, r.run(r.now + 1000));
        assertEquals(Why.NO_TEMPERATURE, r.k.why());
    }

    // ---- no heat ----
    @Test public void oneSessionWithoutHeatKeepsItArmed() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        assertEquals(START, r.run(T0 + 180000));
        r.k.automaticStart(r.now, true);
        assertEquals(NONE, r.session(30000, 0));
        assertTrue(r.k.armed());
    }
    @Test public void twoSessionsInARowWithoutHeatDisarmWhenTheSecondEnds() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        assertEquals(NONE, r.spend(true, 0));
        assertEquals(START, r.runUntilEvent(T0 + 7200000L));
        r.k.automaticStart(r.now, true);
        r.snap = waiting(); assertEquals(NONE, r.run(r.now + 30000));
        assertTrue(r.k.armed());
        r.snap = ended();
        assertEquals(DISARM, r.run(r.now + 1000));
        assertEquals(Why.NO_HEAT, r.k.why());
    }
    @Test public void aSessionThatHeatedResetsTheNoHeatCount() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        assertEquals(NONE, r.spend(true, 0));        // no heat: 1
        assertEquals(NONE, r.spend(true, 60000));    // heated: back to 0
        assertEquals(NONE, r.spend(true, 0));        // no heat: 1 again
        assertTrue(r.k.armed());
    }
    @Test public void noHeatWinsOverTheStartLimitOnTheFourthSession() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.spend(true, 0); r.spend(true, 60000); r.spend(true, 0);
        assertTrue(r.k.armed());
        assertEquals(DISARM, r.spend(true, 0));
        assertEquals(Why.NO_HEAT, r.k.why());
    }
    @Test public void aSessionIsHeatedAsSoonAsOneTickWasActive() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        assertEquals(START, r.run(T0 + 180000));
        r.k.automaticStart(r.now, true);
        r.snap = waiting(); r.run(r.now + 100000);
        r.snap = active(); r.run(r.now + 1000);
        r.snap = waiting(); r.run(r.now + 100000);
        r.snap = ended(); r.run(r.now + 1000);
        r.snap = idle();
        assertEquals(START, r.runUntilEvent(T0 + 7200000L));
        r.k.automaticStart(r.now, true);
        assertEquals(NONE, r.session(30000, 0));
        assertTrue(r.k.armed());
    }
    @Test public void aSessionNotStartedByKeepWarmIsFollowedFromTheSnapshot() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.snap = waiting(); r.run(T0 + 60000);   // the driver's own session, never heated
        r.snap = ended(); r.run(r.now + 1000);
        r.snap = idle();
        assertEquals(START, r.runUntilEvent(T0 + 7200000L));
        assertEquals(T0 + 61000 + 300000, r.now);
        r.k.automaticStart(r.now, true);
        assertEquals(DISARM, r.session(30000, 0));
        assertEquals(Why.NO_HEAT, r.k.why());
    }

    // ---- arming while a session runs ----
    @Test public void armingDuringAnActiveSessionFollowsItAndCountsItAsHeated() {
        Rig r = new Rig(); r.snap = active(); r.arm(120).coldAllTheWay();
        assertEquals(NONE, r.run(T0 + 60000));
        r.snap = ended(); r.run(r.now + 1000);
        long end = r.now;
        r.snap = idle();
        assertEquals(START, r.runUntilEvent(T0 + 7200000L));
        // The running session counts as the last Start (its elapsed time is 0 here, so it began at
        // arming): 8 minutes from it is later than 5 minutes after its end.
        assertTrue(end + 300000 < T0 + 480000);
        assertEquals(T0 + 480000, r.now);
        r.k.automaticStart(r.now, true);
        assertEquals(NONE, r.session(30000, 0));
        assertTrue("the first session heated, so one idle session is not two in a row", r.k.armed());
    }
    @Test public void armingDuringAnActiveSessionDoesNotCountItAgainstTheLimit() {
        Rig r = new Rig(); r.snap = active(); r.arm(120).coldAllTheWay();
        assertEquals(0, r.k.automaticStarts());
    }
    @Test public void armingDuringAWaitingSessionThatNeverHeatsCountsItAsNoHeat() {
        Rig r = new Rig(); r.snap = waiting(); r.arm(120).coldAllTheWay();
        r.run(T0 + 30000);
        r.snap = ended(); r.run(r.now + 1000);
        r.snap = idle();
        assertEquals(START, r.runUntilEvent(T0 + 7200000L));
        r.k.automaticStart(r.now, true);
        assertEquals(DISARM, r.session(30000, 0));
        assertEquals(Why.NO_HEAT, r.k.why());
    }
    @Test public void aSessionThatBecomesActiveAfterArmingWhileWaitingIsHeated() {
        Rig r = new Rig(); r.snap = waiting(); r.arm(120).coldAllTheWay();
        r.run(T0 + 30000);
        r.snap = active(); r.run(r.now + 5000);
        r.snap = ended(); r.run(r.now + 1000);
        r.snap = idle();
        assertEquals(START, r.runUntilEvent(T0 + 7200000L));
        r.k.automaticStart(r.now, true);
        assertEquals(NONE, r.session(30000, 0));
    }

    // ---- the driver's own Start ----
    @Test public void aManualStartIsNotCountedAsAutomatic() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.run(T0 + 5000);
        r.k.manualStart(r.now, true);
        assertEquals(0, r.k.automaticStarts());
    }
    @Test public void aManualStartThatWasSentSetsTheEightMinuteSpacing() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.run(T0 + 170000);
        long m = r.now;
        r.k.manualStart(m, true);
        r.session(10000, 5000);
        assertEquals(NONE, r.run(m + 479999));
        assertEquals(START, r.run(m + 480000));
        assertEquals(0, r.k.automaticStarts());
    }
    @Test public void aManualStartThatWasNotSentChangesNothing() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.run(T0 + 100000);
        r.k.manualStart(r.now, false);
        assertEquals(NONE, r.run(T0 + 179999));
        assertEquals(START, r.run(T0 + 180000));
        assertEquals(0, r.k.automaticStarts());
    }
    @Test public void aManualStartOpensASessionThatBlocksStart() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.run(T0 + 170000);
        r.k.manualStart(r.now, true);
        r.snap = waiting();
        assertEquals(NONE, r.run(T0 + 900000));
    }
    @Test public void manualStartsNeverUseUpTheLimitOfFour() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        for (int i = 0; i < 6; i++) {
            r.k.manualStart(r.now, true);
            assertEquals(NONE, r.session(5000, 20000));
            r.run(r.now + 60000);
            assertTrue("manual start " + i, r.k.armed());
        }
        assertEquals(0, r.k.automaticStarts());
        assertEquals(START, r.runUntilEvent(T0 + 7200000L));
    }
    @Test public void aManualSessionWithoutHeatCountsTowardNoHeat() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.run(T0 + 10000);
        r.k.manualStart(r.now, true);
        assertEquals(NONE, r.session(30000, 0));
        r.run(r.now + 60000);
        r.k.manualStart(r.now, true);
        assertEquals(DISARM, r.session(30000, 0));
        assertEquals(Why.NO_HEAT, r.k.why());
    }
    @Test public void startsAndSamplesAfterDisarmChangeNothing() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.k.disarm(Why.STOPPED);
        r.k.automaticStart(T0 + 1000, true); r.k.manualStart(T0 + 1000, true); r.k.sample(T0 + 1000, COLD, THRESHOLD);
        assertEquals(0, r.k.automaticStarts());
        assertNull(r.k.minimumC(T0 + 1000));
    }

    // ---- after disarm ----
    @Test public void afterDisarmNothingIsReturnedButNone() {
        Rig r = new Rig().arm(120).coldAllTheWay();
        r.run(T0 + 179000);
        r.k.disarm(Why.STOPPED);
        assertEquals(NONE, r.run(T0 + 1000000));
        assertFalse(r.k.armed());
    }
    @Test public void afterATimeUpNothingIsReturnedButNone() {
        Rig r = new Rig().arm(30).samples(T0, T0 + 3000000, 30000, COLD);
        r.snap = idleCannotStart();
        assertEquals(DISARM, r.run(T0 + 1800000));
        r.snap = idle();
        assertEquals(NONE, r.run(T0 + 2000000));
    }
    @Test public void disarmWithStoppedGivesItsReasonAndASentence() {
        Rig r = new Rig().arm(30);
        r.k.disarm(Why.STOPPED);
        assertFalse(r.k.armed()); assertEquals(Why.STOPPED, r.k.why());
        assertSentence(r.k.ended());
    }
    @Test public void disarmingWhenNotArmedDoesNothing() {
        KeepWarm k = new KeepWarm();
        k.disarm(Why.STOPPED);
        assertEquals(Why.NONE, k.why()); assertEquals("", k.ended());
    }
    @Test public void aSecondDisarmDoesNotChangeTheReason() {
        Rig r = new Rig().arm(30);
        r.k.disarm(Why.STOPPED); r.k.disarm(Why.CAR_ERROR);
        assertEquals(Why.STOPPED, r.k.why());
    }
    @Test public void theEndedTextIsEmptyWhileItRunsAndOnceItEndsItIsASentence() {
        Rig r = new Rig().arm(30);
        assertEquals("", r.k.ended());
        r.k.disarm(Why.STOPPED);
        assertSentence(r.k.ended());
    }
    @Test public void everyReasonHasItsOwnFullSentence() {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Why why : Why.values()) {
            if (why == Why.NONE) continue;
            KeepWarm k = new KeepWarm();
            k.arm(T0, 60, idle()); k.disarm(why);
            assertSentence(k.ended());
            assertTrue(why.name() + " repeats another text", seen.add(k.ended()));
        }
    }
    @Test public void timeUpTextNamesTheChosenMinutes() {
        for (int minutes : new int[]{15, 30, 45}) {
            KeepWarm k = new KeepWarm();
            k.arm(T0, minutes, idle()); k.disarm(Why.TIME_UP);
            assertTrue(k.ended(), k.ended().contains(minutes + " minutes"));
        }
        String[][] hours = {{"60", "after 1 h,"}, {"90", "after 1 h 30,"}, {"120", "after 2 h,"}, {"360", "after 6 h,"}};
        for (String[] h : hours) {
            KeepWarm k = new KeepWarm();
            k.arm(T0, Integer.parseInt(h[0]), idle()); k.disarm(Why.TIME_UP);
            assertSentence(k.ended());
            assertTrue(k.ended(), k.ended().contains(h[1]));
        }
    }
    // ---- custom duration ----
    @Test public void labelSaysMinutesUnderAnHourThenHoursAndMinutes() {
        assertEquals("15 min", KeepWarm.label(15)); assertEquals("45 min", KeepWarm.label(45));
        assertEquals("1 h", KeepWarm.label(60)); assertEquals("1 h 15", KeepWarm.label(75));
        assertEquals("1 h 30", KeepWarm.label(90)); assertEquals("2 h 05", KeepWarm.label(125));
        assertEquals("6 h", KeepWarm.label(360));
    }
    @Test public void stepMovesFifteenMinutesAndStopsAtBothEnds() {
        assertEquals(75, KeepWarm.step(60, 1)); assertEquals(45, KeepWarm.step(60, -1));
        assertEquals(15, KeepWarm.step(15, -1)); assertEquals(30, KeepWarm.step(15, 1));
        assertEquals(120, KeepWarm.step(105, 1)); assertEquals(150, KeepWarm.step(120, 1));
        assertEquals(120, KeepWarm.step(150, -1)); assertEquals(105, KeepWarm.step(120, -1));
        assertEquals(360, KeepWarm.step(360, 1)); assertEquals(330, KeepWarm.step(360, -1));
        assertEquals(360, KeepWarm.step(330, 1)); assertEquals(60, KeepWarm.step(60, 0));
        // From the default, 1 h to 6 h is 12 presses of +.
        int p = KeepWarm.DEFAULT_MINUTES, presses = 0;
        while (p < KeepWarm.MAX_MINUTES) { p = KeepWarm.step(p, 1); presses++; }
        assertEquals(12, presses);
        assertEquals(75, KeepWarm.step(60, 1000)); assertEquals(45, KeepWarm.step(60, -1000));
        // Every value reachable from the default by steps is one arm() accepts.
        for (int m = KeepWarm.DEFAULT_MINUTES, i = 0; i < 40; i++, m = KeepWarm.step(m, 1)) assertTrue("" + m, KeepWarm.validMinutes(m));
        for (int m = KeepWarm.DEFAULT_MINUTES, i = 0; i < 40; i++, m = KeepWarm.step(m, -1)) assertTrue("" + m, KeepWarm.validMinutes(m));
        assertTrue(KeepWarm.validMinutes(KeepWarm.DEFAULT_MINUTES));
    }
    @Test public void theStartLimitIsFourUpToTwoHoursThenTwoPerHour() {
        int[][] cases = {{15, 4}, {30, 4}, {60, 4}, {120, 4}, {150, 5}, {180, 6}, {240, 8}, {300, 10}, {360, 12}};
        for (int[] c : cases) {
            assertEquals("" + c[0], c[1], KeepWarm.startLimitFor(c[0]));
            KeepWarm k = new KeepWarm();
            assertTrue(k.arm(T0, c[0], idle()));
            assertEquals("" + c[0], c[1], k.startLimit());
        }
    }
    @Test public void timeLeftSaysHoursFromAnHourUp() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 240, idle());
        assertEquals("Keep warm ends in 4 h.", k.timeLeft(T0));
        assertEquals("Keep warm ends in 3 h 30.", k.timeLeft(T0 + 1800000));
        assertEquals("Keep warm ends in 1 h.", k.timeLeft(T0 + 3 * 3600000L));
        assertEquals("Keep warm ends in 59 minutes.", k.timeLeft(T0 + 3 * 3600000L + 60000));
    }
    @Test public void startLimitTextNamesTheFour() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle()); k.disarm(Why.START_LIMIT);
        assertTrue(k.ended(), k.ended().contains("4 times"));
    }
    @Test public void startLimitTextNamesTheScaledCount() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 240, idle()); k.disarm(Why.START_LIMIT);
        assertTrue(k.ended(), k.ended().contains("8 times"));
    }
    @Test public void eightAutomaticStartsInFourHoursThenTheLimit() {
        Rig r = new Rig().arm(240).samples(T0, T0 + 4 * 3600000L, 30000, COLD);
        for (int i = 1; i <= 8; i++) {
            assertEquals("attempt " + i, START, r.runUntilEvent(T0 + 4 * 3600000L));
            r.k.automaticStart(r.now, false);
            assertEquals(i, r.k.automaticStarts());
            assertTrue("attempt " + i, r.k.armed());
        }
        assertEquals(DISARM, r.runUntilEvent(r.now + 3600000L));
        assertEquals(Why.START_LIMIT, r.k.why());
        assertEquals(8, r.k.automaticStarts());
    }
    @Test public void sixHoursRunsUntilTheTimeIsUpWhenTheCarHeatsLong() {
        // Twelve automatic Starts are allowed; with 40 min heats and the 5 min rest fewer fit, and time runs out first.
        Rig r = new Rig().arm(360).samples(T0, T0 + 6 * 3600000L + 60000, 30000, COLD);
        int starts = 0;
        while (r.k.armed()) {
            KeepWarm.Decision d = r.runUntilEvent(T0 + 6 * 3600000L + 2000);
            if (d != START) break;
            r.k.automaticStart(r.now, true); starts++;
            r.snap = waiting(); r.run(r.now + 10000);
            r.snap = active(); r.run(r.now + 40 * 60000L);
            r.snap = ended(); r.run(r.now + 1000);
            r.snap = idle();
        }
        assertFalse(r.k.armed());
        assertEquals(Why.TIME_UP, r.k.why());
        assertTrue("starts " + starts, starts >= 7 && starts <= 8);
    }
    @Test public void twelveAutomaticStartsInSixHoursThenTheLimit() {
        Rig r = new Rig().arm(360).samples(T0, T0 + 6 * 3600000L, 30000, COLD);
        for (int i = 1; i <= 12; i++) {
            assertEquals("attempt " + i, START, r.runUntilEvent(T0 + 6 * 3600000L));
            r.k.automaticStart(r.now, false);
        }
        assertEquals(Long.MAX_VALUE, r.k.nextStartAt(r.now));
        String waiting = r.k.waiting(r.now, THRESHOLD);
        assertTrue(waiting, waiting.contains("12 times"));
        assertEquals(DISARM, r.runUntilEvent(r.now + 3600000L));
        assertEquals(Why.START_LIMIT, r.k.why());
        assertTrue(r.k.ended(), r.k.ended().contains("12 times"));
    }
    @Test public void rearmingForAShorterTimeBringsTheLimitBackToFour() {
        KeepWarm k = new KeepWarm();
        assertEquals(4, k.startLimit());
        k.arm(T0, 360, idle()); assertEquals(12, k.startLimit());
        k.disarm(Why.STOPPED);
        k.arm(T0 + 1000, 30, idle()); assertEquals(4, k.startLimit());
    }
    @Test public void labelAtTheMinuteEdges() {
        assertEquals("59 min", KeepWarm.label(59)); assertEquals("1 h 01", KeepWarm.label(61));
        assertEquals("1 h 59", KeepWarm.label(119)); assertEquals("5 h 59", KeepWarm.label(359));
    }
    @Test public void thePickerStatesOnlyWhatCanFit() {
        assertEquals(2, KeepWarm.mostAutomaticStarts(15));
        assertEquals(4, KeepWarm.mostAutomaticStarts(30));
        assertEquals(4, KeepWarm.mostAutomaticStarts(45));
        assertEquals(4, KeepWarm.mostAutomaticStarts(120));
        assertEquals(12, KeepWarm.mostAutomaticStarts(360));
    }
    @Test public void aSessionThatEndedWhileDisarmedStillImposesTheRest() {
        Rig r = new Rig().arm(30).coldAllTheWay();
        assertEquals(START, r.runUntilEvent(T0 + 1800000L));
        r.k.automaticStart(r.now, true);
        // Long enough that the 8-minute spacing from that Start is spent before re-arming.
        r.snap = active(); r.run(r.now + 600000);
        r.k.disarm(Why.TIME_UP);            // keep warm ends while its session runs
        r.snap = ended(); r.run(r.now + 1000); r.snap = idle();
        long armedAgain = r.now + 1000;
        r.now = armedAgain; assertTrue(r.k.arm(r.now, 60, r.snap));
        // Cold all along: without the rest a Start would come after the 3-minute cold run.
        assertEquals(NONE, r.run(armedAgain + KeepWarm.REST_MS - 1000));
        assertEquals(START, r.run(armedAgain + KeepWarm.REST_MS));
    }
    @Test public void noTemperatureTextNamesTheFiveMinutes() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle()); k.disarm(Why.NO_TEMPERATURE);
        assertTrue(k.ended(), k.ended().contains("5 minutes"));
    }
    @Test public void statusAfterDisarmShowsOffWithTheReason() {
        Rig r = new Rig().arm(30);
        r.k.disarm(Why.CAR_ERROR);
        KeepWarm.Status s = r.k.status(T0 + 5000, THRESHOLD);
        assertFalse(s.armed); assertEquals(0, s.minutes); assertEquals(0L, s.remainingMs);
        assertEquals("", s.waiting); assertEquals("", s.timeLeft);
        assertEquals(r.k.ended(), s.ended); assertSentence(s.ended);
    }
    @Test public void statusOfAKeepWarmNeverArmedIsEmpty() {
        KeepWarm.Status s = new KeepWarm().status(T0, THRESHOLD);
        assertFalse(s.armed); assertEquals("", s.ended); assertEquals("", s.waiting); assertEquals("", s.timeLeft);
    }
    @Test public void statusWhileArmedCarriesTheLiveTexts() {
        Rig r = new Rig().arm(60);
        r.k.sample(T0 + 60000, COLD, THRESHOLD);
        KeepWarm.Status s = r.k.status(T0 + 60000, THRESHOLD);
        assertTrue(s.armed); assertEquals(60, s.minutes);
        assertEquals(3600000L - 60000L, s.remainingMs);
        assertEquals(r.k.waiting(T0 + 60000, THRESHOLD), s.waiting);
        assertEquals(r.k.timeLeft(T0 + 60000), s.timeLeft);
        assertEquals("", s.ended);
    }
    @Test public void offStatusConstantIsEmpty() {
        assertFalse(KeepWarm.Status.OFF.armed); assertEquals("", KeepWarm.Status.OFF.ended);
    }

    // ---- readings: minimumC, nextStartAt, remainingMs ----
    @Test public void minimumIsNullBeforeAnySampleAndWhenNotArmed() {
        KeepWarm k = new KeepWarm();
        assertNull(k.minimumC(T0));
        k.arm(T0, 30, idle());
        assertNull(k.minimumC(T0));
    }
    @Test public void minimumIsTheNewestSampleForFortyFiveSeconds() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle());
        k.sample(T0 + 1000, 17, THRESHOLD); k.sample(T0 + 20000, 16, THRESHOLD);
        assertEquals(Integer.valueOf(16), k.minimumC(T0 + 20000));
        assertEquals(Integer.valueOf(16), k.minimumC(T0 + 20000 + 45000));
        assertNull(k.minimumC(T0 + 20000 + 45001));
    }
    @Test public void minimumIsNullAfterDisarm() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle()); k.sample(T0 + 1000, 17, THRESHOLD); k.disarm(Why.STOPPED);
        assertNull(k.minimumC(T0 + 2000));
    }
    @Test public void nextStartAtIsNeverWhenNotArmedOrWithoutASample() {
        KeepWarm k = new KeepWarm();
        assertEquals(Long.MAX_VALUE, k.nextStartAt(T0));
        k.arm(T0, 30, idle());
        assertEquals(Long.MAX_VALUE, k.nextStartAt(T0));
    }
    @Test public void nextStartAtIsNeverWhileTheLastSampleWasWarm() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle());
        k.sample(T0, COLD, THRESHOLD); k.sample(T0 + 30000, 19, THRESHOLD);
        assertEquals(Long.MAX_VALUE, k.nextStartAt(T0 + 30000));
    }
    @Test public void nextStartAtIsThreeMinutesAfterTheFirstColdSample() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle());
        k.sample(T0 + 5000, COLD, THRESHOLD); k.sample(T0 + 35000, COLD, THRESHOLD);
        assertEquals(T0 + 5000 + 180000, k.nextStartAt(T0 + 35000));
    }
    @Test public void nextStartAtIsNeverOnceTheNewestSampleIsStale() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle());
        k.sample(T0, COLD, THRESHOLD);
        assertEquals(T0 + 180000, k.nextStartAt(T0 + 45000));
        assertEquals(Long.MAX_VALUE, k.nextStartAt(T0 + 45001));
    }
    @Test public void nextStartAtIsNeverAfterFourAutomaticStarts() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 120, idle());
        k.sample(T0, COLD, THRESHOLD);
        for (int i = 0; i < 4; i++) k.automaticStart(T0 + i * 1000, false);
        assertEquals(Long.MAX_VALUE, k.nextStartAt(T0 + 10000));
    }
    @Test public void remainingCountsDownFromTheDurationAndNeverGoesNegative() {
        KeepWarm k = new KeepWarm();
        assertEquals(0L, k.remainingMs(T0));
        k.arm(T0, 30, idle());
        assertEquals(1800000L, k.remainingMs(T0));
        assertEquals(1799000L, k.remainingMs(T0 + 1000));
        assertEquals(1L, k.remainingMs(T0 + 1799999));
        assertEquals(0L, k.remainingMs(T0 + 1800000));
        assertEquals(0L, k.remainingMs(T0 + 5000000));
    }

    // ---- texts ----
    @Test public void timeLeftRoundsUpToWholeMinutes() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle());
        assertEquals("Keep warm ends in 30 minutes.", k.timeLeft(T0));
        assertEquals("Keep warm ends in 30 minutes.", k.timeLeft(T0 + 1));
        assertEquals("Keep warm ends in 30 minutes.", k.timeLeft(T0 + 59999));
        assertEquals("Keep warm ends in 29 minutes.", k.timeLeft(T0 + 60000));
        assertEquals("Keep warm ends in 2 minutes.", k.timeLeft(T0 + 1800000 - 60001));
        assertEquals("Keep warm ends in 1 minute.", k.timeLeft(T0 + 1800000 - 60000));
        assertEquals("Keep warm ends in 1 minute.", k.timeLeft(T0 + 1800000 - 1));
        assertEquals("Keep warm ends in 1 minute.", k.timeLeft(T0 + 1800000));
    }
    @Test public void timeLeftNamesTheLongerDurations() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 120, idle());
        assertEquals("Keep warm ends in 2 h.", k.timeLeft(T0));
        assertEquals("Keep warm ends in 1 h 59.", k.timeLeft(T0 + 60000));
    }
    @Test public void waitingWithoutAReadingAsksForOne() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle());
        String text = k.waiting(T0, THRESHOLD);
        assertSentence(text);
        assertTrue(text, text.toLowerCase().contains("temperature"));
    }
    @Test public void waitingWithAStaleReadingAsksForANewOne() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle()); k.sample(T0, COLD, THRESHOLD);
        assertTrue(k.waiting(T0 + 45000, THRESHOLD).contains("15"));
        assertEquals(waitingWithoutAReading(), k.waiting(T0 + 45001, THRESHOLD));
    }
    private static String waitingWithoutAReading() {
        KeepWarm k = new KeepWarm(); k.arm(T0, 30, idle());
        return k.waiting(T0, THRESHOLD);
    }
    @Test public void waitingWhileWarmNamesTheTemperatureAndTheColdLimit() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle()); k.sample(T0, 22, THRESHOLD);
        String text = k.waiting(T0, THRESHOLD);
        assertSentence(text);
        assertTrue(text, text.contains("22")); assertTrue(text, text.contains("18")); assertTrue(text, text.contains("3 minutes"));
    }
    @Test public void waitingColdLimitFollowsTheThreshold() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle()); k.sample(T0, 22, 21);
        String text = k.waiting(T0, 21);
        assertTrue(text, text.contains("19")); assertTrue(text, text.contains("22"));
    }
    @Test public void waitingCountsTheMinutesUpToTheNextStart() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle());
        for (long t = 0; t <= 120000; t += 30000) k.sample(T0 + t, COLD, THRESHOLD);
        String text = k.waiting(T0 + 120000, THRESHOLD);   // 60 s to go
        assertSentence(text); assertTrue(text, text.contains("15")); assertTrue(text, text.contains("about 1 minute."));
    }
    @Test public void waitingRoundsUpWhenTheNextStartIsAMillisecondOverTheMinute() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle());
        for (long t = 0; t <= 90000; t += 30000) k.sample(T0 + t, COLD, THRESHOLD);
        String text = k.waiting(T0 + 119999, THRESHOLD);   // 60.001 s to go
        assertTrue(text, text.contains("about 2 minutes."));
    }
    @Test public void waitingAtTheStartOfAColdRunSaysThreeMinutes() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle()); k.sample(T0, COLD, THRESHOLD);
        assertTrue(k.waiting(T0, THRESHOLD), k.waiting(T0, THRESHOLD).contains("about 3 minutes."));
    }
    @Test public void waitingWhenTheStartIsDueSaysAboutOneMinute() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 30, idle());
        for (long t = 0; t <= 180000; t += 30000) k.sample(T0 + t, COLD, THRESHOLD);
        String text = k.waiting(T0 + 180000, THRESHOLD);
        assertSentence(text); assertTrue(text, text.contains("about 1 minute."));
    }
    @Test public void waitingAfterTheLimitSaysItWontAskAgain() {
        KeepWarm k = new KeepWarm();
        k.arm(T0, 120, idle()); k.sample(T0, COLD, THRESHOLD);
        for (int i = 0; i < 4; i++) k.automaticStart(T0 + 1000 * i, false);
        String text = k.waiting(T0 + 10000, THRESHOLD);
        assertSentence(text);
        assertTrue(text, text.contains("4 times")); assertTrue(text, text.contains("15")); assertTrue(text, text.contains("again"));
    }
}
