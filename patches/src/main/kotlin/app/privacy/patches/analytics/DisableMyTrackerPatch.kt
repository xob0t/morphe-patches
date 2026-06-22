package app.privacy.patches.analytics

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import app.shared.*

@Suppress("unused")
val disableMyTrackerPatch = resourcePatch(
    name = "Disable MyTracker",
    description = "Disables MyTracker manifest entry points.",
    default = false,
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.childrenNamed("application").single() as Element

            val disabledComponents = application.disableComponentsWhere { name ->
                name.startsWith("com.my.tracker.") ||
                    name.startsWith("ru.mail.mytracker.") ||
                    name.contains(".mytracker.", ignoreCase = true)
            }

            println("Disable MyTracker: disabled $disabledComponents manifest components.")
        }
    }
}
