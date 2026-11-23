package app.avito.patches.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

private const val LOCATION = "Lcom/avito/android/remote/model/Location;"

/**
 * Matches the Avito settings-screen list builder
 * (`com.avito.android.settings.mvi.m.a(...)`), which assembles the `ArrayList`
 * of settings rows. Identified by its return type, the stable `Location` model
 * parameter followed by two booleans, and the settings row key strings it
 * references. The patch appends a "Настройки Morphe" row to the returned list.
 *
 * Not pinned to a package: Avito 235.1 repackaged the builder into the default
 * package (`wif2`). The parameters after the two booleans are obfuscated and their
 * number varies (one object up to 237.5, `(L, Z, Boolean)` since 238.0), so the
 * shape is checked in `custom` rather than the fixed-arity `parameters` field.
 */
object SettingsListBuilderFingerprint : Fingerprint(
    returnType = "Ljava/util/ArrayList;",
    filters = listOf(
        string("notifications"),
        string("helpCenter"),
    ),
    custom = { method, _ ->
        method.parameterTypes.map { it.toString() }.let { params ->
            params.size >= 4 &&
                params[0] == LOCATION &&
                params[1] == "Z" &&
                params[2] == "Z"
        }
    },
)
