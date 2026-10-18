package eu.devsrv.samapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final long POLL_MS = 20_000, DAY = 86_400_000L;
    private int accent, onAccent, live, onLive = 0xFF1A1000, muted, text; // from res/values/colors.xml, set in onCreate

    private static final int TAB_LEAGUES = 0, TAB_MINE = 1, TAB_FOLLOWING = 2, TAB_TABLE = 3;
    private static final String[] TITLES = {"Leagues", "My teams", "Following", "Table"};
    private static final String[] ICONS = {"⚽", "★", "🔔", "🏆"};
    private static final String[] EMPTY = {
            "No matches this week",
            "No matches for your teams this week",
            "Not following any games yet.\nTap Follow on a match.",
            "No table for this league"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = () -> new Thread(this::refresh).start();
    private final MatchAdapter adapter = new MatchAdapter();
    private LinearLayout chips;
    private View chipScroll;
    private TextView status, title, empty;
    private final TextView[] nav = new TextView[4];

    private int tab = TAB_LEAGUES;
    private String league = Football.LEAGUES[0][0];
    private volatile List<Football.Match> matches = new ArrayList<>();
    private volatile List<Football.Row> table = new ArrayList<>(); // Table tab
    private volatile boolean mineLoaded; // false = My teams needs a full-week scan, true = just refresh today
    private boolean loaded;              // first data for the current view has arrived
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accent = getColor(R.color.accent);
        onAccent = getColor(R.color.on_accent);
        live = getColor(R.color.live);
        muted = getColor(R.color.muted);
        text = getColor(R.color.text);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        title = findViewById(R.id.title);
        chips = findViewById(R.id.chips);
        chipScroll = findViewById(R.id.chipScroll);
        empty = findViewById(R.id.empty);
        ListView list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        nav[0] = findViewById(R.id.navLeagues);
        nav[1] = findViewById(R.id.navMine);
        nav[2] = findViewById(R.id.navFollowing);
        nav[3] = findViewById(R.id.navTable);
        for (int i = 0; i < nav.length; i++) {
            int t = i;
            nav[i].setOnClickListener(v -> selectTab(t));
        }

        findViewById(R.id.test).setOnClickListener(v -> {
            requestNotificationPermission();
            Toast.makeText(this, "Lock your phone now - your team scores in 5s, theirs 7s later", Toast.LENGTH_LONG).show();
            Context app = getApplicationContext();
            EventLog.add(app, "test button pressed");
            // Arsenal = your team: they score (cheer), then Chelsea equalise (boo).
            // post() may download the logos, so it runs off the main thread.
            handler.postDelayed(() -> {
                new Thread(() -> Football.post(app, Football.test(1, 0, "Test: Arsenal score!"), true)).start();
                Sounds.cheer(app);
            }, 5000);
            handler.postDelayed(() -> {
                new Thread(() -> Football.post(app, Football.test(1, 1, "Test: Chelsea equalise"), true)).start();
                Sounds.boo(app);
            }, 12000);
        });
        findViewById(R.id.test).setOnLongClickListener(v -> { // hold Test: debug menu
            showLog();
            return true;
        });
        requestNotificationPermission();

        selectTab(!Football.favourites(this).isEmpty() ? TAB_MINE : TAB_LEAGUES);
    }

    /** Debug menu (hold Test): dialog with an "Events" tab (event log, newest last), a "Data" tab (feed source and match data) and a "Refresh" tab (how often and how fast the feed is polled). */
    private void showLog() {
        TextView t = new TextView(this);
        t.setTextIsSelectable(true);
        t.setTextSize(11);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setPadding(24, 16, 24, 16);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);

        int[] cur = {0};
        String[] names = {"Events", "Data", "Refresh"};
        TextView[] tabs = {new TextView(this), new TextView(this), new TextView(this)};
        Runnable[] load = new Runnable[1];
        LinearLayout bar = new LinearLayout(this);
        for (int i = 0; i < tabs.length; i++) {
            int tab = i;
            TextView b = tabs[i];
            b.setText(names[i]);
            b.setGravity(android.view.Gravity.CENTER);
            b.setPadding(0, 24, 0, 24);
            b.setOnClickListener(v -> { cur[0] = tab; load[0].run(); });
            bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        load[0] = () -> {
            for (int i = 0; i < tabs.length; i++) tabs[i].setTextColor(i == cur[0] ? accent : muted);
            if (cur[0] == 2) {
                t.setText(refreshReport());
                return;
            }
            if (cur[0] == 0) {
                String log = EventLog.read(this);
                t.setText(log.isEmpty() ? "(empty)" : log);
                sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                return;
            }
            t.setText("Loading from feed...");
            new Thread(() -> {
                String d = dataReport();
                runOnUiThread(() -> { if (cur[0] == 1) t.setText(d); });
            }).start();
        };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(bar);
        box.addView(sv);
        load[0].run();
        new AlertDialog.Builder(this).setTitle("Debug").setView(box)
                .setPositiveButton("Close", null)
                .setNegativeButton("Clear log", (d, w) -> EventLog.clear(this))
                .setNeutralButton("Check update", (d, w) -> Updates.check(this, true))
                .show();
    }

    /** When the feed was last refreshed and how fast, for the service (background) and the app (screen). */
    private String refreshReport() {
        String[] lines = EventLog.readPolls(this).split("\n");
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("Service polls every 12s while a followed match is live, else 30s.\nApp screen refreshes every 20s while open.\n");
        StringBuilder hist = new StringBuilder();
        for (String who : new String[]{"service", "app"}) {
            List<long[]> rows = new ArrayList<>(); // {time, ms, failed}
            String lastErr = "";
            for (String line : lines) {
                String[] p = line.split("\t", 4);
                if (p.length < 4 || !p[1].equals(who)) continue;
                boolean ok = p[3].equals("ok");
                if (!ok) lastErr = p[3];
                rows.add(new long[]{Long.parseLong(p[0]), Long.parseLong(p[2]), ok ? 0 : 1});
            }
            sb.append("\n").append(who.toUpperCase(Locale.US)).append("\n");
            if (rows.isEmpty()) {
                sb.append("  no refreshes recorded yet\n");
                continue;
            }
            int n = rows.size(), from = Math.max(0, n - 10), fails = 0;
            long ms = 0;
            for (int i = from; i < n; i++) { ms += rows.get(i)[1]; fails += (int) rows.get(i)[2]; }
            long[] last = rows.get(n - 1);
            sb.append("  last refresh ").append(fmt.format(new Date(last[0]))).append(" (").append((now - last[0]) / 1000).append("s ago)")
                    .append(last[2] == 1 ? " FAILED" : "").append("\n");
            if (n - from > 1) sb.append("  average gap ").append((rows.get(n - 1)[0] - rows.get(from)[0]) / (n - 1 - from) / 100 / 10.0).append("s\n");
            sb.append("  request time ").append(last[1]).append("ms (avg ").append(ms / (n - from)).append("ms)\n")
                    .append("  failures in last ").append(n - from).append(": ").append(fails).append("\n");
            if (fails > 0) sb.append("  last error: ").append(lastErr.substring(0, Math.min(160, lastErr.length()))).append("\n");
            for (int i = n - 1; i >= Math.max(0, n - 8); i--) {
                hist.append("  ").append(fmt.format(new Date(rows.get(i)[0]))).append(" ").append(who).append(" ")
                        .append(rows.get(i)[1]).append("ms ").append(rows.get(i)[2] == 1 ? "FAIL" : "ok").append("\n");
            }
        }
        return sb.append("\nRECENT\n").append(hist).toString();
    }

    /** Where the data comes from and what it says right now for every followed match. Blocking (network). */
    private String dataReport() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("SOURCE\nfootball-data.org v4 (free key, 10 requests/min)\n")
                .append(Football.url("<league codes>", now, now + DAY)).append("\n")
                .append("one request per refresh; service polls every 30s\n")
                .append("Mode: ").append(BuildConfig.FOOTBALL_DATA_KEY.isEmpty() ? (BuildConfig.WORKER_URL.isEmpty() ? "NOT CONFIGURED" : "score server (no key in app)") : "direct with a developer key").append("\n")
                .append("App version ").append(BuildConfig.VERSION_NAME).append("\n\n")
                .append("FAVOURITES\n").append(Football.favourites(this)).append("\n\n");
        SimpleDateFormat fmt = new SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault());
        List<Football.Match> follows = Football.follows(this);
        sb.append("FOLLOWED MATCHES (").append(follows.size()).append(")\n");
        java.util.Map<String, List<Football.Match>> cache = new java.util.HashMap<>();
        for (Football.Match f : follows) {
            sb.append("\n").append(f.home).append(" v ").append(f.away).append("\n")
                    .append("  id ").append(f.key()).append("\n")
                    .append("  kick-off ").append(fmt.format(new Date(f.when))).append("\n")
                    .append("  ").append(Football.url(null, f.when - DAY, f.when + DAY)).append("\n");
            try {
                Football.Match m = Football.lookup(f, cache);
                if (m == null) sb.append("  feed: NOT FOUND\n");
                else sb.append("  feed: ").append(m.hg).append("-").append(m.ag).append("  state=").append(m.state)
                        .append("  status=").append(m.status).append("\n");
            } catch (Exception e) {
                sb.append("  feed error: ").append(e).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updates.check(this, false); // every time the app is opened (20s debounce)
        running = true;
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacks(poll);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ---- navigation

    private void selectTab(int t) {
        tab = t;
        title.setText(TITLES[t]);
        for (int i = 0; i < nav.length; i++) {
            SpannableString s = new SpannableString(ICONS[i] + "\n" + TITLES[i]);
            s.setSpan(new RelativeSizeSpan(1.8f), 0, ICONS[i].length(), 0);
            nav[i].setText(s);
            nav[i].setTextColor(i == t ? accent : muted);
        }
        buildChips();
        reload();
    }

    /** Throw away what's on screen and fetch the current view from scratch. */
    private void reload() {
        matches = new ArrayList<>();
        table = new ArrayList<>();
        mineLoaded = false;
        loaded = false;
        status.setText("Loading…");
        render();
        if (running) {
            handler.removeCallbacks(poll);
            handler.post(poll);
        }
    }

    /** Leagues tab: one chip per league. My teams tab: one chip per starred team (tap to remove). */
    private void buildChips() {
        chips.removeAllViews();
        if (tab == TAB_LEAGUES || tab == TAB_TABLE) {
            for (String[] l : Football.LEAGUES) {
                TextView chip = pill(l[1]);
                setPill(chip, l[0].equals(league));
                chip.setOnClickListener(v -> {
                    league = l[0];
                    buildChips();
                    reload();
                });
                addChip(chip);
            }
        } else if (tab == TAB_MINE) {
            List<String> teams = new ArrayList<>(Football.favourites(this));
            Collections.sort(teams);
            for (String team : teams) {
                TextView chip = pill("★ " + team + "  ✕");
                setPill(chip, true);
                chip.setOnClickListener(v -> toggleTeam(team));
                addChip(chip);
            }
        }
        chipScroll.setVisibility(chips.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addChip(TextView chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(8));
        chips.addView(chip, lp);
    }

    // ---- data

    private void refresh() {
        int t = tab;
        String lg = league;
        long t0 = System.currentTimeMillis();
        try {
            if (t == TAB_TABLE) {
                List<Football.Row> rows = Football.standings(lg);
                EventLog.poll(this, "app", System.currentTimeMillis() - t0, null);
                runOnUiThread(() -> {
                    if (t != tab || !lg.equals(league)) return;
                    table = rows;
                    loaded = true;
                    render();
                    status.setText("Updated " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
                    reschedule();
                });
                return;
            }
            List<Football.Match> ms = t == TAB_MINE ? Football.fetchMine(this, matches, !mineLoaded)
                    : t == TAB_FOLLOWING ? Football.fetchFollowed(this)
                    : Football.fetch(lg, 1, 6);
            EventLog.poll(this, "app", System.currentTimeMillis() - t0, null);
            runOnUiThread(() -> {
                if (t != tab || !lg.equals(league)) return; // user switched view mid-request
                matches = ms;
                mineLoaded = true;
                loaded = true;
                if (Football.autoFollow(this, ms)) Football.syncService(this);
                render();
                status.setText("Updated " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())
                        + " · tap a team to ★ it");
                reschedule();
            });
        } catch (Exception e) {
            EventLog.add(this, "refresh failed (tab " + t + "): " + e);
            EventLog.poll(this, "app", System.currentTimeMillis() - t0, e.toString());
            runOnUiThread(() -> {
                if (t != tab || !lg.equals(league)) return;
                status.setText("Offline - retrying");
                reschedule();
            });
        }
    }

    private void reschedule() {
        handler.removeCallbacks(poll);
        if (running) handler.postDelayed(poll, POLL_MS);
    }

    /** Live matches first, then today and upcoming (soonest first), then earlier days (most recent first). Table tab: heading + one row per team. */
    private void render() {
        List<Object> rows = new ArrayList<>();
        if (tab == TAB_TABLE) {
            if (!table.isEmpty()) rows.add("heading");
            rows.addAll(table);
        } else {
            List<Football.Match> sorted = new ArrayList<>(matches);
            String today = Football.ymd(System.currentTimeMillis());
            Collections.sort(sorted, (a, b) -> {
                int ra = rank(a, today), rb = rank(b, today);
                if (ra != rb) return Integer.compare(ra, rb);
                return ra == 2 ? Long.compare(b.when, a.when) : Long.compare(a.when, b.when);
            });
            rows.addAll(sorted);
        }
        adapter.rows = rows;
        adapter.notifyDataSetChanged();
        empty.setText(!loaded ? "Loading…"
                : tab == TAB_MINE && Football.favourites(this).isEmpty() ? "No teams yet.\nOpen Leagues and tap a team name to ★ it."
                : EMPTY[tab]);
    }

    private static int rank(Football.Match m, String today) {
        if (m.state.equals("in")) return 0;
        return Football.ymd(m.when).compareTo(today) < 0 ? 2 : 1;
    }

    private static String dayLabel(long when) {
        long now = System.currentTimeMillis();
        String d = Football.ymd(when);
        if (d.equals(Football.ymd(now))) return "Today";
        if (d.equals(Football.ymd(now + DAY))) return "Tomorrow";
        if (d.equals(Football.ymd(now - DAY))) return "Yesterday";
        return new SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(new Date(when));
    }

    /** Tap a match card: live games open BBC Sport's scores page for the league (tap the game for live commentary), others a web search that Google shows as a stats/preview card. */
    private void openMatch(Football.Match m) {
        String url;
        if (m.state.equals("in")) {
            url = Football.bbcUrl(m.league);
        } else {
            String q = m.home + " vs " + m.away + " " + new SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(new Date(m.when));
            url = "https://www.google.com/search?q=" + Uri.encode(q);
        }
        startActivity(new Intent(this, WebActivity.class).putExtra("url", url));
    }

    private void toggleFollow(Football.Match m) {
        if (Football.isFollowed(this, m.key())) {
            Football.unfollow(this, m.key());
            getSystemService(NotificationManager.class).cancel(m.key().hashCode());
            if (tab == TAB_FOLLOWING) { // no longer belongs on this tab
                List<Football.Match> rest = new ArrayList<>(matches);
                rest.removeIf(x -> x.key().equals(m.key()));
                matches = rest;
            }
        } else {
            requestNotificationPermission();
            Football.follow(this, m);
        }
        Football.syncService(this);
        render();
    }

    private void toggleTeam(String team) {
        boolean added = Football.toggleFavourite(this, team);
        Football.autoFollow(this, matches); // pick up its fixtures already on screen
        Football.syncService(this);         // service finds the rest across all leagues
        Toast.makeText(this, added ? team + " is now your team - you'll be told when they play" : team + " removed",
                Toast.LENGTH_SHORT).show();
        if (tab == TAB_MINE) { // the tab's contents depend on the favourites
            buildChips();
            reload();
        } else {
            render();
        }
    }

    // ---- views

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView pill(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        return t;
    }

    private void setPill(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.bg_pill_on : R.drawable.bg_pill_off);
        t.setTextColor(on ? onAccent : accent);
    }

    private class MatchAdapter extends BaseAdapter {
        List<Object> rows = new ArrayList<>();

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int i) { return rows.get(i) instanceof String ? 0 : rows.get(i) instanceof Football.Row ? 2 : 1; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Object row = rows.get(i);
            if (row instanceof String) {
                return v != null ? v : getLayoutInflater().inflate(R.layout.item_header, parent, false);
            }
            if (row instanceof Football.Row) return tableRow((Football.Row) row, v, parent);
            Football.Match m = (Football.Match) row;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_match, parent, false);
            boolean pre = m.state.equals("pre"), isLive = m.state.equals("in");
            v.findViewById(R.id.card).setBackgroundResource(isLive ? R.drawable.bg_card_live : R.drawable.bg_card);
            ((TextView) v.findViewById(R.id.date)).setText(dayLabel(m.when));
            TextView lg = v.findViewById(R.id.league);
            lg.setVisibility(tab == TAB_LEAGUES ? View.GONE : View.VISIBLE); // the Leagues tab already says which league
            lg.setText(Football.leagueName(m.league));
            TextView st = v.findViewById(R.id.state);
            if (isLive) { // orange LIVE badge, with the minute when we have one
                st.setText(m.status.equals("Live") ? "LIVE" : "LIVE " + m.status);
                st.setTextColor(onLive);
                st.setBackgroundResource(R.drawable.bg_live_badge);
            } else {
                st.setText(m.status);
                st.setTextColor(muted);
                st.setBackground(null);
            }
            team(v.findViewById(R.id.home), m.home, "★ %s");
            team(v.findViewById(R.id.away), m.away, "★ %s");
            Logos.load(v.findViewById(R.id.homeLogo), m.homeLogo);
            Logos.load(v.findViewById(R.id.awayLogo), m.awayLogo);
            ((TextView) v.findViewById(R.id.score)).setText(pre ? "VS" : m.hg + " : " + m.ag);
            TextView follow = v.findViewById(R.id.follow);
            boolean followed = Football.isFollowed(MainActivity.this, m.key());
            follow.setVisibility(m.state.equals("post") ? View.GONE : View.VISIBLE); // nothing to follow once finished
            follow.setText(followed ? "Following ✓" : "Follow");
            setPill(follow, followed);
            follow.setOnClickListener(x -> toggleFollow(m));
            v.findViewById(R.id.card).setOnClickListener(x -> openMatch(m));
            return v;
        }

        private View tableRow(Football.Row r, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_table_row, parent, false);
            boolean fav = Football.favourites(MainActivity.this).contains(r.team);
            v.findViewById(R.id.bar).setVisibility(fav ? View.VISIBLE : View.INVISIBLE);
            ((TextView) v.findViewById(R.id.pos)).setText(String.format(Locale.US, "%02d", r.pos));
            ((TextView) v.findViewById(R.id.team)).setText(fav ? "★ " + r.team : r.team);
            ((TextView) v.findViewById(R.id.played)).setText(String.format(Locale.US, "%02d", r.played));
            ((TextView) v.findViewById(R.id.goals)).setText(r.gf + "/" + r.ga);
            ((TextView) v.findViewById(R.id.pts)).setText(String.format(Locale.US, "%02d", r.pts));
            Logos.load(v.findViewById(R.id.crest), r.crest);
            v.setOnClickListener(x -> startActivity(new Intent(MainActivity.this, NewsActivity.class).putExtra("team", r.team)));
            v.setOnLongClickListener(x -> { toggleTeam(r.team); return true; }); // long-press = star the team
            return v;
        }

        /** Team name; tap to make it your team (green with a star). */
        private void team(View view, String name, String starFormat) {
            TextView t = (TextView) view;
            boolean fav = Football.favourites(MainActivity.this).contains(name);
            t.setText((fav ? String.format(starFormat, name) : name).toUpperCase(Locale.getDefault()));
            t.setTextColor(fav ? accent : text);
            t.setOnClickListener(x -> toggleTeam(name));
        }
    }
}
