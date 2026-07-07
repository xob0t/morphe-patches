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
 */
public final class AdCleanup {

    // -2 = not resolved yet; -1 = resource not found (give up); >0 = the id.
    private static int adEmptyId = -2;

    private AdCleanup() {
    }

    public static java.util.ArrayList<?> withoutPrizePortalProfileWidgets(java.util.ArrayList<?> items) {
        try {
            if (items == null || items.isEmpty()) {
                return items;
            }
            java.util.Iterator<?> iterator = items.iterator();
            while (iterator.hasNext()) {
                Object item = iterator.next();
                if (stringValues(item).contains("Портал призов")) {
                    iterator.remove();
                }
            }
        } catch (Throwable ignored) {
        }
        return items;
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

    private static java.util.LinkedHashSet<String> stringValues(Object target) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        if (target == null) {
            return values;
        }
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
}
