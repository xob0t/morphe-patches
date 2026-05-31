package app.avito.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

private fun isCommercialBannerLoader(classType: String) =
    classType.startsWith("Lcom/avito/android/advertising/loaders/")

object CommercialBannerLoaderErrorFingerprint : Fingerprint(
    returnType = "Lio/reactivex/rxjava3/core/z;",
    filters = listOf(
        string("Not supported SerpBanner type: "),
    ),
    custom = { _, classDef ->
        isCommercialBannerLoader(classDef.type)
    },
)

object HeroBannerWidgetConverterFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/hero_banner/widget/e;",
    returnType = "Lcom/avito/android/hero_banner/widget/HeroBannerWidgetItem;",
    parameters = listOf(
        "Lcom/avito/android/remote/model/serp/HeroBannerWidget;",
    ),
    custom = { method, _ -> method.name == "a" },
)

object HeroBannerToolbarConfigFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/remote/model/serp/HeroBannerWidget;",
    returnType = "Lcom/avito/android/remote/model/ToolbarConfig;",
    parameters = emptyList(),
    custom = { method, _ -> method.name == "getToolbarConfig" },
)
