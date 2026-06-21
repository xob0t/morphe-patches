package app.privacy.patches.analytics

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import app.shared.*

@Suppress("unused")
val disableAdjustPatch = resourcePatch(
    name = "Disable Adjust",
    description = "Disables Adjust attribution manifest entry points.",
    default = false,
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val application = manifest.childrenNamed("application").single() as Element

            manifest.removeChildren(
                manifest.childrenNamed("uses-permission")
                    .filter { it.getAttribute("android:name").startsWith("com.adjust.") },
            )

            val disabledComponents = application.disableComponentsByPrefix(
                "com.adjust.",
            )

            println("Disable Adjust: disabled $disabledComponents manifest components.")
        }
    }
}
