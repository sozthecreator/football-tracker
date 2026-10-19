package eu.devsrv.samapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Team logos: downloaded on a small pool, kept in memory only. ponytail: no disk cache, add one if startup flicker bothers you. */
final class Logos {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(200); // 96px logos, ~37 KB each
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    /** Blocking: call off the main thread. Null for no/failed logo. */
    static Bitmap fetch(String url) {
        if (url.isEmpty()) return null;
        Bitmap bmp = CACHE.get(url);
        if (bmp != null) return bmp;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            try (InputStream in = c.getInputStream()) {
                bmp = BitmapFactory.decodeStream(in);
            }
            if (bmp != null) CACHE.put(url, bmp);
        } catch (Exception e) {
            // no logo: the caller just goes without
        }
        return bmp;
    }

    static void load(ImageView view, String url) {
        view.setTag(url); // rows get recycled: a late download must not land on a different team's row
        Bitmap cached = url.isEmpty() ? null : CACHE.get(url);
        view.setImageBitmap(cached);
        if (cached != null || url.isEmpty()) return;
        POOL.execute(() -> {
            Bitmap bmp = fetch(url);
            if (bmp != null) view.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(bmp); });
        });
    }
}
