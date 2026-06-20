package app.avito.patches.settings

import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Element

private const val MORPHE_SETTINGS_CLASS = "Lapp/avito/morphe/MorpheSettings;"
private const val MORPHE_SETTINGS_ACTIVITY = "app.avito.morphe.MorpheSettingsActivity"

private fun Element.childrenNamed(name: String): List<Element> {
    val nodes = childNodes
    return buildList {
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.nodeName == name) add(node)
        }
    }
}

/**
 * Registers the generic Morphe settings screen ([MORPHE_SETTINGS_ACTIVITY],
 * provided by the extension) in the app manifest. Opened from the in-app Settings
 * entry; declared `exported` so it can also be opened directly for testing via
 * `adb shell am start`.
 */
val registerMorpheSettingsActivityPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_AVITO)

    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.childrenNamed("application").single()
            val alreadyRegistered = application.childrenNamed("activity").any {
                it.getAttribute("android:name") == MORPHE_SETTINGS_ACTIVITY
            }
            if (alreadyRegistered) return@use

            val activity = document.createElement("activity")
            activity.setAttribute("android:name", MORPHE_SETTINGS_ACTIVITY)
            activity.setAttribute("android:exported", "true")
            activity.setAttribute("android:label", "Настройки Morphe")
            activity.setAttribute("android:theme", "@style/Theme.Avito")
            application.appendChild(activity)
        }
    }
}

/**
 * "Morphe settings" — the host framework other Avito patches plug into. It adds a
 * single "Настройки Morphe" row to Avito's Settings screen that opens a generic,
 * code-built settings screen, and provides the runtime API
 * (`MorpheSettings.isEnabled`) feature patches gate their behaviour on.
 *
 * Feature patches `dependsOn(morpheSettingsPatch)` and register entries via
 * [MorpheSettingsRegistry] (`addSwitch` / `addScreen`); the registered set is
 * baked into `MorpheSettings.config()` at [finalize] time (after every feature's
 * `execute` has run).
 *
 * Avito has no androidx `PreferenceFragment`, so — unlike the YouTube settings
 * patch — this renders with a custom Activity + a JSON registry rather than
 * injected preference XML.
 */
@Suppress("unused")
val morpheSettingsPatch = bytecodePatch(
    name = "Morphe settings",
    description = "Adds a \"Настройки Morphe\" entry to Avito's settings that hosts the configuration " +
        "for the other Morphe patches.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    dependsOn(registerMorpheSettingsActivityPatch)
    extendWith("extensions/extension.mpe")

    execute {
        // Start from a clean registry so a reused Gradle daemon never carries
        // entries between builds. Feature patches (which dependsOn this) add their
        // entries in their own execute, which runs after this one.
        MorpheSettingsRegistry.reset()

        // The single "Настройки Morphe" row into Avito's settings list.
        val settingsBuilder = SettingsListBuilderFingerprint.methodOrNull
        if (settingsBuilder != null) {
            val instructions = settingsBuilder.instructionsOrNull?.toList().orEmpty()
            val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            if (returnIndex >= 0) {
                val listRegister = (instructions[returnIndex] as OneRegisterInstruction).registerA
                settingsBuilder.addInstructions(
                    returnIndex,
                    "invoke-static/range {v$listRegister .. v$listRegister}, " +
                        "$MORPHE_SETTINGS_CLASS->addSettingsEntry(Ljava/util/List;)V",
                )
                println("Morphe settings: added entry to Avito Settings")
            }
        } else {
            println("Morphe settings: settings list builder not found; entry skipped")
        }

        // Konveyor adapter-presenter bind hook → MorpheSettings.onBind, which wires
        // the Settings row click and delegates advert binds to the blacklist.
        // Matched STRUCTURALLY (R8 repackages konveyor per release): the
        // SimpleAdapterPresenter is the class that has both getItem(int)->ref (the
        // bound item; `getItem` keeps its name) and a bind method e(viewHolder,
        // int, List)V. getItem is called on the concrete class so no interface
        // name is needed.
        var bindHooks = 0
        classDefForEach { classDef ->
            val getItem = classDef.methods.firstOrNull { method ->
                method.name == "getItem" &&
                    method.parameterTypes.map { it.toString() } == listOf("I") &&
                    method.returnType.startsWith("L")
            } ?: return@classDefForEach

            val bind = classDef.methods.firstOrNull { method ->
                method.name == "e" &&
                    method.implementation != null &&
                    method.returnType == "V" &&
                    method.parameterTypes.map { it.toString() }.let { params ->
                        params.size == 3 &&
                            params[0].startsWith("L") &&
                            params[1] == "I" &&
                            params[2] == "Ljava/util/List;"
                    }
            } ?: return@classDefForEach

            mutableClassDefBy(classDef).methods
                .first { it.name == "e" && it.parameterTypes == bind.parameterTypes }
                .addInstructions(
                    0,
                    """
                        invoke-virtual {p0, p2}, ${classDef.type}->getItem(I)${getItem.returnType}
                        move-result-object v0
                        invoke-static {p1, v0}, $MORPHE_SETTINGS_CLASS->onBind(Ljava/lang/Object;Ljava/lang/Object;)V
                    """,
                )
            bindHooks++
        }
        println("Morphe settings: installed bind hook in $bindHooks adapter presenter(s)")
    }

    finalize {
        // Bake the registry (now populated by every feature patch's execute) into
        // MorpheSettings.config() by prepending a const-string return of the JSON.
        val json = MorpheSettingsRegistry.toJson()
        val smaliJson = json.replace("\\", "\\\\").replace("\"", "\\\"")
        val config = classDefByOrNull(MORPHE_SETTINGS_CLASS)
            ?.methods?.firstOrNull { it.name == "config" && it.parameterTypes.isEmpty() }
        if (config != null) {
            mutableClassDefBy(classDefByOrNull(MORPHE_SETTINGS_CLASS)!!).methods
                .first { it.name == "config" && it.parameterTypes.isEmpty() }
                .addInstructions(
                    0,
                    """
                        const-string v0, "$smaliJson"
                        return-object v0
                    """,
                )
            println("Morphe settings: baked config $json")
        } else {
            println("Morphe settings: MorpheSettings.config() not found; config not baked")
        }
    }
}
