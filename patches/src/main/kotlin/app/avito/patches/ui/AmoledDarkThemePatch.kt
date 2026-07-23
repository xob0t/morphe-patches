package app.avito.patches.ui

import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.io.FileNotFoundException

private val DARK_COLOR_FILES = listOf(
    // Newer RUIKit screens reference dark_gray_white directly from the base
    // palette instead of going through a values-night semantic colour.
    "res/values/colors.xml",
    "res/values-night/colors.xml",
    "res/values-night-v29/colors.xml",
)

// "Which Avito dark-theme colours are flat-black backgrounds."
//
// A colour counts as a flattenable background only when BOTH hold: its dark value
// is one of Avito's neutral dark tiers and its name identifies a page or navigation
// background. Elevated cards, sheets, list items and control fills deliberately stay
// gray so their boundaries remain visible against the black page.
private val AMOLED_SURFACE_NIGHT_RGB = setOf("0a0a0a", "191919", "1f1e1d", "252525", "262624")

private const val AMOLED_PURE_BLACK = "#ff000000"

/** Normalises a colour literal to its lower-case 6-digit RGB (drops a leading ff alpha). */
private fun amoledRgbOf(value: String): String {
    var s = value.trim().removePrefix("#").lowercase()
    if (s.length == 8 && s.startsWith("ff")) s = s.substring(2)
    return s
}

private fun isAmoledSurfaceColorName(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith("white") ||                 // page background (white / *_white / common_white)
        n.endsWith("old_background") ||           // legacy screen background
        n == "tab_bar_background" ||
        // Navigation chrome (#191919): the bottom nav/tab bar (ru_bg_elevation2),
        // bottom action panel, floating contact actions and the SERP filter toolbar.
        n.contains("bg_elevation") ||
        n.contains("bottom_action_panel") ||
        n.contains("floating_contact_actions") ||
        n.endsWith("toolbar_background")
}

/**
 * Makes Avito's dark theme AMOLED-friendly by rewriting page and navigation
 * backgrounds to #000000. Elevated cards, sheets, list items and control fills remain
 * gray so their boundaries stay readable. Dark mode only — the light theme is
 * untouched — and borders, dividers, branded banner/promo fills and disabled states
 * are left alone. Baked into the resources at build time (opt-in via patch selection).
 */
@Suppress("unused")
val amoledDarkThemePatch = resourcePatch(
    name = "AMOLED dark theme",
    description = "Makes dark-theme page and navigation backgrounds pure black (AMOLED) while keeping " +
        "elevated cards, sheets and controls gray so their boundaries remain visible.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_AVITO)

    execute {
        var changed = 0

        DARK_COLOR_FILES.forEach { path ->
            try {
                document(path).use { document ->
                    val nodes = document.documentElement.childNodes
                    for (i in 0 until nodes.length) {
                        val node = nodes.item(i)
                        if (node !is Element || node.nodeName != "color") continue
                        if (amoledRgbOf(node.textContent) !in AMOLED_SURFACE_NIGHT_RGB) continue
                        if (!isAmoledSurfaceColorName(node.getAttribute("name"))) continue

                        node.textContent = AMOLED_PURE_BLACK
                        changed++
                    }
                }
            } catch (_: FileNotFoundException) {
                // This colour file doesn't exist on this build; skip it.
            }
        }

        println("AMOLED dark theme: blackened $changed dark-mode page/navigation background colour(s).")
    }
}
