package app.avito.patches.updates

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

object ForceUpdateOpenFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/version_conflict/",
    returnType = "V",
    filters = listOf(
        string("open_params"),
        methodCall(
            definingClass = "Landroid/content/Context;",
            name = "startActivity",
        ),
    ),
    custom = { method, _ ->
        method.parameterTypes.singleOrNull()
            ?.toString()
            ?.startsWith("Lcom/avito/android/forceupdate/screens/forceupdateroot/") == true
    },
)
