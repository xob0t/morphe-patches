package app.avito.patches.privacy

import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables Avito first-party clickstream analytics and Avito's direct Adjust telemetry wrapper.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_AVITO)

    execute {
        // Each telemetry entry point is patched independently and tolerates absence;
        // Avito's analytics get repackaged across releases, so a missing fingerprint
        // skips that entry point instead of aborting the whole patch.
        //
        // Clickstream targets 227.0+ first (the ClickStreamEventTracker.c(event)
        // method) and falls back to the older dedicated enqueue runnable. On 227 R8
        // merged that runnable into a shared lambda dispatcher, so the tracker method
        // is the only safe choke point.
        val clickstream = ClickstreamTrackEventFingerprint.methodOrNull
            ?: ClickstreamEnqueueRunnableFingerprint.methodOrNull

        val targets = listOf(
            "clickstream" to clickstream,
            "Adjust init" to AdjustInitFingerprint.methodOrNull,
            "Adjust trackEvent" to AdjustTrackEventFingerprint.methodOrNull,
            "Adjust userId" to AdjustUserIdFingerprint.methodOrNull,
            "Adjust pushToken" to AdjustPushTokenFingerprint.methodOrNull,
        )

        var disabled = 0
        val skipped = mutableListOf<String>()
        targets.forEach { (label, method) ->
            if (method == null) {
                skipped += label
            } else {
                method.addInstructions(0, "return-void")
                disabled++
            }
        }

        println(
            "Avito privacy: disabled $disabled telemetry entry point(s)" +
                if (skipped.isEmpty()) "." else "; skipped (absent on this build): ${skipped.joinToString()}.",
        )
    }
}
