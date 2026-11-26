package miui.app;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;

@SuppressWarnings("unused")
public class ToggleManager {
    private static final ToggleManager INSTANCE = new ToggleManager();

    public interface OnToggleChangedListener {
        void OnToggleChanged(int toggleId);
    }

    public static ToggleManager createInstance(Context context) {
        return INSTANCE;
    }

    public static ArrayList<Integer> getAllToggles(Context context) {
        return new ArrayList<>();
    }

    public static int getGeneralImage(int id) {
        return 0;
    }

    public static Drawable getImageDrawable(int id, Context context) {
        return null;
    }

    public static int getName(int id) {
        return 0;
    }

    public static boolean getStatus(int id) {
        return false;
    }

    public static String getStatusName(int id, Resources res) {
        return "";
    }

    public static int getToggleIdFromString(String str) {
        return -1;
    }

    public static String getToggleStringFromId(int id) {
        return "";
    }

    public static void initDrawable(int id, Drawable drawable) {
    }

    public void setOnToggleChangedListener(OnToggleChangedListener listener) {
    }
}
