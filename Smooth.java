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

final class Smooth {
    static void install(JScrollPane sp) {
        for (MouseWheelListener l : sp.getMouseWheelListeners()) sp.removeMouseWheelListener(l);
        JScrollBar bar = sp.getVerticalScrollBar();
        double[] pos = {0}, target = {0};
        javax.swing.Timer t = new javax.swing.Timer(8, null);
        t.addActionListener(e -> {
            double d = target[0] - pos[0];
            if (Math.abs(d) < 0.6) { pos[0] = target[0]; t.stop(); }
            else pos[0] += d * 0.17;
            bar.setValue((int) Math.round(pos[0]));
        });
        sp.addMouseWheelListener(e -> {
            int max = bar.getMaximum() - bar.getVisibleAmount();
            if (max <= 0) {
                Container par = SwingUtilities.getAncestorOfClass(JScrollPane.class, sp);
                if (par != null) {
                    Point pt = SwingUtilities.convertPoint(sp, e.getPoint(), par);
                    par.dispatchEvent(new MouseWheelEvent(par, e.getID(), e.getWhen(), e.getModifiersEx(), pt.x, pt.y,
                            e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), false, e.getScrollType(), e.getScrollAmount(), e.getWheelRotation(), e.getPreciseWheelRotation()));
                }
                return;
            }
            if (!t.isRunning()) { pos[0] = bar.getValue(); target[0] = pos[0]; }
            target[0] = Math.max(0, Math.min(max, target[0] + e.getPreciseWheelRotation() * 80));
            t.start();
        });
    }
}
