package com.tilepad;

/** Immutable battery reading for the UI thread: the car's last reply and how the reader is doing. */
final class BatteryStatus {
    enum Reader { IDLE, READING, STOPPED, STUCK }
    static final long STALE_MS = 45 * 1000L;
    static final BatteryStatus NONE = new BatteryStatus(null, 0, Reader.IDLE, BatteryTemperature.EXPECTED_HEATING_THRESHOLD_C);
    /** Null until the car has answered once. */
    final BatteryTemperature.Info info;
    final long receivedAt;
    final Reader reader;
    /** The profile's expected heating threshold, for the graph's line and Tilepad's reading. */
    final int thresholdC;
    /** The battery's temperature when the running session began; null with no session or no reading. */
    final Integer startC;
    BatteryStatus(BatteryTemperature.Info info, long receivedAt, Reader reader, int thresholdC) {
        this(info, receivedAt, reader, thresholdC, null);
    }
    BatteryStatus(BatteryTemperature.Info info, long receivedAt, Reader reader, int thresholdC, Integer startC) {
        this.info = info; this.receivedAt = receivedAt; this.reader = reader; this.thresholdC = thresholdC;
        this.startC = startC;
    }
    boolean fresh(long now) { return info != null && now - receivedAt <= STALE_MS; }
    long ageSeconds(long now) { return Math.max(0, now - receivedAt) / 1000; }
    static int fahrenheit(int celsius) { return Math.round(celsius * 9f / 5f + 32f); }
    /** "14°C (57°F)". */
    static String degrees(int celsius) { return celsius + "°C (" + fahrenheit(celsius) + "°F)"; }
}
