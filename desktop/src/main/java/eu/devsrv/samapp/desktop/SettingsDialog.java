package eu.devsrv.samapp.desktop;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

/** Settings: theme, sounds, notifications, battery saver, start screen, account (sign in / sync), updates. */
final class SettingsDialog {
    private final Main main;
    private final JFrame frame;
    private final JDialog dialog;
    private final JPanel col = new JPanel();
    private String note = "";

    private SettingsDialog(Main main, JFrame frame) {
        this.main = main;
        this.frame = frame;
        dialog = new JDialog(frame, "Settings", true);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(Ui.BG);
        col.setBorder(BorderFactory.createEmptyBorder(16, 20, 20, 20));
        JScrollPane sp = new JScrollPane(col, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        Ui.styleScroll(sp);
        dialog.add(sp);
        dialog.setSize(460, 700);
        dialog.setLocationRelativeTo(frame);
    }

    static void show(Main main, JFrame frame) {
        SettingsDialog s = new SettingsDialog(main, frame);
        s.build();
        s.dialog.setVisible(true);
    }

    private void build() {
        col.removeAll();
        JLabel title = Ui.label("Settings", 24, true, Ui.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(title);

        section("Appearance");
        JPanel theme = row();
        ButtonGroup g = new ButtonGroup();
        theme.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 6));
        for (String[] t : Ui.THEMES) {
            boolean current = t[0].equals(Ui.themeName);
            JToggleButton b = new JToggleButton(t[1], current);
            g.add(b);
            b.addActionListener(e -> {
                if (current) return;
                Store.setSetting("theme", t[0]);
                Ui.applyTheme(t[0]);
                dialog.dispose();
                main.restart(); // rebuilds the window with the new colours
            });
            theme.add(b);
        }
        col.add(theme);

        section("Sound and notifications");
        check("Goal sounds (cheer when your team scores, boo when they concede)", "sounds", true);
        check("Live score notifications", "notifications", true);

        section("Refresh");
        check("Battery saver (refresh half as often; applies after restart)", "saver", false);

        section("Start screen");
        JComboBox<String> start = new JComboBox<>(new String[]{"Auto", "Leagues", "My teams", "Following", "Table"});
        start.setSelectedIndex(Integer.parseInt(Store.setting("startTab", "-1")) + 1);
        start.addActionListener(e -> Store.setSetting("startTab", String.valueOf(start.getSelectedIndex() - 1)));
        start.setAlignmentX(Component.LEFT_ALIGNMENT);
        start.setMaximumSize(new Dimension(200, 28));
        col.add(start);

        section("Account");
        if (Account.signedIn()) {
            col.add(wrap("Signed in as " + Store.user() + ". Your favourite teams and followed matches sync to your other devices.", Ui.TEXT));
            JPanel buttons = row();
            buttons.add(button("Sync now", () -> work("Syncing…", () -> { if (Account.sync()) main.refreshAfterSync(); return "Synced"; })));
            buttons.add(button("Sign out", () -> { Account.signOut(); note = ""; build(); }));
            buttons.add(button("Delete account", () -> {
                if (JOptionPane.showConfirmDialog(dialog, "Delete your account? Saved teams are removed from the server (they stay on this device).", "Delete account", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                    work("Deleting…", () -> { Account.delete(); return "Account deleted"; });
            }));
            col.add(buttons);
        } else {
            col.add(wrap("Sign in to keep your favourite teams and followed matches on every device.", Ui.TEXT));
            JTextField user = new JTextField();
            JPasswordField pass = new JPasswordField();
            for (JTextField f : new JTextField[]{user, pass}) {
                f.setMaximumSize(new Dimension(300, 28));
                f.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            JLabel ul = Ui.label("Username (letters, numbers, _)", 12, false, Ui.HINT), pl = Ui.label("Password (8+ characters)", 12, false, Ui.HINT);
            ul.setAlignmentX(Component.LEFT_ALIGNMENT);
            pl.setAlignmentX(Component.LEFT_ALIGNMENT);
            col.add(ul);
            col.add(user);
            col.add(pl);
            col.add(pass);
            JPanel buttons = row();
            buttons.add(button("Sign in", () -> signIn(user.getText(), new String(pass.getPassword()), false)));
            buttons.add(button("Create account", () -> signIn(user.getText(), new String(pass.getPassword()), true)));
            col.add(buttons);
        }
        if (!note.isEmpty()) col.add(wrap(note, Ui.LIVE));

        section("About");
        col.add(wrap("Version " + Updates.version() + "\nScores: football-data.org", Ui.MUTED));
        JPanel upd = row();
        upd.add(button("Check for updates", () -> Updates.check(frame, true)));
        col.add(upd);

        col.revalidate();
        col.repaint();
    }

    // ---- account actions

    private interface Job { String run() throws Exception; }

    private void work(String busy, Job job) {
        note = busy;
        build();
        Thread t = new Thread(() -> {
            String msg;
            try { msg = job.run(); } catch (Exception e) { msg = e.getMessage() == null ? "Failed" : e.getMessage(); }
            String shown = msg;
            SwingUtilities.invokeLater(() -> { note = shown; build(); });
        });
        t.setDaemon(true);
        t.start();
    }

    private void signIn(String user, String pass, boolean create) {
        work(create ? "Creating account…" : "Signing in…", () -> {
            Account.signIn(user.trim(), pass, create);
            main.refreshAfterSync();
            return "Signed in as " + Store.user();
        });
    }

    // ---- building blocks

    private void section(String name) {
        JLabel l = Ui.label(name.toUpperCase(), 11, true, Ui.MUTED);
        l.setBorder(BorderFactory.createEmptyBorder(20, 0, 6, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(l);
    }

    private JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void check(String text, String key, boolean def) {
        JCheckBox c = new JCheckBox(text, Store.flag(key, def));
        c.setForeground(Ui.TEXT);
        c.setBackground(Ui.BG);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.addActionListener(e -> Store.setSetting(key, String.valueOf(c.isSelected())));
        col.add(c);
    }

    private JLabel wrap(String text, Color color) {
        JLabel l = Ui.label("<html><div style='width:380px'>" + text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</div></html>", 13, false, color);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        return l;
    }
}
