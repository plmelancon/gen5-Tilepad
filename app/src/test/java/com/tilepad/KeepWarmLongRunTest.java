package com.tilepad;

import com.tilepad.KeepWarm.Decision;
import com.tilepad.KeepWarm.Why;
import com.tilepad.PreconditioningSnapshot.State;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Many deterministic random three-hour days, driving the real controller and KeepWarm together
 * the way the service does every tick. Every tick is checked against the rules, not against what
 * the code happens to do.
 */
public class KeepWarmLongRunTest {
    private static final int SEEDS = 200;
    private static final int TICKS = 7 * 3600;
    private static final int THRESHOLD = 20;
    private static final long BASE = 1000000L;
    private static final long NEVER = Long.MAX_VALUE;
    /** Every duration the picker offers. */
    private static final int[] DURATIONS = {15, 30, 45, 60, 75, 90, 105, 120, 150, 180, 210, 240, 270, 300, 330, 360};

    /** What the whole batch of runs did, so the test cannot pass by never exercising anything. */
    private static final class Totals {
        int autoSent, autoAttempts, manualSent, armings, jumps, userStops;
        final Map<Why, Integer> reasons = new EnumMap<>(Why.class);
        void reason(Why why) { Integer n = reasons.get(why); reasons.put(why, n == null ? 1 : n + 1); }
        int count(Why why) { Integer n = reasons.get(why); return n == null ? 0 : n; }
        @Override public String toString() {
            return "autoSent=" + autoSent + " attempts=" + autoAttempts + " manual=" + manualSent + " armings=" + armings
                + " jumps=" + jumps + " userStops=" + userStops + " reasons=" + reasons;
        }
    }

    private static final class Run {
        final int seed, chaos;
        final Random rnd;
        final Totals totals;
        final PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        final PreconditioningControllerTest.Journal journal = new PreconditioningControllerTest.Journal();
        final PreconditioningController controller = new PreconditioningController(car, journal);
        final KeepWarm kw = new KeepWarm();
        long now = BASE;

        // The simulated car.
        boolean on;
        long onAt = NEVER, offAt = NEVER;
        int seenStarts, seenStops;
        long readFailUntil, notReadyUntil, disabledUntil, outageUntil, readerStoppedUntil, oddUntil;
        int oddKind;
        int temp;
        long nextSampleAt;
        long lastSampleAt = -1; int lastSampleMin;

        // What the test remembers about the arming, independently of KeepWarm.
        long armedAt, durationMs, prevTick, rearmAt;
        final List<long[]> history = new ArrayList<>();   // {at, minimumC} since arming
        int attempts, autoSentThisArm, limit = KeepWarm.MAX_AUTOMATIC_STARTS;
        long lastSentStart = -1, lastSessionEnd = -1, unknownSince = -1;
        boolean sessionOpen, sawActive;
        int noHeat;
        int manualSent, autoSent, armings;
        boolean frozen; int frozenStarts;

        Run(int seed, Totals totals) {
            this.seed = seed; this.chaos = seed % 3; this.totals = totals;
            this.rnd = new Random(1000L + seed);
            temp = 10 + rnd.nextInt(15);
            rearmAt = BASE + 1000L * rnd.nextInt(200);
        }

        void check(boolean condition, String message) {
            if (!condition) fail("seed " + seed + " at +" + (now - BASE) / 1000 + " s: " + message);
        }

        Integer freshLast() { return lastSampleAt >= 0 && now - lastSampleAt <= 45000 ? Integer.valueOf(lastSampleMin) : null; }

        void go() {
            car.reading = new VehiclePort.Reading(2, 0, 1, -1);
            controller.refresh(now);
            for (int i = 0; i < TICKS; i++) {
                boolean jump = chaos > 0 && rnd.nextInt(chaos == 1 ? 3000 : 1000) == 0;
                now += jump ? 30000 : 1000;
                if (jump) totals.jumps++;
                world();
                if (!kw.armed() && rearmAt >= 0 && now >= rearmAt) arm();
                boolean wasArmed = kw.armed();
                controller.refresh(now);
                PreconditioningSnapshot snap = controller.snapshot(now);
                boolean readerStopped = now < readerStoppedUntil;
                long gap = now - prevTick;
                boolean timeUp = wasArmed && now - armedAt >= durationMs;
                if (wasArmed) trackSession(snap);
                int startsBefore = car.starts;
                Decision d = kw.tick(now, snap, readerStopped);
                check(car.starts == startsBefore, "tick itself must never send anything");
                if (wasArmed) prevTick = now;
                checkDecision(wasArmed, d, snap, gap, timeUp, readerStopped);
                if (d == Decision.START) autoStart(snap, gap);
                check(kw.automaticStarts() == attempts, "automaticStarts " + kw.automaticStarts() + " but " + attempts + " attempts");
                if (!kw.armed()) {
                    if (wasArmed) { frozen = true; frozenStarts = car.starts; totals.reason(kw.why()); rearmAt = rnd.nextInt(10) < 7 ? now + 5000 + rnd.nextInt(600000) : -1; }
                    check(car.starts == frozenStarts, "a Start was sent while disarmed (" + car.starts + " vs " + frozenStarts + ")");
                } else {
                    userActions(snap);
                }
                check(car.starts == manualSent + autoSent, "starts " + car.starts + " != manual " + manualSent + " + auto " + autoSent);
            }
            recoveryNeverStarts();
            totals.autoSent += autoSent; totals.manualSent += manualSent; totals.armings += armings;
        }

        void arm() {
            int minutes = DURATIONS[rnd.nextInt(DURATIONS.length)];
            limit = KeepWarm.startLimitFor(minutes);
            PreconditioningSnapshot snap = controller.snapshot(now);
            check(!kw.arm(now, 50, snap), "50 minutes must be refused");
            check(!kw.armed(), "refused arm armed it");
            check(kw.arm(now, minutes, snap), "arm refused");
            check(!kw.arm(now, minutes, snap), "second arm accepted");
            armings++; armedAt = now; durationMs = minutes * 60000L; prevTick = now;
            history.clear(); attempts = 0; autoSentThisArm = 0;
            lastSentStart = -1; lastSessionEnd = -1; unknownSince = -1;
            sessionOpen = snap.requested; sawActive = snap.requested && snap.state == State.ACTIVE; noHeat = 0;
            frozen = false; rearmAt = -1;
            if (rnd.nextInt(5) < 2 && snap.canStart) manualStart();
        }

        void manualStart() {
            long before = car.starts;
            boolean sent = controller.start(now, freshLast());
            check(sent == car.starts > before, "start() said " + sent + " but the car saw " + (car.starts - before));
            if (car.starts > before) { manualSent++; sentStartChecks(); lastSentStart = now; sessionOpen = true; sawActive = false; }
            kw.manualStart(now, sent);
        }

        void sentStartChecks() {
            check(!car.readFails, "Start sent while the read was failing");
            check(car.reading.ready == 1 && car.reading.enabled == 2 && car.reading.indicator == 0,
                "Start sent with ready=" + car.reading.ready + " enabled=" + car.reading.enabled + " indicator=" + car.reading.indicator);
        }

        void world() {
            if (chaos > 0) {
                int m = chaos == 1 ? 1 : 3;
                if (rnd.nextInt(6000 / m) == 0) notReadyUntil = now + 5000 + rnd.nextInt(60000);
                if (rnd.nextInt(6000 / m) == 0) disabledUntil = now + 5000 + rnd.nextInt(60000);
                if (rnd.nextInt(4000 / m) == 0) readFailUntil = now + 3000 + rnd.nextInt(90000);
                if (rnd.nextInt(3000 / m) == 0) outageUntil = now + 20000 + rnd.nextInt(400000);
                if (rnd.nextInt(8000 / m) == 0) readerStoppedUntil = now + 5000 + rnd.nextInt(30000);
                // Values the car should never send: neither ready=0/1 nor setting=1/2.
                if (rnd.nextInt(7000 / m) == 0) { oddUntil = now + 5000 + rnd.nextInt(90000); oddKind = rnd.nextInt(3); }
            }
            if (car.starts > seenStarts) {
                seenStarts = car.starts;
                onAt = rnd.nextInt(4) == 0 ? NEVER : now + 1000L * (1 + rnd.nextInt(120));
                offAt = NEVER;
            }
            if (car.stops > seenStops) {
                seenStops = car.stops;
                onAt = NEVER;
                if (on) offAt = Math.min(offAt, now + 1000L * rnd.nextInt(4));
            }
            if (!on && now >= onAt) { on = true; onAt = NEVER; offAt = now + 1000L * (20 + rnd.nextInt(900)); }
            if (on && now >= offAt) { on = false; offAt = NEVER; }
            car.readFails = now < readFailUntil;
            int setting = now < disabledUntil ? 1 : 2, ready = now < notReadyUntil ? 0 : 1;
            if (now < oddUntil) { if (oddKind == 0) ready = 2; else setting = oddKind == 1 ? 3 : 0; }
            car.reading = new VehiclePort.Reading(setting, on ? 1 : 0, ready, -1);

            if (now >= nextSampleAt) {
                nextSampleAt = now + 20000;
                temp += rnd.nextInt(5) - 2 + (on ? 1 : 0);
                temp = Math.max(10, Math.min(24, temp));
                boolean missing = now < outageUntil || rnd.nextInt(10) == 0;
                if (!missing) {
                    if (kw.armed()) history.add(new long[]{now, temp});
                    kw.sample(now, temp, THRESHOLD);
                    lastSampleAt = now; lastSampleMin = temp;
                }
            }
        }

        /** Mirrors how a session is seen from the snapshots, without looking at KeepWarm. */
        void trackSession(PreconditioningSnapshot snap) {
            if (snap.requested) {
                if (!sessionOpen) { sessionOpen = true; sawActive = false; }
                if (snap.state == State.ACTIVE) sawActive = true;
            } else if (sessionOpen) {
                sessionOpen = false; lastSessionEnd = now;
                noHeat = sawActive ? 0 : noHeat + 1;
            }
        }

        /** What the car answered this tick: -1 for a value that wasn't read. */
        int readyRaw() { return car.readFails ? -1 : car.reading.ready; }
        int settingRaw() { return car.readFails ? -1 : car.reading.enabled; }

        /** Three minutes of readings, measured from the first cold reading to the newest one, no gap over 45 s. */
        boolean coldRun() {
            int n = history.size();
            if (n == 0 || now - history.get(n - 1)[0] > 45000) return false;
            long runStart = -1, prevAt = -1;
            for (int i = n - 1; i >= 0; i--) {
                long at = history.get(i)[0];
                if (history.get(i)[1] > THRESHOLD - 2) break;
                if (prevAt != -1 && prevAt - at > 45000) break;
                runStart = at; prevAt = at;
            }
            return runStart != -1 && history.get(n - 1)[0] - runStart >= 180000;
        }

        void checkDecision(boolean wasArmed, Decision d, PreconditioningSnapshot snap, long gap, boolean timeUp, boolean readerStopped) {
            if (!wasArmed) { check(d == Decision.NONE, "decision " + d + " while not armed"); return; }
            boolean slept = gap > 10000;
            if (slept) {
                check(d == Decision.DISARM && kw.why() == Why.SLEPT && !kw.armed(), "gap " + gap + " ms must disarm with SLEPT, got " + d + "/" + kw.why());
                return;
            }
            if (timeUp) {
                check(d == Decision.DISARM && kw.why() == Why.TIME_UP && !kw.armed(), "duration over must disarm with TIME_UP, got " + d + "/" + kw.why());
                return;
            }
            // The raw values, as the car answered them, must agree with what the snapshot carries.
            check(snap.readyRaw == readyRaw() && snap.enabledRaw == settingRaw(), "snapshot raw " + snap.readyRaw + "/" + snap.enabledRaw + " but the car said " + readyRaw() + "/" + settingRaw());
            boolean anyRead = readyRaw() != -1 || settingRaw() != -1;
            boolean oddValue = anyRead && (readyRaw() != 1 || settingRaw() != 2);
            boolean unknown = snap.state == State.UNKNOWN || oddValue;
            if (unknown) { if (unknownSince < 0) unknownSince = now; } else unknownSince = -1;
            if (snap.state == State.ERROR) { check(d == Decision.DISARM && kw.why() == Why.CAR_ERROR, "ERROR must give CAR_ERROR, got " + d + "/" + kw.why()); return; }
            // Lost READY or the setting switched off: the car's values decide, whatever the state says
            // (after a session the state stays ENDED / TIMED_OUT).
            if (snap.state == State.UNAVAILABLE || readyRaw() == 0 || settingRaw() == 1) {
                check(d == Decision.DISARM && kw.why() == Why.NOT_READY, "state " + snap.state + " ready=" + readyRaw() + " setting=" + settingRaw() + " must give NOT_READY, got " + d + "/" + kw.why());
                return;
            }
            if (unknown && now - unknownSince >= 60000) {
                check(d == Decision.DISARM && kw.why() == Why.STATUS_UNKNOWN, "unknown for " + (now - unknownSince) + " ms must give STATUS_UNKNOWN, got " + d + "/" + kw.why());
                return;
            }
            long lastSample = history.isEmpty() ? armedAt : Math.max(armedAt, history.get(history.size() - 1)[0]);
            if (readerStopped || now - lastSample >= 300000) {
                check(d == Decision.DISARM && kw.why() == Why.NO_TEMPERATURE, "no temperature for " + (now - lastSample) + " ms (stopped=" + readerStopped + ") must give NO_TEMPERATURE, got " + d + "/" + kw.why());
                return;
            }
            if (noHeat >= 2) {
                check(d == Decision.DISARM && kw.why() == Why.NO_HEAT, "two sessions without heat must give NO_HEAT, got " + d + "/" + kw.why());
            } else if (attempts >= limit && !sessionOpen) {
                check(d == Decision.DISARM && kw.why() == Why.START_LIMIT, "four Starts used and no session open must give START_LIMIT, got " + d + "/" + kw.why());
            } else {
                check(d != Decision.DISARM, "unexpected DISARM " + kw.why() + " on a healthy tick");
                check(kw.armed(), "still armed expected after " + d);
                check(kw.why() == Why.NONE, "why " + kw.why() + " while armed");
            }
            if (d == Decision.DISARM) {
                check(!kw.armed(), "DISARM but still armed");
                check(!kw.ended().isEmpty() && kw.ended().endsWith("."), "ended() must be a sentence, got '" + kw.ended() + "'");
            }
        }

        void autoStart(PreconditioningSnapshot first, long gap) {
            // Every rule that must hold when a START is returned.
            check(kw.armed(), "START while disarmed");
            check(gap <= 10000, "START after a gap of " + gap + " ms");
            check(now - armedAt < durationMs, "START after the arming time was used up");
            check(!first.requested && first.canStart && first.state != State.WARM, "START on snapshot " + first.state + " requested=" + first.requested + " canStart=" + first.canStart);
            check(!sessionOpen, "START while a session is open");
            check(attempts < limit, "START as automatic attempt number " + (attempts + 1));
            check(lastSampleAt >= 0 && now - lastSampleAt <= 45000, "START with a newest sample " + (now - lastSampleAt) + " ms old");
            check(lastSampleMin <= THRESHOLD - 2, "START with a newest sample of " + lastSampleMin + " C");
            check(coldRun(), "START without 180 s of cold samples");
            check(lastSentStart < 0 || now - lastSentStart >= 480000, "START only " + (now - lastSentStart) + " ms after the previous Start");
            check(lastSessionEnd < 0 || now - lastSessionEnd >= 300000, "START only " + (now - lastSessionEnd) + " ms after a session ended");
            // The ordinary start, as the service does it.
            long before = car.starts;
            controller.refresh(now);
            PreconditioningSnapshot s = controller.snapshot(now);
            boolean sent = false;
            if (s.canStart && s.state != State.WARM) sent = controller.start(now, freshLast());
            kw.automaticStart(now, sent);
            attempts++; totals.autoAttempts++;
            if (car.starts > before) {
                check(sent, "car saw a Start but start() said false");
                check(car.starts == before + 1, "more than one Start in one tick");
                autoSent++; autoSentThisArm++;
                sentStartChecks();
                lastSentStart = now; sessionOpen = true; sawActive = false;
            } else {
                check(!sent, "start() said true but the car saw nothing");
            }
            check(autoSentThisArm <= limit && kw.startLimit() == limit, "more than " + limit + " automatic Starts in one arming");
        }

        void userActions(PreconditioningSnapshot snap) {
            if (snap.requested && snap.canStop && rnd.nextInt(chaos == 0 ? 2500 : 1200) == 0) { controller.stop(now); totals.userStops++; }
            if (now - armedAt < durationMs && rnd.nextInt(2500) == 0 && controller.snapshot(now).canStart) manualStart();
        }

        /** A fresh controller that was told a session was pending must never send a Start. */
        void recoveryNeverStarts() {
            for (boolean pending : new boolean[]{true, false}) {
                PreconditioningControllerTest.FakeVehicle other = new PreconditioningControllerTest.FakeVehicle();
                other.reading = new VehiclePort.Reading(1 + rnd.nextInt(2), rnd.nextInt(2), rnd.nextInt(2), -1);
                PreconditioningControllerTest.Journal j = new PreconditioningControllerTest.Journal();
                j.pending = pending;
                PreconditioningController c = new PreconditioningController(other, j);
                long t = now;
                c.recover(pending, t);
                for (int i = 0; i < 40; i++) {
                    t += 1000L * (1 + rnd.nextInt(30));
                    if (rnd.nextInt(4) == 0) other.readFails = !other.readFails;
                    if (rnd.nextInt(4) == 0) other.reading = new VehiclePort.Reading(1 + rnd.nextInt(2), rnd.nextInt(2), rnd.nextInt(2), -1);
                    c.refresh(t); c.snapshot(t);
                    check(other.starts == 0, "recover(" + pending + ") on a fresh controller sent a Start");
                }
            }
        }
    }

    @Test public void randomThreeHourRunsKeepEveryRule() {
        Totals totals = new Totals();
        for (int seed = 0; seed < SEEDS; seed++) new Run(seed, totals).go();
        String summary = totals.toString();
        System.out.println("KeepWarmLongRunTest: " + summary);
        // The simulation must really have exercised the rules.
        assertTrue(summary, totals.autoSent >= 100);
        assertTrue(summary, totals.manualSent >= 20);
        assertTrue(summary, totals.armings > SEEDS);
        assertTrue(summary, totals.jumps >= 10);
        assertTrue(summary, totals.count(Why.TIME_UP) > 0);
        assertTrue(summary, totals.count(Why.SLEPT) > 0);
        assertTrue(summary, totals.count(Why.START_LIMIT) > 0);
        assertTrue(summary, totals.count(Why.NO_HEAT) > 0);
        assertTrue(summary, totals.count(Why.NO_TEMPERATURE) > 0);
        assertTrue(summary, totals.count(Why.NOT_READY) > 0);
        assertTrue(summary, totals.count(Why.STATUS_UNKNOWN) > 0);
    }
}
