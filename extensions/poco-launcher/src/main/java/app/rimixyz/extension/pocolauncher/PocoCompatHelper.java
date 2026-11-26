package app.rimixyz.extension.pocolauncher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Public-SDK runtime compatibility helpers for POCO Launcher running rootless on non-Xiaomi
 * Android 14+ devices (e.g. Samsung One UI).
 */
@SuppressWarnings("unused")
public final class PocoCompatHelper {
    private static final String TAG = "PocoCompat";
    public static final int REQUEST_BIND_APPWIDGET = 11;
    private static final int REQUEST_CREATE_APPWIDGET = 5;
    private static final int EXTRA_FLAG_STATUS_BAR_DARK_MODE = 16;
    private static final String NOTIFICATION_LISTENER_CLASS =
            "com.miui.home.launcher.notification.NotificationListener";
    private static final String LOCK_ACCESSIBILITY_SERVICE_CLASS =
            "app.rimixyz.extension.pocolauncher.PocoLockScreenService";
    private static final String PREF_KEY_NOTIFICATION_ACCESS =
            "poco_compat_notification_access";
    private static final String PREF_KEY_ICON_CUSTOMIZE =
            "poco_compat_icon_customize";
    private static final String PREF_KEY_HIDE_APPS_SHORTCUT =
            "poco_compat_hide_apps";
    private static final String PREF_KEY_DEFAULT_HOME =
            "poco_compat_default_home";

    private static volatile Context sAppContext;
    private static volatile Class<?> sAutoLayoutHostClass;
    private static volatile boolean sAutoLayoutHostResolved;
    private static volatile boolean sHideAppsMigrationChecked;
    private static volatile boolean sMemoryCallbacksRegistered;
    private static volatile Display sDefaultDisplay;
    private static volatile int sCachedTotalRamGb;
    private static volatile Method sGetLauncherMethod;
    private static volatile boolean sGetLauncherResolved;
    private static volatile Method sSysPropGet;
    private static volatile Method sSysPropGetInt;
    private static volatile Method sSysPropGetBool;
    private static volatile boolean sSysPropResolved;
    private static final ConcurrentHashMap<Object, CancellationSignal> ACTIVE_BIOMETRIC_SIGNALS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> SYSPROP_RO_CACHE =
            new ConcurrentHashMap<>();
    private static final Set<String> ABSENT_PROVIDER_AUTHORITIES =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final ConcurrentHashMap<String, Boolean> INSTALLED_PKG_CACHE =
            new ConcurrentHashMap<>();

    // ==================== Layout / setFrame Bridge ====================

    private static final Set<String> BASE_LAYOUT_CLASSES = new HashSet<>(Arrays.asList(
            "com.miui.launcher.views.LauncherViewGroup",
            "com.miui.home.customview.LauncherFrameLayout",
            "com.miui.launcher.views.LauncherRelativeLayout",
            "com.miui.launcher.views.LauncherWidgetHostView"
    ));

    private static final class BridgeInfo {
        final boolean needed;
        final boolean isAutoLayoutHost;
        final Method miuiSetFrameMethod;

        BridgeInfo(boolean needed, boolean isAutoLayoutHost, Method miuiSetFrameMethod) {
            this.needed = needed;
            this.isAutoLayoutHost = isAutoLayoutHost;
            this.miuiSetFrameMethod = miuiSetFrameMethod;
        }
    }

    private static final ConcurrentHashMap<Class<?>, BridgeInfo> BRIDGE_CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<IdentityHashMap<View, Boolean>> IN_LAYOUT_BRIDGE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    // ==================== Pending AppWidget Bind & Configure State ====================

    private static final class PendingBindWidget {
        final int appWidgetId;
        final Object widgetInfo;
        final long screenId;
        final Object pinSuccessCallback;
        final Bundle pinOptions;

        PendingBindWidget(int appWidgetId, Object widgetInfo, long screenId) {
            this(appWidgetId, widgetInfo, screenId, null, null);
        }

        PendingBindWidget(
                int appWidgetId,
                Object widgetInfo,
                long screenId,
                Object pinSuccessCallback,
                Bundle pinOptions
        ) {
            this.appWidgetId = appWidgetId;
            this.widgetInfo = widgetInfo;
            this.screenId = screenId;
            this.pinSuccessCallback = pinSuccessCallback;
            this.pinOptions = pinOptions;
        }
    }

    private static volatile PendingBindWidget sPendingBindWidget;
    private static volatile PendingBindWidget sPendingConfigureWidget;

    private PocoCompatHelper() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        sAppContext = app != null ? app : context;
        registerMemoryTrimCallbacks(sAppContext);
    }

    private static void registerMemoryTrimCallbacks(Context ctx) {
        if (sMemoryCallbacksRegistered || ctx == null) {
            return;
        }
        synchronized (PocoCompatHelper.class) {
            if (sMemoryCallbacksRegistered) {
                return;
            }
            sMemoryCallbacksRegistered = true;
            try {
                ctx.registerComponentCallbacks(new ComponentCallbacks2() {
                    @Override
                    public void onTrimMemory(int level) {
                        onAppTrimMemory(level);
                    }

                    @Override
                    public void onConfigurationChanged(Configuration newConfig) {
                        sDefaultDisplay = null;
                    }

                    @Override
                    public void onLowMemory() {
                        onAppTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    public static Context getAppContext() {
        Context ctx = sAppContext;
        if (ctx != null) {
            return ctx;
        }
        try {
            Class<?> appClass = Class.forName("com.miui.home.launcher.Application");
            Method getInstance = appClass.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            if (instance instanceof Context) {
                sAppContext = (Context) instance;
                return sAppContext;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentApp = atClass.getMethod("currentApplication");
            Object app = currentApp.invoke(null);
            if (app instanceof Application) {
                sAppContext = (Application) app;
                return sAppContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== View.setFrame Public-SDK Replacement ====================

    /**
     * Public-SDK replacement for {@code super.setFrame(l, t, r, b)} on ViewGroup subclasses
     * (where {@code View.setFrame} is {@code max-target-p} blocked on API 34+).
     */
    public static boolean applyViewFrame(View view, int left, int top, int right, int bottom) {
        if (view == null) {
            return false;
        }
        boolean changed = view.getLeft() != left
                || view.getTop() != top
                || view.getRight() != right
                || view.getBottom() != bottom;
        view.setLeftTopRightBottom(left, top, right, bottom);
        return changed;
    }

    /**
     * Attaches a public {@link View.OnLayoutChangeListener} ONLY to views whose concrete class
     * overrides {@code miuiSetFrame}, {@code onSetFrame}, {@code correctFrame}, or
     * {@code overrideSetFrame}, preserving {@code AutoLayoutAnimation}, {@code UninstallDialog},
     * and {@code ScreenView.SlideBar} without calling or overriding hidden {@code View.setFrame}.
     */
    public static void attachLayoutBridge(View view) {
        if (view == null) {
            return;
        }
        final BridgeInfo info = resolveBridgeInfo(view.getClass());
        if (!info.needed || info.miuiSetFrameMethod == null) {
            return;
        }
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v,
                    int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom
            ) {
                IdentityHashMap<View, Boolean> active = IN_LAYOUT_BRIDGE.get();
                if (active != null && active.containsKey(v)) {
                    return;
                }
                boolean changed = left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom;
                if (!changed) {
                    return;
                }
                if (active != null) {
                    active.put(v, Boolean.TRUE);
                }
                try {
                    if (info.isAutoLayoutHost && (oldRight != oldLeft || oldBottom != oldTop)) {
                        // Restore previous frame briefly ONLY for AutoLayoutAnimation$HostView so
                        // sliding animations observe the real old bounds delta without double
                        // invalidation on other View subclasses (ScreenView$SlideBar, UninstallDialog).
                        v.setLeftTopRightBottom(oldLeft, oldTop, oldRight, oldBottom);
                    }
                    info.miuiSetFrameMethod.invoke(v, left, top, right, bottom);
                } catch (Throwable t) {
                    Log.w(TAG, "Layout bridge invocation failed for " + v.getClass().getName(), t);
                    v.setLeftTopRightBottom(left, top, right, bottom);
                } finally {
                    if (active != null) {
                        active.remove(v);
                    }
                }
            }
        });
    }

    private static Class<?> resolveAutoLayoutHostClass() {
        if (!sAutoLayoutHostResolved) {
            try {
                sAutoLayoutHostClass = Class.forName("com.miui.home.common.animate.AutoLayoutAnimation$HostView");
            } catch (Throwable ignored) {
                sAutoLayoutHostClass = null;
            }
            sAutoLayoutHostResolved = true;
        }
        return sAutoLayoutHostClass;
    }

    private static BridgeInfo resolveBridgeInfo(Class<?> clazz) {
        BridgeInfo cached = BRIDGE_CACHE.get(clazz);
        if (cached != null) {
            return cached;
        }
        boolean overridesHook = hasCustomLayoutHook(clazz);
        Method miuiSetFrame = null;
        if (overridesHook) {
            miuiSetFrame = findMethodInHierarchy(
                    clazz, "miuiSetFrame", int.class, int.class, int.class, int.class);
        }
        Class<?> hostCls = resolveAutoLayoutHostClass();
        boolean isHost = hostCls != null && hostCls.isAssignableFrom(clazz);
        BridgeInfo info = new BridgeInfo(overridesHook && miuiSetFrame != null, isHost, miuiSetFrame);
        BRIDGE_CACHE.put(clazz, info);
        return info;
    }

    private static boolean hasCustomLayoutHook(Class<?> clazz) {
        Class<?> cur = clazz;
        while (cur != null && !BASE_LAYOUT_CLASSES.contains(cur.getName()) && cur != View.class) {
            if (declaresMethod(cur, "miuiSetFrame", int.class, int.class, int.class, int.class)
                    || declaresMethod(cur, "onSetFrame", int.class, int.class, int.class, int.class)
                    || declaresMethod(cur, "correctFrame", int.class, int.class, int.class, int.class)
                    || declaresMethod(cur, "overrideSetFrame")) {
                return true;
            }
            cur = cur.getSuperclass();
        }
        return false;
    }

    private static boolean declaresMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            clazz.getDeclaredMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... params) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                Method m = cur.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    // ==================== Window / Display / StatusBar ====================

    public static void setExtraFlag(Window window, int flags, int mask) {
        if (window == null) {
            return;
        }
        if ((mask & EXTRA_FLAG_STATUS_BAR_DARK_MODE) != 0) {
            try {
                View decor = window.getDecorView();
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    boolean darkIcons = (flags & EXTRA_FLAG_STATUS_BAR_DARK_MODE) != 0;
                    controller.setSystemBarsAppearance(
                            darkIcons ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    );
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static int getRotation() {
        Display cached = sDefaultDisplay;
        if (cached != null) {
            try {
                if (cached.isValid()) {
                    return cached.getRotation();
                }
            } catch (Throwable ignored) {
            }
            sDefaultDisplay = null;
        }
        Context ctx = getAppContext();
        if (ctx != null) {
            try {
                DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                    if (d != null) {
                        sDefaultDisplay = d;
                        return d.getRotation();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return Surface.ROTATION_0;
    }

    public static int getNaturalWidth(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx != null) {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    Rect bounds = wm.getMaximumWindowMetrics().getBounds();
                    int rot = getRotation();
                    if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
                        return bounds.height();
                    }
                    return bounds.width();
                }
            } catch (Throwable ignored) {
            }
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return Math.min(dm.widthPixels, dm.heightPixels);
        }
        return 1080;
    }

    public static Point getInitialDisplaySize() {
        Context ctx = getAppContext();
        if (ctx != null) {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    Rect bounds = wm.getMaximumWindowMetrics().getBounds();
                    return new Point(bounds.width(), bounds.height());
                }
            } catch (Throwable ignored) {
            }
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return new Point(dm.widthPixels, dm.heightPixels);
        }
        return null;
    }

    public static void handleStatusBarMessage(Message msg) {
        if (msg != null && msg.obj instanceof Runnable) {
            try {
                ((Runnable) msg.obj).run();
            } catch (Throwable t) {
                Log.w(TAG, "StatusBar callback error", t);
            }
        }
    }

    // ==================== LauncherUtils / PortableUtils / ContentProvider ====================

    public static void startActivityAsUser(Context context, Intent intent, Bundle options, UserHandle user) {
        if (context == null || intent == null) {
            return;
        }
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent, options);
    }

    public static void registerReceiverAsUser(
            Context context,
            BroadcastReceiver receiver,
            UserHandle user,
            IntentFilter filter,
            String broadcastPermission,
            Handler scheduler
    ) {
        registerReceiverAsUser(context, receiver, user, filter, broadcastPermission, scheduler, Context.RECEIVER_EXPORTED);
    }

    public static void registerReceiverAsUser(
            Context context,
            BroadcastReceiver receiver,
            UserHandle user,
            IntentFilter filter,
            String broadcastPermission,
            Handler scheduler,
            int flags
    ) {
        if (context == null || receiver == null || filter == null) {
            return;
        }
        int effectiveFlags = (flags & (Context.RECEIVER_EXPORTED | Context.RECEIVER_NOT_EXPORTED)) != 0
                ? flags
                : Context.RECEIVER_EXPORTED;
        try {
            context.registerReceiver(receiver, filter, broadcastPermission, scheduler, effectiveFlags);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiverAsUser fallback failed", t);
        }
    }

    public static Intent registerReceiverCompat(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (context == null || receiver == null || filter == null) {
            return null;
        }
        try {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiverCompat failed", t);
            return null;
        }
    }

    public static File getSharedPrefsFile(Context context, String name) {
        File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        return new File(prefsDir, name + ".xml");
    }

    public static boolean isPackageDisableAsUser(Context context, String packageName, int userId) {
        if (context == null || packageName == null) {
            return false;
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return info != null && info.applicationInfo != null && !info.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int moveFiles(File sourceDir, File targetDir, String prefix) {
        if (sourceDir == null || targetDir == null || !sourceDir.isDirectory()) {
            return 0;
        }
        File[] files = sourceDir.listFiles((dir, name) -> prefix == null || name.startsWith(prefix));
        if (files == null || files.length == 0) {
            return 0;
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        int moved = 0;
        byte[] buffer = new byte[8192];
        for (File src : files) {
            if (!src.isFile()) {
                continue;
            }
            File dst = new File(targetDir, src.getName());
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                out.flush();
            } catch (IOException e) {
                Log.w(TAG, "Failed to migrate " + src, e);
                moved = -1;
                continue;
            }
            if (!src.delete()) {
                moved = -1;
            } else if (moved != -1) {
                moved++;
            }
        }
        return moved;
    }

    private static boolean isKnownAbsentAuthority(String authority) {
        return authority != null && !"settings".equals(authority)
                && ABSENT_PROVIDER_AUTHORITIES.contains(authority);
    }

    private static void markAbsentAuthority(String authority) {
        if (authority != null && !authority.isEmpty() && !"settings".equals(authority)
                && ABSENT_PROVIDER_AUTHORITIES.size() < 128) {
            ABSENT_PROVIDER_AUTHORITIES.add(authority);
        }
    }

    public static boolean safeIsProviderExists(Context context, Uri uri) {
        if (context == null || uri == null) {
            return false;
        }
        String auth = uri.getAuthority();
        if (isKnownAbsentAuthority(auth)) {
            return false;
        }
        try (ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(uri)) {
            if (client != null) {
                return true;
            }
            markAbsentAuthority(auth);
            return false;
        } catch (Throwable ignored) {
            markAbsentAuthority(auth);
            return false;
        }
    }

    public static void safeUpdateProviderData(Context context, Uri uri, ContentValues values) {
        if (context == null || uri == null || isKnownAbsentAuthority(uri.getAuthority())) {
            return;
        }
        try {
            context.getContentResolver().update(uri, values, null, null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Wraps {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver)} so
     * missing Xiaomi-only ContentProviders (e.g. {@code com.android.settings.cloud.CloudSettings})
     * do not throw {@link SecurityException} on Android 11+ and abort {@code BaseLauncher.onCreate}.
     * Also tracks {@code content://settings/*} observers so locally persisted
     * {@link PocoSettingsCompat} changes dispatch immediately.
     */
    public static void safeRegisterContentObserver(
            ContentResolver cr,
            Uri uri,
            boolean notifyForDescendants,
            ContentObserver observer
    ) {
        if (cr == null || uri == null || observer == null) {
            return;
        }
        PocoSettingsCompat.registerLocalObserver(uri, observer);
        String auth = uri.getAuthority();
        if (isKnownAbsentAuthority(auth)) {
            return;
        }
        try {
            cr.registerContentObserver(uri, notifyForDescendants, observer);
        } catch (Throwable t) {
            markAbsentAuthority(auth);
            Log.w(TAG, "Ignoring ContentObserver registration for " + uri + ": " + t.getMessage());
        }
    }

    public static void safeUnregisterContentObserver(ContentResolver cr, ContentObserver observer) {
        if (observer == null) {
            return;
        }
        PocoSettingsCompat.unregisterLocalObserver(observer);
        if (cr == null) {
            return;
        }
        try {
            cr.unregisterContentObserver(observer);
        } catch (Throwable ignored) {
        }
    }

    public static Bundle safeContentResolverCall(
            ContentResolver cr,
            Uri uri,
            String method,
            String arg,
            Bundle extras
    ) {
        if (cr == null || uri == null || method == null) {
            return null;
        }
        String auth = uri.getAuthority();
        if (isKnownAbsentAuthority(auth)) {
            return null;
        }
        try {
            return cr.call(uri, method, arg, extras);
        } catch (SecurityException | IllegalArgumentException t) {
            markAbsentAuthority(auth);
            Log.w(TAG, "Ignoring missing/denied ContentProvider call " + uri + "#" + method + ": " + t.getMessage());
            return null;
        }
    }

    public static Bundle safeContentResolverCall(
            ContentResolver cr,
            String authority,
            String method,
            String arg,
            Bundle extras
    ) {
        if (cr == null || authority == null || method == null) {
            return null;
        }
        if (isKnownAbsentAuthority(authority)) {
            return null;
        }
        try {
            return cr.call(authority, method, arg, extras);
        } catch (SecurityException | IllegalArgumentException t) {
            markAbsentAuthority(authority);
            Log.w(TAG, "Ignoring missing/denied ContentProvider call " + authority + "#" + method + ": " + t.getMessage());
            return null;
        }
    }

    public static void safeNotifyChange(ContentResolver cr, Uri uri, ContentObserver observer) {
        if (cr == null || uri == null) {
            return;
        }
        try {
            cr.notifyChange(uri, observer);
        } catch (SecurityException | IllegalArgumentException t) {
            Log.w(TAG, "Ignoring notifyChange for " + uri + ": " + t.getMessage());
        }
    }

    public static void safeNotifyChange(
            ContentResolver cr,
            Uri uri,
            ContentObserver observer,
            boolean syncToNetwork
    ) {
        if (cr == null || uri == null) {
            return;
        }
        try {
            cr.notifyChange(uri, observer, syncToNetwork);
        } catch (SecurityException | IllegalArgumentException t) {
            Log.w(TAG, "Ignoring notifyChange for " + uri + ": " + t.getMessage());
        }
    }

    public static void safeNotifyChange(
            ContentResolver cr,
            Uri uri,
            ContentObserver observer,
            int flags
    ) {
        if (cr == null || uri == null) {
            return;
        }
        try {
            cr.notifyChange(uri, observer, flags);
        } catch (SecurityException | IllegalArgumentException t) {
            Log.w(TAG, "Ignoring notifyChange for " + uri + ": " + t.getMessage());
        }
    }

    // ==================== Android 14+ Privilege & Rootless Guards ====================

    private static Throwable unwrapInvocation(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    /**
     * Safely creates an {@code android.app.prediction.AppPredictor} session. On non-Xiaomi
     * rootless devices without {@code PACKAGE_USAGE_STATS}, {@code createAppPredictionSession}
     * throws {@link SecurityException}; returning {@code null} lets
     * {@code RecommendAppProviderManager} fall back cleanly to local {@code MiuiRecommendApp}.
     */
    public static Object safeCreateAppPredictionSession(
            Object appPredictionManager,
            Context context,
            int predictedTargetCount
    ) {
        if (appPredictionManager == null || context == null) {
            return null;
        }
        try {
            Class<?> builderClass = Class.forName("android.app.prediction.AppPredictionContext$Builder");
            Constructor<?> ctor = builderClass.getConstructor(Context.class);
            Object builder = ctor.newInstance(context);
            builderClass.getMethod("setUiSurface", String.class).invoke(builder, "home");
            builderClass.getMethod("setPredictedTargetCount", int.class).invoke(builder, predictedTargetCount);
            Object predictionContext = builderClass.getMethod("build").invoke(builder);
            Class<?> ctxClass = Class.forName("android.app.prediction.AppPredictionContext");
            Method createMethod = appPredictionManager.getClass()
                    .getMethod("createAppPredictionSession", ctxClass);
            return createMethod.invoke(appPredictionManager, predictionContext);
        } catch (Throwable t) {
            Throwable cause = unwrapInvocation(t);
            Log.w(TAG, "AppPrediction session unavailable (falling back to local recommendations): "
                    + cause.getMessage());
            return null;
        }
    }

    public static void safeRegisterPredictionUpdates(
            Object predictor,
            Executor executor,
            Object callback
    ) {
        if (predictor == null || executor == null || callback == null) {
            return;
        }
        try {
            Class<?> cbClass = Class.forName("android.app.prediction.AppPredictor$Callback");
            predictor.getClass()
                    .getMethod("registerPredictionUpdates", Executor.class, cbClass)
                    .invoke(predictor, executor, callback);
        } catch (Throwable t) {
            Log.w(TAG, "Ignoring AppPredictor.registerPredictionUpdates failure: "
                    + unwrapInvocation(t).getMessage());
        }
    }

    public static void safeUnregisterPredictionUpdates(Object predictor, Object callback) {
        if (predictor == null || callback == null) {
            return;
        }
        try {
            Class<?> cbClass = Class.forName("android.app.prediction.AppPredictor$Callback");
            predictor.getClass()
                    .getMethod("unregisterPredictionUpdates", cbClass)
                    .invoke(predictor, callback);
        } catch (Throwable t) {
            Log.w(TAG, "Ignoring AppPredictor.unregisterPredictionUpdates failure: "
                    + unwrapInvocation(t).getMessage());
        }
    }

    public static void safeRequestPredictionUpdate(Object predictor) {
        if (predictor == null) {
            return;
        }
        try {
            predictor.getClass().getMethod("requestPredictionUpdate").invoke(predictor);
        } catch (Throwable t) {
            Log.w(TAG, "Ignoring AppPredictor.requestPredictionUpdate failure: "
                    + unwrapInvocation(t).getMessage());
        }
    }

    public static void safeNotifyAppTargetEvent(Object predictor, Object event) {
        if (predictor == null || event == null) {
            return;
        }
        try {
            Class<?> eventClass = Class.forName("android.app.prediction.AppTargetEvent");
            predictor.getClass()
                    .getMethod("notifyAppTargetEvent", eventClass)
                    .invoke(predictor, event);
        } catch (Throwable t) {
            Log.w(TAG, "Ignoring AppPredictor.notifyAppTargetEvent failure: "
                    + unwrapInvocation(t).getMessage());
        }
    }

    /**
     * Android 14+ ({@code targetSdk >= 34}) throws {@link SecurityException} on
     * {@link AlarmManager#setExact} without {@code SCHEDULE_EXACT_ALARM}. Falls back to inexact
     * {@link AlarmManager#set} when exact alarms are not granted.
     */
    public static void safeSetExactAlarm(
            AlarmManager am,
            int type,
            long triggerAtMillis,
            PendingIntent operation
    ) {
        if (am == null || operation == null) {
            return;
        }
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExact(type, triggerAtMillis, operation);
            } else {
                am.set(type, triggerAtMillis, operation);
            }
        } catch (Throwable t) {
            Log.w(TAG, "AlarmManager.setExact fallback to set(): " + t.getMessage());
            try {
                am.set(type, triggerAtMillis, operation);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Android 14+ ({@code targetSdk >= 34}) throws {@link SecurityException} on 2-arg
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)} for non-system broadcasts.
     * Preserves {@code receiver == null} sticky-intent queries and passes
     * {@link Context#RECEIVER_EXPORTED} for actual receivers.
     */
    public static Intent safeRegisterReceiver(
            Context context,
            BroadcastReceiver receiver,
            IntentFilter filter
    ) {
        if (context == null || filter == null) {
            return null;
        }
        try {
            if (receiver == null) {
                return context.registerReceiver(null, filter);
            }
            return context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "Ignoring registerReceiver failure: " + t.getMessage());
            return null;
        }
    }

    public static void safeSendBroadcast(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        try {
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Ignoring sendBroadcast(" + intent.getAction() + ") failure: " + t.getMessage());
        }
    }

    public static void safeSendBroadcast(Context context, Intent intent, String receiverPermission) {
        if (context == null || intent == null) {
            return;
        }
        try {
            context.sendBroadcast(intent, receiverPermission);
        } catch (Throwable t) {
            Log.w(TAG, "Ignoring sendBroadcast(" + intent.getAction() + ") failure: " + t.getMessage());
        }
    }

    /**
     * Replaces {@code Context.sendBroadcastAsUser(Intent, UserHandle)} (which requires
     * {@code INTERACT_ACROSS_USERS}) with current-user {@link Context#sendBroadcast(Intent)}.
     */
    public static void safeSendBroadcastAsUser(Context context, Intent intent, UserHandle user) {
        safeSendBroadcast(context, intent);
    }

    /**
     * Guards {@link UserManager#requestQuietModeEnabled(boolean, UserHandle)} against
     * {@link SecurityException} when POCO Launcher is not yet the active default Home app.
     */
    public static boolean safeRequestQuietModeEnabled(
            Context context,
            boolean enableQuietMode,
            UserHandle userHandle
    ) {
        if (context == null || userHandle == null) {
            return false;
        }
        try {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return um != null && um.requestQuietModeEnabled(enableQuietMode, userHandle);
        } catch (Throwable t) {
            Log.w(TAG, "Ignoring requestQuietModeEnabled failure: " + t.getMessage());
            return false;
        }
    }

    /**
     * Guards {@link LauncherApps#getShortcutIconDrawable(ShortcutInfo, int)} against
     * {@link SecurityException} when POCO Launcher is not yet the active default Home app.
     */
    public static Drawable safeGetShortcutIconDrawable(Context context, Object shortcutInfo) {
        if (context == null || !(shortcutInfo instanceof ShortcutInfo)) {
            return null;
        }
        try {
            LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
            if (launcherApps == null) {
                return null;
            }
            return launcherApps.getShortcutIconDrawable((ShortcutInfo) shortcutInfo, 0);
        } catch (Throwable t) {
            Log.w(TAG, "Ignoring getShortcutIconDrawable failure: " + t.getMessage());
            return null;
        }
    }

    public static boolean safeHasBroughtToForeground(Activity launcher) {
        if (launcher == null || launcher.isFinishing() || launcher.isDestroyed()) {
            return false;
        }
        try {
            ActivityManager am = (ActivityManager) launcher.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                @SuppressWarnings("deprecation")
                List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                    ComponentName top = tasks.get(0).topActivity;
                    return launcher.getPackageName().equals(top.getPackageName())
                            && top.getClassName().equals(launcher.getClass().getName());
                }
            }
        } catch (Throwable ignored) {
        }
        return launcher.hasWindowFocus();
    }

    public static ComponentName getComponentName(ActivityInfo info) {
        if (info == null || info.packageName == null || info.name == null) {
            return null;
        }
        return new ComponentName(info.packageName, info.name);
    }

    public static boolean canBeResolved(Context context, Intent intent) {
        if (context == null || intent == null) {
            return false;
        }
        try {
            return context.getPackageManager().resolveActivity(intent, 0) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int safeGetAppLaunchCount(Object usageStats) {
        if (usageStats == null) {
            return 0;
        }
        try {
            Field f = usageStats.getClass().getDeclaredField("mLaunchCount");
            f.setAccessible(true);
            return f.getInt(usageStats);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static ComponentName getHomeActivities(List<ResolveInfo> outActivities) {
        Context ctx = getAppContext();
        if (ctx == null) {
            return null;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            if (outActivities != null) {
                List<ResolveInfo> resolved = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolved != null) {
                    outActivities.addAll(resolved);
                }
            }
            ResolveInfo def = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (def != null && def.activityInfo != null) {
                return new ComponentName(def.activityInfo.packageName, def.activityInfo.name);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static SurfaceControl createBufferLayerSurface(
            String name,
            int width,
            int height,
            SurfaceControl parent
    ) {
        try {
            SurfaceControl.Builder builder = new SurfaceControl.Builder()
                    .setBufferSize(Math.max(1, width), Math.max(1, height))
                    .setName(name != null ? name : "PocoLayer")
                    .setFormat(1)
                    .setOpaque(false);
            if (parent != null && parent.isValid()) {
                builder.setParent(parent);
            }
            return builder.build();
        } catch (Throwable t) {
            Log.w(TAG, "createBufferLayerSurface fallback failed: " + t.getMessage());
            return null;
        }
    }

    public static SurfaceControl.Transaction noopSurfaceTransaction(
            SurfaceControl.Transaction tx,
            SurfaceControl sc,
            float value
    ) {
        return tx;
    }

    public static SurfaceControl.Transaction noopSurfaceTransaction(
            SurfaceControl.Transaction tx,
            SurfaceControl sc,
            SurfaceControl relativeTo,
            int relativeLayer
    ) {
        return tx;
    }

    /**
     * Builds a public-SDK {@link ActivityOptions} clip-reveal animation for launching an app or
     * widget from {@code view}, avoiding blocked {@code android.window.IRemoteTransition} APIs.
     */
    public static ActivityOptions makeClipRevealOptions(View view) {
        try {
            if (view != null && view.getWidth() > 0 && view.getHeight() > 0) {
                return ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.getWidth(), view.getHeight());
            }
        } catch (Throwable ignored) {
        }
        return ActivityOptions.makeBasic();
    }

    /**
     * Rootless replacement for {@code StateManager.onLauncherStartActivity(...)}. Launches app
     * icons and widget clicks through {@code BaseLauncher} with public {@link ActivityOptions}
     * instead of constructing MIUI Shell {@code WindowElement} / {@code WindowTransitionCompat}
     * remote transitions (whose {@code IRemoteTransition$Stub.<init>} is blocked on Android 14+).
     */
    public static void launchActivityFromStateManager(
            Intent intent,
            Object tag,
            View view,
            Object widgetClickEventInfo
    ) {
        Object launcher = getActiveLauncher();
        if (widgetClickEventInfo != null) {
            try {
                View targetView = view;
                Object viewRefObj = invokeZeroArg(widgetClickEventInfo, "getViewRef");
                if (viewRefObj instanceof WeakReference) {
                    Object ref = ((WeakReference<?>) viewRefObj).get();
                    if (ref instanceof View) {
                        targetView = (View) ref;
                    }
                }
                ActivityOptions opts = makeClipRevealOptions(targetView);
                Bundle animBundle = opts != null ? opts.toBundle() : null;
                if (launcher != null) {
                    try {
                        invokeZeroArg(launcher, "onLaunchActivityProcessStart");
                    } catch (Throwable ignored) {
                    }
                    IntentSender sender = (IntentSender) invokeZeroArg(widgetClickEventInfo, "getIntent");
                    Intent fillIn = (Intent) invokeZeroArg(widgetClickEventInfo, "getFillInIntent");
                    if (sender == null) {
                        if (fillIn != null) {
                            Method superStart = findMethodInHierarchy(
                                    launcher.getClass(),
                                    "superStartActivity",
                                    Intent.class, Bundle.class, boolean.class);
                            if (superStart != null) {
                                superStart.invoke(launcher, fillIn, animBundle, Boolean.TRUE);
                            } else if (launcher instanceof Activity) {
                                ((Activity) launcher).startActivity(fillIn, animBundle);
                            }
                        }
                    } else {
                        Bundle options = (Bundle) invokeZeroArg(widgetClickEventInfo, "getOptions");
                        if (options != null && animBundle != null) {
                            options.putAll(animBundle);
                        }
                        Bundle effectiveOptions = options != null ? options : animBundle;
                        int flagsMask = toInt(invokeZeroArg(widgetClickEventInfo, "getFlagsMask"));
                        int flagsValues = toInt(invokeZeroArg(widgetClickEventInfo, "getFlagsValues"));
                        int extraFlags = toInt(invokeZeroArg(widgetClickEventInfo, "getExtraFlags"));
                        Method superSender = findMethodInHierarchy(
                                launcher.getClass(),
                                "superStartIntentSender",
                                IntentSender.class, Intent.class, int.class, int.class, int.class, Bundle.class);
                        if (superSender != null) {
                            superSender.invoke(launcher, sender, fillIn, flagsMask, flagsValues, extraFlags, effectiveOptions);
                        } else if (launcher instanceof Activity) {
                            ((Activity) launcher).startIntentSender(sender, fillIn, flagsMask, flagsValues, extraFlags, effectiveOptions);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "launchActivityFromStateManager (widget) failed", t);
            }
            return;
        }

        if (intent == null) {
            return;
        }
        ActivityOptions opts = makeClipRevealOptions(view);
        if (launcher != null) {
            try {
                try {
                    invokeZeroArg(launcher, "onLaunchActivityProcessStart");
                } catch (Throwable ignored) {
                }
                Method start4 = findMethodInHierarchy(
                        launcher.getClass(),
                        "startActivity",
                        Intent.class, Object.class, View.class, ActivityOptions.class);
                if (start4 != null) {
                    start4.invoke(launcher, intent, tag, view, opts);
                    return;
                }
            } catch (Throwable t) {
                Log.e(TAG, "launchActivityFromStateManager (launcher.startActivity) failed", t);
            }
        }
        try {
            Context ctx = launcher instanceof Context ? (Context) launcher : getAppContext();
            if (ctx != null) {
                startActivityAsUser(ctx, intent, opts != null ? opts.toBundle() : null, null);
            }
        } catch (Throwable t) {
            Log.e(TAG, "launchActivityFromStateManager fallback failed", t);
        }
    }

    private static Object getActiveLauncher() {
        try {
            if (!sGetLauncherResolved) {
                sGetLauncherResolved = true;
                Class<?> appClass = Class.forName("com.miui.home.launcher.Application");
                sGetLauncherMethod = appClass.getMethod("getLauncher");
            }
            Method m = sGetLauncherMethod;
            return m != null ? m.invoke(null) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int toInt(Object obj) {
        return obj instanceof Number ? ((Number) obj).intValue() : 0;
    }

    // ==================== Cached SystemProperties Reflection ====================

    private static void ensureSysPropMethods() {
        if (sSysPropResolved) {
            return;
        }
        sSysPropResolved = true;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sSysPropGet = sp.getMethod("get", String.class, String.class);
            sSysPropGetInt = sp.getMethod("getInt", String.class, int.class);
            sSysPropGetBool = sp.getMethod("getBoolean", String.class, boolean.class);
        } catch (Throwable ignored) {
        }
    }

    public static String sysPropGet(String key, String def) {
        if (key == null || key.isEmpty()) {
            return def;
        }
        boolean cacheableRo = key.startsWith("ro.");
        if (cacheableRo) {
            String cached = SYSPROP_RO_CACHE.get(key);
            if (cached != null) {
                return cached.isEmpty() ? (def != null ? def : "") : cached;
            }
        }
        ensureSysPropMethods();
        String raw = null;
        Method get = sSysPropGet;
        if (get != null) {
            try {
                Object res = get.invoke(null, key, "");
                if (res instanceof String) {
                    raw = (String) res;
                }
            } catch (Throwable ignored) {
            }
        }
        // LauncherSettings.<clinit> selects its ContentProvider authority by comparing
        // ro.miui.product.home against "com.mi.android.globallauncher". On non-Xiaomi ROMs
        // this property is empty, which made LauncherSettings.AUTHORITY fall back to
        // "com.miui.home.launcher.settings" (mismatching the manifest's
        // "com.mi.android.globallauncher.launcher.settings") and caused LauncherProvider to
        // throw SecurityException + Process.killProcess in an infinite loop on first boot.
        if ("ro.miui.product.home".equals(key) && (raw == null || raw.isEmpty())) {
            raw = "com.mi.android.globallauncher";
        }
        if (cacheableRo && SYSPROP_RO_CACHE.size() < 256) {
            SYSPROP_RO_CACHE.put(key, raw != null ? raw : "");
        }
        if (raw == null || raw.isEmpty()) {
            return def;
        }
        return raw;
    }

    public static int sysPropGetInt(String key, int def) {
        if (key == null || key.isEmpty()) {
            return def;
        }
        if (key.startsWith("ro.")) {
            String raw = sysPropGet(key, null);
            if (raw == null || raw.isEmpty()) {
                return def;
            }
            try {
                return Integer.decode(raw);
            } catch (Throwable ignored) {
                return def;
            }
        }
        ensureSysPropMethods();
        Method getInt = sSysPropGetInt;
        if (getInt != null) {
            try {
                Object res = getInt.invoke(null, key, def);
                return res instanceof Integer ? (Integer) res : def;
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    public static boolean sysPropGetBoolean(String key, boolean def) {
        if (key == null || key.isEmpty()) {
            return def;
        }
        if (key.startsWith("ro.")) {
            String raw = sysPropGet(key, null);
            if (raw == null || raw.isEmpty()) {
                return def;
            }
            if ("1".equals(raw) || "y".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw)
                    || "true".equalsIgnoreCase(raw) || "on".equalsIgnoreCase(raw)) {
                return true;
            }
            if ("0".equals(raw) || "n".equalsIgnoreCase(raw) || "no".equalsIgnoreCase(raw)
                    || "false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw)) {
                return false;
            }
            return def;
        }
        ensureSysPropMethods();
        Method getBool = sSysPropGetBool;
        if (getBool != null) {
            try {
                Object res = getBool.invoke(null, key, def);
                return res instanceof Boolean ? (Boolean) res : def;
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    // ==================== Thread-Safe Negative-Caching ReflectUtils Backend ====================

    private static final Object REFLECT_MISSING = new Object();
    private static final ConcurrentHashMap<String, Object> REFLECT_CLASS_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ReflectMethodKey, Object> REFLECT_METHOD_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ReflectFieldKey, Object> REFLECT_FIELD_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ReflectMethodKey, Object> REFLECT_CTOR_CACHE =
            new ConcurrentHashMap<>();

    private static final class ReflectMethodKey {
        final Class<?> cls;
        final String name;
        final Class<?>[] params;
        final boolean declared;
        final int hash;

        ReflectMethodKey(Class<?> cls, String name, Class<?>[] params, boolean declared) {
            this.cls = cls;
            this.name = name;
            this.params = params;
            this.declared = declared;
            int h = cls.hashCode() * 31 + name.hashCode();
            h = h * 31 + (declared ? 1 : 0);
            if (params != null) {
                for (Class<?> p : params) {
                    h = h * 31 + (p != null ? p.hashCode() : 0);
                }
            }
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReflectMethodKey)) return false;
            ReflectMethodKey other = (ReflectMethodKey) o;
            return hash == other.hash
                    && declared == other.declared
                    && cls == other.cls
                    && name.equals(other.name)
                    && Arrays.equals(params, other.params);
        }
    }

    private static final class ReflectFieldKey {
        final Class<?> cls;
        final String name;
        final int hash;

        ReflectFieldKey(Class<?> cls, String name) {
            this.cls = cls;
            this.name = name;
            this.hash = cls.hashCode() * 31 + name.hashCode();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReflectFieldKey)) return false;
            ReflectFieldKey other = (ReflectFieldKey) o;
            return hash == other.hash && cls == other.cls && name.equals(other.name);
        }
    }

    public static Class<?> reflectGetClass(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        Object cached = REFLECT_CLASS_CACHE.get(className);
        if (cached != null) {
            return cached == REFLECT_MISSING ? null : (Class<?>) cached;
        }
        Class<?> resolved = null;
        try {
            resolved = Class.forName(className);
        } catch (Throwable ignored) {
        }
        if (REFLECT_CLASS_CACHE.size() < 512) {
            REFLECT_CLASS_CACHE.put(className, resolved != null ? resolved : REFLECT_MISSING);
        }
        return resolved;
    }

    public static Method reflectGetMethod(
            Class<?> cls,
            String name,
            String ignoredSig,
            Class<?>... params
    ) {
        return reflectLookupMethod(cls, name, params, false);
    }

    public static Method reflectGetDeclaredMethod(
            Class<?> cls,
            String name,
            String ignoredSig,
            Class<?>... params
    ) {
        return reflectLookupMethod(cls, name, params, true);
    }

    public static boolean reflectHasMethod(Class<?> cls, String name, Class<?>[] params) {
        return reflectLookupMethod(cls, name, params, true) != null;
    }

    private static Method reflectLookupMethod(
            Class<?> cls,
            String name,
            Class<?>[] params,
            boolean declared
    ) {
        if (cls == null || name == null) {
            return null;
        }
        ReflectMethodKey key = new ReflectMethodKey(cls, name, params, declared);
        Object cached = REFLECT_METHOD_CACHE.get(key);
        if (cached != null) {
            return cached == REFLECT_MISSING ? null : (Method) cached;
        }
        Method resolved = null;
        try {
            resolved = declared ? cls.getDeclaredMethod(name, params) : cls.getMethod(name, params);
            resolved.setAccessible(true);
        } catch (Throwable ignored) {
        }
        if (REFLECT_METHOD_CACHE.size() < 1024) {
            Class<?>[] keyParams = params != null ? params.clone() : null;
            REFLECT_METHOD_CACHE.put(
                    new ReflectMethodKey(cls, name, keyParams, declared),
                    resolved != null ? resolved : REFLECT_MISSING);
        }
        return resolved;
    }

    public static Field reflectGetField(Class<?> cls, String name, String ignoredSig) {
        if (cls == null || name == null) {
            return null;
        }
        ReflectFieldKey key = new ReflectFieldKey(cls, name);
        Object cached = REFLECT_FIELD_CACHE.get(key);
        if (cached != null) {
            return cached == REFLECT_MISSING ? null : (Field) cached;
        }
        Field resolved = null;
        Class<?> cur = cls;
        while (cur != null) {
            try {
                resolved = cur.getDeclaredField(name);
                resolved.setAccessible(true);
                break;
            } catch (Throwable ignored) {
                cur = cur.getSuperclass();
            }
        }
        if (REFLECT_FIELD_CACHE.size() < 1024) {
            REFLECT_FIELD_CACHE.put(key, resolved != null ? resolved : REFLECT_MISSING);
        }
        return resolved;
    }

    public static Object reflectGetObjectField(Object obj, String name) {
        if (obj == null || name == null) {
            return null;
        }
        Field f = reflectGetField(obj.getClass(), name, null);
        if (f == null) {
            return null;
        }
        try {
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object reflectCallObjectMethod(
            Object obj,
            Class<?> retType,
            String name,
            Class<?>[] params,
            Object... args
    ) {
        if (obj == null || name == null) {
            return null;
        }
        Method m = reflectLookupMethod(obj.getClass(), name, params, true);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(obj, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object reflectCallObjectMethod2(
            Object obj,
            Class<?> retType,
            String name,
            Class<?>[] params,
            Object... args
    ) {
        if (obj == null || name == null) {
            return null;
        }
        Method m = reflectLookupMethod(obj.getClass(), name, params, false);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(obj, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object reflectCallStaticMethod(
            String className,
            Class<?> retType,
            String name,
            Class<?>[] params,
            Object... args
    ) {
        Class<?> cls = reflectGetClass(className);
        if (cls == null) {
            return null;
        }
        return reflectInvokeObject(cls, null, name, retType, params, args);
    }

    public static Object reflectCreateNewInstance(
            Class<?> cls,
            Class<?>[] params,
            Object... args
    ) {
        if (cls == null) {
            return null;
        }
        ReflectMethodKey key = new ReflectMethodKey(cls, "<init>", params, false);
        Object cached = REFLECT_CTOR_CACHE.get(key);
        Constructor<?> ctor;
        if (cached != null) {
            if (cached == REFLECT_MISSING) {
                return null;
            }
            ctor = (Constructor<?>) cached;
        } else {
            ctor = null;
            try {
                ctor = cls.getConstructor(params);
            } catch (Throwable ignored) {
            }
            if (REFLECT_CTOR_CACHE.size() < 256) {
                Class<?>[] keyParams = params != null ? params.clone() : null;
                REFLECT_CTOR_CACHE.put(
                        new ReflectMethodKey(cls, "<init>", keyParams, false),
                        ctor != null ? ctor : REFLECT_MISSING);
            }
        }
        if (ctor == null) {
            return null;
        }
        try {
            return ctor.newInstance(args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void reflectInvoke(
            Class<?> cls,
            Object obj,
            String name,
            Class<?> retType,
            Class<?>[] params,
            Object... args
    ) {
        Method m = reflectLookupMethod(cls, name, params, false);
        if (m == null) {
            return;
        }
        try {
            m.invoke(obj, args);
        } catch (Throwable ignored) {
        }
    }

    public static Object reflectInvokeObject(
            Class<?> cls,
            Object obj,
            String name,
            Class<?> retType,
            Class<?>[] params,
            Object... args
    ) {
        Method m = reflectLookupMethod(cls, name, params, false);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(obj, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object reflectInvokeObjectDefault(
            Class<?> cls,
            Object obj,
            String name,
            Class<?> retType,
            Object defaultValue,
            Class<?>[] params,
            Object... args
    ) {
        Method m = reflectLookupMethod(cls, name, params, false);
        if (m == null) {
            return defaultValue;
        }
        try {
            return m.invoke(obj, args);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    // ==================== Overlay Client Guards & RAM Trimming ====================

    /**
     * Skips creating dead Minus-1 overlay {@code LauncherClient} connections (which repeatedly
     * attempt {@code bindService} on every {@code onStart}/{@code onResume}/{@code onAttachedToWindow})
     * when neither Xiaomi App Vault nor Personal Assistant is installed.
     */
    public static boolean shouldCreateMinusScreenClient(Context context, String packageName) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return false;
        }
        if (packageName != null && !"com.google.android.googlequicksearchbox".equals(packageName)) {
            return isPackageInstalled(ctx, packageName);
        }
        // Google Discover WINDOW_OVERLAY requires OEM-allowlisted Xiaomi framework/signing;
        // only bind when Xiaomi minus-screen infrastructure is actually present.
        return isPackageInstalled(ctx, "com.mi.globalminusscreen")
                || isPackageInstalled(ctx, "com.miui.personalassistant");
    }

    /**
     * Skips creating the International Search overlay client when Xiaomi AppFinder isn't installed.
     */
    public static boolean shouldCreateAppFinderClient(Context context) {
        Context ctx = context != null ? context : getAppContext();
        return ctx != null && isPackageInstalled(ctx, "com.mi.appfinder");
    }

    /**
     * Releases non-essential bitmap, MAML, and file caches when Android trims launcher memory
     * in background (preserving active desktop icons on {@code TRIM_MEMORY_UI_HIDDEN} so returning
     * Home stays instantaneous).
     */
    public static void onAppTrimMemory(int level) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            return;
        }
        try {
            PocoSettingsCompat.pruneDeadObservers();
        } catch (Throwable ignored) {
        }
        try {
            Class<?> rmCls = reflectGetClass("com.miui.maml.ResourceManager");
            if (rmCls != null) {
                Method clear = reflectLookupMethod(rmCls, "clear", null, false);
                if (clear != null) {
                    clear.invoke(null);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> faCls = reflectGetClass("com.miui.home.library.utils.FileAccessable$Factory");
            if (faCls != null) {
                Method clear = reflectLookupMethod(faCls, "clearCache", null, false);
                if (clear != null) {
                    clear.invoke(null);
                }
            }
        } catch (Throwable ignored) {
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            try {
                Class<?> icCls = reflectGetClass("miui.content.res.IconCustomizer");
                if (icCls != null) {
                    Method clear = reflectLookupMethod(icCls, "clearCache", null, false);
                    if (clear != null) {
                        clear.invoke(null);
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> modelCompCls = reflectGetClass("com.miui.home.model.api.IModel$Companion");
                if (modelCompCls != null) {
                    Field compField = reflectGetField(reflectGetClass("com.miui.home.model.api.IModel"), "Companion", null);
                    Object comp = compField != null ? compField.get(null) : null;
                    Object model = comp != null ? invokeZeroArg(comp, "getInstance") : null;
                    Object iconCache = model != null ? invokeZeroArg(model, "getIconCache") : null;
                    Object bigCache = iconCache != null ? readField(iconCache, "mBigIconCache") : null;
                    if (bigCache != null) {
                        invokeZeroArg(bigCache, "clear");
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> rmCls = reflectGetClass("com.miui.home.recents.RecentsModel");
                Field instField = rmCls != null ? reflectGetField(rmCls, "INSTANCE", null) : null;
                Object recentsModel = instField != null ? instField.get(null) : null;
                Object taskLoader = recentsModel != null ? invokeZeroArg(recentsModel, "getTaskLoader") : null;
                if (taskLoader != null) {
                    Method trim = reflectLookupMethod(taskLoader.getClass(), "onTrimMemory", new Class<?>[]{int.class}, false);
                    if (trim != null) {
                        trim.invoke(taskLoader, level);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== Native Non-Xiaomi UX: Uninstall, Widgets, Wallpaper ====================

    public static boolean launchUninstallIntent(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName));
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch ACTION_DELETE for " + packageName, t);
            return false;
        }
    }

    public static void openWallpaperPickerFallback(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        PackageManager pm = ctx.getPackageManager();
        String selfPkg = ctx.getPackageName();

        // 1. Standard Android ACTION_SET_WALLPAPER (if at least one external handler exists).
        try {
            Intent picker = new Intent(Intent.ACTION_SET_WALLPAPER);
            List<ResolveInfo> handlers = pm.queryIntentActivities(picker, PackageManager.MATCH_DEFAULT_ONLY);
            List<ResolveInfo> external = new ArrayList<>();
            if (handlers != null) {
                for (ResolveInfo ri : handlers) {
                    if (ri != null && ri.activityInfo != null
                            && !selfPkg.equals(ri.activityInfo.packageName)) {
                        external.add(ri);
                    }
                }
            }
            if (!external.isEmpty()) {
                Intent target;
                if (external.size() == 1) {
                    ActivityInfo ai = external.get(0).activityInfo;
                    target = new Intent(Intent.ACTION_SET_WALLPAPER);
                    target.setClassName(ai.packageName, ai.name);
                } else {
                    target = Intent.createChooser(picker, null);
                }
                if (!(ctx instanceof Activity)) {
                    target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                ctx.startActivity(target);
                return;
            }
        } catch (Throwable ignored) {
        }

        // 2. Known OEM wallpaper intent actions (e.g. Samsung One UI Wallpaper & Style).
        String[] oemActions = {
                "com.samsung.android.intent.action.SET_WALLPAPER",
                "com.samsung.intent.action.SET_WALLPAPER",
                "com.samsung.android.app.dressroom.intent.action.SHOW_WALLPAPER_SETTING",
                "com.samsung.android.themestore.action.SET_WALLPAPER",
        };
        for (String action : oemActions) {
            try {
                Intent oemIntent = new Intent(action);
                if (oemIntent.resolveActivity(pm) != null) {
                    if (!(ctx instanceof Activity)) {
                        oemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    ctx.startActivity(oemIntent);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. Direct launch / runtime exported-activity discovery for Samsung dressroom & Google Wallpapers.
        String[] oemPackages = {
                "com.samsung.android.app.dressroom",
                "com.google.android.apps.wallpaper",
        };
        for (String pkg : oemPackages) {
            if (tryStartWallpaperPackage(ctx, pm, pkg)) {
                return;
            }
        }

        // 4. Final fallback: Android Display Settings (where Wallpaper & Style lives on One UI / AOSP).
        try {
            Intent displaySettings = new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
            if (!(ctx instanceof Activity)) {
                displaySettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(displaySettings);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open Android wallpaper picker", t);
        }
    }

    private static boolean tryStartWallpaperPackage(Context ctx, PackageManager pm, String pkg) {
        try {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                if (!(ctx instanceof Activity)) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                ctx.startActivity(launch);
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi == null || pi.activities == null) {
                return false;
            }
            List<ActivityInfo> preferred = new ArrayList<>();
            List<ActivityInfo> fallback = new ArrayList<>();
            for (ActivityInfo ai : pi.activities) {
                if (ai == null || !ai.exported || !ai.enabled || ai.name == null) {
                    continue;
                }
                if (ai.permission != null
                        && ctx.checkSelfPermission(ai.permission) != PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                String lower = ai.name.toLowerCase(Locale.ROOT);
                if (lower.contains("wallpapersetting")
                        || lower.contains("dressroom")
                        || lower.contains("categorypicker")
                        || lower.contains("main")) {
                    preferred.add(ai);
                } else {
                    fallback.add(ai);
                }
            }
            preferred.addAll(fallback);
            for (ActivityInfo ai : preferred) {
                try {
                    Intent intent = new Intent();
                    intent.setClassName(ai.packageName, ai.name);
                    if (!(ctx instanceof Activity)) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    ctx.startActivity(intent);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void openThemeOrSystemWallpaperPicker(
            Context context, String action, String pkg, String cls) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        try {
            if (action != null && !action.isEmpty()) {
                Intent intent = new Intent(action);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(ctx.getPackageManager()) != null) {
                    ctx.startActivity(intent);
                    return;
                }
            }
            if (pkg != null && cls != null) {
                Intent intent = new Intent();
                intent.setClassName(pkg, cls);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(ctx.getPackageManager()) != null) {
                    ctx.startActivity(intent);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        openWallpaperPickerFallback(ctx);
    }

    /**
     * Public-SDK replacement for {@code Utilities.getAppVersionCode(Context, String, UserHandle)},
     * which invoked hidden {@code PackageManager.getPackageInfoAsUser} and returned {@code 0} on
     * {@code SecurityException}, forcing every added widget into a broken restore placeholder.
     */
    public static int getAppVersionCode(Context context, String packageName, UserHandle userHandle) {
        if (context == null || packageName == null || packageName.isEmpty()) {
            return 0;
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            if (info != null) {
                long code = info.getLongVersionCode();
                if (code > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
                return code > 0 ? (int) code : 1;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Package may belong to a secondary/work profile; probe via LauncherApps.
            if (userHandle != null) {
                try {
                    LauncherApps la = context.getSystemService(LauncherApps.class);
                    if (la != null) {
                        ApplicationInfo ai = la.getApplicationInfo(packageName, 0, userHandle);
                        if (ai != null) {
                            return 1;
                        }
                    }
                } catch (Throwable ignored2) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getAppVersionCode failed for " + packageName + ": " + t.getMessage());
        }
        return 0;
    }

    /**
     * Builds Android 14+ {@link ActivityOptions} allowing background activity launch for
     * {@link AppWidgetHost#startAppWidgetConfigureActivityForResult} PendingIntent/IntentSender.
     */
    public static Bundle makeBalAllowedOptions() {
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            return opts.toBundle();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean needsWidgetConfigure(AppWidgetProviderInfo info) {
        if (info == null || info.configure == null) {
            return false;
        }
        int features = info.widgetFeatures;
        boolean reconfigurable = (features & AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0;
        boolean configOptional = (features & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0;
        return !(reconfigurable && configOptional);
    }

    /**
     * Launches Android's native {@link AppWidgetManager#ACTION_APPWIDGET_BIND} user-consent dialog
     * when {@code bindAppWidgetIdIfAllowed} returns {@code false} (rootless non-system launcher).
     */
    public static void requestBindAppWidget(
            Activity launcher,
            int appWidgetId,
            Object widgetInfo,
            Object cellScreen,
            Bundle options
    ) {
        requestBindAppWidgetInternal(launcher, appWidgetId, widgetInfo, cellScreen, options, null);
    }

    /**
     * Handles {@code InstallWidgetReceiver.addWidget} (in-app widget pinning via
     * {@code AddItemActivity}) when {@code bindAppWidgetIdIfAllowed} returns {@code false}.
     */
    public static void requestPinBindAppWidget(
            Object pinRunnable,
            Activity launcher,
            int appWidgetId,
            Object providerInfo,
            Bundle options
    ) {
        if (launcher == null || providerInfo == null) {
            cleanupFailedBindWidget(launcher, appWidgetId);
            return;
        }
        try {
            Class<?> widgetInfoClass = Class.forName("com.miui.home.launcher.LauncherAppWidgetInfo");
            Class<?> providerInfoClass = Class.forName("com.miui.home.launcher.LauncherAppWidgetProviderInfo");
            Constructor<?> ctor = widgetInfoClass.getConstructor(int.class, providerInfoClass);
            Object widgetInfo = ctor.newInstance(appWidgetId, providerInfo);

            long screenId = -1L;
            Object pinCallback = null;
            if (pinRunnable != null) {
                Object sid = readField(pinRunnable, "val$screenId");
                if (sid instanceof Long) {
                    screenId = (Long) sid;
                }
                Object cx = readField(pinRunnable, "val$cellX");
                Object cy = readField(pinRunnable, "val$cellY");
                writeField(widgetInfo, "screenId", screenId);
                if (cx instanceof Integer) {
                    writeField(widgetInfo, "cellX", cx);
                }
                if (cy instanceof Integer) {
                    writeField(widgetInfo, "cellY", cy);
                }
                pinCallback = readField(pinRunnable, "val$addWidgetCallback");
            }
            requestBindAppWidgetInternal(launcher, appWidgetId, widgetInfo, null, options, pinCallback);
        } catch (Throwable t) {
            Log.e(TAG, "requestPinBindAppWidget failed", t);
            cleanupFailedBindWidget(launcher, appWidgetId);
        }
    }

    private static void requestBindAppWidgetInternal(
            Activity launcher,
            int appWidgetId,
            Object widgetInfo,
            Object cellScreen,
            Bundle options,
            Object pinSuccessCallback
    ) {
        if (launcher == null || widgetInfo == null) {
            return;
        }
        try {
            ComponentName provider = (ComponentName) invokeZeroArg(widgetInfo, "getProvider");
            UserHandle user = (UserHandle) readField(widgetInfo, "user");
            long screenId = -1L;
            if (cellScreen != null) {
                Object cellLayout = invokeZeroArg(cellScreen, "getCellLayout");
                if (cellLayout != null) {
                    Object idObj = invokeZeroArg(cellLayout, "getScreenId");
                    if (idObj instanceof Long) {
                        screenId = (Long) idObj;
                    }
                }
            }
            if (screenId == -1L) {
                Object infoScreenId = readField(widgetInfo, "screenId");
                if (infoScreenId instanceof Long) {
                    screenId = (Long) infoScreenId;
                }
            }
            sPendingConfigureWidget = null;
            sPendingBindWidget = new PendingBindWidget(
                    appWidgetId, widgetInfo, screenId, pinSuccessCallback, options);

            Intent bindIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            if (provider != null) {
                bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider);
            }
            if (user != null) {
                bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user);
            }
            if (options != null) {
                bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options);
            }
            launcher.startActivityForResult(bindIntent, REQUEST_BIND_APPWIDGET);
        } catch (Throwable t) {
            Log.e(TAG, "requestBindAppWidget failed", t);
            sPendingBindWidget = null;
            writeField(launcher, "mWaitingForResult", Boolean.FALSE);
            cleanupFailedBindWidget(launcher, appWidgetId);
        }
    }

    /**
     * Replacement for {@code LauncherAppWidgetHost.startConfigActivity(BaseActivity, int, int)}.
     * Passes Android 14+ BAL-allowed {@link ActivityOptions} and preserves pending widget info
     * across {@code BaseLauncher.onResume() -> clearLastAddInfo()} while the external configure
     * activity (e.g. Todoist) runs.
     */
    public static void startWidgetConfigActivity(
            AppWidgetHost host,
            Activity activity,
            int appWidgetId,
            int requestCode
    ) {
        if (host == null || activity == null) {
            return;
        }
        if (requestCode == REQUEST_CREATE_APPWIDGET && sPendingConfigureWidget == null) {
            Object lastAdd = readField(activity, "mLastAddInfo");
            if (lastAdd != null) {
                long screenId = -1L;
                Object sid = readField(lastAdd, "screenId");
                if (sid instanceof Long) {
                    screenId = (Long) sid;
                }
                sPendingConfigureWidget = new PendingBindWidget(appWidgetId, lastAdd, screenId);
            }
        }
        try {
            host.startAppWidgetConfigureActivityForResult(
                    activity, appWidgetId, 0, requestCode, makeBalAllowedOptions());
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "startWidgetConfigActivity failed for id=" + appWidgetId, e);
            if (requestCode == REQUEST_CREATE_APPWIDGET) {
                sPendingConfigureWidget = null;
            }
            writeField(activity, "mWaitingForResult", Boolean.FALSE);
            try {
                int msgId = activity.getResources().getIdentifier(
                        "activity_not_found", "string", activity.getPackageName());
                if (msgId != 0) {
                    Toast.makeText(activity, msgId, Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Method onResult = findMethodInHierarchy(
                            activity.getClass(), "onActivityResult", int.class, int.class, Intent.class);
                    if (onResult != null) {
                        onResult.invoke(activity, requestCode, Activity.RESULT_CANCELED, null);
                    }
                } catch (Throwable ignored) {
                }
            });
        }
    }

    /**
     * Handles {@link #REQUEST_BIND_APPWIDGET} (11) and pending {@link #REQUEST_CREATE_APPWIDGET} (5)
     * results returned to {@code BaseLauncher.onActivityResult}.
     *
     * @return {@code true} if handled (caller should return immediately), {@code false} otherwise.
     */
    public static boolean handleAppWidgetBindResult(
            Activity launcher,
            int requestCode,
            int resultCode,
            Intent data
    ) {
        if (requestCode == REQUEST_CREATE_APPWIDGET) {
            return handleWidgetConfigureResult(launcher, resultCode, data);
        }
        if (requestCode != REQUEST_BIND_APPWIDGET) {
            return false;
        }
        // Clear BaseLauncher.mWaitingForResult (set by startActivityForResult(11)) so touches
        // are never left locked if the user cancels or if widget binding fails.
        writeField(launcher, "mWaitingForResult", Boolean.FALSE);

        PendingBindWidget pending = sPendingBindWidget;
        sPendingBindWidget = null;

        int widgetId = pending != null ? pending.appWidgetId : -1;
        if (data != null) {
            int fromData = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (fromData != -1) {
                widgetId = fromData;
            }
        }

        if (resultCode != Activity.RESULT_OK || widgetId == -1 || pending == null) {
            cleanupFailedBindWidget(launcher, widgetId);
            return true;
        }

        try {
            writeField(pending.widgetInfo, "appWidgetId", widgetId);
            if (pending.screenId != -1L) {
                Object curSid = readField(pending.widgetInfo, "screenId");
                if (!(curSid instanceof Long) || (Long) curSid == -1L) {
                    writeField(pending.widgetInfo, "screenId", pending.screenId);
                }
            }
            // Restore BaseLauncher.mLastAddInfo in case onResume cleared it while the dialog was shown.
            writeField(launcher, "mLastAddInfo", pending.widgetInfo);

            notifyPinWidgetCallback(pending);

            AppWidgetManager awm = AppWidgetManager.getInstance(launcher);
            AppWidgetProviderInfo providerInfo = awm != null ? awm.getAppWidgetInfo(widgetId) : null;
            if (needsWidgetConfigure(providerInfo)) {
                Object host = readField(launcher, "mAppWidgetHost");
                if (host instanceof AppWidgetHost) {
                    sPendingConfigureWidget = new PendingBindWidget(
                            widgetId, pending.widgetInfo, pending.screenId);
                    startWidgetConfigActivity(
                            (AppWidgetHost) host, launcher, widgetId, REQUEST_CREATE_APPWIDGET);
                    return true;
                }
            }

            dispatchCompleteAddWidget(launcher, widgetId, pending.screenId, null);
        } catch (Throwable t) {
            Log.e(TAG, "handleAppWidgetBindResult failed", t);
            sPendingConfigureWidget = null;
            cleanupFailedBindWidget(launcher, widgetId);
        }
        return true;
    }

    private static boolean handleWidgetConfigureResult(
            Activity launcher,
            int resultCode,
            Intent data
    ) {
        PendingBindWidget pending = sPendingConfigureWidget;
        sPendingConfigureWidget = null;

        if (pending == null) {
            if (resultCode == Activity.RESULT_OK && data == null) {
                writeField(launcher, "mWaitingForResult", Boolean.FALSE);
                Object lastAdd = readField(launcher, "mLastAddInfo");
                long screenId = -1L;
                int widgetId = -1;
                if (lastAdd != null) {
                    Object sid = readField(lastAdd, "screenId");
                    if (sid instanceof Long) {
                        screenId = (Long) sid;
                    }
                    Object wid = readField(lastAdd, "appWidgetId");
                    if (wid instanceof Integer) {
                        widgetId = (Integer) wid;
                    }
                }
                dispatchCompleteAddWidget(launcher, widgetId, screenId, null);
                return true;
            }
            return false;
        }

        writeField(launcher, "mWaitingForResult", Boolean.FALSE);
        int widgetId = pending.appWidgetId;
        if (data != null) {
            int fromData = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (fromData != -1) {
                widgetId = fromData;
            }
        }
        if (resultCode != Activity.RESULT_OK || widgetId == -1) {
            cleanupFailedBindWidget(launcher, widgetId);
            return true;
        }

        try {
            if (pending.widgetInfo != null) {
                writeField(pending.widgetInfo, "appWidgetId", widgetId);
                if (pending.screenId != -1L) {
                    Object curSid = readField(pending.widgetInfo, "screenId");
                    if (!(curSid instanceof Long) || (Long) curSid == -1L) {
                        writeField(pending.widgetInfo, "screenId", pending.screenId);
                    }
                }
                // Restore mLastAddInfo which BaseLauncher.onResume()->clearLastAddInfo() wiped
                // while the external widget configure activity was active.
                writeField(launcher, "mLastAddInfo", pending.widgetInfo);
            }
            dispatchCompleteAddWidget(launcher, widgetId, pending.screenId, data);
        } catch (Throwable t) {
            Log.e(TAG, "handleWidgetConfigureResult failed", t);
            cleanupFailedBindWidget(launcher, widgetId);
        }
        return true;
    }

    private static void dispatchCompleteAddWidget(
            Activity launcher,
            int widgetId,
            long screenId,
            Intent sourceData
    ) {
        try {
            Intent completeIntent = sourceData != null
                    ? new Intent(sourceData)
                    : new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_DEFAULT);
            if (widgetId != -1 && !completeIntent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
                completeIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            }
            if (!completeIntent.hasExtra("ScreenID")) {
                completeIntent.putExtra("ScreenID", screenId);
            }
            Method onActivityResult = findMethodInHierarchy(
                    launcher.getClass(), "onActivityResult", int.class, int.class, Intent.class);
            if (onActivityResult != null) {
                onActivityResult.invoke(launcher, REQUEST_CREATE_APPWIDGET, Activity.RESULT_OK, completeIntent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "dispatchCompleteAddWidget failed", t);
            cleanupFailedBindWidget(launcher, widgetId);
        }
    }

    private static void notifyPinWidgetCallback(PendingBindWidget pending) {
        if (pending == null || pending.pinSuccessCallback == null) {
            return;
        }
        try {
            Method m = findMethodInHierarchy(
                    pending.pinSuccessCallback.getClass(), "onWidgetAdded", Bundle.class);
            if (m != null) {
                m.invoke(pending.pinSuccessCallback, pending.pinOptions);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Null-safe replacement for {@code BaseLauncher.updateQsbDefaultLayout}, which dereferenced
     * {@code getReceiverInfo(...).metaData} without a null check.
     */
    public static void safeUpdateQsbDefaultLayout(
            Context context,
            AppWidgetProviderInfo appWidgetProviderInfo,
            AppWidgetHostView appWidgetHostView
    ) {
        if (context == null || appWidgetProviderInfo == null || appWidgetHostView == null) {
            return;
        }
        ComponentName componentName = appWidgetProviderInfo.provider;
        if (!new ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.googlequicksearchbox.SearchWidgetProvider"
        ).equals(componentName)) {
            return;
        }
        try {
            Bundle bundle = new Bundle();
            bundle.putString("attached-launcher-identifier", context.getPackageName());
            bundle.putString("requested-widget-style", "cqsb");
            appWidgetHostView.updateAppWidgetOptions(bundle);
            ActivityInfo receiverInfo = context.getPackageManager()
                    .getReceiverInfo(componentName, PackageManager.GET_META_DATA);
            if (receiverInfo != null && receiverInfo.metaData != null) {
                int altLayout = receiverInfo.metaData.getInt(
                        "com.google.android.gsa.searchwidget.alt_initial_layout_cqsb", -1);
                if (altLayout != -1) {
                    appWidgetProviderInfo.initialLayout = altLayout;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cleanupFailedBindWidget(Activity launcher, int appWidgetId) {
        if (launcher == null) {
            return;
        }
        try {
            if (appWidgetId != -1) {
                Object host = readField(launcher, "mAppWidgetHost");
                if (host instanceof AppWidgetHost) {
                    ((AppWidgetHost) host).deleteAppWidgetId(appWidgetId);
                }
            }
            Method clear = findMethodInHierarchy(launcher.getClass(), "clearLastAddInfo");
            if (clear != null) {
                clear.invoke(launcher);
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== Notification Badges & Access (Non-Xiaomi) ====================

    private static volatile long sLastNotificationRebindMs;

    public static boolean isNotificationAccessEnabled(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return false;
        }
        try {
            // Read directly from real Android Settings.Secure (extension package is excluded from
            // Settings rewrite).
            String flat = Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) {
                return false;
            }
            String pkg = ctx.getPackageName();
            String expected = new ComponentName(pkg, NOTIFICATION_LISTENER_CLASS).flattenToString();
            String[] parts = flat.split(":");
            for (String part : parts) {
                if (part == null || part.isEmpty()) {
                    continue;
                }
                if (expected.equals(part)) {
                    return true;
                }
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (cn != null && pkg.equals(cn.getPackageName())
                        && NOTIFICATION_LISTENER_CLASS.equals(cn.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isNotificationAccessEnabled check failed: " + t.getMessage());
        }
        return false;
    }

    public static void ensureNotificationListenerBound(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        if (!isNotificationAccessEnabled(ctx)) {
            return;
        }
        try {
            Class<?> listenerCls = Class.forName(NOTIFICATION_LISTENER_CLASS);
            Field connectedField = listenerCls.getDeclaredField("sIsConnected");
            connectedField.setAccessible(true);
            boolean connected = connectedField.getBoolean(null);
            Field instanceField = listenerCls.getDeclaredField("sNotificationListenerInstance");
            instanceField.setAccessible(true);
            Object instance = instanceField.get(null);
            if (connected && instance != null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - sLastNotificationRebindMs < 5000L) {
                return;
            }
            sLastNotificationRebindMs = now;
            ComponentName cn = new ComponentName(ctx, NOTIFICATION_LISTENER_CLASS);
            NotificationListenerService.requestRebind(cn);
            Log.i(TAG, "Requested NotificationListenerService rebind for " + cn.flattenToShortString());
        } catch (Throwable t) {
            Log.w(TAG, "ensureNotificationListenerBound failed: " + t.getMessage());
        }
    }

    public static boolean hasInvalidNotificationExtras(StatusBarNotification sbn) {
        return sbn == null || sbn.getNotification() == null || sbn.getNotification().extras == null;
    }

    public static void openNotificationAccessSettings(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        PackageManager pm = ctx.getPackageManager();
        ComponentName cn = new ComponentName(ctx.getPackageName(), NOTIFICATION_LISTENER_CLASS);

        // 1. Direct Notification Access detail page for POCO Launcher (API 30+ / One UI / AOSP).
        try {
            Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            detail.putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString());
            if (!(ctx instanceof Activity)) {
                detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            if (detail.resolveActivity(pm) != null) {
                ctx.startActivity(detail);
                return;
            }
        } catch (Throwable ignored) {
        }

        // 2. General Notification Access list.
        try {
            Intent list = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            if (!(ctx instanceof Activity)) {
                list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            if (list.resolveActivity(pm) != null) {
                ctx.startActivity(list);
                return;
            }
        } catch (Throwable ignored) {
        }

        // 3. Final fallback: App Details page.
        try {
            Intent details = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            if (!(ctx instanceof Activity)) {
                details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(details);
        } catch (Throwable t) {
            Log.e(TAG, "openNotificationAccessSettings failed", t);
        }
    }

    // ==================== Double-Tap Lock, System Nav & Notification Shade ====================

    private static volatile long sLastLockPromptMs;

    public static boolean isLockAccessibilityEnabled(Context context) {
        if (PocoLockScreenService.isConnected()) {
            return true;
        }
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return false;
        }
        try {
            // Read directly from real Android Settings.Secure (extension package is excluded from
            // Settings rewrite).
            String flat = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null || flat.isEmpty()) {
                return false;
            }
            String pkg = ctx.getPackageName();
            String expected = new ComponentName(pkg, LOCK_ACCESSIBILITY_SERVICE_CLASS).flattenToString();
            String[] parts = flat.split(":");
            for (String part : parts) {
                if (part == null || part.isEmpty()) {
                    continue;
                }
                if (expected.equals(part)) {
                    return true;
                }
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (cn != null && pkg.equals(cn.getPackageName())
                        && LOCK_ACCESSIBILITY_SERVICE_CLASS.equals(cn.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isLockAccessibilityEnabled check failed: " + t.getMessage());
        }
        return false;
    }

    public static void openLockAccessibilitySettings(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        boolean pt = isPortugueseLocale();
        String msg = pt
                ? "Ative \"Toque duas vezes...\" em Acessibilidade → Aplicativos instalados"
                : "Enable \"Double tap to lock screen\" in Accessibility → Installed apps";
        showToastOnMain(ctx, msg);

        ComponentName cn = new ComponentName(ctx.getPackageName(), LOCK_ACCESSIBILITY_SERVICE_CLASS);
        String flat = cn.flattenToString();
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            Bundle showArgs = new Bundle();
            showArgs.putString(":settings:fragment_args_key", flat);
            intent.putExtra(":settings:fragment_args_key", flat);
            intent.putExtra(":settings:show_fragment_args", showArgs);
            if (!(ctx instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "openLockAccessibilitySettings failed: " + t.getMessage());
        }
    }

    /**
     * Replacement for MIUI's privileged {@code ExtraStatusBarManager.action_TRIGGER_TOGGLE}
     * broadcast in {@code SuperDraglayer$DoubleTapListener.onDoubleTapEvent}. Uses public
     * {@link PocoLockScreenService#performLockScreen()} ({@code GLOBAL_ACTION_LOCK_SCREEN}),
     * which preserves fingerprint and biometric unlock on Android 9+.
     */
    public static void performDoubleTapLockScreen(Context context) {
        if (PocoLockScreenService.performLockScreen()) {
            return;
        }
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sLastLockPromptMs < 2500L) {
            return;
        }
        sLastLockPromptMs = now;
        openLockAccessibilitySettings(ctx);
    }

    /**
     * Invoked from {@code MiuiHomeSettings.onPreferenceChange} immediately after persisting
     * {@code lock_screen_mode}. Syncs {@code LauncherUtils.sIsDoubleTapLock}, updates the
     * preference summary, and guides the user to Accessibility Settings if enabled without
     * {@link PocoLockScreenService} active.
     */
    public static void onDoubleTapLockPreferenceChanged(Object fragment, Object newValue) {
        Context ctx = fragment != null ? resolvePreferenceContext(fragment) : getAppContext();
        if (ctx == null) {
            return;
        }
        boolean enabled = Boolean.TRUE.equals(newValue);
        refreshDoubleTapLockSync(ctx);
        if (fragment != null) {
            updateDoubleTapLockPreferenceUi(fragment, ctx, enabled);
        }
        if (enabled && !isLockAccessibilityEnabled(ctx)) {
            openLockAccessibilitySettings(ctx);
        }
    }

    /**
     * Replacement for {@code LauncherUtils.expandStatusBar(Context)}. Prefers public
     * {@code GLOBAL_ACTION_NOTIFICATIONS} when {@link PocoLockScreenService} is connected,
     * falling back to {@code StatusBarManager.expandNotificationsPanel()} via reflection.
     */
    public static void expandNotificationsPanel(Context context) {
        if (PocoLockScreenService.performExpandNotifications()) {
            return;
        }
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        try {
            Object sbm = ctx.getSystemService("statusbar");
            if (sbm != null) {
                Method expand = sbm.getClass().getMethod("expandNotificationsPanel");
                expand.invoke(sbm);
            }
        } catch (Throwable t) {
            Log.w(TAG, "expandNotificationsPanel fallback failed: " + t.getMessage());
        }
    }

    public static String getSystemNavigationModeLabel(Context context) {
        Context ctx = context != null ? context : getAppContext();
        int mode = 0;
        if (ctx != null) {
            try {
                mode = Settings.Secure.getInt(ctx.getContentResolver(), "navigation_mode", 0);
            } catch (Throwable ignored) {
            }
        }
        boolean pt = isPortugueseLocale();
        if (mode == 2) {
            return pt ? "Gestos" : "Gestures";
        }
        return pt ? "Botões" : "Buttons";
    }

    public static void openSystemNavigationSettings(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        PackageManager pm = ctx.getPackageManager();
        Intent[] candidates = new Intent[]{
                new Intent("com.samsung.settings.NAVIGATIONBAR_SETTINGS"),
                new Intent().setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings$NavigationBarSettingsActivity")),
                new Intent("android.settings.SYSTEM_NAVIGATION_SETTINGS"),
                new Intent().setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings$SystemNavigationSettingsActivity")),
                new Intent(Settings.ACTION_DISPLAY_SETTINGS),
        };
        for (Intent candidate : candidates) {
            try {
                if (!(ctx instanceof Activity)) {
                    candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                if (candidate.resolveActivity(pm) != null) {
                    ctx.startActivity(candidate);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static void openIconCustomizeActivity(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent("com.miui.home.action.ICON_CUSTOMIZE");
            intent.setPackage(ctx.getPackageName());
            if (!(ctx instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "openIconCustomizeActivity failed: " + t.getMessage());
        }
    }

    private static void refreshDoubleTapLockSync(Context ctx) {
        try {
            Class<?> cls = Class.forName("com.miui.launcher.utils.LauncherUtils");
            Method m = cls.getMethod("refreshDoubleTapLock", Context.class);
            m.invoke(null, ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isDoubleTapLockSettingOn(Context ctx) {
        return PocoSettingsCompat.systemGetBoolean(ctx.getContentResolver(), "lock_screen_mode", false);
    }

    private static void showToastOnMain(final Context ctx, final String text) {
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Runnable show = new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appCtx, text, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            new Handler(Looper.getMainLooper()).post(show);
        }
    }

    // ==================== Hide Apps Native Compatibility & Modern Biometrics ====================

    /**
     * One-time seamless migration guard for devices upgrading from earlier builds where POCO's
     * Xiaomi-only {@code HideAppsTransferController} disabled {@code hide_apps_switch} and set
     * {@code first_enter_hidden_in_old_machine=true}. Restores {@code hide_apps_switch=true} once
     * without requiring a data wipe, while respecting any explicit user toggle afterwards.
     */
    public static void ensureHideAppsMigrationChecked() {
        if (sHideAppsMigrationChecked) {
            return;
        }
        try {
            Class<?> prefCls = Class.forName(
                    "com.miui.home.launcher.allapps.hideapps.HideAppPreferenceHelper");
            Object prefHelper = prefCls.getMethod("getInstance").invoke(null);
            if (prefHelper == null) {
                return;
            }
            Method getBool = findMethodInHierarchy(
                    prefHelper.getClass(), "getBoolean", String.class, boolean.class);
            Method putBool = findMethodInHierarchy(
                    prefHelper.getClass(), "putBoolean", String.class, boolean.class);
            if (getBool == null || putBool == null) {
                return;
            }
            sHideAppsMigrationChecked = true;
            if (Boolean.TRUE.equals(getBool.invoke(prefHelper, "poco_compat_hide_apps_migrated_v1", false))) {
                return;
            }
            putBool.invoke(prefHelper, "poco_compat_hide_apps_migrated_v1", true);

            boolean wasTransferred = Boolean.TRUE.equals(
                    getBool.invoke(prefHelper, "first_enter_hidden_in_old_machine", false));
            boolean switchOpen = Boolean.TRUE.equals(
                    getBool.invoke(prefHelper, "hide_apps_switch", false));
            Method getStr = findMethodInHierarchy(
                    prefHelper.getClass(), "getString", String.class, String.class);
            Object pwd = getStr != null ? getStr.invoke(prefHelper, "hide_apps_lock_password", null) : null;
            boolean hasPwd = pwd instanceof String && !((String) pwd).isEmpty();
            if (wasTransferred && !switchOpen && !hasPwd) {
                putBool.invoke(prefHelper, "first_enter_hidden_in_old_machine", false);
                putBool.invoke(prefHelper, "hide_apps_switch", true);
                Log.i(TAG, "Restored Hide Apps switch after legacy Xiaomi transfer marker");
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureHideAppsMigrationChecked failed: " + t.getMessage());
        }
    }

    /**
     * Public-SDK replacement for {@code HideAppsLockUtils.isScreenLockOpen(Context)} which
     * reflected MIUI-only {@code MiuiSettings$Secure.hasCommonPassword}.
     */
    public static boolean isDeviceScreenLockOpen(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return false;
        }
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isDeviceSecure();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Public-SDK replacement for deprecated {@code FingerprintManager.isHardwareDetected()} in
     * {@code FingerprintHelper.isHardwareDetectedHideAppsLock()}.
     */
    public static boolean isBiometricHardwareDetected(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return false;
        }
        try {
            BiometricManager bm = ctx.getSystemService(BiometricManager.class);
            if (bm == null) {
                return false;
            }
            int res = bm.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.BIOMETRIC_WEAK);
            return res == BiometricManager.BIOMETRIC_SUCCESS
                    || res == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Public-SDK replacement for deprecated {@code FingerprintManager.hasEnrolledFingerprints()}
     * in {@code FingerprintHelper.hasEnrolledFingerprintsHideAppsLock()}.
     */
    public static boolean hasEnrolledBiometrics(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return false;
        }
        try {
            BiometricManager bm = ctx.getSystemService(BiometricManager.class);
            if (bm == null) {
                return false;
            }
            return bm.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Public-SDK replacement for {@code FingerprintHelper.authenticateHideAppsLock(...)}.
     * Uses Android's native {@link BiometricPrompt} sheet (supports side/in-display fingerprint
     * and One UI biometrics seamlessly) with a "Use pattern" negative button.
     */
    public static void authenticateHideAppsBiometric(
            final Object fingerprintHelper,
            final Object hideAppsCallback
    ) {
        if (fingerprintHelper == null || hideAppsCallback == null) {
            return;
        }
        cancelHideAppsBiometric(fingerprintHelper);
        final Context uiCtx = resolveBiometricContext();
        if (uiCtx == null || !hasEnrolledBiometrics(uiCtx)) {
            invokeHideAppsAuthCallback(hideAppsCallback, "onAuthenticationCanceled");
            return;
        }
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean pt = isPortugueseLocale();
                    Executor mainExec = uiCtx.getMainExecutor();
                    final CancellationSignal signal = new CancellationSignal();
                    ACTIVE_BIOMETRIC_SIGNALS.put(fingerprintHelper, signal);

                    BiometricPrompt prompt = new BiometricPrompt.Builder(uiCtx)
                            .setTitle(pt ? "Apps ocultos" : "Hidden apps")
                            .setSubtitle(pt ? "Use sua biometria para desbloquear" : "Use biometrics to unlock")
                            .setAllowedAuthenticators(
                                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                                            | BiometricManager.Authenticators.BIOMETRIC_WEAK)
                            .setNegativeButton(
                                    pt ? "Usar padrão" : "Use pattern",
                                    mainExec,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            ACTIVE_BIOMETRIC_SIGNALS.remove(fingerprintHelper, signal);
                                            invokeHideAppsAuthCallback(hideAppsCallback, "onAuthenticationCanceled");
                                        }
                                    })
                            .build();

                    prompt.authenticate(signal, mainExec, new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            ACTIVE_BIOMETRIC_SIGNALS.remove(fingerprintHelper, signal);
                            invokeHideAppsAuthCallback(hideAppsCallback, "onAuthenticationSucceeded");
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // BiometricPrompt handles retry UI & haptics natively; avoid calling
                            // HideAppsUnlockContainerView.onAuthenticationFailed() which disables
                            // the pattern view.
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            ACTIVE_BIOMETRIC_SIGNALS.remove(fingerprintHelper, signal);
                            if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT
                                    || errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT) {
                                invokeHideAppsAuthCallback(hideAppsCallback, "onAuthenticationLockout");
                            } else {
                                invokeHideAppsAuthCallback(hideAppsCallback, "onAuthenticationCanceled");
                            }
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "authenticateHideAppsBiometric failed: " + t.getMessage());
                    ACTIVE_BIOMETRIC_SIGNALS.remove(fingerprintHelper);
                    invokeHideAppsAuthCallback(hideAppsCallback, "onAuthenticationCanceled");
                }
            }
        });
    }

    public static void cancelHideAppsBiometric(Object fingerprintHelper) {
        if (fingerprintHelper == null) {
            return;
        }
        CancellationSignal signal = ACTIVE_BIOMETRIC_SIGNALS.remove(fingerprintHelper);
        if (signal != null && !signal.isCanceled()) {
            try {
                signal.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Replacement for {@code HideAppDialogManager.createFingerprintVerityDialog()}: avoids
     * stacking a legacy MIUI fingerprint dialog underneath Android's native {@link BiometricPrompt}
     * sheet, and proceeds cleanly to pattern setup if no pattern exists yet.
     */
    public static void startHideAppsFingerprintVerify(final Object dialogManager) {
        final Context uiCtx = resolveBiometricContext();
        if (uiCtx == null || !hasEnrolledBiometrics(uiCtx)) {
            openHideAppsPatternSetupIfUnset();
            return;
        }
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean pt = isPortugueseLocale();
                    Executor mainExec = uiCtx.getMainExecutor();
                    CancellationSignal signal = new CancellationSignal();
                    if (dialogManager != null) {
                        cancelHideAppsBiometric(dialogManager);
                        ACTIVE_BIOMETRIC_SIGNALS.put(dialogManager, signal);
                    }
                    final CancellationSignal sigRef = signal;

                    BiometricPrompt prompt = new BiometricPrompt.Builder(uiCtx)
                            .setTitle(pt ? "Ativar desbloqueio biométrico" : "Enable biometric unlock")
                            .setSubtitle(pt
                                    ? "Use sua digital para abrir os apps ocultos"
                                    : "Use biometrics to open hidden apps")
                            .setAllowedAuthenticators(
                                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                                            | BiometricManager.Authenticators.BIOMETRIC_WEAK)
                            .setNegativeButton(
                                    pt ? "Agora não" : "Not now",
                                    mainExec,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            if (dialogManager != null) {
                                                ACTIVE_BIOMETRIC_SIGNALS.remove(dialogManager, sigRef);
                                            }
                                            onHideAppsBiometricVerifyDeclined();
                                        }
                                    })
                            .build();

                    prompt.authenticate(signal, mainExec, new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            if (dialogManager != null) {
                                ACTIVE_BIOMETRIC_SIGNALS.remove(dialogManager, sigRef);
                            }
                            setHideAppsFingerprintEnabled(true);
                            boolean ptNow = isPortugueseLocale();
                            showToastOnMain(uiCtx, ptNow
                                    ? "Biometria ativada para apps ocultos"
                                    : "Biometrics enabled for hidden apps");
                            openHideAppsPatternSetupIfUnset();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (dialogManager != null) {
                                ACTIVE_BIOMETRIC_SIGNALS.remove(dialogManager, sigRef);
                            }
                            onHideAppsBiometricVerifyDeclined();
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "startHideAppsFingerprintVerify failed: " + t.getMessage());
                    onHideAppsBiometricVerifyDeclined();
                }
            }
        });
    }

    /**
     * Hooked at the end of {@code HideAppsUnlockContainerView.onFinishInflate()} so tapping the
     * fingerprint icon re-triggers {@link BiometricPrompt} after dismissing it once, and applies
     * Dark/Light mode colors immediately (including when inflated inside the Disable Hide Apps
     * confirmation sheet).
     */
    public static void wireHideAppsUnlockView(final Object unlockView) {
        if (unlockView == null) {
            return;
        }
        try {
            Object iconObj = readField(unlockView, "mFingerprintIcon");
            if (iconObj instanceof View) {
                ((View) iconObj).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            invokeZeroArg(unlockView, "registerFingerprint");
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            invokeZeroArg(unlockView, "updateColorForUiMode");
        } catch (Throwable ignored) {
        }
    }

    /**
     * Binds {@code HideAppsViewPlaceHolderSheet.hideAppsViewPlaceHolder} after XML inflation.
     * POCO's original 2-arg Kotlin constructor called {@code findViewById} before
     * {@link android.view.LayoutInflater} inflated children, always throwing
     * {@code InflateException(NullPointerException)} and trapping users on the password guide.
     */
    public static void bindHideAppsPlaceHolderSheet(View sheet) {
        if (sheet == null) {
            return;
        }
        try {
            if (readField(sheet, "hideAppsViewPlaceHolder") != null) {
                return;
            }
            View holder = null;
            Context ctx = sheet.getContext();
            if (ctx != null && ctx.getResources() != null) {
                int id = ctx.getResources().getIdentifier(
                        "hide_apps_view_place_holder", "id", ctx.getPackageName());
                if (id != 0) {
                    holder = sheet.findViewById(id);
                }
            }
            if (holder == null && sheet instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) sheet;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (child != null && child.getClass().getName().endsWith("HideAppsViewPlaceHolder")) {
                        holder = child;
                        break;
                    }
                }
            }
            if (holder != null) {
                writeField(sheet, "hideAppsViewPlaceHolder", holder);
            }
        } catch (Throwable t) {
            Log.w(TAG, "bindHideAppsPlaceHolderSheet failed: " + t.getMessage());
        }
    }

    /**
     * Replacement for {@code HideAppsViewPlaceHolder.hide()}.
     *
     * <p>The original {@code hide()} checked {@code getAnimation() != null && getAnimation().hasStarted()},
     * which remained {@code true} after {@code show()} started {@code mEnterAnim} without ever
     * clearing it — causing {@code hide()} to skip {@code mExitAnim} and {@code mCurrentView.onExit()}
     * so {@code onPasswordSetSuccess()} was NEVER called after confirming a new pattern.
     */
    public static void closeHideAppsViewPlaceHolder(View placeHolder) {
        if (placeHolder == null) {
            return;
        }
        try {
            placeHolder.clearAnimation();
        } catch (Throwable ignored) {
        }
        Object currentView = readField(placeHolder, "mCurrentView");
        Object closeCallback = readField(placeHolder, "mCloseCallback");
        writeField(placeHolder, "mCurrentView", null);
        writeField(placeHolder, "mCloseCallback", null);

        if (currentView != null) {
            boolean pwdSetSuccess = currentView.getClass().getName().endsWith("HideAppsSetPasswordContainerView")
                    && Boolean.TRUE.equals(readField(currentView, "setPasswordSuccess"))
                    && hasHideAppsPassword();
            if (pwdSetSuccess) {
                prepareLauncherForHideAppsContent();
            }
            try {
                invokeZeroArg(currentView, "onExit");
            } catch (Throwable t) {
                Log.w(TAG, "HideApps currentView.onExit failed: " + t.getMessage());
            }
            try {
                invokeZeroArg(currentView, "onHide");
            } catch (Throwable ignored) {
            }
        }

        try {
            if (placeHolder instanceof ViewGroup) {
                ((ViewGroup) placeHolder).removeAllViews();
            }
            placeHolder.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }

        if (closeCallback instanceof Supplier) {
            try {
                ((Supplier<?>) closeCallback).get();
            } catch (Throwable t) {
                Log.w(TAG, "HideApps closeCallback failed: " + t.getMessage());
            }
        }
    }

    private static void prepareLauncherForHideAppsContent() {
        try {
            Object launcher = getActiveLauncher();
            if (launcher == null) {
                return;
            }
            Object stateManager = invokeZeroArg(launcher, "getStateManager");
            Class<?> stateCls = reflectGetClass("com.miui.home.launcher.LauncherState");
            Object allAppsState = stateCls != null ? stateCls.getField("ALL_APPS").get(null) : null;
            if (stateManager != null && allAppsState != null) {
                Object curState = invokeZeroArg(stateManager, "getState");
                if (curState != allAppsState) {
                    Method goTo = findMethodInHierarchy(
                            stateManager.getClass(), "goToState", stateCls, boolean.class);
                    if (goTo != null) {
                        goTo.invoke(stateManager, allAppsState, Boolean.FALSE);
                    }
                }
            }
            Object appsView = invokeZeroArg(launcher, "getAppsView");
            if (appsView != null) {
                Object hideContainer = readField(appsView, "mHideAppsContainerView");
                if (hideContainer == null) {
                    invokeZeroArg(appsView, "createHideAppContainerView");
                    hideContainer = readField(appsView, "mHideAppsContainerView");
                }
                if (hideContainer instanceof View) {
                    ensureHideAppsListSynced((View) hideContainer);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "prepareLauncherForHideAppsContent failed: " + t.getMessage());
        }
    }

    /**
     * Ensures {@code HideAppsContentView} and {@code HideAppsSelectView} are populated from
     * {@code AllAppsStore} before entering the Hidden Apps vault (prevents an empty app picker
     * when Hide Apps is enabled for the first time without restarting the launcher).
     */
    public static void ensureHideAppsListSynced(View hideAppsContainerView) {
        if (hideAppsContainerView == null) {
            return;
        }
        try {
            Method setApps = findMethodInHierarchy(
                    hideAppsContainerView.getClass(), "setApps", List.class);
            if (setApps != null) {
                setApps.invoke(hideAppsContainerView, Collections.emptyList());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hooked at the end of {@code HideAppsSetPasswordContainerView.onFinishInflate()} to fix
     * Dark Mode contrast on the bottom action buttons (whose selector hardcoded black text with
     * no night-mode override) and update the pattern dot bitmaps for the active UI mode.
     */
    public static void styleHideAppsSetPasswordButtons(View container) {
        if (container == null) {
            return;
        }
        try {
            boolean darkMode = false;
            try {
                Class<?> dcCls = reflectGetClass("com.miui.home.common.device.DeviceConfigs");
                if (dcCls != null) {
                    darkMode = Boolean.TRUE.equals(dcCls.getMethod("isDarkMode").invoke(null));
                }
            } catch (Throwable ignored) {
            }
            if (!darkMode && container.getResources() != null) {
                int nightMask = container.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                darkMode = (nightMask == Configuration.UI_MODE_NIGHT_YES);
            }

            Object mediator = readField(container, "passwordUnlockMediator");
            if (mediator != null) {
                try {
                    invokeZeroArg(mediator, "updateColor");
                } catch (Throwable ignored) {
                }
            }

            if (darkMode) {
                int enabledColor = 0xE6FFFFFF;
                int disabledColor = 0x4DFFFFFF;
                int[][] states = new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_enabled},
                        new int[]{}
                };
                ColorStateList darkBtnColors = new ColorStateList(
                        states, new int[]{disabledColor, enabledColor, enabledColor});

                Object leftBtn = readField(container, "mFooterLeftButton");
                if (leftBtn instanceof TextView) {
                    ((TextView) leftBtn).setTextColor(darkBtnColors);
                }
                Object rightBtn = readField(container, "mFooterRightButton");
                if (rightBtn instanceof TextView) {
                    ((TextView) rightBtn).setTextColor(darkBtnColors);
                }
                Object titleTv = readField(container, "unlockTitle");
                if (titleTv instanceof TextView) {
                    ((TextView) titleTv).setTextColor(enabledColor);
                }
                Object descTv = readField(container, "unlockDescribe");
                if (descTv instanceof TextView) {
                    ((TextView) descTv).setTextColor(0x99FFFFFF);
                }
                Object titleBarLeft = readField(container, "titleBarLeftBtn");
                if (titleBarLeft != null) {
                    try {
                        invokeOneArg(titleBarLeft, "setPatternColor", int.class, 0xB3FFFFFF);
                    } catch (Throwable ignored) {
                    }
                }
                Object titleBarTitle = readField(container, "titleBarTitleContent");
                if (titleBarTitle instanceof ViewGroup) {
                    ViewGroup switcher = (ViewGroup) titleBarTitle;
                    for (int i = 0; i < switcher.getChildCount(); i++) {
                        View child = switcher.getChildAt(i);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(enabledColor);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "styleHideAppsSetPasswordButtons failed: " + t.getMessage());
        }
    }

    /**
     * Replacement for {@code HideAppsPasswordGuideView.lambda$onFinishInflate$0(View)}.
     * If a Hide Apps pattern is already configured, switches the vault to its unlock screen
     * instead of returning early and leaving the user stranded on the "Set password" guide.
     */
    public static void onHideAppsPasswordGuideClick(View view) {
        try {
            if (hasHideAppsPassword()) {
                Object launcher = getActiveLauncher();
                if (launcher != null) {
                    Object appsView = invokeZeroArg(launcher, "getAppsView");
                    Object hideContainer = appsView != null
                            ? readField(appsView, "mHideAppsContainerView")
                            : null;
                    if (hideContainer != null) {
                        if (hideContainer instanceof View) {
                            ensureHideAppsListSynced((View) hideContainer);
                        }
                        invokeZeroArg(hideContainer, "changeToUnlockView");
                        invokeZeroArg(hideContainer, "onShow");
                        return;
                    }
                }
            }
            Class<?> mgrCls = reflectGetClass(
                    "com.miui.home.launcher.allapps.hideapps.HideAppDialogManager");
            if (mgrCls != null) {
                Object mgr = mgrCls.getMethod("getInstance").invoke(null);
                if (mgr != null) {
                    invokeOneArg(mgr, "onHideAppsSwitchClick", boolean.class, Boolean.TRUE);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onHideAppsPasswordGuideClick failed: " + t.getMessage());
        }
    }

    /**
     * Replacement for {@code HideAppsUnlockContainerView.onForgetPasswordBtnClick()} which
     * required a Xiaomi Account or emailing {@code global-poco@xiaomi.com}. Verifies the user via
     * Android's device screen lock (PIN, pattern, password, or biometrics) and opens the native
     * pattern reset sheet immediately on success.
     */
    public static void handleHideAppsForgetPassword(final Object unlockView) {
        if (unlockView == null) {
            return;
        }
        try {
            invokeZeroArg(unlockView, "unregisterFingerprint");
        } catch (Throwable ignored) {
        }
        final Context uiCtx = resolveBiometricContext();
        if (uiCtx == null) {
            return;
        }
        if (!isDeviceScreenLockOpen(uiCtx)) {
            boolean pt = isPortugueseLocale();
            showToastOnMain(uiCtx, pt ? "Desenhe um novo padrão" : "Draw a new pattern");
            openHideAppsPatternResetSheet(unlockView);
            return;
        }
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean pt = isPortugueseLocale();
                    Executor mainExec = uiCtx.getMainExecutor();
                    CancellationSignal signal = new CancellationSignal();
                    // DEVICE_CREDENTIAL allows PIN/pattern/password/biometrics (do NOT setNegativeButton).
                    BiometricPrompt prompt = new BiometricPrompt.Builder(uiCtx)
                            .setTitle(pt ? "Redefinir padrão dos apps ocultos" : "Reset hidden apps pattern")
                            .setSubtitle(pt
                                    ? "Confirme o bloqueio de tela do dispositivo"
                                    : "Confirm your device screen lock")
                            .setAllowedAuthenticators(
                                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                                            | BiometricManager.Authenticators.BIOMETRIC_WEAK
                                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                            .build();
                    prompt.authenticate(signal, mainExec, new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            openHideAppsPatternResetSheet(unlockView);
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            // Leave pattern screen usable without looping the prompt.
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "handleHideAppsForgetPassword failed: " + t.getMessage());
                    openHideAppsPatternResetSheet(unlockView);
                }
            }
        });
    }

    private static Context resolveBiometricContext() {
        Object launcher = getActiveLauncher();
        if (launcher instanceof Activity) {
            Activity act = (Activity) launcher;
            if (!act.isFinishing() && !act.isDestroyed()) {
                return act;
            }
        }
        return getAppContext();
    }

    private static void invokeHideAppsAuthCallback(Object callback, String methodName) {
        if (callback == null) {
            return;
        }
        try {
            invokeZeroArg(callback, methodName);
        } catch (Throwable t) {
            Log.w(TAG, "HideApps auth callback " + methodName + " failed: " + t.getMessage());
        }
    }

    private static void setHideAppsFingerprintEnabled(boolean enabled) {
        try {
            Class<?> utilsCls = Class.forName(
                    "com.miui.home.launcher.allapps.hideapps.HideAppsLockUtils");
            utilsCls.getMethod("setFingerprintEnable", boolean.class).invoke(null, enabled);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasHideAppsPassword() {
        try {
            Class<?> utilsCls = Class.forName(
                    "com.miui.home.launcher.allapps.hideapps.HideAppsLockUtils");
            return Boolean.TRUE.equals(utilsCls.getMethod("hasHideAppsUnlockPassWord").invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isHideAppsSwitchOpen() {
        try {
            Class<?> utilsCls = Class.forName(
                    "com.miui.home.launcher.allapps.hideapps.HideAppsLockUtils");
            return Boolean.TRUE.equals(utilsCls.getMethod("isHideAppsOpen").invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int getHiddenAppsCount() {
        try {
            Class<?> utilsCls = Class.forName(
                    "com.miui.home.launcher.allapps.hideapps.HideAppsLockUtils");
            Object setObj = utilsCls.getMethod("getHideApps").invoke(null);
            if (setObj instanceof Collection) {
                return ((Collection<?>) setObj).size();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void onHideAppsBiometricVerifyDeclined() {
        try {
            Class<?> prefCls = Class.forName(
                    "com.miui.home.launcher.allapps.hideapps.HideAppPreferenceHelper");
            Object prefHelper = prefCls.getMethod("getInstance").invoke(null);
            if (prefHelper != null) {
                Method getInt = findMethodInHierarchy(
                        prefHelper.getClass(), "getInt", String.class, int.class);
                Method putInt = findMethodInHierarchy(
                        prefHelper.getClass(), "putIntForce", String.class, int.class);
                if (getInt != null && putInt != null) {
                    int cur = (Integer) getInt.invoke(prefHelper, "cancel_fingerprint_verify_times", 0);
                    putInt.invoke(prefHelper, "cancel_fingerprint_verify_times", cur + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        setHideAppsFingerprintEnabled(false);
        openHideAppsPatternSetupIfUnset();
    }

    public static void openHideAppsPatternSetupIfUnset() {
        if (!hasHideAppsPassword()) {
            showHideAppsPlaceHolder(0, null);
        }
    }

    private static void openHideAppsPatternResetSheet(Object unlockView) {
        if (unlockView != null) {
            writeField(unlockView, "isFromForgetPassword", Boolean.TRUE);
            try {
                invokeZeroArg(unlockView, "unregisterFingerprint");
            } catch (Throwable ignored) {
            }
        }
        Bundle bundle = new Bundle();
        bundle.putBoolean("is_from_reset_password", true);
        showHideAppsPlaceHolder(0, bundle);
    }

    private static void showHideAppsPlaceHolder(int page, Bundle bundle) {
        try {
            Class<?> sheetCls = Class.forName(
                    "com.miui.home.launcher.allapps.hideapps.HideAppsViewPlaceHolderSheet");
            Object companion = sheetCls.getField("Companion").get(null);
            if (companion != null) {
                Method show = companion.getClass().getMethod(
                        "showHideAppsViewPlaceHolder", int.class, Bundle.class);
                show.invoke(companion, page, bundle);
            }
        } catch (Throwable t) {
            Log.w(TAG, "showHideAppsPlaceHolder failed: " + t.getMessage());
        }
    }

    private static void openHiddenAppsVaultOrSettings(Object fragment, Context ctx) {
        try {
            boolean drawerMode = false;
            try {
                Class<?> ctrlCls = Class.forName(
                        "com.miui.home.launcher.allapps.LauncherModeController");
                drawerMode = Boolean.TRUE.equals(ctrlCls.getMethod("isDrawerMode").invoke(null));
            } catch (Throwable ignored) {
            }
            if (drawerMode && isHideAppsSwitchOpen() && getActiveLauncher() != null) {
                navigateToHomeForSettings(fragment, 3);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Object launcher = getActiveLauncher();
                            if (launcher == null) {
                                return;
                            }
                            Object stateManager = invokeZeroArg(launcher, "getStateManager");
                            Class<?> stateCls = Class.forName("com.miui.home.launcher.LauncherState");
                            Object allAppsState = stateCls.getField("ALL_APPS").get(null);
                            if (stateManager != null && allAppsState != null) {
                                Method goTo = findMethodInHierarchy(
                                        stateManager.getClass(), "goToState", stateCls, boolean.class);
                                if (goTo != null) {
                                    goTo.invoke(stateManager, allAppsState, Boolean.FALSE);
                                }
                            }
                            Object appsView = invokeZeroArg(launcher, "getAppsView");
                            if (appsView != null) {
                                Object controller = readField(appsView, "mHideAppsTransitionController");
                                if (controller == null) {
                                    invokeZeroArg(appsView, "createHideAppContainerView");
                                    controller = readField(appsView, "mHideAppsTransitionController");
                                }
                                Object hideContainer = readField(appsView, "mHideAppsContainerView");
                                if (hideContainer instanceof View) {
                                    ensureHideAppsListSynced((View) hideContainer);
                                }
                                if (controller != null) {
                                    invokeOneArg(controller, "showHideView", boolean.class, Boolean.TRUE);
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Opening Hidden Apps vault failed: " + t.getMessage());
                        }
                    }
                }, 180L);
                return;
            }
            Intent intent = new Intent("com.miui.home.action.ALL_APPS_SETTINGS");
            intent.setPackage(ctx.getPackageName());
            intent.putExtra("is_drawer_setting", false);
            if (!(ctx instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "openHiddenAppsVaultOrSettings failed: " + t.getMessage());
        }
    }

    private static void runOnMainThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            new Handler(Looper.getMainLooper()).post(action);
        }
    }

    // ==================== Non-Xiaomi Settings, Search & Security Fallbacks ====================

    private static boolean isPortugueseLocale() {
        Locale loc = Locale.getDefault();
        return loc != null && "pt".equalsIgnoreCase(loc.getLanguage());
    }

    /**
     * Replacement for {@code MiuiHomeSettings.initLauncherPullDown()}, which removed the Swipe-down
     * gesture setting whenever Xiaomi AppFinder wasn't installed. Configures 3 working options:
     * Notification shade & Control center (default), Search (Google Search), and None.
     */
    public static void initNonXiaomiPullDownSetting(final Object fragment) {
        if (fragment == null) {
            return;
        }
        try {
            Method findPref = findMethodInHierarchy(
                    fragment.getClass(), "findPreference", CharSequence.class);
            if (findPref == null) {
                return;
            }
            final Object pullDownPref = findPref.invoke(fragment, "launcher_pull_down");
            if (pullDownPref == null) {
                return;
            }
            writeField(fragment, "mLauncherPullDown", pullDownPref);

            String keyNotif = (String) readField(fragment, "KEY_NOTIFICATION_BAR");
            String keySearch = (String) readField(fragment, "KEY_GLOBAL_SEARCH");
            String keyNone = (String) readField(fragment, "KEY_NO_ACTION");
            String keyShelf = (String) readField(fragment, "KEY_GLOBAL_SHELF");
            if (keyNotif == null || keySearch == null || keyNone == null) {
                return;
            }

            final Map<String, String> map = new HashMap<>();
            map.put(keyNotif, "notification_bar");
            map.put(keySearch, "pull_down_global_search");
            if (keyShelf != null) {
                map.put(keyShelf, "pull_down_global_search");
            }
            map.put(keyNone, "no_action");
            writeField(fragment, "mPullDownContentMap", map);

            CharSequence[] entries = new CharSequence[]{keyNotif, keySearch, keyNone};
            invokeOneArg(pullDownPref, "setEntries", CharSequence[].class, entries);
            invokeOneArg(pullDownPref, "setEntryValues", CharSequence[].class, entries);

            final Context ctx = resolvePreferenceContext(fragment);
            Class<?> changeCls = Class.forName(
                    "androidx.preference.Preference$OnPreferenceChangeListener");
            Object changeProxy = Proxy.newProxyInstance(
                    changeCls.getClassLoader(),
                    new Class<?>[]{changeCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onPreferenceChange".equals(name)) {
                                String selected = args != null && args.length > 1 && args[1] != null
                                        ? args[1].toString() : null;
                                String gestureVal = selected != null ? map.get(selected) : null;
                                if (gestureVal != null && ctx != null) {
                                    try {
                                        Class<?> gcCls = Class.forName(
                                                "com.miui.home.settings.LauncherGestureController");
                                        gcCls.getMethod("putSystemString", Context.class, String.class, String.class)
                                                .invoke(null, ctx, "launcher_pulldown_gesture", gestureVal);
                                    } catch (Throwable ignored) {
                                    }
                                    try {
                                        Class.forName("com.mi.appfinder.launcher.globalsearch.FinderGlobalSearchUtil")
                                                .getMethod("updatePullDownGestureGlobalSearch")
                                                .invoke(null);
                                    } catch (Throwable ignored) {
                                    }
                                    try {
                                        Class.forName("com.miui.home.launcher.GlobalSearchUtil")
                                                .getMethod("updatePullDownGestureGlobalSearch")
                                                .invoke(null);
                                    } catch (Throwable ignored) {
                                    }
                                    try {
                                        invokeOneArg(pullDownPref, "setValue", String.class, selected);
                                    } catch (Throwable ignored) {
                                    }
                                    writeField(fragment, "currSlidDownConfig", selected);
                                }
                                return Boolean.TRUE;
                            }
                            if ("toString".equals(name)) {
                                return "PocoCompatPullDownChangeListener";
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return proxy == (args != null && args.length > 0 ? args[0] : null);
                            }
                            return null;
                        }
                    });
            invokeOneArg(pullDownPref, "setOnPreferenceChangeListener", changeCls, changeProxy);

            String currentVal = resolveCurrentPullDownLabel(ctx, keyNotif, keySearch, keyNone);
            writeField(fragment, "currSlidDownConfig", currentVal);
            invokeOneArg(pullDownPref, "setValue", String.class, currentVal);
            invokeOneArg(pullDownPref, "setVisible", boolean.class, Boolean.TRUE);
        } catch (Throwable t) {
            Log.w(TAG, "initNonXiaomiPullDownSetting failed: " + t.getMessage());
        }
    }

    private static String resolveCurrentPullDownLabel(
            Context ctx,
            String keyNotif,
            String keySearch,
            String keyNone
    ) {
        try {
            Class<?> gcCls = Class.forName("com.miui.home.settings.LauncherGestureController");
            Object val = gcCls.getMethod("getPullDownGesture", Context.class)
                    .invoke(null, ctx != null ? ctx : getAppContext());
            if ("pull_down_global_search".equals(val) || "global_search".equals(val)) {
                return keySearch;
            }
            if ("no_action".equals(val)) {
                return keyNone;
            }
        } catch (Throwable ignored) {
        }
        return keyNotif;
    }

    /**
     * Replacement for {@code LauncherGestureController.onPullDownAction()} so all three Swipe-down
     * gesture modes work natively on non-Xiaomi devices: Notification shade, Search (Google Search
     * fallback), and None — without needing MIUI Quickstep's GlobalSearchSwipeController overlay.
     */
    public static void handlePullDownAction(Object gestureController) {
        if (gestureController == null) {
            return;
        }
        try {
            Object launcherObj = readField(gestureController, "mLauncher");
            if (!(launcherObj instanceof Activity)) {
                launcherObj = getActiveLauncher();
            }
            Activity launcher = launcherObj instanceof Activity ? (Activity) launcherObj : null;
            Object keyObj = invokeZeroArg(gestureController, "getPullDownGesture");
            String key = keyObj != null ? keyObj.toString() : "notification_bar";
            if ("no_action".equals(key)) {
                return;
            }
            if ("pull_down_global_search".equals(key) || "global_search".equals(key)) {
                startGlobalSearchFallback(launcher, "pulldown", null, 0, 0, null);
                return;
            }
            if (launcher != null) {
                expandNotificationsPanel(launcher);
                try {
                    final Object shortcutLayer = invokeZeroArg(launcher, "getShortcutMenuLayer");
                    if (shortcutLayer instanceof View) {
                        ((View) shortcutLayer).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Class<?> vuCls = Class.forName("com.miui.home.common.utils.ViewUtils");
                                    vuCls.getMethod("cancelTouchEvent", View.class).invoke(null, shortcutLayer);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "handlePullDownAction failed: " + t.getMessage());
        }
    }

    public static void setupNonXiaomiHomeSettings(final Object fragment) {
        if (fragment == null) {
            return;
        }
        try {
            hideInertMiuiHomeSettings(fragment);

            final Context prefCtx = resolvePreferenceContext(fragment);
            if (prefCtx == null) {
                return;
            }
            wireSystemNavigationPreference(fragment, prefCtx);

            Object category = readField(fragment, "mMiuiHomeSettingConfig");
            if (category == null) {
                refreshNonXiaomiHomeSettings(fragment);
                return;
            }

            Method findPref = findMethodInHierarchy(
                    fragment.getClass(), "findPreference", CharSequence.class);
            Class<?> valuePrefCls = Class.forName("com.miui.home.settings.preference.ValuePreference");
            Class<?> listenerCls = Class.forName(
                    "androidx.preference.Preference$OnPreferenceClickListener");
            Class<?> prefBaseCls = Class.forName("androidx.preference.Preference");
            Method addPref = findMethodInHierarchy(category.getClass(), "addPreference", prefBaseCls);

            if (findPref == null || findPref.invoke(fragment, PREF_KEY_ICON_CUSTOMIZE) == null) {
                Object iconPref = valuePrefCls.getConstructor(Context.class).newInstance(prefCtx);
                invokeOneArg(iconPref, "setKey", String.class, PREF_KEY_ICON_CUSTOMIZE);
                invokeOneArg(iconPref, "setPersistent", boolean.class, Boolean.FALSE);
                invokeOneArg(iconPref, "setOnPreferenceClickListener", listenerCls,
                        createPreferenceClickProxy(listenerCls, "PocoCompatIconCustomizeClick", new Runnable() {
                            @Override
                            public void run() {
                                openIconCustomizeActivity(prefCtx);
                            }
                        }));
                if (addPref != null) {
                    addPref.invoke(category, iconPref);
                }
            }

            if (findPref == null || findPref.invoke(fragment, PREF_KEY_HIDE_APPS_SHORTCUT) == null) {
                Object hidePref = valuePrefCls.getConstructor(Context.class).newInstance(prefCtx);
                invokeOneArg(hidePref, "setKey", String.class, PREF_KEY_HIDE_APPS_SHORTCUT);
                invokeOneArg(hidePref, "setPersistent", boolean.class, Boolean.FALSE);
                invokeOneArg(hidePref, "setOnPreferenceClickListener", listenerCls,
                        createPreferenceClickProxy(listenerCls, "PocoCompatHideAppsClick", new Runnable() {
                            @Override
                            public void run() {
                                openHiddenAppsVaultOrSettings(fragment, prefCtx);
                            }
                        }));
                if (addPref != null) {
                    addPref.invoke(category, hidePref);
                }
            }

            if (findPref == null || findPref.invoke(fragment, PREF_KEY_NOTIFICATION_ACCESS) == null) {
                Object notifPref = valuePrefCls.getConstructor(Context.class).newInstance(prefCtx);
                invokeOneArg(notifPref, "setKey", String.class, PREF_KEY_NOTIFICATION_ACCESS);
                invokeOneArg(notifPref, "setPersistent", boolean.class, Boolean.FALSE);
                invokeOneArg(notifPref, "setOnPreferenceClickListener", listenerCls,
                        createPreferenceClickProxy(listenerCls, "PocoCompatNotificationAccessClick", new Runnable() {
                            @Override
                            public void run() {
                                openNotificationAccessSettings(prefCtx);
                            }
                        }));
                if (addPref != null) {
                    addPref.invoke(category, notifPref);
                }
            }

            Object recentsCat = readField(fragment, "mRecentsConfigPrefCategory");
            if (recentsCat != null && (findPref == null || findPref.invoke(fragment, PREF_KEY_DEFAULT_HOME) == null)) {
                Object defaultHomePref = valuePrefCls.getConstructor(Context.class).newInstance(prefCtx);
                invokeOneArg(defaultHomePref, "setKey", String.class, PREF_KEY_DEFAULT_HOME);
                invokeOneArg(defaultHomePref, "setPersistent", boolean.class, Boolean.FALSE);
                invokeOneArg(defaultHomePref, "setOnPreferenceClickListener", listenerCls,
                        createPreferenceClickProxy(listenerCls, "PocoCompatDefaultHomeClick", new Runnable() {
                            @Override
                            public void run() {
                                openDefaultHomeSettings(prefCtx);
                            }
                        }));
                Method addRecentsPref = findMethodInHierarchy(recentsCat.getClass(), "addPreference", prefBaseCls);
                if (addRecentsPref != null) {
                    addRecentsPref.invoke(recentsCat, defaultHomePref);
                }
            }

            refreshNonXiaomiHomeSettings(fragment);
        } catch (Throwable t) {
            Log.w(TAG, "setupNonXiaomiHomeSettings failed: " + t.getMessage(), t);
        }
    }

    public static void refreshNonXiaomiHomeSettings(Object fragment) {
        if (fragment == null) {
            return;
        }
        try {
            hideInertMiuiHomeSettings(fragment);
            Context ctx = resolvePreferenceContext(fragment);
            if (ctx == null) {
                return;
            }
            refreshDoubleTapLockSync(ctx);
            boolean doubleTapOn = isDoubleTapLockSettingOn(ctx);
            updateDoubleTapLockPreferenceUi(fragment, ctx, doubleTapOn);
            wireSystemNavigationPreference(fragment, ctx);

            Method findPref = findMethodInHierarchy(
                    fragment.getClass(), "findPreference", CharSequence.class);
            if (findPref != null) {
                Object iconPref = findPref.invoke(fragment, PREF_KEY_ICON_CUSTOMIZE);
                if (iconPref != null) {
                    updateIconCustomizePreferenceUi(iconPref);
                }
                Object hidePref = findPref.invoke(fragment, PREF_KEY_HIDE_APPS_SHORTCUT);
                if (hidePref != null) {
                    updateHideAppsShortcutPreferenceUi(hidePref);
                }
                Object notifPref = findPref.invoke(fragment, PREF_KEY_NOTIFICATION_ACCESS);
                if (notifPref != null) {
                    updateNotificationAccessPreferenceUi(notifPref, ctx);
                }
                Object defaultHomePref = findPref.invoke(fragment, PREF_KEY_DEFAULT_HOME);
                if (defaultHomePref != null) {
                    updateDefaultHomePreferenceUi(defaultHomePref, ctx);
                }
            }
            if (isNotificationAccessEnabled(ctx)) {
                ensureNotificationListenerBound(ctx);
                Object launcher = getActiveLauncher();
                if (launcher != null) {
                    Object notifHelper = invokeZeroArg(launcher, "getNotificationCallback");
                    if (notifHelper != null) {
                        invokeZeroArg(notifHelper, "recreateNotificationChangeListener");
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshNonXiaomiHomeSettings failed: " + t.getMessage());
        }
    }

    private static void hideInertMiuiHomeSettings(Object fragment) {
        // Recents layout & RAM info only work when MIUI Quickstep owns Android Recents
        // (platform-signed system provider; impossible without root). Keep the category itself
        // visible so the adapted System Navigation & Default Home entries stay accessible.
        hidePreference(readField(fragment, "mRecentsLayoutStyle"));
        hidePreference(readField(fragment, "mShowMemInfo"));

        // Minus-one screen (Xiaomi App Vault / Google Discover overlay) requires Xiaomi system
        // packages or OEM-signed Google App allowlisting; hide when neither Xiaomi package exists.
        Context ctx = resolvePreferenceContext(fragment);
        if (ctx != null
                && !isPackageInstalled(ctx, "com.mi.globalminusscreen")
                && !isPackageInstalled(ctx, "com.miui.personalassistant")) {
            hidePreference(readField(fragment, "mOpenPersonalAssistant"));
            hidePreference(readField(fragment, "mSwitchPersonalAssistant"));
            hidePreference(readField(fragment, "mOpenPersonalAssistantSettings"));
        }
    }

    private static boolean isPackageInstalled(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) {
            return false;
        }
        Boolean cached = INSTALLED_PKG_CACHE.get(pkg);
        if (cached != null) {
            return cached;
        }
        boolean installed = false;
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            installed = true;
        } catch (Throwable ignored) {
        }
        if (INSTALLED_PKG_CACHE.size() < 64) {
            INSTALLED_PKG_CACHE.put(pkg, installed);
        }
        return installed;
    }

    private static boolean isPocoDefaultHome(Context ctx) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null
                    && ctx.getPackageName().equals(ri.activityInfo.packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void openDefaultHomeSettings(Context context) {
        Context ctx = context != null ? context : getAppContext();
        if (ctx == null) {
            return;
        }
        Intent[] candidates = new Intent[]{
                new Intent(Settings.ACTION_HOME_SETTINGS),
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS),
        };
        PackageManager pm = ctx.getPackageManager();
        for (Intent candidate : candidates) {
            try {
                if (!(ctx instanceof Activity)) {
                    candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                if (candidate.resolveActivity(pm) != null) {
                    ctx.startActivity(candidate);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void updateHideAppsShortcutPreferenceUi(Object pref) {
        try {
            ensureHideAppsMigrationChecked();
            boolean open = isHideAppsSwitchOpen();
            int count = open ? getHiddenAppsCount() : 0;
            boolean pt = isPortugueseLocale();
            String title = pt ? "Ocultar ícones dos apps" : "Hide app icons";
            String value;
            if (!open) {
                value = pt ? "Desativado" : "Off";
            } else if (count > 0) {
                value = pt ? (count + (count == 1 ? " oculto" : " ocultos")) : (count + " hidden");
            } else {
                value = pt ? "Ativado" : "On";
            }
            String summary = pt
                    ? (open
                        ? "Deslize 2× para a direita na gaveta ou toque para abrir"
                        : "Toque para ativar nas configurações da gaveta de apps")
                    : (open
                        ? "Swipe right twice in App drawer or tap to open"
                        : "Tap to enable in App drawer settings");
            invokeOneArg(pref, "setTitle", CharSequence.class, title);
            invokeOneArg(pref, "setSummary", CharSequence.class, summary);
            invokeOneArg(pref, "setValue", String.class, value);
            invokeOneArg(pref, "setVisible", boolean.class, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private static void updateDefaultHomePreferenceUi(Object pref, Context ctx) {
        try {
            boolean isDefault = isPocoDefaultHome(ctx);
            boolean pt = isPortugueseLocale();
            String title = pt ? "Tela inicial padrão" : "Default Home app";
            String value = isDefault ? "POCO Launcher" : (pt ? "Outro" : "Other");
            String summary = pt
                    ? (isDefault
                        ? "POCO Launcher está definido como padrão"
                        : "Toque para definir o POCO Launcher como padrão")
                    : (isDefault
                        ? "POCO Launcher is set as default"
                        : "Tap to set POCO Launcher as default");
            invokeOneArg(pref, "setTitle", CharSequence.class, title);
            invokeOneArg(pref, "setSummary", CharSequence.class, summary);
            invokeOneArg(pref, "setValue", String.class, value);
            invokeOneArg(pref, "setVisible", boolean.class, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private static void wireSystemNavigationPreference(Object fragment, final Context ctx) {
        Object navPref = readField(fragment, "mNavigationType");
        if (navPref == null) {
            return;
        }
        try {
            invokeOneArg(navPref, "setIntent", Intent.class, null);
            Class<?> listenerCls = Class.forName(
                    "androidx.preference.Preference$OnPreferenceClickListener");
            invokeOneArg(navPref, "setOnPreferenceClickListener", listenerCls,
                    createPreferenceClickProxy(listenerCls, "PocoCompatSystemNavClick", new Runnable() {
                        @Override
                        public void run() {
                            openSystemNavigationSettings(ctx);
                        }
                    }));
            invokeOneArg(navPref, "setValue", String.class, getSystemNavigationModeLabel(ctx));
            invokeOneArg(navPref, "setVisible", boolean.class, Boolean.TRUE);
            Object recentsCat = readField(fragment, "mRecentsConfigPrefCategory");
            if (recentsCat != null) {
                invokeOneArg(recentsCat, "setVisible", boolean.class, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updateDoubleTapLockPreferenceUi(Object fragment, Context ctx, boolean toggleOn) {
        Object pref = readField(fragment, "mDoubleTapLockPref");
        if (pref == null) {
            return;
        }
        try {
            boolean serviceOn = isLockAccessibilityEnabled(ctx);
            boolean pt = isPortugueseLocale();
            String summary;
            if (toggleOn && serviceOn) {
                summary = pt
                        ? "Ativo via Acessibilidade — preserva biometria/digital"
                        : "Active via Accessibility — preserves fingerprint unlock";
            } else if (toggleOn) {
                summary = pt
                        ? "Ative o serviço em Acessibilidade → Aplicativos instalados"
                        : "Enable the service in Accessibility → Installed apps";
            } else if (serviceOn) {
                summary = pt
                        ? "Serviço de Acessibilidade pronto — ative a chave para usar"
                        : "Accessibility service ready — switch on to use";
            } else {
                summary = pt
                        ? "Bloqueia a tela via Acessibilidade sem exigir PIN"
                        : "Locks screen via Accessibility while keeping biometrics";
            }
            invokeOneArg(pref, "setChecked", boolean.class, toggleOn);
            invokeOneArg(pref, "setSummary", CharSequence.class, summary);
            invokeOneArg(pref, "setVisible", boolean.class, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private static void updateIconCustomizePreferenceUi(Object pref) {
        try {
            boolean pt = isPortugueseLocale();
            String title = pt ? "Ícones" : "Icons";
            String summary = pt
                    ? "Tamanho do ícone e cores monocromáticas"
                    : "Icon size and monochrome themed colors";
            String value = "";
            try {
                Class<?> dcCls = Class.forName("com.miui.home.common.device.DeviceConfigs");
                Object scaleObj = dcCls.getMethod("getIconScale").invoke(null);
                if (scaleObj instanceof Float) {
                    int pct = Math.round(((Float) scaleObj) * 100f);
                    if (pct > 0) {
                        value = pct + "%";
                    }
                }
            } catch (Throwable ignored) {
            }
            invokeOneArg(pref, "setTitle", CharSequence.class, title);
            invokeOneArg(pref, "setSummary", CharSequence.class, summary);
            invokeOneArg(pref, "setValue", String.class, value);
            invokeOneArg(pref, "setVisible", boolean.class, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private static Object createPreferenceClickProxy(
            Class<?> listenerCls,
            final String label,
            final Runnable onClick
    ) {
        return Proxy.newProxyInstance(
                listenerCls.getClassLoader(),
                new Class<?>[]{listenerCls},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("onPreferenceClick".equals(name)) {
                            onClick.run();
                            return Boolean.TRUE;
                        }
                        if ("toString".equals(name)) {
                            return label;
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        }
                        return null;
                    }
                });
    }

    private static void hidePreference(Object pref) {
        if (pref == null) {
            return;
        }
        try {
            invokeOneArg(pref, "setVisible", boolean.class, Boolean.FALSE);
        } catch (Throwable ignored) {
        }
    }

    private static void updateNotificationAccessPreferenceUi(Object pref, Context ctx) {
        try {
            boolean enabled = isNotificationAccessEnabled(ctx);
            boolean pt = isPortugueseLocale();
            String title = pt ? "Emblemas de notificação" : "Notification badges";
            String value = pt ? (enabled ? "Ativado" : "Desativado") : (enabled ? "On" : "Off");
            String summary = pt
                    ? (enabled
                        ? "Contagem de notificações ativa nos ícones"
                        : "Toque para permitir Acesso às notificações")
                    : (enabled
                        ? "Unread badge counts active on icons"
                        : "Tap to grant Notification access");
            invokeOneArg(pref, "setTitle", CharSequence.class, title);
            invokeOneArg(pref, "setSummary", CharSequence.class, summary);
            invokeOneArg(pref, "setValue", String.class, value);
            invokeOneArg(pref, "setVisible", boolean.class, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private static Context resolvePreferenceContext(Object fragment) {
        try {
            Object pm = invokeZeroArg(fragment, "getPreferenceManager");
            if (pm != null) {
                Object ctx = invokeZeroArg(pm, "getContext");
                if (ctx instanceof Context) {
                    return (Context) ctx;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object ctx = invokeZeroArg(fragment, "getContext");
            if (ctx instanceof Context) {
                return (Context) ctx;
            }
        } catch (Throwable ignored) {
        }
        return getAppContext();
    }

    private static void invokeOneArg(Object target, String name, Class<?> paramType, Object arg)
            throws Exception {
        Method m = findMethodInHierarchy(target.getClass(), name, paramType);
        if (m != null) {
            m.invoke(target, arg);
        }
    }

    /**
     * Replacement for {@code MiuiHomeSettings.injectKeyEvent(int)} which called hidden
     * {@code InputManager.injectInputEvent(KEYCODE_HOME)} (requires {@code INJECT_EVENTS}).
     * Brings POCO Launcher to front and finishes Settings so "Home screen layout" preview opens.
     */
    public static void navigateToHomeForSettings(Object fragment, int keyCode) {
        if (fragment == null || keyCode != 3) {
            return;
        }
        try {
            Object actObj = invokeZeroArg(fragment, "getActivity");
            Context ctx = actObj instanceof Context ? (Context) actObj : resolvePreferenceContext(fragment);
            if (ctx != null) {
                Intent homeIntent = null;
                try {
                    Class<?> providerCls = Class.forName("com.miui.home.common.LauncherClassProvider");
                    Field f = providerCls.getDeclaredField("launcherClass");
                    f.setAccessible(true);
                    Object clsObj = f.get(null);
                    if (clsObj instanceof Class) {
                        homeIntent = new Intent(ctx, (Class<?>) clsObj);
                    }
                } catch (Throwable ignored) {
                }
                if (homeIntent == null) {
                    homeIntent = new Intent();
                    homeIntent.setClassName(ctx.getPackageName(), "com.miui.home.launcher.Launcher");
                }
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                ctx.startActivity(homeIntent);
            }
            if (actObj instanceof Activity) {
                ((Activity) actObj).finish();
            }
        } catch (Throwable t) {
            Log.w(TAG, "navigateToHomeForSettings failed: " + t.getMessage());
        }
    }

    /**
     * Replacement for {@code HideAppsLockUtils.setFingerComponent(String, String)}, which
     * hardcoded MIUI-only {@code MiuiSecurityChooseUnlock} / {@code NewFingerprintActivity}.
     */
    public static Intent createSecurityOrBiometricIntent(String pkg, String cls) {
        Context ctx = getAppContext();
        Intent direct = new Intent();
        if (pkg != null && cls != null) {
            direct.setComponent(new ComponentName(pkg, cls));
            if (ctx != null && canBeResolved(ctx, direct)) {
                return direct;
            }
        }
        Intent bio = new Intent(Settings.ACTION_BIOMETRIC_ENROLL);
        if (ctx != null && canBeResolved(ctx, bio)) {
            return bio;
        }
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }

    /**
     * Replacement for {@code InternationalGlobalSearch.startGlobalSearchByActivity} so swipe-down
     * or swipe-up global search opens Google Search / system web search when Xiaomi AppFinder is
     * absent.
     */
    public static void startInternationalGlobalSearchFallback(
            Activity launcher,
            View view,
            boolean fromSearchBox
    ) {
        startGlobalSearchFallback(
                launcher,
                fromSearchBox ? "launcher_searchbox" : "launcher",
                null,
                0,
                0,
                view);
    }

    /**
     * Replacement for {@code OpenSearchUtil.startGlobalSearchByActivity}, falling back to Google
     * Search / system web search when MIUI's {@code com.android.quicksearchbox} is absent.
     */
    public static void startGlobalSearchFallback(
            Activity launcher,
            String ref,
            String target,
            int x,
            int y,
            View view
    ) {
        if (launcher == null) {
            return;
        }
        PackageManager pm = launcher.getPackageManager();
        // 1. Original MIUI Search if present.
        try {
            Intent miui = new Intent(Intent.ACTION_SEARCH);
            miui.setData(Uri.parse("qsb://query?ref=" + ref));
            if (target != null && !target.isEmpty()) {
                miui.putExtra("search_bar_target", target);
            }
            miui.setPackage("com.android.quicksearchbox");
            miui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (miui.resolveActivity(pm) != null) {
                launcher.startActivity(miui);
                return;
            }
        } catch (Throwable ignored) {
        }

        // 2. Google App SearchActivity / global search.
        try {
            Intent google = new Intent(Intent.ACTION_WEB_SEARCH);
            google.setPackage("com.google.android.googlequicksearchbox");
            google.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (google.resolveActivity(pm) != null) {
                launcher.startActivity(google);
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            Intent googleAct = new Intent();
            googleAct.setComponent(new ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.googlequicksearchbox.SearchActivity"));
            googleAct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (googleAct.resolveActivity(pm) != null) {
                launcher.startActivity(googleAct);
                return;
            }
        } catch (Throwable ignored) {
        }

        // 3. Any installed search handler.
        try {
            Intent web = new Intent(Intent.ACTION_WEB_SEARCH);
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (web.resolveActivity(pm) != null) {
                launcher.startActivity(web);
                return;
            }
            launcher.onSearchRequested();
        } catch (Throwable t) {
            Log.w(TAG, "startGlobalSearchFallback failed: " + t.getMessage());
        }
    }

    private static Object invokeZeroArg(Object target, String methodName) throws Exception {
        Method m = findMethodInHierarchy(target.getClass(), methodName);
        if (m == null) {
            return null;
        }
        return m.invoke(target);
    }

    private static Object readField(Object target, String fieldName) {
        Class<?> cur = target.getClass();
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void writeField(Object target, String fieldName, Object value) {
        Class<?> cur = target.getClass();
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    // ==================== Hardware / Memory Info (/proc/meminfo) ====================

    public static long readMemInfoBytes(String key, long fallbackBytes) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(key)) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) * 1024L;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallbackBytes;
    }

    public static int readTotalRamGb() {
        int cached = sCachedTotalRamGb;
        if (cached > 0) {
            return cached;
        }
        long bytes = readMemInfoBytes("MemTotal:", 4L * 1024L * 1024L * 1024L);
        int gb = Math.max(2, (int) Math.round(bytes / (1024.0 * 1024.0 * 1024.0)));
        sCachedTotalRamGb = gb;
        return gb;
    }

    // ==================== Non-Xiaomi Default Home Screen & Dock ====================

    private static final int CONTAINER_DESKTOP = -100;
    private static final int CONTAINER_HOTSEAT = -101;
    private static final int ITEM_TYPE_APPLICATION = 0;
    private static final int ITEM_TYPE_FOLDER = 2;
    private static final int ITEM_TYPE_GADGET = 5;
    private static final int GADGET_ID_GOOGLE_SEARCH = 13;

    private static final String[] GOOGLE_FOLDER_PACKAGES = {
            "com.google.android.googlequicksearchbox",
            "com.android.chrome",
            "com.google.android.gm",
            "com.google.android.apps.maps",
            "com.google.android.youtube",
            "com.google.android.apps.docs",
            "com.google.android.apps.photos",
            "com.google.android.apps.tachyon",
            "com.google.android.apps.meetings",
            "com.google.android.calendar",
            "com.google.android.apps.youtube.music",
            "com.google.android.keep",
            "com.google.android.apps.nbu.files",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.videos",
            "com.google.android.play.games",
    };

    private static final String[] POPULAR_FALLBACK_PACKAGES = {
            "com.whatsapp",
            "com.instagram.android",
            "com.spotify.music",
            "org.telegram.messenger",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.facebook.katana",
            "com.netflix.mediaclient",
            "com.samsung.android.app.notes",
            "com.samsung.android.app.contacts",
            "com.google.android.contacts",
            "com.google.android.youtube",
            "com.google.android.gm",
            "com.google.android.apps.maps",
    };

    /**
     * Populates a clean POCO-style default home screen and dock on non-Xiaomi devices when the
     * built-in MIUI {@code default_workspace.xml} resolved fewer than 4 real apps.
     */
    public static void populateNonXiaomiDefaultWorkspace(
            Context context,
            SQLiteDatabase db,
            Object launcherMode,
            Collection<?> allApps
    ) {
        if (context == null || db == null || allApps == null || allApps.isEmpty()) {
            return;
        }
        try {
            if (launcherMode != null) {
                Object elderly = invokeZeroArg(launcherMode, "isElderlyManMode");
                if (Boolean.TRUE.equals(elderly)) {
                    return;
                }
            }
            if (countInstalledAppsInFavorites(db) >= 4) {
                return;
            }

            UserHandle myUser = Process.myUserHandle();
            String selfPkg = context.getPackageName();
            Map<String, LauncherActivityInfo> byPackage = new LinkedHashMap<>();
            List<LauncherActivityInfo> allLaunchable = new ArrayList<>();
            for (Object obj : allApps) {
                if (!(obj instanceof LauncherActivityInfo)) {
                    continue;
                }
                LauncherActivityInfo info = (LauncherActivityInfo) obj;
                ComponentName cn = info.getComponentName();
                if (cn == null || selfPkg.equals(cn.getPackageName())) {
                    continue;
                }
                if (info.getUser() != null && !myUser.equals(info.getUser())) {
                    continue;
                }
                byPackage.putIfAbsent(cn.getPackageName(), info);
                allLaunchable.add(info);
            }
            if (byPackage.isEmpty()) {
                return;
            }

            int cellCountX = Math.max(4, readDeviceConfigInt("getCellCountX", 4));
            int cellCountY = Math.max(5, readDeviceConfigInt("getCellCountY", 5));
            long profileId = 0L;
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um != null) {
                profileId = um.getSerialNumberForUser(myUser);
            }

            PackageManager pm = context.getPackageManager();
            Set<String> usedPackages = new HashSet<>();

            // 1. Resolve 4-slot POCO Dock (Phone, Messages, Browser, Camera).
            List<LauncherActivityInfo> dockApps = new ArrayList<>(4);
            addResolvedApp(dockApps, usedPackages, resolveRoleApp(pm, byPackage, usedPackages,
                    new Intent(Intent.ACTION_DIAL),
                    "com.samsung.android.dialer", "com.google.android.dialer",
                    "com.android.dialer", "com.android.contacts"));
            addResolvedApp(dockApps, usedPackages, resolveRoleApp(pm, byPackage, usedPackages,
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                    new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                    "com.google.android.apps.messaging", "com.samsung.android.messaging",
                    "com.android.mms"));
            addResolvedApp(dockApps, usedPackages, resolveRoleApp(pm, byPackage, usedPackages,
                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                            .addCategory(Intent.CATEGORY_BROWSABLE),
                    "com.android.chrome", "com.sec.android.app.sbrowser",
                    "org.mozilla.firefox", "com.microsoft.emmx", "com.brave.browser",
                    "com.opera.browser"));
            addResolvedApp(dockApps, usedPackages, resolveRoleApp(pm, byPackage, usedPackages,
                    new Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                    "com.sec.android.app.camera", "com.google.android.GoogleCamera",
                    "com.motorola.camera3", "com.motorola.camera2", "com.oneplus.camera",
                    "com.android.camera2", "com.android.camera"));

            // 2. Reserve Screen 0 primary apps before building the Google folder to avoid duplicates.
            LauncherActivityInfo playStore = pickUnusedPackage(byPackage, usedPackages, "com.android.vending");
            LauncherActivityInfo gallery = resolveRoleApp(pm, byPackage, usedPackages,
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
                    "com.sec.android.gallery3d", "com.google.android.apps.photos",
                    "com.oneplus.gallery", "com.motorola.MotGallery2", "com.android.gallery3d");
            markUsed(usedPackages, gallery);
            LauncherActivityInfo settings = pickUnusedPackage(byPackage, usedPackages, "com.android.settings");

            LauncherActivityInfo clock = pickUnusedPackage(byPackage, usedPackages,
                    "com.sec.android.app.clockpackage", "com.google.android.deskclock",
                    "com.android.deskclock", "com.oneplus.deskclock");
            LauncherActivityInfo calendar = resolveRoleApp(pm, byPackage, usedPackages,
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
                    "com.samsung.android.calendar", "com.google.android.calendar",
                    "com.android.calendar");
            markUsed(usedPackages, calendar);
            LauncherActivityInfo calculator = resolveRoleApp(pm, byPackage, usedPackages,
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR),
                    "com.sec.android.app.popupcalculator", "com.google.android.calculator",
                    "com.oneplus.calculator", "com.android.calculator2");
            markUsed(usedPackages, calculator);
            LauncherActivityInfo filesOrContacts = pickUnusedPackage(byPackage, usedPackages,
                    "com.sec.android.app.myfiles", "com.google.android.apps.nbu.files",
                    "com.google.android.documentsui", "com.android.documentsui",
                    "com.samsung.android.app.contacts", "com.google.android.contacts",
                    "com.android.contacts");

            // 3. Collect remaining Google apps for the Google folder.
            List<LauncherActivityInfo> googleFolderApps = new ArrayList<>();
            for (String pkg : GOOGLE_FOLDER_PACKAGES) {
                LauncherActivityInfo info = byPackage.get(pkg);
                if (info != null && !usedPackages.contains(pkg)) {
                    googleFolderApps.add(info);
                    usedPackages.add(pkg);
                }
            }

            // Fill dock up to 4 icons if any role (e.g. Dialer on tablet) was missing.
            int dockTarget = Math.min(cellCountX, 4);
            while (dockApps.size() < dockTarget) {
                LauncherActivityInfo extra = pickFallbackApp(byPackage, allLaunchable, usedPackages);
                if (extra == null) {
                    break;
                }
                addResolvedApp(dockApps, usedPackages, extra);
            }

            // Build Screen 0 bottom rows cleanly (row1 = cellCountY - 2, row2 = cellCountY - 1).
            int row1 = Math.max(1, cellCountY - 2);
            int row2 = Math.max(row1 + 1, cellCountY - 1);

            List<LauncherActivityInfo> row1Apps = new ArrayList<>();
            if (playStore != null) row1Apps.add(playStore);
            if (gallery != null) row1Apps.add(gallery);
            if (settings != null) row1Apps.add(settings);
            int row1IconSlots = googleFolderApps.size() >= 2 ? cellCountX - 1 : cellCountX;
            if (googleFolderApps.size() == 1) {
                row1Apps.add(0, googleFolderApps.get(0));
            }
            while (row1Apps.size() < row1IconSlots) {
                LauncherActivityInfo extra = pickFallbackApp(byPackage, allLaunchable, usedPackages);
                if (extra == null) {
                    break;
                }
                markUsed(usedPackages, extra);
                row1Apps.add(extra);
            }

            List<LauncherActivityInfo> row2Apps = new ArrayList<>();
            if (clock != null) row2Apps.add(clock);
            if (calendar != null) row2Apps.add(calendar);
            if (calculator != null) row2Apps.add(calculator);
            if (filesOrContacts != null) row2Apps.add(filesOrContacts);
            while (row2Apps.size() < cellCountX) {
                LauncherActivityInfo extra = pickFallbackApp(byPackage, allLaunchable, usedPackages);
                if (extra == null) {
                    break;
                }
                markUsed(usedPackages, extra);
                row2Apps.add(extra);
            }

            long nextId = 1L;
            int inserted = 0;
            db.beginTransaction();
            try {
                db.delete("favorites", null, null);

                // Top Google Search bar gadget (native POCO view, zero permissions needed).
                Intent googleSearchCheck = new Intent().setComponent(new ComponentName(
                        "com.google.android.googlequicksearchbox",
                        "com.google.android.googlequicksearchbox.SearchActivity"));
                if (canBeResolved(context, googleSearchCheck)) {
                    ContentValues searchCv = new ContentValues();
                    searchCv.put("_id", nextId++);
                    searchCv.put("profileId", profileId);
                    searchCv.put("container", CONTAINER_DESKTOP);
                    searchCv.put("screen", 0);
                    searchCv.put("cellX", 0);
                    searchCv.put("cellY", 0);
                    searchCv.put("spanX", cellCountX);
                    searchCv.put("spanY", 1);
                    searchCv.put("itemType", ITEM_TYPE_GADGET);
                    searchCv.put("appWidgetId", GADGET_ID_GOOGLE_SEARCH);
                    db.insert("favorites", null, searchCv);
                    inserted++;
                }

                // Dock (hotseat) icons.
                for (int i = 0; i < dockApps.size(); i++) {
                    insertWorkspaceApp(db, nextId++, profileId, CONTAINER_HOTSEAT, null, i, 0, dockApps.get(i));
                    inserted++;
                }

                // Row 1: optional Google folder at col 0 + primary apps.
                int col = 0;
                if (googleFolderApps.size() >= 2) {
                    long folderId = nextId++;
                    ContentValues folderCv = new ContentValues();
                    folderCv.put("_id", folderId);
                    folderCv.put("profileId", profileId);
                    folderCv.put("container", CONTAINER_DESKTOP);
                    folderCv.put("screen", 0);
                    folderCv.put("cellX", col++);
                    folderCv.put("cellY", row1);
                    folderCv.put("spanX", 1);
                    folderCv.put("spanY", 1);
                    folderCv.put("itemType", ITEM_TYPE_FOLDER);
                    folderCv.put("title", "Google");
                    folderCv.put("label", "Google");
                    db.insert("favorites", null, folderCv);
                    inserted++;

                    for (int i = 0; i < googleFolderApps.size(); i++) {
                        insertWorkspaceApp(db, nextId++, profileId, folderId, -1, i, 0, googleFolderApps.get(i));
                        inserted++;
                    }
                }
                for (LauncherActivityInfo info : row1Apps) {
                    if (col >= cellCountX) break;
                    insertWorkspaceApp(db, nextId++, profileId, CONTAINER_DESKTOP, 0, col++, row1, info);
                    inserted++;
                }

                // Row 2: everyday essentials.
                for (int i = 0; i < row2Apps.size() && i < cellCountX; i++) {
                    insertWorkspaceApp(db, nextId++, profileId, CONTAINER_DESKTOP, 0, i, row2, row2Apps.get(i));
                    inserted++;
                }

                db.setTransactionSuccessful();
                Log.i(TAG, "Populated non-Xiaomi default workspace (" + inserted + " items, grid "
                        + cellCountX + "x" + cellCountY + ")");
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            Log.w(TAG, "populateNonXiaomiDefaultWorkspace failed: " + t.getMessage(), t);
        }
    }

    private static int countInstalledAppsInFavorites(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM favorites WHERE itemType=?",
                new String[]{String.valueOf(ITEM_TYPE_APPLICATION)})) {
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int readDeviceConfigInt(String methodName, int fallback) {
        try {
            Class<?> cls = Class.forName("com.miui.home.common.device.DeviceConfigs");
            Object res = cls.getMethod(methodName).invoke(null);
            if (res instanceof Integer) {
                return (Integer) res;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static void insertWorkspaceApp(
            SQLiteDatabase db,
            long id,
            long profileId,
            long container,
            Integer screen,
            int cellX,
            int cellY,
            LauncherActivityInfo info
    ) {
        ComponentName cn = info.getComponentName();
        Intent launchIntent = new Intent(Intent.ACTION_MAIN, null);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launchIntent.setComponent(cn);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        ContentValues cv = new ContentValues();
        cv.put("_id", id);
        cv.put("profileId", profileId);
        cv.put("container", container);
        if (screen != null) {
            cv.put("screen", screen);
        }
        cv.put("cellX", cellX);
        cv.put("cellY", cellY);
        cv.put("spanX", 1);
        cv.put("spanY", 1);
        cv.put("itemType", ITEM_TYPE_APPLICATION);
        cv.put("iconPackage", cn.getPackageName());
        cv.put("intent", launchIntent.toUri(0));
        db.insert("favorites", null, cv);
    }

    private static LauncherActivityInfo resolveRoleApp(
            PackageManager pm,
            Map<String, LauncherActivityInfo> byPackage,
            Set<String> usedPackages,
            Intent primaryIntent,
            String... fallbackPackages
    ) {
        return resolveRoleApp(pm, byPackage, usedPackages, primaryIntent, null, fallbackPackages);
    }

    private static LauncherActivityInfo resolveRoleApp(
            PackageManager pm,
            Map<String, LauncherActivityInfo> byPackage,
            Set<String> usedPackages,
            Intent primaryIntent,
            Intent secondaryIntent,
            String... fallbackPackages
    ) {
        LauncherActivityInfo fromPrimary = resolveViaIntent(pm, byPackage, usedPackages, primaryIntent);
        if (fromPrimary != null) {
            return fromPrimary;
        }
        LauncherActivityInfo fromSecondary = resolveViaIntent(pm, byPackage, usedPackages, secondaryIntent);
        if (fromSecondary != null) {
            return fromSecondary;
        }
        for (String pkg : fallbackPackages) {
            LauncherActivityInfo info = byPackage.get(pkg);
            if (info != null && !usedPackages.contains(pkg)) {
                return info;
            }
        }
        return null;
    }

    private static LauncherActivityInfo resolveViaIntent(
            PackageManager pm,
            Map<String, LauncherActivityInfo> byPackage,
            Set<String> usedPackages,
            Intent intent
    ) {
        if (pm == null || intent == null) {
            return null;
        }
        try {
            ResolveInfo def = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (def != null && def.activityInfo != null) {
                String pkg = def.activityInfo.packageName;
                if (pkg != null && !"android".equals(pkg) && !usedPackages.contains(pkg)) {
                    LauncherActivityInfo info = byPackage.get(pkg);
                    if (info != null) {
                        return info;
                    }
                }
            }
            List<ResolveInfo> candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (candidates != null) {
                for (ResolveInfo ri : candidates) {
                    if (ri != null && ri.activityInfo != null) {
                        String pkg = ri.activityInfo.packageName;
                        if (pkg != null && !"android".equals(pkg) && !usedPackages.contains(pkg)) {
                            LauncherActivityInfo info = byPackage.get(pkg);
                            if (info != null) {
                                return info;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static LauncherActivityInfo pickUnusedPackage(
            Map<String, LauncherActivityInfo> byPackage,
            Set<String> usedPackages,
            String... packages
    ) {
        for (String pkg : packages) {
            LauncherActivityInfo info = byPackage.get(pkg);
            if (info != null && !usedPackages.contains(pkg)) {
                usedPackages.add(pkg);
                return info;
            }
        }
        return null;
    }

    private static LauncherActivityInfo pickFallbackApp(
            Map<String, LauncherActivityInfo> byPackage,
            List<LauncherActivityInfo> allLaunchable,
            Set<String> usedPackages
    ) {
        for (String pkg : POPULAR_FALLBACK_PACKAGES) {
            LauncherActivityInfo info = byPackage.get(pkg);
            if (info != null && !usedPackages.contains(pkg)) {
                return info;
            }
        }
        for (LauncherActivityInfo info : allLaunchable) {
            ComponentName cn = info.getComponentName();
            if (cn != null && !usedPackages.contains(cn.getPackageName())) {
                return info;
            }
        }
        return null;
    }

    private static void addResolvedApp(
            List<LauncherActivityInfo> list,
            Set<String> usedPackages,
            LauncherActivityInfo info
    ) {
        if (info != null && info.getComponentName() != null) {
            list.add(info);
            usedPackages.add(info.getComponentName().getPackageName());
        }
    }

    private static void markUsed(Set<String> usedPackages, LauncherActivityInfo info) {
        if (info != null && info.getComponentName() != null) {
            usedPackages.add(info.getComponentName().getPackageName());
        }
    }
}
