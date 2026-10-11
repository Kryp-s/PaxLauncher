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

final class Updater {
    static final String VERSION = "1.0.0";
    static final String REPO = "Kryp-s/PaxLauncher";

    record Release(String version, String url, String file, String sha256) {}

    static void start(LauncherWindow win, Settings cfg) {
        if (!cfg.getb("autoupdate", true)) { log("автооновлення вимкнено (autoupdate=false у launcher.properties)"); return; }
        if (!installedBuild()) return;
        log("перевірка оновлень, встановлена версія " + VERSION);
        Thread t = new Thread(() -> {
            Release r = check();
            if (r != null) SwingUtilities.invokeLater(() -> apply(win, r));
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    static boolean installedBuild() {
        try {
            if (!Engine.osName().equals("windows")) { log("пропуск: автооновлення працює лише у Windows"); return false; }
            var cs = PaxLauncher.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) { log("пропуск: невідомий шлях запуску"); return false; }
            Path loc = Path.of(cs.getLocation().toURI());
            String n = loc.getFileName().toString();
            boolean ok = n.endsWith(".jar") && !n.equals("PaxLauncher-lean.jar");
            if (!ok) log("пропуск: це не встановлена збірка (запуск з " + loc + ")");
            return ok;
        } catch (Exception e) {
            log("installedBuild: " + e);
            return false;
        }
    }

    static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    static Release check() {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "PaxLauncher/" + VERSION)
                    .timeout(Duration.ofSeconds(12)).build();
            HttpResponse<String> resp = client().send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) { log("GitHub API: HTTP " + resp.statusCode()); return null; }
            Object j = Json.parse(resp.body());
            String tag = Json.string(Json.get(j, "tag_name"));
            if (tag == null) { log("у відповіді немає tag_name"); return null; }
            String v = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            if (cmp(v, VERSION) <= 0) { log("оновлень немає: встановлено " + VERSION + ", на GitHub " + v); return null; }
            Release best = null;
            for (Object a : Json.list(Json.get(j, "assets"))) {
                String name = Json.string(Json.get(a, "name"));
                String url = Json.string(Json.get(a, "browser_download_url"));
                if (name == null || url == null) continue;
                String low = name.toLowerCase();
                boolean msi = low.endsWith(".msi");
                boolean exe = low.endsWith(".exe") && !low.contains("portable");
                if (!msi && !exe) continue;
                String dg = Json.string(Json.get(a, "digest"));
                String sha = dg != null && dg.startsWith("sha256:") ? dg.substring(7) : null;
                Release r = new Release(v, url, name, sha);
                if (msi) return r;
                if (best == null) best = r;
            }
            if (best == null) log("у релізі " + tag + " немає інсталятора (.msi або .exe)");
            else log("знайдено оновлення " + v + ": " + best.file());
            return best;
        } catch (Throwable e) {
            log("check: " + e);
        }
        return null;
    }

    static void log(String s) {
        try {
            Files.createDirectories(PaxLauncher.ROOT);
            Path lf = PaxLauncher.ROOT.resolve("update.log");
            if (Files.exists(lf) && Files.size(lf) > 100_000) Files.delete(lf);
            Files.writeString(lf,
                    java.time.LocalDateTime.now() + " " + s + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    static int cmp(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        String d = s.replaceAll("\\D.*$", "");
        try { return d.isEmpty() ? 0 : Integer.parseInt(d); } catch (NumberFormatException e) { return 0; }
    }

    static final int COUNTDOWN = 7;

    private static String countdownText(Release r, int sec) {
        return I18n.t("Знайдено нову версію ") + r.version()
                + I18n.t(". Запускач закриється через ") + sec + I18n.t(" с і встановить оновлення.");
    }

    static void apply(LauncherWindow win, Release r) {
        int[] left = {COUNTDOWN};
        boolean[] auto = {false};
        JLabel msg = Dlg.text(countdownText(r, left[0]));
        boolean now = Dlg.show(win, I18n.t("Оновлення"), msg, I18n.t("Оновити зараз"), I18n.t("Пізніше"), d -> {
            javax.swing.Timer tm = new javax.swing.Timer(1000, null);
            tm.addActionListener(e -> {
                if (!d.isDisplayable()) { tm.stop(); return; }
                if (--left[0] <= 0) { tm.stop(); auto[0] = true; d.dispose(); }
                else msg.setText(Dlg.text(countdownText(r, left[0])).getText());
            });
            tm.start();
        });
        if (!now && !auto[0]) { log("користувач відклав оновлення " + r.version()); return; }
        install(win, r);
    }

    private static void install(LauncherWindow win, Release r) {
        Dlg.show(win, I18n.t("Оновлення"),
                Dlg.text(I18n.t("Завантажую оновлення ") + r.version() + I18n.t("… Запускач закриється й встановить його сам.")),
                I18n.t("Гаразд"), null, d -> {
                    Thread t = new Thread(() -> {
                        try {
                            Path f = download(r);
                            log("завантажено " + f);
                            runInstaller(f);
                            log("встановлювач запущено, запускач закривається");
                            System.exit(0);
                        } catch (Exception e) {
                            log("install: " + e);
                            SwingUtilities.invokeLater(() -> {
                                d.dispose();
                                Dlg.show(win, I18n.t("Оновлення"),
                                        Dlg.text(I18n.t("Не вдалося встановити оновлення. Подробиці — у файлі update.log (папка paxlauncher).")),
                                        I18n.t("Гаразд"), null);
                            });
                        }
                    }, "update-install");
                    t.setDaemon(false);
                    t.start();
                });
    }

    static void runInstaller(Path f) throws IOException {
        if (f.getFileName().toString().toLowerCase().endsWith(".msi")) {
            new ProcessBuilder("msiexec", "/i", f.toString(), "/passive", "/norestart").start();
            return;
        }
        String[] flags = {"/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS"};
        List<String> cmd = new ArrayList<>();
        cmd.add(f.toString());
        cmd.addAll(Arrays.asList(flags));
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            log("прямий запуск не вдався (" + e.getMessage() + "), пробую через cmd start");
            List<String> viaShell = new ArrayList<>(List.of("cmd", "/c", "start", ""));
            viaShell.addAll(cmd);
            new ProcessBuilder(viaShell).start();
        }
    }

    static Path download(Release r) throws Exception {
        Path f = Files.createTempDirectory("paxupd").resolve(r.file());
        HttpRequest rq = HttpRequest.newBuilder(URI.create(r.url())).header("User-Agent", "PaxLauncher/" + VERSION)
                .timeout(Duration.ofMinutes(5)).build();
        HttpResponse<Path> resp = client().send(rq, HttpResponse.BodyHandlers.ofFile(f));
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        if (r.sha256() != null) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (!hex.toString().equalsIgnoreCase(r.sha256())) throw new IOException("checksum mismatch");
        }
        return f;
    }
}
