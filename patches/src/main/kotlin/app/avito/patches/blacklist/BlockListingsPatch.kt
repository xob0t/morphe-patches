package app.avito.patches.blacklist

import app.avito.patches.settings.MorpheSettingsRegistry
import app.avito.patches.settings.morpheSettingsPatch
import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.shared.childrenNamed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Element

private const val BLACKLIST_CLASS = "Lapp/avito/blacklist/Blacklist;"
private const val BLOCK_MENU_CLASS = "Lapp/avito/morphe/MorpheBlockMenu;"
private const val BLACKLIST_ACTIVITY = "app.avito.blacklist.BlacklistActivity"

/**
 * Registers the self-contained blacklist management screen
 * ([BLACKLIST_ACTIVITY], provided by the extension) in the app manifest. It is
 * opened from the "Чёрный список" row inside the Morphe settings screen. Declared
 * `exported` so it can also be opened directly for testing via
 * `adb shell am start`.
 */
private val registerBlacklistActivityPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_AVITO)

    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.childrenNamed("application").single()
            val alreadyRegistered = application.childrenNamed("activity").any {
                it.getAttribute("android:name") == BLACKLIST_ACTIVITY
            }
            if (alreadyRegistered) return@use

            val activity = document.createElement("activity")
            activity.setAttribute("android:name", BLACKLIST_ACTIVITY)
            activity.setAttribute("android:exported", "true")
            activity.setAttribute("android:label", "Чёрный список")
            activity.setAttribute("android:theme", "@style/Theme.Avito")
            application.appendChild(activity)
        }
    }
}

/**
 * Hides classifieds (offers) in Avito search feeds whose advert id or seller
 * `userKey` is on the user's blacklist, and contributes a "Чёрный список" sub-screen
 * to the Morphe settings host to manage and import/export that blacklist.
 *
 * The feed filter runs inside the obfuscated SERP element converter
 * ([SerpElementsConverterFingerprint]): the input `List<SerpElement>` is passed
 * to `Blacklist.filterSerpElements`, which removes blocked adverts in place before
 * they are converted into adapter items. The Settings entry, its click, and the
 * long-press bind hook live in [morpheSettingsPatch] (this patch's `onBindAdvert`
 * is called from there).
 */
@Suppress("unused")
val blockListingsPatch = bytecodePatch(
    name = "Block listings",
    description = "Hides Avito offers from blacklisted adverts or sellers and adds a blacklist manager " +
        "(import/export compatible with the Ave Blacklist extension).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    // morpheSettingsPatch provides the shared extension + the Settings host this
    // feature plugs into; registerBlacklistActivityPatch registers our own screen.
    dependsOn(morpheSettingsPatch, registerBlacklistActivityPatch)

    execute {
        val converter = SerpElementsConverterFingerprint.methodOrNull
            ?: throw PatchException("SERP elements converter was not found")

        // Two-stage feed sanitization in the obfuscated converter:
        //
        // 1) INPUT: remove blocked network SerpElements up front (p1, the
        //    List<SerpElement>). Cheap, and it captures readable labels for the
        //    blacklist manager. Range form so it's valid regardless of register count.
        // 2) OUTPUT: just before the method returns its ArrayList of adapter items,
        //    remove any blocked AdvertItem that the input pass missed. The input
        //    getters (network model) don't cover every feed — notably the home grid
        //    — so this output pass, which uses the same robust id resolution as the
        //    long-press bind, is what guarantees blocked items never reach the grid
        //    (no leftover gaps) across both search and home.
        converter.addInstructions(
            0,
            "invoke-static/range {p1 .. p1}, $BLACKLIST_CLASS->filterSerpElements(Ljava/util/List;)V",
        )

        // Inject before every return-object (descending, so earlier indices stay
        // valid) — the returned register holds the ArrayList of items.
        val returnIndices = converter.instructionsOrNull
            ?.toList().orEmpty()
            .mapIndexedNotNull { index, instruction ->
                if (instruction.opcode == Opcode.RETURN_OBJECT) index else null
            }
            .reversed()
        for (returnIndex in returnIndices) {
            val itemsRegister =
                (converter.instructionsOrNull!!.toList()[returnIndex] as OneRegisterInstruction).registerA
            converter.addInstructions(
                returnIndex,
                "invoke-static/range {v$itemsRegister .. v$itemsRegister}, " +
                    "$BLACKLIST_CLASS->filterAdvertItems(Ljava/util/List;)V",
            )
        }
        println(
            "Block listings: installed SERP feed filter (in+out, ${returnIndices.size} returns) " +
                "in ${SerpElementsConverterFingerprint.originalClassDef.type}",
        )

        // Add block-offer / block-seller actions to the advert-detail toolbar. The
        // presenter setup method gets the AdvertDetails and builds the toolbar, so we
        // pass it (p2) and the presenter (p0) to the extension. Optional: skip if the
        // method isn't present on this build instead of aborting.
        val toolbar = AdvertDetailsToolbarMenuFingerprint.methodOrNull
        if (toolbar != null) {
            toolbar.addInstructions(
                0,
                "invoke-static/range {p0 .. p2}, " +
                    "$BLACKLIST_CLASS->onAdvertToolbar(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
            )
            println("Block listings: added block actions to the advert toolbar")
        } else {
            println("Block listings: advert toolbar presenter not found on this build; skipped detail buttons")
        }

        // Add a "block seller" action to the seller-profile toolbar. The profile
        // header converter receives the deep-link strings (one is the userKey) and
        // the ExtendedProfile model; pass p1..p3 to the extension. Optional.
        val sellerConverter = SellerProfileConverterFingerprint.methodOrNull
        if (sellerConverter != null) {
            sellerConverter.addInstructions(
                0,
                "invoke-static/range {p1 .. p3}, " +
                    "$BLACKLIST_CLASS->onSellerToolbar(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V",
            )
            println("Block listings: added block action to the seller profile toolbar")
        } else {
            println("Block listings: seller profile converter not found on this build; skipped seller button")
        }

        // Register the blacklist manager as a sub-screen of Настройки Morphe.
        MorpheSettingsRegistry.addScreen("avito_blacklist", "Чёрный список", BLACKLIST_ACTIVITY)
    }
}
