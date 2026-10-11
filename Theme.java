import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.*;

final class Theme {
    static boolean light = false;
    static final Color DEFAULT_ACCENT = new Color(0x6C7BFF);
    static final Color NEUTRAL_ACCENT = new Color(0x1BD96A);
    static Color accent = DEFAULT_ACCENT;
    static Color BG1, BG2, CARD, CARD2, ACC1, ACC2, ACC_HI, ACC_ON, TEXT, MUTED, LINE, INPUT, SIDE;
    static Color SEL, SEL_FIELD, SEL_FIELD_FG, SUB, ICON, ICON_HI, NAV_IDLE, DIS_BG, DIS_FG, DIS_ICON, DIS_INPUT;
    static Color SEC_HOV, SEC_PR, CARD_HOV, CHECK_OFF, DATE, CONSOLE_FG, THUMB, THUMB_HOV, THUMB_DRAG;
    static { apply(false, DEFAULT_ACCENT); }

    private static Color c(int rgb) { return new Color(rgb); }

    static double lum(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    static Color parseHex(String s, Color def) {
        try { return new Color(Integer.parseInt(s.trim().replace("#", ""), 16) & 0xFFFFFF); } catch (Exception e) { return def; }
    }

    static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    static Color ov(int a) {
        a = Math.max(0, Math.min(255, a));
        return light ? new Color(0, 0, 0, Math.round(a * 0.8f)) : new Color(255, 255, 255, a);
    }

    static Color acc(int a) { return new Color(ACC2.getRed(), ACC2.getGreen(), ACC2.getBlue(), Math.max(0, Math.min(255, a))); }

    private static float kb, kt;

    private static Color t(int rgb, float k) {
        Color c = new Color(rgb);
        if (k <= 0f) return c;
        float[] f = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        float[] ac = Color.RGBtoHSB(accent.getRed(), accent.getGreen(), accent.getBlue(), null);
        return Color.getHSBColor(ac[0], Math.min(1f, Math.max(f[1], ac[1] * k)), f[2]);
    }

    static void apply(boolean lt, Color want) {
        light = lt;
        accent = new Color(want.getRed(), want.getGreen(), want.getBlue());
        Color a = accent;
        for (int i = 0; i < 40 && lt && lum(a) > 0.30; i++) a = Fx.mix(a, Color.BLACK, 0.06f);
        for (int i = 0; i < 40 && !lt && lum(a) < 0.10; i++) a = Fx.mix(a, Color.WHITE, 0.08f);
        kb = accent.equals(NEUTRAL_ACCENT) ? 0f : (lt ? 0.16f : 0.55f);
        kt = kb * 0.2f;
        ACC2 = a;
        ACC1 = Fx.mix(a, Color.BLACK, 0.22f);
        ACC_HI = Fx.mix(a, Color.WHITE, 0.22f);
        ACC_ON = lum(a) > 0.32 ? Fx.mix(a, Color.BLACK, 0.85f) : Color.WHITE;
        if (!lt) {
            BG1 = t(0x1B1C20, kb); BG2 = t(0x16171B, kb); CARD = t(0x23252A, kb); CARD2 = t(0x2E3137, kb);
            TEXT = t(0xF2F3F5, kt); MUTED = t(0x9A9EA6, kt); LINE = t(0x31343B, kb); INPUT = t(0x1A1C20, kb); SIDE = t(0x191B1E, kb);
            SUB = t(0xB9BDC4, kt); ICON = t(0xD5D8DD, kt); ICON_HI = Color.WHITE; NAV_IDLE = t(0xA9ADB5, kt);
            DIS_BG = t(0x2B2E34, kb); DIS_FG = t(0x7C8089, kt); DIS_ICON = t(0x575A61, kt); DIS_INPUT = t(0x202226, kb);
            SEC_HOV = t(0x3A3E46, kb); SEC_PR = t(0x262930, kb); CARD_HOV = t(0x2A2D33, kb); CHECK_OFF = t(0x4A4E56, kb);
            DATE = t(0x7E828A, kt); CONSOLE_FG = t(0xD4D5D6, kt);
            THUMB = t(0x3A3D44, kb); THUMB_HOV = t(0x535862, kb); THUMB_DRAG = t(0x6C717B, kb);
            SEL = Fx.mix(CARD2, ACC1, 0.32f);
            SEL_FIELD = Fx.mix(ACC1, Color.BLACK, 0.25f);
            SEL_FIELD_FG = Color.WHITE;
        } else {
            BG1 = t(0xE3E5EA, kb); BG2 = t(0xF3F4F7, kb); CARD = t(0xFFFFFF, kb); CARD2 = t(0xE9EBEF, kb);
            TEXT = t(0x1B1C20, kt); MUTED = t(0x626771, kt); LINE = t(0xD5D8DF, kb); INPUT = t(0xF0F2F5, kb); SIDE = t(0xEDEEF2, kb);
            SUB = t(0x4D525B, kt); ICON = t(0x4A4E57, kt); ICON_HI = t(0x111216, kt); NAV_IDLE = t(0x5B606A, kt);
            DIS_BG = t(0xDDE0E5, kb); DIS_FG = t(0x9AA0A9, kt); DIS_ICON = t(0xB0B5BD, kt); DIS_INPUT = t(0xE6E8EC, kb);
            SEC_HOV = t(0xD9DCE2, kb); SEC_PR = t(0xCDD1D8, kb); CARD_HOV = t(0xF0F2F5, kb); CHECK_OFF = t(0xB4B9C1, kb);
            DATE = t(0x7B808A, kt); CONSOLE_FG = t(0x23252A, kt);
            THUMB = t(0xC4C8CF, kb); THUMB_HOV = t(0xA3A9B2, kb); THUMB_DRAG = t(0x8D939D, kb);
            SEL = Fx.mix(CARD2, ACC2, 0.28f);
            SEL_FIELD = Fx.mix(ACC2, Color.WHITE, 0.55f);
            SEL_FIELD_FG = TEXT;
        }
    }

    static void applyUI() {
        UIManager.put("OptionPane.background", CARD);
        UIManager.put("Panel.background", CARD);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("ToolTip.background", CARD2);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.font", font(Font.PLAIN, 13));
        UIManager.put("ToolTip.border", new javax.swing.border.CompoundBorder(new LineBorder(LINE, 1), new EmptyBorder(5, 9, 5, 9)));
    }

    static Color[] palette() {
        return new Color[]{BG1, BG2, CARD, CARD2, SIDE, INPUT, LINE, TEXT, MUTED, SUB, ACC1, ACC2, ACC_ON, CONSOLE_FG, SEL};
    }

    static Map<Integer, Color> remap(Color[] old) {
        Color[] nw = palette();
        Map<Integer, Color> m = new HashMap<>();
        for (int i = 0; i < old.length; i++) if (!old[i].equals(nw[i])) m.putIfAbsent(old[i].getRGB(), nw[i]);
        return m;
    }

    private static Border recolorBorder(Border b, Map<Integer, Color> m) {
        if (b instanceof LineBorder lb) {
            Color n = m.get(lb.getLineColor().getRGB());
            return n == null ? b : new LineBorder(n, lb.getThickness(), lb.getRoundedCorners());
        }
        if (b instanceof javax.swing.border.CompoundBorder cb)
            return new javax.swing.border.CompoundBorder(recolorBorder(cb.getOutsideBorder(), m), recolorBorder(cb.getInsideBorder(), m));
        return b;
    }

    static void recolor(Component c, Map<Integer, Color> m) {
        if (c.isForegroundSet()) {
            Color f = c.getForeground();
            Color n = f.getAlpha() == 255 ? m.get(f.getRGB()) : null;
            if (n != null) c.setForeground(n);
        }
        if (c.isBackgroundSet()) {
            Color f = c.getBackground();
            Color n = f.getAlpha() == 255 ? m.get(f.getRGB()) : null;
            if (n != null) c.setBackground(n);
        }
        if (c instanceof JComponent jc) {
            Border b = jc.getBorder();
            if (b != null) { Border nb = recolorBorder(b, m); if (nb != b) jc.setBorder(nb); }
        }
        if (c instanceof javax.swing.text.JTextComponent t) {
            t.setCaretColor(ACC2);
            t.setSelectionColor(SEL_FIELD);
            t.setSelectedTextColor(SEL_FIELD_FG);
        }
        if (c instanceof JScrollBar sb && sb.getUI() instanceof SlimScrollBarUI) sb.setUI(new SlimScrollBarUI());
        if (c instanceof Combo<?> cb) cb.refreshTheme();
        if (c instanceof Container ct) for (Component ch : ct.getComponents()) recolor(ch, m);
        c.repaint();
    }

    static final int TITLE_H = 48, SIDE_W = 76;
    static final String DISCORD_ID = "1558210355229106416";

    private static final Font BASE_FONT = new Font("Segoe UI", Font.PLAIN, 14);
    private static final Map<Long, Font> FONTS = new ConcurrentHashMap<>();

    static Font font(int style, float size) {
        long k = ((long) style << 32) | (Float.floatToIntBits(size) & 0xFFFFFFFFL);
        Font f = FONTS.get(k);
        if (f == null) { f = BASE_FONT.deriveFont(style, size); FONTS.put(k, f); }
        return f;
    }

    static java.awt.image.BufferedImage icon(int sz) { return icon(sz, true); }

    private static void face(Graphics2D g, double ox, double oy, double ux, double uy, double vx, double vy, java.util.function.BiFunction<Integer, Integer, Color> col) {
        java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform(ux, uy, vx, vy, ox, oy);
        Graphics2D f = (Graphics2D) g.create();
        f.transform(at);
        for (int r = 0; r < 8; r++)
            for (int k = 0; k < 8; k++) {
                f.setColor(col.apply(k, r));
                f.fill(new Rectangle2D.Double(k / 8.0 - 0.004, r / 8.0 - 0.004, 1 / 8.0 + 0.008, 1 / 8.0 + 0.008));
            }
        f.dispose();
    }

    private static Color shade(int rgb, double f, int n) {
        int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 255) * f + n)), gg = (int) Math.max(0, Math.min(255, ((rgb >> 8) & 255) * f + n)),
                b = (int) Math.max(0, Math.min(255, (rgb & 255) * f + n));
        return new Color(r, gg, b);
    }

    private static final String LOGO_B64 = String.join("",
        "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");
    private static java.awt.image.BufferedImage logoArt;

    private static synchronized java.awt.image.BufferedImage art() {
        if (logoArt == null) {
            try { logoArt = javax.imageio.ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(LOGO_B64))); }
            catch (Exception ex) { logoArt = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB); }
        }
        return logoArt;
    }

    private static final Map<Integer, java.awt.image.BufferedImage> ICONS = new HashMap<>();

    static java.awt.image.BufferedImage icon(int sz, boolean rounded) {
        java.awt.image.BufferedImage c = ICONS.get(sz);
        if (c != null) return c;
        java.awt.image.BufferedImage cur = art();
        if (sz >= 40) {
            java.awt.image.BufferedImage px = new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = px.createGraphics();
            pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            pg.drawImage(cur, 0, 0, sz, sz, null);
            pg.dispose();
            ICONS.put(sz, px);
            return px;
        }
        int w = cur.getWidth();
        while (w / 2 >= sz) {
            w /= 2;
            java.awt.image.BufferedImage n = new java.awt.image.BufferedImage(w, w, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = n.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cur, 0, 0, w, w, null);
            g.dispose();
            cur = n;
        }
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(cur, 0, 0, sz, sz, null);
        g.dispose();
        ICONS.put(sz, im);
        return im;
    }

    private static final Map<Integer, java.awt.image.BufferedImage> TEX = new HashMap<>();

    private static int th(int a, int b) {
        int h = a * 374761393 + b * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    private static int tint(int rgb, int d) {
        int r = Math.max(0, Math.min(255, ((rgb >> 16) & 255) + d)), g = Math.max(0, Math.min(255, ((rgb >> 8) & 255) + d)), b = Math.max(0, Math.min(255, (rgb & 255) + d));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static java.awt.image.BufferedImage tex(int kind, int face) {
        int key = kind * 2 + face;
        java.awt.image.BufferedImage t = TEX.get(key);
        if (t != null) return t;
        t = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                int n = th(x, y + key * 31) % 25 - 12;
                int c;
                if (kind == 0) {
                    if (face == 0) c = tint(0x79B83F, n);
                    else {
                        int fringe = 3 + th(x, 77) % 3;
                        int dn = n - (th(x, y + 9) % 100 < 14 ? 22 : 0);
                        c = y < fringe ? tint(0x79B83F, n) : tint(0x866043, dn);
                    }
                } else {
                    c = tint(0x7E7F83, n);
                    int q = th(x / 2, y / 2 + 5) % 100;
                    if (face == 1 && q < 22) c = tint(0x5DECF5, n / 2);
                    else if (face == 0 && q < 14) c = tint(0x5DECF5, n / 2);
                }
                t.setRGB(x, y, c);
            }
        TEX.put(key, t);
        return t;
    }

    static void texCube(Graphics2D g0, int x, int y, int sz, int kind) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        double S = sz;
        java.awt.geom.AffineTransform base = g.getTransform();
        g.translate(x, y);
        g.drawImage(tex(kind, 1), new java.awt.geom.AffineTransform(S / 32, S / 64, 0, S / 32, 0, S / 4), null);
        g.setColor(new Color(0, 0, 0, 70));
        g.fillPolygon(new int[]{0, (int) (S / 2), (int) (S / 2), 0}, new int[]{(int) (S / 4), (int) (S / 2), (int) S, (int) (S * 3 / 4)}, 4);
        g.drawImage(tex(kind, 1), new java.awt.geom.AffineTransform(S / 32, -S / 64, 0, S / 32, S / 2, S / 2), null);
        g.setColor(new Color(0, 0, 0, 130));
        g.fillPolygon(new int[]{(int) (S / 2), (int) S, (int) S, (int) (S / 2)}, new int[]{(int) (S / 2), (int) (S / 4), (int) (S * 3 / 4), (int) S}, 4);
        g.drawImage(tex(kind, 0), new java.awt.geom.AffineTransform(S / 32, S / 64, -S / 32, S / 64, S / 2, 0), null);
        g.setTransform(base);
        g.dispose();
    }

    static void cube(Graphics2D g, int x, int y, int size, Color top, Color left, Color right, boolean grass) {
        double w = size, h = size;
        int cx = x + size / 2;
        int[] tx = {cx, (int) (x + w), cx, x};
        int[] ty = {(int) (y), (int) (y + h * 0.25), (int) (y + h * 0.5), (int) (y + h * 0.25)};
        int[] lx = {x, cx, cx, x};
        int[] ly = {(int) (y + h * 0.25), (int) (y + h * 0.5), (int) (y + h), (int) (y + h * 0.75)};
        int[] rx = {cx, (int) (x + w), (int) (x + w), cx};
        int[] ry = {(int) (y + h * 0.5), (int) (y + h * 0.25), (int) (y + h * 0.75), (int) (y + h)};
        g.setColor(left); g.fillPolygon(lx, ly, 4);
        g.setColor(right); g.fillPolygon(rx, ry, 4);
        if (grass) {
            g.setColor(top.darker());
            g.fillPolygon(new int[]{x, cx, cx, x}, new int[]{ly[0], ly[1], (int) (ly[1] + h * 0.14), (int) (ly[0] + h * 0.14)}, 4);
            g.setColor(top.darker().darker());
            g.fillPolygon(new int[]{cx, rx[1], rx[1], cx}, new int[]{ry[0], ry[1], (int) (ry[1] + h * 0.14), (int) (ry[0] + h * 0.14)}, 4);
        }
        g.setColor(top); g.fillPolygon(tx, ty, 4);
        g.setColor(new Color(0, 0, 0, 70));
        g.setStroke(new BasicStroke(Math.max(1f, size / 24f)));
        g.drawPolygon(tx, ty, 4);
        g.drawLine(cx, ty[2], cx, y + size);
    }

    static volatile int skin = 0;
    static volatile BufferedImage headImg = null;

    private static final int SKINS = 6;

    static int skinFor(String name) {
        if (name == null || name.isEmpty()) return 0;
        return Math.floorMod(name.toLowerCase().hashCode(), SKINS);
    }

    static void head(Graphics2D g, int x, int y, int sz) {
        String[] steve = {"HHHHHHHH", "HHHHHHHH", "HSSSSSSH", "SSSSSSSS", "SWESSEWS", "SSSDDSSS", "SSMMMMSS", "SSMSSMSS"};
        String[] alex  = {"HHHHHHHH", "HHHHHHHH", "HHSSSSHH", "HSSSSSSH", "HWESSEWH", "SSSSSSSS", "SSSMMSSS", "SSSSSSSS"};
        int[][] pal = {
                {0x4A3222, 0xB98A6A, 0x946A4E, 0x4B3F9E, 0x8A5A44},
                {0xC9783A, 0xDDA98A, 0xB98468, 0x3E8F3A, 0xB06458},
                {0x1E1E24, 0x8B5A3C, 0x6E4228, 0x2B2B2B, 0x5A3320},
                {0xE0B84C, 0xF0C7A4, 0xC99C7C, 0x2F6FB8, 0xC4766A},
                {0x7B3F9E, 0xC68E6C, 0xA0704F, 0x1BD96A, 0x8E4B45},
                {0xB8402A, 0xE8B596, 0xBF8A6D, 0x6B4A2A, 0xB5655A}
        };
        int v = Math.floorMod(skin, SKINS);
        String[] rows = (v == 1 || v == 3 || v == 5) ? alex : steve;
        int[] c = pal[v];
        int cell = Math.max(1, sz / 8);
        for (int r = 0; r < 8; r++)
            for (int k = 0; k < 8; k++) {
                Color col;
                switch (rows[r].charAt(k)) {
                    case 'H': col = new Color(c[0]); break;
                    case 'W': col = Color.WHITE; break;
                    case 'E': col = new Color(c[3]); break;
                    case 'M': col = new Color(c[4]); break;
                    case 'D': col = new Color(c[2]); break;
                    default: col = new Color(c[1]);
                }
                g.setColor(col);
                g.fillRect(x + k * cell, y + r * cell, cell, cell);
            }
    }

    static void headRound(Graphics2D g0, int x, int y, int sz, int arc) { headRound(g0, x, y, sz, arc, headImg); }

    static void headRound(Graphics2D g0, int x, int y, int sz, int arc, BufferedImage real) {
        BufferedImage im = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        if (real != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(real, 0, 0, sz, sz, null);
        } else head(g, 0, 0, sz);
        g.dispose();
        BufferedImage out = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = out.createGraphics();
        aa(mg);
        mg.setColor(Color.WHITE);
        mg.fillRoundRect(0, 0, sz, sz, arc, arc);
        mg.setComposite(AlphaComposite.SrcIn);
        mg.drawImage(im, 0, 0, null);
        mg.dispose();
        g0.drawImage(out, x, y, null);
    }

    static void railIcon(Graphics2D g0, int kind, int cx, int cy, Color c, double scale) {
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(cx, cy);
        g.scale(scale, scale);
        g.setColor(c);
        g.setStroke(new BasicStroke((float) (2.0 / Math.sqrt(scale)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case 0: {
                Path2D.Float roof = new Path2D.Float();
                roof.moveTo(-10, 0); roof.lineTo(0, -9); roof.lineTo(10, 0);
                g.draw(roof);
                Path2D.Float body = new Path2D.Float();
                body.moveTo(-7, -3); body.lineTo(-7, 9); body.lineTo(7, 9); body.lineTo(7, -3);
                g.draw(body);
                Path2D.Float door = new Path2D.Float();
                door.moveTo(-2.5f, 9); door.lineTo(-2.5f, 3); door.lineTo(2.5f, 3); door.lineTo(2.5f, 9);
                g.draw(door);
                break;
            }
            case 1: {
                g.drawOval(-10, -10, 20, 20);
                Path2D.Float d = new Path2D.Float();
                d.moveTo(5, -5); d.lineTo(1.5f, 1.5f); d.lineTo(-5, 5); d.lineTo(-1.5f, -1.5f); d.closePath();
                g.draw(d);
                break;
            }
            case 2: {
                g.drawOval(-6, -6, 12, 12);
                g.drawOval(-2, -2, 4, 4);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    g.draw(new java.awt.geom.Line2D.Double(Math.cos(a) * 6.5, Math.sin(a) * 6.5, Math.cos(a) * 10.5, Math.sin(a) * 10.5));
                }
                break;
            }
            case 3: {
                g.drawRoundRect(-11, -9, 22, 18, 6, 6);
                g.drawPolyline(new int[]{-6, -2, -6}, new int[]{-3, 0, 3}, 3);
                g.drawLine(1, 4, 6, 4);
                break;
            }
            case 4:
                g.drawLine(-9, 0, 9, 0);
                g.drawLine(0, -9, 0, 9);
                break;
            case 6: {
                g.drawRoundRect(-10, -8, 20, 16, 5, 5);
                g.fillOval(3, -5, 4, 4);
                Path2D.Float mt = new Path2D.Float();
                mt.moveTo(-10, 6); mt.lineTo(-4, -1); mt.lineTo(1, 4); mt.lineTo(4, 1); mt.lineTo(10, 7);
                g.draw(mt);
                break;
            }
            default: {
                Path2D.Float f = new Path2D.Float();
                f.moveTo(-10, -5); f.lineTo(-10, -8); f.lineTo(-3, -8); f.lineTo(-1, -5); f.lineTo(10, -5);
                f.lineTo(10, 8); f.lineTo(-10, 8); f.closePath();
                g.draw(f);
            }
        }
        g.dispose();
    }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
}
