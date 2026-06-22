package app.privacy.patches.vpn

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import app.shared.*
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val TRANSPORT_VPN = 4
private const val NET_CAPABILITY_NOT_VPN = 15

private const val NETWORK_CAPABILITIES = "Landroid/net/NetworkCapabilities;"
private const val NETWORK_REQUEST_BUILDER = "Landroid/net/NetworkRequest\$Builder;"
private const val CONNECTIVITY_MANAGER = "Landroid/net/ConnectivityManager;"
private const val NETWORK_REQUEST = "Landroid/net/NetworkRequest;"
private const val NETWORK_CALLBACK = "Landroid/net/ConnectivityManager\$NetworkCallback;"
private const val LINK_PROPERTIES = "Landroid/net/LinkProperties;"
private const val NETWORK_INTERFACE = "Ljava/net/NetworkInterface;"

private fun MethodReference.isNetworkCapabilitiesHasTransport() =
    definingClass == NETWORK_CAPABILITIES &&
        name == "hasTransport" &&
        parameterTypes.singleOrNull()?.toString() == "I" &&
        returnType == "Z"

private fun MethodReference.isNetworkCapabilitiesHasCapability() =
    definingClass == NETWORK_CAPABILITIES &&
        name == "hasCapability" &&
        parameterTypes.singleOrNull()?.toString() == "I" &&
        returnType == "Z"

private fun MethodReference.isNetworkCapabilitiesToString() =
    definingClass == NETWORK_CAPABILITIES &&
        name == "toString" &&
        parameterTypes.isEmpty() &&
        returnType == "Ljava/lang/String;"

private fun MethodReference.isNetworkRequestBuilderAddTransportType() =
    definingClass == NETWORK_REQUEST_BUILDER &&
        name == "addTransportType" &&
        parameterTypes.singleOrNull()?.toString() == "I" &&
        returnType == NETWORK_REQUEST_BUILDER

private fun MethodReference.isConnectivityManagerRegisterNetworkCallback() =
    definingClass == CONNECTIVITY_MANAGER &&
        name == "registerNetworkCallback" &&
        parameterTypes.size >= 2 &&
        parameterTypes[0].toString() == NETWORK_REQUEST &&
        parameterTypes[1].toString() == NETWORK_CALLBACK &&
        returnType == "V"

private fun MethodReference.isLinkPropertiesGetInterfaceName() =
    definingClass == LINK_PROPERTIES &&
        name == "getInterfaceName" &&
        parameterTypes.isEmpty() &&
        returnType == "Ljava/lang/String;"

private fun MethodReference.isLinkPropertiesGetHttpProxy() =
    definingClass == LINK_PROPERTIES &&
        name == "getHttpProxy" &&
        parameterTypes.isEmpty() &&
        returnType == "Landroid/net/ProxyInfo;"

private fun MethodReference.isLinkPropertiesGetRoutes() =
    definingClass == LINK_PROPERTIES &&
        name == "getRoutes" &&
        parameterTypes.isEmpty() &&
        returnType == "Ljava/util/List;"

private fun MethodReference.isNetworkInterfaceGetNetworkInterfaces() =
    definingClass == NETWORK_INTERFACE &&
        name == "getNetworkInterfaces" &&
        parameterTypes.isEmpty() &&
        returnType == "Ljava/util/Enumeration;"

private fun Instruction.registers(): List<Int> =
    when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG)
            .take(registerCount)

        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

private fun Instruction.invokeRegisterListSmali(): String =
    when (this) {
        is FiveRegisterInstruction -> registers().joinToString(prefix = "{", postfix = "}") { "v$it" }
        is RegisterRangeInstruction -> {
            val endRegister = startRegister + registerCount - 1
            "{v$startRegister .. v$endRegister}"
        }

        else -> error("Instruction is not an invoke instruction: $this")
    }

private fun Instruction.isConstLiteral(register: Int, literal: Int): Boolean =
    this is OneRegisterInstruction &&
        this is NarrowLiteralInstruction &&
        registerA == register &&
        narrowLiteral == literal

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

private fun List<Instruction>.hasLiteralBefore(index: Int, register: Int, literal: Int): Boolean {
    for (candidateIndex in index - 1 downTo maxOf(0, index - 8)) {
        val candidate = this[candidateIndex]
        if (candidate.isConstLiteral(register, literal)) return true
        if (candidate.writesRegister(register)) return false
    }

    return false
}

private fun List<Instruction>.hasVpnTransportLiteralBefore(index: Int, register: Int) =
    hasLiteralBefore(index, register, TRANSPORT_VPN)

private fun List<Instruction>.hasNotVpnCapabilityLiteralBefore(index: Int, register: Int) =
    hasLiteralBefore(index, register, NET_CAPABILITY_NOT_VPN)

private fun List<Instruction>.hasVpnDetectionString(): Boolean =
    any { instruction ->
        val string = instruction.stringReferenceOrNull()?.lowercase() ?: return@any false
        listOf("vpn", "tun", "wg", "ppp", "ipsec", "utun").any { marker -> marker in string }
    }

private fun List<Instruction>.hasVpnOsStatePatchTarget(): Boolean =
    withIndex().any { (index, instruction) ->
        val reference = instruction.methodReferenceOrNull() ?: return@any false
        val transportRegister = instruction.registers().lastOrNull() ?: return@any false

        (reference.isNetworkCapabilitiesHasTransport() ||
            reference.isNetworkRequestBuilderAddTransportType()) &&
            hasVpnTransportLiteralBefore(index, transportRegister)
    } ||
        withIndex().any { (index, instruction) ->
            val reference = instruction.methodReferenceOrNull() ?: return@any false
            val capabilityRegister = instruction.registers().lastOrNull() ?: return@any false

            reference.isNetworkCapabilitiesHasCapability() &&
                hasNotVpnCapabilityLiteralBefore(index, capabilityRegister)
        }

private fun List<Instruction>.hasVpnSignalContext(definingClass: String): Boolean {
    if (hasVpnOsStatePatchTarget()) return true
    if (hasVpnDetectionString()) return true

    val className = definingClass.lowercase()
    return "vpn" in className || "tun" in className
}

private fun List<Instruction>.hasVpnNetworkStateCollectionTarget(definingClass: String): Boolean {
    if (!hasVpnSignalContext(definingClass)) return false

    return any { instruction ->
        val reference = instruction.methodReferenceOrNull() ?: return@any false
        reference.isNetworkCapabilitiesToString() ||
            reference.isLinkPropertiesGetInterfaceName() ||
            reference.isLinkPropertiesGetHttpProxy() ||
            reference.isLinkPropertiesGetRoutes() ||
            reference.isNetworkInterfaceGetNetworkInterfaces()
    }
}

private fun List<Instruction>.hasVpnNetworkRequestBuilder(): Boolean =
    withIndex().any { (index, instruction) ->
        val reference = instruction.methodReferenceOrNull() ?: return@any false
        val transportRegister = instruction.registers().lastOrNull() ?: return@any false

        reference.isNetworkRequestBuilderAddTransportType() &&
            hasVpnTransportLiteralBefore(index, transportRegister)
    }

private fun Method.hasVpnPatchTarget(): Boolean {
    val instructions = instructionsOrNull?.toList() ?: return false
    return instructions.hasVpnOsStatePatchTarget() ||
        instructions.hasVpnNetworkRequestBuilder() ||
        instructions.hasVpnNetworkStateCollectionTarget(definingClass)
}

@Suppress("unused")
val spoofVpnStatusPatch = bytecodePatch(
    name = "Spoof VPN status",
    description = "Spoofs VPN state through common Android network APIs.",
    default = false,
) {
    execute {
        var patchedHasTransportCalls = 0
        var patchedHasNotVpnCapabilityCalls = 0
        var patchedVpnNetworkCallbacks = 0
        var patchedCapabilityStrings = 0
        var patchedInterfaceNames = 0
        var patchedHttpProxies = 0
        var patchedRoutes = 0
        var patchedNetworkInterfaceEnumerations = 0

        classDefForEach { classDef ->
            if (classDef.methods.none { it.hasVpnPatchTarget() }) return@classDefForEach

            mutableClassDefBy(classDef).methods.forEach { method ->
                val instructions = method.instructionsOrNull?.toList() ?: return@forEach
                val hasVpnNetworkRequestBuilder = instructions.hasVpnNetworkRequestBuilder()
                val hasVpnSignalContext = instructions.hasVpnSignalContext(method.definingClass)
                if (
                    !instructions.hasVpnOsStatePatchTarget() &&
                    !hasVpnNetworkRequestBuilder &&
                    !instructions.hasVpnNetworkStateCollectionTarget(method.definingClass)
                ) {
                    return@forEach
                }

                instructions.forEachIndexed { index, instruction ->
                    val reference = instruction.methodReferenceOrNull() ?: return@forEachIndexed

                    when {
                        reference.isNetworkCapabilitiesHasTransport() -> {
                            val transportRegister = instruction.registers().lastOrNull() ?: return@forEachIndexed
                            if (!instructions.hasVpnTransportLiteralBefore(index, transportRegister)) {
                                return@forEachIndexed
                            }

                            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction
                                ?: return@forEachIndexed
                            if (moveResult.opcode != Opcode.MOVE_RESULT) return@forEachIndexed

                            method.replaceInstruction(index + 1, "const/4 v${moveResult.registerA}, 0x0")
                            patchedHasTransportCalls++
                        }

                        reference.isNetworkCapabilitiesHasCapability() -> {
                            val capabilityRegister = instruction.registers().lastOrNull() ?: return@forEachIndexed
                            if (!instructions.hasNotVpnCapabilityLiteralBefore(index, capabilityRegister)) {
                                return@forEachIndexed
                            }

                            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction
                                ?: return@forEachIndexed
                            if (moveResult.opcode != Opcode.MOVE_RESULT) return@forEachIndexed

                            method.replaceInstruction(index + 1, "const/4 v${moveResult.registerA}, 0x1")
                            patchedHasNotVpnCapabilityCalls++
                        }

                        hasVpnNetworkRequestBuilder && reference.isConnectivityManagerRegisterNetworkCallback() -> {
                            method.replaceInstruction(index, "nop")
                            patchedVpnNetworkCallbacks++
                        }

                        hasVpnSignalContext && reference.isNetworkCapabilitiesToString() -> {
                            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction
                                ?: return@forEachIndexed
                            if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed

                            method.replaceInstruction(index + 1, "const-string v${moveResult.registerA}, \"\"")
                            patchedCapabilityStrings++
                        }

                        hasVpnSignalContext && reference.isLinkPropertiesGetInterfaceName() -> {
                            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction
                                ?: return@forEachIndexed
                            if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed

                            method.replaceInstruction(index + 1, "const/4 v${moveResult.registerA}, 0x0")
                            patchedInterfaceNames++
                        }

                        hasVpnSignalContext && reference.isLinkPropertiesGetHttpProxy() -> {
                            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction
                                ?: return@forEachIndexed
                            if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed

                            method.replaceInstruction(index + 1, "const/4 v${moveResult.registerA}, 0x0")
                            patchedHttpProxies++
                        }

                        hasVpnSignalContext && reference.isLinkPropertiesGetRoutes() -> {
                            method.replaceInstruction(
                                index,
                                "invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;",
                            )
                            patchedRoutes++
                        }

                        hasVpnSignalContext && reference.isNetworkInterfaceGetNetworkInterfaces() -> {
                            method.replaceInstruction(
                                index,
                                "invoke-static {}, Ljava/util/Collections;->emptyEnumeration()Ljava/util/Enumeration;",
                            )
                            patchedNetworkInterfaceEnumerations++
                        }
                    }
                }
            }
        }

        if (
            patchedHasTransportCalls == 0 &&
            patchedHasNotVpnCapabilityCalls == 0 &&
            patchedVpnNetworkCallbacks == 0 &&
            patchedCapabilityStrings == 0 &&
            patchedInterfaceNames == 0 &&
            patchedHttpProxies == 0 &&
            patchedRoutes == 0 &&
            patchedNetworkInterfaceEnumerations == 0
        ) {
            println("Spoof VPN status: no local VPN status call sites were found.")
            return@execute
        }

        println(
            "Spoof VPN status: patched $patchedHasTransportCalls hasTransport(TRANSPORT_VPN) calls, " +
                "$patchedHasNotVpnCapabilityCalls hasCapability(NOT_VPN) calls, " +
                "$patchedVpnNetworkCallbacks VPN network callback registrations, " +
                "$patchedCapabilityStrings capability strings, " +
                "$patchedInterfaceNames interface names, " +
                "$patchedHttpProxies HTTP proxies, " +
                "$patchedRoutes route lists, and " +
                "$patchedNetworkInterfaceEnumerations network interface enumerations.",
        )
    }
}
