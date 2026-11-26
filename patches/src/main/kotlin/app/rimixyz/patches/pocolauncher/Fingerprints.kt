package app.rimixyz.patches.pocolauncher

import app.morphe.patcher.Fingerprint

// ==================== Application / Startup ====================

internal val MainApplicationAttachBaseContextFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/MainApplication;",
    name = "attachBaseContext",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal val ApplicationHasBroughtToForegroundFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/Application;",
    name = "hasBroughtToForeground",
    returnType = "Z",
    parameters = emptyList(),
)

internal val FallbackHomeNeedKillSelfFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/FallbackHomeCompat;",
    name = "needKillSelf",
    returnType = "Z",
    parameters = emptyList(),
)

internal val DeviceConfigLoadThemeResourceFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/DeviceConfig;",
    name = "loadThemeResource",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    isStatic = true,
)

internal val BaseLauncherRequestPowerKeeperFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/BaseLauncher;",
    name = "requestPowerKeeperPermissionIfNeed",
    returnType = "V",
    parameters = emptyList(),
)

internal val BaseRecentsCreateNavStubFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/recents/BaseRecentsImpl;",
    name = "createAndAddNavStubView",
    returnType = "V",
    parameters = emptyList(),
)

internal val BaseRecentsAddBackStubFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/recents/BaseRecentsImpl;",
    name = "addBackStubWindow",
    returnType = "V",
    parameters = emptyList(),
)

internal val KeyguardOccludedRegisterFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/KeyguardOccludedChangedListener;",
    name = "registerOccludedChangedListener",
    returnType = "V",
    parameters = listOf("Landroid/app/IOccludedChangedListener;"),
)

internal val HighContrastRegisterFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/HighContrastTextStateChangeHelper;",
    name = "registerHighContrastTextStateChangeListener",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

internal val HighContrastUnregisterFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/HighContrastTextStateChangeHelper;",
    name = "unRegisterHighContrastTextStateChangeListener",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

// ==================== Android 14/15/16 Hidden-API & BootClassLoader Fixes ====================

internal val StatusBarHandleMessageFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/StatusBarController\$H;",
    name = "handleMessage",
    returnType = "V",
    parameters = listOf("Landroid/os/Message;"),
)

internal val BitmapRendererCreateAccelFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/BitmapRenderer;",
    name = "createHardwareBitmapWithAcceleratedCanvas",
    returnType = "Landroid/graphics/Bitmap;",
    parameters = listOf(
        "I",
        "I",
        "Landroid/graphics/Outline;",
        "Lcom/miui/launcher/utils/BitmapRenderer\$Renderer;",
    ),
    isStatic = true,
)

internal val ActivityManagerWrapperTrimMemoryFingerprint = Fingerprint(
    definingClass = "Lcom/android/systemui/shared/recents/system/ActivityManagerWrapper;",
    name = "trimMemory",
    returnType = "V",
    parameters = listOf("I"),
)

internal val ActivityManagerWrapperGetComponentNameFingerprint = Fingerprint(
    definingClass = "Lcom/android/systemui/shared/recents/system/ActivityManagerWrapper;",
    name = "getComponentName",
    returnType = "Landroid/content/ComponentName;",
    parameters = listOf("Landroid/content/pm/ActivityInfo;"),
)

internal val AppOpsManagerNoteOpFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/AppOpsManagerUtils;",
    name = "noteOpNoThrow",
    returnType = "I",
    parameters = listOf("Landroid/content/Context;", "I", "I", "Ljava/lang/String;"),
    isStatic = true,
)

internal val ContentProviderUpdateDataFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/ContentProviderUtils;",
    name = "updateData",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/net/Uri;", "Landroid/content/ContentValues;"),
    isStatic = true,
)

internal val ContentProviderIsProviderExistsFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/ContentProviderUtils;",
    name = "isProviderExists",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Landroid/net/Uri;"),
    isStatic = true,
)

internal val ActivityUtilsCompatMakeCustomAnimFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/ActivityUtilsCompat;",
    name = "makeCustomAnimation",
    returnType = "Landroid/app/ActivityOptions;",
    parameters = listOf("Landroid/content/Context;", "I", "I", "Landroid/os/Handler;"),
    isStatic = true,
)

internal val DisplayManagerGlobalGetRotationFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/DisplayManagerGlobalUtils;",
    name = "getRotation",
    returnType = "I",
    parameters = emptyList(),
    isStatic = true,
)

internal val MiuiWindowManagerSetExtraFlagFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/MiuiWindowManagerUtils;",
    name = "setExtraFlag",
    returnType = "V",
    parameters = listOf("Landroid/view/Window;", "I", "I"),
    isStatic = true,
)

internal val MiuiWindowManagerAddExtraFlagFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/MiuiWindowManagerUtils;",
    name = "addExtraFlag",
    returnType = "V",
    parameters = listOf("Landroid/view/WindowManager\$LayoutParams;", "I"),
    isStatic = true,
)

internal val MiuiWindowManagerGetInitialDisplaySizeFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/MiuiWindowManagerUtils;",
    name = "getInitialDisplaySize",
    returnType = "Landroid/graphics/Point;",
    parameters = emptyList(),
    isStatic = true,
)

internal val InternalResourceClinitFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/common/InternalResource;",
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
)

internal val ServiceDeliveryIntentUtilsCanBeResolvedFingerprint = Fingerprint(
    definingClass = "Lcom/miui/miuiwidget/servicedelivery/utils/IntentUtils;",
    name = "canBeResolved",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;", "Z"),
    isStatic = true,
)

internal val BoostHelperGetRenderThreadIdFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/BoostHelper;",
    name = "getRenderThreadId",
    returnType = "I",
    parameters = listOf("Landroid/view/View;"),
)

internal val BlurUtilsSetBlurFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/BlurUtils;",
    name = "setBlur",
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;", "F", "I", "Ljava/util/function/Consumer;"),
    isStatic = true,
)

// ==================== LauncherUtils / PortableUtils / SystemProperties / Recents Wrappers ====================

internal val LauncherUtilsGetSenderFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "getSender",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Intent;"),
    isStatic = true,
)

internal val LauncherUtilsGetNaturalWidthFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "getNaturalWidth",
    returnType = "I",
    parameters = listOf("Landroid/content/Context;"),
    isStatic = true,
)

internal val LauncherUtilsStartActivityAsUserFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "startActivityAsUser",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;", "Landroid/os/Bundle;", "Landroid/os/UserHandle;"),
    isStatic = true,
)

internal val LauncherUtilsRegisterReceiverAsUser6Fingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "registerReceiverAsUser",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/content/BroadcastReceiver;",
        "Landroid/os/UserHandle;",
        "Landroid/content/IntentFilter;",
        "Ljava/lang/String;",
        "Landroid/os/Handler;",
    ),
    isStatic = true,
)

internal val LauncherUtilsRegisterReceiverAsUser7Fingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "registerReceiverAsUser",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/content/BroadcastReceiver;",
        "Landroid/os/UserHandle;",
        "Landroid/content/IntentFilter;",
        "Ljava/lang/String;",
        "Landroid/os/Handler;",
        "I",
    ),
    isStatic = true,
)

internal val LauncherUtilsGetSharedPrefsFileFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "getSharedPrefsFile",
    returnType = "Ljava/io/File;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    isStatic = true,
)

internal val LauncherUtilsIsPackageDisableAsUserFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "isPackageDisableAsUser",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "I"),
    isStatic = true,
)

internal val LauncherUtilsHasRelativeXSpaceAppFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "hasRelativeXSpaceApp",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    isStatic = true,
)

internal val LauncherUtilsSetProcessForegroundFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "setProcessForeground",
    returnType = "V",
    parameters = listOf("Landroid/os/IBinder;"),
    isStatic = true,
)

internal val LauncherUtilsSetAsyncTaskExecutorFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "setAsyncTaskDefaultExecutor",
    returnType = "V",
    parameters = listOf("Ljava/util/concurrent/Executor;"),
    isStatic = true,
)

internal val LauncherUtilsGetAllUserHandleFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "getAllUserHandle",
    returnType = "Landroid/os/UserHandle;",
    parameters = emptyList(),
    isStatic = true,
)

internal val LauncherUtilsGetCurrentUserHandleFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "getCurrentUserHandle",
    returnType = "Landroid/os/UserHandle;",
    parameters = emptyList(),
    isStatic = true,
)

internal val LauncherUtilsGetAppLaunchCountFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "getAppLaunchCount",
    returnType = "I",
    parameters = listOf("Landroid/app/usage/UsageStats;"),
    isStatic = true,
)

internal val LauncherUtilsDeletePackageFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "deletePackage",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Lcom/miui/launcher/common/PackageDeleteObserverDelegate;",
    ),
    isStatic = true,
)

internal val LauncherUtilsDeletePackageAsUserFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/LauncherUtils;",
    name = "deletePackageAsUser",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Lcom/miui/launcher/common/PackageDeleteObserverDelegate;",
        "I",
        "I",
    ),
    isStatic = true,
)

internal val PortableUtilsQueryIntentActivitiesAsUserFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/PortableUtils;",
    name = "queryIntentActivitiesAsUser",
    returnType = "Ljava/util/List;",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;", "I", "I"),
    isStatic = true,
)

internal val PortableUtilsGetCurrentWallpaperInfoFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/PortableUtils;",
    name = "getCurrentWallpaperInfo",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    isStatic = true,
)

internal val PortableUtilsUpdateCurrentWallpaperInfoFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/PortableUtils;",
    name = "updateCurrentWallpaperInfo",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"),
    isStatic = true,
)

internal val PortableUtilsMoveFilesFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/PortableUtils;",
    name = "moveFiles",
    returnType = "I",
    parameters = listOf("Ljava/io/File;", "Ljava/io/File;", "Ljava/lang/String;"),
    isStatic = true,
)

internal val TaskStackRegisterListenerFingerprint = Fingerprint(
    definingClass = "Lcom/android/systemui/shared/recents/system/TaskStackChangeListeners;",
    name = "registerTaskStackListener",
    returnType = "V",
    parameters = listOf("Lcom/android/systemui/shared/recents/system/TaskStackChangeListener;"),
)

internal val SystemPropertiesGet1Fingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/SystemProperties;",
    name = "get",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;"),
    isStatic = true,
)

internal val SystemPropertiesGet2Fingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/SystemProperties;",
    name = "get",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    isStatic = true,
)

internal val SystemPropertiesGetIntFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/SystemProperties;",
    name = "getInt",
    returnType = "I",
    parameters = listOf("Ljava/lang/String;", "I"),
    isStatic = true,
)

internal val SystemPropertiesGetBooleanFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/SystemProperties;",
    name = "getBoolean",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "Z"),
    isStatic = true,
)

internal val SystemPropertiesGetBooleanNoCheckFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/SystemProperties;",
    name = "getBooleanWithoutLengthCheck",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "Z"),
    isStatic = true,
)

internal val SystemPropertiesSetFingerprint = Fingerprint(
    definingClass = "Lcom/miui/launcher/utils/SystemProperties;",
    name = "set",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    isStatic = true,
)

// ==================== Market SDK 2-arg registerReceiver Android 14+ Crash ====================

internal val EnableStateManagerRegisterReceiverFingerprint = Fingerprint(
    definingClass = "Lcom/market/sdk/EnableStateManager;",
    name = "registerReceiverIfNeeded",
    returnType = "V",
    parameters = emptyList(),
)

internal val UpdateResultReceiverRegisterFingerprint = Fingerprint(
    definingClass = "Lcom/market/sdk/silentupdate/UpdateResultReceiver;",
    name = "register",
    returnType = "V",
    parameters = listOf("Lcom/market/sdk/silentupdate/UpdateResultReceiver\$Callback;"),
)

// ==================== Samsung / Non-Xiaomi Native UX (Widgets, Wallpaper) ====================

internal val MiuiWidgetUtilIsSupportFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/MIUIWidgetUtil;",
    name = "isMIUIWidgetSupport",
    returnType = "Z",
    parameters = emptyList(),
    isStatic = true,
)

internal val BaseLauncherAddAppWidgetFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/BaseLauncher;",
    name = "addAppWidgetWithoutFindPlaceAgain",
    returnType = "I",
    parameters = listOf(
        "Lcom/miui/home/launcher/LauncherAppWidgetInfo;",
        "Lcom/miui/home/launcher/CellScreen;",
    ),
)

internal val BaseLauncherOnActivityResultFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/BaseLauncher;",
    name = "onActivityResult",
    returnType = "V",
    parameters = listOf("I", "I", "Landroid/content/Intent;"),
)

internal val DesktopWallpaperManagerInitFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/wallpaper/DesktopWallpaperManager;",
    name = "<init>",
    returnType = "V",
    parameters = listOf("Lcom/miui/home/launcher/BaseLauncher;"),
)

internal val WallpaperManagerCompatVTIsMiuiUsingFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/wallpaper/WallpaperManagerCompatVT;",
    name = "isMiuiWallpaperComponentUsing",
    returnType = "Z",
    parameters = emptyList(),
)

internal val EditingEntryJumpThemeWallpaperFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/EditingEntryThumbnailView;",
    name = "jumpThemeWallpaper",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
)

internal val WallpaperShortcutOnCreateAroundFingerprint = Fingerprint(
    definingClass = "Lcom/miui/home/launcher/shortcuts/WallpaperShortcutActivity;",
    name = "onCreate_aroundBody0",
    returnType = "V",
    parameters = listOf(
        "Lcom/miui/home/launcher/shortcuts/WallpaperShortcutActivity;",
        "Landroid/os/Bundle;",
        "Lorg/aspectj/lang/JoinPoint;",
    ),
    isStatic = true,
)
