package app.privacy.patches.emulator

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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val EMULATOR_INDICATOR_STRINGS = setOf(
    "/dev/qemu_pipe",
    "/dev/qemu_trace",
    "/proc/tty/drivers",
    "/proc/cpuinfo",
    "/sys/qemu_trace",
    "/system/bin/qemu-props",
    "/system/lib/libc_malloc_debug_qemu.so",
    "/system/lib64/libc_malloc_debug_qemu.so",
    "Android SDK built for x86",
    "Build.",
    "Genymotion",
    "HARDWARE is goldfish",
    "HARDWARE is ranchu",
    "HARDWARE is vbox86",
    "MANUFACTURER contains Genymotion",
    "google_sdk",
    "ro.boot.qemu",
    "ro.hardware",
    "ro.kernel.qemu",
    "ro.product.board",
    "ro.product.brand",
    "ro.product.device",
    "ro.product.manufacturer",
    "ro.product.model",
    "ro.product.name",
)

private val EMULATOR_INDICATOR_SUBSTRINGS = setOf(
    "android sdk built for x86",
    "bluestacks",
    "droid4x",
    "emulator",
    "genymotion",
    "goldfish",
    "google_sdk",
    "nox",
    "qemu",
    "ranchu",
    "sdk_gphone",
    "sdk_x86",
    "vbox86",
)

private val BUILD_FIELD_SPOOFS = mapOf(
    "BOARD" to "oriole",
    "BOOTLOADER" to "slider-1.2-8893284",
    "BRAND" to "google",
    "DEVICE" to "oriole",
    "FINGERPRINT" to "google/oriole/oriole:13/TQ3A.230805.001/10316531:user/release-keys",
    "HARDWARE" to "oriole",
    "MANUFACTURER" to "Google",
    "MODEL" to "Pixel 6",
    "PRODUCT" to "oriole",
    "TAGS" to "release-keys",
    "TYPE" to "user",
)

private val SYSTEM_PROPERTY_SPOOFS = mapOf(
    "init.svc.qemu-props" to "stopped",
    "qemu.hw.mainkeys" to "0",
    "ro.boot.qemu" to "0",
    "ro.hardware" to "oriole",
    "ro.kernel.qemu" to "0",
    "ro.product.board" to "oriole",
    "ro.product.brand" to "google",
    "ro.product.device" to "oriole",
    "ro.product.manufacturer" to "Google",
    "ro.product.model" to "Pixel 6",
    "ro.product.name" to "oriole",
)

private val EMULATOR_PACKAGE_NAMES = setOf(
    "com.bluestacks",
    "com.bluestacks.appmart",
    "com.bluestacks.BstCommandProcessor",
    "com.genymotion.superuser",
    "com.google.android.launcher.layouts.genymotion",
    "com.microvirt.launcher",
    "com.microvirt.market",
    "com.nox.mopen.app",
    "com.vphone.launcher",
)

private fun Instruction.registers(): List<Int> =
    when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG)
            .take(registerCount)

        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

private fun Instruction.writesRegister(register: Int): Boolean {
    if (this !is OneRegisterInstruction || registerA != register) return false

    return when (opcode) {
        Opcode.CONST,
        Opcode.CONST_4,
        Opcode.CONST_16,
        Opcode.CONST_CLASS,
        Opcode.CONST_HIGH16,
        Opcode.CONST_STRING,
        Opcode.CONST_STRING_JUMBO,
        Opcode.MOVE,
        Opcode.MOVE_16,
        Opcode.MOVE_EXCEPTION,
        Opcode.MOVE_FROM16,
        Opcode.MOVE_OBJECT,
        Opcode.MOVE_OBJECT_16,
        Opcode.MOVE_OBJECT_FROM16,
        Opcode.MOVE_RESULT,
        Opcode.MOVE_RESULT_OBJECT,
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

private fun String.hasEmulatorMarker(): Boolean =
    this in EMULATOR_INDICATOR_STRINGS ||
        EMULATOR_INDICATOR_SUBSTRINGS.any { marker -> contains(marker, ignoreCase = true) }

private fun Iterable<Instruction>.hasEmulatorDetectionContext(): Boolean {
    if (any { instruction ->
            instruction.stringReferenceOrNull()?.hasEmulatorMarker() == true
        }
    ) {
        return true
    }

    val buildFieldReads = count { instruction ->
        if (instruction.opcode != Opcode.SGET_OBJECT) return@count false
        val field = instruction.fieldReferenceOrNull() ?: return@count false
        field.isBuildFieldToSpoof()
    }
    return buildFieldReads >= 3
}

private fun Method.hasEmulatorDetectionContext(): Boolean =
    instructionsOrNull?.hasEmulatorDetectionContext() == true

private fun FieldReference.isBuildFieldToSpoof() =
    definingClass == "Landroid/os/Build;" &&
        type == "Ljava/lang/String;" &&
        name in BUILD_FIELD_SPOOFS

private fun MethodReference.isFileExists() =
    definingClass == "Ljava/io/File;" &&
        name == "exists" &&
        parameterTypes.isEmpty() &&
        returnType == "Z"

private fun MethodReference.isFileCanRead() =
    definingClass == "Ljava/io/File;" &&
        name == "canRead" &&
        parameterTypes.isEmpty() &&
        returnType == "Z"

private fun MethodReference.isRuntimeExec() =
    definingClass == "Ljava/lang/Runtime;" &&
        name == "exec" &&
        returnType == "Ljava/lang/Process;"

private fun MethodReference.isProcessBuilderStart() =
    definingClass == "Ljava/lang/ProcessBuilder;" &&
        name == "start" &&
        parameterTypes.isEmpty() &&
        returnType == "Ljava/lang/Process;"

private fun MethodReference.isSystemGetProperty() =
    (definingClass == "Ljava/lang/System;" && name == "getProperty") ||
        (definingClass == "Landroid/os/SystemProperties;" && name == "get")

private fun MethodReference.isPackageManagerGetPackageInfo() =
    definingClass == "Landroid/content/pm/PackageManager;" &&
        name == "getPackageInfo" &&
        returnType == "Landroid/content/pm/PackageInfo;"

private fun MethodReference.isPackageManagerGetApplicationInfo() =
    definingClass == "Landroid/content/pm/PackageManager;" &&
        name == "getApplicationInfo" &&
        returnType == "Landroid/content/pm/ApplicationInfo;"

@Suppress("unused")
val spoofEmulatorStatusPatch = bytecodePatch(
    name = "Spoof emulator status",
    description = "Spoofs emulator state through common Build, QEMU file, command, and system property checks.",
    default = false,
) {
    execute {
        var patchedBuildFieldReads = 0
        var patchedFileChecks = 0
        var patchedCommandExecutions = 0
        var patchedSystemProperties = 0
        var patchedPackageQueries = 0

        classDefForEach { classDef ->
            if (classDef.methods.none { it.hasEmulatorDetectionContext() }) return@classDefForEach

            mutableClassDefBy(classDef).methods.forEach { method ->
                val instructions = method.instructionsOrNull ?: return@forEach
                val instructionList = instructions.toList()
                if (!instructionList.hasEmulatorDetectionContext()) return@forEach

                instructionList.forEachIndexed { index, instruction ->
                    when {
                        instruction.opcode == Opcode.SGET_OBJECT -> {
                            val field = instruction.fieldReferenceOrNull() ?: return@forEachIndexed
                            if (!field.isBuildFieldToSpoof()) return@forEachIndexed

                            val destinationRegister = (instruction as OneRegisterInstruction).registerA
                            val safeValue = BUILD_FIELD_SPOOFS[field.name] ?: return@forEachIndexed
                            method.replaceInstruction(index, "const-string v$destinationRegister, \"$safeValue\"")
                            patchedBuildFieldReads++
                        }

                        instruction.opcode in setOf(
                            Opcode.INVOKE_STATIC,
                            Opcode.INVOKE_STATIC_RANGE,
                            Opcode.INVOKE_VIRTUAL,
                            Opcode.INVOKE_VIRTUAL_RANGE,
                        ) -> {
                            val reference = instruction.methodReferenceOrNull() ?: return@forEachIndexed

                            when {
                                reference.isFileExists() || reference.isFileCanRead() -> {
                                    val moveResult = instructionList.getOrNull(index + 1) as? OneRegisterInstruction
                                        ?: return@forEachIndexed
                                    if (moveResult.opcode != Opcode.MOVE_RESULT) return@forEachIndexed

                                    method.replaceInstruction(index + 1, "const/4 v${moveResult.registerA}, 0x0")
                                    patchedFileChecks++
                                }

                                reference.isRuntimeExec() || reference.isProcessBuilderStart() -> {
                                    val moveResult = instructionList.getOrNull(index + 1) as? OneRegisterInstruction
                                        ?: return@forEachIndexed
                                    if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed

                                    method.replaceInstruction(index + 1, "const/4 v${moveResult.registerA}, 0x0")
                                    patchedCommandExecutions++
                                }

                                reference.isPackageManagerGetPackageInfo() ||
                                    reference.isPackageManagerGetApplicationInfo() -> {
                                    val packageRegister = instruction.registers().getOrNull(1)
                                        ?: return@forEachIndexed
                                    val packageName =
                                        instructionList.constantStringForRegisterBefore(index, packageRegister)
                                            ?: return@forEachIndexed
                                    if (packageName !in EMULATOR_PACKAGE_NAMES) return@forEachIndexed

                                    val moveResult = instructionList.getOrNull(index + 1) as? OneRegisterInstruction
                                        ?: return@forEachIndexed
                                    if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed

                                    method.replaceInstruction(index + 1, "const/4 v${moveResult.registerA}, 0x0")
                                    patchedPackageQueries++
                                }

                                reference.isSystemGetProperty() -> {
                                    val propertyRegister = instruction.registers().firstOrNull()
                                        ?: return@forEachIndexed
                                    val propertyName =
                                        instructionList.constantStringForRegisterBefore(index, propertyRegister)
                                            ?: return@forEachIndexed
                                    val safeValue = SYSTEM_PROPERTY_SPOOFS[propertyName] ?: return@forEachIndexed

                                    val moveResult = instructionList.getOrNull(index + 1) as? OneRegisterInstruction
                                        ?: return@forEachIndexed
                                    if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed

                                    method.replaceInstruction(
                                        index + 1,
                                        "const-string v${moveResult.registerA}, \"$safeValue\"",
                                    )
                                    patchedSystemProperties++
                                }
                            }
                        }
                    }
                }
            }
        }

        if (
            patchedBuildFieldReads == 0 &&
            patchedFileChecks == 0 &&
            patchedCommandExecutions == 0 &&
            patchedSystemProperties == 0 &&
            patchedPackageQueries == 0
        ) {
            println("Spoof emulator status: no emulator status call sites were found.")
            return@execute
        }

        println(
            "Spoof emulator status: patched $patchedBuildFieldReads Build field reads, " +
                "$patchedFileChecks file checks, " +
                "$patchedCommandExecutions command executions, " +
                "$patchedSystemProperties system property reads, and " +
                "$patchedPackageQueries package queries.",
        )
    }
}
