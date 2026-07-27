package app.wildberries.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val PACKAGE_NAME = "com.wildberries.ru"

    val COMPATIBILITY_WILDBERRIES = Compatibility(
        name = "Wildberries",
        packageName = PACKAGE_NAME,
        apkFileType = ApkFileType.APK,
        appIconColor = 0xA73AFD,
        targets = listOf(
            AppTarget(
                version = "7.7.2001-rustore",
                versionCode = 61050,
                minSdk = 26,
            ),
        ),
    )
}
