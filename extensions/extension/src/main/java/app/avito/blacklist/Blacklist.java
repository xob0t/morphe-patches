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
    private static final String KEY_OFFER_SELLER_LABELS = "offer_seller_labels";
    private static final String KEY_OFFER_TIMES = "offer_times";
    private static final String KEY_SELLER_TIMES = "seller_times";

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
    // Seller name of each blocked offer, keyed by offer id. Shown under the offer
    // in the manager so it's clear who the listing belongs to.
    private static final java.util.Map<String, String> offerSellerLabels = new java.util.HashMap<>();
    // When each id was blocked (epoch millis), keyed by id. Used to sort the
    // manager most-recent-first. Local-only metadata, not part of import/export.
    private static final java.util.Map<String, Long> offerTimes = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> sellerTimes = new java.util.HashMap<>();

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
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .putString(KEY_OFFERS, new JSONArray(blockedOffers).toString())
                .putString(KEY_SELLERS, new JSONArray(blockedSellers).toString())
                .putString(KEY_OFFER_LABELS, new JSONObject(offerLabels).toString())
                .putString(KEY_SELLER_LABELS, new JSONObject(sellerLabels).toString())
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

    public static boolean removeSeller(String userKey) {
        ensureLoaded();
        synchronized (LOCK) {
            if (blockedSellers.remove(userKey)) {
                sellerLabels.remove(userKey);
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
        if (offerCount() == 0 && sellerCount() == 0) {
            return;
        }
        try {
            Iterator<?> it = items.iterator();
            while (it.hasNext()) {
                Object item = it.next();
                if (item == null || !item.getClass().getName().endsWith("AdvertItem")) {
                    continue;
                }
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
        try {
            if (viewHolder == null || item == null) {
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

    private static void showBlockDialog(android.content.Context ctx, final android.view.View root, Object item) {
        final String offerId = offerIdOf(item);
        final String userKey = sellerUserKey(item);
        final String offerTitle = callString(item, "getTitle");
        final Object sellerObj = sellerObjectOf(item);
        String sellerName = nameOf(sellerObj);
        if (isBlank(sellerName)) {
            // The redesigned tile leaves SellerInfoModel.displayName empty and
            // shows the store name in the Beduin freeForm tree instead.
            sellerName = sellerNameFromConstructor(item);
        }
        final String sellerNameFinal = sellerName;

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
            labels.add("Скрыть все объявления продавца");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    addSeller(userKey);
                    putSellerLabel(userKey, sellerNameFinal);
                    collapseMatching(false, userKey);
                    String who = isBlank(sellerNameFinal) ? "Продавец" : sellerNameFinal;
                    undoBar(root, who + " скрыт", "common_ic_block_user_24", new Runnable() {
                        @Override
                        public void run() {
                            removeSeller(userKey);
                            restoreMatching(false, userKey);
                        }
                    });
                }
            });
        }
        if (actions.isEmpty()) {
            return;
        }
        showRoundedMenu(ctx, "Чёрный список", labels, actions);
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
     * The real numeric advert id, matching what the feed filter blocks and what
     * the browser-extension export uses. The conveyor {@code getStringId()} is the
     * advert id string (same value the network model's {@code getId()} returns);
     * the item's {@code getId()} long is an internal id and must not be used.
     */
    private static String offerIdOf(Object item) {
        String stringId = callString(item, "getStringId");
        if (stringId != null && !stringId.isEmpty() && isAllDigits(stringId)) {
            return stringId;
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

    /**
     * The seller's {@code userKey}. Works for both the legacy {@code AdvertItem}
     * ({@code AdvertSellerInfo} field) and the redesigned item
     * ({@code getSellerInfo()} -> {@code SellerInfoModel}). The userKey getter is
     * not always present, so it is parsed from the seller object's data-class
     * {@code toString()} ("...userKey=<value>, ..."), which is stable.
     */
    private static String sellerUserKey(Object item) {
        Object seller = sellerObjectOf(item);
        if (seller == null) {
            return null;
        }
        String key = callString(seller, "getUserKey");
        if (key != null && !key.isEmpty()) {
            return key;
        }
        return parseField(seller.toString(), "userKey=");
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
            String value = isOffer ? offerIdOf(item) : sellerUserKey(item);
            if (id.equals(value)) {
                restore(entry.getKey());
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

    private static void toast(android.view.View anchor, String message, String iconName) {
        try {
            // Reuse the icon toast from the block-menu helper (these run on the main
            // thread after a user tap, so touching that class here is safe).
            app.avito.morphe.MorpheBlockMenu.toast(anchor.getContext(), message, iconName, true);
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
