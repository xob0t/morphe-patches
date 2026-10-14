package app.avito.patches.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * Matches the Avito settings-screen list builder
 * (`com.avito.android.settings.mvi.m.a(...)`), which assembles the `ArrayList`
 * of settings rows. Identified by its return type and the stable settings row
 * key strings it references. The patch appends a "Настройки Morphe" row to the
 * returned list.
 */
object SettingsListBuilderFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/settings/",
    returnType = "Ljava/util/ArrayList;",
    filters = listOf(
        string("notifications"),
        string("helpCenter"),
    ),
)
