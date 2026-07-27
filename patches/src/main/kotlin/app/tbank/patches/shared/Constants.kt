package app.tbank.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val PACKAGE_NAME = "com.idamob.tinkoff.android"

    val COMPATIBILITY_TBANK = Compatibility(
        name = "TBank",
        packageName = PACKAGE_NAME,
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFFDD2D,
        targets = listOf(
            AppTarget(
                version = "7.40.0",
                versionCode = 12840,
                minSdk = 28,
            ),
        ),
    )
}
