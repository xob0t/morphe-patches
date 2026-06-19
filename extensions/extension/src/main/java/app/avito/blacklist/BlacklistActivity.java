package app.avito.blacklist;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
        colorBackground = themeColor(android.R.attr.colorBackground, Color.WHITE);
        textPrimary = themeColor(android.R.attr.textColorPrimary, Color.BLACK);
        textSecondary = themeColor(android.R.attr.textColorSecondary, Color.GRAY);
        accent = themeColor(android.R.attr.colorAccent, 0xFF0A7CFF);
        colorSurface = blend(colorBackground, textPrimary, 0.04f);
        divider = blend(colorBackground, textPrimary, 0.12f);

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

        addInputRow(root, "ID объявления", true);
        addInputRow(root, "ID продавца (userKey)", false);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, 8 * dp, 0, 0);
        buttons.addView(secondaryButton("Импорт", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startImport();
            }
        }));
        buttons.addView(secondaryButton("Экспорт", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        }));
        buttons.addView(secondaryButton("Очистить", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        }));
        root.addView(buttons);

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

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(textPrimary);
        back.setPadding(16 * dp, 12 * dp, 16 * dp, 12 * dp);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("Чёрный список");
        title.setTextSize(20);
        title.setTextColor(textPrimary);
        bar.addView(title);

        return bar;
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

    private void addInputRow(LinearLayout parent, String hint, final boolean offer) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 8 * dp);

        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(textPrimary);
        input.setHintTextColor(textSecondary);
        input.setTextSize(15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(input);

        Button add = primaryButton("Добавить", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) {
                    return;
                }
                boolean added = offer ? Blacklist.addOffer(value) : Blacklist.addSeller(value);
                input.setText("");
                refresh();
                toast(added ? "Добавлено" : "Уже в списке");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = 8 * dp;
        add.setLayoutParams(lp);
        row.addView(add);

        parent.addView(row);
    }

    private Button primaryButton(String text, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextColor(onAccentColor());
        button.setBackground(pill(accent));
        return button;
    }

    private Button secondaryButton(String text, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextColor(accent);
        button.setBackground(pill(colorSurface));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = 8 * dp;
        button.setLayoutParams(lp);
        return button;
    }

    private Button baseButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(44 * dp);
        button.setMinimumHeight(44 * dp);
        button.setPadding(16 * dp, 0, 16 * dp, 0);
        button.setStateListAnimator(null);
        button.setOnClickListener(listener);
        return button;
    }

    private GradientDrawable pill(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(12 * dp);
        return d;
    }

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

            TextView value = new TextView(this);
            value.setText(id);
            value.setTextColor(textPrimary);
            value.setTextSize(15);
            value.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            rowItem.addView(value);

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

    /** White or black text, whichever contrasts with the accent fill. */
    private int onAccentColor() {
        int r = Color.red(accent);
        int g = Color.green(accent);
        int b = Color.blue(accent);
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.6 ? Color.BLACK : Color.WHITE;
    }

    private static int blend(int base, int over, float ratio) {
        int r = Math.round(Color.red(base) * (1 - ratio) + Color.red(over) * ratio);
        int g = Math.round(Color.green(base) * (1 - ratio) + Color.green(over) * ratio);
        int b = Math.round(Color.blue(base) * (1 - ratio) + Color.blue(over) * ratio);
        return Color.rgb(r, g, b);
    }
}
