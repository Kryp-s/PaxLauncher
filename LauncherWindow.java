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

final class LauncherWindow extends JFrame {
    record VItem(String id, String type, boolean installed, String profile) {
        String key() { return profile != null ? "profile:" + profile : id; }

        @Override public String toString() {
            if (profile != null) return profile + I18n.t("  · збірка (") + id + ")";
            String tag = type.equals("snapshot") ? I18n.t("  · знімок") : type.startsWith("old") ? I18n.t("  · давня") : type.equals("custom") ? I18n.t("  · з модами") : "";
            return id + tag + (installed ? I18n.t("  · встановлено") : "");
        }
    }

    final Path root = PaxLauncher.ROOT;
    final Settings cfg = new Settings(root.resolve("launcher.properties"));
    final JTextArea console = new JTextArea();
    final Engine engine = new Engine(root, this::log);
    final ModApi api = new ModApi(engine);

    final CardLayout cards = new CardLayout();
    final FadePanel pages = new FadePanel(cards);
    final Map<String, NavBtn> nav = new LinkedHashMap<>();

    final Combo<VItem> versions = new Combo<>();
    final Check cRel = new Check(I18n.t("Випуски"), true), cSnap = new Check(I18n.t("Знімки"), false), cOld = new Check(I18n.t("Давні (бета/альфа)"), false);
    final Bar bar = new Bar();
    final JLabel status = label(I18n.t("Готово до запуску"), 13, false, Theme.MUTED);
    final Btn playBtn = new Btn(I18n.t("Грати"), Btn.Kind.PRIMARY);
    final Btn loadersBtn = new Btn(I18n.t("Додати версію"), Btn.Kind.SECONDARY);
    boolean filling, busy;

    final Combo<String> lMc = new Combo<>(), lType = new Combo<>();
    final Combo<Engine.LV> lVer = new Combo<>();
    final Btn lInstall = new Btn(I18n.t("Встановити"), Btn.Kind.PRIMARY);
    final JLabel lStatus = label(I18n.t("Обери версію Minecraft і завантажувач"), 13, false, Theme.MUTED);
    final Bar lBar = new Bar();
    final Btn cancelBtn = new Btn(I18n.t("Скасувати"), Btn.Kind.DANGER), lCancel = new Btn(I18n.t("Скасувати"), Btn.Kind.DANGER),
            pCancel = new Btn(I18n.t("Скасувати"), Btn.Kind.DANGER);
    final Btn pauseBtn = new Btn(I18n.t("Пауза"), Btn.Kind.SECONDARY), lPause = new Btn(I18n.t("Пауза"), Btn.Kind.SECONDARY),
            pPause = new Btn(I18n.t("Пауза"), Btn.Kind.SECONDARY);
    final AtomicInteger lSeq = new AtomicInteger();
    boolean lFilling;

    final Combo<JavaFinder.Inst> javaC = new Combo<>();
    final JavaFinder.Inst javaAuto = new JavaFinder.Inst(I18n.t("Автоматично (під версію гри)"), "");
    final JavaFinder.Inst javaCustom = new JavaFinder.Inst(I18n.t("Свій шлях…"), null);
    boolean javaSync;
    final Field javaF = new Field(""), jvmF = new Field(""), wF = new Field(""), hF = new Field(""), dirF = new Field("");
    final JSlider ram = new JSlider(2, 32, 8);
    final JLabel ramLbl = label("4096 MB", 14, true, Theme.TEXT);
    final Check hideC = new Check(I18n.t("Ховати запускач, поки триває гра"), true);
    final Field discF = new Field("");

    record Prof(String name, String versionId, String mc, String loader) {
        @Override public String toString() { return name + "  · " + loader + " " + mc; }
    }

    final Combo<Prof> mProf = new Combo<>();
    final Combo<ModApi.Kind> mKind = new Combo<>();
    final Btn mDirB = new Btn(I18n.t("Папка модів"), Btn.Kind.SECONDARY);
    final Btn mRmB = new Btn(I18n.t("Видалити мод"), Btn.Kind.SECONDARY);
    final JLabel mInstTitle = label(I18n.t("Встановлені моди"), 15, true, Theme.TEXT);
    final JLabel mVerLabel = label(I18n.t("Версія мода (для цієї збірки)"), 12, true, Theme.MUTED);
    final Field mQuery = new Field("");
    final JLabel mInfo = label(" ", 13, false, Theme.MUTED);
    final JPanel mInfoWrap = new JPanel();
    Btn mImpB;
    final JLabel dIcon = new JLabel(), iIcon = new JLabel();
    final JPanel dChips = chipRow(), iChips = chipRow();
    final JLabel iTitle = label(" ", 22, true, Theme.TEXT), iMeta = label(" ", 13, false, Theme.MUTED);
    final JTextArea iDesc = new JTextArea();
    final Btn iPage = new Btn("", Btn.Kind.SECONDARY);
    final Btn togB = new Btn(I18n.t("Вимкнути / увімкнути"), Btn.Kind.SECONDARY);
    final Map<String, String> iProj = new ConcurrentHashMap<>();
    final AtomicInteger dSeq = new AtomicInteger(), iDetSeq = new AtomicInteger();
    volatile String dBody = "";

    static JPanel chipRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)) {
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                d.height = Math.max(24, d.height);
                return d;
            }
        };
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    static void fillChips(JPanel p, List<String> items) {
        p.removeAll();
        for (String s : items) if (s != null && !s.isBlank()) p.add(new Chip(s));
        p.revalidate();
        p.repaint();
    }

    static String sizeText(long n) {
        return n >= (1 << 20) ? String.format("%.1f МБ", n / 1048576.0) : Math.max(1, n / 1024) + " КБ";
    }

    void setLabelIcon(JLabel l, String tok, int size) {
        l.putClientProperty("tok", tok);
        l.setIcon(modIcon(tok, size, l));
        if (tok == null || tok.isEmpty() || iconRaw.containsKey(tok)) return;
        javax.swing.Timer t = new javax.swing.Timer(300, null);
        int[] n = {0};
        t.addActionListener(e -> {
            if (!tok.equals(l.getClientProperty("tok")) || ++n[0] > 60) { t.stop(); return; }
            if (iconRaw.containsKey(tok)) {
                t.stop();
                l.setIcon(modIcon(tok, size, l));
                l.repaint();
            }
        });
        t.start();
    }

    String tr(String s) {
        if (s == null || s.isBlank()) return "";
        return api.needsTranslation(s) ? api.translate(s) : api.cached(s);
    }

    void setModInfo(String t) {
        boolean show = t != null && !t.isBlank();
        mInfo.setText(show ? t : " ");
        if (mInfoWrap.isVisible() != show) {
            mInfoWrap.setVisible(show);
            if (mInfoWrap.getParent() != null) { mInfoWrap.getParent().revalidate(); mInfoWrap.getParent().repaint(); }
        }
    }
    final JLabel mStatus = label(I18n.t("Створи збірку і шукай моди"), 13, false, Theme.MUTED);
    final Bar mBar = new Bar();
    final DefaultListModel<ModApi.Hit> mModel = new DefaultListModel<>();
    final JList<ModApi.Hit> mList = new JList<>(mModel);
    final JLabel dTitle = label(I18n.t("Обери мод зі списку"), 18, true, Theme.TEXT);
    final JLabel dMeta = label(" ", 12, false, Theme.MUTED);
    final JTextArea dDesc = new JTextArea();
    final Combo<ModApi.ModFile> mFiles = new Combo<>();
    final Btn mInstall = new Btn(I18n.t("Встановити"), Btn.Kind.PRIMARY);
    final Btn mCancel = new Btn(I18n.t("Зупинити"), Btn.Kind.DANGER);
    volatile boolean mCancelled;
    final Btn mSearch = new Btn(I18n.t("Знайти"), Btn.Kind.PRIMARY);
    final Btn mMore = new Btn(I18n.t("Ще результати"), Btn.Kind.SECONDARY);
    final Btn mPage = new Btn(I18n.t("Сторінка мода"), Btn.Kind.SECONDARY);
    final DefaultListModel<String> iModel = new DefaultListModel<>();
    final JList<String> iList = new JList<>(iModel);
    final CardLayout mCards = new CardLayout();
    final JPanel mCenter = new JPanel(mCards);
    JScrollPane playScroll;

    static final String DISCORD_URL = "https://discord.gg/WeP5n53z8e";
    static final String SUPPORT_URL = "https://send.monobank.ua/jar/A8UDDsJojo";
    final Btn mToggleView = new Btn(I18n.t("Встановлені моди"), Btn.Kind.SECONDARY);
    JPanel dFoot;
    final AtomicInteger mSeq = new AtomicInteger(), mfSeq = new AtomicInteger();
    boolean mFilling, mBusy, mInstalledView;

    LauncherWindow() {
        setTitle("PaxLauncher");
        setIconImage(Theme.icon(64));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setUndecorated(true);
        shapeUpd = Round.apply(this, () -> animating && plainAnim ? -1 : maxed ? 0 : 10);
        setSize(1240, 780);
        setMinimumSize(new Dimension(1000, 660));
        setLocationRelativeTo(null);

        bg = new Backdrop(new BorderLayout());
        setContentPane(bg);
        JPanel app = new JPanel(new BorderLayout());
        app.setOpaque(false);
        app.add(topBar(true), BorderLayout.NORTH);
        app.add(sidebar(), BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        pages.setOpaque(false);
        pages.add(playPage(), "play");
        pages.add(loadersPage(), "loaders");
        pages.add(consolePage(), "console");
        pages.add(galleryPage(), "gallery");
        pages.add(modsPage(), "mods");
        pages.add(settingsPage(), "settings");
        right.add(pages, BorderLayout.CENTER);
        app.add(right, BorderLayout.CENTER);

        rootPanel.setOpaque(false);
        rootPanel.add(loginPage(), "login");
        rootPanel.add(app, "app");
        bg.add(rootPanel, BorderLayout.CENTER);

        loadSettings();
        online.start();
        rpc.configure(discC.isSelected() ? discF.getText().trim() : "");
        presenceIdle();
        showPage("play");
        refreshVersions();
        if (accounts.current() != null) enterApp(); else showLogin();
        log(I18n.t("Папка запускача: ") + root);
    }

    static JLabel label(String text, int size, boolean bold, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(bold ? Font.BOLD : Font.PLAIN, size));
        l.setForeground(c);
        return l;
    }

    void msg(String text) {
        Dlg.show(this, "PaxLauncher", Dlg.text(text), "OK", null);
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<String> logQ = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicBoolean logPending = new AtomicBoolean();

    void log(String s) {
        if (s == null) return;
        logQ.add(s.length() > 4000 ? s.substring(0, 4000) + "…" : s);
        if (logPending.compareAndSet(false, true)) SwingUtilities.invokeLater(this::flushLog);
    }

    private void flushLog() {
        logPending.set(false);
        StringBuilder sb = new StringBuilder();
        String ln;
        while ((ln = logQ.poll()) != null) sb.append(ln).append('\n');
        if (sb.length() == 0) return;
        console.append(sb.toString());
        javax.swing.text.Document d = console.getDocument();
        if (d.getLength() > 120_000) {
            try { d.remove(0, d.getLength() - 80_000); } catch (Exception ignored) { }
        }
        console.setCaretPosition(d.getLength());
    }

    void trimMemory() {
        Thread t = new Thread(System::gc, "trim");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    {
        addWindowListener(new WindowAdapter() {
            @Override public void windowIconified(WindowEvent e) { trimMemory(); }
            @Override public void windowClosing(WindowEvent e) { exit(); }
        });
        javax.swing.Timer warm = new javax.swing.Timer(6000, e -> trimMemory());
        warm.setRepeats(false);
        warm.start();
    }

    final DiscordRpc rpc = new DiscordRpc(this::log);
    final long launcherStart = System.currentTimeMillis() / 1000;
    Backdrop bg;
    boolean maxed;
    Rectangle restoreBounds;
    final List<Btn> maxBtns = new ArrayList<>();
    final JLabel welcomeLbl = label(I18n.t("Ласкаво просимо!"), 26, true, Theme.TEXT);
    JLabel pageTitle;
    ArrowBtn backB, fwdB;
    AvatarBtn avatarBtn;
    final List<String> hist = new ArrayList<>();
    int hpos = -1;
    boolean navigating;
    final Check discC = new Check(I18n.t("Показувати гру в Discord"), true);
    final Check onlineC = new Check(I18n.t("Враховувати мене в лічильнику онлайну"), true);
    final OnlineBadge onlineBadge = new OnlineBadge();
    final Online online = new Online(cfg, () -> onlineC.isSelected(), onlineBadge::set);
    final JLabel playerLbl = label("—", 15, true, Theme.TEXT);

    private javax.swing.Timer maxAnim;
    private boolean animating;
    private Boolean translucent;
    private Runnable shapeUpd;
    private static final int[] BASE_SIZE = {1240, 780};
    private static final double MAX_MS = 240e6;
    // true  = прозоре вікно на весь екран + прямокутник усередині (інколи дає привиди на стиках);
    // false = вікно змінює розмір напряму, без перемикання прозорості.
    private static final boolean LAYERED_ANIM = false;

    private static double ease(double t) { return 1 - Math.pow(1 - t, 3); }

    private java.awt.image.BufferedImage snap(JComponent c, int w, int h, double sx, double sy) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                Math.max(1, (int) Math.ceil(w * sx)), Math.max(1, (int) Math.ceil(h * sy)), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(sx, sy);
        c.paint(g);
        g.dispose();
        return img;
    }

    private final class Veil extends JComponent {
        final java.awt.image.BufferedImage oldImg;
        volatile java.awt.image.BufferedImage newImg;
        volatile boolean painted;
        boolean layered;
        double k;
        int arc;
        final Rectangle r = new Rectangle();

        Veil(java.awt.image.BufferedImage o, java.awt.image.BufferedImage n, boolean layered) {
            oldImg = o;
            newImg = n;
            this.layered = layered;
        }

        @Override public boolean isOpaque() { return !layered; }

        private static double step(double q) {
            q = Math.max(0, Math.min(1, q));
            return q * q * (3 - 2 * q);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            if (layered) {
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setComposite(AlphaComposite.SrcOver);
                if (arc > 0) g.clip(new java.awt.geom.RoundRectangle2D.Float(r.x, r.y, r.width, r.height, arc * 2f, arc * 2f));
                else g.clipRect(r.x, r.y, r.width, r.height);
            } else r.setBounds(0, 0, getWidth(), getHeight());
            g.clipRect(r.x, r.y, r.width, r.height);
            // Обидва знімки завжди розтягуються на поточний прямокутник вікна, тож жодного «меншого вікна»
            // в куті не буває. Найближчий сусід у ~8 разів швидший за білінійну інтерполяцію.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double b = k >= 1 ? 1 : step((k - 0.2) / 0.55);
            if (b < 1) g.drawImage(oldImg, r.x, r.y, r.width, r.height, null);
            if (b > 0 && newImg != null) {
                if (b < 1) g.setComposite(AlphaComposite.SrcOver.derive((float) b));
                g.drawImage(newImg, r.x, r.y, r.width, r.height, null);
            }
            g.dispose();
            painted = true;
        }
    }

    private boolean plainAnim;
    private JWindow overlay;

    private boolean overlayOk() {
        try {
            return getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (RuntimeException e) { return false; }
    }

    // Анімацію веде окреме прозоре вікно-накладка: головне вікно ніколи не перемикає прозорість
    // і не змінює розмір на кожному кадрі, тому немає ні привидів, ні смикання.
    void toggleMax() {
        if (animating) return;
        if (!overlayOk()) { toggleMaxPlain(); return; }
        animating = true;
        final boolean expand = !maxed;
        final Rectangle from = getBounds();
        final Rectangle to;
        if (expand) {
            restoreBounds = from;
            GraphicsConfiguration gc = getGraphicsConfiguration();
            Rectangle sb = gc.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            to = new Rectangle(sb.x + in.left, sb.y + in.top, sb.width - in.left - in.right, sb.height - in.top - in.bottom);
        } else {
            to = restoreBounds != null ? new Rectangle(restoreBounds) : new Rectangle(from.x, from.y, BASE_SIZE[0], BASE_SIZE[1]);
        }
        java.awt.geom.AffineTransform dt = getGraphicsConfiguration().getDefaultTransform();
        final double sx = dt.getScaleX(), sy = dt.getScaleY();
        final Rectangle area = from.union(to);
        final int arcFrom = expand ? 10 : 0, arcTo = expand ? 0 : 10;

        // крок A: накладка з поточним кадром точно поверх вікна
        final Veil veil = new Veil(snap(bg, bg.getWidth(), bg.getHeight(), sx, sy), null, true);
        veil.arc = arcFrom;
        veil.r.setBounds(from.x - area.x, from.y - area.y, from.width, from.height);
        if (overlay == null) {
            overlay = new JWindow(this);
            overlay.setFocusableWindowState(false);
            overlay.setBackground(new Color(0, 0, 0, 0));
        }
        overlay.setContentPane(veil);
        overlay.setBounds(area);
        overlay.setVisible(true);

        // крок B: коли накладка вже намальована, перебудовуємо інтерфейс під головним вікном
        final int[] cnt = {0, 0};
        final javax.swing.Timer wait = new javax.swing.Timer(8, null);
        wait.addActionListener(ev -> {
            cnt[0]++;
            if (veil.painted) cnt[1]++;
            if (cnt[1] < 3 && cnt[0] <= 40) return;
            wait.stop();

            maxed = expand;
            bg.arc = maxed ? 0 : 10;
            for (Btn b : maxBtns) b.setGlyph(maxed ? 4 : 3);
            double old = Theme.S, ns = expand ? UiScale.pick(to.width, to.height) : 1.0;
            if (Math.abs(old - ns) >= 0.001) {
                Theme.setScale(ns);
                UiScale.apply(getContentPane(), old, ns);
            }
            bg.frozen = false;
            bg.setSize(to.width, to.height);
            bg.validate();
            veil.newImg = snap(bg, to.width, to.height, sx, sy);
            bg.frozen = true;
            bg.setVisible(false);
            // головне вікно ховається під накладкою: лишається меншим із двох розмірів
            if (!expand) setBounds(to);

            final long t0 = System.nanoTime();
            maxAnim = new javax.swing.Timer(4, null);
            maxAnim.addActionListener(e -> {
                double t = Math.min(1.0, (System.nanoTime() - t0) / MAX_MS), k = ease(t);
                veil.k = k;
                if (t >= 1.0) {
                    maxAnim.stop();
                    maxAnim = null;
                    veil.k = 1;
                    veil.arc = arcTo;
                    veil.r.setBounds(to.x - area.x, to.y - area.y, to.width, to.height);
                    veil.paintImmediately(0, 0, veil.getWidth(), veil.getHeight());
                    if (expand) setBounds(to);
                    bg.frozen = false;
                    bg.setVisible(true);
                    bg.validate();
                    final javax.swing.Timer fin = new javax.swing.Timer(90, e2 -> {
                        overlay.setVisible(false);
                        animating = false;
                        bg.repaint();
                    });
                    fin.setRepeats(false);
                    fin.start();
                    return;
                }
                int x = from.x + (int) Math.round((to.x - from.x) * k), y = from.y + (int) Math.round((to.y - from.y) * k);
                int w = from.width + (int) Math.round((to.width - from.width) * k), h = from.height + (int) Math.round((to.height - from.height) * k);
                veil.r.setBounds(x - area.x, y - area.y, w, h);
                veil.arc = (int) Math.round(arcFrom + (arcTo - arcFrom) * k);
                veil.paintImmediately(0, 0, veil.getWidth(), veil.getHeight());
            });
            maxAnim.start();
        });
        wait.start();
    }

    void toggleMaxPlain() {
        if (animating) return;
        plainAnim = true;
        final boolean expand = !maxed;
        final Rectangle from = getBounds();
        final Rectangle to;
        if (expand) {
            restoreBounds = from;
            GraphicsConfiguration gc = getGraphicsConfiguration();
            Rectangle sb = gc.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            to = new Rectangle(sb.x + in.left, sb.y + in.top, sb.width - in.left - in.right, sb.height - in.top - in.bottom);
        } else {
            to = restoreBounds != null ? new Rectangle(restoreBounds) : new Rectangle(from.x, from.y, BASE_SIZE[0], BASE_SIZE[1]);
        }

        java.awt.geom.AffineTransform dt = getGraphicsConfiguration().getDefaultTransform();
        final double sx = dt.getScaleX(), sy = dt.getScaleY();
        final java.awt.image.BufferedImage oldImg = snap(bg, bg.getWidth(), bg.getHeight(), sx, sy);

        animating = true;
        try { setShape(null); } catch (RuntimeException ignored) { }
        maxed = expand;
        bg.arc = maxed ? 0 : 10;
        for (Btn b : maxBtns) b.setGlyph(maxed ? 4 : 3);

        double old = Theme.S, ns = expand ? UiScale.pick(to.width, to.height) : 1.0;
        if (Math.abs(old - ns) >= 0.001) {
            Theme.setScale(ns);
            UiScale.apply(getContentPane(), old, ns);
        }
        bg.frozen = false;
        bg.setSize(to.width, to.height);
        bg.validate();
        final java.awt.image.BufferedImage newImg = snap(bg, to.width, to.height, sx, sy);
        bg.frozen = true;

        boolean lay = false;
        if (LAYERED_ANIM && (translucent == null || translucent)) {
            try {
                if (getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                    setBackground(new Color(0, 0, 0, 0));
                    lay = true;
                }
            } catch (RuntimeException ignored) { }
            translucent = lay;
        }
        final boolean layered = lay;
        final Rectangle area = layered ? from.union(to) : null;

        final Component oldGlass = getGlassPane();
        final Veil veil = new Veil(oldImg, newImg, layered);
        veil.arc = expand ? 10 : 0;
        if (layered) veil.r.setBounds(from.x - area.x, from.y - area.y, from.width, from.height);
        setGlassPane(veil);
        veil.setVisible(true);
        bg.setVisible(false);
        if (layered) setBounds(area);

        final int arcFrom = expand ? 10 : 0, arcTo = expand ? 0 : 10;
        final long t0 = System.nanoTime();
        maxAnim = new javax.swing.Timer(4, null);
        maxAnim.addActionListener(e -> {
            double t = Math.min(1.0, (System.nanoTime() - t0) / MAX_MS), k = ease(t);
            veil.k = k;
            int x = from.x + (int) Math.round((to.x - from.x) * k), y = from.y + (int) Math.round((to.y - from.y) * k);
            int w = from.width + (int) Math.round((to.width - from.width) * k), h = from.height + (int) Math.round((to.height - from.height) * k);
            if (t >= 1.0) {
                maxAnim.stop();
                veil.k = 1;
                veil.arc = arcTo;
                setBounds(to);
                if (layered) {
                    // спершу малюємо фінальний кадр ще у прозорому вікні нового розміру,
                    // і лише потім робимо вікно непрозорим: так не буває порожнього кадру
                    veil.r.setBounds(0, 0, to.width, to.height);
                    veil.paintImmediately(0, 0, to.width, to.height);
                    veil.layered = false;
                    setBackground(Theme.BG2);
                }
                bg.frozen = false;
                bg.setVisible(true);
                bg.validate();
                veil.paintImmediately(0, 0, to.width, to.height);
                SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
                    setGlassPane(oldGlass);
                    oldGlass.setVisible(false);
                    animating = false;
                    plainAnim = false;
                    shapeUpd.run();
                    bg.repaint();
                    for (java.awt.event.ActionListener al : maxAnim.getActionListeners()) maxAnim.removeActionListener(al);
                    maxAnim = null;
                }));
                return;
            }
            if (layered) {
                veil.r.setBounds(x - area.x, y - area.y, w, h);
                veil.arc = (int) Math.round(arcFrom + (arcTo - arcFrom) * k);
            } else {
                setBounds(x, y, w, h);
                validate();
            }
            veil.paintImmediately(0, 0, veil.getWidth(), veil.getHeight());
        });
        maxAnim.start();
    }

    volatile boolean inGame;
    String curPage = "play";

    void presenceIdle() {
        String d, st;
        switch (curPage) {
            case "mods": d = I18n.t("У вкладці «Моди»"); st = I18n.t("Шукає й встановлює моди"); break;
            case "settings": d = I18n.t("У налаштуваннях"); st = I18n.t("Налаштовує запускач"); break;
            case "console": d = I18n.t("У консолі"); st = I18n.t("Читає записи гри"); break;
            case "gallery": d = I18n.t("У галереї"); st = I18n.t("Дивиться знятки"); break;
            case "loaders": d = I18n.t("Додає версію"); st = I18n.t("Обирає завантажувач"); break;
            default: d = I18n.t("На головній сторінці"); st = I18n.t("Обирає версію"); break;
        }
        rpc.update(d, st, launcherStart);
    }

    void makeDraggable(JComponent c) {
        Point[] start = new Point[1];
        c.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { start[0] = e.getPoint(); }
        });
        c.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (maxed) return;
                Point p = getLocation();
                setLocation(p.x + e.getX() - start[0].x, p.y + e.getY() - start[0].y);
            }
        });
    }

    static String titleOf(String k) {
        switch (k) {
            case "mods": return I18n.t("Моди");
            case "console": return I18n.t("Консоль");
            case "gallery": return I18n.t("Галерея");
            case "loaders": return I18n.t("Додати версію");
            case "settings": return I18n.t("Налаштування");
            default: return I18n.t("Головна");
        }
    }

    void go(int d) {
        int n = hpos + d;
        if (n < 0 || n >= hist.size()) return;
        navigating = true;
        hpos = n;
        showPage(hist.get(n));
        navigating = false;
    }

    void showPage(String key) {
        pages.change(() -> cards.show(pages, key));
        curPage = key;
        if (!inGame) presenceIdle();
        nav.forEach((k, b) -> b.setSel(k.equals(key)));
        if (pageTitle != null) pageTitle.setText(titleOf(key));
        if (!navigating && (hpos < 0 || !hist.get(hpos).equals(key))) {
            while (hist.size() > hpos + 1) hist.remove(hist.size() - 1);
            hist.add(key);
            hpos++;
        }
        if (backB != null) { backB.setEnabled(hpos > 0); fwdB.setEnabled(hpos < hist.size() - 1); }
        if (key.equals("gallery")) refreshGallery();
        if (key.equals("mods") && !modsInit) {
            modsInit = true;
            refreshProfiles(null);
        }
    }

    void exit() {
        try { saveSettings(); } catch (Throwable ignored) { }
        try {
            Thread bt = new Thread(online::bye, "online-bye");
            bt.setDaemon(true);
            bt.start();
            bt.join(800);
        } catch (Throwable ignored) { }
        try { dispose(); } catch (Throwable ignored) { }
        Thread w = new Thread(() -> {
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(0);
        }, "exit-watchdog");
        w.setDaemon(true);
        w.start();
        System.exit(0);
    }

    void openFolder(Path p) {
        try {
            Files.createDirectories(p);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(p.toFile());
        } catch (Exception e) { msg(I18n.t("Не вдалося відкрити папку: ") + p); }
    }

    static JScrollPane vscroll(JComponent c, Color bg) {
        JScrollPane sp = new JScrollPane(c, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getViewport().setBackground(bg);
        sp.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        sp.getVerticalScrollBar().setUnitIncrement(28);
        sp.getVerticalScrollBar().setOpaque(false);
        Smooth.install(sp);
        return sp;
    }

    JPanel sidebar() {
        JPanel side = new JPanel();
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(new EmptyBorder(8, 0, 10, 0));
        side.setPreferredSize(new Dimension(Theme.SIDE_W, 0));

        String[][] top = {{"play", I18n.t("Головна"), "0"}, {"mods", I18n.t("Моди"), "1"}, {"gallery", I18n.t("Галерея"), "6"}, {"console", I18n.t("Консоль"), "3"}};
        for (String[] it : top) {
            NavBtn b = new NavBtn("", it[1], Integer.parseInt(it[2]));
            b.addActionListener(e -> showPage(it[0]));
            nav.put(it[0], b);
            side.add(b);
        }
        side.add(Box.createVerticalStrut(6));
        side.add(new JComponent() {
            { setPreferredSize(new Dimension(Theme.SIDE_W, 9)); setMaximumSize(new Dimension(Theme.SIDE_W, 9)); setAlignmentX(Component.CENTER_ALIGNMENT); }
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Theme.ov(26));
                g.fillRect((getWidth() - 30) / 2, 4, 30, 1);
            }
        });
        side.add(Box.createVerticalStrut(6));
        NavBtn st = new NavBtn("", I18n.t("Налаштування"), 2);
        st.addActionListener(e -> showPage("settings"));
        nav.put("settings", st);
        side.add(st);

        side.add(Box.createVerticalGlue());
        AvatarBtn av = new AvatarBtn();
        av.addActionListener(e -> showLogin());
        avatarBtn = av;
        side.add(av);
        makeDraggable(side);
        return side;
    }

    JPanel topBar(boolean full) {
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setPreferredSize(new Dimension(100, Theme.TITLE_H));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.setBorder(new EmptyBorder(0, 14, 0, 0));
        JLabel ic = new JLabel(new ImageIcon(Theme.icon(24)));
        ic.setAlignmentY(Component.CENTER_ALIGNMENT);
        JLabel nm = label("PaxLauncher", 18, true, Theme.ACC2);
        nm.setAlignmentY(Component.CENTER_ALIGNMENT);
        left.add(ic);
        left.add(Box.createHorizontalStrut(8));
        left.add(nm);
        if (full) {
            left.add(Box.createHorizontalStrut(22));
            backB = new ArrowBtn(-1);
            fwdB = new ArrowBtn(1);
            backB.setAlignmentY(Component.CENTER_ALIGNMENT);
            fwdB.setAlignmentY(Component.CENTER_ALIGNMENT);
            backB.setEnabled(false);
            fwdB.setEnabled(false);
            backB.addActionListener(e -> go(-1));
            fwdB.addActionListener(e -> go(1));
            pageTitle = label(I18n.t("Головна"), 15, true, Theme.TEXT);
            pageTitle.setAlignmentY(Component.CENTER_ALIGNMENT);
            pageTitle.setPreferredSize(new Dimension(220, 24));
            left.add(backB);
            left.add(Box.createHorizontalStrut(6));
            left.add(fwdB);
            left.add(Box.createHorizontalStrut(14));
            left.add(pageTitle);
        }

        JPanel ctl = new JPanel(new BorderLayout());
        ctl.setOpaque(false);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        btns.setOpaque(false);
        Btn min = new Btn("", Btn.Kind.GHOST);
        min.setGlyph(1);
        Btn max = new Btn("", Btn.Kind.GHOST);
        max.setGlyph(maxed ? 4 : 3);
        maxBtns.add(max);
        Btn close = new Btn("", Btn.Kind.DANGER);
        close.setGlyph(2);
        min.addActionListener(e -> setState(Frame.ICONIFIED));
        max.addActionListener(e -> toggleMax());
        close.addActionListener(e -> exit());
        btns.add(min);
        btns.add(max);
        btns.add(close);
        ctl.add(btns, BorderLayout.EAST);

        JPanel links = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, (Theme.TITLE_H - 34) / 2));
        links.setOpaque(false);
        LinkBtn dcL = new LinkBtn(I18n.t("Приєднатися до нашого Discord"), 0);
        LinkBtn donL = new LinkBtn(I18n.t("Підтримати запускач"), 1);
        dcL.addActionListener(e -> browse(DISCORD_URL));
        donL.addActionListener(e -> browse(SUPPORT_URL));
        links.add(dcL);
        links.add(donL);

        top.add(left, BorderLayout.WEST);
        top.add(links, BorderLayout.CENTER);
        top.add(ctl, BorderLayout.EAST);
        makeDraggable(links);
        MouseAdapter dbl = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) toggleMax(); }
        };
        top.addMouseListener(dbl);
        left.addMouseListener(dbl);
        makeDraggable(top);
        makeDraggable(left);
        return top;
    }

    static JLabel header(String text) {
        return label(text, 16, true, Theme.TEXT);
    }

    JPanel accountTile() {
        JPanel t = new JPanel(new BorderLayout(12, 0)) {
            boolean hov;
            { addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hov = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hov = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { showLogin(); }
            }); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(hov ? Theme.CARD_HOV : Theme.CARD);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g.dispose();
            }
        };
        t.setOpaque(false);
        t.setBorder(new EmptyBorder(10, 12, 10, 12));
        t.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JComponent avatar = new JComponent() {
            { setPreferredSize(new Dimension(40, 40)); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.headRound(g, 0, 0, 40, 12);
                g.dispose();
            }
        };
        JPanel names = new JPanel();
        names.setOpaque(false);
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        accName.setFont(Theme.font(Font.BOLD, 15));
        accType.setFont(Theme.font(Font.PLAIN, 12));
        names.add(Box.createVerticalGlue());
        names.add(accName);
        names.add(accType);
        names.add(Box.createVerticalGlue());
        JComponent arrow = new JComponent() {
            { setPreferredSize(new Dimension(20, 40)); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(Theme.MUTED);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = getWidth() / 2, cy = getHeight() / 2;
                g.drawPolyline(new int[]{cx - 7, cx, cx + 7}, new int[]{cy - 3, cy + 4, cy - 3}, 3);
                g.dispose();
            }
        };
        t.add(avatar, BorderLayout.WEST);
        t.add(names, BorderLayout.CENTER);
        t.add(arrow, BorderLayout.EAST);
        return t;
    }

    Path gameDir() {
        String d = dirF.getText().trim();
        return Path.of(d.isEmpty() ? root.toString() : d);
    }

    JPanel rightPanel() {
        VPanel rp = new VPanel();
        rp.setBorder(new EmptyBorder(22, 18, 24, 18));
        rp.row(header(I18n.t("Поточна обліківка")), 0);
        rp.row(accountTile(), 12);

        rp.row(header(I18n.t("Швидкі дії")), 26);
        JPanel acts = new JPanel(new GridLayout(0, 1, 0, 2));
        acts.setOpaque(false);
        ListBtn b1 = new ListBtn(I18n.t("Папка гри"), 5);
        ListBtn b2 = new ListBtn(I18n.t("Моди"), 1);
        ListBtn b3 = new ListBtn(I18n.t("Консоль"), 3);
        ListBtn bg2 = new ListBtn(I18n.t("Галерея"), 6);
        bg2.addActionListener(e -> showPage("gallery"));
        ListBtn b4 = new ListBtn(I18n.t("Налаштування"), 2);
        b1.addActionListener(e -> openFolder(gameDir()));
        b2.addActionListener(e -> showPage("mods"));
        b3.addActionListener(e -> showPage("console"));
        b4.addActionListener(e -> showPage("settings"));
        for (ListBtn b : new ListBtn[]{b1, b2, bg2, b3, b4}) acts.add(b);
        rp.row(acts, 8);

        rp.row(header(I18n.t("Сервери Minecraft")), 26);
        rp.row(new ServerAds(this::browse), 12);

        rp.row(header(I18n.t("Новини Minecraft")), 26);
        rp.row(new NewsPanel(), 12);
        if (Online.configured()) rp.row(onlineBadge, 4);
        rp.end();

        JScrollPane sp = vscroll(rp, Theme.SIDE);
        JPanel wrap = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Theme.SIDE);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.ov(18));
                g.fillRect(0, 0, 1, getHeight());
            }
        };
        wrap.setOpaque(false);
        wrap.setPreferredSize(new Dimension(300, 0));
        wrap.add(sp, BorderLayout.CENTER);
        return wrap;
    }

    JPanel playPage() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);

        VPanel col = new VPanel();
        col.setBorder(new EmptyBorder(24, 28, 30, 28));
        welcomeLbl.setFont(Theme.font(Font.BOLD, 26));
        col.row(welcomeLbl, 0);

        Card jump = new Card(new GridBagLayout(), 16);
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0; g.gridx = 0; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        versions.iconed = true;
        versions.setPreferredSize(new Dimension(280, 58));
        jump.add(versions, g);
        playBtn.setFont(Theme.font(Font.BOLD, 17));
        playBtn.setPreferredSize(new Dimension(170, 58));
        playBtn.setMinimumSize(new Dimension(170, 58));
        g.gridx = 1; g.weightx = 0; g.insets = new Insets(0, 14, 0, 0);
        jump.add(playBtn, g);

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        filters.setOpaque(false);
        for (Check ch : new Check[]{cRel, cSnap, cOld}) {
            ch.setFont(Theme.font(Font.PLAIN, 13));
            ch.setBorder(new EmptyBorder(0, 0, 0, 16));
            ch.addActionListener(e -> { if (!filling) fillVersions(null); });
            filters.add(ch);
        }
        g.gridy = 1; g.gridx = 0; g.weightx = 1; g.insets = new Insets(12, 0, 0, 0);
        jump.add(filters, g);
        small(loadersBtn);
        loadersBtn.setText(I18n.t("Додати версію"));
        loadersBtn.setPlus(true);
        loadersBtn.setPreferredSize(new Dimension(190, 42));
        g.gridx = 1; g.weightx = 0; g.insets = new Insets(12, 14, 0, 0);
        jump.add(loadersBtn, g);

        status.setHorizontalAlignment(SwingConstants.LEFT);
        g.gridy = 2; g.gridx = 0; g.gridwidth = 1; g.weightx = 1; g.insets = new Insets(14, 0, 0, 0);
        jump.add(status, g);
        small(cancelBtn);
        small(pauseBtn);
        cancelBtn.setVisible(false);
        pauseBtn.setVisible(false);
        JPanel cp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        cp.setOpaque(false);
        cp.add(pauseBtn);
        cp.add(cancelBtn);
        g.gridx = 1; g.weightx = 0; g.fill = GridBagConstraints.NONE; g.insets = new Insets(10, 14, 0, 0);
        jump.add(cp, g);
        g.fill = GridBagConstraints.HORIZONTAL; g.gridwidth = 2;
        bar.setPreferredSize(new Dimension(100, 6));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        g.gridy = 3; g.gridx = 0; g.weightx = 1; g.insets = new Insets(8, 0, 0, 0);
        jump.add(bar, g);
        col.row(jump, 12);

        JLabel disc = header(I18n.t("Відкрий для себе  ›"));
        col.row(disc, 30);
        JPanel grid = new JPanel(new GridLayout(0, 2, 16, 16));
        grid.setOpaque(false);
        grid.add(new ArtCard(Art.FIREWORKS, 0.35, I18n.t("Остання версія"),
                I18n.t("Запускай свіжий випуск Minecraft: Java Edition одним натисканням — версії завантажуються самі."),
                () -> {
                    cRel.setSelected(true);
                    fillVersions(null);
                    selectLatestRelease();
                    scrollPlayToTop();
                }));
        grid.add(new ArtCard(Art.STEVE, 0.3, I18n.t("Моди та збірки"),
                I18n.t("Шукай моди та набори ресурсів на Modrinth і став їх у власну збірку прямо із запускача."),
                () -> showPage("mods")));
        grid.add(new ArtCard(Art.CAVE, 0.45, "Fabric, Quilt, Forge, NeoForge",
                I18n.t("Встанови завантажувач модів для будь-якої версії гри — і він з'явиться у списку версій."),
                this::openLoaders));
        grid.add(new ArtCard(Art.MEADOW, 0.5, I18n.t("Налаштуй під себе"),
                I18n.t("Пам'ять, власна Java, роздільна здатність вікна, папка гри та стан у Discord."),
                () -> showPage("settings")));
        col.row(grid, 12);
        col.end();

        playScroll = vscroll(col, Theme.BG2);
        p.add(playScroll, BorderLayout.CENTER);
        p.add(rightPanel(), BorderLayout.EAST);

        playBtn.addActionListener(e -> play());
        cancelBtn.addActionListener(e -> cancelDownload());
        pauseBtn.addActionListener(e -> togglePause());
        loadersBtn.addActionListener(e -> openLoaders());
        return p;
    }

    JPanel consolePage() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(24, 28, 28, 28));
        p.add(consolePanel(), BorderLayout.CENTER);
        return p;
    }

    static final int[] PRESETS = {0x1BD96A, 0x2FA4FF, 0x6C7BFF, 0xB26BFF, 0xFF5C9A, 0xFF5A5F, 0xFF9A3C, 0xFFD23F, 0x1FD3C6};
    final JPanel themeModeP = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    final JPanel themeColorP = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));

    final JPanel langP = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));

    void buildLangControls() {
        langP.setOpaque(false);
        langP.removeAll();
        Combo<String> cb = new Combo<>();
        int sel = 0;
        for (int i = 0; i < I18n.LANGS.length; i++) {
            cb.addItem(I18n.LANGS[i][1]);
            if (I18n.LANGS[i][0].equals(I18n.code())) sel = i;
        }
        cb.setSelectedIndex(sel);
        cb.setPreferredSize(new Dimension(220, 40));
        cb.addActionListener(e -> {
            int i = cb.getSelectedIndex();
            if (i >= 0) chooseLang(I18n.LANGS[i][0]);
        });
        langP.add(cb);
        langP.revalidate();
        langP.repaint();
    }

    void chooseLang(String code) {
        if (cfg.get("lang", "uk").equals(code)) return;
        cfg.set("lang", code);
        cfg.save();
        if (code.equals(I18n.code())) return;
        String name = I18n.nameOf(code);
        String now = I18n.restartNote(I18n.code(), name);
        String next = I18n.restartNote(code, name);
        msg(now.equals(next) ? now : now + "\n\n" + next);
    }

    void buildThemeControls() {
        final double sc = Theme.S;
        Theme.S = 1.0;
        themeModeP.setOpaque(false);
        themeColorP.setOpaque(false);
        themeModeP.removeAll();
        themeColorP.removeAll();
        Btn dk = small(new Btn(I18n.t("Темна"), Theme.light ? Btn.Kind.SECONDARY : Btn.Kind.PRIMARY));
        Btn lt = small(new Btn(I18n.t("Світла"), Theme.light ? Btn.Kind.PRIMARY : Btn.Kind.SECONDARY));
        dk.addActionListener(e -> { if (Theme.light) changeTheme(false, Theme.accent); });
        lt.addActionListener(e -> { if (!Theme.light) changeTheme(true, Theme.accent); });
        themeModeP.add(dk);
        themeModeP.add(lt);
        for (int rgb : PRESETS) {
            Color c = new Color(rgb);
            themeColorP.add(new Swatch(c, () -> changeTheme(Theme.light, c)));
        }
        Btn custom = small(new Btn(I18n.t("Свій колір…"), Btn.Kind.SECONDARY));
        custom.addActionListener(e -> pickColor());
        themeColorP.add(custom);
        Theme.S = sc;
        if (Math.abs(sc - 1.0) > 0.001) {
            for (Component c : themeModeP.getComponents()) UiScale.apply(c, 1.0, sc);
            for (Component c : themeColorP.getComponents()) UiScale.apply(c, 1.0, sc);
        }
        themeModeP.revalidate();
        themeModeP.repaint();
        themeColorP.revalidate();
        themeColorP.repaint();
    }

    void changeTheme(boolean light, Color accent) {
        Color[] old = Theme.palette();
        Theme.apply(light, accent);
        Theme.applyUI();
        Map<Integer, Color> m = Theme.remap(old);
        setBackground(Theme.BG2);
        Theme.recolor(getContentPane(), m);
        cfg.set("theme", light ? "light" : "dark");
        cfg.set("accent", String.format("%06X", accent.getRGB() & 0xFFFFFF));
        cfg.save();
        buildThemeControls();
        revalidate();
        repaint();
    }

    void pickColor() {
        ColorPick pick = new ColorPick(Theme.accent);
        Field hex = new Field(String.format("%06X", Theme.accent.getRGB() & 0xFFFFFF));
        hex.setColumns(7);
        boolean[] lock = {false};
        pick.onChange = c -> {
            lock[0] = true;
            hex.setText(String.format("%06X", c.getRGB() & 0xFFFFFF));
            lock[0] = false;
        };
        hex.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void upd() {
                if (lock[0]) return;
                String t = hex.getText().trim().replace("#", "");
                if (t.matches("[0-9a-fA-F]{6}")) pick.set(new Color(Integer.parseInt(t, 16)));
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
        });
        JPanel hexRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        hexRow.setOpaque(false);
        hexRow.add(label("HEX  #", 14, false, Theme.MUTED));
        hexRow.add(hex);
        hexRow.add(new JComponent() {
            { setPreferredSize(new Dimension(44, 40)); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(pick.get());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g.dispose();
            }
        });
        JPanel box = new JPanel(new BorderLayout(0, 14));
        box.setOpaque(false);
        box.add(pick, BorderLayout.CENTER);
        box.add(hexRow, BorderLayout.SOUTH);
        if (Dlg.show(this, I18n.t("Колір теми"), box, I18n.t("Застосувати"), I18n.t("Скасувати"))) changeTheme(Theme.light, pick.get());
    }

    void row(JPanel c, int y, String text, JComponent comp, JComponent extra) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = y; g.anchor = GridBagConstraints.WEST;
        g.gridx = 0; g.insets = new Insets(8, 0, 8, 14);
        JLabel l = label(text, 14, false, Theme.MUTED);
        l.setPreferredSize(new Dimension(230, 24));
        c.add(l, g);
        g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(8, 0, 8, extra != null ? 10 : 0);
        c.add(comp, g);
        if (extra != null) {
            g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE; g.insets = new Insets(8, 0, 8, 0);
            c.add(extra, g);
        }
    }

    JPanel settingsPage() {
        JPanel p = new JPanel(new BorderLayout(0, 18));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(24, 28, 26, 28));
        p.add(label(I18n.t("Налаштування"), 26, true, Theme.TEXT), BorderLayout.NORTH);

        Card c = new Card(new GridBagLayout(), 20);

        Btn javaBrowse = new Btn(I18n.t("Огляд"), Btn.Kind.SECONDARY);
        javaBrowse.addActionListener(e -> {
            File jStart = null;
            try {
                String cur = javaPath();
                if (!cur.isEmpty() && Path.of(cur).getParent() != null && Files.isDirectory(Path.of(cur).getParent()))
                    jStart = Path.of(cur).getParent().toFile();
            } catch (Exception ignored) { }
            File[] jRes = FilePick.show(this, I18n.t("Обери java (або папку JDK)"), JFileChooser.FILES_AND_DIRECTORIES, false, jStart);
            if (jRes != null && jRes.length > 0) javaF.setText(jRes[0].getAbsolutePath());
        });
        javaC.setPreferredSize(new Dimension(300, 40));
        javaC.wideList = true;
        javaF.setToolTipText(I18n.t("Шлях до java (або до папки JDK). Порожньо — автоматичний вибір Java під версію гри."));
        JPanel javaP = new JPanel(new BorderLayout(10, 0));
        javaP.setOpaque(false);
        javaP.add(javaC, BorderLayout.WEST);
        javaP.add(javaF, BorderLayout.CENTER);
        javaC.addItem(javaAuto);
        javaC.addItem(javaCustom);
        javaC.addActionListener(e -> {
            if (javaSync) return;
            Object o = javaC.getSelectedItem();
            if (o == javaCustom) javaF.requestFocusInWindow();
            else if (o instanceof JavaFinder.Inst) {
                javaSync = true;
                javaF.setText(((JavaFinder.Inst) o).path());
                javaSync = false;
            }
        });
        javaF.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { syncJavaCombo(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { syncJavaCombo(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { syncJavaCombo(); }
        });
        syncJavaCombo();
        new Thread(() -> {
            List<JavaFinder.Inst> found = JavaFinder.scan();
            SwingUtilities.invokeLater(() -> fillJava(found));
        }, "java-scan").start();
        row(c, 0, "Java", javaP, javaBrowse);
        row(c, 1, I18n.t("Додаткові JVM-аргументи"), jvmF, null);

        ram.setOpaque(false);
        ram.setUI(new SlimSliderUI(ram));
        long totalMb = totalMemMb();
        ram.setMaximum((int) Math.max(4, Math.min(64, totalMb / 512)));
        ram.addChangeListener(e -> ramLbl.setText(ram.getValue() * 512 + " MB"));
        ramLbl.setPreferredSize(new Dimension(90, 24));
        JPanel ramP = new JPanel(new BorderLayout(12, 0));
        ramP.setOpaque(false);
        ramP.add(ram, BorderLayout.CENTER);
        ramP.add(ramLbl, BorderLayout.EAST);
        row(c, 2, I18n.t("Виділена пам'ять (ОЗП)"), ramP, null);

        wF.setColumns(6); hF.setColumns(6);
        JPanel res = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        res.setOpaque(false);
        res.add(wF);
        res.add(label("×", 16, true, Theme.MUTED));
        res.add(hF);
        row(c, 3, I18n.t("Роздільна здатність вікна"), res, null);

        Btn dirBrowse = new Btn(I18n.t("Огляд"), Btn.Kind.SECONDARY);
        dirBrowse.addActionListener(e -> {
            File dStart = null;
            try { File cur = new File(dirF.getText().trim()); if (cur.isDirectory()) dStart = cur; } catch (Exception ignored) { }
            File[] dRes = FilePick.show(this, I18n.t("Ігрова папка (saves, mods)"), JFileChooser.DIRECTORIES_ONLY, false, dStart);
            if (dRes != null && dRes.length > 0) dirF.setText(dRes[0].getAbsolutePath());
        });
        row(c, 4, I18n.t("Ігрова папка (saves, mods)"), dirF, dirBrowse);

        JPanel hideP = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        hideP.setOpaque(false);
        hideP.add(hideC);
        discC.setBorder(new EmptyBorder(0, 24, 0, 0));
        hideP.add(discC);
        row(c, 8, I18n.t("Поведінка"), hideP, null);
        if (Online.configured()) {
            JPanel onlineP = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            onlineP.setOpaque(false);
            onlineP.add(onlineC);
            row(c, 8, I18n.t("Приватність"), onlineP, null);
        }

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        btns.setOpaque(false);
        Btn openGame = small(new Btn(I18n.t("Папка гри"), Btn.Kind.SECONDARY));
        Btn openMods = small(new Btn(I18n.t("Папка модів"), Btn.Kind.SECONDARY));
        Btn save = small(new Btn(I18n.t("Зберегти"), Btn.Kind.PRIMARY));
        openGame.addActionListener(e -> openFolder(Path.of(dirF.getText().trim().isEmpty() ? root.toString() : dirF.getText().trim())));
        openMods.addActionListener(e -> openFolder(Path.of(dirF.getText().trim().isEmpty() ? root.toString() : dirF.getText().trim()).resolve("mods")));
        save.addActionListener(e -> {
            saveSettings();
            String j = javaPath();
            boolean bad = !j.isEmpty() && (j.contains("/") || j.contains("\\")) && !Files.isRegularFile(Path.of(j));
            status.setText(bad ? I18n.t("Збережено, але файл Java не знайдено: ") + j : I18n.t("Налаштування збережено"));
        });
        btns.add(openGame);
        btns.add(openMods);
        btns.add(save);
        buildThemeControls();
        row(c, 9, I18n.t("Тема інтерфейсу"), themeModeP, null);
        row(c, 10, I18n.t("Колір теми"), themeColorP, null);
        buildLangControls();
        row(c, 11, I18n.t("Мова"), langP, null);
        row(c, 12, "", btns, null);

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(c, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);
        return p;
    }

    static long totalMemMb() {
        try {
            return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize() / 1048576L;
        } catch (Throwable t) { return 8192; }
    }

    JPanel consolePanel() {
        Card c = new Card(new BorderLayout(0, 10), 14);

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(label(I18n.t("Консоль"), 14, true, Theme.TEXT), BorderLayout.WEST);
        Btn clear = small(new Btn(I18n.t("Очистити"), Btn.Kind.GHOST));
        clear.setBorder(new EmptyBorder(5, 12, 5, 12));
        clear.addActionListener(e -> console.setText(""));
        head.add(clear, BorderLayout.EAST);
        c.add(head, BorderLayout.NORTH);

        Color dark = Theme.INPUT;
        console.setEditable(false);
        console.setLineWrap(true);
        console.setWrapStyleWord(true);
        console.setOpaque(false);
        console.setForeground(Theme.CONSOLE_FG);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setBorder(new EmptyBorder(4, 6, 4, 6));
        JScrollPane sp = new JScrollPane(console); Smooth.install(sp);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getViewport().setBackground(dark);
        sp.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());

        JPanel box = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(Theme.INPUT);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g.dispose();
            }
        };
        box.setOpaque(false);
        box.setBorder(new EmptyBorder(6, 6, 6, 6));
        box.add(sp, BorderLayout.CENTER);
        c.add(box, BorderLayout.CENTER);
        return c;
    }

    void fillJava(List<JavaFinder.Inst> found) {
        javaSync = true;
        javaC.removeAllItems();
        javaC.addItem(javaAuto);
        for (JavaFinder.Inst i : found) javaC.addItem(i);
        javaC.addItem(javaCustom);
        javaSync = false;
        syncJavaCombo();
    }

    void syncJavaCombo() {
        if (javaSync) return;
        String t = javaF.getText().trim();
        JavaFinder.Inst pick = javaCustom;
        if (t.isEmpty()) pick = javaAuto;
        else for (int i = 0; i < javaC.getItemCount(); i++) {
            JavaFinder.Inst it = javaC.getItemAt(i);
            if (it.path() != null && !it.path().isEmpty() && it.path().equalsIgnoreCase(t)) { pick = it; break; }
        }
        javaSync = true;
        javaC.setSelectedItem(pick);
        javaSync = false;
    }

    String javaPath() {
        String t = javaF.getText().trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1).trim();
        if (t.isEmpty()) return "";
        try {
            Path p = Path.of(t);
            if (Files.isDirectory(p)) {
                String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
                Path c = p.resolve("bin").resolve(exe);
                if (Files.isRegularFile(c)) return c.toString();
                c = p.resolve(exe);
                if (Files.isRegularFile(c)) return c.toString();
            }
        } catch (Exception ignored) { }
        return t;
    }

    void loadSettings() {
        String did = cfg.get("discord", "").trim();
        discF.setText(did.isEmpty() ? Theme.DISCORD_ID : did);
        discC.setSelected(cfg.getb("discordOn", true));
        onlineC.setSelected(cfg.getb("onlineOn", true));
        javaF.setText(cfg.get("java", ""));
        syncJavaCombo();
        jvmF.setText(cfg.get("jvm", ""));
        long totalMb = totalMemMb();
        int def = (int) Math.max(1024, Math.min(4096, totalMb / 2));
        ram.setValue(Math.max(ram.getMinimum(), Math.min(ram.getMaximum(), cfg.geti("ram", def) / 512)));
        ramLbl.setText(ram.getValue() * 512 + " MB");
        int w = cfg.geti("w", 0), h = cfg.geti("h", 0);
        wF.setText(w > 0 ? String.valueOf(w) : "");
        hF.setText(h > 0 ? String.valueOf(h) : "");
        dirF.setText(cfg.get("gameDir", root.toString()));
        hideC.setSelected(cfg.getb("hide", true));
        cRel.setSelected(cfg.getb("rel", true));
        cSnap.setSelected(cfg.getb("snap", false));
        cOld.setSelected(cfg.getb("old", false));
    }

    void saveSettings() {
        if (discF.getText().trim().isEmpty()) discF.setText(Theme.DISCORD_ID);
        cfg.set("discord", discF.getText().trim());
        cfg.set("discordOn", String.valueOf(discC.isSelected()));
        cfg.set("onlineOn", String.valueOf(onlineC.isSelected()));
        cfg.set("java", javaPath());
        cfg.set("jvm", jvmF.getText().trim());
        cfg.set("ram", String.valueOf(ram.getValue() * 512));
        cfg.set("w", wF.getText().trim().matches("\\d+") ? wF.getText().trim() : "0");
        cfg.set("h", hF.getText().trim().matches("\\d+") ? hF.getText().trim() : "0");
        cfg.set("gameDir", dirF.getText().trim().isEmpty() ? root.toString() : dirF.getText().trim());
        cfg.set("hide", String.valueOf(hideC.isSelected()));
        cfg.set("rel", String.valueOf(cRel.isSelected()));
        cfg.set("snap", String.valueOf(cSnap.isSelected()));
        cfg.set("old", String.valueOf(cOld.isSelected()));
        Object sel = versions.getSelectedItem();
        if (sel instanceof VItem) cfg.set("version", ((VItem) sel).key());
        cfg.save();
        rpc.configure(discC.isSelected() ? discF.getText().trim() : "");
    }

    String selectedId() {
        Object o = versions.getSelectedItem();
        return o instanceof VItem ? ((VItem) o).id() : null;
    }

    void refreshVersions() {
        status.setText(I18n.t("Завантаження списку версій…"));
        new Thread(() -> {
            try { engine.loadManifest(); } catch (Exception e) { log(I18n.t("Не вдалося отримати список версій: ") + e.getMessage()); }
            SwingUtilities.invokeLater(() -> {
                fillVersions(null);
                fillLoaderMc();
                status.setText(I18n.t("Готово до запуску"));
            });
        }, "versions").start();
    }

    void cancelUi(boolean on, Btn... bs) {
        engine.paused = false;
        for (Btn b : bs) {
            b.setEnabled(true);
            b.setVisible(on);
            if (b.getText().equals(I18n.t("Продовжити"))) b.setText(I18n.t("Пауза"));
        }
    }

    void togglePause() {
        boolean p = !engine.paused;
        engine.paused = p;
        for (Btn b : new Btn[]{pauseBtn, lPause, pPause}) b.setText(p ? I18n.t("Продовжити") : I18n.t("Пауза"));
        String st = p ? I18n.t("Завантаження на паузі") : I18n.t("Продовжую завантаження…");
        status.setText(st);
        lStatus.setText(st);
        mStatus.setText(st);
    }

    void cancelDownload() {
        engine.cancel();
        for (Btn b : new Btn[]{cancelBtn, lCancel, pCancel, pauseBtn, lPause, pPause}) b.setEnabled(false);
        status.setText(I18n.t("Скасовую завантаження…"));
        lStatus.setText(I18n.t("Скасовую завантаження…"));
        mStatus.setText(I18n.t("Скасовую завантаження…"));
    }

    void scrollPlayToTop() {
        if (playScroll == null) return;
        JScrollBar sb = playScroll.getVerticalScrollBar();
        if (sb.getValue() <= 0) return;
        double[] pos = {sb.getValue()};
        javax.swing.Timer t = new javax.swing.Timer(8, null);
        t.addActionListener(e -> {
            pos[0] += (0 - pos[0]) * 0.16;
            if (pos[0] < 1) { pos[0] = 0; t.stop(); }
            sb.setValue((int) Math.round(pos[0]));
        });
        t.start();
    }

    void selectLatestRelease() {
        String id = engine.latestRelease;
        if (id == null || id.isEmpty()) {
            for (Engine.VInfo vi : engine.manifest) if (vi.type().equals("release")) { id = vi.id(); break; }
        }
        if (id == null || id.isEmpty()) { status.setText(I18n.t("Список версій ще завантажується — спробуй за мить")); return; }
        for (int i = 0; i < versions.getItemCount(); i++) {
            VItem it = versions.getItemAt(i);
            if (it.profile() == null && it.id().equals(id)) {
                versions.setSelectedIndex(i);
                status.setText(I18n.t("Обрано останній випуск: ") + id);
                return;
            }
        }
        status.setText(I18n.t("Останній випуск: ") + id + I18n.t(" — його ще немає у списку"));
    }

    Set<String> hiddenIds() {
        Set<String> r = new HashSet<>();
        Path d = root.resolve("instances");
        if (!Files.isDirectory(d)) return r;
        try (var st = Files.list(d)) {
            st.forEach(p -> {
                Path pf = p.resolve("profile.properties");
                if (!Files.isRegularFile(pf)) return;
                Properties pr = new Properties();
                try (Reader rd = Files.newBufferedReader(pf, StandardCharsets.UTF_8)) { pr.load(rd); } catch (IOException e) { return; }
                for (String x : pr.getProperty("hide", "").split(",")) if (!x.isBlank()) r.add(x.trim());
            });
        } catch (IOException ignored) { }
        return r;
    }

    void unhideVersion(String id) {
        Path d = root.resolve("instances");
        if (!Files.isDirectory(d)) return;
        try (var st = Files.list(d)) {
            st.forEach(p -> {
                Path pf = p.resolve("profile.properties");
                if (!Files.isRegularFile(pf)) return;
                Properties pr = new Properties();
                try (Reader rd = Files.newBufferedReader(pf, StandardCharsets.UTF_8)) { pr.load(rd); } catch (IOException e) { return; }
                String h = pr.getProperty("hide", "");
                if (h.isEmpty()) return;
                List<String> keep = new ArrayList<>();
                boolean ch = false;
                for (String x : h.split(",")) {
                    if (x.isBlank()) continue;
                    if (x.trim().equals(id)) ch = true; else keep.add(x.trim());
                }
                if (!ch) return;
                if (keep.isEmpty()) pr.remove("hide"); else pr.setProperty("hide", String.join(",", keep));
                try (Writer w = Files.newBufferedWriter(pf, StandardCharsets.UTF_8)) { pr.store(w, "PaxLauncher profile"); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    void fillVersions(String want) {
        if (want == null) { Object so = versions.getSelectedItem(); if (so instanceof VItem) want = ((VItem) so).key(); }
        if (want == null) want = cfg.get("version", "");
        filling = true;
        versions.removeAllItems();
        List<String> installed = engine.installedIds();
        Set<String> inst = new HashSet<>(installed);
        List<VItem> items = new ArrayList<>();
        for (Prof pf : loadProfiles()) items.add(new VItem(pf.versionId(), "custom", true, pf.name()));
        Set<String> hidden = hiddenIds();
        for (String id : installed) if (!engine.urls.containsKey(id) && !hidden.contains(id)) items.add(new VItem(id, "custom", true, null));
        for (Engine.VInfo vi : engine.manifest) {
            boolean ok;
            switch (vi.type()) {
                case "release": ok = cRel.isSelected(); break;
                case "snapshot": ok = cSnap.isSelected(); break;
                default: ok = cOld.isSelected();
            }
            if (ok || inst.contains(vi.id())) items.add(new VItem(vi.id(), vi.type(), inst.contains(vi.id()), null));
        }
        VItem pick = null;
        for (VItem it : items) {
            versions.addItem(it);
            if (pick == null && it.key().equals(want)) pick = it;
        }
        if (pick != null) versions.setSelectedItem(pick);
        else if (!items.isEmpty()) versions.setSelectedIndex(0);
        filling = false;
    }

    void setBusy(boolean b) {
        busy = b;
        playBtn.setEnabled(!b);
        loadersBtn.setEnabled(!b);
        lInstall.setEnabled(!b && lVer.getItemCount() > 0);
        playBtn.setText(b ? I18n.t("Зачекай…") : I18n.t("Грати"));
    }

    JPanel loadersPage() {
        JPanel p = new JPanel(new BorderLayout(0, 18));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(24, 28, 26, 28));

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        JLabel t = label(I18n.t("Додати версію"), 26, true, Theme.TEXT);
                t.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(t);
        head.add(Box.createVerticalStrut(4));
                Btn back = small(new Btn(I18n.t("Назад"), Btn.Kind.SECONDARY));
        back.addActionListener(e -> showPage("play"));
        JPanel backP = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        backP.setOpaque(false);
        backP.add(back);
        JPanel hw = new JPanel(new BorderLayout());
        hw.setOpaque(false);
        hw.add(head, BorderLayout.WEST);
        hw.add(backP, BorderLayout.EAST);
        p.add(hw, BorderLayout.NORTH);

        lType.addItem("Vanilla");
        lType.addItem("Fabric");
        lType.addItem("Quilt");
        lType.addItem("Forge");
        lType.addItem("NeoForge");
        for (JComponent cb : new JComponent[]{lMc, lType, lVer}) cb.setPreferredSize(new Dimension(300, 44));

        Card c = new Card(new GridBagLayout(), 22);
        row(c, 0, I18n.t("Версія Minecraft"), lMc, null);
        row(c, 1, I18n.t("Завантажувач"), lType, null);
        row(c, 2, I18n.t("Версія завантажувача"), lVer, null);
        JPanel ib = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        ib.setBorder(new EmptyBorder(0, -12, 0, 0));
        ib.setOpaque(false);
        ib.add(lInstall);
        small(lCancel);
        small(lPause);
        lCancel.setVisible(false);
        lPause.setVisible(false);
        ib.add(lPause);
        ib.add(lCancel);
        row(c, 3, "", ib, null);

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(c, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        lStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(lStatus);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(lBar);
        p.add(bottom, BorderLayout.SOUTH);

        lMc.addActionListener(e -> { if (!lFilling) loadLoaderVersions(); });
        lType.addActionListener(e -> { if (!lFilling) loadLoaderVersions(); });
        lInstall.addActionListener(e -> installLoader());
        lCancel.addActionListener(e -> cancelDownload());
        lPause.addActionListener(e -> togglePause());
        lInstall.setEnabled(false);
        return p;
    }

    void fillLoaderMc() {
        Object want = lMc.getSelectedItem();
        lFilling = true;
        lMc.removeAllItems();
        for (Engine.VInfo vi : engine.manifest) if (vi.type().equals("release")) lMc.addItem(vi.id());
        if (want != null) lMc.setSelectedItem(want);
        lFilling = false;
        loadLoaderVersions();
    }

    void openLoaders() {
        String id = selectedId();
        if (id != null && engine.manifest.stream().anyMatch(v -> v.id().equals(id) && v.type().equals("release"))) {
            lFilling = true;
            lMc.setSelectedItem(id);
            lFilling = false;
            loadLoaderVersions();
        }
        showPage("loaders");
    }

    void loadLoaderVersions() {
        Object mo = lMc.getSelectedItem(), to = lType.getSelectedItem();
        int my = lSeq.incrementAndGet();
        lVer.removeAllItems();
        lInstall.setEnabled(false);
        if (mo == null || to == null) return;
        String mc = (String) mo, type = (String) to;
        lStatus.setText(I18n.t("Завантаження списку версій ") + type + "…");
        new Thread(() -> {
            List<Engine.LV> list = null;
            String err = null;
            try { list = engine.loaderVersions(type, mc); } catch (Exception ex) { err = ex.getMessage(); }
            List<Engine.LV> res = list;
            String er = err;
            SwingUtilities.invokeLater(() -> {
                if (my != lSeq.get()) return;
                lVer.removeAllItems();
                if (res != null) for (Engine.LV lv : res) lVer.addItem(lv);
                boolean ok = res != null && !res.isEmpty();
                if (ok) {
                    for (int i = 0; i < res.size(); i++)
                        if (res.get(i).label().contains("recommended")) { lVer.setSelectedIndex(i); break; }
                }
                lInstall.setEnabled(ok && !busy);
                lStatus.setText(er != null ? I18n.t("Не вдалося отримати список: ") + er
                        : ok ? I18n.t("Доступно версій: ") + res.size() : type + I18n.t(" не підтримує Minecraft ") + mc);
            });
        }, "loader-versions").start();
    }

    void installLoader() {
        Object mo = lMc.getSelectedItem(), to = lType.getSelectedItem(), vo = lVer.getSelectedItem();
        if (mo == null || to == null || !(vo instanceof Engine.LV)) {
            msg(I18n.t("Обери версію Minecraft, завантажувач і його версію."));
            return;
        }
        String mc = (String) mo, type = (String) to, ver = ((Engine.LV) vo).version();
        String java = javaPath();
        setBusy(true);
        lBar.set(0.05);
        engine.stop = false;
        cancelUi(true, lCancel, lPause);
        Engine.Progress pg = (t, f) -> SwingUtilities.invokeLater(() -> { lStatus.setText(t); lBar.set(f); });
        new Thread(() -> {
            try {
                String id = engine.installLoader(type, mc, ver, java, pg);
                log(I18n.t("Встановлено: ") + id);
                try {
                    pg.update(I18n.t("Завантаження файлів гри для ") + id + "…", 0);
                    engine.build(engine.loadVersion(id), cfg, Accounts.offline("Player"), pg, root);
                } catch (Engine.Cancelled cx) { throw cx;
                } catch (Exception pre) { log(I18n.t("Попереднє завантаження файлів не вдалося (буде при запуску): ") + pre.getMessage()); }
                unhideVersion(id);
                SwingUtilities.invokeLater(() -> {
                    fillVersions(id);
                    lStatus.setText(I18n.t("Обери версію Minecraft і завантажувач"));
                    status.setText(" ");
                    lBar.set(0);
                    bar.set(0);
                    showPage("play");
                });
            } catch (Engine.Cancelled cx) {
                log(I18n.t("Встановлення скасовано"));
                SwingUtilities.invokeLater(() -> { lStatus.setText(I18n.t("Завантаження скасовано")); lBar.set(0); });
            } catch (Exception ex) {
                log(I18n.t("Помилка встановлення: ") + ex);
                SwingUtilities.invokeLater(() -> {
                    lStatus.setText(I18n.t("Помилка встановлення"));
                    lBar.set(0);
                    msg(I18n.t("Не вдалося встановити ") + type + ":\n" + ex.getMessage());
                });
            } finally {
                SwingUtilities.invokeLater(() -> { setBusy(false); cancelUi(false, lCancel, lPause); });
            }
        }, "loader-install").start();
    }

    Path instDir(String name) { return root.resolve("instances").resolve(name); }

    List<Prof> loadProfiles() {
        List<Prof> r = new ArrayList<>();
        Path d = root.resolve("instances");
        if (!Files.isDirectory(d)) return r;
        try (var st = Files.list(d)) {
            st.filter(Files::isDirectory).sorted().forEach(p -> {
                Path pf = p.resolve("profile.properties");
                if (!Files.exists(pf)) return;
                Properties pr = new Properties();
                try (Reader rd = Files.newBufferedReader(pf, StandardCharsets.UTF_8)) { pr.load(rd); } catch (IOException e) { return; }
                String ver = pr.getProperty("version", "");
                if (ver.isEmpty()) return;
                r.add(new Prof(p.getFileName().toString(), ver, pr.getProperty("mc", ""), pr.getProperty("loader", "Vanilla")));
            });
        } catch (IOException ignored) { }
        return r;
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    static String fmt(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1e6);
        if (n >= 1000) return String.format("%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) { msg(I18n.t("Відкрий вручну: ") + url); }
    }

    static Btn small(Btn b) {
        b.setBorder(new EmptyBorder(10, 16, 10, 16));
        b.setFont(Theme.font(Font.BOLD, 13));
        return b;
    }

    Prof curProf() { Object o = mProf.getSelectedItem(); return o instanceof Prof ? (Prof) o : null; }

    void refreshProfiles(String select) {
        Prof cur = curProf();
        String want = select != null ? select : cur != null ? cur.name() : cfg.get("modProfile", "");
        mFilling = true;
        mProf.removeAllItems();
        Prof pick = null;
        for (Prof p : loadProfiles()) {
            mProf.addItem(p);
            if (p.name().equals(want)) pick = p;
        }
        if (pick != null) mProf.setSelectedItem(pick);
        mFilling = false;
        onProfileChanged();
    }

    void onProfileChanged() {
        Prof p = curProf();
        mSeq.incrementAndGet();
        mfSeq.incrementAndGet();
        mModel.clear();
        mFiles.removeAllItems();
        mInstall.setEnabled(false);
        mMore.setEnabled(false);
        showDetails(null);
        if (p == null) {
            setModInfo(null);
            iModel.clear();
            mStatus.setText(I18n.t("Створи збірку і шукай моди"));
            updateEmptyState();
            return;
        }
        updateEmptyState();
        cfg.set("modProfile", p.name());
        ModApi.Kind k = curKind();
        setModInfo(k.isMod() && p.loader().equals("Vanilla")
                ? I18n.t("Чиста збірка не підтримує моди. Створи збірку на основі Fabric / Quilt / Forge / NeoForge (набори ресурсів працюють і в чистій).")
                : null);
        refreshInstalled();
        if (modsInit && (!k.isMod() || !p.loader().equals("Vanilla"))) search(false, true);
    }

    void newProfile() {
        if (engine.manifest.isEmpty()) { msg(I18n.t("Список версій Minecraft ще завантажується — спробуй за мить.")); return; }
        List<String> installedLd = new ArrayList<>();
        for (String id : engine.installedIds()) {
            String ld = Engine.detectLoader(id);
            if (ld.equals("Fabric") || ld.equals("Quilt") || ld.equals("Forge") || ld.equals("NeoForge")) installedLd.add(id);
        }
        Field name = new Field("");
        Combo<String> mode = new Combo<>();
        mode.addItem(I18n.t("Обрати версію гри й завантажувач"));
        if (!installedLd.isEmpty()) mode.addItem(I18n.t("Взяти вже встановлену версію"));
        Combo<String> mc = new Combo<>();
        for (Engine.VInfo vi : engine.manifest) if (vi.type().equals("release")) mc.addItem(vi.id());
        Combo<String> type = new Combo<>();
        for (String ty : new String[]{"Fabric", "Quilt", "Forge", "NeoForge"}) type.addItem(ty);
        Combo<Engine.LV> lv = new Combo<>();
        Combo<String> have = new Combo<>();
        for (String id : installedLd) have.addItem(id);
        JLabel hint = label(" ", 12, false, Theme.MUTED);
        for (JComponent c : new JComponent[]{name, mode, mc, type, lv, have}) {
            c.setPreferredSize(new Dimension(340, 42));
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        }

        JPanel manual = new JPanel();
        manual.setOpaque(false);
        manual.setLayout(new BoxLayout(manual, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{
                label(I18n.t("Версія Minecraft"), 13, true, Theme.MUTED), mc,
                label(" ", 6, false, Theme.MUTED),
                label(I18n.t("Завантажувач"), 13, true, Theme.MUTED), type,
                label(" ", 6, false, Theme.MUTED),
                label(I18n.t("Версія завантажувача"), 13, true, Theme.MUTED), lv}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            manual.add(c);
        }
        JPanel existing = new JPanel();
        existing.setOpaque(false);
        existing.setLayout(new BoxLayout(existing, BoxLayout.Y_AXIS));
        JLabel hl = label(I18n.t("Встановлена версія"), 13, true, Theme.MUTED);
        hl.setAlignmentX(Component.LEFT_ALIGNMENT);
        have.setAlignmentX(Component.LEFT_ALIGNMENT);
        existing.add(hl);
        existing.add(have);
        existing.add(Box.createVerticalGlue());
        CardLayout modeCl = new CardLayout();
        JPanel modeP = new JPanel(modeCl);
        modeP.setOpaque(false);
        modeP.add(manual, "manual");
        modeP.add(existing, "have");
        mode.addActionListener(e -> modeCl.show(modeP, mode.getSelectedIndex() == 0 ? "manual" : "have"));

        AtomicInteger seq = new AtomicInteger();
        Runnable loadLv = () -> {
            Object mo = mc.getSelectedItem(), to = type.getSelectedItem();
            int my = seq.incrementAndGet();
            lv.removeAllItems();
            if (mo == null || to == null) return;
            hint.setText(I18n.t("Завантаження списку версій ") + to + "…");
            new Thread(() -> {
                List<Engine.LV> list = null;
                String err = null;
                try { list = engine.loaderVersions((String) to, (String) mo); } catch (Exception ex) { err = ex.getMessage(); }
                List<Engine.LV> res = list;
                String er = err;
                SwingUtilities.invokeLater(() -> {
                    if (my != seq.get()) return;
                    lv.removeAllItems();
                    if (res != null) for (Engine.LV x : res) lv.addItem(x);
                    boolean ok = res != null && !res.isEmpty();
                    if (ok) {
                        for (int i = 0; i < res.size(); i++)
                            if (res.get(i).label().contains("recommended")) { lv.setSelectedIndex(i); break; }
                    }
                    hint.setText(er != null ? I18n.t("Не вдалося отримати список: ") + er
                            : ok ? I18n.t("Доступно версій: ") + res.size() : to + I18n.t(" не підтримує Minecraft ") + mo);
                });
            }, "profile-loader-versions").start();
        };
        mc.addActionListener(e -> loadLv.run());
        type.addActionListener(e -> loadLv.run());
        loadLv.run();

        JPanel pn = new JPanel();
        pn.setOpaque(false);
        pn.setLayout(new BoxLayout(pn, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{
                label(I18n.t("Назва збірки"), 13, true, Theme.MUTED), name,
                label(" ", 6, false, Theme.MUTED),
                label(I18n.t("Звідки взяти версію"), 13, true, Theme.MUTED), mode,
                label(" ", 6, false, Theme.MUTED),
                modeP,
                label(" ", 6, false, Theme.MUTED), hint}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            pn.add(c);
        }
        if (!Dlg.show(this, I18n.t("Нова збірка"), pn, I18n.t("Створити"), I18n.t("Скасувати"))) { seq.incrementAndGet(); return; }
        seq.incrementAndGet();
        String n = name.getText().trim();
        if (!n.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} _.-]{0,31}")) {
            msg(I18n.t("Назва: до 32 символів — літери, цифри, пробіл, _ - ."));
            return;
        }
        if (Files.exists(instDir(n))) { msg(I18n.t("Збірка з такою назвою вже є.")); return; }
        boolean fresh = mode.getSelectedIndex() == 0;
        String mcv = (String) mc.getSelectedItem(), ldType = (String) type.getSelectedItem();
        Object lo = lv.getSelectedItem();
        String haveId = (String) have.getSelectedItem();
        if (fresh && (mcv == null || ldType == null || !(lo instanceof Engine.LV))) {
            msg(I18n.t("Обери версію Minecraft, завантажувач і його версію."));
            return;
        }
        if (!fresh && haveId == null) { msg(I18n.t("Обери встановлену версію.")); return; }
        String ldVer = fresh ? ((Engine.LV) lo).version() : null;
        String java = javaPath();
        final Set<String> before = new HashSet<>(engine.installedIds());
        setBusy(true);
        mBar.set(0.05);
        engine.stop = false;
        cancelUi(true, pCancel, pPause);
        Engine.Progress pg = (tx, fr) -> SwingUtilities.invokeLater(() -> { mStatus.setText(tx); mBar.set(fr); });
        new Thread(() -> {
            try {
                String vid;
                if (fresh) {
                    vid = engine.installLoader(ldType, mcv, ldVer, java, pg);
                    log(I18n.t("Встановлено: ") + vid);
                } else vid = haveId;
                Map<String, Object> v = engine.loadVersion(vid);
                String jar = Json.string(v.get("_jar"));
                String mcId = jar != null ? jar : vid;
                String loader = Engine.detectLoader(vid);
                Path d = instDir(n);
                Files.createDirectories(d.resolve("mods"));
                Properties pr = new Properties();
                pr.setProperty("version", vid);
                pr.setProperty("mc", mcId);
                pr.setProperty("loader", loader);
                if (fresh && !before.contains(vid)) pr.setProperty("hide", vid);
                try (Writer w = Files.newBufferedWriter(d.resolve("profile.properties"), StandardCharsets.UTF_8)) {
                    pr.store(w, "PaxLauncher profile");
                }
                log(I18n.t("Створено збірку «") + n + "» (" + loader + " " + mcId + ")");
                SwingUtilities.invokeLater(() -> {
                    refreshProfiles(n);
                    fillVersions(null);
                    mBar.set(0);
                    mStatus.setText(I18n.t("Збірка «") + n + I18n.t("» створена й з'явилась у списку версій"));
                    status.setText(" ");
                });
            } catch (Engine.Cancelled cx) {
                log(I18n.t("Створення збірки скасовано"));
                SwingUtilities.invokeLater(() -> { mBar.set(0); mStatus.setText(I18n.t("Завантаження скасовано")); });
            } catch (Exception ex) {
                log(I18n.t("Помилка створення збірки: ") + ex);
                SwingUtilities.invokeLater(() -> {
                    mBar.set(0);
                    mStatus.setText(I18n.t("Не вдалося створити збірку"));
                    msg(I18n.t("Не вдалося створити збірку:\n") + ex.getMessage());
                });
            } finally {
                SwingUtilities.invokeLater(() -> { setBusy(false); cancelUi(false, pCancel, pPause); });
            }
        }, "profile-create").start();
    }

    void deleteProfile() {
        Prof p = curProf();
        if (p == null) return;
        Path dir = instDir(p.name());
        if (!Dlg.show(this, I18n.t("Видалити збірку"),
                Dlg.text(I18n.t("Видалити збірку «") + p.name() + I18n.t("» разом з модами та світами?\n") + dir),
                I18n.t("Видалити"), I18n.t("Скасувати"))) return;
        try (var w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (IOException ignored) { } });
        } catch (IOException e) { msg(I18n.t("Не вдалося видалити папку: ") + e.getMessage()); }
        refreshProfiles(null);
        fillVersions(null);
    }

    boolean modsInit;

    JPanel modsPage() {
        JPanel p = new JPanel(new BorderLayout(0, 14));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(24, 28, 22, 28));

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        JLabel t = label(I18n.t("Моди"), 26, true, Theme.TEXT);
                t.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(t);
        head.add(Box.createVerticalStrut(4));
                p.add(head, BorderLayout.NORTH);

        mKind.addItem(ModApi.Kind.MOD);
        mKind.addItem(ModApi.Kind.PACK);
        mKind.setPreferredSize(new Dimension(170, 44));
        mProf.setPreferredSize(new Dimension(260, 44));

        Btn newB = small(new Btn(I18n.t("Нова збірка"), Btn.Kind.SECONDARY));
        Btn delB = small(new Btn(I18n.t("Видалити"), Btn.Kind.SECONDARY));
        Btn dirB = small(mDirB);
        small(mSearch);
        small(mToggleView);
        small(mMore);
        small(mPage);
        small(mCancel);
        mCancel.setVisible(false);
        mCancel.addActionListener(e -> { mCancelled = true; mCancel.setEnabled(false); mStatus.setText(I18n.t("Зупиняю завантаження…")); });

        JPanel row1 = new JPanel(new BorderLayout(10, 0));
        row1.setOpaque(false);
        row1.add(mProf, BorderLayout.WEST);
        JPanel r1b = new JPanel();
        r1b.setOpaque(false);
        r1b.setLayout(new BoxLayout(r1b, BoxLayout.X_AXIS));
        Btn impB = small(new Btn(I18n.t("Перенесення модів"), Btn.Kind.SECONDARY));
        impB.addActionListener(e -> importMods());
        mImpB = impB;
        JComponent[] r1c = {newB, delB, dirB, impB};
        for (int ci = 0; ci < r1c.length; ci++) {
            if (ci > 0) r1b.add(Box.createHorizontalStrut(8));
            r1c[ci].setAlignmentY(Component.CENTER_ALIGNMENT);
            r1b.add(r1c[ci]);
        }
        r1b.add(Box.createHorizontalGlue());
        row1.add(r1b, BorderLayout.CENTER);
        row1.add(mToggleView, BorderLayout.EAST);

        JPanel row2 = new JPanel(new BorderLayout(10, 0));
        row2.setOpaque(false);
        row2.add(mKind, BorderLayout.WEST);
        row2.add(mQuery, BorderLayout.CENTER);
        JPanel r2b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        r2b.setOpaque(false);
        r2b.add(mSearch);
        row2.add(r2b, BorderLayout.EAST);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        mInfoWrap.setOpaque(false);
        mInfoWrap.setLayout(new BoxLayout(mInfoWrap, BoxLayout.Y_AXIS));
        mInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        mInfoWrap.add(mInfo);
        mInfoWrap.add(Box.createVerticalStrut(8));
        mInfoWrap.setVisible(false);
        for (JComponent c : new JComponent[]{row1, null, row2}) {
            if (c == null) { mInfoWrap.setAlignmentX(Component.LEFT_ALIGNMENT); top.add(mInfoWrap); continue; }
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(c);
            top.add(Box.createVerticalStrut(8));
        }

        mList.setBackground(Theme.CARD2);
        mList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mList.setFixedCellHeight(58);
        mList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, false);
                ModApi.Hit h = (ModApi.Hit) v;
                String full = api.cached(h.desc());
                String d = full.length() > 50 ? full.substring(0, 50) + "…" : full;
                setText("<html><b>" + esc(h.title()) + "</b>  <font color='" + Theme.hex(Theme.SUB) + "'>" + fmt(h.downloads()) + I18n.t(" завант.")
                        + "</font><br><font color='" + Theme.hex(Theme.SUB) + "'>" + esc(d) + "</font></html>");
                setIcon(modIcon(h.iconUrl(), 40, mList));
                setIconTextGap(12);
                setBackground(sel ? Theme.SEL : Theme.CARD2);
                setForeground(Theme.TEXT);
                setFont(Theme.font(Font.PLAIN, 13));
                setBorder(new EmptyBorder(6, 12, 6, 12));
                return this;
            }
        });
        JScrollPane lsp = new JScrollPane(mList); Smooth.install(lsp);
        lsp.setBorder(null);
        lsp.getViewport().setBackground(Theme.CARD2);
        lsp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        lsp.getHorizontalScrollBar().setUI(new SlimScrollBarUI());
        lsp.setPreferredSize(new Dimension(350, 100));
        lsp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setOpaque(false);
        left.add(lsp, BorderLayout.CENTER);
        JPanel moreP = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        moreP.setOpaque(false);
        moreP.add(mMore);
        left.add(moreP, BorderLayout.SOUTH);

        dDesc.setEditable(false);
        dDesc.setLineWrap(true);
        dDesc.setWrapStyleWord(true);
        dDesc.setOpaque(false);
        dDesc.setForeground(Theme.TEXT);
        dDesc.setFont(Theme.font(Font.PLAIN, 14));
        dDesc.setBorder(null);
        JScrollPane dsp = new JScrollPane(dDesc); Smooth.install(dsp);
        dsp.setBorder(null);
        dsp.setOpaque(false);
        dsp.getViewport().setOpaque(false);
        dsp.getViewport().setBackground(Theme.CARD);
        dsp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        dsp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        dsp.getHorizontalScrollBar().setUI(new SlimScrollBarUI());

        dTitle.setFont(Theme.font(Font.BOLD, 22));
        JPanel dText = new JPanel();
        dText.setOpaque(false);
        dText.setLayout(new BoxLayout(dText, BoxLayout.Y_AXIS));
        dTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        dMeta.setAlignmentX(Component.LEFT_ALIGNMENT);
        dText.add(dTitle);
        dText.add(Box.createVerticalStrut(2));
        dText.add(dMeta);
        dText.add(Box.createVerticalStrut(10));
        dText.add(dChips);
        dIcon.setPreferredSize(new Dimension(72, 72));
        dIcon.setVerticalAlignment(SwingConstants.TOP);
        dIcon.setIcon(modIcon(null, 72, dIcon));
        JPanel dIconWrap = new JPanel(new BorderLayout());
        dIconWrap.setOpaque(false);
        dIconWrap.add(dIcon, BorderLayout.NORTH);
        JPanel dHead = new JPanel(new BorderLayout(16, 0));
        dHead.setOpaque(false);
        dHead.add(dIconWrap, BorderLayout.WEST);
        dHead.add(dText, BorderLayout.CENTER);

        mFiles.setPreferredSize(new Dimension(300, 44));
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);
        btnRow.add(mInstall);
        btnRow.add(mCancel);
        btnRow.add(mPage);
        JPanel fp = new JPanel(new BorderLayout(12, 0));
        fp.setOpaque(false);
        fp.add(mFiles, BorderLayout.CENTER);
        fp.add(btnRow, BorderLayout.EAST);
        JPanel fcol = new JPanel(new BorderLayout(0, 8));
        fcol.setOpaque(false);
        fcol.add(mVerLabel, BorderLayout.NORTH);
        fcol.add(fp, BorderLayout.CENTER);
        Inset foot = new Inset(new BorderLayout());
        foot.add(fcol, BorderLayout.CENTER);
        dFoot = foot;
        dFoot.setVisible(false);

        JPanel details = new JPanel(new BorderLayout(0, 14));
        details.setOpaque(false);
        details.add(dHead, BorderLayout.NORTH);
        details.add(dsp, BorderLayout.CENTER);
        details.add(dFoot, BorderLayout.SOUTH);

        JPanel searchView = new JPanel(new BorderLayout(18, 0));
        searchView.setOpaque(false);
        searchView.add(left, BorderLayout.WEST);
        searchView.add(details, BorderLayout.CENTER);

        iList.setBackground(Theme.CARD2);
        iList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        iList.setFixedCellHeight(54);
        iList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, false);
                String n = (String) v;
                boolean off = n.endsWith(".disabled");
                String base = off ? n.substring(0, n.length() - 9) : n;
                String shown = base.replaceFirst("(?i)\\.(jar|zip)$", "");
                setText("<html><b>" + esc(shown) + "</b>" + (off ? "  <font color='" + Theme.hex(Theme.SUB) + "'>" + esc(I18n.t("   (вимкнено)").trim()) + "</font>" : "")
                        + "<br><font color='" + Theme.hex(Theme.SUB) + "'>" + esc(base) + "</font></html>");
                setIcon(modIcon(fileIcon.get(iconDirKey + "|" + base), 40, iList));
                setIconTextGap(12);
                setBackground(sel ? Theme.SEL : Theme.CARD2);
                setForeground(off && !sel ? Theme.MUTED : Theme.TEXT);
                setFont(Theme.font(Font.PLAIN, 13));
                setBorder(new EmptyBorder(6, 12, 6, 12));
                return this;
            }
        });
        JScrollPane isp = new JScrollPane(iList); Smooth.install(isp);
        isp.setBorder(null);
        isp.getViewport().setBackground(Theme.CARD2);
        isp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        isp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        isp.getHorizontalScrollBar().setUI(new SlimScrollBarUI());
        isp.setPreferredSize(new Dimension(350, 100));

        iDesc.setEditable(false);
        iDesc.setLineWrap(true);
        iDesc.setWrapStyleWord(true);
        iDesc.setOpaque(false);
        iDesc.setForeground(Theme.TEXT);
        iDesc.setFont(Theme.font(Font.PLAIN, 14));
        iDesc.setBorder(null);
        JScrollPane idsp = new JScrollPane(iDesc); Smooth.install(idsp);
        idsp.setBorder(null);
        idsp.setOpaque(false);
        idsp.getViewport().setOpaque(false);
        idsp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        idsp.getVerticalScrollBar().setUI(new SlimScrollBarUI());

        iIcon.setPreferredSize(new Dimension(72, 72));
        iIcon.setVerticalAlignment(SwingConstants.TOP);
        iIcon.setIcon(modIcon(null, 72, iIcon));
        iTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        iMeta.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel iText = new JPanel();
        iText.setOpaque(false);
        iText.setLayout(new BoxLayout(iText, BoxLayout.Y_AXIS));
        iText.add(iTitle);
        iText.add(Box.createVerticalStrut(2));
        iText.add(iMeta);
        iText.add(Box.createVerticalStrut(10));
        iText.add(iChips);
        JPanel iIconWrap = new JPanel(new BorderLayout());
        iIconWrap.setOpaque(false);
        iIconWrap.add(iIcon, BorderLayout.NORTH);
        JPanel iHead = new JPanel(new BorderLayout(16, 0));
        iHead.setOpaque(false);
        iHead.add(iIconWrap, BorderLayout.WEST);
        iHead.add(iText, BorderLayout.CENTER);

        small(togB);
        Btn rmB = small(mRmB);
        small(iPage);
        iPage.setEnabled(false);
        JPanel ib = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        ib.setOpaque(false);
        ib.add(togB);
        ib.add(rmB);
        ib.add(iPage);
        Inset iFoot = new Inset(new BorderLayout());
        iFoot.add(ib, BorderLayout.CENTER);
        JPanel iRight = new JPanel(new BorderLayout(0, 14));
        iRight.setOpaque(false);
        iRight.add(iHead, BorderLayout.NORTH);
        iRight.add(idsp, BorderLayout.CENTER);
        iRight.add(iFoot, BorderLayout.SOUTH);
        JPanel iBody = new JPanel(new BorderLayout(18, 0));
        iBody.setOpaque(false);
        iBody.add(isp, BorderLayout.WEST);
        iBody.add(iRight, BorderLayout.CENTER);

        Btn backI = small(new Btn(I18n.t("Назад до пошуку"), Btn.Kind.SECONDARY));
        backI.addActionListener(e -> showInstalledView(false));
        JPanel ihead = new JPanel(new BorderLayout(10, 0));
        ihead.setOpaque(false);
        ihead.add(mInstTitle, BorderLayout.CENTER);
        ihead.add(backI, BorderLayout.EAST);
        JPanel installedView = new JPanel(new BorderLayout(0, 12));
        installedView.setOpaque(false);
        installedView.add(ihead, BorderLayout.NORTH);
        installedView.add(iBody, BorderLayout.CENTER);
        iList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) onInstalledSelected(); });
        iPage.addActionListener(e -> {
            String n = iList.getSelectedValue();
            if (n == null) return;
            String base = n.endsWith(".disabled") ? n.substring(0, n.length() - 9) : n;
            String pid = iProj.get(iconDirKey + "|" + base);
            if (pid != null) browse("https://modrinth.com/" + curKind().type + "/" + pid);
        });

        mCenter.setOpaque(false);
        mCenter.add(searchView, "search");
        mCenter.add(installedView, "installed");
        JPanel emptyV = new JPanel(new GridBagLayout());
        emptyV.setOpaque(false);
        emptyV.add(label(I18n.t("Ви не маєте збірок"), 18, true, Theme.MUTED));
        mCenter.add(emptyV, "empty");

        Card c = new Card(new BorderLayout(0, 6), 16);
        c.add(top, BorderLayout.NORTH);
        c.add(mCenter, BorderLayout.CENTER);
        p.add(c, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        JPanel srow = new JPanel(new BorderLayout(10, 0));
        srow.setOpaque(false);
        srow.setAlignmentX(Component.LEFT_ALIGNMENT);
        small(pCancel);
        small(pPause);
        pCancel.setVisible(false);
        pPause.setVisible(false);
        pCancel.addActionListener(e -> cancelDownload());
        pPause.addActionListener(e -> togglePause());
        JPanel pp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        pp.setOpaque(false);
        pp.add(pPause);
        pp.add(pCancel);
        srow.add(mStatus, BorderLayout.CENTER);
        srow.add(pp, BorderLayout.EAST);
        bottom.add(srow);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(mBar);
        p.add(bottom, BorderLayout.SOUTH);

        mInstall.setEnabled(false);
        mMore.setEnabled(false);
        mPage.setEnabled(false);

        mProf.addActionListener(e -> { if (!mFilling) onProfileChanged(); });
        mKind.addActionListener(e -> {
            applyKindTexts();
            if (modsInit && !mFilling) { showInstalledView(false); onProfileChanged(); }
        });
        mQuery.addActionListener(e -> search(false, false));
        mSearch.addActionListener(e -> search(false, false));
        mMore.addActionListener(e -> search(true, false));
        mToggleView.addActionListener(e -> showInstalledView(!mInstalledView));
        mList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) onHitSelected(); });
        mFiles.addActionListener(e -> {
            Object o = mFiles.getSelectedItem();
            mInstall.setText(o instanceof ModApi.ModFile && ((ModApi.ModFile) o).url() == null ? I18n.t("Відкрити на сайті") : I18n.t("Встановити"));
        });
        mInstall.addActionListener(e -> modInstall());
        mPage.addActionListener(e -> { ModApi.Hit h = mList.getSelectedValue(); if (h != null) browse(h.pageUrl()); });
        newB.addActionListener(e -> newProfile());
        delB.addActionListener(e -> deleteProfile());
        dirB.addActionListener(e -> {
            Prof pf = curProf();
            if (pf == null) msg(I18n.t("Спочатку створи збірку."));
            else openFolder(instDir(pf.name()).resolve(curKind().folder));
        });
        togB.addActionListener(e -> toggleMod());
        rmB.addActionListener(e -> deleteMod());
        return p;
    }

    void updateEmptyState() {
        mCards.show(mCenter, curProf() == null ? "empty" : mInstalledView ? "installed" : "search");
    }

    void showInstalledView(boolean installed) {
        mInstalledView = installed;
        updateEmptyState();
        applyKindTexts();
        if (mToggleView.getParent() != null) mToggleView.getParent().revalidate();
        if (installed) refreshInstalled();
    }

    ModApi.Kind curKind() {
        Object o = mKind.getSelectedItem();
        return o instanceof ModApi.Kind ? (ModApi.Kind) o : ModApi.Kind.MOD;
    }

    void applyKindTexts() {
        ModApi.Kind k = curKind();
        mDirB.setText(k.folderBtn);
        mRmB.setText(k.removeBtn);
        mPage.setText(k.pageBtn);
        mInstTitle.setText(k.installedTitle);
        mVerLabel.setText(k.versionLabel);
        mToggleView.setText(mInstalledView ? k.searchTitle : k.installedTitle);
        iPage.setText(k.pageBtn);
        if (mImpB != null && mImpB.isVisible() != k.isMod()) {
            mImpB.setVisible(k.isMod());
            if (mImpB.getParent() != null) { mImpB.getParent().revalidate(); mImpB.getParent().repaint(); }
        }
        for (JComponent c : new JComponent[]{mDirB, mRmB, mPage, mToggleView, iPage}) {
            if (c.getParent() != null) { c.getParent().revalidate(); c.getParent().repaint(); }
        }
    }

    static String target(ModApi.Kind k, Prof p) { return k.isMod() ? p.loader() + " " + p.mc() : p.mc(); }

    void setDesc(ModApi.Hit h) {
        String t = api.cached(h.desc());
        String b = dBody;
        dDesc.setText(b.isBlank() ? t : t + "\n\n" + b);
        dDesc.setCaretPosition(0);
    }

    void translateHits() {
        List<ModApi.Hit> hs = Collections.list(mModel.elements());
        int my = mSeq.get();
        ExecutorService ex = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "mod-translate"); t.setDaemon(true); return t; });
        for (ModApi.Hit h : hs) {
            if (!api.needsTranslation(h.desc())) continue;
            ex.submit(() -> {
                if (my != mSeq.get()) return;
                api.translate(h.desc());
                SwingUtilities.invokeLater(() -> {
                    if (my != mSeq.get()) return;
                    mList.repaint();
                    if (h == mList.getSelectedValue()) setDesc(h);
                });
            });
        }
        ex.shutdown();
    }

    void setFootVisible(boolean v) {
        if (dFoot == null || dFoot.isVisible() == v) return;
        dFoot.setVisible(v);
        if (dFoot.getParent() != null) { dFoot.getParent().revalidate(); dFoot.getParent().repaint(); }
    }

    void showDetails(ModApi.Hit h) {
        int my = dSeq.incrementAndGet();
        dBody = "";
        if (h == null) {
            dTitle.setText(I18n.t("Обери зі списку"));
            dMeta.setText(" ");
            dDesc.setText("");
            fillChips(dChips, new ArrayList<>());
            setLabelIcon(dIcon, null, 72);
            mPage.setEnabled(false);
            setFootVisible(false);
            return;
        }
        setFootVisible(true);
        dTitle.setText(h.title());
        dMeta.setText(I18n.t("від ") + h.author() + "  ·  Modrinth");
        fillChips(dChips, hitChips(h, null));
        setLabelIcon(dIcon, h.iconUrl(), 72);
        setDesc(h);
        if (api.needsTranslation(h.desc())) {
            new Thread(() -> {
                api.translate(h.desc());
                SwingUtilities.invokeLater(() -> { if (h == mList.getSelectedValue()) setDesc(h); });
            }, "mod-translate-one").start();
        }
        mPage.setEnabled(true);
        new Thread(() -> {
            ModApi.Detail pd = null;
            try { pd = api.project(h.id()); } catch (Exception ignored) { }
            if (pd == null || my != dSeq.get()) return;
            String body = tr(ModApi.excerpt(pd.body(), 1200));
            ModApi.Detail fpd = pd;
            SwingUtilities.invokeLater(() -> {
                if (my != dSeq.get()) return;
                dBody = body;
                fillChips(dChips, hitChips(h, fpd));
                setDesc(h);
            });
        }, "mod-detail").start();
    }

    List<String> hitChips(ModApi.Hit h, ModApi.Detail pd) {
        List<String> c = new ArrayList<>();
        c.add(fmt(h.downloads()) + I18n.t(" завант."));
        if (pd != null && pd.followers() > 0) c.add(fmt(pd.followers()) + I18n.t(" вподобань"));
        if (!h.loaders().isEmpty()) for (String x : h.loaders().split(",\\s*")) c.add(x);
        if (pd != null && h.kind().isMod()) {
            boolean cl = !pd.client().isEmpty() && !pd.client().equals("unsupported");
            boolean sv = !pd.server().isEmpty() && !pd.server().equals("unsupported");
            if (cl && sv) c.add(I18n.t("Клієнт і сервер"));
            else if (cl) c.add(I18n.t("Лише клієнт"));
            else if (sv) c.add(I18n.t("Лише сервер"));
        }
        if (pd != null && pd.updated().length() >= 10) {
            String u = pd.updated();
            c.add(I18n.t("Оновлено ") + u.substring(8, 10) + "." + u.substring(5, 7) + "." + u.substring(0, 4));
        }
        return c.size() > 7 ? new ArrayList<>(c.subList(0, 7)) : c;
    }

    void onInstalledSelected() {
        String n = iList.getSelectedValue();
        int my = iDetSeq.incrementAndGet();
        Prof p = curProf();
        boolean has = n != null && p != null;
        togB.setEnabled(has);
        mRmB.setEnabled(has);
        if (!has) {
            iTitle.setText(iModel.isEmpty() ? I18n.t("Тут ще нічого немає") : I18n.t("Обери файл зі списку"));
            iMeta.setText(" ");
            fillChips(iChips, new ArrayList<>());
            iDesc.setText("");
            setLabelIcon(iIcon, null, 72);
            iPage.setEnabled(false);
            return;
        }
        ModApi.Kind k = curKind();
        boolean off = n.endsWith(".disabled");
        String base = off ? n.substring(0, n.length() - 9) : n;
        String key = iconDirKey + "|" + base;
        Path f = instDir(p.name()).resolve(k.folder).resolve(n);
        List<String> chips = new ArrayList<>();
        try {
            chips.add(sizeText(Files.size(f)));
            chips.add(ShotCard.fmtDate(Files.getLastModifiedTime(f).toMillis()).substring(0, 10));
        } catch (IOException ignored) { }
        chips.add(off ? I18n.t("Вимкнено") : I18n.t("Увімкнено"));
        iTitle.setText(base.replaceFirst("(?i)\\.(jar|zip)$", ""));
        iMeta.setText(base);
        fillChips(iChips, chips);
        iDesc.setText("");
        setLabelIcon(iIcon, fileIcon.get(key), 72);
        String pid = iProj.get(key);
        iPage.setEnabled(pid != null);
        if (pid == null) return;
        new Thread(() -> {
            ModApi.Detail pd = null;
            try { pd = api.project(pid); } catch (Exception ignored) { }
            if (pd == null || my != iDetSeq.get()) return;
            String desc = tr(pd.desc()), body = tr(ModApi.excerpt(pd.body(), 1200));
            ModApi.Detail fpd = pd;
            SwingUtilities.invokeLater(() -> {
                if (my != iDetSeq.get()) return;
                iTitle.setText(fpd.title());
                List<String> c2 = new ArrayList<>();
                c2.add(fmt(fpd.downloads()) + I18n.t(" завант."));
                c2.addAll(chips);
                fillChips(iChips, c2);
                iDesc.setText(desc + (body.isBlank() ? "" : "\n\n" + body));
                iDesc.setCaretPosition(0);
            });
        }, "mod-installed-detail").start();
    }

    void search(boolean more, boolean quiet) {
        Prof p = curProf();
        if (p == null) { if (!quiet) msg(I18n.t("Спочатку створи збірку («Нова збірка»).")); return; }
        ModApi.Kind k = curKind();
        if (k.isMod() && p.loader().equals("Vanilla")) {
            if (!quiet) msg(I18n.t("Чиста збірка не підтримує моди.\nСтвори збірку на основі Fabric / Quilt / Forge / NeoForge."));
            return;
        }
        if (mInstalledView) showInstalledView(false);
        String q = mQuery.getText().trim();
        int off = more ? mModel.size() : 0;
        int my = mSeq.incrementAndGet();
        mSearch.setEnabled(false);
        mMore.setEnabled(false);
        mStatus.setText(I18n.t("Пошук: ") + k.title.toLowerCase(Locale.ROOT) + I18n.t(" на Modrinth…"));
        new Thread(() -> {
            List<ModApi.Hit> res = null;
            String err = null;
            try { res = api.search(k, q, p.mc(), p.loader(), off); } catch (Exception ex) { err = ex.getMessage(); }
            List<ModApi.Hit> rr = res;
            String er = err;
            SwingUtilities.invokeLater(() -> {
                if (my != mSeq.get()) return;
                mSearch.setEnabled(true);
                if (!more) mModel.clear();
                if (rr != null) for (ModApi.Hit h : rr) mModel.addElement(h);
                if (!more && !mModel.isEmpty() && mList.getSelectedIndex() < 0) mList.setSelectedIndex(0);
                mMore.setEnabled(rr != null && rr.size() >= ModApi.PAGE);
                mStatus.setText(er != null ? I18n.t("Помилка пошуку: ") + er
                        : mModel.isEmpty() ? I18n.t("Нічого не знайдено для ") + target(k, p)
                        : I18n.t("Знайдено: ") + mModel.size() + " • " + k.title + " • " + target(k, p));
                mBar.set(0);
                if (rr != null) translateHits();
            });
        }, "mod-search").start();
    }

    void onHitSelected() {
        ModApi.Hit h = mList.getSelectedValue();
        Prof p = curProf();
        showDetails(h);
        mFiles.removeAllItems();
        mInstall.setEnabled(false);
        int my = mfSeq.incrementAndGet();
        if (h == null || p == null) return;
        mStatus.setText(I18n.t("Завантаження версій…"));
        new Thread(() -> {
            List<ModApi.ModFile> res = null;
            String err = null;
            try { res = api.files(h.kind(), h.id(), p.mc(), p.loader()); } catch (Exception ex) { err = ex.getMessage(); }
            List<ModApi.ModFile> rr = res;
            String er = err;
            SwingUtilities.invokeLater(() -> {
                if (my != mfSeq.get()) return;
                mFiles.removeAllItems();
                boolean ok = rr != null && !rr.isEmpty();
                if (ok) {
                    int idx = 0;
                    for (int i = 0; i < rr.size(); i++) {
                        mFiles.addItem(rr.get(i));
                    }
                    for (int i = 0; i < rr.size(); i++) if (rr.get(i).channel().equals("release")) { idx = i; break; }
                    mFiles.setSelectedIndex(idx);
                }
                mInstall.setEnabled(ok && !mBusy);
                mStatus.setText(er != null ? I18n.t("Помилка: ") + er
                        : ok ? I18n.t("Версій для ") + target(h.kind(), p) + ": " + rr.size()
                        : I18n.t("Немає файлів для ") + target(h.kind(), p) + I18n.t(" — не підтримує цю збірку"));
            });
        }, "mod-files").start();
    }

    void modInstall() {
        Prof p = curProf();
        ModApi.Hit h = mList.getSelectedValue();
        Object fo = mFiles.getSelectedItem();
        if (p == null || h == null || !(fo instanceof ModApi.ModFile)) return;
        ModApi.ModFile f = (ModApi.ModFile) fo;
        if (f.url() == null) {
            browse(h.pageUrl());
            msg(I18n.t("Автор заборонив завантаження сторонніми запускачами.\n")
                    + I18n.t("Завантаж файл на сайті й поклади в папку збірки (кнопка «") + h.kind().folderBtn + "»).");
            return;
        }
        Path dir = instDir(p.name()).resolve(h.kind().folder);
        mBusy = true;
        mCancelled = false;
        mInstall.setEnabled(false);
        mCancel.setEnabled(true);
        mCancel.setVisible(true);
        mBar.set(0.4);
        new Thread(() -> {
            try {
                api.install(h.kind(), h.id(), f, p.mc(), p.loader(), dir, new HashSet<>(), false,
                        t -> SwingUtilities.invokeLater(() -> mStatus.setText(t)), () -> mCancelled);
                SwingUtilities.invokeLater(() -> {
                    mStatus.setText(I18n.t("Встановлено: ") + f.filename() + I18n.t(" у збірку «") + p.name() + "»");
                    mBar.done();
                    refreshInstalled();
                });
            } catch (Engine.Cancelled ex) {
                log(I18n.t("Встановлення скасовано."));
                SwingUtilities.invokeLater(() -> {
                    mStatus.setText(I18n.t("Завантаження скасовано"));
                    mBar.set(0);
                    refreshInstalled();
                });
            } catch (Exception ex) {
                log(I18n.t("Помилка встановлення: ") + ex);
                SwingUtilities.invokeLater(() -> {
                    mStatus.setText(I18n.t("Помилка встановлення"));
                    mBar.set(0);
                    msg(I18n.t("Не вдалося встановити:\n") + ex.getMessage());
                });
            } finally {
                SwingUtilities.invokeLater(() -> { mBusy = false; mCancel.setVisible(false); mInstall.setEnabled(mFiles.getItemCount() > 0); });
            }
        }, "mod-install").start();
    }

    void importMods() {
        Prof p = curProf();
        if (p == null) { msg(I18n.t("Спочатку створи збірку («Нова збірка»).")); return; }
        if (mBusy) return;
        File[] picked = FilePick.show(this, I18n.t("Обери папку збірки (або папку mods) чи окремі .jar"),
                JFileChooser.FILES_AND_DIRECTORIES, true, null);
        if (picked == null) return;
        List<Path> jars = new ArrayList<>();
        for (File sel : picked) {
            Path sp = sel.toPath();
            if (Files.isDirectory(sp)) {
                Path md = Files.isDirectory(sp.resolve("mods")) ? sp.resolve("mods") : sp;
                try (var st = Files.list(md)) {
                    st.filter(x -> Files.isRegularFile(x) && x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                      .sorted().forEach(jars::add);
                } catch (IOException ex) { log(I18n.t("Помилка: ") + ex); }
            } else if (sp.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) jars.add(sp);
        }
        if (jars.isEmpty()) { msg(I18n.t("Файлів .jar не знайдено.")); return; }
        Path dir = instDir(p.name()).resolve(ModApi.Kind.MOD.folder);
        mBusy = true;
        mCancelled = false;
        mInstall.setEnabled(false);
        mCancel.setEnabled(true);
        mCancel.setVisible(true);
        mBar.set(0);
        new Thread(() -> {
            List<ModApi.ImportRow> rows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean cancelled = false;
            try {
                for (int i = 0; i < jars.size(); i++) {
                    double frac = (double) i / jars.size();
                    SwingUtilities.invokeLater(() -> mBar.set(frac));
                    rows.add(api.importJar(jars.get(i), p.mc(), p.loader(), dir, seen,
                            t -> SwingUtilities.invokeLater(() -> mStatus.setText(t)), () -> mCancelled));
                }
            } catch (Engine.Cancelled ex) {
                cancelled = true;
            } catch (Exception ex) {
                log(I18n.t("Помилка: ") + ex);
            }
            boolean wasCancelled = cancelled;
            SwingUtilities.invokeLater(() -> {
                mBusy = false;
                mCancel.setVisible(false);
                mInstall.setEnabled(mFiles.getItemCount() > 0);
                if (wasCancelled) mBar.set(0); else mBar.done();
                mStatus.setText(wasCancelled ? I18n.t("Завантаження скасовано") : I18n.t("Готово"));
                refreshInstalled();
                showImportReport(p, rows, jars.size());
            });
        }, "mod-import").start();
    }

    void showImportReport(Prof p, List<ModApi.ImportRow> rows, int total) {
        StringBuilder bad = new StringBuilder();
        int ok = 0;
        for (ModApi.ImportRow r : rows) {
            if (r.ok()) ok++;
            else bad.append("• ").append(r.file()).append(r.title().equals(r.file()) ? "" : " (" + r.title() + ")")
                    .append(" — ").append(r.reason()).append("\n");
        }
        int notDone = total - rows.size();
        if (notDone > 0) bad.append("• ").append(I18n.t("не оброблено через скасування: ")).append(notDone).append("\n");
        String head = I18n.t("Встановлено: ") + ok + " / " + total + "  (" + p.loader() + " " + p.mc() + ")";
        if (bad.length() == 0) { msg(head); return; }
        JTextArea ta = new JTextArea(head + "\n\n" + I18n.t("Не вдалося встановити:") + "\n" + bad);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setBackground(Theme.CARD2);
        ta.setForeground(Theme.TEXT);
        ta.setFont(Theme.font(Font.PLAIN, 13));
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(560, 300));
        Dlg.show(this, I18n.t("Перенесення модів"), sp, I18n.t("Закрити"), null);
    }

    final AtomicInteger iSeq = new AtomicInteger();

    void refreshInstalled() {
        iModel.clear();
        Prof p = curProf();
        if (p == null) return;
        ModApi.Kind k = curKind();
        Path d = instDir(p.name()).resolve(k.folder);
        iconDirKey = d.toString();
        if (!Files.isDirectory(d)) return;
        List<String> names = new ArrayList<>();
        try (var st = Files.list(d)) {
            st.filter(x -> Files.isRegularFile(x)).map(x -> x.getFileName().toString())
                    .filter(k::matches)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(n -> { iModel.addElement(n); names.add(n); });
        } catch (IOException ignored) { }
        if (!iModel.isEmpty()) iList.setSelectedIndex(0); else onInstalledSelected();
        loadInstalledIcons(d, k, names);
    }

    void loadInstalledIcons(Path d, ModApi.Kind k, List<String> names) {
        if (names.isEmpty()) { iSeq.incrementAndGet(); return; }
        final String key = d.toString();
        final int my = iSeq.incrementAndGet();
        new Thread(() -> {
            List<String> bases = new ArrayList<>();
            for (String n : names) bases.add(n.endsWith(".disabled") ? n.substring(0, n.length() - 9) : n);
            final Map<String, String> f2p = new HashMap<>();
            final Map<String, String> urls = new HashMap<>();
            try {
                f2p.putAll(ModApi.fileToProject(d, k));
                List<String> unknown = new ArrayList<>();
                for (String b : bases) if (!f2p.containsKey(b)) unknown.add(b);
                if (!unknown.isEmpty()) f2p.putAll(api.identify(d, unknown));
                if (!f2p.isEmpty()) urls.putAll(api.iconUrls(new HashSet<>(f2p.values())));
            } catch (Throwable ignored) { }
            if (my != iSeq.get()) return;
            for (String b : bases) { String pid = f2p.get(b); if (pid != null) iProj.put(key + "|" + b, pid); }
            SwingUtilities.invokeLater(() -> { if (my == iSeq.get()) onInstalledSelected(); });
            ExecutorService ex = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "mod-icons-file");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            for (String b : bases) {
                ex.execute(() -> {
                    if (my != iSeq.get()) return;
                    String tok = null;
                    String pid = f2p.get(b);
                    String u = pid == null ? null : urls.get(pid);
                    if (u != null && !u.isEmpty()) {
                        BufferedImage im = iconRaw.get(u);
                        if (im == null) {
                            im = fetchProjectIcon(u);
                            if (im != null) iconRaw.put(u, im);
                        }
                        if (im != null && im != NO_ICON) tok = u;
                    }
                    if (tok == null) {
                        Path f = d.resolve(b);
                        if (!Files.isRegularFile(f)) f = d.resolve(b + ".disabled");
                        BufferedImage em = ModApi.embeddedIcon(f, k);
                        if (em != null) {
                            tok = "file:" + key + "|" + b;
                            iconRaw.put(tok, roundIcon(em, 64));
                        }
                    }
                    if (tok != null) fileIcon.put(key + "|" + b, tok);
                    SwingUtilities.invokeLater(iList::repaint);
                });
            }
            ex.shutdown();
            try { ex.awaitTermination(2, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }
            SwingUtilities.invokeLater(() -> { if (my == iSeq.get()) onInstalledSelected(); });
        }, "mod-icons-meta").start();
    }

    void toggleMod() {
        Prof p = curProf();
        String n = iList.getSelectedValue();
        if (p == null || n == null) return;
        Path d = instDir(p.name()).resolve(curKind().folder);
        String nn = n.endsWith(".disabled") ? n.substring(0, n.length() - 9) : n + ".disabled";
        try {
            Files.move(d.resolve(n), d.resolve(nn), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { msg(I18n.t("Не вдалося перейменувати: ") + e.getMessage()); }
        refreshInstalled();
        iList.setSelectedValue(nn, true);
    }

    void deleteMod() {
        Prof p = curProf();
        String n = iList.getSelectedValue();
        if (p == null || n == null) return;
        Path d = instDir(p.name()).resolve(curKind().folder);
        if (!Dlg.show(this, curKind().removeBtn, Dlg.text(I18n.t("Видалити ") + n + "?"), I18n.t("Видалити"), I18n.t("Скасувати"))) return;
        try {
            Files.deleteIfExists(d.resolve(n));
            ModApi.forget(d, n);
        } catch (IOException e) { msg(I18n.t("Не вдалося видалити: ") + e.getMessage()); }
        refreshInstalled();
    }

    static final BufferedImage NO_ICON = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    final Map<String, BufferedImage> iconRaw = new ConcurrentHashMap<>();
    final Map<String, Icon> iconObjs = new ConcurrentHashMap<>();
    final Set<String> iconBusy = ConcurrentHashMap.newKeySet();
    final Map<String, String> fileIcon = new ConcurrentHashMap<>();
    String iconDirKey = "";
    final ExecutorService iconPool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "mod-icons");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    Icon modIcon(String url, int size, JComponent target) {
        if (url == null || url.isEmpty()) return iconObjs.computeIfAbsent("@" + size, k -> new ImgIcon(null, size));
        BufferedImage im = iconRaw.get(url);
        if (im == null) {
            loadIcon(url, target);
            return iconObjs.computeIfAbsent("@" + size, k -> new ImgIcon(null, size));
        }
        if (im == NO_ICON) return iconObjs.computeIfAbsent("@" + size, k -> new ImgIcon(null, size));
        return iconObjs.computeIfAbsent(url + "@" + size, k -> new ImgIcon(im, size));
    }

    final Map<String, Integer> iconFails = new ConcurrentHashMap<>();
    final Map<String, Long> iconRetryAt = new ConcurrentHashMap<>();

    BufferedImage fetchProjectIcon(String url) {
        try {
            BufferedImage src = fetchImage(url);
            if (src == null && url.matches(".*_\\d+\\.webp(\\?.*)?$")) {
                String base = url.replaceAll("_\\d+\\.webp(\\?.*)?$", "");
                for (String ext : new String[]{".png", ".jpg", ".jpeg", ".gif"}) {
                    src = fetchImage(base + ext);
                    if (src != null) break;
                }
            }
            if (src == null) {
                src = fetchImage("https://wsrv.nl/?output=png&w=128&h=128&fit=cover&url=" + URLEncoder.encode(url, StandardCharsets.UTF_8));
            }
            return src == null ? null : roundIcon(src, 64);
        } catch (Throwable e) {
            return null;
        }
    }

    void loadIcon(String url, JComponent target) {
        Long again = iconRetryAt.get(url);
        if (again != null && System.currentTimeMillis() < again) return;
        if (!iconBusy.add(url)) return;
        iconPool.execute(() -> {
            BufferedImage res = fetchProjectIcon(url);
            if (res == null) {
                int n = iconFails.merge(url, 1, Integer::sum);
                if (n < 3) {
                    iconRetryAt.put(url, System.currentTimeMillis() + 15_000);
                    iconBusy.remove(url);
                    return;
                }
                res = NO_ICON;
            }
            iconRaw.put(url, res);
            iconBusy.remove(url);
            SwingUtilities.invokeLater(target::repaint);
        });
    }

    BufferedImage fetchImage(String url) {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "PaxLauncher/1.0")
                    .timeout(Duration.ofSeconds(15)).build();
            HttpResponse<byte[]> r = engine.http.send(rq, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) return null;
            return javax.imageio.ImageIO.read(new ByteArrayInputStream(r.body()));
        } catch (Throwable e) {
            return null;
        }
    }

    static BufferedImage roundIcon(BufferedImage src, int s) {
        int sw = src.getWidth(), sh = src.getHeight(), side = Math.min(sw, sh);
        BufferedImage sq = src.getSubimage((sw - side) / 2, (sh - side) / 2, side, side);
        int t = Math.min(128, side);
        return ImgIcon.smooth(sq, t, t);
    }

    static final int SHOT_PAGE = 60;
    final Combo<String> gFilter = new Combo<>();
    final JPanel gGrid = new JPanel(new WrapGrid(ShotCard.W, ShotCard.H, 14));
    final JLabel gInfo = label(" ", 13, false, Theme.MUTED);
    final Btn gMore = new Btn(I18n.t("Показати ще"), Btn.Kind.SECONDARY);
    List<Shot> gShots = new ArrayList<>();
    int gShown = SHOT_PAGE;
    boolean gFilling;
    final AtomicInteger gSeq = new AtomicInteger();
    final ExecutorService shotPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shot-thumbs");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    final Map<String, BufferedImage> thumbCache = Collections.synchronizedMap(new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 120; }
    });

    JPanel galleryPage() {
        JPanel p = new JPanel(new BorderLayout(0, 16));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(24, 28, 26, 28));

        gFilter.setPreferredSize(new Dimension(300, 44));
        Btn refresh = small(new Btn(I18n.t("Оновити"), Btn.Kind.SECONDARY));
        Btn folder = small(new Btn(I18n.t("Відкрити папку"), Btn.Kind.SECONDARY));
        refresh.addActionListener(e -> refreshGallery());
        folder.addActionListener(e -> openFolder(shotDir()));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);
        left.setBorder(new EmptyBorder(0, -12, 0, 0));
        left.add(label(I18n.t("Версія"), 13, true, Theme.MUTED));
        left.add(gFilter);
        left.add(refresh);
        left.add(folder);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(left, BorderLayout.WEST);
        head.add(gInfo, BorderLayout.EAST);
        p.add(head, BorderLayout.NORTH);

        gGrid.setOpaque(false);
        small(gMore);
        gMore.setVisible(false);
        gMore.addActionListener(e -> { gShown += SHOT_PAGE; showGallery(false); });
        JPanel moreP = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        moreP.setOpaque(false);
        moreP.add(gMore);

        VPanel body = new VPanel();
        body.row(gGrid, 0);
        body.row(moreP, 16);
        body.end();
        p.add(vscroll(body, Theme.BG2), BorderLayout.CENTER);

        gFilter.addActionListener(e -> { if (!gFilling) showGallery(true); });
        return p;
    }

    Path shotDir() {
        Object o = gFilter.getSelectedItem();
        if (gFilter.getSelectedIndex() > 0 && o != null)
            for (Prof p : loadProfiles()) if (p.name().equals(o)) return instDir(p.name()).resolve("screenshots");
        return gameDir().resolve("screenshots");
    }

    void refreshGallery() {
        int my = gSeq.incrementAndGet();
        Path def = gameDir();
        List<Prof> profs = loadProfiles();
        new Thread(() -> {
            List<Shot> list = scanShots(def, profs);
            SwingUtilities.invokeLater(() -> {
                if (my != gSeq.get()) return;
                gShots = list;
                fillGalleryFilter();
                showGallery(true);
            });
        }, "gallery-scan").start();
    }

    List<Shot> scanShots(Path def, List<Prof> profs) {
        List<Shot> out = new ArrayList<>();
        for (Prof p : profs) collectShots(out, instDir(p.name()).resolve("screenshots"), p.name(), null, null);
        collectShots(out, def.resolve("screenshots"), null, readSessions(), def.toAbsolutePath().normalize().toString());
        out.sort((a, b) -> Long.compare(b.time(), a.time()));
        return out;
    }

    void collectShots(List<Shot> out, Path dir, String fixed, List<String[]> ses, String dirStr) {
        if (!Files.isDirectory(dir)) return;
        try (var st = Files.list(dir)) {
            st.filter(x -> {
                String n = x.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
            }).forEach(x -> {
                long t;
                try { t = Files.getLastModifiedTime(x).toMillis(); } catch (IOException e) { t = 0; }
                out.add(new Shot(x, fixed != null ? fixed : guessVersion(ses, dirStr, t), t));
            });
        } catch (IOException ignored) { }
    }

    String guessVersion(List<String[]> ses, String dirStr, long t) {
        String best = null;
        long bestStart = -1;
        if (ses != null) for (String[] s : ses) {
            long st;
            try { st = Long.parseLong(s[0]); } catch (NumberFormatException e) { continue; }
            if (st <= t + 2000 && st > bestStart && s[2].equals(dirStr)) { best = s[1]; bestStart = st; }
        }
        return best != null ? best : I18n.t("Невідома версія");
    }

    List<String[]> readSessions() {
        List<String[]> r = new ArrayList<>();
        Path f = root.resolve("gallery-sessions.txt");
        if (!Files.isRegularFile(f)) return r;
        try {
            for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] x = ln.split("\t");
                if (x.length >= 3) r.add(x);
            }
        } catch (IOException ignored) { }
        return r;
    }

    void noteSession(String label, Path dir) {
        try {
            Files.writeString(root.resolve("gallery-sessions.txt"),
                    System.currentTimeMillis() + "\t" + label + "\t" + dir.toAbsolutePath().normalize() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    void fillGalleryFilter() {
        Object prev = gFilter.getSelectedItem();
        int prevIdx = gFilter.getSelectedIndex();
        Set<String> labels = new LinkedHashSet<>();
        for (Shot s : gShots) labels.add(s.label());
        gFilling = true;
        gFilter.removeAllItems();
        gFilter.addItem(I18n.t("Всі"));
        for (String l : labels) gFilter.addItem(l);
        gFilter.setSelectedIndex(0);
        if (prevIdx > 0 && prev != null && labels.contains(prev)) gFilter.setSelectedItem(prev);
        gFilling = false;
    }

    List<Shot> filteredShots() {
        int idx = gFilter.getSelectedIndex();
        Object sel = gFilter.getSelectedItem();
        List<Shot> list = new ArrayList<>();
        for (Shot s : gShots) if (idx <= 0 || s.label().equals(sel)) list.add(s);
        return list;
    }

    void showGallery(boolean reset) {
        if (reset) gShown = SHOT_PAGE;
        for (Component c : gGrid.getComponents()) if (c instanceof ShotCard) ((ShotCard) c).dead = true;
        gGrid.removeAll();
        List<Shot> list = filteredShots();
        int n = Math.min(gShown, list.size());
        for (int i = 0; i < n; i++) gGrid.add(new ShotCard(list.get(i), this));
        gMore.setVisible(list.size() > n);
        gInfoBase = list.isEmpty() ? I18n.t("Знятків ще немає") : I18n.t("Знятків: ") + list.size();
        gInfo.setText(gInfoBase);
        gGrid.revalidate();
        gGrid.repaint();
    }

    void loadThumb(ShotCard c) {
        String key = c.shot.file() + "|" + c.shot.time();
        BufferedImage hit = thumbCache.get(key);
        if (hit != null) { c.thumb = hit; return; }
        shotPool.execute(() -> {
            if (c.dead) return;
            BufferedImage im = null;
            try { im = Shots.read(c.shot.file(), ShotCard.IW, ShotCard.IH, true); } catch (Throwable ignored) { }
            if (im == null) return;
            thumbCache.put(key, im);
            c.thumb = im;
            SwingUtilities.invokeLater(c::repaint);
        });
    }

    String gInfoBase = " ";

    void flashInfo(String text) {
        gInfo.setText(text);
        javax.swing.Timer t = new javax.swing.Timer(2500, e -> {
            if (gInfo.getText().equals(text)) gInfo.setText(gInfoBase);
        });
        t.setRepeats(false);
        t.start();
    }

    void openShot(Shot s) {
        List<Shot> list = filteredShots();
        int i = list.indexOf(s);
        if (i < 0) { list = new ArrayList<>(); list.add(s); i = 0; }
        ShotViewer.show(this, list, i);
    }

    void copyShot(Shot s) { copyShot(s, () -> flashInfo(I18n.t("Зняток скопійовано"))); }

    void copyShot(Shot s, Runnable done) {
        new Thread(() -> {
            try {
                BufferedImage im = javax.imageio.ImageIO.read(s.file().toFile());
                if (im == null) throw new IOException(I18n.t("невідомий формат зображення"));
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSel(im), null);
                SwingUtilities.invokeLater(done);
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> msg(I18n.t("Не вдалося скопіювати: ") + ex.getMessage()));
            }
        }, "shot-copy").start();
    }

    final CardLayout rootCards = new CardLayout();
    final JPanel rootPanel = new JPanel(rootCards);
    final Accounts accounts = new Accounts(root.resolve("accounts.properties"), engine.http);
    final JLabel accName = label("—", 14, true, Theme.TEXT), accType = label(" ", 12, false, Theme.MUTED);
    final Btn contBtn = new Btn(I18n.t("Продовжити"), Btn.Kind.PRIMARY);
    Btn loginBack;
    final JPanel savedBox = new JPanel();
    final JLabel savedHdr = label(I18n.t("Ліцензійні обліківки"), 13, true, Theme.MUTED);

    static Btn big(Btn b) {
        b.setFont(Theme.font(Font.BOLD, 15));
        b.setBorder(new EmptyBorder(15, 22, 15, 22));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        return b;
    }

    JPanel loginPage() {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                int w = getWidth(), h = getHeight(), y0 = Theme.TITLE_H;
                Art.coverCached(g, Art.get(Art.LOGIN), 0, y0, w, h - y0, 0.5);
                g.setPaint(new GradientPaint(0, y0, Theme.light ? new Color(243, 244, 247, 175) : new Color(12, 13, 16, 150), 0, h, Theme.light ? new Color(243, 244, 247, 238) : new Color(12, 13, 16, 225)));
                g.fillRect(0, y0, w, h - y0);
                g.dispose();
            }
        };
        p.setOpaque(false);
        p.add(topBar(false), BorderLayout.NORTH);

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        Btn ms = big(new Btn(I18n.t("Увійти через Microsoft"), Btn.Kind.PRIMARY));
        Btn off = big(new Btn(I18n.t("Грати без ліцензії"), Btn.Kind.SECONDARY));
        loginBack = big(new Btn(I18n.t("Назад"), Btn.Kind.GHOST));
        loginBack.addActionListener(e -> { if (accounts.current() != null) enterApp(); });

        savedBox.setOpaque(false);
        savedBox.setLayout(new BoxLayout(savedBox, BoxLayout.Y_AXIS));
        savedBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        savedHdr.setAlignmentX(Component.LEFT_ALIGNMENT);

        col.add(savedHdr);
        col.add(Box.createVerticalStrut(8));
        col.add(savedBox);
        col.add(ms);
        col.add(Box.createVerticalStrut(10));
        col.add(off);
        col.add(Box.createVerticalStrut(10));
        col.add(loginBack);
        JComponent wide = (JComponent) Box.createHorizontalStrut(380);
        wide.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(wide);

        Card card = new Card(new BorderLayout(), 30);
        card.add(col, BorderLayout.CENTER);
        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        center.add(card);
        p.add(center, BorderLayout.CENTER);

        ms.addActionListener(e -> loginMicrosoft());
        off.addActionListener(e -> offlineDialog());
        return p;
    }

    void updateAccountUi() {
        Accounts.Acc a = accounts.current();
        accName.setText(a == null ? "—" : a.name());
        playerLbl.setText(a == null ? "—" : a.name());
        accType.setText(a == null ? " " : a.label());
        Theme.skin = a == null ? 0 : Theme.skinFor(a.name());
        Theme.headImg = a != null && a.type().equals("ms") ? accounts.headCached(a.uuid()) : null;
        if (a != null && a.type().equals("ms")) loadHeadAsync(a);
        if (avatarBtn != null) avatarBtn.repaint();
        getContentPane().repaint();
        welcomeLbl.setText(a == null ? I18n.t("Ласкаво просимо!") : I18n.t("Ласкаво просимо, ") + a.name() + "!");
    }

    void loadHeadAsync(Accounts.Acc a) {
        new Thread(() -> {
            java.awt.image.BufferedImage im = accounts.headFetch(a.uuid());
            if (im == null) return;
            SwingUtilities.invokeLater(() -> {
                Accounts.Acc cur = accounts.current();
                if (cur != null && cur.uuid().equals(a.uuid())) Theme.headImg = im;
                if (avatarBtn != null) avatarBtn.repaint();
                getContentPane().repaint();
                refreshSavedAccounts();
            });
        }, "head-" + a.name()).start();
    }

    void enterApp() {
        updateAccountUi();
        bg.side = true;
        bg.repaint();
        rootCards.show(rootPanel, "app");
        showPage("play");
    }

    void showLogin() {
        bg.side = false;
        bg.repaint();
        refreshSavedAccounts();
        rootCards.show(rootPanel, "login");
    }

    void refreshSavedAccounts() {
        if (loginBack != null) loginBack.setVisible(accounts.current() != null);
        savedBox.removeAll();
        List<Accounts.Acc> list = accounts.msAccounts();
        savedHdr.setVisible(!list.isEmpty());
        Accounts.Acc cur = accounts.current();
        JPanel rows = new JPanel();
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        for (Accounts.Acc acc : list) {
            boolean active = cur != null && cur.type().equals("ms") && cur.uuid().equals(acc.uuid());
            rows.add(savedRow(acc, active));
            rows.add(Box.createVerticalStrut(6));
            if (accounts.headCached(acc.uuid()) == null) {
                new Thread(() -> {
                    if (accounts.headFetch(acc.uuid()) != null) SwingUtilities.invokeLater(savedBox::repaint);
                }, "head-" + acc.name()).start();
            }
        }
        if (!list.isEmpty()) {
            JScrollPane sp = new JScrollPane(rows);
            Smooth.install(sp);
            sp.setBorder(null);
            sp.setOpaque(false);
            sp.getViewport().setOpaque(false);
            sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
            sp.setAlignmentX(Component.LEFT_ALIGNMENT);
            int h = Math.min(list.size(), 4) * 58;
            sp.setPreferredSize(new Dimension(380, h));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            savedBox.add(sp);
            savedBox.add(Box.createVerticalStrut(10));
        }
        savedBox.revalidate();
        savedBox.repaint();
    }

    JComponent savedRow(Accounts.Acc acc, boolean active) {
        JPanel row = new JPanel(new BorderLayout(12, 0)) {
            boolean hov;
            { addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hov = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hov = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { useSaved(acc); }
            }); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(active ? Theme.SEL : hov ? Theme.CARD_HOV : Theme.CARD);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g.dispose();
            }
        };
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(8, 10, 8, 10));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setPreferredSize(new Dimension(380, 52));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        JComponent head = new JComponent() {
            { setPreferredSize(new Dimension(36, 36)); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.headRound(g, 0, 0, 36, 10, accounts.headCached(acc.uuid()));
                g.dispose();
            }
        };
        JPanel names = new JPanel();
        names.setOpaque(false);
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        JLabel n = label(acc.name(), 14, true, Theme.TEXT);
        JLabel t = label(active ? "Microsoft · " + I18n.t("Поточна обліківка") : "Microsoft", 12, false, Theme.MUTED);
        names.add(Box.createVerticalGlue());
        names.add(n);
        names.add(t);
        names.add(Box.createVerticalGlue());

        Btn del = small(new Btn(I18n.t("Видалити"), Btn.Kind.GHOST));
        del.addActionListener(e -> {
            if (!Dlg.show(this, I18n.t("Видалити"), Dlg.text(I18n.t("Видалити цю обліківку зі списку?") + "\n" + acc.name()),
                    I18n.t("Видалити"), I18n.t("Скасувати"))) return;
            accounts.removeMs(acc.uuid());
            Accounts.Acc cur = accounts.current();
            if (cur == null) { Theme.headImg = null; updateAccountUi(); }
            refreshSavedAccounts();
        });
        row.add(head, BorderLayout.WEST);
        row.add(names, BorderLayout.CENTER);
        row.add(del, BorderLayout.EAST);
        return row;
    }

    void useSaved(Accounts.Acc acc) {
        accounts.setActive(acc);
        enterApp();
    }

    void loginEly() {
        Field user = new Field("");
        PassField pass = new PassField();
        Field totp = new Field("");
        JPanel pn = new JPanel();
        pn.setLayout(new BoxLayout(pn, BoxLayout.Y_AXIS));
        Object[][] rows = {{I18n.t("Ім'я користувача або е-пошта"), user}, {I18n.t("Пароль"), pass}, {I18n.t("Код двоетапної перевірки (лише якщо ввімкнено)"), totp}};
        for (Object[] r : rows) {
            JLabel l = label((String) r[0], 13, true, Theme.MUTED);
            JComponent f = (JComponent) r[1];
            f.setPreferredSize(new Dimension(340, 42));
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            f.setAlignmentX(Component.LEFT_ALIGNMENT);
            pn.add(l);
            pn.add(Box.createVerticalStrut(4));
            pn.add(f);
            pn.add(Box.createVerticalStrut(10));
        }
        if (!Dlg.show(this, I18n.t("Вхід через Ely.by"), pn, I18n.t("Увійти"), I18n.t("Скасувати"))) return;
        String u = user.getText().trim(), pw = new String(pass.getPassword()), tp = totp.getText().trim();
        if (u.isEmpty() || pw.isEmpty()) { msg(I18n.t("Введи ім'я користувача й пароль.")); return; }
        new Thread(() -> {
            try {
                Accounts.Acc a = accounts.loginEly(u, pw, tp);
                accounts.setActive(a);
                log(I18n.t("Ely.by: вхід як ") + a.name());
                SwingUtilities.invokeLater(this::enterApp);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> msg(I18n.t("Не вдалося увійти через Ely.by:\n") + ex.getMessage()));
            }
        }, "ely-login").start();
    }

    void loginMicrosoft() {
        final String cid = Accounts.LEGACY_CID;
        saveSettings();
        new Thread(() -> {
            try {
                Accounts.Dev d = accounts.msStart(cid);
                SwingUtilities.invokeLater(() -> msDeviceDialog(cid, d));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> msg(I18n.t("Не вдалося почати вхід через Microsoft:\n") + ex.getMessage()));
            }
        }, "ms-start").start();
    }

    void msDeviceDialog(String cid, Accounts.Dev d) {
        AtomicBoolean cancelled = new AtomicBoolean(), done = new AtomicBoolean();
        JDialog[] dlg = new JDialog[1];

        JPanel pn = new JPanel();
        pn.setLayout(new BoxLayout(pn, BoxLayout.Y_AXIS));
        JLabel l1 = label(I18n.t("Відкрий сторінку й введи цей код:"), 14, false, Theme.TEXT);
        JLabel code = label(d.userCode(), 38, true, Theme.ACC2);
        JLabel url = label(d.url(), 13, false, Theme.MUTED);
        JLabel wait = label(I18n.t("Очікую підтвердження…"), 12, false, Theme.MUTED);
        Btn open = small(new Btn(I18n.t("Відкрити сторінку"), Btn.Kind.PRIMARY));
        Btn copy = small(new Btn(I18n.t("Копіювати код"), Btn.Kind.SECONDARY));
        open.addActionListener(e -> browse(d.url()));
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(d.userCode()), null));
        JPanel br = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        br.setOpaque(false);
        br.add(open);
        br.add(copy);
        for (JComponent c : new JComponent[]{l1, code, url, br, wait}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            pn.add(c);
            pn.add(Box.createVerticalStrut(8));
        }
        browse(d.url());

        new Thread(() -> {
            Accounts.Acc a = null;
            String err = null;
            try {
                a = accounts.msFinish(cid, d, cancelled::get);
                accounts.setActive(a);
            } catch (Exception ex) { err = ex.getMessage(); }
            done.set(true);
            Accounts.Acc fa = a;
            String fe = err;
            SwingUtilities.invokeLater(() -> {
                if (dlg[0] != null) dlg[0].dispose();
                if (fa != null) { log(I18n.t("Microsoft: вхід як ") + fa.name()); enterApp(); }
                else if (!cancelled.get()) msg(I18n.t("Не вдалося увійти через Microsoft:\n") + fe);
            });
        }, "ms-login").start();

        Dlg.show(this, I18n.t("Вхід через Microsoft"), pn, I18n.t("Скасувати"), null, x -> dlg[0] = x);
        if (!done.get()) cancelled.set(true);
    }

    void offlineDialog() {
        DefaultListModel<String> model = new DefaultListModel<>();
        for (String n : accounts.offlineNames()) model.addElement(n);
        JList<String> list = new JList<>(model);
        list.setBackground(Theme.CARD2);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(36);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, false);
                setBackground(sel ? Theme.SEL : Theme.CARD2);
                setForeground(Theme.TEXT);
                setFont(Theme.font(Font.PLAIN, 14));
                setBorder(new EmptyBorder(6, 14, 6, 14));
                return this;
            }
        });
        Accounts.Acc cur = accounts.current();
        if (cur != null && cur.type().equals("offline")) list.setSelectedValue(cur.name(), true);
        else if (!model.isEmpty()) list.setSelectedIndex(0);

        JScrollPane sp = new JScrollPane(list); Smooth.install(sp);
        sp.setBorder(null);
        sp.getViewport().setBackground(Theme.CARD2);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        sp.setPreferredSize(new Dimension(380, 170));

        Field add = new Field("");
        add.setPreferredSize(new Dimension(200, 42));
        Btn addB = small(new Btn(I18n.t("Додати"), Btn.Kind.PRIMARY));
        Btn delB = small(new Btn(I18n.t("Видалити"), Btn.Kind.SECONDARY));
        JLabel err = label(I18n.t("Ім'я гравця: 3–16 символів — латиниця, цифри, _"), 12, false, Theme.MUTED);

        Runnable doAdd = () -> {
            String n = add.getText().trim();
            if (!n.matches("[A-Za-z0-9_]{3,16}")) { err.setText(I18n.t("Ім'я гравця має містити 3–16 символів: латиниця, цифри, _")); return; }
            if (!accounts.addOffline(n)) { err.setText(I18n.t("Така обліківка вже є")); return; }
            model.addElement(n);
            list.setSelectedValue(n, true);
            add.setText("");
            err.setText(I18n.t("Додано: ") + n);
        };
        addB.addActionListener(e -> doAdd.run());
        add.addActionListener(e -> doAdd.run());
        delB.addActionListener(e -> {
            String n = list.getSelectedValue();
            if (n == null) { err.setText(I18n.t("Спочатку обери обліківку у списку")); return; }
            accounts.removeOffline(n);
            model.removeElement(n);
            err.setText(I18n.t("Видалено: ") + n);
            if (!model.isEmpty()) list.setSelectedIndex(0);
        });

        JPanel addRow = new JPanel(new BorderLayout(8, 0));
        addRow.setOpaque(false);
        addRow.add(add, BorderLayout.CENTER);
        JPanel bb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bb.setOpaque(false);
        bb.add(addB);
        bb.add(delB);
        addRow.add(bb, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(addRow, BorderLayout.NORTH);
        south.add(err, BorderLayout.SOUTH);

        JPanel pn = new JPanel(new BorderLayout(0, 12));
        pn.setOpaque(false);
        pn.add(label(I18n.t("Неліцензійні обліківки"), 13, true, Theme.MUTED), BorderLayout.NORTH);
        pn.add(sp, BorderLayout.CENTER);
        pn.add(south, BorderLayout.SOUTH);

        if (!Dlg.show(this, I18n.t("Грати без ліцензії"), pn, I18n.t("Грати з цією обліківкою"), I18n.t("Закрити"))) return;
        String sel = list.getSelectedValue();
        if (sel == null) { msg(I18n.t("Обери обліківку у списку або додай нову.")); return; }
        accounts.setActive(Accounts.offline(sel));
        enterApp();
    }

    void javaMissing(int n) {
        boolean yes = Dlg.show(this, I18n.t("Потрібна Java ") + n,
                Dlg.text(I18n.t("Для цієї версії гри потрібна Java ") + n + I18n.t(", але її не знайдено.\nЗапускач може сам завантажити потрібну Java.")),
                I18n.t("Завантажити Java"), I18n.t("Відмовитися від завантаження"));
        if (yes) fetchJava(n, this::play); else status.setText(" ");
    }

    void fetchJava(int n, Runnable after) {
        setBusy(true);
        bar.set(0);
        engine.stop = false;
        cancelUi(true, cancelBtn, pauseBtn);
        Engine.Progress pg = (t, f) -> SwingUtilities.invokeLater(() -> { status.setText(t); bar.set(f); });
        boolean[] ok = {false};
        new Thread(() -> {
            try {
                Path exe = engine.downloadJava(n, pg);
                log(I18n.t("Java ") + n + I18n.t(" готова: ") + exe);
                ok[0] = true;
            } catch (Engine.Cancelled cx) {
                log(I18n.t("Завантаження скасовано"));
            } catch (Exception ex) {
                log(I18n.t("Не вдалося завантажити Java: ") + ex);
                SwingUtilities.invokeLater(() -> msg(I18n.t("Не вдалося завантажити Java:\n") + ex.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    bar.set(0);
                    status.setText(" ");
                    setBusy(false);
                    cancelUi(false, cancelBtn, pauseBtn);
                    if (ok[0]) after.run();
                });
            }
        }, "java-download").start();
    }

    // При першому запуску (options.txt ще немає) ставимо українську мову гри.
    // Далі файл належить гравцеві й лаунчер його більше не чіпає.
    void firstLaunchLang(Path gd, String mc) {
        try {
            Path opt = gd.resolve("options.txt");
            if (Files.exists(opt)) return;
            // до 1.11 код мови писався з великою регіональною частиною: uk_UA, далі uk_ua
            java.util.regex.Matcher m = Pattern.compile("^1\\.(\\d+)").matcher(mc == null ? "" : mc);
            boolean legacy = m.find() && Integer.parseInt(m.group(1)) < 11;
            String code = legacy ? "uk_UA" : "uk_ua";
            Files.writeString(opt, "lang:" + code + "\n", StandardCharsets.UTF_8);
            log(I18n.t("Перший запуск: мову гри встановлено ") + code);
        } catch (Exception ignored) { }
    }

    void play() {
        Accounts.Acc acc = accounts.current();
        if (acc == null) { msg(I18n.t("Спочатку обери обліківку.")); showLogin(); return; }
        Object o = versions.getSelectedItem();
        if (!(o instanceof VItem)) { msg(I18n.t("Спочатку обери версію гри.")); return; }
        VItem it = (VItem) o;
        saveSettings();
        setBusy(true);
        bar.set(0);
        engine.stop = false;
        cancelUi(true, cancelBtn, pauseBtn);
        boolean hide = hideC.isSelected();
        Engine.Progress pg = (t, f) -> SwingUtilities.invokeLater(() -> { status.setText(t); bar.set(f); });

        new Thread(() -> {
            try {
                pg.update(I18n.t("Запуск: ") + it.id() + " (" + Engine.detectLoader(it.id()) + ")…", 0);
                Map<String, Object> v = engine.loadVersion(it.id());
                log(I18n.t("Версія: ") + it.id() + I18n.t("  ·  завантажувач: ") + Engine.detectLoader(it.id()) + "  ·  mainClass: " + Json.string(v.get("mainClass")));
                Path gd = it.profile() != null ? instDir(it.profile()) : Path.of(cfg.get("gameDir", root.toString()));
                if (it.profile() != null) log(I18n.t("Збірка «") + it.profile() + "»: " + gd);
                pg.update(I18n.t("Перевірка обліківки…"), 0);
                Accounts.Acc a = accounts.ensureFresh(acc, Accounts.LEGACY_CID);
                List<String> cmd = engine.build(v, cfg, a, pg, gd);
                firstLaunchLang(gd, Json.string(v.get("_jar")) != null ? Json.string(v.get("_jar")) : it.id());
                String line = String.join(" ", cmd);
                if (a.token().length() > 3) line = line.replace(a.token(), "***");
                log(I18n.t("Запуск: ") + (line.length() > 500 ? line.substring(0, 500) + " …" : line));
                Process pr = new ProcessBuilder(cmd).directory(gd.toFile()).redirectErrorStream(true).start();
                if (it.profile() == null) noteSession(it.id(), gd);
                pg.update(I18n.t("Гра запущена. Приємної гри!"), 1);
                SwingUtilities.invokeLater(() -> cancelUi(false, cancelBtn, pauseBtn));
                String mcv = Json.string(v.get("_jar")) != null ? Json.string(v.get("_jar")) : it.id();
                String ld = Engine.detectLoader(it.id());
                inGame = true;
                rpc.update(I18n.t("Грає в Minecraft ") + mcv,
                        ld.equals("Vanilla") ? I18n.t("Чиста гра · через PaxLauncher")
                                : ld + (it.profile() != null ? I18n.t(" · збірка «") + it.profile() + "»" : I18n.t(" · через PaxLauncher")),
                        System.currentTimeMillis() / 1000);
                if (hide) SwingUtilities.invokeLater(() -> { setVisible(false); trimMemory(); });
                try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                    String ln;
                    while ((ln = r.readLine()) != null) log(ln);
                }
                int code = pr.waitFor();
                log(I18n.t("Гра завершилась з кодом ") + code);
                inGame = false;
                presenceIdle();
                pg.update(code == 0 ? " " : I18n.t("Гра завершилась з помилкою (код ") + code + I18n.t("), дивись Консоль"), 0);
            } catch (Engine.Cancelled cx) {
                log(I18n.t("Завантаження скасовано"));
                SwingUtilities.invokeLater(() -> { status.setText(I18n.t("Завантаження скасовано")); bar.set(0); });
            } catch (Engine.JavaMissing jm) {
                log(jm.getMessage());
                SwingUtilities.invokeLater(() -> javaMissing(jm.major));
            } catch (Exception ex) {
                log(I18n.t("ПОМИЛКА: ") + ex);
                SwingUtilities.invokeLater(() -> {
                    status.setText(I18n.t("Помилка запуску"));
                    msg(I18n.t("Не вдалося запустити гру:\n") + ex.getMessage());
                });
            } finally {
                SwingUtilities.invokeLater(() -> { setVisible(true); setBusy(false); cancelUi(false, cancelBtn, pauseBtn); });
            }
        }, "launch").start();
    }
}
