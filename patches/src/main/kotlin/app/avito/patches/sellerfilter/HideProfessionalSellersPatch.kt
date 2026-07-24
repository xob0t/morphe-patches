package app.avito.patches.sellerfilter

import app.avito.patches.blacklist.SerpElementsConverterFingerprint
import app.avito.patches.settings.morpheSettingsPatch
import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SELLER_FILTER_CLASS =
    "Lapp/avito/sellerfilter/ProfessionalSellerFilter;"

/**
 * Adds local maximum-seller-reviews controls to Avito's native Filters screen
 * and either removes matching adverts or tints their bound tiles.
 */
@Suppress("unused")
val hideProfessionalSellersPatch = bytecodePatch(
    name = "Hide professional sellers",
    description = "Adds a maximum seller review count to Avito search filters and hides or " +
        "dims offers from sellers above that limit.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    dependsOn(morpheSettingsPatch)

    execute {
        val converter = SerpElementsConverterFingerprint.methodOrNull
            ?: throw PatchException("SERP elements converter was not found")
        converter.addInstructions(
            0,
            "invoke-static/range {p1 .. p1}, " +
                "$SELLER_FILTER_CLASS->filterSerpElements(Ljava/util/List;)Ljava/util/List;\n" +
                "move-result-object p1",
        )

        val returnIndices = converter.instructionsOrNull
            ?.toList().orEmpty()
            .mapIndexedNotNull { index, instruction ->
                if (instruction.opcode == Opcode.RETURN_OBJECT) index else null
            }
            .reversed()
        for (returnIndex in returnIndices) {
            val itemsRegister =
                (converter.instructionsOrNull!!.toList()[returnIndex] as OneRegisterInstruction)
                    .registerA
            converter.addInstructions(
                returnIndex,
                "invoke-static/range {v$itemsRegister .. v$itemsRegister}, " +
                    "$SELLER_FILTER_CLASS->filterAdvertItems(Ljava/util/List;)V",
            )
        }

        var installedUiHooks = 0
        WidgetFiltersActivityOnCreateFingerprint.methodOrNull?.let { filtersOnCreate ->
            val returnIndices = filtersOnCreate.instructionsOrNull
                ?.toList().orEmpty()
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_VOID) index else null
                }
                .reversed()
            for (returnIndex in returnIndices) {
                filtersOnCreate.addInstructions(
                    returnIndex,
                    "invoke-static/range {p0 .. p0}, " +
                        "$SELLER_FILTER_CLASS->attachToFilterActivity(Landroid/app/Activity;)V",
                )
            }
            installedUiHooks += returnIndices.size
        }

        HomeActivityOnCreateFingerprint.methodOrNull?.let { homeOnCreate ->
            val returnIndices = homeOnCreate.instructionsOrNull
                ?.toList().orEmpty()
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_VOID) index else null
                }
                .reversed()
            for (returnIndex in returnIndices) {
                homeOnCreate.addInstructions(
                    returnIndex,
                    "invoke-static/range {p0 .. p0}, " +
                        "$SELLER_FILTER_CLASS->observeHostActivity(Landroid/app/Activity;)V",
                )
            }
            installedUiHooks += returnIndices.size
        }
        if (installedUiHooks == 0) {
            throw PatchException("No Avito Filters UI host was found")
        }

        println(
            "Hide professional sellers: installed SERP filter, tint binder, and native controls " +
                "(${returnIndices.size} converter returns, $installedUiHooks activity returns)",
        )
    }
}
