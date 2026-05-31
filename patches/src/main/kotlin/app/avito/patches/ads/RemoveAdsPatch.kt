package app.avito.patches.ads

import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.FileNotFoundException

private val adPermissions = setOf(
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
)

private val adComponents = setOf(
    "com.yandex.mobile.ads.common.AdActivity",
    "com.yandex.mobile.ads.core.initializer.MobileAdsInitializeProvider",
    "com.my.target.common.MyTargetActivity",
    "com.my.target.common.MyTargetContentProvider",
)

private val adProperties = setOf(
    "android.adservices.AD_SERVICES_CONFIG",
)

private val adMetaDataDefaults = mapOf(
    "google_analytics_adid_collection_enabled" to "false",
)

private val carouselGalleryConverterParameterTypes = listOf(
    "Lcom/avito/android/remote/model/NativeVideo;",
    "Lcom/avito/android/remote/model/Video;",
    "Lcom/avito/android/remote/model/autotekateaser/AutotekaTeaserResult;",
    "Lcom/avito/android/remote/model/model_card/GalleryTeaser;",
    "Ljava/util/List;",
    "Ljava/util/List;",
    "Ljava/util/List;",
    "Ljava/util/List;",
    "Ljava/util/Map;",
    "Z",
)

private val hiddenRewardLayouts = listOf(
    "res/layout/item_rewards.xml",
    "res/layout/reward_bug_entry_floating_view.xml",
)

private val hiddenAdLayouts = listOf(
    "res/layout/ad_avito.xml",
    "res/layout/ad_avito_network_bdui.xml",
    "res/layout/ad_avito_network_avl_bdui.xml",
    "res/layout/yandex_ad.xml",
    "res/layout/yandex_list_ad.xml",
    "res/layout/yandex_avl_ad.xml",
    "res/layout/my_target_ad.xml",
    "res/layout/my_target_list_ad.xml",
)

private val hiddenHomeBannerLayouts = listOf(
    "res/layout/hero_banner.xml",
    "res/layout/hero_banner_toolbar.xml",
    "res/layout/main_promo_banner_toolbar.xml",
)

private fun Instruction.methodReferenceOrNull(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.isRxThrowableObservableFactory() =
    definingClass == "Lio/reactivex/rxjava3/core/z;" &&
        parameterTypes.map { it.toString() } == listOf("Ljava/lang/Throwable;") &&
        returnType.startsWith("Lio/reactivex/rxjava3/")

private fun Element.childrenNamed(name: String): List<Element> {
    val nodes = childNodes
    return buildList {
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.nodeName == name) add(node)
        }
    }
}

private fun Element.childrenNamed(vararg names: String): List<Element> {
    val acceptedNames = names.toSet()
    val nodes = childNodes
    return buildList {
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.nodeName in acceptedNames) add(node)
        }
    }
}

private fun Element.removeChildren(nodes: List<Node>) {
    nodes.forEach(::removeChild)
}

private fun Element.hideView() {
    setAttribute("android:visibility", "gone")
    setAttribute("android:layout_width", "0dp")
    setAttribute("android:layout_height", "0dp")
    setAttribute("android:layout_margin", "0dp")
    setAttribute("android:layout_marginTop", "0dp")
    setAttribute("android:layout_marginBottom", "0dp")
    setAttribute("android:layout_marginStart", "0dp")
    setAttribute("android:layout_marginEnd", "0dp")
    setAttribute("android:clickable", "false")
    setAttribute("android:focusable", "false")
    setAttribute("android:importantForAccessibility", "no")
}

private fun Element.getOrCreateApplicationMetaData(name: String): Element {
    childrenNamed("meta-data")
        .firstOrNull { it.getAttribute("android:name") == name }
        ?.let { return it }

    val metaData = ownerDocument.createElement("meta-data")
    metaData.setAttribute("android:name", name)
    appendChild(metaData)
    return metaData
}

private val removeAdResourcesPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_AVITO)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val application = manifest.childrenNamed("application").single()

            manifest.removeChildren(
                manifest.childrenNamed("uses-permission")
                    .filter { it.getAttribute("android:name") in adPermissions }
            )

            application.removeChildren(
                application.childrenNamed("property")
                    .filter { it.getAttribute("android:name") in adProperties }
            )

            listOf("activity", "provider", "service", "receiver").forEach { tag ->
                application.childrenNamed(tag)
                    .filter { it.getAttribute("android:name") in adComponents }
                    .forEach { component ->
                        component.setAttribute("android:enabled", "false")
                        component.setAttribute("android:exported", "false")
                    }
            }

            adMetaDataDefaults.forEach { (name, value) ->
                application.getOrCreateApplicationMetaData(name)
                    .setAttribute("android:value", value)
            }
        }

        var hiddenLayouts = 0
        var missingLayouts = 0

        (hiddenRewardLayouts + hiddenAdLayouts).forEach { path ->
            try {
                document(path).use { document ->
                    document.documentElement.hideView()
                }
                hiddenLayouts++
            } catch (_: FileNotFoundException) {
                missingLayouts++
            }
        }

        var hiddenHomeBannerLayoutsCount = 0
        hiddenHomeBannerLayouts.forEach { path ->
            try {
                document(path).use { document ->
                    val root = document.documentElement
                    if (root.nodeName == "merge") {
                        root.childrenNamed(
                            "androidx.constraintlayout.widget.ConstraintLayout",
                            "com.google.android.material.appbar.CollapsingToolbarLayout",
                        ).forEach(Element::hideView)
                    } else {
                        root.hideView()
                    }
                }
                hiddenHomeBannerLayoutsCount++
            } catch (_: FileNotFoundException) {
                missingLayouts++
            }
        }

        try {
            document("res/layout/bx_content_fragment.xml").use { document ->
                document.documentElement.childrenNamed("FrameLayout")
                    .filter { it.getAttribute("android:id") == "@id/hero_banner_shadow" }
                    .forEach(Element::hideView)
            }
            hiddenHomeBannerLayoutsCount++
        } catch (_: FileNotFoundException) {
            missingLayouts++
        }

        println(
            "Remove ads: hid $hiddenLayouts ad/reward layouts and " +
                "$hiddenHomeBannerLayoutsCount home banner layouts, skipped $missingLayouts missing layouts.",
        )
    }
}

@Suppress("unused")
val removeAdsPatch = bytecodePatch(
    name = "Remove ads",
    description = "Disables Avito ads by removing ad SDK entry points and short-circuiting commercial banner loading.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    dependsOn(removeAdResourcesPatch)

    execute {
        HeroBannerWidgetConverterFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )

        HeroBannerToolbarConfigFingerprint.method.addInstructions(
            0,
            """
                const/4 p0, 0x0
                return-object p0
            """,
        )

        var galleryTeaserConvertersPatched = 0
        classDefForEach { classDef ->
            val converterMethod = classDef.methods.singleOrNull { method ->
                AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.returnType == "Ljava/util/ArrayList;" &&
                    method.parameterTypes.map { it.toString() } == carouselGalleryConverterParameterTypes &&
                    method.implementation != null
            } ?: return@classDefForEach

            mutableClassDefBy(classDef).methods
                .single { method -> method.name == converterMethod.name && method.parameterTypes == converterMethod.parameterTypes }
                .addInstructions(
                    0,
                    """
                        const/4 v0, 0x0
                        move-object/from16 p7, v0
                    """,
                )
            galleryTeaserConvertersPatched++
        }
        if (galleryTeaserConvertersPatched == 0) {
            throw PatchException("Carousel gallery Beduin teaser converter was not found")
        }

        val commercialBannerLoaderMethod = CommercialBannerLoaderErrorFingerprint.method
        val rxErrorFactory = commercialBannerLoaderMethod.instructionsOrNull
            ?.firstNotNullOfOrNull { instruction ->
                if (instruction.opcode !in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)) {
                    return@firstNotNullOfOrNull null
                }

                instruction.methodReferenceOrNull()
                    ?.takeIf { it.isRxThrowableObservableFactory() }
            }
            ?: throw PatchException("Commercial banner loader RxJava error factory was not found")

        commercialBannerLoaderMethod.addInstructions(
            0,
            """
                new-instance v0, Ljava/lang/RuntimeException;
                const-string v1, "Avito ads disabled"
                invoke-direct {v0, v1}, Ljava/lang/RuntimeException;-><init>(Ljava/lang/String;)V
                invoke-static {v0}, ${rxErrorFactory.definingClass}->${rxErrorFactory.name}(Ljava/lang/Throwable;)${rxErrorFactory.returnType}
                move-result-object v0
                return-object v0
            """,
        )

        println("Remove ads: disabled home hero banner, hero banner toolbar config, and $galleryTeaserConvertersPatched gallery Beduin teaser converter(s).")
    }
}
