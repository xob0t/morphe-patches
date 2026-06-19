package app.avito.patches.blacklist

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

private const val SERP_DISPLAY_TYPE = "Lcom/avito/android/remote/model/SerpDisplayType;"
private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"

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
    returnType = "Ljava/util/ArrayList;",
    custom = { method, classDef ->
        method.implementation != null &&
            classDef.type.startsWith("Lcom/avito/android/serp/adapter/") &&
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

/**
 * Matches the Avito settings-screen list builder
 * (`com.avito.android.settings.mvi.m.a(...)`), which assembles the `ArrayList`
 * of settings rows. Identified by its return type and the stable settings row
 * key strings it references. The patch appends a "Чёрный список" row to the
 * returned list.
 */
object SettingsListBuilderFingerprint : Fingerprint(
    returnType = "Ljava/util/ArrayList;",
    filters = listOf(
        string("notifications"),
        string("helpCenter"),
    ),
    custom = { _, classDef ->
        classDef.type.startsWith("Lcom/avito/android/settings/")
    },
)
