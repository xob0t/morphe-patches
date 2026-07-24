package app.avito.patches.sellerfilter

import app.morphe.patcher.Fingerprint

/**
 * Stable, non-obfuscated host for Avito's full search Filters screen.
 */
object WidgetFiltersActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/widget_filters/WidgetFiltersActivity;",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { method, _ ->
        method.name == "onCreate" && method.implementation != null
    },
)

/**
 * Main host for the newer embedded Filters fragment used from Avito's home SERP.
 */
object HomeActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/home/HomeActivity;",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { method, _ ->
        method.name == "onCreate" && method.implementation != null
    },
)
