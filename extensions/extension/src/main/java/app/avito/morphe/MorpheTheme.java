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

    // Native Avito settings text sizes (sp), measured from the stock screen so the
    // code-built screens match it. All paired with setIncludeFontPadding(false) —
    // the stock screen renders tight, so font padding would make our text look
    // larger and crammed.
    public static final float TITLE_SP = 18f;     // top-bar title
    public static final float ROW_TITLE_SP = 16f; // list row title
    public static final float SUBTITLE_SP = 14f;  // row subtitle / summary
    public static final float META_SP = 13f;      // small meta / hint line

    private final Activity host;
    public final float density;
    public final int colorBackground;
    public final int colorSurface;
    public final int textPrimary;
    public final int textSecondary;
    public final int accent;
    public final int divider;

    public MorpheTheme(Activity host) {
        this.host = host;
        this.density = host.getResources().getDisplayMetrics().density;
        this.colorBackground = avitoColor("white", themeColor(android.R.attr.colorBackground, Color.WHITE));
        this.textPrimary = avitoColor("black", themeColor(android.R.attr.textColorPrimary, Color.BLACK));
        this.textSecondary = avitoColor("gray54", themeColor(android.R.attr.textColorSecondary, Color.GRAY));
        this.accent = avitoColor("blue", 0xFF00AAFF);
        this.colorSurface = avitoColor("gray4", blend(colorBackground, textPrimary, 0.05f));
        this.divider = avitoColor("gray8", blend(colorBackground, textPrimary, 0.12f));
    }

    /**
     * Converts a dp value to pixels using the real (fractional) display density.
     * Using {@code Math.round(density) * value} instead would quantise the density
     * to an integer and skew every dimension on non-integer-density screens (e.g.
     * 1.5×, 2.75×), which is what made the screens look right only on the device
     * they were tuned on.
     */
    public int dp(float value) {
        return Math.round(value * density);
    }

    /** A simple Avito-style top bar: native back arrow + title. */
    public View buildTopBar(String title, final Runnable onBack) {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(56));
        bar.setBackgroundColor(colorBackground);
        applyStatusBarInset(bar);

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
            // Avito tints the settings back arrow with the accent colour.
            back.setColorFilter(accent);
            back.setBackground(themeDrawable(android.R.attr.selectableItemBackgroundBorderless));
            back.setPadding(dp(12), dp(12), dp(12), dp(12));
            back.setOnClickListener(backAction);
            bar.addView(back);
        } else {
            TextView back = new TextView(host);
            back.setText("←");
            back.setTextSize(24);
            back.setTextColor(accent);
            back.setPadding(dp(16), dp(12), dp(16), dp(12));
            back.setOnClickListener(backAction);
            bar.addView(back);
        }

        TextView titleView = new TextView(host);
        titleView.setText(title);
        titleView.setIncludeFontPadding(false);
        titleView.setTextSize(TITLE_SP);
        titleView.setTextColor(textPrimary);
        // weight 1 so the title fills the bar (pushes any trailing action to the
        // right); leftMargin lands the text at the ~72dp toolbar title inset Avito
        // uses next to a navigation icon.
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(20);
        titleView.setLayoutParams(titleLp);
        bar.addView(titleView);
        return bar;
    }

    /**
     * Pads a top bar down by the status-bar height so it never sits under the
     * status bar. Uses the live {@link android.view.WindowInsets} top inset only:
     * on edge-to-edge devices that reports the status-bar height (so we pad); on
     * legacy devices the system already insets the content and reports 0 (so we add
     * nothing and avoid a double gap). Correct under cutouts and gesture nav.
     */
    public static void applyStatusBarInset(final View bar) {
        final int base = bar.getPaddingTop();
        bar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                v.setPadding(v.getPaddingLeft(), base + insets.getSystemWindowInsetTop(),
                        v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            }
        });
        if (bar.isAttachedToWindow()) {
            bar.requestApplyInsets();
        } else {
            bar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    v.requestApplyInsets();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            });
        }
    }

    public View makeDivider() {
        View line = new View(host);
        line.setBackgroundColor(divider);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density / 2f))));
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
