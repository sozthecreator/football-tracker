package eu.devsrv.samapp.desktop;

import java.awt.Desktop;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Checks GitHub Releases for a newer version, asks the user, then downloads the installer and opens it. */
final class Updates {
    private static final String LATEST = "https://api.github.com/repos/sozthecreator/football-tracker/releases/latest";

    static String version() {
        try (InputStream in = Updates.class.getResourceAsStream("/version.properties")) {
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            String v = p.getProperty("version", "0").trim();
            return v.startsWith("@") ? "0" : v; // not filtered = run from an IDE
        } catch (Exception e) {
            return "0";
        }
    }

    /** Background check; {@code manual} also reports "up to date" and failures. */
    static void check(JFrame parent, boolean manual) {
        Thread t = new Thread(() -> {
            try {
                Map<String, Object> rel = Feed.Json.obj(Feed.Json.parse(Feed.getString(LATEST, null)));
                String tag = Feed.Json.str(rel, "tag_name"), notes = Feed.Json.str(rel, "body").trim();
                if (!newer(tag, version())) {
                    if (manual) SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent, "You're up to date (" + version() + ")."));
                    return;
                }
                String url = pickAsset(Feed.Json.list(rel.get("assets")));
                if (url == null) return;
                int choice = askOnEdt(parent, tag, notes);
                if (choice == JOptionPane.YES_OPTION) download(parent, url);
            } catch (Exception e) {
                Store.log("update check failed: " + e);
                if (manual) SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent, "Couldn't check for updates: " + e.getMessage()));
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static int askOnEdt(JFrame parent, String tag, String notes) throws Exception {
        int[] out = new int[1];
        SwingUtilities.invokeAndWait(() -> out[0] = JOptionPane.showConfirmDialog(parent,
                "Version " + tag + " is available (you have " + version() + ").\n" + (notes.isEmpty() ? "" : "\n" + notes + "\n") + "\nDownload and install it now?",
                "Update available", JOptionPane.YES_NO_OPTION));
        return out[0];
    }

    /** The installer for this OS: .msi on Windows, .deb on Linux, otherwise the zip. */
    private static String pickAsset(List<Object> assets) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), want = os.contains("win") ? ".msi" : os.contains("linux") ? ".deb" : ".zip", zip = null;
        for (Object o : assets) {
            Map<String, Object> a = Feed.Json.obj(o);
            String name = Feed.Json.str(a, "name"), url = Feed.Json.str(a, "browser_download_url");
            if (name.endsWith(want)) return url;
            if (name.endsWith(".zip")) zip = url;
        }
        return zip;
    }

    private static void download(JFrame parent, String url) {
        try {
            String name = url.substring(url.lastIndexOf('/') + 1);
            Path dir = Path.of(System.getProperty("user.home"), "Downloads");
            if (!Files.isDirectory(dir)) dir = Path.of(System.getProperty("java.io.tmpdir"));
            Path file = dir.resolve(name);
            Store.log("downloading update " + url);
            HttpResponse<InputStream> r = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.ALWAYS).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "FootballTracker").build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) { Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING); }
            File f = file.toFile();
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(parent, "Downloaded to " + f + "\nThe installer will open now; Football Tracker will be replaced when it finishes.");
                try { Desktop.getDesktop().open(f); } catch (Exception e) { JOptionPane.showMessageDialog(parent, "Open it yourself: " + f); }
            });
        } catch (Exception e) {
            Store.log("update download failed: " + e);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent, "Download failed: " + e.getMessage()));
        }
    }

    /** "v1.2.0" newer than "1.1.0"? */
    static boolean newer(String tag, String current) {
        String[] x = tag.replaceFirst("^v", "").split("\\."), y = current.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int a = i < x.length ? num(x[i]) : 0, b = i < y.length ? num(y[i]) : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (NumberFormatException e) { return 0; }
    }
}
