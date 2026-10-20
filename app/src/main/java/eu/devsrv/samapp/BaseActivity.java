package eu.devsrv.samapp;

import android.app.Activity;
import android.os.Bundle;

/** Applies the colour palette chosen in Settings before the screen is drawn. */
public class BaseActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Palette.style(this)); // before any views: the palette supplies every colour
        super.onCreate(savedInstanceState);
    }
}
