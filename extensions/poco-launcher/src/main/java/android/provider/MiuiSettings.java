package android.provider;

import android.content.ContentResolver;
import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.rimixyz.extension.pocolauncher.PocoSettingsCompat;

@SuppressWarnings("unused")
public class MiuiSettings {
    public static class ForceTouch {
        public static boolean isEnabled(Context context) {
            return false;
        }
    }

    public static class Global {
        public static boolean getBoolean(ContentResolver cr, String name) {
            return PocoSettingsCompat.globalGetBoolean(cr, name);
        }

        public static boolean putBoolean(ContentResolver cr, String name, boolean value) {
            return PocoSettingsCompat.globalPutBoolean(cr, name, value);
        }
    }

    public static class SettingsCloudData {
        public static class CloudData {
            public JSONObject json() {
                return null;
            }
        }

        public static boolean getCloudDataBoolean(
                ContentResolver cr, String moduleName, String key, boolean defValue) {
            return defValue;
        }

        public static List<CloudData> getCloudDataList(ContentResolver cr, String moduleName) {
            return Collections.emptyList();
        }

        public static long getCloudDataLong(
                ContentResolver cr, String moduleName, String key, long defValue) {
            return defValue;
        }

        public static String getCloudDataString(
                ContentResolver cr, String moduleName, String key, String defValue) {
            return defValue;
        }
    }

    public static class System {
        public static boolean getBoolean(ContentResolver cr, String name, boolean def) {
            return PocoSettingsCompat.systemGetBoolean(cr, name, def);
        }

        public static boolean getBooleanForUser(
                ContentResolver cr, String name, boolean def, int userHandle) {
            return PocoSettingsCompat.systemGetBoolean(cr, name, def);
        }

        public static ArrayList<Integer> getScreenKeyOrder(Context context) {
            ArrayList<Integer> order = new ArrayList<>(3);
            order.add(0);
            order.add(1);
            order.add(2);
            return order;
        }

        public static String getString(ContentResolver cr, String name) {
            return PocoSettingsCompat.systemGetString(cr, name);
        }

        public static String getStringForUser(ContentResolver cr, String name, int userHandle) {
            return PocoSettingsCompat.systemGetString(cr, name);
        }

        public static boolean putBoolean(ContentResolver cr, String name, boolean value) {
            return PocoSettingsCompat.systemPutBoolean(cr, name, value);
        }

        public static boolean putBooleanForUser(
                ContentResolver cr, String name, boolean value, int userHandle) {
            return PocoSettingsCompat.systemPutBoolean(cr, name, value);
        }

        public static boolean putString(ContentResolver cr, String name, String value) {
            return PocoSettingsCompat.systemPutString(cr, name, value);
        }
    }
}
