package miui.autoinstall.config.pm;

import android.content.Context;
import android.content.pm.PackageManager;

@SuppressWarnings("unused")
public class PackageManagerCompat {
    public PackageManagerCompat(Context context) {
    }

    public boolean packageExists(PackageManager pm, String packageName) {
        if (pm == null || packageName == null) {
            return false;
        }
        try {
            return pm.getPackageInfo(packageName, 0) != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
