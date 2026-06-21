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

    /**
     * Gate for the "single-row home categories" feature, injected into the
     * rubricator tile's getRowLine(): when on, every tile reports row 1 so the
     * category rubricator collapses to one row. Off → stock (two rows).
     */
    public static boolean singleRowCategories() {
        return isEnabled("avito_single_row_categories", true);
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
    public static java.util.List<?> withoutSubscriptionsTab(java.util.List<?> tabs) {
        try {
            if (tabs == null || tabs.isEmpty() || !isEnabled("avito_hide_subscriptions_tab", true)) {
                return tabs;
            }
            java.util.ArrayList<Object> kept = new java.util.ArrayList<>(tabs.size());
            for (Object tab : tabs) {
                if (tab != null && tab.getClass().getName().endsWith(".SellersTab")) {
                    continue;
                }
                kept.add(tab);
            }
            return kept;
        } catch (Throwable ignored) {
            return tabs;
        }
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
        // Collapse the leftover "Реклама скрыта" empty-ad stub (ads are removed).
        AdCleanup.onBind(viewHolder);
        Blacklist.onBindAdvert(viewHolder, item);
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
