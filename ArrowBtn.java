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

final class ArrowBtn extends JButton {
    private final int dir;
    private final Fx fx = new Fx(this);

    ArrowBtn(int dir) {
        super();
        this.dir = dir;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.font(Font.PLAIN, 13));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Dimension d = new Dimension(36, 30);
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

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        Fx.squeeze(g, w, h, isEnabled() ? fx.p() : 0);
        g.setColor(isEnabled() ? Theme.ov(Math.round(14 + 20 * fx.h())) : Theme.ov(8));
        g.fillRoundRect(0, 0, w, h, h, h);
        g.setColor(isEnabled() ? Theme.TEXT : Theme.DIS_ICON);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = w / 2, cy = h / 2;
        if (dir < 0) g.drawPolyline(new int[]{cx + 3, cx - 3, cx + 3}, new int[]{cy - 6, cy, cy + 6}, 3);
        else g.drawPolyline(new int[]{cx - 3, cx + 3, cx - 3}, new int[]{cy - 6, cy, cy + 6}, 3);
        g.dispose();
    }
}
