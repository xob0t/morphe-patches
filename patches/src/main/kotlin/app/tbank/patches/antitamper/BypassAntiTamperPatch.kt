package app.tbank.patches.antitamper

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.option
import app.tbank.patches.shared.Constants.COMPATIBILITY_TBANK
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import app.shared.*
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

// Helpers.

private fun MethodReference.isRaspExec() =
    definingClass == RASP_EXECUTOR &&
        name == "exec" &&
        parameterTypes.size == 1 &&
        parameterTypes[0].toString() == "J" &&
        returnType == "Ljava/lang/String;"

// Matches every native void executor call: exec2, exec5, exec6, and any future
// execN(boolean) the app adds. All share the RASP executor and a single boolean
// parameter; 7.40.0 added exec5/exec6 alongside exec2, and an un-stubbed one hits
// an unresolved JNI symbol (the native lib is blocked from loading) and crashes.
private fun MethodReference.isRaspVoidExec() =
    definingClass == RASP_EXECUTOR &&
        name.startsWith("exec") &&
        name.drop(4).all { it.isDigit() } &&
        parameterTypes.size == 1 &&
        parameterTypes[0].toString() == "Z" &&
        returnType == "V"

private fun MethodReference.isSystemLoadLibrary() =
    definingClass == SYSTEM &&
        name == "loadLibrary" &&
        parameterTypes.size == 1 &&
        parameterTypes[0].toString() == "Ljava/lang/String;" &&
        returnType == "V"

private fun MethodReference.isTamperFlagConstructor() =
    name == "<init>" &&
        parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;", "J") &&
        returnType == "V"

private fun List<Instruction>.tamperFlagProviderName(): String? {
    if (size != 5 ||
        this[0].opcode != Opcode.NEW_INSTANCE ||
        this[1].opcode !in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) ||
        this[2].opcode !in setOf(Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE) ||
        this[3].opcode != Opcode.INVOKE_DIRECT ||
        this[4].opcode != Opcode.RETURN_VOID ||
        this[3].methodReferenceOrNull()?.isTamperFlagConstructor() != true
    ) {
        return null
    }

    return this[1].stringReferenceOrNull()?.takeIf { it in TAMPER_FLAG_NAMES }
}

private data class AntiTamperTargets(
    val hasRaspCalls: Boolean,
    val hasLoadLibrary: Boolean,
    val hasTamperFlag: Boolean,
) {
    val hasAnyTarget: Boolean
        get() = hasRaspCalls || hasLoadLibrary || hasTamperFlag
}

private fun Iterable<Instruction>.antiTamperTargets(): AntiTamperTargets {
    var hasRaspCalls = false
    var hasSystemLoadLibrary = false
    var hasRaspNativeLib = false
    var hasTamperFlag = false

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
        if (string in TAMPER_FLAG_NAMES) {
            hasTamperFlag = true
        }
    }

    return AntiTamperTargets(
        hasRaspCalls = hasRaspCalls,
        hasLoadLibrary = hasSystemLoadLibrary && hasRaspNativeLib,
        hasTamperFlag = hasTamperFlag,
    )
}

@Suppress("unused")
val bypassAntiTamperPatch = bytecodePatch(
    name = "Bypass anti-tamper",
    description = "Stubs TBank's native RASP executor calls and neutralizes tamper flag reporting.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TBANK)

    val strictTargets by option<Boolean>(
        key = "strictTargets",
        title = "Require all current targets",
        description = "Fails when a current integrity-check target no longer matches. Intended for automated builds.",
        default = false,
    )

    execute {
        var patchedRaspExecCalls = 0
        var fullyStubbedRaspExecCalls = 0
        var patchedRaspVoidExecCalls = 0
        var patchedLibraryLoads = 0
        var patchedTamperFlags = 0
        val blockedNativeLibraries = mutableSetOf<String>()
        val neutralizedTamperFlagNames = mutableSetOf<String>()

        classDefForEach { classDef ->
            val classTargets = classDef.methods
                .mapNotNull { it.instructionsOrNull?.antiTamperTargets() }
                .fold(AntiTamperTargets(false, false, false)) { current, methodTargets ->
                    AntiTamperTargets(
                        hasRaspCalls = current.hasRaspCalls || methodTargets.hasRaspCalls,
                        hasLoadLibrary = current.hasLoadLibrary || methodTargets.hasLoadLibrary,
                        hasTamperFlag = current.hasTamperFlag || methodTargets.hasTamperFlag,
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

                // Real flag providers are tiny static initializers. The same strings
                // also appear in a large enum initializer, so never rewrite a whole
                // method merely because it contains a target string.
                if (classTargets.hasTamperFlag) {
                    val tamperFlagName = instructionList.tamperFlagProviderName()
                    if (method.name == "<clinit>" && tamperFlagName != null) {
                        for (index in 0..3) {
                            method.replaceInstruction(index, "nop")
                        }
                        patchedTamperFlags++
                        neutralizedTamperFlagNames += tamperFlagName
                    }
                }
            }
        }

        if (strictTargets == true) {
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
                    "Bypass anti-tamper strict validation failed; missing current target(s): " +
                        missingTargets.joinToString(", "),
                )
            }
        }

        println(
            "Bypass anti-tamper: stubbed $patchedRaspExecCalls Executor.exec() calls, " +
                "$patchedRaspVoidExecCalls Executor.execN(boolean) calls, " +
                "blocked $patchedLibraryLoads native library loads, " +
                "neutralized $patchedTamperFlags tamper flag providers.",
        )
    }
}
