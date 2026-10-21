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
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.RadialGradientPaint;
import java.awt.geom.Area;
import java.awt.event.MouseAdapter;
import java.awt.geom.Arc2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
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
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Palette and the few custom-painted widgets (rounded cards, pills, the hexagon score box, crests). */
final class Ui {
    static Color BG, SURFACE, SURFACE_DARK, STROKE, ACCENT, LIVE, TEXT, MUTED, PILL_A, PILL_B, ROW, HEX_FILL, HEX_STROKE;
    static boolean dark = true;

    static { applyTheme("dark"); }

    /** Theme keys (stored in settings) and the names shown in Settings. */
    static final String[][] THEMES = {{"dark", "Dark"}, {"light", "Light"}, {"midnight", "Midnight"}, {"pitch", "Pitch"}, {"sunset", "Sunset"}, {"white", "White"}, {"black", "Black"}};
    static String themeName = "dark";

    /** Switches the palette; the window is rebuilt afterwards (see Main.restart). Unknown names fall back to dark. */
    static void applyTheme(String name) {
        themeName = name;
        dark = !"light".equals(name) && !"white".equals(name);
        backdrop = null; // rebuild the coloured backdrop in the new theme's colours
        blurred = null;
        PILL_A = new Color(0x1B3FE0);
        PILL_B = new Color(0x2F6BFF);
        switch (name) {
            case "light":
                BG = new Color(0xF1F4FD); SURFACE = new Color(0xFFFFFF); SURFACE_DARK = new Color(0xE7EDFC); STROKE = new Color(0xCFD9F5);
                ACCENT = new Color(0x2F5BFF); LIVE = new Color(0xF08A00); TEXT = new Color(0x0B1233); MUTED = new Color(0x5A6590);
                ROW = new Color(0xFFFFFF); HEX_FILL = new Color(0xEEF3FF); HEX_STROKE = new Color(0xB7C5F2);
                break;
            case "white": // pure white, black accents
                BG = new Color(0xFFFFFF); SURFACE = new Color(0xFFFFFF); SURFACE_DARK = new Color(0xF2F2F2); STROKE = new Color(0xD9D9D9);
                ACCENT = new Color(0x000000); LIVE = new Color(0xF08A00); TEXT = new Color(0x000000); MUTED = new Color(0x666666);
                ROW = new Color(0xFFFFFF); HEX_FILL = new Color(0xF5F5F5); HEX_STROKE = new Color(0xBDBDBD);
                PILL_A = new Color(0x000000); PILL_B = new Color(0x3A3A3A);
                break;
            case "black": // pure black, white accents
                PILL_A = new Color(0x2A2A2A); PILL_B = new Color(0x4A4A4A);
                BG = new Color(0x000000); SURFACE = new Color(0x111111); SURFACE_DARK = new Color(0x0A0A0A); STROKE = new Color(0x2A2A2A);
                ACCENT = new Color(0xFFFFFF); LIVE = new Color(0xFF9A1F); TEXT = new Color(0xFFFFFF); MUTED = new Color(0x9E9E9E);
                ROW = new Color(0x0A0A0A); HEX_FILL = new Color(0x0A0A0A); HEX_STROKE = new Color(0x333333);
                break;
            case "midnight": // black with cyan
                PILL_A = new Color(0x0E7490); PILL_B = new Color(0x22D3EE);
                BG = new Color(0x05060A); SURFACE = new Color(0x14161F); SURFACE_DARK = new Color(0x0B0D14); STROKE = new Color(0x2A2F3F);
                ACCENT = new Color(0x22D3EE); LIVE = new Color(0xFFB020); TEXT = new Color(0xF5F7FA); MUTED = new Color(0x8B93A7);
                ROW = new Color(0x0B0D14); HEX_FILL = new Color(0x0E1018); HEX_STROKE = new Color(0x2A2F3F);
                break;
            case "pitch": // deep football-pitch green
                PILL_A = new Color(0x15803D); PILL_B = new Color(0x2ECC71);
                BG = new Color(0x06140C); SURFACE = new Color(0x0E2A1A); SURFACE_DARK = new Color(0x0A1F13); STROKE = new Color(0x1F4D32);
                ACCENT = new Color(0x2ECC71); LIVE = new Color(0xFFB020); TEXT = new Color(0xEEFBF3); MUTED = new Color(0x8FB39C);
                ROW = new Color(0x0A1F13); HEX_FILL = new Color(0x0C2317); HEX_STROKE = new Color(0x2A6B45);
                break;
            case "sunset": // plum with coral and gold
                PILL_A = new Color(0xB83A5E); PILL_B = new Color(0xFF7A59);
                BG = new Color(0x1A0B1F); SURFACE = new Color(0x3A1A3F); SURFACE_DARK = new Color(0x240F2A); STROKE = new Color(0x5A2D62);
                ACCENT = new Color(0xFF7A59); LIVE = new Color(0xFFC24B); TEXT = new Color(0xFFF4EE); MUTED = new Color(0xB896A8);
                ROW = new Color(0x240F2A); HEX_FILL = new Color(0x2A1230); HEX_STROKE = new Color(0x6B3470);
                break;
            default: // dark: deep navy with electric blue
                themeName = "dark";
                BG = new Color(0x060A1F); SURFACE = new Color(0x0F1A4A); SURFACE_DARK = new Color(0x0A1236); STROKE = new Color(0x1E2E70);
                ACCENT = new Color(0x4F7DFF); LIVE = new Color(0xFF9A1F); TEXT = new Color(0xF4F6FF); MUTED = new Color(0x8A93C0);
                ROW = new Color(0x0A1236); HEX_FILL = new Color(0x0A1240); HEX_STROKE = new Color(0x2A3C8F);
        }
    }

    /** Liquid-glass fill: a light tint in dark mode, a frosted white in light mode. */
    static Color glassTop() { return dark ? new Color(255, 255, 255, 40) : new Color(255, 255, 255, 200); }
    static Color glassBottom() { return dark ? new Color(255, 255, 255, 12) : new Color(255, 255, 255, 120); }
    static Color withAlpha(Color c, int alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha); }

    /** The window's coloured backdrop (drawn by Centered), and a blurred copy that glass surfaces show through. */
    private static Component root;
    private static BufferedImage backdrop, blurred;

    /** Soft coloured light behind the whole window, so the glass has something to refract. */
    static void paintAmbient(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, 0, BG, 0, h, BG));
        g.fillRect(0, 0, w, h);
        int m = Math.max(w, h);
        blob(g, w * 0.18f, h * 0.22f, m * 0.45f, PILL_B, dark ? 200 : 150);
        blob(g, w * 0.86f, h * 0.70f, m * 0.40f, LIVE, dark ? 120 : 90);
        blob(g, w * 0.55f, h * 0.05f, m * 0.35f, new Color(0x9B5CFF), dark ? 120 : 80);
    }

    private static void blob(Graphics2D g, float x, float y, float r, Color c, int alpha) {
        g.setPaint(new RadialGradientPaint(new Point2D.Float(x, y), r, new float[]{0f, 1f}, new Color[]{withAlpha(c, alpha), withAlpha(c, 0)}));
        g.fillRect((int) (x - r), (int) (y - r), (int) (2 * r), (int) (2 * r));
    }

    /** Blurs a picture: shrink it, box-blur twice in each direction, stretch it back up. Cheap and soft. */
    static BufferedImage blur(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight(), sw = Math.max(1, w / 4), sh = Math.max(1, h / 4);
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        g.drawImage(src, 0, 0, sw, sh, null);
        g.dispose();
        int[] px = small.getRGB(0, 0, sw, sh, null, 0, sw);
        for (int pass = 0; pass < 2; pass++) {
            px = boxBlur(px, sw, sh, 2, true);
            px = boxBlur(px, sw, sh, 2, false);
        }
        small.setRGB(0, 0, sw, sh, px, 0, sw);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(small, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static int[] boxBlur(int[] src, int w, int h, int r, boolean horizontal) {
        int[] out = new int[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, red = 0, green = 0, blue = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int sx = horizontal ? Math.min(w - 1, Math.max(0, x + k)) : x;
                    int sy = horizontal ? y : Math.min(h - 1, Math.max(0, y + k));
                    int p = src[sy * w + sx];
                    a += p >>> 24; red += (p >> 16) & 255; green += (p >> 8) & 255; blue += p & 255; n++;
                }
                out[y * w + x] = ((a / n) << 24) | ((red / n) << 16) | ((green / n) << 8) | (blue / n);
            }
        }
        return out;
    }

    /**
     * Liquid glass for a shape in component c: the blurred backdrop behind it, a light tint, a sheen from the top,
     * and a bright rim that fades toward the bottom-right (orange when live).
     */
    static void paintGlass(Graphics2D g0, Component c, Shape body, boolean live) {
        Graphics2D g = (Graphics2D) g0.create();
        antialias(g);
        int w = c.getWidth(), h = c.getHeight();
        Rectangle2D b = body.getBounds2D();
        float edge = (float) Math.min(7, b.getHeight() * 0.15); // how wide the bent-light rim is
        Shape inner = new RoundRectangle2D.Double(b.getX() + edge, b.getY() + edge, b.getWidth() - 2 * edge, b.getHeight() - 2 * edge, 20, 20);
        Point p = (blurred != null && root != null) ? SwingUtilities.convertPoint(c, 0, 0, root) : new Point(0, 0);
        if (blurred != null) {
            // frosted centre: the blurred backdrop behind the glass
            Graphics2D centre = (Graphics2D) g.create();
            centre.clip(body);
            centre.drawImage(blurred, -p.x, -p.y, null);
            centre.dispose();
            // bent light: the sharp backdrop shows through a rim around the edge, brightened, like light bending through thick glass
            Area rim = new Area(body);
            rim.subtract(new Area(inner));
            Graphics2D bent = (Graphics2D) g.create();
            bent.clip(rim);
            bent.drawImage(backdrop, -p.x, -p.y, null);
            bent.setPaint(withAlpha(Color.WHITE, dark ? 30 : 70));
            bent.fill(rim);
            bent.dispose();
        }
        // body tint
        g.setPaint(new GradientPaint(0, 0, glassTop(), 0, h, glassBottom()));
        g.fill(body);
        // specular: a hot highlight from the top-left, like light hitting a curved surface
        Graphics2D spec = (Graphics2D) g.create();
        spec.clip(body);
        spec.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.22f, h * -0.05f), w * 0.75f, new float[]{0f, 1f},
                new Color[]{withAlpha(Color.WHITE, dark ? 150 : 230), withAlpha(Color.WHITE, 0)}));
        spec.fill(body);
        spec.dispose();
        // rim: a bright edge, fading toward the bottom-right (orange when live)
        g.setStroke(new BasicStroke(live ? 1.6f : 1.3f));
        g.setPaint(live ? LIVE : new GradientPaint(0, 0, withAlpha(Color.WHITE, dark ? 255 : 255), w, h, withAlpha(Color.WHITE, dark ? 30 : 110)));
        g.draw(body);
        // depth: a faint dark line just inside the edge, darker at the bottom
        if (!live) {
            g.setStroke(new BasicStroke(1f));
            g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, dark ? 70 : 28)));
            g.draw(inner);
        }
        g.dispose();
    }

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
            setOpaque(false); // let the ambient backdrop show between the glass cards
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

        /** Paints the ambient backdrop, and keeps a blurred copy of it for the glass surfaces to show through. */
        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (backdrop == null || backdrop.getWidth() != w || backdrop.getHeight() != h) {
                backdrop = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D bg = backdrop.createGraphics();
                antialias(bg);
                paintAmbient(bg, w, h);
                bg.dispose();
                blurred = blur(backdrop);
                root = this;
            }
            g.drawImage(backdrop, 0, 0, null);
        }

        @Override
        public void doLayout() {
            int w = Math.min(max, getWidth());
            getComponent(0).setBounds((getWidth() - w) / 2, 0, w, getHeight());
        }

        @Override public Dimension getPreferredSize() { return getComponent(0).getPreferredSize(); }
    }

    /** Vector icons (emoji don't render reliably in Swing): ball, star, bell, trophy, flask, heart, gear. Painted in the given colour, or the component's foreground. */
    static final class Glyph implements Icon {
        private final String kind;
        private final int size;
        private final Color color;

        Glyph(String kind, int size) {
            this(kind, size, null);
        }

        Glyph(String kind, int size, Color color) {
            this.kind = kind;
            this.size = size;
            this.color = color;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            antialias(g);
            g.translate(x, y);
            g.scale(size, size); // draw in a unit square
            g.setColor(color != null ? color : c.getForeground());
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
                case "gear": {
                    g.draw(new Ellipse2D.Double(0.28, 0.28, 0.44, 0.44));
                    g.setStroke(new BasicStroke(0.14f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                    for (int i = 0; i < 8; i++) {
                        double a = Math.toRadians(i * 45);
                        g.draw(new Line2D.Double(0.5 + 0.3 * Math.cos(a), 0.5 + 0.3 * Math.sin(a), 0.5 + 0.42 * Math.cos(a), 0.5 + 0.42 * Math.sin(a)));
                    }
                    break;
                }
                case "heart": {
                    Path2D heart = new Path2D.Double();
                    heart.moveTo(0.5, 0.88);
                    heart.curveTo(0.15, 0.62, 0.05, 0.45, 0.08, 0.3);
                    heart.curveTo(0.12, 0.12, 0.42, 0.1, 0.5, 0.3);
                    heart.curveTo(0.58, 0.1, 0.88, 0.12, 0.92, 0.3);
                    heart.curveTo(0.95, 0.45, 0.85, 0.62, 0.5, 0.88);
                    heart.closePath();
                    g.fill(heart);
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
            paintGlass(g, this, new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, 28, 28), live);
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
            } else if (outline != null) { // outlined pills are frosted glass instead of a flat fill
                paintGlass(g, this, new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, arc, arc), false);
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

        private final int size;

        Hex(String text) {
            this(text, 26);
        }

        Hex(String text, int size) {
            this.text = text;
            this.size = size;
            setPreferredSize(new Dimension(104, 60));
            setMaximumSize(new Dimension(104, 60));
            setAlignmentX(CENTER_ALIGNMENT);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            antialias(g);
            Polygon p = new Polygon(new int[]{18, 86, 103, 86, 18, 1}, new int[]{1, 1, 30, 59, 59, 30}, 6);
            g.setColor(HEX_FILL);
            g.fillPolygon(p);
            g.setColor(HEX_STROKE);
            g.setStroke(new BasicStroke(1.5f));
            g.drawPolygon(p);
            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    // ---- crests: generated badges

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });

    /** Team crest: a generated badge for "badge:..." specs, otherwise the image at the URL, loaded in the background. */
    static void crest(JLabel l, String spec, int size) {
        l.setPreferredSize(new Dimension(size, size));
        l.setMinimumSize(new Dimension(size, size));
        l.setMaximumSize(new Dimension(size, size));
        if (spec.startsWith("badge:")) { l.setIcon(new ImageIcon(badge(spec, size))); return; }
        if (spec.isEmpty()) return;
        Image img = CACHE.get(spec + size);
        if (img != null) { l.setIcon(new ImageIcon(img)); return; }
        POOL.execute(() -> {
            try {
                BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(Feed.getBytes(spec)));
                if (src == null) return;
                Image scaled = src.getScaledInstance(size, size, Image.SCALE_SMOOTH);
                CACHE.put(spec + size, scaled);
                SwingUtilities.invokeLater(() -> l.setIcon(new ImageIcon(scaled)));
            } catch (Exception e) {
                // no crest: the row just goes without
            }
        });
    }

    /** Generic team badge: a shield in a colour derived from the team name, with its three-letter code. */
    static BufferedImage badge(String spec, int size) {
        String[] p = spec.substring(6).split(":", 2);
        String name = p.length > 1 ? p[1] : "", code = (p[0].isBlank() ? name.replaceAll("[^A-Za-z]", "") : p[0]).toUpperCase();
        if (code.length() > 3) code = code.substring(0, 3);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        antialias(g);
        g.scale(size, size);
        Path2D shield = new Path2D.Double();
        shield.moveTo(0.1, 0.08);
        shield.lineTo(0.9, 0.08);
        shield.lineTo(0.9, 0.5);
        shield.quadTo(0.9, 0.86, 0.5, 0.97);
        shield.quadTo(0.1, 0.86, 0.1, 0.5);
        shield.closePath();
        g.setColor(Color.getHSBColor((float) ((((name.hashCode() * 2654435761L) >>> 8) % 360) / 360.0), 0.55f, 0.58f));
        g.fill(shield);
        g.setColor(new Color(255, 255, 255, 150));
        g.setStroke(new BasicStroke(0.045f));
        g.draw(shield);
        g.setTransform(new java.awt.geom.AffineTransform()); // text in real pixels
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, Math.round(size * (code.length() > 2 ? 0.3f : 0.38f)))));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(code, (size - fm.stringWidth(code)) / 2f, size * 0.5f + (fm.getAscent() - fm.getDescent()) / 2f);
        g.dispose();
        return img;
    }

    /** Flat, rounded scroll bar: no arrow buttons, a slim translucent thumb on a transparent track. Also drops the pane's border. */
    static void styleScroll(JScrollPane sp) {
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        JScrollBar bar = sp.getVerticalScrollBar();
        bar.setUnitIncrement(24);
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(10, 0));
        bar.setUI(new ThinScrollBar());
    }

    static final class ThinScrollBar extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor = new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 110);
            thumbDarkShadowColor = thumbColor;
            thumbHighlightColor = thumbColor;
            thumbLightShadowColor = thumbColor;
            trackColor = BG;
        }

        @Override protected JButton createDecreaseButton(int o) { return emptyButton(); }
        @Override protected JButton createIncreaseButton(int o) { return emptyButton(); }

        private static JButton emptyButton() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { /* transparent */ }

        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (!scrollbar.isEnabled() || r.width <= 0 || r.height <= 0) return;
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            g2.setColor(thumbColor);
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g2.dispose();
        }
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
