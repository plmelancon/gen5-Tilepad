package com.tilepad;

import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * Scriptable emulator-only car. It runs the real {@link PreconditioningProfile} command sequences
 * and answers from a small model of the pack. Every property is read fresh on each call
 * ({@code adb shell setprop debug.tilepad.NAME VALUE}; empty or unset = default). Log tag: tilepad-sim.
 * <pre>
 * ready          1     READY flag in the status reply
 * enabled        2     "enabled" field in the status reply
 * failure        -1    raw failure field in the status reply
 * indicator      auto  auto = from the model; any integer (0, 1, 7...) is forced, the model keeps running
 * refuse         0     1 = the car never starts heating after a Start pulse
 * activate_s     8     seconds from the Start pulse until the indicator turns 1
 * target_c       20    the car stops heating by itself when the battery reaches this
 * session_max_s  0     the car stops heating by itself after this many seconds of heating (0 = no limit)
 * battery_min_c  unset exact battery temperature; unset = the model runs (and resumes from the last value)
 * start_c        12    model: starting temperature, read when the car is first used
 * heat_s         20    model: seconds per +1 C while heating
 * cool_s         30    model: seconds per -1 C while not heating
 * ambient_c      5     model: cooling stops here
 * temp_fail      0     1 = battery reads return null
 * temp_stuck     0     1 = battery reads return null and the reader is reported stuck
 * read_fail      0     1 = read() throws
 * hang_ms        0     read() first sleeps this long
 * write_fail     0     62, 65 or 66 = write() of that transaction throws (after logging)
 * charge_raw     150   raw charge field of the battery reply
 * outside_raw    90    raw outside field of the battery reply
 * cond_raw       auto  raw conditioning field; unset = 1 while heating else 0
 * device         wp_daudioplus_cvev_ca   firmware profile for the timeout and the warm threshold
 * </pre>
 * Example, heat to target (battery starts at 12 C, target 20 C, about 160 s):
 * <pre>
 * adb shell setprop debug.tilepad.target_c 16; adb shell setprop debug.tilepad.heat_s 10
 * (tap Start; the indicator turns 1 after 8 s, the car stops itself at 16 C)
 * </pre>
 * Example, the car refuses (the app should cancel after the activation timeout):
 * <pre>
 * adb shell setprop debug.tilepad.refuse 1
 * (tap Start; no heating follows; adb shell setprop debug.tilepad.refuse "" to undo)
 * </pre>
 * Example, lose READY while heating:
 * <pre>
 * (tap Start and wait for heating) adb shell setprop debug.tilepad.ready 0
 * (back: adb shell setprop debug.tilepad.ready 1)
 * </pre>
 * Conditions of a Start (ready, enabled, refuse, target) are checked when the activation delay
 * has elapsed, at the first call after that moment, with the property values of that call.
 */
final class SimulatedCar implements VehiclePort, PreconditioningProfile.Commands {
    private static final String TAG = "tilepad-sim", PREFIX = "debug.tilepad.", DEFAULT_DEVICE = "wp_daudioplus_cvev_ca";
    private static Method propertyGet;
    // Model, guarded by this. Time is SystemClock.elapsedRealtime().
    private double temp = Double.NaN;
    private long last;
    private boolean requested, pending, heating;
    private long startAt, heatingSince;
    private int lastMetadataTransaction, lastMetadataValue;
    private long lastMetadataAt = -1;

    private static synchronized String prop(String name) {
        try {
            if (propertyGet == null) propertyGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            String value = (String) propertyGet.invoke(null, PREFIX + name);
            return value == null ? "" : value.trim();
        } catch (Exception ignored) { return ""; }
    }
    private static Integer intOrNull(String name) {
        try { String value = prop(name); return value.isEmpty() ? null : Integer.valueOf(value); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static int integer(String name, int fallback) { Integer value = intOrNull(name); return value == null ? fallback : value; }
    private static boolean flag(String name) { return integer(name, 0) == 1; }
    private static void log(String message) { Log.i(TAG, message); }

    /** Brings the model up to now, one event at a time (activation, target, session limit). */
    private void advance() {
        long now = SystemClock.elapsedRealtime();
        Integer forced = intOrNull("battery_min_c");
        if (Double.isNaN(temp)) { temp = integer("start_c", 12); last = now; }
        if (forced != null) temp = forced;
        double heatMs = Math.max(1, integer("heat_s", 20)) * 1000.0, coolMs = Math.max(1, integer("cool_s", 30)) * 1000.0;
        int target = integer("target_c", 20), ambient = integer("ambient_c", 5);
        long activateMs = Math.max(0, integer("activate_s", 8)) * 1000L, sessionMs = Math.max(0, integer("session_max_s", 0)) * 1000L;
        for (int guard = 0; guard < 32; guard++) {
            if (pending && last >= startAt + activateMs) {
                pending = false;
                String why = flag("refuse") ? "refused" : integer("ready", 1) != 1 ? "not ready"
                    : integer("enabled", 2) != 2 ? "not enabled" : celsius() >= target ? "battery at target" : "";
                if (why.isEmpty()) { heating = true; heatingSince = last; log("heating started"); }
                else log("heating not started: " + why);
            }
            if (heating && celsius() >= target) stopHeating("target reached");
            else if (heating && sessionMs > 0 && last - heatingSince >= sessionMs) stopHeating("session limit");
            if (last >= now) break;
            long next = now;
            if (pending) next = Math.min(next, Math.max(last, startAt + activateMs));
            if (heating) {
                if (sessionMs > 0) next = Math.min(next, Math.max(last, heatingSince + sessionMs));
                if (forced == null) next = Math.min(next, last + (long) Math.ceil(Math.max(0, target - temp) * heatMs));
            }
            if (next <= last) next = Math.min(now, last + 1);
            if (forced == null) {
                if (heating) temp += (next - last) / heatMs;
                else if (temp > ambient) temp = Math.max(ambient, temp - (next - last) / coolMs);
            }
            last = next;
        }
        last = now;
    }
    private int celsius() { return (int) Math.floor(temp + 1e-6); }
    private void stopHeating(String why) {
        if (!heating) return;
        heating = false;
        log("heating stopped: " + why);
    }

    @Override public String unavailableReason() { return ""; }
    @Override public Reading read() throws Exception {
        int hang = integer("hang_ms", 0);
        if (hang > 0) SystemClock.sleep(hang); // outside the lock: a blocked car service blocks only this call
        synchronized (this) {
            if (flag("read_fail")) throw new IllegalStateException("Simulated: the car didn't answer a status request.");
            advance();
            return new Reading(integer("enabled", 2), integer("indicator", heating ? 1 : 0), // "auto" does not parse: model
                integer("ready", 1), integer("failure", -1));
        }
    }
    @Override public synchronized Integer readMinimumBatteryTemperatureC() {
        BatteryTemperature.Info info = readBatteryInfo();
        return info == null ? null : Integer.valueOf(info.minimumC);
    }
    @Override public synchronized BatteryTemperature.Info readBatteryInfo() {
        if (flag("temp_fail") || flag("temp_stuck")) return null;
        advance();
        return new BatteryTemperature.Info(celsius(), integer("charge_raw", 150), integer("outside_raw", 90),
            integer("cond_raw", heating ? 1 : 0));
    }
    @Override public boolean batteryReaderStuck() { return flag("temp_stuck"); }
    @Override public long activationTimeoutMs() { return PreconditioningProfile.activationTimeoutMs(device()); }
    @Override public int warmThresholdC() { return PreconditioningProfile.warmThresholdC(device()); }
    private static String device() { String value = prop("device"); return value.isEmpty() ? DEFAULT_DEVICE : value; }
    @Override public void beginManual() throws Exception { PreconditioningProfile.start(this); }
    @Override public void maintainManual() throws Exception { PreconditioningProfile.maintain(this); }
    @Override public void stopAndRelease() throws Exception { PreconditioningProfile.cancel(this); }
    @Override public long maintenanceIntervalMs() { return PreconditioningProfile.REFRESH_MS; }
    @Override public void pause(long milliseconds) { SystemClock.sleep(milliseconds); }
    @Override public synchronized void write(int transaction, int... values) throws Exception {
        if (!((transaction == 62 && values.length == 2) ||
            ((transaction == 65 || transaction == 66) && values.length == 1))) {
            log("write rejected: " + transaction + " (" + values.length + " values)");
            throw new IllegalArgumentException("Tilepad won't send a command it hasn't verified for preconditioning.");
        }
        StringBuilder line = new StringBuilder("write ").append(transaction);
        for (int value : values) line.append(' ').append(value);
        log(line.toString());
        if (integer("write_fail", 0) == transaction) throw new IllegalStateException("Simulated: the car didn't accept the command.");
        advance();
        if (transaction == 62 && values[0] == 2 && values[1] == 3) {
            requested = true;
            if (!heating) { pending = true; startAt = last; }
            log("start pulse received");
        } else if (transaction == 62 && values[0] == 0 && values[1] == 0) {
            requested = false; pending = false;
            stopHeating("cancelled");
        } else if (transaction != 62) {
            lastMetadataTransaction = transaction; lastMetadataValue = values[0]; lastMetadataAt = last;
        }
    }
}
