package app.avito.patches.ui

import app.avito.patches.settings.MorpheSettingsRegistry
import app.avito.patches.settings.morpheSettingsPatch
import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import app.shared.*
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val NAVIGATION_TAB = "Lcom/avito/android/bottom_navigation/NavigationTab;"
private const val BOTTOM_NAVIGATION_SPACE = "Lcom/avito/android/bottom_navigation/space/BottomNavigationSpace;"
private const val MORPHE_SETTINGS_CLASS = "Lapp/avito/morphe/MorpheSettings;"
private const val ADVERT_DETAILS = "Lcom/avito/android/remote/model/AdvertDetails;"
private const val CREDIT_BROKER_PRODUCT = "Lcom/avito/android/remote/model/credit_broker/CreditBrokerProduct;"
private const val ICE_BREAKERS = "Lcom/avito/android/remote/model/IceBreakers;"
private const val VISUAL_RUBRICATOR_ELEMENT =
    "Lcom/avito/android/visual_rubricator/element/VisualRubricatorWidgetElementItemImpl;"
private const val INTEGER = "Ljava/lang/Integer;"

private val AVI_TAB_NAMES = setOf("AI_ASSISTANT", "AI_ASSISTANT_SELLER")

private fun Method.usesBottomNavigationSpace() =
    parameterTypes.any { it.toString() == BOTTOM_NAVIGATION_SPACE }

private fun Method.hasFieldReference(fields: Set<String>): Boolean =
    instructionsOrNull?.any { instruction ->
        val reference = instruction.fieldReferenceOrNull() ?: return@any false
        reference.definingClass == NAVIGATION_TAB && reference.name in fields
    } == true

/**
 * A collection of optional interface tweaks, each gated by its own toggle in
 * Настройки Morphe so it can be turned off without rebuilding:
 *
 *  - **Force home categories into a single row.**
 *  - **Hide the "Подписки" tab** on the Избранное (Favorites) screen.
 *  - **Hide the installments (Рассрочка)** surfaces and the **"Спросите у
 *    продавца"** block on offer pages.
 *  - **Hide the Avi assistant tab** in the bottom navigation bar.
 *
 * Each tweak is applied independently and degrades gracefully: if the target
 * isn't present on a given release the tweak (and its toggle) is simply skipped,
 * and the patch never aborts the build.
 */
@Suppress("unused")
val uiTweaksPatch = bytecodePatch(
    name = "UI tweaks",
    description = "Optional interface tweaks, each toggleable in Настройки Morphe: single-row home " +
        "categories, hide the \"Подписки\" tab in Избранное, hide installments (Рассрочка) and the " +
        "\"Спросите у продавца\" block on offers, and hide the Avi assistant tab in the bottom navigation.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    dependsOn(morpheSettingsPatch)

    execute {
        // --- Force the home-screen categories into a single row -----------------
        // The DoubleRows visual rubricator routes each tile to row_first/row_second
        // by its getRowLine(); when the toggle is on, make every tile report row 1
        // so the second row collapses and all categories land in one scrollable row.
        // The class/method keep their real names across 213–227.
        val rubricatorElement = classDefByOrNull(VISUAL_RUBRICATOR_ELEMENT)
        val getRowLine = rubricatorElement?.methods?.firstOrNull {
            it.name == "getRowLine" && it.parameterTypes.isEmpty() && it.returnType == INTEGER
        }
        if (rubricatorElement == null || getRowLine == null) {
            println("UI tweaks: rubricator getRowLine() not found; single-row categories skipped")
        } else {
            val rowLineMethod = mutableClassDefBy(rubricatorElement).methods
                .single { it.name == getRowLine.name && it.parameterTypes == getRowLine.parameterTypes }
            rowLineMethod.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $MORPHE_SETTINGS_CLASS->singleRowCategories()Z
                    move-result v0
                    if-eqz v0, :stock
                    const/4 v0, 0x1
                    invoke-static {v0}, $INTEGER->valueOf(I)$INTEGER
                    move-result-object v0
                    return-object v0
                """,
                ExternalLabel("stock", rowLineMethod.getInstruction(0)),
            )
            // Restart-required: the rubricator items are diffed and won't rebind on a
            // simple settings round-trip.
            MorpheSettingsRegistry.addSwitch(
                key = "avito_single_row_categories",
                title = "Категории в одну строку",
                summary = "Показывать категории на главной одной строкой",
                default = true,
                restartRequired = true,
            )
            println("UI tweaks: gated single-row home categories behind the toggle.")
        }

        // --- Hide the "Подписки" (subscribed sellers) tab in Избранное ----------
        // Replace the tab list at the entry of the presenter method that consumes
        // it and builds the tab strip (the active path; the obvious builder A.a is
        // bypassed by a feature flag). withoutSubscriptionsTab returns a copy with
        // the SellersTab dropped when the toggle is on. Re-evaluated each time the
        // Favorites screen opens, so no restart is required.
        val tabConsumer = FavoritesTabsConsumerFingerprint.methodOrNull
        if (tabConsumer == null) {
            println("UI tweaks: Favorites tab consumer not found; subscriptions tab skipped")
        } else {
            // p1 is the List<FavoritesTab>; swap it for the filtered copy.
            tabConsumer.addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $MORPHE_SETTINGS_CLASS->withoutSubscriptionsTab(Ljava/util/List;)Ljava/util/List;
                    move-result-object p1
                """,
            )
            MorpheSettingsRegistry.addSwitch(
                key = "avito_hide_subscriptions_tab",
                title = "Скрыть вкладку «Подписки»",
                summary = "Убрать вкладку подписок на экране Избранное",
                default = true,
            )
            println(
                "UI tweaks: gated the Favorites subscriptions tab in " +
                    "${FavoritesTabsConsumerFingerprint.originalClassDef.type}->${tabConsumer.name}",
            )
        }

        // --- Hide offer-page blocks by nulling their AdvertDetails source -------
        // Each of these blocks reads a single nullable AdvertDetails getter; routing
        // that getter's return through a null-gate makes every consumer natively
        // render nothing (the offer-page block, plus any other surface that reads
        // the same field). One early hook beats per-component patches.
        //
        // Local helper so the AdvertDetails class is resolved once and the
        // null-gate injection (before each return) isn't duplicated per block.
        val advertDetailsClass = classDefByOrNull(ADVERT_DETAILS)
        fun gateAdvertDetailsGetter(
            getterName: String,
            returnType: String,
            gateMethod: String,
            key: String,
            title: String,
            summary: String,
        ) {
            val getter = advertDetailsClass?.methods?.firstOrNull {
                it.name == getterName && it.parameterTypes.isEmpty()
            }
            if (advertDetailsClass == null || getter == null) {
                println("UI tweaks: AdvertDetails.$getterName not found; $key skipped")
                return
            }
            val method = mutableClassDefBy(advertDetailsClass).methods.first {
                it.name == getterName && it.parameterTypes.isEmpty()
            }
            val returnIndices = method.instructionsOrNull
                ?.toList().orEmpty()
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_OBJECT) index else null
                }
                .reversed()
            for (returnIndex in returnIndices) {
                val register =
                    (method.instructionsOrNull!!.toList()[returnIndex] as OneRegisterInstruction).registerA
                method.addInstructions(
                    returnIndex,
                    """
                        invoke-static/range { v$register .. v$register }, $MORPHE_SETTINGS_CLASS->$gateMethod(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$register
                        check-cast v$register, $returnType
                    """,
                )
            }
            MorpheSettingsRegistry.addSwitch(key = key, title = title, summary = summary, default = true)
            println("UI tweaks: gated AdvertDetails.$getterName behind $key (${returnIndices.size} returns).")
        }

        // Рассрочка (installments): block on the offer page + row in the buy bar.
        gateAdvertDetailsGetter(
            getterName = "getCreditInfo",
            returnType = CREDIT_BROKER_PRODUCT,
            gateMethod = "creditInfoOrNull",
            key = "avito_hide_installments",
            title = "Скрыть рассрочку",
            summary = "Убрать рассрочку со страниц объявлений",
        )

        // "Спросите у продавца" (icebreakers): the suggested-questions block.
        gateAdvertDetailsGetter(
            getterName = "getIcebreakers",
            returnType = ICE_BREAKERS,
            gateMethod = "icebreakersOrNull",
            key = "avito_hide_ask_seller",
            title = "Скрыть «Спросите у продавца»",
            summary = "Убрать блок с вопросами продавцу",
        )

        // --- Hide the Avi assistant tab in the bottom navigation ----------------
        // The Avi tab doesn't exist on every release (e.g. 227.0); when absent the
        // tweak and its toggle are skipped. Route the tab's field loads through
        // aviTabOrNull so the toggle controls whether it's dropped (null) or kept.
        val navigationTabClass = classDefByOrNull(NAVIGATION_TAB)
        val aiTabFields = navigationTabClass?.methods
            ?.firstOrNull { it.name == "<clinit>" }
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

        if (aiTabFields.isEmpty()) {
            println("UI tweaks: no Avi tab on this version; Avi tab skipped")
            return@execute
        }

        var patchedReferences = 0

        classDefForEach { classDef ->
            // No package filter: the nav builder is repackaged differently per
            // release (`com/avito/android/bottom_navigation/...` on older builds vs
            // `qr/y` on 227.0). The structural signature — a method taking a
            // BottomNavigationSpace and reading the Avi NavigationTab fields — is
            // distinctive; the cheap param check short-circuits the scan.
            if (classDef.methods.none { it.usesBottomNavigationSpace() && it.hasFieldReference(aiTabFields) }) {
                return@classDefForEach
            }

            mutableClassDefBy(classDef).methods.forEach { method ->
                if (!method.usesBottomNavigationSpace()) return@forEach

                val instructions = method.instructionsOrNull?.toList() ?: return@forEach
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
            println("UI tweaks: no bottom-nav references on this version; Avi tab skipped")
            return@execute
        }

        // Restart-required: the bottom nav is assembled once at startup.
        MorpheSettingsRegistry.addSwitch(
            key = "avito_hide_avi_tab",
            title = "Скрыть вкладку Avi",
            summary = "Убрать кнопку ИИ-ассистента из нижней навигации",
            default = true,
            restartRequired = true,
        )
        println("UI tweaks: gated $patchedReferences Avi tab references behind the toggle.")
    }
}
