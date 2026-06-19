package app.avito.patches.blacklist

import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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
 * opened from the in-app Settings entry (no launcher icon). Declared `exported`
 * so it can also be opened directly for testing via `adb shell am start`.
 */
private val registerBlacklistActivityPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_AVITO)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val application = manifest.childrenNamed("application").single()

            val alreadyRegistered = application.childrenNamed("activity").any {
                it.getAttribute("android:name") == BLACKLIST_ACTIVITY
            }
            if (alreadyRegistered) return@use

            val activity = document.createElement("activity")
            activity.setAttribute("android:name", BLACKLIST_ACTIVITY)
            activity.setAttribute("android:exported", "true")
            activity.setAttribute("android:label", "Чёрный список")
            // Use the app's own theme so the screen follows Avito's colours and
            // light/dark appearance.
            activity.setAttribute("android:theme", "@style/Theme.Avito")

            application.appendChild(activity)
        }
    }
}

/**
 * Hides classifieds (offers) in Avito search feeds whose advert id or seller
 * `userKey` is on the user's blacklist, and adds an in-app screen to manage and
 * import/export that blacklist.
 *
 * The feed filter runs inside the obfuscated SERP element converter
 * ([SerpElementsConverterFingerprint]): the input `List<SerpElement>` is passed
 * to `Blacklist.filterSerpElements`, which removes blocked adverts in place
 * before they are converted into adapter items.
 */
@Suppress("unused")
val blockListingsPatch = bytecodePatch(
    name = "Block listings",
    description = "Hides Avito offers from blacklisted adverts or sellers and adds a blacklist manager " +
        "(import/export compatible with the Ave Blacklist extension).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    dependsOn(registerBlacklistActivityPatch)
    extendWith("extensions/extension.mpe")

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

        // Long-press to block: hook the konveyor adapter-presenter bind
        // `<SimpleAdapterPresenter>.e(viewHolder, position, payloads)` — the
        // dispatch every Avito list bind funnels through (the SERP's
        // GridLayoutAppendingAdapter -> ListRecyclerAdapter -> this). The
        // extension attaches a long-press handler + collapses blocked advert tiles.
        //
        // Matched STRUCTURALLY, not by name: R8 repackages the konveyor classes
        // differently per release (`com/avito/konveyor/adapter/{i,h,b}` on <=226.5,
        // `xt/a` + `sl1/{b,g}` on 227.0), but the SimpleAdapterPresenter always has
        // both a `getItem(int)` returning a reference (the bound item; `getItem`
        // keeps its name across obfuscation) and a bind method `e(viewHolder, int,
        // List)V` on the same class. getItem is called directly on the concrete
        // class so no interface name is needed. A miss must never abort the feed
        // filter above.
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
                        invoke-static {p1, v0}, $BLACKLIST_CLASS->onBindAdvert(Ljava/lang/Object;Ljava/lang/Object;)V
                    """,
                )
            bindHooks++
            println("Block listings: hooked adapter presenter ${classDef.type}")
        }
        println("Block listings: installed long-press block handler in $bindHooks adapter presenter(s)")

        // Add a "Чёрный список" row to the app's Settings screen. Inject a call to
        // the extension at the settings-list builder's return so the row is
        // appended to the returned ArrayList. Its click is wired by the bind hook
        // above (the row carries the marker id). Best-effort: a miss here must not
        // abort the rest of the patch.
        val settingsBuilder = SettingsListBuilderFingerprint.methodOrNull
        if (settingsBuilder != null) {
            val instructions = settingsBuilder.instructionsOrNull?.toList().orEmpty()
            val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            if (returnIndex >= 0) {
                val listRegister = (instructions[returnIndex] as OneRegisterInstruction).registerA
                settingsBuilder.addInstructions(
                    returnIndex,
                    "invoke-static/range {v$listRegister .. v$listRegister}, " +
                        "$BLACKLIST_CLASS->addSettingsEntry(Ljava/util/List;)V",
                )
                println("Block listings: added blacklist entry to Settings")
            }
        } else {
            println("Block listings: settings list builder not found; Settings entry skipped")
        }
    }
}
