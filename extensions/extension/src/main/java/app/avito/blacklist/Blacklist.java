package app.avito.blacklist;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime blacklist for Avito offers (adverts) and sellers (users).
 *
 * <p>Mirrors the data model of the "Ave Blacklist" browser extension:
 * <ul>
 *   <li>offers are identified by their numeric advert id, stored with the
 *       {@code _blacklist_ad} suffix in exported databases;</li>
 *   <li>sellers are identified by their long {@code userKey} hash, stored with
 *       the {@code _blacklist_user} suffix.</li>
 * </ul>
 *
 * <p>All feed-facing entry points are defensive: any failure leaves the feed
 * untouched (fail-open) so a blacklist bug can never break the app.
 */
@SuppressWarnings("unused")
public final class Blacklist {

    public static final String PREFS_NAME = "avito_blacklist";
    private static final String KEY_OFFERS = "offers";
    private static final String KEY_SELLERS = "sellers";

    /** Suffixes used by the browser extension's export/import format. */
    public static final String SUFFIX_OFFER = "_blacklist_ad";
    public static final String SUFFIX_SELLER = "_blacklist_user";

    private static final Object LOCK = new Object();

    private static volatile boolean loaded = false;
    private static Context appContext;
    private static final Set<String> blockedOffers = new LinkedHashSet<>();
    private static final Set<String> blockedSellers = new LinkedHashSet<>();

    private Blacklist() {
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
            // No context yet; callers fail open.
        }
        return appContext;
    }

    private static SharedPreferences prefs() {
        Context ctx = context();
        if (ctx == null) {
            return null;
        }
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            SharedPreferences prefs = prefs();
            if (prefs == null) {
                // Context not ready yet: do not flip "loaded" so we retry later.
                return;
            }
            readInto(blockedOffers, prefs.getString(KEY_OFFERS, null));
            readInto(blockedSellers, prefs.getString(KEY_SELLERS, null));
            loaded = true;
        }
    }

    private static void readInto(Set<String> target, String json) {
        target.clear();
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String value = arr.optString(i, null);
                if (value != null && !value.isEmpty()) {
                    target.add(value);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void persist() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .putString(KEY_OFFERS, new JSONArray(blockedOffers).toString())
                .putString(KEY_SELLERS, new JSONArray(blockedSellers).toString())
                .apply();
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    public static boolean isOfferBlocked(String offerId) {
        if (offerId == null || offerId.isEmpty()) {
            return false;
        }
        ensureLoaded();
        synchronized (LOCK) {
            return blockedOffers.contains(offerId);
        }
    }

    public static boolean isSellerBlocked(String userKey) {
        if (userKey == null || userKey.isEmpty()) {
            return false;
        }
        ensureLoaded();
        synchronized (LOCK) {
            return blockedSellers.contains(userKey);
        }
    }

    public static List<String> getOffers() {
        ensureLoaded();
        synchronized (LOCK) {
            List<String> list = new ArrayList<>(blockedOffers);
            Collections.sort(list);
            return list;
        }
    }

    public static List<String> getSellers() {
        ensureLoaded();
        synchronized (LOCK) {
            return new ArrayList<>(blockedSellers);
        }
    }

    public static int offerCount() {
        ensureLoaded();
        synchronized (LOCK) {
            return blockedOffers.size();
        }
    }

    public static int sellerCount() {
        ensureLoaded();
        synchronized (LOCK) {
            return blockedSellers.size();
        }
    }

    // ---------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------

    public static boolean addOffer(String offerId) {
        if (offerId == null) {
            return false;
        }
        offerId = offerId.trim();
        if (offerId.isEmpty()) {
            return false;
        }
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedOffers.add(offerId)) {
                persist();
                return true;
            }
            return false;
        }
    }

    public static boolean removeOffer(String offerId) {
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedOffers.remove(offerId)) {
                persist();
                return true;
            }
            return false;
        }
    }

    public static boolean addSeller(String userKey) {
        if (userKey == null) {
            return false;
        }
        userKey = userKey.trim();
        if (userKey.isEmpty()) {
            return false;
        }
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedSellers.add(userKey)) {
                persist();
                return true;
            }
            return false;
        }
    }

    public static boolean removeSeller(String userKey) {
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedSellers.remove(userKey)) {
                persist();
                return true;
            }
            return false;
        }
    }

    public static void clear() {
        ensureLoaded();
        synchronized (LOCK) {
            blockedOffers.clear();
            blockedSellers.clear();
            persist();
        }
    }

    // ---------------------------------------------------------------------
    // Feed filtering (called from patched bytecode)
    // ---------------------------------------------------------------------

    /**
     * Removes blacklisted adverts from a list of network SERP elements in place.
     * Each advert element exposes {@code String getId()} and
     * {@code AdvertSellerInfo getSellerInfo()} (whose {@code getUserKey()} is the
     * seller hash). Non-advert elements (banners, widgets) lack {@code getId()}
     * and are left untouched.
     *
     * <p>Fully defensive: any error aborts filtering for that call without
     * throwing into the app's feed pipeline.
     */
    public static void filterSerpElements(List<?> elements) {
        if (elements == null || elements.isEmpty()) {
            return;
        }
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedOffers.isEmpty() && blockedSellers.isEmpty()) {
                return;
            }
        }
        try {
            Iterator<?> it = elements.iterator();
            while (it.hasNext()) {
                Object element = it.next();
                if (element == null) {
                    continue;
                }
                if (shouldHide(element)) {
                    try {
                        it.remove();
                    } catch (Throwable removeFailed) {
                        // Immutable list: stop trying, leave feed intact.
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Never propagate into the feed.
        }
    }

    private static boolean shouldHide(Object element) {
        String offerId = callString(element, "getId");
        if (offerId != null && isOfferBlocked(offerId)) {
            return true;
        }
        Object seller = callObject(element, "getSellerInfo");
        if (seller == null) {
            seller = callObject(element, "getSeller");
        }
        if (seller != null) {
            String userKey = callString(seller, "getUserKey");
            return userKey != null && isSellerBlocked(userKey);
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Long-press to block (called from the konveyor adapter-presenter bind)
    // ---------------------------------------------------------------------

    /**
     * Attaches a long-press "block" handler to advert snippets. Called for every
     * list bind; cheap no-op for non-advert items. Fully defensive.
     *
     * <p>Matches both the legacy {@code AdvertItem} and the redesigned
     * {@code SerpConstructorAdvertItem} (both class names contain "AdvertItem").
     */
    /** Marker id of the blacklist row injected into the app's Settings screen. */
    private static final String SETTINGS_ENTRY_ID = "avito_blacklist";
    private static final String SETTINGS_ENTRY_TITLE = "Чёрный список";
    private static final String BLACKLIST_ACTIVITY = "app.avito.blacklist.BlacklistActivity";

    public static void onBindAdvert(Object viewHolder, Object item) {
        try {
            if (viewHolder == null || item == null) {
                return;
            }
            // Our injected Settings row: wire its click to open the manager.
            if (SETTINGS_ENTRY_ID.equals(callString(item, "getStringId"))) {
                wireSettingsRow(itemViewOf(viewHolder));
                return;
            }
            if (!item.getClass().getName().endsWith("AdvertItem")) {
                return;
            }
            final android.view.View root = itemViewOf(viewHolder);
            if (root == null) {
                return;
            }
            final Object boundItem = item;

            // Track the bound view so blocking a seller can immediately hide all
            // of that seller's currently-visible tiles. Re-apply the hidden state
            // deterministically on every (re)bind so recycled views never leak a
            // collapsed state onto a different advert, and so any blocked item the
            // feed filter missed is still hidden here.
            boundAdvertViews.put(root, boundItem);
            if (isItemBlocked(boundItem)) {
                collapse(root);
            } else {
                restore(root);
            }

            final android.view.View.OnLongClickListener listener =
                    new android.view.View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(android.view.View v) {
                            showBlockDialog(v.getContext(), root, boundItem);
                            return true;
                        }
                    };
            root.setOnLongClickListener(listener);
            // Some snippets (e.g. extended-gallery tiles) contain a scrollable
            // image pager that consumes the long-press, and its child views are
            // added during bind. Re-attach across the whole tree after layout so
            // a long-press anywhere on the tile opens the block menu.
            root.post(new Runnable() {
                @Override
                public void run() {
                    attachLongPressRecursive(root, listener);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void attachLongPressRecursive(android.view.View view, android.view.View.OnLongClickListener listener) {
        try {
            view.setOnLongClickListener(listener);
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    attachLongPressRecursive(group.getChildAt(i), listener);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Appends the "Чёрный список" row to the app's settings list. Called from the
     * patched settings-list builder. Clones the runtime class of an existing
     * navigation row (found by its stable "notifications" id) so no obfuscated
     * class name is hard-coded.
     */
    public static void addSettingsEntry(java.util.List<?> items) {
        try {
            if (items == null || items.isEmpty()) {
                return;
            }
            for (Object existing : items) {
                if ("notifications".equals(callString(existing, "getStringId"))) {
                    Object row = existing.getClass()
                            .getConstructor(String.class, String.class)
                            .newInstance(SETTINGS_ENTRY_ID, SETTINGS_ENTRY_TITLE);
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> mutable = (java.util.List<Object>) items;
                    mutable.add(row);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void wireSettingsRow(final android.view.View root) {
        if (root == null) {
            return;
        }
        // Deferred so this click listener wins over the one the settings binder
        // installs after this bind callback returns.
        root.post(new Runnable() {
            @Override
            public void run() {
                root.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        try {
                            android.content.Intent intent = new android.content.Intent();
                            intent.setClassName(v.getContext().getPackageName(), BLACKLIST_ACTIVITY);
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            v.getContext().startActivity(intent);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        });
    }

    /**
     * Returns the row view of a {@code RecyclerView.ViewHolder}: the public
     * {@code itemView} field, or (if minification renamed it) the View field
     * declared on the minified RecyclerView.ViewHolder base class.
     */
    private static android.view.View itemViewOf(Object viewHolder) {
        try {
            Object value = viewHolder.getClass().getField("itemView").get(viewHolder);
            if (value instanceof android.view.View) {
                return (android.view.View) value;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> c = viewHolder.getClass();
            while (c != null && c != Object.class) {
                if (c.getName().startsWith("androidx.recyclerview.widget.RecyclerView")) {
                    for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                        if (android.view.View.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            Object value = field.get(viewHolder);
                            if (value instanceof android.view.View) {
                                return (android.view.View) value;
                            }
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void showBlockDialog(android.content.Context ctx, final android.view.View root, Object item) {
        final String offerId = offerIdOf(item);
        final String userKey = sellerUserKey(item);

        java.util.List<String> labels = new ArrayList<>();
        final java.util.List<Runnable> actions = new ArrayList<>();
        if (offerId != null && !offerId.isEmpty()) {
            labels.add("Скрыть это объявление");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    addOffer(offerId);
                    collapseMatching(true, offerId);
                    toast(root, "Объявление скрыто");
                }
            });
        }
        if (userKey != null && !userKey.isEmpty()) {
            labels.add("Скрыть все объявления продавца");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    addSeller(userKey);
                    collapseMatching(false, userKey);
                    toast(root, "Объявления продавца скрыты");
                }
            });
        }
        if (actions.isEmpty()) {
            return;
        }
        try {
            new android.app.AlertDialog.Builder(ctx)
                    .setTitle("Чёрный список")
                    .setItems(labels.toArray(new CharSequence[0]), new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            if (which >= 0 && which < actions.size()) {
                                actions.get(which).run();
                            }
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /**
     * The numeric advert id, matching what the feed filter blocks. Both item
     * types expose {@code getId()} (long for the redesigned item, the advert id);
     * falls back to the conveyor {@code getStringId()}.
     */
    private static String offerIdOf(Object item) {
        Object id = callObject(item, "getId");
        if (id instanceof Long || id instanceof Integer) {
            return String.valueOf(id);
        }
        if (id instanceof String && !((String) id).isEmpty()) {
            return (String) id;
        }
        return callString(item, "getStringId");
    }

    /**
     * The seller's {@code userKey}. Works for both the legacy {@code AdvertItem}
     * ({@code AdvertSellerInfo} field) and the redesigned item
     * ({@code getSellerInfo()} -> {@code SellerInfoModel}). The userKey getter is
     * not always present, so it is parsed from the seller object's data-class
     * {@code toString()} ("...userKey=<value>, ..."), which is stable.
     */
    private static String sellerUserKey(Object item) {
        Object seller = callObject(item, "getSellerInfo");
        if (seller == null) {
            seller = sellerFieldOf(item);
        }
        if (seller == null) {
            return null;
        }
        String key = callString(seller, "getUserKey");
        if (key != null && !key.isEmpty()) {
            return key;
        }
        return parseUserKey(seller.toString());
    }

    private static Object sellerFieldOf(Object item) {
        try {
            for (java.lang.reflect.Field field : item.getClass().getDeclaredFields()) {
                if (field.getType().getName().endsWith("SellerInfo")) {
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String parseUserKey(String s) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf("userKey=");
        if (i < 0) {
            return null;
        }
        i += "userKey=".length();
        int j = i;
        while (j < s.length() && s.charAt(j) != ',' && s.charAt(j) != ')') {
            j++;
        }
        String value = s.substring(i, j).trim();
        return (value.isEmpty() || "null".equals(value)) ? null : value;
    }

    /** Advert tiles currently bound to a view, so a block can hide them at once. */
    private static final java.util.Map<android.view.View, Object> boundAdvertViews =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<android.view.View, Object>());
    /** Original heights of collapsed views, to restore them on rebind. */
    private static final java.util.Map<android.view.View, Integer> originalHeights =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<android.view.View, Integer>());

    private static boolean isItemBlocked(Object item) {
        if (offerCount() == 0 && sellerCount() == 0) {
            return false;
        }
        String offerId = offerIdOf(item);
        if (offerId != null && isOfferBlocked(offerId)) {
            return true;
        }
        String userKey = sellerUserKey(item);
        return userKey != null && isSellerBlocked(userKey);
    }

    /** Immediately collapse every currently-bound tile that matches the block. */
    private static void collapseMatching(boolean isOffer, String id) {
        if (id == null) {
            return;
        }
        java.util.List<java.util.Map.Entry<android.view.View, Object>> entries;
        synchronized (boundAdvertViews) {
            entries = new ArrayList<>(boundAdvertViews.entrySet());
        }
        for (java.util.Map.Entry<android.view.View, Object> entry : entries) {
            Object item = entry.getValue();
            String value = isOffer ? offerIdOf(item) : sellerUserKey(item);
            if (id.equals(value)) {
                collapse(entry.getKey());
            }
        }
    }

    private static void collapse(android.view.View view) {
        try {
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                if (!originalHeights.containsKey(view)) {
                    originalHeights.put(view, lp.height);
                }
                lp.height = 0;
                view.setLayoutParams(lp);
            }
            view.setVisibility(android.view.View.GONE);
        } catch (Throwable ignored) {
        }
    }

    /** Undo a previous {@link #collapse}; no-op for views that were never collapsed. */
    private static void restore(android.view.View view) {
        Integer original = originalHeights.remove(view);
        if (original == null) {
            return;
        }
        try {
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.height = original;
                view.setLayoutParams(lp);
            }
            view.setVisibility(android.view.View.VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    private static void toast(android.view.View anchor, String message) {
        try {
            android.widget.Toast.makeText(anchor.getContext(), message, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static String callString(Object target, String method) {
        Object value = callObject(target, method);
        return (value instanceof String) ? (String) value : null;
    }

    private static Object callObject(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Import / export (parity with the browser extension)
    // ---------------------------------------------------------------------

    /**
     * Full-database export, matching the extension's {@code avito_blacklist_database.json}:
     * a JSON object mapping {@code "<id>_blacklist_ad"} / {@code "<userKey>_blacklist_user"}
     * to {@code true}.
     */
    public static String exportFull() {
        ensureLoaded();
        JSONObject obj = new JSONObject();
        synchronized (LOCK) {
            try {
                for (String offer : blockedOffers) {
                    obj.put(offer + SUFFIX_OFFER, true);
                }
                for (String seller : blockedSellers) {
                    obj.put(seller + SUFFIX_SELLER, true);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            return obj.toString(2);
        } catch (Throwable t) {
            return obj.toString();
        }
    }

    /** Export only raw offer ids as a JSON array (extension's offers export). */
    public static String exportOffers() {
        ensureLoaded();
        synchronized (LOCK) {
            return new JSONArray(blockedOffers).toString();
        }
    }

    /** Export only raw seller ids as a JSON array (extension's users export). */
    public static String exportSellers() {
        ensureLoaded();
        synchronized (LOCK) {
            return new JSONArray(blockedSellers).toString();
        }
    }

    /**
     * Imports a blacklist from text. Accepts:
     * <ul>
     *   <li>the full-database object format ({@code {"<id>_blacklist_ad": true, ...}});</li>
     *   <li>a JSON array of raw ids (classified by length: long hashes are
     *       sellers, short numerics are offers, mirroring the extension).</li>
     * </ul>
     *
     * @param replace when true, the current blacklist is cleared first.
     * @return number of newly added entries, or -1 on parse failure.
     */
    public static int importText(String text, boolean replace) {
        if (text == null) {
            return -1;
        }
        text = text.trim();
        if (text.isEmpty()) {
            return -1;
        }
        Set<String> offers = new LinkedHashSet<>();
        Set<String> sellers = new LinkedHashSet<>();
        try {
            if (text.charAt(0) == '[') {
                JSONArray arr = new JSONArray(text);
                for (int i = 0; i < arr.length(); i++) {
                    classifyRaw(arr.optString(i, ""), offers, sellers);
                }
            } else {
                JSONObject obj = new JSONObject(text);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    classifyKey(keys.next(), offers, sellers);
                }
            }
        } catch (Throwable t) {
            return -1;
        }

        ensureLoaded();
        synchronized (LOCK) {
            if (replace) {
                blockedOffers.clear();
                blockedSellers.clear();
            }
            int before = blockedOffers.size() + blockedSellers.size();
            blockedOffers.addAll(offers);
            blockedSellers.addAll(sellers);
            int added = (blockedOffers.size() + blockedSellers.size()) - before;
            persist();
            return added;
        }
    }

    private static void classifyKey(String key, Set<String> offers, Set<String> sellers) {
        if (key == null) {
            return;
        }
        if (key.endsWith(SUFFIX_OFFER)) {
            String id = key.substring(0, key.length() - SUFFIX_OFFER.length()).trim();
            if (!id.isEmpty()) {
                offers.add(id);
            }
        } else if (key.endsWith(SUFFIX_SELLER)) {
            String id = key.substring(0, key.length() - SUFFIX_SELLER.length()).trim();
            if (!id.isEmpty()) {
                sellers.add(id);
            }
        } else {
            classifyRaw(key, offers, sellers);
        }
    }

    private static void classifyRaw(String raw, Set<String> offers, Set<String> sellers) {
        if (raw == null) {
            return;
        }
        raw = raw.trim();
        if (raw.isEmpty()) {
            return;
        }
        // Seller userKeys are long alphanumeric hashes; offer ids are short numerics.
        if (raw.length() >= 25 || !isAllDigits(raw)) {
            sellers.add(raw);
        } else {
            offers.add(raw);
        }
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
