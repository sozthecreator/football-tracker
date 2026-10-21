package eu.devsrv.samapp;

import android.content.Context;
import android.util.TypedValue;

/** The colour palettes: each is a style in res/values/palettes.xml. Settings picks one. */
final class Palette {
    static final String[] KEYS = {"light", "dark", "midnight", "pitch", "sunset", "white", "black"};
    static final String[] NAMES = {"Light", "Dark", "Midnight", "Pitch", "Sunset", "White", "Black"};
    private static final int[] STYLES = {R.style.Palette_Light, R.style.Palette_Dark, R.style.Palette_Midnight, R.style.Palette_Pitch, R.style.Palette_Sunset, R.style.Palette_White, R.style.Palette_Black};

    private Palette() {}

    /** Position of the saved palette in KEYS (Dark if unknown). */
    static int index(Context c) {
        String key = Settings.palette(c);
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 1;
    }

    static int style(Context c) { return STYLES[index(c)]; }

    /** A colour from the palette the screen is using, e.g. Palette.color(this, R.attr.palAccent). */
    static int color(Context c, int attr) {
        TypedValue v = new TypedValue();
        c.getTheme().resolveAttribute(attr, v, true);
        return v.data;
    }
}
