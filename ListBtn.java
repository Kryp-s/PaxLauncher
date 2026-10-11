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

final class ListBtn extends JButton {
    private final Fx fx = new Fx(this);
    private final int icon;

    ListBtn(String text, int icon) {
        super(text);
        this.icon = icon;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.font(Font.PLAIN, 14));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(100, 40));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        float hv = fx.h();
        Fx.squeeze(g, w, h, fx.p());
        if (hv > 0) {
            g.setColor(Theme.ov(Math.round(14 * hv + 8 * fx.p())));
            g.fillRoundRect(0, 0, w, h, 12, 12);
        }
        int dx = Math.round(2 * hv);
        Theme.railIcon(g, icon, 26 + dx, h / 2, Fx.mix(Theme.MUTED, Theme.TEXT, hv), 1.0);
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Fx.mix(Theme.ICON, Theme.ICON_HI, hv));
        g.drawString(getText(), 52 + dx, (h - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
    }
}
