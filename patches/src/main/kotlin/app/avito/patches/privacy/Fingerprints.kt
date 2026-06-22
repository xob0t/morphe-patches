package app.avito.patches.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

// Primary (227.0+): ClickStreamEventTrackerImpl.c(event) — the public track-event
// method that wraps each event in a runnable and dispatches it to an Executor.
// Neutering it stops clickstream at the source. (On 227 R8 merged the enqueue
// runnable itself into a shared lambda dispatcher shared with unrelated lambdas, so
// the tracker method is the correct, safe target.)
object ClickstreamTrackEventFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/analytics/clickstream/",
    returnType = "V",
    // The single parameter is the clickstream event, which lives in the
    // `com.avito.android.analytics` package — anchor on that package prefix rather
    // than the event class's obfuscated name (it was `analytics/o` but R8 re-letters
    // it). A type without a trailing `;` is matched with startsWith.
    parameters = listOf(
        "Lcom/avito/android/analytics/",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/concurrent/Executor;",
            name = "execute",
        ),
    ),
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
    definingClass = "Lcom/avito/android/analytics_adjust/",
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/adjust/sdk/Adjust;",
            name = "initSdk",
        ),
        string("Adjust initialized"),
    ),
)

object AdjustTrackEventFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/analytics_adjust/",
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
)

object AdjustUserIdFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/analytics_adjust/",
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
)

object AdjustPushTokenFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/analytics_adjust/",
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
)
