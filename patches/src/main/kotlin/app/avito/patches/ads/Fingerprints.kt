package app.avito.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

object CommercialBannerLoaderErrorFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/advertising/loaders/",
    returnType = "Lio/reactivex/rxjava3/core/z;",
    filters = listOf(
        string("Not supported SerpBanner type: "),
    ),
)

/**
 * Matches the home hero-banner widget converter: the method that turns the network
 * `HeroBannerWidget` model into the rendered widget item. Nulling it stops the
 * widget from ever building.
 *
 * Identified structurally so it survives per-release minification. Avito 228.0
 * minified `HeroBannerWidgetItem`, so the return type is constrained to the stable
 * widget package instead of the old readable class name.
 */
object HeroBannerWidgetConverterFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/hero_banner/widget/",
    parameters = listOf(
        "Lcom/avito/android/remote/model/serp/HeroBannerWidget;",
    ),
    custom = { method, _ ->
        method.implementation != null &&
            method.returnType.startsWith("Lcom/avito/android/hero_banner/widget/") &&
            method.returnType != "V"
    },
)

object HeroBannerToolbarConfigFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/remote/model/serp/HeroBannerWidget;",
    name = "getToolbarConfig",
    returnType = "Lcom/avito/android/remote/model/ToolbarConfig;",
    parameters = emptyList(),
)
