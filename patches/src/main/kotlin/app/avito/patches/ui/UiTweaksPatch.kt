package app.avito.patches.ui

import app.avito.patches.blacklist.SerpElementsConverterFingerprint
import app.avito.patches.settings.MorpheSettingsRegistry
import app.avito.patches.settings.morpheSettingsPatch
import app.avito.patches.shared.Constants.COMPATIBILITY_AVITO
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.shared.*
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val NAVIGATION_TAB = "Lcom/avito/android/bottom_navigation/NavigationTab;"
private const val BOTTOM_NAVIGATION_SPACE = "Lcom/avito/android/bottom_navigation/space/BottomNavigationSpace;"
private const val MORPHE_SETTINGS_CLASS = "Lapp/avito/morphe/MorpheSettings;"
private const val ADVERT_DETAILS = "Lcom/avito/android/remote/model/AdvertDetails;"
private const val CREDIT_BROKER_PRODUCT = "Lcom/avito/android/remote/model/credit_broker/CreditBrokerProduct;"
private const val ICE_BREAKERS = "Lcom/avito/android/remote/model/IceBreakers;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val FAVORITES_ADAPTER_PACKAGE = "Lcom/avito/android/user_favorites/adapter/"
private const val ONBOARDING_DIALOG_FRAGMENT = "Lcom/avito/android/onboarding/dialog/OnboardingDialogFragment;"
private const val USER_PROFILE_RESULT = "Lcom/avito/android/remote/model/user_profile/UserProfileResult;"

private val AVI_TAB_NAMES = setOf("AI_ASSISTANT", "AI_ASSISTANT_SELLER")
private val PROFILE_PRO_OUTPUT_ITEM_TYPES = setOf(
    "Lcom/avito/android/profile/pro/impl/screen/item/group/row/ProfileProGroupRowItem;",
    "Lcom/avito/android/profile/pro/impl/screen/item/widget_group/widget/ProfileProWidgetItem;",
)

private fun Method.usesBottomNavigationSpace() = parameterTypes.any { it.toString() == BOTTOM_NAVIGATION_SPACE }

private fun Method.hasFieldReference(fields: Set<String>): Boolean = instructionsOrNull?.any { instruction ->
    val reference = instruction.fieldReferenceOrNull() ?: return@any false
    reference.definingClass == NAVIGATION_TAB && reference.name in fields
} == true

private fun ClassDef.isFavoritesTabModel() = type.startsWith(FAVORITES_ADAPTER_PACKAGE) &&
    AccessFlags.ABSTRACT.isSet(accessFlags) &&
    interfaces.any { it.toString() == "Landroid/os/Parcelable;" } &&
    fields.count { it.type == "I" } == 1 &&
    fields.count { it.type == "Ljava/lang/String;" } == 2 &&
    methods.count { method ->
        method.implementation != null &&
            method.parameterTypes.isEmpty() &&
            method.returnType == "Ljava/lang/String;"
    } >= 2

private fun Method.isFavoritesTabViewBind(modelType: String) = returnType == "V" &&
    parameterTypes.map { it.toString() } == listOf(modelType) &&
    implementation != null

private fun Method.isFavoritesTabTitleBind() = returnType == "V" &&
    parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;", "Ljava/lang/String;") &&
    implementation != null

private fun Method.profileProOutputItemTypes(): Set<String> {
    if (returnType != "Ljava/util/ArrayList;" || implementation == null || parameterTypes.size != 1) {
        return emptySet()
    }

    return implementation!!.instructions
        .asSequence()
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type
        }
        .filterTo(mutableSetOf()) { it in PROFILE_PRO_OUTPUT_ITEM_TYPES }
}

/**
 * A collection of optional interface tweaks, each gated by its own toggle in
 * Настройки Morphe so it can be turned off without rebuilding:
 *
 *  - **Force home categories into a single row.**
 *  - **Hide the "Подписки" tab** on the Избранное (Favorites) screen.
 *  - **Hide the installments (Рассрочка)** surfaces and the **"Спросите у
 *    продавца"** block on offer pages.
 *  - **Expand descriptions by default** so the full text shows without tapping
 *    "Читать далее".
 *  - **Hide the recommendations block** at the bottom of offer pages.
 *  - **Hide the Avi assistant tab** in the bottom navigation bar.
 *  - **Hide launch drawers** used by Avito's promotional and informational
 *    onboarding carousel.
 *  - **Hide “Знак добра” banners** in search results.
 *  - **Hide the “Портал призов” raffle promo** on the profile page.
 *  - **Hide the referral-program entry point** on the profile page.
 *  - **Hide the Avito Pro entry point** on the profile page.
 *
 * Every advertised tweak is required on the supported app target. A missing hook
 * aborts patching so an incomplete build cannot be published.
 */
@Suppress("unused")
val uiTweaksPatch = bytecodePatch(
    name = "UI tweaks",
    description = "Optional interface tweaks, each toggleable in Настройки Morphe: single-row home " +
        "categories, hide the \"Подписки\" tab in Избранное, hide installments (Рассрочка) and the " +
        "\"Спросите у продавца\" block on offers, expand descriptions by default (no \"Читать далее\"), " +
        "hide offer recommendations, hide profile raffle, referral and Avito Pro promos, and hide the Avi assistant tab in the bottom navigation.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_AVITO)
    dependsOn(morpheSettingsPatch)

    execute {
        // --- Force the home-screen categories into a single row -----------------
        // The DoubleRows visual rubricator routes each tile to row_first/row_second
        // by its getRowLine(); when the toggle is on, make every tile report row 1
        // so the second row collapses and all categories land in one scrollable row.
        // The class/method keep their real names across 213–227.
        val rubricatorMatch = VisualRubricatorElementFingerprint.matchOrNull()
        val rowLineMatch = rubricatorMatch?.let { match ->
            VisualRubricatorRowLineFingerprint.matchOrNull(match.originalClassDef)
        }
        val getRowLine = rowLineMatch?.let { match ->
            val rowLineField = match.instructionMatches[1].instruction.fieldReferenceOrNull()
                ?: return@let null
            Fingerprint(
                returnType = rowLineField.type,
                parameters = emptyList(),
                filters = listOf(fieldAccess(rowLineField)),
            ).matchOrNull(match.originalClassDef)
        }
        if (getRowLine == null) {
            throw PatchException("UI tweaks: rubricator getRowLine() not found")
        } else {
            val rowLineMethod = getRowLine.method
            rowLineMethod.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $MORPHE_SETTINGS_CLASS->singleRowCategories()Z
                    move-result v0
                    if-eqz v0, :stock
                    const/4 v0, 0x1
                    invoke-static {v0}, $INTEGER->valueOf(I)$INTEGER
                    move-result-object v0
                    return-object v0
                """,
                ExternalLabel("stock", rowLineMethod.getInstruction(0)),
            )
            // Restart-required: the rubricator items are diffed and won't rebind on a
            // simple settings round-trip.
            MorpheSettingsRegistry.addSwitch(
                key = "avito_single_row_categories",
                title = "Категории в одну строку",
                summary = "Показывать категории на главной одной строкой",
                default = true,
                restartRequired = true,
                section = MorpheSettingsRegistry.Section.NAVIGATION,
                order = 10,
            )
            println("UI tweaks: gated single-row home categories behind the toggle.")
        }

        // --- Hide the "Подписки" and "Подборки" tabs in Избранное -----------------
        // Filter the configured Favorites tabs. Two entry points are tried in
        // order so the patch spans app versions:
        //   * 227+: the presenter consumer that reads UserFavoritesTabsRenderMode (the
        //     active path; the obvious builder A.a is bypassed by a feature flag).
        //   * older builds with no render-mode enum (e.g. 226.5): the
        //     UserFavoritesChanges(List, boolean) data-class constructor that every
        //     favorites builder funnels the assembled tab list through.
        // withoutSubscriptionsTab returns a filtered copy when the toggle is on. The
        // injected filter is the same in both cases — p1 is the List<FavoritesTab>.
        // Restart-required: the assembled tab list is consumed once and the tab strip
        // is diffed, so it won't rebind on a live settings round-trip.
        val filterTabListInstructions = """
            invoke-static/range { p1 .. p1 }, $MORPHE_SETTINGS_CLASS->withoutHiddenFavoritesTabs(Ljava/util/List;)Ljava/util/List;
            move-result-object p1
        """
        fun registerFavoritesTabToggles() {
            MorpheSettingsRegistry.addSwitch(
                key = "avito_hide_subscriptions_tab",
                title = "Скрыть вкладку «Подписки/Лента»",
                summary = "Убрать вкладку подписок на экране Избранное",
                default = true,
                restartRequired = true,
                section = MorpheSettingsRegistry.Section.FAVORITES,
                order = 10,
            )
            MorpheSettingsRegistry.addSwitch(
                key = "avito_hide_collections_tab",
                title = "Скрыть вкладку «Подборки»",
                summary = "Убрать вкладку подборок на экране Избранное",
                default = true,
                restartRequired = true,
                section = MorpheSettingsRegistry.Section.FAVORITES,
                order = 20,
            )
        }

        val tabConsumer = FavoritesTabsConsumerFingerprint.methodOrNull
        val changesCtor = if (tabConsumer == null && FavoritesChangesFingerprint.methodOrNull != null) {
            FavoritesChangesFingerprint.classDef.methods.singleOrNull {
                it.name == "<init>" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/util/List;", "Z")
            }
        } else {
            null
        }

        val favoritesTabModelCandidates = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.isFavoritesTabModel()) favoritesTabModelCandidates += classDef.type
        }
        val favoritesTabModel = favoritesTabModelCandidates.singleOrNull()

        var favoritesTabViewHooks = 0
        if (favoritesTabModel != null) {
            classDefForEach { classDef ->
                if (!classDef.type.startsWith(FAVORITES_ADAPTER_PACKAGE)) return@classDefForEach
                val bind = classDef.methods.firstOrNull { method ->
                    method.isFavoritesTabViewBind(favoritesTabModel)
                } ?: return@classDefForEach
                val mutableBind = mutableClassDefBy(classDef).methods.first {
                    it.name == bind.name && it.parameterTypes == bind.parameterTypes
                }
                mutableBind.addInstructions(
                    0,
                    "invoke-static/range {p0 .. p1}, $MORPHE_SETTINGS_CLASS->updateFavoritesTabView(Ljava/lang/Object;Ljava/lang/Object;)V",
                )
                favoritesTabViewHooks++

                val titleBind = classDef.methods.firstOrNull { method -> method.isFavoritesTabTitleBind() }
                    ?: return@classDefForEach
                val mutableTitleBind = mutableClassDefBy(classDef).methods.first {
                    it.name == titleBind.name && it.parameterTypes == titleBind.parameterTypes
                }
                mutableTitleBind.addInstructions(
                    0,
                    "invoke-static/range {p0 .. p1}, $MORPHE_SETTINGS_CLASS->updateFavoritesTabViewByTitle(Ljava/lang/Object;Ljava/lang/String;)V",
                )
                favoritesTabViewHooks++
            }
        } else if (favoritesTabModelCandidates.isNotEmpty()) {
            println(
                "UI tweaks: Favorites tab model was ambiguous " +
                    "(${favoritesTabModelCandidates.joinToString()}); legacy renderers skipped",
            )
        }

        when {
            tabConsumer != null -> {
                val mapperMatch = FavoritesTabsControlMapperFingerprint.matchOrNull(tabConsumer)
                if (mapperMatch != null) {
                    val mapperReference = mapperMatch.instructionMatches[0].instruction.methodReferenceOrNull()
                        ?: throw PatchException("Favorites tabs control mapper reference not found")
                    val moveResult = mapperMatch.instructionMatches[1]
                        .getInstruction<OneRegisterInstruction>()
                    val insertionIndex = mapperMatch.instructionMatches[1].index + 1
                    val stateType = mapperReference.returnType
                    tabConsumer.addInstructions(
                        insertionIndex,
                        """
                            invoke-static/range {v${moveResult.registerA} .. v${moveResult.registerA}}, $MORPHE_SETTINGS_CLASS->withoutHiddenFavoritesTabsControlState(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v${moveResult.registerA}
                            check-cast v${moveResult.registerA}, $stateType
                        """,
                    )
                    registerFavoritesTabToggles()
                    println(
                        "UI tweaks: gated Favorites tabs control state $stateType and " +
                            "$favoritesTabViewHooks legacy renderer(s) in " +
                            "${FavoritesTabsConsumerFingerprint.originalClassDef.type}->${tabConsumer.name}",
                    )
                } else {
                    val message =
                        "Favorites tabs control mapper was not found in " +
                            "${FavoritesTabsConsumerFingerprint.originalClassDef.type}->${tabConsumer.name}"
                    throw PatchException(message)
                }
            }

            changesCtor != null -> {
                changesCtor.addInstructions(0, filterTabListInstructions)
                registerFavoritesTabToggles()
                println(
                    "UI tweaks: gated Favorites tabs via UserFavoritesChanges in " +
                        "${FavoritesChangesFingerprint.originalClassDef.type}",
                )
            }

            else -> {
                throw PatchException("Favorites tab consumer and changes constructor were not found")
            }
        }

        // --- Hide the server-driven “Знак добра” SERP cards ---------------------
        // The compact header card and the larger in-feed card are both Beduin
        // models carrying the same campaign marker. Filter both the input network
        // elements and the converted adapter items so either representation is
        // removed without leaving a blank RecyclerView row.
        val kindnessSerpConverter = SerpElementsConverterFingerprint.methodOrNull
        if (kindnessSerpConverter == null) {
            throw PatchException("UI tweaks: SERP converter not found for kindness banners")
        } else {
            kindnessSerpConverter.addInstructions(
                0,
                """
                    invoke-static/range {p1 .. p1}, $MORPHE_SETTINGS_CLASS->withoutKindnessBanners(Ljava/util/List;)Ljava/util/List;
                    move-result-object p1
                """,
            )
            val kindnessReturnIndices = kindnessSerpConverter.instructionsOrNull
                ?.toList().orEmpty()
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_OBJECT) index else null
                }
                .reversed()
            if (kindnessReturnIndices.isEmpty()) {
                throw PatchException("UI tweaks: SERP converter has no object return for kindness banners")
            }
            for (returnIndex in kindnessReturnIndices) {
                val register =
                    (kindnessSerpConverter.instructionsOrNull!!.toList()[returnIndex] as OneRegisterInstruction).registerA
                kindnessSerpConverter.addInstructions(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $MORPHE_SETTINGS_CLASS->withoutKindnessBanners(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                        check-cast v$register, Ljava/util/ArrayList;
                    """,
                )
            }
            MorpheSettingsRegistry.addSwitch(
                key = "avito_hide_kindness_banners",
                title = "Скрыть «Знак добра»",
                summary = "Убрать баннеры «Знак добра» из результатов поиска",
                default = true,
                section = MorpheSettingsRegistry.Section.PROMO,
                order = 10,
            )
            println(
                "UI tweaks: gated kindness banners in SERP input and " +
                    "${kindnessReturnIndices.size} output(s).",
            )
        }

        // --- Hide Profile Pro promo widgets on the profile page ----------------
        // Profile Pro builds the promo into two independently converted widget
        // groups. Filter their returned lists through a live setting gate so the
        // stock promo comes back as soon as its toggle is disabled. This path
        // includes the current referral widget (stable stringId "referral");
        // UserProfileResult below only covers the legacy profile screen.
        var profilePromoConvertersPatched = 0
        val profileOutputItemTypesPatched = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (!classDef.type.startsWith("Lcom/avito/android/profile/pro/impl/converters/")) {
                return@classDefForEach
            }
            val converterMethod = classDef.methods.singleOrNull { method ->
                method.profileProOutputItemTypes().isNotEmpty()
            } ?: return@classDefForEach
            val outputItemTypes = converterMethod.profileProOutputItemTypes()
            val method = mutableClassDefBy(classDef).methods.single {
                it.name == converterMethod.name && it.parameterTypes == converterMethod.parameterTypes
            }
            val returnTargets = method.instructionsOrNull
                ?.toList().orEmpty()
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_OBJECT) {
                        index to (instruction as OneRegisterInstruction).registerA
                    } else {
                        null
                    }
                }
                .reversed()
            if (returnTargets.isEmpty()) {
                throw PatchException("UI tweaks: profile promo converter ${classDef.type} has no object return")
            }
            returnTargets.forEach { (returnIndex, register) ->
                method.addInstructions(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $MORPHE_SETTINGS_CLASS->withoutProfilePromoWidgets(Ljava/util/ArrayList;)Ljava/util/ArrayList;
                        move-result-object v$register
                    """,
                )
            }
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static/range {p1 .. p1}, $MORPHE_SETTINGS_CLASS->hideProfilePromoGroup(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :stock
                    new-instance v0, Ljava/util/ArrayList;
                    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                    return-object v0
                """,
                ExternalLabel("stock", method.getInstruction(0)),
            )
            profilePromoConvertersPatched++
            profileOutputItemTypesPatched += outputItemTypes
        }
        val missingProfileOutputItemTypes = PROFILE_PRO_OUTPUT_ITEM_TYPES - profileOutputItemTypesPatched
        if (missingProfileOutputItemTypes.isNotEmpty()) {
            throw PatchException(
                "UI tweaks: profile promo output type(s) not found: " +
                    missingProfileOutputItemTypes.joinToString(),
            )
        }
        if (profilePromoConvertersPatched == 0) {
            throw PatchException("UI tweaks: profile promo converters not found")
        } else {
            MorpheSettingsRegistry.addSwitch(
                key = "avito_hide_profile_raffle",
                title = "Скрыть «Портал призов»",
                summary = "Убрать блок с розыгрышем со страницы профиля",
                default = true,
                section = MorpheSettingsRegistry.Section.PROMO,
                order = 20,
            )
            MorpheSettingsRegistry.addSwitch(
                key = "avito_hide_profile_avito_pro",
                title = "Скрыть «Авито Pro»",
                summary = "Убрать блок «Работайте как профи» со страницы профиля",
                default = true,
                section = MorpheSettingsRegistry.Section.PROMO,
                order = 40,
            )
            println(
                "UI tweaks: gated $profilePromoConvertersPatched profile promo converter(s)" +
                    if (missingProfileOutputItemTypes.isEmpty()) {
                        "."
                    } else {
                        "; missing ${missingProfileOutputItemTypes.joinToString()}."
                    },
            )
        }

        // --- Hide the referral-program entry point on the profile page ----------
        // The server sends referrals as a dedicated ReferralEntryPoint model in
        // UserProfileResult.elements. Gate getItems() so every profile consumer
        // sees a list without that exact model while the setting is enabled. The
        // same API-level gate covers the RewardsItem representation of the prize
        // portal used by the current profile screen.
        val profileItemsGetter = mutableClassDefByOrNull(USER_PROFILE_RESULT)
            ?.methods
            ?.firstOrNull { method ->
                method.name == "getItems" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == "Ljava/util/List;" &&
                    method.implementation != null
            }
        if (profileItemsGetter == null) {
            throw PatchException("UI tweaks: UserProfileResult.getItems not found")
        } else {
            val method = profileItemsGetter
            val returnTargets = method.instructionsOrNull
                ?.toList().orEmpty()
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_OBJECT) {
                        index to (instruction as OneRegisterInstruction).registerA
                    } else {
                        null
                    }
                }
                .reversed()
            if (returnTargets.isEmpty()) {
                throw PatchException("UI tweaks: UserProfileResult.getItems has no object return")
            }
            returnTargets.forEach { (returnIndex, register) ->
                method.addInstructions(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $MORPHE_SETTINGS_CLASS->withoutProfilePromoItems(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """,
                )
            }
            MorpheSettingsRegistry.addSwitch(
                key = "avito_hide_profile_referrals",
                title = "Скрыть реферальный блок",
                summary = "Убрать предложение пригласить друзей со страницы профиля",
                default = true,
                section = MorpheSettingsRegistry.Section.PROMO,
                order = 30,
            )
            println("UI tweaks: gated profile referrals behind the toggle (${returnTargets.size} returns).")
        }

        // --- Hide promotional/informational onboarding drawers on launch --------
        // OnboardingDialogFragment is dedicated to Avito's server-driven onboarding
        // carousel bottom sheet. Install an on-show dismiss gate on the dialog it
        // returns, which suppresses the drawer before the first rendered frame while
        // leaving unrelated bottom sheets untouched.
        val onboardingDialogFactory = mutableClassDefByOrNull(ONBOARDING_DIALOG_FRAGMENT)
            ?.methods
            ?.firstOrNull { method ->
                method.name == "onCreateDialog" &&
                    method.returnType == "Landroid/app/Dialog;" &&
                    method.parameterTypes.map { it.toString() } == listOf("Landroid/os/Bundle;") &&
                    method.implementation != null
            }
        if (onboardingDialogFactory == null) {
            throw PatchException("UI tweaks: onboarding dialog factory not found")
        } else {
            val method = onboardingDialogFactory
            val returnIndices = method.instructionsOrNull
                ?.toList().orEmpty()
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_OBJECT) index else null
                }
                .reversed()
            if (returnIndices.isEmpty()) {
                throw PatchException("UI tweaks: onboarding dialog factory has no object return")
            }
            for (returnIndex in returnIndices) {
                val register =
                    (method.instructionsOrNull!!.toList()[returnIndex] as OneRegisterInstruction).registerA
                method.addInstructions(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $MORPHE_SETTINGS_CLASS->suppressOnboardingDrawer(Landroid/app/Dialog;)Landroid/app/Dialog;
                        move-result-object v$register
                    """,
                )
            }
            MorpheSettingsRegistry.addSwitch(
                key = "avito_hide_launch_drawers",
                title = "Скрыть шторки при запуске",
                summary = "Не показывать рекламные и информационные шторки при запуске приложения",
                default = true,
                section = MorpheSettingsRegistry.Section.APP,
                order = 10,
            )
            println("UI tweaks: gated onboarding launch drawers (${returnIndices.size} returns).")
        }

        // --- Hide offer-page blocks by nulling their AdvertDetails source -------
        // Each of these blocks reads a single nullable AdvertDetails getter; routing
        // that getter's return through a null-gate makes every consumer natively
        // render nothing (the offer-page block, plus any other surface that reads
        // the same field). One early hook beats per-component patches.
        //
        // Local helper so the AdvertDetails class is resolved once and the
        // null-gate injection (before each return) isn't duplicated per block.
        val advertDetailsClass = mutableClassDefByOrNull(ADVERT_DETAILS)
        fun gateAdvertDetailsGetter(
            getterName: String,
            returnType: String,
            gateMethod: String,
            key: String,
            title: String,
            summary: String,
            order: Int,
        ) {
            val getter = advertDetailsClass?.methods?.firstOrNull {
                it.name == getterName && it.parameterTypes.isEmpty()
            }
            if (advertDetailsClass == null || getter == null) {
                throw PatchException("UI tweaks: AdvertDetails.$getterName not found")
            }
            val method = getter
            val returnIndices = method.instructionsOrNull
                ?.toList().orEmpty()
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_OBJECT) index else null
                }
                .reversed()
            if (returnIndices.isEmpty()) {
                throw PatchException("UI tweaks: AdvertDetails.$getterName has no object return")
            }
            for (returnIndex in returnIndices) {
                val register =
                    (method.instructionsOrNull!!.toList()[returnIndex] as OneRegisterInstruction).registerA
                method.addInstructions(
                    returnIndex,
                    """
                        invoke-static/range { v$register .. v$register }, $MORPHE_SETTINGS_CLASS->$gateMethod(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$register
                        check-cast v$register, $returnType
                    """,
                )
            }
            MorpheSettingsRegistry.addSwitch(
                key = key,
                title = title,
                summary = summary,
                default = true,
                section = MorpheSettingsRegistry.Section.ADVERT,
                order = order,
            )
            println("UI tweaks: gated AdvertDetails.$getterName behind $key (${returnIndices.size} returns).")
        }

        // Рассрочка (installments): block on the offer page + row in the buy bar.
        gateAdvertDetailsGetter(
            getterName = "getCreditInfo",
            returnType = CREDIT_BROKER_PRODUCT,
            gateMethod = "creditInfoOrNull",
            key = "avito_hide_installments",
            title = "Скрыть рассрочку",
            summary = "Убрать рассрочку со страниц объявлений",
            order = 20,
        )

        // "Спросите у продавца" (icebreakers): the suggested-questions block.
        gateAdvertDetailsGetter(
            getterName = "getIcebreakers",
            returnType = ICE_BREAKERS,
            gateMethod = "icebreakersOrNull",
            key = "avito_hide_ask_seller",
            title = "Скрыть «Спросите у продавца»",
            summary = "Убрать блок с вопросами продавцу",
            order = 30,
        )

        // --- Expand offer descriptions by default -------------------------------
        // Every "Читать далее" description block hands its collapse threshold to
        // ExpandablePanelLayout.setCollapsedLineCount(Integer). Route that count
        // through expandedLineCount(): when the toggle is on it returns an
        // effectively-unlimited value, so the panel never truncates and the
        // read-more handle stays hidden. Re-evaluated on each (re)bind — no restart.
        val collapsedLinesSetter = ExpandablePanelCollapsedLinesFingerprint.methodOrNull
        if (collapsedLinesSetter == null) {
            throw PatchException("UI tweaks: ExpandablePanelLayout.setCollapsedLineCount not found")
        } else {
            collapsedLinesSetter.addInstructions(
                0,
                """
                    invoke-static {p1}, $MORPHE_SETTINGS_CLASS->expandedLineCount($INTEGER)$INTEGER
                    move-result-object p1
                """,
            )
            MorpheSettingsRegistry.addSwitch(
                key = "avito_expand_description",
                title = "Разворачивать описание",
                summary = "Показывать полное описание объявления без кнопки «Читать далее»",
                default = true,
                section = MorpheSettingsRegistry.Section.ADVERT,
                order = 10,
            )
            println("UI tweaks: gated ExpandablePanelLayout collapsed-line count behind avito_expand_description.")
        }

        // --- Hide the complete recommendations block on offer pages ------------
        // AdvertAsyncComplementaryPresenter starts the recommendation request when
        // an AdvertDetails is loaded. Returning before that launch keeps its item
        // flow empty, so the title, filter chips and carousel are all omitted
        // without a blank section or unnecessary network work.
        val recommendationsLoader = OfferRecommendationsLoadFingerprint.methodOrNull
        if (recommendationsLoader == null) {
            throw PatchException("UI tweaks: offer recommendations loader not found")
        } else {
            recommendationsLoader.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $MORPHE_SETTINGS_CLASS->hideOfferRecommendations()Z
                    move-result v0
                    if-eqz v0, :stock
                    return-void
                """,
                ExternalLabel("stock", recommendationsLoader.getInstruction(0)),
            )
            MorpheSettingsRegistry.addSwitch(
                key = "avito_hide_offer_recommendations",
                title = "Скрыть рекомендации",
                summary = "Убрать блок «Рекомендации» со страницы объявления",
                default = true,
                section = MorpheSettingsRegistry.Section.ADVERT,
                order = 40,
            )
            println("UI tweaks: gated the offer recommendations loader behind the toggle.")
        }

        // --- Hide the Avi assistant tab in the bottom navigation ----------------
        // Route the Avi tab's field loads through aviTabOrNull so the toggle
        // controls whether it is dropped (null) or kept.
        val navigationTabClass = classDefByOrNull(NAVIGATION_TAB)
        val aiTabFields = navigationTabClass?.methods
            ?.firstOrNull { it.name == "<clinit>" }
            ?.let { method ->
                val instructions = method.implementation?.instructions?.toList().orEmpty()
                buildSet {
                    instructions.forEachIndexed { index, instruction ->
                        if (instruction.stringReferenceOrNull() !in AVI_TAB_NAMES) return@forEachIndexed

                        instructions
                            .drop(index + 1)
                            .take(16)
                            .firstNotNullOfOrNull { candidate ->
                                val reference = candidate.fieldReferenceOrNull() ?: return@firstNotNullOfOrNull null
                                reference.takeIf {
                                    candidate.opcode == Opcode.SPUT_OBJECT &&
                                        it.definingClass == NAVIGATION_TAB &&
                                        it.type == NAVIGATION_TAB
                                }?.name
                            }
                            ?.let(::add)
                    }
                }
            }
            .orEmpty()

        if (aiTabFields.isEmpty()) {
            throw PatchException("UI tweaks: Avi navigation tab fields not found")
        }

        var patchedReferences = 0

        classDefForEach { classDef ->
            // No package filter: the nav builder is repackaged differently per
            // release (`com/avito/android/bottom_navigation/...` on older builds vs
            // `qr/y` on 227.0). The structural signature — a method taking a
            // BottomNavigationSpace and reading the Avi NavigationTab fields — is
            // distinctive; the cheap param check short-circuits the scan.
            if (classDef.methods.none { it.usesBottomNavigationSpace() && it.hasFieldReference(aiTabFields) }) {
                return@classDefForEach
            }

            mutableClassDefBy(classDef).methods.forEach { method ->
                if (!method.usesBottomNavigationSpace()) return@forEach

                val instructions = method.instructionsOrNull?.toList() ?: return@forEach
                val targets = buildList {
                    instructions.forEachIndexed { index, instruction ->
                        if (instruction.opcode != Opcode.SGET_OBJECT) return@forEachIndexed
                        val reference = instruction.fieldReferenceOrNull() ?: return@forEachIndexed
                        if (reference.definingClass != NAVIGATION_TAB || reference.name !in aiTabFields) {
                            return@forEachIndexed
                        }
                        val register = (instruction as? OneRegisterInstruction)?.registerA
                            ?: return@forEachIndexed
                        add(index to register)
                    }
                }

                targets.sortedByDescending { it.first }.forEach { (index, register) ->
                    val invoke = if (register <= 15) {
                        "invoke-static {v$register}"
                    } else {
                        "invoke-static/range {v$register .. v$register}"
                    }
                    method.addInstructions(
                        index + 1,
                        """
                            $invoke, $MORPHE_SETTINGS_CLASS->aviTabOrNull(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$register
                            check-cast v$register, $NAVIGATION_TAB
                        """,
                    )
                    patchedReferences++
                }
            }
        }

        if (patchedReferences == 0) {
            throw PatchException("UI tweaks: Avi bottom-navigation references not found")
        }

        // Restart-required: the bottom nav is assembled once at startup.
        MorpheSettingsRegistry.addSwitch(
            key = "avito_hide_avi_tab",
            title = "Скрыть вкладку Avi",
            summary = "Убрать кнопку ИИ-ассистента из нижней навигации",
            default = true,
            restartRequired = true,
            section = MorpheSettingsRegistry.Section.NAVIGATION,
            order = 20,
        )
        println("UI tweaks: gated $patchedReferences Avi tab references behind the toggle.")
    }
}
