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

final class Dlg {
    static JLabel text(String t) {
        String h = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return LauncherWindow.label("<html><div style='width:360px'>" + h + "</div></html>", 14, false, Theme.TEXT);
    }

    static boolean show(Component parent, String title, JComponent content, String ok, String cancel) {
        return show(parent, title, content, ok, cancel, null);
    }

    static boolean show(Component parent, String title, JComponent content, String ok, String cancel, Consumer<JDialog> ready) {
        Window owner = parent instanceof Window ? (Window) parent : parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        final boolean aa = Round.applyAA(d, 10);
        boolean[] res = {false};

        JPanel root = new JPanel(new BorderLayout(0, 18)) {
            @Override protected void paintComponent(Graphics g0) { Round.paintCard(g0, getWidth(), getHeight(), 10, aa); }
        };
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(24, 28, 22, 28));
        root.add(LauncherWindow.label(title, 18, true, Theme.TEXT), BorderLayout.NORTH);
        content.setOpaque(false);
        root.add(content, BorderLayout.CENTER);

        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bp.setOpaque(false);
        Btn okB = new Btn(ok, Btn.Kind.PRIMARY);
        okB.setBorder(new EmptyBorder(10, 26, 10, 26));
        okB.addActionListener(e -> { res[0] = true; d.dispose(); });
        if (cancel != null) {
            Btn cB = new Btn(cancel, Btn.Kind.SECONDARY);
            cB.setBorder(new EmptyBorder(10, 26, 10, 26));
            cB.addActionListener(e -> d.dispose());
            bp.add(cB);
        }
        bp.add(okB);
        root.add(bp, BorderLayout.SOUTH);

        Point[] start = new Point[1];
        root.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { start[0] = e.getPoint(); }
        });
        root.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                Point p = d.getLocation();
                d.setLocation(p.x + e.getX() - start[0].x, p.y + e.getY() - start[0].y);
            }
        });

        d.setContentPane(root);
        d.getRootPane().setDefaultButton(okB);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.pack();
        if (d.getWidth() < 420) d.setSize(420, d.getHeight());
        d.setLocationRelativeTo(owner);
        if (ready != null) ready.accept(d);
        d.setVisible(true);
        return res[0];
    }
}
