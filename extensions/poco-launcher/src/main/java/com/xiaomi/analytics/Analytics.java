package com.xiaomi.analytics;

import android.content.Context;

@SuppressWarnings("unused")
public class Analytics {
    private static final Analytics INSTANCE = new Analytics();
    private static final Tracker TRACKER = new Tracker();

    public static Analytics getInstance(Context context) {
        return INSTANCE;
    }

    public Tracker getTracker(String configKey) {
        return TRACKER;
    }

    public void setDontUseSystemAnalytics(boolean dontUse) {
    }
}
