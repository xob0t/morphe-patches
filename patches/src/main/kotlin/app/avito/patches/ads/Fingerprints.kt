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
 * Matches the home hero-banner widget converter — the method that turns the network
 * `HeroBannerWidget` model into the rendered `HeroBannerWidgetItem`. Nulling it stops
 * the widget from ever building.
 *
 * Identified structurally so it survives the per-release minification of the
 * class/method names: a concrete method in the (stable) `hero_banner/widget/`
 * package taking a `HeroBannerWidget` and returning a `HeroBannerWidgetItem`. Both
 * model types keep their real names, and that exact shape is unique to the
 * converter — the previous pin to the obfuscated class `e`/method `a` rolled on
 * every R8 build.
 */
object HeroBannerWidgetConverterFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/hero_banner/widget/",
    returnType = "Lcom/avito/android/hero_banner/widget/HeroBannerWidgetItem;",
    parameters = listOf(
        "Lcom/avito/android/remote/model/serp/HeroBannerWidget;",
    ),
    custom = { method, _ -> method.implementation != null },
)

object HeroBannerToolbarConfigFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/remote/model/serp/HeroBannerWidget;",
    name = "getToolbarConfig",
    returnType = "Lcom/avito/android/remote/model/ToolbarConfig;",
    parameters = emptyList(),
)
