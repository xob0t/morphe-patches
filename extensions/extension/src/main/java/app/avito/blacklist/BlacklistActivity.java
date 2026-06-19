package app.avito.blacklist;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
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
 * <p>Built entirely in code (no app resources) so it can be merged into any
 * Avito build by the patch. Provides add/remove for offer and seller ids and
 * import/export of the same JSON format used by the Ave Blacklist extension.
 */
public final class BlacklistActivity extends Activity {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int PAD = 32;

    private LinearLayout listContainer;
    private TextView header;
    private String pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Avito Blacklist");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(PAD, PAD, PAD, PAD);
        scroll.addView(root);

        header = new TextView(this);
        header.setTextColor(Color.BLACK);
        header.setTextSize(18);
        header.setPadding(0, 0, 0, PAD);
        root.addView(header);

        addInputRow(root, "Offer id", true);
        addInputRow(root, "Seller id (userKey)", false);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(makeButton("Import", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startImport();
            }
        }));
        buttons.addView(makeButton("Export", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        }));
        buttons.addView(makeButton("Clear", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Blacklist.clear();
                refresh();
                toast("Blacklist cleared");
            }
        }));
        root.addView(buttons);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, PAD, 0, 0);
        root.addView(listContainer);

        setContentView(scroll);
        handleIntentExtras(getIntent());
        refresh();
    }

    /**
     * Allows seeding/importing the blacklist non-interactively, e.g.
     * {@code am start -n <pkg>/app.avito.blacklist.BlacklistActivity --es add_offer 123}.
     * Supports {@code add_offer}, {@code add_seller} and {@code import_json} extras.
     */
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

    private void addInputRow(LinearLayout parent, String hint, final boolean offer) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, PAD / 2);

        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.BLACK);
        input.setHintTextColor(Color.GRAY);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(lp);
        row.addView(input);

        row.addView(makeButton("Add", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) {
                    return;
                }
                boolean added = offer ? Blacklist.addOffer(value) : Blacklist.addSeller(value);
                input.setText("");
                refresh();
                toast(added ? "Added" : "Already blocked");
            }
        }));
        parent.addView(row);
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private void refresh() {
        int offers = Blacklist.offerCount();
        int sellers = Blacklist.sellerCount();
        header.setText("Blocked offers: " + offers + "    Blocked sellers: " + sellers);

        listContainer.removeAllViews();
        addSection("Offers", Blacklist.getOffers(), true);
        addSection("Sellers", Blacklist.getSellers(), false);
    }

    private void addSection(String title, List<String> items, final boolean offer) {
        TextView label = new TextView(this);
        label.setText(title + " (" + items.size() + ")");
        label.setTextColor(Color.DKGRAY);
        label.setTextSize(15);
        label.setPadding(0, PAD / 2, 0, PAD / 4);
        listContainer.addView(label);

        for (final String id : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView value = new TextView(this);
            value.setText(id);
            value.setTextColor(Color.BLACK);
            value.setTextSize(14);
            value.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(value);

            row.addView(makeButton("Remove", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (offer) {
                        Blacklist.removeOffer(id);
                    } else {
                        Blacklist.removeSeller(id);
                    }
                    refresh();
                }
            }));
            listContainer.addView(row);
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
            toast("No file picker available");
        }
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Throwable t) {
            toast("No file picker available");
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
                toast("Could not read file");
                return;
            }
            int added = Blacklist.importText(content, false);
            if (added < 0) {
                toast("Import failed: unrecognized format");
            } else {
                refresh();
                toast("Imported " + added + " new entries");
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
                toast("Exported");
            }
        } catch (Throwable t) {
            toast("Export failed");
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
}
