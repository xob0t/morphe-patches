package app.avito.patches.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

private fun isAvitoClickstreamTracker(classType: String) =
    classType.startsWith("Lcom/avito/android/analytics/clickstream/")

private fun isAvitoAdjustWrapper(classType: String) =
    classType.startsWith("Lcom/avito/android/analytics_adjust/")

// Primary (227.0+): ClickStreamEventTrackerImpl.c(event) — the public track-event
// method that wraps each event in a runnable and dispatches it to an Executor.
// Neutering it stops clickstream at the source. (On 227 R8 merged the enqueue
// runnable itself into a shared lambda dispatcher shared with unrelated lambdas, so
// the tracker method is the correct, safe target.)
object ClickstreamTrackEventFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lcom/avito/android/analytics/o;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/concurrent/Executor;",
            name = "execute",
        ),
    ),
    custom = { _, classDef ->
        isAvitoClickstreamTracker(classDef.type)
    },
)

// Fallback (older builds): the clickstream enqueue runnable itself, identified by
// its ANR log line and the inhouse-transport add() call, when it still lives in a
// dedicated clickstream class.
object ClickstreamEnqueueRunnableFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("Sending event on main thread. May cause ANR"),
        methodCall(
            definingClass = "Lcom/avito/android/analytics/inhouse_transport/u;",
            name = "add",
        ),
    ),
    custom = { _, classDef ->
        isAvitoClickstreamTracker(classDef.type)
    },
)

object AdjustInitFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/adjust/sdk/Adjust;",
            name = "initSdk",
        ),
        string("Adjust initialized"),
    ),
    custom = { _, classDef ->
        isAvitoAdjustWrapper(classDef.type)
    },
)

object AdjustTrackEventFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lcom/adjust/sdk/AdjustEvent;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/adjust/sdk/Adjust;",
            name = "trackEvent",
        ),
    ),
    custom = { _, classDef ->
        isAvitoAdjustWrapper(classDef.type)
    },
)

object AdjustUserIdFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/adjust/sdk/Adjust;",
            name = "addGlobalPartnerParameter",
        ),
        methodCall(
            definingClass = "Lcom/adjust/sdk/Adjust;",
            name = "removeGlobalPartnerParameter",
        ),
    ),
    custom = { _, classDef ->
        isAvitoAdjustWrapper(classDef.type)
    },
)

object AdjustPushTokenFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/adjust/sdk/Adjust;",
            name = "setPushToken",
        ),
    ),
    custom = { _, classDef ->
        isAvitoAdjustWrapper(classDef.type)
    },
)
