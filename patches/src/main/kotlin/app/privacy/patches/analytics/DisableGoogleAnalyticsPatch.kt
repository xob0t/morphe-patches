package app.privacy.patches.analytics

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import app.shared.*

@Suppress("unused")
val disableGoogleAnalyticsPatch = resourcePatch(
    name = "Disable Google Analytics",
    description = "Disables legacy Google Analytics manifest entry points.",
    default = false,
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.childrenNamed("application").single() as Element

            application.setApplicationMetaData("google_analytics_adid_collection_enabled", "false")
            application.setApplicationMetaData("google_analytics_deferred_deep_link_enabled", "false")

            val disabledComponents = application.disableComponentsWhere { name ->
                name.startsWith("com.google.android.gms.analytics.") ||
                    name.startsWith("com.google.android.gms.tagmanager.")
            }

            println("Disable Google Analytics: disabled $disabledComponents manifest components.")
        }
    }
}
