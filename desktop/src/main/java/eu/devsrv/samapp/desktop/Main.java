package eu.devsrv.samapp.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import eu.devsrv.samapp.desktop.Ui.Card;
import eu.devsrv.samapp.desktop.Ui.Grid;
import eu.devsrv.samapp.desktop.Ui.Pill;

/** Desktop (Linux/Windows) version of the app: same tabs, goal sounds, notifications and debug menu. */
public final class Main {
    private static final int LEAGUES = 0, MINE = 1, FOLLOWING = 2, TABLE = 3;
    private static final String[] TITLES = {"Leagues", "My teams", "Following", "Table"};
    private static final String[] EMPTY = {"No matches this week", "No matches for your teams this week",
            "Not following any games yet.\nClick Follow on a match.", "No table for this league"};
    // football-data.org free tier: 10 requests/min. Tracker = 1 request per match day per tick, screen = 1 per refresh.
    private static final long SCREEN_MS = 20_000, POLL_MS = 30_000, LIVE_POLL_MS = 12_000, SCAN_MS = 30 * 60_000L, HOUR = 3_600_000L;

    private final ScheduledExecutorService ex = Executors.newScheduledThreadPool(3, r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
    private final JFrame frame = new JFrame("Football Tracker");
    private final JLabel title = Ui.label("", 26, true, Ui.TEXT), status = Ui.label(" ", 12, false, Ui.MUTED);
    private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
    private final Grid list = new Grid(440, 560, 3);
    private final JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final JLabel[] nav = new JLabel[4];

    private int tab = LEAGUES;
    private String league = "PL";
    private volatile List<Feed.Match> matches = new ArrayList<>();
    private volatile List<Feed.Row> table = new ArrayList<>();
    private volatile boolean mineLoaded, loaded;
    private final AtomicBoolean busy = new AtomicBoolean(), again = new AtomicBoolean();

    // tracker state (what the Android service keeps)
    private final Map<String, int[]> goals = new HashMap<>();
    private final Map<String, String> last = new HashMap<>();
    private long lastScan;
    private TrayIcon tray;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) {
            selfTest();
            return;
        }
        if (Store.apiKey().isEmpty() && !Feed.hasProxy()) { // only source builds without a score server get here
            String k = JOptionPane.showInputDialog(null, "No score server is configured in this build.\nPaste a football-data.org API key to run it directly:", "Football Tracker", JOptionPane.QUESTION_MESSAGE);
            if (k == null || k.isBlank()) System.exit(0);
            Store.setApiKey(k);
        }
        Ui.applyTheme(!"light".equals(Store.setting("theme", "dark")));
        SwingUtilities.invokeLater(() -> new Main().start());
    }

    private static boolean updateChecked;

    /** Rebuild the whole window (theme change): stop this instance's timers and open a fresh one. */
    void restart() {
        ex.shutdownNow();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        frame.dispose();
        SwingUtilities.invokeLater(() -> new Main().start());
    }

    /** Another device changed the teams: redraw. */
    void refreshAfterSync() {
        SwingUtilities.invokeLater(() -> { buildChips(); reload(); });
    }

    private static void selfTest() throws Exception {
        long now = System.currentTimeMillis();
        List<Feed.Match> ms = Feed.fetchLeagues(List.of("PL"), 1, 6);
        System.out.println("PL matches: " + ms.size() + (ms.isEmpty() ? "" : " first: " + ms.get(0).home + " v " + ms.get(0).away + " [" + ms.get(0).state + " " + ms.get(0).hg + "-" + ms.get(0).ag + " " + ms.get(0).status + "]"));
        List<Feed.Row> t = Feed.standings("PL");
        System.out.println("PL table rows: " + t.size() + (t.isEmpty() ? "" : " leader: " + t.get(0).team + " " + t.get(0).pts));
        List<Feed.News> n = Feed.news("Arsenal");
        System.out.println("news items: " + n.size() + (n.isEmpty() ? "" : " first: " + n.get(0).title + " | " + n.get(0).source + " | " + n.get(0).ago));
        System.out.println("url sample: " + Feed.url("PL", now, now + Feed.DAY));
        Sounds.cheer();
        Thread.sleep(2500);
    }

    // ---- window

    private void start() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel page = new JPanel(new BorderLayout()); // content stays at a readable width when the window is maximised
        page.setBackground(Ui.BG);
        frame.setContentPane(new Ui.Centered(page, 1500));

        Pill test = new Pill("Test", Ui.ACCENT, null, null, Color.WHITE, 13);
        test.setIcon(new Ui.Glyph("flask", 15, Color.WHITE));
        test.setIconTextGap(6);
        test.setBorder(javax.swing.BorderFactory.createEmptyBorder(9, 16, 9, 18));
        test.setToolTipText("Click: fake goals (sound + notification). Hold or right-click: debug menu (Ctrl+D)");
        test.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        Timer hold = new Timer(700, e -> showDebug());
        hold.setRepeats(false);
        test.addMouseListener(new MouseAdapter() {
            private boolean held;

            @Override public void mousePressed(MouseEvent e) {
                held = false;
                if (SwingUtilities.isRightMouseButton(e)) { held = true; showDebug(); return; }
                hold.restart();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (hold.isRunning()) { hold.stop(); if (!held && SwingUtilities.isLeftMouseButton(e)) runTest(); }
            }
        });
        frame.getRootPane().registerKeyboardAction(e -> showDebug(), javax.swing.KeyStroke.getKeyStroke("ctrl D"), JPanel.WHEN_IN_FOCUSED_WINDOW);

        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(Ui.BG);
        head.setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        titles.add(title);
        titles.add(status);
        head.add(titles, BorderLayout.CENTER);
        JPanel testBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        testBox.setOpaque(false);
        // round icon-only settings button: same height as the pills beside it
        Pill gear = new Pill("", Ui.SURFACE, null, Ui.STROKE, Ui.TEXT, 13);
        gear.setIcon(new Ui.Glyph("gear", 18, Ui.ACCENT));
        gear.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        gear.setPreferredSize(new Dimension(36, 36));
        gear.setToolTipText("Settings");
        gear.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        Ui.click(gear, () -> SettingsDialog.show(this, frame));
        if (!Feed.supportUrl().isEmpty()) { // only shown once a support page is configured
            Pill support = new Pill("Support", Ui.SURFACE, null, Ui.STROKE, Ui.TEXT, 13);
            support.setIcon(new Ui.Glyph("heart", 15, Ui.LIVE));
            support.setIconTextGap(6);
            support.setBorder(javax.swing.BorderFactory.createEmptyBorder(9, 14, 9, 16));
            Ui.click(support, () -> browse(Feed.supportUrl()));
            testBox.add(support);
            testBox.add(javax.swing.Box.createHorizontalStrut(8));
        }
        testBox.add(gear);
        testBox.add(javax.swing.Box.createHorizontalStrut(8));
        testBox.add(test);
        head.add(testBox, BorderLayout.EAST);

        chips.setBackground(Ui.BG);
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Ui.BG);
        top.add(head, BorderLayout.NORTH);
        top.add(chips, BorderLayout.CENTER);
        page.add(top, BorderLayout.NORTH);

        Ui.styleScroll(scroll);
        scroll.getViewport().setBackground(Ui.BG);
        page.add(scroll, BorderLayout.CENTER);

        JPanel bar = new JPanel(new GridLayout(1, 4));
        bar.setBackground(Ui.BG);
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.STROKE));
        for (int i = 0; i < 4; i++) {
            int t = i;
            nav[i] = Ui.label(TITLES[i], 12, true, Ui.MUTED);
            nav[i].setIcon(new Ui.Glyph(new String[]{"ball", "star", "bell", "trophy"}[i], 24));
            nav[i].setVerticalTextPosition(JLabel.BOTTOM);
            nav[i].setHorizontalTextPosition(JLabel.CENTER);
            nav[i].setIconTextGap(4);
            nav[i].setHorizontalAlignment(JLabel.CENTER);
            nav[i].setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
            Ui.click(nav[i], () -> selectTab(t));
            bar.add(nav[i]);
        }
        page.add(bar, BorderLayout.SOUTH);

        try {
            frame.setIconImage(javax.imageio.ImageIO.read(Main.class.getResource("/icon.png")));
        } catch (Exception ignored) { /* default icon */ }
        frame.setSize(1040, 860);
        frame.setMinimumSize(new Dimension(420, 500));
        frame.setLocationByPlatform(true);
        setupTray();
        int startTab = Integer.parseInt(Store.setting("startTab", "-1"));
        selectTab(startTab >= 0 ? startTab : Store.favourites().isEmpty() ? LEAGUES : MINE);
        frame.setVisible(true);

        String shot = System.getProperty("shot"); // dev aid: -Dshot=/tmp/x.png [-Dtab=3] paints the window to a PNG and exits
        if (shot != null) {
            if (System.getProperty("tab") != null) selectTab(Integer.parseInt(System.getProperty("tab")));
            if (System.getProperty("w") != null) frame.setSize(Integer.parseInt(System.getProperty("w")), Integer.getInteger("h", 900));
            new Timer(9000, e -> {
                try {
                    BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                    frame.paint(img.getGraphics());
                    javax.imageio.ImageIO.write(img, "png", new java.io.File(shot));
                } catch (Exception ex2) {
                    ex2.printStackTrace();
                }
                System.exit(0);
            }).start();
        }
        Account.onPulled = this::refreshAfterSync;
        if (Account.signedIn()) ex.execute(() -> { try { if (Account.sync()) refreshAfterSync(); } catch (Exception e) { Store.log("sync failed: " + e.getMessage()); } });
        ex.scheduleWithFixedDelay(this::refresh, 0, SCREEN_MS * saver(), TimeUnit.MILLISECONDS);
        ex.schedule(this::trackTick, 2, TimeUnit.SECONDS);
        if (System.getProperty("shot") == null && !updateChecked) { updateChecked = true; Updates.check(frame, false); }
    }

    private void selectTab(int t) {
        tab = t;
        title.setText(TITLES[t]);
        for (int i = 0; i < nav.length; i++) nav[i].setForeground(i == t ? Ui.ACCENT : Ui.MUTED);
        buildChips();
        reload();
    }

    private void reload() {
        matches = new ArrayList<>();
        table = new ArrayList<>();
        mineLoaded = false;
        loaded = false;
        status.setText("Loading…");
        render();
        ex.execute(this::refresh);
    }

    private void buildChips() {
        chips.removeAll();
        if (tab == LEAGUES || tab == TABLE) {
            for (String[] l : Feed.LEAGUES) {
                Pill p = l[0].equals(league) ? new Pill(l[1], Ui.ACCENT, null, null, Color.WHITE, 12) : new Pill(l[1], null, null, Ui.ACCENT, Ui.ACCENT, 12);
                Ui.click(p, () -> { league = l[0]; buildChips(); reload(); });
                chips.add(p);
            }
        } else if (tab == MINE) {
            List<String> teams = new ArrayList<>(Store.favourites());
            Collections.sort(teams);
            for (String team : teams) {
                Pill p = new Pill("★ " + team + "  ✕", Ui.ACCENT, null, null, Color.WHITE, 12);
                Ui.click(p, () -> toggleTeam(team));
                chips.add(p);
            }
        }
        chips.setVisible(chips.getComponentCount() > 0);
        chips.revalidate();
        chips.repaint();
    }

    // ---- data

    private void refresh() {
        if (!busy.compareAndSet(false, true)) { again.set(true); return; } // run once more as soon as the current one ends
        int t = tab;
        String lg = league;
        long t0 = System.currentTimeMillis();
        try {
            if (t == TABLE) {
                List<Feed.Row> rows = Feed.standings(lg);
                Store.poll("screen", System.currentTimeMillis() - t0, null);
                SwingUtilities.invokeLater(() -> {
                    if (t != tab || !lg.equals(league)) return;
                    table = rows;
                    loaded = true;
                    render();
                    status.setText("Updated " + clock());
                });
                return;
            }
            List<Feed.Match> ms = t == MINE ? fetchMine(matches, !mineLoaded) : t == FOLLOWING ? fetchFollowed() : Feed.fetchLeagues(List.of(lg), 1, 6);
            Store.poll("screen", System.currentTimeMillis() - t0, null);
            SwingUtilities.invokeLater(() -> {
                if (t != tab || !lg.equals(league)) return; // switched view mid-request
                matches = ms;
                mineLoaded = true;
                loaded = true;
                Store.autoFollow(ms);
                render();
                status.setText("Updated " + clock() + " · click a team name to ★ it");
            });
        } catch (Exception e) {
            Store.log("refresh failed (tab " + t + "): " + e);
            Store.poll("screen", System.currentTimeMillis() - t0, e.toString());
            SwingUtilities.invokeLater(() -> { if (t == tab) status.setText("Offline - retrying"); });
        } finally {
            busy.set(false);
            if (again.getAndSet(false)) ex.execute(this::refresh);
        }
    }

    private static String clock() {
        return DateTimeFormatter.ofPattern("HH:mm:ss").format(ZonedDateTime.now());
    }

    private List<Feed.Match> fetchMine(List<Feed.Match> prev, boolean full) throws Exception {
        Set<String> teams = Store.favourites();
        if (teams.isEmpty()) return new ArrayList<>();
        List<String> leagues = Feed.allLeagueCodes();
        if (!full) {
            leagues = new ArrayList<>();
            for (Feed.Match m : prev) if (!leagues.contains(m.league)) leagues.add(m.league);
        }
        Map<String, Feed.Match> out = new LinkedHashMap<>();
        for (Feed.Match m : prev) if (teams.contains(m.home) || teams.contains(m.away)) out.put(m.key(), m);
        if (!leagues.isEmpty()) {
            for (Feed.Match m : Feed.fetchLeagues(leagues, full ? 1 : 0, full ? 6 : 0)) {
                if (teams.contains(m.home) || teams.contains(m.away)) out.put(m.key(), m);
            }
        }
        return new ArrayList<>(out.values());
    }

    private List<Feed.Match> fetchFollowed() throws Exception {
        Map<String, List<Feed.Match>> cache = new HashMap<>();
        List<Feed.Match> out = new ArrayList<>(), saved = Store.follows();
        int failed = 0;
        for (Feed.Match f : saved) {
            Feed.Match m = null;
            try { m = Feed.lookup(f, cache); } catch (Exception e) { failed++; }
            out.add(m != null ? m : f);
        }
        if (!saved.isEmpty() && failed == saved.size()) throw new java.io.IOException("all lookups failed");
        return out;
    }

    // ---- tracker (the Android TrackerService): goal sounds + notifications for followed matches

    private void trackTick() {
        long delay = POLL_MS;
        try {
            delay = track();
        } catch (Exception e) {
            Store.log("tracker error: " + e);
        } finally {
            if (!ex.isShutdown()) ex.schedule(this::trackTick, delay * saver(), TimeUnit.MILLISECONDS);
        }
    }

    private long track() {
        long now = System.currentTimeMillis();
        if (!Store.favourites().isEmpty() && now - lastScan > SCAN_MS) {
            lastScan = now;
            try {
                Store.log("scan done, new matches followed: " + Store.autoFollow(Feed.fetchLeagues(Feed.allLeagueCodes(), 0, 1)));
            } catch (Exception e) {
                Store.log("scan failed: " + e);
            }
        }
        Map<String, List<Feed.Match>> cache = new HashMap<>();
        boolean anyLive = false;
        String error = null;
        int looked = 0;
        long t0 = System.currentTimeMillis();
        for (Feed.Match f : Store.follows()) {
            try {
                if (f.when > now + 6 * HOUR) continue; // far off: stay quiet until kick-off is near
                looked++;
                Feed.Match m = Feed.lookup(f, cache);
                if (m != null && m.state.equals("in")) anyLive = true;
                if (m == null) {
                    Store.log("not in feed: " + f.home + " v " + f.away + " (" + f.key() + ")");
                    if (f.when < now - 6 * HOUR) Store.unfollow(f.key());
                    continue;
                }
                reactToGoal(m);
                if (m.state.equals("pre")) continue; // no notification for upcoming games
                String snap = m.state + m.hg + "-" + m.ag;
                if (!snap.equals(last.put(f.key(), snap))) {
                    Store.log("update " + m.home + " " + m.hg + "-" + m.ag + " " + m.away + " [" + m.state + " " + m.status + "]");
                    notifyUser(m.home + " " + m.hg + " - " + m.ag + " " + m.away, m.state.equals("in") ? "Live " + m.status : m.status);
                }
                if (m.state.equals("post")) { Store.unfollow(f.key()); last.remove(f.key()); }
            } catch (Exception e) {
                error = e.toString();
                Store.log("poll error " + f.home + " v " + f.away + ": " + e);
            }
        }
        if (looked > 0) Store.poll("tracker", System.currentTimeMillis() - t0, error);
        return anyLive ? LIVE_POLL_MS : POLL_MS;
    }

    /** Cheer when a favourite team scores, boo when its opponent does. */
    private void reactToGoal(Feed.Match m) {
        int[] now;
        try { now = new int[]{Integer.parseInt(m.hg), Integer.parseInt(m.ag)}; } catch (NumberFormatException e) { return; }
        int[] before = goals.put(m.key(), now);
        if (before == null) { Store.log("baseline " + m.home + " " + now[0] + "-" + now[1] + " " + m.away + " (" + m.state + ")"); return; }
        Set<String> mine = Store.favourites();
        boolean homeFav = mine.contains(m.home), awayFav = mine.contains(m.away), homeScored = now[0] > before[0], awayScored = now[1] > before[1];
        if (!homeScored && !awayScored) return;
        String what = "GOAL " + m.home + " " + before[0] + "-" + before[1] + " -> " + now[0] + "-" + now[1] + " " + m.away + " (homeFav=" + homeFav + ", awayFav=" + awayFav + ")";
        if ((homeFav && homeScored) || (awayFav && awayScored)) { Store.log(what + " -> cheer"); Sounds.cheer(); }
        else if ((homeFav && awayScored) || (awayFav && homeScored)) { Store.log(what + " -> boo"); Sounds.boo(); }
        else Store.log(what + " -> no sound (neither team is a favourite)");
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            Ui.antialias(g);
            g.setColor(Ui.ACCENT);
            g.fillOval(2, 2, 28, 28);
            g.dispose();
            tray = new TrayIcon(img, "Football Tracker");
            tray.setImageAutoSize(true);
            SystemTray.getSystemTray().add(tray);
        } catch (Exception e) {
            tray = null;
        }
    }

    private static int saver() { return Store.flag("saver", false) ? 2 : 1; }

    private void notifyUser(String head, String body) {
        Store.log("notify: " + head + " | " + body);
        if (!Store.flag("notifications", true)) return;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            try { new ProcessBuilder("notify-send", "-a", "Football Tracker", head, body).start(); return; } catch (Exception ignored) { /* fall through to tray */ }
        }
        if (tray != null) tray.displayMessage(head, body, TrayIcon.MessageType.INFO);
    }

    private void runTest() {
        Store.log("test button pressed");
        status.setText("Test: your team scores in 5s, theirs 7s later");
        ex.schedule(() -> { notifyUser("Arsenal 1 - 0 Chelsea", "Test: Arsenal score!"); Sounds.cheer(); }, 5, TimeUnit.SECONDS);
        ex.schedule(() -> { notifyUser("Arsenal 1 - 1 Chelsea", "Test: Chelsea equalise"); Sounds.boo(); }, 12, TimeUnit.SECONDS);
    }

    // ---- rendering

    private static int rank(Feed.Match m, String today) {
        if (m.state.equals("in")) return 0;
        return Feed.ymd(m.when).compareTo(today) < 0 ? 2 : 1;
    }

    private static String dayLabel(long when) {
        long now = System.currentTimeMillis();
        String d = Feed.ymd(when);
        if (d.equals(Feed.ymd(now))) return "Today";
        if (d.equals(Feed.ymd(now + Feed.DAY))) return "Tomorrow";
        if (d.equals(Feed.ymd(now - Feed.DAY))) return "Yesterday";
        return DateTimeFormatter.ofPattern("EEEE, d MMM").format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(when), ZoneId.systemDefault()));
    }

    private void render() {
        int scrollPos = scroll.getVerticalScrollBar().getValue();
        if (tab == TABLE) list.setLimits(600, 760, 1); else list.setLimits(440, 560, 3);
        list.removeAll();
        int n = 0;
        if (tab == TABLE) {
            if (!table.isEmpty()) list.add(tableHeader());
            for (Feed.Row r : table) list.add(tableRow(r));
            n = table.size();
            List<Feed.Zone> zones = Feed.zones(league);
            if (n > 0 && !zones.isEmpty()) { // legend: what each coloured band means
                JLabel note = Ui.label("What the colours mean (usual rules; cup winners can change the European places)", 12, false, Ui.MUTED);
                note.setBorder(BorderFactory.createEmptyBorder(18, 26, 6, 14));
                list.add(note);
                for (Feed.Zone z : zones) {
                    JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 3));
                    line.setBackground(Ui.BG);
                    line.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
                    JLabel dot = new JLabel("●");
                    dot.setForeground(new Color(z.rgb));
                    line.add(dot);
                    line.add(Ui.label((z.from == z.to ? "" + z.from : z.from + "–" + z.to) + "   " + z.label, 13, false, Ui.TEXT));
                    list.add(line);
                }
            }
        } else {
            List<Feed.Match> sorted = new ArrayList<>(matches);
            String today = Feed.ymd(System.currentTimeMillis());
            sorted.sort((a, b) -> {
                int ra = rank(a, today), rb = rank(b, today);
                if (ra != rb) return Integer.compare(ra, rb);
                return ra == 2 ? Long.compare(b.when, a.when) : Long.compare(a.when, b.when);
            });
            for (Feed.Match m : sorted) list.add(card(m));
            n = sorted.size();
        }
        if (n == 0) {
            JLabel e = Ui.label("<html><div style='text-align:center'>" + (!loaded ? "Loading…" : tab == MINE && Store.favourites().isEmpty()
                    ? "No teams yet.<br>Open Leagues and click a team name to ★ it." : EMPTY[tab].replace("\n", "<br>")) + "</div></html>", 15, false, Ui.MUTED);
            e.setHorizontalAlignment(JLabel.CENTER);
            e.setBorder(BorderFactory.createEmptyBorder(80, 0, 0, 0));
            e.putClientProperty("full", true);
            list.add(e);
        }
        list.revalidate();
        list.repaint();
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(scrollPos));
    }

    private JPanel card(Feed.Match m) {
        boolean live = m.state.equals("in"), pre = m.state.equals("pre");
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Ui.BG);
        outer.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
        Card card = new Card(live);
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel mid = new JPanel();
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        mid.setOpaque(false);
        Pill date = new Pill(dayLabel(m.when), Ui.PILL_A, Ui.PILL_B, null, Ui.TEXT, 12);
        date.setAlignmentX(Component.CENTER_ALIGNMENT);
        mid.add(date);
        if (tab != LEAGUES) {
            JLabel lg = Ui.label(Feed.leagueName(m.league), 11, false, Ui.MUTED);
            lg.setAlignmentX(Component.CENTER_ALIGNMENT);
            mid.add(lg);
        }
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(mid, BorderLayout.CENTER);
        if (!m.state.equals("post")) {
            boolean followed = Store.isFollowed(m.key());
            Pill follow = followed ? new Pill("Following ✓", Ui.ACCENT, null, null, Color.WHITE, 11) : new Pill("Follow", null, null, Ui.ACCENT, Ui.ACCENT, 11);
            Ui.click(follow, () -> { if (followed) Store.unfollow(m.key()); else Store.follow(m); render(); });
            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            east.setOpaque(false);
            east.add(follow);
            top.add(east, BorderLayout.EAST);
        }
        card.add(top, BorderLayout.NORTH);

        JPanel body = new JPanel(new GridLayout(1, 3));
        body.setOpaque(false);
        body.add(teamCol(m.home, m.homeLogo, "At Home"));
        JPanel centre = new JPanel();
        centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
        centre.setOpaque(false);
        centre.add(new Ui.Hex(pre ? "VS" : m.hg + " : " + m.ag));
        centre.add(javax.swing.Box.createVerticalStrut(8));
        Pill st = live ? new Pill(m.status.equals("Live") ? "LIVE" : "LIVE " + m.status, Ui.LIVE, null, null, new Color(0x1A1000), 11)
                : new Pill(m.status, null, null, null, Ui.MUTED, 11);
        st.setAlignmentX(Component.CENTER_ALIGNMENT);
        centre.add(st);
        body.add(centre);
        body.add(teamCol(m.away, m.awayLogo, "Away"));
        card.add(body, BorderLayout.CENTER);

        Ui.clickable(card, () -> openMatch(m));
        outer.add(card);
        return outer;
    }

    private JPanel teamCol(String name, String logo, String sub) {
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setOpaque(false);
        JLabel crest = new JLabel();
        crest.setAlignmentX(Component.CENTER_ALIGNMENT);
        Ui.crest(crest, logo, 52);
        boolean fav = Store.favourites().contains(name);
        JLabel n = Ui.label("<html><div style='text-align:center;width:110px'>" + (fav ? "★ " : "") + name.toUpperCase(Locale.ROOT) + "</div></html>", 12, true, fav ? Ui.ACCENT : Ui.TEXT);
        n.setAlignmentX(Component.CENTER_ALIGNMENT);
        n.setToolTipText("Click to " + (fav ? "un-star" : "star") + " " + name);
        Ui.click(n, () -> toggleTeam(name));
        Ui.click(crest, () -> toggleTeam(name)); // the crest and the empty space around it star the team too
        Ui.click(c, () -> toggleTeam(name));
        JLabel s = Ui.label(sub, 10, false, Ui.MUTED);
        s.setAlignmentX(Component.CENTER_ALIGNMENT);
        c.add(crest);
        c.add(javax.swing.Box.createVerticalStrut(6));
        c.add(n);
        c.add(s);
        return c;
    }

    private static JLabel num(String text, int size, boolean bold, Color c, int width) {
        JLabel l = Ui.label(text, size, bold, c);
        l.setHorizontalAlignment(JLabel.RIGHT);
        l.setPreferredSize(new Dimension(width, l.getPreferredSize().height));
        return l;
    }

    private JPanel tableHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Ui.BG);
        p.setBorder(BorderFactory.createEmptyBorder(8, 26, 4, 22));
        p.add(Ui.label("#   TEAM", 11, false, Ui.MUTED), BorderLayout.WEST);
        JPanel cols = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        cols.setOpaque(false);
        cols.add(num("P", 11, false, Ui.MUTED, 34));
        cols.add(num("GF/GA", 11, false, Ui.MUTED, 64));
        cols.add(num("PTS", 11, false, Ui.MUTED, 40));
        p.add(cols, BorderLayout.EAST);
        return p;
    }

    private JPanel tableRow(Feed.Row r) {
        boolean fav = Store.favourites().contains(r.team);
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Ui.BG);
        outer.setBorder(BorderFactory.createEmptyBorder(3, 14, 3, 14));
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setBackground(Ui.ROW);
        Feed.Zone zone = Feed.zoneFor(Feed.zones(league), r.pos);
        row.setBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, zone != null ? new Color(zone.rgb) : Ui.ROW));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        left.setOpaque(false);
        JLabel pos = Ui.label(String.format("%02d", r.pos), 13, false, Ui.MUTED);
        JLabel crest = new JLabel();
        Ui.crest(crest, r.crest, 24);
        left.add(pos);
        left.add(crest);
        JPanel names = new JPanel();
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        names.setOpaque(false);
        names.add(Ui.label((fav ? "★ " : "") + r.team, 14, false, fav ? Ui.ACCENT : Ui.TEXT));
        if (zone != null) names.add(Ui.label(zone.label, 10, true, new Color(zone.rgb)));
        left.add(names);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        right.setOpaque(false);
        right.add(num(String.format("%02d", r.played), 13, false, Ui.MUTED, 34));
        right.add(num(r.gf + "/" + r.ga, 13, false, Ui.MUTED, 64));
        right.add(num(String.format("%02d", r.pts), 14, true, Ui.TEXT, 40));
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        row.setToolTipText("Click for news about " + r.team + ". Right-click to ★ it.");
        Ui.clickable(row, () -> showNews(r.team));
        row.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isRightMouseButton(e)) toggleTeam(r.team); }
        });
        outer.add(row);
        return outer;
    }

    // ---- actions

    private void toggleTeam(String team) {
        boolean added = Store.toggleFavourite(team);
        Store.autoFollow(matches);
        status.setText(added ? team + " is now your team - you'll be told when they play" : team + " removed");
        if (tab == MINE) { buildChips(); reload(); } else render();
    }

    private void openMatch(Feed.Match m) {
        String url = m.state.equals("in") ? Feed.bbcUrl(m.league)
                : "https://www.google.com/search?q=" + java.net.URLEncoder.encode(m.home + " vs " + m.away + " "
                + DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(m.when), ZoneId.systemDefault())), java.nio.charset.StandardCharsets.UTF_8);
        browse(url);
    }

    private static void browse(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) java.awt.Desktop.getDesktop().browse(URI.create(url));
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception e) {
            Store.log("couldn't open browser: " + e);
        }
    }

    private void showNews(String team) {
        JDialog d = new JDialog(frame, team + " news", false);
        d.getContentPane().setBackground(Ui.BG);
        Grid col = new Grid(400, 700, 1);
        JLabel head = Ui.label("Loading…", 12, false, Ui.MUTED);
        head.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
        d.add(head, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(col, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        Ui.styleScroll(sp);
        d.add(sp, BorderLayout.CENTER);
        d.setSize(460, 720);
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
        ex.execute(() -> {
            try {
                List<Feed.News> items = Feed.news(team);
                SwingUtilities.invokeLater(() -> {
                    head.setText(items.isEmpty() ? "No news found" : "Latest headlines · click to read");
                    for (Feed.News n : items) {
                        JPanel outer = new JPanel(new BorderLayout());
                        outer.setBackground(Ui.BG);
                        outer.setBorder(BorderFactory.createEmptyBorder(5, 14, 5, 14));
                        Card c = new Card(false);
                        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
                        c.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
                        c.add(Ui.label("<html><div style='width:340px'>" + n.title.replace("<", "&lt;") + "</div></html>", 14, true, Ui.TEXT));
                        c.add(Ui.label((n.source.isEmpty() ? "" : n.source + " · ") + n.ago, 12, false, Ui.MUTED));
                        Ui.clickable(c, () -> browse(n.link));
                        outer.add(c);
                        col.add(outer);
                    }
                    col.revalidate();
                    col.repaint();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> head.setText("Couldn't load news: " + e.getMessage()));
            }
        });
    }

    // ---- debug menu

    private void showDebug() {
        JDialog d = new JDialog(frame, "Debug", false);
        JTabbedPane tabs = new JTabbedPane();
        JTextArea events = area(), data = area(), refresh = area();
        tabs.addTab("Events", new JScrollPane(events));
        tabs.addTab("Data", new JScrollPane(data));
        tabs.addTab("Refresh", new JScrollPane(refresh));
        Runnable fill = () -> {
            String log = Store.readLog();
            events.setText(log.isEmpty() ? "(empty)" : log);
            events.setCaretPosition(events.getDocument().getLength());
            refresh.setText(refreshReport());
            data.setText("Loading from feed...");
            ex.execute(() -> { String s = dataReport(); SwingUtilities.invokeLater(() -> data.setText(s)); });
        };
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton r = new JButton("Refresh"), c = new JButton("Clear log"), u = new JButton("Check for updates"), x = new JButton("Close");
        u.addActionListener(e -> Updates.check(frame, true));
        r.addActionListener(e -> fill.run());
        c.addActionListener(e -> { Store.clearLog(); fill.run(); });
        x.addActionListener(e -> d.dispose());
        buttons.add(r);
        buttons.add(c);
        buttons.add(u);
        buttons.add(x);
        d.add(tabs, BorderLayout.CENTER);
        d.add(buttons, BorderLayout.SOUTH);
        d.setSize(640, 560);
        d.setLocationRelativeTo(frame);
        fill.run();
        d.setVisible(true);
    }

    private static JTextArea area() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        return a;
    }

    private String refreshReport() {
        String[] lines = Store.readPolls().split("\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("Tracker polls every 12s while a followed match is live, else 30s.\nScreen refreshes every 20s.\n");
        StringBuilder hist = new StringBuilder();
        for (String who : new String[]{"tracker", "screen"}) {
            List<long[]> rows = new ArrayList<>();
            String lastErr = "";
            for (String line : lines) {
                String[] p = line.split("\t", 4);
                if (p.length < 4 || !p[1].equals(who)) continue;
                boolean ok = p[3].equals("ok");
                if (!ok) lastErr = p[3];
                rows.add(new long[]{Long.parseLong(p[0]), Long.parseLong(p[2]), ok ? 0 : 1});
            }
            sb.append("\n").append(who.toUpperCase(Locale.ROOT)).append("\n");
            if (rows.isEmpty()) { sb.append("  no refreshes recorded yet\n"); continue; }
            int n = rows.size(), from = Math.max(0, n - 10), fails = 0;
            long ms = 0;
            for (int i = from; i < n; i++) { ms += rows.get(i)[1]; fails += (int) rows.get(i)[2]; }
            long[] lastRow = rows.get(n - 1);
            sb.append("  last refresh ").append(fmt.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastRow[0]), ZoneId.systemDefault()))).append(" (").append((now - lastRow[0]) / 1000).append("s ago)").append(lastRow[2] == 1 ? " FAILED" : "").append("\n");
            if (n - from > 1) sb.append("  average gap ").append((lastRow[0] - rows.get(from)[0]) / (n - 1 - from) / 100 / 10.0).append("s\n");
            sb.append("  request time ").append(lastRow[1]).append("ms (avg ").append(ms / (n - from)).append("ms)\n  failures in last ").append(n - from).append(": ").append(fails).append("\n");
            if (fails > 0) sb.append("  last error: ").append(lastErr, 0, Math.min(160, lastErr.length())).append("\n");
            for (int i = n - 1; i >= Math.max(0, n - 8); i--) hist.append("  ").append(fmt.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(rows.get(i)[0]), ZoneId.systemDefault()))).append(" ").append(who).append(" ").append(rows.get(i)[1]).append("ms ").append(rows.get(i)[2] == 1 ? "FAIL" : "ok").append("\n");
        }
        return sb.append("\nRECENT\n").append(hist).toString();
    }

    private String dataReport() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("SOURCE\nfootball-data.org v4 (free key, 10 requests/min)\n")
                .append(Feed.url("<league codes>", now, now + Feed.DAY)).append("\n")
                .append("App version ").append(Updates.version()).append("\n").append("Mode: ").append(Store.apiKey().isEmpty() ? (Feed.hasProxy() ? "score server (no key needed)" : "NOT CONFIGURED") : "direct with a developer key").append("\n\nFAVOURITES\n").append(Store.favourites()).append("\n\n");
        List<Feed.Match> follows = Store.follows();
        sb.append("FOLLOWED MATCHES (").append(follows.size()).append(")\n");
        Map<String, List<Feed.Match>> cache = new HashMap<>();
        for (Feed.Match f : follows) {
            sb.append("\n").append(f.home).append(" v ").append(f.away).append("\n  id ").append(f.key()).append("\n  kick-off ")
                    .append(DateTimeFormatter.ofPattern("EEE d MMM HH:mm").format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(f.when), ZoneId.systemDefault()))).append("\n  ")
                    .append(Feed.url(null, f.when - Feed.DAY, f.when + Feed.DAY)).append("\n");
            try {
                Feed.Match m = Feed.lookup(f, cache);
                if (m == null) sb.append("  feed: NOT FOUND\n");
                else sb.append("  feed: ").append(m.hg).append("-").append(m.ag).append("  state=").append(m.state).append("  status=").append(m.status).append("\n");
            } catch (Exception e) {
                sb.append("  feed error: ").append(e).append("\n");
            }
        }
        return sb.toString();
    }
}
