package eu.devsrv.samapp;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings: theme, sounds, notifications, battery saver, start screen, account (sign in / sync), updates. */
public class SettingsActivity extends BaseActivity {
    private LinearLayout col;
    private float d;
    private int text, muted, accent, onAccent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        text = getColor(R.color.text);
        muted = getColor(R.color.muted);
        accent = getColor(R.color.accent);
        onAccent = getColor(R.color.on_accent);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.bg));
        sv.setOnApplyWindowInsetsListener((v, insets) -> { // targetSdk 37 draws edge-to-edge
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(32));
        sv.addView(col);
        setContentView(sv);
        build();
    }

    private int dp(int v) { return (int) (v * d); }

    private void build() {
        col.removeAllViews();
        TextView title = label("Settings", 26, true, text);
        col.addView(title);

        section("Appearance");
        choice(new String[]{"System", "Dark", "Light"}, Settings.theme(this), i -> { Settings.setTheme(this, i); recreate(); });

        section("Sound and notifications");
        toggle("Goal sounds (cheer when your team scores, boo when they concede)", Settings.sounds(this), v -> Settings.setSounds(this, v));
        toggle("Live score notifications", Settings.notifications(this), v -> Settings.setNotifications(this, v));

        section("Refresh");
        toggle("Battery saver (refresh half as often)", Settings.saver(this), v -> Settings.setSaver(this, v));

        section("Start screen");
        choice(new String[]{"Auto", "Leagues", "My teams", "Following", "Table"}, Settings.startTab(this) + 1, i -> Settings.setStartTab(this, i - 1));

        section("Account");
        if (Settings.signedIn(this)) {
            col.addView(label("Signed in as " + Settings.user(this) + ". Your favourite teams and followed matches sync to your other devices.", 14, false, text));
            button("Sync now", () -> run("Syncing…", () -> { Sync.sync(this); return "Synced"; }));
            button("Sign out", () -> { Sync.signOut(this); build(); });
            button("Delete account", () -> new AlertDialog.Builder(this).setTitle("Delete account?")
                    .setMessage("Your account and its saved teams are removed from the server. Teams stay on this device.")
                    .setPositiveButton("Delete", (dlg, w) -> run("Deleting…", () -> { Sync.deleteAccount(this); return "Account deleted"; }))
                    .setNegativeButton("Cancel", null).show());
        } else {
            col.addView(label("Sign in to keep your favourite teams and followed matches on every device.", 14, false, text));
            button("Sign in / Create account", () -> accountDialog(""));
        }

        section("About");
        col.addView(label("Version " + BuildConfig.VERSION_NAME + "\nScores: football-data.org", 14, false, muted));
        button("Check for updates", () -> Updates.check(this, true));
    }

    // ---- building blocks

    private TextView label(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void section(String name) {
        TextView t = label(name.toUpperCase(), 12, true, muted);
        t.setPadding(dp(2), dp(22), 0, dp(6));
        col.addView(t);
    }

    private interface Pick { void on(int index); }
    private interface Flip { void on(boolean value); }

    /** Row of pills; the selected one is filled. */
    private void choice(String[] names, int selected, Pick pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        TextView[] pills = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            int idx = i;
            TextView p = new TextView(this);
            p.setText(names[i]);
            p.setTextSize(13);
            p.setPadding(dp(16), dp(8), dp(16), dp(8));
            pills[i] = p;
            p.setOnClickListener(v -> {
                for (int k = 0; k < pills.length; k++) paint(pills[k], k == idx);
                pick.on(idx);
            });
            paint(p, i == selected);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(8));
            row.addView(p, lp);
        }
        hs.addView(row);
        col.addView(hs);
    }

    private void paint(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.bg_pill_on : R.drawable.bg_pill_off);
        t.setTextColor(on ? onAccent : accent);
    }

    private void toggle(String name, boolean on, Flip flip) {
        Switch s = new Switch(this);
        s.setText(name);
        s.setTextColor(text);
        s.setTextSize(14);
        s.setChecked(on);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener((b, v) -> flip.on(v));
        col.addView(s);
    }

    private void button(String name, Runnable action) {
        TextView b = new TextView(this);
        b.setText(name);
        b.setTextSize(14);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        paint(b, false);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        col.addView(b, lp);
    }

    // ---- account

    private interface Job { String run() throws Exception; }

    /** Runs a network job off the main thread, then toasts the result (or the error) and redraws. */
    private void run(String busy, Job job) {
        Toast.makeText(this, busy, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try { msg = job.run(); } catch (Exception e) { msg = e.getMessage() == null ? "Failed" : e.getMessage(); }
            String shown = msg;
            runOnUiThread(() -> { Toast.makeText(this, shown, Toast.LENGTH_LONG).show(); build(); });
        }).start();
    }

    private void accountDialog(String prefill) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText user = new EditText(this), pass = new EditText(this);
        user.setHint("Username (letters, numbers, _)");
        user.setText(prefill);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pass.setHint("Password (8+ characters)");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(user);
        box.addView(pass);
        TextView note = label("There's no email, so a forgotten password can't be reset.", 12, false, muted);
        box.addView(note);
        new AlertDialog.Builder(this).setTitle("Account").setView(box)
                .setPositiveButton("Sign in", (dlg, w) -> submit(user.getText().toString(), pass.getText().toString(), false))
                .setNegativeButton("Create account", (dlg, w) -> submit(user.getText().toString(), pass.getText().toString(), true))
                .setNeutralButton("Cancel", null).show();
    }

    private void submit(String user, String pass, boolean create) {
        Toast.makeText(this, create ? "Creating account…" : "Signing in…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Sync.signIn(this, user.trim(), pass, create);
                runOnUiThread(() -> { Toast.makeText(this, "Signed in as " + Settings.user(this), Toast.LENGTH_SHORT).show(); build(); });
            } catch (Exception e) {
                runOnUiThread(() -> { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); accountDialog(user); });
            }
        }).start();
    }
}
