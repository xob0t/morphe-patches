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
                version = "7.6.8001",
                versionCode = 10061041,
                minSdk = 26,
            ),
            AppTarget(
                version = "7.6.1000-rustore",
                versionCode = 61016,
                minSdk = 26,
            ),
            AppTarget(
                version = "7.0.6000",
                versionCode = 10060832,
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
