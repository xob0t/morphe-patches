package app.tbank.patches.ads

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.option
import app.tbank.patches.shared.Constants.COMPATIBILITY_TBANK
import org.w3c.dom.Element
import java.io.FileNotFoundException

private val storyLayoutFiles = listOf(
    "res/layout/accounts_list_fragment.xml",
    "res/layout/activity_main_flow_container.xml",
    "res/layout/payments_hub_activity.xml",
)

private val storyViewIds = setOf(
    "@id/accountsListStoriesCarouselWrapper",
    "@id/main_stories_container",
)

private const val ACCOUNT_LIST_STORIES_WRAPPER_ID = "@id/accountsListStoriesCarouselWrapper"

private val storyAppBarIds = setOf(
    "@id/accountsListCollapsingAppBarLayout",
)

private val offerLayoutFiles = listOf(
    "res/layout/core_offers_view_bottom_sheet_offer.xml",
    "res/layout/core_offers_view_combo_offer.xml",
    "res/layout/core_offers_view_huge_offer.xml",
    "res/layout/core_offers_view_in_app_message_badge_btn_offer.xml",
    "res/layout/core_offers_view_in_app_message_badge_offer.xml",
    "res/layout/core_offers_view_in_app_message_offer.xml",
    "res/layout/core_offers_view_main_offer.xml",
    "res/layout/offers_ui_view_bottom_sheet_offer.xml",
    "res/layout/offers_ui_view_combo_good_offer.xml",
    "res/layout/offers_ui_view_combo_good_redesign_offer.xml",
    "res/layout/offers_ui_view_combo_offer.xml",
    "res/layout/offers_ui_view_huge_offer.xml",
    "res/layout/offers_ui_view_in_app_message_badge_btn_offer.xml",
    "res/layout/offers_ui_view_in_app_message_badge_offer.xml",
    "res/layout/offers_ui_view_in_app_message_offer.xml",
    "res/layout/offers_ui_view_main_offer.xml",
)

private val productStreamLayoutFiles = listOf(
    "res/layout/accounts_list_applications_item_gallery_teaser_item_b.xml",
    "res/layout/accounts_list_applications_item_gallery_teaser_item_m.xml",
    "res/layout/accounts_list_applications_item_gallery_teaser_item_s.xml",
    "res/layout/accounts_list_common_ui_banner_content_default_layout.xml",
    "res/layout/accounts_list_common_ui_banner_content_layout.xml",
    "res/layout/accounts_list_common_ui_banner_layout.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_banner_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_categories_widget_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_category_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_for_shopping_button_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_partner_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_partner_shimmer_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_partners_widget_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_product_image_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_product_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_product_redesign_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_product_shimmer_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_products_title_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_shelf_widget_item.xml",
    "res/layout/accounts_list_main_shopping_sphere_ui_title_item.xml",
    "res/layout/accounts_list_main_sphere_my_house_auto_skeleton.xml",
    "res/layout/accounts_list_main_sphere_my_house_auto_title.xml",
    "res/layout/accounts_list_main_sphere_onboarding_button.xml",
    "res/layout/accounts_list_main_sphere_onboarding_item.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_carousel_item.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_constructor_widget_item.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_discovery_carousel_item.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_main_discovery_carousel.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_main_carousel.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_offers.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_shimmer.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_small_banner_compilation.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_small_banner_default.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_to_travel_button_item.xml",
    "res/layout/accounts_list_main_travel_sphere_ui_widget.xml",
    "res/layout/accounts_list_my_auto_zero_big_item.xml",
    "res/layout/accounts_list_my_auto_zero_full_item.xml",
    "res/layout/accounts_list_my_auto_zero_small_item.xml",
    "res/layout/accounts_list_my_auto_zero_wide_item.xml",
    "res/layout/accounts_list_my_home_zero_big_item.xml",
    "res/layout/accounts_list_my_home_zero_small_item.xml",
    "res/layout/accounts_list_my_home_zero_wide_item.xml",
    "res/layout/main_page_showcase_teasers_item_gallery_2_teaser.xml",
    "res/layout/main_page_showcase_teasers_item_gallery_3_teaser.xml",
    "res/layout/main_page_showcase_teasers_item_gallery_4_teaser.xml",
    "res/layout/main_page_showcase_teasers_item_gallery_teaser_item_b.xml",
    "res/layout/main_page_showcase_teasers_item_gallery_teaser_item_m.xml",
    "res/layout/main_page_showcase_teasers_item_gallery_teaser_item_s.xml",
)

private val sphereFeatureToggleClasses = setOf(
    "Lru/tinkoff/mb/featuretoggles/main/page/toggles/AccountsShoppingSphere126239Feature;",
    "Lru/tinkoff/mb/featuretoggles/main/page/toggles/MainSpheresAutoRemote128879Feature;",
    "Lru/tinkoff/mb/featuretoggles/main/page/toggles/MainSpheresHomeRemote128878Feature;",
    "Lru/tinkoff/mb/featuretoggles/main/page/toggles/MainSpheresOnboardingRemote128880Feature;",
    "Lru/tinkoff/mb/featuretoggles/main/page/toggles/MainSpheresTravelActiveRemote127476Feature;",
)

private data class TBankResourceTargets(
    val storyViewIds: Set<String> = emptySet(),
    val storyAppBarIds: Set<String> = emptySet(),
    val offerLayouts: Set<String> = emptySet(),
    val productLayouts: Set<String> = emptySet(),
    val missingLayouts: Set<String> = emptySet(),
)

private var patchedResourceTargets = TBankResourceTargets()

private fun Element.walk(): Sequence<Element> = sequence {
    yield(this@walk)

    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val child = nodes.item(index)
        if (child is Element) yieldAll(child.walk())
    }
}

private fun Element.hideView() {
    setAttribute("android:visibility", "gone")
    setAttribute("android:alpha", "0.0")
    setAttribute("android:layout_width", "0dp")
    setAttribute("android:layout_height", "0dp")
    setAttribute("android:maxHeight", "0dp")
    setAttribute("android:minHeight", "0dp")
    setAttribute("android:padding", "0dp")
    setAttribute("android:layout_margin", "0dp")
    setAttribute("android:layout_marginTop", "0dp")
    setAttribute("android:layout_marginBottom", "0dp")
    setAttribute("android:layout_marginStart", "0dp")
    setAttribute("android:layout_marginEnd", "0dp")
    setAttribute("android:textSize", "0sp")
    setAttribute("android:textColor", "@android:color/transparent")
    setAttribute("android:scaleX", "0.0")
    setAttribute("android:scaleY", "0.0")
    setAttribute("android:clickable", "false")
    setAttribute("android:enabled", "false")
    setAttribute("android:focusable", "false")
    setAttribute("android:importantForAccessibility", "no")
}

private fun Element.hideLayoutRoot() {
    hideView()

    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val child = nodes.item(index)
        if (child is Element) child.hideView()
    }
}

private fun Element.markInvisibleViewState() {
    setAttribute("app:layout_viewState", "invisible")
}

private val removeTBankAdResourcesPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_TBANK)

    execute {
        patchedResourceTargets = TBankResourceTargets()
        var hiddenStoryViews = 0
        var collapsedStoryAppBars = 0
        var hiddenOfferViews = 0
        var hiddenProductViews = 0
        val patchedStoryViewIds = mutableSetOf<String>()
        val patchedStoryAppBarIds = mutableSetOf<String>()
        val patchedOfferLayouts = mutableSetOf<String>()
        val patchedProductLayouts = mutableSetOf<String>()
        val missing = mutableListOf<String>()

        storyLayoutFiles.forEach { path ->
            try {
                document(path).use { document ->
                    document.documentElement.walk()
                        .filter { it.getAttribute("android:id") in storyViewIds }
                        .forEach { view ->
                            patchedStoryViewIds += view.getAttribute("android:id")
                            view.hideView()
                            if (view.getAttribute("android:id") == ACCOUNT_LIST_STORIES_WRAPPER_ID) {
                                view.markInvisibleViewState()
                            }
                            hiddenStoryViews++
                        }

                    document.documentElement.walk()
                        .filter { it.getAttribute("android:id") in storyAppBarIds }
                        .forEach { view ->
                            patchedStoryAppBarIds += view.getAttribute("android:id")
                            view.setAttribute("app:scabw_shadow_height", "0dp")
                            collapsedStoryAppBars++
                        }
                }
            } catch (_: FileNotFoundException) {
                missing += path
            }
        }

        offerLayoutFiles.forEach { path ->
            try {
                document(path).use { document ->
                    document.documentElement.walk()
                        .filter { it.getAttribute("android:id") == "@id/offerContent" }
                        .forEach { view ->
                            patchedOfferLayouts += path
                            view.hideView()
                            hiddenOfferViews++
                        }
                }
            } catch (_: FileNotFoundException) {
                missing += path
            }
        }

        productStreamLayoutFiles.forEach { path ->
            try {
                document(path).use { document ->
                    document.documentElement.hideLayoutRoot()
                    patchedProductLayouts += path
                    hiddenProductViews++
                }
            } catch (_: FileNotFoundException) {
                missing += path
            }
        }

        patchedResourceTargets = TBankResourceTargets(
            storyViewIds = patchedStoryViewIds,
            storyAppBarIds = patchedStoryAppBarIds,
            offerLayouts = patchedOfferLayouts,
            productLayouts = patchedProductLayouts,
            missingLayouts = missing.toSet(),
        )

        val resourceStatus = if (missing.isEmpty()) {
            "."
        } else {
            "; ${missing.size} layout(s) absent, continuing in best-effort mode."
        }
        println(
            "Remove TBank ads: hid $hiddenStoryViews story views, " +
                "collapsed $collapsedStoryAppBars story app bars, " +
                "hid $hiddenOfferViews offer views, " +
                "hid $hiddenProductViews product stream views$resourceStatus",
        )
    }
}

@Suppress("unused")
val removeTBankAdsPatch = bytecodePatch(
    name = "Remove TBank ads",
    description = "Removes TBank stories and promotional surfaces.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TBANK)
    dependsOn(removeTBankAdResourcesPatch)

    val strictTargets by option<Boolean>(
        key = "strictTargets",
        title = "Require all current targets",
        description = "Fails when a current promotional surface no longer matches. Intended for automated builds.",
        default = false,
    )

    execute {
        var disabledFeatureToggles = 0
        val foundFeatureToggleClasses = mutableSetOf<String>()
        val patchedFeatureToggleIdClasses = mutableSetOf<String>()
        val patchedFeatureToggleDefaultClasses = mutableSetOf<String>()

        classDefForEach { classDef ->
            if (classDef.type !in sphereFeatureToggleClasses) return@classDefForEach
            foundFeatureToggleClasses += classDef.type

            mutableClassDefBy(classDef).methods.forEach { method ->
                if (method.instructionsOrNull == null) return@forEach

                when (method.name) {
                    "getId" -> {
                        method.addInstructions(
                            0,
                            """
                                const-string p0, "morphe/disabled/tbank/main/spheres"
                                return-object p0
                            """,
                        )
                        disabledFeatureToggles++
                        patchedFeatureToggleIdClasses += classDef.type
                    }
                    "getDefaultValue" -> {
                        method.addInstructions(
                            0,
                            """
                                sget-object p0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                                return-object p0
                            """,
                        )
                        patchedFeatureToggleDefaultClasses += classDef.type
                    }
                }
            }
        }

        if (strictTargets == true) {
            val missingTargets = buildList {
                patchedResourceTargets.missingLayouts.forEach { add("resource layout $it") }
                (storyViewIds - patchedResourceTargets.storyViewIds).forEach { add("story view $it") }
                (storyAppBarIds - patchedResourceTargets.storyAppBarIds).forEach { add("story app bar $it") }
                (offerLayoutFiles - patchedResourceTargets.offerLayouts).forEach { add("offer content in $it") }
                (productStreamLayoutFiles - patchedResourceTargets.productLayouts).forEach {
                    add("product stream layout $it")
                }
                (sphereFeatureToggleClasses - foundFeatureToggleClasses).forEach { add("feature toggle class $it") }
                (foundFeatureToggleClasses - patchedFeatureToggleIdClasses).forEach { add("feature toggle getId in $it") }
                (foundFeatureToggleClasses - patchedFeatureToggleDefaultClasses).forEach {
                    add("feature toggle getDefaultValue in $it")
                }
            }

            if (missingTargets.isNotEmpty()) {
                throw PatchException(
                    "Remove TBank ads strict validation failed; missing current target(s): " +
                        missingTargets.joinToString(", "),
                )
            }
        }

        println("Remove TBank ads: disabled $disabledFeatureToggles promotional sphere feature toggles.")
    }
}
