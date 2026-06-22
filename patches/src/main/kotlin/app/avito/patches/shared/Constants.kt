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
                version = "227.0",
                minSdk = 28,
            ),
            AppTarget(
                version = "226.5",
                minSdk = 28,
            ),
            AppTarget(
                version = "225.5",
                minSdk = 28,
            ),
            AppTarget(
                version = "224.6",
                minSdk = 28,
            ),
            // Unlisted versions are best-effort: the ad patches require ad surfaces
            // that older builds (≤ ~213) lack, so they hard-fail there. Floor verified
            // at 221; explicit targets above are all known-good.
            AppTarget(
                version = null,
                isExperimental = true,
                minSdk = 28,
            ),
        ),
    )
}
