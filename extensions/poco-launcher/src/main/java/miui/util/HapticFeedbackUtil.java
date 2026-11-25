package miui.util;

import android.content.Context;
import android.os.VibrationAttributes;

@SuppressWarnings("unused")
public class HapticFeedbackUtil {
    public HapticFeedbackUtil(Context context, boolean once) {
    }

    public static boolean isSupportLinearMotorVibrate() {
        return false;
    }

    public static boolean isSupportLinearMotorVibrate(int effectId) {
        return false;
    }

    public boolean isSupportExtHapticFeedback(int effectId) {
        return false;
    }

    public boolean performExtHapticFeedback(int effectId) {
        return false;
    }

    public boolean performHapticFeedback(int effectId, boolean always, int level) {
        return false;
    }

    public boolean performHapticFeedback(
            VibrationAttributes attributes, int effectId, boolean always, int level) {
        return false;
    }
}
