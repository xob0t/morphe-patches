package app.avito.patches.blacklist

import app.morphe.patcher.Fingerprint

private const val SERP_DISPLAY_TYPE = "Lcom/avito/android/remote/model/SerpDisplayType;"
private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"
private const val ADVERT_DETAILS = "Lcom/avito/android/remote/model/AdvertDetails;"
private const val ADVERT_DETAILS_STYLE = "Lcom/avito/android/advert_details/AdvertDetailsStyle;"
private const val EXTENDED_PROFILE = "Lcom/avito/android/remote/model/ExtendedProfile;"

/**
 * Matches the seller-profile (ExtendedProfile) header converter — the method that
 * turns the loaded profile into the screen's UI, receiving the deep-link
 * `userKey`/`context` strings and the full `ExtendedProfile`. We hook its entry to
 * add a "block seller" action to the profile toolbar.
 *
 * Identified by its distinctive shape — two `String`s followed by an
 * `ExtendedProfile` — within the (stable) `extended_profile/converter` package, so
 * it survives the per-release minification of the class/method names.
 */
object SellerProfileConverterFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/extended_profile/converter/",
    parameters = listOf(STRING, STRING, EXTENDED_PROFILE),
    // Only the concrete converter implementation — not an abstract declaration with
    // the same signature (which has no body to patch).
    custom = { method, _ -> method.implementation != null },
)

/**
 * Matches `AdvertDetailsToolbarPresenter`'s navbar-setup method, which builds the
 * advert-detail toolbar (and inflates its menu) and receives the full
 * `AdvertDetails`. We hook its entry to add block-offer / block-seller actions.
 *
 * Identified purely by its distinctive, stable parameter shape — an
 * `AdvertDetailsStyle`, an `AdvertDetails`, a `String` and a `boolean`, returning
 * void — so it survives the per-release minification of the class/method names.
 * Both model/style types keep their real names across releases.
 */
object AdvertDetailsToolbarMenuFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        ADVERT_DETAILS_STYLE,
        ADVERT_DETAILS,
        STRING,
        "Z",
    ),
    // Only the concrete presenter implementation — not the abstract interface
    // declaration with the same signature (which has no body to patch).
    custom = { method, _ -> method.implementation != null },
)

/**
 * Matches the SERP element converter that turns a list of network
 * `SerpElement`s into the adapter item list rendered in search results.
 *
 * This is the sole concrete implementation of the converter interface
 * (`U0`/`T0` in 226.5, `P0`/`O0` in 221.0), invoked by the parallel
 * `convertParallel` path. The method name and class are obfuscated and the
 * parameter count varies between releases:
 *
 * - 221.0: `(List, SerpDisplayType, String, String, boolean, List) -> ArrayList`
 * - 226.5: `(List, SerpDisplayType, String, String, boolean, boolean, List, int) -> ArrayList`
 *
 * The match therefore relies only on the stable core shared by both: a concrete
 * method in `serp/adapter` returning `ArrayList`, taking a `List` then a
 * (non-obfuscated) `SerpDisplayType`, two `String`s, and at least one more
 * `List` afterwards. `SerpDisplayType` makes this highly distinctive — it is the
 * only `serp/adapter` method with this shape.
 */
object SerpElementsConverterFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/serp/adapter/",
    returnType = "Ljava/util/ArrayList;",
    // Variable-arity shape (param count differs across releases), so the parameter
    // constraints stay here rather than in the fixed-arity `parameters` field.
    custom = { method, _ ->
        method.implementation != null &&
            method.parameterTypes.map { it.toString() }.let { params ->
                params.size >= 5 &&
                    params[0] == LIST &&
                    params[1] == SERP_DISPLAY_TYPE &&
                    params[2] == STRING &&
                    params[3] == STRING &&
                    params.drop(4).contains(LIST)
            }
    },
)
