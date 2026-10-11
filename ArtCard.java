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

final class ArtCard extends JComponent {
    static final int H = 266, IMG_H = 150;
    private final int art;
    private final double focus;
    private final String title, desc;
    private final Fx fx = new Fx(this);
    private BufferedImage masked;
    private int maskW;

    ArtCard(int art, double focus, String title, String desc, Runnable click) {
        this.art = art;
        this.focus = focus;
        this.title = title;
        this.desc = desc;
        setPreferredSize(new Dimension(300, H));
        setMinimumSize(new Dimension(180, H));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); }
            @Override public void mouseClicked(MouseEvent e) { click.run(); }
        });
    }

    static BufferedImage topRounded(BufferedImage im, int w, int h, double focus) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        Theme.aa(ig);
        Art.cover(ig, im, 0, 0, w, h, focus);
        ig.dispose();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = out.createGraphics();
        Theme.aa(mg);
        mg.setColor(Color.WHITE);
        mg.fillRoundRect(0, 0, w, h + 24, 16, 16);
        mg.setComposite(AlphaComposite.SrcIn);
        mg.drawImage(img, 0, 0, null);
        mg.dispose();
        return out;
    }

    static List<String> wrap(String s, FontMetrics fm, int maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        String cur = "";
        String[] words = s.split(" ");
        boolean cut = false;
        for (String wd : words) {
            String t = cur.isEmpty() ? wd : cur + " " + wd;
            if (cur.isEmpty() || fm.stringWidth(t) <= maxW) { cur = t; continue; }
            out.add(cur);
            cur = wd;
            if (out.size() == maxLines) { cut = true; break; }
        }
        if (!cut && !cur.isEmpty()) out.add(cur);
        if (cut) {
            String last = out.get(out.size() - 1);
            while (!last.isEmpty() && fm.stringWidth(last + "…") > maxW) last = last.substring(0, last.length() - 1);
            out.set(out.size() - 1, last + "…");
        }
        return out;
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        if (w <= 0) { g.dispose(); return; }
        if (masked == null || maskW != w) {
            masked = topRounded(Art.get(art), w, IMG_H, focus);
            maskW = w;
        }
        g.setColor(Theme.CARD);
        g.fillRoundRect(0, 0, w, h, 16, 16);
        g.drawImage(masked, 0, 0, null);
        if (fx.h() > 0) {
            g.setColor(Theme.ov(Math.round(12 * fx.h())));
            g.fillRoundRect(0, 0, w, h, 16, 16);
            g.setColor(Theme.acc(Math.round(170 * fx.h())));
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(1, 1, w - 3, h - 3, 16, 16);
        }
        int pad = 16, y = IMG_H + 30;
        g.setFont(Theme.font(Font.BOLD, 16));
        g.setColor(Theme.TEXT);
        g.drawString(title, pad, y);
        g.setFont(Theme.font(Font.PLAIN, 13));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Theme.MUTED);
        int ly = y + 10 + fm.getAscent();
        for (String line : wrap(desc, fm, w - 2 * pad, 3)) {
            g.drawString(line, pad, ly);
            ly += fm.getHeight() + 1;
        }
        g.dispose();
    }
}
