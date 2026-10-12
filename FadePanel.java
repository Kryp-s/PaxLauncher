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
    private static final int DUR_MS = 300;   // тривалість переходу між розділами
    private static final int SLIDE = 18;     // наскільки нова сторінка «в'їжджає» знизу, px
    private BufferedImage oldImg, newImg;
    private boolean anim;
    private long t0;
    // Анімація залежить від часу, а не від кількості кадрів: однаково плавна і на слабкому, і на швидкому ПК.
    private final javax.swing.Timer t = new javax.swing.Timer(8, e -> {
        if ((System.nanoTime() - t0) >= DUR_MS * 1e6f) { anim = false; ((javax.swing.Timer) e.getSource()).stop(); }
        repaint();
    });

    FadePanel(LayoutManager lm) { super(lm); }

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

    void change(Runnable r) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || !isShowing()) { r.run(); return; }
        t.stop();
        anim = false;
        oldImg = fresh(oldImg, w, h);
        newImg = fresh(newImg, w, h);
        shoot(oldImg);   // знімок старої сторінки
        r.run();
        validate();
        shoot(newImg);   // знімок нової — далі анімуємо лише готові картинки, це дешево й без ривків
        anim = true;
        t0 = System.nanoTime();
        t.start();
    }

    @Override public void paint(Graphics g) {
        if (!anim) { super.paint(g); return; }
        float p = Math.min(1f, (System.nanoTime() - t0) / (DUR_MS * 1e6f));
        float e = 1f - (1f - p) * (1f - p) * (1f - p) * (1f - p);   // easeOutQuart: швидкий старт, довге м'яке гальмування
        float aOld = 1f - Math.min(1f, p / 0.6f);                    // стара сторінка зникає в першій частині руху
        aOld *= aOld;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (aOld > 0.01f) {
            g2.setComposite(AlphaComposite.SrcOver.derive(aOld));
            g2.drawImage(oldImg, 0, Math.round(-6 * e), null);
        }
        g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, e))));
        g2.drawImage(newImg, 0, Math.round(SLIDE * (1f - e)), null);
        g2.dispose();
    }
}
