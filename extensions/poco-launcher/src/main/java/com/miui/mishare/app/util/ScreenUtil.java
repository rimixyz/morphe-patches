package com.miui.mishare.app.util;

import android.content.Context;

@SuppressWarnings("unused")
public class ScreenUtil {
    public static int dip2px(Context context, float dpValue) {
        if (context == null) {
            return (int) (dpValue + 0.5f);
        }
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dpValue * scale + 0.5f);
    }
}
