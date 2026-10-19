package app.avito.patches.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal const val VISUAL_RUBRICATOR_ITEM_MARKER = "VisualRubricatorWidgetElementItemImpl(stringId="
internal const val ROW_LINE_MARKER = ", rowLine="

object VisualRubricatorElementFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(string(VISUAL_RUBRICATOR_ITEM_MARKER)),
)

/**
 * Matches the Favorites presenter method that consumes the assembled tab list and
 * populates the (legacy) tab strip — `user_favorites/O.b(List)` on 227.0.
 *
 * This is the point the active screen actually goes through (the obvious builder
 * `A.a` is bypassed by a feature flag), so hooking here drops the subscriptions
 * tab regardless of how the list was built. Identified by its stable shape: a
 * `void` method taking a single `List` whose body reads the (non-obfuscated)
 * `UserFavoritesTabsRenderMode` enum — unique to this method. Not pinned to
 * `user_favorites`: Avito 235.1 repackaged the presenter into the default package.
 */
object FavoritesTabsConsumerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    // The method reads UserFavoritesTabsRenderMode enum values; match a field access
    // of that (non-obfuscated) type rather than scanning every instruction's reference.
    filters = listOf(
        fieldAccess(type = "Lcom/avito/android/user_favorites/UserFavoritesTabsRenderMode;"),
    ),
)

object FavoritesTabsControlMapperFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = "Lcom/avito/android/user_favorites/tabs_control/",
            parameters = listOf("I", "Ljava/util/List;"),
            returnType = "Lcom/avito/android/user_favorites/tabs_control/",
            opcodes = listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE),
        ),
        opcode(
            Opcode.MOVE_RESULT_OBJECT,
            InstructionLocation.MatchAfterImmediately(),
        ),
    ),
)

/**
 * Cross-version fallback for builds without the 227 `UserFavoritesTabsRenderMode`
 * consumer (e.g. 226.5). Matches the `UserFavoritesChanges(tabs, hasP2PAccess)` data
 * class by its non-obfuscated Kotlin `toString` marker — the one place every favorites
 * builder funnels the assembled `List<FavoritesTab>` through. The patch then hooks that
 * class's `(List, boolean)` constructor to drop the subscriptions tab, so it works
 * regardless of which builder path is active. The `toString` marker is unique, so
 * the class is not pinned to a package (Avito 235.1 moved it to the default package).
 */
object FavoritesChangesFingerprint : Fingerprint(
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

/**
 * Matches the advert-details complementary-section loader that fetches and emits
 * the complete "Рекомендации" block (title, filter chips and advert cards).
 *
 * The implementation class is minified. Its load method takes the current
 * AdvertDetails and starts a coroutine; the sibling setter with the same parameter
 * does not. Avito 235.1 repackaged both the presenter (out of `advert/item/similars`)
 * and kotlinx.coroutines' `launch` holder (`k` became `ank`), so the presenter is
 * matched by package or preserved source file and `launch` as a static `d(...)` call.
 */
object OfferRecommendationsLoadFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/avito/android/remote/model/AdvertDetails;"),
    filters = listOf(
        methodCall(
            name = "d",
            opcodes = listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE),
        ),
    ),
    custom = { _, classDef ->
        classDef.type.startsWith("Lcom/avito/android/advert/item/similars/") ||
            classDef.sourceFile == "AdvertAsyncComplementaryPresenter.kt"
    },
)
