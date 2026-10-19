import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Іконки збірок: набір власних намальованих іконок (без зовнішніх файлів) + можливість поставити свою картинку.
 * Вибір зберігається у profile.properties: icon=&lt;id&gt; або icon=custom (тоді картинка лежить у icon.png в папці збірки).
 */
final class BuildIcons {
    private BuildIcons() { }

    static final String CUSTOM = "custom";
    static final String[] IDS = {
            "cube", "pickaxe", "sword", "shield", "heart", "star", "bolt", "flame", "tree", "gem", "crown",
            "skull", "ghost", "rocket", "planet", "moon", "sun", "cloud", "snow", "anvil", "potion", "compass",
            "map", "key", "fish", "paw", "trophy", "gear", "book", "house", "pixel", "target", "note", "wave",
            "mountain", "tower", "clover", "robot", "hammer", "torch", "eye", "anchor"};

    static boolean valid(String id) { return Arrays.asList(IDS).contains(id); }

    /** Значок за замовчуванням: стабільно різний для різних назв, щоб бібліотека не була однотипною. */
    static String defaultId(String name) { return IDS[Math.floorMod(name == null ? 0 : name.hashCode(), IDS.length)]; }

    private static Color[] pair(int i) {
        float h = (float) ((i * 0.381966) % 1.0);
        return new Color[]{Color.getHSBColor(h, 0.58f, 1.0f), Color.getHSBColor((h + 0.06f) % 1f, 0.82f, 0.70f)};
    }

    // ---------- малювання ----------

    private static void tile(Graphics2D g, int s, Color c1, Color c2) {
        RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, s, s, s * 0.46f, s * 0.46f);
        g.setPaint(new GradientPaint(0, 0, c1, s, s, c2));
        g.fill(shape);
        Shape old = g.getClip();
        g.clip(shape);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(s * 0.28f, s * 0.12f), s * 0.8f, new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 72), new Color(255, 255, 255, 0)}));
        g.fill(shape);
        g.setClip(old);
        g.setColor(new Color(255, 255, 255, 46));
        g.setStroke(new BasicStroke(Math.max(1f, s / 48f)));
        g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, s - 1f, s - 1f, s * 0.46f, s * 0.46f));
    }

    /** Малює вбудовану іконку у квадрат s×s з верхнім лівим кутом (x, y). */
    static void paint(Graphics2D g0, String id, int x, int y, int s) {
        int idx = Math.max(0, Arrays.asList(IDS).indexOf(id));
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(x, y);
        Color[] c = pair(idx);
        tile(g, s, c[0], c[1]);
        g.translate(s / 2.0, s / 2.0);
        double k = s / 56.0;
        g.scale(k, k);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        glyph(g, id);
        g.dispose();
    }

    private static final Color SH = new Color(0, 0, 0, 70);
    private static final Color W70 = new Color(255, 255, 255, 178);
    private static final Color W45 = new Color(255, 255, 255, 115);

    private static Path2D.Float poly(float... p) {
        Path2D.Float r = new Path2D.Float();
        r.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) r.lineTo(p[i], p[i + 1]);
        r.closePath();
        return r;
    }

    private static void line(Graphics2D g, float x1, float y1, float x2, float y2) { g.draw(new Line2D.Float(x1, y1, x2, y2)); }

    private static void glyph(Graphics2D g, String id) {
        switch (id) {
            case "cube": {
                g.fill(poly(0, -21, 19, -10.5f, 0, 0, -19, -10.5f));
                g.setColor(W70); g.fill(poly(-19, -10.5f, 0, 0, 0, 21, -19, 10.5f));
                g.setColor(W45); g.fill(poly(19, -10.5f, 0, 0, 0, 21, 19, 10.5f));
                break;
            }
            case "pickaxe": {
                g.setStroke(new BasicStroke(4.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                line(g, -13, 21, 9, -14);
                Path2D.Float h = new Path2D.Float();
                h.moveTo(-9, -15); h.quadTo(12, -25, 21, -2);
                g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(h);
                break;
            }
            case "sword": {
                g.setStroke(new BasicStroke(5.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                line(g, -4, 4, 17, -17);
                g.setStroke(new BasicStroke(4.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                line(g, -12, -4, 4, 12);
                line(g, -8, 8, -17, 17);
                g.fill(new Ellipse2D.Float(-21, 13, 8, 8));
                break;
            }
            case "shield": {
                Path2D.Float s = new Path2D.Float();
                s.moveTo(0, -21); s.lineTo(18, -14); s.lineTo(18, 2); s.quadTo(18, 14, 0, 22);
                s.quadTo(-18, 14, -18, 2); s.lineTo(-18, -14); s.closePath();
                g.fill(s);
                Shape old = g.getClip();
                g.clip(s);
                g.setColor(SH); g.fill(new Rectangle2D.Float(0, -24, 24, 48));
                g.setClip(old);
                break;
            }
            case "heart": {
                Path2D.Float h = new Path2D.Float();
                h.moveTo(0, 19); h.curveTo(-27, 1, -17, -21, 0, -9); h.curveTo(17, -21, 27, 1, 0, 19);
                g.fill(h);
                break;
            }
            case "star": {
                Path2D.Float s = new Path2D.Float();
                for (int i = 0; i < 10; i++) {
                    double r = i % 2 == 0 ? 22 : 9.2, a = -Math.PI / 2 + i * Math.PI / 5;
                    float px = (float) (Math.cos(a) * r), py = (float) (Math.sin(a) * r + 1.5);
                    if (i == 0) s.moveTo(px, py); else s.lineTo(px, py);
                }
                s.closePath();
                g.fill(s);
                break;
            }
            case "bolt": g.fill(poly(5, -23, -13, 4, -1, 4, -6, 23, 14, -6, 2, -6)); break;
            case "flame": {
                Path2D.Float f = new Path2D.Float();
                f.moveTo(1, -23); f.curveTo(4, -11, 17, -8, 15, 6); f.curveTo(14, 17, 7, 23, 0, 23);
                f.curveTo(-9, 23, -16, 16, -14, 6); f.curveTo(-13, 0, -8, -3, -7, -10); f.curveTo(-3, -7, -1, -4, 1, -23);
                g.fill(f);
                Path2D.Float in = new Path2D.Float();
                in.moveTo(1, 4); in.curveTo(6, 9, 8, 13, 5, 17); in.curveTo(3, 20, -3, 20, -5, 16); in.curveTo(-7, 11, -1, 9, 1, 4);
                g.setColor(SH); g.fill(in);
                break;
            }
            case "tree": {
                g.fill(poly(0, -23, 12, -7, -12, -7));
                g.fill(poly(0, -15, 15, 3, -15, 3));
                g.fill(poly(0, -6, 18, 12, -18, 12));
                g.setColor(W70); g.fill(new Rectangle2D.Float(-3.5f, 12, 7, 11));
                break;
            }
            case "gem": {
                g.fill(poly(-10, -15, 10, -15, 21, -4, 0, 21, -21, -4));
                g.setColor(SH);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                line(g, -21, -4, 21, -4);
                Path2D.Float a = new Path2D.Float(); a.moveTo(-10, -15); a.lineTo(-6, -4); a.lineTo(0, 21); g.draw(a);
                Path2D.Float b = new Path2D.Float(); b.moveTo(10, -15); b.lineTo(6, -4); b.lineTo(0, 21); g.draw(b);
                break;
            }
            case "crown": {
                g.fill(poly(-19, 14, -21, -11, -10, 0, 0, -17, 10, 0, 21, -11, 19, 14));
                g.setColor(SH); g.fill(new Rectangle2D.Float(-19, 9, 38, 5));
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Float(-24, -15, 7, 7)); g.fill(new Ellipse2D.Float(-3.5f, -22, 7, 7)); g.fill(new Ellipse2D.Float(17, -15, 7, 7));
                break;
            }
            case "skull": {
                g.fill(new RoundRectangle2D.Float(-16, -20, 32, 28, 26, 26));
                g.fill(new RoundRectangle2D.Float(-9.5f, 2, 19, 18, 6, 6));
                g.setColor(SH);
                g.fill(new Ellipse2D.Float(-11, -8, 9, 10)); g.fill(new Ellipse2D.Float(2, -8, 9, 10));
                g.fill(poly(0, 3, -2.5f, 8, 2.5f, 8));
                g.setStroke(new BasicStroke(1.8f));
                line(g, -3.2f, 13, -3.2f, 20); line(g, 3.2f, 13, 3.2f, 20);
                break;
            }
            case "ghost": {
                Path2D.Float s = new Path2D.Float();
                s.moveTo(-16, 22); s.lineTo(-16, -3); s.curveTo(-16, -27, 16, -27, 16, -3); s.lineTo(16, 22);
                s.lineTo(8, 15); s.lineTo(0, 22); s.lineTo(-8, 15); s.closePath();
                g.fill(s);
                g.setColor(SH); g.fill(new Ellipse2D.Float(-9, -7, 6.5f, 9)); g.fill(new Ellipse2D.Float(2.5f, -7, 6.5f, 9));
                break;
            }
            case "rocket": {
                Path2D.Float b = new Path2D.Float();
                b.moveTo(0, -24); b.curveTo(14, -13, 13, 5, 8, 14); b.lineTo(-8, 14); b.curveTo(-13, 5, -14, -13, 0, -24);
                g.fill(b);
                g.setColor(W70);
                g.fill(poly(-8, 4, -19, 19, -8, 14)); g.fill(poly(8, 4, 19, 19, 8, 14));
                g.setColor(SH); g.fill(new Ellipse2D.Float(-5, -9, 10, 10));
                g.setColor(W45); g.fill(poly(-4.5f, 16, 0, 25, 4.5f, 16));
                break;
            }
            case "planet": {
                g.fill(new Ellipse2D.Float(-12, -12, 24, 24));
                Graphics2D r = (Graphics2D) g.create();
                r.rotate(-0.38);
                r.setStroke(new BasicStroke(3.4f));
                r.setColor(W70);
                r.draw(new Ellipse2D.Float(-24, -7, 48, 14));
                r.dispose();
                g.setColor(SH);
                Shape old = g.getClip();
                g.clip(new Ellipse2D.Float(-12, -12, 24, 24));
                g.fill(new Ellipse2D.Float(-2, -8, 24, 24));
                g.setClip(old);
                break;
            }
            case "moon": {
                Area a = new Area(new Ellipse2D.Float(-18, -18, 36, 36));
                a.subtract(new Area(new Ellipse2D.Float(-6, -24, 32, 32)));
                g.fill(a);
                g.fill(new Ellipse2D.Float(12, 8, 5, 5));
                g.fill(new Ellipse2D.Float(7, 15, 3.4f, 3.4f));
                break;
            }
            case "sun": {
                g.fill(new Ellipse2D.Float(-9.5f, -9.5f, 19, 19));
                g.setStroke(new BasicStroke(3.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    line(g, (float) (Math.cos(a) * 15), (float) (Math.sin(a) * 15), (float) (Math.cos(a) * 22), (float) (Math.sin(a) * 22));
                }
                break;
            }
            case "cloud": {
                Area a = new Area(new Ellipse2D.Float(-23, -1, 20, 20));
                a.add(new Area(new Ellipse2D.Float(-12, -17, 25, 25)));
                a.add(new Area(new Ellipse2D.Float(3, -9, 22, 22)));
                a.add(new Area(new RoundRectangle2D.Float(-13, 6, 26, 13, 12, 12)));
                g.fill(a);
                break;
            }
            case "snow": {
                g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 6; i++) {
                    Graphics2D r = (Graphics2D) g.create();
                    r.rotate(i * Math.PI / 3);
                    line(r, 0, 0, 0, -22);
                    line(r, 0, -14, -5.5f, -19.5f);
                    line(r, 0, -14, 5.5f, -19.5f);
                    r.dispose();
                }
                break;
            }
            case "anvil": {
                g.fill(poly(-22, -12, 17, -12, 22, -9, 15, -2, 7, 1, 7, 8, 15, 15, -15, 15, -7, 8, -7, 1, -15, -2));
                g.setColor(W70); g.fill(new RoundRectangle2D.Float(-17, 15, 34, 6, 3, 3));
                break;
            }
            case "potion": {
                Area a = new Area(new Ellipse2D.Float(-15, -5, 30, 30));
                a.add(new Area(new Rectangle2D.Float(-5.5f, -19, 11, 17)));
                g.fill(a);
                Shape old = g.getClip();
                g.clip(a);
                g.setColor(SH); g.fill(new Rectangle2D.Float(-20, 9, 40, 20));
                g.setClip(old);
                g.setColor(W70); g.fill(new RoundRectangle2D.Float(-7.5f, -24, 15, 6, 3, 3));
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Float(-6, 13, 4, 4)); g.fill(new Ellipse2D.Float(2, 17, 3, 3));
                break;
            }
            case "compass": {
                g.setStroke(new BasicStroke(3.4f));
                g.draw(new Ellipse2D.Float(-20, -20, 40, 40));
                Graphics2D r = (Graphics2D) g.create();
                r.rotate(Math.PI / 4);
                r.fill(poly(0, -14, 5.5f, 0, -5.5f, 0));
                r.setColor(W45);
                r.fill(poly(0, 14, 5.5f, 0, -5.5f, 0));
                r.dispose();
                g.setColor(SH); g.fill(new Ellipse2D.Float(-2.2f, -2.2f, 4.4f, 4.4f));
                break;
            }
            case "map": {
                g.fill(poly(-22, -14, -7.5f, -10, 7.5f, -14, 22, -10, 22, 15, 7.5f, 19, -7.5f, 15, -22, 19));
                g.setColor(SH);
                g.setStroke(new BasicStroke(2f));
                line(g, -7.5f, -10, -7.5f, 15); line(g, 7.5f, -14, 7.5f, 19);
                g.fill(new Ellipse2D.Float(9.5f, -4, 6, 6));
                break;
            }
            case "key": {
                g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Ellipse2D.Float(-20, -20, 17, 17));
                line(g, -6, -6, 18, 18);
                g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                line(g, 10, 10, 15, 5); line(g, 16, 16, 21, 11);
                break;
            }
            case "fish": {
                g.fill(new Ellipse2D.Float(-22, -10, 32, 20));
                g.fill(poly(7, 0, 22, -11, 22, 11));
                g.setColor(SH); g.fill(new Ellipse2D.Float(-16, -4, 5, 5));
                break;
            }
            case "paw": {
                g.fill(new Ellipse2D.Float(-10, 1, 20, 18));
                g.fill(new Ellipse2D.Float(-20, -9, 8, 11)); g.fill(new Ellipse2D.Float(-10, -18, 8.5f, 12));
                g.fill(new Ellipse2D.Float(1.5f, -18, 8.5f, 12)); g.fill(new Ellipse2D.Float(12, -9, 8, 11));
                break;
            }
            case "trophy": {
                g.setStroke(new BasicStroke(3.2f));
                g.draw(new Ellipse2D.Float(-22, -16, 12, 14)); g.draw(new Ellipse2D.Float(10, -16, 12, 14));
                Path2D.Float c = new Path2D.Float();
                c.moveTo(-13, -20); c.lineTo(13, -20); c.lineTo(11, -3); c.quadTo(9, 6, 0, 7); c.quadTo(-9, 6, -11, -3); c.closePath();
                g.fill(c);
                g.fill(new Rectangle2D.Float(-3, 6, 6, 8));
                g.setColor(W70); g.fill(new RoundRectangle2D.Float(-11, 14, 22, 7, 3, 3));
                break;
            }
            case "gear": {
                Area a = new Area(new Ellipse2D.Float(-14, -14, 28, 28));
                for (int i = 0; i < 8; i++) {
                    Path2D.Float t = new Path2D.Float(new RoundRectangle2D.Float(-4, -23, 8, 11, 2, 2));
                    t.transform(AffineTransform.getRotateInstance(i * Math.PI / 4));
                    a.add(new Area(t));
                }
                a.subtract(new Area(new Ellipse2D.Float(-6, -6, 12, 12)));
                g.fill(a);
                break;
            }
            case "book": {
                g.fill(poly(-22, -13, -2, -8, -2, 17, -22, 12));
                g.fill(poly(22, -13, 2, -8, 2, 17, 22, 12));
                g.setColor(SH);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 3; i++) { line(g, -17, -5 + i * 6, -7, -3 + i * 6); line(g, 17, -5 + i * 6, 7, -3 + i * 6); }
                break;
            }
            case "house": {
                g.fill(poly(0, -23, 24, -1, -24, -1));
                g.setColor(W70); g.fill(new Rectangle2D.Float(-15, -1, 30, 22));
                g.setColor(SH); g.fill(new RoundRectangle2D.Float(-4.5f, 7, 9, 14, 3, 3));
                break;
            }
            case "pixel": {
                g.fill(new Rectangle2D.Float(-20, -20, 40, 40));
                g.setColor(SH);
                g.fill(new Rectangle2D.Float(-14, -12, 9, 9)); g.fill(new Rectangle2D.Float(5, -12, 9, 9));
                g.fill(new Rectangle2D.Float(-10, 6, 20, 5));
                g.setColor(Color.WHITE);
                g.fill(new Rectangle2D.Float(-5, 6, 4, 5)); g.fill(new Rectangle2D.Float(1, 6, 4, 5));
                break;
            }
            case "target": {
                g.setStroke(new BasicStroke(3.4f));
                g.draw(new Ellipse2D.Float(-20, -20, 40, 40));
                g.draw(new Ellipse2D.Float(-11.5f, -11.5f, 23, 23));
                g.fill(new Ellipse2D.Float(-4.5f, -4.5f, 9, 9));
                break;
            }
            case "note": {
                g.fill(new Ellipse2D.Float(-20, 8, 14, 11)); g.fill(new Ellipse2D.Float(2, 4, 14, 11));
                g.setStroke(new BasicStroke(3.2f));
                line(g, -7, 13, -7, -14); line(g, 15, 9, 15, -18);
                g.fill(poly(-7, -17, 15, -22, 15, -13, -7, -8));
                break;
            }
            case "wave": {
                g.setStroke(new BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int row = 0; row < 3; row++) {
                    Path2D.Float p = new Path2D.Float();
                    for (int i = 0; i <= 40; i++) {
                        float x = -21 + i * 1.05f, y = -11 + row * 11 + (float) Math.sin(i * 0.33 + row) * 4.2f;
                        if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                    }
                    g.draw(p);
                }
                break;
            }
            case "mountain": {
                g.setColor(W45); g.fill(poly(2, 19, 13, -8, 25, 19));
                g.setColor(Color.WHITE); g.fill(poly(-24, 19, -7, -12, 3, 3, 8, -4, 21, 19));
                g.setColor(SH); g.fill(poly(-7, -12, -12.5f, -2, -7.5f, -5, -3, 0));
                break;
            }
            case "tower": {
                g.fill(new Rectangle2D.Float(-14, -3, 28, 24));
                g.fill(new Rectangle2D.Float(-14, -13, 7, 10)); g.fill(new Rectangle2D.Float(-3.5f, -13, 7, 10)); g.fill(new Rectangle2D.Float(7, -13, 7, 10));
                g.setColor(SH);
                Path2D.Float d = new Path2D.Float(new Rectangle2D.Float(-4.5f, 9, 9, 12));
                g.fill(d);
                g.fill(new RoundRectangle2D.Float(-2, 0, 4, 6, 2, 2));
                break;
            }
            case "clover": {
                g.fill(new Ellipse2D.Float(-17, -17, 17, 17)); g.fill(new Ellipse2D.Float(0, -17, 17, 17));
                g.fill(new Ellipse2D.Float(-17, 0, 17, 17)); g.fill(new Ellipse2D.Float(0, 0, 17, 17));
                g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D.Float s = new Path2D.Float(); s.moveTo(0, 10); s.quadTo(3, 20, 10, 23); g.draw(s);
                break;
            }
            case "robot": {
                g.fill(new RoundRectangle2D.Float(-16, -10, 32, 29, 10, 10));
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                line(g, 0, -10, 0, -18);
                g.fill(new Ellipse2D.Float(-3.5f, -24, 7, 7));
                g.fill(new RoundRectangle2D.Float(-21, 0, 5, 11, 3, 3)); g.fill(new RoundRectangle2D.Float(16, 0, 5, 11, 3, 3));
                g.setColor(SH);
                g.fill(new Ellipse2D.Float(-11, -3, 8, 8)); g.fill(new Ellipse2D.Float(3, -3, 8, 8));
                g.fill(new RoundRectangle2D.Float(-8, 11, 16, 3.4f, 2, 2));
                break;
            }
            case "hammer": {
                g.setStroke(new BasicStroke(4.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                line(g, -15, 18, 6, -3);
                Graphics2D r = (Graphics2D) g.create();
                r.translate(9, -9);
                r.rotate(Math.PI / 4);
                r.fill(new RoundRectangle2D.Float(-15, -7, 30, 14, 4, 4));
                r.dispose();
                break;
            }
            case "torch": {
                g.fill(poly(-3.5f, 0, 3.5f, 0, 3.5f, 23, -3.5f, 23));
                g.setColor(W70); g.fill(new RoundRectangle2D.Float(-7, -4, 14, 8, 3, 3));
                g.setColor(Color.WHITE);
                Path2D.Float f = new Path2D.Float();
                f.moveTo(0, -24); f.curveTo(3, -17, 10, -14, 8, -8); f.curveTo(7, -5, 3, -4, 0, -4);
                f.curveTo(-4, -4, -8, -6, -8, -10); f.curveTo(-8, -14, -3, -16, 0, -24);
                g.fill(f);
                break;
            }
            case "eye": {
                Path2D.Float e = new Path2D.Float();
                e.moveTo(-24, 0); e.quadTo(0, -25, 24, 0); e.quadTo(0, 25, -24, 0);
                g.fill(e);
                g.setColor(SH); g.fill(new Ellipse2D.Float(-8.5f, -8.5f, 17, 17));
                g.setColor(Color.WHITE); g.fill(new Ellipse2D.Float(-4, -5, 5, 5));
                break;
            }
            case "anchor": {
                g.setStroke(new BasicStroke(3.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Ellipse2D.Float(-4.5f, -22, 9, 9));
                line(g, 0, -13, 0, 19);
                line(g, -9, -5, 9, -5);
                g.draw(new Arc2D.Float(-17, -9, 34, 30, 180, 180, Arc2D.OPEN));
                line(g, -17, 6, -21, 1); line(g, 17, 6, 21, 1);
                break;
            }
            default: g.fill(new Ellipse2D.Float(-16, -16, 32, 32));
        }
    }

    static BufferedImage render(String id, int s) {
        BufferedImage im = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        paint(g, id, 0, 0, s);
        g.dispose();
        return im;
    }

    // ---------- власна картинка ----------

    /** Вирізає центральний квадрат, зменшує до s×s і заокруглює кути так само, як у вбудованих іконок. */
    static BufferedImage fit(BufferedImage src, int s) {
        int w = src.getWidth(), h = src.getHeight(), m = Math.min(w, h);
        BufferedImage sq = src.getSubimage((w - m) / 2, (h - m) / 2, m, m);
        BufferedImage sm = ImgIcon.smooth(sq, s, s);
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setClip(new RoundRectangle2D.Float(0, 0, s, s, s * 0.46f, s * 0.46f));
        g.drawImage(sm, 0, 0, s, s, null);
        g.dispose();
        return out;
    }

    static BufferedImage readImage(File f) throws IOException {
        if (f.length() > 20L << 20) throw new IOException("файл завеликий");
        BufferedImage im = ImageIO.read(f);
        if (im == null) throw new IOException("це не зображення");
        return im;
    }

    // ---------- збереження у збірці ----------

    private static Properties props(Path inst) {
        Properties pr = new Properties();
        try (Reader r = Files.newBufferedReader(inst.resolve("profile.properties"), StandardCharsets.UTF_8)) { pr.load(r); } catch (IOException ignored) { }
        return pr;
    }

    /** Що зараз обрано: id вбудованої іконки, CUSTOM або порожньо (тоді береться значок за замовчуванням). */
    static String choice(Path inst) { return props(inst).getProperty("icon", ""); }

    static void save(Path inst, String id, BufferedImage custom) throws IOException {
        Files.createDirectories(inst);
        Path png = inst.resolve("icon.png");
        Properties pr = props(inst);
        if (custom != null) {
            ImageIO.write(fit(custom, 256), "png", png.toFile());
            pr.setProperty("icon", CUSTOM);
        } else {
            Files.deleteIfExists(png);
            pr.setProperty("icon", valid(id) ? id : defaultId(inst.getFileName().toString()));
        }
        try (Writer w = Files.newBufferedWriter(inst.resolve("profile.properties"), StandardCharsets.UTF_8)) { pr.store(w, "PaxLauncher profile"); }
    }

    /** Готова іконка збірки розміром s×s пікселів. */
    static BufferedImage load(Path inst, int s) {
        String c = choice(inst);
        if (CUSTOM.equals(c)) {
            try {
                BufferedImage im = ImageIO.read(inst.resolve("icon.png").toFile());
                if (im != null) return fit(im, s);
            } catch (IOException ignored) { }
        }
        String id = valid(c) ? c : defaultId(inst.getFileName().toString());
        return render(id, s);
    }
}
