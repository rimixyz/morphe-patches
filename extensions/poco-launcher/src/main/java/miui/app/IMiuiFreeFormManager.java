package miui.app;

import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import java.util.List;

@SuppressWarnings("unused")
public interface IMiuiFreeFormManager extends IInterface {
    List<MiuiFreeFormManager.MiuiFreeFormStackInfo> getAllFreeFormStackInfosOnDisplay(int displayId)
            throws RemoteException;

    List<MiuiFreeFormManager.MiuiFreeFormStackInfo> getAllPinedFreeFormStackInfosOnDisplay(int displayId)
            throws RemoteException;

    MiuiFreeFormManager.MiuiFreeFormStackInfo getFreeFormStackInfoByActivity(IBinder token)
            throws RemoteException;

    MiuiFreeFormManager.MiuiFreeFormStackInfo getFreeFormStackInfoByStackId(int stackId)
            throws RemoteException;

    MiuiFreeFormManager.MiuiFreeFormStackInfo getFreeFormStackInfoByWindow(IBinder token)
            throws RemoteException;

    MiuiFreeFormManager.MiuiFreeFormStackInfo getFreeFormStackToAvoid(int displayId, String pkg)
            throws RemoteException;

    boolean hasMiuiFreeformOnShellFeature() throws RemoteException;

    boolean isSupportPin() throws RemoteException;

    void notifyCameraStateChanged(String packageName, int state) throws RemoteException;

    boolean openCameraInFreeForm(String packageName) throws RemoteException;

    void registerFreeformCallback(IFreeformCallback callback) throws RemoteException;

    int startSmallFreeformFromNotification() throws RemoteException;

    void unregisterFreeformCallback(IFreeformCallback callback) throws RemoteException;
}
