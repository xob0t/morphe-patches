package app.avito.patches.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * Matches the Avito settings-screen list builder
 * (`com.avito.android.settings.mvi.m.a(...)`), which assembles the `ArrayList`
 * of settings rows. Identified by its return type, the stable `Location` model
 * parameter, and the settings row key strings it references. The patch appends a
 * "Настройки Morphe" row to the returned list.
 *
 * Not pinned to a package: Avito 235.1 repackaged the builder into the default
 * package (`wif2`). The toggle parameter is obfuscated, so it is matched as any
 * object type.
 */
object SettingsListBuilderFingerprint : Fingerprint(
    returnType = "Ljava/util/ArrayList;",
    parameters = listOf(
        "Lcom/avito/android/remote/model/Location;",
        "Z",
        "Z",
        "L",
    ),
    filters = listOf(
        string("notifications"),
        string("helpCenter"),
    ),
)
