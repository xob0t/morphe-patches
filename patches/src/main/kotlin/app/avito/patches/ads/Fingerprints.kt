package app.avito.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

private const val HERO_BANNER_WIDGET = "Lcom/avito/android/remote/model/serp/HeroBannerWidget;"

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
 * Identified by stable model calls so it survives per-release minification. Avito
 * 230.5 obfuscated both the converter and its return type, while the input model and
 * its getters remained stable. Requiring the getter calls also excludes the fake
 * converter, which has the same signature but immediately returns null.
 */
object HeroBannerWidgetConverterFingerprint : Fingerprint(
    parameters = listOf(
        HERO_BANNER_WIDGET,
    ),
    filters = listOf(
        methodCall(
            definingClass = HERO_BANNER_WIDGET,
            name = "getTitle",
        ),
        methodCall(
            definingClass = HERO_BANNER_WIDGET,
            name = "getToolbarConfig",
        ),
    ),
    custom = { method, _ ->
        method.implementation != null &&
            method.returnType.startsWith("L")
    },
)

object HeroBannerToolbarConfigFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/remote/model/serp/HeroBannerWidget;",
    name = "getToolbarConfig",
    returnType = "Lcom/avito/android/remote/model/ToolbarConfig;",
    parameters = emptyList(),
)
