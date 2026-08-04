package com.android.server.display;

import android.os.IBinder;
import android.os.SystemClock;
import android.util.Slog;
import android.view.SurfaceControl;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ROM-side full-screen AOD relight for the OFF -> DOZE recovery edge.
 *
 * Injected into {@code LocalDisplayAdapter$LocalDisplayDevice$1} at two points, mirroring the
 * structure of the LSPosed module (the only variant verified to actually light the panel):
 *
 * <ol>
 *   <li>top of {@code setDisplayState(int)} -> {@link #onDisplayState} records the recovery edge;</li>
 *   <li>a wrapper around {@code setDisplayBrightness(float, float)} ->
 *       {@link #beginBrightness} / {@link #endBrightness} perform
 *       {@code NORMAL -> write -> DOZE} on that edge.</li>
 * </ol>
 *
 * <h2>Why the brightness write is the place to act</h2>
 *
 * {@code DisplayPowerController} splits a recovery into two separate requests. Device logs
 * (Xiaomi 14 / houji, OS3.0.303.0.WNCCNXM):
 *
 * <pre>
 * .352  setScreenState: state=DOZE
 * .370  updateAodAutoBrightness: newAodScreenAutoBrightness=0.030490575
 * .378  setDisplayState(state=DOZE)          &lt;- val$brightnessState here is -1.0
 * .451  getDozeBrightness: 0.030490575       &lt;- the real value, a separate request
 * </pre>
 *
 * So the state-change request does not carry a usable brightness — 9 of 10 recovery edges reported
 * {@code brightness=-1.0}. Only the later brightness request knows the value, which is why the edge
 * has to be remembered across the two events. That is the single piece of state here.
 *
 * <h2>Ordering and the two sleeps</h2>
 *
 * At the brightness write the panel is already in LP1, so the write would be rejected by the N2/O2
 * kernel ({@code is_backlight_set_skip ... due to LP1 on}, present in this device's dmesg on every
 * recovery). The sequence is therefore NORMAL, write, back to DOZE.
 *
 * Both power mode and brightness are asynchronous SurfaceControl transactions. Measured on device,
 * {@code setDisplayPowerMode(NORMAL)} took ~27 ms to reach the panel, and a brightness write ~50 ms
 * to reach the kernel — a v2 attempt with a single 16 ms delay still lost the race and logged
 * {@code skip set backlight 85 due to LP1 on}. Hence {@link #PANEL_SETTLE_MS} before the write and
 * {@link #COMMIT_DELAY_MS} before returning to DOZE. Both are tuning knobs: if the log shows the
 * relight completing but the panel still dark, raise them; the cost is added latency on the
 * recovery edge only.
 *
 * <h2>Do not use string concatenation carelessly</h2>
 *
 * This class lives on the boot classpath. javac 17 compiles {@code "a" + x} into an
 * {@code invoke-dynamic} against {@code StringConcatFactory.makeConcatWithConstants}, and ART
 * aborts the whole process while resolving that bootstrap method ({@code Runtime::Abort} — a native
 * abort, so no Java {@code catch} and no smali {@code .catchall} can contain it). That, not the
 * design, is what killed ROM patch v1 and v2.0: system_server died on the first log line before
 * anything reached logcat, leaving the panel mid-transition — which is also what produced v1's
 * "brightness stuck at a fixed low value" symptom. Stock services.jar contains zero references to
 * {@code StringConcatFactory}. {@code build.sh} compiles with {@code -XDstringConcat=inline} and
 * {@code patch_smali.py} fails the build if any {@code invoke-custom} survives.
 */
public final class AodDozeBridge {
    public static final String TAG = "AodDozeBridge";

    private static final int STATE_OFF = 1;
    private static final int STATE_ON = 2;
    private static final int STATE_DOZE = 3;
    private static final int STATE_DOZE_SUSPEND = 4;

    private static final int POWER_MODE_DOZE = 1;
    private static final int POWER_MODE_NORMAL = 2;

    private static final int NO_EDGE = -1;

    /** Let the panel actually leave LP1 before the brightness transaction is queued. */
    private static final long PANEL_SETTLE_MS = 16L;
    /** Let the brightness transaction reach the kernel before the panel goes back to LP1. */
    private static final long COMMIT_DELAY_MS = 64L;

    /**
     * Logical display id with a pending OFF -> DOZE recovery edge, or {@link #NO_EDGE}.
     *
     * The only mutable state. Set from {@code setDisplayState}, consumed by the next brightness
     * write on the same display, and cleared on any OFF or ON transition so it can never survive
     * into an unrelated screen-on session.
     */
    private static volatile int sPendingEdge = NO_EDGE;

    /**
     * Display token whose power mode must be returned to DOZE.
     *
     * Non-null only between {@link #beginBrightness} and {@link #endBrightness}, which the injected
     * wrapper pairs with a {@code .catchall}, so it cannot leak past one call.
     */
    private static volatile IBinder sArmedToken;

    private static final Object LOG_LOCK = new Object();

    /**
     * Mirror of every log line, so a recovery attempt can be inspected after a reboot without
     * catching it live on logcat. system_server runs as uid system and owns this directory.
     */
    private static final String LOG_PATH = "/data/system/aod_bridge.log";
    /** Truncate rather than grow without bound; one recovery writes a couple of hundred bytes. */
    private static final long LOG_MAX_BYTES = 256L * 1024L;

    private AodDozeBridge() {
    }

    /**
     * Called at the top of {@code LocalDisplayAdapter$LocalDisplayDevice$1.setDisplayState(int)}.
     *
     * Records nothing but the edge; the panel is not touched here.
     *
     * @param displayId logical display id, from {@code getDisplayId(mPhysicalDisplayId)}
     * @param oldState  {@code val$oldState} of the display request
     * @param state     the state being applied
     */
    public static void onDisplayState(int displayId, int oldState, int state) {
        try {
            if (state == STATE_OFF || state == STATE_ON) {
                sPendingEdge = NO_EDGE;
            } else if (oldState == STATE_OFF && isDoze(state) && isFullAod(displayId)) {
                sPendingEdge = displayId;
                info("armed OFF->DOZE edge, display=" + displayId + " state=" + state);
            }
        } catch (Throwable t) {
            // A framework mismatch must degrade to a no-op, never to a boot loop.
            sPendingEdge = NO_EDGE;
            warn("onDisplayState failed", t);
        }
    }

    /**
     * Called from the {@code setDisplayBrightness(float, float)} wrapper, before the original body.
     *
     * @param displayId logical display id
     * @param state     {@code val$state} of the display request
     * @param token     {@code val$token} of the display request
     * @param committed the brightness the framework is about to write
     * @return the brightness to write instead, or {@code 0} to keep the original arguments and
     *         leave the panel untouched. A positive return value is the only case in which the
     *         panel power mode was changed, and then {@link #endBrightness} restores it.
     */
    public static float beginBrightness(int displayId, int state, IBinder token, float committed) {
        try {
            return beginBrightnessImpl(displayId, state, token, committed);
        } catch (Throwable t) {
            // Leave sArmedToken alone: endBrightness() always runs and returns the panel to DOZE.
            warn("beginBrightness failed", t);
            return 0.0f;
        }
    }

    private static float beginBrightnessImpl(int displayId, int state, IBinder token,
            float committed) {
        if (sPendingEdge != displayId || !isDoze(state) || token == null) {
            return 0.0f;
        }
        // Consume the edge before anything can fail, so one edge relights at most once.
        sPendingEdge = NO_EDGE;

        if (!isUsable(committed)) {
            info("OFF->DOZE edge without usable brightness, display=" + displayId
                    + " brightness=" + committed);
            return 0.0f;
        }
        if (!isFullAod(displayId)) {
            return 0.0f;
        }

        try {
            SurfaceControl.setDisplayPowerMode(token, POWER_MODE_NORMAL);
        } catch (Throwable t) {
            warn("cannot enter NORMAL for display " + displayId, t);
            return 0.0f;
        }
        sArmedToken = token;
        SystemClock.sleep(PANEL_SETTLE_MS);
        info("relighting display " + displayId + " state=" + state
                + " with brightness " + committed);
        return committed;
    }

    /**
     * Called from the {@code setDisplayBrightness(float, float)} wrapper after the original body,
     * on both the normal and the exceptional path. A no-op unless
     * {@link #beginBrightness} armed the panel.
     */
    public static void endBrightness() {
        try {
            IBinder token = sArmedToken;
            if (token == null) {
                return;
            }
            sArmedToken = null;
            SystemClock.sleep(COMMIT_DELAY_MS);
            SurfaceControl.setDisplayPowerMode(token, POWER_MODE_DOZE);
        } catch (Throwable t) {
            warn("endBrightness failed", t);
        }
    }

    private static boolean isDoze(int state) {
        return state == STATE_DOZE || state == STATE_DOZE_SUSPEND;
    }

    private static boolean isUsable(float brightness) {
        return !Float.isNaN(brightness) && !Float.isInfinite(brightness) && brightness > 0.0f;
    }

    private static boolean isFullAod(int displayId) {
        try {
            DisplayFeatureManagerServiceStub stub = DisplayFeatureManagerServiceStub.getInstance();
            return stub != null && stub.isFullAodState(displayId);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void info(String message) {
        Slog.i(TAG, message);
        appendToFile("I", message, null);
    }

    private static void warn(String message, Throwable t) {
        Slog.w(TAG, message, t);
        appendToFile("W", message, t);
    }

    /**
     * Appends one line to {@link #LOG_PATH}. Retrieve it after a reboot with
     * {@code adb shell su -c 'cat /data/system/aod_bridge.log'}.
     *
     * Best effort by design: every failure here is swallowed, because losing a log line must never
     * affect the display pipeline. Called at most a handful of times per screen-off cycle, so the
     * synchronous write is not on any hot path.
     */
    private static void appendToFile(String level, String message, Throwable t) {
        try {
            synchronized (LOG_LOCK) {
                File file = new File(LOG_PATH);
                boolean truncate = file.length() > LOG_MAX_BYTES;
                StringBuilder line = new StringBuilder();
                line.append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date()));
                line.append(' ').append(level).append(' ').append(message);
                if (t != null) {
                    line.append(" | ").append(t.getClass().getName())
                            .append(": ").append(t.getMessage());
                }
                line.append('\n');
                FileOutputStream out = new FileOutputStream(file, !truncate);
                try {
                    out.write(line.toString().getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }
        } catch (Throwable ignored) {
            // Logging must never break the caller.
        }
    }
}
