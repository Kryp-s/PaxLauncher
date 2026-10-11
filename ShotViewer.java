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

final class ShotViewer {
    private static final int IW = 880, IH = 495;
    private final LauncherWindow win;
    private final List<Shot> list;
    private volatile int cur;
    private final JDialog d;
    private final JLabel title = LauncherWindow.label(" ", 18, true, Theme.TEXT);
    private final JLabel counter = LauncherWindow.label(" ", 13, true, Theme.MUTED);
    private final JLabel cap = LauncherWindow.label(" ", 12, false, Theme.MUTED);
    private final JLabel status = LauncherWindow.label(" ", 12, true, Theme.ACC2);
    private final JLabel imgL = new JLabel("", SwingConstants.CENTER);
    private final ArrowBtn prev = new ArrowBtn(-1), next = new ArrowBtn(1);
    private final Map<Path, BufferedImage> cache = new LinkedHashMap<Path, BufferedImage>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, BufferedImage> e) { return size() > 6; }
    };
    private final Set<Path> busy = new HashSet<>();
    private final ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shot-view");
        t.setDaemon(true);
        return t;
    });
    private javax.swing.Timer statusTimer;

    static void show(LauncherWindow win, List<Shot> list, int start) {
        new ShotViewer(win, list, start).run();
    }

    private ShotViewer(LauncherWindow win, List<Shot> list, int start) {
        this.win = win;
        this.list = list;
        this.cur = start;
        d = new JDialog(win, "", Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        final boolean aa = Round.applyAA(d, 10);

        JPanel root = new JPanel(new BorderLayout(0, 14)) {
            @Override protected void paintComponent(Graphics g0) { Round.paintCard(g0, getWidth(), getHeight(), 10, aa); }
        };
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(22, 24, 20, 24));

        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setOpaque(false);
        head.add(title, BorderLayout.CENTER);
        head.add(counter, BorderLayout.EAST);
        root.add(head, BorderLayout.NORTH);

        imgL.setPreferredSize(new Dimension(IW, IH));
        imgL.setForeground(Theme.MUTED);
        imgL.setFont(Theme.font(Font.PLAIN, 13));
        prev.setFocusable(false);
        next.setFocusable(false);
        prev.addActionListener(e -> go(-1));
        next.addActionListener(e -> go(1));
        JPanel mid = new JPanel(new BorderLayout(12, 0));
        mid.setOpaque(false);
        mid.add(side(prev), BorderLayout.WEST);
        mid.add(imgL, BorderLayout.CENTER);
        mid.add(side(next), BorderLayout.EAST);
        root.add(mid, BorderLayout.CENTER);

        Btn copyB = new Btn(I18n.t("Копіювати"), Btn.Kind.SECONDARY);
        Btn closeB = new Btn(I18n.t("Закрити"), Btn.Kind.PRIMARY);
        copyB.setBorder(new EmptyBorder(10, 26, 10, 26));
        closeB.setBorder(new EmptyBorder(10, 26, 10, 26));
        copyB.setFocusable(false);
        copyB.addActionListener(e -> copy());
        closeB.addActionListener(e -> d.dispose());
        JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        info.setOpaque(false);
        info.add(cap);
        info.add(status);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btns.setOpaque(false);
        btns.add(copyB);
        btns.add(closeB);
        JPanel foot = new JPanel(new BorderLayout(12, 0));
        foot.setOpaque(false);
        foot.add(info, BorderLayout.CENTER);
        foot.add(btns, BorderLayout.EAST);
        root.add(foot, BorderLayout.SOUTH);

        Point[] start0 = new Point[1];
        root.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { start0[0] = e.getPoint(); }
        });
        root.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (start0[0] == null) return;
                Point p = d.getLocation();
                d.setLocation(p.x + e.getX() - start0[0].x, p.y + e.getY() - start0[0].y);
            }
        });

        d.setContentPane(root);
        JRootPane rp = d.getRootPane();
        rp.registerKeyboardAction(e -> go(-1), KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        rp.registerKeyboardAction(e -> go(1), KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        rp.registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        rp.registerKeyboardAction(e -> copy(), KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                ex.shutdownNow();
                if (statusTimer != null) statusTimer.stop();
            }
        });
    }

    private static JPanel side(JComponent c) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    private void run() {
        render();
        d.pack();
        d.setLocationRelativeTo(win);
        d.setVisible(true);
    }

    private void go(int delta) {
        int n = cur + delta;
        if (n < 0 || n >= list.size()) return;
        cur = n;
        render();
    }

    private void render() {
        int c = cur;
        Shot s = list.get(c);
        title.setText(s.file().getFileName().toString());
        cap.setText(s.label() + "  ·  " + ShotCard.fmtDate(s.time()));
        counter.setText((c + 1) + " / " + list.size());
        prev.setEnabled(c > 0);
        next.setEnabled(c < list.size() - 1);
        BufferedImage im = cache.get(s.file());
        if (im != null) { imgL.setIcon(new ImageIcon(im)); imgL.setText(null); }
        else { imgL.setIcon(null); imgL.setText("…"); }
        fetch(c);
        fetch(c + 1);
        fetch(c - 1);
    }

    private void fetch(int i) {
        if (i < 0 || i >= list.size()) return;
        Path f = list.get(i).file();
        if (cache.containsKey(f) || !busy.add(f)) return;
        try {
            ex.execute(() -> {
                BufferedImage im = null;
                if (Math.abs(i - cur) <= 1) {
                    try { im = Shots.read(f, IW, IH, false); } catch (Throwable ignored) { }
                }
                BufferedImage fim = im;
                SwingUtilities.invokeLater(() -> {
                    busy.remove(f);
                    if (fim != null) cache.put(f, fim);
                    if (list.get(cur).file().equals(f)) {
                        if (fim != null) { imgL.setIcon(new ImageIcon(fim)); imgL.setText(null); }
                        else { imgL.setIcon(null); imgL.setText(I18n.t("Не вдалося відкрити зняток")); }
                    }
                });
            });
        } catch (RejectedExecutionException ignored) { }
    }

    private void copy() {
        win.copyShot(list.get(cur), () -> flash(I18n.t("Зняток скопійовано")));
    }

    private void flash(String t) {
        status.setText(t);
        if (statusTimer != null) statusTimer.stop();
        statusTimer = new javax.swing.Timer(2500, e -> status.setText(" "));
        statusTimer.setRepeats(false);
        statusTimer.start();
    }
}
