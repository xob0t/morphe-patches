package app.privacy.patches.analytics

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import app.shared.*

@Suppress("unused")
val disableAppsFlyerPatch = resourcePatch(
    name = "Disable AppsFlyer",
    description = "Disables AppsFlyer install referrer and attribution manifest entry points.",
    default = false,
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val application = manifest.childrenNamed("application").single() as Element

            manifest.removeChildren(
                manifest.childrenNamed("uses-permission")
                    .filter { it.getAttribute("android:name") == "com.appsflyer.referrer.INSTALL_PROVIDER" },
            )

            val disabledComponents = application.disableComponentsByPrefix(
                "com.appsflyer.",
            )

            println("Disable AppsFlyer: disabled $disabledComponents manifest components.")
        }
    }
}
