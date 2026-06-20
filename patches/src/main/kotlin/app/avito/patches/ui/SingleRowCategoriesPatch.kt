package app.avito.patches.ui

import app.avito.patches.settings.MorpheSettingsRegistry
import app.avito.patches.settings.morpheSettingsPatch
import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

private const val ELEMENT_IMPL =
    "Lcom/avito/android/visual_rubricator/element/VisualRubricatorWidgetElementItemImpl;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val MORPHE_SETTINGS_CLASS = "Lapp/avito/morphe/MorpheSettings;"

// The home-screen category rubricator (the "DoubleRows" visual rubricator) places
// each tile into row_first or row_second purely by the tile's own getRowLine():
// the view sends tiles with rowLine == 2 to the second row and everything else to
// the first. So if every tile reports row 1, the second row stays empty (the view
// sets it GONE) and all categories land in one horizontally scrollable row.
//
// We gate that on a runtime toggle: while it's on, getRowLine() returns 1;
// otherwise it falls through to the stock value. VisualRubricatorWidgetElementItemImpl
// and getRowLine() keep their real (non-minified) names across releases (verified on
// 213.0–227.0), so this stays robust; the patch skips cleanly if absent.
@Suppress("unused")
val singleRowCategoriesPatch = bytecodePatch(
    name = "Single-row home categories",
    description = "Collapses the home-screen category rubricator from two rows into a " +
        "single horizontally scrollable row. Toggleable in Настройки Morphe (needs an " +
        "app restart to apply).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    dependsOn(morpheSettingsPatch)

    execute {
        val elementClass = classDefByOrNull(ELEMENT_IMPL) ?: run {
            println("Single-row categories: rubricator element class not found; skipped")
            return@execute
        }
        val getRowLine = elementClass.methods.firstOrNull {
            it.name == "getRowLine" && it.parameterTypes.isEmpty() && it.returnType == INTEGER
        } ?: run {
            println("Single-row categories: getRowLine() not found; skipped")
            return@execute
        }

        // While the toggle is on, return Integer.valueOf(1); otherwise fall through to
        // the stock body. getRowLine() is `.locals 1` (v0), and the no-arg gate needs
        // no extra registers.
        val method = mutableClassDefBy(elementClass).methods
            .single { it.name == getRowLine.name && it.parameterTypes == getRowLine.parameterTypes }
        method.addInstructionsWithLabels(
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
            ExternalLabel("stock", method.getInstruction(0)),
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
        println("Single-row categories: gated category rubricator behind the toggle.")
    }
}
