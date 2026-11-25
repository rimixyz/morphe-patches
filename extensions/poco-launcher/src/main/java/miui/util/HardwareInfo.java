package miui.util;

import app.rimixyz.extension.pocolauncher.PocoCompatHelper;

@SuppressWarnings("unused")
public class HardwareInfo {
    public static long getFreeMemory() {
        return PocoCompatHelper.readMemInfoBytes("MemAvailable:", 1024L * 1024L * 1024L);
    }

    public static long getTotalPhysicalMemory() {
        return PocoCompatHelper.readMemInfoBytes("MemTotal:", 4L * 1024L * 1024L * 1024L);
    }
}
