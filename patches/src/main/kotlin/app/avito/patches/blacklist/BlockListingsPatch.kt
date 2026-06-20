package app.avito.patches.blacklist

import app.avito.patches.settings.MorpheSettingsRegistry
import app.avito.patches.settings.morpheSettingsPatch
import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val BLACKLIST_CLASS = "Lapp/avito/blacklist/Blacklist;"
private const val BLACKLIST_ACTIVITY = "app.avito.blacklist.BlacklistActivity"

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
    default = false,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    // morpheSettingsPatch provides the shared extension + the Settings host this
    // feature plugs into; registerBlacklistActivityPatch registers our own screen.
    dependsOn(morpheSettingsPatch, registerBlacklistActivityPatch)

    execute {
        val converter = SerpElementsConverterFingerprint.methodOrNull
            ?: throw PatchException("SERP elements converter was not found")

        // p1 is the first parameter: the List<SerpElement> to be converted.
        // Range form is used so the call is valid regardless of register count.
        converter.addInstructions(
            0,
            "invoke-static/range {p1 .. p1}, $BLACKLIST_CLASS->filterSerpElements(Ljava/util/List;)V",
        )
        println("Block listings: installed SERP feed filter in ${SerpElementsConverterFingerprint.originalClassDef.type}")

        // Register the blacklist manager as a sub-screen of Настройки Morphe.
        MorpheSettingsRegistry.addScreen("avito_blacklist", "Чёрный список", BLACKLIST_ACTIVITY)
    }
}
