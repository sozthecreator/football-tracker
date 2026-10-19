package eu.devsrv.samapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Account on the score server: sign up / sign in, and keep favourite teams + followed matches in step across devices (last change wins). */
final class Sync {
    private static final ScheduledExecutorService EX = Executors.newSingleThreadScheduledExecutor();
    private static boolean pending;

    private static JSONObject call(String method, String path, JSONObject body, String token) throws IOException {
        if (BuildConfig.WORKER_URL.isEmpty()) throw new IOException("Accounts need the score server");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.WORKER_URL + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("Content-Type", "application/json");
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "{}" : new Scanner(in, "UTF-8").useDelimiter("\\A").next();
            JSONObject o = new JSONObject(text);
            if (code >= 400) throw new IOException(o.optString("error", "Error " + code));
            return o;
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected reply from the server");
        }
    }

    // ---- local data <-> JSON

    private static JSONObject export(Context c) throws Exception {
        SharedPreferences p = Football.prefs(c);
        JSONObject o = new JSONObject();
        o.put("teams", new JSONArray(p.getStringSet("teams", new HashSet<>())));
        o.put("follows", new JSONArray(p.getStringSet("k", new HashSet<>())));
        o.put("auto", new JSONArray(p.getStringSet("auto", new HashSet<>())));
        return o;
    }

    private static Set<String> set(JSONObject o, String k) {
        Set<String> s = new HashSet<>();
        JSONArray a = o.optJSONArray(k);
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    private static void store(Context c, JSONObject o, long modified) {
        Football.prefs(c).edit().putStringSet("teams", set(o, "teams")).putStringSet("k", set(o, "follows"))
                .putStringSet("auto", set(o, "auto")).putLong("modified", modified).apply();
    }

    private static void push(Context c, String token, long modified) throws Exception {
        call("PUT", "/profile", new JSONObject().put("data", export(c).toString()).put("updated", modified), token);
    }

    // ---- account actions (blocking: call off the main thread)

    /** Sign in or create an account, then combine this device's teams with the account's (nothing is lost). */
    static void signIn(Context c, String user, String password, String email, boolean create) throws Exception {
        JSONObject body = new JSONObject().put("username", user).put("password", password);
        if (create) body.put("email", email);
        JSONObject r = call("POST", create ? "/auth/register" : "/auth/login", body, null);
        Settings.setAccount(c, r.getString("username"), r.getString("token"));
        JSONObject server = call("GET", "/profile", null, r.getString("token"));
        JSONObject merged = export(c);
        if (!server.isNull("data")) {
            JSONObject theirs = new JSONObject(server.getString("data"));
            for (String k : new String[]{"teams", "follows", "auto"}) {
                Set<String> both = set(merged, k);
                both.addAll(set(theirs, k));
                merged.put(k, new JSONArray(both));
            }
        }
        long now = System.currentTimeMillis();
        store(c, merged, now);
        push(c, r.getString("token"), now);
    }

    /** @return true if this device's data was replaced by newer data from another device */
    static boolean sync(Context c) throws Exception {
        String token = Settings.token(c);
        if (token.isEmpty()) return false;
        long mine = Football.prefs(c).getLong("modified", 0);
        JSONObject server;
        try {
            server = call("GET", "/profile", null, token);
        } catch (IOException e) {
            if ("Not signed in".equals(e.getMessage())) Settings.clearAccount(c); // token expired or account deleted elsewhere
            throw e;
        }
        long theirs = server.optLong("updated", 0);
        if (server.isNull("data") || mine > theirs) { push(c, token, Math.max(mine, 1)); return false; }
        if (theirs > mine) { store(c, new JSONObject(server.getString("data")), theirs); return true; }
        return false;
    }

    static void signOut(Context c) {
        String token = Settings.token(c);
        Settings.clearAccount(c);
        EX.execute(() -> { try { call("POST", "/auth/logout", null, token); } catch (Exception ignored) { /* token just expires */ } });
    }

    static void deleteAccount(Context c) throws Exception {
        call("DELETE", "/account", null, Settings.token(c));
        Settings.clearAccount(c);
    }

    /** Called after every local change to teams / followed matches. */
    static void pushSoon(Context c) {
        if (!Settings.signedIn(c)) return;
        Context app = c.getApplicationContext();
        synchronized (Sync.class) {
            if (pending) return;
            pending = true;
        }
        EX.schedule(() -> {
            synchronized (Sync.class) { pending = false; }
            try { sync(app); } catch (Exception e) { EventLog.add(app, "sync failed: " + e.getMessage()); }
        }, 3, TimeUnit.SECONDS);
    }
}
