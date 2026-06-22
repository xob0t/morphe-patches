package app.privacy.patches.analytics

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import app.shared.*

/*
 * Adapted from ReVanced:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/shared/misc/privacy/DisableSentryTelemetry.kt
 */
@Suppress("unused")
val disableSentryTelemetryPatch = resourcePatch(
    name = "Disable Sentry telemetry",
    description = "Disables Sentry telemetry by turning off SDK auto-init and clearing the DSN.",
    default = false,
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.childrenNamed("application").single() as Element

            application.setApplicationMetaData("io.sentry.enabled", "false")
            application.setApplicationMetaData("io.sentry.dsn", "")

            val disabledComponents = application.disableComponentsWhere { name ->
                name.startsWith("io.sentry.") || name.contains(".Sentry")
            }

            println("Disable Sentry telemetry: disabled $disabledComponents manifest components.")
        }
    }
}
