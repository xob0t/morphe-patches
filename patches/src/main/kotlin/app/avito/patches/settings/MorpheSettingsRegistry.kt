package app.avito.patches.settings

/**
 * Build-time registry of Morphe settings entries. Feature patches call
 * [addSwitch] / [addScreen] in their `execute` block (after `dependsOn`ing the
 * [morpheSettingsPatch]); the settings patch serialises everything collected here
 * into the extension at `finalize` time (see [toJson]).
 *
 * Mirrors the ReVanced/De-Vanced `preferenceScreen.addPreferences(...)` shape,
 * adapted to Avito's code-built settings screen (literal titles instead of string
 * resources, since Avito has no PreferenceFragment to render preference XML).
 */
object MorpheSettingsRegistry {

    /** A registered settings entry. */
    sealed interface Entry {
        val key: String
        val title: String
    }

    /** A runtime toggle, read by feature code via `MorpheSettings.isEnabled(key, default)`. */
    data class Switch(
        override val key: String,
        override val title: String,
        val summary: String? = null,
        val default: Boolean = true,
        /** Whether changing it needs an app restart to take effect (e.g. UI built
         *  once at startup). The settings screen then offers to restart. */
        val restartRequired: Boolean = false,
    ) : Entry

    /** A row that opens another Activity (e.g. the blacklist manager). */
    data class Screen(
        override val key: String,
        override val title: String,
        val activity: String,
    ) : Entry

    private val entries = linkedMapOf<String, Entry>()

    /** Cleared by the settings patch before feature patches register, so a reused
     *  Gradle daemon never carries stale entries between builds. */
    fun reset() = entries.clear()

    fun addSwitch(
        key: String,
        title: String,
        summary: String? = null,
        default: Boolean = true,
        restartRequired: Boolean = false,
    ) {
        entries[key] = Switch(key, title, summary, default, restartRequired)
    }

    fun addScreen(key: String, title: String, activity: String) {
        entries[key] = Screen(key, title, activity)
    }

    /** Serialises the registered entries to the JSON `MorpheSettings.config()` returns. */
    fun toJson(): String = entries.values.joinToString(",", "[", "]") { entry ->
        when (entry) {
            is Switch -> buildString {
                append("{\"type\":\"switch\",\"key\":").append(jsonString(entry.key))
                append(",\"title\":").append(jsonString(entry.title))
                if (entry.summary != null) append(",\"summary\":").append(jsonString(entry.summary))
                append(",\"default\":").append(entry.default)
                if (entry.restartRequired) append(",\"restart\":true")
                append("}")
            }
            is Screen -> buildString {
                append("{\"type\":\"screen\",\"key\":").append(jsonString(entry.key))
                append(",\"title\":").append(jsonString(entry.title))
                append(",\"activity\":").append(jsonString(entry.activity)).append("}")
            }
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}
