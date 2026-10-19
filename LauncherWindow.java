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
    final JTextPane console = new JTextPane();
    enum Lvl { ERROR, WARN, INFO, DEBUG }
    record LogEnt(Lvl lvl, String text) { }
    final List<LogEnt> logAll = new ArrayList<>();
    final EnumMap<Lvl, Check> lvlChecks = new EnumMap<>(Lvl.class);
    final Field conQ = new Field("");
    Lvl lastLvl = Lvl.INFO;
    static final int LOG_MAX = 6000;
    final Engine engine = new Engine(root, this::log);
    final ModApi api = new ModApi(engine);

    final CardLayout cards = new CardLayout();
    final FadePanel pages = new FadePanel(cards);
    final Map<String, Component> pageComp = new HashMap<>();
    final Map<String, NavBtn> nav = new LinkedHashMap<>();

    final Combo<VItem> versions = new Combo<>();
    final Check cRel = new Check(I18n.t("Випуски"), true), cSnap = new Check(I18n.t("Знімки"), false), cOld = new Check(I18n.t("Давні (бета/альфа)"), false);
    final Bar bar = new Bar();
    final JLabel status = label(I18n.t("Готово до запуску"), 13, false, Theme.MUTED);
    final Btn playBtn = new Btn(I18n.t("Грати"), Btn.Kind.PRIMARY);
    final Set<String> running = ConcurrentHashMap.newKeySet();

    static String accKey(Accounts.Acc a) { return a.type() + ":" + a.name() + ":" + a.uuid(); }
    final Btn loadersBtn = new Btn(I18n.t("Створити збірку"), Btn.Kind.SECONDARY);
    boolean filling;
    volatile boolean busy;

    final Combo<String> lMc = new Combo<>(), lType = new Combo<>();
    final Check lSnaps = new Check(I18n.t("Показати знімки та давні версії"), false);
    final JLabel lvLbl = label(I18n.t("Версія завантажувача"), 13, true, Theme.MUTED);
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
    static final int[][] RES_PRESETS = {{1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}};
    final Combo<String> resC = new Combo<>();
    final JLabel resHint = label(" ", 12, false, Theme.MUTED);
    boolean resSync;
    String pendLang;
    boolean themeDirty;
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
    final Combo<ModApi.Source> mSrc = new Combo<>();
    boolean srcAuto;
    ModApi.Source srcNow() {
        Object o = mSrc.getSelectedItem();
        return o instanceof ModApi.Source ? (ModApi.Source) o : ModApi.Source.MODRINTH;
    }
    boolean srcChosen() { return mSrc.getSelectedItem() instanceof ModApi.Source; }
    /** Пошук доступний, лише коли обрано і категорію, і платформу. */
    boolean kindChosen() { return mKind.getSelectedItem() instanceof ModApi.Kind && srcChosen(); }
    final Btn mDirB = new Btn(I18n.t("Папка модів"), Btn.Kind.SECONDARY);
    final Btn mRmB = new Btn(I18n.t("Видалити мод"), Btn.Kind.SECONDARY);
    final JLabel mInstTitle = label(I18n.t("Встановлені моди"), 15, true, Theme.TEXT);
    final JLabel mVerLabel = label(I18n.t("Версія мода (для цієї збірки)"), 12, true, Theme.MUTED);
    final Field mQuery = new Field("");
    final JLabel mInfo = label(" ", 13, false, Theme.MUTED);
    final JPanel mInfoWrap = new JPanel();
    Btn mImpB, mImpPackB;
    JPanel mTop;
    JPanel manageHead, mRow1, mRow2, mR1b, mR2b, mhActions, mhSearch;
    final JLabel mhName = label(" ", 18, true, Theme.TEXT);
    final JLabel mhSub = label(" ", 12, false, Theme.MUTED);
    BufferedImage mhImg;
    final List<KindPill> kindPills = new ArrayList<>();

    /** Перемикач категорії у вигляді «пілюль» для режиму керування збіркою. */
    static final class KindPill extends JComponent {
        final ModApi.Kind kind;
        final String text;
        boolean sel, hov;
        KindPill(String text, Runnable click) { this(null, text, click); }
        KindPill(ModApi.Kind k, Runnable click) { this(k, k.title, click); }
        private KindPill(ModApi.Kind k, String text, Runnable click) {
            this.kind = k;
            this.text = text;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Font f = Theme.bfont(Font.BOLD, 12);
            setFont(f);
            int w = getFontMetrics(f).stringWidth(text) + Theme.px(26);
            setPreferredSize(new Dimension(w, Theme.px(30)));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hov = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hov = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { click.run(); }
            });
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            int w = getWidth(), h = getHeight();
            g.setColor(sel ? Theme.ACC2 : hov ? Theme.CARD_HOV : Theme.INPUT);
            g.fillRoundRect(0, 0, w, h, h, h);
            g.setFont(getFont());
            g.setColor(sel ? Theme.ACC_ON : hov ? Theme.TEXT : Theme.MUTED);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (w - fm.stringWidth(text)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    void syncPills() {
        Object cur = mKind.getSelectedItem();
        for (KindPill p : kindPills) { p.sel = p.kind == cur; p.repaint(); }
    }

    /** Повзунок ОЗП для збірки. Без власного значення показує загальне з налаштувань. */
    final class RamPick extends JPanel {
        final JSlider sl = new JSlider(2, 32, 8);
        final JLabel lbl = label(" ", 12, false, Theme.MUTED);
        boolean quiet;
        RamPick(Runnable onChange) {
            super(new BorderLayout(12, 0));
            setOpaque(false);
            sl.setOpaque(false);
            sl.setUI(new SlimSliderUI(sl));
            sl.setMaximum((int) Math.max(4, Math.min(64, totalMemMb() / 512)));
            lbl.setPreferredSize(new Dimension(Theme.px(90), 24));
            add(sl, BorderLayout.CENTER);
            add(lbl, BorderLayout.EAST);
            sl.addChangeListener(e -> {
                lbl.setText(sl.getValue() * 512 + " MB");
                if (!quiet && !sl.getValueIsAdjusting() && onChange != null) onChange.run();
            });
        }
        int globalMb() {
            int def = (int) Math.max(1024, Math.min(4096, totalMemMb() / 2));
            return cfg.geti("ram", def);
        }
        /** mb < 512 — значення не задано, береться загальне з налаштувань. */
        void setValueMb(int mb) {
            quiet = true;
            int v = mb >= 512 ? mb : globalMb();
            sl.setValue(Math.max(sl.getMinimum(), Math.min(sl.getMaximum(), v / 512)));
            lbl.setText(sl.getValue() * 512 + " MB");
            quiet = false;
        }
        int valueMb() { return sl.getValue() * 512; }
    }

    RamPick mhRam;
    boolean mhRamLoading;

    int profileRam(String name) {
        try (Reader rd = Files.newBufferedReader(instDir(name).resolve("profile.properties"), StandardCharsets.UTF_8)) {
            Properties pr = new Properties();
            pr.load(rd);
            return Integer.parseInt(pr.getProperty("ram", "0").trim());
        } catch (Exception e) { return 0; }
    }

    void saveProfileRam(String name, int mb) {
        Path pf = instDir(name).resolve("profile.properties");
        try {
            Properties pr = new Properties();
            if (Files.isRegularFile(pf)) try (Reader rd = Files.newBufferedReader(pf, StandardCharsets.UTF_8)) { pr.load(rd); }
            if (mb >= 512) pr.setProperty("ram", String.valueOf(mb)); else pr.remove("ram");
            try (Writer w = Files.newBufferedWriter(pf, StandardCharsets.UTF_8)) { pr.store(w, "PaxLauncher profile"); }
        } catch (IOException ex) { log(I18n.t("Не вдалося зберегти налаштування збірки: ") + ex.getMessage()); }
    }

    void updateManageHead() {
        Prof p = curProf();
        if (p == null) return;
        mhName.setText(p.name());
        mhSub.setText(p.loader() + "  ·  Minecraft " + p.mc());
        if (mhRam != null) { mhRamLoading = true; mhRam.setValueMb(profileRam(p.name())); mhRamLoading = false; }
        mhImg = BuildIcons.load(instDir(p.name()), Theme.px(48) * 2);
        if (manageHead != null) manageHead.repaint();
    }

    JPanel buildManageHead() {
        JComponent ic = new JComponent() {
            { setPreferredSize(new Dimension(Theme.px(48), Theme.px(48))); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                int s = Math.min(getWidth(), getHeight());
                if (mhImg != null) g.drawImage(mhImg, 0, 0, s, s, null);
                g.dispose();
            }
        };
        JPanel names = new JPanel();
        names.setOpaque(false);
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        mhName.setAlignmentX(Component.LEFT_ALIGNMENT);
        mhSub.setAlignmentX(Component.LEFT_ALIGNMENT);
        names.add(Box.createVerticalGlue());
        names.add(mhName);
        names.add(Box.createVerticalStrut(2));
        names.add(mhSub);
        names.add(Box.createVerticalGlue());
        JPanel id = new JPanel(new BorderLayout(12, 0));
        id.setOpaque(false);
        id.add(ic, BorderLayout.WEST);
        id.add(names, BorderLayout.CENTER);

        mhActions = new JPanel();
        mhActions.setOpaque(false);
        mhActions.setLayout(new BoxLayout(mhActions, BoxLayout.X_AXIS));
        JPanel actW = new JPanel(new GridBagLayout());
        actW.setOpaque(false);
        actW.add(mhActions);

        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        top.add(id, BorderLayout.WEST);
        top.add(actW, BorderLayout.EAST);

        JPanel pills = new JPanel();
        pills.setOpaque(false);
        pills.setLayout(new BoxLayout(pills, BoxLayout.X_AXIS));
        for (ModApi.Kind k : new ModApi.Kind[]{ModApi.Kind.MOD, ModApi.Kind.PACK, ModApi.Kind.SHADER, ModApi.Kind.WORLD, ModApi.Kind.MODPACK}) {
            KindPill kp = new KindPill(k, () -> mKind.setSelectedItem(k));
            kindPills.add(kp);
            if (pills.getComponentCount() > 0) pills.add(Box.createHorizontalStrut(6));
            pills.add(kp);
        }
        JPanel pillsW = new JPanel(new GridBagLayout());
        pillsW.setOpaque(false);
        pillsW.add(pills);

        mhSearch = new JPanel(new BorderLayout());
        mhSearch.setOpaque(false);
        mhSearch.setPreferredSize(new Dimension(Theme.px(280), Theme.px(38)));
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setOpaque(false);
        bar.add(pillsW, BorderLayout.WEST);
        bar.add(mhSearch, BorderLayout.EAST);

        JPanel wrap = new JPanel(new BorderLayout(0, 12));
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(0, 0, 4, 0));
        wrap.add(top, BorderLayout.NORTH);
        wrap.add(bar, BorderLayout.CENTER);
        mhRam = new RamPick(() -> {
            Prof cp = curProf();
            if (cp != null && !mhRamLoading) saveProfileRam(cp.name(), mhRam.valueMb());
        });
        JLabel ramT = label(I18n.t("Виділена пам'ять (ОЗП)"), 12, true, Theme.MUTED);
        JLabel ramH = label(I18n.t("Діє з наступного запуску гри."), 11, false, Theme.MUTED);
        JPanel ramBox = new JPanel(new BorderLayout(12, 0));
        ramBox.setOpaque(false);
        ramBox.setBorder(new EmptyBorder(4, 0, 0, 0));
        JPanel ramL = new JPanel();
        ramL.setOpaque(false);
        ramL.setLayout(new BoxLayout(ramL, BoxLayout.Y_AXIS));
        ramT.setAlignmentX(Component.LEFT_ALIGNMENT);
        ramH.setAlignmentX(Component.LEFT_ALIGNMENT);
        ramL.add(ramT);
        ramL.add(ramH);
        ramL.setPreferredSize(new Dimension(Theme.px(190), ramL.getPreferredSize().height));
        ramBox.add(ramL, BorderLayout.WEST);
        ramBox.add(mhRam, BorderLayout.CENTER);
        wrap.add(ramBox, BorderLayout.SOUTH);
        wrap.setVisible(false);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        return wrap;
    }

    /** У режимі керування кнопки «Імпортувати файли / Назад» і пошук живуть у шапці збірки; поза ним — у звичайних рядках. */
    void placeManageControls(boolean manage) {
        if (mhActions == null || mR1b == null) return;
        if (manage) {
            mhActions.removeAll();
            mhActions.add(mImpB);
            mhActions.add(Box.createHorizontalStrut(8));
            mhActions.add(mManage);
            mhSearch.removeAll();
            mhSearch.add(mQuery, BorderLayout.CENTER);
        } else {
            mR1b.removeAll();
            mR1b.add(mImpB);
            mR1b.add(Box.createHorizontalStrut(8));
            mR1b.add(mManage);
            mR1b.add(Box.createHorizontalGlue());
            mRow2.add(mQuery, BorderLayout.CENTER);
        }
        mhActions.revalidate(); mhSearch.revalidate(); mR1b.revalidate(); mRow2.revalidate();
    }
    Btn lOk;
    JPanel loadersBox;
    Card mCard;
    final JLabel dIcon = new JLabel();
    JPanel dIconWrap;
    final JPanel dChips = chipRow();
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

    void syncDetailIcon(String tok) {
        if (dIconWrap == null) return;
        BufferedImage im = tok == null || tok.isEmpty() ? null : iconRaw.get(tok);
        boolean has = im != null && im != NO_ICON;
        if (dIconWrap.isVisible() != has) {
            dIconWrap.setVisible(has);
            if (dIconWrap.getParent() != null) { dIconWrap.getParent().revalidate(); dIconWrap.getParent().repaint(); }
        }
    }

    void setLabelIcon(JLabel l, String tok, int size) {
        l.putClientProperty("tok", tok);
        l.setIcon(modIcon(tok, size, l));
        if (l == dIcon) syncDetailIcon(tok);
        if (tok == null || tok.isEmpty() || iconRaw.containsKey(tok)) return;
        javax.swing.Timer t = new javax.swing.Timer(300, null);
        int[] n = {0};
        t.addActionListener(e -> {
            if (!tok.equals(l.getClientProperty("tok")) || ++n[0] > 60) { t.stop(); return; }
            if (iconRaw.containsKey(tok)) {
                t.stop();
                l.setIcon(modIcon(tok, size, l));
                if (l == dIcon) syncDetailIcon(tok);
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
    final JLabel mStatus = label(" ", 13, false, Theme.MUTED);
    final Bar mBar = new Bar();
    final DefaultListModel<ModApi.Hit> mModel = new DefaultListModel<>();
    volatile boolean mLoading;
    final javax.swing.Timer mPulse = new javax.swing.Timer(40, null);
    final JComponent mSpin = new JComponent() {
        { setPreferredSize(new Dimension(Theme.px(44), Theme.px(44))); setOpaque(false); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.min(getWidth(), getHeight()) - 6;
            int x = (getWidth() - d) / 2, y = (getHeight() - d) / 2;
            g.setStroke(new BasicStroke(Theme.px(4), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(1f, 1f, 1f, 0.10f));
            g.drawOval(x, y, d, d);
            int ang = (int) (System.nanoTime() / 1_000_000 % 1000) * 360 / 1000;
            g.setColor(Theme.MUTED);
            g.drawArc(x, y, d, d, -ang, -100);
            g.dispose();
        }
    };
    final JList<ModApi.Hit> mList = new JList<ModApi.Hit>(mModel) {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!mLoading || !mModel.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double ph = (System.nanoTime() / 1_000_000 % 1400) / 1400.0;
            int rows = Math.max(1, getHeight() / 58 + 1);
            for (int i = 0; i < rows; i++) {
                double w = 0.5 + 0.5 * Math.sin((ph - i * 0.08) * Math.PI * 2);
                float a = (float) (0.10 + 0.10 * w);
                int y = i * 58 + 9;
                g2.setColor(new Color(1f, 1f, 1f, a));
                g2.fillRoundRect(12, y, 40, 40, 12, 12);
                g2.fillRoundRect(64, y + 5, Math.max(40, (int) (getWidth() * (i % 3 == 0 ? 0.50 : i % 3 == 1 ? 0.38 : 0.56))), 12, 8, 8);
                g2.fillRoundRect(64, y + 25, Math.max(40, (int) (getWidth() * (i % 2 == 0 ? 0.68 : 0.58))), 10, 8, 8);
            }
            g2.dispose();
        }
    };
    final JLabel dTitle = label(I18n.t("Обери мод зі списку"), 18, true, Theme.TEXT);
    final JLabel dMeta = label(" ", 12, false, Theme.MUTED);
    final JTextArea dDesc = new JTextArea();
    final Combo<ModApi.ModFile> mFiles = new Combo<>();
    final Btn mInstall = new Btn(I18n.t("Встановити"), Btn.Kind.PRIMARY);
    final Btn mManage = new Btn(I18n.t("Керувати збіркою"), Btn.Kind.SECONDARY);
    final Btn mRootB = new Btn(I18n.t("Коренева папка"), Btn.Kind.SECONDARY);
    boolean packInstalledNow;
    final Btn mCancel = new Btn(I18n.t("Зупинити"), Btn.Kind.DANGER);
    volatile boolean mCancelled;
    final Btn mSearch = new Btn(I18n.t("Знайти"), Btn.Kind.PRIMARY);
    final Btn mMore = new Btn(I18n.t("Ще результати"), Btn.Kind.SECONDARY);
    final Btn mPage = new Btn(I18n.t("Сторінка мода"), Btn.Kind.SECONDARY);
    final DefaultListModel<String> iModel = new DefaultListModel<>();
    final JList<String> iList = emptyAware(iModel);
    final CardLayout mCards = new CardLayout();
    final JPanel mCenter = new JPanel(mCards);
    JScrollPane playScroll;

    static final String DISCORD_URL = "https://discord.gg/WeP5n53z8e";
    static final String SITE_URL = "https://paxlauncher.pp.ua";
    static final String SUPPORT_URL = "https://send.monobank.ua/jar/A8UDDsJojo";
    final Btn mToggleView = new Btn(I18n.t("Встановлені моди"), Btn.Kind.SECONDARY);
    JPanel dFoot;
    final AtomicInteger mSeq = new AtomicInteger(), mfSeq = new AtomicInteger();
    boolean mFilling, mBusy, mInstalledView;

    volatile ModApi.Kind kindNow = ModApi.Kind.MOD;

    void prefetchMods(String mcv, String loader) {
        if (mcv == null || loader == null || !srcChosen()) return;
        final ModApi.Source src = srcNow();
        for (ModApi.Kind k : ModApi.Kind.values()) {
            if (k.isPack()) continue;
            if (k.isMod() && loader.equals("Vanilla")) continue;
            if (src == ModApi.Source.MODRINTH && !k.onModrinth()) continue;
            Thread t = new Thread(() -> {
                try { warmHits(api.search(src, k, "", mcv, loader, 0), 8000); } catch (Throwable ignored) { }
            }, "mods-prefetch-" + k.type);
            t.setDaemon(true);
            t.start();
        }
    }

    void warmHits(List<ModApi.Hit> hs, long maxMs) { warmHits(hs, maxMs, false, () -> false); }

    /** Готує іконку одразу (у фоні), щоб потік інтерфейсу не масштабував зображення під час малювання списку. */
    void prebuildIcon(String url, BufferedImage im) {
        if (url == null || im == null || im == NO_ICON) return;
        try { iconObjs.computeIfAbsent(url + "@40", k -> new ImgIcon(im, 40)); } catch (Throwable ignored) { }
    }

    void warmHits(List<ModApi.Hit> hs, long maxMs, boolean more, java.util.function.BooleanSupplier stale) {
        if (hs == null || hs.isEmpty()) return;
        long end = System.currentTimeMillis() + maxMs;
        List<Future<?>> fs = new ArrayList<>();
        int n = hs.size();
        for (int i = 0; i < n; i++) {
            ModApi.Hit h = hs.get(i);
            final boolean head = i == 0 && !more;
            String u = h.iconUrl();
            if (u != null && !u.isEmpty())
                fs.add(iconPool.submit(() -> {
                    if (stale.getAsBoolean()) return;
                    BufferedImage im = iconRaw.get(u);
                    if (im == null) {
                        im = fetchProjectIcon(u);
                        if (im != null) iconRaw.put(u, im);
                    }
                    prebuildIcon(u, im);
                }));
            if (api.needsTranslation(h.desc())) fs.add(iconPool.submit(() -> { if (!stale.getAsBoolean()) api.translate(h.desc()); }));
            if (head) fs.add(iconPool.submit(() -> {
                if (stale.getAsBoolean()) return;
                try {
                    ModApi.Detail pd = api.project(h.id());
                    if (pd != null) tr(ModApi.excerpt(pd.body(), 1200));
                } catch (Throwable ignored) { }
            }));
        }
        for (Future<?> f : fs) {
            if (stale.getAsBoolean()) break;
            long left = end - System.currentTimeMillis();
            if (left <= 0) break;
            try { f.get(left, TimeUnit.MILLISECONDS); } catch (Throwable ignored) { }
        }
    }

    LauncherWindow() {
        Art.preload();
        ModApi.translateOn = cfg.getb("translateDesc", true);
        Thread trWarm = new Thread(api::preloadTranslations, "tr-preload");
        trWarm.setDaemon(true);
        trWarm.start();
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
        addPage("play", playPage());
        loadersBox = loadersPage();
        addPage("console", consolePage());
        addPage("gallery", galleryPage());
        addPage("mods", modsPage());
        addPage("library", libraryPage());
        addPage("settings", settingsPage());
        right.add(pages, BorderLayout.CENTER);
        app.add(right, BorderLayout.CENTER);

        rootPanel.setOpaque(false);
        rootPanel.add(loginPage(), "login");
        rootPanel.add(app, "app");
        bg.add(rootPanel, BorderLayout.CENTER);

        loadSettings();
        rpc.configure(discC.isSelected() ? discF.getText().trim() : "");
        presenceIdle();
        showPage("play");
        refreshVersions();
        if (accounts.current() != null) enterApp(); else showLogin();
        SwingUtilities.invokeLater(this::warmPages);
        log(I18n.t("Папка запускача: ") + root);
        live = this;
        Thread pre = new Thread(() -> {
            try {
                api.warm();
                List<Prof> ps = loadProfiles();
                String want = cfg.get("modProfile", "");
                Prof pick = ps.isEmpty() ? null : ps.get(0);
                for (Prof q : ps) if (q.name().equals(want)) pick = q;
                if (pick != null) prefetchMods(pick.mc(), pick.loader());
            } catch (Throwable ignored) { }
        }, "mods-warm");
        pre.setDaemon(true);
        pre.start();
    }

    /** Список, який у порожньому стані малює по центру «Нічого не встановлено». */
    static <T> JList<T> emptyAware(ListModel<T> m) {
        return new JList<T>(m) {
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                if (getModel().getSize() > 0) return;
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                Container v = getParent();
                int w = v instanceof JViewport ? v.getWidth() : getWidth();
                int h = v instanceof JViewport ? v.getHeight() : getHeight();
                String t = I18n.t("Нічого не встановлено");
                g.setFont(Theme.font(Font.BOLD, 16));
                g.setColor(Theme.MUTED);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(t, (w - fm.stringWidth(t)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
                g.dispose();
            }
        };
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

    static final java.util.regex.Pattern LVL_TAG = java.util.regex.Pattern.compile("[/ \\[](FATAL|ERROR|WARN|WARNING|INFO|DEBUG|TRACE)\\]");

    /** Визначає рівень запису: за позначкою Minecraft/Log4j, за стеком помилки, за словами лаунчера. */
    Lvl detectLvl(String s) {
        java.util.regex.Matcher m = LVL_TAG.matcher(s.length() > 200 ? s.substring(0, 200) : s);
        if (m.find()) {
            switch (m.group(1)) {
                case "FATAL": case "ERROR": return Lvl.ERROR;
                case "WARN": case "WARNING": return Lvl.WARN;
                case "DEBUG": case "TRACE": return Lvl.DEBUG;
                default: return Lvl.INFO;
            }
        }
        String t = s.stripLeading();
        if (!s.isEmpty() && (Character.isWhitespace(s.charAt(0)) || t.startsWith("at ") || t.startsWith("Caused by") || t.startsWith("..."))
                && lastLvl == Lvl.ERROR) return Lvl.ERROR;
        if (s.startsWith("Exception ") || t.matches("^[\\w.$]+(Exception|Error)(:.*)?$") || t.startsWith("Caused by")) return Lvl.ERROR;
        String l = s.toLowerCase(Locale.ROOT);
        if (l.contains("помилка") || l.contains("не вдалося") || l.contains(" failed") || l.startsWith("error")) return Lvl.ERROR;
        if (l.startsWith("увага") || l.startsWith("warning") || l.contains("потрібно завантажити вручну")) return Lvl.WARN;
        return Lvl.INFO;
    }

    Color lvlColor(Lvl l) {
        boolean lt = Theme.light;
        switch (l) {
            case ERROR: return lt ? new Color(0xC62828) : new Color(0xFF6B6B);
            case WARN: return lt ? new Color(0x9A6700) : new Color(0xE9B949);
            case DEBUG: return Theme.MUTED;
            default: return Theme.CONSOLE_FG;
        }
    }

    String lvlName(Lvl l) {
        switch (l) {
            case ERROR: return I18n.t("Помилки");
            case WARN: return I18n.t("Попередження");
            case DEBUG: return I18n.t("Debug");
            default: return I18n.t("Інфо");
        }
    }

    boolean lvlShown(LogEnt e, String q) {
        Check c = lvlChecks.get(e.lvl());
        if (c != null && !c.isSelected()) return false;
        return q.isEmpty() || e.text().toLowerCase(Locale.ROOT).contains(q);
    }

    void appendLog(LogEnt e) {
        javax.swing.text.StyledDocument d = console.getStyledDocument();
        javax.swing.text.SimpleAttributeSet a = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setForeground(a, lvlColor(e.lvl()));
        javax.swing.text.StyleConstants.setBold(a, e.lvl() == Lvl.ERROR);
        try { d.insertString(d.getLength(), e.text() + "\n", a); } catch (Exception ignored) { }
    }

    /** Перемальовує консоль за поточними фільтрами (рівні + пошук). */
    void rebuildConsole() {
        String q = conQ.getText().trim().toLowerCase(Locale.ROOT);
        console.setText("");
        StringBuilder none = new StringBuilder();
        for (LogEnt e : logAll) if (lvlShown(e, q)) appendLog(e);
        console.setCaretPosition(console.getDocument().getLength());
        updateLvlCounts();
    }

    void updateLvlCounts() {
        int[] n = new int[Lvl.values().length];
        for (LogEnt e : logAll) n[e.lvl().ordinal()]++;
        for (Lvl l : Lvl.values()) {
            Check c = lvlChecks.get(l);
            if (c != null) c.setText(lvlName(l) + " " + n[l.ordinal()]);
        }
    }

    void clearConsole() {
        logAll.clear();
        lastLvl = Lvl.INFO;
        console.setText("");
        updateLvlCounts();
    }

    /** Повертає весь журнал у вигляді тексту (для перенесення при зміні мови). */
    void loadLog(List<LogEnt> src) {
        logAll.clear();
        logAll.addAll(src);
        rebuildConsole();
    }

    private void flushLog() {
        logPending.set(false);
        String ln;
        boolean any = false, trimmed = false;
        String q = conQ.getText().trim().toLowerCase(Locale.ROOT);
        while ((ln = logQ.poll()) != null) {
            for (String part : ln.split("\\R", -1)) {
                Lvl lv = detectLvl(part);
                lastLvl = lv;
                LogEnt e = new LogEnt(lv, part);
                logAll.add(e);
                any = true;
                if (lvlShown(e, q)) appendLog(e);
            }
        }
        if (!any) return;
        if (logAll.size() > LOG_MAX) {
            logAll.subList(0, logAll.size() - (LOG_MAX - 1000)).clear();
            trimmed = true;
        }
        if (trimmed) rebuildConsole();
        else {
            updateLvlCounts();
            console.setCaretPosition(console.getDocument().getLength());
        }
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

    static volatile LauncherWindow live;
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
    final Check discC = new Check(I18n.t("Показувати гру в Discord"), false);
    final JLabel playerLbl = label("—", 15, true, Theme.TEXT);

    private javax.swing.Timer maxAnim;
    private boolean animating;
    private Boolean translucent;
    private Runnable shapeUpd;
    private static final int[] BASE_SIZE = {1240, 780};
    private static final double MAX_MS = 240e6;
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
            case "library": d = I18n.t("У бібліотеці збірок"); st = I18n.t("Переглядає свої збірки"); break;
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
            case "mods": return I18n.t("Збірки");
            case "library": return I18n.t("Бібліотека збірок");
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

    void addPage(String key, JComponent c) {
        pages.add(c, key);
        pageComp.put(key, c);
    }

    void warmPages() {
        final String[] keys = {"play", "mods", "library", "gallery", "console", "settings", "loaders"};
        final int[] i = {0};
        final java.awt.image.BufferedImage[] scratch = {null};
        javax.swing.Timer wt = new javax.swing.Timer(70, null);
        wt.addActionListener(e -> {
            if (i[0] >= keys.length) {
                wt.stop();
                pages.warm();
                rootPanel.warm();
                return;
            }
            try {
                Component c = pageComp.get(keys[i[0]++]);
                if (c == null || !c.isDisplayable()) return;
                int w = pages.getWidth(), h = pages.getHeight();
                if (w <= 0 || h <= 0) { w = Math.max(900, getWidth() - Theme.SIDE_W); h = Math.max(520, getHeight() - Theme.TITLE_H); }
                if (c.getWidth() != w || c.getHeight() != h) c.setBounds(0, 0, w, h);
                c.validate();
                if (scratch[0] == null || scratch[0].getWidth() != w || scratch[0].getHeight() != h)
                    scratch[0] = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scratch[0].createGraphics();
                c.paint(g);
                g.dispose();
            } catch (Throwable ignored) { }
        });
        wt.setInitialDelay(350);
        wt.start();
    }

    boolean pageShown;

    void showPage(String key) {
        if (key.equals("mods")) manageFromLib = false;
        if (pageShown && key.equals(curPage)) return;
        pageShown = true;
        if (!key.equals("settings") && curPage.equals("settings")) {
            if (themeDirty) revertTheme();
            reloadSettingsForm();
            pendLang = null;
            buildLangControls();
        }
        if (key.equals("mods") && !modsInit) {
            modsInit = true;
            refreshProfiles(null);
        }
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
        if (key.equals("library")) refreshLibrary();
    }

    void exit() {
        try { hostStop(); } catch (Throwable ignored) { }
        try { Upnp.closeAll(); } catch (Throwable ignored) { }
        try { saveVersion(); } catch (Throwable ignored) { }
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

        String[][] top = {{"play", I18n.t("Головна"), "0"}, {"library", I18n.t("Бібліотека"), "7"}, {"mods", I18n.t("Збірки"), "1"}, {"gallery", I18n.t("Галерея"), "6"}, {"console", I18n.t("Консоль"), "3"}};
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
        LinkBtn siteL = new LinkBtn(I18n.t("Сайт запускача"), 2);
        LinkBtn donL = new LinkBtn(I18n.t("Підтримати запускач"), 1);
        siteL.addActionListener(e -> browse(SITE_URL));
        dcL.addActionListener(e -> browse(DISCORD_URL));
        donL.addActionListener(e -> browse(SUPPORT_URL));
        links.add(dcL);
        links.add(siteL);
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

        rp.row(header(I18n.t("Гра з другом")), 26);
        rp.row(friendsCard(), 8);

        rp.row(header(I18n.t("Сервери Minecraft")), 26);
        rp.row(new ServerAds(this::browse), 12);

        rp.row(header(I18n.t("Новини Minecraft")), 26);
        rp.row(new NewsPanel(), 12);
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
        playBtn.setFont(Theme.bfont(Font.BOLD, 17));
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
        loadersBtn.setText(I18n.t("Створити збірку"));
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

        col.row(libraryBanner(), 16);

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
        loadersBtn.addActionListener(e -> newProfile());
        return p;
    }

    // ---------- «Гра з другом» ----------
    LanProxy lan;                      // активна сесія хоста
    volatile String inviteCode;        // код активної сесії хоста
    volatile String inviteNote = " ";
    volatile String joinTarget, pendingJoin, joinCode, hostWorld;
    volatile int hostLanPort;
    volatile String hostAgentMsg;
    volatile boolean hostManualJoin;   // версія без --quickPlaySingleplayer: гравець заходить у світ сам
    volatile boolean hostAlive;
    javax.swing.Timer lanTimer;
    volatile Process joinProc;         // гра, запущена для приєднання
    final JLabel friendsLbl = label(" ", 12, false, Theme.MUTED);
    Field inviteOut;
    JLabel inviteNoteL, hostWorldL;
    Combo<String> hostPlayers, hostBanned;
    static final Pattern LAN_PORT = Pattern.compile("(?iu)\\[CHAT\\].*?(?:port|puert|порт|prt)\\p{L}*\\D{0,6}(\\d{4,5})");

    Btn hostBtn;
    final JLabel friendsNote = label(" ", 11, false, Theme.MUTED);

    JPanel friendsCard() {
        Btn jb = small(new Btn(I18n.t("Приєднатися"), Btn.Kind.SECONDARY));
        Btn hb = small(new Btn(I18n.t("Створити"), Btn.Kind.PRIMARY));
        hostBtn = hb;
        jb.addActionListener(e -> openJoin());
        hb.addActionListener(e -> openHost());
        JPanel row = new JPanel(new GridLayout(2, 1, 0, 8));
        row.setOpaque(false);
        row.add(jb);
        row.add(hb);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setOpaque(false);
        p.add(row, BorderLayout.NORTH);
        JPanel info = new JPanel(new BorderLayout(0, 6));
        info.setOpaque(false);
        info.add(friendsLbl, BorderLayout.NORTH);
        info.add(friendsNote, BorderLayout.CENTER);
        p.add(info, BorderLayout.CENTER);
        // клік по «Сесія: PAX-…» копіює код
        friendsLbl.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                String c = inviteCode;
                if (lan == null || c == null) return;
                copyText(c);
                friendsLbl.setText(I18n.t("Код скопійовано"));
                javax.swing.Timer t = new javax.swing.Timer(1300, ev -> refreshFriendsCard());
                t.setRepeats(false);
                t.start();
            }
        });
        return p;
    }

    void refreshFriendsCard() {
        String t = " ";
        if (lan != null) {
            if (inviteCode == null) t = I18n.t("Створюю сесію…");
            else if (hostAlive) t = I18n.t("Сесія: ") + inviteCode;
            else t = "<html>" + I18n.t("Сесія: ") + inviteCode + "<br>" + I18n.t("світ ще не відкрито") + "</html>";
        }
        else if (joinCode != null) t = I18n.t("Приєднано: ") + joinCode;
        friendsLbl.setText(t);
        friendsNote.setText(lan != null
                ? "<html><div style='width:170px'>" + I18n.t("Якщо закрити запускач — сервер вимкнеться.") + "</div></html>" : " ");
        if (hostBtn != null) {
            hostBtn.setText(lan != null ? I18n.t("Налаштування") : I18n.t("Створити"));
            hostBtn.revalidate(); hostBtn.repaint();
        }
        friendsLbl.setCursor(Cursor.getPredefinedCursor(lan != null && inviteCode != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /** Слідкує за логом гри: коли хост відкрив світ для мережі, сесія спрямовує гравців у нього. */
    Path flagPath() { return root.resolve("runtime").resolve("lan-joined.flag"); }

    static String wrapHtml(String s) {
        if (s == null || s.trim().isEmpty()) return " ";
        return "<html><div style='width:380px'>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>";
    }

    static List<String> worldsOf(Path inst) {
        List<String> out = new ArrayList<>();
        Path saves = inst.resolve("saves");
        if (!Files.isDirectory(saves)) return out;
        try (var st = Files.list(saves)) {
            st.filter(p -> Files.isRegularFile(p.resolve("level.dat"))).forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException ignored) { }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static int freePort(int from) {
        for (int p = from; p < from + 60; p++) {
            try (java.net.ServerSocket s = new java.net.ServerSocket(p)) { return p; } catch (IOException ignored) { }
        }
        return from;
    }

    void inviteHook(String ln) {
        LanProxy l = lan;
        if (l != null && ln != null && ln.contains("[PaxLan]")) {
            hostAgentMsg = ln.substring(ln.indexOf("[PaxLan]") + 8).trim();
            if (hostAgentMsg.startsWith("PORT ")) {
                // агент побачив порт, на якому світ відкрито для мережі (автоматично чи вручну) — спрямовуємо сесію туди
                try { l.setTarget(Integer.parseInt(hostAgentMsg.substring(5).trim())); } catch (NumberFormatException ignored) { }
            }
            SwingUtilities.invokeLater(this::refreshHostUi);
        }
        if (l != null && hostLanPort > 0 && ln != null && ln.contains(" joined the game")) {
            // сигнал агенту в грі: хост зайшов у світ — можна відкривати його для мережі
            try {
                Path f = flagPath();
                Files.createDirectories(f.getParent());
                Files.writeString(f, String.valueOf(System.nanoTime()));
            } catch (IOException ignored) { }
        }
        if (l == null || ln == null || !ln.contains("[CHAT]")) return;
        java.util.regex.Matcher m = LAN_PORT.matcher(ln);
        if (m.find()) {
            try { l.setTarget(Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) { }
            SwingUtilities.invokeLater(this::refreshHostUi);
        }
    }

    void copyText(String t) {
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(t), null); }
        catch (Throwable ignored) { }
    }

    String agentText() {
        String m = hostAgentMsg == null ? "" : hostAgentMsg;
        String manual = I18n.t(" Відкрий світ вручну: Пауза → Відкрити для мережі.");
        if (m.startsWith("PUBLISHED") || m.startsWith("PORT")) return I18n.t("Світ відкрито для мережі…");
        if (m.startsWith("WORLD")) return I18n.t("Ти у світі — відкриваю його для друзів…");
        if (m.startsWith("NOCLASS")) return I18n.t("Агент не знайшов потрібні класи гри.") + manual;
        if (m.startsWith("NOOFFLINE")) return I18n.t("Не вдалося дозволити неліцензійних гравців — вони не зайдуть.");
        if (m.startsWith("OFFLINE")) return I18n.t("Неліцензійні гравці дозволені…");
        if (m.startsWith("FAIL") || m.startsWith("RETRY")) return I18n.t("Автоматично не вийшло (") + m + ")." + manual;
        if (m.startsWith("LOADED")) return I18n.t("Агент підключено. Чекаю, поки ти зайдеш у світ «") + hostWorld + "»…";
        if (hostManualJoin) return I18n.t("У цій версії гра не вміє заходити у світ сама: відкрий світ «") + hostWorld
                + I18n.t("» вручну (Одиночна гра) — він відкриється для друзів, щойно ти зайдеш.");
        return I18n.t("Чекаю, поки ти зайдеш у світ «") + hostWorld + "»…";
    }

    static void fillCombo(Combo<String> cb, List<String> items) {
        if (cb == null) return;
        List<String> cur = new ArrayList<>();
        for (int i = 0; i < cb.getItemCount(); i++) cur.add(cb.getItemAt(i));
        if (cur.equals(items)) return;
        Object sel = cb.getSelectedItem();
        cb.removeAllItems();
        for (String x : items) cb.addItem(x);
        if (sel != null && items.contains(String.valueOf(sel))) cb.setSelectedItem(sel);
    }

    void saveBans() {
        LanProxy l = lan;
        if (l != null) { cfg.set("hostBans", String.join(",", l.banEntries())); cfg.save(); }
    }

    void refreshHostUi() {
        LanProxy l = lan;
        if (l != null) { fillCombo(hostPlayers, l.playerNames()); fillCombo(hostBanned, l.bans()); }
        if (inviteOut != null) inviteOut.setText(inviteCode == null ? "" : inviteCode);
        if (inviteNoteL != null) inviteNoteL.setText(wrapHtml(inviteNote));
        if (hostWorldL != null && l != null) {
            new Thread(() -> {
                boolean a = l.alive();
                int n = l.players();
                SwingUtilities.invokeLater(() -> {
                    if (hostWorldL != null) hostWorldL.setText(a ? I18n.t("Світ відкрито · гравців зараз: ") + n
                            : hostWorld != null ? agentText()
                            : I18n.t("Чекаю, поки ти відкриєш світ для мережі…"));
                });
            }, "host-state").start();
        }
    }

    /** Створює сесію: випадковий порт, UPnP, код запрошення. Код чинний, доки сесія активна. */
    void hostStart() {
        if (lan != null) return;
        Accounts.Acc acc = accounts.current();
        LanProxy l = new LanProxy(acc == null ? "" : acc.name());
        l.setBans(Arrays.asList(cfg.get("hostBans", "").split(",")));
        int port = 0;
        Random rnd = new Random();
        for (int i = 0; i < 60 && port == 0; i++) {
            boolean reuse = i == 0 && cfg.getb("noUpnp", false) && cfg.geti("hostPort", 0) > 0;
            int p = reuse ? cfg.geti("hostPort", 0) : 30000 + rnd.nextInt(30000);
            try { l.start(p); port = p; } catch (IOException ignored) { }
        }
        if (port == 0) { msg(I18n.t("Не вдалося відкрити порт для сесії.")); return; }
        final int fport = port;
        lan = l;
        inviteCode = null;
        inviteNote = " ";
        hostAlive = false;
        if (lanTimer != null) lanTimer.stop();
        lanTimer = new javax.swing.Timer(3000, e -> {
            LanProxy cur = lan;
            if (cur == null) return;
            new Thread(() -> {
                boolean al = cur.alive();
                if (al != hostAlive) {
                    hostAlive = al;
                    SwingUtilities.invokeLater(this::refreshFriendsCard);
                }
            }, "lan-alive").start();
        });
        lanTimer.start();
        refreshFriendsCard();
        new Thread(() -> {
            Upnp.Result r = Upnp.open(fport);
            SwingUtilities.invokeLater(() -> {
                if (lan != l) { Upnp.close(fport); return; }
                String code = r.ip == null ? null : Invite.encode(r.ip, fport);
                if (code == null) {
                    hostStop();
                    msg(I18n.t(r.note));
                    return;
                }
                inviteCode = code;
                inviteNote = r.note;
                cfg.set("noUpnp", String.valueOf(!r.mapped));
                if (!r.mapped) cfg.set("hostPort", String.valueOf(fport));
                cfg.save();
                copyText(code);
                log(I18n.t("Код сесії: ") + code + (r.note.trim().isEmpty() ? "" : "  ·  " + r.note));
                refreshFriendsCard();
                refreshHostUi();
            });
        }, "host-open").start();
    }

    void hostStop() {
        LanProxy l = lan;
        lan = null;
        if (l != null) {
            l.stop();
            try { Upnp.close(l.port()); } catch (Throwable ignored) { }
        }
        inviteCode = null;
        inviteNote = " ";
        hostWorld = null;
        hostLanPort = 0;
        hostAgentMsg = null;
        hostManualJoin = false;
        hostAlive = false;
        if (lanTimer != null) { lanTimer.stop(); lanTimer = null; }
        try { Files.deleteIfExists(flagPath()); } catch (IOException ignored) { }
        SwingUtilities.invokeLater(this::refreshFriendsCard);
    }

    Path dirOf(VItem it) {
        return it.profile() != null ? instDir(it.profile()) : Path.of(cfg.get("gameDir", root.toString()));
    }

    void openHost() {
        final boolean active = lan != null;
        final Btn[] okRef = new Btn[1];
        final Runnable[] gateRef = {() -> { }};
        javax.swing.Timer tm = null;
        Combo<VItem> vc = new Combo<>();
        Combo<String> wc = new Combo<>();
        Check offC = new Check(I18n.t("Дозволяти вхід з неліцензійних акаунтів"), cfg.getb("allowOffline", false));
        offC.setFont(Theme.font(Font.PLAIN, 13));
        JPanel pn = new JPanel();
        pn.setOpaque(false);
        pn.setLayout(new BoxLayout(pn, BoxLayout.Y_AXIS));
        if (!active) {
            VItem cur = versions.getSelectedItem() instanceof VItem ? (VItem) versions.getSelectedItem() : null;
            for (int i = 0; i < versions.getItemCount(); i++) {
                VItem it = versions.getItemAt(i);
                if (it.installed()) vc.addItem(it);
            }
            if (cur != null) vc.setSelectedItem(cur);
            Runnable reload = () -> {
                wc.removeAllItems();
                Object o = vc.getSelectedItem();
                if (o instanceof VItem) for (String w : worldsOf(dirOf((VItem) o))) wc.addItem(w);
                gateRef[0].run();
            };
            vc.addActionListener(e -> reload.run());
            wc.addActionListener(e -> gateRef[0].run());
            for (JComponent c : new JComponent[]{vc, wc}) {
                c.setPreferredSize(new Dimension(200, 38));
                c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            }
            wc.placeholder = I18n.t("У цій збірці ще немає світів");
            for (JComponent c : new JComponent[]{
                    label(I18n.t("Версія або збірка"), 12, true, Theme.MUTED), vc,
                    label(" ", 6, false, Theme.MUTED),
                    label(I18n.t("Світ, який відкриється для друзів"), 12, true, Theme.MUTED), wc,
                    label(" ", 6, false, Theme.MUTED), offC}) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                pn.add(c);
            }
            gateRef[0] = () -> { if (okRef[0] != null) okRef[0].setEnabled(vc.getSelectedItem() != null && wc.getSelectedItem() != null); };
            reload.run();
        } else {
            inviteOut = new Field(inviteCode == null ? "" : inviteCode);
            inviteOut.setEditable(false);
            inviteOut.setHint(I18n.t("Створюю код…"));
            inviteOut.setPreferredSize(new Dimension(200, 38));
            inviteOut.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            Btn copy = tiny(new Btn(I18n.t("Копіювати"), Btn.Kind.SECONDARY));
            copy.addActionListener(e -> { String t = inviteOut.getText(); if (!t.isEmpty()) copyText(t); });
            JPanel codeRow = new JPanel(new BorderLayout(8, 0));
            codeRow.setOpaque(false);
            codeRow.add(inviteOut, BorderLayout.CENTER);
            codeRow.add(copy, BorderLayout.EAST);
            hostWorldL = label(" ", 12, false, Theme.MUTED);
            hostWorldL.setPreferredSize(new Dimension(Theme.px(400), Theme.px(40)));
            hostWorldL.setVerticalAlignment(SwingConstants.TOP);
            hostPlayers = new Combo<>();
            hostPlayers.placeholder = I18n.t("Нікого немає");
            hostBanned = new Combo<>();
            hostBanned.placeholder = I18n.t("Список порожній");
            for (JComponent c : new JComponent[]{hostPlayers, hostBanned}) {
                c.setPreferredSize(new Dimension(200, 38));
                c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            }
            Btn kickB = tiny(new Btn(I18n.t("Від'єднати"), Btn.Kind.SECONDARY));
            Btn banB = tiny(new Btn(I18n.t("Забанити"), Btn.Kind.SECONDARY));
            Btn unbanB = tiny(new Btn(I18n.t("Розбанити"), Btn.Kind.SECONDARY));
            kickB.addActionListener(e -> {
                Object n = hostPlayers.getSelectedItem();
                if (lan != null && n != null) { lan.kick(String.valueOf(n)); refreshHostUi(); }
            });
            banB.addActionListener(e -> {
                Object n = hostPlayers.getSelectedItem();
                if (lan != null && n != null) { lan.ban(String.valueOf(n)); saveBans(); refreshHostUi(); }
            });
            unbanB.addActionListener(e -> {
                Object n = hostBanned.getSelectedItem();
                if (lan != null && n != null) { lan.unban(String.valueOf(n)); saveBans(); refreshHostUi(); }
            });
            JPanel pBtns = new JPanel(new GridLayout(1, 2, 6, 0));
            pBtns.setOpaque(false);
            pBtns.add(kickB);
            pBtns.add(banB);
            JPanel pRow = new JPanel(new BorderLayout(8, 0));
            pRow.setOpaque(false);
            pRow.add(hostPlayers, BorderLayout.CENTER);
            pRow.add(pBtns, BorderLayout.EAST);
            JPanel bRow = new JPanel(new BorderLayout(8, 0));
            bRow.setOpaque(false);
            bRow.add(hostBanned, BorderLayout.CENTER);
            bRow.add(unbanB, BorderLayout.EAST);
            for (JComponent c : new JComponent[]{
                    label(I18n.t("Код сесії — скинь його друзям:"), 12, true, Theme.MUTED), codeRow, hostWorldL,
                    label(I18n.t("Гравці в сесії"), 12, true, Theme.MUTED), pRow,
                    label(I18n.t("Забанені"), 12, true, Theme.MUTED), bRow}) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                pn.add(c);
                pn.add(Box.createVerticalStrut(5));
            }
            refreshHostUi();
            tm = new javax.swing.Timer(2000, e -> refreshHostUi());
            tm.start();
        }
        pn.setPreferredSize(new Dimension(Theme.px(440), pn.getPreferredSize().height));
        boolean ok = Dlg.show(this, I18n.t("Створити сесію"), pn, active ? I18n.t("Вимкнути сесію") : I18n.t("Створити"),
                I18n.t("Закрити"), null, b -> { okRef[0] = b; gateRef[0].run(); });
        if (tm != null) tm.stop();
        inviteOut = null;
        inviteNoteL = null;
        hostWorldL = null;
        hostPlayers = null;
        hostBanned = null;
        if (!ok) return;
        if (active) { hostStop(); status.setText(I18n.t("Сесію вимкнено")); return; }
        cfg.set("allowOffline", String.valueOf(offC.isSelected()));
        cfg.save();
        Object so = vc.getSelectedItem();
        Object w = wc.getSelectedItem();
        if (!(so instanceof VItem) || w == null) return;
        if (!running.isEmpty()) {
            msg(I18n.t("Спочатку закрий запущену гру: світ відкриється для друзів сам, якщо запустити її через «Створити»."));
            return;
        }
        hostStart();
        if (lan == null) return;
        hostWorld = String.valueOf(w);
        hostLanPort = freePort(25565);
        lan.setTarget(hostLanPort);
        fillVersions(((VItem) so).key());
        play();
    }

    void openJoin() {
        if (joinCode != null) {
            JPanel pn = new JPanel();
            pn.setOpaque(false);
            pn.setLayout(new BoxLayout(pn, BoxLayout.Y_AXIS));
            JLabel a = label(I18n.t("Ти приєднаний до сесії ") + joinCode, 13, true, Theme.TEXT);
            a.setAlignmentX(Component.LEFT_ALIGNMENT);
            pn.add(a);
            pn.setPreferredSize(new Dimension(Theme.px(400), pn.getPreferredSize().height + 6));
            if (Dlg.show(this, I18n.t("Приєднатися"), pn, I18n.t("Від'єднатися"), I18n.t("Закрити"))) joinDisconnect();
            return;
        }
        Field codeIn = new Field("");
        codeIn.setHint(I18n.t("Код: PAX-XXXX-XXXX-XXXX"));
        codeIn.setPreferredSize(new Dimension(Theme.px(400), 38));
        final Btn[] okRef = new Btn[1];
        Runnable gate = () -> { if (okRef[0] != null) okRef[0].setEnabled(Invite.decode(codeIn.getText()) != null); };
        codeIn.getDocument().addDocumentListener(simpleDoc(gate));
        boolean ok = Dlg.show(this, I18n.t("Приєднатися"), codeIn, I18n.t("Приєднатися"), I18n.t("Скасувати"), null,
                b -> { okRef[0] = b; gate.run(); });
        if (ok) joinWithCode(codeIn.getText());
    }

    void joinWithCode(String code) {
        String[] t = Invite.decode(code);
        if (t == null) { msg(I18n.t("Невірний код запрошення.")); return; }
        if (joinCode != null) { msg(I18n.t("Ти вже приєднаний до сесії.")); return; }
        String ip = t[0];
        int port = Integer.parseInt(t[1]);
        String norm = Invite.encode(ip, port);
        status.setText(I18n.t("Перевіряю сесію…"));
        new Thread(() -> {
            // той самий комп'ютер (інший екземпляр лаунчера): ходимо через 127.0.0.1, без петлі через роутер
            String me = Upnp.publicIp();
            final String tgt = me != null && me.equals(ip) ? "127.0.0.1" : ip;
            int st = LanProxy.probe(tgt, port);
            SwingUtilities.invokeLater(() -> {
                status.setText(" ");
                if (st == 2) { msg(I18n.t("Сесія неактивна: хост її вимкнув, або код застарів.")); return; }
                if (st == 1) { msg(I18n.t("Хост ще не відкрив світ для мережі. Спробуй трохи пізніше.")); return; }
                // запис «Гра з друзями» додаємо в список серверів усіх збірок, щоб він був у будь-якій, з якої запустиш гру
                String logo = LanProxy.logoBase64();
                for (Path d : allGameDirs()) {
                    try { Nbt.addServer(d.resolve("servers.dat"), "Гра з друзями", tgt + ":" + port, logo); }
                    catch (Exception ex) { log("servers.dat: " + ex.getMessage()); }
                }
                joinTarget = tgt + ":" + port;
                pendingJoin = norm;
                play();
            });
        }, "join-probe").start();
    }

    void startJoinMonitor(String code, String target) {
        String[] hp = target.split(":");
        String ip = hp[0];
        int port = Integer.parseInt(hp[1]);
        Thread t = new Thread(() -> {
            int fails = 0;
            while (code.equals(joinCode)) {
                try { Thread.sleep(6000); } catch (InterruptedException e) { return; }
                if (!code.equals(joinCode)) return;
                fails = LanProxy.probe(ip, port) != 0 ? fails + 1 : 0;
                if (fails >= 2) {
                    joinCode = null;
                    SwingUtilities.invokeLater(() -> {
                        refreshFriendsCard();
                        status.setText(I18n.t("Хост вийшов зі світу або вимкнув сесію — тебе від'єднано."));
                    });
                    return;
                }
            }
        }, "join-monitor");
        t.setDaemon(true);
        t.start();
    }

    /** Від'єднатися: закриває гру, запущену для цієї сесії. */
    void joinDisconnect() {
        Process p = joinProc;
        joinCode = null;
        refreshFriendsCard();
        if (p != null && p.isAlive()) p.destroy();
        status.setText(I18n.t("Від'єднано від сесії."));
    }

    static javax.swing.event.DocumentListener simpleDoc(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        };
    }

    /** Помітний блок на головній: тут можна створити свою збірку й відкрити бібліотеку збірок. */
    JPanel libraryBanner() {
        Card c = new Card(new BorderLayout(16, 0), 16);
        JPanel txt = new JPanel();
        txt.setOpaque(false);
        txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
        JLabel a = label(I18n.t("Твої збірки"), 17, true, Theme.TEXT);
        JLabel b = label(I18n.t("Окрема копія гри зі своїми модами, світами й налаштуваннями."), 13, false, Theme.MUTED);
        a.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        txt.add(a);
        txt.add(Box.createVerticalStrut(4));
        txt.add(b);
        c.add(txt, BorderLayout.CENTER);
        Btn lib = small(new Btn(I18n.t("Бібліотека"), Btn.Kind.PRIMARY));
        lib.setPreferredSize(new Dimension(170, 42));
        lib.addActionListener(e -> showPage("library"));
        JPanel bt = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bt.setOpaque(false);
        bt.add(lib);
        c.add(bt, BorderLayout.EAST);
        return c;
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
            if (I18n.LANGS[i][0].equals(pendLang != null ? pendLang : I18n.code())) sel = i;
        }
        cb.setSelectedIndex(sel);
        cb.setPreferredSize(new Dimension(220, 40));
        cb.addActionListener(e -> {
            int i = cb.getSelectedIndex();
            if (i >= 0) pendLang = I18n.norm(I18n.LANGS[i][0]);
        });
        langP.add(cb);
        langP.revalidate();
        langP.repaint();
    }

    void switchLanguage(String code) {
        try { saveSettings(); } catch (Throwable ignored) { }
        cfg.set("lang", code);
        cfg.save();
        final boolean wasMax = maxed;
        final Rectangle nb = wasMax && restoreBounds != null ? new Rectangle(restoreBounds) : getBounds();
        final String page = curPage;
        final List<LogEnt> logKeep = new ArrayList<>(logAll);
        I18n.init(code);
        Theme.setScale(1.0);
        LauncherWindow nw = new LauncherWindow();
        nw.setBounds(nb);
        nw.loadLog(logKeep);
        nw.showPage(page);
        nw.setVisible(true);
        if (wasMax) SwingUtilities.invokeLater(nw::toggleMaxPlain);
        rpc.shutdown();
        dispose();
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
        dk.addActionListener(e -> { if (Theme.light) previewTheme(false, Theme.accent); });
        lt.addActionListener(e -> { if (!Theme.light) previewTheme(true, Theme.accent); });
        themeModeP.add(dk);
        themeModeP.add(lt);
        for (int rgb : PRESETS) {
            Color c = new Color(rgb);
            themeColorP.add(new Swatch(c, () -> previewTheme(Theme.light, c)));
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

    void previewTheme(boolean light, Color accent) {
        themeDirty = true;
        changeTheme(light, accent, false);
    }

    void revertTheme() {
        themeDirty = false;
        changeTheme("light".equals(cfg.get("theme", "dark")), Theme.parseHex(cfg.get("accent", ""), Theme.DEFAULT_ACCENT), false);
    }

    void changeTheme(boolean light, Color accent) { changeTheme(light, accent, true); }

    void changeTheme(boolean light, Color accent, boolean persist) {
        Color[] old = Theme.palette();
        Theme.apply(light, accent);
        Theme.applyUI();
        Map<Integer, Color> m = Theme.remap(old);
        setBackground(Theme.BG2);
        Theme.recolor(getContentPane(), m);
        if (persist) {
            cfg.set("theme", light ? "light" : "dark");
            cfg.set("accent", String.format("%06X", accent.getRGB() & 0xFFFFFF));
            cfg.save();
        }
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
        if (Dlg.show(this, I18n.t("Колір теми"), box, I18n.t("Застосувати"), I18n.t("Скасувати"))) previewTheme(Theme.light, pick.get());
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
        comp.setPreferredSize(new Dimension(10, comp.getPreferredSize().height));
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
        javaBrowse.addActionListener(e -> openFolder(javaRoot()));
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
            else if (o instanceof JavaFinder.Inst && ((JavaFinder.Inst) o).major() > 0 && "".equals(((JavaFinder.Inst) o).path())) {
                final int jn = ((JavaFinder.Inst) o).major();
                javaSync = true;
                syncJavaComboNow();
                javaSync = false;
                if (busy || mBusy || inGame) { msg(I18n.t("Зачекай, поки завершиться завантаження чи гра.")); return; }
                fetchJava(jn, () -> new Thread(() -> {
                    List<JavaFinder.Inst> found = JavaFinder.scan();
                    Path mj = engine.managedJava(jn);
                    SwingUtilities.invokeLater(() -> { fillJava(found); if (mj != null) javaF.setText(mj.toString()); });
                }, "java-scan").start());
            }
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
        resC.addItem(I18n.t("Стандартна (авто)"));
        for (int[] pr : RES_PRESETS) resC.addItem(pr[0] + "×" + pr[1]);
        resC.addItem(I18n.t("Власна"));
        resC.setPreferredSize(new Dimension(190, 40));
        resC.addActionListener(e -> {
            if (resSync) return;
            int ri = resC.getSelectedIndex();
            resSync = true;
            if (ri == 0) { wF.setText(""); hF.setText(""); }
            else if (ri >= 1 && ri <= RES_PRESETS.length) {
                wF.setText(String.valueOf(RES_PRESETS[ri - 1][0]));
                hF.setText(String.valueOf(RES_PRESETS[ri - 1][1]));
            }
            resSync = false;
            updateResHint();
        });
        JPanel resRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        resRow.setOpaque(false);
        resRow.add(resC);
        resRow.add(wF);
        resRow.add(label("×", 16, true, Theme.MUTED));
        resRow.add(hF);
        JPanel res = new JPanel(new BorderLayout(0, 6));
        res.setOpaque(false);
        res.add(resRow, BorderLayout.NORTH);
        res.add(resHint, BorderLayout.SOUTH);
        row(c, 3, I18n.t("Роздільна здатність вікна"), res, null);

        Btn dirBrowse = new Btn(I18n.t("Огляд"), Btn.Kind.SECONDARY);
        dirBrowse.addActionListener(e -> {
            Path d = root;
            try { String t = dirF.getText().trim(); if (!t.isEmpty()) d = Path.of(t); Files.createDirectories(d); } catch (Exception ignored) { }
            openFolder(d);
        });
        row(c, 4, I18n.t("Ігрова папка (saves, mods)"), dirF, dirBrowse);

        JPanel hideP = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        hideP.setOpaque(false);
        hideP.add(hideC);
        discC.setBorder(new EmptyBorder(0, 24, 0, 0));
        hideP.add(discC);
        row(c, 8, I18n.t("Поведінка"), hideP, null);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        btns.setOpaque(false);
        Btn save = small(new Btn(I18n.t("Зберегти"), Btn.Kind.PRIMARY));
        JLabel saveNote = label(" ", 13, false, Theme.MUTED);
        javax.swing.Timer noteT = new javax.swing.Timer(3000, e -> saveNote.setText(" "));
        noteT.setRepeats(false);
        save.addActionListener(e -> {
            saveSettings();
            updateResHint();
            themeDirty = false;
            cfg.set("theme", Theme.light ? "light" : "dark");
            cfg.set("accent", String.format("%06X", Theme.accent.getRGB() & 0xFFFFFF));
            cfg.save();
            if (pendLang != null && !pendLang.equals(I18n.code())) {
                if (busy || mBusy || inGame) {
                    msg(I18n.t("Зачекай, поки завершиться завантаження чи гра, — тоді можна змінити мову."));
                } else {
                    String nl = pendLang;
                    pendLang = null;
                    SwingUtilities.invokeLater(() -> switchLanguage(nl));
                    return;
                }
            }
            String j = javaPath();
            boolean bad = !j.isEmpty() && (j.contains("/") || j.contains("\\")) && !Files.isRegularFile(Path.of(j));
            saveNote.setText(bad ? I18n.t("Збережено, але файл Java не знайдено: ") + j : I18n.t("Налаштування збережено"));
            noteT.restart();
        });
        btns.add(save);
        btns.add(saveNote);
        buildThemeControls();
        row(c, 9, I18n.t("Тема інтерфейсу"), themeModeP, null);
        row(c, 10, I18n.t("Колір теми"), themeColorP, null);
        buildLangControls();
        row(c, 11, I18n.t("Мова"), langP, null);
        row(c, 12, "", btns, null);
        wireResSync();

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
        clear.addActionListener(e -> clearConsole());
        head.add(clear, BorderLayout.EAST);

        JPanel filt = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        filt.setOpaque(false);
        for (Lvl l : Lvl.values()) {
            Check ch = new Check(lvlName(l) + " 0", l != Lvl.DEBUG);
            ch.setFont(Theme.font(Font.PLAIN, 13));
            ch.setBorder(new EmptyBorder(0, 0, 0, 18));
            ch.setForeground(l == Lvl.INFO ? Theme.TEXT : lvlColor(l));
            ch.addActionListener(e -> rebuildConsole());
            lvlChecks.put(l, ch);
            filt.add(ch);
        }
        conQ.setHint(I18n.t("Пошук у журналі…"));
        conQ.setPreferredSize(new Dimension(260, 36));
        conQ.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void ch() { rebuildConsole(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { ch(); }
        });
        JPanel bar2 = new JPanel(new BorderLayout(12, 0));
        bar2.setOpaque(false);
        bar2.add(filt, BorderLayout.CENTER);
        bar2.add(conQ, BorderLayout.EAST);
        JPanel north = new JPanel(new BorderLayout(0, 10));
        north.setOpaque(false);
        north.add(head, BorderLayout.NORTH);
        north.add(bar2, BorderLayout.CENTER);
        c.add(north, BorderLayout.NORTH);

        Color dark = Theme.INPUT;
        console.setEditable(false);
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

    Path javaRoot() {
        try {
            String p = javaPath();
            if (!p.isEmpty()) {
                Path exe = Path.of(p);
                Path dir = exe.getParent();
                if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("bin") && dir.getParent() != null) dir = dir.getParent();
                if (dir != null && Files.isDirectory(dir)) return dir;
            }
        } catch (Exception ignored) { }
        Path rt = root.resolve("runtime");
        return Files.isDirectory(rt) ? rt : root;
    }

    void fillJava(List<JavaFinder.Inst> found) {
        javaSync = true;
        javaC.removeAllItems();
        javaC.addItem(javaAuto);
        for (JavaFinder.Inst i : found) javaC.addItem(i);
        for (int jn : new int[]{8, 17, 21, 25}) {
            boolean have = false;
            for (JavaFinder.Inst i : found) if (i.major() == jn) { have = true; break; }
            if (!have) javaC.addItem(new JavaFinder.Inst(I18n.t("Завантажити Java") + " " + jn, "", jn));
        }
        javaC.addItem(javaCustom);
        javaSync = false;
        syncJavaCombo();
    }

    void syncJavaCombo() {
        if (javaSync) return;
        syncJavaComboNow();
    }

    void syncJavaComboNow() {
        boolean was = javaSync;
        javaSync = true;
        try { syncJavaComboBody(); } finally { javaSync = was; }
    }

    void syncJavaComboBody() {
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
        reloadSettingsForm();
        cRel.setSelected(cfg.getb("rel", true));
        cSnap.setSelected(cfg.getb("snap", false));
        cOld.setSelected(cfg.getb("old", false));
    }

    void reloadSettingsForm() {
        String did = cfg.get("discord", "").trim();
        discF.setText(did.isEmpty() ? Theme.DISCORD_ID : did);
        discC.setSelected(cfg.getb("discordOn", false));
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
        syncResCombo();
        updateResHint();
        dirF.setText(cfg.get("gameDir", root.toString()));
        hideC.setSelected(cfg.getb("hide", true));
    }

    void wireResSync() {
        javax.swing.event.DocumentListener rl = new javax.swing.event.DocumentListener() {
            void upd() { syncResCombo(); updateResHint(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
        };
        wF.getDocument().addDocumentListener(rl);
        hF.getDocument().addDocumentListener(rl);
    }

    int[] resolution() {
        String ws = wF.getText().trim(), hs = hF.getText().trim();
        if (!ws.matches("\\d{3,5}") || !hs.matches("\\d{3,5}")) return null;
        int w = Integer.parseInt(ws), h = Integer.parseInt(hs);
        return w >= 320 && h >= 240 && w <= 16384 && h <= 16384 ? new int[]{w, h} : null;
    }

    void syncResCombo() {
        if (resSync) return;
        String ws = wF.getText().trim(), hs = hF.getText().trim();
        int idx = resC.getItemCount() - 1;
        if (ws.isEmpty() && hs.isEmpty()) idx = 0;
        else for (int i = 0; i < RES_PRESETS.length; i++)
            if (ws.equals(String.valueOf(RES_PRESETS[i][0])) && hs.equals(String.valueOf(RES_PRESETS[i][1]))) { idx = i + 1; break; }
        resSync = true;
        resC.setSelectedIndex(idx);
        resSync = false;
    }

    void updateResHint() {
        if (wF.getText().trim().isEmpty() && hF.getText().trim().isEmpty()) {
            resHint.setText(I18n.t("Гра відкриється зі стандартним розміром вікна"));
        } else if (resolution() != null) {
            resHint.setText(I18n.t("Застосується після натискання «Зберегти» при наступному запуску гри"));
        } else {
            resHint.setText(I18n.t("Вкажи ширину й висоту числами (від 320×240), інакше буде стандартний розмір"));
        }
    }

    void saveVersion() {
        cfg.set("rel", String.valueOf(cRel.isSelected()));
        cfg.set("snap", String.valueOf(cSnap.isSelected()));
        cfg.set("old", String.valueOf(cOld.isSelected()));
        Object sel = versions.getSelectedItem();
        if (sel instanceof VItem) cfg.set("version", ((VItem) sel).key());
        cfg.save();
    }

    void saveSettings() {
        String discId = discF.getText().trim().isEmpty() ? Theme.DISCORD_ID : discF.getText().trim();
        cfg.set("discord", discId);
        cfg.set("discordOn", String.valueOf(discC.isSelected()));
        cfg.set("java", javaPath());
        cfg.set("jvm", jvmF.getText().trim());
        cfg.set("ram", String.valueOf(ram.getValue() * 512));
        int[] rs = resolution();
        cfg.set("w", rs != null ? String.valueOf(rs[0]) : "0");
        cfg.set("h", rs != null ? String.valueOf(rs[1]) : "0");
        cfg.set("gameDir", dirF.getText().trim().isEmpty() ? root.toString() : dirF.getText().trim());
        cfg.set("hide", String.valueOf(hideC.isSelected()));
        cfg.set("rel", String.valueOf(cRel.isSelected()));
        cfg.set("snap", String.valueOf(cSnap.isSelected()));
        cfg.set("old", String.valueOf(cOld.isSelected()));
        Object sel = versions.getSelectedItem();
        if (sel instanceof VItem) cfg.set("version", ((VItem) sel).key());
        cfg.save();
        rpc.configure(discC.isSelected() ? discId : "");
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
        loadersBtn.setEnabled(!b);
        lInstall.setEnabled(!b && lVer.getItemCount() > 0);
        refreshPlayText();
    }

    final AtomicInteger games = new AtomicInteger();

    void refreshPlayText() {
        Accounts.Acc cur = accounts == null ? null : accounts.current();
        boolean curRunning = cur != null && running.contains(accKey(cur));
        playBtn.setEnabled(!busy && !curRunning);
        playBtn.setText(busy ? I18n.t("Зачекай…") : curRunning ? I18n.t("Гра запущена") : I18n.t("Грати"));
    }

    /** Вміст діалогу «Додати версію» (збирається один раз; сторінки більше немає). */
    JPanel loadersPage() {
        final String[] LD = {"Vanilla", "Fabric", "Quilt", "Forge", "NeoForge"};
        for (String nm : LD) lType.addItem(nm);
        lType.setSelectedItem("Fabric");
        for (JComponent cb : new JComponent[]{lMc, lVer}) {
            cb.setPreferredSize(new Dimension(340, 42));
            cb.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        }

        // той самий вигляд, що й у створенні збірки: плитки завантажувачів + списки версій
        JPanel tileRow = new JPanel(new GridLayout(1, LD.length, 8, 0));
        tileRow.setOpaque(false);
        for (String nm : LD) {
            LoaderTile lt = new LoaderTile(nm, () -> {
                lType.setSelectedItem(nm);
                for (LoaderTile x : tileRowTiles(tileRow)) x.setSel(x.name.equals(nm));
            });
            lt.setSel(nm.equals("Fabric"));
            tileRow.add(lt);
        }

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{
                label(I18n.t("Завантажувач"), 13, true, Theme.MUTED), tileRow,
                label(" ", 6, false, Theme.MUTED),
                label(I18n.t("Версія Minecraft"), 13, true, Theme.MUTED), lMc, lSnaps,
                label(" ", 6, false, Theme.MUTED),
                lvLbl, lVer,
                label(" ", 6, false, Theme.MUTED), lStatus}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            col.add(c);
        }
        col.setPreferredSize(new Dimension(Theme.px(500), col.getPreferredSize().height));

        lMc.addActionListener(e -> { if (!lFilling) loadLoaderVersions(); });
        lType.addActionListener(e -> { if (!lFilling) loadLoaderVersions(); });
        lSnaps.addActionListener(e -> fillLoaderMc());
        // кнопка «Встановити» в діалозі повторює стан lInstall (активна, коли список версій готовий)
        lInstall.addPropertyChangeListener("enabled", e -> { if (lOk != null) lOk.setEnabled(lInstall.isEnabled()); });
        lInstall.setEnabled(false);
        return col;
    }

    void fillLoaderMc() {
        Object want = lMc.getSelectedItem();
        lFilling = true;
        lMc.removeAllItems();
        for (Engine.VInfo vi : engine.manifest) if (vi.type().equals("release") || lSnaps.isSelected()) lMc.addItem(vi.id());
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
        if (busy) { msg(I18n.t("Зачекай, поки завершиться завантаження.")); return; }
        lStatus.setText(" ");
        boolean ok = Dlg.show(this, I18n.t("Додати версію"), loadersBox, I18n.t("Встановити"), I18n.t("Скасувати"), null,
                b -> { lOk = b; b.setEnabled(lInstall.isEnabled()); });
        lOk = null;
        lSeq.incrementAndGet();
        if (ok) installLoader();
    }

    void loadLoaderVersions() {
        Object mo = lMc.getSelectedItem(), to = lType.getSelectedItem();
        int my = lSeq.incrementAndGet();
        lVer.removeAllItems();
        lInstall.setEnabled(false);
        if (mo == null || to == null) return;
        String mc = (String) mo, type = (String) to;
        boolean van = type.equals("Vanilla");
        lVer.setEnabled(!van);
        lvLbl.setText(van ? I18n.t("Версія завантажувача — не потрібна") : I18n.t("Версія завантажувача"));
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
                        : ok ? I18n.t("Готово до встановлення") : type + I18n.t(" не підтримує Minecraft ") + mc);
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
        bar.set(0.05);
        engine.stop = false;
        cancelUi(true, cancelBtn, pauseBtn);
        Engine.Progress pg = (t, f) -> SwingUtilities.invokeLater(() -> { status.setText(t); bar.set(f); });
        new Thread(() -> {
            try {
                String id = installLoaderEnsured(type, mc, ver, java, pg);
                log(I18n.t("Встановлено: ") + id);
                try {
                    pg.update(I18n.t("Завантаження файлів гри для ") + id + "…", 0);
                    engine.build(engine.loadVersion(id), cfg, Accounts.offline("Player"), pg, root);
                } catch (Engine.Cancelled cx) { throw cx;
                } catch (Exception pre) { log(I18n.t("Попереднє завантаження файлів не вдалося (буде при запуску): ") + pre.getMessage()); }
                unhideVersion(id);
                SwingUtilities.invokeLater(() -> {
                    fillVersions(id);
                    status.setText(I18n.t("Готово до запуску"));
                    bar.set(0);
                });
            } catch (Engine.Cancelled cx) {
                log(I18n.t("Встановлення скасовано"));
                SwingUtilities.invokeLater(() -> { status.setText(I18n.t("Завантаження скасовано")); bar.set(0); });
            } catch (Exception ex) {
                log(I18n.t("Помилка встановлення: ") + ex);
                SwingUtilities.invokeLater(() -> {
                    status.setText(I18n.t("Помилка встановлення"));
                    bar.set(0);
                    msg(I18n.t("Не вдалося встановити ") + type + ":\n" + ex.getMessage());
                });
            } finally {
                SwingUtilities.invokeLater(() -> { setBusy(false); cancelUi(false, cancelBtn, pauseBtn); });
            }
        }, "loader-install").start();
    }

    Path instDir(String name) { return root.resolve("instances").resolve(name); }

    /** Папки гри всіх збірок (і типова), які вже існують. */
    List<Path> allGameDirs() {
        List<Path> out = new ArrayList<>();
        Path def = Path.of(cfg.get("gameDir", root.toString()));
        if (Files.isDirectory(def)) out.add(def);
        try {
            for (Prof pf : loadProfiles()) {
                Path d = instDir(pf.name());
                if (Files.isDirectory(d) && !out.contains(d)) out.add(d);
            }
        } catch (Exception ignored) { }
        return out;
    }

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
            if (url == null || !url.startsWith("https://")) return;
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) { msg(I18n.t("Відкрий вручну: ") + url); }
    }

    static Btn tiny(Btn b) {
        b.setBorder(new EmptyBorder(6, 12, 6, 12));
        b.setFont(Theme.bfont(Font.BOLD, 12));
        return b;
    }

    static Btn small(Btn b) {
        b.setBorder(new EmptyBorder(10, 16, 10, 16));
        b.setFont(Theme.bfont(Font.BOLD, 13));
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
        refreshLibrary();
    }

    void onProfileChanged() {
        Prof p = curProf();
        mSeq.incrementAndGet();
        mfSeq.incrementAndGet();
        mModel.clear();
        mLoading = false;
        mFiles.removeAllItems();
        mInstall.setEnabled(false);
        mMore.setEnabled(false);
        showDetails(null);
        if (mInstalledView && kindChosen0() && curKind().isPack()) {
            setModInfo(null);
            updateEmptyState();
            refreshPackList();
            return;
        }
        if (kindChosen() && curKind().isPack()) {
            setModInfo(null);
            iModel.clear();
            mStatus.setText(" ");
            updateEmptyState();
            if (modsInit) search(false, true);
            return;
        }
        if (p == null) {
            setModInfo(null);
            iModel.clear();
            mStatus.setText(" ");
            updateEmptyState();
            return;
        }
        updateEmptyState();
        cfg.set("modProfile", p.name());
        if (!kindChosen()) {
            setModInfo(null);
            iModel.clear();
            mStatus.setText(" ");
            return;
        }
        ModApi.Kind k = curKind();
        setModInfo(k.isMod() && p.loader().equals("Vanilla")
                ? I18n.t("Чиста збірка не підтримує моди. Створи збірку на основі Fabric / Quilt / Forge / NeoForge (набори ресурсів працюють і в чистій).")
                : k == ModApi.Kind.SHADER
                ? I18n.t("Шейдерам потрібен мод Iris (Fabric / Quilt / NeoForge) або OptiFine — встанови його в цю збірку на вкладці «Моди».")
                : null);
        refreshInstalled();
        if (modsInit && (!k.isMod() || !p.loader().equals("Vanilla"))) search(false, true);
    }

    static List<LoaderTile> tileRowTiles(JPanel row) {
        List<LoaderTile> r = new ArrayList<>();
        for (Component c : row.getComponents()) if (c instanceof LoaderTile) r.add((LoaderTile) c);
        return r;
    }

    void newProfile() {
        if (engine.manifest.isEmpty()) { msg(I18n.t("Список версій Minecraft ще завантажується — спробуй за мить.")); return; }
        Field name = new Field("");
        final String[] LD = {"Vanilla", "Fabric", "Quilt", "Forge", "NeoForge"};
        final String[] sel = {"Fabric"};
        Combo<String> mc = new Combo<>();
        Check snaps = new Check(I18n.t("Показати знімки та давні версії"), false);
        RamPick newRam = new RamPick(null);
        newRam.setValueMb(0);
        final boolean[] rebuild = {false};
        Combo<Engine.LV> lv = new Combo<>();
        JLabel hint = label(" ", 12, false, Theme.MUTED);
        for (JComponent c : new JComponent[]{name, mc, lv}) {
            c.setPreferredSize(new Dimension(200, 38));
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        }
        final JLabel lvLabel = label(I18n.t("Версія завантажувача"), 13, true, Theme.MUTED);
        final Btn[] okRef = {null};
        // «Створити» активна лише коли версія завантажувача вже підвантажилась
        Runnable gate = () -> {
            Btn b = okRef[0];
            if (b == null) return;
            boolean ok;
            if (sel[0].equals("Vanilla")) ok = mc.getSelectedItem() != null;
            else ok = lv.getSelectedItem() instanceof Engine.LV;
            b.setEnabled(ok);
        };

        AtomicInteger seq = new AtomicInteger();
        Runnable loadLv = () -> {
            Object mo = mc.getSelectedItem();
            String to = sel[0];
            int my = seq.incrementAndGet();
            lv.removeAllItems();
            gate.run();
            boolean van = to.equals("Vanilla");
            lv.setEnabled(!van);
            lvLabel.setText(van ? I18n.t("Версія завантажувача — не потрібна") : I18n.t("Версія завантажувача"));
            if (mo == null) return;
            if (van) { hint.setText(" "); return; }
            prefetchMods((String) mo, Engine.detectLoader(to));
            hint.setText(I18n.t("Завантаження списку версій ") + to + "…");
            new Thread(() -> {
                List<Engine.LV> list = null;
                String err = null;
                try { list = engine.loaderVersions(to, (String) mo); } catch (Exception ex) { err = ex.getMessage(); }
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
                            : ok ? " " : to + I18n.t(" не підтримує Minecraft ") + mo);
                    gate.run();
                });
            }, "profile-loader-versions").start();
        };
        Runnable fillMc = () -> {
            rebuild[0] = true;
            Object keep = mc.getSelectedItem();
            mc.removeAllItems();
            for (Engine.VInfo vi : engine.manifest) if (vi.type().equals("release") || snaps.isSelected()) mc.addItem(vi.id());
            if (keep != null) mc.setSelectedItem(keep);
            rebuild[0] = false;
            loadLv.run();
        };

        JPanel tileRow = new JPanel(new GridLayout(1, LD.length, 8, 0));
        tileRow.setOpaque(false);
        for (String nm : LD) {
            LoaderTile t = new LoaderTile(nm, () -> {
                sel[0] = nm;
                for (LoaderTile x : tileRowTiles(tileRow)) x.setSel(x.name.equals(nm));
                loadLv.run();
            });
            t.setSel(nm.equals(sel[0]));
            tileRow.add(t);
        }

        lv.addActionListener(e -> gate.run());
        mc.addActionListener(e -> { if (!rebuild[0]) loadLv.run(); });
        snaps.addActionListener(e -> fillMc.run());
        fillMc.run();

        // іконка збірки: превʼю + кнопка вибору поруч із назвою
        final String[] iconId = {"cube"};
        final BufferedImage[] iconImg = {null};
        final JComponent[] prevRef = {null};
        JComponent prev = new JComponent() {
            boolean hov;
            {
                setPreferredSize(new Dimension(Theme.px(92), Theme.px(92)));
                setMinimumSize(getPreferredSize());
                setMaximumSize(getPreferredSize());
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText(I18n.t("Обрати іконку"));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hov = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hov = false; repaint(); }
                    @Override public void mouseClicked(MouseEvent e) { pickNewIcon(iconId, iconImg, prevRef[0]); }
                });
            }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                int s = Math.min(getWidth(), getHeight());
                if (iconImg[0] != null) g.drawImage(BuildIcons.fit(iconImg[0], s * 2), 0, 0, s, s, null);
                else BuildIcons.paint(g, iconId[0], 0, 0, s);
                if (hov) {
                    g.setColor(new Color(0, 0, 0, 90));
                    g.fillRoundRect(0, 0, s, s, (int) (s * 0.46), (int) (s * 0.46));
                    g.setColor(Color.WHITE);
                    g.setFont(Theme.font(Font.BOLD, 11));
                    String t = I18n.t("Змінити");
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(t, (s - fm.stringWidth(t)) / 2, s / 2 + fm.getAscent() / 2 - 2);
                }
                g.dispose();
            }
        };
        prevRef[0] = prev;
        Btn pickIcon = tiny(new Btn(I18n.t("Змінити іконку"), Btn.Kind.SECONDARY));
        pickIcon.addActionListener(e -> pickNewIcon(iconId, iconImg, prev));
        JPanel nameCol = new JPanel();
        nameCol.setOpaque(false);
        nameCol.setLayout(new BoxLayout(nameCol, BoxLayout.Y_AXIS));
        JLabel nameLbl = label(I18n.t("Назва збірки"), 12, true, Theme.MUTED);
        nameLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        pickIcon.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameCol.add(nameLbl);
        nameCol.add(Box.createVerticalStrut(5));
        nameCol.add(name);
        nameCol.add(Box.createVerticalStrut(8));
        nameCol.add(pickIcon);
        nameCol.add(Box.createVerticalGlue());
        JPanel prevW = new JPanel(new BorderLayout());
        prevW.setOpaque(false);
        prevW.add(prev, BorderLayout.NORTH);
        JPanel nameRow = new JPanel(new BorderLayout(14, 0));
        nameRow.setOpaque(false);
        nameRow.add(prevW, BorderLayout.WEST);
        nameRow.add(nameCol, BorderLayout.CENTER);

        JPanel mcCol = new JPanel();
        mcCol.setOpaque(false);
        mcCol.setLayout(new BoxLayout(mcCol, BoxLayout.Y_AXIS));
        JLabel mcLbl = label(I18n.t("Версія Minecraft"), 12, true, Theme.MUTED);
        lvLabel.setFont(Theme.font(Font.BOLD, 12));
        for (JComponent c : new JComponent[]{mcLbl, mc}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            mcCol.add(c);
            if (c == mcLbl) mcCol.add(Box.createVerticalStrut(5));
        }
        JPanel lvCol = new JPanel();
        lvCol.setOpaque(false);
        lvCol.setLayout(new BoxLayout(lvCol, BoxLayout.Y_AXIS));
        lvLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        lv.setAlignmentX(Component.LEFT_ALIGNMENT);
        lvCol.add(lvLabel);
        lvCol.add(Box.createVerticalStrut(5));
        lvCol.add(lv);
        lvCol.add(Box.createVerticalGlue());
        JPanel vrow = new JPanel(new GridLayout(1, 2, 12, 0));
        vrow.setOpaque(false);
        vrow.add(mcCol);
        vrow.add(lvCol);

        JPanel pn = new JPanel();
        pn.setOpaque(false);
        pn.setLayout(new BoxLayout(pn, BoxLayout.Y_AXIS));
        JLabel ldLbl = label(I18n.t("Завантажувач"), 12, true, Theme.MUTED);
        List<JComponent> parts = new ArrayList<>(List.of(
                nameRow,
                label(" ", 6, false, Theme.MUTED),
                ldLbl, tileRow,
                label(" ", 6, false, Theme.MUTED),
                vrow,
                snaps,
                label(" ", 6, false, Theme.MUTED),
                label(I18n.t("Виділена пам'ять (ОЗП)"), 12, true, Theme.MUTED),
                newRam,
                hint));
        for (JComponent c : parts) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            pn.add(c);
        }
        pn.setPreferredSize(new Dimension(Theme.px(460), pn.getPreferredSize().height));
        if (!Dlg.show(this, I18n.t("Нова збірка"), pn, I18n.t("Створити збірку"), I18n.t("Скасувати"), null,
                b -> { okRef[0] = b; gate.run(); })) { seq.incrementAndGet(); return; }
        seq.incrementAndGet();
        final int newRamMb = newRam.valueMb();
        String n = name.getText().trim();
        if (!n.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} _.-]{0,31}")) {
            msg(I18n.t("Назва: до 32 символів — літери, цифри, пробіл, _ - ."));
            return;
        }
        if (Files.exists(instDir(n))) { msg(I18n.t("Збірка з такою назвою вже є.")); return; }
        String mcv = (String) mc.getSelectedItem(), ldType = sel[0];
        final boolean vanilla = ldType.equals("Vanilla");
        Object lo = lv.getSelectedItem();
        if (mcv == null || (!vanilla && !(lo instanceof Engine.LV))) {
            msg(I18n.t("Обери версію Minecraft, завантажувач і його версію."));
            return;
        }
        String ldVer = !vanilla ? ((Engine.LV) lo).version() : null;
        final String chosenIcon = iconImg[0] != null ? null : iconId[0];
        final BufferedImage chosenImg = iconImg[0];
        String java = javaPath();
        final Set<String> before = new HashSet<>(engine.installedIds());
        setBusy(true);
        mBar.set(0.05);
        engine.stop = false;
        cancelUi(true, pCancel, pPause);
        Engine.Progress pg = (tx, fr) -> SwingUtilities.invokeLater(() -> { mStatus.setText(tx); mBar.set(fr); });
        if (!vanilla) prefetchMods(mcv, Engine.detectLoader(ldType));
        new Thread(() -> {
            try {
                String vid;
                if (vanilla) vid = mcv;
                else {
                    vid = installLoaderEnsured(ldType, mcv, ldVer, java, pg);
                    log(I18n.t("Встановлено: ") + vid);
                }
                Map<String, Object> v = engine.loadVersion(vid);
                String jar = Json.string(v.get("_jar"));
                String mcId = jar != null ? jar : vid;
                String loader = Engine.detectLoader(vid);
                prefetchMods(mcId, loader);
                Path d = instDir(n);
                Files.createDirectories(d.resolve("mods"));
                Properties pr = new Properties();
                pr.setProperty("version", vid);
                pr.setProperty("mc", mcId);
                pr.setProperty("loader", loader);
                if (newRamMb >= 512) pr.setProperty("ram", String.valueOf(newRamMb));
                if (!vanilla && !before.contains(vid)) pr.setProperty("hide", vid);
                try (Writer w = Files.newBufferedWriter(d.resolve("profile.properties"), StandardCharsets.UTF_8)) {
                    pr.store(w, "PaxLauncher profile");
                }
                try { BuildIcons.save(d, chosenIcon, chosenImg); } catch (IOException ie) { log(I18n.t("Не вдалося зберегти іконку: ") + ie.getMessage()); }
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

    /** Діалог вибору іконки для нової збірки; результат кладе в масиви (id або власна картинка). */
    void pickNewIcon(String[] id, BufferedImage[] img, JComponent repaintMe) {
        IconPicker ip = new IconPicker(id[0], img[0], this, "cube");
        if (!Dlg.show(this, I18n.t("Іконка збірки"), ip, I18n.t("Обрати"), I18n.t("Скасувати"))) return;
        id[0] = ip.id();
        img[0] = ip.custom();
        if (repaintMe != null) repaintMe.repaint();
    }

    /** Завантажує модпак з Modrinth / CurseForge у ВЖЕ ІСНУЮЧУ збірку: нової збірки не створює й завантажувач не ставить. */
    Path packRegistry(Prof p) { return instDir(p.name()).resolve(".pax-modpacks.properties"); }

    boolean isPackInstalled(Prof p, ModApi.Hit h) {
        if (p == null || h == null) return false;
        Properties pr = new Properties();
        try (Reader r = Files.newBufferedReader(packRegistry(p), StandardCharsets.UTF_8)) { pr.load(r); } catch (IOException e) { return false; }
        return pr.getProperty(h.id()) != null;
    }

    void rememberPack(Prof p, ModApi.Hit h) { rememberPack(p, h.id(), h.title()); }

    void rememberPack(Prof p, String id, String title) {
        Properties pr = new Properties();
        Path f = packRegistry(p);
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { pr.load(r); } catch (IOException ignored) { }
        pr.setProperty(id, title);
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) { pr.store(w, "PaxLauncher modpacks"); } catch (IOException ignored) { }
    }

    /** Для збірки модів, яку вже встановлено у вибрану збірку: кнопка «Встановлено» + «Керувати збіркою модів». */
    void updatePackState() {
        ModApi.Hit h = mList.getSelectedValue();
        boolean inst = h != null && h.kind().isPack() && isPackInstalled(curProf(), h);
        packInstalledNow = inst;
        if (inst) {
            mInstall.setText(I18n.t("Встановлено"));
            mInstall.setEnabled(false);
        } else if (h != null && h.kind().isPack()) {
            Object o = mFiles.getSelectedItem();
            mInstall.setText(o instanceof ModApi.ModFile && ((ModApi.ModFile) o).url() == null ? I18n.t("Відкрити на сайті") : I18n.t("Встановити"));
        }
    }

    String savedQuery = "";

    /** Єдина кнопка «Керувати збіркою»: перемикає між пошуком і редагуванням встановленого (категорію обирають зверху). */
    boolean manageFromLib;

    void openPackManager() {
        if (curProf() == null) return;
        if (mInstalledView && manageFromLib) {
            manageFromLib = false;
            showInstalledView(false);
            showPage("library");
            return;
        }
        showInstalledView(!mInstalledView);
    }

    interface PlanOpener { Modpacks.Plan open(Modpacks packs, Engine.Progress pOpen) throws Exception; }

    void installPackInto(Prof prof, ModApi.Hit hit, ModApi.ModFile file) {
        runPackInstall(prof, hit.id(), hit.title(), (packs, pOpen) ->
                packs.open(CurseForge.isCf(hit.id()), hit.title(), hit.pageUrl(), file, pOpen, () -> engine.stop));
    }

    /** Імпорт збірки модів із файлу на диску (.mrpack Modrinth або .zip CurseForge) у ВЖЕ ІСНУЮЧУ збірку. */
    void importModpackFile(Prof prof) {
        if (prof == null) { msg(I18n.t("Спочатку створи збірку.")); return; }
        if (busy || mBusy) { msg(I18n.t("Зачекай, поки завершиться завантаження.")); return; }
        File[] f = FilePick.show(this, I18n.t("Обери файл збірки модів (.mrpack або .zip)"), JFileChooser.FILES_ONLY, false, null);
        if (f == null || f.length == 0) return;
        Path src = f[0].toPath();
        String fn = src.getFileName().toString();
        String low = fn.toLowerCase(Locale.ROOT);
        if (!low.endsWith(".mrpack") && !low.endsWith(".zip")) { msg(I18n.t("Потрібен файл збірки модів: .mrpack (Modrinth) або .zip (CurseForge).")); return; }
        String base = fn.replaceAll("(?i)\\.(mrpack|zip)$", "");
        runPackInstall(prof, "local:" + base, base, (packs, pOpen) -> packs.openLocal(src, base, pOpen));
    }

    void libProgress(String tx, double fr) {
        if (!libBuilt) return;
        libBar.set(fr);
        libBar.setVisible(true);
    }

    void libProgressEnd() {
        if (!libBuilt) return;
        libBar.set(0);
        libBar.setVisible(false);
        refreshLibrary();
    }

    void runPackInstall(Prof prof, String packId, String packTitle, PlanOpener opener) {
        final Modpacks packs = new Modpacks(api);
        final Path d = instDir(prof.name());
        setBusy(true);
        mBusy = true;
        mInstall.setEnabled(false);
        mBar.set(0.02);
        engine.stop = false;
        cancelUi(true, pCancel, pPause);
        Engine.Progress pg = (tx, fr) -> SwingUtilities.invokeLater(() -> { mStatus.setText(tx); mBar.set(fr); libProgress(tx, fr); });
        Engine.Progress pOpen = (tx, fr) -> pg.update(tx, 0.02 + 0.18 * fr);
        Engine.Progress pFiles = (tx, fr) -> pg.update(tx, 0.20 + 0.77 * fr);
        new Thread(() -> {
            final Path[] arc = {null};
            try {
                final Modpacks.Plan fplan = opener.open(packs, pOpen);
                arc[0] = fplan.archive();
                if (!Modpacks.sameLoader(fplan.loader(), prof.loader()))
                    throw new IOException(I18n.t("Ця збірка модів зроблена для завантажувача ") + fplan.loader()
                            + I18n.t(", а твоя збірка «") + prof.name() + I18n.t("» — на ") + prof.loader()
                            + I18n.t(". Моди не запрацюють, тому нічого не змінено."));
                if (!fplan.mc().equals(prof.mc()))
                    log(I18n.t("Увага: збірка модів для Minecraft ") + fplan.mc() + I18n.t(", а збірка «") + prof.name() + I18n.t("» — на ") + prof.mc());
                Files.createDirectories(d.resolve("mods"));
                final Set<String> filesBefore = PackStore.snapshot(d);
                Modpacks.Report rep = packs.install(fplan, d, pFiles, () -> engine.stop);
                PackStore.record(d, packId, filesBefore, PackStore.snapshot(d));
                rememberPack(prof, packId, packId.startsWith("local:") && !fplan.name().isEmpty() ? fplan.name() : packTitle);
                log(I18n.t("Збірку модів «") + fplan.name() + I18n.t("» встановлено у збірку «") + prof.name() + "»");
                for (String s : rep.failed()) log(I18n.t("Не вдалося завантажити: ") + s);
                for (String s : rep.manual()) log(I18n.t("Потрібно завантажити вручну: ") + s);
                SwingUtilities.invokeLater(() -> {
                    mBar.set(0);
                    mStatus.setText(I18n.t("Збірку модів «") + fplan.name() + I18n.t("» встановлено у збірку «") + prof.name() + "»");
                    status.setText(" ");
                    if (!rep.clean()) {
                        msg(I18n.t("Збірка модів встановлена, але не всі файли вдалося отримати: ") + (rep.failed().size() + rep.manual().size())
                                + I18n.t(" з ") + rep.total() + ".\n"
                                + I18n.t("Список файлів і посилання на них збережено у файлі PaxLauncher-missing-mods.txt в папці збірки:\n") + d);
                    } else if (packId.startsWith("local:")) {
                        msg(I18n.t("Збірку модів «") + fplan.name() + I18n.t("» встановлено у збірку «") + prof.name() + "»");
                    }
                });
            } catch (Engine.Cancelled cx) {
                log(I18n.t("Встановлення збірки модів скасовано"));
                SwingUtilities.invokeLater(() -> { mBar.set(0); mStatus.setText(I18n.t("Завантаження скасовано")); });
            } catch (Exception ex) {
                log(I18n.t("Помилка встановлення збірки модів: ") + ex);
                SwingUtilities.invokeLater(() -> {
                    mBar.set(0);
                    mStatus.setText(I18n.t("Не вдалося встановити збірку модів"));
                    msg(I18n.t("Не вдалося встановити збірку модів:\n") + ex.getMessage());
                });
            } finally {
                if (arc[0] != null) { try { Files.deleteIfExists(arc[0]); } catch (IOException ignored) { } }
                SwingUtilities.invokeLater(() -> {
                    mBusy = false;
                    setBusy(false);
                    cancelUi(false, pCancel, pPause);
                    mInstall.setEnabled(mFiles.getItemCount() > 0);
                    updatePackState();
                    libProgressEnd();
                });
            }
        }, "pack-install").start();
    }

    record PackEnt(String id, String title, int files) {
        @Override public String toString() {
            return title + "   ·   " + (files < 0 ? I18n.t("список файлів невідомий") : files + I18n.t(" файлів"));
        }
    }

    List<PackEnt> installedPacks(Prof p) {
        List<PackEnt> r = new ArrayList<>();
        if (p == null) return r;
        Properties pr = new Properties();
        try (Reader rd = Files.newBufferedReader(packRegistry(p), StandardCharsets.UTF_8)) { pr.load(rd); } catch (IOException e) { return r; }
        Path d = instDir(p.name());
        for (String id : new TreeSet<>(pr.stringPropertyNames())) r.add(new PackEnt(id, pr.getProperty(id), PackStore.count(d, id)));
        return r;
    }

    final DefaultListModel<PackEnt> pkModel = new DefaultListModel<>();
    final JList<PackEnt> pkList = emptyAware(pkModel);
    final Btn pkRm = new Btn(I18n.t("Видалити збірку модів"), Btn.Kind.SECONDARY);
    final JLabel pkEmpty = label(" ", 14, false, Theme.MUTED);

    /** Вигляд «Збірки модів» у керуванні збіркою: список поставлених у цю збірку збірок модів. */
    JPanel packsView() {
        pkList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pkList.setBackground(Theme.CARD2);
        pkList.setForeground(Theme.TEXT);
        pkList.setSelectionBackground(Theme.acc(70));
        pkList.setSelectionForeground(Theme.TEXT);
        pkList.setFont(Theme.font(Font.PLAIN, 15));
        pkList.setFixedCellHeight(46);
        pkList.setBorder(new EmptyBorder(4, 10, 4, 10));
        JScrollPane sp = new JScrollPane(pkList); Smooth.install(sp);
        sp.setBorder(null);
        sp.getViewport().setBackground(Theme.CARD2);
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        small(pkRm);
        pkRm.addActionListener(e -> removePackEntry(curProf(), pkList.getSelectedValue()));
        pkList.addListSelectionListener(e -> pkRm.setEnabled(pkList.getSelectedValue() != null));
        JLabel title = label(I18n.t("Встановлені збірки модів"), 15, true, Theme.TEXT);
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        head.add(title, BorderLayout.WEST);
        head.add(pkEmpty, BorderLayout.CENTER);
        pkEmpty.setHorizontalAlignment(SwingConstants.RIGHT);
        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        foot.setOpaque(false);
        foot.add(pkRm);
        JPanel v = new JPanel(new BorderLayout(0, 12));
        v.setOpaque(false);
        v.add(sp, BorderLayout.CENTER);
        v.add(foot, BorderLayout.SOUTH);
        return v;
    }

    void refreshPackList() {
        Prof p = curProf();
        PackEnt keep = pkList.getSelectedValue();
        pkModel.clear();
        List<PackEnt> list = installedPacks(p);
        for (PackEnt e : list) pkModel.addElement(e);
        pkEmpty.setText(" ");
        if (!list.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < list.size(); i++) if (keep != null && list.get(i).id().equals(keep.id())) idx = i;
            pkList.setSelectedIndex(idx);
        }
        pkRm.setEnabled(!list.isEmpty());
    }

    void removePackEntry(Prof p, PackEnt e) {
        if (p == null || e == null) return;
        if (busy || mBusy) { msg(I18n.t("Зачекай, поки завершиться завантаження.")); return; }
        Path d = instDir(p.name());
        boolean legacy = e.files() < 0;
        String q = legacy
                ? I18n.t("Збірку модів «") + e.title() + I18n.t("» поставила стара версія запускача — списку її файлів немає, тому точно прибрати лише її не вийде.\nБуде очищено папки mods, config, defaultconfigs, kubejs, scripts, resourcepacks і shaderpacks у збірці «") + p.name()
                + I18n.t("» (разом з модами, які ти додав вручну). Світи, options.txt та інші файли залишаться.\nПродовжити?")
                : I18n.t("Видалити збірку модів «") + e.title() + I18n.t("» зі збірки «") + p.name() + I18n.t("»?\nБуде видалено файлів: ") + e.files();
        if (!Dlg.show(this, I18n.t("Видалити збірку модів"), Dlg.text(q), legacy ? I18n.t("Очистити й видалити") : I18n.t("Видалити"), I18n.t("Скасувати"))) return;
        int n = 0;
        try { n = legacy ? PackStore.wipe(d) : PackStore.remove(d, e.id()); }
        catch (IOException ex) { msg(I18n.t("Не вдалося видалити файли: ") + ex.getMessage()); return; }
        Properties pr = new Properties();
        Path f = packRegistry(p);
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { pr.load(r); } catch (IOException ignored) { }
        pr.remove(e.id());
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) { pr.store(w, "PaxLauncher modpacks"); } catch (IOException ignored) { }
        PackStore.forget(d, e.id());
        log(I18n.t("Збірку модів «") + e.title() + I18n.t("» видалено зі збірки «") + p.name() + I18n.t("»: файлів ") + n);
        mStatus.setText(I18n.t("Збірку модів «") + e.title() + I18n.t("» видалено (файлів: ") + n + ")");
        updatePackState();
        refreshPackList();
        refreshLibrary();
    }

    boolean kindChosen0() { return mKind.getSelectedItem() instanceof ModApi.Kind; }

    // ---------- Бібліотека збірок ----------

    final JPanel libGrid = new JPanel(new WrapGrid(330, 208, 14));
    final JLabel libInfo = label(" ", 13, false, Theme.MUTED);
    final Field libQuery = new Field("");
    final Bar libBar = new Bar();
    final JLabel libEmpty = label(" ", 15, false, Theme.MUTED);
    final JLabel libCount = label(" ", 13, false, Theme.MUTED);
    final List<Prof> libProfs = new ArrayList<>();
    final List<JComponent> libCards = new ArrayList<>();
    boolean libBuilt;

    /** Яскравий блок угорі бібліотеки: головна точка входу в створення збірки. */
    JComponent libHero() {
        JPanel hero = new JPanel(new BorderLayout(18, 0)) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                int w = getWidth(), h = getHeight();
                RoundRectangle2D rr = new RoundRectangle2D.Float(0, 0, w, h, 20, 20);
                g.setColor(Theme.CARD);
                g.fill(rr);
                g.setPaint(new GradientPaint(0, 0, Theme.acc(104), w, h, Theme.acc(16)));
                g.fill(rr);
                Shape old = g.getClip();
                g.clip(rr);
                g.setColor(Theme.acc(28));
                g.fillOval(w - 190, -70, 230, 230);
                g.setColor(Theme.acc(20));
                g.fillOval(w - 100, h - 60, 150, 150);
                g.fillOval(w - 330, h - 34, 70, 70);
                g.setClip(old);
                g.setColor(Theme.acc(110));
                g.setStroke(new BasicStroke(1.3f));
                g.draw(new RoundRectangle2D.Float(0.65f, 0.65f, w - 1.3f, h - 1.3f, 20, 20));
                g.dispose();
            }
        };
        hero.setOpaque(false);
        hero.setBorder(new EmptyBorder(14, 20, 14, 20));

        JComponent badge = new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                int s = Math.min(getWidth(), getHeight());
                g.setPaint(new GradientPaint(0, 0, Theme.ACC_HI, s, s, Theme.ACC1));
                g.fillRoundRect(0, 0, s, s, s * 3 / 8, s * 3 / 8);
                g.setColor(Theme.ACC_ON);
                g.setStroke(new BasicStroke(Math.max(3f, s / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int c = s / 2, r = s / 5;
                g.drawLine(c - r, c, c + r, c);
                g.drawLine(c, c - r, c, c + r);
                g.dispose();
            }
        };
        badge.setPreferredSize(new Dimension(48, 48));
        JPanel badgeW = new JPanel(new GridBagLayout());
        badgeW.setOpaque(false);
        badgeW.add(badge);
        hero.add(badgeW, BorderLayout.WEST);

        JPanel txt = new JPanel();
        txt.setOpaque(false);
        txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
        JLabel a = label(I18n.t("Створи нову збірку"), 18, true, Theme.TEXT);
        JLabel b = label(I18n.t("Окрема копія гри зі своїми модами, світами та власною іконкою."), 13, false, Theme.SUB);
        a.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        txt.add(Box.createVerticalGlue());
        txt.add(a);
        txt.add(Box.createVerticalStrut(4));
        txt.add(b);
        txt.add(Box.createVerticalGlue());
        hero.add(txt, BorderLayout.CENTER);

        Btn mk = small(new Btn(I18n.t("Створити збірку"), Btn.Kind.PRIMARY));
        mk.setFont(Theme.bfont(Font.BOLD, 14));
        mk.setPlus(true);
        mk.setPreferredSize(new Dimension(200, 42));
        mk.addActionListener(e -> newProfile());
        JPanel bw = new JPanel(new GridBagLayout());
        bw.setOpaque(false);
        bw.add(mk);
        hero.add(bw, BorderLayout.EAST);
        return hero;
    }

    JPanel libraryPage() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(24, 28, 22, 28));
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        JLabel t = label(I18n.t("Бібліотека збірок"), 25, true, Theme.TEXT);
        // кількість збірок — просто число праворуч від заголовка, без тексту
        libInfo.setFont(Theme.font(Font.PLAIN, 13));
        JPanel titleRow = new JPanel(new BorderLayout(10, 0));
        titleRow.setOpaque(false);
        titleRow.add(t, BorderLayout.WEST);
        JPanel cntW = new JPanel(new GridBagLayout());
        cntW.setOpaque(false);
        cntW.add(libInfo);
        titleRow.add(cntW, BorderLayout.EAST);
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        head.add(titleRow);
        head.add(Box.createVerticalStrut(10));
        JComponent hero = libHero();
        hero.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(hero);
        head.add(Box.createVerticalStrut(10));

        libQuery.setHint(I18n.t("Пошук збірки"));
        libQuery.setPreferredSize(new Dimension(300, 40));
        Btn clr = tiny(new Btn(I18n.t("Очистити"), Btn.Kind.GHOST));
        clr.setVisible(false);
        clr.addActionListener(e -> { libQuery.setText(""); libQuery.requestFocusInWindow(); });
        // кнопка «Очистити» живе всередині поля пошуку, праворуч; з'являється, коли є що очищати
        libQuery.setBorder(new EmptyBorder(11, 14, 11, Theme.px(96)));
        libQuery.setLayout(new LayoutManager() {
            @Override public void addLayoutComponent(String n, Component c) { }
            @Override public void removeLayoutComponent(Component c) { }
            @Override public Dimension preferredLayoutSize(Container c) { return new Dimension(0, 0); }
            @Override public Dimension minimumLayoutSize(Container c) { return new Dimension(0, 0); }
            @Override public void layoutContainer(Container c) {
                Dimension d = clr.getPreferredSize();
                clr.setBounds(c.getWidth() - d.width - 6, (c.getHeight() - d.height) / 2, d.width, d.height);
            }
        });
        libQuery.add(clr);
        libQuery.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void ch() { clr.setVisible(!libQuery.getText().isEmpty()); applyLibFilter(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { ch(); }
        });
        JPanel sr = new JPanel(new BorderLayout());
        sr.setOpaque(false);
        sr.add(libQuery, BorderLayout.CENTER);
        sr.setAlignmentX(Component.LEFT_ALIGNMENT);
        sr.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        head.add(sr);
        libBar.setPreferredSize(new Dimension(100, 6));
        libBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        libBar.setVisible(false);
        libBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(Box.createVerticalStrut(4));
        head.add(libBar);
        p.add(head, BorderLayout.NORTH);

        libGrid.setOpaque(false);
        libEmpty.setBorder(new EmptyBorder(4, 2, 0, 0));
        VPanel body = new VPanel();
        body.row(libGrid, 0);
        body.row(libEmpty, 6);
        body.end();
        p.add(vscroll(body, Theme.BG2), BorderLayout.CENTER);
        libBuilt = true;
        refreshLibrary();
        return p;
    }

    /** Кругла кнопка-іконка збірки на картці: клік відкриває вибір іконки. */
    JComponent libIcon(Prof pf, Path d) {
        final int px = Theme.px(64);
        final BufferedImage im = BuildIcons.load(d, px * 2);
        JComponent c = new JComponent() {
            boolean hov;
            {
                setPreferredSize(new Dimension(px, px));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText(I18n.t("Змінити іконку"));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hov = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hov = false; repaint(); }
                    @Override public void mouseClicked(MouseEvent e) { changeIcon(pf); }
                });
            }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                int s = Math.min(getWidth(), getHeight());
                g.drawImage(im, 0, 0, s, s, null);
                if (hov) {
                    g.setColor(new Color(0, 0, 0, 90));
                    g.fillRoundRect(0, 0, s, s, (int) (s * 0.46), (int) (s * 0.46));
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke(Math.max(2f, s / 24f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int cx = s / 2, cy = s / 2, r = s / 6;
                    g.drawLine(cx - r, cy + r, cx + r, cy - r);
                    g.drawLine(cx - r, cy + r, cx - r, cy + r - s / 12);
                    g.drawLine(cx - r, cy + r, cx - r + s / 12, cy + r);
                }
                g.dispose();
            }
        };
        return c;
    }

    void changeIcon(Prof pf) {
        Path d = instDir(pf.name());
        String cur = BuildIcons.choice(d);
        BufferedImage custom = null;
        if (BuildIcons.CUSTOM.equals(cur)) {
            try { custom = javax.imageio.ImageIO.read(d.resolve("icon.png").toFile()); } catch (IOException ignored) { }
        }
        String dflt = BuildIcons.defaultId(pf.name());
        IconPicker ip = new IconPicker(BuildIcons.valid(cur) ? cur : dflt, custom, this, dflt);
        if (!Dlg.show(this, I18n.t("Іконка збірки «") + pf.name() + "»", ip, I18n.t("Зберегти"), I18n.t("Скасувати"))) return;
        try { BuildIcons.save(d, ip.id(), ip.custom()); }
        catch (IOException ex) { msg(I18n.t("Не вдалося зберегти іконку: ") + ex.getMessage()); return; }
        refreshLibrary();
    }

    JComponent libCard(Prof pf) {
        Path d = instDir(pf.name());
        int mods = 0;
        try (var st = Files.list(d.resolve("mods"))) {
            mods = (int) st.filter(x -> { String n = x.getFileName().toString(); return n.endsWith(".jar") || n.endsWith(".jar.disabled"); }).count();
        } catch (IOException ignored) { }
        int packs = installedPacks(pf).size();

        Card c = new Card(new BorderLayout(0, 12), 14);
        JLabel name = label(pf.name(), 18, true, Theme.TEXT);
        JLabel sub = label(pf.loader() + "  ·  Minecraft " + pf.mc(), 13, false, Theme.MUTED);
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel stat = label(I18n.t("Модів: ") + mods + I18n.t("   ·   збірок модів: ") + packs, 13, true, Theme.TEXT);
        stat.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(Box.createVerticalGlue());
        top.add(name);
        top.add(Box.createVerticalStrut(2));
        top.add(sub);
        top.add(Box.createVerticalStrut(4));
        top.add(stat);
        top.add(Box.createVerticalGlue());
        JPanel head = new JPanel(new BorderLayout(14, 0));
        head.setOpaque(false);
        JPanel icW = new JPanel(new BorderLayout());
        icW.setOpaque(false);
        icW.setBorder(new EmptyBorder(Theme.px(6), 0, 0, 0));
        icW.add(libIcon(pf, d), BorderLayout.CENTER);
        head.add(icW, BorderLayout.WEST);
        head.add(top, BorderLayout.CENTER);
        c.add(head, BorderLayout.CENTER);

        Btn play = small(new Btn(I18n.t("Грати"), Btn.Kind.PRIMARY));
        Btn manage = small(new Btn(I18n.t("Керувати"), Btn.Kind.SECONDARY));
        Btn dir = small(new Btn(I18n.t("Папка"), Btn.Kind.SECONDARY));
        Btn imp = small(new Btn(I18n.t("Імпорт"), Btn.Kind.SECONDARY));
        Btn ico = small(new Btn(I18n.t("Іконка"), Btn.Kind.SECONDARY));
        Btn del = small(new Btn(I18n.t("Видалити"), Btn.Kind.GHOST));
        play.addActionListener(e -> {
            fillVersions("profile:" + pf.name());
            showPage("play");
            play();
        });
        manage.addActionListener(e -> {
            modsInit = true;
            refreshProfiles(pf.name());
            showInstalledView(true);
            showPage("mods");
            manageFromLib = true;
        });
        dir.addActionListener(e -> openFolder(d));
        imp.addActionListener(e -> importChooser(pf));
        ico.addActionListener(e -> changeIcon(pf));
        del.addActionListener(e -> deleteProfile(pf));
        JPanel r1 = new JPanel(new GridLayout(1, 3, 8, 0));
        r1.setOpaque(false);
        r1.add(play);
        r1.add(manage);
        r1.add(dir);
        JPanel r2 = new JPanel(new GridLayout(1, 3, 8, 0));
        r2.setOpaque(false);
        r2.add(imp);
        r2.add(ico);
        r2.add(del);
        JPanel bt = new JPanel(new GridLayout(2, 1, 0, 8));
        bt.setOpaque(false);
        bt.add(r1);
        bt.add(r2);
        c.add(bt, BorderLayout.SOUTH);
        return c;
    }

    /** Перебудовує картки збірок з диска (після створення / видалення / імпорту) і застосовує пошук. */
    void refreshLibrary() {
        if (!libBuilt) return;
        libProfs.clear();
        libCards.clear();
        for (Prof pf : loadProfiles()) {
            libProfs.add(pf);
            libCards.add(libCard(pf));
        }
        applyLibFilter();
    }

    /** Показує лише ті збірки, що підходять під пошуковий запит (за назвою, завантажувачем і версією гри). */
    void applyLibFilter() {
        if (!libBuilt) return;
        String q = libQuery.getText().trim().toLowerCase(Locale.ROOT);
        String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
        libGrid.removeAll();
        int shown = 0;
        for (int i = 0; i < libProfs.size(); i++) {
            Prof pf = libProfs.get(i);
            String hay = (pf.name() + " " + pf.loader() + " " + pf.mc() + " " + pf.versionId()).toLowerCase(Locale.ROOT);
            boolean ok = true;
            for (String w : words) if (!hay.contains(w)) { ok = false; break; }
            if (ok) { libGrid.add(libCards.get(i)); shown++; }
        }
        int total = libProfs.size();
        libInfo.setText(total == 0 ? " " : q.isEmpty() ? String.valueOf(total) : shown + " / " + total);
        libCount.setText(" ");
        libEmpty.setText(total == 0 ? I18n.t("У бібліотеці ще немає збірок — натисни «Створити збірку» вгорі.")
                : shown == 0 ? I18n.t("За запитом «") + libQuery.getText().trim() + I18n.t("» нічого не знайдено.") : " ");
        libEmpty.setVisible(shown == 0);
        libGrid.setVisible(shown > 0);
        libGrid.revalidate();
        libGrid.repaint();
        if (libEmpty.getParent() != null) libEmpty.getParent().revalidate();
    }

    void deleteProfile(Prof p) {
        if (p == null) return;
        if (busy || mBusy) { msg(I18n.t("Зачекай, поки завершиться завантаження.")); return; }
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
        JLabel t = label(I18n.t("Збірки"), 26, true, Theme.TEXT);
                t.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(t);
        head.add(Box.createVerticalStrut(4));
                p.add(head, BorderLayout.NORTH);

        mKind.addItem(ModApi.Kind.MOD);
        mKind.addItem(ModApi.Kind.PACK);
        mKind.addItem(ModApi.Kind.SHADER);
        mKind.addItem(ModApi.Kind.WORLD);
        mKind.addItem(ModApi.Kind.MODPACK);
        mKind.placeholder = I18n.t("Оберіть категорію пошуку");
        mKind.setSelectedItem(null);
        mKind.setPreferredSize(new Dimension(260, 44));
        mProf.setPreferredSize(new Dimension(260, 44));
        mSrc.addItem(ModApi.Source.MODRINTH);
        mSrc.addItem(ModApi.Source.CURSEFORGE);
        mSrc.placeholder = I18n.t("Оберіть платформу");
        mSrc.setSelectedItem(null);
        mSrc.setPreferredSize(new Dimension(190, 44));

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
        Btn impB = small(new Btn(I18n.t("Імпортувати файли"), Btn.Kind.SECONDARY));
        impB.addActionListener(e -> importChooser());
        mImpB = impB;
        small(mManage);
        mManage.setText(I18n.t("Керувати збіркою"));
        JComponent[] r1c = {impB, mManage};
        for (int ci = 0; ci < r1c.length; ci++) {
            if (ci > 0) r1b.add(Box.createHorizontalStrut(8));
            r1c[ci].setAlignmentY(Component.CENTER_ALIGNMENT);
            r1b.add(r1c[ci]);
        }
        r1b.add(Box.createHorizontalGlue());
        row1.add(r1b, BorderLayout.CENTER);

        JPanel row2 = new JPanel(new BorderLayout(10, 0));
        row2.setOpaque(false);
        JPanel kindBox = new JPanel();
        kindBox.setOpaque(false);
        kindBox.setLayout(new BoxLayout(kindBox, BoxLayout.X_AXIS));
        kindBox.add(mKind);
        kindBox.add(Box.createHorizontalStrut(8));
        kindBox.add(mSrc);
        row2.add(kindBox, BorderLayout.WEST);
        row2.add(mQuery, BorderLayout.CENTER);
        JPanel r2b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        r2b.setOpaque(false);
        r2b.add(mSearch);
        row2.add(r2b, BorderLayout.EAST);

        mRow1 = row1; mRow2 = row2; mR1b = r1b; mR2b = r2b;
        JPanel top = new JPanel();
        mTop = top;
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        manageHead = buildManageHead();
        top.add(manageHead);
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
        mList.setCellRenderer(new ListCellRenderer<ModApi.Hit>() {
            final RowCell cell = new RowCell(58);
            @Override public Component getListCellRendererComponent(JList<? extends ModApi.Hit> l, ModApi.Hit h, int i, boolean sel, boolean foc) {
                cell.sel = sel;
                cell.dim = false;
                cell.title = h.title();
                cell.meta = fmt(h.downloads()) + I18n.t(" завант.");
                cell.sub = api.cached(h.desc());
                final String u = h.iconUrl();
                cell.icon = () -> modIcon(u == null || u.isEmpty() ? "def" : u, 40, mList);
                return cell;
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
        dIconWrap = new JPanel(new BorderLayout());
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
        iList.setCellRenderer(new ListCellRenderer<String>() {
            final RowCell cell = new RowCell(54);
            @Override public Component getListCellRendererComponent(JList<? extends String> l, String n, int i, boolean sel, boolean foc) {
                boolean off = n.endsWith(".disabled");
                String base = off ? n.substring(0, n.length() - 9) : n;
                cell.sel = sel;
                cell.dim = off;
                cell.title = base.replaceFirst("(?i)\\.(jar|zip)$", "");
                cell.meta = off ? I18n.t("   (вимкнено)").trim() : "";
                cell.sub = base;
                final String tok = fileIcon.get(iconDirKey + "|" + base);
                cell.icon = () -> modIcon(tok, 40, iList);
                return cell;
            }
        });
        JScrollPane isp = new JScrollPane(iList); Smooth.install(isp);
        isp.setBorder(null);
        isp.getViewport().setBackground(Theme.CARD2);
        isp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        isp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        isp.getHorizontalScrollBar().setUI(new SlimScrollBarUI());
        isp.setPreferredSize(new Dimension(100, 100));

        small(togB);
        Btn rmB = small(mRmB);
        small(iPage);
        iPage.setEnabled(false);
        small(mRootB);
        JPanel selBtns = new JPanel();
        selBtns.setOpaque(false);
        selBtns.setLayout(new BoxLayout(selBtns, BoxLayout.X_AXIS));
        selBtns.add(togB);
        selBtns.add(Box.createHorizontalStrut(8));
        selBtns.add(rmB);
        selBtns.add(Box.createHorizontalStrut(8));
        selBtns.add(iPage);
        JPanel dirBtns = new JPanel();
        dirBtns.setOpaque(false);
        dirBtns.setLayout(new BoxLayout(dirBtns, BoxLayout.X_AXIS));
        dirBtns.add(mDirB);
        dirBtns.add(Box.createHorizontalStrut(8));
        dirBtns.add(mRootB);
        JPanel iFoot = new JPanel(new BorderLayout(12, 0));
        iFoot.setOpaque(false);
        iFoot.add(selBtns, BorderLayout.WEST);
        iFoot.add(dirBtns, BorderLayout.EAST);
        JPanel iBody = new JPanel(new BorderLayout(0, 12));
        iBody.setOpaque(false);
        iBody.add(isp, BorderLayout.CENTER);
        iBody.add(iFoot, BorderLayout.SOUTH);

        JPanel installedView = new JPanel(new BorderLayout(0, 12));
        installedView.setOpaque(false);
        installedView.add(iBody, BorderLayout.CENTER);
        iList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) onInstalledSelected(); });
        iPage.addActionListener(e -> {
            String n = iList.getSelectedValue();
            if (n == null) return;
            String base = n.endsWith(".disabled") ? n.substring(0, n.length() - 9) : n;
            String pid = iProj.get(iconDirKey + "|" + base);
            if (pid != null) {
                if (CurseForge.isCf(pid)) browse("https://www.curseforge.com/projects/" + pid.substring(3).replaceAll("\\D", ""));
                else browse("https://modrinth.com/" + curKind().type + "/" + pid);
            }
        });

        mCenter.setOpaque(false);
        mCenter.add(searchView, "search");
        mCenter.add(installedView, "installed");
        mCenter.add(packsView(), "packs");
        JPanel emptyV = new JPanel(new GridBagLayout());
        emptyV.setOpaque(false);
        Btn createB = new Btn(I18n.t("Створити збірку"), Btn.Kind.PRIMARY);
        createB.setBorder(new EmptyBorder(14, 28, 14, 28));
        createB.setFont(Theme.bfont(Font.BOLD, 15));
        createB.addActionListener(e -> newProfile());
        emptyV.add(createB);
        mCenter.add(emptyV, "empty");
        JPanel pickV = new JPanel(new GridBagLayout());
        pickV.setOpaque(false);
        mCenter.add(pickV, "pick");
        JPanel loadV = new JPanel(new GridBagLayout());
        loadV.setOpaque(false);
        JPanel loadBox = new JPanel();
        loadBox.setOpaque(false);
        loadBox.setLayout(new BoxLayout(loadBox, BoxLayout.Y_AXIS));
        mSpin.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel loadLbl = label(I18n.t("Завантаження…"), 15, true, Theme.MUTED);
        loadLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        loadBox.add(mSpin);
        loadBox.add(Box.createVerticalStrut(14));
        loadBox.add(loadLbl);
        loadV.add(loadBox);
        mCenter.add(loadV, "loading");

        Card c = new Card(new BorderLayout(0, 6), 16);
        mCard = c;
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
        mPulse.addActionListener(e -> { if (mLoading) { if (mSpin.isShowing()) mSpin.repaint(); } else mPulse.stop(); });
        mSrc.addActionListener(e -> {
            if (srcAuto || !srcChosen()) return;
            ModApi.Source sn = srcNow();
            if (sn == ModApi.Source.MODRINTH && mKind.getSelectedItem() instanceof ModApi.Kind && curKind().isWorld()) {
                msg(I18n.t("Мап на Modrinth немає — вони є лише на CurseForge."));
                srcAuto = true;
                mSrc.setSelectedItem(ModApi.Source.CURSEFORGE);
                srcAuto = false;
                return;
            }
            cfg.set("modSource", sn.name());
            cfg.save();
            kindNow = curKind();
            applyKindTexts();
            if (modsInit && !mFilling) { showInstalledView(false); onProfileChanged(); }
        });
        mKind.addActionListener(e -> {
            syncPills();
            kindNow = curKind();
            if (mKind.getSelectedItem() instanceof ModApi.Kind && curKind().isWorld()
                    && (!srcChosen() || srcNow() == ModApi.Source.MODRINTH)) {
                srcAuto = true;
                mSrc.setSelectedItem(ModApi.Source.CURSEFORGE);
                srcAuto = false;
                cfg.set("modSource", ModApi.Source.CURSEFORGE.name());
                cfg.save();
            }
            applyKindTexts();
            if (modsInit && !mFilling) {
                if (mInstalledView) {
                    updateEmptyState();
                    if (curKind().isPack()) refreshPackList(); else refreshInstalled();
                }
                else onProfileChanged();
            }
        });
        mQuery.setHint(I18n.t("Пошук…"));
        mQuery.addActionListener(e -> { if (mInstalledView) applyInstalledFilter(); else search(false, false); });
        mSearch.addActionListener(e -> { if (mInstalledView) applyInstalledFilter(); else search(false, false); });
        mQuery.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void ch() { if (mInstalledView && !mFilling) applyInstalledFilter(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { ch(); }
        });
        mMore.addActionListener(e -> search(true, false));
        mManage.addActionListener(e -> openPackManager());
        mList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) onHitSelected(); });
        mFiles.addActionListener(e -> {
            Object o = mFiles.getSelectedItem();
            mInstall.setText(o instanceof ModApi.ModFile && ((ModApi.ModFile) o).url() == null ? I18n.t("Відкрити на сайті") : I18n.t("Встановити"));
            updatePackState();
        });
        mRootB.addActionListener(e -> {
            Prof pf = curProf();
            if (pf == null) msg(I18n.t("Спочатку створи збірку.")); else openFolder(instDir(pf.name()));
        });
        mInstall.addActionListener(e -> modInstall());
        mPage.addActionListener(e -> { ModApi.Hit h = mList.getSelectedValue(); if (h != null) browse(h.pageUrl()); });
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
        boolean chosen = kindChosen(), pack = chosen && curKind().isPack(), hasProf = curProf() != null;
        boolean tv = false;
        if (mToggleView.isVisible() != tv) {
            mToggleView.setVisible(tv);
            if (mToggleView.getParent() != null) { mToggleView.getParent().revalidate(); mToggleView.getParent().repaint(); }
        }
        if (mManage.isVisible() != hasProf) mManage.setVisible(hasProf);
        if (mTop != null && mTop.isVisible() != hasProf) {
            mTop.setVisible(hasProf);
            if (mTop.getParent() != null) { mTop.getParent().revalidate(); mTop.getParent().repaint(); }
        }
        mQuery.setEnabled((chosen || mInstalledView) && hasProf);
        if (!mLoading) mSearch.setEnabled((chosen || mInstalledView) && hasProf);
        mCards.show(mCenter, !hasProf ? "empty" : mInstalledView && mKind.getSelectedItem() instanceof ModApi.Kind ? (curKind().isPack() ? "packs" : "installed") : !chosen ? "pick"
                : mLoading && mModel.isEmpty() ? "loading" : "search");
    }

    Object savedKind;

    void showInstalledView(boolean installed) {
        boolean changed = installed != mInstalledView;
        if (installed && changed) savedQuery = mQuery.getText();
        mFilling = true;
        try {
            if (changed) {
                mQuery.setText(installed ? "" : savedQuery);
                if (installed) {
                    // у керуванні «Збірки модів» не потрібні, а категорію користувач обирає наново
                    savedKind = mKind.getSelectedItem();
                    mKind.placeholder = I18n.t("Обрати категорію");
                    mKind.setSelectedItem(ModApi.Kind.MOD);
                } else {
                    mKind.placeholder = I18n.t("Оберіть категорію пошуку");
                    if (!(mKind.getSelectedItem() instanceof ModApi.Kind)) mKind.setSelectedItem(savedKind);
                }
                kindNow = curKind();
            }
        } finally { mFilling = false; }
        mInstalledView = installed;
        placeManageControls(installed);
        if (!mBusy) { mfSeq.incrementAndGet(); mStatus.setText(" "); }
        manageHead.setVisible(installed);
        mRow1.setVisible(!installed);
        mRow2.setVisible(!installed);
        mKind.setVisible(!installed);
        mProf.setVisible(!installed);
        mSearch.setVisible(!installed);
        if (installed) updateManageHead();
        syncPills();
        if (mTop != null) { mTop.revalidate(); mTop.repaint(); }
                mToggleView.setVisible(false);
        mSrc.setVisible(!installed);
        mQuery.setHint(installed ? I18n.t("Пошук у встановлених…") : I18n.t("Пошук…"));
        mManage.setText(installed ? I18n.t("Назад до пошуку") : I18n.t("Керувати збіркою"));
        updateEmptyState();
        applyKindTexts();
        if (mManage.getParent() != null) { mManage.getParent().revalidate(); mManage.getParent().repaint(); }
        if (mSrc.getParent() != null) { mSrc.getParent().revalidate(); mSrc.getParent().repaint(); }
        mKind.repaint();
        if (installed) { iModel.clear(); iAll.clear(); if (mKind.getSelectedItem() instanceof ModApi.Kind) { if (curKind().isPack()) refreshPackList(); else refreshInstalled(); } }
        else if (changed && modsInit) onProfileChanged();
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
        for (JComponent c : new JComponent[]{mDirB, mRmB, mPage, mToggleView, iPage}) {
            if (c.getParent() != null) { c.getParent().revalidate(); c.getParent().repaint(); }
        }
    }

    static String importTitle(ModApi.Kind k) {
        switch (k) {
            case PACK: return I18n.t("Перенесення наборів ресурсів");
            case SHADER: return I18n.t("Перенесення шейдерів");
            default: return I18n.t("Перенесення модів");
        }
    }

    static String target(ModApi.Kind k, Prof p) { return k.isMod() ? p.loader() + " " + p.mc() : p.mc(); }

    void setDesc(ModApi.Hit h) {
        String t = api.cached(h.desc());
        String b = dBody;
        dDesc.setText(b.isBlank() ? t : t + "\n\n" + b);
        dDesc.setCaretPosition(0);
    }

    final javax.swing.Timer mRepaintT = new javax.swing.Timer(150, null);
    final javax.swing.Timer iRepaintT = new javax.swing.Timer(150, null);
    {
        mRepaintT.setRepeats(false);
        mRepaintT.addActionListener(e -> mList.repaint());
        iRepaintT.setRepeats(false);
        iRepaintT.addActionListener(e -> iList.repaint());
    }

    /** Не більше одного перемальовування списку за ~150 мс, навіть якщо в фоні щойно прийшла сотня описів/іконок. */
    void repaintListSoon() { if (!mRepaintT.isRunning()) mRepaintT.start(); }

    void repaintInstalledSoon() {
        SwingUtilities.invokeLater(() -> { if (!iRepaintT.isRunning()) iRepaintT.start(); });
    }

    final ExecutorService trPool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "mod-translate");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    final Set<String> trQueued = ConcurrentHashMap.newKeySet();

    void translateHits() { translateHits(0); }

    void translateHits(int attempt) {
        List<ModApi.Hit> hs = Collections.list(mModel.elements());
        int my = mSeq.get();
        boolean left = false;
        for (ModApi.Hit h : hs) {
            String d = h.desc();
            if (!api.needsTranslation(d)) continue;
            left = true;
            if (!trQueued.add(d)) continue;
            trPool.execute(() -> {
                try {
                    if (my != mSeq.get()) return;
                    api.translate(d);
                } finally {
                    trQueued.remove(d);
                }
                SwingUtilities.invokeLater(() -> {
                    if (my != mSeq.get()) return;
                    repaintListSoon();
                    if (h == mList.getSelectedValue()) setDesc(h);
                });
            });
        }
        if (left && attempt < 25) {
            int delay = Math.min(12000, 3000 + attempt * 1000);
            javax.swing.Timer rt = new javax.swing.Timer(delay, e -> { if (my == mSeq.get()) translateHits(attempt + 1); });
            rt.setRepeats(false);
            rt.start();
        }
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
        dMeta.setText(I18n.t("від ") + h.author() + "  ·  " + h.source().label);
        fillChips(dChips, hitChips(h, null));
        setLabelIcon(dIcon, h.iconUrl(), 72);
        setDesc(h);
        if (api.needsTranslation(h.desc())) {
            new Thread(() -> {
                for (int i = 0; i < 6 && my == dSeq.get() && api.needsTranslation(h.desc()); i++) {
                    api.translate(h.desc());
                    if (!api.needsTranslation(h.desc())) break;
                    try { Thread.sleep(2500L * (i + 1)); } catch (InterruptedException e) { return; }
                }
                SwingUtilities.invokeLater(() -> { if (my == dSeq.get() && h == mList.getSelectedValue()) setDesc(h); repaintListSoon(); });
            }, "mod-translate-one").start();
        }
        mPage.setEnabled(true);
        new Thread(() -> {
            ModApi.Detail pd = null;
            try { pd = api.project(h.id()); } catch (Exception ignored) { }
            if (pd == null || my != dSeq.get()) return;
            final String raw = ModApi.excerpt(pd.body(), 1200);
            final ModApi.Detail fpd = pd;
            SwingUtilities.invokeLater(() -> {
                if (my != dSeq.get()) return;
                dBody = api.cached(raw);
                fillChips(dChips, hitChips(h, fpd));
                setDesc(h);
            });
            for (int i = 0; i < 6 && my == dSeq.get() && api.needsTranslation(raw); i++) {
                final String t = api.translate(raw);
                if (!api.needsTranslation(raw)) {
                    SwingUtilities.invokeLater(() -> {
                        if (my != dSeq.get()) return;
                        dBody = t;
                        setDesc(h);
                    });
                    break;
                }
                try { Thread.sleep(2500L * (i + 1)); } catch (InterruptedException e) { return; }
            }
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
        boolean has = n != null && curProf() != null;
        togB.setEnabled(has && !curKind().isWorld());
        mRmB.setEnabled(has);
        if (!has) { iPage.setEnabled(false); return; }
        boolean off = n.endsWith(".disabled");
        String base = off ? n.substring(0, n.length() - 9) : n;
        iPage.setEnabled(iProj.get(iconDirKey + "|" + base) != null);
    }

    void search(boolean more, boolean quiet) {
        if (!kindChosen()) return;
        Prof p = curProf();
        ModApi.Kind k = curKind();
        if (p == null) { if (!quiet) msg(I18n.t("Спочатку створи збірку у вкладці «Бібліотека».")); return; }
        final String smc = p.mc(), sld = p.loader();
        final String tgt = target(k, p);
        final ModApi.Source src = srcNow();
        if ((k.isMod() || k.isPack()) && sld.equals("Vanilla")) {
            if (!quiet) msg(I18n.t("Чиста збірка не підтримує моди.\nСтвори збірку на основі Fabric / Quilt / Forge / NeoForge."));
            return;
        }
        if (mInstalledView) showInstalledView(false);
        String q = mQuery.getText().trim();
        int off = more ? mModel.size() : 0;
        int my = mSeq.incrementAndGet();
        mSearch.setEnabled(false);
        mMore.setEnabled(false);
        if (!more) {
            mModel.clear();
            mLoading = true;
            if (!mPulse.isRunning()) mPulse.start();
            showDetails(null);
            updateEmptyState();
        }
        mStatus.setText(I18n.t("Пошук: ") + k.title.toLowerCase(Locale.ROOT) + I18n.t(" на ") + src.label + "…");
        new Thread(() -> {
            List<ModApi.Hit> res = null;
            String err = null;
            try { res = api.search(src, k, q, smc, sld, off); } catch (Exception ex) { err = ex.getMessage(); }
            if (res != null && my == mSeq.get()) warmHits(res, more ? 1500 : 900, more, () -> my != mSeq.get());
            List<ModApi.Hit> rr = res;
            String er = err;
            SwingUtilities.invokeLater(() -> {
                if (my != mSeq.get()) return;
                mLoading = false;
                mSearch.setEnabled(true);
                if (!more) mModel.clear();
                if (rr != null && !rr.isEmpty()) mModel.addAll(rr);
                updateEmptyState();
                if (!more && !mModel.isEmpty() && mList.getSelectedIndex() < 0) mList.setSelectedIndex(0);
                if (!more && mModel.isEmpty()) showDetails(null);
                mMore.setEnabled(rr != null && rr.size() >= ModApi.PAGE);
                mStatus.setText(er != null ? I18n.t("Помилка пошуку: ") + er
                        : mModel.isEmpty() ? (k.isPack() ? I18n.t("Нічого не знайдено") : I18n.t("Нічого не знайдено для ") + tgt)
                        : I18n.t("Знайдено: ") + mModel.size() + " • " + k.title + " • " + src.label + " • " + tgt);
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
        final String smc = p.mc(), sld = p.loader();
        final String tgt = target(h.kind(), p);
        mStatus.setText(" ");
        new Thread(() -> {
            List<ModApi.ModFile> res = null;
            String err = null;
            try { res = api.files(h.kind(), h.id(), smc, sld); } catch (Exception ex) { err = ex.getMessage(); }
            List<ModApi.ModFile> rr = res;
            String er = err;
            SwingUtilities.invokeLater(() -> {
                if (my != mfSeq.get()) return;
                mFiles.removeAllItems();
                Modpacks.Pick packPick = null;
                boolean ok = rr != null && !rr.isEmpty();
                if (ok) {
                    int idx = 0;
                    for (int i = 0; i < rr.size(); i++) {
                        mFiles.addItem(rr.get(i));
                    }
                    for (int i = 0; i < rr.size(); i++) if (rr.get(i).channel().equals("release")) { idx = i; break; }
                    if (h.kind().isPack()) { packPick = Modpacks.pick(rr, smc, sld); if (packPick != null) idx = packPick.index(); }
                    mFiles.setSelectedIndex(idx);
                }
                mInstall.setEnabled(ok && !mBusy);
                updatePackState();
                mStatus.setText(er != null ? I18n.t("Помилка: ") + er
                        : ok && h.kind().isPack() ? packPickStatus(packPick, tgt, rr.size())
                        : ok ? " "
                        : h.kind().isPack() ? I18n.t("Для цієї збірки немає файлів, які можна встановити")
                        : I18n.t("Немає файлів для ") + tgt + I18n.t(" — не підтримує цю збірку"));
            });
        }, "mod-files").start();
    }

    /** Рядок стану після вибору найближчої версії модпака. */
    String packPickStatus(Modpacks.Pick pk, String mc, int total) {
        if (pk == null || pk.nearestMc() == null) return " ";
        if (pk.exact()) return I18n.t("Обрано версію для Minecraft ") + mc;
        String w = I18n.t("Увага: ") + I18n.t("на ") + mc + I18n.t(" немає, є на ") + pk.nearestMc() + I18n.t(" (найближча)");
        if (pk.newestMc() != null && !pk.newestMc().equals(pk.nearestMc())) w += I18n.t(", найновіша — ") + pk.newestMc();
        return w;
    }

    /** Попередження, якщо версія гри обраного файлу модпака не збігається з версією гри збірки. Порожній рядок — усе гаразд. */
    String packVersionNote(Prof p, ModApi.ModFile f) {
        if (f.mcs().isEmpty() || f.mcs().contains(p.mc())) return "";
        List<ModApi.ModFile> all = new ArrayList<>();
        for (int i = 0; i < mFiles.getItemCount(); i++) all.add(mFiles.getItemAt(i));
        Modpacks.Pick pk = Modpacks.pick(all, p.mc(), p.loader());
        String have = String.join(", ", f.mcs());
        StringBuilder sb = new StringBuilder(I18n.t("На версію Minecraft ") + p.mc() + I18n.t(" (твоя збірка) такої збірки модів немає.\n"));
        sb.append(I18n.t("Обрана версія зроблена для Minecraft ")).append(have).append(".");
        if (pk != null && pk.newestMc() != null && !f.mcs().contains(pk.newestMc()))
            sb.append(I18n.t(" Найновіша, що є: ")).append(pk.newestMc()).append(".");
        sb.append(I18n.t("\nМоди можуть не запрацювати."));
        return sb.toString();
    }

    /** Збірка модів із пошуку: ставиться в обрану зверху існуючу збірку, нової збірки не створює. */
    void installPack(ModApi.Hit h, ModApi.ModFile f) {
        if (mBusy || busy) return;
        if (isPackInstalled(curProf(), h)) { updatePackState(); return; }
        Prof p = curProf();
        if (p == null) { msg(I18n.t("Спочатку створи збірку у вкладці «Бібліотека» або обери наявну — збірка модів встановлюється в неї.")); return; }
        if (p.loader().equalsIgnoreCase("Vanilla")) {
            msg(I18n.t("Чиста збірка не підтримує моди.\nОбери збірку на основі Fabric / Quilt / Forge / NeoForge."));
            return;
        }
        if (f.url() == null) {
            browse(h.pageUrl());
            msg(I18n.t("Автор заборонив завантаження сторонніми запускачами — візьми збірку на сайті."));
            return;
        }
        if (!f.loaders().isEmpty() && !Modpacks.loaderOk(f, p.loader())) {
            msg(I18n.t("Ця версія збірки модів зроблена для: ") + String.join(", ", f.loaders())
                    + I18n.t(", а твоя збірка «") + p.name() + I18n.t("» — на ") + p.loader()
                    + I18n.t(".\nМоди не запрацюють. Обери іншу версію або іншу збірку."));
            return;
        }
        String warn = packVersionNote(p, f);
        String body = I18n.t("Усі моди та налаштування збірки «") + h.title() + I18n.t("» буде встановлено у твою збірку «") + p.name()
                + I18n.t("» (") + p.loader() + " " + p.mc() + ")."
                + (warn.isEmpty() ? "" : "\n\n" + I18n.t("Увага: ") + warn);
        if (!Dlg.show(this, h.title(), Dlg.text(body), I18n.t("Встановити"), I18n.t("Скасувати"))) return;
        installPackInto(p, h, f);
    }

    void modInstall() {
        Prof p = curProf();
        ModApi.Hit h = mList.getSelectedValue();
        Object fo = mFiles.getSelectedItem();
        if (h != null && h.kind().isPack() && fo instanceof ModApi.ModFile) { installPack(h, (ModApi.ModFile) fo); return; }
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

    /** Одна кнопка імпорту: у вікні обирається, що саме переносимо — моди, набори ресурсів чи шейдери. */
    void importChooser() { importChooser(curProf()); }

    record ImpChoice(String label, ModApi.Kind kind) {
        @Override public String toString() { return label; }
    }

    /** «Імпорт» у вказану збірку: файли (моди / набори ресурсів / шейдери) або цілу збірку модів. */
    void importChooser(Prof p) {
        if (p == null) { msg(I18n.t("Спочатку створи збірку.")); return; }
        if (mBusy || busy) { msg(I18n.t("Зачекай, поки завершиться завантаження.")); return; }
        Combo<ImpChoice> what = new Combo<>();
        what.addItem(new ImpChoice(I18n.t("Моди"), ModApi.Kind.MOD));
        what.addItem(new ImpChoice(I18n.t("Набори ресурсів"), ModApi.Kind.PACK));
        what.addItem(new ImpChoice(I18n.t("Шейдери"), ModApi.Kind.SHADER));
        what.addItem(new ImpChoice(I18n.t("Збірки модів"), null));
        what.setPreferredSize(new Dimension(Theme.px(340), Theme.px(44)));
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        JLabel lb = label(I18n.t("Що імпортувати у збірку «") + p.name() + "»?", 13, true, Theme.MUTED);
        JLabel hint = label(" ", 12, false, Theme.MUTED);
        Runnable upd = () -> {
            ImpChoice c = (ImpChoice) what.getSelectedItem();
            hint.setText(c == null || c.kind() != null
                    ? I18n.t("Обери папку іншої збірки або окремі файли — підходящі буде перенесено.")
                    : I18n.t("Файл збірки модів, завантажений на диск: усі моди й налаштування з нього встановляться в цю збірку."));
        };
        what.addActionListener(e -> upd.run());
        upd.run();
        lb.setAlignmentX(Component.LEFT_ALIGNMENT);
        what.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(lb);
        box.add(Box.createVerticalStrut(8));
        box.add(what);
        box.add(Box.createVerticalStrut(8));
        box.add(hint);
        if (!Dlg.show(this, I18n.t("Імпорт у збірку"), box, I18n.t("Далі"), I18n.t("Скасувати"))) return;
        ImpChoice c = (ImpChoice) what.getSelectedItem();
        if (c == null) return;
        if (c.kind() == null) importModpackFile(p); else importMods(p, c.kind());
    }

    void importMods(ModApi.Kind ik) { importMods(curProf(), ik); }

    void importMods(Prof p, ModApi.Kind ik) {
        if (p == null) { msg(I18n.t("Спочатку створи збірку.")); return; }
        if (mBusy || busy) { msg(I18n.t("Зачекай, поки завершиться завантаження.")); return; }
        final String ext = ik.isMod() ? ".jar" : ".zip";
        File[] picked = FilePick.show(this, ik.isMod()
                        ? I18n.t("Обери папку збірки (або папку mods) чи окремі .jar")
                        : I18n.t("Обери папку збірки (або папку ") + ik.folder + I18n.t(") чи окремі .zip"),
                JFileChooser.FILES_AND_DIRECTORIES, true, null);
        if (picked == null) return;
        List<Path> jars = new ArrayList<>();
        for (File sel : picked) {
            Path sp = sel.toPath();
            if (Files.isDirectory(sp)) {
                Path md = Files.isDirectory(sp.resolve(ik.folder)) ? sp.resolve(ik.folder) : sp;
                try (var st = Files.list(md)) {
                    st.filter(x -> Files.isRegularFile(x) && x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext))
                      .sorted().forEach(jars::add);
                } catch (IOException ex) { log(I18n.t("Помилка: ") + ex); }
            } else if (sp.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)) jars.add(sp);
        }
        if (jars.isEmpty()) { msg(I18n.t("Файлів ") + ext + I18n.t(" не знайдено.")); return; }
        Path dir = instDir(p.name()).resolve(ik.folder);
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
                    SwingUtilities.invokeLater(() -> { mBar.set(frac); libProgress(I18n.t("Імпорт у «") + p.name() + "»…", frac); });
                    rows.add(api.importFile(ik, jars.get(i), p.mc(), p.loader(), dir, seen,
                            t -> SwingUtilities.invokeLater(() -> { mStatus.setText(t); libProgress(t, (double) 0.5); }), () -> mCancelled));
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
                libProgressEnd();
                showImportReport(p, rows, jars.size(), ik);
            });
        }, "mod-import").start();
    }

    void showImportReport(Prof p, List<ModApi.ImportRow> rows, int total, ModApi.Kind ik) {
        StringBuilder bad = new StringBuilder();
        int ok = 0;
        for (ModApi.ImportRow r : rows) {
            if (r.ok()) ok++;
            else bad.append("• ").append(r.file()).append(r.title().equals(r.file()) ? "" : " (" + r.title() + ")")
                    .append(" — ").append(r.reason()).append("\n");
        }
        int notDone = total - rows.size();
        if (notDone > 0) bad.append("• ").append(I18n.t("не оброблено через скасування: ")).append(notDone).append("\n");
        String head = I18n.t("Встановлено: ") + ok + " / " + total + "  (" + target(ik, p) + ")";
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
        Dlg.show(this, importTitle(ik), sp, I18n.t("Закрити"), null);
    }

    final AtomicInteger iSeq = new AtomicInteger();

    void refreshInstalled() {
        iModel.clear();
        if (kindChosen0() && curKind().isPack()) { refreshPackList(); return; }
        Prof p = curProf();
        if (p == null) return;
        ModApi.Kind k = curKind();
        Path d = instDir(p.name()).resolve(k.folder);
        iconDirKey = d.toString();
        if (!Files.isDirectory(d)) return;
        List<String> names = new ArrayList<>();
        try (var st = Files.list(d)) {
            st.filter(x -> k.isWorld() ? Files.isDirectory(x) : Files.isRegularFile(x)).map(x -> x.getFileName().toString())
                    .filter(k::matches)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(names::add);
        } catch (IOException ignored) { }
        iAll.clear();
        iAll.addAll(names);
        applyInstalledFilter();
        loadInstalledIcons(d, k, names);
    }

    final List<String> iAll = new ArrayList<>();

    /** Пошук у вкладці редагування: відфільтровує встановлені файли (моди, ресурси, шейдери, мапи) за назвою. */
    void applyInstalledFilter() {
        String q = mQuery.getText().trim().toLowerCase(Locale.ROOT);
        String sel = iList.getSelectedValue();
        iModel.clear();
        List<String> out = new ArrayList<>();
        for (String n : iAll) if (q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q)) out.add(n);
        if (!out.isEmpty()) iModel.addAll(out);
        if (!iModel.isEmpty()) {
            if (sel != null && out.contains(sel)) iList.setSelectedValue(sel, true); else iList.setSelectedIndex(0);
        } else onInstalledSelected();
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
            } catch (Throwable ignored) { }
            for (int attempt = 0; attempt < 3 && !f2p.isEmpty(); attempt++) {
                try {
                    Set<String> pids = new HashSet<>(f2p.values());
                    pids.removeAll(urls.keySet());
                    if (pids.isEmpty()) break;
                    urls.putAll(api.iconUrls(pids));
                    break;
                } catch (Throwable ignored) {
                    try { Thread.sleep(1500L * (attempt + 1)); } catch (InterruptedException ie) { break; }
                }
            }
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
                        BufferedImage im = null;
                        for (int attempt = 0; attempt < 3 && im == null; attempt++) {
                            im = iconRaw.get(u);
                            if (im == null) {
                                im = fetchProjectIcon(u);
                                if (im != null) iconRaw.put(u, im);
                                else try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ie) { break; }
                            }
                        }
                        if (im != null && im != NO_ICON) tok = u;
                    }
                    if (tok == null) {
                        Path f = d.resolve(b);
                        if (!Files.exists(f)) f = d.resolve(b + ".disabled");
                        BufferedImage em = ModApi.embeddedIcon(f, k);
                        if (em != null) {
                            tok = "file:" + key + "|" + b;
                            iconRaw.put(tok, roundIcon(em, 64));
                        }
                    }
                    if (tok != null) prebuildIcon(tok, iconRaw.get(tok));
                    else tok = "def";
                    fileIcon.put(key + "|" + b, tok);
                    repaintInstalledSoon();
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
        if (!Dlg.show(this, curKind().removeBtn, Dlg.text(curKind().isWorld()
                ? I18n.t("Видалити мапу ") + n + I18n.t("? Усі збереження в ній буде втрачено назавжди.")
                : I18n.t("Видалити ") + n + "?"), I18n.t("Видалити"), I18n.t("Скасувати"))) return;
        try {
            Path target = ModApi.child(d, n);
            if (curKind().isWorld()) ModApi.deleteTree(target); else Files.deleteIfExists(target);
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
    final ExecutorService iconPool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "mod-icons");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    Icon modIcon(String url, int size, JComponent target) {
        if (url == null || url.isEmpty()) return iconObjs.computeIfAbsent("@" + size, k -> new ImgIcon(null, size));
        if (url.equals("def")) return iconObjs.computeIfAbsent("@def" + size, k -> ImgIcon.fallback(size));
        BufferedImage im = iconRaw.get(url);
        if (im == null) {
            loadIcon(url, target);
            return iconObjs.computeIfAbsent("@" + size, k -> new ImgIcon(null, size));
        }
        if (im == NO_ICON) return iconObjs.computeIfAbsent("@def" + size, k -> ImgIcon.fallback(size));
        return iconObjs.computeIfAbsent(url + "@" + size, k -> new ImgIcon(im, size));
    }

    final Map<String, Integer> iconFails = new ConcurrentHashMap<>();
    final Map<String, Long> iconRetryAt = new ConcurrentHashMap<>();

    /** Чи вміє Java читати WebP (стандартно — ні; тоді одразу беремо оригінал іконки, а не мініатюру _96.webp). */
    static final boolean WEBP_OK = javax.imageio.ImageIO.getImageReadersByFormatName("webp").hasNext();
    static final Pattern WEBP_THUMB = Pattern.compile(".*_\\d+\\.webp(\\?.*)?$");

    /** Одна й та сама іконка вантажиться лише раз, навіть якщо її одночасно просять кілька потоків. */
    final Map<String, CompletableFuture<BufferedImage>> iconFlight = new ConcurrentHashMap<>();

    /**
     * Повертає іконку; NO_ICON — іконки точно немає (404 / не зображення); null — тимчасова помилка мережі, варто повторити пізніше.
     */
    BufferedImage fetchProjectIcon(String url) {
        CompletableFuture<BufferedImage> mine = new CompletableFuture<>();
        CompletableFuture<BufferedImage> other = iconFlight.putIfAbsent(url, mine);
        if (other != null) {
            try { return other.get(40, TimeUnit.SECONDS); } catch (Throwable e) { return null; }
        }
        BufferedImage res = null;
        try {
            res = fetchProjectIconCached(url);
            return res;
        } finally {
            mine.complete(res);
            iconFlight.remove(url);
        }
    }

    private BufferedImage fetchProjectIconCached(String url) {
        Path cf = null;
        try {
            cf = api.cacheDir("icons").resolve(ModApi.sha1s(url) + ".png");
            if (Files.isRegularFile(cf)) {
                BufferedImage c = javax.imageio.ImageIO.read(cf.toFile());
                if (c != null) return c;
            }
        } catch (Throwable ignored) { }
        BufferedImage got = fetchProjectIconNet(url);
        if (got != null && got != NO_ICON && cf != null) {
            try {
                Path tmp = cf.resolveSibling(cf.getFileName() + ".tmp");
                javax.imageio.ImageIO.write(got, "png", tmp.toFile());
                Files.move(tmp, cf, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable ignored) { }
        }
        return got;
    }

    BufferedImage fetchProjectIconNet(String url) {
        try {
            boolean thumb = WEBP_THUMB.matcher(url).matches();
            BufferedImage src = thumb && !WEBP_OK ? null : fetchImage(url);
            if (src == null && thumb) {
                String base = url.replaceAll("_\\d+\\.webp(\\?.*)?$", "");
                for (String ext : new String[]{".png", ".jpg", ".jpeg", ".gif"}) {
                    src = fetchImage(base + ext);
                    if (src != null) break;
                }
            }
            return src == null ? NO_ICON : roundIcon(src, 64);
        } catch (IOException e) {
            return null;
        } catch (Throwable e) {
            return NO_ICON;
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
                if (n < 6) {
                    iconRetryAt.put(url, System.currentTimeMillis() + 3_000L * n);
                    iconBusy.remove(url);
                    SwingUtilities.invokeLater(() -> {
                        javax.swing.Timer t = new javax.swing.Timer((int) (3_000L * n) + 200, ev -> target.repaint());
                        t.setRepeats(false);
                        t.start();
                    });
                    return;
                }
                res = NO_ICON;
            }
            iconRaw.put(url, res);
            prebuildIcon(url, res);
            iconBusy.remove(url);
            SwingUtilities.invokeLater(target::repaint);
        });
    }

    /** null — картинки там немає (404 / не зображення); IOException — тимчасова помилка (мережа, 429, 5xx). */
    BufferedImage fetchImage(String url) throws IOException {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "PaxLauncher/1.0")
                .timeout(Duration.ofSeconds(15)).build();
        HttpResponse<byte[]> r;
        try {
            r = engine.sendRetry(rq, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        int sc = r.statusCode();
        if (sc == 408 || sc == 429 || sc >= 500) throw new IOException("HTTP " + sc);
        if (sc != 200) return null;
        byte[] body = r.body();
        if (body.length > 4_000_000) return null;
        return ModApi.readImage(body);
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
    final JLabel gEmpty = label(I18n.t("Знятків ще немає"), 20, true, Theme.MUTED);
    final Btn gMore = new Btn(I18n.t("Показати ще"), Btn.Kind.SECONDARY);
    List<Shot> gShots = new ArrayList<>();
    int gShown = SHOT_PAGE;
    boolean gFilling;
    final AtomicInteger gSeq = new AtomicInteger();
    final ExecutorService shotPool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "shot-thumbs");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
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
        gEmpty.setHorizontalAlignment(SwingConstants.CENTER);
        gEmpty.setBorder(new EmptyBorder(80, 0, 0, 0));
        gEmpty.setVisible(false);
        body.row(gEmpty, 0);
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
        gEmpty.setVisible(list.isEmpty());
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
    final FadePanel rootPanel = new FadePanel(rootCards, 520, 14, true);
    final Accounts accounts = new Accounts(root.resolve("accounts.properties"), engine.http);
    final JLabel accName = label("—", 14, true, Theme.TEXT), accType = label(" ", 12, false, Theme.MUTED);
    final Btn contBtn = new Btn(I18n.t("Продовжити"), Btn.Kind.PRIMARY);
    Btn loginBack, loginMs, loginOff;
    Component loginOffGap;
    final JPanel savedBox = new JPanel();
    final JLabel savedHdr = label(I18n.t("Ліцензійні обліківки"), 13, true, Theme.MUTED);

    static Btn big(Btn b) {
        b.setFont(Theme.bfont(Font.BOLD, 15));
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
        loginMs = ms;
        loginOff = off;
        loginOffGap = Box.createVerticalStrut(10);
        col.add(ms);
        col.add(loginOffGap);
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

        ms.addActionListener(e -> { if (accounts.msAccounts().isEmpty()) loginMicrosoft(); else licenseDialog(); });
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
        refreshPlayText();
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
        bg.setSide(true);
        rootPanel.change(() -> {
            pages.mute = true;
            try { showPage("play"); } finally { pages.mute = false; }
            rootCards.show(rootPanel, "app");
        });
    }

    void showLogin() {
        bg.setSide(false);
        rootPanel.change(() -> {
            refreshSavedAccounts();
            rootCards.show(rootPanel, "login");
        });
    }

    void refreshSavedAccounts() {
        if (loginBack != null) loginBack.setVisible(accounts.current() != null);
        List<Accounts.Acc> list = accounts.msAccounts();
        if (loginMs != null) loginMs.setText(list.isEmpty() ? I18n.t("Увійти через Microsoft") : I18n.t("Ліцензійні обліківки"));
        savedBox.removeAll();
        savedBox.setVisible(false);
        savedHdr.setVisible(false);
        for (Accounts.Acc acc : list) {
            if (accounts.headCached(acc.uuid()) == null) {
                new Thread(() -> { accounts.headFetch(acc.uuid()); }, "head-" + acc.name()).start();
            }
        }
    }

    void licenseDialog() {
        DefaultListModel<Accounts.Acc> model = new DefaultListModel<>();
        for (Accounts.Acc a : accounts.msAccounts()) model.addElement(a);
        JList<Accounts.Acc> list = new JList<>(model);
        list.setBackground(Theme.CARD2);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(36);
        Accounts.Acc cur = accounts.current();
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, false);
                Accounts.Acc a = (Accounts.Acc) v;
                Accounts.Acc c = accounts.current();
                boolean active = c != null && c.type().equals("ms") && c.uuid().equals(a.uuid());
                setText(a.name() + (active ? "   ·  " + I18n.t("Поточна обліківка") : ""));
                setBackground(sel ? Theme.SEL : Theme.CARD2);
                setForeground(Theme.TEXT);
                setFont(Theme.font(Font.PLAIN, 14));
                setBorder(new EmptyBorder(6, 14, 6, 14));
                return this;
            }
        });
        boolean picked = false;
        for (int k = 0; k < model.size(); k++)
            if (cur != null && cur.type().equals("ms") && model.get(k).uuid().equals(cur.uuid())) { list.setSelectedIndex(k); picked = true; }
        if (!picked && !model.isEmpty()) list.setSelectedIndex(0);

        JScrollPane sp = new JScrollPane(list); Smooth.install(sp);
        sp.setBorder(null);
        sp.getViewport().setBackground(Theme.CARD2);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        sp.setPreferredSize(new Dimension(380, 170));

        Btn addB = small(new Btn(I18n.t("Додати обліківку"), Btn.Kind.PRIMARY));
        Btn delB = small(new Btn(I18n.t("Видалити"), Btn.Kind.SECONDARY));
        JLabel err = label(" ", 12, false, Theme.MUTED);
        boolean[] addReq = {false};
        addB.addActionListener(e -> { addReq[0] = true; Window w = SwingUtilities.getWindowAncestor(addB); if (w != null) w.dispose(); });
        delB.addActionListener(e -> {
            Accounts.Acc a = list.getSelectedValue();
            if (a == null) { err.setText(I18n.t("Спочатку обери обліківку у списку")); return; }
            accounts.removeMs(a.uuid());
            model.removeElement(a);
            err.setText(I18n.t("Видалено: ") + a.name());
            if (accounts.current() == null) { Theme.headImg = null; updateAccountUi(); }
            if (!model.isEmpty()) list.setSelectedIndex(0);
        });

        JPanel bb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bb.setOpaque(false);
        bb.add(addB);
        bb.add(delB);
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(bb, BorderLayout.NORTH);
        south.add(err, BorderLayout.SOUTH);

        JPanel pn = new JPanel(new BorderLayout(0, 12));
        pn.setOpaque(false);
        pn.add(sp, BorderLayout.CENTER);
        pn.add(south, BorderLayout.SOUTH);

        boolean ok = Dlg.show(this, I18n.t("Ліцензійні обліківки"), pn, I18n.t("Грати з цією обліківкою"), I18n.t("Закрити"));
        refreshSavedAccounts();
        if (addReq[0]) { loginMicrosoft(); return; }
        if (!ok) return;
        Accounts.Acc sel = list.getSelectedValue();
        if (sel == null) { msg(I18n.t("Обери обліківку у списку або додай нову.")); return; }
        useSaved(sel);
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
        saveVersion();
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

    boolean ensureJava(int n, Engine.Progress pg) throws Exception {
        boolean[] yes = {false};
        SwingUtilities.invokeAndWait(() -> yes[0] = Dlg.show(this, I18n.t("Потрібна Java ") + n,
                Dlg.text(I18n.t("Для цієї версії гри потрібна Java ") + n + I18n.t(", але її не знайдено.\nЗапускач може сам завантажити потрібну Java.")),
                I18n.t("Завантажити Java"), I18n.t("Відмовитися від завантаження")));
        if (!yes[0]) return false;
        Path exe = engine.downloadJava(n, pg);
        log(I18n.t("Java ") + n + I18n.t(" готова: ") + exe);
        return true;
    }

    String installLoaderEnsured(String type, String mc, String ver, String java, Engine.Progress pg) throws Exception {
        try {
            return engine.installLoader(type, mc, ver, java, pg);
        } catch (Engine.JavaMissing jm) {
            if (!ensureJava(jm.major, pg)) throw new Engine.Cancelled();
            return engine.installLoader(type, mc, ver, java, pg);
        }
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

    void firstLaunchLang(Path gd, String mc) {
        try {
            Path opt = gd.resolve("options.txt");
            if (Files.exists(opt)) return;
            java.util.regex.Matcher m = Pattern.compile("^1\\.(\\d+)").matcher(mc == null ? "" : mc);
            boolean legacy = m.find() && Integer.parseInt(m.group(1)) < 11;
            String code = legacy ? "uk_UA" : "uk_ua";
            Files.writeString(opt, "lang:" + code + "\n", StandardCharsets.UTF_8);
            log(I18n.t("Перший запуск: мову гри встановлено ") + code);
        } catch (Exception ignored) { }
    }

    void play() {
        final String jt = joinTarget;
        joinTarget = null;
        final String jc = pendingJoin;
        pendingJoin = null;
        final String hw = hostWorld;
        final int hport = hostLanPort;
        Accounts.Acc acc = accounts.current();
        if (acc == null) { msg(I18n.t("Спочатку обери обліківку.")); showLogin(); return; }
        Object o = versions.getSelectedItem();
        if (!(o instanceof VItem)) { msg(I18n.t("Спочатку обери версію гри.")); return; }
        VItem it = (VItem) o;
        if (running.contains(accKey(acc))) return;
        saveVersion();
        setBusy(true);
        bar.set(0);
        engine.stop = false;
        cancelUi(true, cancelBtn, pauseBtn);
        // хост бачить лаунчер (там код і стан сесії); гість ховає його за налаштуванням, як зазвичай
        boolean hide = hideC.isSelected() && hw == null;
        Engine.Progress pg = (t, f) -> SwingUtilities.invokeLater(() -> { status.setText(t); bar.set(f); });

        final boolean[] started = {false}, ended = {false};
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
                if (hw != null && hport > 0 && lan != null) {
                    String mcj = Json.string(v.get("_jar")) != null ? Json.string(v.get("_jar")) : it.id();
                    try {
                        Path ag = LanAgent.ensure(root);
                        Path flag = flagPath();
                        Files.createDirectories(flag.getParent());
                        Files.deleteIfExists(flag);
                        cmd = new ArrayList<>(cmd);
                        Path cfgf = flag.resolveSibling("lan.cfg");
                        Files.writeString(cfgf, hport + "\n" + gd.resolve("saves") + "\n" + hw + "\n" + flag + "\n"
                                + (cfg.getb("allowOffline", false) ? "1" : "0") + "\n", java.nio.charset.StandardCharsets.UTF_8);
                        cmd.add(1, "-javaagent:" + ag + "=" + cfgf);
                        hostManualJoin = !Invite.modern(mcj);
                        if (!hostManualJoin) { cmd.add("--quickPlaySingleplayer"); cmd.add(hw); }
                        log(I18n.t("Світ «") + hw + I18n.t("» відкриється для друзів, щойно ти в нього зайдеш.")
                                + (hostManualJoin ? I18n.t(" (ця версія не заходить у світ сама — обери його в «Одиночна гра»)") : ""));
                    } catch (Throwable ex) {
                        log(I18n.t("Авто-відкриття світу недоступне: ") + ex.getMessage());
                    }
                }
                if (jt != null) {
                    String mcj = Json.string(v.get("_jar")) != null ? Json.string(v.get("_jar")) : it.id();
                    cmd = new ArrayList<>(cmd);
                    cmd.addAll(Invite.joinArgs(mcj, jt));
                    log(I18n.t("Автовхід на сервер друга: ") + jt);
                }
                firstLaunchLang(gd, Json.string(v.get("_jar")) != null ? Json.string(v.get("_jar")) : it.id());
                String line = String.join(" ", cmd);
                if (a.token().length() > 3) line = line.replace(a.token(), "***");
                log(I18n.t("Запуск: ") + (line.length() > 500 ? line.substring(0, 500) + " …" : line));
                Process pr = new ProcessBuilder(cmd).directory(gd.toFile()).redirectErrorStream(true).start();
                if (jt != null && jc != null) {
                    joinProc = pr;
                    joinCode = jc;
                    SwingUtilities.invokeLater(this::refreshFriendsCard);
                    startJoinMonitor(jc, jt);
                }
                started[0] = true;
                running.add(accKey(acc));
                if (it.profile() == null) noteSession(it.id(), gd);
                pg.update(I18n.t("Гра запущена. Приємної гри!"), 1);
                games.incrementAndGet();
                inGame = true;
                SwingUtilities.invokeLater(() -> { setBusy(false); cancelUi(false, cancelBtn, pauseBtn); });
                String mcv = Json.string(v.get("_jar")) != null ? Json.string(v.get("_jar")) : it.id();
                String ld = Engine.detectLoader(it.id());
                rpc.update("Minecraft " + mcv,
                        ld + (it.profile() != null ? I18n.t(" · збірка «") + it.profile() + "»" : ""),
                        System.currentTimeMillis() / 1000);
                if (hide) SwingUtilities.invokeLater(() -> { setVisible(false); trimMemory(); });
                try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                    String ln;
                    while ((ln = r.readLine()) != null) { log(ln); inviteHook(ln); }
                }
                int code = pr.waitFor();
                if (lan != null && jt == null) {
                    // сесія живе, доки відкрита гра хоста: закрив Minecraft — сесія неактивна
                    SwingUtilities.invokeLater(() -> {
                        if (lan != null) {
                            hostStop();
                            status.setText(I18n.t("Сесію завершено: гру закрито"));
                        }
                    });
                }
                if (jt != null) {
                    try {
                        String[] hp = jt.split(":");
                        if (LanProxy.probe(hp[0], Integer.parseInt(hp[1])) == 2)
                            for (Path d : allGameDirs()) Nbt.removeServer(d.resolve("servers.dat"), "Гра з друзями");
                    } catch (Exception ignored) { }
                }
                if (jt != null && joinProc == pr) {
                    joinProc = null;
                    joinCode = null;
                    SwingUtilities.invokeLater(this::refreshFriendsCard);
                }
                log(I18n.t("Гра завершилась з кодом ") + code);
                inGame = games.decrementAndGet() > 0;
                running.remove(accKey(acc));
                ended[0] = true;
                if (!inGame) presenceIdle();
                if (!busy) pg.update(code == 0 ? " " : I18n.t("Гра завершилась з помилкою (код ") + code + I18n.t("), дивись Консоль"), 0);
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
                if (started[0] && !ended[0]) { inGame = games.decrementAndGet() > 0; running.remove(accKey(acc)); }
                final boolean st = started[0];
                SwingUtilities.invokeLater(() -> {
                    setVisible(true);
                    if (st) refreshPlayText();
                    else { setBusy(false); cancelUi(false, cancelBtn, pauseBtn); }
                });
            }
        }, "launch").start();
    }
}
