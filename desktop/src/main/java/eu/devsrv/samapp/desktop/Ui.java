package eu.devsrv.samapp.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** Palette and the few custom-painted widgets (rounded cards, pills, the hexagon score box, crests). */
final class Ui {
    static final Color BG = new Color(0x060A1F), SURFACE = new Color(0x0F1A4A), SURFACE_DARK = new Color(0x0A1236), STROKE = new Color(0x1E2E70),
            ACCENT = new Color(0x4F7DFF), LIVE = new Color(0xFF9A1F), TEXT = new Color(0xF4F6FF), MUTED = new Color(0x8A93C0),
            PILL_A = new Color(0x1B3FE0), PILL_B = new Color(0x2F6BFF), ROW = new Color(0x0A1236);

    static JLabel label(String text, int size, boolean bold, Color c) {
        JLabel l = new JLabel(text);
        l.setForeground(c);
        l.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size));
        return l;
    }

    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /**
     * Responsive card grid inside a scroll pane: as many columns as fit (between minW and maxW wide each, at most maxCols), centred.
     * Children flagged with client property "full" take a whole row (empty-state message).
     */
    static final class Grid extends JPanel implements Scrollable {
        private int minW, maxW, maxCols;
        private static final int GAP = 14;

        Grid(int minW, int maxW, int maxCols) {
            setLimits(minW, maxW, maxCols);
            setBackground(BG);
        }

        void setLimits(int minW, int maxW, int maxCols) {
            this.minW = minW;
            this.maxW = maxW;
            this.maxCols = maxCols;
        }

        /** Places the children for width w (when apply) and returns the total height. */
        private int place(int w, boolean apply) {
            Component[] cs = getComponents();
            int cols = Math.max(1, Math.min(maxCols, (w + GAP) / (minW + GAP)));
            int cw = Math.min(maxW, (w - (cols - 1) * GAP) / cols);
            int x0 = (w - (cols * cw + (cols - 1) * GAP)) / 2, y = 0, i = 0;
            while (i < cs.length) {
                if (cs[i] instanceof JComponent && Boolean.TRUE.equals(((JComponent) cs[i]).getClientProperty("full"))) {
                    int h = cs[i].getPreferredSize().height;
                    if (apply) cs[i].setBounds(0, y, w, h);
                    y += h;
                    i++;
                    continue;
                }
                int n = Math.min(cols, cs.length - i), rowH = 0;
                for (int k = 0; k < n; k++) rowH = Math.max(rowH, cs[i + k].getPreferredSize().height);
                if (apply) for (int k = 0; k < n; k++) cs[i + k].setBounds(x0 + k * (cw + GAP), y, cw, rowH);
                y += rowH;
                i += n;
            }
            return y + 16;
        }

        @Override public void doLayout() { place(getWidth(), true); }
        @Override public Dimension getPreferredSize() { return new Dimension(1, place(getWidth() > 0 ? getWidth() : 480, false)); }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 200; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Child at most max wide, centred, full height. */
    static final class Centered extends JPanel {
        private final int max;

        Centered(JComponent child, int max) {
            super(null);
            this.max = max;
            setBackground(BG);
            add(child);
        }

        @Override
        public void doLayout() {
            int w = Math.min(max, getWidth());
            getComponent(0).setBounds((getWidth() - w) / 2, 0, w, getHeight());
        }

        @Override public Dimension getPreferredSize() { return getComponent(0).getPreferredSize(); }
    }

    /** Vector icons (emoji don't render reliably in Swing): ball, star, bell, trophy, flask. Painted in the component's foreground colour. */
    static final class Glyph implements Icon {
        private final String kind;
        private final int size;

        Glyph(String kind, int size) {
            this.kind = kind;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            antialias(g);
            g.translate(x, y);
            g.scale(size, size); // draw in a unit square
            g.setColor(c.getForeground());
            g.setStroke(new BasicStroke(0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case "ball": {
                    g.draw(new Ellipse2D.Double(0.08, 0.08, 0.84, 0.84));
                    Path2D pent = new Path2D.Double();
                    for (int i = 0; i < 5; i++) {
                        double a = Math.toRadians(-90 + i * 72), px = 0.5 + 0.2 * Math.cos(a), py = 0.5 + 0.2 * Math.sin(a);
                        if (i == 0) pent.moveTo(px, py); else pent.lineTo(px, py);
                        g.draw(new Line2D.Double(px, py, 0.5 + 0.42 * Math.cos(a), 0.5 + 0.42 * Math.sin(a)));
                    }
                    pent.closePath();
                    g.fill(pent);
                    break;
                }
                case "star": {
                    Path2D star = new Path2D.Double();
                    for (int i = 0; i < 10; i++) {
                        double a = Math.toRadians(-90 + i * 36), r = i % 2 == 0 ? 0.46 : 0.19;
                        double px = 0.5 + r * Math.cos(a), py = 0.54 + r * Math.sin(a);
                        if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
                    }
                    star.closePath();
                    g.fill(star);
                    break;
                }
                case "flask": { // test tube / lab flask
                    Path2D flask = new Path2D.Double();
                    flask.moveTo(0.4, 0.12);
                    flask.lineTo(0.6, 0.12);
                    flask.lineTo(0.6, 0.42);
                    flask.lineTo(0.86, 0.82);
                    flask.quadTo(0.9, 0.92, 0.8, 0.92);
                    flask.lineTo(0.2, 0.92);
                    flask.quadTo(0.1, 0.92, 0.14, 0.82);
                    flask.lineTo(0.4, 0.42);
                    flask.closePath();
                    g.fill(flask);
                    g.draw(new Line2D.Double(0.32, 0.1, 0.68, 0.1));
                    break;
                }
                case "bell": {
                    Path2D bell = new Path2D.Double();
                    bell.moveTo(0.16, 0.76);
                    bell.curveTo(0.26, 0.68, 0.28, 0.58, 0.28, 0.42);
                    bell.curveTo(0.28, 0.24, 0.4, 0.16, 0.5, 0.16);
                    bell.curveTo(0.6, 0.16, 0.72, 0.24, 0.72, 0.42);
                    bell.curveTo(0.72, 0.58, 0.74, 0.68, 0.84, 0.76);
                    bell.closePath();
                    g.fill(bell);
                    g.fill(new Ellipse2D.Double(0.41, 0.8, 0.18, 0.14));
                    g.fill(new Ellipse2D.Double(0.45, 0.06, 0.1, 0.1));
                    break;
                }
                default: { // trophy
                    Path2D cup = new Path2D.Double();
                    cup.moveTo(0.28, 0.12);
                    cup.lineTo(0.72, 0.12);
                    cup.lineTo(0.72, 0.4);
                    cup.curveTo(0.72, 0.58, 0.62, 0.64, 0.5, 0.64);
                    cup.curveTo(0.38, 0.64, 0.28, 0.58, 0.28, 0.4);
                    cup.closePath();
                    g.fill(cup);
                    g.draw(new Arc2D.Double(0.1, 0.16, 0.26, 0.28, 90, 180, Arc2D.OPEN));
                    g.draw(new Arc2D.Double(0.64, 0.16, 0.26, 0.28, 90, -180, Arc2D.OPEN));
                    g.fill(new Rectangle2D.Double(0.46, 0.64, 0.08, 0.16));
                    g.fill(new Rectangle2D.Double(0.32, 0.8, 0.36, 0.08));
                }
            }
            g.dispose();
        }
    }

    /** Navy gradient card with a thin border (orange when live). */
    static final class Card extends JPanel {
        private final boolean live;

        Card(boolean live) {
            this.live = live;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            antialias(g);
            g.setPaint(new GradientPaint(0, 0, SURFACE, 0, getHeight(), SURFACE_DARK));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 28, 28);
            g.setColor(live ? LIVE : STROKE);
            g.setStroke(new BasicStroke(live ? 1.6f : 1f));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 28, 28);
            g.dispose();
        }
    }

    /** Rounded label: solid, gradient (date pill) or outline (chips, Follow). */
    static final class Pill extends JLabel {
        private final Color fillA, fillB, outline;

        Pill(String text, Color fillA, Color fillB, Color outline, Color fg, int size) {
            super(text, SwingConstants.CENTER);
            this.fillA = fillA;
            this.fillB = fillB;
            this.outline = outline;
            setForeground(fg);
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
            setOpaque(false);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 14, 5, 14));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            antialias(g);
            int arc = getHeight();
            if (fillA != null) {
                g.setPaint(fillB == null ? fillA : new GradientPaint(0, 0, fillA, getWidth(), 0, fillB));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            if (outline != null) {
                g.setColor(outline);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** The hexagon behind the score. */
    static final class Hex extends JComponent {
        private final String text;

        Hex(String text) {
            this.text = text;
            setPreferredSize(new Dimension(104, 60));
            setMaximumSize(new Dimension(104, 60));
            setAlignmentX(CENTER_ALIGNMENT);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            antialias(g);
            Polygon p = new Polygon(new int[]{18, 86, 103, 86, 18, 1}, new int[]{1, 1, 30, 59, 59, 30}, 6);
            g.setColor(new Color(0x0A1240));
            g.fillPolygon(p);
            g.setColor(new Color(0x2A3C8F));
            g.setStroke(new BasicStroke(1.5f));
            g.drawPolygon(p);
            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    // ---- crests, downloaded once and cached

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });

    static void crest(JLabel l, String url, int size) {
        l.setPreferredSize(new Dimension(size, size));
        l.setMinimumSize(new Dimension(size, size));
        l.setMaximumSize(new Dimension(size, size));
        if (url.isEmpty()) return;
        Image img = CACHE.get(url + size);
        if (img != null) { l.setIcon(new ImageIcon(img)); return; }
        POOL.execute(() -> {
            try {
                BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(Feed.getBytes(url)));
                if (src == null) return;
                Image scaled = src.getScaledInstance(size, size, Image.SCALE_SMOOTH);
                CACHE.put(url + size, scaled);
                SwingUtilities.invokeLater(() -> l.setIcon(new ImageIcon(scaled)));
            } catch (Exception e) {
                // no crest: the row just goes without
            }
        });
    }

    /** Run action when c or any descendant is clicked, except components marked with the client property "own". */
    static void clickable(Component c, Runnable action) {
        if (c instanceof JComponent && Boolean.TRUE.equals(((JComponent) c).getClientProperty("own"))) return;
        c.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        c.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) action.run(); }
        });
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) clickable(k, action);
    }

    static void click(JComponent c, Runnable action) {
        c.putClientProperty("own", true);
        c.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        c.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) action.run(); }
        });
    }
}
