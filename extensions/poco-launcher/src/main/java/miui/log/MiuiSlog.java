package miui.log;

import android.util.Log;

@SuppressWarnings("unused")
public class MiuiSlog {
    public static void d(String tag, String msg) {
        Log.d(tag, msg != null ? msg : "");
    }
}
