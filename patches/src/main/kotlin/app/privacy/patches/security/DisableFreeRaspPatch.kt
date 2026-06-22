package app.privacy.patches.security

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import app.shared.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val CONTEXT = "Landroid/content/Context;"
private const val STRING = "Ljava/lang/String;"

private fun Method.hasFreeRaspStartContext(): Boolean {
    val instructions = instructionsOrNull?.toList() ?: return false

    return instructions.any { instruction ->
        instruction.stringReferenceOrNull()?.contains("Unable to run Talsec", ignoreCase = true) == true
    } && instructions.any { instruction ->
        instruction.stringReferenceOrNull() == "start"
    }
}

private fun MethodReference.isFreeRaspStartCandidate(): Boolean =
    returnType == "V" &&
        parameterTypes.size == 2 &&
        parameterTypes[0].toString() == CONTEXT &&
        parameterTypes[1].toString() != STRING &&
        parameterTypes[1].toString() != "Z" &&
        parameterTypes[1].toString() != "I"

@Suppress("unused")
val disableFreeRaspPatch = bytecodePatch(
    name = "Disable freeRASP",
    description = "Disables the freeRASP mobile security SDK startup.",
    default = false,
) {
    execute {
        var patchedStartCalls = 0

        classDefForEach { classDef ->
            if (classDef.methods.none { it.hasFreeRaspStartContext() }) {
                return@classDefForEach
            }

            mutableClassDefBy(classDef).methods.forEach { method ->
                if (!method.hasFreeRaspStartContext()) return@forEach

                val instructions = method.instructionsOrNull ?: return@forEach
                instructions.toList().forEachIndexed { index, instruction ->
                    if (instruction.opcode != Opcode.INVOKE_STATIC) return@forEachIndexed

                    val reference = instruction.methodReferenceOrNull() ?: return@forEachIndexed
                    if (!reference.isFreeRaspStartCandidate()) return@forEachIndexed

                    method.replaceInstruction(index, "nop")
                    patchedStartCalls++
                }
            }
        }

        println("Disable freeRASP: disabled $patchedStartCalls SDK startup calls.")
    }
}
