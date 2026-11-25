package android.util;

import android.content.res.Resources;

@SuppressWarnings("unused")
public class MiuiDisplayMetrics {
    public static final int DENSITY_DEVICE = resolveDensity();

    private static int resolveDensity() {
        try {
            return Resources.getSystem().getDisplayMetrics().densityDpi;
        } catch (Throwable ignored) {
            return 420;
        }
    }
}
