package app.avito.patches.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

private const val CLICKSTREAM_TRACKER_SOURCE_FILE = "ClickStreamEventTrackerImpl.kt"

// Avito's Adjust wrapper is matched by its SDK calls; Avito 235.1 moved it out of
// `analytics_adjust/` into the default package (`g91`). Skip the SDK's own classes.
private const val ADJUST_SDK_PACKAGE = "Lcom/adjust/"

// Primary (227.0+): ClickStreamEventTrackerImpl.c(event) — the public track-event
// method that wraps each event in a runnable and dispatches it to an Executor.
// Neutering it stops clickstream at the source. (On 227 R8 merged the enqueue
// runnable itself into a shared lambda dispatcher shared with unrelated lambdas, so
// the tracker method is the correct, safe target.)
//
// Anchored on the class's preserved Kotlin source file rather than its package:
// Avito 235.1 repackaged the tracker and its event interface into the default
// package (`qws.b(pbg0)`), so neither the class nor the parameter keeps a stable name.
object ClickstreamTrackEventFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/concurrent/Executor;",
            name = "execute",
        ),
    ),
    custom = { _, classDef -> classDef.sourceFile == CLICKSTREAM_TRACKER_SOURCE_FILE },
)

// Fallback (older builds): the clickstream enqueue runnable itself, identified by
// its (unique) ANR log line and an add() call into the inhouse-transport package,
// when it still lives in a dedicated clickstream class. The transport class name is
// matched by package prefix, not its obfuscated leaf (`inhouse_transport/u` rolls).
object ClickstreamEnqueueRunnableFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/analytics/clickstream/",
    returnType = "V",
    filters = listOf(
        string("Sending event on main thread. May cause ANR"),
        methodCall(
            definingClass = "Lcom/avito/android/analytics/inhouse_transport/",
            name = "add",
        ),
    ),
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
    custom = { _, classDef -> !classDef.type.startsWith(ADJUST_SDK_PACKAGE) },
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
    custom = { _, classDef -> !classDef.type.startsWith(ADJUST_SDK_PACKAGE) },
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
    custom = { _, classDef -> !classDef.type.startsWith(ADJUST_SDK_PACKAGE) },
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
    custom = { _, classDef -> !classDef.type.startsWith(ADJUST_SDK_PACKAGE) },
)
