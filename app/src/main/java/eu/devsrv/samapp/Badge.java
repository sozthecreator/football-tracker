package eu.devsrv.samapp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * Generic team badge drawn in code (a shield in a colour derived from the team name, with its three-letter code).
 * No club crests are used, so there is nothing to license. A badge is described by the string "badge:TLA:Team name".
 */
final class Badge extends Drawable {
    static final String PREFIX = "badge:";
    static final int SIZE = 96;

    private final String code;
    private final int color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Badge(String spec) {
        String[] p = spec.substring(PREFIX.length()).split(":", 2);
        String name = p.length > 1 ? p[1] : "";
        String tla = p[0].trim();
        code = (tla.isEmpty() ? name.replaceAll("[^A-Za-z]", "") : tla).toUpperCase();
        float hue = ((name.hashCode() * 2654435761L) >>> 8) % 360; // mixed so similar names still get different colours
        color = Color.HSVToColor(new float[]{hue, 0.55f, 0.58f});
    }

    /** Spec for a team: its three-letter code and name. */
    static String spec(String tla, String name) {
        return PREFIX + (tla == null ? "" : tla.replace(":", "")) + ":" + name.replace(":", "");
    }

    static boolean is(String url) { return url != null && url.startsWith(PREFIX); }

    static Badge of(String spec) { return new Badge(spec); }

    /** Renders the badge to a bitmap (notification card). */
    static Bitmap bitmap(String spec) {
        Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Badge d = new Badge(spec);
        d.setBounds(0, 0, SIZE, SIZE);
        d.draw(new Canvas(b));
        return b;
    }

    @Override
    public void draw(Canvas c) {
        float w = getBounds().width(), h = getBounds().height(), s = Math.min(w, h);
        c.save();
        c.translate(getBounds().left + (w - s) / 2, getBounds().top + (h - s) / 2);
        c.scale(s, s); // draw in a unit square
        Path shield = new Path();
        shield.moveTo(0.1f, 0.08f);
        shield.lineTo(0.9f, 0.08f);
        shield.lineTo(0.9f, 0.5f);
        shield.quadTo(0.9f, 0.86f, 0.5f, 0.97f);
        shield.quadTo(0.1f, 0.86f, 0.1f, 0.5f);
        shield.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(shield, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.045f);
        paint.setColor(Color.argb(150, 255, 255, 255));
        c.drawPath(shield, paint);
        c.restore();
        // text in real pixels (a scaled-up tiny font renders badly)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(s * (code.length() > 2 ? 0.3f : 0.38f));
        paint.setStrokeWidth(0);
        float cx = getBounds().left + w / 2, cy = getBounds().top + h * 0.5f;
        c.drawText(code.length() > 3 ? code.substring(0, 3) : code, cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
    }

    @Override public int getIntrinsicWidth() { return SIZE; }
    @Override public int getIntrinsicHeight() { return SIZE; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
