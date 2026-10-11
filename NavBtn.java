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

final class NavBtn extends JButton {
    private final Fx fx = new Fx(this);
    private final int icon;

    NavBtn(String cap, String text, int icon) {
        super(text);
        this.icon = icon;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setAlignmentX(Component.CENTER_ALIGNMENT);
        Dimension d = new Dimension(Theme.SIDE_W, 56);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    void setSel(boolean b) { fx.sel(b, false); }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight(), s = 46;
        float hv = fx.h(), sl = fx.s(), pr = fx.p();
        Fx.squeeze(g, w, h, pr);
        if (hv > 0 && sl < 1) {
            g.setColor(Theme.ov(Math.round(16 * hv * (1 - sl))));
            g.fillRoundRect((w - s) / 2, (h - s) / 2, s, s, 20, 20);
        }
        if (sl > 0) {
            g.setColor(Theme.acc(Math.round(48 * sl)));
            int ss = (int) Math.round(s * (0.8 + 0.2 * sl));
            g.fillRoundRect((w - ss) / 2, (h - ss) / 2, ss, ss, 20, 20);
        }
        Color c = Fx.mix(Fx.mix(Theme.NAV_IDLE, Theme.TEXT, hv), Theme.ACC2, sl);
        Theme.railIcon(g, icon, w / 2, h / 2, c, 1.35);
        g.dispose();
    }
}
