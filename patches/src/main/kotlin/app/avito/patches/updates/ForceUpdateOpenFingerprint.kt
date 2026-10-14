package app.avito.patches.updates

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val FORCE_UPDATE_ROOT_ACTIVITY =
    "Lcom/avito/android/forceupdate/screens/forceupdateroot/ForceUpdateRootActivity;"

/**
 * Matches the method that opens the force-update screen: it builds an Intent for
 * [FORCE_UPDATE_ROOT_ACTIVITY] with the `open_params` extra and starts it.
 *
 * Anchored on the activity class rather than the opener's package: Avito 235.1
 * repackaged the opener (`version_conflict/o` became `m413`) and the open-params
 * type into the default package. The activity keeps its name because the manifest
 * references it.
 */
object ForceUpdateOpenFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        string("open_params"),
        methodCall(
            definingClass = "Landroid/content/Context;",
            name = "startActivity",
        ),
    ),
    custom = { method, _ ->
        method.instructionsOrNull?.any { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type == FORCE_UPDATE_ROOT_ACTIVITY
        } == true
    },
)
