package miui.securityspace;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

@SuppressWarnings("unused")
public class XSpaceUserHandle {
    public static Drawable getXSpaceIcon(Context context, Drawable icon) {
        return icon;
    }

    public static boolean isXSpaceUser(UserHandle user) {
        return false;
    }

    public static boolean isXSpaceUserId(int userId) {
        return false;
    }
}
