package miui.content.pm;

import android.content.Context;

@SuppressWarnings("unused")
public class PreloadedAppPolicy {
    public static boolean isProtectedDataApp(Context context, String packageName, int flags) {
        return false;
    }
}
