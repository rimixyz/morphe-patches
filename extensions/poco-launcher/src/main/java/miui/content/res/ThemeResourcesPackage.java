package miui.content.res;

import android.content.res.MiuiResources;

@SuppressWarnings("unused")
public class ThemeResourcesPackage {
    private static final ThemeResourcesPackage INSTANCE = new ThemeResourcesPackage();

    public static ThemeResourcesPackage getThemeResources(
            MiuiResources resources, String packageName) {
        return INSTANCE;
    }

    public void mergeThemeValues(String filterKey, ThemeValues values) {
    }
}
