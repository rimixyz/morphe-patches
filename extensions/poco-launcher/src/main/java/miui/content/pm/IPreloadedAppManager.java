package miui.content.pm;

import android.content.pm.IPackageInstallObserver2;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

@SuppressWarnings("unused")
public interface IPreloadedAppManager extends IInterface {
    void reinstallPreloadedApp2(
            String packageName, IPackageInstallObserver2 observer, int flags) throws RemoteException;

    abstract class Stub extends Binder implements IPreloadedAppManager {
        public static IPreloadedAppManager asInterface(IBinder obj) {
            return null;
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
