package app.tbank.patches.antitamper

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.shared.*
import app.tbank.patches.shared.Constants.COMPATIBILITY_TBANK
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val RASP_EXECUTOR = "Lcom/t/core/miaf/ndk/Executor;"
private const val SYSTEM = "Ljava/lang/System;"

// RASP native libraries to prevent loading.

private val RASP_NATIVE_LIBS = setOf(
    "i",
    "rooot",
    "toolChecker",
)

// Flag parameter names reported to backend.

private val TAMPER_FLAG_NAMES = setOf(
    "clonnedApp_flag",
    "repackagedApk_flag",
)

private object TamperFlagProviderFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        opcode(Opcode.NEW_INSTANCE, InstructionLocation.MatchFirst()),
        anyInstruction(
            *TAMPER_FLAG_NAMES.map(::string).toTypedArray(),
            location = InstructionLocation.MatchAfterImmediately(),
        ),
        anyInstruction(
            opcode(Opcode.CONST_WIDE_16),
            opcode(Opcode.CONST_WIDE_32),
            opcode(Opcode.CONST_WIDE),
            location = InstructionLocation.MatchAfterImmediately(),
        ),
        methodCall(
            name = "<init>",
            parameters = listOf("Ljava/lang/String;", "J"),
            returnType = "V",
            opcode = Opcode.INVOKE_DIRECT,
            location = InstructionLocation.MatchAfterImmediately(),
        ),
        opcode(Opcode.RETURN_VOID, InstructionLocation.MatchAfterImmediately()),
    ),
    custom = { method, _ -> method.instructionsOrNull?.count() == 5 },
)

// Helpers.

private fun MethodReference.isRaspExec() = definingClass == RASP_EXECUTOR &&
    name == "exec" &&
    parameterTypes.size == 1 &&
    parameterTypes[0].toString() == "J" &&
    returnType == "Ljava/lang/String;"

// Matches every native void executor call: exec2, exec5, exec6, and any future
// execN(boolean) the app adds. All share the RASP executor and a single boolean
// parameter; 7.40.0 added exec5/exec6 alongside exec2, and an un-stubbed one hits
// an unresolved JNI symbol (the native lib is blocked from loading) and crashes.
private fun MethodReference.isRaspVoidExec() = definingClass == RASP_EXECUTOR &&
    name.startsWith("exec") &&
    name.drop(4).all { it.isDigit() } &&
    parameterTypes.size == 1 &&
    parameterTypes[0].toString() == "Z" &&
    returnType == "V"

private fun MethodReference.isSystemLoadLibrary() = definingClass == SYSTEM &&
    name == "loadLibrary" &&
    parameterTypes.size == 1 &&
    parameterTypes[0].toString() == "Ljava/lang/String;" &&
    returnType == "V"

private data class AntiTamperTargets(
    val hasRaspCalls: Boolean,
    val hasLoadLibrary: Boolean,
) {
    val hasAnyTarget: Boolean
        get() = hasRaspCalls || hasLoadLibrary
}

private fun Iterable<Instruction>.antiTamperTargets(): AntiTamperTargets {
    var hasRaspCalls = false
    var hasSystemLoadLibrary = false
    var hasRaspNativeLib = false

    forEach { instruction ->
        val reference = instruction.methodReferenceOrNull()
        if (reference?.isRaspExec() == true || reference?.isRaspVoidExec() == true) {
            hasRaspCalls = true
        }
        if (reference?.isSystemLoadLibrary() == true) {
            hasSystemLoadLibrary = true
        }

        val string = instruction.stringReferenceOrNull()
        if (string in RASP_NATIVE_LIBS) {
            hasRaspNativeLib = true
        }
    }

    return AntiTamperTargets(
        hasRaspCalls = hasRaspCalls,
        hasLoadLibrary = hasSystemLoadLibrary && hasRaspNativeLib,
    )
}

@Suppress("unused")
val bypassAntiTamperPatch = bytecodePatch(
    name = "Bypass anti-tamper",
    description = "Stubs TBank's native RASP executor calls and neutralizes tamper flag reporting.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TBANK)

    execute {
        var patchedRaspExecCalls = 0
        var fullyStubbedRaspExecCalls = 0
        var patchedRaspVoidExecCalls = 0
        var patchedLibraryLoads = 0
        var patchedTamperFlags = 0
        val blockedNativeLibraries = mutableSetOf<String>()
        val neutralizedTamperFlagNames = mutableSetOf<String>()

        TamperFlagProviderFingerprint.matchAllOrNull().orEmpty().forEach { match ->
            val tamperFlagName = match.instructionMatches[1].instruction.stringReferenceOrNull()
                ?: return@forEach
            val method = match.method
            for (index in 0..3) {
                method.replaceInstruction(index, "nop")
            }
            patchedTamperFlags++
            neutralizedTamperFlagNames += tamperFlagName
        }

        classDefForEach { classDef ->
            val classTargets = classDef.methods
                .mapNotNull { it.instructionsOrNull?.antiTamperTargets() }
                .fold(AntiTamperTargets(false, false)) { current, methodTargets ->
                    AntiTamperTargets(
                        hasRaspCalls = current.hasRaspCalls || methodTargets.hasRaspCalls,
                        hasLoadLibrary = current.hasLoadLibrary || methodTargets.hasLoadLibrary,
                    )
                }

            if (!classTargets.hasAnyTarget) return@classDefForEach

            mutableClassDefBy(classDef).methods.forEach { method ->
                val instructions = method.instructionsOrNull ?: return@forEach
                val instructionList = instructions.toList()

                instructionList.forEachIndexed { index, instruction ->
                    val reference = instruction.methodReferenceOrNull()

                    when {
                        // Stub Executor.exec(long) by replacing move-result-object with an empty string.
                        reference?.isRaspExec() == true -> {
                            method.replaceInstruction(index, "nop")
                            val nextInstruction = instructionList.getOrNull(index + 1)
                            if (nextInstruction?.opcode == Opcode.MOVE_RESULT_OBJECT) {
                                val moveResult = nextInstruction as? OneRegisterInstruction
                                if (moveResult != null) {
                                    method.replaceInstruction(
                                        index + 1,
                                        "const-string v${moveResult.registerA}, \"\"",
                                    )
                                    fullyStubbedRaspExecCalls++
                                }
                            } else {
                                // The caller discards the String result, so removing the
                                // invoke itself is the complete patch for this site.
                                fullyStubbedRaspExecCalls++
                            }
                            patchedRaspExecCalls++
                        }

                        // Stub Executor.execN(boolean) (exec2, exec5, exec6, ...).
                        reference?.isRaspVoidExec() == true -> {
                            method.replaceInstruction(index, "nop")
                            patchedRaspVoidExecCalls++
                        }

                        // Block RASP native library loading.
                        reference?.isSystemLoadLibrary() == true -> {
                            for (scanIndex in index - 1 downTo maxOf(0, index - 4)) {
                                val libName = instructionList[scanIndex].stringReferenceOrNull()
                                    ?: continue
                                if (libName in RASP_NATIVE_LIBS) {
                                    method.replaceInstruction(index, "nop")
                                    patchedLibraryLoads++
                                    blockedNativeLibraries += libName
                                }
                                break
                            }
                        }
                    }
                }
            }
        }

        val missingTargets = buildList {
            if (patchedRaspExecCalls == 0) add("Executor.exec")
            if (fullyStubbedRaspExecCalls != patchedRaspExecCalls) {
                add("Executor.exec result handling (${patchedRaspExecCalls - fullyStubbedRaspExecCalls} unresolved)")
            }
            if (patchedRaspVoidExecCalls == 0) add("Executor.execN(boolean)")
            (RASP_NATIVE_LIBS - blockedNativeLibraries).forEach { add("native library $it") }
            (TAMPER_FLAG_NAMES - neutralizedTamperFlagNames).forEach { add("tamper flag $it") }
        }
        if (missingTargets.isNotEmpty()) {
            throw PatchException(
                "Bypass anti-tamper validation failed; missing target(s): " +
                    missingTargets.joinToString(", "),
            )
        }

        println(
            "Bypass anti-tamper: stubbed $patchedRaspExecCalls Executor.exec() calls, " +
                "$patchedRaspVoidExecCalls Executor.execN(boolean) calls, " +
                "blocked $patchedLibraryLoads native library loads, " +
                "neutralized $patchedTamperFlags tamper flag providers.",
        )
    }
}
