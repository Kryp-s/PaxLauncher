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
    private BufferedImage snap;
    private float alpha;
    private final javax.swing.Timer t = new javax.swing.Timer(15, e -> {
        alpha -= 0.11f;
        if (alpha <= 0) { alpha = 0; snap = null; ((javax.swing.Timer) e.getSource()).stop(); }
        repaint();
    });

    FadePanel(LayoutManager lm) { super(lm); }

    void change(Runnable r) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || !isShowing()) { r.run(); return; }
        t.stop();
        snap = null;
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        super.paint(g);
        g.dispose();
        r.run();
        snap = im;
        alpha = 1f;
        t.start();
    }

    @Override public void paint(Graphics g) {
        super.paint(g);
        if (snap != null && alpha > 0) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, alpha * alpha)));
            g2.drawImage(snap, 0, 0, null);
            g2.dispose();
        }
    }
}
