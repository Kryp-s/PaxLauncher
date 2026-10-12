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

final class FadePanel extends JPanel {
    private static final long MAX_STEP = 34_000_000L;
    private final int durMs;
    private final int slide;
    private final boolean cross;
    private BufferedImage oldImg, newImg;
    private boolean anim;
    private long last, elapsed;
    boolean mute;
    private final javax.swing.Timer t;

    FadePanel(LayoutManager lm) { this(lm, 300, 18, false); }

    FadePanel(LayoutManager lm, int durMs, int slide, boolean cross) {
        super(lm);
        this.durMs = durMs;
        this.slide = slide;
        this.cross = cross;
        this.t = new javax.swing.Timer(8, e -> {
            long now = System.nanoTime();
            elapsed += Math.min(now - last, MAX_STEP);
            last = now;
            if (elapsed >= this.durMs * 1_000_000L) { anim = false; ((javax.swing.Timer) e.getSource()).stop(); }
            repaint();
        });
    }

    private BufferedImage fresh(BufferedImage old, int w, int h) {
        if (old != null && old.getWidth() == w && old.getHeight() == h) return old;
        GraphicsConfiguration gc = getGraphicsConfiguration();
        return gc != null ? gc.createCompatibleImage(w, h, Transparency.TRANSLUCENT) : new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    private void shoot(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setComposite(AlphaComposite.SrcOver);
        super.paint(g);
        g.dispose();
    }

    void warm() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || anim) return;
        oldImg = fresh(oldImg, w, h);
        newImg = fresh(newImg, w, h);
    }

    void change(Runnable r) {
        int w = getWidth(), h = getHeight();
        if (mute || w <= 0 || h <= 0 || !isShowing()) { t.stop(); anim = false; r.run(); return; }
        t.stop();
        anim = false;
        oldImg = fresh(oldImg, w, h);
        newImg = fresh(newImg, w, h);
        shoot(oldImg);
        r.run();
        validate();
        shoot(newImg);
        anim = true;
        elapsed = 0;
        last = System.nanoTime();
        t.start();
    }

    @Override public void paint(Graphics g) {
        if (!anim) { super.paint(g); return; }
        float p = Math.min(1f, elapsed / (durMs * 1e6f));
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (cross) {
            float e = p < 0.5f ? 4f * p * p * p : 1f - (float) Math.pow(-2f * p + 2f, 3) / 2f;
            float aOld = 1f - e;
            float aNew = Math.min(1f, e * 1.25f);
            g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, aOld))));
            g2.drawImage(oldImg, 0, Math.round(-slide * 0.5f * e), null);
            g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, aNew))));
            g2.drawImage(newImg, 0, Math.round(slide * (1f - e)), null);
        } else {
            float e = 1f - (1f - p) * (1f - p) * (1f - p) * (1f - p);
            float aOld = 1f - Math.min(1f, p / 0.6f);
            aOld *= aOld;
            if (aOld > 0.01f) {
                g2.setComposite(AlphaComposite.SrcOver.derive(aOld));
                g2.drawImage(oldImg, 0, Math.round(-6 * e), null);
            }
            g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, e))));
            g2.drawImage(newImg, 0, Math.round(slide * (1f - e)), null);
        }
        g2.dispose();
    }
}
