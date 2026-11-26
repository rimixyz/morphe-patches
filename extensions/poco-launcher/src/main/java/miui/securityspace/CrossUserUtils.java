package miui.securityspace;

import android.content.Context;
import android.os.Process;

@SuppressWarnings("unused")
public class CrossUserUtils {
    public static int getCurrentUserId() {
        return Process.myUid() / 100000;
    }

    public static int getSecondSpaceId() {
        return -10000;
    }

    public static boolean hasSecondSpace(Context context) {
        return false;
    }

    public static boolean isAirSpace(Context context, int userId) {
        return false;
    }
}
