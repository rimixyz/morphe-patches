package miui.os;

import android.os.Process;

@SuppressWarnings("unused")
public class UserHandle {
    public static int myUserId() {
        return Process.myUid() / 100000;
    }
}
