package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreconPresenterKeepWarmTest {
    private static final KeepWarm.Status ARMED = new KeepWarm.Status(true, 60, 41 * 60000L,
        "The battery is at 21°C. Tilepad asks again once it has stayed at 18°C or colder for 3 minutes.",
        "Keep warm ends in 41 minutes.", "");
    @Test public void theMonitorLineCountsTheAutomaticRequests() {
        KeepWarm.Status armed = new KeepWarm.Status(true, 240, 105 * 60000L, "w", "Keep warm ends in 1 h 45.", "", 2, 8);
        assertEquals("Keep warm: 1 h 45 left · requests 2 of 8", PreconPresenter.keepWarmLine(armed));
        KeepWarm.Status longest = new KeepWarm.Status(true, 360, 345 * 60000L, "w", "t", "", 12, 12);
        assertEquals("Keep warm: 5 h 45 left · requests 12 of 12", PreconPresenter.keepWarmLine(longest));
        KeepWarm.Status ended = new KeepWarm.Status(false, 0, 0, "", "", "Keep warm ended after 4 h, as set.", 5, 8);
        assertEquals("Automatic requests: 5 of 8", PreconPresenter.keepWarmLine(ended));
        KeepWarm.Status acknowledged = new KeepWarm.Status(false, 0, 0, "", "", "", 5, 8);
        assertEquals("", PreconPresenter.keepWarmLine(acknowledged));
        assertEquals("", PreconPresenter.keepWarmLine(KeepWarm.Status.OFF));
        // An older status without counts still shows the time left.
        assertEquals("Keep warm ends in 41 minutes.", PreconPresenter.keepWarmLine(ARMED));
    }
    @Test public void theStatusCarriesTheCountsWhileArmedAndAfter() {
        KeepWarm k = new KeepWarm();
        k.arm(1000, 240, new PreconditioningSnapshot(PreconditioningSnapshot.State.IDLE, false, true, false, 0, -1, 0, "", 0, 1, 2));
        k.automaticStart(2000, true); k.automaticStart(3000, false);
        KeepWarm.Status s = k.status(4000, 20);
        assertEquals(2, s.automaticStarts); assertEquals(8, s.startLimit);
        k.disarm(KeepWarm.Why.TIME_UP);
        s = k.status(5000, 20);
        assertEquals(2, s.automaticStarts); assertEquals(8, s.startLimit);
        assertEquals("Automatic requests: 2 of 8", PreconPresenter.keepWarmLine(s));
    }
    private static PreconditioningSnapshot snap(PreconditioningSnapshot.State state, boolean requested, boolean canStart, int indicator) {
        return new PreconditioningSnapshot(state, requested, canStart, requested, indicator, -1, 65000, "Detail.", 0, 1, 2);
    }
    private static BatteryStatus battery(int celsius, long at) {
        return new BatteryStatus(new BatteryTemperature.Info(celsius, 150, 90, 0), at, BatteryStatus.Reader.READING, 20);
    }

    @Test public void everyStateHasItsOwnWordsArmedOrNot() {
        for (PreconditioningSnapshot.State state : PreconditioningSnapshot.State.values()) {
            for (boolean requested : new boolean[]{false, true}) {
                PreconditioningSnapshot s = snap(state, requested, false, 0);
                for (KeepWarm.Status keepWarm : new KeepWarm.Status[]{KeepWarm.Status.OFF, ARMED}) {
                    String headline = PreconPresenter.headline(s, keepWarm);
                    assertFalse(state + " headline", headline.isEmpty());
                    // Only the error state may read as an error, on the tile and in the notification.
                    if (state != PreconditioningSnapshot.State.ERROR) {
                        assertNotEquals(state + " headline", "Error", headline);
                        assertNotEquals(state + " title", "Request error", s.title());
                        assertNotEquals(state + " tone", PreconPresenter.Tone.BAD, PreconPresenter.tone(s, keepWarm));
                    }
                    assertNotNull(PreconPresenter.detail(s, keepWarm, BatteryStatus.NONE, 100000));
                    assertNotNull(PreconPresenter.hint(s, keepWarm));
                    assertFalse(state + " monitor", PreconPresenter.monitorState(s, keepWarm, "").isEmpty());
                }
            }
        }
    }
    @Test public void armedBetweenSessionsSaysKeepingWarmOnTheTile() {
        PreconditioningSnapshot idle = snap(PreconditioningSnapshot.State.IDLE, false, true, 0);
        assertEquals("Keeping warm", PreconPresenter.headline(idle, ARMED));
        assertEquals(PreconPresenter.Tone.GOOD, PreconPresenter.tone(idle, ARMED));
        assertEquals("21°C · 41 min left", PreconPresenter.detail(idle, ARMED, battery(21, 100000), 100000));
        assertEquals("41 min left", PreconPresenter.detail(idle, ARMED, BatteryStatus.NONE, 100000));
        assertEquals("Tap for details", PreconPresenter.hint(idle, ARMED));
    }
    @Test public void aSessionKeepsItsOwnTileWordsWhileArmed() {
        PreconditioningSnapshot active = snap(PreconditioningSnapshot.State.ACTIVE, true, false, 1);
        assertEquals("Active", PreconPresenter.headline(active, ARMED));
        // With a fresh reading the tile shows now and target (and the start, once known).
        assertEquals("15 → 20°C", PreconPresenter.detail(active, ARMED, battery(15, 100000), 100000));
        assertEquals("Keeping warm · 41 min", PreconPresenter.hint(active, ARMED, battery(15, 100000), 100000));
        // Without one it falls back to the elapsed time.
        assertEquals("Elapsed 1:05", PreconPresenter.detail(active, ARMED, battery(15, 100000), 145001));
        assertEquals("Tap for details", PreconPresenter.hint(active, ARMED, battery(15, 100000), 145001));
    }
    @Test public void theTileShowsStartNowAndTargetDuringASession() {
        PreconditioningSnapshot active = snap(PreconditioningSnapshot.State.ACTIVE, true, false, 1);
        BatteryStatus known = new BatteryStatus(new BatteryTemperature.Info(15, 150, 90, 1), 100000,
            BatteryStatus.Reader.READING, 20, 12);
        assertEquals("12 → 15 → 20°C", PreconPresenter.detail(active, KeepWarm.Status.OFF, known, 100000));
        assertEquals("start · now · target", PreconPresenter.hint(active, KeepWarm.Status.OFF, known, 100000));
        assertEquals("15 → 20°C", PreconPresenter.detail(active, KeepWarm.Status.OFF, battery(15, 100000), 100000));
        assertEquals("now · target", PreconPresenter.hint(active, KeepWarm.Status.OFF, battery(15, 100000), 100000));
        // Between keep-warm sessions the tile says at what temperature Tilepad asks again.
        PreconditioningSnapshot idle = snap(PreconditioningSnapshot.State.IDLE, false, true, 0);
        assertEquals("Asks again at 18°C", PreconPresenter.hint(idle, ARMED, battery(19, 100000), 100000));
        assertEquals("now · target", PreconPresenter.hint(idle, KeepWarm.Status.OFF, battery(11, 100000), 100000));
    }
    @Test public void theTileBarRunsFromTheStartToTheTarget() {
        PreconditioningSnapshot active = snap(PreconditioningSnapshot.State.ACTIVE, true, false, 1);
        BatteryStatus known = new BatteryStatus(new BatteryTemperature.Info(15, 150, 90, 1), 100000,
            BatteryStatus.Reader.READING, 20, 12);
        PreconPresenter.Bar b = PreconPresenter.bar(active, KeepWarm.Status.OFF, known, 100000);
        assertEquals("Active · 1:05", b.caption);
        assertEquals("15°C", b.temperature);
        assertEquals(0.375f, b.fraction, 0.0001f);
        assertEquals("start 12°", b.left); assertEquals("target 20°", b.right); assertTrue(b.heating);
        // Idle: the temperature and the target, an empty bar.
        PreconditioningSnapshot idle = snap(PreconditioningSnapshot.State.IDLE, false, true, 0);
        b = PreconPresenter.bar(idle, KeepWarm.Status.OFF, battery(11, 100000), 100000);
        assertEquals("Precondition · Off", b.caption);
        assertEquals("now 11°", b.left); assertEquals("target 20°", b.right);
        assertEquals(0f, b.fraction, 0f); assertFalse(b.heating);
        // Already at or above the target while idle: the bar is full, not empty.
        assertEquals(1f, PreconPresenter.bar(idle, KeepWarm.Status.OFF, battery(24, 100000), 100000).fraction, 0f);
        // Keep warm between sessions: the band from "asks again" to the target.
        b = PreconPresenter.bar(idle, ARMED, battery(19, 100000), 100000);
        assertEquals("Keeping warm · 41 min left", b.caption);
        assertEquals("heats again 18°", b.left); assertEquals("20°", b.right);
        assertEquals(0.5f, b.fraction, 0.0001f);
        // A session that began warmer than the target, or without a start reading, never divides by zero.
        BatteryStatus warm = new BatteryStatus(new BatteryTemperature.Info(21, 150, 90, 1), 100000,
            BatteryStatus.Reader.READING, 20, 21);
        assertEquals(1f, PreconPresenter.bar(active, KeepWarm.Status.OFF, warm, 100000).fraction, 0f);
        assertEquals("now 15°", PreconPresenter.bar(active, KeepWarm.Status.OFF, battery(15, 100000), 100000).left);
        // No fresh reading, or a state the driver must read: the tile keeps its plain words.
        assertNull(PreconPresenter.bar(active, KeepWarm.Status.OFF, known, 145001));
        assertNull(PreconPresenter.bar(active, KeepWarm.Status.OFF, BatteryStatus.NONE, 100000));
        for (PreconditioningSnapshot.State state : new PreconditioningSnapshot.State[]{PreconditioningSnapshot.State.CHECKING,
            PreconditioningSnapshot.State.UNAVAILABLE, PreconditioningSnapshot.State.UNKNOWN, PreconditioningSnapshot.State.ERROR})
            assertNull(state.name(), PreconPresenter.bar(snap(state, false, false, 0), KeepWarm.Status.OFF, known, 100000));
    }
    @Test public void theTileTapOnlyEverOpensThePopup() {
        PreconditioningSnapshot ready = snap(PreconditioningSnapshot.State.IDLE, false, true, 0);
        assertEquals(PreconPresenter.Tap.STATUS, PreconPresenter.tap(ready, KeepWarm.Status.OFF));
        assertEquals("Tap to open", PreconPresenter.hint(ready, KeepWarm.Status.OFF));
        assertEquals(PreconPresenter.Tap.STATUS, PreconPresenter.tap(ready, ARMED));
        for (PreconditioningSnapshot.State state : PreconditioningSnapshot.State.values()) {
            PreconditioningSnapshot running = snap(state, true, false, 1);
            // A tap never stops or starts anything while a session runs or keep warm is armed.
            assertEquals(state.name(), PreconPresenter.Tap.STATUS, PreconPresenter.tap(running, KeepWarm.Status.OFF));
            assertEquals(state.name(), PreconPresenter.Tap.STATUS, PreconPresenter.tap(snap(state, false, true, 0), ARMED));
        }
    }
    @Test public void theIdleTileShowsAFreshBatteryReadingOnly() {
        PreconditioningSnapshot idle = snap(PreconditioningSnapshot.State.IDLE, false, true, 0);
        assertEquals("14 → 20°C", PreconPresenter.detail(idle, KeepWarm.Status.OFF, battery(14, 100000), 145000));
        assertEquals("now · target", PreconPresenter.hint(idle, KeepWarm.Status.OFF, battery(14, 100000), 145000));
        assertEquals("Tap to open", PreconPresenter.hint(idle, KeepWarm.Status.OFF, battery(14, 100000), 145001));
        assertEquals("", PreconPresenter.detail(idle, KeepWarm.Status.OFF, battery(14, 100000), 145001));
        assertEquals("Battery 14°C (57°F)", PreconPresenter.batteryLine(battery(14, 100000), 100000));
        assertEquals("", PreconPresenter.batteryLine(battery(14, 100000), 145001));
        assertEquals("", PreconPresenter.batteryLine(BatteryStatus.NONE, 100000));
    }
    @Test public void batteryAgeSaysWhyThereIsNoReading() {
        assertEquals("57°F · read 4 s ago.", PreconPresenter.batteryAge(battery(14, 100000), 104000));
        assertEquals("57°F · read 2 min ago.", PreconPresenter.batteryAge(battery(14, 100000), 220000));
        assertEquals("Reading the battery…", PreconPresenter.batteryAge(
            new BatteryStatus(null, 0, BatteryStatus.Reader.READING, 20), 1000));
        assertEquals("The battery reading isn’t available.", PreconPresenter.batteryAge(
            new BatteryStatus(null, 0, BatteryStatus.Reader.STOPPED, 20), 1000));
        assertEquals("The car isn’t answering the battery reading.", PreconPresenter.batteryAge(
            new BatteryStatus(null, 0, BatteryStatus.Reader.STUCK, 20), 1000));
    }
    @Test public void tilepadsReadingNeverClaimsTheCarsReason() {
        long now = 100000;
        PreconditioningSnapshot waiting = snap(PreconditioningSnapshot.State.WAITING, true, false, 0);
        assertTrue(PreconPresenter.reading(waiting, battery(12, now), now).contains("gives no sign of why"));
        String warm = PreconPresenter.reading(waiting, battery(22, now), now);
        assertTrue(warm, warm.startsWith("Tilepad’s reading: ") && warm.contains("22°C") && warm.contains("20°C"));
        // A stale reading is not used to explain anything.
        assertTrue(PreconPresenter.reading(waiting, battery(22, now), now + 45001).contains("gives no sign of why"));
        PreconditioningSnapshot notReady = new PreconditioningSnapshot(PreconditioningSnapshot.State.UNAVAILABLE,
            false, false, false, 0, -1, 0, "Detail.", 0, 0, 2);
        assertTrue(PreconPresenter.reading(notReady, BatteryStatus.NONE, now).contains("isn’t ready to drive"));
        PreconditioningSnapshot settingOff = new PreconditioningSnapshot(PreconditioningSnapshot.State.UNAVAILABLE,
            false, false, false, 0, -1, 0, "Detail.", 0, 1, 1);
        assertTrue(PreconPresenter.reading(settingOff, BatteryStatus.NONE, now).contains("switched off"));
        assertEquals("", PreconPresenter.reading(snap(PreconditioningSnapshot.State.IDLE, false, true, 0), battery(12, now), now));
    }
    @Test public void rawValuesAreShownAsReadAndUnreadOnesSaySo() {
        PreconditioningSnapshot unread = PreconditioningSnapshot.checking();
        assertEquals("Raw vehicle values: ready not read, conditioning setting not read, indicator not read, status value 115 is not read.",
            PreconPresenter.rawValues(unread, BatteryStatus.NONE));
        PreconditioningSnapshot s = new PreconditioningSnapshot(PreconditioningSnapshot.State.WAITING,
            true, false, true, 0, 3, 0, "Detail.", 0, 1, 2);
        assertEquals("Raw vehicle values: ready 1, conditioning setting 2, indicator 0, status value 115 is 3, battery status 0, charge 150, outside sensor 90.",
            PreconPresenter.rawValues(s, battery(12, 1000)));
    }
    @Test public void theMonitorSaysWhatTheSessionIsDoing() {
        PreconditioningSnapshot idle = snap(PreconditioningSnapshot.State.IDLE, false, true, 0);
        // The monitor shows the time left on its own line, so the state text never repeats it.
        assertEquals("Keeping warm. The battery is at 21°C. Tilepad asks again once it has stayed at 18°C or colder for 3 minutes.",
            PreconPresenter.monitorState(idle, ARMED, ""));
        assertFalse(PreconPresenter.monitorState(idle, ARMED, "").contains(ARMED.timeLeft));
        KeepWarm.Status ended = new KeepWarm.Status(false, 0, 0, "", "", "Keep warm ended after 60 minutes, as set.");
        assertEquals("Keep warm ended after 60 minutes, as set.", PreconPresenter.monitorState(idle, ended, ""));
        PreconditioningSnapshot over = snap(PreconditioningSnapshot.State.ENDED, false, true, 0);
        assertEquals("The car stopped heating the battery, so Tilepad ended its request.",
            PreconPresenter.monitorState(over, KeepWarm.Status.OFF, "The car stopped heating the battery, so Tilepad ended its request."));
        assertEquals("Detail.", PreconPresenter.monitorState(over, KeepWarm.Status.OFF, ""));
    }

    // ---- the monitor and the tile bar ----

    @Test public void theMonitorSaysTheCarHeatsOnItsOwnWheneverNothingIsRequestedAndTheIndicatorIsOn() {
        String own = "The car is conditioning the battery on its own.";
        for (PreconditioningSnapshot.State state : PreconditioningSnapshot.State.values()) {
            PreconditioningSnapshot s = snap(state, false, false, 1);
            assertEquals(state.name(), own, PreconPresenter.monitorState(s, KeepWarm.Status.OFF, ""));
            assertEquals(state.name() + " with a note", own, PreconPresenter.monitorState(s, KeepWarm.Status.OFF, "You turned preconditioning off."));
        }
        // The case the ended note used to hide: state ENDED, the indicator on, a note left from our own session.
        PreconditioningSnapshot ended = snap(PreconditioningSnapshot.State.ENDED, false, false, 1);
        assertEquals(own, PreconPresenter.monitorState(ended, KeepWarm.Status.OFF,
            "The car stopped heating the battery, so Tilepad ended its request."));
        // With the indicator off the note is still shown.
        assertEquals("You turned preconditioning off.", PreconPresenter.monitorState(
            snap(PreconditioningSnapshot.State.ENDED, false, true, 0), KeepWarm.Status.OFF, "You turned preconditioning off."));
        // A request in progress or an armed keep warm speaks for itself.
        assertTrue(PreconPresenter.monitorState(snap(PreconditioningSnapshot.State.ACTIVE, true, false, 1), KeepWarm.Status.OFF, "")
            .startsWith("Active for 1:05."));
    }
    @Test public void theMonitorSaysTheBatteryIsAlreadyWarmWithoutMentioningButtons() {
        String warm = "The battery is already warm, so no request was sent.";
        PreconditioningSnapshot s = snap(PreconditioningSnapshot.State.WARM, false, true, 0);
        assertEquals(warm, PreconPresenter.monitorState(s, KeepWarm.Status.OFF, ""));
        assertEquals(warm, PreconPresenter.monitorState(s, KeepWarm.Status.OFF, "Some note."));
    }
    @Test public void theBarCaptionDuringASessionIsTheStateAndTheElapsedTime() {
        long now = 100000;
        PreconditioningSnapshot active = snap(PreconditioningSnapshot.State.ACTIVE, true, false, 1);
        PreconPresenter.Bar bar = PreconPresenter.bar(active, KeepWarm.Status.OFF, battery(15, now), now);
        assertEquals("Active · 1:05", bar.caption);
        assertEquals("15°C", bar.temperature);
        PreconditioningSnapshot waiting = snap(PreconditioningSnapshot.State.WAITING, true, false, 0);
        assertEquals("Waiting · 1:05", PreconPresenter.bar(waiting, KeepWarm.Status.OFF, battery(15, now), now).caption);
        // Keep warm armed: the caption carries the time left instead.
        assertEquals("Keeping warm · 41 min left", PreconPresenter.bar(active, ARMED, battery(15, now), now).caption);
    }
    @Test public void theIdleBarIsFullWhenTheBatteryIsAtOrAboveTheThresholdAndEmptyBelow() {
        long now = 100000;
        PreconditioningSnapshot idle = snap(PreconditioningSnapshot.State.IDLE, false, true, 0);
        // Threshold is 20 in the helper battery.
        assertEquals(1f, PreconPresenter.bar(idle, KeepWarm.Status.OFF, battery(20, now), now).fraction, 0f);
        assertEquals(1f, PreconPresenter.bar(idle, KeepWarm.Status.OFF, battery(25, now), now).fraction, 0f);
        assertEquals(0f, PreconPresenter.bar(idle, KeepWarm.Status.OFF, battery(19, now), now).fraction, 0f);
        assertEquals(0f, PreconPresenter.bar(idle, KeepWarm.Status.OFF, battery(5, now), now).fraction, 0f);
        // No fresh reading, no bar.
        assertNull(PreconPresenter.bar(idle, KeepWarm.Status.OFF, battery(25, now), now + 45001));
    }
}
