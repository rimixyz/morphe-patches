package miui.os;

import java.util.Locale;

import app.rimixyz.extension.pocolauncher.PocoCompatHelper;

@SuppressWarnings("unused")
public class Build {
    public static final String BRAND = android.os.Build.BRAND;
    public static final String DEVICE = android.os.Build.DEVICE;
    public static final String MODEL = android.os.Build.MODEL;
    public static final String PRODUCT = android.os.Build.PRODUCT;

    public static final boolean IS_INTERNATIONAL_BUILD = true;
    public static final boolean IS_GLOBAL_BUILD = true;
    public static final boolean IS_STABLE_VERSION = true;
    public static final boolean IS_ALPHA_BUILD = false;
    public static final boolean IS_DEVELOPMENT_VERSION = false;
    public static final boolean IS_DEV_VERSION = false;
    public static final boolean IS_MIUI_LITE_VERSION = false;
    public static final boolean IS_CM_CUSTOMIZATION = false;
    public static final boolean IS_DEMO_BUILD = false;
    public static final boolean IS_HONGMI = false;
    public static final boolean IS_MIPAD = false;
    public static final boolean IS_TABLET = false;

    public static final int DEVICE_LOW_END = 0;
    public static final int DEVICE_MIDDLE_END = 1;
    public static final int DEVICE_HIGH_END = 2;

    public static final int TOTAL_RAM = PocoCompatHelper.readTotalRamGb();

    public static String getRegion() {
        String country = Locale.getDefault().getCountry();
        return (country != null && !country.isEmpty()) ? country : "US";
    }

    public static int getDeviceLevelForAnimation() {
        return DEVICE_HIGH_END;
    }
}
