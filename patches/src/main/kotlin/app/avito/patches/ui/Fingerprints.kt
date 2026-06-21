package app.avito.patches.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess

/**
 * Matches the Favorites presenter method that consumes the assembled tab list and
 * populates the (legacy) tab strip — `user_favorites/O.b(List)` on 227.0.
 *
 * This is the point the active screen actually goes through (the obvious builder
 * `A.a` is bypassed by a feature flag), so hooking here drops the subscriptions
 * tab regardless of how the list was built. Identified by its stable shape: a
 * `void` method in `com.avito.android.user_favorites` taking a single `List` whose
 * body reads the (non-obfuscated) `UserFavoritesTabsRenderMode` enum — unique to
 * this method.
 */
object FavoritesTabsConsumerFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/user_favorites/",
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    // The method reads UserFavoritesTabsRenderMode enum values; match a field access
    // of that (non-obfuscated) type rather than scanning every instruction's reference.
    filters = listOf(
        fieldAccess(type = "Lcom/avito/android/user_favorites/UserFavoritesTabsRenderMode;"),
    ),
)
