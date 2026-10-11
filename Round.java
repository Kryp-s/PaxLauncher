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

final class Round {
    static boolean applyAA(Window w, int radius) {
        try {
            if (GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                w.setBackground(new Color(0, 0, 0, 0));
                return true;
            }
        } catch (RuntimeException ignored) { }
        apply(w, () -> radius);
        return false;
    }

    static void paintCard(Graphics g0, int w, int h, int radius, boolean aa) {
        Graphics2D g = (Graphics2D) g0.create();
        if (!aa) {
            g.setColor(Theme.CARD);
            g.fillRect(0, 0, w, h);
            g.setColor(Theme.ov(40));
            g.drawRect(0, 0, w - 1, h - 1);
        } else {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Theme.CARD);
            g.fill(new RoundRectangle2D.Float(0, 0, w, h, radius * 2f, radius * 2f));
            g.setColor(Theme.ov(40));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, radius * 2f, radius * 2f));
        }
        g.dispose();
    }

    static Runnable apply(Window w, java.util.function.IntSupplier arc) {
        w.setBackground(Theme.BG2);
        Runnable upd = () -> {
            try {
                int a = arc.getAsInt();
                if (a < 0) return;
                if (a == 0 || w.getWidth() <= 0) w.setShape(null);
                else w.setShape(new RoundRectangle2D.Double(0, 0, w.getWidth(), w.getHeight(), a * 2, a * 2));
            } catch (RuntimeException ignored) { }
        };
        w.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { upd.run(); }
            @Override public void componentShown(ComponentEvent e) { upd.run(); }
        });
        return upd;
    }
}
