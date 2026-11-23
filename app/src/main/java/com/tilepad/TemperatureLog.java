package com.tilepad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The battery readings taken since the service started, for the monitor's graph. In memory only:
 * it starts empty every time, and a failed read leaves a hole rather than a guessed point.
 */
final class TemperatureLog {
    static final int CAPACITY = 360;
    /** Two readings further apart than this aren't joined by a line. */
    static final long BREAK_MS = 50 * 1000L;

    static final class Point {
        final long at;
        final int minimumC;
        /** The car's own indicator at that moment: it was conditioning the battery. */
        final boolean heating;
        Point(long at, int minimumC, boolean heating) { this.at = at; this.minimumC = minimumC; this.heating = heating; }
    }

    private final Point[] ring = new Point[CAPACITY];
    private int first, count;

    synchronized void add(long at, int minimumC, boolean heating) {
        // Elapsed time never runs backwards inside one process; ignore anything that does.
        if (count > 0 && at <= ring[(first + count - 1) % CAPACITY].at) return;
        ring[(first + count) % CAPACITY] = new Point(at, minimumC, heating);
        if (count < CAPACITY) count++; else first = (first + 1) % CAPACITY;
    }

    synchronized void clear() { first = 0; count = 0; }

    /** Oldest first; an unmodifiable copy for the UI thread. */
    synchronized List<Point> points() {
        List<Point> copy = new ArrayList<>(count);
        for (int i = 0; i < count; i++) copy.add(ring[(first + i) % CAPACITY]);
        return Collections.unmodifiableList(copy);
    }

    synchronized Point latest() { return count == 0 ? null : ring[(first + count - 1) % CAPACITY]; }
}
