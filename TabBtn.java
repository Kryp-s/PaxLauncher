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

final class TabBtn extends JButton {
    private final Fx fx = new Fx(this);

    TabBtn(String text) {
        super(text);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.font(Font.BOLD, 16));
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    void setSel(boolean b) { fx.sel(b, false); }

    @Override public Dimension getPreferredSize() {
        return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + 6, 34);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        float sl = fx.s();
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Fx.mix(Theme.MUTED, Theme.ICON_HI, Math.max(sl, fx.h())));
        g.drawString(getText(), 3, (h - 6 - fm.getHeight()) / 2 + fm.getAscent() + 2);
        if (sl > 0) {
            g.setColor(Theme.ACC2);
            int uw = Math.round(w * sl);
            g.fillRect((w - uw) / 2, h - 4, uw, 3);
        }
        g.dispose();
    }
}
