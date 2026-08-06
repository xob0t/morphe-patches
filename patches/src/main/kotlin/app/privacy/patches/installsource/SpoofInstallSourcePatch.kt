package app.privacy.patches.installsource

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PLAY_STORE_PACKAGE = "com.android.vending"
private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
private const val INSTALL_SOURCE_INFO = "Landroid/content/pm/InstallSourceInfo;"

private val installSourceCallFilter = anyInstruction(
    methodCall(
        definingClass = PACKAGE_MANAGER,
        name = "getInstallerPackageName",
        parameters = listOf("Ljava/lang/String;"),
        returnType = "Ljava/lang/String;",
        opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
    ),
    *listOf(
        "getInitiatingPackageName",
        "getInstallingPackageName",
        "getOriginatingPackageName",
        "getUpdateOwnerPackageName",
    ).map { methodName ->
        methodCall(
            definingClass = INSTALL_SOURCE_INFO,
            name = methodName,
            parameters = emptyList(),
            returnType = "Ljava/lang/String;",
            opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
        )
    }.toTypedArray(),
)

private object InstallSourceCallFingerprint : Fingerprint(
    filters = listOf(installSourceCallFilter),
)

@Suppress("unused")
val spoofInstallSourcePatch = bytecodePatch(
    name = "Spoof install source",
    description = "Spoofs package installer checks to report Google Play as the install source.",
    default = false,
) {
    execute {
        var patchedInstallerPackageNameReads = 0

        InstallSourceCallFingerprint.matchAllOrNull().orEmpty().forEach { match ->
            val method = match.method
            val instructionList = method.instructionsOrNull?.toList() ?: return@forEach

            instructionList.forEachIndexed { index, instruction ->
                if (!installSourceCallFilter.matches(method, instruction)) return@forEachIndexed
                val moveResult = instructionList.getOrNull(index + 1) as? OneRegisterInstruction
                    ?: return@forEachIndexed
                if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed

                method.replaceInstruction(
                    index + 1,
                    "const-string v${moveResult.registerA}, \"$PLAY_STORE_PACKAGE\"",
                )
                patchedInstallerPackageNameReads++
            }
        }

        if (patchedInstallerPackageNameReads == 0) {
            println("Spoof install source: no install source call sites were found.")
            return@execute
        }

        println(
            "Spoof install source: patched $patchedInstallerPackageNameReads installer package reads.",
        )
    }
}
