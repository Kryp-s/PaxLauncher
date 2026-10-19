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

    /** Логотип Discord (22 x 15,3), масштабований під слот 20 x 18. */
    private static java.awt.geom.Area discord() {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(4.6f, 1.4f);
        p.curveTo(6.0f, 0.8f, 7.4f, 0.4f, 8.2f, 0.3f);
        p.lineTo(8.7f, 1.2f);
        p.curveTo(10.2f, 1.0f, 11.8f, 1.0f, 13.3f, 1.2f);
        p.lineTo(13.8f, 0.3f);
        p.curveTo(14.6f, 0.4f, 16.0f, 0.8f, 17.4f, 1.4f);
        p.curveTo(19.4f, 4.4f, 20.6f, 8.2f, 20.4f, 12.6f);
        p.curveTo(18.9f, 13.8f, 17.2f, 14.6f, 15.4f, 15.0f);
        p.lineTo(14.4f, 13.4f);
        p.curveTo(13.4f, 13.7f, 12.2f, 13.9f, 11f, 13.9f);
        p.curveTo(9.8f, 13.9f, 8.6f, 13.7f, 7.6f, 13.4f);
        p.lineTo(6.6f, 15.0f);
        p.curveTo(4.8f, 14.6f, 3.1f, 13.8f, 1.6f, 12.6f);
        p.curveTo(1.4f, 8.2f, 2.6f, 4.4f, 4.6f, 1.4f);
        p.closePath();
        java.awt.geom.Area a = new java.awt.geom.Area(p);
        a.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(6.8f, 6.6f, 3.0f, 3.6f)));
        a.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(12.2f, 6.6f, 3.0f, 3.6f)));
        a.transform(java.awt.geom.AffineTransform.getTranslateInstance(-1.4, -0.3));
        a.transform(java.awt.geom.AffineTransform.getScaleInstance(20 / 19.0, 20 / 19.0));
        a.transform(java.awt.geom.AffineTransform.getTranslateInstance(0, 1.3));
        return a;
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(10 + 20 + 6 + fm.stringWidth(getText()) + 10, 34);
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
        g.translate(10, (h - 18) / 2);
        if (icon == 0) {
            g.fill(discord());
        } else if (icon == 2) {
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Ellipse2D.Float(1.5f, 0.6f, 17f, 17f));
            g.draw(new java.awt.geom.Ellipse2D.Float(6f, 0.6f, 8f, 17f));
            g.draw(new java.awt.geom.Line2D.Float(1.5f, 9.1f, 18.5f, 9.1f));
        } else {
            Path2D.Float hp = new Path2D.Float();
            hp.moveTo(10, 17.5f);
            hp.curveTo(-4, 8, 3, -1, 10, 4.5f);
            hp.curveTo(17, -1, 24, 8, 10, 17.5f);
            hp.closePath();
            g.fill(hp);
        }
        g.translate(-10, -(h - 18) / 2);
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        g.drawString(getText(), 10 + 20 + 6, (h - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
    }
}
