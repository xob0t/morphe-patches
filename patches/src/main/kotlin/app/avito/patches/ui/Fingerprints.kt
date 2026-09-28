package app.avito.patches.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.shared.fieldReferenceOrNull
import app.shared.stringReferenceOrNull
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val VISUAL_RUBRICATOR_ITEM_MARKER = "VisualRubricatorWidgetElementItemImpl(stringId="
internal const val ROW_LINE_MARKER = ", rowLine="

object VisualRubricatorElementFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(string(VISUAL_RUBRICATOR_ITEM_MARKER)),
)

/**
 * Resolves the field a Kotlin data-class `toString()` prints right after [label].
 *
 * The generated method loads one `name=` label per property and reads the
 * properties in declaration order, but R8 hoists the label constants and outlines
 * append runs — on 234.0 the rubricator item emits
 * `z(sb, textIcon, ", rowLine=", rowLine, ", rowSpan=")` with both labels loaded
 * before either field. So the field read after a label isn't necessarily its
 * value; pairing the n-th label with the n-th distinct field read is.
 *
 * Returns null when the label/field counts disagree (not a plain data-class
 * `toString`) or the label is absent.
 */
internal fun Method.dataClassToStringField(label: String): FieldReference? {
    val instructions = instructionsOrNull ?: return null
    val labels = instructions.mapNotNull { it.stringReferenceOrNull() }.filter { it.endsWith("=") }
    val fields = instructions.mapNotNull { it.fieldReferenceOrNull() }
        .filter { it.definingClass == definingClass }
        .distinct()
    if (labels.size != fields.size) return null
    return fields.getOrNull(labels.indexOf(label))
}

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

/**
 * Matches the Profile Pro converter that turns the loaded `List<ProfileTabWidget>`
 * into profile screen items (`converters/t.a` on 233.5). Its sibling in the same
 * package with this signature only delegates here; this one dispatches every
 * widget type and builds the recommendations item with a literal id.
 */
object ProfileWidgetsConverterFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/profile/pro/impl/converters/",
    returnType = "Ljava/util/List;",
    parameters = listOf(
        "Ljava/util/ArrayList;",
        "Lcom/avito/android/activeOrders/",
        "Lcom/avito/android/safedeal_items_public/",
        "Lcom/avito/android/profile/pro/impl/interactor/",
    ),
    filters = listOf(
        string("recommendations"),
    ),
)

/**
 * Matches the advert-details complementary-section loader that fetches and emits
 * the complete "Рекомендации" block (title, filter chips and advert cards).
 *
 * The implementation class is minified, but remains in the stable
 * `advert/item/similars` package. Its load method takes the current AdvertDetails
 * and starts a coroutine; the sibling setter with the same parameter does not.
 */
object OfferRecommendationsLoadFingerprint : Fingerprint(
    definingClass = "Lcom/avito/android/advert/item/similars/",
    returnType = "V",
    parameters = listOf("Lcom/avito/android/remote/model/AdvertDetails;"),
    filters = listOf(
        methodCall(
            definingClass = "Lkotlinx/coroutines/k;",
            name = "d",
        ),
    ),
)
