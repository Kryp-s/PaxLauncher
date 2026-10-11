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

final class Bar extends JComponent {
    private double v, tv;
    private final javax.swing.Timer tm = new javax.swing.Timer(15, e -> {
        double d = tv - v;
        if (Math.abs(d) < 0.002) { v = tv; ((javax.swing.Timer) e.getSource()).stop(); }
        else v += d * 0.2;
        repaint();
    });

    Bar() {
        setOpaque(false);
        setPreferredSize(new Dimension(100, 10));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
        setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private int gen;

    void set(double d) {
        gen++;
        tv = Math.max(0, Math.min(1, d));
        if (tv < v) v = tv;
        tm.start();
    }

    void done() {
        set(1);
        final int g = gen;
        javax.swing.Timer t = new javax.swing.Timer(700, e -> { if (g == gen) set(0); });
        t.setRepeats(false);
        t.start();
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        g.setColor(Theme.INPUT);
        g.fillRoundRect(0, 0, w, h, h, h);
        int fw = (int) (w * v);
        if (fw > 0) {
            g.setPaint(new GradientPaint(0, 0, Theme.ACC1, w, 0, Theme.ACC2));
            g.fillRoundRect(0, 0, Math.max(fw, h), h, h, h);
        }
        g.dispose();
    }
}
