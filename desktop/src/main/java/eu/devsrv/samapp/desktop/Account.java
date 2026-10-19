package eu.devsrv.samapp.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Account on the score server: sign up / sign in, and keep favourite teams + followed matches in step across devices (last change wins). */
final class Account {
    private static final ScheduledExecutorService EX = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
    private static boolean pending;
    /** Called on the Swing thread-agnostic worker after a pull replaced local data (the window refreshes itself). */
    static volatile Runnable onPulled = () -> { };

    static boolean signedIn() { return !Store.token().isEmpty(); }

    private static String export() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("teams", new ArrayList<>(Store.raw("teams")));
        o.put("follows", new ArrayList<>(Store.raw("follows")));
        o.put("auto", new ArrayList<>(Store.raw("auto")));
        return Feed.Json.write(o);
    }

    private static Set<String> set(Map<String, Object> o, String k) {
        Set<String> s = new LinkedHashSet<>();
        for (Object x : Feed.Json.list(o.get(k))) s.add(String.valueOf(x));
        return s;
    }

    private static void push(String token, long modified) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", export());
        body.put("updated", modified);
        Feed.send("PUT", "/profile", body, token);
    }

    /** Sign in or create an account, then combine this device's teams with the account's (nothing is lost). */
    static void signIn(String user, String password, String email, boolean create) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", user);
        body.put("password", password);
        if (create) body.put("email", email);
        Map<String, Object> r = Feed.send("POST", create ? "/auth/register" : "/auth/login", body, null);
        String token = Feed.Json.str(r, "token");
        Store.setAccount(Feed.Json.str(r, "username"), token);
        Map<String, Object> server = Feed.send("GET", "/profile", null, token);
        Set<String> teams = Store.raw("teams"), follows = Store.raw("follows"), auto = Store.raw("auto");
        if (server.get("data") instanceof String) {
            Map<String, Object> theirs = Feed.Json.obj(Feed.Json.parse((String) server.get("data")));
            teams.addAll(set(theirs, "teams"));
            follows.addAll(set(theirs, "follows"));
            auto.addAll(set(theirs, "auto"));
        }
        long now = System.currentTimeMillis();
        Store.replaceData(teams, follows, auto, now);
        push(token, now);
    }

    /** @return true if this device's data was replaced by newer data from another device */
    static boolean sync() throws Exception {
        String token = Store.token();
        if (token.isEmpty()) return false;
        long mine = Store.modified();
        Map<String, Object> server;
        try {
            server = Feed.send("GET", "/profile", null, token);
        } catch (java.io.IOException e) {
            if ("Not signed in".equals(e.getMessage())) Store.clearAccount(); // token expired or account deleted elsewhere
            throw e;
        }
        long theirs = server.get("updated") instanceof Double ? ((Double) server.get("updated")).longValue() : 0;
        if (!(server.get("data") instanceof String) || mine > theirs) { push(token, Math.max(mine, 1)); return false; }
        if (theirs > mine) {
            Map<String, Object> o = Feed.Json.obj(Feed.Json.parse((String) server.get("data")));
            Store.replaceData(set(o, "teams"), set(o, "follows"), set(o, "auto"), theirs);
            return true;
        }
        return false;
    }

    static void signOut() {
        String token = Store.token();
        Store.clearAccount();
        EX.execute(() -> { try { Feed.send("POST", "/auth/logout", null, token); } catch (Exception ignored) { /* token just expires */ } });
    }

    static void delete() throws Exception {
        Feed.send("DELETE", "/account", null, Store.token());
        Store.clearAccount();
    }

    /** Called after every local change to teams / followed matches. */
    static void pushSoon() {
        if (!signedIn()) return;
        synchronized (Account.class) {
            if (pending) return;
            pending = true;
        }
        EX.schedule(() -> {
            synchronized (Account.class) { pending = false; }
            try { if (sync()) onPulled.run(); } catch (Exception e) { Store.log("sync failed: " + e.getMessage()); }
        }, 3, TimeUnit.SECONDS);
    }
}
