package app.privacy.patches.usb

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import app.shared.*
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val USB_DEBUG_INTEGER_SETTINGS = setOf(
    "adb_enabled",
    "development_settings_enabled",
    "usb_mass_storage_enabled",
    "wait_for_debugger",
)

private val USB_DEBUG_STRING_SETTINGS = USB_DEBUG_INTEGER_SETTINGS + "debug_app"

private const val SETTINGS_GLOBAL = "Landroid/provider/Settings\$Global;"
private const val SETTINGS_SECURE = "Landroid/provider/Settings\$Secure;"
private const val SETTINGS_SYSTEM = "Landroid/provider/Settings\$System;"
private const val DEBUG = "Landroid/os/Debug;"

private fun zeroConstant(register: Int) =
    if (register <= 15) {
        "const/4 v$register, 0x0"
    } else {
        "const/16 v$register, 0x0"
    }

private fun Instruction.registers(): List<Int> =
    when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG)
            .take(registerCount)

        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

private fun MethodReference.isSettingsGetInt() =
    definingClass in setOf(SETTINGS_GLOBAL, SETTINGS_SECURE, SETTINGS_SYSTEM) &&
        name == "getInt" &&
        parameterTypes.size in 2..3 &&
        parameterTypes[0].toString() == "Landroid/content/ContentResolver;" &&
        parameterTypes[1].toString() == "Ljava/lang/String;" &&
        returnType == "I"

private fun MethodReference.isSettingsGetString() =
    definingClass in setOf(SETTINGS_GLOBAL, SETTINGS_SECURE, SETTINGS_SYSTEM) &&
        name == "getString" &&
        parameterTypes.size == 2 &&
        parameterTypes[0].toString() == "Landroid/content/ContentResolver;" &&
        parameterTypes[1].toString() == "Ljava/lang/String;" &&
        returnType == "Ljava/lang/String;"

private fun MethodReference.isDebuggerStateRead() =
    definingClass == DEBUG &&
        name in setOf("isDebuggerConnected", "waitingForDebugger") &&
        parameterTypes.isEmpty() &&
        returnType == "Z"

private fun List<Instruction>.constantStringForRegisterBefore(index: Int, register: Int): String? {
    for (candidateIndex in index - 1 downTo maxOf(0, index - 16)) {
        val candidate = this[candidateIndex]
        if (
            candidate is OneRegisterInstruction &&
            candidate.registerA == register &&
            candidate.opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)
        ) {
            return candidate.stringReferenceOrNull()
        }

        if (candidate.writesRegister(register)) return null
    }

    return null
}

private fun Instruction.writesRegister(register: Int): Boolean {
    if (this !is OneRegisterInstruction || registerA != register) return false

    return when (opcode) {
        Opcode.CONST_4,
        Opcode.CONST_16,
        Opcode.CONST,
        Opcode.CONST_HIGH16,
        Opcode.CONST_STRING,
        Opcode.CONST_STRING_JUMBO,
        Opcode.CONST_CLASS,
        Opcode.MOVE,
        Opcode.MOVE_FROM16,
        Opcode.MOVE_16,
        Opcode.MOVE_OBJECT,
        Opcode.MOVE_OBJECT_FROM16,
        Opcode.MOVE_OBJECT_16,
        Opcode.MOVE_RESULT,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.MOVE_EXCEPTION,
        Opcode.NEW_INSTANCE,
        Opcode.SGET,
        Opcode.SGET_BOOLEAN,
        Opcode.SGET_BYTE,
        Opcode.SGET_CHAR,
        Opcode.SGET_OBJECT,
        Opcode.SGET_SHORT,
            -> true

        else -> false
    }
}

private fun List<Instruction>.hasUsbDebugPatchTarget(): Boolean =
    withIndex().any { (index, instruction) ->
        val reference = instruction.methodReferenceOrNull() ?: return@any false
        val registers = instruction.registers()
        val settingRegister = registers.getOrNull(1)

        when {
            reference.isDebuggerStateRead() -> true
            reference.isSettingsGetInt() && settingRegister != null ->
                constantStringForRegisterBefore(index, settingRegister)
                    ?.let { it in USB_DEBUG_INTEGER_SETTINGS }
                    ?: true

            reference.isSettingsGetString() && settingRegister != null ->
                constantStringForRegisterBefore(index, settingRegister) in USB_DEBUG_STRING_SETTINGS

            else -> false
        }
    }

private fun Method.hasUsbDebugPatchTarget(): Boolean =
    instructionsOrNull?.toList()?.hasUsbDebugPatchTarget() == true

@Suppress("unused")
val spoofUsbDebuggingStatusPatch = bytecodePatch(
    name = "Spoof USB debugging status",
    description = "Spoofs USB debugging and related developer settings through common Android APIs.",
    default = false,
) {
    execute {
        var patchedSettingsIntReads = 0
        var patchedDynamicSettingsIntReads = 0
        var patchedSettingsStringReads = 0
        var patchedDebuggerStateReads = 0

        classDefForEach { classDef ->
            if (classDef.methods.none { it.hasUsbDebugPatchTarget() }) return@classDefForEach

            mutableClassDefBy(classDef).methods.forEach { method ->
                val instructions = method.instructionsOrNull?.toList() ?: return@forEach
                if (!instructions.hasUsbDebugPatchTarget()) return@forEach

                instructions.forEachIndexed { index, instruction ->
                    val reference = instruction.methodReferenceOrNull() ?: return@forEachIndexed

                    when {
                        reference.isSettingsGetInt() -> {
                            val settingRegister = instruction.registers().getOrNull(1) ?: return@forEachIndexed
                            val settingName = instructions.constantStringForRegisterBefore(index, settingRegister)
                            if (settingName != null && settingName !in USB_DEBUG_INTEGER_SETTINGS) {
                                return@forEachIndexed
                            }

                            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction
                                ?: return@forEachIndexed
                            if (moveResult.opcode != Opcode.MOVE_RESULT) return@forEachIndexed

                            method.replaceInstruction(index + 1, zeroConstant(moveResult.registerA))
                            if (settingName == null) {
                                patchedDynamicSettingsIntReads++
                            } else {
                                patchedSettingsIntReads++
                            }
                        }

                        reference.isSettingsGetString() -> {
                            val settingRegister = instruction.registers().getOrNull(1) ?: return@forEachIndexed
                            val settingName = instructions.constantStringForRegisterBefore(index, settingRegister)
                            if (settingName !in USB_DEBUG_STRING_SETTINGS) return@forEachIndexed

                            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction
                                ?: return@forEachIndexed
                            if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed

                            if (settingName == "debug_app") {
                                method.replaceInstruction(index + 1, zeroConstant(moveResult.registerA))
                            } else {
                                method.replaceInstruction(index + 1, "const-string v${moveResult.registerA}, \"0\"")
                            }
                            patchedSettingsStringReads++
                        }

                        reference.isDebuggerStateRead() -> {
                            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction
                                ?: return@forEachIndexed
                            if (moveResult.opcode != Opcode.MOVE_RESULT) return@forEachIndexed

                            method.replaceInstruction(index + 1, zeroConstant(moveResult.registerA))
                            patchedDebuggerStateReads++
                        }
                    }
                }
            }
        }

        if (
            patchedSettingsIntReads == 0 &&
            patchedDynamicSettingsIntReads == 0 &&
            patchedSettingsStringReads == 0 &&
            patchedDebuggerStateReads == 0
        ) {
            println("Spoof USB debugging status: no USB debugging status call sites were found.")
            return@execute
        }

        println(
            "Spoof USB debugging status: patched $patchedSettingsIntReads settings int reads, " +
                "$patchedDynamicSettingsIntReads dynamic settings int reads, " +
                "$patchedSettingsStringReads settings string reads, and " +
                "$patchedDebuggerStateReads debugger state reads.",
        )
    }
}
