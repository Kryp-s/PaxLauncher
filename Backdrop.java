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

final class Backdrop extends JPanel {
    static final int SIDE_DUR = 480;
    int arc = 10;
    boolean frozen;
    boolean side = true;
    private float sideP = 1f, fromP = 1f, toP = 1f;
    private long elapsed, last;
    private final javax.swing.Timer sideAnim = new javax.swing.Timer(8, e -> sideStep());

    Backdrop(LayoutManager lm) { super(lm); setOpaque(true); setBackground(Theme.BG2); }

    void setSide(boolean s) {
        side = s;
        float to = s ? 1f : 0f;
        if (!isShowing()) { sideAnim.stop(); sideP = fromP = toP = to; repaint(); return; }
        fromP = sideP;
        toP = to;
        elapsed = 0;
        last = System.nanoTime();
        sideAnim.start();
    }

    private void sideStep() {
        long now = System.nanoTime();
        elapsed += Math.min(now - last, 34_000_000L);
        last = now;
        float p = Math.min(1f, elapsed / (SIDE_DUR * 1e6f));
        float e = p < 0.5f ? 4f * p * p * p : 1f - (float) Math.pow(-2f * p + 2f, 3) / 2f;
        sideP = fromP + (toP - fromP) * e;
        if (p >= 1f) { sideP = toP; sideAnim.stop(); }
        repaint();
    }

    @Override public void doLayout() { if (!frozen) super.doLayout(); }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        g.setColor(Theme.BG1);
        g.fillRect(0, 0, w, h);
        g.setColor(Theme.BG2);
        float p = sideP;
        int x = Math.round(Theme.SIDE_W * p), y = Theme.TITLE_H;
        int rw = Math.round(w - x + 30 * p), rh = Math.round(h - y + 30 * p), ra = Math.round(24 * p);
        g.fillRoundRect(x, y, rw, rh, ra, ra);
        g.setColor(Theme.ov(28));
        if (arc > 0) g.drawRoundRect(0, 0, w - 1, h - 1, arc * 2, arc * 2);
        else g.drawRect(0, 0, w - 1, h - 1);
        g.dispose();
    }
}
