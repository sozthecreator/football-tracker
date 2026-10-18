package eu.devsrv.samapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

/** Checks GitHub Releases for a newer version, asks the user, then downloads and installs the APK. */
final class Updates {
    private static final String LATEST = "https://api.github.com/repos/kiaroa/football-tracker/releases/latest";
    private static final long EVERY_MS = 15 * 60_000L;
    private static final String ACTION = "eu.devsrv.samapp.INSTALL_STATUS";

    /** {@code manual}: ignore the 6-hour throttle and say "up to date" when there's nothing new. */
    static void check(Activity a, boolean manual) {
        SharedPreferences p = a.getSharedPreferences("updates", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - p.getLong("last", 0) < EVERY_MS) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LATEST).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                String json;
                try (InputStream in = c.getInputStream()) { json = new Scanner(in, "UTF-8").useDelimiter("\\A").next(); }
                p.edit().putLong("last", now).apply();
                JSONObject rel = new JSONObject(json);
                String tag = rel.getString("tag_name");
                String url = null;
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    if (assets.getJSONObject(i).getString("name").endsWith(".apk")) url = assets.getJSONObject(i).getString("browser_download_url");
                }
                if (url == null || !newer(tag, BuildConfig.VERSION_NAME)) {
                    if (manual) a.runOnUiThread(() -> Toast.makeText(a, "You're up to date (" + BuildConfig.VERSION_NAME + ")", Toast.LENGTH_SHORT).show());
                    return;
                }
                String apk = url, notes = rel.optString("body", "").trim();
                if (!manual && tag.equals(p.getString("skipped", ""))) return;
                a.runOnUiThread(() -> new AlertDialog.Builder(a)
                        .setTitle("Update available: " + tag)
                        .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "You have " + BuildConfig.VERSION_NAME + ".")
                        .setPositiveButton("Update now", (d, w) -> install(a, apk))
                        .setNegativeButton("Later", (d, w) -> p.edit().putString("skipped", tag).apply())
                        .show());
            } catch (Exception e) {
                EventLog.add(a, "update check failed: " + e);
                if (manual) a.runOnUiThread(() -> Toast.makeText(a, "Couldn't check for updates", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** "v1.2.0" newer than "1.1.0"? Compares dot-separated numbers. */
    static boolean newer(String tag, String current) {
        String[] x = tag.replaceFirst("^v", "").split("\\."), y = current.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int a = i < x.length ? parse(x[i]) : 0, b = i < y.length ? parse(y[i]) : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (NumberFormatException e) { return 0; }
    }

    private static void install(Activity a, String apkUrl) {
        if (!a.getPackageManager().canRequestPackageInstalls()) { // one-off "install unknown apps" switch for this app
            a.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().remove("last").apply(); // so coming back from Settings asks again
            Toast.makeText(a, "Switch on 'Allow from this source', then come back and tap Update", Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        Toast.makeText(a, "Downloading update…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                int id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection(); // follows GitHub's redirect to the file host
                    c.setConnectTimeout(15_000);
                    c.setReadTimeout(30_000);
                    try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("update", 0, -1)) {
                        byte[] buf = new byte[65536];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    BroadcastReceiver r = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ctx, Intent i) {
                            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                                ctx.startActivity(i.<Intent>getParcelableExtra(Intent.EXTRA_INTENT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                                Toast.makeText(ctx, "Update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Toast.LENGTH_LONG).show();
                                EventLog.add(ctx, "update install failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                            }
                        }
                    };
                    a.registerReceiver(r, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
                    s.commit(PendingIntent.getBroadcast(a, id, new Intent(ACTION).setPackage(a.getPackageName()),
                            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
                }
            } catch (Exception e) {
                EventLog.add(a, "update download failed: " + e);
                a.runOnUiThread(() -> Toast.makeText(a, "Update download failed", Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
