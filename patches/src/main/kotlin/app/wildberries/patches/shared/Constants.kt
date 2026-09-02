package app.wildberries.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val PACKAGE_NAME = "com.wildberries.ru"

    private const val RUSTORE_VERSION_SUFFIX = "-rustore"
    private const val GOOGLE_PLAY_VERSION_CODE_OFFSET = 10_000_000

    private fun AppTarget.withGooglePlayCounterpart(): List<AppTarget> {
        val rustoreVersion = version
            ?.takeIf { it.endsWith(RUSTORE_VERSION_SUFFIX) }
            ?: return listOf(this)

        return listOf(
            this,
            copy(
                version = rustoreVersion.removeSuffix(RUSTORE_VERSION_SUFFIX),
                versionCodes = versionCodes?.mapValues { (_, versionCode) ->
                    versionCode + GOOGLE_PLAY_VERSION_CODE_OFFSET
                },
            ),
        )
    }

    val COMPATIBILITY_WILDBERRIES = Compatibility(
        name = "Wildberries",
        packageName = PACKAGE_NAME,
        apkFileType = ApkFileType.APK,
        appIconColor = 0xA73AFD,
        targets = listOf(
            AppTarget(
                version = "7.7.8001-rustore",
                versionCode = 61069,
                minSdk = 26,
            ),
            AppTarget(
                version = "7.7.7001-rustore",
                versionCode = 61066,
                minSdk = 26,
            ),
            AppTarget(
                version = "7.7.6003-rustore",
                versionCode = 61064,
                minSdk = 26,
            ),
            AppTarget(
                version = "7.7.5003-rustore",
                versionCode = 61060,
                minSdk = 26,
            ),
            AppTarget(
                version = "7.7.4003-rustore",
                versionCode = 61056,
                minSdk = 26,
            ),
            AppTarget(
                version = "7.7.3001-rustore",
                versionCode = 61052,
                minSdk = 26,
            ),
            AppTarget(
                version = "7.7.2001-rustore",
                versionCode = 61050,
                minSdk = 26,
            ),
        ).flatMap { it.withGooglePlayCounterpart() },
    )
}
