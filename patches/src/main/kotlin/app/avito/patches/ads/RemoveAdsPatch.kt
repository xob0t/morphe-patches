package app.avito.patches.ads

import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.shared.childrenNamed
import app.shared.getOrCreateApplicationMetaData
import app.shared.methodReferenceOrNull
import app.shared.removeChildren
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element
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

private const val NATIVE_VIDEO = "Lcom/avito/android/remote/model/NativeVideo;"
private const val VIDEO = "Lcom/avito/android/remote/model/Video;"
private const val AUTOTEKA_TEASER_RESULT = "Lcom/avito/android/remote/model/autotekateaser/AutotekaTeaserResult;"
private const val GALLERY_TEASER = "Lcom/avito/android/remote/model/model_card/GalleryTeaser;"
private const val LIST = "Ljava/util/List;"
private const val MAP = "Ljava/util/Map;"
private const val BOOLEAN = "Z"

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

private fun Method.isCarouselGalleryConverter(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "Ljava/util/ArrayList;" || implementation == null) {
        return false
    }

    val parameters = parameterTypes.map { it.toString() }
    val galleryTeaserIndex = parameters.indexOf(GALLERY_TEASER)
    if (galleryTeaserIndex < 0) return false

    return parameters.getOrNull(0) == NATIVE_VIDEO &&
        parameters.getOrNull(1) == VIDEO &&
        parameters.getOrNull(2) == AUTOTEKA_TEASER_RESULT &&
        parameters.lastOrNull() == BOOLEAN &&
        parameters.drop(galleryTeaserIndex + 1).contains(MAP)
}

private fun Method.galleryTeaserParameterIndexes(): List<Int> {
    val parameters = parameterTypes.map { it.toString() }
    val galleryTeaserIndex = parameters.indexOf(GALLERY_TEASER)
    if (galleryTeaserIndex < 0) return emptyList()

    val extraTeaserListIndexes = parameters
        .withIndex()
        .drop(galleryTeaserIndex + 1)
        .takeWhile { it.value != MAP }
        .filter { it.value == LIST }
        // The first three post-gallery lists are image/realty image inputs.
        .drop(3)
        .map { it.index }

    return listOf(galleryTeaserIndex) + extraTeaserListIndexes
}

private fun nullParametersInstructions(parameterIndexes: List<Int>) =
    buildString {
        appendLine("const/4 v0, 0x0")
        parameterIndexes.forEach { index ->
            appendLine("move-object/from16 p$index, v0")
        }
    }

private fun MethodReference.isRxThrowableObservableFactory() =
    definingClass == "Lio/reactivex/rxjava3/core/z;" &&
        parameterTypes.map { it.toString() } == listOf("Ljava/lang/Throwable;") &&
        returnType.startsWith("Lio/reactivex/rxjava3/")

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

        // Every ad/banner surface is mandatory: if an expected layout (or the ad
        // element inside it) is gone, the app changed and the patch is stale, so we
        // fail loudly instead of silently shipping ads. Missing surfaces are collected
        // and reported together. (Older builds that lack some surfaces are out of
        // scope — we only patch current releases.)
        val missing = mutableListOf<String>()
        var hiddenLayouts = 0

        (hiddenRewardLayouts + hiddenAdLayouts).forEach { path ->
            try {
                document(path).use { document ->
                    document.documentElement.hideView()
                }
                hiddenLayouts++
            } catch (_: FileNotFoundException) {
                missing += path
            }
        }

        var hiddenHomeBannerLayoutsCount = 0
        hiddenHomeBannerLayouts.forEach { path ->
            try {
                document(path).use { document ->
                    val root = document.documentElement
                    val hidden = if (root.nodeName == "merge") {
                        val banners = root.childrenNamed(
                            "androidx.constraintlayout.widget.ConstraintLayout",
                            "com.google.android.material.appbar.CollapsingToolbarLayout",
                        )
                        banners.forEach(Element::hideView)
                        banners.isNotEmpty()
                    } else {
                        root.hideView()
                        true
                    }
                    if (hidden) hiddenHomeBannerLayoutsCount++ else missing += "$path (no banner view)"
                }
            } catch (_: FileNotFoundException) {
                missing += path
            }
        }

        try {
            document("res/layout/bx_content_fragment.xml").use { document ->
                val shadows = document.documentElement.childrenNamed("FrameLayout")
                    .filter { it.getAttribute("android:id") == "@id/hero_banner_shadow" }
                shadows.forEach(Element::hideView)
                if (shadows.isNotEmpty()) {
                    hiddenHomeBannerLayoutsCount++
                } else {
                    missing += "res/layout/bx_content_fragment.xml (@id/hero_banner_shadow)"
                }
            }
        } catch (_: FileNotFoundException) {
            missing += "res/layout/bx_content_fragment.xml"
        }

        if (missing.isNotEmpty()) {
            throw PatchException(
                "Remove ads: ${missing.size} expected ad/banner surface(s) not found — " +
                    "the app layout changed, update RemoveAdsPatch: ${missing.joinToString(", ")}",
            )
        }

        println(
            "Remove ads: hid $hiddenLayouts ad/reward layouts and " +
                "$hiddenHomeBannerLayoutsCount home banner layouts (all required surfaces present).",
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
        // Every ad surface is mandatory: ad entry points are core to the app, so a
        // fingerprint that no longer resolves means the app changed and the patch is
        // stale — fail loudly instead of silently shipping ads. (Older builds missing
        // a surface are out of scope; we only patch current releases.)

        // Home hero banner widget: null the converter so the widget never builds.
        HeroBannerWidgetConverterFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )

        // Hero banner toolbar config: return null so no banner toolbar is shown.
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
                method.isCarouselGalleryConverter()
            } ?: return@classDefForEach
            val teaserParameterIndexes = converterMethod.galleryTeaserParameterIndexes()
            if (teaserParameterIndexes.isEmpty()) return@classDefForEach

            mutableClassDefBy(classDef).methods
                .single { method -> method.name == converterMethod.name && method.parameterTypes == converterMethod.parameterTypes }
                .addInstructions(
                    0,
                    nullParametersInstructions(teaserParameterIndexes),
                )
            galleryTeaserConvertersPatched++
        }
        if (galleryTeaserConvertersPatched == 0) {
            throw PatchException(
                "Remove ads: no gallery Beduin teaser converter found — the converter " +
                    "signature changed, update isCarouselGalleryConverter() for this version.",
            )
        }

        // Commercial banner loader: emit an Rx error instead of loading a banner.
        val commercialBannerLoaderMethod = CommercialBannerLoaderErrorFingerprint.method
        val rxErrorFactory = commercialBannerLoaderMethod.instructionsOrNull
            ?.firstNotNullOfOrNull { instruction ->
                if (instruction.opcode !in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)) {
                    return@firstNotNullOfOrNull null
                }

                instruction.methodReferenceOrNull()
                    ?.takeIf { it.isRxThrowableObservableFactory() }
            }
            ?: throw PatchException(
                "Remove ads: commercial banner loader matched but its Rx error factory " +
                    "was not found — update CommercialBannerLoaderErrorFingerprint for this version.",
            )
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

        println("Remove ads: patched 3 banner surface(s) and $galleryTeaserConvertersPatched gallery Beduin teaser converter(s) (all required).")
    }
}
