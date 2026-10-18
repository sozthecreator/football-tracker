package eu.devsrv.samapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executors;

/** football-data.org fetching, followed-match storage and score notifications. */
final class Football {
    // football-data.org v4: free key (10 requests/min), one request covers every league and day in a range
    private static final String API = "https://api.football-data.org/v4";
    // Free-tier competitions only (others answer 403)
    static final String[][] LEAGUES = { // code, name, bbc.co.uk/sport/football/<slug>
            {"PL", "Premier League", "premier-league"}, {"ELC", "Championship", "championship"},
            {"PD", "La Liga", "spanish-la-liga"}, {"BL1", "Bundesliga", "german-bundesliga"},
            {"SA", "Serie A", "italian-serie-a"}, {"FL1", "Ligue 1", "french-ligue-one"},
            {"CL", "Champions League", "champions-league"}, {"DED", "Eredivisie", "dutch-eredivisie"},
            {"PPL", "Primeira Liga", "portuguese-primeira-liga"}, {"BSA", "Brasileirão", "brazilian-league"},
    };
    private static final long DAY_MS = 86_400_000L;
    private static final String CH_SCORES = "scores", CH_TRACK = "tracking_min";

    /** One line of a league table. */
    static final class Row {
        int pos, played, gf, ga, pts;
        String team = "", crest = "";
    }

    static final class Match {
        String league, id, home, away, hg = "0", ag = "0", state = "pre", status = "", homeLogo = "", awayLogo = "";
        long when;

        String key() { return league + "|" + id; }
    }

    // ---- data

    /** Matches from {@code back} days ago to {@code fwd} days ahead: a single request. */
    static List<Match> fetch(String league, int back, int fwd) throws Exception {
        return fetchLeagues(List.of(league), back, fwd);
    }

    static List<Match> fetchLeagues(List<String> leagues, int back, int fwd) throws Exception {
        long now = System.currentTimeMillis();
        return range(String.join(",", leagues), now - back * DAY_MS, now + (fwd + 1) * DAY_MS);
    }

    static List<String> allLeagueSlugs() {
        List<String> slugs = new ArrayList<>();
        for (String[] l : LEAGUES) slugs.add(l[0]);
        return slugs;
    }

    /**
     * Matches of favourite teams across all leagues. A {@code full} load scans the whole week in every league;
     * later refreshes only re-read today in the leagues where a match was found, and merge into prev,
     * so live scores stay cheap.
     */
    static List<Match> fetchMine(Context c, List<Match> prev, boolean full) throws Exception {
        Set<String> teams = favourites(c);
        if (teams.isEmpty()) return new ArrayList<>();
        List<String> leagues = allLeagueSlugs();
        if (!full) {
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (Match m : prev) seen.add(m.league);
            leagues = new ArrayList<>(seen);
        }
        Map<String, Match> out = new LinkedHashMap<>();
        for (Match m : prev) if (teams.contains(m.home) || teams.contains(m.away)) out.put(m.key(), m);
        if (!leagues.isEmpty()) {
            for (Match m : fetchLeagues(leagues, full ? 1 : 0, full ? 6 : 0)) {
                if (teams.contains(m.home) || teams.contains(m.away)) out.put(m.key(), m);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Current data for a followed match, or null if the feed doesn't list it. {@code cache}: one request per match day. */
    static Match lookup(Match f, Map<String, List<Match>> cache) throws Exception {
        String ck = ymd(f.when);
        List<Match> day = cache.get(ck);
        if (day == null) cache.put(ck, day = range(null, f.when - DAY_MS, f.when + DAY_MS));
        for (Match d : day) if (d.id.equals(f.id)) return d;
        return null;
    }

    /** All followed matches with live data; ones the feed can't find fall back to what was stored when following. */
    static List<Match> fetchFollowed(Context c) throws Exception {
        Map<String, List<Match>> cache = new java.util.HashMap<>();
        List<Match> out = new ArrayList<>(), saved = follows(c);
        int failed = 0;
        for (Match f : saved) {
            Match m = null;
            try {
                m = lookup(f, cache);
            } catch (Exception e) {
                failed++;
            }
            out.add(m != null ? m : f);
        }
        if (!saved.isEmpty() && failed == saved.size()) throw new java.io.IOException("all lookups failed");
        return out;
    }

    /** League table for a competition code. */
    static List<Row> standings(String code) throws Exception {
        JSONArray groups = new JSONObject(get(base() + "/competitions/" + code + "/standings")).getJSONArray("standings");
        JSONObject pick = groups.getJSONObject(0);
        for (int i = 0; i < groups.length(); i++) {
            if ("TOTAL".equals(groups.getJSONObject(i).optString("type"))) { pick = groups.getJSONObject(i); break; }
        }
        JSONArray table = pick.getJSONArray("table");
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < table.length(); i++) {
            JSONObject t = table.getJSONObject(i), team = t.getJSONObject("team");
            Row r = new Row();
            r.pos = t.getInt("position");
            r.team = teamName(team);
            r.crest = smallLogo(team.isNull("crest") ? "" : team.optString("crest"));
            r.played = t.getInt("playedGames");
            r.gf = t.getInt("goalsFor");
            r.ga = t.getInt("goalsAgainst");
            r.pts = t.getInt("points");
            out.add(r);
        }
        return out;
    }

    /** BBC Sport scores page for the league: live matches are tapped from there. */
    static String bbcUrl(String code) {
        for (String[] l : LEAGUES) if (l[0].equals(code)) return "https://www.bbc.co.uk/sport/football/" + l[2] + "/scores-fixtures";
        return "https://www.bbc.co.uk/sport/football/scores-fixtures";
    }

    static String leagueName(String slug) {
        for (String[] l : LEAGUES) if (l[0].equals(slug)) return l[1];
        return slug;
    }

    /** Developer builds with a key talk to football-data.org directly; release builds go through the score server (Cloudflare Worker) that holds the key. */
    static String base() {
        return BuildConfig.FOOTBALL_DATA_KEY.isEmpty() && !BuildConfig.WORKER_URL.isEmpty() ? BuildConfig.WORKER_URL + "/v4" : API;
    }

    private static String dash(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    static String url(String competitions, long from, long to) {
        return base() + "/matches?dateFrom=" + dash(from) + "&dateTo=" + dash(to)
                + (competitions == null ? "" : "&competitions=" + competitions);
    }

    private static String teamName(JSONObject t) {
        String n = t.isNull("shortName") ? "" : t.optString("shortName");
        if (n.isEmpty()) n = t.isNull("name") ? "" : t.optString("name");
        return n;
    }

    /** GET with the API key; throws with the server's message on 4xx/5xx. */
    private static String get(String url) throws Exception {
        if (BuildConfig.FOOTBALL_DATA_KEY.isEmpty() && BuildConfig.WORKER_URL.isEmpty())
            throw new java.io.IOException("no score server or API key: add workerUrl=... or footballDataKey=... to local.properties and rebuild");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (!BuildConfig.FOOTBALL_DATA_KEY.isEmpty()) c.setRequestProperty("X-Auth-Token", BuildConfig.FOOTBALL_DATA_KEY);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        int code = c.getResponseCode();
        if (code >= 400) { // 429 = over 10 requests/min, 403 = key can't see that competition
            String body = "";
            try (InputStream err = c.getErrorStream()) { if (err != null) body = new Scanner(err, "UTF-8").useDelimiter("\\A").next(); } catch (Exception ignored) { }
            throw new java.io.IOException("HTTP " + code + " " + url + " " + body.substring(0, Math.min(200, body.length())));
        }
        String json;
        try (InputStream in = c.getInputStream()) {
            // Scanner, not readAllBytes(): that needs API 33, minSdk is 29
            json = new Scanner(in, "UTF-8").useDelimiter("\\A").next();
        }
        return json;
    }

    /** All matches between the two instants, optionally only for comma-separated competition codes. */
    static List<Match> range(String competitions, long from, long to) throws Exception {
        String json = get(url(competitions, from, to));
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

        List<Match> out = new ArrayList<>();
        JSONArray events = new JSONObject(json).getJSONArray("matches");
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject h = e.getJSONObject("homeTeam"), a = e.getJSONObject("awayTeam");
            Match m = new Match();
            m.home = teamName(h);
            m.away = teamName(a);
            if (m.home.isEmpty() || m.away.isEmpty()) continue; // knockout tie with teams still to be decided
            m.league = e.getJSONObject("competition").getString("code");
            m.id = String.valueOf(e.getLong("id"));
            m.when = iso.parse(e.getString("utcDate")).getTime();
            String st = e.getString("status");
            switch (st) {
                case "IN_PLAY": case "PAUSED": case "LIVE":
                    m.state = "in";
                    m.status = st.equals("PAUSED") ? "HT" : e.isNull("minute") ? "Live" : e.optString("minute") + "'";
                    break;
                case "FINISHED": case "AWARDED":
                    m.state = "post";
                    m.status = "FT";
                    break;
                case "POSTPONED": case "CANCELLED": case "SUSPENDED":
                    m.state = "post";
                    m.status = st.charAt(0) + st.substring(1).toLowerCase(Locale.US);
                    break;
                default: // SCHEDULED, TIMED
                    m.state = "pre";
                    m.status = clock.format(new Date(m.when));
            }
            JSONObject ft = e.getJSONObject("score").getJSONObject("fullTime"); // current score while live
            if (!ft.isNull("home")) m.hg = String.valueOf(ft.getInt("home"));
            if (!ft.isNull("away")) m.ag = String.valueOf(ft.getInt("away"));
            m.homeLogo = smallLogo(h.isNull("crest") ? "" : h.optString("crest"));
            m.awayLogo = smallLogo(a.isNull("crest") ? "" : a.optString("crest"));
            out.add(m);
        }
        return out;
    }

    /** ESPN's logos are 500px; its image combiner serves a 96px copy. football-data crests are often SVG, which Android can't decode, so a proxy turns those into PNG. */
    private static String smallLogo(String url) {
        if (url.endsWith(".svg")) return "https://wsrv.nl/?url=" + url + "&w=96&h=96&output=png";
        String host = "https://a.espncdn.com";
        return url.startsWith(host) ? host + "/combiner/i?img=" + url.substring(host.length()) + "&w=96&h=96" : url;
    }

    static String ymd(long millis) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(millis));
    }

    // ---- followed matches (kept as "league|id|when|home|away" so the service can notify without a fetch)

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("follows", Context.MODE_PRIVATE);
    }

    static List<Match> follows(Context c) {
        List<Match> out = new ArrayList<>();
        for (String s : prefs(c).getStringSet("k", new HashSet<>())) {
            String[] p = s.split("\\|", 7);
            if (p.length < 5 || p[0].contains(".")) continue; // saves from the ESPN version
            Match m = new Match();
            m.league = p[0]; m.id = p[1]; m.when = Long.parseLong(p[2]); m.home = p[3]; m.away = p[4];
            if (p.length == 7) { m.homeLogo = p[5]; m.awayLogo = p[6]; } // older saves have no logos
            m.status = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(m.when));
            out.add(m);
        }
        return out;
    }

    static boolean isFollowed(Context c, String key) {
        for (String s : prefs(c).getStringSet("k", new HashSet<>())) if (s.startsWith(key + "|")) return true;
        return false;
    }

    static void follow(Context c, Match m) {
        unfollow(c, m.key());
        Set<String> set = new HashSet<>(prefs(c).getStringSet("k", new HashSet<>()));
        set.add(m.key() + "|" + m.when + "|" + m.home + "|" + m.away + "|" + m.homeLogo + "|" + m.awayLogo);
        prefs(c).edit().putStringSet("k", set).apply();
    }

    static void unfollow(Context c, String key) {
        Set<String> set = new HashSet<>(prefs(c).getStringSet("k", new HashSet<>()));
        set.removeIf(s -> s.startsWith(key + "|"));
        prefs(c).edit().putStringSet("k", set).apply();
    }

    // ---- favourite teams: their matches are followed automatically

    static Set<String> favourites(Context c) {
        return new HashSet<>(prefs(c).getStringSet("teams", new HashSet<>()));
    }

    /** @return true if the team is now a favourite */
    static boolean toggleFavourite(Context c, String team) {
        Set<String> set = favourites(c);
        boolean added = set.add(team);
        if (!added) set.remove(team);
        prefs(c).edit().putStringSet("teams", set).apply();
        return added;
    }

    /**
     * Follow every upcoming/live match involving a favourite team. Each match is auto-followed only once,
     * so unfollowing one by hand sticks. ponytail: the "auto" set only grows (a few bytes per match), prune if it matters.
     */
    static boolean autoFollow(Context c, List<Match> ms) {
        Set<String> teams = favourites(c);
        if (teams.isEmpty()) return false;
        Set<String> auto = new HashSet<>(prefs(c).getStringSet("auto", new HashSet<>()));
        boolean any = false;
        for (Match m : ms) {
            if (m.state.equals("post") || !(teams.contains(m.home) || teams.contains(m.away))) continue;
            if (auto.add(m.key())) { follow(c, m); any = true; }
        }
        if (any) prefs(c).edit().putStringSet("auto", auto).apply();
        return any;
    }

    /** Run the background poller iff there is something to follow or watch for. */
    static void syncService(Context c) {
        Intent i = new Intent(c, TrackerService.class);
        if (follows(c).isEmpty() && favourites(c).isEmpty()) c.stopService(i);
        else c.startForegroundService(i);
    }

    // ---- notifications

    static void ensureChannels(Context c) {
        NotificationChannel scores = new NotificationChannel(CH_SCORES, "Match scores", NotificationManager.IMPORTANCE_HIGH);
        scores.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // IMPORTANCE_MIN: no sound, no status-bar icon, collapsed at the bottom of the shade. Android insists on some notification for a foreground service.
        NotificationChannel track = new NotificationChannel(CH_TRACK, "Background tracking", NotificationManager.IMPORTANCE_MIN);
        track.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel("tracking"); // old LOW-importance channel
        nm.createNotificationChannels(List.of(scores, track));
    }

    private static PendingIntent openApp(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * One notification per match, updated in place; {@code alert} makes it buzz (goal, kick-off, full time).
     * Shown with a custom score card (crests, names, big score), which is what appears on the lock screen.
     * Blocking when logos aren't cached yet, so call off the main thread.
     */
    static void post(Context c, Match m, boolean alert) {
        ensureChannels(c);
        boolean pre = m.state.equals("pre"), live = m.state.equals("in");
        String score = pre ? "vs" : m.hg + " - " + m.ag;
        String text = pre ? "Kick-off " + new SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(new Date(m.when))
                : live ? "Live " + m.status : m.status;

        RemoteViews card = new RemoteViews(c.getPackageName(), R.layout.notification_match);
        card.setTextViewText(R.id.nHome, m.home);
        card.setTextViewText(R.id.nAway, m.away);
        card.setTextViewText(R.id.nScore, score);
        card.setTextViewText(R.id.nStatus, text);
        Bitmap home = Logos.fetch(m.homeLogo), away = Logos.fetch(m.awayLogo);
        if (home != null) card.setImageViewBitmap(R.id.nHomeLogo, home);
        if (away != null) card.setImageViewBitmap(R.id.nAwayLogo, away);

        Notification n = new Notification.Builder(c, CH_SCORES)
                .setSmallIcon(R.drawable.ic_ball)
                .setColor(c.getColor(R.color.accent))
                .setContentTitle(m.home + " " + score + " " + m.away) // plain version for surfaces that can't show the card
                .setContentText(text)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(card)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(!alert)
                .setOngoing(live)
                .setAutoCancel(!live)
                .setContentIntent(openApp(c))
                .build();
        c.getSystemService(NotificationManager.class).notify(m.key().hashCode(), n);
    }

    static Notification summary(Context c, int count) {
        ensureChannels(c);
        return new Notification.Builder(c, CH_TRACK)
                .setSmallIcon(R.drawable.ic_ball)
                .setContentTitle(count > 0 ? "Following " + count + (count == 1 ? " match" : " matches") : "Watching your teams")
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .setContentIntent(openApp(c))
                .build();
    }

    /** Fake match "Arsenal v Chelsea" (Arsenal = your team). State "post" so the notification stays dismissible. */
    static Match test(int hg, int ag, String status) {
        Match m = new Match();
        m.league = "test"; m.id = "1"; m.home = "Arsenal"; m.away = "Chelsea";
        m.homeLogo = smallLogo("https://a.espncdn.com/i/teamlogos/soccer/500/359.png");
        m.awayLogo = smallLogo("https://a.espncdn.com/i/teamlogos/soccer/500/363.png");
        m.hg = String.valueOf(hg); m.ag = String.valueOf(ag); m.state = "post"; m.status = status;
        return m;
    }
}
