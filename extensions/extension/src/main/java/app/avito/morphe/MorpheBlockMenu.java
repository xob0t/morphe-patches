package app.avito.morphe;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import java.lang.reflect.Field;

import app.avito.blacklist.Blacklist;

/**
 * Adds block / unblock actions to Avito's advert-detail and seller toolbars.
 *
 * <p>The advert toolbar setup method runs inside the advert-detail presenter's
 * reactive build pipeline, and triggering this class's first-time initialization
 * on that thread silently stalls the page at its skeleton. So the bytecode hook
 * lives on the always-loaded {@link Blacklist} class
 * ({@code Blacklist.onAdvertToolbar}), which stashes references and bounces to the
 * main thread; only there — fully off the reactive stack — does it call into this
 * class. We then pull the offer id + seller userKey from the model, locate the
 * toolbar via the presenter's view tree, and add menu items rendered as action
 * icons (Avito's own {@code common_ic_block_24} / {@code common_ic_block_user_24}).
 * Tapping toggles the blacklist and recolours the icon. Fully defensive — any
 * failure leaves the toolbar untouched.
 */
public final class MorpheBlockMenu {

    // Arbitrary, app-unique menu item ids (Avito's are 0x7f……).
    private static final int ID_BLOCK_OFFER = 0x6D6F0001;
    private static final int ID_BLOCK_SELLER = 0x6D6F0002;

    private MorpheBlockMenu() {
    }

    /**
     * Builds the advert toolbar's block actions. Called on the MAIN thread from
     * {@code Blacklist.onAdvertToolbar}'s deferred runnable — never directly from
     * the presenter's reactive build stack (see class doc).
     */
    public static void installAdvert(Object presenter, final Object advertDetails) {
        try {
            if (advertDetails == null || presenter == null) {
                return;
            }
            final String offerId = Blacklist.callString(advertDetails, "getId");
            final String offerTitle = Blacklist.callString(advertDetails, "getTitle");
            Object seller = callObject(advertDetails, "getSeller");
            final String userKey = seller == null ? null : Blacklist.callString(seller, "getUserKey");
            final String sellerName = seller == null ? null : Blacklist.callString(seller, "getName");
            if ((offerId == null || offerId.isEmpty()) && (userKey == null || userKey.isEmpty())) {
                return;
            }

            // Reach the toolbar from the currently-resumed Activity (we are on the
            // main thread), independent of the presenter's per-version field layout.
            View decor = currentDecorView();
            if (decor == null) {
                View anyView = findView(presenter);
                decor = anyView == null ? null : anyView.getRootView();
            }
            if (decor == null) {
                return;
            }
            Menu menu = toolbarMenu(decor);
            if (menu == null) {
                return;
            }
            Context ctx = decor.getContext();
            if (offerId != null && !offerId.isEmpty()) {
                addToggle(menu, ctx, ID_BLOCK_OFFER, "common_ic_block_24",
                        "Скрыть объявление",
                        "Объявление в чёрном списке — скрыто из ленты",
                        "Объявление убрано из чёрного списка",
                        new Toggle() {
                            @Override
                            public boolean blocked() {
                                return Blacklist.isOfferBlocked(offerId);
                            }

                            @Override
                            public void toggle() {
                                if (Blacklist.isOfferBlocked(offerId)) {
                                    Blacklist.removeOffer(offerId);
                                } else {
                                    Blacklist.addOffer(offerId, offerTitle, sellerName);
                                }
                            }
                        });
            }
            if (userKey != null && !userKey.isEmpty()) {
                final String sellerLabel = sellerName == null || sellerName.isEmpty() ? "Продавец" : sellerName;
                addToggle(menu, ctx, ID_BLOCK_SELLER, "common_ic_block_user_24",
                        "Скрыть продавца",
                        sellerLabel + " в чёрном списке — его объявления скрыты",
                        sellerLabel + " убран из чёрного списка",
                        new Toggle() {
                            @Override
                            public boolean blocked() {
                                return Blacklist.isSellerBlocked(userKey);
                            }

                            @Override
                            public void toggle() {
                                if (Blacklist.isSellerBlocked(userKey)) {
                                    Blacklist.removeSeller(userKey);
                                } else {
                                    Blacklist.addSeller(userKey, sellerName);
                                }
                            }
                        });
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Builds the seller-profile toolbar's single "block seller" action. Called on
     * the MAIN thread from {@code Blacklist.onSellerToolbar}'s deferred runnable.
     * The name is read here (off the reactive stack) from the profile model:
     * {@code ExtendedProfile.getData().getName()}.
     */
    public static void installSeller(Object userKeyObj, final Object profile) {
        try {
            final String userKey = (userKeyObj instanceof String) ? (String) userKeyObj : null;
            if (userKey == null || userKey.isEmpty()) {
                return;
            }
            String name = null;
            Object data = callObject(profile, "getData");
            if (data != null) {
                name = Blacklist.callString(data, "getName");
            }
            final String sellerName = name;

            View decor = currentDecorView();
            if (decor == null) {
                return;
            }
            Menu menu = toolbarMenu(decor);
            if (menu == null) {
                return;
            }
            Context ctx = decor.getContext();
            final String sellerLabel = sellerName == null || sellerName.isEmpty() ? "Продавец" : sellerName;
            addToggle(menu, ctx, ID_BLOCK_SELLER, "common_ic_block_user_24",
                    "Скрыть продавца",
                    sellerLabel + " в чёрном списке — его объявления скрыты",
                    sellerLabel + " убран из чёрного списка",
                    new Toggle() {
                        @Override
                        public boolean blocked() {
                            return Blacklist.isSellerBlocked(userKey);
                        }

                        @Override
                        public void toggle() {
                            if (Blacklist.isSellerBlocked(userKey)) {
                                Blacklist.removeSeller(userKey);
                            } else {
                                Blacklist.addSeller(userKey, sellerName);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Locates the advert toolbar's Menu within the window's view tree. Tries the
     * {@code @id/toolbar} first, then falls back to the first {@code Toolbar}-typed
     * view found — both reach the same {@code getMenu()}. Main thread only.
     */
    private static Menu toolbarMenu(View root) {
        try {
            Context ctx = root.getContext();
            int tbId = ctx.getResources().getIdentifier("toolbar", "id", ctx.getPackageName());
            View toolbar = tbId == 0 ? null : root.findViewById(tbId);
            if (toolbar == null) {
                toolbar = findToolbarView(root);
            }
            if (toolbar == null) {
                return null;
            }
            Object menuObj = toolbar.getClass().getMethod("getMenu").invoke(toolbar);
            return menuObj instanceof Menu ? (Menu) menuObj : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Depth-first search for the first {@code Toolbar}-typed view. */
    private static View findToolbarView(View v) {
        if (v == null) {
            return null;
        }
        if (isToolbar(v)) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findToolbarView(g.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isToolbar(View v) {
        for (Class<?> c = v.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            if (c.getName().endsWith("Toolbar")) {
                return true;
            }
        }
        return false;
    }

    /** The decor view of the currently-resumed Activity, via the ActivityThread. */
    private static View currentDecorView() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object inst = at.getMethod("currentActivityThread").invoke(null);
            Field activitiesF = at.getDeclaredField("mActivities");
            activitiesF.setAccessible(true);
            Object activities = activitiesF.get(inst);
            if (!(activities instanceof java.util.Map)) {
                return null;
            }
            for (Object record : ((java.util.Map<?, ?>) activities).values()) {
                if (record == null) {
                    continue;
                }
                try {
                    Field pausedF = record.getClass().getDeclaredField("paused");
                    pausedF.setAccessible(true);
                    if (pausedF.getBoolean(record)) {
                        continue;
                    }
                    Field activityF = record.getClass().getDeclaredField("activity");
                    activityF.setAccessible(true);
                    Object activity = activityF.get(record);
                    if (activity instanceof android.app.Activity) {
                        android.view.Window w = ((android.app.Activity) activity).getWindow();
                        if (w != null) {
                            return w.getDecorView();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------------

    private interface Toggle {
        boolean blocked();

        void toggle();
    }

    private static void addToggle(Menu menu, final Context ctx, final int id, String iconName,
                                  String title, final String blockedMsg, final String unblockedMsg,
                                  final Toggle toggle) {
        try {
            if (menu.findItem(id) != null) {
                return;
            }
            // order 1 → placed before Avito's items (which start later).
            MenuItem item = menu.add(Menu.NONE, id, 1, title);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            Drawable icon = drawableByName(ctx, iconName);
            if (icon != null) {
                item.setIcon(applyTint(icon, toggle.blocked()));
            }
            item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem mi) {
                    toggle.toggle();
                    boolean nowBlocked = toggle.blocked();
                    applyTint(mi.getIcon(), nowBlocked);
                    toast(ctx, nowBlocked ? blockedMsg : unblockedMsg, iconName, nowBlocked);
                    return true;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Blocked → red, not blocked → white (matching Avito's other toolbar icons). */
    private static Drawable applyTint(Drawable icon, boolean blocked) {
        if (icon == null) {
            return null;
        }
        try {
            int color = blocked ? 0xFFFF4053 : 0xFFFFFFFF;
            Drawable d = icon.mutate();
            // SRC_IN forces a solid recolour even on multi-colour assets, so both the
            // synthesized glyph and Avito's own icons render as a flat white/red shape.
            d.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    color, android.graphics.PorterDuff.Mode.SRC_IN));
            return d;
        } catch (Throwable ignored) {
            return icon;
        }
    }

    private static Drawable drawableByName(Context ctx, String name) {
        try {
            int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
            if (id != 0) {
                return ctx.getResources().getDrawable(id, ctx.getTheme());
            }
        } catch (Throwable ignored) {
        }
        // Older builds may not ship the named asset — synthesize a no-entry glyph so
        // the action still renders as an icon (and not as bare text in the bar).
        return synthBlockIcon(ctx);
    }

    /** A white "no-entry" (circle + slash) icon, drawn in code; tinted by the caller. */
    private static Drawable synthBlockIcon(Context ctx) {
        try {
            float d = ctx.getResources().getDisplayMetrics().density;
            int size = Math.max(1, (int) (24 * d));
            android.graphics.Bitmap bmp =
                    android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(2f * d);
            paint.setColor(0xFFFFFFFF);
            float cx = size / 2f, cy = size / 2f;
            float r = size / 2f - 2f * d;
            canvas.drawCircle(cx, cy, r, paint);
            double a = Math.PI / 4;
            float dx = (float) (r * Math.cos(a)), dy = (float) (r * Math.sin(a));
            canvas.drawLine(cx - dx, cy + dy, cx + dx, cy - dy, paint);
            return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Finds a View reachable from the presenter so we can reach the window's
     * toolbar. Surgical (no deep graph walk): the presenter's navbar holder is a
     * field whose type *declares* a View field; we read only that holder and its
     * View. Avoids traversing the presenter's whole DI graph on the main thread.
     */
    private static View findView(Object presenter) {
        try {
            Class<?> pc = presenter.getClass();
            // A direct View field on the presenter, if any.
            for (Field f : pc.getDeclaredFields()) {
                if (View.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(presenter);
                    if (v instanceof View) {
                        return (View) v;
                    }
                }
            }
            // Otherwise the navbar holder: an Avito-typed field whose class declares
            // a View field. Only that holder + its View are read.
            for (Field f : pc.getDeclaredFields()) {
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isInterface() || !t.getName().startsWith("com.avito")) {
                    continue;
                }
                Field viewField = null;
                for (Field inner : t.getDeclaredFields()) {
                    if (View.class.isAssignableFrom(inner.getType())) {
                        viewField = inner;
                        break;
                    }
                }
                if (viewField == null) {
                    continue;
                }
                f.setAccessible(true);
                Object holder = f.get(presenter);
                if (holder == null) {
                    continue;
                }
                viewField.setAccessible(true);
                Object v = viewField.get(holder);
                if (v instanceof View) {
                    return (View) v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object callObject(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * A toast that looks like the standard dark pill but carries a leading Avito
     * icon (text toasts can't be given an icon on API 11+, so we supply a custom
     * view; it renders fine since these always fire while the app is foreground).
     * {@code blocked} tints the icon red (blocked) or white (restored). Falls back
     * to a plain text toast if anything goes wrong.
     */
    public static void toast(Context ctx, String message, String iconName, boolean blocked) {
        try {
            float d = ctx.getResources().getDisplayMetrics().density;
            android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF2B2B2B);
            bg.setCornerRadius(24f * d);
            row.setBackground(bg);
            row.setPadding((int) (20 * d), (int) (13 * d), (int) (20 * d), (int) (13 * d));

            Drawable icon = drawableByName(ctx, iconName);
            if (icon != null) {
                android.widget.ImageView iv = new android.widget.ImageView(ctx);
                iv.setImageDrawable(applyTint(icon, blocked));
                int size = (int) (20 * d);
                android.widget.LinearLayout.LayoutParams ip =
                        new android.widget.LinearLayout.LayoutParams(size, size);
                ip.rightMargin = (int) (12 * d);
                row.addView(iv, ip);
            }
            android.widget.TextView tv = new android.widget.TextView(ctx);
            tv.setText(message);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(14f);
            tv.setMaxWidth((int) (260 * d));
            row.addView(tv);

            android.widget.Toast t = new android.widget.Toast(ctx);
            t.setView(row);
            t.setDuration(android.widget.Toast.LENGTH_LONG);
            t.show();
        } catch (Throwable ignored) {
            try {
                android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable t2) {
            }
        }
    }
}
