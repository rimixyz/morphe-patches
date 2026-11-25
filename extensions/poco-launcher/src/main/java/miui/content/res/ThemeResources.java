package miui.content.res;

@SuppressWarnings("unused")
public class ThemeResources {
    public static final String THEME_MAGIC_PATH = "/data/system/theme/";
    private static final ThemeResourcesSystem SYSTEM = new ThemeResourcesSystem();

    public static ThemeResourcesSystem getSystem() {
        return SYSTEM;
    }
}
