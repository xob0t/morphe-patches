package app.avito.patches.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string

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

/**
 * Cross-version fallback for builds without the 227 `UserFavoritesTabsRenderMode`
 * consumer (e.g. 226.5). Matches the `UserFavoritesChanges(tabs, hasP2PAccess)` data
 * class by its non-obfuscated Kotlin `toString` marker — the one place every favorites
 * builder funnels the assembled `List<FavoritesTab>` through. The patch then hooks that
 * class's `(List, boolean)` constructor to drop the subscriptions tab, so it works
 * regardless of which builder path is active.
 */
object FavoritesChangesFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/user_favorites/",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(
        string("UserFavoritesChanges(tabs="),
    ),
)

/**
 * Matches `ExpandablePanelLayout.setCollapsedLineCount(Integer)` — the single setter
 * every "Читать далее" description block funnels its collapsed-line threshold through
 * (the offer description, plus hotel/gig/own-advert/branding variants). Forcing that
 * threshold high makes the text render in full and keeps the read-more handle hidden.
 *
 * The class name is kept (the widget is inflated from layout XML, so R8 can't rename
 * it), and the `(Integer)V` signature is unique within the class — so we match
 * structurally and don't rely on the method name surviving minification.
 */
object ExpandablePanelCollapsedLinesFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/util/ExpandablePanelLayout;",
    returnType = "V",
    parameters = listOf("Ljava/lang/Integer;"),
)
