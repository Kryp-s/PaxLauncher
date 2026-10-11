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

final class ServerAds extends JComponent {
    record Srv(String name, String ip, String version, String supported, String desc, String discord, BufferedImage face) { }

    static final int H = 118, ROTATE_MS = 40_000;
    private static final String PAX_B64 = String.join("",
            "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");
    private static final String CIV_B64 = String.join("",
            "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",
            "AAAABJRU5ErkJggg==");

    private final List<Srv> list = new ArrayList<>();
    private final Consumer<String> browse;
    private final Fx fx = new Fx(this);
    private int cur, prev = -1;
    private float fade = 1f;
    private final javax.swing.Timer fadeT, rotT;

    ServerAds(Consumer<String> browse) {
        this.browse = browse;
        list.add(new Srv("PaxPolit", null, "1.21.4", I18n.t("1.16.5 і новіші"),
                I18n.t("PaxPolit - це український політичний сервер в майнкрафті: танки, зброя, політика та багато чого іншого. Приєднуйся до нас в дискорді."),
                "https://discord.gg/g9DveTHQQV", face(CIV_B64)));
        list.add(new Srv("PaxCiv", null, null, null,
                I18n.t("PaxCiv - це підсервер PaxPolit, який існує для проведення цивілізаційних експериментів типу Ish'a тощо"),
                "https://discord.gg/4U9rBAfSbh", face(PAX_B64)));

        setPreferredSize(new Dimension(250, H));
        setMinimumSize(new Dimension(180, H));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fadeT = new javax.swing.Timer(15, e -> {
            fade += 0.045f;
            if (fade >= 1f) { fade = 1f; prev = -1; ((javax.swing.Timer) e.getSource()).stop(); }
            repaint();
        });
        rotT = new javax.swing.Timer(ROTATE_MS, e -> next());
        rotT.setRepeats(true);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) { if (list.size() > 1) rotT.start(); }
            else { rotT.stop(); fadeT.stop(); fade = 1f; prev = -1; }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); }
            @Override public void mouseClicked(MouseEvent e) {
                rotT.restart();
                details(list.get(cur));
            }
        });
    }

    void add(Srv s) {
        list.add(s);
        if (list.size() > 1 && !rotT.isRunning() && isShowing()) rotT.start();
        repaint();
    }

    private void next() {
        if (list.size() < 2) return;
        prev = cur;
        cur = (cur + 1) % list.size();
        fade = 0f;
        fadeT.start();
    }

    static BufferedImage face(String b64) {
        try {
            BufferedImage im = javax.imageio.ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
            if (im == null) return null;
            int sz = 116;
            BufferedImage out = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            Theme.aa(g);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(Color.WHITE);
            g.fillRoundRect(0, 0, sz, sz, 28, 28);
            g.setComposite(AlphaComposite.SrcIn);
            g.drawImage(im, 0, 0, sz, sz, null);
            g.dispose();
            return out;
        } catch (Exception e) { return null; }
    }

    private void drawSrv(Graphics2D g, Srv s, int w, int h) {
        int pad = 14, av = 58, ay = (h - av) / 2;
        if (s.face() != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(s.face(), pad, ay, av, av, null);
        } else {
            g.setColor(Theme.ACC1);
            g.fillRoundRect(pad, ay, av, av, 14, 14);
        }
        int tx = pad + av + 12, tw = w - tx - pad;
        g.setFont(Theme.font(Font.PLAIN, 12));
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = ArtCard.wrap(s.desc(), fm, tw, 2);
        int blockH = 50 + (lines.size() - 1) * fm.getHeight() + 4;
        int y0 = (h - blockH) / 2;
        g.setFont(Theme.font(Font.BOLD, 15));
        g.setColor(Theme.TEXT);
        g.drawString(s.name(), tx, y0 + 12);
        g.setFont(Theme.font(Font.PLAIN, 12));
        String sub = (s.version() != null ? I18n.t("Версія ") + s.version() + " · " : "") + "IP: " + (s.ip() == null ? "—" : s.ip());
        g.setColor(s.ip() == null ? new Color(0xE0B04A) : Theme.ACC2);
        g.drawString(sub, tx, y0 + 30);
        g.setColor(Theme.SUB);
        int ly = y0 + 50;
        for (String line : lines) {
            g.drawString(line, tx, ly);
            ly += fm.getHeight();
        }
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        if (w <= 0) { g.dispose(); return; }
        g.setColor(Theme.CARD);
        g.fillRoundRect(0, 0, w, h, 16, 16);
        if (fx.h() > 0) {
            g.setColor(Theme.ov(Math.round(12 * fx.h())));
            g.fillRoundRect(0, 0, w, h, 16, 16);
            g.setColor(Theme.acc(Math.round(170 * fx.h())));
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(1, 1, w - 3, h - 3, 16, 16);
        }
        boolean fading = prev >= 0 && fade < 1f;
        if (fading) {
            g.setComposite(AlphaComposite.SrcOver.derive(1f - fade));
            drawSrv(g, list.get(prev), w, h);
        }
        g.setComposite(AlphaComposite.SrcOver.derive(fading ? fade : 1f));
        drawSrv(g, list.get(cur), w, h);
        g.setComposite(AlphaComposite.SrcOver);
        if (list.size() > 1) {
            int n = list.size(), x = w - 14 - (n * 10 - 4), y = h - 12;
            for (int i = 0; i < n; i++) {
                g.setColor(i == cur ? Theme.ACC2 : Theme.ov(50));
                g.fillOval(x + i * 10, y, 6, 6);
            }
        }
        g.dispose();
    }

    private static String esc(String t) { return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private JLabel kv(String k, String v) {
        return LauncherWindow.label("<html><span style='color:" + Theme.hex(Theme.MUTED) + "'>" + esc(k) + ":</span>&nbsp; " + esc(v) + "</html>", 14, false, Theme.TEXT);
    }

    private void details(Srv s) {
        JPanel c = new JPanel(new BorderLayout(0, 14));
        c.setOpaque(false);

        JPanel top = new JPanel(new BorderLayout(16, 0));
        top.setOpaque(false);
        if (s.face() != null) {
            Image ic = s.face().getScaledInstance(80, 80, Image.SCALE_SMOOTH);
            top.add(new JLabel(new ImageIcon(ic)), BorderLayout.WEST);
        }
        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.add(Box.createVerticalGlue());
        info.add(kv("IP", s.ip() == null ? "—" : s.ip()));
        info.add(Box.createVerticalStrut(6));
        if (s.version() != null) {
            info.add(kv(I18n.t("Версія"), s.version()));
            info.add(Box.createVerticalStrut(6));
        }
        if (s.supported() != null) info.add(kv(I18n.t("Підтримуються"), s.supported()));
        info.add(Box.createVerticalGlue());
        top.add(info, BorderLayout.CENTER);
        c.add(top, BorderLayout.NORTH);

        c.add(Dlg.text(s.desc()), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btns.setOpaque(false);
        if (s.discord() != null) {
            Btn d = LauncherWindow.small(new Btn(I18n.t("Відкрити Discord"), Btn.Kind.PRIMARY));
            d.addActionListener(e -> browse.accept(s.discord()));
            btns.add(d);
        }
        if (s.ip() != null) {
            Btn cp = LauncherWindow.small(new Btn(I18n.t("Копіювати IP"), Btn.Kind.SECONDARY));
            cp.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(s.ip()), null));
            btns.add(cp);
        }
        c.add(btns, BorderLayout.SOUTH);
        Dlg.show(SwingUtilities.getWindowAncestor(this), s.name(), c, I18n.t("Закрити"), null);
    }
}
