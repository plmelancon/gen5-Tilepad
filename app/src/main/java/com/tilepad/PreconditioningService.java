package com.tilepad;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.util.List;

/**
 * APK-contained ownership. There is no boot receiver and nothing is restored after a restart.
 * Keep warm is the only automatic Start: armed by hand for a set time, in memory only, and every
 * Start it asks for goes through the same checks as the button.
 */
public final class PreconditioningService extends Service {
    static final String START = "com.tilepad.PRECONDITION_START";
    static final String START_ANYWAY = "com.tilepad.PRECONDITION_START_ANYWAY";
    static final String STOP = "com.tilepad.PRECONDITION_STOP";
    static final String KEEP_WARM = "com.tilepad.PRECONDITION_KEEP_WARM";
    static final String MINUTES = "minutes";
    /** A reading this recent stands in for the Start pre-check's own read. */
    static final long PRECHECK_CACHE_MS = 10 * 1000L;
    static final long POLL_VISIBLE_MS = 10 * 1000L, POLL_BACKGROUND_MS = 20 * 1000L;
    /** After this many failed reads in a row the reader stops until it is asked again. */
    static final int READ_FAILURE_LIMIT = 5;
    private static final String TAG = "tilepad-precon";
    private static final int NOTIFICATION = 1203;
    private static final int ENDED_NOTICE = 1206;
    private final LocalBinder binder = new LocalBinder();
    private HandlerThread thread, batteryThread;
    private Handler worker, batteryReader;
    private PreconditioningController controller;
    private VehiclePort vehicle;
    private SharedPreferences journal;
    private final KeepWarm keepWarm = new KeepWarm();
    private final TemperatureLog history = new TemperatureLog();
    private final EventTrail events = new EventTrail(new EventTrail.Sink() {
        @Override public void line(String text) { Log.i(TAG, text); }
    });
    private volatile PreconditioningSnapshot snapshot = PreconditioningSnapshot.checking();
    private volatile BatteryStatus battery = BatteryStatus.NONE;
    private volatile KeepWarm.Status keepWarmStatus = KeepWarm.Status.OFF;
    private volatile String endNote = "";
    private boolean bound, foreground, userStop, wasRequested, wasStopping, sawActive;
    private String promoted = "";
    // Battery reader. Written on the worker; the reader thread only looks at these.
    private volatile boolean watching, readWanted, readerStopped, destroyed;
    /** Set on the caller's thread the moment Stop arrives, so a Start being prepared isn't sent. */
    private volatile boolean stopPending;
    /** The running session was started by keep warm: it never pops a card over the screen. */
    private volatile boolean quietSession;
    private volatile int generation;
    private int readFailures;
    private BatteryTemperature.Info cached;
    private long cachedAt;
    /** The battery when the running session began: the reading just before Start, else the first after. */
    private Integer sessionStartC;

    final class LocalBinder extends Binder {
        PreconditioningSnapshot snapshot() { return snapshot; }
        BatteryStatus battery() { return battery; }
        KeepWarm.Status keepWarm() { return keepWarmStatus; }
        List<TemperatureLog.Point> history() { return history.points(); }
        List<String> events() { return events.lines(); }
        /** Why the last session ended, as far as Tilepad saw; "" during a session or when unknown. */
        String endNote() { return endNote; }
        boolean quietSession() { return quietSession; }
        /** The popup that showed why keep warm ended has been closed. */
        void acknowledgeKeepWarmEnded() {
            worker.post(new Runnable() { @Override public void run() { acknowledgeEnd(); publish(); } });
        }
        /** The popup or the monitor is on screen (or no longer is): read the battery every 10 s. */
        void watch(final boolean on) {
            worker.post(new Runnable() { @Override public void run() {
                // Asking again while already watching restarts a reader that gave up after failed reads.
                if (watching == on && !(on && readerStopped)) return;
                watching = on; updateReader(on);
            } });
        }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            controller.refresh(SystemClock.elapsedRealtime());
            // Read the clock after the refresh: if the car's service blocked it, keep warm must see
            // the gap now and stand down, not act on a time from before the block.
            long now = SystemClock.elapsedRealtime();
            KeepWarm.Decision decision = keepWarm.tick(now, controller.snapshot(now), readerStopped);
            if (decision == KeepWarm.Decision.START && !stopPending) {
                // Held up past the gap limit, or past the end of the chosen time: nothing is sent.
                boolean sent = startOrdinary(now + Math.min(KeepWarm.TICK_GAP_MS, keepWarm.remainingMs(now)));
                keepWarm.automaticStart(SystemClock.elapsedRealtime(), sent);
                if (sent) quietSession = true;
                standDownOnError();
                events.add(clock(), sent ? "Keep warm: Start sent, " + keepWarm.automaticStarts() + " of " + keepWarm.startLimit()
                    : "Keep warm: Start not sent, state=" + controller.snapshot(SystemClock.elapsedRealtime()).state);
            } else if (decision == KeepWarm.Decision.DISARM) {
                events.add(clock(), keepWarm.ended());
                // Ending keep warm only stops further Starts. A session already running, keep warm's own
                // or the driver's, goes on and ends by the ordinary rules: nothing is sent to the car here.
                // Only the driver's Stop (popup or notification) sends a stop. From now on that session is
                // an ordinary one: its result (an error, say) may pop a card like any other.
                quietSession = false;
                announceEnd();
                updateReader(false);
            }
            publish();
            if (alive()) worker.postDelayed(this, 1000);
            else stopSelf();
        }
    };
    private boolean alive() { return bound || snapshot.requested || keepWarm.armed(); }
    /** Milliseconds since local midnight: the trail's lines read as the time of day. */
    private static long clock() {
        long wall = System.currentTimeMillis();
        return (wall + java.util.TimeZone.getDefault().getOffset(wall)) % 86400000L;
    }

    @Override public void onCreate() {
        super.onCreate();
        journal = getSharedPreferences("tilepad_preconditioning", MODE_PRIVATE);
        thread = new HandlerThread("tilepad-vehicle"); thread.start();
        worker = new Handler(thread.getLooper());
        // A blocked battery read must never hold up the controller, so it has its own thread.
        batteryThread = new HandlerThread("tilepad-battery"); batteryThread.start();
        batteryReader = new Handler(batteryThread.getLooper());
        worker.post(new Runnable() { @Override public void run() {
            vehicle = VehicleClientFactory.create();
            controller = new PreconditioningController(vehicle,
                new PreconditioningController.Journal() {
                    @Override public void requested(boolean value) {
                        if (!journal.edit().putBoolean("requested", value).commit())
                            throw new IllegalStateException("Could not record session ownership");
                    }
                });
            boolean pending = journal.getBoolean("requested", false);
            if (pending) promote("Recovering · cancel pending request");
            controller.recover(pending, SystemClock.elapsedRealtime());
            if (pending) events.add(clock(), "Recovered a pending request: cancel sent, no Start");
            publish();
        } });
    }
    private final Runnable attach = new Runnable() { @Override public void run() {
        bound = true;
        // On a rebind Android hands the client its connection before this runs, so its request to
        // watch the battery arrived while nothing was bound yet: honour it now.
        if (watching) updateReader(false);
        worker.removeCallbacks(tick); tick.run();
    } };
    @Override public IBinder onBind(Intent intent) { worker.post(attach); return binder; }
    @Override public void onRebind(Intent intent) { worker.post(attach); }
    @Override public boolean onUnbind(Intent intent) {
        worker.post(new Runnable() { @Override public void run() {
            bound = false;
            if (watching) { watching = false; updateReader(false); }
            if (!alive()) { worker.removeCallbacks(tick); stopSelf(); }
        } });
        return true; // Android reuses the Binder while a requested session keeps this service alive.
    }
    @Override public int onStartCommand(final Intent intent, int flags, int startId) {
        // Seen at once, on this thread: the worker may be in the middle of preparing a Start.
        if (intent != null && STOP.equals(intent.getAction())) stopPending = true;
        worker.post(new Runnable() { @Override public void run() {
            if (intent == null) return;
            long now = SystemClock.elapsedRealtime();
            if (START.equals(intent.getAction())) {
                acknowledgeEnd();
                boolean sent = startOrdinary(Long.MAX_VALUE);
                keepWarm.manualStart(SystemClock.elapsedRealtime(), sent);
                if (sent) { quietSession = false; events.add(clock(), "Start sent"); }
            }
            // While keep warm is armed the warm notice is never overridden, by anyone.
            else if (START_ANYWAY.equals(intent.getAction()) && !keepWarm.armed()) {
                acknowledgeEnd();
                controller.refresh(now);
                if (controller.snapshot(now).state == PreconditioningSnapshot.State.WARM && !stopPending) {
                    promote("Sending manual request");
                    boolean sent = controller.startAnyway(SystemClock.elapsedRealtime());
                    keepWarm.manualStart(SystemClock.elapsedRealtime(), sent);
                    if (sent) { quietSession = false; events.add(clock(), "Start sent after the warm notice"); }
                }
            }
            else if (KEEP_WARM.equals(intent.getAction())) {
                controller.refresh(now);
                PreconditioningSnapshot s = controller.snapshot(now);
                boolean ending = s.state == PreconditioningSnapshot.State.STOPPING || s.state == PreconditioningSnapshot.State.ERROR;
                boolean possible = !ending && (s.requested || s.canStart || s.state == PreconditioningSnapshot.State.WARM);
                if (possible && keepWarm.arm(now, intent.getIntExtra(MINUTES, 0), s)) {
                    endedNotice("");
                    events.add(clock(), "Keep warm armed for " + KeepWarm.label(keepWarm.minutes()) + ", up to " + keepWarm.startLimit() + " automatic Starts");
                    updateReader(true);
                    if (!s.requested) {
                        boolean sent = startOrdinary(Long.MAX_VALUE);
                        keepWarm.manualStart(SystemClock.elapsedRealtime(), sent);
                        if (sent) { quietSession = false; events.add(clock(), "Start sent"); }
                        standDownOnError();
                    }
                }
            }
            else if (STOP.equals(intent.getAction())) {
                stopPending = false;
                // Disarm first, so nothing can ask for another Start behind this Stop.
                if (keepWarm.armed()) {
                    keepWarm.disarm(KeepWarm.Why.STOPPED);
                    events.add(clock(), keepWarm.ended());
                    updateReader(false);
                }
                if (snapshot.requested) { userStop = true; events.add(clock(), "Stop requested"); }
                controller.stop(now);
            }
            publish(); worker.removeCallbacks(tick);
            if (alive()) worker.postDelayed(tick, 1000); else stopSelf();
        } });
        return START_NOT_STICKY;
    }

    /**
     * Right after a Start attempt: an error (even one before anything was sent, which the next
     * refresh would forget) ends keep warm. It must not sit armed and try again later.
     */
    private void standDownOnError() {
        if (!keepWarm.armed()) return;
        if (controller.snapshot(SystemClock.elapsedRealtime()).state != PreconditioningSnapshot.State.ERROR) return;
        keepWarm.disarm(KeepWarm.Why.CAR_ERROR);
        events.add(clock(), keepWarm.ended());
        quietSession = false;
        announceEnd();
        updateReader(false);
    }

    /**
     * Keep warm ended by itself: a plain notification says why, so a driver who isn't looking at
     * Tilepad (a long drive) still learns that it stopped asking. "" removes it.
     */
    private void endedNotice(String text) {
        // Never let a notification problem reach the thread that runs the session.
        try {
            Object service = getSystemService(NOTIFICATION_SERVICE);
            if (!(service instanceof NotificationManager)) return;
            NotificationManager manager = (NotificationManager) service;
            if (text.isEmpty()) { manager.cancel(ENDED_NOTICE); return; }
            PendingIntent content = PendingIntent.getActivity(this, 1205, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
            manager.notify(ENDED_NOTICE, new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Battery preconditioning")
                .setContentText(text).setTicker(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(content).build());
        } catch (RuntimeException e) {
            events.add(clock(), "Keep warm notice not shown: " + e);
        }
    }
    /** Keep warm ended by itself: tell the driver, except when the car was switched off (their own act). */
    private void announceEnd() {
        // The controller's own reading: the published snapshot is still the previous tick's.
        if (keepWarm.why() == KeepWarm.Why.NOT_READY && controller.snapshot(SystemClock.elapsedRealtime()).readyRaw == 0) return;
        endedNotice(keepWarm.ended());
    }
    /** The driver has moved on (a Start, or closed the card that said it): forget why keep warm ended. */
    private void acknowledgeEnd() {
        keepWarm.acknowledge();
        endedNotice("");
    }

    /**
     * The one way a Start is sent: eligibility, the temperature pre-check, eligibility again.
     * An automatic Start also carries a deadline: if the checks were held up past it, nothing is sent.
     */
    private boolean startOrdinary(long deadline) {
        controller.refresh(SystemClock.elapsedRealtime());
        PreconditioningSnapshot before = controller.snapshot(SystemClock.elapsedRealtime());
        if (!before.canStart || before.state == PreconditioningSnapshot.State.WARM) return false;
        promote("Checking battery temperature");
        Integer minimum = precheck();
        // The optional bounded read may take time; recheck eligibility before Start.
        controller.refresh(SystemClock.elapsedRealtime());
        // A Stop that arrived during these checks wins: its own turn on this thread comes next.
        if (stopPending || SystemClock.elapsedRealtime() > deadline) return false;
        return controller.start(SystemClock.elapsedRealtime(), minimum);
    }
    private Integer precheck() {
        if (cached != null && SystemClock.elapsedRealtime() - cachedAt < PRECHECK_CACHE_MS) return cached.minimumC;
        Integer minimum = vehicle.readMinimumBatteryTemperatureC();
        // A read that joined the reader's own request can run out of its time; ask once more.
        if (minimum == null && readWanted && !vehicle.batteryReaderStuck()) minimum = vehicle.readMinimumBatteryTemperatureC();
        return minimum;
    }

    // ---- battery reader ----

    private final Runnable read = new Runnable() { @Override public void run() {
        final int mine = generation;
        if (!readWanted || readerStopped || destroyed) return;
        final BatteryTemperature.Info info = vehicle.readBatteryInfo();
        final long at = SystemClock.elapsedRealtime();
        // The worker schedules the next read, so a restart can never leave two loops running.
        worker.post(new Runnable() { @Override public void run() { onRead(mine, info, at); } });
    } };
    /** Worker thread. Starts, restarts or stops the reader to match who needs it. */
    private void updateReader(boolean readNow) {
        boolean wanted = (bound && watching) || keepWarm.armed();
        if (!wanted) {
            if (readWanted) { readWanted = false; generation++; batteryReader.removeCallbacks(read); }
            publishBattery();
            return;
        }
        if (readWanted && !readNow && !readerStopped) return;
        // A new viewing or keep-warm period starts its own graph.
        if (!readWanted) history.clear();
        readWanted = true; readerStopped = false; readFailures = 0; generation++;
        batteryReader.removeCallbacks(read);
        batteryReader.post(read);
        publishBattery();
    }
    /** Worker thread. A result from an earlier generation is dropped. */
    private void onRead(int from, BatteryTemperature.Info info, long at) {
        if (from != generation || destroyed) return;
        if (info == null) {
            if (++readFailures >= READ_FAILURE_LIMIT && !readerStopped) {
                readerStopped = true;
                events.add(clock(), "Battery reader stopped after " + READ_FAILURE_LIMIT + " failed reads");
            }
        } else {
            readFailures = 0; cached = info; cachedAt = at;
            if (snapshot.requested && sessionStartC == null && snapshot.elapsedMs < 60000) sessionStartC = info.minimumC;
            history.add(at, info.minimumC, snapshot.indicator == 1);
            keepWarm.sample(at, info.minimumC, vehicle.warmThresholdC());
        }
        if (readWanted && !readerStopped) {
            batteryReader.removeCallbacks(read);
            batteryReader.postDelayed(read, watching ? POLL_VISIBLE_MS : POLL_BACKGROUND_MS);
        }
        publishBattery();
    }
    private void publishBattery() {
        BatteryStatus.Reader state = vehicle != null && vehicle.batteryReaderStuck() ? BatteryStatus.Reader.STUCK
            : readerStopped ? BatteryStatus.Reader.STOPPED
            : readWanted ? BatteryStatus.Reader.READING : BatteryStatus.Reader.IDLE;
        battery = new BatteryStatus(cached, cachedAt, state,
            vehicle == null ? BatteryTemperature.EXPECTED_HEATING_THRESHOLD_C : vehicle.warmThresholdC(),
            snapshot.requested ? sessionStartC : null);
    }

    private void publish() {
        long now = SystemClock.elapsedRealtime();
        snapshot = controller.snapshot(now);
        noteSessionEnd(snapshot);
        keepWarmStatus = keepWarm.status(now, vehicle.warmThresholdC());
        publishBattery();
        events.state(clock(), "Car status: " + snapshot.state + (snapshot.requested ? ", request held" : ", no request")
            + ", ready " + snapshot.readyRaw + ", setting " + snapshot.enabledRaw + ", indicator " + snapshot.indicator
            + ", value 115 is " + snapshot.failureRaw + ".");
        if (cached != null) events.quiet("battery=" + cached.minimumC + "C status5=" + cached.conditioningRaw
            + " charge0=" + cached.chargeRaw + " outside4=" + cached.outsideRaw);
        if (snapshot.requested) {
            promote((keepWarm.armed() ? "Keeping warm · " : "") + snapshot.title() + " · " + snapshot.elapsed());
        } else if (keepWarm.armed()) {
            Integer minimum = keepWarm.minimumC(now);
            promote("Keeping warm · " + (minimum == null ? "waiting" : "battery " + minimum + "°C") + " · "
                + KeepWarm.label((int) Math.max(1, (keepWarm.remainingMs(now) + 59999) / 60000)) + " left");
        } else if (foreground) { stopForeground(true); foreground = false; promoted = ""; }
    }
    /** Records, at the moment a session starts to end, what Tilepad saw that explains it. */
    private void noteSessionEnd(PreconditioningSnapshot s) {
        if (s.requested && !wasRequested) {
            endNote = ""; userStop = false; sawActive = false;
            sessionStartC = cached != null && SystemClock.elapsedRealtime() - cachedAt <= BatteryStatus.STALE_MS
                ? Integer.valueOf(cached.minimumC) : null;
        }
        if (s.requested && s.state == PreconditioningSnapshot.State.ACTIVE) sawActive = true;
        boolean stopping = s.requested && s.state == PreconditioningSnapshot.State.STOPPING;
        if (!s.requested) quietSession = false;
        if (stopping && !wasStopping) {
            // A request the car never acted on has its own "No activation" text from the controller.
            endNote = userStop ? "You turned preconditioning off."
                : s.readyRaw == 0 ? "The session ended because the car is no longer ready to drive."
                : s.enabledRaw == 1 ? "The session ended because battery conditioning was switched off in the car’s EV settings."
                : sawActive && s.indicator == 0 ? "The car stopped heating the battery, so Tilepad ended its request."
                : "";
            if (!endNote.isEmpty()) events.add(clock(), endNote);
        }
        wasRequested = s.requested; wasStopping = stopping;
    }
    private void promote(String message) {
            if (foreground && message.equals(promoted)) return;
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent content = PendingIntent.getActivity(this, 1203, open, PendingIntent.FLAG_UPDATE_CURRENT);
            Intent stop = new Intent(this, PreconditioningService.class).setAction(STOP);
            PendingIntent cancel = PendingIntent.getService(this, 1204, stop, PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Battery preconditioning")
                .setContentText(message).setOngoing(true)
                .setContentIntent(content).addAction(R.drawable.ic_notification, "Stop", cancel).build();
            startForeground(NOTIFICATION, notification); foreground = true; promoted = message;
    }
    @Override public void onDestroy() {
        destroyed = true;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (batteryReader != null) batteryReader.removeCallbacksAndMessages(null);
        // Retain the ownership journal after unexpected destruction. Recovery attempts
        // cancellation, never Start. Do not claim cancellation from onDestroy alone.
        if (thread != null) thread.quitSafely();
        if (batteryThread != null) batteryThread.quitSafely();
        super.onDestroy();
    }
}
