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
 * <p>Offers are identified by their numeric advert id, sellers by their long
 * {@code userKey} hash. Internally and in our own export both are kept as proper
 * structured data (arrays of objects carrying the id, readable labels and the
 * block timestamp — see {@link #exportNative()}). For migration we still
 * <em>import</em> the "Ave Blacklist" browser extension's flatter formats — the
 * {@code "<id>_blacklist_ad": true} object and raw id arrays — but we don't
 * reproduce that lossy schema on export.
 *
 * <p>All feed-facing entry points are defensive: any failure leaves the feed
 * untouched (fail-open) so a blacklist bug can never break the app.
 */
@SuppressWarnings("unused")
public final class Blacklist {

    public static final String PREFS_NAME = "avito_blacklist";
    private static final String KEY_OFFERS = "offers";
    private static final String KEY_SELLERS = "sellers";
    private static final String KEY_OFFER_LABELS = "offer_labels";
    private static final String KEY_SELLER_LABELS = "seller_labels";
    private static final String KEY_SELLER_LINKS = "seller_links";
    private static final String KEY_OFFER_SELLER_LABELS = "offer_seller_labels";
    private static final String KEY_OFFER_TIMES = "offer_times";
    private static final String KEY_SELLER_TIMES = "seller_times";
    private static final String KEY_NAME_BLOCK_WARNING_OFF = "name_block_warning_off";
    private static final String JOB_EMPLOYER_PREFIX = "job_employer:";
    public static final String BRAND_SELLER_PREFIX = "brand:";
    public static final String JOB_EMPLOYER_URI_PREFIX = "job_employer_uri:";
    /**
     * Synthetic seller key for listings whose feed model carries no seller
     * {@code userKey} (Beduin v2 search tiles and some SERP responses expose only
     * the seller's display name).
     */
    public static final String SELLER_NAME_PREFIX = "seller_name:";
    /** Placeholder names Avito shows instead of a real seller name; never matched. */
    private static final Set<String> GENERIC_SELLER_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "пользователь", "продавец", "частное лицо", "компания", "магазин"));

    /** Suffixes used by the browser extension's export/import format. */
    public static final String SUFFIX_OFFER = "_blacklist_ad";
    public static final String SUFFIX_SELLER = "_blacklist_user";

    private static final Object LOCK = new Object();
    private static volatile boolean loaded = false;
    private static Context appContext;
    private static final Set<String> blockedOffers = new LinkedHashSet<>();
    private static final Set<String> blockedSellers = new LinkedHashSet<>();
    // Human-readable labels (offer title / seller name) keyed by id. Local-only
    // metadata, not part of the import/export format.
    private static final java.util.Map<String, String> offerLabels = new java.util.HashMap<>();
    private static final java.util.Map<String, String> sellerLabels = new java.util.HashMap<>();
    private static final java.util.Map<String, String> sellerLinks = new java.util.HashMap<>();
    // Seller name of each blocked offer, keyed by offer id. Shown under the offer
    // in the manager so it's clear who the listing belongs to.
    private static final java.util.Map<String, String> offerSellerLabels = new java.util.HashMap<>();
    // When each id was blocked (epoch millis), keyed by id. Used to sort the
    // manager most-recent-first. Local-only metadata, not part of import/export.
    private static final java.util.Map<String, Long> offerTimes = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> sellerTimes = new java.util.HashMap<>();
    private static final java.util.Map<String, String> seenOfferSellerKeys =
            new java.util.HashMap<>();
    private static final java.util.Map<String, String> seenOfferSellerNames =
            new java.util.HashMap<>();
    private static final java.util.Map<String, String> seenSellerAliasKeys =
            new java.util.HashMap<>();
    private static final java.util.Map<String, String> seenSellerAliasNames =
            new java.util.HashMap<>();

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
            readMap(offerLabels, prefs.getString(KEY_OFFER_LABELS, null));
            readMap(sellerLabels, prefs.getString(KEY_SELLER_LABELS, null));
            readMap(sellerLinks, prefs.getString(KEY_SELLER_LINKS, null));
            readMap(offerSellerLabels, prefs.getString(KEY_OFFER_SELLER_LABELS, null));
            readTimes(offerTimes, prefs.getString(KEY_OFFER_TIMES, null));
            readTimes(sellerTimes, prefs.getString(KEY_SELLER_TIMES, null));
            loaded = true;

            // One-time migration: an earlier version stored the item's internal
            // (often negative) id instead of the advert id. Such entries can't
            // filter or open, so drop any offer id that isn't a plain numeric
            // advert id.
            boolean changed = false;
            Iterator<String> it = blockedOffers.iterator();
            while (it.hasNext()) {
                String id = it.next();
                if (id == null || id.isEmpty() || !isAllDigits(id)) {
                    it.remove();
                    offerLabels.remove(id);
                    offerSellerLabels.remove(id);
                    changed = true;
                }
            }
            if (changed) {
                persist();
            }
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

    private static void readMap(java.util.Map<String, String> target, String json) {
        target.clear();
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = obj.optString(key, null);
                if (value != null && !value.isEmpty()) {
                    target.put(key, value);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void readTimes(java.util.Map<String, Long> target, String json) {
        target.clear();
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long value = obj.optLong(key, 0L);
                if (value > 0L) {
                    target.put(key, value);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void persist() {
        blockedSellerNames = null;
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .putString(KEY_OFFERS, new JSONArray(blockedOffers).toString())
                .putString(KEY_SELLERS, new JSONArray(blockedSellers).toString())
                .putString(KEY_OFFER_LABELS, new JSONObject(offerLabels).toString())
                .putString(KEY_SELLER_LABELS, new JSONObject(sellerLabels).toString())
                .putString(KEY_SELLER_LINKS, new JSONObject(sellerLinks).toString())
                .putString(KEY_OFFER_SELLER_LABELS, new JSONObject(offerSellerLabels).toString())
                .putString(KEY_OFFER_TIMES, new JSONObject(offerTimes).toString())
                .putString(KEY_SELLER_TIMES, new JSONObject(sellerTimes).toString())
                .apply();
    }

    // ---------------------------------------------------------------------
    // Labels (human-readable offer title / seller name)
    // ---------------------------------------------------------------------

    public static String getOfferLabel(String offerId) {
        ensureLoaded();
        synchronized (LOCK) {
            return offerLabels.get(offerId);
        }
    }

    public static String getSellerLabel(String userKey) {
        ensureLoaded();
        synchronized (LOCK) {
            return sellerLabels.get(userKey);
        }
    }

    public static String getSellerLink(String userKey) {
        ensureLoaded();
        synchronized (LOCK) {
            return sellerLinks.get(userKey);
        }
    }

    /** Seller name recorded for a blocked offer (may be null). */
    public static String getOfferSellerLabel(String offerId) {
        ensureLoaded();
        synchronized (LOCK) {
            return offerSellerLabels.get(offerId);
        }
    }

    private static void putOfferLabel(String offerId, String label) {
        if (offerId == null || label == null || label.trim().isEmpty()) {
            return;
        }
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedOffers.contains(offerId) && !label.equals(offerLabels.get(offerId))) {
                offerLabels.put(offerId, label.trim());
                persist();
            }
        }
    }

    private static void putOfferSellerLabel(String offerId, String sellerName) {
        if (offerId == null || sellerName == null || sellerName.trim().isEmpty()) {
            return;
        }
        ensureLoaded();
        synchronized (LOCK) {
            String trimmed = sellerName.trim();
            if (blockedOffers.contains(offerId) && !trimmed.equals(offerSellerLabels.get(offerId))) {
                offerSellerLabels.put(offerId, trimmed);
                persist();
            }
        }
    }

    private static void putSellerLabel(String userKey, String label) {
        if (userKey == null || label == null || label.trim().isEmpty()) {
            return;
        }
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedSellers.contains(userKey) && !label.equals(sellerLabels.get(userKey))) {
                sellerLabels.put(userKey, label.trim());
                persist();
            }
        }
    }

    private static void putSellerLink(String userKey, String link) {
        if (userKey == null || link == null || link.trim().isEmpty()) {
            return;
        }
        ensureLoaded();
        synchronized (LOCK) {
            String trimmed = link.trim();
            if (blockedSellers.contains(userKey) && !trimmed.equals(sellerLinks.get(userKey))) {
                sellerLinks.put(userKey, trimmed);
                persist();
            }
        }
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

    /**
     * Whether the seller's display name is blocked by an explicit name block
     * ({@link #SELLER_NAME_PREFIX}). Name blocks are only created, with a warning,
     * where the feed has no seller id; they then apply to every seller with that
     * name. Sellers blocked by {@code userKey} are never matched by name.
     */
    public static boolean isSellerNameBlocked(String sellerName) {
        Set<String> names = blockedSellerNames();
        if (names.isEmpty()) {
            return false;
        }
        String normalized = normalizeSellerName(sellerName);
        return normalized != null && names.contains(normalized);
    }

    /** Whether any explicit name block exists (cheap pre-check before resolving names). */
    private static boolean hasSellerNameBlocks() {
        return !blockedSellerNames().isEmpty();
    }

    private static Set<String> blockedSellerNames() {
        Set<String> names = blockedSellerNames;
        if (names != null) {
            return names;
        }
        ensureLoaded();
        synchronized (LOCK) {
            names = new java.util.HashSet<>();
            for (String key : blockedSellers) {
                if (key.startsWith(SELLER_NAME_PREFIX)) {
                    names.add(key.substring(SELLER_NAME_PREFIX.length()));
                }
            }
            // persist() resets the cache under LOCK, so a set built here is current.
            if (loaded) {
                blockedSellerNames = names;
            }
            return names;
        }
    }

    /** Names from explicit name blocks; rebuilt lazily after any change. */
    private static volatile Set<String> blockedSellerNames;

    /** Seller blocked by {@code userKey} or by an explicit name block. */
    public static boolean isSellerBlocked(String userKey, String sellerName) {
        return isSellerBlocked(userKey) || isSellerNameBlocked(sellerName);
    }

    /** Unblocks a seller: their {@code userKey} and any name block for their name. */
    public static void removeSeller(String userKey, String sellerName) {
        if (userKey != null) {
            removeSeller(userKey);
        }
        String nameKey = sellerNameKey(sellerName);
        if (nameKey != null) {
            removeSeller(nameKey);
        }
    }

    /** Synthetic {@link #SELLER_NAME_PREFIX} key for a seller known only by name. */
    public static String sellerNameKey(String sellerName) {
        String normalized = normalizeSellerName(sellerName);
        return normalized == null ? null : SELLER_NAME_PREFIX + normalized;
    }

    public static boolean isSellerNameKey(String userKey) {
        return userKey != null && userKey.startsWith(SELLER_NAME_PREFIX);
    }

    private static String normalizeSellerName(String name) {
        if (name == null) {
            return null;
        }
        String value = name.replace('\u00A0', ' ').trim().toLowerCase(java.util.Locale.ROOT)
                .replace('ё', 'е').replaceAll("\\s+", " ");
        if (value.isEmpty() || "null".equals(value) || GENERIC_SELLER_NAMES.contains(value)) {
            return null;
        }
        return value;
    }

    public static List<String> getOffers() {
        ensureLoaded();
        synchronized (LOCK) {
            List<String> list = new ArrayList<>(blockedOffers);
            sortByRecency(list, offerTimes);
            return list;
        }
    }

    public static List<String> getSellers() {
        ensureLoaded();
        synchronized (LOCK) {
            List<String> list = new ArrayList<>(blockedSellers);
            sortByRecency(list, sellerTimes);
            return list;
        }
    }

    /** When the offer was blocked (epoch millis), or 0 if unknown (pre-timestamp entry). */
    public static long getOfferTime(String offerId) {
        ensureLoaded();
        synchronized (LOCK) {
            Long t = offerTimes.get(offerId);
            return t == null ? 0L : t;
        }
    }

    /** When the seller was blocked (epoch millis), or 0 if unknown. */
    public static long getSellerTime(String userKey) {
        ensureLoaded();
        synchronized (LOCK) {
            Long t = sellerTimes.get(userKey);
            return t == null ? 0L : t;
        }
    }

    /** Sorts ids most-recently-blocked first; entries without a timestamp (0) sink
     *  to the bottom, tie-broken by id for a stable order. */
    private static void sortByRecency(List<String> ids, final java.util.Map<String, Long> times) {
        Collections.sort(ids, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                long ta = times.containsKey(a) ? times.get(a) : 0L;
                long tb = times.containsKey(b) ? times.get(b) : 0L;
                if (ta != tb) {
                    return ta > tb ? -1 : 1;
                }
                return a.compareTo(b);
            }
        });
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
                offerTimes.put(offerId, System.currentTimeMillis());
                persist();
                return true;
            }
            return false;
        }
    }

    /** Block an offer and capture its title / seller name so the manager shows a
     *  readable label (used when blocking from the advert detail screen). */
    public static boolean addOffer(String offerId, String title, String sellerName) {
        boolean added = addOffer(offerId);
        putOfferLabel(offerId, title);
        putOfferSellerLabel(offerId, sellerName);
        return added;
    }

    public static boolean removeOffer(String offerId) {
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedOffers.remove(offerId)) {
                offerLabels.remove(offerId);
                offerSellerLabels.remove(offerId);
                offerTimes.remove(offerId);
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
                sellerTimes.put(userKey, System.currentTimeMillis());
                persist();
                return true;
            }
            return false;
        }
    }

    /** Block a seller and capture their display name for the manager label (used
     *  when blocking from the advert detail / seller screen). */
    public static boolean addSeller(String userKey, String name) {
        boolean added = addSeller(userKey);
        putSellerLabel(userKey, name);
        return added;
    }

    public static String sellerKeyForBlocking(Object item) {
        try {
            return sellerUserKey(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String sellerNameForBlocking(Object item) {
        try {
            Object sellerObj = sellerObjectOf(item);
            String sellerName = nameOf(sellerObj);
            if (isBlank(sellerName)) {
                sellerName = seenSellerNameFor(item);
            }
            if (isBlank(sellerName)) {
                sellerName = sellerNameFromConstructor(item);
            }
            if (isBlank(sellerName)) {
                sellerName = jobEmployerNameOf(item);
            }
            return sellerName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void putSellerLinkForBlocking(String userKey, Object item) {
        try {
            putSellerLink(userKey, itemLinkOf(item));
        } catch (Throwable ignored) {
        }
    }

    public static boolean removeSeller(String userKey) {
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedSellers.remove(userKey)) {
                sellerLabels.remove(userKey);
                sellerLinks.remove(userKey);
                sellerTimes.remove(userKey);
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
            offerLabels.clear();
            sellerLabels.clear();
            sellerLinks.clear();
            offerSellerLabels.clear();
            offerTimes.clear();
            sellerTimes.clear();
            persist();
        }
    }

    /** Clears only blocked offers (adverts), leaving blocked sellers intact. */
    public static void clearOffers() {
        ensureLoaded();
        synchronized (LOCK) {
            blockedOffers.clear();
            offerLabels.clear();
            offerSellerLabels.clear();
            offerTimes.clear();
            persist();
        }
    }

    /** Clears only blocked sellers, leaving blocked offers intact. */
    public static void clearSellers() {
        ensureLoaded();
        synchronized (LOCK) {
            blockedSellers.clear();
            sellerLabels.clear();
            sellerTimes.clear();
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
        rememberSeenSellerKeys(elements);
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
                rememberSeenSellerKeys(element);
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

    /**
     * Removes blocked adverts from a converter's OUTPUT list of adapter items
     * (the {@code AdvertItem}s about to populate the grid), using the same robust
     * id/seller resolution as the long-press bind ({@link #isItemBlocked}). This is
     * the reliable place to sanitize every feed (search and home alike): the items
     * are gone before the grid is laid out, so Avito builds it with no gaps —
     * unlike the input {@link #filterSerpElements} pass, whose network-model
     * getters miss some feeds and leave the bind-time collapse to paper over them.
     */
    public static void filterAdvertItems(List<?> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        rememberSeenSellerKeys(items);
        if (offerCount() == 0 && sellerCount() == 0) {
            return;
        }
        try {
            Iterator<?> it = items.iterator();
            while (it.hasNext()) {
                Object item = it.next();
                if (item == null || !isBlockableListingItem(item)) {
                    continue;
                }
                rememberSeenSellerKeys(item);
                if (isItemBlocked(item)) {
                    try {
                        it.remove();
                    } catch (Throwable removeFailed) {
                        // Immutable list: stop trying, leave the grid intact.
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean shouldHide(Object element) {
        String offerId = callString(element, "getId");
        if (offerId != null && isOfferBlocked(offerId)) {
            // Opportunistically capture a readable label (also labels imported ids).
            putOfferLabel(offerId, callString(element, "getTitle"));
            Object offerSeller = callObject(element, "getSellerInfo");
            if (offerSeller == null) {
                offerSeller = callObject(element, "getSeller");
            }
            if (offerSeller != null) {
                putOfferSellerLabel(offerId, nameOf(offerSeller));
            }
            return true;
        }
        Object seller = callObject(element, "getSellerInfo");
        if (seller == null) {
            seller = callObject(element, "getSeller");
        }
        if (seller != null) {
            String userKey = callString(seller, "getUserKey");
            if (userKey != null && isSellerBlocked(userKey)) {
                putSellerLabel(userKey, nameOf(seller));
                return true;
            }
            if (hasSellerNameBlocks() && isSellerNameBlocked(nameOf(seller))) {
                return true;
            }
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
     *
     * <p>Called (via {@code MorpheSettings.onBind}) for every list bind that isn't
     * the Morphe settings row.
     */
    public static void onBindAdvert(Object viewHolder, Object item) {
        onBindAdvert(viewHolder, item, false);
    }

    /**
     * Bind hook for the adverts listed on a seller's profile page. Adds the same
     * long-press block menu, but only individually blocked offers are hidden
     * there: the user opened this seller on purpose, so a seller-level block
     * (or a seller id learned for only some of the adverts) doesn't empty the page.
     */
    public static void onBindSellerProfileAdvert(Object viewHolder, Object item) {
        onBindAdvert(viewHolder, item, true);
    }

    /** Tiles bound on a seller's profile page (see {@link #onBindSellerProfileAdvert}). */
    private static final java.util.Map<android.view.View, Boolean> sellerPageViews =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<android.view.View, Boolean>());

    private static void onBindAdvert(Object viewHolder, Object item, boolean sellerPage) {
        try {
            if (viewHolder == null || item == null) {
                return;
            }
            rememberSeenSellerKeys(item);
            // Seller-page advert items use their own (obfuscated) model; there, any
            // item exposing a numeric advert id is an advert tile.
            boolean blockable = isBlockableListingItem(item)
                    || (sellerPage && !isBlank(offerIdOf(item)));
            if (!blockable) {
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
            if (sellerPage) {
                sellerPageViews.put(root, Boolean.TRUE);
            } else {
                sellerPageViews.remove(root);
            }
            boolean blocked = sellerPage
                    ? isOfferBlocked(offerIdOf(boundItem))
                    : isItemBlocked(boundItem);
            if (blocked) {
                collapse(root);
            } else {
                restore(root);
            }

            // Detect the long-press with a GestureDetector fed by a touch listener
            // that stays NON-consuming for normal taps/clicks/swipes (returns false,
            // so the tile's own handlers run) but SWALLOWS the gesture once the
            // long-press fires — otherwise a tile that opens on tap (notably the big
            // cars/estate gallery tiles) treats the same press as a tap and navigates
            // to the advert on release. setOnLongClickListener is avoided on purpose:
            // it makes every view longClickable, so a leaf that used to let the tap
            // bubble up to the clickable tile swallows it and the advert never opens.
            final boolean[] longPressFired = {false};
            final android.view.GestureDetector detector = new android.view.GestureDetector(
                    root.getContext(),
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(android.view.MotionEvent e) {
                            // Track the gesture so the long-press timer runs on every
                            // variant, including children that don't consume the down.
                            return true;
                        }

                        @Override
                        public void onLongPress(android.view.MotionEvent e) {
                            longPressFired[0] = true;
                            // Subtle haptic tick on proc (respects the system haptic
                            // setting; no VIBRATE permission needed).
                            try {
                                root.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.LONG_PRESS);
                            } catch (Throwable ignored) {
                            }
                            showBlockDialog(root.getContext(), root, boundItem);
                        }
                    });
            final android.view.View.OnTouchListener touch =
                    new android.view.View.OnTouchListener() {
                        @Override
                        public boolean onTouch(android.view.View v, android.view.MotionEvent ev) {
                            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                                longPressFired[0] = false;
                            }
                            detector.onTouchEvent(ev);
                            // Only consume once the long-press has fired, so taps,
                            // clicks and gallery swipes still reach Avito's handlers.
                            return longPressFired[0];
                        }
                    };
            // Some snippets (e.g. extended-gallery tiles) have a scrollable image
            // pager whose child views are added during bind, so re-attach across
            // the whole tree after layout to detect a long-press anywhere on the
            // tile. Returning false means we never replace any view's effective
            // touch handling (its onTouchEvent still runs).
            attachTouchRecursive(root, touch);
            root.post(new Runnable() {
                @Override
                public void run() {
                    attachTouchRecursive(root, touch);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void attachTouchRecursive(android.view.View view, android.view.View.OnTouchListener observer) {
        try {
            // CHAIN rather than replace: some tile views — notably the "extended"
            // snippet's swipeable photo gallery — open the advert through their OWN
            // OnTouchListener. A plain setOnTouchListener would overwrite it, so
            // tapping the photos would do nothing. We preserve any existing listener
            // and call it after feeding our (non-consuming) long-press observer.
            android.view.View.OnTouchListener existing = existingTouchListener(view);
            android.view.View.OnTouchListener original =
                    (existing instanceof ChainTouch) ? ((ChainTouch) existing).original : existing;
            view.setOnTouchListener(new ChainTouch(observer, original));
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    attachTouchRecursive(group.getChildAt(i), observer);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Feeds touches to our long-press observer first. While the observer stays
     * passive (normal taps/swipes) the event is delegated to the view's pre-existing
     * OnTouchListener so its native behaviour — e.g. the photo gallery's tap-to-open
     * — is preserved. Once the observer claims the gesture (a long-press fired), the
     * event is swallowed and NOT passed on, so the tile can't also navigate.
     */
    private static final class ChainTouch implements android.view.View.OnTouchListener {
        private final android.view.View.OnTouchListener observer;
        final android.view.View.OnTouchListener original;

        ChainTouch(android.view.View.OnTouchListener observer, android.view.View.OnTouchListener original) {
            this.observer = observer;
            this.original = original;
        }

        @Override
        public boolean onTouch(android.view.View v, android.view.MotionEvent ev) {
            boolean consumed = false;
            try {
                consumed = observer.onTouch(v, ev);
            } catch (Throwable ignored) {
            }
            if (consumed) {
                return true;
            }
            try {
                if (original != null) {
                    return original.onTouch(v, ev);
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    /** Reads a View's current OnTouchListener (hidden field) so it can be chained. */
    private static android.view.View.OnTouchListener existingTouchListener(android.view.View view) {
        try {
            java.lang.reflect.Field liField = android.view.View.class.getDeclaredField("mListenerInfo");
            liField.setAccessible(true);
            Object listenerInfo = liField.get(view);
            if (listenerInfo == null) {
                return null;
            }
            java.lang.reflect.Field otField = listenerInfo.getClass().getDeclaredField("mOnTouchListener");
            otField.setAccessible(true);
            Object value = otField.get(listenerInfo);
            return (value instanceof android.view.View.OnTouchListener)
                    ? (android.view.View.OnTouchListener) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Returns the row view of a {@code RecyclerView.ViewHolder}: the public
     * {@code itemView} field, or (if minification renamed it) the View field
     * declared on the minified RecyclerView.ViewHolder base class.
     */
    public static android.view.View itemViewOf(Object viewHolder) {
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

    private static void showBlockDialog(android.content.Context ctx, final android.view.View root, final Object item) {
        final String offerId = offerIdOf(item);
        String resolvedKey = sellerUserKey(item);
        final String offerTitle = listingTitleOf(item);
        final Object sellerObj = sellerObjectOf(item);
        String sellerName = nameOf(sellerObj);
        if (isBlank(sellerName)) {
            sellerName = seenSellerNameFor(item);
        }
        if (isBlank(sellerName)) {
            // The redesigned tile leaves SellerInfoModel.displayName empty and
            // shows the store name in the Beduin freeForm tree instead.
            sellerName = sellerNameFromConstructor(item);
        }
        if (isBlank(sellerName)) {
            sellerName = jobEmployerNameOf(item);
        }
        final String sellerNameFinal = sellerName;
        if (isBlank(resolvedKey)) {
            // No userKey in the feed model: block by the visible seller name.
            resolvedKey = sellerNameKey(sellerNameFinal);
        }
        final String userKey = resolvedKey;

        java.util.List<String> labels = new ArrayList<>();
        final java.util.List<Runnable> actions = new ArrayList<>();
        if (offerId != null && !offerId.isEmpty()) {
            labels.add("Скрыть это объявление");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    addOffer(offerId);
                    putOfferLabel(offerId, offerTitle);
                    putOfferSellerLabel(offerId, sellerNameFinal);
                    collapseMatching(true, offerId);
                    undoBar(root, "Объявление скрыто", "common_ic_block_24", new Runnable() {
                        @Override
                        public void run() {
                            removeOffer(offerId);
                            restoreMatching(true, offerId);
                        }
                    });
                }
            });
        }
        if (userKey != null && !userKey.isEmpty()) {
            final boolean byName = isSellerNameKey(userKey);
            final Runnable blockSeller = new Runnable() {
                @Override
                public void run() {
                    addSeller(userKey);
                    putSellerLabel(userKey, sellerNameFinal);
                    putSellerLink(userKey, itemLinkOf(item));
                    collapseMatching(false, userKey);
                    String who = isBlank(sellerNameFinal) ? "Продавец" : sellerNameFinal;
                    undoBar(root, who + (byName ? " скрыт по имени" : " скрыт"), "common_ic_block_user_24",
                            new Runnable() {
                                @Override
                                public void run() {
                                    removeSeller(userKey);
                                    restoreMatching(false, userKey);
                                }
                            });
                }
            };
            if (byName) {
                // The feed gave no seller id, only the display name: say what that
                // means before blocking (unless the user opted out), since
                // same-named sellers are hidden too.
                final android.content.Context dialogCtx = ctx;
                labels.add("Скрыть продавца по имени");
                actions.add(new Runnable() {
                    @Override
                    public void run() {
                        if (isNameBlockWarningOff()) {
                            blockSeller.run();
                            return;
                        }
                        final android.widget.CheckBox dontShow = dontShowAgainBox(dialogCtx);
                        java.util.List<String> confirmLabels = new ArrayList<>();
                        java.util.List<Runnable> confirmActions = new ArrayList<>();
                        confirmLabels.add("Скрыть по имени");
                        confirmActions.add(new Runnable() {
                            @Override
                            public void run() {
                                if (dontShow.isChecked()) {
                                    setNameBlockWarningOff();
                                }
                                blockSeller.run();
                            }
                        });
                        showRoundedMenu(dialogCtx, nameBlockWarning(sellerNameFinal), dontShow,
                                confirmLabels, confirmActions);
                    }
                });
            } else {
                labels.add("Скрыть все объявления продавца");
                actions.add(blockSeller);
            }
        }
        if (actions.isEmpty()) {
            return;
        }
        showRoundedMenu(ctx, "Чёрный список", labels, actions);
    }

    public static boolean isNameBlockWarningOff() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_NAME_BLOCK_WARNING_OFF, false);
    }

    private static void setNameBlockWarningOff() {
        setNameBlockWarningOff(true);
    }

    /** Turns the name-block warning off ("Больше не показывать") or back on. */
    public static void setNameBlockWarningOff(boolean off) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_NAME_BLOCK_WARNING_OFF, off).apply();
        }
    }

    /** "Больше не показывать" checkbox, tinted with Avito's accent colour. */
    private static android.widget.CheckBox dontShowAgainBox(android.content.Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        android.widget.CheckBox box = new android.widget.CheckBox(ctx);
        box.setText("Больше не показывать");
        box.setTextSize(14f);
        box.setTextColor(avitoAttrColor(ctx, "black", 0xFFFFFFFF));
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(
                avitoAttrColor(ctx, "blue", 0xFF00AAFF)));
        box.setPadding((int) (4 * d), 0, 0, 0);
        return box;
    }

    /** Warning shown before blocking a seller known only by display name. */
    public static String nameBlockWarning(String sellerName) {
        String who = isBlank(sellerName) ? "продавца" : "«" + sellerName.trim() + "»";
        return "Avito не передал ID продавца для этого объявления, поэтому блокировка будет "
                + "по имени " + who + ". Будут скрыты объявления всех продавцов с таким же именем.";
    }

    /**
     * A rounded, Avito-themed action sheet, code-built to match the app's
     * design-system colours (the stock {@link android.app.AlertDialog} has square
     * corners and an off-palette surface). Each label maps to the action at the
     * same index; an extra "Отмена" row dismisses.
     */
    static void showRoundedMenu(android.content.Context ctx, String title,
                                java.util.List<String> labels,
                                final java.util.List<Runnable> actions) {
        showRoundedMenu(ctx, title, null, labels, actions);
    }

    /** As above, with an optional {@code extra} view (e.g. a checkbox) under the title. */
    static void showRoundedMenu(android.content.Context ctx, String title, android.view.View extra,
                                java.util.List<String> labels,
                                final java.util.List<Runnable> actions) {
        try {
            final float d = ctx.getResources().getDisplayMetrics().density;
            int surface = avitoAttrColor(ctx, "white", 0xFF1A1A1A);
            int textPrimary = avitoAttrColor(ctx, "black", 0xFFFFFFFF);
            int textSecondary = avitoAttrColor(ctx, "gray54", 0xFF8C8C8C);

            android.widget.LinearLayout content = new android.widget.LinearLayout(ctx);
            content.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(surface);
            bg.setCornerRadius(22f * d);
            content.setBackground(bg);
            content.setPadding(0, (int) (12 * d), 0, (int) (8 * d));

            android.widget.TextView header = new android.widget.TextView(ctx);
            header.setText(title);
            header.setTextColor(textSecondary);
            header.setTextSize(13f);
            header.setPadding((int) (24 * d), (int) (6 * d), (int) (24 * d), (int) (10 * d));
            content.addView(header);
            if (extra != null) {
                android.widget.LinearLayout.LayoutParams extraLp = new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                extraLp.leftMargin = extraLp.rightMargin = (int) (20 * d);
                content.addView(extra, extraLp);
            }

            final android.app.Dialog dialog = new android.app.Dialog(ctx);

            android.util.TypedValue ripple = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);

            for (int i = 0; i < labels.size(); i++) {
                final Runnable action = actions.get(i);
                android.widget.TextView row = new android.widget.TextView(ctx);
                row.setText(labels.get(i));
                row.setTextColor(textPrimary);
                row.setTextSize(16f);
                row.setPadding((int) (24 * d), (int) (15 * d), (int) (24 * d), (int) (15 * d));
                row.setClickable(true);
                if (ripple.resourceId != 0) {
                    row.setBackgroundResource(ripple.resourceId);
                }
                row.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        dialog.dismiss();
                        action.run();
                    }
                });
                content.addView(row);
            }

            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            int margin = (int) (28 * d);
            android.widget.FrameLayout wrap = new android.widget.FrameLayout(ctx);
            // Tapping the dimmed area outside the panel dismisses (no Cancel button);
            // the panel itself is clickable so its own taps don't fall through.
            wrap.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    dialog.dismiss();
                }
            });
            content.setClickable(true);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = margin;
            lp.gravity = android.view.Gravity.CENTER;
            wrap.addView(content, lp);
            dialog.setContentView(wrap);
            dialog.setCanceledOnTouchOutside(true);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0x99000000));
                dialog.getWindow().setLayout(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            }
            dialog.show();
        } catch (Throwable ignored) {
        }
    }

    /** Resolves an Avito design-system colour attribute (e.g. "white"/"black"). */
    private static int avitoAttrColor(android.content.Context ctx, String attrName, int fallback) {
        try {
            int id = ctx.getResources().getIdentifier(attrName, "attr", ctx.getPackageName());
            if (id != 0) {
                android.util.TypedValue tv = new android.util.TypedValue();
                if (ctx.getTheme().resolveAttribute(id, tv, true)) {
                    if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                            && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                        return tv.data;
                    }
                    if (tv.resourceId != 0) {
                        return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /**
     * The real numeric advert/vacancy id, matching what the feed filter blocks and
     * what the browser-extension export uses. The conveyor advert
     * {@code getStringId()} is usually the id string (same value the network
     * model's {@code getId()} returns). Job/vacancy rows sometimes wrap that id in
     * a typed row id or expose it only in their deeplink, so they get a narrow
     * fallback below. The item's plain long {@code getId()} remains deliberately
     * unused for non-job adverts: on regular tiles it is an internal hash-like id.
     */
    private static String offerIdOf(Object item) {
        String stringId = callString(item, "getStringId");
        if (stringId != null && !stringId.isEmpty() && isAllDigits(stringId)) {
            return stringId;
        }
        if (isJobOrVacancyListingItem(item)) {
            String id = firstLongDigitRun(stringId);
            if (id == null) {
                id = firstLongDigitRun(String.valueOf(callObject(item, "getDeepLink")));
            }
            if (id == null) {
                id = firstLongDigitRun(String.valueOf(item));
            }
            if (id != null) {
                return id;
            }
        }
        // Last resort: a string-valued getId() that is itself a numeric advert id.
        // The item's *long* getId() is an internal id (often a hashCode-like value
        // that is not a real advert id and cannot be navigated to or matched), so
        // it is deliberately never used as a fallback.
        Object id = callObject(item, "getId");
        if (id instanceof String && isAllDigits((String) id)) {
            return (String) id;
        }
        return null;
    }

    private static boolean isBlockableListingItem(Object item) {
        if (item == null) {
            return false;
        }
        String name = item.getClass().getName();
        if (name != null && name.contains("AdvertItem")) {
            return true;
        }
        return isJobOrVacancyListingItem(item);
    }

    private static boolean isJobOrVacancyListingItem(Object item) {
        if (item == null) {
            return false;
        }
        String name = item.getClass().getName();
        if (name != null && name.toLowerCase(java.util.Locale.US).contains("vacancy")) {
            return true;
        }
        String s = String.valueOf(item);
        return s.startsWith("JobListItem(")
                || s.startsWith("ApplyToVacancyItem(")
                || s.startsWith("SearchAdvertItem(") && s.contains("Vacancy")
                || s.contains("displayType=Vacancy")
                || s.contains("displayType=CarouselVacancy");
    }

    private static String listingTitleOf(Object item) {
        String title = callString(item, "getTitle");
        if (!isBlank(title)) {
            return title;
        }
        title = parseField(String.valueOf(item), "title=");
        return isBlank(title) ? null : title;
    }

    private static String firstLongDigitRun(String s) {
        if (s == null) {
            return null;
        }
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?<!\\d)(\\d{5,})(?!\\d)")
                    .matcher(s);
            return matcher.find() ? matcher.group(1) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The seller's {@code userKey}. Works for both the legacy {@code AdvertItem}
     * ({@code AdvertSellerInfo} field) and the redesigned item
     * ({@code getSellerInfo()} -> {@code SellerInfoModel}). The userKey getter is
     * not always present, so it is parsed from the seller object's data-class
     * {@code toString()} ("...userKey=<value>, ..."), which is stable.
     */
    private static String sellerUserKey(Object item) {
        String seenKey = seenSellerKeyFor(item);
        if (!isBlank(seenKey)) {
            return seenKey;
        }
        String aliasKey = seenAliasSellerKeyFor(jobEmployerNameOf(item));
        if (!isBlank(aliasKey)) {
            return aliasKey;
        }
        Object seller = sellerObjectOf(item);
        if (seller == null) {
            String brandKey = brandSellerKeyOf(item);
            if (brandKey != null) {
                return brandKey;
            }
            String uriKey = jobEmployerUriKeyOf(item);
            return uriKey != null ? uriKey : jobEmployerNameKeyOf(item);
        }
        String key = callString(seller, "getUserKey");
        if (key != null && !key.isEmpty()) {
            return key;
        }
        key = parseField(seller.toString(), "userKey=");
        if (key != null && !key.isEmpty()) {
            return key;
        }
        String brandKey = brandSellerKeyOf(item);
        if (brandKey != null) {
            return brandKey;
        }
        String uriKey = jobEmployerUriKeyOf(item);
        return uriKey != null ? uriKey : jobEmployerNameKeyOf(item);
    }

    private static String seenSellerKeyFor(Object item) {
        String offerId = offerIdOf(item);
        if (isBlank(offerId)) {
            return null;
        }
        synchronized (LOCK) {
            return seenOfferSellerKeys.get(offerId);
        }
    }

    private static String seenSellerNameFor(Object item) {
        String offerId = offerIdOf(item);
        if (isBlank(offerId)) {
            String aliasName = seenAliasSellerNameFor(jobEmployerNameOf(item));
            return isBlank(aliasName) ? null : aliasName;
        }
        synchronized (LOCK) {
            String name = seenOfferSellerNames.get(offerId);
            if (!isBlank(name)) {
                return name;
            }
        }
        return seenAliasSellerNameFor(jobEmployerNameOf(item));
    }

    private static void rememberSeenSellerKeys(Object item) {
        if (item instanceof Iterable) {
            int count = 0;
            Iterator<?> it = ((Iterable<?>) item).iterator();
            while (it.hasNext() && count < 200) {
                Object nested = it.next();
                count++;
                if (nested != null && nested != item) {
                    rememberSeenSellerKeys(nested);
                }
            }
            return;
        }
        rememberDirectSellerKey(item);
        rememberNestedSellerKeys(item);
    }

    private static void rememberDirectSellerKey(Object item) {
        String offerId = offerIdOf(item);
        if (isBlank(offerId)) {
            return;
        }
        Object seller = sellerObjectOf(item);
        if (seller == null) {
            return;
        }
        String key = callString(seller, "getUserKey");
        if (isBlank(key)) {
            key = parseField(String.valueOf(seller), "userKey=");
        }
        if (isBlank(key)) {
            return;
        }
        synchronized (LOCK) {
            seenOfferSellerKeys.put(offerId, key);
            String label = nameOf(seller);
            if (!isBlank(label)) {
                seenOfferSellerNames.put(offerId, label);
                rememberSellerAliasLocked(label, key);
                migrateLegacySellerBlocksLocked(label, key);
            }
            trimSeenSellerCache();
        }
    }

    /**
     * Remembers the advert/seller pair of an opened advert page, so feeds that
     * carry no seller id (Beduin v2 search tiles) can still hide that advert when
     * its seller is blocked by {@code userKey}.
     */
    public static void rememberAdvertSeller(Object advertDetails) {
        try {
            rememberDirectSellerKey(advertDetails);
        } catch (Throwable ignored) {
        }
    }

    public static String resolveSellerKeyForNavigation(String userKey) {
        ensureLoaded();
        if (isBlank(userKey) || !userKey.startsWith(JOB_EMPLOYER_PREFIX)) {
            return userKey;
        }
        synchronized (LOCK) {
            return resolveLegacySellerKeyLocked(userKey);
        }
    }

    private static String seenAliasSellerKeyFor(String label) {
        String alias = sellerAliasOf(label);
        if (isBlank(alias)) {
            return null;
        }
        synchronized (LOCK) {
            return seenSellerAliasKeys.get(alias);
        }
    }

    private static String seenAliasSellerNameFor(String label) {
        String alias = sellerAliasOf(label);
        if (isBlank(alias)) {
            return null;
        }
        synchronized (LOCK) {
            return seenSellerAliasNames.get(alias);
        }
    }

    private static void rememberSellerAliasLocked(String label, String key) {
        String alias = sellerAliasOf(label);
        if (isBlank(alias) || isBlank(key)) {
            return;
        }
        seenSellerAliasKeys.put(alias, key);
        seenSellerAliasNames.put(alias, label);
    }

    private static void migrateLegacySellerBlocksLocked(String label, String realKey) {
        if (isBlank(label) || isBlank(realKey)) {
            return;
        }
        String labelAlias = sellerAliasOf(label);
        if (isBlank(labelAlias)) {
            return;
        }
        String matchedLegacy = null;
        Iterator<String> it = blockedSellers.iterator();
        while (it.hasNext()) {
            String blocked = it.next();
            if (!isLegacyJobSellerKey(blocked)) {
                continue;
            }
            String legacyAlias = sellerAliasOf(legacyJobSellerLabel(blocked));
            if (aliasesMatch(labelAlias, legacyAlias)) {
                matchedLegacy = blocked;
                it.remove();
                break;
            }
        }
        if (matchedLegacy == null) {
            return;
        }
        boolean hadReal = blockedSellers.contains(realKey);
        if (!hadReal) {
            blockedSellers.add(realKey);
        }
        String oldLabel = sellerLabels.remove(matchedLegacy);
        Long oldTime = sellerTimes.remove(matchedLegacy);
        if (!hadReal || isBlank(sellerLabels.get(realKey))) {
            sellerLabels.put(realKey, isBlank(label) ? oldLabel : label);
        }
        if (!hadReal || !sellerTimes.containsKey(realKey)) {
            if (oldTime != null && oldTime > 0L) {
                sellerTimes.put(realKey, oldTime);
            } else {
                sellerTimes.put(realKey, System.currentTimeMillis());
            }
        }
        persist();
    }

    private static String resolveLegacySellerKeyLocked(String legacyKey) {
        String legacyAlias = sellerAliasOf(legacyJobSellerLabel(legacyKey));
        if (isBlank(legacyAlias)) {
            return legacyKey;
        }
        String direct = seenSellerAliasKeys.get(legacyAlias);
        if (!isBlank(direct)) {
            return direct;
        }
        for (java.util.Map.Entry<String, String> entry : seenSellerAliasKeys.entrySet()) {
            if (aliasesMatch(entry.getKey(), legacyAlias)) {
                return entry.getValue();
            }
        }
        return legacyKey;
    }

    private static boolean isLegacyJobSellerKey(String key) {
        return key != null && key.startsWith(JOB_EMPLOYER_PREFIX);
    }

    private static String legacyJobSellerLabel(String key) {
        if (!isLegacyJobSellerKey(key)) {
            return key;
        }
        String label = sellerLabels.get(key);
        if (!isBlank(label)) {
            return label;
        }
        return key.substring(JOB_EMPLOYER_PREFIX.length());
    }

    private static boolean aliasesMatch(String a, String b) {
        if (isBlank(a) || isBlank(b)) {
            return false;
        }
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private static String sellerAliasOf(String label) {
        String value = cleanEmployerName(label);
        if (isBlank(value)) {
            return null;
        }
        value = value.toLowerCase(java.util.Locale.US).replace('ё', 'е');
        value = value.replaceAll("[^\\p{L}\\p{Nd}]+", " ");
        value = " " + value.replaceAll("\\s+", " ").trim() + " ";
        String[] noise = new String[] {
                "работа", "вакансия", "вакансии", "в", "на", "для", "и", "ооо", "ип"
        };
        for (int i = 0; i < noise.length; i++) {
            value = value.replace(" " + noise[i] + " ", " ");
        }
        value = value.replaceAll("\\s+", "");
        return value.length() < 3 ? null : value;
    }

    private static void rememberNestedSellerKeys(Object holder) {
        if (holder == null) {
            return;
        }
        try {
            Class<?> cls = holder.getClass();
            int inspected = 0;
            while (cls != null && cls != Object.class && inspected < 60) {
                java.lang.reflect.Field[] fields = cls.getDeclaredFields();
                for (int i = 0; i < fields.length && inspected < 60; i++) {
                    java.lang.reflect.Field field = fields[i];
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    inspected++;
                    if (value instanceof Iterable) {
                        int count = 0;
                        Iterator<?> it = ((Iterable<?>) value).iterator();
                        while (it.hasNext() && count < 80) {
                            Object nested = it.next();
                            count++;
                            if (nested != null && nested != holder) {
                                rememberDirectSellerKey(nested);
                            }
                        }
                    } else if (value != null && value.getClass().isArray()) {
                        int len = Math.min(java.lang.reflect.Array.getLength(value), 80);
                        for (int j = 0; j < len; j++) {
                            Object nested = java.lang.reflect.Array.get(value, j);
                            if (nested != null && nested != holder) {
                                rememberDirectSellerKey(nested);
                            }
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void trimSeenSellerCache() {
        if (seenOfferSellerKeys.size() <= 300) {
            return;
        }
        Iterator<String> it = seenOfferSellerKeys.keySet().iterator();
        int remove = seenOfferSellerKeys.size() - 240;
        while (it.hasNext() && remove > 0) {
            String key = it.next();
            it.remove();
            seenOfferSellerNames.remove(key);
            remove--;
        }
    }

    private static String jobEmployerNameKeyOf(Object item) {
        String employer = jobEmployerNameOf(item);
        return isBlank(employer) ? null : jobEmployerKey(employer);
    }

    private static String itemLinkOf(Object item) {
        Object deepLink = callObject(item, "getDeepLink");
        if (deepLink != null) {
            String value = String.valueOf(deepLink).trim();
            if (!value.isEmpty() && !"null".equals(value)) {
                return value;
            }
        }
        String offerId = offerIdOf(item);
        if (!isBlank(offerId)) {
            return "https://www.avito.ru/items/" + offerId;
        }
        return null;
    }






    private static Object sellerObjectOf(Object item) {
        Object seller = callObject(item, "getSellerInfo");
        if (seller == null) {
            seller = callObject(item, "getSeller");
        }
        if (seller == null) {
            seller = sellerFieldOf(item);
        }
        return seller;
    }

    /** The seller's display name, for a readable label. */
    private static String nameOf(Object seller) {
        if (seller == null) {
            return null;
        }
        String name = callString(seller, "getDisplayName");
        if (isBlank(name)) {
            name = callString(seller, "getName");
        }
        if (isBlank(name)) {
            name = parseField(seller.toString(), "displayName=");
        }
        if (isBlank(name)) {
            name = parseField(seller.toString(), "name=");
        }
        return isBlank(name) ? null : name;
    }

    /**
     * Extracts the seller/store name shown on a redesigned advert tile
     * ({@code SerpConstructorAdvertItem}). The {@code SellerInfoModel.displayName}
     * is empty for these; the visible name lives in the Beduin {@code freeForm}
     * tree under a {@code sellerNameAndRating...} container as a
     * {@code TextToken(title=<name>)}. Parsed from the item's {@code toString()},
     * which is the only place it is exposed. Best-effort and fully defensive.
     */
    private static String sellerNameFromConstructor(Object item) {
        try {
            String s = String.valueOf(item);
            int anchor = s.indexOf("sellerNameAndRating");
            if (anchor < 0) {
                return null;
            }
            int t = s.indexOf("title=", anchor);
            if (t < 0) {
                return null;
            }
            t += "title=".length();
            int end = s.indexOf(", overridenAttributes", t);
            if (end < 0) {
                end = s.indexOf(")", t);
            }
            if (end <= t) {
                return null;
            }
            String name = s.substring(t, end).trim();
            return (name.isEmpty() || "null".equals(name)) ? null : name;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Vacancy SERP cards do not expose the normal seller userKey. Use the visible
     * employer/company name as a synthetic seller key so "block seller" still
     * works consistently across job search cards.
     */
    private static String jobEmployerNameOf(Object item) {
        if (!isJobOrVacancyListingItem(item)) {
            return null;
        }
        String direct = firstNonBlank(
                callString(item, "getEmployerName"),
                callString(item, "getCompanyName"),
                parseField(String.valueOf(item), "employerName="),
                parseField(String.valueOf(item), "companyName="),
                parseField(String.valueOf(item), "shopName="),
                parseField(String.valueOf(item), "additionalName="));
        direct = cleanEmployerName(direct);
        if (!isBlank(direct)) {
            return direct;
        }
        return employerNameFromTextTokens(String.valueOf(item), listingTitleOf(item));
    }

    private static String brandSellerKeyOf(Object item) {
        String url = brandUrlOf(item);
        return isBlank(url) ? null : BRAND_SELLER_PREFIX + url;
    }

    private static String jobEmployerKey(String employer) {
        String normalized = cleanEmployerName(employer);
        if (isBlank(normalized)) {
            return null;
        }
        normalized = normalized.toLowerCase(java.util.Locale.US).replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? null : JOB_EMPLOYER_PREFIX + normalized;
    }

    private static String jobEmployerUriKeyOf(Object item) {
        if (!isJobOrVacancyListingItem(item)) {
            return null;
        }
        String uri = jobEmployerUriOf(item);
        return isBlank(uri) ? null : JOB_EMPLOYER_URI_PREFIX + uri.trim();
    }

    private static String jobEmployerUriOf(Object item) {
        Object employer = callObject(item, "getEmployer");
        String direct = uriStringOf(employer);
        if (!isBlank(direct)) {
            return direct;
        }
        employer = fieldObject(item, "f36730e");
        direct = uriStringOf(employer);
        if (!isBlank(direct)) {
            return direct;
        }
        String parsed = parseUriField(String.valueOf(item), "uri=");
        if (!isBlank(parsed) && looksLikeWorkProfileUri(parsed)) {
            return parsed;
        }
        return null;
    }

    private static String uriStringOf(Object holder) {
        if (holder == null) {
            return null;
        }
        Object uri = callObject(holder, "getUri");
        if (uri == null) {
            uri = fieldObject(holder, "uri");
        }
        if (uri == null) {
            return null;
        }
        Object androidUri = callObject(uri, "getUri");
        String value = androidUri != null ? String.valueOf(androidUri) : String.valueOf(uri);
        return looksLikeWorkProfileUri(value) ? value : null;
    }

    private static boolean looksLikeWorkProfileUri(String s) {
        if (s == null) {
            return false;
        }
        String lower = s.toLowerCase(java.util.Locale.US);
        return lower.contains("work") || lower.contains("job") || lower.contains("employer")
                || lower.contains("company") || lower.contains("vacancy") || lower.contains("/brands/");
    }

    private static String brandUrlOf(Object item) {
        String s = String.valueOf(item);
        if (s == null) {
            return null;
        }
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?:https?://(?:www\\.|m\\.)?avito\\.ru)?/brands/[0-9a-fA-F]{16,64}(?:\\?[^\\s,)]+)?")
                    .matcher(s);
            if (matcher.find()) {
                String url = matcher.group();
                if (url.startsWith("/")) {
                    url = "https://www.avito.ru" + url;
                }
                return url;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String employerNameFromTextTokens(String s, String listingTitle) {
        if (s == null) {
            return null;
        }
        String title = cleanEmployerName(listingTitle);
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("title=([^,)]+)")
                    .matcher(s);
            while (matcher.find()) {
                String candidate = cleanEmployerName(matcher.group(1));
                if (isBlank(candidate)) {
                    continue;
                }
                if (title != null && candidate.equalsIgnoreCase(title)) {
                    continue;
                }
                if (looksLikeEmployerName(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean looksLikeEmployerName(String s) {
        if (isBlank(s)) {
            return false;
        }
        String lower = s.toLowerCase(java.util.Locale.US);
        if (lower.contains("отзыв") || lower.contains("график") || lower.contains("опыт")
                || lower.contains("зарплат") || lower.contains("смен") || lower.contains("₽")) {
            return false;
        }
        return !s.matches(".*\\d{2,}.*");
    }

    private static String cleanEmployerName(String s) {
        if (s == null) {
            return null;
        }
        String value = s.trim();
        while (value.startsWith("_")) {
            value = value.substring(1).trim();
        }
        value = value.replace('\u00A0', ' ');
        value = value.replaceAll("\\s+в сети$", "");
        value = value.replaceAll("\\s+", " ").trim();
        return (value.isEmpty() || "null".equals(value)) ? null : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
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

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Extracts {@code key<value>} up to the next ',' or ')' from a data-class toString. */
    private static String parseField(String s, String key) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf(key);
        if (i < 0) {
            return null;
        }
        i += key.length();
        int j = i;
        while (j < s.length() && s.charAt(j) != ',' && s.charAt(j) != ')') {
            j++;
        }
        String value = s.substring(i, j).trim();
        return (value.isEmpty() || "null".equals(value)) ? null : value;
    }

    private static String parseUriField(String s, String key) {
        String value = parseField(s, key);
        if (value == null) {
            return null;
        }
        value = value.trim();
        while (value.endsWith(")")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
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
        if (userKey != null && isSellerBlocked(userKey)) {
            return true;
        }
        String legacyJobKey = jobEmployerNameKeyOf(item);
        if (legacyJobKey != null && isSellerBlocked(legacyJobKey)) {
            return true;
        }
        return hasSellerNameBlocks() && isSellerNameBlocked(sellerNameForBlocking(item));
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
            boolean matches = isOffer
                    ? id.equals(offerIdOf(item))
                    : !sellerPageViews.containsKey(entry.getKey()) && isItemBlocked(item);
            if (matches) {
                collapse(entry.getKey());
            }
        }
        refreshBeduinLists();
    }

    /** Undo a {@link #collapseMatching}: restore every bound tile matching the id. */
    private static void restoreMatching(boolean isOffer, String id) {
        if (id == null) {
            return;
        }
        java.util.List<java.util.Map.Entry<android.view.View, Object>> entries;
        synchronized (boundAdvertViews) {
            entries = new ArrayList<>(boundAdvertViews.entrySet());
        }
        for (java.util.Map.Entry<android.view.View, Object> entry : entries) {
            Object item = entry.getValue();
            boolean matches = isOffer ? id.equals(offerIdOf(item)) : !isItemBlocked(item);
            if (matches) {
                restore(entry.getKey());
            }
        }
        refreshBeduinLists();
    }

    private static void collapse(android.view.View view) {
        try {
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                if (!originalHeights.containsKey(view)) {
                    originalHeights.put(view, lp.height);
                }
                // In the 2-column staggered SERP grid, a 0-height tile still
                // reserves its column slot, leaving an empty gap. Making it
                // full-span collapses that slot so the remaining tiles close up.
                setFullSpan(lp, true);
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
                setFullSpan(lp, false);
                lp.height = original;
                view.setLayoutParams(lp);
            }
            view.setVisibility(android.view.View.VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Toggles {@code StaggeredGridLayoutManager.LayoutParams.setFullSpan} via
     * reflection (so the extension needs no androidx dependency). No-op when the
     * tile isn't in a staggered grid (the method is absent on other LayoutParams).
     */
    private static void setFullSpan(android.view.ViewGroup.LayoutParams lp, boolean full) {
        try {
            lp.getClass().getMethod("setFullSpan", boolean.class).invoke(lp, full);
        } catch (Throwable ignored) {
        }
    }

    /** Block toast with a one-tap "Отменить" action (Snackbar-style; see MorpheBlockMenu). */
    private static void undoBar(android.view.View anchor, String message, String iconName, Runnable onUndo) {
        try {
            app.avito.morphe.MorpheBlockMenu.undoBar(anchor.getContext(), message, iconName, true, onUndo);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Beduin v2 lists (server-driven UI; newer search results screen)
    // ---------------------------------------------------------------------

    /**
     * A Beduin v2 advert tile, exposed through the same getters the rest of the
     * blacklist reads from feed items ({@code getStringId}, {@code getTitle},
     * {@code getSellerInfo}, {@code getDeepLink}). Beduin tiles carry the advert id
     * and the seller's display name, but no seller {@code userKey}.
     */
    public static final class BeduinAdvertItem {
        private final String id;
        private final String title;
        private final String uri;
        private final BeduinSeller seller;

        BeduinAdvertItem(String id, String title, String uri, String sellerName) {
            this.id = id;
            this.title = title;
            this.uri = uri;
            this.seller = new BeduinSeller(sellerName);
        }

        public String getStringId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getDeepLink() {
            return uri;
        }

        public BeduinSeller getSellerInfo() {
            return seller;
        }

        @Override
        public String toString() {
            return "BeduinAdvertItem(id=" + id + ", title=" + title + ")";
        }
    }

    public static final class BeduinSeller {
        private final String displayName;

        BeduinSeller(String displayName) {
            this.displayName = displayName;
        }

        public String getUserKey() {
            return null;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return "BeduinSeller(displayName=" + displayName + ")";
        }
    }

    /** Last unfiltered list submitted to each live Beduin lazy adapter. */
    private static final java.util.Map<Object, List<?>> beduinLists =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, List<?>>());
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> beduinGetItem =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Called at the entry of Beduin v2's {@code LazyComponentAdapter.submitList}
     * with the components about to be displayed. Returns the list without blocked
     * advert tiles (the original list when nothing is blocked). Fail-open.
     */
    public static List<?> filterBeduinComponents(Object adapter, List<?> components) {
        if (components == null) {
            if (adapter != null) {
                beduinLists.remove(adapter);
            }
            return null;
        }
        try {
            if (adapter != null) {
                beduinLists.put(adapter, components);
            }
            if (offerCount() == 0 && sellerCount() == 0) {
                return components;
            }
            ArrayList<Object> kept = null;
            for (int i = 0; i < components.size(); i++) {
                Object component = components.get(i);
                BeduinAdvertItem advert = beduinAdvertOf(component);
                if (advert != null && isBeduinAdvertBlocked(advert)) {
                    if (kept == null) {
                        kept = new ArrayList<>(components.subList(0, i));
                    }
                    continue;
                }
                if (kept != null) {
                    kept.add(component);
                }
            }
            return kept == null ? components : realignBeduinColumns(components, kept);
        } catch (Throwable ignored) {
            return components;
        }
    }

    /**
     * Two-column Beduin grids carry each tile's column in its layout params (an
     * outer-edge side margin, e.g. {@code Indents(start=10, end=0)} for the left
     * column). Dropping blocked tiles shifts the following tiles into the other
     * column with the wrong margins, so re-assign each half-width tile the params
     * of an original tile of the same shape from the column it now lands in.
     * Full-span items (banners, headers) start a new row. Any mismatch keeps the
     * filtered list as is.
     */
    public static List<?> realignBeduinColumns(List<?> original, List<Object> kept) {
        try {
            // Only realign what verifiably is a two-column grid: every sided tile of
            // the original list must sit in the column its margins are for. Rows,
            // pagers and wider grids fail this check and are left alone.
            java.util.Map<String, Object[]> paramsByShape = new java.util.HashMap<>();
            int column = 0;
            for (Object child : original) {
                Object params = beduinParamsOf(child);
                if (params == null || isBeduinHidden(params)) {
                    continue;
                }
                if (!isBeduinGridParams(params)) {
                    return kept;
                }
                if (isBeduinFullSpan(params)) {
                    column = 0;
                    continue;
                }
                int side = beduinParamsSide(params);
                if (side >= 0) {
                    if (side != column) {
                        return kept;
                    }
                    Object[] bySide = paramsByShape.get(beduinParamsShape(params));
                    if (bySide == null) {
                        bySide = new Object[2];
                        paramsByShape.put(beduinParamsShape(params), bySide);
                    }
                    if (bySide[side] == null) {
                        bySide[side] = params;
                    }
                }
                column = (column + 1) % 2;
            }
            if (paramsByShape.isEmpty()) {
                return kept;
            }
            column = 0;
            for (int i = 0; i < kept.size(); i++) {
                Object child = kept.get(i);
                Object params = beduinParamsOf(child);
                if (params == null || isBeduinHidden(params) || !isBeduinGridParams(params)) {
                    continue;
                }
                if (isBeduinFullSpan(params)) {
                    column = 0;
                    continue;
                }
                int side = beduinParamsSide(params);
                if (side >= 0 && side != column) {
                    Object[] bySide = paramsByShape.get(beduinParamsShape(params));
                    Object replacement = bySide == null ? null : bySide[column];
                    Object rebuilt = replacement == null ? null : rebuildBeduinChild(child, params, replacement);
                    if (rebuilt != null) {
                        kept.set(i, rebuilt);
                    }
                }
                column = (column + 1) % 2;
            }
        } catch (Throwable ignored) {
        }
        return kept;
    }

    /** Grid-cell params ({@code span=} is a grid-only field; rows and pagers lack it). */
    private static boolean isBeduinGridParams(Object params) {
        return params != null && params.toString().contains("span=");
    }

    /** Hidden children ({@code layoutVisible=false}) are dropped by the adapter and take no cell. */
    private static boolean isBeduinHidden(Object params) {
        return params.toString().contains("layoutVisible=false");
    }

    private static boolean isBeduinFullSpan(Object params) {
        Integer span = parseIntField(params.toString(), "span=");
        return span != null && span >= 2;
    }

    /** The grid child's layout params (the field whose value prints as {@code Params(...)}). */
    private static Object beduinParamsOf(Object child) {
        if (child == null) {
            return null;
        }
        for (java.lang.reflect.Field field : beduinFieldsOf(child.getClass())) {
            try {
                Object value = field.get(child);
                if (value != null && value.toString().startsWith("Params(")) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 0 = left column margins, 1 = right column margins, -1 = symmetric / none. */
    private static int beduinParamsSide(Object params) {
        if (params == null) {
            return -1;
        }
        String text = params.toString();
        int margin = text.indexOf("margin=Indents(");
        if (margin < 0) {
            return -1;
        }
        String indents = text.substring(margin);
        Integer start = parseIntField(indents, "start=");
        Integer end = parseIntField(indents, "end=");
        if (start == null || end == null || start.equals(end)) {
            return -1;
        }
        return start > end ? 0 : 1;
    }

    /** Params without their margins, so left and right tiles of one kind compare equal. */
    private static String beduinParamsShape(Object params) {
        return params.toString().replaceAll("margin=Indents\\([^)]*\\)", "");
    }

    private static Integer parseIntField(String text, String key) {
        String value = parseField(text, key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Copy of a grid child with other params, via its (component, params) constructor. */
    private static Object rebuildBeduinChild(Object child, Object params, Object replacement) {
        try {
            for (java.lang.reflect.Constructor<?> constructor : child.getClass().getDeclaredConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length != 2 || !types[1].isInstance(replacement)) {
                    continue;
                }
                Object component = null;
                for (java.lang.reflect.Field field : beduinFieldsOf(child.getClass())) {
                    Object value = field.get(child);
                    if (value != null && value != params && types[0].isInstance(value)) {
                        component = value;
                        break;
                    }
                }
                if (component == null) {
                    return null;
                }
                constructor.setAccessible(true);
                return constructor.newInstance(component, replacement);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Offer id, a seller key seen for this offer elsewhere, or a name block. */
    private static boolean isBeduinAdvertBlocked(BeduinAdvertItem advert) {
        if (isOfferBlocked(advert.id)) {
            return true;
        }
        String seenKey;
        synchronized (LOCK) {
            seenKey = seenOfferSellerKeys.get(advert.id);
        }
        return isSellerBlocked(seenKey) || isSellerNameBlocked(advert.seller.displayName);
    }

    /**
     * Called at the entry of every Beduin v2 lazy adapter's
     * {@code onBindViewHolder(holder, position)}: wires the long-press block menu
     * onto advert tiles, like {@link #onBindAdvert} does for Konveyor lists.
     */
    public static void onBindBeduin(Object adapter, Object viewHolder, int position) {
        try {
            BeduinAdvertItem advert = beduinAdvertOf(beduinItemAt(adapter, position));
            if (advert == null) {
                android.view.View root = viewHolder == null ? null : itemViewOf(viewHolder);
                if (root != null) {
                    boundAdvertViews.remove(root);
                    restore(root);
                }
                return;
            }
            onBindAdvert(viewHolder, advert);
        } catch (Throwable ignored) {
        }
    }

    /** Re-submits every live Beduin list so a (un)block takes effect at once. */
    private static void refreshBeduinLists() {
        final java.util.List<java.util.Map.Entry<Object, List<?>>> entries;
        synchronized (beduinLists) {
            if (beduinLists.isEmpty()) {
                return;
            }
            entries = new ArrayList<>(beduinLists.entrySet());
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                for (java.util.Map.Entry<Object, List<?>> entry : entries) {
                    try {
                        Object adapter = entry.getKey();
                        if (adapter == null) {
                            continue;
                        }
                        adapter.getClass().getMethod("submitList", List.class, Runnable.class)
                                .invoke(adapter, entry.getValue(), null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private static Object beduinItemAt(Object adapter, int position) {
        if (adapter == null || position < 0) {
            return null;
        }
        try {
            java.lang.reflect.Method getItem = beduinGetItem.get(adapter.getClass());
            if (getItem == null) {
                for (Class<?> c = adapter.getClass(); c != null && getItem == null; c = c.getSuperclass()) {
                    try {
                        getItem = c.getDeclaredMethod("getItem", int.class);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                if (getItem == null) {
                    return null;
                }
                getItem.setAccessible(true);
                beduinGetItem.put(adapter.getClass(), getItem);
            }
            return getItem.invoke(adapter, position);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Reads an advert tile out of a Beduin v2 list component. The tile's data is
     * the element the server template iterates over ({@code it}), holding an
     * {@code itemSnippet} object with {@code item.itemId}, {@code item.title},
     * {@code item.uri} and {@code seller.name}. The engine's classes are obfuscated,
     * so the element is located by shape: a map keyed by {@code "itemSnippet"}.
     * Returns null for non-advert components.
     */
    private static BeduinAdvertItem beduinAdvertOf(Object component) {
        java.util.Map<?, ?> snippet = beduinSnippetOf(component);
        if (snippet == null) {
            return null;
        }
        java.util.Map<?, ?> item = beduinMap(snippet.get("item"));
        String id = item == null ? null : beduinString(item.get("itemId"));
        if (isBlank(id)) {
            java.util.Map<?, ?> analytics = beduinMap(snippet.get("analytics"));
            id = analytics == null ? null : beduinString(analytics.get("itemId"));
        }
        if (isBlank(id) || !isAllDigits(id)) {
            return null;
        }
        java.util.Map<?, ?> seller = beduinMap(snippet.get("seller"));
        return new BeduinAdvertItem(
                id,
                item == null ? null : beduinString(item.get("title")),
                item == null ? null : beduinString(item.get("uri")),
                seller == null ? null : beduinString(seller.get("name")));
    }

    /**
     * Whether a Beduin v2 list component is an advert tile marked reserved
     * ({@code itemSnippet.item.isReserved}). These tiles show no «Забронировано»
     * badge, but the flag matches the one legacy SERP models expose.
     */
    public static boolean isBeduinTileReserved(Object component) {
        try {
            java.util.Map<?, ?> snippet = beduinSnippetOf(component);
            java.util.Map<?, ?> item = snippet == null ? null : beduinMap(snippet.get("item"));
            return item != null && "true".equals(beduinString(item.get("isReserved")));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The component's {@code itemSnippet} object, or null for non-advert components. */
    private static java.util.Map<?, ?> beduinSnippetOf(Object component) {
        if (component == null) {
            return null;
        }
        String description = String.valueOf(component);
        if (!description.contains("Snippet")) {
            return null;
        }
        return findBeduinSnippet(component,
                component.getClass().getName() + "|" + parseField(description, "componentType="));
    }

    /** One hop from a component towards its element map: a field or a map key. */
    private static final class BeduinStep {
        final java.lang.reflect.Field field;
        final String mapKey;

        BeduinStep(java.lang.reflect.Field field, String mapKey) {
            this.field = field;
            this.mapKey = mapKey;
        }
    }

    /** Hops from a component to its element map, learned once per component kind. */
    private static final java.util.Map<String, List<BeduinStep>> beduinPaths =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Finds a component's {@code itemSnippet} object. The first component of each
     * kind is searched breadth-first (see {@link #searchBeduinElement}); the hops
     * found are cached so later components of that kind are read directly.
     */
    private static java.util.Map<?, ?> findBeduinSnippet(Object root, String kind) {
        List<BeduinStep> path = beduinPaths.get(kind);
        if (path != null) {
            java.util.Map<?, ?> element = followBeduinPath(root, path);
            java.util.Map<?, ?> snippet = element == null ? null : beduinMap(element.get("itemSnippet"));
            if (snippet != null) {
                return snippet;
            }
        }
        // Kinds without advert data (skeletons, other snippet types) are retried
        // only after a while, not searched again on every submit and bind.
        Long missedAt = beduinMisses.get(kind);
        long now = android.os.SystemClock.uptimeMillis();
        if (missedAt != null && now - missedAt < BEDUIN_MISS_RETRY_MS) {
            return null;
        }
        List<BeduinStep> found = new ArrayList<>();
        java.util.Map<?, ?> element = searchBeduinElement(root, found);
        if (element == null) {
            if (path == null) {
                beduinMisses.put(kind, now);
            }
            return null;
        }
        beduinMisses.remove(kind);
        beduinPaths.put(kind, found);
        return beduinMap(element.get("itemSnippet"));
    }

    private static final long BEDUIN_MISS_RETRY_MS = 30_000L;
    /** Component kinds whose search found no advert data, with when it was tried. */
    private static final java.util.Map<String, Long> beduinMisses =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.Map<?, ?> followBeduinPath(Object root, List<BeduinStep> path) {
        Object current = root;
        try {
            for (BeduinStep step : path) {
                if (current == null) {
                    return null;
                }
                if (step.field != null) {
                    if (!step.field.getDeclaringClass().isInstance(current)) {
                        return null;
                    }
                    current = step.field.get(current);
                } else {
                    if (!(current instanceof java.util.Map)) {
                        return null;
                    }
                    Object next = null;
                    for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) current).entrySet()) {
                        if (step.mapKey.equals(String.valueOf(entry.getKey()))) {
                            next = entry.getValue();
                            break;
                        }
                    }
                    current = next;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return current instanceof java.util.Map ? (java.util.Map<?, ?>) current : null;
    }

    /**
     * Breadth-first search from a component for its element map (the one with an
     * {@code itemSnippet} key), recording the hops taken into {@code path}. Only
     * follows object fields and map entries keyed by a name or by a local template
     * argument ({@code RefArg(...)} without an {@code @} scope), which keeps it
     * inside the component's own data rather than the shared engine state.
     * Bounded, and fail-closed (null).
     */
    private static java.util.Map<?, ?> searchBeduinElement(Object root, List<BeduinStep> path) {
        java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
        // node -> {parent, step}
        java.util.IdentityHashMap<Object, Object[]> seen = new java.util.IdentityHashMap<>();
        seen.put(root, new Object[] {null, null});
        queue.add(root);
        int budget = 4000;
        while (!queue.isEmpty() && budget-- > 0) {
            Object o = queue.poll();
            if (o instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) o;
                if (map.containsKey("itemSnippet") && beduinMap(map.get("itemSnippet")) != null) {
                    java.util.LinkedList<BeduinStep> steps = new java.util.LinkedList<>();
                    for (Object node = o; node != root; ) {
                        Object[] link = seen.get(node);
                        steps.addFirst((BeduinStep) link[1]);
                        node = link[0];
                    }
                    path.addAll(steps);
                    return map;
                }
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    String keyText = String.valueOf(key);
                    Object value = entry.getValue();
                    if (value != null && !seen.containsKey(value) && (key instanceof String
                            || (keyText.startsWith("RefArg(") && !keyText.contains("@")))) {
                        seen.put(value, new Object[] {o, new BeduinStep(null, keyText)});
                        queue.add(value);
                    }
                }
                continue;
            }
            if (!isBeduinGraphNode(o)) {
                continue;
            }
            for (java.lang.reflect.Field field : beduinFieldsOf(o.getClass())) {
                try {
                    Object value = field.get(o);
                    if (value != null && !seen.containsKey(value)) {
                        seen.put(value, new Object[] {o, new BeduinStep(field, null)});
                        queue.add(value);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static final java.util.Map<Class<?>, java.lang.reflect.Field[]> beduinFields =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Accessible instance, non-primitive fields of a class and its superclasses. */
    private static java.lang.reflect.Field[] beduinFieldsOf(Class<?> cls) {
        java.lang.reflect.Field[] cached = beduinFields.get(cls);
        if (cached != null) {
            return cached;
        }
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                }
            }
        }
        cached = fields.toArray(new java.lang.reflect.Field[0]);
        beduinFields.put(cls, cached);
        return cached;
    }

    /** App/engine objects worth descending into (not platform, JDK or scalar values). */
    private static boolean isBeduinGraphNode(Object o) {
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean
                || o instanceof Character || o instanceof Iterable || o instanceof Class
                || o instanceof android.view.View || o instanceof android.content.Context) {
            return false;
        }
        Class<?> cls = o.getClass();
        if (cls.isArray() || cls.isEnum()) {
            return false;
        }
        String name = cls.getName();
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin.")
                || name.startsWith("kotlinx.") || name.startsWith("io.reactivex."));
    }

    /** Unwraps a Beduin object value to its backing map (a few wrapper levels deep). */
    private static java.util.Map<?, ?> beduinMap(Object value) {
        return (java.util.Map<?, ?>) beduinUnwrap(value, true, 0);
    }

    /** Unwraps a Beduin primitive value to its String form (numbers without exponent). */
    private static String beduinString(Object value) {
        Object scalar = beduinUnwrap(value, false, 0);
        if (scalar instanceof Double || scalar instanceof Float) {
            double d = ((Number) scalar).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
        }
        if (scalar == null) {
            return null;
        }
        String text = String.valueOf(scalar).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    private static Object beduinUnwrap(Object value, boolean wantMap, int depth) {
        if (value == null || depth > 3) {
            return null;
        }
        if (wantMap ? value instanceof java.util.Map
                : (value instanceof CharSequence || value instanceof Number || value instanceof Boolean)) {
            return value;
        }
        if (value instanceof java.util.Map || !isBeduinGraphNode(value)) {
            return null;
        }
        for (java.lang.reflect.Field field : beduinFieldsOf(value.getClass())) {
            try {
                Object found = beduinUnwrap(field.get(value), wantMap, depth + 1);
                if (found != null) {
                    return found;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // Advert-toolbar block actions. The bytecode hook lives here, on this
    // always-initialized class, rather than on the UI helper: the presenter's
    // toolbar-build method runs inside a reactive pipeline, and triggering a fresh
    // class's static init on that stack silently stalls the page at its skeleton.
    // We therefore only stash references on that stack and bounce to the main
    // thread, where MorpheBlockMenu initializes safely and does the real work.
    private static volatile Object pendingAdvertPresenter;
    private static volatile Object pendingAdvert;
    private static android.os.Handler advertToolbarHandler;
    private static final Runnable ADVERT_TOOLBAR_INSTALL = new Runnable() {
        @Override
        public void run() {
            app.avito.morphe.MorpheBlockMenu.installAdvert(pendingAdvertPresenter, pendingAdvert);
        }
    };

    /**
     * Entry hook for the advert-detail toolbar (called from the presenter's
     * reactive build stack). Stores references and defers; does no reflection,
     * view work, or foreign-class init here.
     */
    public static void onAdvertToolbar(Object presenter, Object style, Object advertDetails) {
        try {
            pendingAdvertPresenter = presenter;
            pendingAdvert = advertDetails;
            android.os.Handler h = advertToolbarHandler();
            // Coalesce the pipeline's many emissions into a single install.
            h.removeCallbacks(ADVERT_TOOLBAR_INSTALL);
            h.postDelayed(ADVERT_TOOLBAR_INSTALL, 450);
        } catch (Throwable ignored) {
        }
    }

    private static synchronized android.os.Handler advertToolbarHandler() {
        if (advertToolbarHandler == null) {
            advertToolbarHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return advertToolbarHandler;
    }

    // Seller-profile (extended profile) toolbar block action — same deferral
    // pattern as the advert toolbar above.
    private static volatile Object pendingSellerKey;
    private static volatile Object pendingSellerProfile;
    private static android.os.Handler sellerToolbarHandler;
    private static final Runnable SELLER_TOOLBAR_INSTALL = new Runnable() {
        @Override
        public void run() {
            app.avito.morphe.MorpheBlockMenu.installSeller(pendingSellerKey, pendingSellerProfile);
        }
    };

    /**
     * Entry hook for the seller-profile (ExtendedProfile) toolbar. The profile
     * converter passes the deep-link {@code userKey} and a {@code context} string
     * (order isn't guaranteed), plus the loaded {@code ExtendedProfile}. We pick
     * the param that looks like a userKey (the only cheap, non-reflective work
     * done on this reactive stack) and defer everything else to the main thread.
     */
    public static void onSellerToolbar(String a, String b, Object profile) {
        try {
            String userKey = looksLikeUserKey(a) ? a : (looksLikeUserKey(b) ? b
                    : (a != null && !a.isEmpty() ? a : b));
            if (userKey == null || userKey.isEmpty()) {
                return;
            }
            pendingSellerKey = userKey;
            pendingSellerProfile = profile;
            android.os.Handler h = sellerToolbarHandler();
            h.removeCallbacks(SELLER_TOOLBAR_INSTALL);
            h.postDelayed(SELLER_TOOLBAR_INSTALL, 450);
        } catch (Throwable ignored) {
        }
    }

    /** A seller userKey is a long hex hash (e.g. "a58fa0dc…"); a context tag isn't. */
    private static boolean looksLikeUserKey(String s) {
        if (s == null || s.length() < 16) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static synchronized android.os.Handler sellerToolbarHandler() {
        if (sellerToolbarHandler == null) {
            sellerToolbarHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return sellerToolbarHandler;
    }

    public static String callString(Object target, String method) {
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

    private static Object fieldObject(Object target, String name) {
        if (target == null || name == null) {
            return null;
        }
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Import / export (parity with the browser extension)
    // ---------------------------------------------------------------------

    /**
     * Native export: a clean, versioned, lossless snapshot that round-trips
     * everything we keep — ids plus the readable labels and the block timestamps —
     * structured as proper arrays of objects:
     *
     * <pre>{@code
     * {
     *   "version": 1,
     *   "offers":  [ { "id": "123", "title": "…", "seller": "…", "blockedAt": 171… } ],
     *   "sellers": [ { "userKey": "abc…", "name": "…", "blockedAt": 171… } ]
     * }
     * }</pre>
     *
     * Deliberately NOT the browser extension's flat {@code "<id>_blacklist_ad": true}
     * schema, which can't carry labels or timestamps and encodes the entry type in a
     * string-key suffix. ({@link #importText} still accepts that legacy schema and
     * raw id arrays, so data can be migrated in from the extension.)
     */
    public static String exportNative() {
        ensureLoaded();
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray offersArr = new JSONArray();
            JSONArray sellersArr = new JSONArray();
            synchronized (LOCK) {
                for (String id : blockedOffers) {
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    putIfPresent(o, "title", offerLabels.get(id));
                    putIfPresent(o, "seller", offerSellerLabels.get(id));
                    Long t = offerTimes.get(id);
                    if (t != null) {
                        o.put("blockedAt", t.longValue());
                    }
                    offersArr.put(o);
                }
                for (String userKey : blockedSellers) {
                    JSONObject s = new JSONObject();
                    s.put("userKey", userKey);
                    putIfPresent(s, "name", sellerLabels.get(userKey));
                    Long t = sellerTimes.get(userKey);
                    if (t != null) {
                        s.put("blockedAt", t.longValue());
                    }
                    sellersArr.put(s);
                }
            }
            root.put("offers", offersArr);
            root.put("sellers", sellersArr);
            return root.toString(2);
        } catch (Throwable t) {
            return "{\"version\":1,\"offers\":[],\"sellers\":[]}";
        }
    }

    private static void putIfPresent(JSONObject obj, String key, String value) throws org.json.JSONException {
        if (value != null && !value.isEmpty()) {
            obj.put(key, value);
        }
    }

    /**
     * Imports a blacklist from text. Accepts, in order of preference:
     * <ul>
     *   <li>our native format ({@code {"offers":[…],"sellers":[…]}}) — restores
     *       ids plus labels and timestamps (see {@link #exportNative()});</li>
     *   <li>the browser extension's flat object ({@code {"<id>_blacklist_ad": true, …}});</li>
     *   <li>a JSON array of raw ids (classified by length: long hashes are
     *       sellers, short numerics are offers).</li>
     * </ul>
     * The last two carry ids only, so labels/timestamps are filled in as the item
     * is encountered later (or stamped at import time).
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
        java.util.Map<String, String> impOfferTitles = new java.util.HashMap<>();
        java.util.Map<String, String> impOfferSellers = new java.util.HashMap<>();
        java.util.Map<String, String> impSellerNames = new java.util.HashMap<>();
        java.util.Map<String, Long> impOfferTimes = new java.util.HashMap<>();
        java.util.Map<String, Long> impSellerTimes = new java.util.HashMap<>();
        try {
            if (text.charAt(0) == '[') {
                JSONArray arr = new JSONArray(text);
                for (int i = 0; i < arr.length(); i++) {
                    classifyRaw(arr.optString(i, ""), offers, sellers);
                }
            } else {
                JSONObject obj = new JSONObject(text);
                JSONArray nativeOffers = obj.optJSONArray("offers");
                JSONArray nativeSellers = obj.optJSONArray("sellers");
                if (nativeOffers != null || nativeSellers != null) {
                    // Native format: objects with id/label/timestamp (tolerant of
                    // bare-id strings too).
                    if (nativeOffers != null) {
                        for (int i = 0; i < nativeOffers.length(); i++) {
                            JSONObject o = nativeOffers.optJSONObject(i);
                            if (o == null) {
                                classifyRaw(nativeOffers.optString(i, ""), offers, sellers);
                                continue;
                            }
                            String id = o.optString("id", "").trim();
                            if (id.isEmpty()) {
                                continue;
                            }
                            offers.add(id);
                            putNonEmpty(impOfferTitles, id, o.optString("title", null));
                            putNonEmpty(impOfferSellers, id, o.optString("seller", null));
                            long t = o.optLong("blockedAt", 0L);
                            if (t > 0) {
                                impOfferTimes.put(id, t);
                            }
                        }
                    }
                    if (nativeSellers != null) {
                        for (int i = 0; i < nativeSellers.length(); i++) {
                            JSONObject s = nativeSellers.optJSONObject(i);
                            if (s == null) {
                                String raw = nativeSellers.optString(i, "").trim();
                                if (!raw.isEmpty()) {
                                    sellers.add(raw);
                                }
                                continue;
                            }
                            String key = s.optString("userKey", "").trim();
                            if (key.isEmpty()) {
                                continue;
                            }
                            sellers.add(key);
                            putNonEmpty(impSellerNames, key, s.optString("name", null));
                            long t = s.optLong("blockedAt", 0L);
                            if (t > 0) {
                                impSellerTimes.put(key, t);
                            }
                        }
                    }
                } else {
                    // Legacy browser-extension object: "<id>_blacklist_ad": true.
                    Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        classifyKey(keys.next(), offers, sellers);
                    }
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
                offerLabels.clear();
                offerSellerLabels.clear();
                sellerLabels.clear();
                offerTimes.clear();
                sellerTimes.clear();
            }
            int before = blockedOffers.size() + blockedSellers.size();
            blockedOffers.addAll(offers);
            blockedSellers.addAll(sellers);
            // Restore any labels/timestamps the import carried (native format).
            offerLabels.putAll(impOfferTitles);
            offerSellerLabels.putAll(impOfferSellers);
            sellerLabels.putAll(impSellerNames);
            offerTimes.putAll(impOfferTimes);
            sellerTimes.putAll(impSellerTimes);
            // Stamp import time on any entry that doesn't already have one, so
            // imported items still sort sensibly (just-imported batch at the top).
            long now = System.currentTimeMillis();
            for (String offer : blockedOffers) {
                if (!offerTimes.containsKey(offer)) {
                    offerTimes.put(offer, now);
                }
            }
            for (String seller : blockedSellers) {
                if (!sellerTimes.containsKey(seller)) {
                    sellerTimes.put(seller, now);
                }
            }
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

    private static void putNonEmpty(java.util.Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty() && !"null".equals(value)) {
            map.put(key, value);
        }
    }
}
