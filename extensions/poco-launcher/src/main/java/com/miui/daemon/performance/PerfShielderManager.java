package com.miui.daemon.performance;

import app.rimixyz.extension.pocolauncher.PocoCompatHelper;

@SuppressWarnings("unused")
public class PerfShielderManager {
    public static Long getFreeMemory() {
        return PocoCompatHelper.readMemInfoBytes("MemAvailable:", 1024L * 1024L * 1024L);
    }
}
