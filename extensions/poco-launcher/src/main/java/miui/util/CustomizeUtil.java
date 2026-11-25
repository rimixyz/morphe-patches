package miui.util;

import android.graphics.Point;
import android.view.Display;

@SuppressWarnings({"unused", "deprecation"})
public class CustomizeUtil {
    public static void getRealSize(Display display, Point outSize) {
        if (display != null && outSize != null) {
            display.getRealSize(outSize);
        }
    }
}
