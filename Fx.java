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

final class Fx {
    private final JComponent c;
    private float hov, prs, sel, tHov, tPrs, tSel;
    private final javax.swing.Timer t;

    Fx(JComponent c) {
        this.c = c;
        t = new javax.swing.Timer(15, e -> step());
    }

    private void go() { if (!t.isRunning()) t.start(); }

    void hover(boolean b) { tHov = b ? 1 : 0; go(); }
    void press(boolean b) { tPrs = b ? 1 : 0; go(); }
    void sel(boolean b, boolean instant) { tSel = b ? 1 : 0; if (instant) sel = tSel; go(); }
    float h() { return hov; }
    float p() { return prs; }
    float s() { return sel; }

    private static float ease(float cur, float tgt, float k) {
        float d = tgt - cur;
        return Math.abs(d) < 0.01f ? tgt : cur + d * k;
    }

    private void step() {
        hov = ease(hov, tHov, 0.26f);
        prs = ease(prs, tPrs, 0.40f);
        sel = ease(sel, tSel, 0.22f);
        c.repaint();
        if (hov == tHov && prs == tPrs && sel == tSel) t.stop();
    }

    static Color mix(Color a, Color b, float f) {
        f = Math.max(0, Math.min(1, f));
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * f),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * f),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * f));
    }

    static Color alpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(c.getAlpha() * a))));
    }

    static void squeeze(Graphics2D g, int w, int h, float press) {
        double sc = 1 - 0.04 * press;
        g.translate(w / 2.0, h / 2.0);
        g.scale(sc, sc);
        g.translate(-w / 2.0, -h / 2.0);
    }
}
