package android.view;
import android.os.IBinder;
public final class SurfaceControl {
    public static void setDisplayPowerMode(IBinder displayToken, int mode) {}

    public static boolean setDisplayBrightness(IBinder displayToken, float brightness) {
        return true;
    }
}
