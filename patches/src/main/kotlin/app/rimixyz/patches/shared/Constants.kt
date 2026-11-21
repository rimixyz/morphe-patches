package app.rimixyz.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_17TRACK = Compatibility(
        name = "17TRACK",
        packageName = "yqtrack.app",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1878F3,
        targets = listOf(
            // Version analyzed and confirmed by reverse engineering of the APK.
            AppTarget(version = "3.1.6896.1")
        )
    )

    val COMPATIBILITY_WRESTLINGREVOLUTION = Compatibility(
        name = "Wrestling Revolution",
        packageName = "air.WRCareer",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xD32F2F,
        targets = listOf(
            // Version analyzed and confirmed by reverse engineering of the APK.
            AppTarget(version = "2.150.64")
        )
    )
}
