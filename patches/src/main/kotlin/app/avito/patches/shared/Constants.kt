package app.avito.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val PACKAGE_NAME = "com.avito.android"

    val COMPATIBILITY_AVITO = Compatibility(
        name = "Avito",
        packageName = PACKAGE_NAME,
        apkFileType = ApkFileType.APK,
        appIconColor = 0x00AAFF,
        targets = listOf(
            AppTarget(
                version = "230.5",
                versionCode = 3476,
                minSdk = 28,
            ),
            AppTarget(
                version = "230.0",
                versionCode = 3472,
                minSdk = 28,
            ),
            AppTarget(
                version = "229.1",
                versionCode = 3444,
                minSdk = 28,
            ),
        ),
    )
}
