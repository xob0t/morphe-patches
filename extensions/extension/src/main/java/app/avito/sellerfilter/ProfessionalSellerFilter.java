package app.avito.sellerfilter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.avito.morphe.MorpheTheme;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Local search filter that hides or dims adverts from sellers whose
 * review count is greater than a user-selected maximum.
 *
 * <p>The setting is presented inside Avito's native Filters screen. Filtering
 * itself happens before Avito converts network SERP elements into adapter items,
 * so removed adverts do not leave empty cells in the results grid.
 */
@SuppressWarnings("unused")
public final class ProfessionalSellerFilter {

    private static final String PREFS_NAME = "avito_morphe_settings";
    private static final String KEY_MAX_REVIEWS = "professional_seller_max_reviews";
    private static final String KEY_TINT_MATCHES = "professional_seller_tint_matches";
    private static final String ROW_TAG = "morphe_professional_seller_filter";
    private static final int HOST_OBSERVER_TAG = 0x6d6f7253;
    private static final int SELLER_ITEM_OBSERVER_TAG = 0x6d6f7254;
    private static final int TINT_DRAWABLE_TAG = 0x6d6f7255;
    private static final int TINT_BOUND_ITEM_TAG = 0x6d6f7256;
    private static final int MATCH_TINT_COLOR = 0x70000000;
    private static final int MAX_THRESHOLD = 999999999;

    private ProfessionalSellerFilter() {
    }

    /**
     * Removes matching network SERP elements in place. Missing or unrecognised
     * seller data is always kept, making the hook fail-open across Avito updates.
     */
    public static List<?> filterSerpElements(List<?> elements) {
        if (elements == null || elements.isEmpty()) {
            return elements;
        }
        int maximum = maximumReviews();
        if (maximum <= 0 || tintMatches()) {
            return elements;
        }
        try {
            ArrayList<Object> filtered = new ArrayList<>(elements.size());
            boolean removed = false;
            for (Object element : elements) {
                int reviews = sellerReviewCount(element);
                if (reviews > maximum) {
                    removed = true;
                } else {
                    filtered.add(element);
                }
            }
            return removed ? filtered : elements;
        } catch (Throwable ignored) {
            // Never let a model change break the feed.
            return elements;
        }
    }

    /**
     * Final feed pass over the mutable adapter-item ArrayList. Some Avito feeds
     * omit seller data from their network wrapper but retain it on AdvertItem,
     * so this mirrors the blacklist patch's reliable pre-return sanitization.
     */
    public static void filterAdvertItems(List<?> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        int maximum = maximumReviews();
        if (maximum <= 0 || tintMatches()) {
            return;
        }
        try {
            Iterator<?> iterator = items.iterator();
            while (iterator.hasNext()) {
                Object item = iterator.next();
                int reviews = sellerReviewCount(item);
                if (reviews > maximum) {
                    try {
                        iterator.remove();
                    } catch (Throwable immutableList) {
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Applies or clears the optional matched-offer tint on every adapter bind.
     * The keyed bound-item tag prevents a delayed callback from tinting a
     * RecyclerView holder that has already been recycled for another model.
     */
    public static void onBind(Object viewHolder, final Object item) {
        try {
            final View root = app.avito.blacklist.Blacklist.itemViewOf(viewHolder);
            if (root == null) {
                return;
            }
            root.setTag(TINT_BOUND_ITEM_TAG, item);
            if (!shouldTint(item)) {
                applyTileTint(root, false);
            }
            root.post(new Runnable() {
                @Override
                public void run() {
                    if (root.getTag(TINT_BOUND_ITEM_TAG) == item) {
                        applyTileTint(root, shouldTint(item));
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean shouldTint(Object item) {
        if (!tintMatches()) {
            return false;
        }
        int maximum = maximumReviews();
        return maximum > 0 && sellerReviewCount(item) > maximum;
    }

    private static void applyTileTint(View root, boolean tinted) {
        try {
            Object tagged = root.getTag(TINT_DRAWABLE_TAG);
            ColorDrawable overlay = tagged instanceof ColorDrawable
                    ? (ColorDrawable) tagged : null;
            if (!tinted) {
                if (overlay != null) {
                    root.getOverlay().remove(overlay);
                    root.setTag(TINT_DRAWABLE_TAG, null);
                }
                return;
            }
            if (overlay == null) {
                overlay = new ColorDrawable(MATCH_TINT_COLOR);
                root.setTag(TINT_DRAWABLE_TAG, overlay);
                root.getOverlay().add(overlay);
            }
            overlay.setBounds(0, 0, Math.max(0, root.getWidth()), Math.max(0, root.getHeight()));
            root.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Adds a native-looking threshold row immediately below the Filters toolbar.
     * The fragment view is committed asynchronously, hence the short bounded retry.
     */
    public static void attachToFilterActivity(final Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            activity.getWindow().getDecorView().post(new Runnable() {
                @Override
                public void run() {
                    attachWhenReady(activity, 0);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Watches Avito's main host Activity for the newer embedded Filters screen.
     * That screen is pushed as a fragment after Activity creation, so an ordinary
     * onCreate-time lookup is too early.
     */
    public static void observeHostActivity(final Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            final View decor = activity.getWindow().getDecorView();
            if (decor.getTag(HOST_OBSERVER_TAG) != null) {
                return;
            }
            decor.setTag(HOST_OBSERVER_TAG, Boolean.TRUE);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            attachToEmbeddedFilters(activity);
                        }
                    });
            decor.post(new Runnable() {
                @Override
                public void run() {
                    attachToEmbeddedFilters(activity);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void attachWhenReady(final Activity activity, final int attempt) {
        try {
            if (activity.isFinishing()
                    || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                return;
            }
            int recyclerId = activity.getResources()
                    .getIdentifier("recycler_view", "id", activity.getPackageName());
            View recycler = recyclerId == 0 ? null : activity.findViewById(recyclerId);
            if (!(recycler instanceof ViewGroup)) {
                retry(activity, attempt);
                return;
            }
            observeSellerFilterItem(activity, (ViewGroup) recycler);
        } catch (Throwable ignored) {
            retry(activity, attempt);
        }
    }

    private static void attachToEmbeddedFilters(Activity activity) {
        try {
            int rootId = activity.getResources()
                    .getIdentifier("filters_screen_root", "id", activity.getPackageName());
            ViewGroup root = rootId == 0 ? null
                    : asViewGroup(activity.findViewById(rootId));
            if (!(root instanceof FrameLayout)) {
                return;
            }
            int recyclerId = activity.getResources()
                    .getIdentifier("recycler_view", "id", activity.getPackageName());
            View recycler = recyclerId == 0 ? null : root.findViewById(recyclerId);
            if (!(recycler instanceof ViewGroup)) {
                return;
            }
            observeSellerFilterItem(activity, (ViewGroup) recycler);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Avito already exposes a native "Продавцы" adapter item. Attach the local
     * threshold field to that item instead of simulating another list row above
     * the RecyclerView. The observer also handles ordinary ViewHolder recycling.
     */
    private static void observeSellerFilterItem(
            final Activity activity,
            final ViewGroup recycler
    ) {
        if (recycler.getTag(SELLER_ITEM_OBSERVER_TAG) != null) {
            attachToVisibleSellerItem(activity, recycler);
            return;
        }
        recycler.setTag(SELLER_ITEM_OBSERVER_TAG, Boolean.TRUE);
        recycler.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        attachToVisibleSellerItem(activity, recycler);
                    }
                });
        attachToVisibleSellerItem(activity, recycler);
    }

    private static void attachToVisibleSellerItem(
            Activity activity,
            ViewGroup recycler
    ) {
        try {
            int titleId = activity.getResources()
                    .getIdentifier("title", "id", activity.getPackageName());
            ViewGroup sellerItem = null;
            for (int i = 0; i < recycler.getChildCount(); i++) {
                ViewGroup item = asViewGroup(recycler.getChildAt(i));
                if (item == null) {
                    continue;
                }
                View taggedRow = item.findViewWithTag(ROW_TAG);
                View titleView = titleId == 0 ? null : item.findViewById(titleId);
                boolean isSellerItem = titleView instanceof TextView
                        && "Продавцы".contentEquals(((TextView) titleView).getText());
                if (isSellerItem) {
                    sellerItem = item;
                } else if (taggedRow != null && taggedRow.getParent() instanceof ViewGroup) {
                    ((ViewGroup) taggedRow.getParent()).removeView(taggedRow);
                }
            }
            if (sellerItem == null || sellerItem.findViewWithTag(ROW_TAG) != null) {
                return;
            }
            LinearLayout row = buildSellerFilterField(activity);
            sellerItem.addView(row, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            sellerItem.requestLayout();
        } catch (Throwable ignored) {
        }
    }

    private static LinearLayout buildSellerFilterField(final Activity activity) {
        final MorpheTheme theme = new MorpheTheme(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setTag(ROW_TAG);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, theme.dp(8), 0, theme.dp(4));
        row.setBackgroundColor(theme.colorBackground);

        TextView label = new TextView(activity);
        label.setText("Максимум отзывов");
        label.setTextColor(theme.textPrimary);
        label.setIncludeFontPadding(false);
        label.setPadding(theme.dp(16), 0, theme.dp(16), 0);
        applyTextAppearance(activity, label, "textM20", 16f, false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.bottomMargin = theme.dp(8);
        row.addView(label, labelParams);

        View inputContainer = inflateNativeFilterInput(activity);
        EditText input = findEditText(inputContainer);
        if (input == null) {
            inputContainer = buildFallbackInput(activity, theme);
            input = findEditText(inputContainer);
        }
        if (input != null) {
            configureThresholdInput(input);
        }
        row.addView(inputContainer);
        row.addView(buildTintModeToggle(activity, theme));
        return row;
    }

    private static View buildTintModeToggle(
            final Activity activity,
            MorpheTheme theme
    ) {
        View container = null;
        View checkboxItem = null;
        try {
            int layoutId = activity.getResources().getIdentifier(
                    "filters_checkbox", "layout", activity.getPackageName());
            if (layoutId != 0) {
                container = LayoutInflater.from(activity).inflate(layoutId, null, false);
                int checkboxId = activity.getResources().getIdentifier(
                        "filters_checkbox", "id", activity.getPackageName());
                checkboxItem = checkboxId == 0 ? container : container.findViewById(checkboxId);
            }
        } catch (Throwable ignored) {
        }
        if (container == null) {
            CheckBox fallback = new CheckBox(activity);
            fallback.setText("Затемнять вместо скрытия");
            fallback.setTextColor(theme.textPrimary);
            fallback.setPadding(theme.dp(12), theme.dp(8), theme.dp(16), theme.dp(8));
            applyTextAppearance(activity, fallback, "textM20", 16f, false);
            container = fallback;
            checkboxItem = fallback;
        }

        boolean nativeTitleSet = false;
        if (checkboxItem != null) {
            try {
                Method setTitle = checkboxItem.getClass()
                        .getMethod("setTitle", CharSequence.class);
                setTitle.invoke(checkboxItem, "Затемнять вместо скрытия");
                nativeTitleSet = true;
            } catch (Throwable ignored) {
            }
        }
        if (!nativeTitleSet) {
            int titleId = activity.getResources().getIdentifier(
                    "design_item_title", "id", activity.getPackageName());
            View titleView = titleId == 0 ? null : container.findViewById(titleId);
            if (titleView instanceof TextView) {
                ((TextView) titleView).setText("Затемнять вместо скрытия");
            } else if (checkboxItem instanceof CheckBox) {
                ((CheckBox) checkboxItem).setText("Затемнять вместо скрытия");
            }
        }

        final CompoundButton toggle = findCompoundButton(container);
        if (toggle != null) {
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(tintMatches());
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    setTintMatches(checked);
                }
            });
            final View.OnClickListener clickListener = new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (view == toggle) {
                        setTintMatches(toggle.isChecked());
                    } else {
                        toggle.setChecked(!toggle.isChecked());
                    }
                }
            };
            wireToggleClicks(checkboxItem == null ? container : checkboxItem, toggle, clickListener);
        }
        container.setContentDescription("Затемнять подходящие объявления вместо скрытия");
        return container;
    }

    private static void wireToggleClicks(
            View view,
            CompoundButton toggle,
            View.OnClickListener listener
    ) {
        view.setOnClickListener(listener);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                wireToggleClicks(group.getChildAt(i), toggle, listener);
            }
        }
    }

    private static CompoundButton findCompoundButton(View view) {
        if (view instanceof CompoundButton) {
            return (CompoundButton) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CompoundButton toggle = findCompoundButton(group.getChildAt(i));
                if (toggle != null) {
                    return toggle;
                }
            }
        }
        return null;
    }

    private static View inflateNativeFilterInput(Activity activity) {
        try {
            int layoutId = activity.getResources().getIdentifier(
                    "filter_screen_input_view", "layout", activity.getPackageName());
            if (layoutId != 0) {
                return LayoutInflater.from(activity).inflate(layoutId, null, false);
            }
        } catch (Throwable ignored) {
        }
        return buildFallbackInput(activity, new MorpheTheme(activity));
    }

    private static View buildFallbackInput(Activity activity, MorpheTheme theme) {
        LinearLayout container = new LinearLayout(activity);
        container.setPadding(theme.dp(16), theme.dp(8), theme.dp(16), theme.dp(16));
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setPadding(theme.dp(16), 0, theme.dp(16), 0);
        input.setTextColor(theme.textPrimary);
        input.setHintTextColor(theme.textSecondary);
        applyTextAppearance(activity, input, "textM20", 16f, false);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(theme.colorSurface);
        surface.setCornerRadius(theme.dp(12));
        input.setBackground(surface);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(52)));
        return container;
    }

    private static EditText findEditText(View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText input = findEditText(group.getChildAt(i));
                if (input != null) {
                    return input;
                }
            }
        }
        return null;
    }

    private static void configureThresholdInput(final EditText input) {
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Не ограничено");
        input.setContentDescription("Максимальное количество отзывов у продавца");
        restoreThresholdText(input, 0L);
        restoreThresholdText(input, 200L);
        restoreThresholdText(input, 1000L);
        final boolean[] userEditing = {false};
        input.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event != null && event.getAction() == MotionEvent.ACTION_DOWN) {
                    userEditing[0] = true;
                }
                return false;
            }
        });
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (!hasFocus) {
                    userEditing[0] = false;
                }
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(
                    CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                // Avito's reusable input holder focuses and clears itself while
                // attaching. Only a touch-started edit is intentional.
                if (!userEditing[0]) {
                    return;
                }
                String value = text == null ? "" : text.toString().trim();
                if (value.isEmpty() || "0".equals(value)) {
                    setMaximumReviews(0);
                    input.setError(null);
                    return;
                }
                int maximum = parseThreshold(value);
                if (maximum > 0) {
                    setMaximumReviews(maximum);
                    input.setError(null);
                } else {
                    input.setError("Введите корректное число");
                }
            }
        });
    }

    private static void restoreThresholdText(final EditText input, long delayMillis) {
        input.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (input.hasFocus()) {
                    return;
                }
                int current = maximumReviews();
                if (current <= 0) {
                    return;
                }
                String value = String.valueOf(current);
                if (!value.contentEquals(input.getText())) {
                    input.setText(value);
                }
                if (input.length() > 0) {
                    input.setSelection(input.length());
                }
            }
        }, delayMillis);
    }

    private static void configureLegacyThresholdInput(final EditText input) {
        int current = maximumReviews();
        if (current > 0) {
            input.setText(String.valueOf(current));
            input.setSelection(input.length());
        }
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(
                    CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                String value = text == null ? "" : text.toString().trim();
                if (value.isEmpty() || "0".equals(value)) {
                    setMaximumReviews(0);
                    input.setError(null);
                    return;
                }
                int maximum = parseThreshold(value);
                if (maximum > 0) {
                    setMaximumReviews(maximum);
                    input.setError(null);
                } else {
                    input.setError("Введите корректное число");
                }
            }
        });
    }

    /**
     * Avito's current Filters screen is a RecyclerView inside a FrameLayout.
     * The injected view therefore has to be overlaid, but is treated visually as
     * adapter position -1: list padding reserves its space and this listener
     * translates and clips it away as adapter position 0 scrolls off screen.
     */
    private static void makeSectionScrollWithList(
            final View recycler,
            final View row
    ) {
        row.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            private boolean configured;

            @Override
            public void onLayoutChange(
                    View view,
                    int left,
                    int top,
                    int right,
                    int bottom,
                    int oldLeft,
                    int oldTop,
                    int oldRight,
                    int oldBottom
            ) {
                if (configured || bottom <= top) {
                    return;
                }
                configured = true;
                row.removeOnLayoutChangeListener(this);
                final int rowHeight = bottom - top;
                final int originalTopPadding = recycler.getPaddingTop();
                recycler.setPadding(
                        recycler.getPaddingLeft(),
                        originalTopPadding + rowHeight,
                        recycler.getPaddingRight(),
                        recycler.getPaddingBottom());
                observeHeaderScroll(
                        recycler,
                        row,
                        originalTopPadding + rowHeight,
                        rowHeight);
            }
        });
    }

    private static void observeHeaderScroll(
            final View recycler,
            final View row,
            final int firstItemTopAtRest,
            final int rowHeight
    ) {
        if (!(recycler instanceof ViewGroup)) {
            return;
        }
        final ViewGroup list = (ViewGroup) recycler;
        recycler.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        try {
                            View firstChild = null;
                            for (int i = 0; i < list.getChildCount(); i++) {
                                View child = list.getChildAt(i);
                                if (firstChild == null
                                        || child.getTop() < firstChild.getTop()) {
                                    firstChild = child;
                                }
                            }
                            int hidden = 0;
                            if (firstChild != null) {
                                int adapterPosition = childAdapterPosition(
                                        recycler, firstChild);
                                hidden = adapterPosition > 0
                                        ? rowHeight
                                        : Math.max(
                                                0,
                                                Math.min(
                                                        rowHeight,
                                                        firstItemTopAtRest
                                                                - firstChild.getTop()));
                            }
                            row.setTranslationY(-hidden);
                            row.setClipBounds(new Rect(
                                    0,
                                    hidden,
                                    Math.max(1, row.getWidth()),
                                    rowHeight));
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                });
    }

    private static int childAdapterPosition(View recycler, View child) {
        try {
            Object value = recycler.getClass()
                    .getMethod("getChildAdapterPosition", View.class)
                    .invoke(recycler, child);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void retry(final Activity activity, final int attempt) {
        if (attempt >= 20) {
            return;
        }
        try {
            activity.getWindow().getDecorView().postDelayed(new Runnable() {
                @Override
                public void run() {
                    attachWhenReady(activity, attempt + 1);
                }
            }, 100L);
        } catch (Throwable ignored) {
        }
    }

    private static int parseThreshold(String text) {
        if (text == null) {
            return -1;
        }
        try {
            long parsed = Long.parseLong(text.trim());
            return parsed > 0 && parsed <= MAX_THRESHOLD ? (int) parsed : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int sellerReviewCount(Object element) {
        if (element == null) {
            return -1;
        }
        int constructorReviews = parseConstructorReviewCount(String.valueOf(element));
        if (constructorReviews >= 0) {
            return constructorReviews;
        }
        Object seller = sellerObjectOf(element);
        if (seller == null) {
            return parseNamedReviewCount(String.valueOf(element));
        }
        Object rating = call(seller, "getRating");
        if (rating == null) {
            rating = call(seller, "getSellerRating");
        }
        if (rating == null) {
            int reviews = reviewCount(seller);
            return reviews >= 0
                    ? reviews
                    : parseNamedReviewCount(String.valueOf(element));
        }
        int reviews = reviewCount(rating);
        return reviews >= 0
                ? reviews
                : parseNamedReviewCount(String.valueOf(element));
    }

    private static Object sellerObjectOf(Object item) {
        Object seller = call(item, "getSellerInfo");
        if (seller == null) {
            seller = call(item, "getSeller");
        }
        if (seller != null) {
            return seller;
        }
        try {
            Class<?> type = item.getClass();
            while (type != null && type != Object.class) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    String fieldType = field.getType().getName();
                    if (!fieldType.contains("SellerInfo")) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (value != null) {
                        return value;
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int reviewCount(Object rating) {
        Object value = call(rating, "getReviewCount");
        if (value == null) {
            value = call(rating, "getReviewsCount");
        }
        if (value == null) {
            value = call(rating, "getReviews");
        }
        if (value instanceof Number) {
            long count = ((Number) value).longValue();
            return count >= 0 && count <= Integer.MAX_VALUE ? (int) count : -1;
        }
        int parsed = parseReviewCount(value == null ? null : String.valueOf(value));
        if (parsed >= 0) {
            return parsed;
        }
        return parseNamedReviewCount(String.valueOf(rating));
    }

    private static int parseNamedReviewCount(String text) {
        if (text == null) {
            return -1;
        }
        String[] keys = {"reviewCount=", "reviewsCount=", "reviews="};
        for (String key : keys) {
            int start = text.indexOf(key);
            if (start < 0) {
                continue;
            }
            start += key.length();
            int end = start;
            while (end < text.length()) {
                char value = text.charAt(end);
                if (value == ',' || value == ')') {
                    break;
                }
                end++;
            }
            int parsed = parseReviewCount(text.substring(start, end));
            if (parsed >= 0) {
                return parsed;
            }
        }
        return -1;
    }

    /**
     * Redesigned advert cards keep the visible seller rating in their Beduin
     * free-form tree rather than SellerInfo. Under the stable
     * sellerNameAndRating container, the review count is emitted as a text token
     * such as {@code title=(247)}.
     */
    private static int parseConstructorReviewCount(String text) {
        if (text == null) {
            return -1;
        }
        int anchor = text.indexOf("sellerNameAndRating");
        if (anchor < 0) {
            return -1;
        }
        int limit = Math.min(text.length(), anchor + 16000);
        int cursor = anchor;
        while (cursor < limit) {
            int title = text.indexOf("title=", cursor);
            if (title < 0 || title >= limit) {
                break;
            }
            int valueStart = title + "title=".length();
            int valueEnd = text.indexOf(", overridenAttributes", valueStart);
            if (valueEnd < 0 || valueEnd > limit) {
                valueEnd = text.indexOf(')', valueStart);
                if (valueEnd >= 0) {
                    valueEnd++;
                }
            }
            if (valueEnd <= valueStart || valueEnd > limit) {
                break;
            }
            String value = text.substring(valueStart, valueEnd).trim();
            if (isParenthesizedCount(value)) {
                return parseReviewCount(value);
            }
            cursor = valueEnd;
        }
        return -1;
    }

    private static boolean isParenthesizedCount(String value) {
        if (value == null || value.length() < 3
                || value.charAt(0) != '('
                || value.charAt(value.length() - 1) != ')') {
            return false;
        }
        boolean digit = false;
        for (int i = 1; i < value.length() - 1; i++) {
            char character = value.charAt(i);
            if (Character.digit(character, 10) >= 0) {
                digit = true;
            } else if (!Character.isWhitespace(character)
                    && character != '\u00a0'
                    && character != '\u202f') {
                return false;
            }
        }
        return digit;
    }

    /**
     * Avito currently supplies strings such as "(247)". Collecting decimal
     * digits also accepts grouping spaces and punctuation without mistaking the
     * rating score itself for a review count.
     */
    private static int parseReviewCount(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        long value = 0;
        boolean found = false;
        for (int i = 0; i < text.length(); i++) {
            int digit = Character.digit(text.charAt(i), 10);
            if (digit >= 0) {
                found = true;
                value = value * 10L + digit;
                if (value > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return found ? (int) value : -1;
    }

    private static Object call(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ViewGroup asViewGroup(Object value) {
        return value instanceof ViewGroup ? (ViewGroup) value : null;
    }

    private static int actionBarHeight(Activity activity, int fallback) {
        try {
            TypedValue value = new TypedValue();
            if (activity.getTheme().resolveAttribute(
                    android.R.attr.actionBarSize, value, true)) {
                if (value.resourceId != 0) {
                    return activity.getResources().getDimensionPixelSize(value.resourceId);
                }
                return TypedValue.complexToDimensionPixelSize(
                        value.data, activity.getResources().getDisplayMetrics());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static void applyTextAppearance(
            Activity activity,
            TextView view,
            String attributeName,
            float fallbackSize,
            boolean fallbackBold
    ) {
        try {
            int attributeId = activity.getResources().getIdentifier(
                    attributeName, "attr", activity.getPackageName());
            TypedValue value = new TypedValue();
            if (attributeId != 0
                    && activity.getTheme().resolveAttribute(attributeId, value, true)
                    && value.resourceId != 0) {
                view.setTextAppearance(activity, value.resourceId);
                return;
            }
        } catch (Throwable ignored) {
        }
        view.setTextSize(fallbackSize);
        if (fallbackBold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
    }

    private static int maximumReviews() {
        SharedPreferences preferences = preferences();
        return preferences == null ? 0 : preferences.getInt(KEY_MAX_REVIEWS, 0);
    }

    private static void setMaximumReviews(int maximum) {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.edit().putInt(KEY_MAX_REVIEWS, Math.max(0, maximum)).apply();
        }
    }

    private static boolean tintMatches() {
        SharedPreferences preferences = preferences();
        return preferences != null && preferences.getBoolean(KEY_TINT_MATCHES, false);
    }

    private static void setTintMatches(boolean enabled) {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.edit().putBoolean(KEY_TINT_MATCHES, enabled).apply();
        }
    }

    @SuppressLint("PrivateApi")
    private static SharedPreferences preferences() {
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (application instanceof Context) {
                return ((Context) application).getSharedPreferences(
                        PREFS_NAME, Context.MODE_PRIVATE);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
