package eu.devsrv.samapp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Crowd reactions from res/raw (cheer.mp3, boo.mp3), each 2s long with a 0.5s fade-out. */
final class Sounds {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final long CLIP_MS = 2000;

    // cheer.mp3 is ~16s: 0-1.5s is silence + build-up, full roar from ~2s, so skip straight to it
    static void cheer(Context c) { play(c, R.raw.cheer, 2000, CLIP_MS); }

    static void boo(Context c) { play(c, R.raw.boo, 0, CLIP_MS); }

    private static void play(Context c, int res, int startAtMs, long maxMs) {
        if (!Settings.sounds(c)) return;
        // The service calls from its worker thread, whose looper is busy/idle unpredictably; MediaPlayer callbacks must run on main like the test button.
        MAIN.post(() -> playOnMain(c, res, startAtMs, maxMs));
    }

    private static void playOnMain(Context c, int res, int startAtMs, long maxMs) {
        String name = res == R.raw.cheer ? "cheer" : "boo";
        AudioManager am = c.getSystemService(AudioManager.class);
        EventLog.add(c, "sound " + name + ": requested, notification volume " + am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                + "/" + am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) + ", ringer mode " + am.getRingerMode());
        MediaPlayer p = MediaPlayer.create(c.getApplicationContext(), res,
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION) // follows notification volume / DND
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (p == null) {
            EventLog.add(c, "sound " + name + ": MediaPlayer.create returned null");
            return;
        }
        p.setOnErrorListener((mp, what, extra) -> {
            EventLog.add(c, "sound " + name + ": player error " + what + "/" + extra);
            return false;
        });
        long[] started = new long[1];
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            long left = maxMs - (SystemClock.uptimeMillis() - started[0]);
            if (left <= 0 || !p.isPlaying()) { // time up, or the clip ended by itself
                p.release();
                EventLog.add(c, "sound " + name + ": finished");
                return;
            }
            float v = Math.min(1f, left / 500f);
            p.setVolume(v, v);
            MAIN.postDelayed(tick[0], 100);
        };
        p.setOnSeekCompleteListener(mp -> {
            started[0] = SystemClock.uptimeMillis();
            mp.start();
            EventLog.add(c, "sound " + name + ": playing");
            MAIN.post(tick[0]);
        });
        p.seekTo(startAtMs, MediaPlayer.SEEK_CLOSEST);
    }
}
