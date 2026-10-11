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

final class LinkBtn extends JButton {
    private final Fx fx = new Fx(this);
    private final int icon;

    LinkBtn(String text, int icon) {
        super(text);
        this.icon = icon;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.font(Font.BOLD, 13));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(14 + 20 + 8 + fm.stringWidth(getText()) + 14, 34);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        float hv = fx.h();
        Fx.squeeze(g, w, h, fx.p());
        if (hv > 0) {
            g.setColor(Theme.ov(Math.round(18 * hv + 10 * fx.p())));
            g.fillRoundRect(0, 0, w, h, 12, 12);
        }
        Color c = Fx.mix(Theme.ICON, Theme.ICON_HI, hv);
        g.setColor(c);
        g.translate(14, (h - 18) / 2);
        if (icon == 0) {
            java.awt.geom.Area a = new java.awt.geom.Area(new RoundRectangle2D.Float(0, 1.5f, 20, 14, 9, 9));
            a.add(new java.awt.geom.Area(new RoundRectangle2D.Float(3, 12, 14, 5.5f, 4, 4)));
            a.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(4.2f, 6.2f, 4f, 5f)));
            a.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(11.8f, 6.2f, 4f, 5f)));
            g.fill(a);
        } else {
            Path2D.Float hp = new Path2D.Float();
            hp.moveTo(10, 17.5f);
            hp.curveTo(-4, 8, 3, -1, 10, 4.5f);
            hp.curveTo(17, -1, 24, 8, 10, 17.5f);
            hp.closePath();
            g.fill(hp);
        }
        g.translate(-14, -(h - 18) / 2);
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        g.drawString(getText(), 14 + 20 + 8, (h - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
    }
}
