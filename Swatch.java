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

final class Swatch extends JComponent {
    // колір, обраний у налаштуваннях, але ще не збережений (null — показуємо поточний колір теми)
    static Color mark;

    private final Color col;
    private final Fx fx = new Fx(this);

    Swatch(Color col, Runnable click) {
        this.col = col;
        setPreferredSize(new Dimension(32, 32));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(Theme.hex(col));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); }
            @Override public void mouseClicked(MouseEvent e) { click.run(); }
        });
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        Color curAcc = mark != null ? mark : Theme.accent;
        boolean sel = (curAcc.getRGB() & 0xFFFFFF) == (col.getRGB() & 0xFFFFFF);
        int in = Math.round(5 - 2 * fx.h());
        if (sel) {
            g.setColor(Theme.TEXT);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(1, 1, w - 3, h - 3);
            in = 6;
        }
        g.setColor(col);
        g.fillOval(in, in, w - 2 * in, h - 2 * in);
        g.dispose();
    }
}
