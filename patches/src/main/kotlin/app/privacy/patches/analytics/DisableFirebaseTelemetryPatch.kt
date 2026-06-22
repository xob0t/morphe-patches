package app.privacy.patches.analytics

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import app.shared.*

@Suppress("unused")
val disableFirebaseTelemetryPatch = resourcePatch(
    name = "Disable Firebase telemetry",
    description = "Disables Firebase telemetry collection flags and DataTransport sender entry points.",
    default = false,
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.childrenNamed("application").single() as Element

            mapOf(
                "firebase_analytics_collection_enabled" to "false",
                "firebase_crashlytics_collection_enabled" to "false",
                "firebase_performance_collection_enabled" to "false",
                "firebase_performance_logcat_enabled" to "false",
                "firebase_data_collection_default_enabled" to "false",
                "google_analytics_adid_collection_enabled" to "false",
                "google_analytics_deferred_deep_link_enabled" to "false",
            ).forEach { (name, value) -> application.setApplicationMetaData(name, value) }

            val disabledComponents = application.disableComponentsByName(
                "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
                "com.google.firebase.sessions.SessionLifecycleService",
            )

            println(
                "Disable Firebase telemetry: disabled $disabledComponents manifest components.",
            )
        }
    }
}
