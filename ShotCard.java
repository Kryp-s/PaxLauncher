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

final class ShotCard extends JComponent {
    static final int W = 240, H = 194, IW = 228, IH = 128;
    private static final java.time.format.DateTimeFormatter FMT = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    final Shot shot;
    volatile BufferedImage thumb;
    volatile boolean dead;
    private final LauncherWindow win;
    private final String date;
    private final String copyText = I18n.t("Копіювати");
    private boolean hover, copyHover;

    static String fmtDate(long t) {
        return FMT.format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), java.time.ZoneId.systemDefault()));
    }

    ShotCard(Shot shot, LauncherWindow win) {
        this.shot = shot;
        this.win = win;
        this.date = fmtDate(shot.time());
        setPreferredSize(new Dimension(W, H));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hover = false; copyHover = false; repaint(); }
            @Override public void mouseMoved(MouseEvent e) {
                boolean c = copyRect().contains(e.getPoint());
                if (c != copyHover) { copyHover = c; repaint(); }
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (copyRect().contains(e.getPoint())) win.copyShot(shot); else win.openShot(shot);
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        win.loadThumb(this);
    }

    private Rectangle copyRect() {
        FontMetrics fm = getFontMetrics(Theme.font(Font.BOLD, 12));
        int cw = fm.stringWidth(copyText) + 22;
        return new Rectangle(W - 8 - cw, H - 32, cw, 24);
    }

    private static String ellipsize(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        g.setColor(Theme.CARD);
        g.fillRoundRect(0, 0, w, h, 16, 16);
        if (hover) {
            g.setColor(Theme.acc(120));
            g.drawRoundRect(0, 0, w - 1, h - 1, 16, 16);
        }
        Shape old = g.getClip();
        g.setClip(new RoundRectangle2D.Float(6, 6, IW, IH, 12, 12));
        BufferedImage t = thumb;
        if (t != null) g.drawImage(t, 6, 6, IW, IH, null);
        else {
            g.setColor(Theme.INPUT);
            g.fillRect(6, 6, IW, IH);
        }
        g.setClip(old);

        g.setFont(Theme.font(Font.BOLD, 13));
        g.setColor(Theme.TEXT);
        g.drawString(ellipsize(shot.label(), g.getFontMetrics(), W - 20), 12, 6 + IH + 20);

        g.setFont(Theme.font(Font.PLAIN, 12));
        g.setColor(Theme.MUTED);
        g.drawString(date, 12, H - 14);

        Rectangle cr = copyRect();
        g.setColor(copyHover ? Theme.acc(70) : Theme.ov(14));
        g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 12, 12);
        g.setFont(Theme.font(Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(copyHover ? Theme.ACC2 : Theme.TEXT);
        g.drawString(copyText, cr.x + (cr.width - fm.stringWidth(copyText)) / 2, cr.y + (cr.height - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
    }
}
