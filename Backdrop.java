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

final class Backdrop extends JPanel {
    int arc = 10;
    boolean side = true;

    Backdrop(LayoutManager lm) { super(lm); setOpaque(true); setBackground(Theme.BG2); }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        g.setColor(Theme.BG1);
        g.fillRect(0, 0, w, h);
        g.setColor(Theme.BG2);
        if (side) g.fillRoundRect(Theme.SIDE_W, Theme.TITLE_H, w - Theme.SIDE_W + 30, h - Theme.TITLE_H + 30, 24, 24);
        else g.fillRect(0, Theme.TITLE_H, w, h - Theme.TITLE_H);
        g.setColor(Theme.ov(28));
        if (arc > 0) g.drawRoundRect(0, 0, w - 1, h - 1, arc * 2, arc * 2);
        else g.drawRect(0, 0, w - 1, h - 1);
        g.dispose();
    }
}
