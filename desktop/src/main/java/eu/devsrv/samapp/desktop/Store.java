package eu.devsrv.samapp.desktop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Favourites, followed matches, API key and the event/poll logs, all under ~/.footballtracker. */
final class Store {
    private static final Path DIR = Path.of(System.getProperty("user.home"), ".footballtracker");
    private static final Path STATE = DIR.resolve("state.properties"), EVENTS = DIR.resolve("events.log"), POLLS = DIR.resolve("polls.log");
    private static final Properties P = new Properties();

    static {
        try {
            Files.createDirectories(DIR);
            if (Files.exists(STATE)) try (Reader r = Files.newBufferedReader(STATE)) { P.load(r); }
        } catch (IOException e) {
            // start empty
        }
    }

    private static synchronized void save() {
        try (Writer w = Files.newBufferedWriter(STATE)) { P.store(w, null); } catch (IOException e) { /* best effort */ }
    }

    private static synchronized Set<String> set(String k) {
        String v = P.getProperty(k, "");
        return v.isEmpty() ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(v.split("\n")));
    }

    private static synchronized void put(String k, Set<String> s) {
        P.setProperty(k, String.join("\n", s));
        P.setProperty("modified", String.valueOf(System.currentTimeMillis()));
        save();
        Account.pushSoon(); // teams / followed matches changed: keep the account in step
    }

    // ---- settings and account

    static synchronized String setting(String k, String def) { return P.getProperty("s." + k, def); }

    static synchronized void setSetting(String k, String v) {
        P.setProperty("s." + k, v);
        save();
    }

    static boolean flag(String k, boolean def) { return Boolean.parseBoolean(setting(k, String.valueOf(def))); }

    static synchronized String user() { return P.getProperty("account.user", ""); }

    static synchronized String token() { return P.getProperty("account.token", ""); }

    static synchronized void setAccount(String user, String token) {
        P.setProperty("account.user", user);
        P.setProperty("account.token", token);
        save();
    }

    static synchronized void clearAccount() {
        P.remove("account.user");
        P.remove("account.token");
        save();
    }

    static synchronized long modified() { return Long.parseLong(P.getProperty("modified", "0")); }

    /** The three synced sets, as stored. */
    static Set<String> raw(String k) { return set(k); }

    /** Replace local teams/follows/auto (no change notification: used when syncing from the account). */
    static synchronized void replaceData(Set<String> teams, Set<String> follows, Set<String> auto, long modified) {
        P.setProperty("teams", String.join("\n", teams));
        P.setProperty("follows", String.join("\n", follows));
        P.setProperty("auto", String.join("\n", auto));
        P.setProperty("modified", String.valueOf(modified));
        save();
    }

    // ---- key

    static synchronized String apiKey() {
        String env = System.getenv("FOOTBALL_DATA_KEY");
        return env != null && !env.isBlank() ? env.trim() : P.getProperty("key", "");
    }

    static synchronized void setApiKey(String k) {
        P.setProperty("key", k.trim());
        save();
    }

    // ---- favourites

    static Set<String> favourites() { return set("teams"); }

    static boolean toggleFavourite(String team) {
        Set<String> s = set("teams");
        boolean added = s.add(team);
        if (!added) s.remove(team);
        put("teams", s);
        return added;
    }

    // ---- followed matches: "league|id|when|home|away|homeLogo|awayLogo"

    static List<Feed.Match> follows() {
        List<Feed.Match> out = new ArrayList<>();
        for (String s : set("follows")) {
            String[] p = s.split("\\|", 7);
            if (p.length < 7) continue;
            Feed.Match m = new Feed.Match();
            m.league = p[0]; m.id = p[1]; m.when = Long.parseLong(p[2]); m.home = p[3]; m.away = p[4]; m.homeLogo = p[5]; m.awayLogo = p[6];
            m.status = Feed.clock(m.when);
            out.add(m);
        }
        return out;
    }

    static boolean isFollowed(String key) {
        for (String s : set("follows")) if (s.startsWith(key + "|")) return true;
        return false;
    }

    static void follow(Feed.Match m) {
        unfollow(m.key());
        Set<String> s = set("follows");
        s.add(m.key() + "|" + m.when + "|" + m.home + "|" + m.away + "|" + m.homeLogo + "|" + m.awayLogo);
        put("follows", s);
    }

    static void unfollow(String key) {
        Set<String> s = set("follows");
        s.removeIf(x -> x.startsWith(key + "|"));
        put("follows", s);
    }

    /** Follow every upcoming/live match of a favourite team, once each (so unfollowing by hand sticks). */
    static boolean autoFollow(List<Feed.Match> ms) {
        Set<String> teams = favourites(), auto = set("auto");
        boolean any = false;
        for (Feed.Match m : ms) {
            if (m.state.equals("post") || !(teams.contains(m.home) || teams.contains(m.away))) continue;
            if (auto.add(m.key())) { follow(m); any = true; }
        }
        if (any) put("auto", auto);
        return any;
    }

    // ---- logs

    private static synchronized void append(Path f, String line, long maxBytes) {
        try {
            if (Files.exists(f) && Files.size(f) > maxBytes) { // keep the newest half
                String all = Files.readString(f);
                Files.writeString(f, all.substring(all.indexOf('\n', all.length() / 2) + 1));
            }
            Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // logging must never break the app
        }
    }

    static void log(String msg) {
        append(EVENTS, LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) + "  " + msg, 100_000);
    }

    /** One feed refresh: tab-separated epoch, who ("tracker" or "screen"), ms, ok|error. */
    static void poll(String who, long ms, String error) {
        append(POLLS, System.currentTimeMillis() + "\t" + who + "\t" + ms + "\t" + (error == null ? "ok" : error.replace('\n', ' ')), 20_000);
    }

    private static synchronized String read(Path f) {
        try { return Files.exists(f) ? Files.readString(f) : ""; } catch (IOException e) { return ""; }
    }

    static String readLog() { return read(EVENTS); }

    static String readPolls() { return read(POLLS); }

    static synchronized void clearLog() {
        try { Files.deleteIfExists(EVENTS); Files.deleteIfExists(POLLS); } catch (IOException e) { /* ignore */ }
    }
}
