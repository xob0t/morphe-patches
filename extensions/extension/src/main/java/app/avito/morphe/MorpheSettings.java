package app.avito.morphe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import app.avito.blacklist.Blacklist;

/**
 * Runtime host for the "Настройки Morphe" screen and the framework other Avito
 * patches plug into.
 *
 * <ul>
 *   <li>{@link #config()} returns a JSON description of every registered settings
 *       entry (toggles + sub-screens). It is baked at build time by the
 *       morpheSettings patch (feature patches register via the patch-layer
 *       {@code MorpheSettingsRegistry}); the default below is used only if no
 *       patch overwrote it.</li>
 *   <li>{@link #isEnabled} / {@link #setEnabled} read/write toggle state in the
 *       {@code avito_morphe_settings} SharedPreferences — feature patches inject a
 *       call to {@code isEnabled(key, default)} to gate their behaviour live.</li>
 *   <li>{@link #onBind} is the konveyor bind hook target: it wires the injected
 *       Settings row to open {@link MorpheSettingsActivity}, and delegates advert
 *       binds to the blacklist feature.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class MorpheSettings {

    public static final String PREFS_NAME = "avito_morphe_settings";
    /** Marker id of the single row injected into Avito's Settings screen. */
    public static final String SETTINGS_ENTRY_ID = "morphe_settings";
    public static final String SETTINGS_ENTRY_TITLE = "Настройки Morphe";
    public static final String SETTINGS_ACTIVITY = "app.avito.morphe.MorpheSettingsActivity";
    private static final int FAVORITES_SUBSCRIPTIONS_TAB_ID = 3;
    private static final int FAVORITES_COLLECTIONS_TAB_ID = 5;
    private static final java.util.Map<android.view.View, HiddenKindnessViewState> HIDDEN_KINDNESS_VIEWS =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<android.view.View, HiddenKindnessViewState>());

    private static Context appContext;

    private MorpheSettings() {
    }

    /**
     * JSON array of registered entries. Overwritten at build time by the patch
     * (its body is replaced with a {@code const-string} of the real config).
     */
    public static String config() {
        return "[]";
    }

    /** Morphe patch-bundle version, replaced with the MPP manifest version when
     *  the settings patch is applied. */
    public static String patchVersion() {
        return "неизвестна";
    }

    public static boolean isEnabled(String key, boolean defaultValue) {
        SharedPreferences prefs = prefs();
        if (prefs == null || key == null) {
            return defaultValue;
        }
        try {
            return prefs.getBoolean(key, defaultValue);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    public static void setEnabled(String key, boolean value) {
        SharedPreferences prefs = prefs();
        if (prefs == null || key == null) {
            return;
        }
        try {
            prefs.edit().putBoolean(key, value).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Restores every Morphe toggle to its registered default. Feature data such
     *  as the blacklist is stored separately and is intentionally preserved. */
    public static void resetPreferences() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        try {
            prefs.edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Cold-restarts the app to apply settings that only take effect at startup.
     * Schedules a relaunch of the launcher activity via AlarmManager (so it fires
     * after this process dies — more reliable than start-then-exit) and kills the
     * process.
     */
    public static void restart(Context ctx) {
        try {
            if (ctx == null) {
                ctx = context();
            }
            if (ctx != null) {
                Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    int flags = android.app.PendingIntent.FLAG_CANCEL_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE;
                    android.app.PendingIntent pending =
                            android.app.PendingIntent.getActivity(ctx, 0, launch, flags);
                    android.app.AlarmManager am =
                            (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                    if (am != null && pending != null) {
                        am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 250, pending);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        Runtime.getRuntime().exit(0);
    }

    /**
     * Gate helper for the "hide Avi tab" feature, injected after the tab's field
     * load: returns {@code null} (so the bottom-nav builder drops the tab) while
     * the toggle is on, otherwise the tab unchanged. Needs an app restart to take
     * effect because the nav bar is built once at startup.
     */
    public static Object aviTabOrNull(Object tab) {
        return isEnabled("avito_hide_avi_tab", true) ? null : tab;
    }

    /**
     * Gate for the "hide installments (Рассрочка)" tweak, injected at the return of
     * {@code AdvertDetails.getCreditInfo()} — the single source every installment
     * surface reads (the offer-page block, the contact-bar row, …). Returns
     * {@code null} (a value the field is already nullable to, so each consumer
     * natively renders nothing) while the toggle is on, otherwise the value
     * unchanged.
     */
    public static Object creditInfoOrNull(Object creditInfo) {
        return isEnabled("avito_hide_installments", true) ? null : creditInfo;
    }

    /**
     * Gate for the "hide ask-seller (Спросите у продавца / icebreakers)" tweak,
     * injected at the return of {@code AdvertDetails.getIcebreakers()} — the single
     * source the block reads. Returns {@code null} (already a valid value for offers
     * without icebreakers) while the toggle is on, otherwise the value unchanged.
     */
    public static Object icebreakersOrNull(Object icebreakers) {
        return isEnabled("avito_hide_ask_seller", true) ? null : icebreakers;
    }

    /**
     * Gate for the "expand description by default" tweak, injected at the entry of
     * {@code ExpandablePanelLayout.setCollapsedLineCount(Integer)} — the single
     * threshold every "Читать далее" description panel funnels through. While the
     * toggle is on it returns an effectively-unlimited line count, so the text shows
     * in full and the read-more handle stays hidden; off → the value unchanged (stock
     * collapse). Re-evaluated on each (re)bind, so no restart is required.
     */
    public static Integer expandedLineCount(Integer count) {
        return isEnabled("avito_expand_description", true) ? Integer.valueOf(Integer.MAX_VALUE) : count;
    }

    /** Gate for the offer-page complementary recommendations loader. Returning
     *  true before its coroutine starts leaves the entire section absent. */
    public static boolean hideOfferRecommendations() {
        return isEnabled("avito_hide_offer_recommendations", true);
    }

    /**
     * Removes configured promo rows from the profile's Pro widget groups. The
     * converter returns a mutable ArrayList, so filtering it in place avoids
     * leaving empty profile rows. Disabling either toggle restores the matching
     * stock row on the next profile load.
     */
    public static java.util.ArrayList<?> withoutProfilePromoWidgets(java.util.ArrayList<?> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        try {
            boolean hideRaffle = isEnabled("avito_hide_profile_raffle", true);
            boolean hideAvitoPro = isEnabled("avito_hide_profile_avito_pro", true);
            if (!hideRaffle && !hideAvitoPro) {
                return items;
            }
            java.util.Iterator<?> iterator = items.iterator();
            while (iterator.hasNext()) {
                Object item = iterator.next();
                boolean hiddenRaffle = hideRaffle && containsStringValue(item, "Портал призов");
                boolean hiddenAvitoPro = hideAvitoPro
                        && (containsStringValue(item, "Работайте как профи")
                        || containsStringValue(item, "Авито Pro"));
                if (hiddenRaffle || hiddenAvitoPro) {
                    iterator.remove();
                }
            }
        } catch (Throwable ignored) {
        }
        return items;
    }

    /** Returns true when an entire Profile Pro group is a configured promo
     * section. Used before conversion so both its heading and rows are omitted. */
    public static boolean hideProfilePromoGroup(Object group) {
        if (group == null) {
            return false;
        }
        try {
            return (isEnabled("avito_hide_profile_avito_pro", true)
                    && containsStringValue(group, "Авито Pro"))
                    || (isEnabled("avito_hide_profile_raffle", true)
                    && containsStringValue(group, "Портал призов"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Removes configurable promotional items from the profile API result. Referral
     * cards have a dedicated stable model. The prize portal uses the broader
     * RewardsItem model, so its exact title is checked as well.
     */
    public static java.util.List<?> withoutProfilePromoItems(java.util.List<?> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        try {
            boolean hideRaffle = isEnabled("avito_hide_profile_raffle", true);
            boolean hideReferrals = isEnabled("avito_hide_profile_referrals", true);
            boolean hideAvitoPro = isEnabled("avito_hide_profile_avito_pro", true);
            if (!hideRaffle && !hideReferrals && !hideAvitoPro) {
                return items;
            }
            java.util.ArrayList<Object> kept = null;
            for (int index = 0; index < items.size(); index++) {
                Object item = items.get(index);
                String type = item != null ? item.getClass().getName() : "";
                boolean hiddenReferral = hideReferrals
                        && "com.avito.android.remote.model.user_profile.items.ReferralEntryPoint".equals(type);
                boolean hiddenRaffle = hideRaffle
                        && "com.avito.android.remote.model.user_profile.items.RewardsItem".equals(type)
                        && containsStringValue(item, "Портал призов");
                boolean hiddenAvitoPro = hideAvitoPro
                        && "com.avito.android.remote.model.user_profile.items.AvitoProItem".equals(type);
                boolean hidden = hiddenReferral || hiddenRaffle || hiddenAvitoPro;
                if (hidden) {
                    if (kept == null) {
                        kept = new java.util.ArrayList<>(items.size() - 1);
                        kept.addAll(items.subList(0, index));
                    }
                } else if (kept != null) {
                    kept.add(item);
                }
            }
            return kept != null ? kept : items;
        } catch (Throwable ignored) {
            return items;
        }
    }

    /**
     * Gate for the "single-row home categories" feature, injected into the
     * rubricator tile's getRowLine(): when on, every tile reports row 1 so the
     * category rubricator collapses to one row. Off → stock (two rows).
     */
    public static boolean singleRowCategories() {
        return isEnabled("avito_single_row_categories", true);
    }

    /**
     * Suppresses Avito's server-driven onboarding carousel drawers before their
     * first rendered frame. The dialog is returned unchanged so disabling the
     * setting restores stock behaviour without rebuilding.
     */
    public static android.app.Dialog suppressOnboardingDrawer(android.app.Dialog dialog) {
        if (dialog == null || !isEnabled("avito_hide_launch_drawers", true)) {
            return dialog;
        }
        try {
            dialog.setOnShowListener(shown -> {
                try {
                    shown.dismiss();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        return dialog;
    }

    /**
     * Removes the server-driven "Знак добра" cards from a SERP list. Both the
     * compact header card and the larger in-feed card carry the same campaign
     * marker in their Beduin model, so one model-level filter covers both without
     * leaving an empty RecyclerView row.
     */
    public static java.util.List<?> withoutKindnessBanners(java.util.List<?> items) {
        if (items == null || items.isEmpty() || !isEnabled("avito_hide_kindness_banners", true)) {
            return items;
        }
        try {
            java.util.ArrayList<Object> kept = null;
            for (int index = 0; index < items.size(); index++) {
                Object item = items.get(index);
                boolean hidden = String.valueOf(item).contains("Знак добра");
                if (hidden) {
                    if (kept == null) {
                        kept = new java.util.ArrayList<>(items.size());
                        kept.addAll(items.subList(0, index));
                    }
                } else if (kept != null) {
                    kept.add(item);
                }
            }
            return kept != null ? kept : items;
        } catch (Throwable ignored) {
            return items;
        }
    }

    /**
     * Rendered-text fallback for Beduin/promo models that keep their visible copy
     * outside the adapter item's toString(). The bind hook runs before Avito binds
     * the item, so inspection is posted until afterwards. Previously collapsed
     * generic holders are restored before reuse.
     */
    private static void updateKindnessBanner(Object viewHolder) {
        try {
            final android.view.View root = Blacklist.itemViewOf(viewHolder);
            if (root == null) {
                return;
            }
            restoreKindnessView(root);
            if (!isEnabled("avito_hide_kindness_banners", true)) {
                return;
            }
            root.post(() -> {
                try {
                    if (containsViewText(root, "Знак добра")) {
                        collapseKindnessView(root);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean containsViewText(android.view.View view, String marker) {
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.toString().contains(marker)) {
                return true;
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsViewText(group.getChildAt(index), marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collapseKindnessView(android.view.View view) {
        if (HIDDEN_KINDNESS_VIEWS.containsKey(view)) {
            return;
        }
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) {
            return;
        }
        boolean fullSpan = false;
        try {
            fullSpan = (Boolean) params.getClass().getMethod("isFullSpan").invoke(params);
        } catch (Throwable ignored) {
        }
        HIDDEN_KINDNESS_VIEWS.put(
                view,
                new HiddenKindnessViewState(params.width, params.height, view.getVisibility(), fullSpan));
        try {
            params.getClass().getMethod("setFullSpan", boolean.class).invoke(params, true);
        } catch (Throwable ignored) {
        }
        params.height = 0;
        view.setLayoutParams(params);
        view.setVisibility(android.view.View.GONE);
    }

    private static void restoreKindnessView(android.view.View view) {
        HiddenKindnessViewState state = HIDDEN_KINDNESS_VIEWS.remove(view);
        if (state == null) {
            return;
        }
        try {
            android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                params.width = state.width;
                params.height = state.height;
                try {
                    params.getClass().getMethod("setFullSpan", boolean.class)
                            .invoke(params, state.fullSpan);
                } catch (Throwable ignored) {
                }
                view.setLayoutParams(params);
            }
            view.setVisibility(state.visibility);
        } catch (Throwable ignored) {
        }
    }

    private static final class HiddenKindnessViewState {
        final int width;
        final int height;
        final int visibility;
        final boolean fullSpan;

        HiddenKindnessViewState(int width, int height, int visibility, boolean fullSpan) {
            this.width = width;
            this.height = height;
            this.visibility = visibility;
            this.fullSpan = fullSpan;
        }
    }

    /**
     * Returns the Favorites tab list without the "Подписки" (subscribed sellers)
     * tab when the toggle is on, otherwise the list unchanged. Injected at the
     * entry of the presenter method that consumes the tab list and populates the
     * tab strip, so it works regardless of how/where the list was built. Returns a
     * fresh list (rather than mutating) so an immutable input is handled too. The
     * tab is identified by its runtime class ({@code …adapter.sellers.SellersTab})
     * — no title string hard-coded. Applies when the Favorites screen is opened.
     */
    public static java.util.List<?> withoutHiddenFavoritesTabs(java.util.List<?> tabs) {
        try {
            if (tabs == null || tabs.isEmpty()) {
                return tabs;
            }
            boolean hideSubscriptions = isEnabled("avito_hide_subscriptions_tab", true);
            boolean hideCollections = isEnabled("avito_hide_collections_tab", true);
            if (!hideSubscriptions && !hideCollections) {
                return tabs;
            }
            java.util.ArrayList<Object> kept = filterFavoritesTabItems(tabs, hideSubscriptions, hideCollections);
            return kept;
        } catch (Throwable ignored) {
            return tabs;
        }
    }

    public static java.util.List<?> withoutSubscriptionsTab(java.util.List<?> tabs) {
        return withoutHiddenFavoritesTabs(tabs);
    }

    public static Object withoutHiddenFavoritesTabsControlState(Object state) {
        try {
            if (state == null) {
                return null;
            }
            boolean hideSubscriptions = isEnabled("avito_hide_subscriptions_tab", true);
            boolean hideCollections = isEnabled("avito_hide_collections_tab", true);
            if (!hideSubscriptions && !hideCollections) {
                return state;
            }

            java.util.ArrayList<java.lang.reflect.Field> listFields = new java.util.ArrayList<>(2);
            java.lang.reflect.Field selectedField = null;
            for (java.lang.reflect.Field field : state.getClass().getDeclaredFields()) {
                Class<?> type = field.getType();
                if (java.util.ArrayList.class.isAssignableFrom(type)) {
                    listFields.add(field);
                } else if (type == Integer.class) {
                    selectedField = field;
                }
            }
            if (listFields.size() < 2 || selectedField == null) {
                return state;
            }

            java.util.ArrayList<?> visible = getArrayList(state, listFields.get(0));
            java.util.ArrayList<?> hidden = getArrayList(state, listFields.get(1));
            if (visible == null || hidden == null) {
                return state;
            }

            java.util.ArrayList<Object> filteredVisible =
                    filterFavoritesTabItems(visible, hideSubscriptions, hideCollections);
            java.util.ArrayList<Object> filteredHidden =
                    filterFavoritesTabItems(hidden, hideSubscriptions, hideCollections);

            selectedField.setAccessible(true);
            Integer selected = (Integer) selectedField.get(state);
            if (selected != null && !containsFavoritesTabId(filteredVisible, selected.intValue())) {
                selected = firstFavoritesTabId(filteredVisible);
            }

            return state.getClass()
                    .getConstructor(java.util.ArrayList.class, Integer.class, boolean.class, java.util.ArrayList.class)
                    .newInstance(filteredVisible, selected, !filteredHidden.isEmpty(), filteredHidden);
        } catch (Throwable ignored) {
            return state;
        }
    }

    public static void updateFavoritesTabView(Object tabView, Object tab) {
        try {
            updateFavoritesTabVisibility(tabView, shouldHideFavoritesTab(
                    tab,
                    isEnabled("avito_hide_subscriptions_tab", true),
                    isEnabled("avito_hide_collections_tab", true)));
        } catch (Throwable ignored) {
        }
    }

    public static void updateFavoritesTabViewByTitle(Object tabView, String title) {
        try {
            boolean hideSubscriptions = isEnabled("avito_hide_subscriptions_tab", true);
            boolean hideCollections = isEnabled("avito_hide_collections_tab", true);
            boolean hidden = (hideSubscriptions && ("Подписки".equals(title) || "Лента".equals(title)))
                    || (hideCollections && "Подборки".equals(title));
            updateFavoritesTabVisibility(tabView, hidden);
        } catch (Throwable ignored) {
        }
    }

    private static void updateFavoritesTabVisibility(Object tabView, boolean hidden) {
        android.view.View root = firstViewField(tabView);
        if (root == null) {
            return;
        }
        root.setVisibility(hidden ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private static boolean shouldHideFavoritesTab(Object tab, boolean hideSubscriptions, boolean hideCollections) {
        if (tab == null) {
            return false;
        }
        int id = favoritesTabId(tab);
        if (hideSubscriptions && id == FAVORITES_SUBSCRIPTIONS_TAB_ID) {
            return true;
        }
        if (hideCollections && id == FAVORITES_COLLECTIONS_TAB_ID) {
            return true;
        }
        if (hideSubscriptions && tab.getClass().getName().endsWith(".SellersTab")) {
            return true;
        }
        java.util.LinkedHashSet<String> values = stringValues(tab);
        if (hideSubscriptions && (values.contains("Подписки") || values.contains("Лента"))) {
            return true;
        }
        return hideCollections && values.contains("Подборки");
    }

    private static java.util.ArrayList<Object> filterFavoritesTabItems(
            java.util.List<?> tabs,
            boolean hideSubscriptions,
            boolean hideCollections) {
        java.util.ArrayList<Object> kept = new java.util.ArrayList<>(tabs.size());
        for (Object tab : tabs) {
            if (shouldHideFavoritesTab(tab, hideSubscriptions, hideCollections)) {
                continue;
            }
            kept.add(tab);
        }
        return kept;
    }

    private static java.util.ArrayList<?> getArrayList(Object target, java.lang.reflect.Field field)
            throws IllegalAccessException {
        field.setAccessible(true);
        Object value = field.get(target);
        return (value instanceof java.util.ArrayList) ? (java.util.ArrayList<?>) value : null;
    }

    private static boolean containsFavoritesTabId(java.util.List<?> tabs, int id) {
        for (Object tab : tabs) {
            if (favoritesTabId(tab) == id) {
                return true;
            }
        }
        return false;
    }

    private static Integer firstFavoritesTabId(java.util.List<?> tabs) {
        for (Object tab : tabs) {
            int id = favoritesTabId(tab);
            if (id > 0) {
                return Integer.valueOf(id);
            }
        }
        return null;
    }

    private static int favoritesTabId(Object target) {
        if (target == null) {
            return -1;
        }
        try {
            Class<?> current = target.getClass();
            while (current != null && current != Object.class) {
                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    if (field.getType() != int.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    int value = field.getInt(target);
                    if (value > 0 && value < 100) {
                        return value;
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static android.view.View firstViewField(Object target) {
        if (target == null) {
            return null;
        }
        try {
            Class<?> current = target.getClass();
            while (current != null && current != Object.class) {
                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    if (field.getType() != android.view.View.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value instanceof android.view.View) {
                        return (android.view.View) value;
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> current = target.getClass();
            while (current != null && current != Object.class) {
                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    if (!android.view.View.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value instanceof android.view.View) {
                        return (android.view.View) value;
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static java.util.LinkedHashSet<String> stringValues(Object target) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        try {
            for (java.lang.reflect.Method method : target.getClass().getMethods()) {
                if (method.getParameterTypes().length != 0 || method.getReturnType() != String.class) {
                    continue;
                }
                Object value = method.invoke(target);
                if (value instanceof String) {
                    values.add((String) value);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> current = target.getClass();
            while (current != null && current != Object.class) {
                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    if (field.getType() != String.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value instanceof String) {
                        values.add((String) value);
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return values;
    }

    private static boolean containsStringValue(Object target, String marker) {
        if (marker == null || marker.isEmpty()) {
            return false;
        }
        for (String value : stringValues(target)) {
            if (value != null && value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Settings-screen integration (called from patched bytecode)
    // ---------------------------------------------------------------------

    /**
     * Appends the single "Настройки Morphe" row to Avito's settings list. Clones
     * the runtime class of an existing navigation row (found by its stable
     * "notifications" id) so no obfuscated class name is hard-coded, and inserts it
     * first — followed by a divider so it doesn't sit flush against the next row.
     *
     * Dividers in this list are their own items (a "Divider" model with a
     * {@code divider_<n>} id), one after each row, rather than a flag on the row
     * itself. We clone that model the same way (found by its {@code divider_} id) and
     * insert it right after our row.
     */
    public static void addSettingsEntry(java.util.List<?> items) {
        try {
            if (items == null || items.isEmpty()) {
                return;
            }
            Object navTemplate = null;
            Object dividerTemplate = null;
            for (Object existing : items) {
                String id = Blacklist.callString(existing, "getStringId");
                if (id == null) {
                    continue;
                }
                if (navTemplate == null && "notifications".equals(id)) {
                    navTemplate = existing;
                } else if (dividerTemplate == null && id.startsWith("divider_")) {
                    dividerTemplate = existing;
                }
            }
            if (navTemplate == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            java.util.List<Object> mutable = (java.util.List<Object>) items;
            Object row = navTemplate.getClass()
                    .getConstructor(String.class, String.class)
                    .newInstance(SETTINGS_ENTRY_ID, SETTINGS_ENTRY_TITLE);
            mutable.add(0, row);
            if (dividerTemplate != null) {
                Object divider = dividerTemplate.getClass()
                        .getConstructor(String.class)
                        .newInstance("divider_morphe");
                mutable.add(1, divider);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Konveyor adapter-presenter bind hook. Wires our injected Settings row to open
     * the Morphe settings screen; everything else is delegated to the blacklist
     * feature's advert handler (long-press to block, collapse blocked tiles).
     */
    public static void onBind(Object viewHolder, Object item) {
        try {
            if (viewHolder == null || item == null) {
                return;
            }
            if (SETTINGS_ENTRY_ID.equals(Blacklist.callString(item, "getStringId"))) {
                wireSettingsRow(Blacklist.itemViewOf(viewHolder));
                return;
            }
        } catch (Throwable ignored) {
        }
        updateKindnessBanner(viewHolder);
        // Collapse the leftover "Реклама скрыта" empty-ad stub (ads are removed).
        AdCleanup.onBind(viewHolder);
        Blacklist.onBindAdvert(viewHolder, item);
        app.avito.sellerfilter.ProfessionalSellerFilter.onBind(viewHolder, item);
    }

    private static void wireSettingsRow(final android.view.View root) {
        if (root == null) {
            return;
        }
        // Detect the tap with a GestureDetector fed by a non-consuming touch
        // listener (returns false), so it works regardless of whatever click/touch
        // handling Avito's settings binder installs (and across app versions).
        final android.view.GestureDetector detector = new android.view.GestureDetector(
                root.getContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName(root.getContext().getPackageName(), SETTINGS_ACTIVITY);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            root.getContext().startActivity(intent);
                        } catch (Throwable ignored) {
                        }
                        return false;
                    }
                });
        final android.view.View.OnTouchListener touch =
                new android.view.View.OnTouchListener() {
                    @Override
                    public boolean onTouch(android.view.View v, android.view.MotionEvent ev) {
                        detector.onTouchEvent(ev);
                        return false;
                    }
                };
        root.post(new Runnable() {
            @Override
            public void run() {
                Blacklist.attachTouchRecursive(root, touch);
            }
        });
    }

    // ---------------------------------------------------------------------
    // Context / storage
    // ---------------------------------------------------------------------

    @SuppressLint("PrivateApi")
    private static Context context() {
        if (appContext != null) {
            return appContext;
        }
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (app instanceof Context) {
                appContext = ((Context) app).getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return appContext;
    }

    private static SharedPreferences prefs() {
        Context ctx = context();
        return ctx == null ? null : ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
