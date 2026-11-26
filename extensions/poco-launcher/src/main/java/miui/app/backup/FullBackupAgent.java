package miui.app.backup;

import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

@SuppressWarnings("unused")
public class FullBackupAgent extends BackupAgent {
    public FullBackupAgent() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onBackup(
            ParcelFileDescriptor oldState,
            BackupDataOutput data,
            ParcelFileDescriptor newState) throws IOException {
    }

    @Override
    public void onRestore(
            BackupDataInput data,
            int appVersionCode,
            ParcelFileDescriptor newState) throws IOException {
    }

    protected void onOriginalAttachesRestore(
            BackupMeta meta,
            ParcelFileDescriptor data,
            long size,
            int type,
            String domain,
            String path,
            long mode,
            long mtime) throws IOException {
    }
}
