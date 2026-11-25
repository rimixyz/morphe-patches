package com.xiaomi.freeform;

import android.app.ActivityOptions;
import android.content.Context;

@SuppressWarnings("unused")
public class MiuiFreeformStub {
    private static final MiuiFreeformStub INSTANCE = new MiuiFreeformStub();

    public static MiuiFreeformStub getInstance() {
        return INSTANCE;
    }

    public ActivityOptions getActivityOptions(
            Context context, String packageName, boolean noCheck, boolean isPinFloating) {
        return null;
    }

    public boolean supportFreeform() {
        return false;
    }
}
