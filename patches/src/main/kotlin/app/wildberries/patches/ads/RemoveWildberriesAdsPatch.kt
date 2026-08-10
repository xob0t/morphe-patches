package app.wildberries.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.wildberries.patches.shared.Constants.COMPATIBILITY_WILDBERRIES
import com.android.tools.smali.dexlib2.iface.Method

private val nullableBannerWrapperGetters = setOf(
    "getMainBanners",
    "getMarketingBannersCarousel",
    "getSecondaryBannersCarousel",
    "getTvBannersCarousel",
)

private val listBannerWrapperGetters = setOf(
    "getGridBanners",
    "getOutBanners",
)

private val mainBannerListGetters = setOf(
    // `getMainBannersCarousel` is the pre-7.6.8001 name for the main hero slider;
    // 7.6.8001 renamed/split it into the `getTopSlider*` family. Both are kept so
    // the top banners are emptied across old and new builds.
    "getMainBannersCarousel",
    "getTopSlider",
    "getTopSliderNF",
    "getTopSliderVF",
    "getMarketingCarousel",
    "getSecondaryBannersCarousel",
    "getTvBanners",
    "getSecondSmallBannersCarousel",
    "getPromoInCatalogMenu",
    "getSearchBannersNewFormat",
    "getThanksForOrder",
    "getTvBannersCarousel",
    "getOutBanners",
    "getTvBannersCarouselNewFormat",
    "getOutBannersNewFormat",
)

private val bannerRenderMethods = setOf(
    "BannersCarousel",
    "MainPageBannersCarousel",
    "GridBanners",
    "MainPageGridBanners",
)

private fun Method.hasImplementation() = implementation != null

private fun Method.isListReturnMethod(name: String) = this.name == name &&
    returnType == "Ljava/util/List;" &&
    hasImplementation()

private fun Method.isArrayListReturnMethod(name: String) = this.name == name &&
    returnType == "Ljava/util/ArrayList;" &&
    hasImplementation()

private fun Method.isBooleanMethod(name: String) = this.name == name &&
    returnType == "Z" &&
    hasImplementation()

private fun Method.isVoidMethod(name: String) = this.name == name &&
    returnType == "V" &&
    hasImplementation()

// A Kotlin `suspend` function lowers to a trailing `Continuation` param. R8 on
// 7.7.2001 specializes many of these to the concrete `ContinuationImpl` subtype,
// so an exact match on `Lkotlin/coroutines/Continuation;` silently misses them
// (this alone dropped lottery `handleTicketCommand`/`emitDelegateEvent` and raffle
// `observe`/`invalidate`). Accept either the interface or the specialized subtype.
private fun Method.hasContinuationTail() = parameterTypes.lastOrNull()?.toString().let {
    it == "Lkotlin/coroutines/Continuation;" ||
        it == "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
}

private fun Method.isSuspendUnitMethod(name: String) = this.name == name &&
    returnType == "Ljava/lang/Object;" &&
    hasContinuationTail() &&
    hasImplementation()

private fun Method.isSuspendObjectMethod(name: String) = this.name == name &&
    returnType == "Ljava/lang/Object;" &&
    hasContinuationTail() &&
    hasImplementation()

/**
 * Matches every `ru.wildberries.*` method named `isBigSaleSearchBarEnabled`
 * returning a boolean. Resolved via `matchAllOrNull` so the patcher locates the
 * handful of declaring classes directly, instead of us iterating (and materialising
 * a mutable proxy for) every Wildberries class — the latter exhausted the patcher
 * heap (see #6). `OrNull` keeps the patch resilient if a future build drops the
 * method (like it dropped `isVideoBannerInMainCarousel` on 7.6.8001).
 */
private object BigSaleSearchBarFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, classDef ->
        method.name == "isBigSaleSearchBarEnabled" &&
            method.implementation != null &&
            classDef.type.startsWith("Lru/wildberries/")
    },
)

// The main-page "big sale" promo search bar restyles the whole header in place of
// the normal search toolbar (red theme, the "находки из Китая" promo strip, the
// `bigSaleCounterButton`). On 7.6.8001 the gate was an `isBigSaleSearchBarEnabled()Z`
// getter (still covered by BigSaleSearchBarFingerprint above), but R8 on 7.7.2001
// inlined that getter to a direct field read, so the fingerprint matches nothing
// there. The version-stable root is `IsBigSaleSearchBarEnabledUseCase`, which feeds
// the `MainPageOptions.isBigSaleSearchBarEnabled` field via two paths: `invoke()Z`
// (synchronous — used by PromoSearchBarInteractor) and `observeIsEnabled()` (reactive
// — used by MainPageOptionsProvider, whose emitted value is computed by the combine
// lambda `…$observeIsEnabled$1`). Forcing both to false reverts the header to the
// app's own non-sale toolbar — MainPageComposeFragment selects the normal bar when
// the flag is false.
private fun String.isBigSaleSearchBarUseCaseClass() = startsWith("Lru/wildberries/mainpage/") &&
    endsWith("/IsBigSaleSearchBarEnabledUseCase;")

private fun String.isBigSaleSearchBarObserveLambdaClass() = startsWith("Lru/wildberries/mainpage/") &&
    contains("IsBigSaleSearchBarEnabledUseCase") &&
    endsWith("observeIsEnabled\$1;")

private fun String.isBannersUiWrapperClass() = startsWith("Lru/wildberries/mainpage/") &&
    endsWith("BannersUiWrapper;")

private fun String.isMainBannersModelClass() = startsWith("Lru/wildberries/banners/") &&
    endsWith("MainBanners;")

private fun String.isBannerMapperClass() = startsWith("Lru/wildberries/banners/") &&
    contains("/data/mapper/") &&
    endsWith("BannersMapperImpl;")

private fun String.isBannerDataSourceClass() = startsWith("Lru/wildberries/banners/") &&
    contains("/data/source/") &&
    endsWith("BannersDataSource;")

private fun String.isMainPageBannerRenderClass() = startsWith("Lru/wildberries/mainpage/") &&
    contains("/presentation/compose/") &&
    (endsWith("MainPageBannersCarouselKt;") || endsWith("MainPageGridBannersKt;"))

// The profile ("personal page") screen renders its own banner section, separate
// from the main-page banner path above. `PersonalPageBanners` is the whole
// section: when the user has real banners it shows them, otherwise it falls back
// to a hardcoded `banner_default_placeholder_*` promo ("There is everything you
// need"). Emptying the banner list therefore does NOT hide it — it pins the
// placeholder. Neutralising the section composable to `return-void` (before it
// opens its restart group) reproduces the app's own no-banners state: the caller's
// `BannersBlock` else-branch already renders nothing, so there is no layout gap.
private fun String.isPersonalPageBannerRenderClass() = startsWith("Lru/wildberries/personalpage/") &&
    contains("/presentation/compose/") &&
    endsWith("PersonalPageBannersKt;")

private fun String.isBigLotteryDelegateClass() = startsWith("Lru/wildberries/mainpage/") &&
    contains("/biglottery/") &&
    endsWith("BigLotteryDelegate;")

private fun String.isBigLotteryMapperClass() = startsWith("Lru/wildberries/mainpage/") &&
    contains("/biglottery/").not() &&
    endsWith("BigLotteryMapper;")

private fun String.isBigLotteryUseCaseClass() = startsWith("Lru/wildberries/tickets/") &&
    endsWith("BigLotteryUseCaseFacadeImpl;")

private fun String.isRandomTicketSpawnsUseCaseClass() = startsWith("Lru/wildberries/tickets/") &&
    endsWith("IsRandomTicketSpawnsEnabledUseCaseImpl;")

private fun String.isCartScreenStateClass() = startsWith("Lru/wildberries/cart/") &&
    endsWith("ProductCartUiState\$Screen;")

private fun String.isCartRecommendationsViewModelClass() = startsWith("Lru/wildberries/cart/") &&
    endsWith("RecommendationsViewModel;")

private fun String.isProductSellerRecommendationsControllerClass() = startsWith("Lru/wildberries/productcard/") &&
    endsWith("SellerRecommendationsBlockControllerKt;")

private fun String.isProductRecommendationsGridClass() = startsWith("Lru/wildberries/productcard/") &&
    endsWith("/recommendations/grid/RecommendationsGridKt;")

private fun String.isRaffleRepositoryClass() = startsWith("Lru/wildberries/raffle/") &&
    endsWith("RaffleDataRepositoryImpl;")

private fun String.isRaffleSharedComposableClass() = startsWith("Lru/wildberries/raffle/") &&
    endsWith("RaffleSharedComposableImpl;")

private fun String.isRaffleItemComposableClass() = startsWith("Lru/wildberries/raffle/") &&
    endsWith("RaffleItemKt;")

@Suppress("unused")
val removeWildberriesAdsPatch = bytecodePatch(
    name = "Remove Wildberries ads",
    description = "Removes Wildberries home banners, grid banners, profile banners, promo headers, product recommendations, and lottery popups.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_WILDBERRIES)

    val hideRecommendationGrids by booleanOption(
        key = "hideRecommendationGrids",
        title = "Hide recommendation grids",
        description = "Removes recommendation grids from cart and product screens.",
        default = true,
    )

    execute {
        val shouldHideRecommendationGrids = hideRecommendationGrids != false

        var patchedBannerWrapperNullableGetters = 0
        var patchedBannerWrapperListGetters = 0
        var patchedMainBannerListGetters = 0
        var patchedMainBannerStateMethods = 0
        var patchedBannerRenderMethods = 0
        var patchedProfileBannerRenderMethods = 0
        var patchedBannerDataMethods = 0
        var patchedBigSaleHeaderMethods = 0
        var patchedCartRecommendationMethods = 0
        var patchedProductSellerRecommendationMethods = 0
        var patchedProductInfiniteRecommendationMethods = 0
        var patchedBigLotteryMethods = 0
        var patchedRaffleMethods = 0

        classDefForEach { classDef ->
            val classType = classDef.type

            when {
                classType.isBigSaleSearchBarUseCaseClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (method.name == "invoke" &&
                            method.returnType == "Z" &&
                            method.parameterTypes.isEmpty() &&
                            method.hasImplementation()
                        ) {
                            method.addInstructions(
                                0,
                                """
                                    const/4 v0, 0x0
                                    return v0
                                """,
                            )
                            patchedBigSaleHeaderMethods++
                        }
                    }
                }

                classType.isBigSaleSearchBarObserveLambdaClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (method.name == "invokeSuspend" &&
                            method.returnType == "Ljava/lang/Object;" &&
                            method.hasImplementation()
                        ) {
                            method.addInstructions(
                                0,
                                """
                                    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                                    return-object v0
                                """,
                            )
                            patchedBigSaleHeaderMethods++
                        }
                    }
                }

                classType.isBannersUiWrapperClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        when {
                            method.name in nullableBannerWrapperGetters && method.hasImplementation() -> {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return-object v0
                                    """,
                                )
                                patchedBannerWrapperNullableGetters++
                            }

                            method.name in listBannerWrapperGetters && method.isListReturnMethod(method.name) -> {
                                method.addInstructions(
                                    0,
                                    """
                                        invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                                        move-result-object v0
                                        return-object v0
                                    """,
                                )
                                patchedBannerWrapperListGetters++
                            }
                        }
                    }
                }

                classType.isMainBannersModelClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        when {
                            method.name in mainBannerListGetters && method.isListReturnMethod(method.name) -> {
                                method.addInstructions(
                                    0,
                                    """
                                        invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                                        move-result-object v0
                                        return-object v0
                                    """,
                                )
                                patchedMainBannerListGetters++
                            }

                            method.name == "isNotEmpty" &&
                                method.returnType == "Z" &&
                                method.hasImplementation() -> {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return v0
                                    """,
                                )
                                patchedMainBannerStateMethods++
                            }

                            method.name == "isVideoBannerInMainCarousel" &&
                                method.returnType == "Z" &&
                                method.hasImplementation() -> {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return v0
                                    """,
                                )
                                patchedMainBannerStateMethods++
                            }
                        }
                    }
                }

                classType.isMainPageBannerRenderClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (method.name in bannerRenderMethods && method.isVoidMethod(method.name)) {
                            method.addInstructions(
                                0,
                                """
                                    return-void
                                """,
                            )
                            patchedBannerRenderMethods++
                        }
                    }
                }

                classType.isPersonalPageBannerRenderClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (method.isVoidMethod("PersonalPageBanners")) {
                            method.addInstructions(
                                0,
                                """
                                    return-void
                                """,
                            )
                            patchedProfileBannerRenderMethods++
                        }
                    }
                }

                classType.isBannerMapperClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        // `toDomainBanners` returned `List` up to 7.6.x but was tightened
                        // to `ArrayList` on 7.7.2001 — match either so the mapper isn't
                        // silently skipped after the return type narrows.
                        if (method.isListReturnMethod("toDomainBanners")) {
                            method.addInstructions(
                                0,
                                """
                                    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                                    move-result-object v0
                                    return-object v0
                                """,
                            )
                            patchedBannerDataMethods++
                        } else if (method.isArrayListReturnMethod("toDomainBanners")) {
                            method.addInstructions(
                                0,
                                """
                                    new-instance v0, Ljava/util/ArrayList;
                                    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                                    return-object v0
                                """,
                            )
                            patchedBannerDataMethods++
                        }
                    }
                }

                classType.isBannerDataSourceClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (method.isArrayListReturnMethod("getBannersByLocation")) {
                            method.addInstructions(
                                0,
                                """
                                    new-instance v0, Ljava/util/ArrayList;
                                    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                                    return-object v0
                                """,
                            )
                            patchedBannerDataMethods++
                        }
                    }
                }

                classType.isCartScreenStateClass() -> if (shouldHideRecommendationGrids) {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (method.isBooleanMethod("getRecommendationsInEmptyCartEnabled")) {
                            method.addInstructions(
                                0,
                                """
                                    const/4 v0, 0x0
                                    return v0
                                """,
                            )
                            patchedCartRecommendationMethods++
                        }
                    }
                }

                classType.isCartRecommendationsViewModelClass() -> if (shouldHideRecommendationGrids) {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        when {
                            // Up to 7.6.x this was the synthetic `access$shouldRecommendationsBeVisible`;
                            // 7.7.2001 dropped the `access$` wrapper, exposing the direct
                            // `shouldRecommendationsBeVisible()Z`. Match either.
                            method.isBooleanMethod("access\$shouldRecommendationsBeVisible") ||
                                method.isBooleanMethod("shouldRecommendationsBeVisible") -> {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return v0
                                    """,
                                )
                                patchedCartRecommendationMethods++
                            }

                            // `loadMoreProducts()V` existed up to 7.6.x; on 7.7.2001 it was
                            // folded into `loadRecommendations(Z)V` (matched below via name+void).
                            method.isVoidMethod("loadMoreProducts") -> {
                                method.addInstructions(
                                    0,
                                    """
                                        return-void
                                    """,
                                )
                                patchedCartRecommendationMethods++
                            }

                            method.isVoidMethod("loadRecommendations") -> {
                                method.addInstructions(
                                    0,
                                    """
                                        return-void
                                    """,
                                )
                                patchedCartRecommendationMethods++
                            }
                        }
                    }
                }

                classType.isProductSellerRecommendationsControllerClass() -> if (shouldHideRecommendationGrids) {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (method.isVoidMethod("RecommendationsBlockController")) {
                            method.addInstructions(
                                0,
                                """
                                    return-void
                                """,
                            )
                            patchedProductSellerRecommendationMethods++
                        }
                    }
                }

                classType.isProductRecommendationsGridClass() -> if (shouldHideRecommendationGrids) {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (
                            method.name.startsWith("recommendationsGrid") &&
                            method.returnType == "V" &&
                            method.parameterTypes.firstOrNull()?.toString() ==
                            "Landroidx/compose/foundation/lazy/grid/LazyGridScope;" &&
                            method.hasImplementation()
                        ) {
                            method.addInstructions(
                                0,
                                """
                                    return-void
                                """,
                            )
                            patchedProductInfiniteRecommendationMethods++
                        }
                    }
                }

                classType.isBigLotteryMapperClass() -> {
                    // NOTE: on 7.7.2001 R8 reduced BigLotteryMapper to an empty singleton
                    // and inlined `map()` into BigLotteryDelegate, so this matches 0 there
                    // (kept for 7.6.x). The lottery popup stays suppressed via the delegate
                    // suspend methods + isBigLotteryAvailable gate below — do not chase it.
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (method.isListReturnMethod("map")) {
                            method.addInstructions(
                                0,
                                """
                                    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                                    move-result-object v0
                                    return-object v0
                                """,
                            )
                            patchedBigLotteryMethods++
                        }
                    }
                }

                classType.isBigLotteryDelegateClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (
                            method.isSuspendUnitMethod("onCommand") ||
                            method.isSuspendUnitMethod("handleTicketCommand") ||
                            method.isSuspendUnitMethod("access\$emitDelegateEvent")
                        ) {
                            method.addInstructions(
                                0,
                                """
                                    sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                                    return-object v0
                                """,
                            )
                            patchedBigLotteryMethods++
                        }
                    }
                }

                classType.isBigLotteryUseCaseClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        when {
                            method.name == "isBigLotteryAvailable" &&
                                method.returnType == "Ljava/lang/Object;" &&
                                method.hasImplementation() -> {
                                method.addInstructions(
                                    0,
                                    """
                                        sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                                        return-object v0
                                    """,
                                )
                                patchedBigLotteryMethods++
                            }

                            // 7.6.x exposed a synthetic `access$isBigLotteryEnabled`;
                            // 7.7.2001 dropped the `access$` wrapper for the direct
                            // instance method `isBigLotteryEnabled(User)Z`. Match either.
                            (
                                method.name == "access\$isBigLotteryEnabled" ||
                                    method.name == "isBigLotteryEnabled"
                                ) &&
                                method.returnType == "Z" &&
                                method.hasImplementation() -> {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return v0
                                    """,
                                )
                                patchedBigLotteryMethods++
                            }
                        }
                    }
                }

                classType.isRandomTicketSpawnsUseCaseClass() -> {
                    // NOTE: on 7.7.2001 R8 inlined `invoke(Z)Z` into its call sites and
                    // stripped the interface, so this matches 0 there (kept for 7.6.x).
                    // Random-ticket spawns ride the same lottery gates above — do not chase it.
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (
                            method.name == "invoke" &&
                            method.returnType == "Z" &&
                            method.parameterTypes.singleOrNull()?.toString() == "Z" &&
                            method.hasImplementation()
                        ) {
                            method.addInstructions(
                                0,
                                """
                                    const/4 v0, 0x0
                                    return v0
                                """,
                            )
                            patchedBigLotteryMethods++
                        }
                    }
                }

                classType.isRaffleRepositoryClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        when {
                            method.isSuspendObjectMethod("observe") -> {
                                method.addInstructions(
                                    0,
                                    """
                                        invoke-static {}, Lkotlinx/coroutines/flow/FlowKt;->emptyFlow()Lkotlinx/coroutines/flow/Flow;
                                        move-result-object v0
                                        return-object v0
                                    """,
                                )
                                patchedRaffleMethods++
                            }

                            method.isSuspendUnitMethod("invalidate") ||
                                method.isSuspendUnitMethod("invalidateSafe") -> {
                                method.addInstructions(
                                    0,
                                    """
                                        sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                                        return-object v0
                                    """,
                                )
                                patchedRaffleMethods++
                            }
                        }
                    }
                }

                classType.isRaffleSharedComposableClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (method.isVoidMethod("Content")) {
                            method.addInstructions(0, "return-void")
                            patchedRaffleMethods++
                        }
                    }
                }

                classType.isRaffleItemComposableClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        if (
                            method.isVoidMethod("RaffleItem") ||
                            method.isVoidMethod("DefaultRaffleItem")
                        ) {
                            method.addInstructions(0, "return-void")
                            patchedRaffleMethods++
                        }
                    }
                }
            }
        }

        // Promo "big sale" header gate: force `isBigSaleSearchBarEnabled` to false on
        // every declaring class. Resolved by fingerprint instead of a catch-all over
        // all `ru.wildberries.*` classes so no mutable proxy is allocated for classes
        // that don't declare it (the catch-all was the #6 OOM source).
        BigSaleSearchBarFingerprint.matchAllOrNull().orEmpty().forEach { match ->
            match.method.addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    return v0
                """,
            )
            patchedBigSaleHeaderMethods++
        }

        if (
            patchedBannerWrapperNullableGetters == 0 &&
            patchedBannerWrapperListGetters == 0 &&
            patchedMainBannerListGetters == 0 &&
            patchedMainBannerStateMethods == 0 &&
            patchedBannerRenderMethods == 0 &&
            patchedProfileBannerRenderMethods == 0 &&
            patchedBannerDataMethods == 0 &&
            patchedBigSaleHeaderMethods == 0 &&
            patchedCartRecommendationMethods == 0 &&
            patchedProductSellerRecommendationMethods == 0 &&
            patchedProductInfiniteRecommendationMethods == 0 &&
            patchedBigLotteryMethods == 0 &&
            patchedRaffleMethods == 0
        ) {
            throw PatchException("No Wildberries banner, promo header, recommendation, or lottery methods were found")
        }

        val missingTargets = buildList {
            // Wrapper/model getters are older implementations and can legitimately
            // be absent. The render + data pair is the complete current banner path.
            if (patchedBannerRenderMethods == 0) add("banner rendering")
            if (patchedProfileBannerRenderMethods == 0) add("profile banners")
            if (patchedBannerDataMethods == 0) add("banner data")
            if (shouldHideRecommendationGrids) {
                if (patchedCartRecommendationMethods == 0) add("cart recommendations")
                if (patchedProductSellerRecommendationMethods == 0) {
                    add("product seller recommendations")
                }
                if (patchedProductInfiniteRecommendationMethods == 0) {
                    add("product infinite recommendations")
                }
            }
            if (patchedBigLotteryMethods == 0) add("lottery")
            if (patchedRaffleMethods == 0) add("raffle")
        }
        if (missingTargets.isNotEmpty()) {
            throw PatchException(
                "Remove Wildberries ads validation failed; missing target(s): " +
                    missingTargets.joinToString(", "),
            )
        }

        println(
            "Remove Wildberries ads: ${if (shouldHideRecommendationGrids) "hid" else "kept"} recommendation grids, " +
                "patched $patchedBannerWrapperNullableGetters banner wrapper object getters, " +
                "$patchedBannerWrapperListGetters banner wrapper list getters, " +
                "$patchedMainBannerListGetters main banner list getters, " +
                "$patchedMainBannerStateMethods main banner state methods, " +
                "$patchedBannerRenderMethods banner render methods, " +
                "$patchedProfileBannerRenderMethods profile banner render methods, " +
                "$patchedBannerDataMethods banner data methods, " +
                "$patchedBigSaleHeaderMethods promo header methods, and " +
                "$patchedCartRecommendationMethods cart recommendation methods, " +
                "$patchedProductSellerRecommendationMethods product seller recommendation methods, " +
                "$patchedProductInfiniteRecommendationMethods product infinite recommendation methods, and " +
                "$patchedBigLotteryMethods lottery methods, and " +
                "$patchedRaffleMethods raffle methods.",
        )
    }
}
