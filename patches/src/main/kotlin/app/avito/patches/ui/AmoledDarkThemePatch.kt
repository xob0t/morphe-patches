package app.avito.patches.ui

import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.io.FileNotFoundException

private val NIGHT_COLOR_FILES = listOf(
    "res/values-night/colors.xml",
    "res/values-night-v29/colors.xml",
)

// "Which Avito dark-theme colours are flat-black surfaces."
//
// A colour counts as a flattenable surface only when BOTH hold: its night value is
// one of the neutral dark tiers (page bg #0a0a0a, elevated nav/panel #191919, warm
// search/tab-bar #1f1e1d/#262624, card/sheet #252525) AND its name is a recognised
// background/surface token. The value gate keeps us off the lighter greys (#454545+:
// dividers/icons/text); the name gate keeps us off non-surface colours that share
// those values (branded banner/promo/gradient fills, a border, a map pin, disabled
// button fills).
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
        n == "gray4" || n == "gray2" || n == "warmgray4" || n == "warmgray2" ||
        n.endsWith("_gray_4") || n.endsWith("_gray_2") ||   // gray/warm-gray surface tiers (not _44/_48)
        n.contains("floating_card_background") ||
        n.contains("material_card_background") ||
        n.contains("gray_plain") ||
        n == "tab_bar_background" ||
        (n.contains("bottom_sheet") && n.contains("bg")) ||
        // Elevated surfaces (#191919): the bottom nav/tab bar (ru_bg_elevation2),
        // bottom action panel, floating contact actions, the SERP filter toolbar and
        // advert-detail list items — all sit on the page and read "too bright" if left.
        n.contains("bg_elevation") ||
        n.contains("bottom_action_panel") ||
        n.contains("floating_contact_actions") ||
        n.endsWith("toolbar_background") ||
        (n.contains("basic_info") && n.contains("item_background"))
}

/**
 * Makes Avito's dark theme a fully-flat pure-black AMOLED theme: rewrites every
 * dark-mode background **and** surface/card/sheet/bar colour to #000000, so the whole
 * UI is true OLED black (deeper black, pixels off, some battery saving). Dark mode
 * only — the light theme is untouched — and borders, dividers, branded banner/promo
 * fills and disabled states are left alone so the UI stays readable. Baked into the
 * resources at build time (opt-in via patch selection); there is no runtime toggle,
 * because a theme overlay can only reach ?attr/ surfaces, not the many direct @color
 * and drawable fills the dark UI uses — only rewriting the colour values covers them.
 */
@Suppress("unused")
val amoledDarkThemePatch = resourcePatch(
    name = "AMOLED dark theme",
    description = "Makes the dark theme fully pure-black (AMOLED): backgrounds and surfaces/cards/bars " +
        "become #000000. Dark mode only; borders, dividers and branded fills are kept for readability.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_AVITO)

    execute {
        var changed = 0

        NIGHT_COLOR_FILES.forEach { path ->
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
                // This night-colour file doesn't exist on this build; skip it.
            }
        }

        println("AMOLED dark theme: blackened $changed dark-mode background/surface colour(s).")
    }
}
