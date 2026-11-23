package com.tilepad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What Tilepad sent and what the car reported, newest last. In memory for the monitor; each line
 * also goes to logcat under a "tilepad" tag, which the support log already keeps.
 */
final class EventTrail {
    static final int CAPACITY = 50;
    interface Sink { void line(String text); }
    private final String[] ring = new String[CAPACITY];
    private final Sink sink;
    private int first, count;
    private String lastState = "", lastQuiet = "";

    EventTrail(Sink sink) { this.sink = sink; }

    synchronized void add(long now, String text) {
        long seconds = now / 1000;
        String line = String.format(java.util.Locale.US, "%d:%02d:%02d  %s", seconds / 3600, seconds / 60 % 60, seconds % 60, text);
        ring[(first + count) % CAPACITY] = line;
        if (count < CAPACITY) count++; else first = (first + 1) % CAPACITY;
        if (sink != null) sink.line(text);
    }

    /** Records the car's values only when they differ from the last recorded ones. */
    synchronized void state(long now, String values) {
        if (values.equals(lastState)) return;
        lastState = values;
        add(now, values);
    }

    /** For the log only, on change: readings that would otherwise crowd the events off the monitor. */
    synchronized void quiet(String values) {
        if (values.equals(lastQuiet)) return;
        lastQuiet = values;
        if (sink != null) sink.line(values);
    }

    synchronized List<String> lines() {
        List<String> copy = new ArrayList<>(count);
        for (int i = 0; i < count; i++) copy.add(ring[(first + i) % CAPACITY]);
        return Collections.unmodifiableList(copy);
    }
}
