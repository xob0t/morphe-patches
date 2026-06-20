package app.avito.patches.privacy

import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val avitoPrivacyPatch = bytecodePatch(
    name = "Avito privacy",
    description = "Disables Avito first-party clickstream analytics and Avito's direct Adjust telemetry wrapper.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_AVITO)

    execute {
        // Each telemetry entry point is patched independently and tolerates
        // absence. Avito's analytics get repackaged across releases (e.g. on 227.0
        // R8 merges the clickstream enqueue runnable into a shared lambda dispatcher,
        // so its fingerprint deliberately no longer matches — neutering that merged
        // method would break the unrelated lambdas sharing it). A missing fingerprint
        // skips that entry point instead of aborting the whole patch.
        val targets = listOf(
            "clickstream enqueue" to ClickstreamEnqueueRunnableFingerprint.methodOrNull,
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
