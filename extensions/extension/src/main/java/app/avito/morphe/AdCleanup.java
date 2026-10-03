package app.avito.morphe;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import app.avito.blacklist.Blacklist;

/**
 * Runtime cleanup of the "Реклама скрыта" (ad hidden) empty-ad placeholder.
 *
 * <p>With the ad SDK removed by the Remove-ads patch, Avito's feed still reserves
 * ad slots and renders an empty stub card (id {@code ad_empty}; layouts
 * {@code empty_ad_stub} / {@code ad_avl_unavailable}). That stub is a
 * RecyclerView item whose height is set by the adapter at bind time, so hiding it
 * in the layout XML has no effect. Instead we collapse the bound item view here on
 * every adapter bind, keyed on the stable {@code ad_empty} resource id.
 *
 * <p>The stub has its own RecyclerView view type, so a holder created for it is
 * only ever rebound as a stub — collapsing it per instance is safe and needs no
 * restore. Fully defensive: any failure leaves the row untouched.
 *
 * <p>Server-driven Beduin v2 feeds (the newer search results screen) render the
 * same stub from an ad banner component instead; those are dropped from the list
 * before display by {@link #withoutBeduinAdBanners}, together with the promo
 * banners the feed embeds. The legacy SERP drops the same promo banner models via
 * {@link #withoutSerpBanners}.
 */
public final class AdCleanup {

    /**
     * Beduin v2 ad slots: grid children laid out as {@code itemType=advBanner}, or
     * server ad components ({@code componentType=Adv<Name>}, e.g.
     * {@code AdvUniversalSearchBanner}; not {@code Advert...}). Also the promo
     * banners Beduin v2 search embeds through {@code BuyerFeedNativeWrapper},
     * keyed by their SERP element type: legacy banner models (e.g.
     * {@code itemType=actionPromoBanner}) and server-driven content widgets
     * ({@code itemType=beduinV2ContentWidget}, e.g. "Упростили перепродажу").
     */
    private static final java.util.regex.Pattern BEDUIN_AD_COMPONENT =
            java.util.regex.Pattern.compile(
                    "itemType=advBanner\\b|componentType=Adv[A-Z]"
                            + "|itemType=(?:actionPromoBanner|info_banner|discount_banner|uspBannerWidget"
                            + "|heroBannerWidget|heroBannerSnippetsWidget|brandspaceWidget|beduinV2ContentWidget)\\b");

    /**
     * Personal-banner rows in My listings: either a direct banner item or a
     * stateless Beduin item with the feature's dedicated view-type prefix.
     * Match the stable Kotlin model markers, since their classes are minified.
     */
    private static final java.util.regex.Pattern USER_ADVERTS_BANNER_ITEM =
            java.util.regex.Pattern.compile(
                    "^DirectBeduinBannerItem\\("
                            + "|^BeduinItem\\(stringId=[^,]*, viewType=personal_banner_item");

    /**
     * Removes both personal-banner renderers before My listings submits its
     * items to the adapter. Ordinary listings and unrelated Beduin items stay
     * in their original order. The input list is never changed. Fail-open.
     */
    public static java.util.List<?> withoutUserAdvertsBanners(java.util.List<?> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        try {
            java.util.ArrayList<Object> kept = null;
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                boolean banner = item != null
                        && USER_ADVERTS_BANNER_ITEM.matcher(String.valueOf(item)).find();
                if (banner) {
                    if (kept == null) {
                        kept = new java.util.ArrayList<>(items.subList(0, i));
                    }
                } else if (kept != null) {
                    kept.add(item);
                }
            }
            return kept == null ? items : kept;
        } catch (Throwable ignored) {
            return items;
        }
    }

    /**
     * Legacy SERP promo banner models (network {@code SerpElement}s), matching the
     * Beduin v2 item types above. Alert and map banners are informational and kept.
     *
     * <p>Also Avito's own ads embedded in the search response
     * ({@code embeddedAdvBanner}). They need no ad SDK, so they still load. Each one
     * renders a full Beduin screen into its row on every bind, while the
     * resource patch keeps that row hidden. That cost 1-3 s of main-thread time
     * per ad while scrolling search, so they are dropped before conversion.
     */
    private static final java.util.Set<String> SERP_BANNER_MODELS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.avito.android.remote.model.advertising.EmbeddedAdvBanner",
            "com.avito.android.remote.model.ActionPromoBanner",
            "com.avito.android.remote.model.InfoBanner",
            "com.avito.android.remote.model.user_adverts.DiscountBanner",
            "com.avito.android.remote.model.vertical_main.UspBannersWidget",
            "com.avito.android.remote.model.vertical_main.BrandspaceWidget",
            "com.avito.android.remote.model.serp.HeroBannerWidget",
            "com.avito.android.remote.model.serp.HeroBannerSnippetsWidget"
    ));

    /**
     * Called at the entry of the legacy SERP element converter: returns the network
     * elements without promo banners, so no adapter row is built for them. The
     * original list is returned when there is nothing to drop. Fail-open.
     */
    public static java.util.List<?> withoutSerpBanners(java.util.List<?> elements) {
        if (elements == null || elements.isEmpty()) {
            return elements;
        }
        try {
            java.util.ArrayList<Object> kept = null;
            for (int i = 0; i < elements.size(); i++) {
                Object element = elements.get(i);
                boolean banner = element != null && SERP_BANNER_MODELS.contains(element.getClass().getName());
                if (banner) {
                    if (kept == null) {
                        kept = new java.util.ArrayList<>(elements.subList(0, i));
                    }
                } else if (kept != null) {
                    kept.add(element);
                }
            }
            return kept == null ? elements : kept;
        } catch (Throwable ignored) {
            return elements;
        }
    }

    /**
     * Called at the entry of Beduin v2's {@code LazyComponentAdapter.submitList}:
     * returns the components without ad banner slots, which only render the
     * "Реклама скрыта" stub once the ad SDK is removed, and without embedded promo
     * banners. The original list is returned when there is nothing to drop. The
     * two-column grid is realigned after removal, since other filters on this list
     * may already have shifted the tiles around the slot. Fail-open.
     */
    public static java.util.List<?> withoutBeduinAdBanners(java.util.List<?> components) {
        if (components == null || components.isEmpty()) {
            return components;
        }
        try {
            java.util.ArrayList<Object> kept = null;
            for (int i = 0; i < components.size(); i++) {
                Object component = components.get(i);
                boolean ad = component != null
                        && BEDUIN_AD_COMPONENT.matcher(String.valueOf(component)).find();
                if (ad) {
                    if (kept == null) {
                        kept = new java.util.ArrayList<>(components.subList(0, i));
                    }
                } else if (kept != null) {
                    kept.add(component);
                }
            }
            return kept == null ? components : Blacklist.realignBeduinColumns(components, kept);
        } catch (Throwable ignored) {
            return components;
        }
    }

    // -2 = not resolved yet; -1 = resource not found (give up); >0 = the id.
    private static int adEmptyId = -2;

    private AdCleanup() {
    }

    static void onBind(Object viewHolder) {
        try {
            final View itemView = Blacklist.itemViewOf(viewHolder);
            if (itemView == null) {
                return;
            }
            int id = adEmptyId(itemView.getContext());
            if (id <= 0) {
                return;
            }
            if (itemView.getId() != id && itemView.findViewById(id) == null) {
                return;
            }
            collapse(itemView);
            // Re-apply after layout in case the adapter sets the height post-bind.
            itemView.post(new Runnable() {
                @Override
                public void run() {
                    collapse(itemView);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static int adEmptyId(Context ctx) {
        if (adEmptyId != -2) {
            return adEmptyId;
        }
        try {
            adEmptyId = ctx.getResources().getIdentifier("ad_empty", "id", ctx.getPackageName());
            if (adEmptyId == 0) {
                adEmptyId = -1;
            }
        } catch (Throwable t) {
            adEmptyId = -1;
        }
        return adEmptyId;
    }

    private static void collapse(View view) {
        try {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                // Full-span so the collapsed slot doesn't leave a gap in the
                // 2-column staggered SERP grid (no-op on other LayoutParams).
                try {
                    lp.getClass().getMethod("setFullSpan", boolean.class).invoke(lp, true);
                } catch (Throwable ignored) {
                }
                lp.height = 0;
                view.setLayoutParams(lp);
            }
            view.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

}
