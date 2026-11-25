package miui.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

@SuppressWarnings("unused")
public interface IMiuiFreeFormGuideTipServices extends IInterface {
    int getShowGuideClick();
    int getShowGuideController();
    int getShowGuideDock();
    int getShowGuideFreeform();
    int getShowGuideNotification();
    int getShowGuidePin();
    int getShowGuideSideBar();
    int getShowGuideSplit();
    boolean hasTipView();
    boolean hasTipViewType(int type);
    void removeFreeFormTipView(int type);
    void setShowGuideClick(int val);
    void setShowGuideController(int val);
    void setShowGuideFreeform(int val);
    void setShowGuideNotification(int val);
    void setShowGuidePin(int val);
    void setShowGuideSideBar(int val);
    void setShowGuideSplit(int val);
    void showFreeFormGuideDialog(int type);
    void showFreeFormTipView(int a, int b, int c, int d);
    void showFreeFormTipViewByClassName(int a, int b, int c, int d, String className);

    abstract class Stub extends Binder implements IMiuiFreeFormGuideTipServices {
        public static IMiuiFreeFormGuideTipServices asInterface(IBinder obj) {
            return null;
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
