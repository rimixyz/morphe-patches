package app.rimixyz.extension.pocolauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;

/**
 * Minimal zero-capability {@link AccessibilityService} used exclusively for rootless
 * double-tap-to-lock ({@link #GLOBAL_ACTION_LOCK_SCREEN}, which preserves fingerprint and
 * biometric unlock on Android 9+) and as a public-SDK fallback for swipe-down notification
 * shade expansion ({@link #GLOBAL_ACTION_NOTIFICATIONS}).
 *
 * <p>Requests zero event types and cannot retrieve window content, so it incurs zero background
 * overhead and never triggers TalkBack detection in POCO Launcher.
 */
@SuppressWarnings("unused")
public class PocoLockScreenService extends AccessibilityService {
    private static final String TAG = "PocoCompat";
    private static volatile WeakReference<PocoLockScreenService> sInstance = new WeakReference<>(null);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = new WeakReference<>(this);
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                info = new AccessibilityServiceInfo();
            }
            info.eventTypes = 0;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags = 0;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        } catch (Throwable t) {
            Log.w(TAG, "PocoLockScreenService setServiceInfo skipped: " + t.getMessage());
        }
        Log.i(TAG, "PocoLockScreenService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally empty: eventTypes == 0, no screen observation performed.
    }

    @Override
    public void onInterrupt() {
        // No-op.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (sInstance.get() == this) {
            sInstance = new WeakReference<>(null);
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (sInstance.get() == this) {
            sInstance = new WeakReference<>(null);
        }
        super.onDestroy();
    }

    public static boolean isConnected() {
        return sInstance.get() != null;
    }

    public static boolean performLockScreen() {
        PocoLockScreenService service = sInstance.get();
        if (service == null) {
            return false;
        }
        try {
            return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        } catch (Throwable t) {
            Log.w(TAG, "GLOBAL_ACTION_LOCK_SCREEN failed: " + t.getMessage());
            return false;
        }
    }

    public static boolean performExpandNotifications() {
        PocoLockScreenService service = sInstance.get();
        if (service == null) {
            return false;
        }
        try {
            return service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        } catch (Throwable t) {
            Log.w(TAG, "GLOBAL_ACTION_NOTIFICATIONS failed: " + t.getMessage());
            return false;
        }
    }
}
