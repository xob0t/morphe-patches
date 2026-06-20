package app.avito.blacklist;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Self-contained management screen for the Avito blacklist.
 *
 * <p>Built in code (no app resources) but styled with the host app's theme
 * ({@code @style/Theme.Avito}): all colours are resolved from theme attributes
 * so the screen follows Avito's palette and light/dark appearance and feels like
 * part of the app. Provides add/remove for offer and seller ids and
 * import/export of the same JSON format used by the Ave Blacklist extension.
 */
public final class BlacklistActivity extends Activity {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private int colorBackground;
    private int colorSurface;
    private int textPrimary;
    private int textSecondary;
    private int accent;
    private int divider;
    private int dp;

    private LinearLayout listContainer;
    private TextView header;
    private String pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Чёрный список");

        dp = Math.round(getResources().getDisplayMetrics().density);
        // Avito design-system colours (DayNight: resolve to light/dark automatically).
        colorBackground = avitoColor("white", themeColor(android.R.attr.colorBackground, Color.WHITE));
        textPrimary = avitoColor("black", themeColor(android.R.attr.textColorPrimary, Color.BLACK));
        textSecondary = avitoColor("gray54", themeColor(android.R.attr.textColorSecondary, Color.GRAY));
        accent = avitoColor("blue", 0xFF00AAFF);
        colorSurface = avitoColor("gray4", blend(colorBackground, textPrimary, 0.05f));
        divider = avitoColor("gray8", blend(colorBackground, textPrimary, 0.12f));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(colorBackground);
        outer.addView(buildTopBar());

        View topDivider = new View(this);
        topDivider.setBackgroundColor(divider);
        topDivider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp / 2)));
        outer.addView(topDivider);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBackground);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.addView(scroll);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16 * dp, 8 * dp, 16 * dp, 24 * dp);
        scroll.addView(root);

        header = new TextView(this);
        header.setTextColor(textSecondary);
        header.setTextSize(14);
        header.setPadding(0, 0, 0, 16 * dp);
        root.addView(header);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, 16 * dp, 0, 0);
        root.addView(listContainer);

        setContentView(outer);
        handleIntentExtras(getIntent());
        refresh();
    }

    /** A simple Avito-style top bar: back arrow + title. */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(56 * dp);
        bar.setBackgroundColor(colorBackground);

        View.OnClickListener backAction = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        };
        // Avito's own back icon (theme homeAsUpIndicator / navigationIcon), with a
        // text fallback if it can't be resolved.
        android.graphics.drawable.Drawable backIcon = themeDrawable(android.R.attr.homeAsUpIndicator);
        if (backIcon == null) {
            backIcon = themeDrawable(android.R.attr.navigationIcon);
        }
        if (backIcon != null) {
            android.widget.ImageButton back = new android.widget.ImageButton(this);
            back.setImageDrawable(backIcon);
            back.setBackground(themeDrawable(android.R.attr.selectableItemBackgroundBorderless));
            back.setPadding(16 * dp, 12 * dp, 16 * dp, 12 * dp);
            back.setOnClickListener(backAction);
            bar.addView(back);
        } else {
            TextView back = new TextView(this);
            back.setText("←");
            back.setTextSize(22);
            back.setTextColor(textPrimary);
            back.setPadding(16 * dp, 12 * dp, 16 * dp, 12 * dp);
            back.setOnClickListener(backAction);
            bar.addView(back);
        }

        TextView title = new TextView(this);
        title.setText("Чёрный список");
        title.setTextSize(20);
        title.setTextColor(textPrimary);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        // Overflow (⋮) menu: import / export / clear live here instead of as
        // always-visible buttons, keeping the screen focused on the list itself.
        TextView overflow = new TextView(this);
        overflow.setText("⋮");
        overflow.setTextSize(22);
        overflow.setTextColor(textPrimary);
        overflow.setGravity(Gravity.CENTER);
        overflow.setMinWidth(48 * dp);
        overflow.setPadding(12 * dp, 12 * dp, 16 * dp, 12 * dp);
        overflow.setBackground(themeDrawable(android.R.attr.selectableItemBackgroundBorderless));
        overflow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflowMenu(v);
            }
        });
        bar.addView(overflow);

        return bar;
    }

    /** Import / export / clear, grouped into the top-bar overflow menu. */
    private void showOverflowMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Импорт");
        menu.getMenu().add(0, 2, 1, "Экспорт");
        menu.getMenu().add(0, 3, 2, "Очистить");
        menu.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        startImport();
                        return true;
                    case 2:
                        startExport();
                        return true;
                    case 3:
                        confirmClear();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    private void handleIntentExtras(Intent intent) {
        if (intent == null) {
            return;
        }
        try {
            String offer = intent.getStringExtra("add_offer");
            if (offer != null) {
                Blacklist.addOffer(offer);
            }
            String seller = intent.getStringExtra("add_seller");
            if (seller != null) {
                Blacklist.addSeller(seller);
            }
            String json = intent.getStringExtra("import_json");
            if (json != null) {
                Blacklist.importText(json, false);
            }
        } catch (Throwable ignored) {
        }
    }

    // -- UI building --------------------------------------------------------

    private void refresh() {
        int offers = Blacklist.offerCount();
        int sellers = Blacklist.sellerCount();
        header.setText("Объявлений: " + offers + "     Продавцов: " + sellers);

        listContainer.removeAllViews();
        addSection("Объявления", Blacklist.getOffers(), true);
        addSection("Продавцы", Blacklist.getSellers(), false);
    }

    private void addSection(String title, List<String> items, final boolean offer) {
        TextView label = new TextView(this);
        label.setText(title + " (" + items.size() + ")");
        label.setTextColor(textSecondary);
        label.setTextSize(13);
        label.setAllCaps(true);
        label.setLetterSpacing(0.04f);
        label.setPadding(0, 16 * dp, 0, 4 * dp);
        listContainer.addView(label);

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Список пуст");
            empty.setTextColor(textSecondary);
            empty.setTextSize(14);
            empty.setPadding(0, 8 * dp, 0, 8 * dp);
            listContainer.addView(empty);
            return;
        }

        for (final String id : items) {
            LinearLayout rowItem = new LinearLayout(this);
            rowItem.setOrientation(LinearLayout.HORIZONTAL);
            rowItem.setGravity(Gravity.CENTER_VERTICAL);
            rowItem.setPadding(0, 12 * dp, 0, 12 * dp);
            // Tap a row to open the advert / seller page in the app.
            rowItem.setBackground(themeDrawable(android.R.attr.selectableItemBackground));
            rowItem.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (offer) {
                        openOffer(id);
                    } else {
                        openSeller(id);
                    }
                }
            });

            String itemLabel = offer ? Blacklist.getOfferLabel(id) : Blacklist.getSellerLabel(id);
            String offerSeller = offer ? Blacklist.getOfferSellerLabel(id) : null;

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView primary = new TextView(this);
            primary.setTextColor(textPrimary);
            primary.setTextSize(15);
            primary.setText(itemLabel != null ? itemLabel : id);
            primary.setMaxLines(2);
            primary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(primary);

            if (offerSeller != null && !offerSeller.isEmpty()) {
                TextView sellerView = new TextView(this);
                sellerView.setTextColor(textSecondary);
                sellerView.setTextSize(13);
                sellerView.setText("Продавец: " + offerSeller);
                sellerView.setMaxLines(1);
                sellerView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                sellerView.setPadding(0, 2 * dp, 0, 0);
                textCol.addView(sellerView);
            }

            if (itemLabel != null) {
                TextView sub = new TextView(this);
                sub.setTextColor(textSecondary);
                sub.setTextSize(12);
                sub.setText(id);
                sub.setMaxLines(1);
                sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                sub.setPadding(0, 2 * dp, 0, 0);
                textCol.addView(sub);
            }

            long blockedAt = offer ? Blacklist.getOfferTime(id) : Blacklist.getSellerTime(id);
            if (blockedAt > 0) {
                TextView when = new TextView(this);
                when.setTextColor(textSecondary);
                when.setTextSize(12);
                when.setText("Заблокировано " + android.text.format.DateUtils.getRelativeTimeSpanString(
                        blockedAt, System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS));
                when.setPadding(0, 2 * dp, 0, 0);
                textCol.addView(when);
            }
            rowItem.addView(textCol);

            TextView remove = new TextView(this);
            remove.setText("Удалить");
            remove.setTextColor(accent);
            remove.setTextSize(14);
            remove.setPadding(12 * dp, 8 * dp, 0, 8 * dp);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (offer) {
                        Blacklist.removeOffer(id);
                    } else {
                        Blacklist.removeSeller(id);
                    }
                    refresh();
                }
            });
            rowItem.addView(remove);

            listContainer.addView(rowItem);
            listContainer.addView(makeDivider());
        }
    }

    private View makeDivider() {
        View line = new View(this);
        line.setBackgroundColor(divider);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp / 2)));
        return line;
    }

    private void confirmClear() {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Очистить чёрный список?")
                    .setMessage("Все заблокированные объявления и продавцы будут удалены.")
                    .setPositiveButton("Очистить", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            Blacklist.clear();
                            refresh();
                            toast("Очищено");
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        } catch (Throwable t) {
            Blacklist.clear();
            refresh();
        }
    }

    // -- Import / export via the Storage Access Framework --------------------

    private void startExport() {
        pendingExport = Blacklist.exportFull();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "avito_blacklist_database.json");
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Throwable t) {
            toast("Нет приложения для выбора файла");
        }
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Throwable t) {
            toast("Нет приложения для выбора файла");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            writeFile(uri, pendingExport);
        } else if (requestCode == REQ_IMPORT) {
            String content = readFile(uri);
            if (content == null) {
                toast("Не удалось прочитать файл");
                return;
            }
            int added = Blacklist.importText(content, false);
            if (added < 0) {
                toast("Ошибка импорта: неизвестный формат");
            } else {
                refresh();
                toast("Импортировано: " + added);
            }
        }
    }

    private void writeFile(Uri uri, String content) {
        if (content == null) {
            content = "{}";
        }
        OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(uri);
            if (out != null) {
                out.write(content.getBytes(UTF8));
                out.flush();
                toast("Экспортировано");
            }
        } catch (Throwable t) {
            toast("Ошибка экспорта");
        } finally {
            closeQuietly(out);
        }
    }

    private String readFile(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF8));
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Opens a deep link inside the Avito app (implicit, so its own intent-filter
     * routes it to the right deep-link handler). */
    /**
     * Opens an advert directly via Avito's standalone
     * {@code AdvertDetailsActivity} (extra {@code advert_id}). Because it is a
     * normal activity started from here (same task, no NEW_TASK), it stacks on top
     * of this screen — pressing back returns to this manager. This avoids the
     * deep-link route, which would funnel through the single-Activity Launcher and
     * synthesise a home back-stack that buries this screen.
     */
    private void openOffer(String advertId) {
        try {
            // AdvertDetailsActivity.onCreate requires a non-null advert_id and a
            // non-null fast_open_params; advert_screen_source is read too. All are
            // simple defaults here (no originating Avito screen / fast-open flow).
            Object fastOpen = newInstanceOf("com.avito.android.advert.item.AdvertDetailsFastOpenParams");
            if (fastOpen instanceof android.os.Parcelable) {
                Intent intent = new Intent();
                intent.setClassName(getPackageName(),
                        "com.avito.android.advert.AdvertDetailsActivity");
                intent.putExtra("advert_id", advertId);
                intent.putExtra("fast_open_params", (android.os.Parcelable) fastOpen);
                Object screenSource = screenSourceEmpty();
                if (screenSource instanceof android.os.Parcelable) {
                    intent.putExtra("advert_screen_source", (android.os.Parcelable) screenSource);
                }
                startActivity(intent);
                return;
            }
        } catch (Throwable ignored) {
        }
        // Fall back to the internal deep link if the activity/params moved.
        openInApp("ru.avito://1/items/" + Uri.encode(advertId));
    }

    /**
     * Instantiates a class with a "default" value. Tries a no-arg constructor
     * first; otherwise picks the constructor with the fewest parameters that are
     * all reference types (so each can be null) and passes nulls. This builds the
     * empty {@code AdvertDetailsFastOpenParams} (all-nullable 14-arg ctor) without
     * hard-coding the obfuscated signature.
     */
    private Object newInstanceOf(String className) {
        Class<?> cls;
        try {
            cls = Class.forName(className);
        } catch (Throwable t) {
            return null;
        }
        try {
            java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable ignored) {
        }
        java.lang.reflect.Constructor<?> best = null;
        for (java.lang.reflect.Constructor<?> ctor : cls.getDeclaredConstructors()) {
            boolean allRef = true;
            for (Class<?> p : ctor.getParameterTypes()) {
                if (p.isPrimitive()) {
                    allRef = false;
                    break;
                }
            }
            if (allRef && (best == null
                    || ctor.getParameterTypes().length < best.getParameterTypes().length)) {
                best = ctor;
            }
        }
        if (best == null) {
            return null;
        }
        try {
            best.setAccessible(true);
            Class<?>[] params = best.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                // Enum fields are written to the parcel via name(), which NPEs on
                // null, so give each enum its first constant; other refs stay null.
                if (params[i].isEnum()) {
                    Object[] constants = params[i].getEnumConstants();
                    if (constants != null && constants.length > 0) {
                        args[i] = constants[0];
                    }
                }
            }
            return best.newInstance(args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The {@code com.avito.android.rec.ScreenSource$EMPTY} singleton (a Parcelable
     * "no originating screen" marker). It is a Kotlin object whose INSTANCE field
     * is obfuscated, so the static self-typed field is found by scanning. Returns
     * null if the class isn't present.
     */
    private Object screenSourceEmpty() {
        try {
            Class<?> empty = Class.forName("com.avito.android.rec.ScreenSource$EMPTY");
            for (java.lang.reflect.Field f : empty.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && empty.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null) {
                        return v;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Opens a seller's public profile via the standalone
     * {@code ExtendedProfileActivity} (extra {@code extra_args} = an
     * {@code ExtendedProfileArguments} parcelable whose first String is the
     * {@code userKey}). Stacks on top of this screen so back returns here. Falls
     * back to the avito.ru profile link if the reflective build fails.
     */
    private void openSeller(String userKey) {
        try {
            Object args = buildExtendedProfileArguments(userKey);
            if (args instanceof android.os.Parcelable) {
                Intent intent = new Intent();
                intent.setClassName(getPackageName(),
                        "com.avito.android.extended_profile.ExtendedProfileActivity");
                intent.putExtra("extra_args", (android.os.Parcelable) args);
                startActivity(intent);
                return;
            }
        } catch (Throwable ignored) {
        }
        openInApp("https://www.avito.ru/user/" + Uri.encode(userKey) + "/profile");
    }

    /**
     * Reflectively builds an {@code ExtendedProfileArguments} for {@code userKey}.
     * Its fields (from the data-class {@code toString}) are
     * {@code userKey, contextId, searchParams, withProfileTabs, floatingBuyBlock},
     * so the primary constructor is {@code (String, String, SearchParams, boolean,
     * String)} with {@code userKey} first; {@code withProfileTabs=true} opens the
     * profile on its listings. Falls back to the secondary
     * {@code (SearchParams, String, String, String, boolean)} ctor. Everything but
     * {@code userKey} is optional (null). Returns null if neither is found.
     */
    private Object buildExtendedProfileArguments(String userKey) {
        Class<?> argsClass;
        Class<?> searchParams;
        try {
            argsClass = Class.forName(
                    "com.avito.android.extended_profile.ExtendedProfileArguments");
            searchParams = Class.forName("com.avito.android.remote.model.SearchParams");
        } catch (Throwable t) {
            return null;
        }
        // Primary constructor: userKey is the first parameter.
        try {
            java.lang.reflect.Constructor<?> ctor = argsClass.getConstructor(
                    String.class, String.class, searchParams, boolean.class, String.class);
            return ctor.newInstance(userKey, null, null, true, null);
        } catch (Throwable ignored) {
        }
        // Secondary constructor: SearchParams first, then the strings.
        try {
            java.lang.reflect.Constructor<?> ctor = argsClass.getConstructor(
                    searchParams, String.class, String.class, String.class, boolean.class);
            return ctor.newInstance(null, userKey, null, null, true);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void openInApp(String uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            intent.setPackage(getPackageName());
            startActivity(intent);
        } catch (Throwable t) {
            toast("Не удалось открыть");
        }
    }

    // -- Theme helpers ------------------------------------------------------

    private int themeColor(int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.resourceId != 0) {
                    return getResources().getColor(tv.resourceId, getTheme());
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
            int id = getResources().getIdentifier(attrName, "attr", getPackageName());
            if (id != 0) {
                TypedValue tv = new TypedValue();
                if (getTheme().resolveAttribute(id, tv, true)) {
                    if (tv.resourceId != 0) {
                        return getResources().getColor(tv.resourceId, getTheme());
                    }
                    return tv.data;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private android.graphics.drawable.Drawable themeDrawable(int attr) {
        try {
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
                return getResources().getDrawable(tv.resourceId, getTheme());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int blend(int base, int over, float ratio) {
        int r = Math.round(Color.red(base) * (1 - ratio) + Color.red(over) * ratio);
        int g = Math.round(Color.green(base) * (1 - ratio) + Color.green(over) * ratio);
        int b = Math.round(Color.blue(base) * (1 - ratio) + Color.blue(over) * ratio);
        return Color.rgb(r, g, b);
    }
}
