package app.avito.morphe;

import android.app.Activity;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared Avito design-system theming for the code-built Morphe screens (settings
 * host + blacklist manager). Resolves colours from Avito's own theme attributes
 * ({@code @style/Theme.Avito}) so the screens follow the app's palette and
 * light/dark appearance, and builds a native-looking top bar. No app resources or
 * androidx dependency — pure framework views + reflection-free attribute lookup.
 */
public final class MorpheTheme {

    private final Activity host;
    public final int dp;
    public final int colorBackground;
    public final int colorSurface;
    public final int textPrimary;
    public final int textSecondary;
    public final int accent;
    public final int divider;

    public MorpheTheme(Activity host) {
        this.host = host;
        this.dp = Math.round(host.getResources().getDisplayMetrics().density);
        this.colorBackground = avitoColor("white", themeColor(android.R.attr.colorBackground, Color.WHITE));
        this.textPrimary = avitoColor("black", themeColor(android.R.attr.textColorPrimary, Color.BLACK));
        this.textSecondary = avitoColor("gray54", themeColor(android.R.attr.textColorSecondary, Color.GRAY));
        this.accent = avitoColor("blue", 0xFF00AAFF);
        this.colorSurface = avitoColor("gray4", blend(colorBackground, textPrimary, 0.05f));
        this.divider = avitoColor("gray8", blend(colorBackground, textPrimary, 0.12f));
    }

    /** A simple Avito-style top bar: native back arrow + title. */
    public View buildTopBar(String title, final Runnable onBack) {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(56 * dp);
        bar.setBackgroundColor(colorBackground);

        View.OnClickListener backAction = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onBack != null) {
                    onBack.run();
                }
            }
        };
        android.graphics.drawable.Drawable backIcon = themeDrawable(android.R.attr.homeAsUpIndicator);
        if (backIcon == null) {
            backIcon = themeDrawable(android.R.attr.navigationIcon);
        }
        if (backIcon != null) {
            android.widget.ImageButton back = new android.widget.ImageButton(host);
            back.setImageDrawable(backIcon);
            back.setBackground(themeDrawable(android.R.attr.selectableItemBackgroundBorderless));
            back.setPadding(16 * dp, 12 * dp, 16 * dp, 12 * dp);
            back.setOnClickListener(backAction);
            bar.addView(back);
        } else {
            TextView back = new TextView(host);
            back.setText("←");
            back.setTextSize(22);
            back.setTextColor(textPrimary);
            back.setPadding(16 * dp, 12 * dp, 16 * dp, 12 * dp);
            back.setOnClickListener(backAction);
            bar.addView(back);
        }

        TextView titleView = new TextView(host);
        titleView.setText(title);
        titleView.setTextSize(20);
        titleView.setTextColor(textPrimary);
        bar.addView(titleView);
        return bar;
    }

    public View makeDivider() {
        View line = new View(host);
        line.setBackgroundColor(divider);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp / 2)));
        return line;
    }

    public android.graphics.drawable.Drawable themeDrawable(int attr) {
        try {
            TypedValue tv = new TypedValue();
            if (host.getTheme().resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
                return host.getResources().getDrawable(tv.resourceId, host.getTheme());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private int themeColor(int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (host.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.resourceId != 0) {
                    return host.getResources().getColor(tv.resourceId, host.getTheme());
                }
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** Resolves an Avito design-system colour attribute (e.g. "blue", "black") by name. */
    private int avitoColor(String attrName, int fallback) {
        try {
            int id = host.getResources().getIdentifier(attrName, "attr", host.getPackageName());
            if (id != 0) {
                TypedValue tv = new TypedValue();
                if (host.getTheme().resolveAttribute(id, tv, true)) {
                    if (tv.resourceId != 0) {
                        return host.getResources().getColor(tv.resourceId, host.getTheme());
                    }
                    return tv.data;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int blend(int from, int to, float ratio) {
        float inv = 1f - ratio;
        int a = (int) (Color.alpha(from) * inv + Color.alpha(to) * ratio);
        int r = (int) (Color.red(from) * inv + Color.red(to) * ratio);
        int g = (int) (Color.green(from) * inv + Color.green(to) * ratio);
        int b = (int) (Color.blue(from) * inv + Color.blue(to) * ratio);
        return Color.argb(a, r, g, b);
    }
}
