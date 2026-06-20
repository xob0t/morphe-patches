package app.avito.patches.ui

import app.avito.patches.settings.MorpheSettingsRegistry
import app.avito.patches.settings.morpheSettingsPatch
import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val NAVIGATION_TAB = "Lcom/avito/android/bottom_navigation/NavigationTab;"
private const val BOTTOM_NAVIGATION_SPACE = "Lcom/avito/android/bottom_navigation/space/BottomNavigationSpace;"
private const val MORPHE_SETTINGS_CLASS = "Lapp/avito/morphe/MorpheSettings;"

private val AVI_TAB_NAMES = setOf("AI_ASSISTANT", "AI_ASSISTANT_SELLER")

private fun Instruction.fieldReferenceOrNull(): FieldReference? =
    (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.stringReferenceOrNull(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Method.usesBottomNavigationSpace() =
    parameterTypes.any { it.toString() == BOTTOM_NAVIGATION_SPACE }

private fun Method.hasFieldReference(fields: Set<String>): Boolean =
    instructionsOrNull?.any { instruction ->
        val reference = instruction.fieldReferenceOrNull() ?: return@any false
        reference.definingClass == NAVIGATION_TAB && reference.name in fields
    } == true

@Suppress("unused")
val hideAviBottomTabPatch = bytecodePatch(
    name = "Hide Avi bottom tab",
    description = "Removes the Avi assistant button from Avito's bottom navigation bar. " +
        "Toggleable in Настройки Morphe (needs an app restart to apply).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    dependsOn(morpheSettingsPatch)

    execute {
        val navigationTabClass = classDefByOrNull(NAVIGATION_TAB)
            ?: throw PatchException("NavigationTab class was not found")

        val aiTabFields = navigationTabClass.methods
            .firstOrNull { it.name == "<clinit>" }
            ?.let { method ->
                val instructions = method.implementation?.instructions?.toList().orEmpty()
                buildSet {
                    instructions.forEachIndexed { index, instruction ->
                        if (instruction.stringReferenceOrNull() !in AVI_TAB_NAMES) return@forEachIndexed

                        instructions
                            .drop(index + 1)
                            .take(16)
                            .firstNotNullOfOrNull { candidate ->
                                val reference = candidate.fieldReferenceOrNull() ?: return@firstNotNullOfOrNull null
                                reference.takeIf {
                                    candidate.opcode == Opcode.SPUT_OBJECT &&
                                        it.definingClass == NAVIGATION_TAB &&
                                        it.type == NAVIGATION_TAB
                                }?.name
                            }
                            ?.let(::add)
                    }
                }
            }
            .orEmpty()

        // The Avi tab doesn't exist on every release (e.g. 227.0's bottom nav). If
        // its fields/references aren't present, skip gracefully and don't register
        // a toggle that wouldn't do anything — never abort the build.
        if (aiTabFields.isEmpty()) {
            println("Hide Avi bottom tab: no Avi tab on this version; skipped")
            return@execute
        }

        var patchedReferences = 0

        classDefForEach { classDef ->
            // No package filter: the nav builder is repackaged differently per
            // release (`com/avito/android/bottom_navigation/...` on older builds vs
            // `qr/y` on 227.0). The structural signature — a method that takes a
            // BottomNavigationSpace and reads the Avi NavigationTab fields — is
            // distinctive enough on its own. usesBottomNavigationSpace() (cheap
            // param check) short-circuits before the instruction scan, so iterating
            // all classes stays fast.
            if (classDef.methods.none { it.usesBottomNavigationSpace() && it.hasFieldReference(aiTabFields) }) {
                return@classDefForEach
            }

            mutableClassDefBy(classDef).methods.forEach { method ->
                if (!method.usesBottomNavigationSpace()) return@forEach

                val instructions = method.instructionsOrNull?.toList() ?: return@forEach
                // Collect the Avi-tab field LOADS (sget-object): keep them, but route
                // the loaded value through MorpheSettings.aviTabOrNull so the toggle
                // controls whether the tab is dropped (null) or kept.
                val targets = buildList {
                    instructions.forEachIndexed { index, instruction ->
                        if (instruction.opcode != Opcode.SGET_OBJECT) return@forEachIndexed
                        val reference = instruction.fieldReferenceOrNull() ?: return@forEachIndexed
                        if (reference.definingClass != NAVIGATION_TAB || reference.name !in aiTabFields) {
                            return@forEachIndexed
                        }
                        val register = (instruction as? OneRegisterInstruction)?.registerA
                            ?: return@forEachIndexed
                        add(index to register)
                    }
                }

                // Inject after each load, highest index first so earlier indices stay valid.
                targets.sortedByDescending { it.first }.forEach { (index, register) ->
                    val invoke = if (register <= 15) {
                        "invoke-static {v$register}"
                    } else {
                        "invoke-static/range {v$register .. v$register}"
                    }
                    method.addInstructions(
                        index + 1,
                        """
                            $invoke, $MORPHE_SETTINGS_CLASS->aviTabOrNull(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$register
                            check-cast v$register, $NAVIGATION_TAB
                        """,
                    )
                    patchedReferences++
                }
            }
        }

        if (patchedReferences == 0) {
            println("Hide Avi bottom tab: no bottom-nav references on this version; skipped")
            return@execute
        }

        // Only offer the toggle when we actually gated the tab.
        // Restart-required: the bottom nav is assembled once at startup.
        MorpheSettingsRegistry.addSwitch(
            key = "avito_hide_avi_tab",
            title = "Скрыть вкладку Avi",
            summary = "Убрать кнопку ИИ-ассистента из нижней навигации",
            default = true,
            restartRequired = true,
        )
        println("Hide Avi bottom tab: gated $patchedReferences Avi tab references behind the toggle.")
    }
}
