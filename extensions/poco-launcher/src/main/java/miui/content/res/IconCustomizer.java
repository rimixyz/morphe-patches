package miui.content.res;

import android.content.Context;
import android.content.pm.PackageItemInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

@SuppressWarnings("unused")
public class IconCustomizer {
    public IconCustomizer() {
    }

    public static void clearCache() {
    }

    public static void clearCustomizedIcons(String packageName) {
    }

    public static BitmapDrawable generateIconStyleDrawable(Drawable base) {
        return generateIconStyleDrawable(base, false);
    }

    public static BitmapDrawable generateIconStyleDrawable(Drawable base, boolean cropOutside) {
        if (base == null) {
            return null;
        }
        if (base instanceof BitmapDrawable) {
            return (BitmapDrawable) base;
        }
        int width = base.getIntrinsicWidth();
        int height = base.getIntrinsicHeight();
        int fallback = getCustomizedIconWidth();
        if (width <= 0) {
            width = fallback;
        }
        if (height <= 0) {
            height = fallback;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            base.setBounds(0, 0, width, height);
            base.draw(canvas);
            return new BitmapDrawable(Resources.getSystem(), bitmap);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getAnimatingIconRelativePath(
            PackageItemInfo info, String packageName, String className) {
        return null;
    }

    public static BitmapDrawable getCustomizedIcon(Context context, String filename) {
        return null;
    }

    public static BitmapDrawable getCustomizedIcon(
            Context context, String packageName, String className, Drawable originalIcon) {
        return originalIcon != null ? generateIconStyleDrawable(originalIcon, false) : null;
    }

    public static int getCustomizedIconWidth() {
        try {
            return Math.max(96, (int) (Resources.getSystem().getDisplayMetrics().density * 60f));
        } catch (Throwable ignored) {
            return 144;
        }
    }

    public static String getFancyIconRelativePath(
            PackageItemInfo info, String packageName, String className) {
        return null;
    }

    public static String getMamlAdaptiveIconRelativePath(
            PackageItemInfo info, String packageName, String className) {
        return null;
    }

    public static Bitmap getRawIcon(String filename) {
        return null;
    }

    public static BitmapDrawable getRawIconDrawable(String filename) {
        return null;
    }

    public static boolean isModIconEnabledForPackageName(String packageName) {
        return false;
    }
}
