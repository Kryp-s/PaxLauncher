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

final class ColorPick extends JComponent {
    private float h, s, v;
    private int drag;
    private final Rectangle sv = new Rectangle(0, 0, 280, 170), hb = new Rectangle(0, 184, 280, 16);
    Consumer<Color> onChange;

    ColorPick(Color c) {
        set(c);
        setPreferredSize(new Dimension(280, 204));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                drag = hb.contains(e.getPoint()) ? 2 : sv.contains(e.getPoint()) ? 1 : 0;
                upd(e);
            }
            @Override public void mouseDragged(MouseEvent e) { upd(e); }
            @Override public void mouseReleased(MouseEvent e) { drag = 0; }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    void set(Color c) {
        float[] f = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        h = f[0]; s = f[1]; v = f[2];
        repaint();
    }

    Color get() { return Color.getHSBColor(h, s, v); }

    private static float cl(float x) { return Math.max(0f, Math.min(1f, x)); }

    private void upd(MouseEvent e) {
        if (drag == 1) {
            s = cl((e.getX() - sv.x) / (float) sv.width);
            v = 1f - cl((e.getY() - sv.y) / (float) sv.height);
        } else if (drag == 2) {
            h = cl((e.getX() - hb.x) / (float) hb.width);
        } else return;
        repaint();
        if (onChange != null) onChange.accept(get());
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.setClip(new RoundRectangle2D.Float(sv.x, sv.y, sv.width, sv.height, 14, 14));
        g.setColor(Color.getHSBColor(h, 1f, 1f));
        g.fill(sv);
        g.setPaint(new GradientPaint(sv.x, 0, Color.WHITE, sv.x + sv.width, 0, new Color(255, 255, 255, 0)));
        g.fill(sv);
        g.setPaint(new GradientPaint(0, sv.y, new Color(0, 0, 0, 0), 0, sv.y + sv.height, Color.BLACK));
        g.fill(sv);
        g.setClip(null);
        int kx = sv.x + Math.round(s * sv.width), ky = sv.y + Math.round((1f - v) * sv.height);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0, 0, 0, 110));
        g.drawOval(kx - 8, ky - 8, 16, 16);
        g.setColor(Color.WHITE);
        g.drawOval(kx - 7, ky - 7, 14, 14);

        g.setClip(new RoundRectangle2D.Float(hb.x, hb.y, hb.width, hb.height, 16, 16));
        for (int x = 0; x < hb.width; x++) {
            g.setColor(Color.getHSBColor(x / (float) hb.width, 1f, 1f));
            g.fillRect(hb.x + x, hb.y, 1, hb.height);
        }
        g.setClip(null);
        int hx = hb.x + Math.round(h * hb.width), hy = hb.y + hb.height / 2;
        g.setColor(new Color(0, 0, 0, 90));
        g.fillOval(hx - 9, hy - 8, 18, 18);
        g.setColor(Color.WHITE);
        g.fillOval(hx - 9, hy - 9, 18, 18);
        g.setColor(Color.getHSBColor(h, 1f, 1f));
        g.fillOval(hx - 6, hy - 6, 12, 12);
        g.dispose();
    }
}
