package app.avito.patches.ui

import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val ELEMENT_IMPL =
    "Lcom/avito/android/visual_rubricator/element/VisualRubricatorWidgetElementItemImpl;"
private const val INTEGER = "Ljava/lang/Integer;"

// The home-screen category rubricator (the "DoubleRows" visual rubricator) places
// each category tile into row_first or row_second purely by the tile's own
// getRowLine(): the view adds tiles with rowLine == 2 to the second row and
// everything else to the first. So if every tile reports row 1, the second row
// stays empty (the view sets it GONE) and all categories land in one horizontally
// scrollable row — Avito's own tiles and styling, just collapsed to a single line.
//
// VisualRubricatorWidgetElementItemImpl and getRowLine() both keep their real
// (non-minified) names across releases, so this stays robust; the patch verifies
// the method is present and skips cleanly otherwise.
@Suppress("unused")
val singleRowCategoriesPatch = bytecodePatch(
    name = "Single-row home categories",
    description = "Collapses the home-screen category rubricator from two rows into a " +
        "single horizontally scrollable row.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_AVITO)

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

        // Return Integer.valueOf(1) for every tile. getRowLine() is `.locals 1`
        // (v0 = its backing field), so reusing v0 needs no extra registers; the
        // original body becomes unreachable.
        mutableClassDefBy(elementClass).methods
            .single { it.name == getRowLine.name && it.parameterTypes == getRowLine.parameterTypes }
            .addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    invoke-static {v0}, $INTEGER->valueOf(I)$INTEGER
                    move-result-object v0
                    return-object v0
                """,
            )

        println("Single-row categories: forced all category tiles to a single row.")
    }
}
