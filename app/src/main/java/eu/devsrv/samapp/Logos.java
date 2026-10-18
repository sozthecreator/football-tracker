package eu.devsrv.samapp;

import android.graphics.Bitmap;
import android.widget.ImageView;

/** Team logos: generated badges only (see Badge), so no club crests are downloaded or shown. */
final class Logos {
    /** For the notification card. Null if the team has no badge. */
    static Bitmap fetch(String spec) {
        return Badge.is(spec) ? Badge.bitmap(spec) : null;
    }

    static void load(ImageView view, String spec) {
        view.setImageDrawable(Badge.is(spec) ? Badge.of(spec) : null);
    }
}
