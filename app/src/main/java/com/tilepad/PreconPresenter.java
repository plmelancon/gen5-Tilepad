package com.tilepad;

/**
 * Maps the controller's snapshot onto the one-button tile and its popup. Only the car's
 * own indicator can produce "Active"; a request in flight is always "Waiting".
 */
final class PreconPresenter {
    enum Tone { MUTED, READY, BUSY, GOOD, WARN, BAD }
    enum Tap { START, STOP, STATUS }

    static Tap tap(PreconditioningSnapshot s) {
        if (s.state == PreconditioningSnapshot.State.WARM) return Tap.STATUS;
        if (s.canStart) return Tap.START;
        // "Click again after success to disable": only an Active session stops on a single tap.
        // While waiting, the tap opens the popup, whose Cancel button stops the request.
        if (s.state == PreconditioningSnapshot.State.ACTIVE && s.canStop) return Tap.STOP;
        return Tap.STATUS;
    }

    static String headline(PreconditioningSnapshot s) {
        switch (s.state) {
            case CHECKING: return "Checking";
            case UNAVAILABLE: return "Unavailable";
            case IDLE: case ENDED: return s.indicator == 1 ? "Car active" : "Off";
            case WAITING: return "Waiting";
            case WARM: return "Battery warm";
            case TIMED_OUT: return "No activation";
            case ACTIVE: return "Active";
            case STOPPING: return "Stopping";
            case UNKNOWN: return "Unknown";
            default: return "Error";
        }
    }

    static Tone tone(PreconditioningSnapshot s) {
        switch (s.state) {
            case CHECKING: case STOPPING: return Tone.BUSY;
            case WAITING: case WARM: case TIMED_OUT: return Tone.WARN;
            case ACTIVE: return Tone.GOOD;
            case IDLE: case ENDED: return s.indicator == 1 ? Tone.GOOD : s.canStart ? Tone.READY : Tone.MUTED;
            case UNAVAILABLE: return Tone.MUTED;
            case UNKNOWN: return Tone.WARN;
            default: return Tone.BAD;
        }
    }

    static boolean busy(PreconditioningSnapshot s) {
        return s.state == PreconditioningSnapshot.State.CHECKING
            || s.state == PreconditioningSnapshot.State.WAITING
            || s.state == PreconditioningSnapshot.State.STOPPING;
    }

    /** Small line under the headline. */
    /** The tile's detail row: elapsed time while a request runs, else what the state needs said. */
    static String detail(PreconditioningSnapshot s) {
        if (s.requested) return "Elapsed " + s.elapsed();
        switch (s.state) {
            case IDLE: case ENDED: case TIMED_OUT: return s.indicator == 1 ? "Controlled by the car" : "";
            case CHECKING: return "Vehicle services";
            default: return "";
        }
    }

    /** The tile's bottom hint: what a tap does. */
    static String hint(PreconditioningSnapshot s) {
        switch (s.state) {
            case CHECKING: return "";
            case IDLE: case ENDED: case TIMED_OUT: return !s.requested && s.indicator != 1 && s.canStart ? "Tap to start" : "Tap for details";
            default: return "Tap for details";
        }
    }

    /**
     * The car's own conditioning indicator, for the tile's status pill; null unless it's on.
     * An off indicator gets no pill: "Car off" read as the car itself being off.
     */
    static String indicator(PreconditioningSnapshot s) {
        return s.indicator == 1 ? "Conditioning" : null;
    }

    /** "Waiting for BMS — activation may…" under a "Waiting for BMS" title reads "Activation may…". */
    static String withoutPrefix(String text, String prefix) {
        if (text == null || !text.startsWith(prefix) || text.length() == prefix.length()) return text;
        String rest = text.substring(prefix.length());
        return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }

    // ---- keep warm and the battery reading ----

    /**
     * The tile's tap always opens the popup, which has Start, Turn off, Keep warm and the battery
     * monitor. Nothing is sent to the car by the tap itself, so the battery can be looked at without
     * starting anything, and a glance-and-tap never starts or stops a session.
     */
    static Tap tap(PreconditioningSnapshot s, KeepWarm.Status keepWarm) {
        return Tap.STATUS;
    }

    static String headline(PreconditioningSnapshot s, KeepWarm.Status keepWarm) {
        return keepWarm.armed && !s.requested ? "Keeping warm" : headline(s);
    }

    static Tone tone(PreconditioningSnapshot s, KeepWarm.Status keepWarm) {
        return keepWarm.armed && !s.requested ? Tone.GOOD : tone(s);
    }

    /** The tile's detail row: elapsed time during a session, else the battery when it was just read. */
    static String detail(PreconditioningSnapshot s, KeepWarm.Status keepWarm, BatteryStatus battery, long now) {
        // During a session: where the battery started, where it is, and where the car is expected to stop.
        if (s.requested) return battery.fresh(now) ? temperatures(battery) : detail(s);
        if (keepWarm.armed) {
            long minutes = Math.max(1, (keepWarm.remainingMs + 59999) / 60000);
            // Short enough for the tile's one line.
            return (battery.fresh(now) ? battery.info.minimumC + "°C · " : "") + KeepWarm.label((int) minutes) + " left";
        }
        // Idle: where the battery is and where the car is expected to stop heating.
        String usual = detail(s);
        return usual.isEmpty() && battery.fresh(now) ? battery.info.minimumC + " → " + battery.thresholdC + "°C" : usual;
    }

    static String hint(PreconditioningSnapshot s, KeepWarm.Status keepWarm) {
        if (keepWarm.armed) return "Tap for details";
        String usual = hint(s);
        return usual.equals("Tap to start") ? "Tap to open" : usual;
    }

    /** The tile with a fresh battery reading: the temperature large, a bar towards the target, a label at each end. */
    static final class Bar {
        final String caption, temperature, left, right;
        /** 0 to 1: how far along the bar is filled. */
        final float fraction;
        final boolean heating;
        Bar(String caption, String temperature, float fraction, String left, String right, boolean heating) {
            this.caption = caption; this.temperature = temperature; this.fraction = fraction;
            this.left = left; this.right = right; this.heating = heating;
        }
    }

    /**
     * Null when the tile should keep its plain words: no fresh reading, or a state the driver has
     * to read first (checking, unavailable, unknown, error).
     */
    static Bar bar(PreconditioningSnapshot s, KeepWarm.Status keepWarm, BatteryStatus battery, long now) {
        if (!battery.fresh(now)) return null;
        switch (s.state) {
            case CHECKING: case UNAVAILABLE: case UNKNOWN: case ERROR: return null;
            default:
        }
        int current = battery.info.minimumC, target = battery.thresholdC;
        String temperature = current + "°C";
        boolean heating = s.indicator == 1;
        if (keepWarm.armed && !s.requested) {
            // Between sessions the bar is the band keep warm works in: from where it asks again up to the target.
            int again = target - KeepWarm.COLD_MARGIN_C;
            long minutes = Math.max(1, (keepWarm.remainingMs + 59999) / 60000);
            return new Bar("Keeping warm · " + KeepWarm.label((int) minutes) + " left", temperature,
                (current - again) / (float) Math.max(1, target - again), "heats again " + again + "°", target + "°", false);
        }
        String state = headline(s, keepWarm);
        // During a session the caption carries the elapsed time, which the bar pushed off the tile.
        String caption = keepWarm.armed
            ? "Keeping warm · " + KeepWarm.label((int) Math.max(1, (keepWarm.remainingMs + 59999) / 60000)) + " left"
            : s.requested ? state + " · " + s.elapsed()
            : state.length() <= 10 ? "Precondition · " + state : state;
        if (s.requested && battery.startC != null) {
            int start = battery.startC;
            float fraction = target > start ? (current - start) / (float) (target - start) : current >= target ? 1f : 0f;
            return new Bar(caption, temperature, fraction, "start " + start + "°", "target " + target + "°", heating);
        }
        // Idle, or a session whose start wasn't read: nothing to measure progress from. Empty below the
        // target, full at or above it, so a warm battery doesn't read as far from the target.
        return new Bar(caption, temperature, current >= target ? 1f : 0f, "now " + current + "°", "target " + target + "°", heating);
    }

    /** "12 → 15 → 20°C": start, now, target. Without a start reading, "15 → 20°C". */
    static String temperatures(BatteryStatus battery) {
        return (battery.startC == null ? "" : battery.startC + " → ") + battery.info.minimumC + " → " + battery.thresholdC + "°C";
    }

    /** The tile's bottom line. While temperatures show above it, it names them. */
    static String hint(PreconditioningSnapshot s, KeepWarm.Status keepWarm, BatteryStatus battery, long now) {
        if (s.requested && battery.fresh(now)) {
            if (keepWarm.armed) return "Keeping warm · " + KeepWarm.label((int) Math.max(1, (keepWarm.remainingMs + 59999) / 60000));
            return battery.startC == null ? "now · target" : "start · now · target";
        }
        if (keepWarm.armed && !s.requested) return "Asks again at " + (battery.thresholdC - KeepWarm.COLD_MARGIN_C) + "°C";
        // Idle with the two temperatures above: name them, as during a session.
        if (!s.requested && battery.fresh(now) && detail(s).isEmpty()) return "now · target";
        return hint(s, keepWarm);
    }

    /** "Battery 14°C (57°F)", or "" when there is no recent reading. For the popup's small line. */
    static String batteryLine(BatteryStatus battery, long now) {
        return battery.fresh(now) ? "Battery " + BatteryStatus.degrees(battery.info.minimumC) : "";
    }

    /** Under the monitor's large number: the other unit and how old the reading is, or why there is none. */
    static String batteryAge(BatteryStatus battery, long now) {
        if (battery.reader == BatteryStatus.Reader.STUCK) return "The car isn’t answering the battery reading.";
        if (battery.info == null)
            return battery.reader == BatteryStatus.Reader.STOPPED ? "The battery reading isn’t available." : "Reading the battery…";
        long seconds = battery.ageSeconds(now);
        String age = seconds < 60 ? seconds + " s ago" : seconds / 60 + " min ago";
        String stopped = battery.reader == BatteryStatus.Reader.STOPPED ? " The reading has stopped." : "";
        return BatteryStatus.fahrenheit(battery.info.minimumC) + "°F · read " + age + "." + stopped;
    }

    /**
     * The monitor's keep-warm line: time left and how many automatic requests went out, while armed;
     * after it ended (until that is acknowledged) the final count; otherwise "".
     */
    static String keepWarmLine(KeepWarm.Status keepWarm) {
        // One short line (the column is 400 dp): the longest is "Keep warm: 5 h 45 left · requests 12 of 12".
        if (keepWarm.armed) {
            if (keepWarm.startLimit <= 0) return keepWarm.timeLeft;
            long minutes = Math.max(1, (keepWarm.remainingMs + 59999) / 60000);
            return "Keep warm: " + KeepWarm.label((int) minutes) + " left · requests "
                + keepWarm.automaticStarts + " of " + keepWarm.startLimit;
        }
        if (keepWarm.ended.isEmpty() || keepWarm.startLimit <= 0) return "";
        return "Automatic requests: " + keepWarm.automaticStarts + " of " + keepWarm.startLimit;
    }

    /** What the session is doing, for the monitor, which shows no popup beside it. */
    static String monitorState(PreconditioningSnapshot s, KeepWarm.Status keepWarm, String endNote) {
        if (s.requested) {
            // Waiting and Active explain themselves; the other states need the controller's sentence.
            boolean plain = s.state == PreconditioningSnapshot.State.WAITING || s.state == PreconditioningSnapshot.State.ACTIVE;
            // Keep warm may have ended while this session goes on: say so, as the popup does.
            String lead = keepWarm.armed ? "Keeping warm. " : keepWarm.ended.isEmpty() ? "" : keepWarm.ended + " ";
            return lead + s.title() + " for " + s.elapsed() + "."
                + (plain ? "" : " " + s.detail);
        }
        if (keepWarm.armed) return "Keeping warm. " + keepWarm.waiting;
        if (!keepWarm.ended.isEmpty()) return keepWarm.ended;
        // The car heating on its own comes before any note about a session of ours that ended.
        if (s.indicator == 1) return "The car is conditioning the battery on its own.";
        // The popup's warm notice talks about buttons the monitor doesn't have.
        if (s.state == PreconditioningSnapshot.State.WARM) return "The battery is already warm, so no request was sent.";
        if (s.state == PreconditioningSnapshot.State.ENDED && !endNote.isEmpty()) return endNote;
        // The idle popup's invitation to start means nothing here: the monitor has no Start.
        if (s.state == PreconditioningSnapshot.State.IDLE)
            return s.indicator == 1 ? "The car is conditioning the battery on its own." : "Preconditioning is off.";
        return s.detail;
    }

    /**
     * Tilepad's own reading of the car's values, labelled as such. The car gives no reason when it
     * doesn't heat, so this never claims one: it says what Tilepad can see, or that it sees nothing.
     */
    static String reading(PreconditioningSnapshot s, BatteryStatus battery, long now) {
        String lead = "Tilepad’s reading: ";
        if (s.readyRaw == 0) return lead + "the car isn’t ready to drive, and it conditions the battery only when it is.";
        if (s.enabledRaw == 1) return lead + "battery conditioning is switched off in the car’s EV settings.";
        if (s.indicator == 1) return lead + "the car is conditioning the battery now.";
        if (s.requested && s.state == PreconditioningSnapshot.State.WAITING) {
            if (battery.fresh(now) && battery.info.minimumC >= battery.thresholdC)
                return lead + "the battery is at " + battery.info.minimumC + "°C, and this car isn’t expected to heat a battery at about "
                    + battery.thresholdC + "°C or warmer.";
            return lead + "the car has the request and hasn’t started heating. It gives no sign of why.";
        }
        return "";
    }

    /** The car's values exactly as read. Their meanings beyond ready, setting and indicator aren't confirmed. */
    static String rawValues(PreconditioningSnapshot s, BatteryStatus battery) {
        String car = "Raw vehicle values: ready " + shown(s.readyRaw) + ", conditioning setting " + shown(s.enabledRaw)
            + ", indicator " + shown(s.indicator) + ", status value 115 is " + shown(s.failureRaw);
        if (battery.info == null) return car + ".";
        return car + ", battery status " + battery.info.conditioningRaw + ", charge " + battery.info.chargeRaw
            + ", outside sensor " + battery.info.outsideRaw + ".";
    }
    private static String shown(int raw) { return raw == -1 ? "not read" : String.valueOf(raw); }

    private PreconPresenter() { }
}
