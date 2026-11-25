package miui.process;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

@SuppressWarnings("unused")
public interface IForegroundInfoListener extends IInterface {
    void onForegroundInfoChanged(ForegroundInfo foregroundInfo);

    abstract class Stub extends Binder implements IForegroundInfoListener {
        public Stub() {
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
