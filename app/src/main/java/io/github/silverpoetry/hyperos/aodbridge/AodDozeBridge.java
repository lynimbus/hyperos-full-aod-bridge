package io.github.silverpoetry.hyperos.aodbridge;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class AodDozeBridge extends XposedModule {
    private static final int OFF = 1;
    private static final int ON = 2;
    private static final int DOZE = 3;
    private static final int DOZE_SUSPEND = 4;
    private static final int POWER_DOZE = 1;
    private static final int POWER_NORMAL = 2;
    private static final long NO_EDGE = Long.MIN_VALUE;

    private volatile boolean fullAod;
    private volatile float latestBrightness = Float.NaN;
    private volatile long pendingShowEdge = NO_EDGE;

    private Field screenAutoBrightness;
    private Field physicalDisplayId;
    private Field oldState;
    private Field targetState;
    private Field displayToken;
    private XposedInterface.Invoker<?, Method> setDisplayPowerMode;

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            install(param.getClassLoader());
        } catch (ReflectiveOperationException ignored) {
            // This module is tied to the exact tested framework build.
        }
    }

    @SuppressLint({"PrivateApi", "SoonBlockedPrivateApi"})
    private void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> strategy = Class.forName(
                "com.android.server.display.brightness.strategy.DozeBrightnessStrategyImpl",
                false,
                loader);
        Field isFullAod = field(strategy, "mIsFullAod");
        hook(strategy.getDeclaredMethod("updateAodMode", int.class))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    fullAod = isFullAod.getBoolean(chain.getThisObject());
                    if (!fullAod) {
                        pendingShowEdge = NO_EDGE;
                    }
                    return result;
                });

        Class<?> controller = Class.forName(
                "com.android.server.display.DozeAutoBrightnessController",
                false,
                loader);
        screenAutoBrightness = field(controller, "mScreenAutoBrightness");
        hook(controller.getDeclaredMethod("updateAutoBrightness"))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    latestBrightness = screenAutoBrightness.getFloat(chain.getThisObject());
                    return result;
                });

        Class<?> runnable = findDisplayRequestClass(loader);
        physicalDisplayId = field(runnable, "val$physicalDisplayId");
        oldState = field(runnable, "val$oldState");
        targetState = field(runnable, "val$state");
        displayToken = field(runnable, "val$token");

        Method powerMode = Class.forName("android.view.SurfaceControl", false, loader)
                .getDeclaredMethod("setDisplayPowerMode", IBinder.class, int.class);
        setDisplayPowerMode = getInvoker(powerMode);
        setDisplayPowerMode.setType(XposedInterface.Invoker.Type.ORIGIN);

        hook(runnable.getDeclaredMethod("setDisplayState", int.class))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    recordState(chain.getThisObject(), ((Number) chain.getArg(0)).intValue());
                    return result;
                });

        hook(runnable.getDeclaredMethod(
                "setDisplayBrightness", float.class, float.class))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(this::writeBrightnessAtShowEdge);
    }

    private void recordState(Object request, int state) throws IllegalAccessException {
        long id = physicalDisplayId.getLong(request);
        if (state == OFF || state == ON) {
            pendingShowEdge = NO_EDGE;
            if (state == OFF) {
                latestBrightness = Float.NaN;
            }
        } else if (fullAod && oldState.getInt(request) == OFF && isDoze(state)) {
            pendingShowEdge = id;
        }
    }

    private Object writeBrightnessAtShowEdge(XposedInterface.Chain chain) throws Throwable {
        Object request = chain.getThisObject();
        long id = physicalDisplayId.getLong(request);
        float brightness = latestBrightness;
        if (!fullAod
                || pendingShowEdge != id
                || !isDoze(targetState.getInt(request))
                || !Float.isFinite(brightness)
                || brightness <= 0.0f) {
            return chain.proceed();
        }

        IBinder token = (IBinder) displayToken.get(request);
        setPowerMode(token, POWER_NORMAL);
        pendingShowEdge = NO_EDGE;
        try {
            Object result = chain.proceed(new Object[]{brightness, brightness});
            SystemClock.sleep(16L);
            return result;
        } finally {
            setPowerMode(token, POWER_DOZE);
        }
    }

    private void setPowerMode(IBinder token, int mode) throws ReflectiveOperationException {
        setDisplayPowerMode.invoke(null, token, mode);
    }

    private static boolean isDoze(int state) {
        return state == DOZE || state == DOZE_SUSPEND;
    }

    private static Class<?> findDisplayRequestClass(ClassLoader loader)
            throws ReflectiveOperationException {
        String prefix = "com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice$";
        for (int index = 1; index <= 32; index++) {
            try {
                Class<?> candidate = Class.forName(prefix + index, false, loader);
                candidate.getDeclaredMethod("setDisplayState", int.class);
                candidate.getDeclaredMethod(
                        "setDisplayBrightness", float.class, float.class);
                candidate.getDeclaredField("val$physicalDisplayId");
                candidate.getDeclaredField("val$oldState");
                candidate.getDeclaredField("val$state");
                candidate.getDeclaredField("val$token");
                return candidate;
            } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException ignored) {
                // Anonymous-class numbering may change between framework builds.
            }
        }
        throw new ClassNotFoundException("LocalDisplayAdapter display request");
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
