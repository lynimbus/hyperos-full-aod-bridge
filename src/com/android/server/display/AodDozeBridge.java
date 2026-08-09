package com.android.server.display;

import android.os.IBinder;
import android.os.SystemClock;
import android.util.Slog;
import android.view.SurfaceControl;

/**
 * ROM-side full-screen AOD relight for the OFF -> DOZE recovery edge.
 *
 * Injected at three points:
 *
 * <ol>
 *   <li>top of {@code setDisplayState(int)} in {@code LocalDisplayAdapter$LocalDisplayDevice$1}
 *       -> {@link #onDisplayState} records the recovery edge and, when the doze brightness is
 *       already known (v3 prelight), relights the panel immediately;</li>
 *   <li>a wrapper around {@code setDisplayBrightness(float, float)} ->
 *       {@link #beginBrightness} / {@link #endBrightness} perform
 *       {@code NORMAL -> write -> DOZE}, and double as the fallback path when the brightness
 *       was not available at the state edge;</li>
 *   <li>best-effort hook in {@code DisplayPowerController.updateAodAutoBrightness} ->
 *       {@link #setDozeBrightness}, which feeds the bridge the doze brightness ~70 ms before
 *       the adapter delivers it.</li>
 * </ol>
 *
 * <h2>Why the early brightness hook exists</h2>
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
 * The state-change request does not carry a usable brightness (9 of 10 recovery edges report
 * {@code brightness=-1.0}); the real doze brightness arrives ~73 ms later as its own request.
 * v1/v2 waited for that brightness request, putting ~70 ms of the perceived wake latency on the
 * critical path. v3 captures the value where {@code updateAodAutoBrightness} computes it (at
 * .370, before the state is even dispatched) and relights at the state edge (.378), so the panel
 * is lit ~66 ms after the state edge instead of ~139 ms.
 *
 * {@code getDozeBrightness} at .451 reads the same field {@code updateAodAutoBrightness} wrote,
 * so the early value is the value the brightness request would carry; the later request then
 * finds the edge already consumed, and its own write is rejected by the kernel (LP1 is on
 * again) — the same stock rejection that happens on every recovery, harmless here because the
 * value is identical.
 *
 * <h2>Ordering and the two sleeps</h2>
 *
 * At the relight moment the panel is already in LP1, so a plain brightness write would be
 * rejected by the N2/O2 kernel ({@code is_backlight_set_skip ... due to LP1 on}, present in this
 * device's dmesg on every recovery). The sequence is therefore NORMAL, write, back to DOZE — in
 * v3 all at the state edge when a prelight value is present, otherwise at the brightness edge.
 *
 * Both power mode and brightness are asynchronous SurfaceControl transactions. Measured on device,
 * {@code setDisplayPowerMode(NORMAL)} took ~27 ms to reach the panel, and a brightness write ~50 ms
 * to reach the kernel — a v1 attempt with a single 16 ms delay still lost the race: its DOZE
 * re-entry overtook the write, which landed on LP1 and was logged as
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
 * design, is what killed ROM patch v1: system_server died on the first log line before
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
     * Fingerprint-auth Local HBM window on tap-to-wake: kernel skips backlight writes
     * while it is on (~360ms measured). The re-write after this delay lands past it.
     */
    private static final long LHBM_WINDOW_MS = 400L;

    /**
     * Logical display id with a pending OFF -> DOZE recovery edge, or {@link #NO_EDGE}.
     *
     * The only mutable state besides the two brightness holders below. Set from
     * {@code setDisplayState}, consumed by the next brightness write on the same display, and
     * cleared on any OFF or ON transition so it can never survive into an unrelated screen-on
     * session.
     */
    private static volatile int sPendingEdge = NO_EDGE;

    /**
     * Display token whose power mode must be returned to DOZE.
     *
     * Non-null only between {@link #beginBrightness} and {@link #endBrightness}, which the injected
     * wrapper pairs with a {@code .catchall}, so it cannot leak past one call.
     */
    private static volatile IBinder sArmedToken;

    /**
     * Doze brightness computed by {@code DisplayPowerController.updateAodAutoBrightness}
     * (injected hook {@link #setDozeBrightness}) and not yet consumed by an OFF -> DOZE edge.
     *
     * Cleared on every OFF/ON transition so a value from an unrelated screen-on session can
     * never be used, and moved out the moment the recovery edge is armed by
     * {@link #onDisplayState}.
     */
    private static volatile float sDozeBrightnessInput = Float.NaN;

    private AodDozeBridge() {
    }

    /**
     * Called at the top of {@code LocalDisplayAdapter$LocalDisplayDevice$1.setDisplayState(int)}.
     *
     * Arms the recovery edge and, when a doze brightness is already available from the
     * {@link #setDozeBrightness} hook, returns it so the injected prelight code that follows
     * can relight immediately; the panel itself is not touched here.
     *
     * @param displayId logical display id, from {@code getDisplayId(mPhysicalDisplayId)}
     * @param oldState  {@code val$oldState} of the display request
     * @param state     the state being applied
     * @return the brightness to write right now, or {@code NaN} when the edge must wait for
     *         the brightness request. At most one edge prelights per value.
     */
    public static float onDisplayState(int displayId, int oldState, int state) {
        try {
            if (state == STATE_OFF || state == STATE_ON) {
                sPendingEdge = NO_EDGE;
                sDozeBrightnessInput = Float.NaN;
                return Float.NaN;
            }
            if (oldState == STATE_OFF && isDoze(state) && isFullAod(displayId)) {
                sPendingEdge = displayId;
                // Take the value fed by the updateAodAutoBrightness hook, if this recovery
                // produced one. The injected hook right after this call consumes the return
                // value.
                float brightness = sDozeBrightnessInput;
                sDozeBrightnessInput = Float.NaN;
                info("armed OFF->DOZE edge, display=" + displayId + " state=" + state
                        + (isUsable(brightness)
                                ? " prelight=" + brightness : " prelight=none"));
                return brightness;
            }
            return Float.NaN;
        } catch (Throwable t) {
            // A framework mismatch must degrade to a no-op, never to a boot loop.
            sPendingEdge = NO_EDGE;
            sDozeBrightnessInput = Float.NaN;
            warn("onDisplayState failed", t);
            return Float.NaN;
        }
    }

    /**
     * Called from the injected {@code DisplayPowerController.updateAodAutoBrightness} hook.
     *
     * Stores the freshly computed doze brightness so the state edge can relight immediately
     * instead of waiting ~70 ms for the adapter's own brightness request. Best effort by
     * design: {@link #onDisplayState} only uses the value when it is finite and positive, and
     * the edge falls back to the brightness request otherwise.
     *
     * @param brightness the {@code newAodScreenAutoBrightness} the firmware just computed
     */
    public static void setDozeBrightness(float brightness) {
        try {
            sDozeBrightnessInput = isUsable(brightness) ? brightness : Float.NaN;
        } catch (Throwable t) {
            sDozeBrightnessInput = Float.NaN;
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
        // 边沿只在 onDisplayState 确认过 Full AOD 后才武装(sPendingEdge 被赋值)，
        // 这里不必再调一次 isFullAodState binder。几十毫秒内 Full AOD 状态不会变。

        try {
            SurfaceControl.setDisplayPowerMode(token, POWER_MODE_NORMAL);
        } catch (Throwable t) {
            warn("cannot enter NORMAL for display " + displayId, t);
            return 0.0f;
        }
        sArmedToken = token;
        SystemClock.sleep(PANEL_SETTLE_MS);
        // 直接写面板亮度，不走原方法：Full AOD 状态下原方法会走“正常亮度”分支并调用
        // updateDozeBrightness(0) 把 doze 亮度清零（实机 dmesg：relight 时刻写 backlight 0）。
        // 返回非零表示 relight 已由桥完成，包装方法不再调用原方法。
        try {
            SurfaceControl.setDisplayBrightness(token, committed);
            info("relighting display " + displayId + " state=" + state
                    + " with brightness " + committed);
        } catch (Throwable t) {
            warn("cannot set brightness for display " + displayId, t);
            return 0.0f;
        }
        // 点击唤醒触发的指纹认证 Local HBM 窗口内，内核会 skip 背光写入（面板不亮）。
        // 延迟后补写一次，确保面板真正亮起；正常场景为同值重复写，无害。
        SystemClock.sleep(LHBM_WINDOW_MS);
        try {
            SurfaceControl.setDisplayBrightness(token, committed);
        } catch (Throwable t) {
            warn("cannot re-set brightness for display " + displayId, t);
        }
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
    }

    private static void warn(String message, Throwable t) {
        Slog.w(TAG, message, t);
    }
}
