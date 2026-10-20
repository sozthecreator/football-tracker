package eu.devsrv.samapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** User settings (Settings screen) and the signed-in account. */
final class Settings {
    static final int SYSTEM = 0, DARK = 1, LIGHT = 2;

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    /** The colour palette key (see Palette). Older builds stored System/Dark/Light; System follows the phone's night mode. */
    static String palette(Context c) {
        String saved = p(c).getString("palette", null);
        if (saved != null) return saved;
        int t = p(c).getInt("theme", SYSTEM);
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return t == LIGHT || (t == SYSTEM && !night) ? "light" : "dark";
    }
    static void setPalette(Context c, String key) { p(c).edit().putString("palette", key).apply(); }

    static boolean sounds(Context c) { return p(c).getBoolean("sounds", true); }
    static void setSounds(Context c, boolean v) { p(c).edit().putBoolean("sounds", v).apply(); }

    static boolean notifications(Context c) { return p(c).getBoolean("notifications", true); }
    static void setNotifications(Context c, boolean v) { p(c).edit().putBoolean("notifications", v).apply(); }

    /** Battery saver: refresh half as often. */
    static boolean saver(Context c) { return p(c).getBoolean("saver", false); }
    static void setSaver(Context c, boolean v) { p(c).edit().putBoolean("saver", v).apply(); }

    /** -1 = automatic (My teams if you have any, else Leagues), otherwise a tab index. */
    static int startTab(Context c) { return p(c).getInt("startTab", -1); }
    static void setStartTab(Context c, int v) { p(c).edit().putInt("startTab", v).apply(); }

    // ---- account

    private static SharedPreferences a(Context c) { return c.getSharedPreferences("account", Context.MODE_PRIVATE); }

    static String user(Context c) { return a(c).getString("user", ""); }
    static String token(Context c) { return a(c).getString("token", ""); }
    static boolean signedIn(Context c) { return !token(c).isEmpty(); }
    static void setAccount(Context c, String user, String token) { a(c).edit().putString("user", user).putString("token", token).apply(); }
    static void clearAccount(Context c) { a(c).edit().clear().apply(); }
}
