package app.avito.morphe;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Generic, code-built "Настройки Morphe" screen. Renders the entries registered by
 * Avito patches (baked into {@link MorpheSettings#config()} as JSON):
 * <ul>
 *   <li>{@code switch} — a toggle persisted to {@code avito_morphe_settings};
 *       feature code reads it via {@link MorpheSettings#isEnabled}.</li>
 *   <li>{@code screen} — a row that opens another Activity (e.g. the blacklist
 *       manager). Stacks in the same task so back returns here.</li>
 * </ul>
 * Entries are grouped by their build-time {@code section} metadata and already
 * arrive in stable display order. Styled with {@link MorpheTheme} so it matches
 * Avito's palette and light/dark.
 */
public final class MorpheSettingsActivity extends Activity {

    private MorpheTheme theme;
    private View restartBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(MorpheSettings.SETTINGS_ENTRY_TITLE);
        theme = new MorpheTheme(this);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(theme.colorBackground);
        outer.addView(theme.buildTopBar(MorpheSettings.SETTINGS_ENTRY_TITLE, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(theme.colorBackground);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.addView(scroll);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(theme.dp(16), 0, theme.dp(16), theme.dp(32));
        scroll.addView(list);

        renderEntries(list);

        restartBar = buildRestartBar();
        outer.addView(restartBar);

        setContentView(outer);
    }

    /** A bottom bar offering to restart the app, shown when a restart-required
     *  toggle is changed. */
    private View buildRestartBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(theme.colorSurface);
        bar.setPadding(theme.dp(16), theme.dp(12), theme.dp(16), theme.dp(12));
        bar.setVisibility(View.GONE);

        TextView msg = new TextView(this);
        msg.setText("Изменения вступят в силу после перезапуска");
        msg.setTextColor(theme.textSecondary);
        msg.setTextSize(13);
        msg.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(msg);

        TextView btn = new TextView(this);
        btn.setText("Перезапустить");
        btn.setTextColor(theme.accent);
        btn.setTextSize(14);
        btn.setAllCaps(true);
        btn.setPadding(theme.dp(12), theme.dp(8), 0, theme.dp(8));
        btn.setBackground(theme.themeDrawable(android.R.attr.selectableItemBackground));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MorpheSettings.restart(MorpheSettingsActivity.this);
            }
        });
        bar.addView(btn);
        return bar;
    }

    private void showRestartBar() {
        if (restartBar != null) {
            restartBar.setVisibility(View.VISIBLE);
        }
    }

    private void renderEntries(LinearLayout list) {
        JSONArray entries;
        try {
            entries = new JSONArray(MorpheSettings.config());
        } catch (Throwable t) {
            entries = new JSONArray();
        }
        if (entries.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("Нет доступных настроек");
            empty.setTextColor(theme.textSecondary);
            empty.setTextSize(14);
            empty.setPadding(0, theme.dp(16), 0, 0);
            list.addView(empty);
        }
        String currentSection = null;
        boolean hasItemInSection = false;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) {
                continue;
            }
            String section = e.optString("section", "Прочее");
            if (!section.equals(currentSection)) {
                currentSection = section;
                hasItemInSection = false;
                list.addView(buildSectionHeader(section, list.getChildCount() == 0));
            } else if (hasItemInSection) {
                list.addView(theme.makeDivider());
            }
            String type = e.optString("type");
            if ("switch".equals(type)) {
                list.addView(buildSwitchRow(e));
                hasItemInSection = true;
            } else if ("screen".equals(type)) {
                list.addView(buildScreenRow(e));
                hasItemInSection = true;
            }
        }
        buildFooter(list);
    }

    private View buildSectionHeader(String title, boolean first) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(theme.accent);
        header.setTextSize(MorpheTheme.SUBTITLE_SP);
        header.setIncludeFontPadding(false);
        header.setPadding(0, theme.dp(first ? 16 : 28), 0, theme.dp(8));
        return header;
    }

    private View buildSwitchRow(JSONObject e) {
        final String key = e.optString("key");
        String title = e.optString("title", key);
        String summary = e.optString("summary", null);
        boolean def = e.optBoolean("default", true);
        final boolean restartRequired = e.optBoolean("restart", false);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(theme.dp(56));
        row.setPadding(0, theme.dp(10), 0, theme.dp(10));
        row.setBackground(theme.themeDrawable(android.R.attr.selectableItemBackground));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = theme.dp(16);
        textCol.setLayoutParams(colLp);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(theme.textPrimary);
        titleView.setIncludeFontPadding(false);
        titleView.setTextSize(MorpheTheme.ROW_TITLE_SP);
        textCol.addView(titleView);

        if (summary != null && !summary.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(summary);
            sub.setTextColor(theme.textSecondary);
            sub.setIncludeFontPadding(false);
            sub.setTextSize(MorpheTheme.SUBTITLE_SP);
            sub.setPadding(0, theme.dp(2), 0, 0);
            textCol.addView(sub);
        }
        if (restartRequired) {
            TextView restartHint = new TextView(this);
            restartHint.setText("Требуется перезапуск приложения");
            restartHint.setTextColor(theme.warning);
            restartHint.setIncludeFontPadding(false);
            restartHint.setTextSize(MorpheTheme.META_SP);
            restartHint.setPadding(0, theme.dp(3), 0, 0);
            textCol.addView(restartHint);
        }
        row.addView(textCol);

        final Switch sw = new Switch(this);
        sw.setChecked(MorpheSettings.isEnabled(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                MorpheSettings.setEnabled(key, isChecked);
                if (restartRequired) {
                    showRestartBar();
                }
            }
        });
        row.addView(sw);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setChecked(!sw.isChecked());
            }
        });
        return row;
    }

    private View buildScreenRow(JSONObject e) {
        String title = e.optString("title", e.optString("key"));
        String summary = e.optString("summary", null);
        final String activity = e.optString("activity", null);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(theme.dp(56));
        row.setPadding(0, theme.dp(10), 0, theme.dp(10));
        row.setBackground(theme.themeDrawable(android.R.attr.selectableItemBackground));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activity == null || activity.isEmpty()) {
                    return;
                }
                try {
                    android.content.Intent intent = new android.content.Intent();
                    intent.setClassName(getPackageName(), activity);
                    startActivity(intent);
                } catch (Throwable ignored) {
                }
            }
        });

        // Like Avito's own navigation rows: text only, no trailing chevron.
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(theme.textPrimary);
        titleView.setIncludeFontPadding(false);
        titleView.setTextSize(MorpheTheme.ROW_TITLE_SP);
        textCol.addView(titleView);

        if (summary != null && !summary.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(summary);
            sub.setTextColor(theme.textSecondary);
            sub.setIncludeFontPadding(false);
            sub.setTextSize(MorpheTheme.SUBTITLE_SP);
            sub.setPadding(0, theme.dp(2), 0, 0);
            textCol.addView(sub);
        }
        row.addView(textCol);
        return row;
    }

    private void buildFooter(LinearLayout list) {
        list.addView(buildSectionHeader("О приложении", false));
        list.addView(buildVersionRow());
        list.addView(theme.makeDivider());
        list.addView(buildPatchVersionRow());
        list.addView(theme.makeDivider());
        list.addView(buildResetRow());
    }

    private View buildVersionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(theme.dp(56));
        row.setPadding(0, theme.dp(10), 0, theme.dp(10));

        TextView title = new TextView(this);
        title.setText("Версия Avito");
        title.setTextColor(theme.textPrimary);
        title.setIncludeFontPadding(false);
        title.setTextSize(MorpheTheme.ROW_TITLE_SP);
        row.addView(title);

        TextView summary = new TextView(this);
        summary.setText(appVersionSummary());
        summary.setTextColor(theme.textSecondary);
        summary.setIncludeFontPadding(false);
        summary.setTextSize(MorpheTheme.SUBTITLE_SP);
        summary.setPadding(0, theme.dp(2), 0, 0);
        row.addView(summary);
        return row;
    }

    private String appVersionSummary() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            String name = info.versionName == null ? "—" : info.versionName;
            return name + " · сборка " + info.versionCode;
        } catch (Throwable ignored) {
            return "—";
        }
    }

    private View buildPatchVersionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(theme.dp(56));
        row.setPadding(0, theme.dp(10), 0, theme.dp(10));

        TextView title = new TextView(this);
        title.setText("Версия пакета патчей Morphe");
        title.setTextColor(theme.textPrimary);
        title.setIncludeFontPadding(false);
        title.setTextSize(MorpheTheme.ROW_TITLE_SP);
        row.addView(title);

        TextView summary = new TextView(this);
        summary.setText(MorpheSettings.patchVersion());
        summary.setTextColor(theme.textSecondary);
        summary.setIncludeFontPadding(false);
        summary.setTextSize(MorpheTheme.SUBTITLE_SP);
        summary.setPadding(0, theme.dp(2), 0, 0);
        row.addView(summary);
        return row;
    }

    private View buildResetRow() {
        TextView row = new TextView(this);
        row.setText("Сбросить настройки");
        row.setTextColor(theme.accent);
        row.setIncludeFontPadding(false);
        row.setTextSize(MorpheTheme.ROW_TITLE_SP);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(theme.dp(56));
        row.setPadding(0, theme.dp(10), 0, theme.dp(10));
        row.setBackground(theme.themeDrawable(android.R.attr.selectableItemBackground));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        return row;
    }

    private void confirmReset() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Сбросить настройки?")
                .setMessage("Все переключатели Morphe вернутся к значениям по умолчанию. Чёрный список не изменится.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сбросить", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        MorpheSettings.resetPreferences();
                        recreate();
                    }
                })
                .show();
    }
}
