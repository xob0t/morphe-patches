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
                version = "19.22.0",
                versionCode = 2687,
                minSdk = 26,
            ),
            AppTarget(
                version = "19.16.0",
                versionCode = 2677,
                minSdk = 26,
            ),
            AppTarget(
                version = "18.37.0",
                versionCode = 2613,
                minSdk = 26,
            ),
            AppTarget(
                version = null,
                isExperimental = true,
                minSdk = 26,
            ),
        ),
    )
}
