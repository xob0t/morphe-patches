package app.avito.patches.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

private val SETTINGS_LIST_BUILDER_PARAMETER_PREFIX = listOf(
    "Lcom/avito/android/remote/model/Location;",
    "Z",
    "Z",
)

/**
 * Matches the Avito settings-screen list builder
 * (`com.avito.android.settings.mvi.m.a(...)`), which assembles the `ArrayList`
 * of settings rows. Identified by its return type, the stable `Location` model
 * parameter, and the settings row key strings it references. The patch appends a
 * "Настройки Morphe" row to the returned list.
 *
 * Not pinned to a package: Avito 235.1 repackaged the builder into the default
 * package (`wif2`). Only the leading `(Location, Z, Z, ...)` parameters are
 * pinned: 238.0 appended `Z` and `Boolean` flags after the obfuscated toggle
 * parameter, and later releases may add more.
 */
object SettingsListBuilderFingerprint : Fingerprint(
    returnType = "Ljava/util/ArrayList;",
    filters = listOf(
        string("notifications"),
        string("helpCenter"),
    ),
    custom = { method, _ ->
        val parameters = method.parameterTypes.map { it.toString() }
        parameters.size >= 4 &&
            parameters.subList(0, SETTINGS_LIST_BUILDER_PARAMETER_PREFIX.size) ==
            SETTINGS_LIST_BUILDER_PARAMETER_PREFIX
    },
)
