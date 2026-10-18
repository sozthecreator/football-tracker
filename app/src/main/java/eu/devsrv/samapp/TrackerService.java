package eu.devsrv.samapp;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Foreground service: polls followed matches and keeps their lock-screen notifications current. */
public class TrackerService extends Service {
    private static final int SUMMARY_ID = 1;
    // football-data.org free tier allows 10 requests/min: one poll = one request per match day, so 12s while a match is live stays under it
    private static final long POLL_MS = 30_000, LIVE_POLL_MS = 12_000, SCAN_MS = 30 * 60_000L, HOUR = 3_600_000L;

    private int shownCount = -1;
    private long lastScan; // 0 = scan for favourite-team matches on the next tick

    private final Map<String, String> last = new HashMap<>(); // match key -> state+score at last notification
    private final Map<String, int[]> goals = new HashMap<>(); // match key -> {home, away} at last poll
    private HandlerThread thread;
    private Handler handler;
    private final Runnable tick = this::poll;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("tracker");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int count = Football.follows(this).size();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(SUMMARY_ID, Football.summary(this, count), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(SUMMARY_ID, Football.summary(this, count));
        }
        EventLog.add(this, "service start: " + count + " followed, favourites " + Football.favourites(this));
        lastScan = 0;
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    /** Look for today's/tomorrow's matches of favourite teams in every league and follow them. */
    private void discover() {
        try {
            boolean any = Football.autoFollow(this, Football.fetchLeagues(Football.allLeagueSlugs(), 0, 1));
            EventLog.add(this, "scan done, new matches followed: " + any);
        } catch (Exception e) {
            EventLog.add(this, "scan failed: " + e); // feed hiccup: try again at the next scan
        }
    }

    /** Cheer when a favourite team scores, boo when its opponent does. */
    private void reactToGoal(Football.Match m) {
        int[] now;
        try {
            now = new int[]{Integer.parseInt(m.hg), Integer.parseInt(m.ag)};
        } catch (NumberFormatException e) {
            EventLog.add(this, "bad score " + m.home + " '" + m.hg + "'-'" + m.ag + "' " + m.away);
            return;
        }
        int[] before = goals.put(m.key(), now);
        if (before == null) { // first sighting: no baseline, so no reaction
            EventLog.add(this, "baseline " + m.home + " " + now[0] + "-" + now[1] + " " + m.away + " (" + m.state + ")");
            return;
        }
        java.util.Set<String> mine = Football.favourites(this);
        boolean homeFav = mine.contains(m.home), awayFav = mine.contains(m.away);
        boolean homeScored = now[0] > before[0], awayScored = now[1] > before[1];
        if (!homeScored && !awayScored) return;
        String what = "GOAL " + m.home + " " + before[0] + "-" + before[1] + " -> " + now[0] + "-" + now[1] + " " + m.away
                + " (homeFav=" + homeFav + ", awayFav=" + awayFav + ")";
        if ((homeFav && homeScored) || (awayFav && awayScored)) {
            EventLog.add(this, what + " -> cheer");
            Sounds.cheer(this);
        } else if ((homeFav && awayScored) || (awayFav && homeScored)) {
            EventLog.add(this, what + " -> boo");
            Sounds.boo(this);
        } else {
            EventLog.add(this, what + " -> no sound (neither team is a favourite)");
        }
    }

    private void poll() {
        long now = System.currentTimeMillis();
        if (!Football.favourites(this).isEmpty() && now - lastScan > SCAN_MS) {
            lastScan = now;
            discover();
        }
        List<Football.Match> follows = Football.follows(this);
        if (follows.isEmpty() && Football.favourites(this).isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        Map<String, List<Football.Match>> cache = new HashMap<>(); // one request per match day per tick
        long t0 = System.currentTimeMillis();
        boolean anyLive = false;
        String error = null;
        int looked = 0;
        for (Football.Match f : follows) {
            try {
                if (f.when > now + 6 * HOUR) continue; // far off: stay quiet until kick-off is near
                looked++;
                Football.Match m = Football.lookup(f, cache);
                if (m != null && m.state.equals("in")) anyLive = true;
                if (m == null) {
                    EventLog.add(this, "not in feed: " + f.home + " v " + f.away + " (" + f.key() + ")");
                    if (f.when < now - 6 * HOUR) Football.unfollow(this, f.key()); // stale, give up
                    continue;
                }
                reactToGoal(m); // before post(): that blocks on logo downloads and may throw
                if (m.state.equals("pre")) continue; // no notification for upcoming games
                String snapshot = m.state + m.hg + "-" + m.ag;
                boolean changed = !snapshot.equals(last.put(f.key(), snapshot));
                if (changed) EventLog.add(this, "update " + m.home + " " + m.hg + "-" + m.ag + " " + m.away + " [" + m.state + " " + m.status + "]");
                Football.post(this, m, changed);
                if (m.state.equals("post")) { // full time: leave the final score, stop following
                    Football.unfollow(this, f.key());
                    last.remove(f.key());
                }
            } catch (Exception e) {
                error = e.toString();
                EventLog.add(this, "poll error " + f.home + " v " + f.away + ": " + e); // keep the last notification, retry next tick
            }
        }
        if (looked > 0) EventLog.poll(this, "service", System.currentTimeMillis() - t0, error);
        int count = Football.follows(this).size();
        if (count != shownCount) { // the foreground notification only changes when the number of followed matches does
            shownCount = count;
            getSystemService(NotificationManager.class).notify(SUMMARY_ID, Football.summary(this, count));
        }
        handler.postDelayed(tick, anyLive ? LIVE_POLL_MS : POLL_MS);
    }

    @Override
    public void onDestroy() {
        thread.quit();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
