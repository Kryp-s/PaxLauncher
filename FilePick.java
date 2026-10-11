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

final class FilePick {
    private record Ent(File f, boolean dir) { }

    private final DefaultListModel<Ent> model = new DefaultListModel<>();
    private final JList<Ent> list = new JList<>(model);
    private final Field path = new Field("");
    private final int mode;
    private File dir;
    private JDialog dialog;
    private boolean forced;

    private FilePick(int mode, boolean multi) {
        this.mode = mode;
        list.setSelectionMode(multi ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
    }

    static File[] show(Component parent, String title, int mode, boolean multi, File start) {
        FilePick fp = new FilePick(mode, multi);
        JPanel pn = fp.build();
        File s = start != null && start.isDirectory() ? start : new File(System.getProperty("user.home", "."));
        fp.go(s.isDirectory() ? s : null);
        boolean ok = Dlg.show(parent, title, pn, I18n.t("Обрати"), I18n.t("Скасувати"), d -> fp.dialog = d);
        if (!ok && !fp.forced) return null;
        return fp.result();
    }

    private static Icon icon(boolean folder) {
        return new Icon() {
            @Override public int getIconWidth() { return 20; }
            @Override public int getIconHeight() { return 16; }
            @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                if (folder) {
                    g.setColor(Theme.ACC2);
                    g.fillRoundRect(x, y + 1, 9, 6, 3, 3);
                    g.fillRoundRect(x, y + 3, 20, 13, 4, 4);
                } else {
                    g.setColor(Theme.MUTED);
                    g.fillRoundRect(x + 4, y, 12, 16, 3, 3);
                    g.setColor(Theme.CARD2);
                    g.fillRect(x + 7, y + 4, 6, 1);
                    g.fillRect(x + 7, y + 7, 6, 1);
                    g.fillRect(x + 7, y + 10, 6, 1);
                }
                g.dispose();
            }
        };
    }

    private JPanel build() {
        list.setBackground(Theme.CARD2);
        list.setFixedCellHeight(34);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                Ent e = (Ent) v;
                String n = e.f().getName().isEmpty() ? e.f().getPath() : e.f().getName();
                super.getListCellRendererComponent(l, n, i, sel, false);
                setIcon(icon(e.dir()));
                setIconTextGap(10);
                setBackground(sel ? Theme.SEL : Theme.CARD2);
                setForeground(e.dir() ? Theme.TEXT : Theme.SUB);
                setFont(Theme.font(Font.PLAIN, 14));
                setBorder(new EmptyBorder(6, 14, 6, 14));
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int i = list.locationToIndex(e.getPoint());
                Rectangle r = i < 0 ? null : list.getCellBounds(i, i);
                if (r != null && r.contains(e.getPoint())) open(i);
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) open(list.getSelectedIndex());
            }
        });

        JScrollPane sp = new JScrollPane(list);
        Smooth.install(sp);
        sp.setBorder(null);
        sp.getViewport().setBackground(Theme.CARD2);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        sp.setPreferredSize(new Dimension(580, 320));

        Btn up = LauncherWindow.small(new Btn(I18n.t("Вгору"), Btn.Kind.SECONDARY));
        up.addActionListener(e -> { if (dir != null) go(dir.getParentFile()); });
        path.setPreferredSize(new Dimension(200, 42));
        path.addActionListener(e -> {
            File f = new File(path.getText().trim());
            if (f.isDirectory()) go(f);
            else if (f.isFile() && mode != JFileChooser.DIRECTORIES_ONLY && f.getParentFile() != null) {
                go(f.getParentFile());
                for (int i = 0; i < model.size(); i++) if (model.get(i).f().equals(f)) { list.setSelectedIndex(i); list.ensureIndexIsVisible(i); }
            }
        });

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(up, BorderLayout.WEST);
        top.add(path, BorderLayout.CENTER);

        JPanel pn = new JPanel(new BorderLayout(0, 12));
        pn.setOpaque(false);
        pn.add(top, BorderLayout.NORTH);
        pn.add(sp, BorderLayout.CENTER);
        if (mode != JFileChooser.FILES_ONLY)
            pn.add(LauncherWindow.label(I18n.t("Нічого не виділено — буде обрано поточну папку."), 12, false, Theme.MUTED), BorderLayout.SOUTH);
        return pn;
    }

    private void go(File d) {
        if (d != null && !d.isDirectory()) return;
        dir = d;
        File[] kids = d == null ? File.listRoots() : d.listFiles();
        List<Ent> l = new ArrayList<>();
        if (kids != null) for (File f : kids) {
            if (d != null && f.isHidden()) continue;
            boolean isDir = f.isDirectory();
            if (mode == JFileChooser.DIRECTORIES_ONLY && !isDir) continue;
            l.add(new Ent(f, isDir));
        }
        l.sort(Comparator.comparing((Ent e) -> !e.dir()).thenComparing(e -> e.f().getName().toLowerCase(Locale.ROOT)));
        model.clear();
        for (Ent e : l) model.addElement(e);
        path.setText(d == null ? "" : d.getAbsolutePath());
        list.clearSelection();
        if (!model.isEmpty()) list.ensureIndexIsVisible(0);
    }

    private void open(int i) {
        if (i < 0 || i >= model.size()) return;
        Ent e = model.get(i);
        if (e.dir()) go(e.f());
        else { forced = true; if (dialog != null) dialog.dispose(); }
    }

    private File[] result() {
        List<File> out = new ArrayList<>();
        for (Ent e : list.getSelectedValuesList()) out.add(e.f());
        if (!out.isEmpty()) return out.toArray(new File[0]);
        File typed = new File(path.getText().trim());
        if (dir == null || !typed.equals(dir)) {
            if (typed.isDirectory() && mode != JFileChooser.FILES_ONLY) return new File[]{typed};
            if (typed.isFile() && mode != JFileChooser.DIRECTORIES_ONLY) return new File[]{typed};
        }
        if (mode != JFileChooser.FILES_ONLY && dir != null) return new File[]{dir};
        return null;
    }
}
