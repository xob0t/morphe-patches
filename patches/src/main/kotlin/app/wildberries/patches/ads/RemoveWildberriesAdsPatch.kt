package app.wildberries.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.option
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

private fun Method.isListReturnMethod(name: String) =
    this.name == name &&
        returnType == "Ljava/util/List;" &&
        hasImplementation()

private fun Method.isArrayListReturnMethod(name: String) =
    this.name == name &&
        returnType == "Ljava/util/ArrayList;" &&
        hasImplementation()

private fun Method.isBooleanMethod(name: String) =
    this.name == name &&
        returnType == "Z" &&
        hasImplementation()

private fun Method.isVoidMethod(name: String) =
    this.name == name &&
        returnType == "V" &&
        hasImplementation()

private fun Method.isSuspendUnitMethod(name: String) =
    this.name == name &&
        returnType == "Ljava/lang/Object;" &&
        parameterTypes.lastOrNull()?.toString() == "Lkotlin/coroutines/Continuation;" &&
        hasImplementation()

private fun Method.isSuspendObjectMethod(name: String) =
    this.name == name &&
        returnType == "Ljava/lang/Object;" &&
        parameterTypes.lastOrNull()?.toString() == "Lkotlin/coroutines/Continuation;" &&
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

private fun String.isBannersUiWrapperClass() =
    startsWith("Lru/wildberries/mainpage/") &&
        endsWith("BannersUiWrapper;")

private fun String.isMainBannersModelClass() =
    startsWith("Lru/wildberries/banners/") &&
        endsWith("MainBanners;")

private fun String.isBannerMapperClass() =
    startsWith("Lru/wildberries/banners/") &&
        contains("/data/mapper/") &&
        endsWith("BannersMapperImpl;")

private fun String.isBannerDataSourceClass() =
    startsWith("Lru/wildberries/banners/") &&
        contains("/data/source/") &&
        endsWith("BannersDataSource;")

private fun String.isMainPageBannerRenderClass() =
    startsWith("Lru/wildberries/mainpage/") &&
        contains("/presentation/compose/") &&
        (endsWith("MainPageBannersCarouselKt;") || endsWith("MainPageGridBannersKt;"))

private fun String.isBigLotteryDelegateClass() =
    startsWith("Lru/wildberries/mainpage/") &&
        contains("/biglottery/") &&
        endsWith("BigLotteryDelegate;")

private fun String.isBigLotteryMapperClass() =
    startsWith("Lru/wildberries/mainpage/") &&
        contains("/biglottery/").not() &&
        endsWith("BigLotteryMapper;")

private fun String.isBigLotteryUseCaseClass() =
    startsWith("Lru/wildberries/tickets/") &&
        endsWith("BigLotteryUseCaseFacadeImpl;")

private fun String.isRandomTicketSpawnsUseCaseClass() =
    startsWith("Lru/wildberries/tickets/") &&
        endsWith("IsRandomTicketSpawnsEnabledUseCaseImpl;")

private fun String.isCartScreenStateClass() =
    startsWith("Lru/wildberries/cart/") &&
        endsWith("ProductCartUiState\$Screen;")

private fun String.isCartRecommendationsViewModelClass() =
    startsWith("Lru/wildberries/cart/") &&
        endsWith("RecommendationsViewModel;")

private fun String.isProductSellerRecommendationsControllerClass() =
    startsWith("Lru/wildberries/productcard/") &&
        endsWith("SellerRecommendationsBlockControllerKt;")

private fun String.isRaffleRepositoryClass() =
    startsWith("Lru/wildberries/raffle/") &&
        endsWith("RaffleDataRepositoryImpl;")

private fun String.isRaffleSharedComposableClass() =
    startsWith("Lru/wildberries/raffle/") &&
        endsWith("RaffleSharedComposableImpl;")

private fun String.isRaffleItemComposableClass() =
    startsWith("Lru/wildberries/raffle/") &&
        endsWith("RaffleItemKt;")

@Suppress("unused")
val removeWildberriesAdsPatch = bytecodePatch(
    name = "Remove Wildberries ads",
    description = "Removes Wildberries home banners, grid banners, promo headers, product recommendations, and lottery popups.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_WILDBERRIES)

    val hideRecommendationGrids by option<Boolean>(
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
        var patchedBannerDataMethods = 0
        var patchedBigSaleHeaderMethods = 0
        var patchedCartRecommendationMethods = 0
        var patchedProductRecommendationMethods = 0
        var patchedBigLotteryMethods = 0
        var patchedRaffleMethods = 0

        classDefForEach { classDef ->
            val classType = classDef.type

            when {
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

                classType.isBannerMapperClass() -> {
                    mutableClassDefBy(classDef).methods.forEach { method ->
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
                            method.isBooleanMethod("access\$shouldRecommendationsBeVisible") -> {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return v0
                                    """,
                                )
                                patchedCartRecommendationMethods++
                            }

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
                            patchedProductRecommendationMethods++
                        }
                    }
                }

                classType.isBigLotteryMapperClass() -> {
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

                            method.name == "access\$isBigLotteryEnabled" &&
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
            patchedBannerDataMethods == 0 &&
            patchedBigSaleHeaderMethods == 0 &&
            patchedCartRecommendationMethods == 0 &&
            patchedProductRecommendationMethods == 0 &&
            patchedBigLotteryMethods == 0 &&
            patchedRaffleMethods == 0
        ) {
            throw PatchException("No Wildberries banner, promo header, recommendation, or lottery methods were found")
        }

        println(
            "Remove Wildberries ads: ${if (shouldHideRecommendationGrids) "hid" else "kept"} recommendation grids, " +
                "patched $patchedBannerWrapperNullableGetters banner wrapper object getters, " +
                "$patchedBannerWrapperListGetters banner wrapper list getters, " +
                "$patchedMainBannerListGetters main banner list getters, " +
                "$patchedMainBannerStateMethods main banner state methods, " +
                "$patchedBannerRenderMethods banner render methods, " +
                "$patchedBannerDataMethods banner data methods, " +
                "$patchedBigSaleHeaderMethods promo header methods, and " +
                "$patchedCartRecommendationMethods cart recommendation methods, " +
                "$patchedProductRecommendationMethods product recommendation methods, and " +
                "$patchedBigLotteryMethods lottery methods, and " +
                "$patchedRaffleMethods raffle methods.",
        )
    }
}
