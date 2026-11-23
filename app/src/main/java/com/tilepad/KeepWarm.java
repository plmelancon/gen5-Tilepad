package com.tilepad;

/**
 * Keep warm: repeats ordinary one-off sessions for a set time. Between sessions Tilepad owns no
 * request, so there is nothing to cancel if the process dies. This class only decides; the service
 * sends any Start through the same path as the button. Armed in memory only, never restored.
 * The idea of waiting for a cold battery and spacing the requests follows the Continuous mode of
 * the WiCAN preconditioning firmware; nothing is held between sessions here.
 * All times are SystemClock.elapsedRealtime(), which keeps counting while the head unit sleeps.
 */
final class KeepWarm {
    /**
     * The driver picks the duration with − / +: steps of STEP_MINUTES up to LONG_FROM_MINUTES, then
     * LONG_STEP_MINUTES, from MIN to MAX (15 min … 2 h by 15 min, then 2 h 30 … 6 h by 30 min).
     */
    static final int MIN_MINUTES = 15, MAX_MINUTES = 360, STEP_MINUTES = 15, DEFAULT_MINUTES = 60;
    static final int LONG_FROM_MINUTES = 120, LONG_STEP_MINUTES = 30;
    /** Automatic Starts per arming, at least. The Start sent by arming itself is the driver's own. */
    static final int MAX_AUTOMATIC_STARTS = 4;
    /** Above two hours the limit grows to one automatic Start per this many minutes (12 at 6 hours). */
    static final int MINUTES_PER_AUTOMATIC_START = 30;
    static final long START_GAP_MS = 8 * 60 * 1000L;
    static final long REST_MS = 5 * 60 * 1000L;
    /** The battery must stay cold this long, on fresh readings without a gap. */
    static final long COLD_RUN_MS = 3 * 60 * 1000L;
    static final long SAMPLE_FRESH_MS = 45 * 1000L;
    static final long NO_TEMPERATURE_MS = 5 * 60 * 1000L;
    static final long UNKNOWN_MS = 60 * 1000L;
    /** A longer silence between ticks means the head unit slept or the worker was blocked. */
    static final long TICK_GAP_MS = 10 * 1000L;
    /** Cold means this far under the profile's threshold: 18°C where the threshold is 20°C. */
    static final int COLD_MARGIN_C = 2;
    static final int NO_HEAT_LIMIT = 2;

    enum Decision { NONE, START, DISARM }
    enum Why { NONE, STOPPED, TIME_UP, SLEPT, CAR_ERROR, NOT_READY, STATUS_UNKNOWN, NO_TEMPERATURE, NO_HEAT, START_LIMIT }

    private boolean armed, sessionOpen, sessionSawActive;
    private long armedAt, durationMs, lastTickAt, lastStartAt, lastSessionEndAt, unknownSince;
    private long coldSince = -1, lastSampleAt;
    private int automaticStarts, noHeatInRow, lastMinimumC, startLimit = MAX_AUTOMATIC_STARTS;
    private Why why = Why.NONE;

    boolean armed() { return armed; }
    Why why() { return why; }
    int automaticStarts() { return automaticStarts; }
    int minutes() { return (int) (durationMs / 60000); }
    /** Automatic Starts allowed in this arming. */
    int startLimit() { return startLimit; }

    static boolean validMinutes(int minutes) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) return false;
        return minutes <= LONG_FROM_MINUTES ? minutes % STEP_MINUTES == 0 : minutes % LONG_STEP_MINUTES == 0;
    }
    static int startLimitFor(int minutes) {
        return Math.max(MAX_AUTOMATIC_STARTS, minutes / MINUTES_PER_AUTOMATIC_START);
    }
    /**
     * At most this many automatic Starts can fit in the time, the number the picker states. When arming
     * sends nothing (warm battery, or a session already running) the first may come after the 3-minute
     * cold run, then every 8 minutes: 2 at 15 min, 4 at 30 min, then the limit.
     */
    static int mostAutomaticStarts(int minutes) {
        return (int) Math.min(startLimitFor(minutes), (minutes * 60000L - COLD_RUN_MS - 1) / START_GAP_MS + 1);
    }
    /** One step up or down from the shown duration, kept within MIN..MAX. */
    static int step(int minutes, int direction) {
        int d = Integer.signum(direction);
        int next = d > 0 ? minutes + (minutes < LONG_FROM_MINUTES ? STEP_MINUTES : LONG_STEP_MINUTES)
            : d < 0 ? minutes - (minutes <= LONG_FROM_MINUTES ? STEP_MINUTES : LONG_STEP_MINUTES) : minutes;
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, next));
    }
    /** "45 min", "1 h", "1 h 30". */
    static String label(int minutes) {
        if (minutes < 60) return minutes + " min";
        int h = minutes / 60, m = minutes % 60;
        return h + " h" + (m == 0 ? "" : " " + (m < 10 ? "0" : "") + m);
    }

    /** Arms for one of the offered durations. A session already running is followed, not restarted. */
    boolean arm(long now, int minutes, PreconditioningSnapshot s) {
        if (armed || !validMinutes(minutes)) return false;
        armed = true; why = Why.NONE;
        armedAt = now; durationMs = minutes * 60000L; lastTickAt = now;
        // A session already running counts as the last Start, so the spacing holds from it. Otherwise
        // the last Start and session end seen during an earlier arming still count.
        if (s.requested) lastStartAt = Math.max(1, now - s.elapsedMs);
        // A session from the earlier arming that ended while disarmed was never seen ending: its rest
        // still applies, counted from now.
        else if (sessionOpen) lastSessionEndAt = now;
        unknownSince = 0;
        automaticStarts = 0; noHeatInRow = 0; startLimit = startLimitFor(minutes);
        sessionOpen = s.requested;
        sessionSawActive = s.requested && s.state == PreconditioningSnapshot.State.ACTIVE;
        // The cold run is measured from arming: readings taken before it don't count.
        coldSince = -1; lastSampleAt = 0;
        return true;
    }

    void disarm(Why reason) {
        if (!armed) return;
        armed = false; why = reason;
    }

    /** The driver has seen why it ended (or started something new): stop saying it. */
    void acknowledge() { if (!armed) why = Why.NONE; }

    /** A successful battery reading. Failed reads are simply not reported. */
    void sample(long at, int minimumC, int thresholdC) {
        if (!armed) return;
        boolean gap = lastSampleAt != 0 && at - lastSampleAt > SAMPLE_FRESH_MS;
        boolean cold = minimumC <= thresholdC - COLD_MARGIN_C;
        if (!cold) coldSince = -1;
        else if (coldSince < 0 || gap) coldSince = at;
        lastSampleAt = at; lastMinimumC = minimumC;
    }

    /** The driver's own Start while armed (arming, the tile or the popup). Not counted as automatic. */
    void manualStart(long now, boolean sent) {
        if (!armed || !sent) return;
        lastStartAt = now; sessionOpen = true; sessionSawActive = false;
    }

    /** The outcome of the Start that {@link #tick} asked for. A warm notice counts, and is never overridden. */
    void automaticStart(long now, boolean sent) {
        if (!armed) return;
        automaticStarts++; lastStartAt = now;
        if (sent) { sessionOpen = true; sessionSawActive = false; }
    }

    /** Once per service tick, before anything else. START means: send one ordinary Start now. */
    Decision tick(long now, PreconditioningSnapshot s, boolean readerStopped) {
        if (!armed) return Decision.NONE;
        long sinceTick = now - lastTickAt;
        lastTickAt = now;
        if (sinceTick > TICK_GAP_MS) return end(Why.SLEPT);
        if (now - armedAt >= durationMs) return end(Why.TIME_UP);

        if (s.requested) {
            if (!sessionOpen) { sessionOpen = true; sessionSawActive = false; }
            if (s.state == PreconditioningSnapshot.State.ACTIVE) sessionSawActive = true;
        } else if (sessionOpen) {
            sessionOpen = false; lastSessionEndAt = now;
            noHeatInRow = sessionSawActive ? 0 : noHeatInRow + 1;
        }

        if (s.state == PreconditioningSnapshot.State.ERROR) return end(Why.CAR_ERROR);
        // The car's own values decide, not the state: after a session the state stays "ended"
        // whatever READY and the setting say.
        if (s.state == PreconditioningSnapshot.State.UNAVAILABLE || s.readyRaw == 0 || s.enabledRaw == 1)
            return end(Why.NOT_READY);
        boolean read = s.readyRaw != -1 || s.enabledRaw != -1;
        boolean unknown = s.state == PreconditioningSnapshot.State.UNKNOWN
            || (read && (s.readyRaw != 1 || s.enabledRaw != 2));
        if (unknown) {
            if (unknownSince == 0) unknownSince = now;
            else if (now - unknownSince >= UNKNOWN_MS) return end(Why.STATUS_UNKNOWN);
        } else unknownSince = 0;
        if (readerStopped || now - Math.max(armedAt, lastSampleAt) >= NO_TEMPERATURE_MS) return end(Why.NO_TEMPERATURE);
        if (noHeatInRow >= NO_HEAT_LIMIT) return end(Why.NO_HEAT);
        if (automaticStarts >= startLimit && !sessionOpen) return end(Why.START_LIMIT);

        if (sessionOpen || !s.canStart || s.state == PreconditioningSnapshot.State.WARM) return Decision.NONE;
        // Three minutes of readings, not three minutes on the clock since the first cold one.
        if (coldSince < 0 || lastSampleAt - coldSince < COLD_RUN_MS) return Decision.NONE;
        return now >= nextStartAt(now) ? Decision.START : Decision.NONE;
    }

    /** When the next automatic Start may go out, or Long.MAX_VALUE while the battery isn't known cold. */
    long nextStartAt(long now) {
        if (!armed || automaticStarts >= startLimit) return Long.MAX_VALUE;
        if (coldSince < 0 || lastSampleAt == 0 || now - lastSampleAt > SAMPLE_FRESH_MS) return Long.MAX_VALUE;
        long at = coldSince + COLD_RUN_MS;
        if (lastStartAt != 0) at = Math.max(at, lastStartAt + START_GAP_MS);
        if (lastSessionEndAt != 0) at = Math.max(at, lastSessionEndAt + REST_MS);
        return at;
    }

    long remainingMs(long now) { return armed ? Math.max(0, armedAt + durationMs - now) : 0; }

    /** The freshest reading taken while armed, or null. */
    Integer minimumC(long now) {
        return armed && lastSampleAt != 0 && now - lastSampleAt <= SAMPLE_FRESH_MS ? lastMinimumC : null;
    }

    /** What keep warm is doing between sessions, as the popup and the notification say it. */
    String waiting(long now, int thresholdC) {
        Integer minimum = minimumC(now);
        int cold = thresholdC - COLD_MARGIN_C;
        if (minimum == null) return "Waiting for a battery temperature reading before asking the car to heat again.";
        long next = nextStartAt(now);
        if (automaticStarts >= startLimit)
            return "The battery is at " + minimum + "°C. Tilepad has asked the car to heat " + startLimit + " times and won't ask again.";
        if (next == Long.MAX_VALUE)
            return "The battery is at " + minimum + "°C. Tilepad asks again once it has stayed at "
                + cold + "°C or colder for 3 minutes.";
        long minutes = Math.max(1, (next - now + 59999) / 60000);
        return "The battery is at " + minimum + "°C. Tilepad asks the car to heat again in about "
            + minutes + (minutes == 1 ? " minute." : " minutes.");
    }

    String timeLeft(long now) {
        long minutes = Math.max(1, (remainingMs(now) + 59999) / 60000);
        if (minutes >= 60) return "Keep warm ends in " + label((int) minutes) + ".";
        return "Keep warm ends in " + minutes + (minutes == 1 ? " minute." : " minutes.");
    }

    /** Why keep warm ended, or "" while it runs or was never armed. */
    String ended() {
        switch (why) {
            case STOPPED: return "Keep warm was turned off.";
            case TIME_UP: return "Keep warm ended after " + (minutes() >= 60 ? label(minutes()) : minutes() + " minutes") + ", as set.";
            case SLEPT: return "Keep warm ended because the head unit stopped responding for a moment.";
            case CAR_ERROR: return "Keep warm ended because a request to the car failed.";
            case NOT_READY: return "Keep warm ended because the car is no longer ready, or battery conditioning is off in the car’s EV settings.";
            case STATUS_UNKNOWN: return "Keep warm ended because the car’s status couldn’t be read for a minute.";
            case NO_TEMPERATURE: return "Keep warm ended because the battery temperature couldn’t be read for 5 minutes.";
            case NO_HEAT: return "Keep warm ended because the car didn’t start heating twice in a row.";
            case START_LIMIT: return "Keep warm ended after asking the car to heat " + startLimit + " times.";
            default: return "";
        }
    }

    /** Immutable copy for the UI thread. */
    static final class Status {
        static final Status OFF = new Status(false, 0, 0, "", "", "");
        final boolean armed;
        final int minutes;
        final long remainingMs;
        /** While armed: what it waits for, and how long is left. After it ended: why. */
        final String waiting, timeLeft, ended;
        /** Automatic Starts tried in this (or the last) arming, and the limit; 0 of 0 when never armed. */
        final int automaticStarts, startLimit;
        Status(boolean armed, int minutes, long remainingMs, String waiting, String timeLeft, String ended) {
            this(armed, minutes, remainingMs, waiting, timeLeft, ended, 0, 0);
        }
        Status(boolean armed, int minutes, long remainingMs, String waiting, String timeLeft, String ended,
               int automaticStarts, int startLimit) {
            this.armed = armed; this.minutes = minutes; this.remainingMs = remainingMs;
            this.waiting = waiting; this.timeLeft = timeLeft; this.ended = ended;
            this.automaticStarts = automaticStarts; this.startLimit = startLimit;
        }
    }
    Status status(long now, int thresholdC) {
        return armed ? new Status(true, minutes(), remainingMs(now), waiting(now, thresholdC), timeLeft(now), "", automaticStarts, startLimit)
            : new Status(false, 0, 0, "", "", ended(), automaticStarts, startLimit);
    }

    private Decision end(Why reason) { disarm(reason); return Decision.DISARM; }
}
