package eu.devsrv.samapp;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/** Applies the light/dark choice from Settings (System = follow the phone). */
public class BaseActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        int t = base.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("theme", Settings.SYSTEM);
        if (t != Settings.SYSTEM) {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | (t == Settings.DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            base = base.createConfigurationContext(cfg);
        }
        super.attachBaseContext(base);
    }
}
