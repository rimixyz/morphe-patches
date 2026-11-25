package miui.app.backup;

import android.content.Context;

@SuppressWarnings("unused")
public class BackupManager {
    private static final BackupManager INSTANCE = new BackupManager();

    public static BackupManager getBackupManager(Context context) {
        return INSTANCE;
    }

    public String getCurrentRunningPackage() {
        return "";
    }

    public int getState() {
        return 0;
    }

    public void setIsNeedBeKilled(boolean needBeKilled) {
    }
}
