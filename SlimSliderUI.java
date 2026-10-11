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

final class SlimSliderUI extends BasicSliderUI {
    private boolean hover, pressed;

    SlimSliderUI(JSlider s) {
        super(s);
        s.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        s.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; s.repaint(); }
            @Override public void mouseExited(MouseEvent e) { hover = false; s.repaint(); }
            @Override public void mousePressed(MouseEvent e) { pressed = true; s.repaint(); }
            @Override public void mouseReleased(MouseEvent e) { pressed = false; s.repaint(); }
        });
    }

    @Override protected TrackListener createTrackListener(JSlider s) {
        return new TrackListener() {
            @Override public void mousePressed(MouseEvent e) {
                if (s.isEnabled() && !thumbRect.contains(e.getX(), e.getY())) {
                    s.setValue(valueForXPosition(e.getX()));
                    calculateThumbLocation();
                }
                super.mousePressed(e);
            }
        };
    }

    @Override public void paintTrack(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int y = trackRect.y + trackRect.height / 2 - 3;
        g.setColor(Theme.INPUT);
        g.fillRoundRect(trackRect.x, y, trackRect.width, 6, 6, 6);
        int fw = thumbRect.x + thumbRect.width / 2 - trackRect.x;
        g.setPaint(new GradientPaint(trackRect.x, 0, Theme.ACC1, trackRect.x + trackRect.width, 0, Theme.ACC2));
        g.fillRoundRect(trackRect.x, y, Math.max(fw, 6), 6, 6, 6);
        g.dispose();
    }

    @Override public void paintThumb(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int cx = thumbRect.x + thumbRect.width / 2, cy = thumbRect.y + thumbRect.height / 2;
        if (hover || pressed) {
            g.setColor(Theme.acc(pressed ? 110 : 70));
            g.fillOval(cx - 11, cy - 11, 22, 22);
        }
        g.setColor(new Color(0, 0, 0, 70));
        g.fillOval(cx - 8, cy - 7, 16, 16);
        g.setColor(Color.WHITE);
        g.fillOval(cx - 8, cy - 8, 16, 16);
        g.setPaint(new GradientPaint(cx - 4, cy - 4, Theme.ACC1, cx + 4, cy + 4, Theme.ACC2));
        g.fillOval(cx - 4, cy - 4, 8, 8);
        g.dispose();
    }

    @Override protected Dimension getThumbSize() { return new Dimension(24, 24); }
    @Override public void paintFocus(Graphics g) { }
}
