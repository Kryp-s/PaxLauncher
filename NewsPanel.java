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

final class NewsPanel extends JPanel {
    record Item(String title, String text, String date, String link, BufferedImage img) { }

    NewsPanel() {
        super(new GridBagLayout());
        setOpaque(false);
        placeholder(I18n.t("Завантаження новин…"));
        Thread t = new Thread(this::load, "news");
        t.setDaemon(true);
        t.start();
    }

    private void placeholder(String s) {
        removeAll();
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.weightx = 1; g.anchor = GridBagConstraints.WEST;
        add(LauncherWindow.label(s, 13, false, Theme.MUTED), g);
        revalidate();
        repaint();
    }

    void show(List<Item> items) {
        removeAll();
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.anchor = GridBagConstraints.NORTH;
        int y = 0;
        for (Item it : items) {
            g.gridy = y++;
            g.insets = new Insets(0, 0, 14, 0);
            add(new NewsCard(it), g);
        }
        revalidate();
        repaint();
    }

    private static BufferedImage scaleTo(BufferedImage im, int w) {
        if (im.getWidth() <= w) return im;
        int h = Math.max(1, im.getHeight() * w / im.getWidth());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(im, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private record Raw(String title, String text, String date, String link, String img) { }

    private void load() {
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
            URI base = URI.create("https://launchercontent.mojang.com");
            String[] feeds = {"https://launchercontent.mojang.com/v2/news.json", "https://launchercontent.mojang.com/news.json"};
            List<Raw> raws = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String feed : feeds) {
                try {
                    HttpResponse<java.io.InputStream> fr = http.send(HttpRequest.newBuilder(URI.create(feed))
                            .header("User-Agent", "PaxLauncher/1.0").timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofInputStream());
                    String body;
                    try (java.io.InputStream fin = fr.body()) {
                        byte[] fb = fin.readNBytes(5_000_001);
                        if (fr.statusCode() != 200 || fb.length > 5_000_000) continue;
                        body = new String(fb, StandardCharsets.UTF_8);
                    }
                    for (Object e : Json.list(Json.map(Json.parse(body)).get("entries"))) {
                        String u = Json.string(Json.get(e, "newsPageImage", "url"));
                        if (u == null) u = Json.string(Json.get(e, "playPageImage", "url"));
                        String title = Json.string(Json.get(e, "title"));
                        if (u == null || title == null || !seen.add(title)) continue;
                        String date = Json.string(Json.get(e, "date"));
                        raws.add(new Raw(title, Json.string(Json.get(e, "text")), date == null ? "" : date,
                                Json.string(Json.get(e, "readMoreLink")), u));
                    }
                } catch (Exception ignored) { }
            }
            raws.sort((x, y) -> y.date().compareTo(x.date()));
            List<Item> out = new ArrayList<>();
            for (Raw r : raws) {
                if (out.size() >= 4) break;
                try {
                    URI iu = base.resolve(r.img());
                    String ih = iu.getHost() == null ? "" : iu.getHost().toLowerCase(java.util.Locale.ROOT);
                    if (!"https".equalsIgnoreCase(iu.getScheme())
                            || !(ih.equals("mojang.com") || ih.endsWith(".mojang.com") || ih.equals("minecraft.net") || ih.endsWith(".minecraft.net"))) continue;
                    HttpResponse<java.io.InputStream> ir = http.send(HttpRequest.newBuilder(iu).header("User-Agent", "PaxLauncher/1.0")
                            .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofInputStream());
                    byte[] bytes;
                    try (java.io.InputStream iin = ir.body()) { bytes = iin.readNBytes(5_000_001); }
                    if (ir.statusCode() != 200 || bytes.length > 5_000_000) continue;
                    BufferedImage im = ModApi.readImage(bytes);
                    if (im == null) continue;
                    out.add(new Item(r.title(), r.text(), r.date().substring(0, Math.min(10, r.date().length())), r.link(), scaleTo(im, 520)));
                } catch (Exception ignored) { }
            }
            if (out.isEmpty()) throw new IOException("no news");
            SwingUtilities.invokeLater(() -> show(out));
        } catch (Throwable ex) {
            SwingUtilities.invokeLater(() -> placeholder(I18n.t("Новини недоступні без інтернету")));
        }
    }

    static final class NewsCard extends JComponent {
        static final int IMG_H = 118;
        private final Item it;
        private final Fx fx = new Fx(this);
        private BufferedImage masked;
        private int maskW;

        NewsCard(Item it) {
            this.it = it;
            setPreferredSize(new Dimension(250, 222));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
                @Override public void mouseExited(MouseEvent e) { fx.hover(false); }
                @Override public void mouseClicked(MouseEvent e) {
                    try {
                        if (it.link() != null && it.link().startsWith("https://") && Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(it.link()));
                    } catch (Exception ignored) { }
                }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            int w = getWidth(), h = getHeight();
            if (w <= 0) { g.dispose(); return; }
            if (masked == null || maskW != w) {
                masked = ArtCard.topRounded(it.img(), w, IMG_H, 0.3);
                maskW = w;
            }
            g.setColor(Theme.CARD);
            g.fillRoundRect(0, 0, w, h, 14, 14);
            g.drawImage(masked, 0, 0, null);
            if (fx.h() > 0) {
                g.setColor(Theme.ov(Math.round(12 * fx.h())));
                g.fillRoundRect(0, 0, w, h, 14, 14);
            }
            int pad = 12;
            g.setFont(Theme.font(Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Theme.TEXT);
            int y = IMG_H + 10 + fm.getAscent();
            for (String line : ArtCard.wrap(it.title(), fm, w - 2 * pad, 2)) {
                g.drawString(line, pad, y);
                y += fm.getHeight();
            }
            if (it.text() != null) {
                g.setFont(Theme.font(Font.PLAIN, 12));
                fm = g.getFontMetrics();
                g.setColor(Theme.MUTED);
                y += 2;
                for (String line : ArtCard.wrap(it.text(), fm, w - 2 * pad, 2)) {
                    g.drawString(line, pad, y);
                    y += fm.getHeight();
                }
            }
            g.setFont(Theme.font(Font.PLAIN, 11));
            g.setColor(Theme.DATE);
            g.drawString(it.date(), pad, h - 10);
            g.dispose();
        }
    }
}
