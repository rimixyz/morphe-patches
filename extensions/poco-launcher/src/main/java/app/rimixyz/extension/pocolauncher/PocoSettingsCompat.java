package app.rimixyz.extension.pocolauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Safe compatibility layer for {@link Settings.System}, {@link Settings.Global},
 * {@link Settings.Secure}, and {@link android.provider.MiuiSettings}.
 *
 * <p>On non-Xiaomi devices POCO Launcher runs as a normal user app without
 * {@code WRITE_SETTINGS} or {@code WRITE_SECURE_SETTINGS}. Writes are persisted locally in
 * private {@link SharedPreferences} (with an in-memory fallback during direct-boot), while reads
 * return any locally persisted override first and otherwise delegate to the real Android
 * {@link Settings} provider so real OS settings remain accurate.
 *
 * <p>Writes are strictly deduplicated: writing an unchanged value performs no disk I/O and does
 * not re-fire local {@link ContentObserver}s, preventing infinite observer feedback loops (such as
 * {@code ApplicationsMessage.ScriptCountObserver} writing {@code *.superscript_count="0"} inside
 * {@code onChange}). Absent non-OS (MIUI-only) keys are negative-cached in memory to eliminate
 * repeated Binder IPC to {@code SettingsProvider} on hot launcher paths.
 */
@SuppressWarnings("unused")
public final class PocoSettingsCompat {
    private static final String NULL_SENTINEL = "\u0000__POCO_NULL__";

    private static final Map<String, String> MEM_SYSTEM = new ConcurrentHashMap<>();
    private static final Map<String, String> MEM_GLOBAL = new ConcurrentHashMap<>();
    private static final Map<String, String> MEM_SECURE = new ConcurrentHashMap<>();

    private static final Set<String> ABSENT_SYSTEM = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> ABSENT_GLOBAL = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> ABSENT_SECURE = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static volatile boolean sLoadedSystem;
    private static volatile boolean sLoadedGlobal;
    private static volatile boolean sLoadedSecure;

    private static final Set<String> DYNAMIC_OS_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "navigation_mode",
            "enabled_notification_listeners",
            "enabled_accessibility_services",
            "accessibility_enabled",
            "animator_duration_scale",
            "transition_animation_scale",
            "window_animation_scale",
            "font_scale",
            "device_provisioned",
            "user_setup_complete",
            "accelerometer_rotation",
            "user_rotation",
            "screen_brightness",
            "screen_brightness_mode",
            "screen_off_timeout",
            "airplane_mode_on",
            "haptic_feedback_enabled",
            "sound_effects_enabled",
            "lock_to_app_enabled",
            "default_input_method",
            "show_touches",
            "pointer_location",
            "development_settings_enabled",
            "night_display_activated",
            "ui_night_mode",
            "display_density_forced",
            "android_id",
            "time_12_24",
            "date_format",
            "stay_on_while_plugged_in",
            "adb_enabled",
            "low_power",
            "power_save_mode_open",
            "zen_mode",
            "auto_time",
            "auto_time_zone",
            "touch_exploration_enabled",
            "high_text_contrast_enabled",
            "accessibility_display_inversion_enabled",
            "accessibility_display_daltonizer_enabled"
    )));

    private static final Map<String, CopyOnWriteArrayList<ContentObserver>> LOCAL_OBSERVERS =
            new ConcurrentHashMap<>();

    private PocoSettingsCompat() {
    }

    static void registerLocalObserver(Uri uri, ContentObserver observer) {
        if (uri == null || observer == null || !"settings".equals(uri.getAuthority())) {
            return;
        }
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() < 2) {
            return;
        }
        String namespace = segments.get(0);
        if (!"system".equals(namespace) && !"global".equals(namespace) && !"secure".equals(namespace)) {
            return;
        }
        String key = namespace + "/" + segments.get(1);
        CopyOnWriteArrayList<ContentObserver> list = LOCAL_OBSERVERS.computeIfAbsent(
                key, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(observer)) {
            list.add(observer);
        }
    }

    static void unregisterLocalObserver(ContentObserver observer) {
        if (observer == null || LOCAL_OBSERVERS.isEmpty()) {
            return;
        }
        for (CopyOnWriteArrayList<ContentObserver> list : LOCAL_OBSERVERS.values()) {
            list.remove(observer);
        }
    }

    private static void notifyLocalObservers(String namespace, String name) {
        if (name == null || LOCAL_OBSERVERS.isEmpty()) {
            return;
        }
        CopyOnWriteArrayList<ContentObserver> list = LOCAL_OBSERVERS.get(namespace + "/" + name);
        if (list == null || list.isEmpty()) {
            return;
        }
        Uri uri = Uri.withAppendedPath(Uri.parse("content://settings/" + namespace), name);
        for (ContentObserver observer : list) {
            try {
                observer.dispatchChange(false, uri);
            } catch (Throwable ignored) {
            }
        }
    }

    private static SharedPreferences prefs(String namespace) {
        Context ctx = PocoCompatHelper.getAppContext();
        if (ctx == null) {
            return null;
        }
        String prefsName = "poco_compat_settings_" + namespace;
        try {
            return ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            try {
                return ctx.createDeviceProtectedStorageContext()
                        .getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static Map<String, String> memMap(String namespace) {
        switch (namespace) {
            case "global":
                return MEM_GLOBAL;
            case "secure":
                return MEM_SECURE;
            default:
                return MEM_SYSTEM;
        }
    }

    private static Set<String> absentSet(String namespace) {
        switch (namespace) {
            case "global":
                return ABSENT_GLOBAL;
            case "secure":
                return ABSENT_SECURE;
            default:
                return ABSENT_SYSTEM;
        }
    }

    private static boolean isLoaded(String namespace) {
        switch (namespace) {
            case "global":
                return sLoadedGlobal;
            case "secure":
                return sLoadedSecure;
            default:
                return sLoadedSystem;
        }
    }

    private static void markLoaded(String namespace) {
        switch (namespace) {
            case "global":
                sLoadedGlobal = true;
                break;
            case "secure":
                sLoadedSecure = true;
                break;
            default:
                sLoadedSystem = true;
                break;
        }
    }

    private static void ensureLoaded(String namespace) {
        if (isLoaded(namespace)) {
            return;
        }
        SharedPreferences sp = prefs(namespace);
        if (sp == null) {
            return;
        }
        try {
            Map<String, ?> all = sp.getAll();
            if (all != null && !all.isEmpty()) {
                Map<String, String> mem = memMap(namespace);
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    String k = entry.getKey();
                    Object v = entry.getValue();
                    if (k != null && v instanceof String) {
                        mem.putIfAbsent(k, (String) v);
                    }
                }
            }
            markLoaded(namespace);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isKnownAbsent(String namespace, String name) {
        return name != null && !DYNAMIC_OS_KEYS.contains(name) && absentSet(namespace).contains(name);
    }

    private static void markAbsentIfCacheable(String namespace, String name) {
        if (name != null && !DYNAMIC_OS_KEYS.contains(name)) {
            Set<String> set = absentSet(namespace);
            if (set.size() < 512) {
                set.add(name);
            }
        }
    }

    private static boolean hasLocal(String namespace, String name) {
        if (name == null) {
            return false;
        }
        ensureLoaded(namespace);
        return memMap(namespace).containsKey(name);
    }

    private static String readLocal(String namespace, String name) {
        if (name == null) {
            return null;
        }
        ensureLoaded(namespace);
        String val = memMap(namespace).get(name);
        if (val != null) {
            return NULL_SENTINEL.equals(val) ? null : val;
        }
        return null;
    }

    /**
     * Persists a local override and returns {@code true} ONLY when the stored value actually
     * changed, eliminating redundant disk writes and ContentObserver feedback loops.
     */
    private static boolean writeLocal(String namespace, String name, String value) {
        if (name == null) {
            return false;
        }
        ensureLoaded(namespace);
        String encoded = value == null ? NULL_SENTINEL : value;
        Map<String, String> mem = memMap(namespace);
        String previous = mem.get(name);
        if (encoded.equals(previous)) {
            return false;
        }
        mem.put(name, encoded);
        absentSet(namespace).remove(name);
        SharedPreferences sp = prefs(namespace);
        if (sp != null) {
            try {
                sp.edit().putString(name, encoded).apply();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    // ==================== Settings.System ====================

    public static boolean systemPutString(ContentResolver cr, String name, String value) {
        if (!writeLocal("system", name, value)) {
            return true;
        }
        boolean wroteToSystem = false;
        Context ctx = PocoCompatHelper.getAppContext();
        if (cr != null && ctx != null && Settings.System.canWrite(ctx)) {
            try {
                wroteToSystem = Settings.System.putString(cr, name, value);
            } catch (Throwable ignored) {
            }
        }
        if (!wroteToSystem) {
            notifyLocalObservers("system", name);
        }
        return true;
    }

    public static boolean systemPutInt(ContentResolver cr, String name, int value) {
        return systemPutString(cr, name, Integer.toString(value));
    }

    public static boolean systemPutLong(ContentResolver cr, String name, long value) {
        return systemPutString(cr, name, Long.toString(value));
    }

    public static boolean systemPutFloat(ContentResolver cr, String name, float value) {
        return systemPutString(cr, name, Float.toString(value));
    }

    public static String systemGetString(ContentResolver cr, String name) {
        if (hasLocal("system", name)) {
            return readLocal("system", name);
        }
        if (cr == null || name == null || isKnownAbsent("system", name)) {
            return null;
        }
        try {
            String result = Settings.System.getString(cr, name);
            if (result == null) {
                markAbsentIfCacheable("system", name);
            }
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int systemGetInt(ContentResolver cr, String name, int def) {
        if (hasLocal("system", name)) {
            String v = readLocal("system", name);
            if (v != null) {
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        if (cr == null || name == null || isKnownAbsent("system", name)) {
            return def;
        }
        try {
            return Settings.System.getInt(cr, name);
        } catch (Settings.SettingNotFoundException ignored) {
            markAbsentIfCacheable("system", name);
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static int systemGetInt(ContentResolver cr, String name) throws Settings.SettingNotFoundException {
        if (hasLocal("system", name)) {
            String v = readLocal("system", name);
            if (v != null) {
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                }
            }
            throw new Settings.SettingNotFoundException(name);
        }
        if (cr == null || name == null || isKnownAbsent("system", name)) {
            throw new Settings.SettingNotFoundException(String.valueOf(name));
        }
        try {
            return Settings.System.getInt(cr, name);
        } catch (Settings.SettingNotFoundException e) {
            markAbsentIfCacheable("system", name);
            throw e;
        }
    }

    public static long systemGetLong(ContentResolver cr, String name, long def) {
        if (hasLocal("system", name)) {
            String v = readLocal("system", name);
            if (v != null) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        if (cr == null || name == null || isKnownAbsent("system", name)) {
            return def;
        }
        try {
            return Settings.System.getLong(cr, name);
        } catch (Settings.SettingNotFoundException ignored) {
            markAbsentIfCacheable("system", name);
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static long systemGetLong(ContentResolver cr, String name) throws Settings.SettingNotFoundException {
        if (hasLocal("system", name)) {
            String v = readLocal("system", name);
            if (v != null) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                }
            }
            throw new Settings.SettingNotFoundException(name);
        }
        if (cr == null || name == null || isKnownAbsent("system", name)) {
            throw new Settings.SettingNotFoundException(String.valueOf(name));
        }
        try {
            return Settings.System.getLong(cr, name);
        } catch (Settings.SettingNotFoundException e) {
            markAbsentIfCacheable("system", name);
            throw e;
        }
    }

    public static float systemGetFloat(ContentResolver cr, String name, float def) {
        if (hasLocal("system", name)) {
            String v = readLocal("system", name);
            if (v != null) {
                try {
                    return Float.parseFloat(v);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        if (cr == null || name == null || isKnownAbsent("system", name)) {
            return def;
        }
        try {
            return Settings.System.getFloat(cr, name);
        } catch (Settings.SettingNotFoundException ignored) {
            markAbsentIfCacheable("system", name);
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static float systemGetFloat(ContentResolver cr, String name) throws Settings.SettingNotFoundException {
        if (hasLocal("system", name)) {
            String v = readLocal("system", name);
            if (v != null) {
                try {
                    return Float.parseFloat(v);
                } catch (NumberFormatException ignored) {
                }
            }
            throw new Settings.SettingNotFoundException(name);
        }
        if (cr == null || name == null || isKnownAbsent("system", name)) {
            throw new Settings.SettingNotFoundException(String.valueOf(name));
        }
        try {
            return Settings.System.getFloat(cr, name);
        } catch (Settings.SettingNotFoundException e) {
            markAbsentIfCacheable("system", name);
            throw e;
        }
    }

    // ==================== Settings.Global ====================

    public static boolean globalPutString(ContentResolver cr, String name, String value) {
        if (writeLocal("global", name, value)) {
            notifyLocalObservers("global", name);
        }
        return true;
    }

    public static boolean globalPutInt(ContentResolver cr, String name, int value) {
        return globalPutString(cr, name, Integer.toString(value));
    }

    public static boolean globalPutLong(ContentResolver cr, String name, long value) {
        return globalPutString(cr, name, Long.toString(value));
    }

    public static boolean globalPutFloat(ContentResolver cr, String name, float value) {
        return globalPutString(cr, name, Float.toString(value));
    }

    public static String globalGetString(ContentResolver cr, String name) {
        if (hasLocal("global", name)) {
            return readLocal("global", name);
        }
        if (cr == null || name == null || isKnownAbsent("global", name)) {
            return null;
        }
        try {
            String result = Settings.Global.getString(cr, name);
            if (result == null) {
                markAbsentIfCacheable("global", name);
            }
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int globalGetInt(ContentResolver cr, String name, int def) {
        if (hasLocal("global", name)) {
            String v = readLocal("global", name);
            if (v != null) {
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        if (cr == null || name == null || isKnownAbsent("global", name)) {
            return def;
        }
        try {
            return Settings.Global.getInt(cr, name);
        } catch (Settings.SettingNotFoundException ignored) {
            markAbsentIfCacheable("global", name);
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static int globalGetInt(ContentResolver cr, String name) throws Settings.SettingNotFoundException {
        if (hasLocal("global", name)) {
            String v = readLocal("global", name);
            if (v != null) {
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                }
            }
            throw new Settings.SettingNotFoundException(name);
        }
        if (cr == null || name == null || isKnownAbsent("global", name)) {
            throw new Settings.SettingNotFoundException(String.valueOf(name));
        }
        try {
            return Settings.Global.getInt(cr, name);
        } catch (Settings.SettingNotFoundException e) {
            markAbsentIfCacheable("global", name);
            throw e;
        }
    }

    public static long globalGetLong(ContentResolver cr, String name, long def) {
        if (hasLocal("global", name)) {
            String v = readLocal("global", name);
            if (v != null) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        if (cr == null || name == null || isKnownAbsent("global", name)) {
            return def;
        }
        try {
            return Settings.Global.getLong(cr, name);
        } catch (Settings.SettingNotFoundException ignored) {
            markAbsentIfCacheable("global", name);
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static long globalGetLong(ContentResolver cr, String name) throws Settings.SettingNotFoundException {
        if (hasLocal("global", name)) {
            String v = readLocal("global", name);
            if (v != null) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                }
            }
            throw new Settings.SettingNotFoundException(name);
        }
        if (cr == null || name == null || isKnownAbsent("global", name)) {
            throw new Settings.SettingNotFoundException(String.valueOf(name));
        }
        try {
            return Settings.Global.getLong(cr, name);
        } catch (Settings.SettingNotFoundException e) {
            markAbsentIfCacheable("global", name);
            throw e;
        }
    }

    public static float globalGetFloat(ContentResolver cr, String name, float def) {
        if (hasLocal("global", name)) {
            String v = readLocal("global", name);
            if (v != null) {
                try {
                    return Float.parseFloat(v);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        if (cr == null || name == null || isKnownAbsent("global", name)) {
            return def;
        }
        try {
            return Settings.Global.getFloat(cr, name);
        } catch (Settings.SettingNotFoundException ignored) {
            markAbsentIfCacheable("global", name);
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static float globalGetFloat(ContentResolver cr, String name) throws Settings.SettingNotFoundException {
        if (hasLocal("global", name)) {
            String v = readLocal("global", name);
            if (v != null) {
                try {
                    return Float.parseFloat(v);
                } catch (NumberFormatException ignored) {
                }
            }
            throw new Settings.SettingNotFoundException(name);
        }
        if (cr == null || name == null || isKnownAbsent("global", name)) {
            throw new Settings.SettingNotFoundException(String.valueOf(name));
        }
        try {
            return Settings.Global.getFloat(cr, name);
        } catch (Settings.SettingNotFoundException e) {
            markAbsentIfCacheable("global", name);
            throw e;
        }
    }

    // ==================== Settings.Secure ====================

    public static boolean securePutString(ContentResolver cr, String name, String value) {
        if (writeLocal("secure", name, value)) {
            notifyLocalObservers("secure", name);
        }
        return true;
    }

    public static boolean securePutInt(ContentResolver cr, String name, int value) {
        return securePutString(cr, name, Integer.toString(value));
    }

    public static boolean securePutLong(ContentResolver cr, String name, long value) {
        return securePutString(cr, name, Long.toString(value));
    }

    public static boolean securePutFloat(ContentResolver cr, String name, float value) {
        return securePutString(cr, name, Float.toString(value));
    }

    public static String secureGetString(ContentResolver cr, String name) {
        if (hasLocal("secure", name)) {
            return readLocal("secure", name);
        }
        if (cr == null || name == null || isKnownAbsent("secure", name)) {
            return null;
        }
        try {
            String result = Settings.Secure.getString(cr, name);
            if (result == null) {
                markAbsentIfCacheable("secure", name);
            }
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int secureGetInt(ContentResolver cr, String name, int def) {
        if (hasLocal("secure", name)) {
            String v = readLocal("secure", name);
            if (v != null) {
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        if (cr == null || name == null || isKnownAbsent("secure", name)) {
            return def;
        }
        try {
            return Settings.Secure.getInt(cr, name);
        } catch (Settings.SettingNotFoundException ignored) {
            markAbsentIfCacheable("secure", name);
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static int secureGetInt(ContentResolver cr, String name) throws Settings.SettingNotFoundException {
        if (hasLocal("secure", name)) {
            String v = readLocal("secure", name);
            if (v != null) {
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                }
            }
            throw new Settings.SettingNotFoundException(name);
        }
        if (cr == null || name == null || isKnownAbsent("secure", name)) {
            throw new Settings.SettingNotFoundException(String.valueOf(name));
        }
        try {
            return Settings.Secure.getInt(cr, name);
        } catch (Settings.SettingNotFoundException e) {
            markAbsentIfCacheable("secure", name);
            throw e;
        }
    }

    public static int secureGetIntForUser(ContentResolver cr, String name, int def, int userHandle) {
        return secureGetInt(cr, name, def);
    }

    public static long secureGetLong(ContentResolver cr, String name, long def) {
        if (hasLocal("secure", name)) {
            String v = readLocal("secure", name);
            if (v != null) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        if (cr == null || name == null || isKnownAbsent("secure", name)) {
            return def;
        }
        try {
            return Settings.Secure.getLong(cr, name);
        } catch (Settings.SettingNotFoundException ignored) {
            markAbsentIfCacheable("secure", name);
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static long secureGetLong(ContentResolver cr, String name) throws Settings.SettingNotFoundException {
        if (hasLocal("secure", name)) {
            String v = readLocal("secure", name);
            if (v != null) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                }
            }
            throw new Settings.SettingNotFoundException(name);
        }
        if (cr == null || name == null || isKnownAbsent("secure", name)) {
            throw new Settings.SettingNotFoundException(String.valueOf(name));
        }
        try {
            return Settings.Secure.getLong(cr, name);
        } catch (Settings.SettingNotFoundException e) {
            markAbsentIfCacheable("secure", name);
            throw e;
        }
    }

    public static float secureGetFloat(ContentResolver cr, String name, float def) {
        if (hasLocal("secure", name)) {
            String v = readLocal("secure", name);
            if (v != null) {
                try {
                    return Float.parseFloat(v);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        if (cr == null || name == null || isKnownAbsent("secure", name)) {
            return def;
        }
        try {
            return Settings.Secure.getFloat(cr, name);
        } catch (Settings.SettingNotFoundException ignored) {
            markAbsentIfCacheable("secure", name);
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static float secureGetFloat(ContentResolver cr, String name) throws Settings.SettingNotFoundException {
        if (hasLocal("secure", name)) {
            String v = readLocal("secure", name);
            if (v != null) {
                try {
                    return Float.parseFloat(v);
                } catch (NumberFormatException ignored) {
                }
            }
            throw new Settings.SettingNotFoundException(name);
        }
        if (cr == null || name == null || isKnownAbsent("secure", name)) {
            throw new Settings.SettingNotFoundException(String.valueOf(name));
        }
        try {
            return Settings.Secure.getFloat(cr, name);
        } catch (Settings.SettingNotFoundException e) {
            markAbsentIfCacheable("secure", name);
            throw e;
        }
    }

    // ==================== Convenience Boolean Helpers for MiuiSettings ====================

    public static boolean systemGetBoolean(ContentResolver cr, String name, boolean def) {
        return systemGetInt(cr, name, def ? 1 : 0) != 0;
    }

    public static boolean systemPutBoolean(ContentResolver cr, String name, boolean value) {
        return systemPutInt(cr, name, value ? 1 : 0);
    }

    public static boolean globalGetBoolean(ContentResolver cr, String name) {
        return globalGetInt(cr, name, 0) != 0;
    }

    public static boolean globalPutBoolean(ContentResolver cr, String name, boolean value) {
        return globalPutInt(cr, name, value ? 1 : 0);
    }
}
