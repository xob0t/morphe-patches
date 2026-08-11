package app.ozon.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val PACKAGE_NAME = "ru.ozon.app.android"

    val COMPATIBILITY_OZON = Compatibility(
        name = "Ozon",
        packageName = PACKAGE_NAME,
        apkFileType = ApkFileType.APK,
        appIconColor = 0x005BFF,
        targets = listOf(
            AppTarget(
                version = "19.30.0",
                versionCode = 2705,
                minSdk = 26,
            ),
            AppTarget(
                version = "19.29.0",
                versionCode = 2700,
                minSdk = 26,
            ),
            AppTarget(
                version = "19.28.0",
                versionCode = 2698,
                minSdk = 26,
            ),
            AppTarget(
                version = "19.27.0",
                versionCode = 2697,
                minSdk = 26,
            ),
        ),
    )
}
