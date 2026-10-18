package eu.devsrv.samapp;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Plain-text event log in the app's files dir, shared by the UI and the service. Shown by long-pressing Test. */
final class EventLog {
    private static final long MAX_BYTES = 100_000;

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "events.log");
    }

    private static File pollFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "polls.log");
    }

    /** One refresh of the feed: {@code who} = "service" or "app", {@code ms} = how long the request(s) took, {@code error} null if fine. Tab-separated: epoch, who, ms, ok|error. */
    static synchronized void poll(Context c, String who, long ms, String error) {
        try {
            File f = pollFile(c);
            if (f.length() > 20_000) { // keep the newest half
                String all = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                Files.write(f.toPath(), all.substring(all.indexOf('\n', all.length() / 2) + 1).getBytes(StandardCharsets.UTF_8));
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(System.currentTimeMillis() + "\t" + who + "\t" + ms + "\t" + (error == null ? "ok" : error.replace('\n', ' ')) + "\n");
            }
        } catch (Exception e) {
            // logging must never break the app
        }
    }

    static synchronized String readPolls(Context c) {
        try {
            return new String(Files.readAllBytes(pollFile(c).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized void add(Context c, String msg) {
        try {
            File f = file(c);
            if (f.length() > MAX_BYTES) { // keep the newest half
                String all = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                int cut = all.indexOf('\n', all.length() / 2);
                Files.write(f.toPath(), all.substring(cut + 1).getBytes(StandardCharsets.UTF_8));
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + msg + "\n");
            }
        } catch (Exception e) {
            // logging must never break the app
        }
    }

    static synchronized String read(Context c) {
        try {
            return new String(Files.readAllBytes(file(c).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized void clear(Context c) {
        pollFile(c).delete();
        file(c).delete();
    }
}
