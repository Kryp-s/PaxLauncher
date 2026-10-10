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

/**
 * PaxLauncher — власний запускач Minecraft на Java (Swing).
 * Потрібна Java 17+.
 *
 * Запуск без збірки:   java PaxLauncher.java
 * Збірка в jar:        javac PaxLauncher.java
 *                      jar cfe PaxLauncher.jar PaxLauncher *.class
 */
public class PaxLauncher {
    static final Path ROOT = resolveRoot();

    /** Коренева папка запускача: ~/paxlauncher. Дані зі старої ~/.pax-launcher переносяться автоматично. */
    static Path resolveRoot() {
        Path home = Path.of(System.getProperty("user.home"));
        Path now = home.resolve("paxlauncher"), old = home.resolve(".pax-launcher");
        if (!Files.exists(now) && Files.isDirectory(old)) {
            try {
                Files.move(old, now);
                // шляхи, збережені в налаштуваннях (наприклад ігрова папка), переписуємо на нову адресу
                Path cfgFile = now.resolve("launcher.properties");
                if (Files.isRegularFile(cfgFile)) {
                    Properties pr = new Properties();
                    try (Reader r = Files.newBufferedReader(cfgFile, StandardCharsets.UTF_8)) { pr.load(r); }
                    boolean changed = false;
                    for (String k : pr.stringPropertyNames()) {
                        String v = pr.getProperty(k);
                        if (v.startsWith(old.toString())) {
                            pr.setProperty(k, now.toString() + v.substring(old.toString().length()));
                            changed = true;
                        }
                    }
                    if (changed) try (Writer w = Files.newBufferedWriter(cfgFile, StandardCharsets.UTF_8)) { pr.store(w, "PaxLauncher"); }
                }
            } catch (IOException e) {
                return old; // не вдалося перенести — працюємо зі старою папкою, нічого не губимо
            }
        }
        return now;
    }

    /** Параметри JVM самого запускача: мінімум пам'яті (гра запускається окремо, зі своїми параметрами). */
    static final String[] LEAN_FLAGS = {
        "-XX:+IgnoreUnrecognizedVMOptions",
        "-XX:+UseSerialGC",                 // найекономніший збирач: без додаткових потоків і структур
        "-Xms8m", "-Xmx160m",
        "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25", // після GC куча стискається й повертає пам'ять ОС
        "-XX:TieredStopAtLevel=1",          // тільки швидкий компілятор C1: менше пам'яті під JIT
        "-XX:CICompilerCount=1",
        "-XX:ReservedCodeCacheSize=32m",
        "-XX:MaxDirectMemorySize=64m",
        "-XX:-UsePerfData",
        "-Dsun.java2d.d3d=false",           // без Direct3D-буферів у ОЗП/відеопам'яті
        "-Dsun.java2d.noddraw=true",
    };

    /**
     * Перезапускає запускач в окремому процесі з LEAN_FLAGS і завершує поточний процес.
     * Для запуску «java PaxLauncher.java» один раз збирає кеш-jar, щоб у фоні не висів компілятор.
     * Вимкнути: -Dpax.norelaunch=true або змінна середовища PAX_NO_RELAUNCH=1.
     */
    static boolean relaunchLean() {
        if (System.getProperty("pax.child") != null || System.getProperty("pax.norelaunch") != null
                || System.getenv("PAX_NO_RELAUNCH") != null) return false;
        try {
            var cs = PaxLauncher.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return false;
            Path loc = Path.of(cs.getLocation().toURI());
            Path cp = loc.toString().endsWith(".java") ? buildCacheJar(loc) : loc;
            if (cp == null) return false;
            boolean win = Engine.osName().equals("windows");
            Path bin = Path.of(System.getProperty("java.home"), "bin");
            Path exe = win && Files.exists(bin.resolve("javaw.exe")) ? bin.resolve("javaw.exe") : bin.resolve(win ? "java.exe" : "java");
            List<String> cmd = new ArrayList<>();
            cmd.add(exe.toString());
            cmd.addAll(Arrays.asList(LEAN_FLAGS));
            cmd.add("-Dpax.child=1");
            cmd.add("-cp");
            cmd.add(cp.toString());
            cmd.add("PaxLauncher");
            Process pr = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            // якщо дочірній процес одразу впав (нестандартна JVM) — працюємо в поточному
            return !pr.waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Компілює PaxLauncher.java в ~/paxlauncher/cache/PaxLauncher-lean.jar (перезбирається, лише якщо файл змінився). */
    static Path buildCacheJar(Path src) {
        try {
            Path dir = ROOT.resolve("cache");
            Files.createDirectories(dir);
            Path jar = dir.resolve("PaxLauncher-lean.jar"), stamp = dir.resolve("PaxLauncher-lean.stamp");
            String st = Files.size(src) + ":" + Files.getLastModifiedTime(src).toMillis() + ":" + Runtime.version();
            if (Files.isRegularFile(jar) && Files.isRegularFile(stamp) && Files.readString(stamp).equals(st)) return jar;
            JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
            if (jc == null) return null;
            Path out = Files.createTempDirectory("paxc");
            try {
                if (jc.run(null, null, null, "-nowarn", "-encoding", "UTF-8", "-d", out.toString(), src.toString()) != 0) return null;
                var mf = new java.util.jar.Manifest();
                mf.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
                mf.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, "PaxLauncher");
                Path tmp = dir.resolve("PaxLauncher-lean.jar.tmp");
                try (var jo = new java.util.jar.JarOutputStream(Files.newOutputStream(tmp), mf); var w = Files.walk(out)) {
                    for (Path f : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                        jo.putNextEntry(new java.util.jar.JarEntry(out.relativize(f).toString().replace('\\', '/')));
                        Files.copy(f, jo);
                        jo.closeEntry();
                    }
                }
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(stamp, st);
                return jar;
            } finally {
                try (var w = Files.walk(out)) { w.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete()); }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static void main(String[] args) {
        if (relaunchLean()) { System.exit(0); return; }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        System.setProperty("sun.java2d.d3d", "false");
        javax.imageio.ImageIO.setUseCache(false); // без тимчасових файлів/буферів при декодуванні картинок
        Settings boot = new Settings(ROOT.resolve("launcher.properties"));
        I18n.init(boot.get("lang", "uk"));
        Theme.apply("light".equals(boot.get("theme", "dark")), Theme.parseHex(boot.get("accent", ""), Theme.DEFAULT_ACCENT));
        Theme.applyUI();
        SwingUtilities.invokeLater(() -> {
            LauncherWindow w = new LauncherWindow();
            w.setVisible(true);
            Updater.start(w, boot); // автоматична перевірка оновлень на GitHub
        });
    }
}

/* ======================================================================
 *  Автооновлення з GitHub Releases (Kryp-s/PaxLauncher)
 * ====================================================================== */
final class Updater {
    /** Під час збірки в GitHub Actions підставляється автоматично з git-тега (v1.0.1 -> 1.0.1). */
    static final String VERSION = "1.0.0";
    static final String REPO = "Kryp-s/PaxLauncher";

    record Release(String version, String url, String file, String sha256) {}

    /** Викликається після показу вікна. Вимкнути: autoupdate=false у launcher.properties. */
    static void start(LauncherWindow win, Settings cfg) {
        if (!cfg.getb("autoupdate", true) || !installedBuild()) return;
        Thread t = new Thread(() -> {
            Release r = check();
            if (r != null) SwingUtilities.invokeLater(() -> apply(win, r));
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Оновлюємось лише коли запущено встановлену програму (jar з jpackage на Windows), а не «java PaxLauncher.java». */
    static boolean installedBuild() {
        try {
            if (!Engine.osName().equals("windows")) return false;
            var cs = PaxLauncher.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return false;
            Path loc = Path.of(cs.getLocation().toURI());
            String n = loc.getFileName().toString();
            return n.endsWith(".jar") && !n.equals("PaxLauncher-lean.jar");
        } catch (Exception e) {
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
            if (resp.statusCode() != 200) return null;
            Object j = Json.parse(resp.body());
            String tag = Json.string(Json.get(j, "tag_name"));
            if (tag == null) return null;
            String v = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            if (cmp(v, VERSION) <= 0) return null;
            for (Object a : Json.list(Json.get(j, "assets"))) {
                String name = Json.string(Json.get(a, "name"));
                String url = Json.string(Json.get(a, "browser_download_url"));
                if (name == null || url == null || !name.toLowerCase().endsWith(".msi")) continue;
                String dg = Json.string(Json.get(a, "digest"));
                String sha = dg != null && dg.startsWith("sha256:") ? dg.substring(7) : null;
                return new Release(v, url, name, sha);
            }
        } catch (Exception ignored) { }
        return null;
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

    /** Показує повідомлення, у фоні завантажує інсталятор, запускає його й закриває лаунчер. */
    static void apply(LauncherWindow win, Release r) {
        Dlg.show(win, I18n.t("Оновлення"),
                Dlg.text(I18n.t("Знайдено нову версію ") + r.version() + I18n.t(". Завантажую й встановлюю — лаунчер перезапуститься сам.")),
                I18n.t("Гаразд"), null, d -> {
                    Thread t = new Thread(() -> {
                        try {
                            Path f = download(r);
                            // тихе встановлення поверх старої версії (MSI з тим самим upgrade-uuid)
                            new ProcessBuilder("msiexec", "/i", f.toString(), "/passive", "/norestart").start();
                            System.exit(0);
                        } catch (Exception e) {
                            SwingUtilities.invokeLater(d::dispose); // не вдалося — просто працюємо далі зі старою версією
                        }
                    }, "update-install");
                    t.setDaemon(false);
                    t.start();
                });
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

/* ======================================================================
 *  JSON (мінімальний парсер, щоб не залежати від бібліотек)
 * ====================================================================== */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) { return new Json(text).val(); }

    private void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }

    private Object val() {
        ws();
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return str();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return num();
        }
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws(); String k = str(); ws(); i++; // ':'
            m.put(k, val()); ws();
            if (s.charAt(i++) == '}') break;
        }
        return m;
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            l.add(val()); ws();
            if (s.charAt(i++) == ']') break;
        }
        return l;
    }

    private String str() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
        return b.toString();
    }

    private Double num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.valueOf(s.substring(st, i));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }

    static String string(Object o) { return o instanceof String ? (String) o : null; }

    static Object get(Object o, String... path) {
        for (String p : path) {
            Map<String, Object> mm = map(o);
            if (mm == null) return null;
            o = mm.get(p);
        }
        return o;
    }
}

/* ======================================================================
 *  Налаштування
 * ====================================================================== */
final class Settings {
    private final Path file;
    private final Properties p = new Properties();

    Settings(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) { }
        }
    }

    String get(String k, String d) { return p.getProperty(k, d); }

    int geti(String k, int d) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(d)).trim()); } catch (Exception e) { return d; }
    }

    boolean getb(String k, boolean d) { return Boolean.parseBoolean(p.getProperty(k, String.valueOf(d))); }

    void set(String k, String v) { p.setProperty(k, v); }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(w, "PaxLauncher"); }
        } catch (IOException ignored) { }
    }
}

/* ======================================================================
 *  Рушій: версії, завантаження, формування команди запуску
 * ====================================================================== */
final class Engine {
    interface Progress { void update(String text, double frac); }

    record VInfo(String id, String type, String url) { }

    record Task(String url, Path dest, String sha1) { }

    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String RES = "https://resources.download.minecraft.net/";
    static final String LIBS = "https://libraries.minecraft.net/";

    final Path root;
    final Consumer<String> log;
    final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    final Map<String, String> urls = new ConcurrentHashMap<>();
    volatile List<VInfo> manifest = new ArrayList<>();
    volatile String latestRelease = "";
    /** Прапорець скасування завантаження + процес інсталятора (Forge/NeoForge), який треба зупинити. */
    volatile boolean stop, paused;
    volatile Process proc;

    private void waitPause(java.util.function.BooleanSupplier cancel) throws Cancelled {
        while (paused) {
            if (cancel.getAsBoolean()) throw new Cancelled();
            try { Thread.sleep(100); } catch (InterruptedException e) { throw new Cancelled(); }
        }
    }

    void cancel() {
        stop = true;
        paused = false;
        Process p = proc;
        if (p != null) p.destroy();
    }

    Engine(Path root, Consumer<String> log) { this.root = root; this.log = log; }

    private static Map<String, Object> m(Object o) { return Json.map(o); }
    private static List<Object> l(Object o) { return Json.list(o); }
    private static String s(Object o) { return Json.string(o); }
    private static Object g(Object o, String... p) { return Json.get(o, p); }

    /* ---------- ОС ---------- */
    static String osName() {
        String n = System.getProperty("os.name").toLowerCase();
        if (n.contains("win")) return "windows";
        if (n.contains("mac") || n.contains("darwin")) return "osx";
        return "linux";
    }

    static boolean is64() { return System.getProperty("os.arch").contains("64"); }

    /** Визначає тип завантажувача за id версії (fabric-loader-..., 1.20.1-forge-..., neoforge-...). */
    static String detectLoader(String id) {
        String l = id.toLowerCase();
        if (l.contains("neoforge")) return "NeoForge";
        if (l.contains("quilt")) return "Quilt";
        if (l.contains("fabric")) return "Fabric";
        if (l.contains("forge")) return "Forge";
        return "Vanilla";
    }

    static boolean isArm() {
        String a = System.getProperty("os.arch").toLowerCase();
        return a.contains("aarch64") || a.contains("arm64");
    }

    /* ---------- мережа ---------- */
    String fetch(String url) throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "PaxLauncher/1.0").timeout(Duration.ofSeconds(30)).build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " — " + url);
        return resp.body();
    }

    static String sha1(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        char[] hx = new char[d.length * 2];
        for (int k = 0; k < d.length; k++) {
            hx[k * 2] = Character.forDigit((d[k] >> 4) & 15, 16);
            hx[k * 2 + 1] = Character.forDigit(d[k] & 15, 16);
        }
        return new String(hx);
    }

    /** Кидається, коли користувач скасував завантаження. */
    static final class Cancelled extends IOException {
        Cancelled() { super(I18n.t("Скасовано користувачем")); }
    }

    void download(String url, Path dest, String sha1) throws Exception {
        download(url, dest, sha1, () -> stop);
    }

    /** Завантаження потоком; cancel перевіряється між шматками, тож зупинка майже миттєва. */
    void download(String url, Path dest, String sha1, java.util.function.BooleanSupplier cancel) throws Exception {
        if (Files.exists(dest) && Files.size(dest) > 0 && (sha1 == null || sha1(dest).equalsIgnoreCase(sha1))) return;
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        Exception last = null;
        for (int t = 0; t < 3; t++) {
            if (cancel.getAsBoolean()) throw new Cancelled();
            waitPause(cancel);
            try {
                HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "PaxLauncher/1.0").timeout(Duration.ofMinutes(5)).build();
                HttpResponse<java.io.InputStream> resp = http.send(r, HttpResponse.BodyHandlers.ofInputStream());
                try (java.io.InputStream in = resp.body()) {
                    if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " — " + url);
                    try (java.io.OutputStream out = Files.newOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (cancel.getAsBoolean()) throw new Cancelled();
                            waitPause(cancel);
                            out.write(buf, 0, n);
                        }
                    }
                }
                if (sha1 != null && !sha1(tmp).equalsIgnoreCase(sha1)) throw new IOException(I18n.t("Контрольна сума не збігається: ") + url);
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (Cancelled c) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                throw c;
            } catch (Exception e) {
                last = e;
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
        throw last;
    }

    /* ---------- версії ---------- */
    List<VInfo> loadManifest() throws Exception {
        Map<String, Object> mf = m(Json.parse(fetch(MANIFEST)));
        List<VInfo> out = new ArrayList<>();
        for (Object o : l(mf.get("versions"))) {
            Map<String, Object> v = m(o);
            String id = s(v.get("id"));
            out.add(new VInfo(id, s(v.get("type")), s(v.get("url"))));
            urls.put(id, s(v.get("url")));
        }
        manifest = out;
        try { latestRelease = s(m(mf.get("latest")).get("release")); } catch (Exception ignored) { latestRelease = ""; }
        return out;
    }

    List<String> installedIds() {
        List<String> r = new ArrayList<>();
        Path vd = root.resolve("versions");
        if (!Files.isDirectory(vd)) return r;
        try (var st = Files.list(vd)) {
            st.filter(Files::isDirectory).forEach(p -> {
                String n = p.getFileName().toString();
                if (Files.exists(p.resolve(n + ".json"))) r.add(n);
            });
        } catch (IOException ignored) { }
        Collections.sort(r);
        return r;
    }

    /** Читає json версії (качає при потребі) і зливає з inheritsFrom (Fabric/Forge). */
    Map<String, Object> loadVersion(String id) throws Exception {
        Path jf = root.resolve("versions").resolve(id).resolve(id + ".json");
        if (!Files.exists(jf)) {
            String url = urls.get(id);
            if (url == null) throw new IOException(I18n.t("Версію ") + id + I18n.t(" не знайдено (немає інтернету?)"));
            log.accept(I18n.t("Завантаження опису версії ") + id);
            Files.createDirectories(jf.getParent());
            Files.writeString(jf, fetch(url));
        }
        Map<String, Object> v = m(Json.parse(Files.readString(jf)));
        String parent = s(v.get("inheritsFrom"));
        if (parent == null) {
            v.put("_jar", id);
            return v;
        }
        return merge(loadVersion(parent), v);
    }

    private Map<String, Object> merge(Map<String, Object> p, Map<String, Object> c) {
        Map<String, Object> r = new LinkedHashMap<>(p);
        for (var e : c.entrySet()) {
            String k = e.getKey();
            if (k.equals("arguments") || k.equals("libraries")) continue;
            r.put(k, e.getValue());
        }
        // бібліотеки: спочатку дочірні, дубльовані (за group:artifact:classifier) відкидаємо
        List<Object> all = new ArrayList<>(l(c.get("libraries")));
        all.addAll(l(p.get("libraries")));
        Map<String, Object> uniq = new LinkedHashMap<>();
        int n = 0;
        for (Object o : all) {
            String name = s(m(o).get("name"));
            String key;
            if (name == null) key = "#" + (n++);
            else {
                String[] pr = name.split(":");
                key = pr[0] + ":" + pr[1] + (pr.length > 3 ? ":" + pr[3] : "");
            }
            uniq.putIfAbsent(key, o);
        }
        r.put("libraries", new ArrayList<>(uniq.values()));
        Map<String, Object> pa = m(p.get("arguments")), ca = m(c.get("arguments"));
        if (pa != null || ca != null) {
            Map<String, Object> na = new LinkedHashMap<>();
            for (String k : new String[]{"game", "jvm"}) {
                List<Object> x = new ArrayList<>();
                if (pa != null) x.addAll(l(pa.get(k)));
                if (ca != null) x.addAll(l(ca.get(k)));
                na.put(k, x);
            }
            r.put("arguments", na);
        }
        return r;
    }

    /** Версія завантажувача для списку: version — що підставляти в URL, label — що бачить користувач. */
    record LV(String version, String label) {
        @Override public String toString() { return label; }
    }

    private static List<String> xmlVersions(String xml) {
        List<String> out = new ArrayList<>();
        var mt = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (mt.find()) out.add(mt.group(1).trim());
        return out;
    }

    /** 1.21.4 -> "21.4.", 1.21 -> "21.0." (так нумеруються версії NeoForge). */
    static String neoPrefix(String mc) {
        String[] p = mc.split("\\.");
        if (p.length >= 2 && p[0].equals("1")) return p[1] + "." + (p.length > 2 ? p[2] : "0") + ".";
        return null;
    }

    /** Список версій завантажувача (Fabric / Quilt / Forge / NeoForge) для версії Minecraft, найновіші першими. */
    List<LV> loaderVersions(String loader, String mc) throws Exception {
        List<LV> out = new ArrayList<>();
        switch (loader) {
            case "Vanilla":
                out.add(new LV(mc, mc + I18n.t("  · без модів")));
                break;
            case "Fabric": {
                for (Object o : l(Json.parse(fetch("https://meta.fabricmc.net/v2/versions/loader/" + mc)))) {
                    Map<String, Object> ld = m(m(o).get("loader"));
                    String v = s(ld.get("version"));
                    out.add(new LV(v, v + (Boolean.TRUE.equals(ld.get("stable")) ? I18n.t("  · стабільна") : "")));
                }
                break;
            }
            case "Quilt": {
                for (Object o : l(Json.parse(fetch("https://meta.quiltmc.org/v3/versions/loader/" + mc)))) {
                    String v = s(m(m(o).get("loader")).get("version"));
                    boolean pre = v.contains("beta") || v.contains("alpha") || v.contains("rc");
                    out.add(new LV(v, v + (pre ? I18n.t("  · бета") : I18n.t("  · стабільна"))));
                }
                break;
            }
            case "Forge": {
                String rec = null, lat = null;
                try {
                    Map<String, Object> promos = m(m(Json.parse(fetch(
                            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"))).get("promos"));
                    rec = s(promos.get(mc + "-recommended"));
                    lat = s(promos.get(mc + "-latest"));
                } catch (Exception ignored) { }
                for (String v : xmlVersions(fetch("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"))) {
                    if (!v.startsWith(mc + "-")) continue;
                    String shown = v.substring(mc.length() + 1);
                    String note = shown.equals(rec) ? I18n.t("  · рекомендована") : shown.equals(lat) ? I18n.t("  · остання") : "";
                    out.add(new LV(v, shown + note));
                }
                Collections.reverse(out);
                break;
            }
            case "NeoForge": {
                String art, pre;
                if (mc.equals("1.20.1")) { art = "forge"; pre = "1.20.1-"; }
                else { art = "neoforge"; pre = neoPrefix(mc); }
                if (pre == null) break;
                for (String v : xmlVersions(fetch("https://maven.neoforged.net/releases/net/neoforged/" + art + "/maven-metadata.xml")))
                    if (v.startsWith(pre)) out.add(new LV(v, v));
                Collections.reverse(out);
                break;
            }
            default:
                throw new IOException(I18n.t("Невідомий завантажувач: ") + loader);
        }
        return out;
    }

    /** Ставить Fabric для вказаної версії Minecraft; повертає id нового профілю. */
    String installFabric(String mc, String loaderVersion) throws Exception {
        String lv = loaderVersion;
        if (lv == null) {
            List<Object> loaders = l(Json.parse(fetch("https://meta.fabricmc.net/v2/versions/loader/" + mc)));
            if (loaders.isEmpty()) throw new IOException(I18n.t("Fabric не підтримує версію ") + mc);
            for (Object o : loaders) {
                Map<String, Object> ld = m(m(o).get("loader"));
                if (Boolean.TRUE.equals(ld.get("stable"))) { lv = s(ld.get("version")); break; }
            }
            if (lv == null) lv = s(m(m(loaders.get(0)).get("loader")).get("version"));
        }
        String profile = fetch("https://meta.fabricmc.net/v2/versions/loader/" + mc + "/" + lv + "/profile/json");
        String id = s(m(Json.parse(profile)).get("id"));
        Path f = root.resolve("versions").resolve(id).resolve(id + ".json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, profile);
        log.accept(I18n.t("Fabric профіль збережено: ") + id);
        return id;
    }

    /** Ставить Quilt для вказаної версії Minecraft (профіль із meta.quiltmc.org); повертає id нового профілю. */
    String installQuilt(String mc, String loaderVersion) throws Exception {
        String lv = loaderVersion;
        if (lv == null) {
            List<Object> loaders = l(Json.parse(fetch("https://meta.quiltmc.org/v3/versions/loader/" + mc)));
            if (loaders.isEmpty()) throw new IOException(I18n.t("Quilt не підтримує версію ") + mc);
            for (Object o : loaders) {
                String v = s(m(m(o).get("loader")).get("version"));
                if (!v.contains("beta") && !v.contains("alpha") && !v.contains("rc")) { lv = v; break; }
            }
            if (lv == null) lv = s(m(m(loaders.get(0)).get("loader")).get("version"));
        }
        String profile = fetch("https://meta.quiltmc.org/v3/versions/loader/" + mc + "/" + lv + "/profile/json");
        String id = s(m(Json.parse(profile)).get("id"));
        Path f = root.resolve("versions").resolve(id).resolve(id + ".json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, profile);
        log.accept(I18n.t("Quilt профіль збережено: ") + id);
        return id;
    }

    /** Ставить Fabric / Quilt / Forge / NeoForge. Forge і NeoForge — офіційним інсталятором. Повертає id нової версії. */
    String installLoader(String loader, String mc, String ver, String javaPath, Progress pg) throws Exception {
        if (loader.equals("Vanilla")) {
            pg.update(I18n.t("Завантаження Minecraft ") + mc + "…", 0.3);
            Map<String, Object> vv = loadVersion(mc);
            Map<String, Object> cl = m(g(vv, "downloads", "client"));
            if (cl != null)
                download(s(cl.get("url")), root.resolve("versions").resolve(mc).resolve(mc + ".jar"), s(cl.get("sha1")));
            pg.update(I18n.t("Готово"), 1);
            return mc;
        }
        if (loader.equals("Fabric")) {
            pg.update(I18n.t("Завантаження профілю Fabric…"), 0.4);
            String id = installFabric(mc, ver);
            pg.update(I18n.t("Готово"), 1);
            return id;
        }
        if (loader.equals("Quilt")) {
            pg.update(I18n.t("Завантаження профілю Quilt…"), 0.4);
            String id = installQuilt(mc, ver);
            pg.update(I18n.t("Готово"), 1);
            return id;
        }
        String name, url;
        if (loader.equals("Forge")) {
            name = "forge-" + ver + "-installer.jar";
            url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + ver + "/" + name;
        } else {
            String art = mc.equals("1.20.1") ? "forge" : "neoforge";
            name = art + "-" + ver + "-installer.jar";
            url = "https://maven.neoforged.net/releases/net/neoforged/" + art + "/" + ver + "/" + name;
        }
        // інсталятору потрібна ванільна версія
        pg.update(I18n.t("Завантаження Minecraft ") + mc + "…", 0.1);
        Map<String, Object> v = loadVersion(mc);
        Map<String, Object> client = m(g(v, "downloads", "client"));
        if (client != null)
            download(s(client.get("url")), root.resolve("versions").resolve(mc).resolve(mc + ".jar"), s(client.get("sha1")));
        Path profiles = root.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) Files.writeString(profiles, "{\"profiles\":{},\"version\":3}");

        pg.update(I18n.t("Завантаження встановлювача ") + loader + "…", 0.25);
        Path inst = root.resolve("installers").resolve(name);
        download(url, inst, null);

        pg.update(I18n.t("Встановлення ") + loader + I18n.t(" (це може зайняти кілька хвилин)…"), 0.45);
        Set<String> before = new HashSet<>(installedIds());
        String java = javaPath.isEmpty()
                ? Path.of(System.getProperty("java.home"), "bin", osName().equals("windows") ? "java.exe" : "java").toString()
                : javaPath;
        Process pr = new ProcessBuilder(java, "-jar", inst.toString(), "--installClient", root.toString())
                .directory(root.toFile()).redirectErrorStream(true).start();
        proc = pr;
        if (stop) pr.destroy();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = r.readLine()) != null) log.accept(ln);
        }
        int code = pr.waitFor();
        proc = null;
        if (stop) throw new Cancelled();
        if (code != 0) throw new IOException(I18n.t("Встановлювач завершився з помилкою (код ") + code + I18n.t("). Деталі дивись у Журналі."));
        pg.update(I18n.t("Готово"), 1);

        List<String> after = installedIds();
        for (String id : after) if (!before.contains(id)) return id;
        String tail = loader.equals("Forge") ? ver.substring(Math.min(ver.length(), mc.length() + 1)) : ver;
        for (String id : after) if (id.contains(tail)) return id;
        throw new IOException(I18n.t("Встановлювач відпрацював, але нову версію не знайдено в ") + root.resolve("versions"));
    }

    /** authlib-injector потрібен для входу через Ely.by (скіни та авторизація на серверах). */
    Path authlib() throws Exception {
        Path f = root.resolve("authlib-injector.jar");
        if (Files.exists(f) && Files.size(f) > 0) return f;
        Map<String, Object> j = m(Json.parse(fetch("https://authlib-injector.yushi.moe/artifact/latest.json")));
        download(s(j.get("download_url")), f, null);
        return f;
    }

    /* ---------- правила (rules) ---------- */
    boolean allowed(Object rules, Set<String> feats) {
        List<Object> list = l(rules);
        if (list.isEmpty()) return true;
        boolean ok = false;
        for (Object ro : list) {
            Map<String, Object> r = m(ro);
            boolean match = true;
            Map<String, Object> os = m(r.get("os"));
            if (os != null) {
                String n = s(os.get("name"));
                if (n != null && !n.equals(osName())) match = false;
                String a = s(os.get("arch"));
                if (a != null) {
                    if (a.equals("x86") && is64()) match = false;
                    if (a.equals("arm64") && !isArm()) match = false;
                }
                String ver = s(os.get("version"));
                if (ver != null && !Pattern.compile(ver).matcher(System.getProperty("os.version")).find()) match = false;
            }
            Map<String, Object> f = m(r.get("features"));
            if (f != null) {
                for (var e : f.entrySet()) {
                    boolean want = Boolean.TRUE.equals(e.getValue());
                    if (want != feats.contains(e.getKey())) match = false;
                }
            }
            if (match) ok = "allow".equals(r.get("action"));
        }
        return ok;
    }

    static String mavenPath(String name) {
        String[] p = name.split(":");
        String cl = p.length > 3 ? "-" + p[3] : "";
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + cl + ".jar";
    }

    private void runTasks(List<Task> tasks, Progress pg) throws Exception {
        if (tasks.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        AtomicInteger done = new AtomicInteger();
        int total = tasks.size();
        List<Future<Object>> fs = new ArrayList<>();
        for (Task t : tasks) {
            fs.add(pool.submit(() -> {
                download(t.url(), t.dest(), t.sha1());
                int d = done.incrementAndGet();
                if (d % 5 == 0 || d == total) pg.update(I18n.t("Завантаження файлів: ") + d + " / " + total, (double) d / total);
                return null;
            }));
        }
        try {
            for (Future<Object> f : fs) f.get();
        } catch (ExecutionException e) {
            pool.shutdownNow();
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        } finally {
            pool.shutdown();
        }
    }

    private static String sub(String t, Map<String, String> ph) {
        for (var e : ph.entrySet()) t = t.replace("${" + e.getKey() + "}", e.getValue());
        return t;
    }

    private List<String> expand(List<Object> items, Map<String, String> ph, Set<String> feats) {
        List<String> out = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof String) out.add(sub((String) o, ph));
            else {
                Map<String, Object> mm = m(o);
                if (mm == null || !allowed(mm.get("rules"), feats)) continue;
                Object val = mm.get("value");
                if (val instanceof String) out.add(sub((String) val, ph));
                else for (Object x : l(val)) if (x instanceof String) out.add(sub((String) x, ph));
            }
        }
        return out;
    }

    /** Докачує все потрібне і повертає готову команду запуску. */
    List<String> build(Map<String, Object> v, Settings cfg, Accounts.Acc acc, Progress pg, Path gameDir) throws Exception {
        String id = s(v.get("id")), jar = s(v.get("_jar"));
        Path vroot = root.resolve("versions"), libRoot = root.resolve("libraries"), assetsRoot = root.resolve("assets");
        Files.createDirectories(gameDir);

        String javaPath = cfg.get("java", "").trim();
        if (!javaPath.isEmpty() && (javaPath.contains("/") || javaPath.contains("\\")) && !Files.isRegularFile(Path.of(javaPath)))
            throw new IOException(I18n.t("Файл Java не знайдено:\n") + javaPath + I18n.t("\nОбери Java в Налаштуваннях або очисти поле, щоб використати вбудовану."));
        Object need = g(v, "javaVersion", "majorVersion");
        if (javaPath.isEmpty() && need instanceof Number) {
            int n = ((Number) need).intValue();
            if (Runtime.version().feature() < n)
                throw new IOException(I18n.t("Ця версія гри вимагає Java ") + n + I18n.t("+, а запускач працює на Java ")
                        + Runtime.version().feature() + I18n.t(".\nВстанови потрібну Java і обери її (або вкажи шлях) у Налаштуваннях."));
        }

        Set<String> feats = new HashSet<>();
        int rw = cfg.geti("w", 0), rh = cfg.geti("h", 0);
        if (rw > 0 && rh > 0) feats.add("has_custom_resolution");

        pg.update(I18n.t("Підготовка файлів…"), 0);
        List<Task> tasks = new ArrayList<>();
        List<Path> cp = new ArrayList<>();
        List<Path> nativeJars = new ArrayList<>();

        Map<String, Object> client = m(g(v, "downloads", "client"));
        Path clientJar = vroot.resolve(jar).resolve(jar + ".jar");
        if (client != null) tasks.add(new Task(s(client.get("url")), clientJar, s(client.get("sha1"))));
        else if (!Files.exists(clientJar)) throw new IOException(I18n.t("Немає клієнтського jar для ") + jar);

        for (Object lo : l(v.get("libraries"))) {
            Map<String, Object> lib = m(lo);
            if (!allowed(lib.get("rules"), feats)) continue;
            Map<String, Object> art = m(g(lib, "downloads", "artifact"));
            if (art != null) {
                Path f = libRoot.resolve(s(art.get("path")));
                String url = s(art.get("url"));
                if (url != null && !url.isEmpty()) tasks.add(new Task(url, f, s(art.get("sha1"))));
                cp.add(f);
            } else if (lib.get("downloads") == null && s(lib.get("name")) != null) {
                String path = mavenPath(s(lib.get("name")));
                String base = s(lib.get("url"));
                if (base == null) base = LIBS;
                if (!base.endsWith("/")) base += "/";
                Path f = libRoot.resolve(path);
                tasks.add(new Task(base + path, f, null));
                cp.add(f);
            }
            Map<String, Object> nat = m(lib.get("natives"));
            if (nat != null) {
                String cls = s(nat.get(osName()));
                if (cls != null) {
                    cls = cls.replace("${arch}", is64() ? "64" : "32");
                    Map<String, Object> na = m(g(lib, "downloads", "classifiers", cls));
                    if (na != null) {
                        Path f = libRoot.resolve(s(na.get("path")));
                        tasks.add(new Task(s(na.get("url")), f, s(na.get("sha1"))));
                        nativeJars.add(f);
                    }
                }
            }
        }
        cp.add(clientJar);

        // ресурси (assets)
        Map<String, Object> ai = m(v.get("assetIndex"));
        String aid = s(ai.get("id"));
        Path idxFile = assetsRoot.resolve("indexes").resolve(aid + ".json");
        download(s(ai.get("url")), idxFile, s(ai.get("sha1")));
        Map<String, Object> idx = m(Json.parse(Files.readString(idxFile)));
        Map<String, Object> objs = m(idx.get("objects"));
        Set<String> seen = new HashSet<>();
        for (var e : objs.entrySet()) {
            String h = s(m(e.getValue()).get("hash"));
            if (seen.add(h)) {
                String pre = h.substring(0, 2);
                tasks.add(new Task(RES + pre + "/" + h, assetsRoot.resolve("objects").resolve(pre).resolve(h), null));
            }
        }

        runTasks(tasks, pg);
        pg.update(I18n.t("Розпакування…"), 1);

        Path gameAssets = assetsRoot;
        boolean virt = Boolean.TRUE.equals(idx.get("virtual"));
        boolean mapRes = Boolean.TRUE.equals(idx.get("map_to_resources"));
        if (virt || mapRes) {
            Path vd = assetsRoot.resolve("virtual").resolve(aid);
            for (var e : objs.entrySet()) {
                String h = s(m(e.getValue()).get("hash"));
                Path src = assetsRoot.resolve("objects").resolve(h.substring(0, 2)).resolve(h);
                Path dst = (virt ? vd : gameDir.resolve("resources")).resolve(e.getKey());
                if (!Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
            if (virt) gameAssets = vd;
        }

        // natives
        Path nd = vroot.resolve(id).resolve("natives");
        Files.createDirectories(nd);
        for (Path nj : nativeJars) {
            try (ZipInputStream z = new ZipInputStream(Files.newInputStream(nj))) {
                ZipEntry ze;
                while ((ze = z.getNextEntry()) != null) {
                    if (ze.isDirectory() || ze.getName().startsWith("META-INF")) continue;
                    Path out = nd.resolve(ze.getName()).normalize();
                    if (!out.startsWith(nd)) continue;
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        // команда запуску
        String sep = File.pathSeparator;
        String cpStr = cp.stream().map(Path::toString).collect(Collectors.joining(sep));
        String uuid = acc.uuid();
        String vtype = s(v.get("type")) == null ? "release" : s(v.get("type"));

        Map<String, String> ph = new HashMap<>();
        ph.put("auth_player_name", acc.name());
        ph.put("version_name", id);
        ph.put("game_directory", gameDir.toString());
        ph.put("assets_root", assetsRoot.toString());
        ph.put("assets_index_name", aid);
        ph.put("auth_uuid", uuid);
        ph.put("auth_access_token", acc.token());
        ph.put("auth_session", acc.token());
        ph.put("clientid", "0");
        ph.put("auth_xuid", "0");
        ph.put("user_type", acc.userType());
        ph.put("user_properties", "{}");
        ph.put("version_type", vtype);
        ph.put("natives_directory", nd.toString());
        ph.put("launcher_name", "PaxLauncher");
        ph.put("launcher_version", Updater.VERSION);
        ph.put("classpath", cpStr);
        ph.put("library_directory", libRoot.toString());
        ph.put("classpath_separator", sep);
        ph.put("resolution_width", String.valueOf(rw));
        ph.put("resolution_height", String.valueOf(rh));
        ph.put("game_assets", gameAssets.toString());

        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath.isEmpty()
                ? Path.of(System.getProperty("java.home"), "bin", osName().equals("windows") ? "java.exe" : "java").toString()
                : javaPath);
        int ram = cfg.geti("ram", 4096);
        cmd.add("-Xmx" + ram + "M");
        cmd.add("-Xms" + Math.min(ram, 1024) + "M");
        if (acc.type().equals("ely")) {
            try { cmd.add("-javaagent:" + authlib() + "=ely.by"); }
            catch (Exception e) { log.accept(I18n.t("authlib-injector недоступний: ") + e.getMessage()); }
        }
        String extra = cfg.get("jvm", "").trim();
        if (!extra.isEmpty()) cmd.addAll(Arrays.asList(extra.split("\\s+")));

        Map<String, Object> args = m(v.get("arguments"));
        if (args != null) {
            cmd.addAll(expand(l(args.get("jvm")), ph, feats));
            cmd.add(s(v.get("mainClass")));
            cmd.addAll(expand(l(args.get("game")), ph, feats));
        } else {
            cmd.add("-Djava.library.path=" + nd);
            cmd.add("-cp");
            cmd.add(cpStr);
            cmd.add(s(v.get("mainClass")));
            for (String a : sub(s(v.get("minecraftArguments")), ph).split(" ")) if (!a.isEmpty()) cmd.add(a);
            if (rw > 0 && rh > 0) { cmd.add("--width"); cmd.add(String.valueOf(rw)); cmd.add("--height"); cmd.add(String.valueOf(rh)); }
        }
        return cmd;
    }
}

/* ======================================================================
 *  Пошук встановлених Java (для вибору в налаштуваннях)
 * ====================================================================== */
final class JavaFinder {
    /** path == "" — вбудована Java запускача, path == null — «свій шлях». */
    record Inst(String label, String path) {
        @Override public String toString() { return label; }
    }

    static int major(String v) {
        try {
            String[] p = v.split("[._-]");
            int a = Integer.parseInt(p[0]);
            return a == 1 && p.length > 1 ? Integer.parseInt(p[1]) : a;
        } catch (Exception e) { return 0; }
    }

    private static String quoted(String text, String key) {
        for (String line : text.split("\\R")) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).replace("\"", "").trim();
        }
        return "";
    }

    /** Версія й постачальник: спершу з файлу release (миттєво), інакше через «java -version». */
    private static String[] info(Path exe) {
        String ver = "", vendor = "";
        try {
            Path rel = exe.getParent().getParent().resolve("release");
            if (Files.isRegularFile(rel)) {
                String t = Files.readString(rel, StandardCharsets.UTF_8);
                ver = quoted(t, "JAVA_VERSION");
                vendor = quoted(t, "IMPLEMENTOR");
            }
        } catch (Exception ignored) { }
        if (ver.isEmpty()) {
            try {
                Process pr = new ProcessBuilder(exe.toString(), "-version").redirectErrorStream(true).start();
                String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!pr.waitFor(4, TimeUnit.SECONDS)) pr.destroyForcibly();
                int a = out.indexOf('"'), b = a < 0 ? -1 : out.indexOf('"', a + 1);
                if (a >= 0 && b > a) ver = out.substring(a + 1, b);
            } catch (Exception ignored) { }
        }
        return new String[]{ver, vendor};
    }

    static List<Inst> scan() {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        String exeName = win ? "java.exe" : "java";
        String home = System.getProperty("user.home", "");

        List<Path> exes = new ArrayList<>();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) exes.add(Path.of(jh, "bin", exeName));
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(File.pathSeparator)) {
            if (!d.isBlank()) try { exes.add(Path.of(d.replace("\"", ""), exeName)); } catch (Exception ignored) { }
        }

        List<Path> roots = new ArrayList<>();
        if (win) {
            for (String e : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "LOCALAPPDATA"}) {
                String b = System.getenv(e);
                if (b == null) continue;
                for (String v : new String[]{"Java", "Eclipse Adoptium", "Eclipse Foundation", "Microsoft", "Zulu", "BellSoft",
                        "Amazon Corretto", "Semeru", "AdoptOpenJDK", "GraalVM", "Programs"})
                    roots.add(Path.of(b, v));
            }
            String ad = System.getenv("APPDATA");
            if (ad != null) roots.add(Path.of(ad, ".minecraft", "runtime"));
            roots.add(Path.of(home, ".jdks"));
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/usr/java"));
            roots.add(Path.of("/opt"));
            roots.add(Path.of(home, ".sdkman", "candidates", "java"));
            roots.add(Path.of(home, ".jdks"));
            roots.add(Path.of(home, ".minecraft", "runtime"));
            if (mac) {
                roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
                roots.add(Path.of(home, "Library", "Java", "JavaVirtualMachines"));
                roots.add(Path.of(home, "Library", "Application Support", "minecraft", "runtime"));
            } else {
                roots.add(Path.of(home, ".local", "share", "PrismLauncher", "java"));
            }
        }
        for (Path r : roots) {
            if (!Files.isDirectory(r)) continue;
            try (var st = Files.walk(r, 7)) {
                st.filter(x -> x.getFileName() != null && x.getFileName().toString().equals(exeName)
                        && x.getParent() != null && x.getParent().getFileName() != null
                        && x.getParent().getFileName().toString().equals("bin")).forEach(exes::add);
            } catch (Exception ignored) { }
        }

        // вбудована Java запускача вже є окремим пунктом — не дублюємо
        Set<String> seen = new HashSet<>();
        try { seen.add(Path.of(System.getProperty("java.home"), "bin", exeName).toRealPath().toString()); } catch (Exception ignored) { }

        List<Object[]> found = new ArrayList<>();
        for (Path e : exes) {
            try {
                if (!Files.isRegularFile(e)) continue;
                if (!seen.add(e.toRealPath().toString())) continue;
                String[] inf = info(e);
                found.add(new Object[]{major(inf[0]), inf[0], inf[1], e.toString()});
            } catch (Exception ignored) { }
        }
        found.sort((a, b) -> {
            int c = Integer.compare((Integer) b[0], (Integer) a[0]);
            return c != 0 ? c : ((String) a[3]).compareToIgnoreCase((String) b[3]);
        });
        List<Inst> out = new ArrayList<>();
        for (Object[] f : found) {
            int mj = (Integer) f[0];
            String vendor = (String) f[2];
            String label = (mj > 0 ? "Java " + mj : "Java") + (vendor.isEmpty() ? "" : " (" + vendor + ")") + " — " + f[3];
            out.add(new Inst(label, (String) f[3]));
        }
        return out;
    }
}

/* ======================================================================
 *  Моди та ресурс-паки: Modrinth (з перекладом описів українською)
 * ====================================================================== */
final class ModApi {
    /** Що саме шукаємо й ставимо: мод чи ресурс-пак. */
    enum Kind {
        MOD("mod", "mods", I18n.t("Моди"), I18n.t("Пошук модів"), I18n.t("Встановлені моди"), I18n.t("Тека модів"),
                I18n.t("Сторінка мода"), I18n.t("Видалити мод"), I18n.t("Версія мода (для цієї збірки)")),
        PACK("resourcepack", "resourcepacks", I18n.t("Набори ресурсів"), I18n.t("Пошук наборів ресурсів"), I18n.t("Встановлені набори ресурсів"), I18n.t("Тека наборів ресурсів"),
                I18n.t("Сторінка набору ресурсів"), I18n.t("Видалити набір ресурсів"), I18n.t("Версія набору ресурсів (для цієї збірки)"));

        final String type, folder, title, searchTitle, installedTitle, folderBtn, pageBtn, removeBtn, versionLabel;

        Kind(String type, String folder, String title, String searchTitle, String installedTitle, String folderBtn,
             String pageBtn, String removeBtn, String versionLabel) {
            this.type = type; this.folder = folder; this.title = title; this.searchTitle = searchTitle;
            this.installedTitle = installedTitle; this.folderBtn = folderBtn; this.pageBtn = pageBtn;
            this.removeBtn = removeBtn; this.versionLabel = versionLabel;
        }

        boolean isMod() { return this == MOD; }

        /** Чи схожий файл у папці на мод / ресурс-пак (враховує вимкнені «.disabled»). */
        boolean matches(String fileName) {
            String n = fileName.toLowerCase(Locale.ROOT);
            if (n.endsWith(".disabled")) n = n.substring(0, n.length() - 9);
            return isMod() ? n.endsWith(".jar") : n.endsWith(".zip");
        }

        @Override public String toString() { return title; }
    }

    record Hit(Kind kind, String id, String slug, String title, String author, String desc,
               long downloads, String loaders) {
        @Override public String toString() { return title; }

        String pageUrl() { return "https://modrinth.com/" + kind.type + "/" + slug; }
    }

    /** url == null — файл не можна завантажити автоматично. */
    record ModFile(String name, String filename, String url, String sha1, String channel, String date, List<String> deps) {
        @Override public String toString() {
            return name + "  · " + channel + (date.isEmpty() ? "" : "  · " + date) + (url == null ? I18n.t("  · тільки з сайту") : "");
        }
    }

    static final String MR = "https://api.modrinth.com/v2";
    static final int PAGE = 30;

    final Engine eng;

    ModApi(Engine eng) { this.eng = eng; }

    private static Map<String, Object> m(Object o) { return Json.map(o); }
    private static List<Object> l(Object o) { return Json.list(o); }
    private static String s(Object o) { return Json.string(o); }
    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
    private static String enc(String x) { return URLEncoder.encode(x, StandardCharsets.UTF_8); }

    private static String str(Object o) { String x = s(o); return x == null ? "" : x; }

    private String get(String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "PaxLauncher/1.0").header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30));
        HttpResponse<String> r = eng.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return r.body();
    }

    /* ---------- переклад описів українською ---------- */
    private final Map<String, String> trCache = new ConcurrentHashMap<>();
    private volatile long trFailUntil;

    private static boolean mostlyCyrillic(String t) {
        int cyr = 0, lat = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '\u0400' && c <= '\u04FF') cyr++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) lat++;
        }
        return cyr > lat;
    }

    /** Переклад із кешу (або оригінал, якщо ще не перекладено). */
    String cached(String text) {
        if (text == null) return "";
        String c = trCache.get(text);
        return c != null ? c : text;
    }

    boolean needsTranslation(String text) {
        return I18n.descLang() != null && text != null && !text.isBlank() && !trCache.containsKey(text) && !mostlyCyrillic(text);
    }

    /** Перекладає текст українською (безкоштовний онлайн-переклад). При помилці повертає оригінал. */
    String translate(String text) {
        if (I18n.descLang() == null) return text == null ? "" : text; // в англійському режимі описи не перекладаємо
        if (text == null || text.isBlank()) return text == null ? "" : text;
        String c = trCache.get(text);
        if (c != null) return c;
        if (mostlyCyrillic(text)) { trCache.put(text, text); return text; }
        if (System.currentTimeMillis() < trFailUntil) return text;
        try {
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + I18n.descLang() + "&dt=t&q=" + enc(text);
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 PaxLauncher/1.0").timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> r = eng.http.send(rq, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            List<Object> root = l(Json.parse(r.body()));
            StringBuilder sb = new StringBuilder();
            if (!root.isEmpty()) {
                for (Object seg : l(root.get(0))) {
                    List<Object> sl = l(seg);
                    if (!sl.isEmpty() && sl.get(0) instanceof String) sb.append((String) sl.get(0));
                }
            }
            String out = sb.toString().trim();
            if (out.isEmpty()) return text;
            if (trCache.size() > 500) trCache.clear();
            trCache.put(text, out);
            return out;
        } catch (Exception e) {
            trFailUntil = System.currentTimeMillis() + 20_000; // не засипаємо сервіс запитами, якщо він недоступний
            return text;
        }
    }

    /* ---------- завантажувачі ---------- */
    static List<String> modrinthLoaders(String loader) {
        switch (loader) {
            case "Fabric": return List.of("fabric");
            case "Quilt": return List.of("quilt", "fabric");
            case "Forge": return List.of("forge");
            case "NeoForge": return List.of("neoforge");
            default: return List.of();
        }
    }

    private static String jsonArr(List<String> items) {
        return items.stream().map(x -> "\"" + x + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private static String prettyLoader(String c) {
        switch (c) {
            case "fabric": return "Fabric";
            case "forge": return "Forge";
            case "neoforge": return "NeoForge";
            case "quilt": return "Quilt";
            default: return null;
        }
    }

    /* ---------- пошук ---------- */
    List<Hit> search(Kind kind, String q, String mc, String loader, int offset) throws Exception {
        StringBuilder f = new StringBuilder("[[\"project_type:" + kind.type + "\"],[\"versions:" + mc + "\"]");
        if (kind.isMod()) {
            List<String> cats = modrinthLoaders(loader);
            if (!cats.isEmpty())
                f.append(",").append(cats.stream().map(c -> "\"categories:" + c + "\"").collect(Collectors.joining(",", "[", "]")));
        }
        f.append("]");
        String url = MR + "/search?query=" + enc(q) + "&facets=" + enc(f.toString())
                + "&limit=" + PAGE + "&offset=" + offset + "&index=" + (q.isEmpty() ? "downloads" : "relevance");
        List<Hit> out = new ArrayList<>();
        for (Object o : l(m(Json.parse(get(url))).get("hits"))) {
            Map<String, Object> h = m(o);
            List<String> ld = new ArrayList<>();
            if (kind.isMod()) {
                for (Object c : l(h.get("categories"))) {
                    String p = prettyLoader(str(c));
                    if (p != null && !ld.contains(p)) ld.add(p);
                }
            }
            out.add(new Hit(kind, str(h.get("project_id")), str(h.get("slug")), str(h.get("title")),
                    str(h.get("author")), str(h.get("description")), num(h.get("downloads")), String.join(", ", ld)));
        }
        return out;
    }

    /* ---------- файли (версії мода / ресурс-паку) ---------- */
    List<ModFile> files(Kind kind, String id, String mc, String loader) throws Exception {
        String url = MR + "/project/" + id + "/version?game_versions=" + enc("[\"" + mc + "\"]");
        if (kind.isMod()) {
            List<String> cats = modrinthLoaders(loader);
            if (!cats.isEmpty()) url += "&loaders=" + enc(jsonArr(cats));
        }
        List<ModFile> out = new ArrayList<>();
        for (Object o : l(Json.parse(get(url)))) {
            Map<String, Object> v = m(o);
            List<Object> fl = l(v.get("files"));
            if (fl.isEmpty()) continue;
            Map<String, Object> file = m(fl.get(0));
            for (Object fo : fl) if (Boolean.TRUE.equals(m(fo).get("primary"))) { file = m(fo); break; }
            String channel = str(v.get("version_type"));
            String date = str(v.get("date_published"));
            List<String> deps = new ArrayList<>();
            for (Object d : l(v.get("dependencies"))) {
                Map<String, Object> dm = m(d);
                if ("required".equals(s(dm.get("dependency_type"))) && s(dm.get("project_id")) != null)
                    deps.add(s(dm.get("project_id")));
            }
            out.add(new ModFile(str(v.get("version_number")), str(file.get("filename")), s(file.get("url")),
                    s(m(file.get("hashes")) == null ? null : m(file.get("hashes")).get("sha1")),
                    channel.isEmpty() ? "release" : channel, date.length() >= 10 ? date.substring(0, 10) : "", deps));
        }
        return out;
    }

    /* ---------- імпорт модів з іншої збірки ---------- */
    /** Результат імпорту одного файлу. reason == null — успішно. */
    record ImportRow(String file, String title, String reason) {
        boolean ok() { return reason == null; }
    }

    /** Впізнає мод за хешем файлу на Modrinth і ставить його версію під потрібну версію гри та завантажувач. */
    ImportRow importJar(Path jar, String mc, String loader, Path dir, Set<String> seen,
                        Consumer<String> status, java.util.function.BooleanSupplier cancel) throws Exception {
        String fn = jar.getFileName().toString();
        String title = fn;
        try {
            if (modrinthLoaders(loader).isEmpty())
                return new ImportRow(fn, title, I18n.t("у збірці без завантажувача моди не працюють"));
            status.accept(I18n.t("Пошук: ") + fn);
            String hash = Engine.sha1(jar);
            Map<String, Object> ver;
            try {
                ver = m(Json.parse(get(MR + "/version_file/" + hash + "?algorithm=sha1")));
            } catch (IOException e) {
                if (String.valueOf(e.getMessage()).contains("404"))
                    return new ImportRow(fn, title, I18n.t("не знайдено на Modrinth (власний мод або з іншого сайту) — постав вручну"));
                throw e;
            }
            String pid = str(ver.get("project_id"));
            if (pid.isEmpty()) return new ImportRow(fn, title, I18n.t("не вдалося визначити проєкт"));
            try {
                String t = str(m(Json.parse(get(MR + "/project/" + pid))).get("title"));
                if (!t.isEmpty()) title = t;
            } catch (IOException ignored) { }
            List<ModFile> fs = files(Kind.MOD, pid, mc, loader);
            if (fs.isEmpty())
                return new ImportRow(fn, title, I18n.t("немає версії для Minecraft ") + mc + " (" + loader + ")");
            ModFile pick = null;
            for (ModFile x : fs) if (x.url() != null && x.channel().equals("release")) { pick = x; break; }
            if (pick == null) for (ModFile x : fs) if (x.url() != null) { pick = x; break; }
            if (pick == null)
                return new ImportRow(fn, title, I18n.t("автор заборонив завантаження сторонніми запускачами — візьми файл на сайті"));
            install(Kind.MOD, pid, pick, mc, loader, dir, seen, false, status, cancel);
            return new ImportRow(pick.filename(), title, null);
        } catch (Engine.Cancelled c) {
            throw c;
        } catch (Exception e) {
            return new ImportRow(fn, title, I18n.t("помилка: ") + e.getMessage());
        }
    }

    /* ---------- встановлення ---------- */
    private static Properties loadReg(Path dir) {
        Properties p = new Properties();
        Path f = dir.resolve(".pax-mods.properties");
        if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) { }
        return p;
    }

    private static void saveReg(Path dir, Properties p) {
        try (Writer w = Files.newBufferedWriter(dir.resolve(".pax-mods.properties"), StandardCharsets.UTF_8)) { p.store(w, "PaxLauncher mods"); }
        catch (IOException ignored) { }
    }

    /** Прибирає запис про файл з реєстру (після ручного видалення). */
    static void forget(Path dir, String fileName) {
        String base = fileName.endsWith(".disabled") ? fileName.substring(0, fileName.length() - 9) : fileName;
        Properties p = loadReg(dir);
        if (p.values().removeIf(v -> v.equals(base))) saveReg(dir, p);
    }

    static String safeName(String n) {
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Качає мод (або ресурс-пак) і його обов'язкові залежності; оновлює старий файл того ж проєкту. */
    void install(Kind kind, String id, ModFile f, String mc, String loader, Path dir,
                 Set<String> seen, boolean isDep, Consumer<String> status) throws Exception {
        install(kind, id, f, mc, loader, dir, seen, isDep, status, () -> false);
    }

    void install(Kind kind, String id, ModFile f, String mc, String loader, Path dir,
                 Set<String> seen, boolean isDep, Consumer<String> status,
                 java.util.function.BooleanSupplier cancel) throws Exception {
        if (cancel.getAsBoolean()) throw new Engine.Cancelled();
        String key = kind.type + ":" + id;
        if (!seen.add(key)) return;
        Files.createDirectories(dir);
        Properties reg = loadReg(dir);
        String old = reg.getProperty(key);
        if (isDep && old != null && (Files.exists(dir.resolve(old)) || Files.exists(dir.resolve(old + ".disabled")))) return;
        if (f.url() == null) throw new IOException(I18n.t("Автор заборонив завантаження сторонніми запускачами — візьми файл на сайті."));
        String fn = safeName(f.filename());
        status.accept((isDep ? I18n.t("Залежність: ") : I18n.t("Завантаження: ")) + fn);
        eng.download(f.url(), dir.resolve(fn), f.sha1(), cancel);
        if (old != null && !old.equals(fn)) {
            Files.deleteIfExists(dir.resolve(old));
            Files.deleteIfExists(dir.resolve(old + ".disabled"));
        }
        reg.setProperty(key, fn);
        saveReg(dir, reg);
        eng.log.accept(I18n.t("Встановлено: ") + fn);

        for (String dep : f.deps()) {
            if (seen.contains(kind.type + ":" + dep)) continue;
            if (cancel.getAsBoolean()) throw new Engine.Cancelled();
            try {
                List<ModFile> fs = files(kind, dep, mc, loader);
                ModFile pick = null;
                for (ModFile x : fs) if (x.url() != null && x.channel().equals("release")) { pick = x; break; }
                if (pick == null) for (ModFile x : fs) if (x.url() != null) { pick = x; break; }
                if (pick == null) {
                    eng.log.accept(I18n.t("Залежність ") + dep + I18n.t(" недоступна автоматично — встанови вручну."));
                    continue;
                }
                install(kind, dep, pick, mc, loader, dir, seen, true, status, cancel);
            } catch (Engine.Cancelled c) {
                throw c;
            } catch (Exception e) {
                eng.log.accept(I18n.t("Не вдалося поставити залежність ") + dep + ": " + e.getMessage());
            }
        }
    }
}

/* ======================================================================
 *  Тема та власні компоненти (дизайн)
 * ====================================================================== */
final class Theme {
    // палітра: темна або світла тема + довільний колір акценту (змінюється через Theme.apply)
    static boolean light = false;
    static final Color DEFAULT_ACCENT = new Color(0x6C7BFF); // тема за замовчуванням
    static final Color NEUTRAL_ACCENT = new Color(0x1BD96A); // зелений: стара нейтральна палітра без відтінку
    static Color accent = DEFAULT_ACCENT; // колір, який обрав користувач (ACC2 може бути трохи скоригований для читабельності)
    static Color BG1, BG2, CARD, CARD2, ACC1, ACC2, ACC_HI, ACC_ON, TEXT, MUTED, LINE, INPUT, SIDE;
    static Color SEL, SEL_FIELD, SEL_FIELD_FG, SUB, ICON, ICON_HI, NAV_IDLE, DIS_BG, DIS_FG, DIS_ICON, DIS_INPUT;
    static Color SEC_HOV, SEC_PR, CARD_HOV, CHECK_OFF, DATE, CONSOLE_FG, THUMB, THUMB_HOV, THUMB_DRAG;
    static { apply(false, DEFAULT_ACCENT); }

    private static Color c(int rgb) { return new Color(rgb); }

    static double lum(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    static Color parseHex(String s, Color def) {
        try { return new Color(Integer.parseInt(s.trim().replace("#", ""), 16) & 0xFFFFFF); } catch (Exception e) { return def; }
    }

    static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    /** Світла тема: білі накладки при наведенні стають темними (і навпаки). */
    static Color ov(int a) {
        a = Math.max(0, Math.min(255, a));
        return light ? new Color(0, 0, 0, Math.round(a * 0.8f)) : new Color(255, 255, 255, a);
    }

    /** Колір акценту з прозорістю. */
    static Color acc(int a) { return new Color(ACC2.getRed(), ACC2.getGreen(), ACC2.getBlue(), Math.max(0, Math.min(255, a))); }

    private static float kb, kt;

    /** Нейтральний колір, підфарбований відтінком теми. */
    private static Color t(int rgb, float k) {
        Color c = new Color(rgb);
        if (k <= 0f) return c;
        float[] f = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        float[] ac = Color.RGBtoHSB(accent.getRed(), accent.getGreen(), accent.getBlue(), null);
        return Color.getHSBColor(ac[0], Math.min(1f, Math.max(f[1], ac[1] * k)), f[2]);
    }

    static void apply(boolean lt, Color want) {
        light = lt;
        accent = new Color(want.getRed(), want.getGreen(), want.getBlue());
        Color a = accent;
        // щоб текст і елементи лишалися читабельними: надто світлий акцент у світлій темі трохи темнішає, надто темний у темній — світлішає
        for (int i = 0; i < 40 && lt && lum(a) > 0.30; i++) a = Fx.mix(a, Color.BLACK, 0.06f);
        for (int i = 0; i < 40 && !lt && lum(a) < 0.10; i++) a = Fx.mix(a, Color.WHITE, 0.08f);
        // колір теми фарбує й поверхні (фони, картки, поля), як темна/світла тема; стандартний зелений лишає нейтральну палітру
        kb = accent.equals(NEUTRAL_ACCENT) ? 0f : (lt ? 0.16f : 0.55f);
        kt = kb * 0.2f;
        ACC2 = a;
        ACC1 = Fx.mix(a, Color.BLACK, 0.22f);
        ACC_HI = Fx.mix(a, Color.WHITE, 0.22f);
        ACC_ON = lum(a) > 0.32 ? Fx.mix(a, Color.BLACK, 0.85f) : Color.WHITE;
        if (!lt) {
            BG1 = t(0x1B1C20, kb); BG2 = t(0x16171B, kb); CARD = t(0x23252A, kb); CARD2 = t(0x2E3137, kb);
            TEXT = t(0xF2F3F5, kt); MUTED = t(0x9A9EA6, kt); LINE = t(0x31343B, kb); INPUT = t(0x1A1C20, kb); SIDE = t(0x191B1E, kb);
            SUB = t(0xB9BDC4, kt); ICON = t(0xD5D8DD, kt); ICON_HI = Color.WHITE; NAV_IDLE = t(0xA9ADB5, kt);
            DIS_BG = t(0x2B2E34, kb); DIS_FG = t(0x7C8089, kt); DIS_ICON = t(0x575A61, kt); DIS_INPUT = t(0x202226, kb);
            SEC_HOV = t(0x3A3E46, kb); SEC_PR = t(0x262930, kb); CARD_HOV = t(0x2A2D33, kb); CHECK_OFF = t(0x4A4E56, kb);
            DATE = t(0x7E828A, kt); CONSOLE_FG = t(0xD4D5D6, kt);
            THUMB = t(0x3A3D44, kb); THUMB_HOV = t(0x535862, kb); THUMB_DRAG = t(0x6C717B, kb);
            SEL = Fx.mix(CARD2, ACC1, 0.32f);
            SEL_FIELD = Fx.mix(ACC1, Color.BLACK, 0.25f);
            SEL_FIELD_FG = Color.WHITE;
        } else {
            BG1 = t(0xE3E5EA, kb); BG2 = t(0xF3F4F7, kb); CARD = t(0xFFFFFF, kb); CARD2 = t(0xE9EBEF, kb);
            TEXT = t(0x1B1C20, kt); MUTED = t(0x626771, kt); LINE = t(0xD5D8DF, kb); INPUT = t(0xF0F2F5, kb); SIDE = t(0xEDEEF2, kb);
            SUB = t(0x4D525B, kt); ICON = t(0x4A4E57, kt); ICON_HI = t(0x111216, kt); NAV_IDLE = t(0x5B606A, kt);
            DIS_BG = t(0xDDE0E5, kb); DIS_FG = t(0x9AA0A9, kt); DIS_ICON = t(0xB0B5BD, kt); DIS_INPUT = t(0xE6E8EC, kb);
            SEC_HOV = t(0xD9DCE2, kb); SEC_PR = t(0xCDD1D8, kb); CARD_HOV = t(0xF0F2F5, kb); CHECK_OFF = t(0xB4B9C1, kb);
            DATE = t(0x7B808A, kt); CONSOLE_FG = t(0x23252A, kt);
            THUMB = t(0xC4C8CF, kb); THUMB_HOV = t(0xA3A9B2, kb); THUMB_DRAG = t(0x8D939D, kb);
            SEL = Fx.mix(CARD2, ACC2, 0.28f);
            SEL_FIELD = Fx.mix(ACC2, Color.WHITE, 0.55f);
            SEL_FIELD_FG = TEXT;
        }
    }

    /** Глобальні налаштування Swing (підказки, діалоги) під поточну палітру. */
    static void applyUI() {
        UIManager.put("OptionPane.background", CARD);
        UIManager.put("Panel.background", CARD);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("ToolTip.background", CARD2);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.font", font(Font.PLAIN, 13));
        UIManager.put("ToolTip.border", new javax.swing.border.CompoundBorder(new LineBorder(LINE, 1), new EmptyBorder(5, 9, 5, 9)));
    }

    /** Кольори, які вже збережені у віджетах під час створення й мають бути замінені при зміні теми. */
    static Color[] palette() {
        return new Color[]{BG1, BG2, CARD, CARD2, SIDE, INPUT, LINE, TEXT, MUTED, SUB, ACC1, ACC2, ACC_ON, CONSOLE_FG, SEL};
    }

    static Map<Integer, Color> remap(Color[] old) {
        Color[] nw = palette();
        Map<Integer, Color> m = new HashMap<>();
        for (int i = 0; i < old.length; i++) if (!old[i].equals(nw[i])) m.putIfAbsent(old[i].getRGB(), nw[i]);
        return m;
    }

    private static Border recolorBorder(Border b, Map<Integer, Color> m) {
        if (b instanceof LineBorder lb) {
            Color n = m.get(lb.getLineColor().getRGB());
            return n == null ? b : new LineBorder(n, lb.getThickness(), lb.getRoundedCorners());
        }
        if (b instanceof javax.swing.border.CompoundBorder cb)
            return new javax.swing.border.CompoundBorder(recolorBorder(cb.getOutsideBorder(), m), recolorBorder(cb.getInsideBorder(), m));
        return b;
    }

    /** Обходить дерево компонентів і міняє старі кольори палітри на нові (власні компоненти й так читають Theme.* при малюванні). */
    static void recolor(Component c, Map<Integer, Color> m) {
        if (c.isForegroundSet()) {
            Color f = c.getForeground();
            Color n = f.getAlpha() == 255 ? m.get(f.getRGB()) : null;
            if (n != null) c.setForeground(n);
        }
        if (c.isBackgroundSet()) {
            Color f = c.getBackground();
            Color n = f.getAlpha() == 255 ? m.get(f.getRGB()) : null;
            if (n != null) c.setBackground(n);
        }
        if (c instanceof JComponent jc) {
            Border b = jc.getBorder();
            if (b != null) { Border nb = recolorBorder(b, m); if (nb != b) jc.setBorder(nb); }
        }
        if (c instanceof javax.swing.text.JTextComponent t) {
            t.setCaretColor(ACC2);
            t.setSelectionColor(SEL_FIELD);
            t.setSelectedTextColor(SEL_FIELD_FG);
        }
        if (c instanceof JScrollBar sb && sb.getUI() instanceof SlimScrollBarUI) sb.setUI(new SlimScrollBarUI());
        if (c instanceof Combo<?> cb) cb.refreshTheme();
        if (c instanceof Container ct) for (Component ch : ct.getComponents()) recolor(ch, m);
        c.repaint();
    }

    static final int TITLE_H = 48, SIDE_W = 76;
    static final String DISCORD_ID = "1558210355229106416";

    private static final Font BASE_FONT = new Font("Segoe UI", Font.PLAIN, 14);
    private static final Map<Long, Font> FONTS = new ConcurrentHashMap<>();

    /** Шрифти незмінні, тож кешуємо: раніше кожне малювання створювало нові об'єкти Font. */
    static Font font(int style, float size) {
        long k = ((long) style << 32) | (Float.floatToIntBits(size) & 0xFFFFFFFFL);
        Font f = FONTS.get(k);
        if (f == null) { f = BASE_FONT.deriveFont(style, size); FONTS.put(k, f); }
        return f;
    }

    /** Логотип PaxLauncher: ізометричний блок трави з Minecraft. */
    static java.awt.image.BufferedImage icon(int sz) { return icon(sz, true); }

    private static void face(Graphics2D g, double ox, double oy, double ux, double uy, double vx, double vy, java.util.function.BiFunction<Integer, Integer, Color> col) {
        java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform(ux, uy, vx, vy, ox, oy);
        Graphics2D f = (Graphics2D) g.create();
        f.transform(at);
        for (int r = 0; r < 8; r++)
            for (int k = 0; k < 8; k++) {
                f.setColor(col.apply(k, r));
                f.fill(new Rectangle2D.Double(k / 8.0 - 0.004, r / 8.0 - 0.004, 1 / 8.0 + 0.008, 1 / 8.0 + 0.008));
            }
        f.dispose();
    }

    private static Color shade(int rgb, double f, int n) {
        int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 255) * f + n)), gg = (int) Math.max(0, Math.min(255, ((rgb >> 8) & 255) * f + n)),
                b = (int) Math.max(0, Math.min(255, (rgb & 255) * f + n));
        return new Color(r, gg, b);
    }

    private static final String LOGO_B64 = String.join("",
        "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");
    private static java.awt.image.BufferedImage logoArt;

    private static synchronized java.awt.image.BufferedImage art() {
        if (logoArt == null) {
            try { logoArt = javax.imageio.ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(LOGO_B64))); }
            catch (Exception ex) { logoArt = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB); }
        }
        return logoArt;
    }

    private static final Map<Integer, java.awt.image.BufferedImage> ICONS = new HashMap<>();

    /** Логотип запускача — блок трави з фото користувача. */
    static java.awt.image.BufferedImage icon(int sz, boolean rounded) {
        java.awt.image.BufferedImage c = ICONS.get(sz);
        if (c != null) return c;
        java.awt.image.BufferedImage cur = art();
        if (sz >= 40) { // великі розміри — чіткі пікселі, як у грі
            java.awt.image.BufferedImage px = new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = px.createGraphics();
            pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            pg.drawImage(cur, 0, 0, sz, sz, null);
            pg.dispose();
            ICONS.put(sz, px);
            return px;
        }
        // зменшення поетапно (вдвічі) — без «зубців»
        int w = cur.getWidth();
        while (w / 2 >= sz) {
            w /= 2;
            java.awt.image.BufferedImage n = new java.awt.image.BufferedImage(w, w, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = n.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cur, 0, 0, w, w, null);
            g.dispose();
            cur = n;
        }
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(cur, 0, 0, sz, sz, null);
        g.dispose();
        ICONS.put(sz, im);
        return im;
    }

    private static final Map<Integer, java.awt.image.BufferedImage> TEX = new HashMap<>();

    private static int th(int a, int b) {
        int h = a * 374761393 + b * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    private static int tint(int rgb, int d) {
        int r = Math.max(0, Math.min(255, ((rgb >> 16) & 255) + d)), g = Math.max(0, Math.min(255, ((rgb >> 8) & 255) + d)), b = Math.max(0, Math.min(255, (rgb & 255) + d));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Текстура 16×16: kind 0 — трава (face 0 верх / 1 бік), kind 1 — алмазна руда. */
    private static java.awt.image.BufferedImage tex(int kind, int face) {
        int key = kind * 2 + face;
        java.awt.image.BufferedImage t = TEX.get(key);
        if (t != null) return t;
        t = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                int n = th(x, y + key * 31) % 25 - 12;
                int c;
                if (kind == 0) {
                    if (face == 0) c = tint(0x79B83F, n);
                    else {
                        int fringe = 3 + th(x, 77) % 3;
                        int dn = n - (th(x, y + 9) % 100 < 14 ? 22 : 0);
                        c = y < fringe ? tint(0x79B83F, n) : tint(0x866043, dn);
                    }
                } else {
                    c = tint(0x7E7F83, n);
                    int q = th(x / 2, y / 2 + 5) % 100;
                    if (face == 1 && q < 22) c = tint(0x5DECF5, n / 2);
                    else if (face == 0 && q < 14) c = tint(0x5DECF5, n / 2);
                }
                t.setRGB(x, y, c);
            }
        TEX.put(key, t);
        return t;
    }

    /** Ізометричний куб із піксельними текстурами Minecraft (kind 0 — блок трави, 1 — руда). */
    static void texCube(Graphics2D g0, int x, int y, int sz, int kind) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        double S = sz;
        java.awt.geom.AffineTransform base = g.getTransform();
        // ліва грань
        g.translate(x, y);
        g.drawImage(tex(kind, 1), new java.awt.geom.AffineTransform(S / 32, S / 64, 0, S / 32, 0, S / 4), null);
        g.setColor(new Color(0, 0, 0, 70));
        g.fillPolygon(new int[]{0, (int) (S / 2), (int) (S / 2), 0}, new int[]{(int) (S / 4), (int) (S / 2), (int) S, (int) (S * 3 / 4)}, 4);
        // права грань
        g.drawImage(tex(kind, 1), new java.awt.geom.AffineTransform(S / 32, -S / 64, 0, S / 32, S / 2, S / 2), null);
        g.setColor(new Color(0, 0, 0, 130));
        g.fillPolygon(new int[]{(int) (S / 2), (int) S, (int) S, (int) (S / 2)}, new int[]{(int) (S / 2), (int) (S / 4), (int) (S * 3 / 4), (int) S}, 4);
        // верх
        g.drawImage(tex(kind, 0), new java.awt.geom.AffineTransform(S / 32, S / 64, -S / 32, S / 64, S / 2, 0), null);
        g.setTransform(base);
        g.dispose();
    }

    /** Ізометричний куб у квадраті size×size. grass — трава зверху й «торочки» на гранях. */
    static void cube(Graphics2D g, int x, int y, int size, Color top, Color left, Color right, boolean grass) {
        double w = size, h = size;
        int cx = x + size / 2;
        int[] tx = {cx, (int) (x + w), cx, x};
        int[] ty = {(int) (y), (int) (y + h * 0.25), (int) (y + h * 0.5), (int) (y + h * 0.25)};
        int[] lx = {x, cx, cx, x};
        int[] ly = {(int) (y + h * 0.25), (int) (y + h * 0.5), (int) (y + h), (int) (y + h * 0.75)};
        int[] rx = {cx, (int) (x + w), (int) (x + w), cx};
        int[] ry = {(int) (y + h * 0.5), (int) (y + h * 0.25), (int) (y + h * 0.75), (int) (y + h)};
        g.setColor(left); g.fillPolygon(lx, ly, 4);
        g.setColor(right); g.fillPolygon(rx, ry, 4);
        if (grass) {
            g.setColor(top.darker());
            g.fillPolygon(new int[]{x, cx, cx, x}, new int[]{ly[0], ly[1], (int) (ly[1] + h * 0.14), (int) (ly[0] + h * 0.14)}, 4);
            g.setColor(top.darker().darker());
            g.fillPolygon(new int[]{cx, rx[1], rx[1], cx}, new int[]{ry[0], ry[1], (int) (ry[1] + h * 0.14), (int) (ry[0] + h * 0.14)}, 4);
        }
        g.setColor(top); g.fillPolygon(tx, ty, 4);
        g.setColor(new Color(0, 0, 0, 70));
        g.setStroke(new BasicStroke(Math.max(1f, size / 24f)));
        g.drawPolygon(tx, ty, 4);
        g.drawLine(cx, ty[2], cx, y + size);
    }

    /** Номер дефолтного скіна, який зараз показується в аватарах (див. skinFor). */
    static volatile int skin = 0;

    private static final int SKINS = 6;

    /** Стабільно обирає дефолтний скін (Steve, Alex…) за ім'ям гравця. */
    static int skinFor(String name) {
        if (name == null || name.isEmpty()) return 0;
        return Math.floorMod(name.toLowerCase().hashCode(), SKINS);
    }

    /** Піксельна голова гравця 8×8: обличчя Steve / Alex та інші дефолтні скіни. */
    static void head(Graphics2D g, int x, int y, int sz) {
        // H — волосся, S — шкіра, D — тінь/ніс, W — біле ока, E — зіниця, M — рот
        String[] steve = {"HHHHHHHH", "HHHHHHHH", "HSSSSSSH", "SSSSSSSS", "SWESSEWS", "SSSDDSSS", "SSMMMMSS", "SSMSSMSS"};
        String[] alex  = {"HHHHHHHH", "HHHHHHHH", "HHSSSSHH", "HSSSSSSH", "HWESSEWH", "SSSSSSSS", "SSSMMSSS", "SSSSSSSS"};
        //                 hair      skin      shadow    eyes      mouth
        int[][] pal = {
                {0x4A3222, 0xB98A6A, 0x946A4E, 0x4B3F9E, 0x8A5A44}, // Steve
                {0xC9783A, 0xDDA98A, 0xB98468, 0x3E8F3A, 0xB06458}, // Alex
                {0x1E1E24, 0x8B5A3C, 0x6E4228, 0x2B2B2B, 0x5A3320}, // темне волосся, смагла шкіра
                {0xE0B84C, 0xF0C7A4, 0xC99C7C, 0x2F6FB8, 0xC4766A}, // блонд
                {0x7B3F9E, 0xC68E6C, 0xA0704F, 0x1BD96A, 0x8E4B45}, // фіолетове волосся
                {0xB8402A, 0xE8B596, 0xBF8A6D, 0x6B4A2A, 0xB5655A}  // руде
        };
        int v = Math.floorMod(skin, SKINS);
        String[] rows = (v == 1 || v == 3 || v == 5) ? alex : steve;
        int[] c = pal[v];
        int cell = Math.max(1, sz / 8);
        for (int r = 0; r < 8; r++)
            for (int k = 0; k < 8; k++) {
                Color col;
                switch (rows[r].charAt(k)) {
                    case 'H': col = new Color(c[0]); break;
                    case 'W': col = Color.WHITE; break;
                    case 'E': col = new Color(c[3]); break;
                    case 'M': col = new Color(c[4]); break;
                    case 'D': col = new Color(c[2]); break;
                    default: col = new Color(c[1]);
                }
                g.setColor(col);
                g.fillRect(x + k * cell, y + r * cell, cell, cell);
            }
    }

    /** Піксельна голова гравця зі скругленими кутами (sz кратне 8). */
    static void headRound(Graphics2D g0, int x, int y, int sz, int arc) {
        BufferedImage im = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        head(g, 0, 0, sz);
        g.dispose();
        BufferedImage out = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = out.createGraphics();
        aa(mg);
        mg.setColor(Color.WHITE);
        mg.fillRoundRect(0, 0, sz, sz, arc, arc);
        mg.setComposite(AlphaComposite.SrcIn);
        mg.drawImage(im, 0, 0, null);
        mg.dispose();
        g0.drawImage(out, x, y, null);
    }

    /** Лінійні іконки: 0 — дім, 1 — компас, 2 — шестерня, 3 — консоль, 4 — плюс, 5 — папка. */
    static void railIcon(Graphics2D g0, int kind, int cx, int cy, Color c, double scale) {
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(cx, cy);
        g.scale(scale, scale);
        g.setColor(c);
        g.setStroke(new BasicStroke((float) (2.0 / Math.sqrt(scale)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case 0: {
                Path2D.Float roof = new Path2D.Float();
                roof.moveTo(-10, 0); roof.lineTo(0, -9); roof.lineTo(10, 0);
                g.draw(roof);
                Path2D.Float body = new Path2D.Float();
                body.moveTo(-7, -3); body.lineTo(-7, 9); body.lineTo(7, 9); body.lineTo(7, -3);
                g.draw(body);
                Path2D.Float door = new Path2D.Float();
                door.moveTo(-2.5f, 9); door.lineTo(-2.5f, 3); door.lineTo(2.5f, 3); door.lineTo(2.5f, 9);
                g.draw(door);
                break;
            }
            case 1: {
                g.drawOval(-10, -10, 20, 20);
                Path2D.Float d = new Path2D.Float();
                d.moveTo(5, -5); d.lineTo(1.5f, 1.5f); d.lineTo(-5, 5); d.lineTo(-1.5f, -1.5f); d.closePath();
                g.draw(d);
                break;
            }
            case 2: {
                g.drawOval(-6, -6, 12, 12);
                g.drawOval(-2, -2, 4, 4);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    g.draw(new java.awt.geom.Line2D.Double(Math.cos(a) * 6.5, Math.sin(a) * 6.5, Math.cos(a) * 10.5, Math.sin(a) * 10.5));
                }
                break;
            }
            case 3: {
                g.drawRoundRect(-11, -9, 22, 18, 6, 6);
                g.drawPolyline(new int[]{-6, -2, -6}, new int[]{-3, 0, 3}, 3);
                g.drawLine(1, 4, 6, 4);
                break;
            }
            case 4:
                g.drawLine(-9, 0, 9, 0);
                g.drawLine(0, -9, 0, 9);
                break;
            default: {
                Path2D.Float f = new Path2D.Float();
                f.moveTo(-10, -5); f.lineTo(-10, -8); f.lineTo(-3, -8); f.lineTo(-1, -5); f.lineTo(10, -5);
                f.lineTo(10, 8); f.lineTo(-10, 8); f.closePath();
                g.draw(f);
            }
        }
        g.dispose();
    }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
}

final class Backdrop extends JPanel {
    int arc = 10;
    boolean side = true; // true — вузька панель зліва й заокруглена область вмісту (як у Modrinth App)

    Backdrop(LayoutManager lm) { super(lm); setOpaque(true); setBackground(Theme.BG2); }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        g.setColor(Theme.BG1);
        g.fillRect(0, 0, w, h);
        g.setColor(Theme.BG2);
        if (side) g.fillRoundRect(Theme.SIDE_W, Theme.TITLE_H, w - Theme.SIDE_W + 30, h - Theme.TITLE_H + 30, 24, 24);
        else g.fillRect(0, Theme.TITLE_H, w, h - Theme.TITLE_H);
        g.setColor(Theme.ov(28));
        if (arc > 0) g.drawRoundRect(0, 0, w - 1, h - 1, arc * 2, arc * 2);
        else g.drawRect(0, 0, w - 1, h - 1);
        g.dispose();
    }
}

final class Card extends JPanel {
    Card(LayoutManager lm, int pad) {
        super(lm);
        setOpaque(false);
        setBorder(new EmptyBorder(pad, pad + 2, pad, pad + 2));
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.setColor(Theme.CARD);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
        g.dispose();
        super.paintComponent(g0);
    }
}

/** Плавна анімація наведення / натискання / вибору: значення 0..1 м'яко наближаються до цілі. */
final class Fx {
    private final JComponent c;
    private float hov, prs, sel, tHov, tPrs, tSel;
    private final javax.swing.Timer t;

    Fx(JComponent c) {
        this.c = c;
        t = new javax.swing.Timer(15, e -> step());
    }

    private void go() { if (!t.isRunning()) t.start(); }

    void hover(boolean b) { tHov = b ? 1 : 0; go(); }
    void press(boolean b) { tPrs = b ? 1 : 0; go(); }
    void sel(boolean b, boolean instant) { tSel = b ? 1 : 0; if (instant) sel = tSel; go(); }
    float h() { return hov; }
    float p() { return prs; }
    float s() { return sel; }

    private static float ease(float cur, float tgt, float k) {
        float d = tgt - cur;
        return Math.abs(d) < 0.01f ? tgt : cur + d * k;
    }

    private void step() {
        hov = ease(hov, tHov, 0.26f);
        prs = ease(prs, tPrs, 0.40f);
        sel = ease(sel, tSel, 0.22f);
        c.repaint();
        if (hov == tHov && prs == tPrs && sel == tSel) t.stop();
    }

    static Color mix(Color a, Color b, float f) {
        f = Math.max(0, Math.min(1, f));
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * f),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * f),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * f));
    }

    static Color alpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(c.getAlpha() * a))));
    }

    /** Стискає малювання навколо центру (ефект натискання). */
    static void squeeze(Graphics2D g, int w, int h, float press) {
        double sc = 1 - 0.04 * press;
        g.translate(w / 2.0, h / 2.0);
        g.scale(sc, sc);
        g.translate(-w / 2.0, -h / 2.0);
    }
}

/** Плавне гортання коліщатком миші. */
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

/** Панель сторінок: при зміні сторінки стара м'яко розчиняється. */
final class FadePanel extends JPanel {
    private BufferedImage snap;
    private float alpha;
    private final javax.swing.Timer t = new javax.swing.Timer(15, e -> {
        alpha -= 0.11f;
        if (alpha <= 0) { alpha = 0; snap = null; ((javax.swing.Timer) e.getSource()).stop(); }
        repaint();
    });

    FadePanel(LayoutManager lm) { super(lm); }

    void change(Runnable r) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || !isShowing()) { r.run(); return; }
        t.stop();
        snap = null;
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        super.paint(g);
        g.dispose();
        r.run();
        snap = im;
        alpha = 1f;
        t.start();
    }

    @Override public void paint(Graphics g) {
        super.paint(g);
        if (snap != null && alpha > 0) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, alpha * alpha)));
            g2.drawImage(snap, 0, 0, null);
            g2.dispose();
        }
    }
}

final class Logo extends JComponent {
    Logo() {
        setPreferredSize(new Dimension(200, 70));
        setMaximumSize(new Dimension(220, 70));
        setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.setFont(Theme.font(Font.BOLD, 28));
        g.setPaint(new GradientPaint(0, 0, Theme.ACC1, 190, 0, Theme.ACC2));
        g.drawString("PaxLauncher", 8, 42);
        g.dispose();
    }
}

/** Плоска кнопка-посилання у верхньому рядку: значок + текст (Discord, підтримка). */
final class LinkBtn extends JButton {
    private final Fx fx = new Fx(this);
    private final int icon; // 0 = Discord, 1 = серце (підтримка)

    LinkBtn(String text, int icon) {
        super(text);
        this.icon = icon;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.font(Font.BOLD, 13));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(14 + 20 + 8 + fm.stringWidth(getText()) + 14, 34);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        float hv = fx.h();
        Fx.squeeze(g, w, h, fx.p());
        if (hv > 0) {
            g.setColor(Theme.ov(Math.round(18 * hv + 10 * fx.p())));
            g.fillRoundRect(0, 0, w, h, 12, 12);
        }
        Color c = Fx.mix(Theme.ICON, Theme.ICON_HI, hv);
        g.setColor(c);
        g.translate(14, (h - 18) / 2);
        if (icon == 0) {
            java.awt.geom.Area a = new java.awt.geom.Area(new RoundRectangle2D.Float(0, 1.5f, 20, 14, 9, 9));
            a.add(new java.awt.geom.Area(new RoundRectangle2D.Float(3, 12, 14, 5.5f, 4, 4)));
            a.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(4.2f, 6.2f, 4f, 5f)));
            a.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(11.8f, 6.2f, 4f, 5f)));
            g.fill(a);
        } else {
            Path2D.Float hp = new Path2D.Float();
            hp.moveTo(10, 17.5f);
            hp.curveTo(-4, 8, 3, -1, 10, 4.5f);
            hp.curveTo(17, -1, 24, 8, 10, 17.5f);
            hp.closePath();
            g.fill(hp);
        }
        g.translate(-14, -(h - 18) / 2);
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        g.drawString(getText(), 14 + 20 + 8, (h - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
    }
}

final class Btn extends JButton {
    enum Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    private final Kind kind;
    private final Fx fx = new Fx(this);
    private int glyph; // 0 = текст, 1 = згорнути, 2 = закрити, 3 = розгорнути, 4 = відновити
    private boolean plus; // великий значок «+» зліва від тексту

    void setPlus(boolean plus) { this.plus = plus; repaint(); }

    Btn(String text, Kind kind) {
        super(text);
        this.kind = kind;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.font(Font.BOLD, 14));
        setForeground(Theme.TEXT);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(new EmptyBorder(11, 22, 11, 22));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    void setGlyph(int glyph) { this.glyph = glyph; repaint(); }

    @Override public Dimension getPreferredSize() {
        return glyph != 0 ? new Dimension(46, Theme.TITLE_H) : super.getPreferredSize();
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        float hv = isEnabled() ? fx.h() : 0, pr = isEnabled() ? fx.p() : 0;
        Color fg = kind == Kind.DANGER ? Fx.mix(Theme.TEXT, Color.WHITE, hv) : Theme.TEXT;
        if (glyph == 0) {
            Fx.squeeze(g, w, h, pr);
            int arc = h >= 44 ? 14 : 10;
            Color face = null;
            if (!isEnabled()) {
                face = Theme.DIS_BG;
                fg = Theme.DIS_FG;
            } else {
                switch (kind) {
                    case PRIMARY:
                        face = Fx.mix(Fx.mix(Theme.ACC2, Theme.ACC_HI, hv), Theme.ACC1, pr);
                        fg = Theme.ACC_ON;
                        break;
                    case SECONDARY:
                        face = Fx.mix(Fx.mix(Theme.CARD2, Theme.SEC_HOV, hv), Theme.SEC_PR, pr);
                        break;
                    case GHOST:
                        face = Theme.ov(Math.round(24 * hv + 12 * pr));
                        break;
                    case DANGER:
                        face = new Color(0xC4, 0x2B, 0x1C, Math.round(255 * hv));
                        break;
                }
            }
            if (face != null && face.getAlpha() > 0) {
                g.setColor(face);
                g.fillRoundRect(0, 0, w, h, arc, arc);
            }
            // текст підганяється під розмір кнопки: велика кнопка — більший шрифт, вузька — менший
            String t = getText();
            float size = getFont().getSize2D();
            if (h >= 44) size = Math.max(size, Math.min(h * 0.36f, 30f));
            Font f = getFont().deriveFont(size);
            FontMetrics fm = g.getFontMetrics(f);
            int maxW = w - 24 - (plus ? Math.round(Math.min(h * 0.46f, 20f)) + 9 : 0);
            while (size > 10 && fm.stringWidth(t) > maxW) {
                size -= 0.5f;
                f = f.deriveFont(size);
                fm = g.getFontMetrics(f);
            }
            g.setFont(f);
            g.setColor(fg);
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            if (plus) {
                int ps = Math.round(Math.min(h * 0.46f, 20f)), gap = 9; // розмір плюсика
                int tw = fm.stringWidth(t), x0 = (w - (ps + gap + tw)) / 2;
                g.setStroke(new BasicStroke(Math.max(2.4f, ps / 7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int pcx = x0 + ps / 2, pcy = h / 2;
                g.drawLine(pcx - ps / 2 + 1, pcy, pcx + ps / 2 - 1, pcy);
                g.drawLine(pcx, pcy - ps / 2 + 1, pcx, pcy + ps / 2 - 1);
                g.drawString(t, x0 + ps + gap, ty);
            } else {
                g.drawString(t, (w - fm.stringWidth(t)) / 2, ty);
            }
        } else {
            if (isEnabled() && hv > 0) {
                g.setColor(kind == Kind.DANGER ? new Color(0xC4, 0x2B, 0x1C, Math.round(255 * hv)) : Theme.ov(Math.round(26 * hv)));
                g.fillRect(0, 0, w, h);
            }
            g.setColor(fg);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            int cx = w / 2, cy = h / 2;
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
            if (glyph == 1) g.drawLine(cx - 7, cy, cx + 7, cy);
            else if (glyph == 3) g.drawRect(cx - 7, cy - 7, 14, 14);
            else if (glyph == 4) {
                g.drawRect(cx - 7, cy - 3, 11, 11);
                g.drawPolyline(new int[]{cx - 3, cx - 3, cx + 7, cx + 7, cx + 4}, new int[]{cy - 3, cy - 7, cy - 7, cy + 3, cy + 3}, 5);
            } else {
                g.drawLine(cx - 7, cy - 7, cx + 7, cy + 7);
                g.drawLine(cx - 7, cy + 7, cx + 7, cy - 7);
            }
        }
        g.dispose();
    }
}

/** Іконка на вертикальній панелі зліва (як у Modrinth App). */
final class NavBtn extends JButton {
    private final Fx fx = new Fx(this);
    private final int icon;

    NavBtn(String cap, String text, int icon) {
        super(text);
        this.icon = icon;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setAlignmentX(Component.CENTER_ALIGNMENT);
        Dimension d = new Dimension(Theme.SIDE_W, 56);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    void setSel(boolean b) { fx.sel(b, false); }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight(), s = 46;
        float hv = fx.h(), sl = fx.s(), pr = fx.p();
        Fx.squeeze(g, w, h, pr);
        if (hv > 0 && sl < 1) {
            g.setColor(Theme.ov(Math.round(16 * hv * (1 - sl))));
            g.fillRoundRect((w - s) / 2, (h - s) / 2, s, s, 20, 20);
        }
        if (sl > 0) {
            g.setColor(Theme.acc(Math.round(48 * sl)));
            int ss = (int) Math.round(s * (0.8 + 0.2 * sl));
            g.fillRoundRect((w - ss) / 2, (h - ss) / 2, ss, ss, 20, 20);
        }
        Color c = Fx.mix(Fx.mix(Theme.NAV_IDLE, Theme.TEXT, hv), Theme.ACC2, sl);
        Theme.railIcon(g, icon, w / 2, h / 2, c, 1.35);
        g.dispose();
    }
}

/** Аватар гравця внизу панелі. */
final class AvatarBtn extends JButton {
    private final Fx fx = new Fx(this);

    AvatarBtn() {
        super();
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setAlignmentX(Component.CENTER_ALIGNMENT);
        Dimension d = new Dimension(Theme.SIDE_W, 56);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        Fx.squeeze(g, w, h, fx.p());
        if (fx.h() > 0) {
            g.setColor(Theme.ov(Math.round(16 * fx.h())));
            g.fillRoundRect((w - 46) / 2, (h - 46) / 2, 46, 46, 20, 20);
        }
        Theme.headRound(g, (w - 32) / 2, (h - 32) / 2, 32, 12);
        g.dispose();
    }
}

/** Кругла кнопка «назад / вперед» у верхній панелі. */
final class ArrowBtn extends JButton {
    private final int dir; // -1 — назад, +1 — вперед
    private final Fx fx = new Fx(this);

    ArrowBtn(int dir) {
        super();
        this.dir = dir;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.font(Font.PLAIN, 13));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Dimension d = new Dimension(36, 30);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        Fx.squeeze(g, w, h, isEnabled() ? fx.p() : 0);
        g.setColor(isEnabled() ? Theme.ov(Math.round(14 + 20 * fx.h())) : Theme.ov(8));
        g.fillRoundRect(0, 0, w, h, h, h);
        g.setColor(isEnabled() ? Theme.TEXT : Theme.DIS_ICON);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = w / 2, cy = h / 2;
        if (dir < 0) g.drawPolyline(new int[]{cx + 3, cx - 3, cx + 3}, new int[]{cy - 6, cy, cy + 6}, 3);
        else g.drawPolyline(new int[]{cx - 3, cx + 3, cx - 3}, new int[]{cy - 6, cy, cy + 6}, 3);
        g.dispose();
    }
}

/** Рядок із іконкою в правій панелі («Папка гри», «Консоль»…). */
final class ListBtn extends JButton {
    private final Fx fx = new Fx(this);
    private final int icon;

    ListBtn(String text, int icon) {
        super(text);
        this.icon = icon;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.font(Font.PLAIN, 14));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(100, 40));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        float hv = fx.h();
        Fx.squeeze(g, w, h, fx.p());
        if (hv > 0) {
            g.setColor(Theme.ov(Math.round(14 * hv + 8 * fx.p())));
            g.fillRoundRect(0, 0, w, h, 12, 12);
        }
        int dx = Math.round(2 * hv);
        Theme.railIcon(g, icon, 26 + dx, h / 2, Fx.mix(Theme.MUTED, Theme.TEXT, hv), 1.0);
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Fx.mix(Theme.ICON, Theme.ICON_HI, hv));
        g.drawString(getText(), 52 + dx, (h - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
    }
}

/** Вкладка під заголовком (Грати / Версії / Консоль). */
final class TabBtn extends JButton {
    private final Fx fx = new Fx(this);

    TabBtn(String text) {
        super(text);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.font(Font.BOLD, 16));
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    void setSel(boolean b) { fx.sel(b, false); }

    @Override public Dimension getPreferredSize() {
        return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + 6, 34);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        float sl = fx.s();
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Fx.mix(Theme.MUTED, Theme.ICON_HI, Math.max(sl, fx.h())));
        g.drawString(getText(), 3, (h - 6 - fm.getHeight()) / 2 + fm.getAscent() + 2);
        if (sl > 0) {
            g.setColor(Theme.ACC2);
            int uw = Math.round(w * sl);
            g.fillRect((w - uw) / 2, h - 4, uw, 3);
        }
        g.dispose();
    }
}

/** Панель, що тягнеться по ширині в’юпорта й росте вниз (для прокручуваних сторінок). */
final class VPanel extends JPanel implements Scrollable {
    private final GridBagConstraints gc = new GridBagConstraints();
    private int row;

    VPanel() {
        super(new GridBagLayout());
        setOpaque(false);
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;
    }

    /** Додає рядок на всю ширину з відступом зверху. */
    void row(Component c, int top) {
        gc.gridy = row++;
        gc.weighty = 0;
        gc.insets = new Insets(top, 0, 0, 0);
        add(c, gc);
    }

    /** Розпірка в кінці, щоб вміст притискався догори. */
    void end() {
        gc.gridy = row++;
        gc.weighty = 1;
        gc.insets = new Insets(0, 0, 0, 0);
        add(Box.createGlue(), gc);
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 28; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(60, (int) (r.height * 0.9)); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}

/** Картка з картинкою гри (як «Discover a modpack» у Modrinth App). */
final class ArtCard extends JComponent {
    static final int H = 266, IMG_H = 150;
    private final int art;
    private final double focus;
    private final String title, desc;
    private final Fx fx = new Fx(this);
    private BufferedImage masked;
    private int maskW;

    ArtCard(int art, double focus, String title, String desc, Runnable click) {
        this.art = art;
        this.focus = focus;
        this.title = title;
        this.desc = desc;
        setPreferredSize(new Dimension(300, H));
        setMinimumSize(new Dimension(180, H));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); }
            @Override public void mouseClicked(MouseEvent e) { click.run(); }
        });
    }

    /** Картинка w×h зі скругленими верхніми кутами (плавні краї через маску). */
    static BufferedImage topRounded(BufferedImage im, int w, int h, double focus) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        Theme.aa(ig);
        Art.cover(ig, im, 0, 0, w, h, focus);
        ig.dispose();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = out.createGraphics();
        Theme.aa(mg);
        mg.setColor(Color.WHITE);
        mg.fillRoundRect(0, 0, w, h + 24, 16, 16);
        mg.setComposite(AlphaComposite.SrcIn);
        mg.drawImage(img, 0, 0, null);
        mg.dispose();
        return out;
    }

    static List<String> wrap(String s, FontMetrics fm, int maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        String cur = "";
        String[] words = s.split(" ");
        boolean cut = false;
        for (String wd : words) {
            String t = cur.isEmpty() ? wd : cur + " " + wd;
            if (cur.isEmpty() || fm.stringWidth(t) <= maxW) { cur = t; continue; }
            out.add(cur);
            cur = wd;
            if (out.size() == maxLines) { cut = true; break; }
        }
        if (!cut && !cur.isEmpty()) out.add(cur);
        if (cut) {
            String last = out.get(out.size() - 1);
            while (!last.isEmpty() && fm.stringWidth(last + "…") > maxW) last = last.substring(0, last.length() - 1);
            out.set(out.size() - 1, last + "…");
        }
        return out;
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        if (w <= 0) { g.dispose(); return; }
        if (masked == null || maskW != w) {
            masked = topRounded(Art.get(art), w, IMG_H, focus);
            maskW = w;
        }
        g.setColor(Theme.CARD);
        g.fillRoundRect(0, 0, w, h, 16, 16);
        g.drawImage(masked, 0, 0, null);
        if (fx.h() > 0) {
            g.setColor(Theme.ov(Math.round(12 * fx.h())));
            g.fillRoundRect(0, 0, w, h, 16, 16);
            g.setColor(Theme.acc(Math.round(170 * fx.h())));
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(1, 1, w - 3, h - 3, 16, 16);
        }
        int pad = 16, y = IMG_H + 30;
        g.setFont(Theme.font(Font.BOLD, 16));
        g.setColor(Theme.TEXT);
        g.drawString(title, pad, y);
        g.setFont(Theme.font(Font.PLAIN, 13));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Theme.MUTED);
        int ly = y + 10 + fm.getAscent();
        for (String line : wrap(desc, fm, w - 2 * pad, 3)) {
            g.drawString(line, pad, ly);
            ly += fm.getHeight() + 1;
        }
        g.dispose();
    }
}

/** Реклама Minecraft-серверів над новинами: плавно змінюється кожні 40 с, клік відкриває інформацію про сервер. */
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
        // таймери крутяться, лише поки реклама видима (не на іншій сторінці, не в згорнутому/схованому вікні)
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

    /** Додати ще один сервер у ротацію. */
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

    /** Аватарка зі скругленими кутами. */
    static BufferedImage face(String b64) {
        try {
            BufferedImage im = javax.imageio.ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
            if (im == null) return null;
            int sz = 116; // 2x для чіткості, малюється у 58
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

    /** Вкладка (вікно) сервера: IP, версія, опис і посилання на Discord. */
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

/** Новини Minecraft у правій панелі (з launchercontent.mojang.com). */
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
            // нова стрічка запускача (v2) + стара — об'єднуємо й сортуємо за датою, щоб зверху були найсвіжіші
            String[] feeds = {"https://launchercontent.mojang.com/v2/news.json", "https://launchercontent.mojang.com/news.json"};
            List<Raw> raws = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String feed : feeds) {
                try {
                    String body = http.send(HttpRequest.newBuilder(URI.create(feed))
                            .header("User-Agent", "PaxLauncher/1.0").timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString()).body();
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
                    byte[] bytes = http.send(HttpRequest.newBuilder(base.resolve(r.img())).header("User-Agent", "PaxLauncher/1.0")
                            .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray()).body();
                    BufferedImage im = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
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
                        if (it.link() != null && Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(it.link()));
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

final class Field extends JTextField {
    Field(String text) {
        super(text);
        setOpaque(false);
        setForeground(Theme.TEXT);
        setCaretColor(Theme.ACC2);
        setSelectionColor(Theme.SEL_FIELD);
        setSelectedTextColor(Theme.SEL_FIELD_FG);
        setFont(Theme.font(Font.PLAIN, 14));
        setBorder(new EmptyBorder(11, 14, 11, 14));
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.setColor(Theme.INPUT);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
        g.setColor(Theme.LINE);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        if (hasFocus()) {
            g.setColor(Theme.ACC2);
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 12, 12);
        }
        g.dispose();
        super.paintComponent(g0);
    }
}

final class SlimScrollBarUI extends BasicScrollBarUI {
    @Override protected void configureScrollBarColors() {
        thumbColor = Theme.THUMB;
        trackColor = Theme.BG2;
    }

    private JButton zero() {
        JButton b = new JButton();
        Dimension d = new Dimension(0, 0);
        b.setPreferredSize(d); b.setMinimumSize(d); b.setMaximumSize(d);
        return b;
    }

    @Override protected JButton createDecreaseButton(int o) { return zero(); }
    @Override protected JButton createIncreaseButton(int o) { return zero(); }

    @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
        if (r.isEmpty() || !scrollbar.isEnabled()) return;
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        g2.setColor(isDragging ? Theme.THUMB_DRAG : isThumbRollover() ? Theme.THUMB_HOV : Theme.THUMB);
        boolean vert = scrollbar.getOrientation() == Adjustable.VERTICAL;
        int pad = 3;
        int x = vert ? r.x + pad : r.x + 1, y = vert ? r.y + 1 : r.y + pad;
        int w = vert ? r.width - 2 * pad : r.width - 2, h = vert ? r.height - 2 : r.height - 2 * pad;
        int arc = Math.max(2, Math.min(w, h));
        g2.fillRoundRect(x, y, w, h, arc, arc);
        g2.dispose();
    }

    /** Доріжка зафарбована кольором вмісту, тож ніяких світлих смуг. */
    @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
        Color bg = Theme.BG2;
        Container p = c.getParent();
        if (p instanceof JScrollPane) {
            Color vb = ((JScrollPane) p).getViewport().getBackground();
            if (vb != null) bg = vb;
        }
        g.setColor(bg);
        g.fillRect(r.x, r.y, r.width, r.height);
    }

    @Override public Dimension getPreferredSize(JComponent c) { return new Dimension(12, 12); }
}

final class Combo<T> extends JComboBox<T> {
    boolean iconed;

    void refreshTheme() {
        setUI(new ComboUI());
    }

    Combo() {
        setUI(new ComboUI());
        setOpaque(false);
        setBackground(Theme.INPUT);
        setForeground(Theme.TEXT);
        setFont(Theme.font(Font.PLAIN, 14));
        setBorder(new EmptyBorder(2, 2, 2, 2));
        setMaximumRowCount(10);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRenderer(new DefaultListCellRenderer() {
            private String sub = "";

            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                String full = v == null ? "" : String.valueOf(v), main = full;
                sub = "";
                int k = full.indexOf("  · ");
                if (k >= 0) { main = full.substring(0, k); sub = full.substring(k + 4); }
                super.getListCellRendererComponent(l, main, i, sel, false);
                setOpaque(true);
                setBackground(sel ? Theme.SEL : Theme.CARD2);
                setForeground(Theme.TEXT);
                setFont(Theme.font(Font.PLAIN, 14));
                setBorder(new EmptyBorder(8, 14, 8, 14));
                return this;
            }

            /** Примітка («встановлено», «знімок»…) — приглушеним кольором праворуч, без крапки. */
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                if (sub.isEmpty()) return;
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
                FontMetrics fm = g.getFontMetrics();
                int x = getWidth() - 14 - fm.stringWidth(sub);
                if (x > 14 + getFontMetrics(getFont()).stringWidth(getText()) + 12) {
                    g.setColor(Theme.MUTED);
                    g.drawString(sub, x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                }
                g.dispose();
            }
        });
    }

    /** Повністю власна відрисовка: ні білих смуг, ні системного виділення. */
    private static final class ComboUI extends BasicComboBoxUI {
        @Override protected JButton createArrowButton() {
            JButton b = new JButton() {
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    Theme.aa(g);
                    g.setColor(isPopupVisible(comboBox) ? Theme.TEXT : Theme.MUTED);
                    g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int cx = getWidth() / 2, cy = getHeight() / 2;
                    g.drawPolyline(new int[]{cx - 7, cx, cx + 7}, new int[]{cy - 3, cy + 4, cy - 3}, 3);
                    g.dispose();
                }
            };
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setOpaque(false);
            b.setBorder(null);
            b.setFocusable(false);
            return b;
        }

        @Override public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = (Graphics2D) g.create();
            Theme.aa(g2);
            int w = c.getWidth(), h = c.getHeight();
            g2.setColor(c.isEnabled() ? Theme.INPUT : Theme.DIS_INPUT);
            g2.fillRoundRect(0, 0, w, h, 12, 12);
            g2.setColor(Theme.LINE);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
            if (c.isEnabled() && (comboBox.hasFocus() || isPopupVisible(comboBox))) {
                g2.setColor(Theme.ACC2);
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawRoundRect(1, 1, w - 3, h - 3, 12, 12);
            }
            g2.dispose();
            paintCurrentValue(g, rectangleForCurrentValue(), false);
        }

        @Override public void paintCurrentValueBackground(Graphics g, Rectangle r, boolean hasFocus) { }

        @Override public void paintCurrentValue(Graphics g, Rectangle r, boolean hasFocus) {
            Object v = comboBox.getSelectedItem();
            if (v == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            Theme.aa(g2);
            g2.clipRect(r.x, r.y, r.width, r.height);
            if (((Combo<?>) comboBox).iconed) {
                String full = String.valueOf(v), main = full, sub = "";
                int k = full.indexOf("  · ");
                if (k >= 0) { main = full.substring(0, k); sub = full.substring(k + 4); }
                int is = 38;
                g2.drawImage(Theme.icon(is), r.x + 8, r.y + (r.height - is) / 2, null);
                int tx = r.x + 8 + is + 12;
                g2.setFont(comboBox.getFont().deriveFont(Font.BOLD, 15f));
                FontMetrics fm1 = g2.getFontMetrics();
                g2.setColor(comboBox.isEnabled() ? Theme.TEXT : Theme.MUTED);
                if (sub.isEmpty()) g2.drawString(main, tx, r.y + (r.height - fm1.getHeight()) / 2 + fm1.getAscent());
                else {
                    g2.drawString(main, tx, r.y + r.height / 2 - 2);
                    g2.setFont(comboBox.getFont().deriveFont(Font.PLAIN, 12f));
                    g2.setColor(Theme.MUTED);
                    g2.drawString(sub, tx, r.y + r.height / 2 + 14);
                }
                g2.dispose();
                return;
            }
            String all = String.valueOf(v), main = all, note = "";
            int dk = all.indexOf("  · ");
            if (dk >= 0) { main = all.substring(0, dk); note = all.substring(dk + 4); }
            g2.setFont(comboBox.getFont());
            g2.setColor(comboBox.isEnabled() ? Theme.TEXT : Theme.MUTED);
            FontMetrics fm = g2.getFontMetrics();
            int ty = r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(main, r.x + 12, ty);
            if (!note.isEmpty()) {
                g2.setFont(comboBox.getFont().deriveFont(Font.PLAIN, 12f));
                g2.setColor(Theme.MUTED);
                g2.drawString(note, r.x + 12 + fm.stringWidth(main) + 10, ty);
            }
            g2.dispose();
        }

        @Override protected ComboPopup createPopup() {
            BasicComboPopup p = new BasicComboPopup(comboBox) {
                @Override protected JScrollPane createScroller() {
                    JScrollPane sp = super.createScroller();
                    sp.setBorder(null);
                    sp.setBackground(Theme.CARD2);
                    sp.getViewport().setBackground(Theme.CARD2);
                    sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
                    Smooth.install(sp);
                    return sp;
                }
            };
            p.setBorder(new LineBorder(Theme.LINE, 1));
            p.setBackground(Theme.CARD2);
            p.setOpaque(true);
            JList<?> l = p.getList();
            l.setBackground(Theme.CARD2);
            l.setForeground(Theme.TEXT);
            l.setSelectionBackground(Theme.SEL);
            l.setSelectionForeground(Theme.TEXT);
            return p;
        }
    }
}

final class Check extends JCheckBox {
    Check(String text, boolean selected) {
        super(text, selected);
        setOpaque(false);
        setForeground(Theme.TEXT);
        setFont(Theme.font(Font.PLAIN, 14));
        setFocusPainted(false);
        setIconTextGap(8);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setIcon(new Icon() {
            @Override public int getIconWidth() { return 20; }
            @Override public int getIconHeight() { return 20; }

            @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                if (((AbstractButton) c).isSelected()) {
                    g.setColor(Theme.ACC2);
                    g.fillRoundRect(x, y, 20, 20, 7, 7);
                    g.setColor(Theme.ACC_ON);
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawPolyline(new int[]{x + 5, x + 9, x + 15}, new int[]{y + 10, y + 14, y + 6}, 3);
                } else {
                    g.setColor(Theme.INPUT);
                    g.fillRoundRect(x, y, 20, 20, 7, 7);
                    g.setColor(Theme.CHECK_OFF);
                    g.setStroke(new BasicStroke(1f));
                    g.drawRoundRect(x, y, 19, 19, 7, 7);
                }
                g.dispose();
            }
        });
    }
}

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

    /** Клік по доріжці одразу переносить повзунок туди і дає тягнути далі. */
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

/** Власні діалоги замість JOptionPane (кнопки в стилі запускача). */
/** Вибір файлів і тек у стилі запускача (замість стандартного JFileChooser): ті самі картки, поля, кнопки та скрол. */
final class FilePick {
    private record Ent(File f, boolean dir) { }

    private final DefaultListModel<Ent> model = new DefaultListModel<>();
    private final JList<Ent> list = new JList<>(model);
    private final Field path = new Field("");
    private final int mode; // JFileChooser.FILES_ONLY / DIRECTORIES_ONLY / FILES_AND_DIRECTORIES
    private File dir;
    private JDialog dialog;
    private boolean forced;

    private FilePick(int mode, boolean multi) {
        this.mode = mode;
        list.setSelectionMode(multi ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
    }

    /** null — скасовано. Якщо нічого не виділено (і режим дозволяє теки) — повертається поточна тека. */
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
            pn.add(LauncherWindow.label(I18n.t("Нічого не виділено — буде обрано поточну теку."), 12, false, Theme.MUTED), BorderLayout.SOUTH);
        return pn;
    }

    /** d == null — список дисків (над коренем файлової системи). */
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
        if (dir == null || !typed.equals(dir)) { // шлях вписали вручну й одразу натиснули «Обрати»
            if (typed.isDirectory() && mode != JFileChooser.FILES_ONLY) return new File[]{typed};
            if (typed.isFile() && mode != JFileChooser.DIRECTORIES_ONLY) return new File[]{typed};
        }
        if (mode != JFileChooser.FILES_ONLY && dir != null) return new File[]{dir};
        return null;
    }
}

final class Dlg {
    static JLabel text(String t) {
        String h = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return LauncherWindow.label("<html><div style='width:360px'>" + h + "</div></html>", 14, false, Theme.TEXT);
    }

    /** true — натиснули головну кнопку. cancel == null — діалог з однією кнопкою. */
    static boolean show(Component parent, String title, JComponent content, String ok, String cancel) {
        return show(parent, title, content, ok, cancel, null);
    }

    /** ready — викликається перед показом (щоб інший потік міг закрити діалог). */
    static boolean show(Component parent, String title, JComponent content, String ok, String cancel, Consumer<JDialog> ready) {
        Window owner = parent instanceof Window ? (Window) parent : parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        Round.apply(d, () -> 10);
        boolean[] res = {false};

        JPanel root = new JPanel(new BorderLayout(0, 18)) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setColor(Theme.CARD);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.ov(40));
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.dispose();
            }
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


final class Bar extends JComponent {
    private double v, tv;
    private final javax.swing.Timer tm = new javax.swing.Timer(15, e -> {
        double d = tv - v;
        if (Math.abs(d) < 0.002) { v = tv; ((javax.swing.Timer) e.getSource()).stop(); }
        else v += d * 0.2;
        repaint();
    });

    Bar() {
        setOpaque(false);
        setPreferredSize(new Dimension(100, 10));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
        setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    void set(double d) {
        tv = Math.max(0, Math.min(1, d));
        if (tv < v) v = tv;
        tm.start();
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        g.setColor(Theme.INPUT);
        g.fillRoundRect(0, 0, w, h, h, h);
        int fw = (int) (w * v);
        if (fw > 0) {
            g.setPaint(new GradientPaint(0, 0, Theme.ACC1, w, 0, Theme.ACC2));
            g.fillRoundRect(0, 0, Math.max(fw, h), h, h, h);
        }
        g.dispose();
    }
}

/* ======================================================================
 *  Головне вікно
 * ====================================================================== */
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

    // Play
    final Combo<VItem> versions = new Combo<>();
    final Check cRel = new Check(I18n.t("Випуски"), true), cSnap = new Check(I18n.t("Знімки"), false), cOld = new Check(I18n.t("Давні (бета/альфа)"), false);
    final Bar bar = new Bar();
    final JLabel status = label(I18n.t("Готово до запуску"), 13, false, Theme.MUTED);
    final Btn playBtn = new Btn(I18n.t("Грати"), Btn.Kind.PRIMARY);
    final Btn loadersBtn = new Btn(I18n.t("Додати версію"), Btn.Kind.SECONDARY);
    boolean filling, busy;

    // Завантажувачі
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

    // Settings
    final Combo<JavaFinder.Inst> javaC = new Combo<>();
    final JavaFinder.Inst javaBuiltin = new JavaFinder.Inst(I18n.t("Вбудована (Java ") + Runtime.version().feature() + ")", "");
    final JavaFinder.Inst javaCustom = new JavaFinder.Inst(I18n.t("Свій шлях…"), null);
    boolean javaSync;
    final Field javaF = new Field(""), jvmF = new Field(""), wF = new Field(""), hF = new Field(""), dirF = new Field("");
    final JSlider ram = new JSlider(2, 32, 8);
    final JLabel ramLbl = label("4096 MB", 14, true, Theme.TEXT);
    final Check hideC = new Check(I18n.t("Ховати запускач, поки триває гра"), true);
    final Field discF = new Field("");

    // Моди / збірки
    record Prof(String name, String versionId, String mc, String loader) {
        @Override public String toString() { return name + "  · " + loader + " " + mc; }
    }

    final Combo<Prof> mProf = new Combo<>();
    final Combo<ModApi.Kind> mKind = new Combo<>();
    final Btn mDirB = new Btn(I18n.t("Тека модів"), Btn.Kind.SECONDARY);
    final Btn mRmB = new Btn(I18n.t("Видалити мод"), Btn.Kind.SECONDARY);
    final JLabel mInstTitle = label(I18n.t("Встановлені моди"), 15, true, Theme.TEXT);
    final JLabel mVerLabel = label(I18n.t("Версія мода (для цієї збірки)"), 12, true, Theme.MUTED);
    final Field mQuery = new Field("");
    final JLabel mInfo = label(" ", 13, false, Theme.MUTED);
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
        setUndecorated(true);
        Round.apply(this, () -> maxed ? 0 : 10);
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
        pages.add(modsPage(), "mods");
        pages.add(settingsPage(), "settings");
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
        log(I18n.t("Тека запускача: ") + root);
    }

    /* ---------- утиліти UI ---------- */
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

    /** Рядки журналу збираються пачками (одне оновлення UI замість сотень), довжина консолі обмежена. */
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

    /** Просить JVM стиснути купу й віддати вільну пам'ять системі (викликається, коли вікно сховане/згорнуте). */
    void trimMemory() {
        Thread t = new Thread(System::gc, "trim");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    {
        addWindowListener(new WindowAdapter() {
            @Override public void windowIconified(WindowEvent e) { trimMemory(); }
        });
        javax.swing.Timer warm = new javax.swing.Timer(6000, e -> trimMemory()); // після старту викидаємо тимчасове сміття
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
    final JLabel playerLbl = label("—", 15, true, Theme.TEXT);

    /** Розгортає вікно на робочу область екрана (не перекриває панель завдань) і повертає назад. */
    void toggleMax() {
        if (!maxed) {
            restoreBounds = getBounds();
            GraphicsConfiguration gc = getGraphicsConfiguration();
            Rectangle sb = gc.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            maxed = true;
            setBounds(sb.x + in.left, sb.y + in.top, sb.width - in.left - in.right, sb.height - in.top - in.bottom);
        } else {
            maxed = false;
            if (restoreBounds != null) setBounds(restoreBounds);
        }
        bg.arc = maxed ? 0 : 10;
        for (Btn b : maxBtns) b.setGlyph(maxed ? 4 : 3);
        revalidate();
        repaint();
    }

    volatile boolean inGame;
    String curPage = "play";

    /** Статус у Discord залежить від сторінки (назва програми вже є в самій активності). */
    void presenceIdle() {
        String d, st;
        switch (curPage) {
            case "mods": d = I18n.t("У вкладці «Моди»"); st = I18n.t("Шукає й встановлює моди"); break;
            case "settings": d = I18n.t("У налаштуваннях"); st = I18n.t("Налаштовує запускач"); break;
            case "console": d = I18n.t("У журналі"); st = I18n.t("Читає записи гри"); break;
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
            case "console": return I18n.t("Журнал");
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
        if (key.equals("mods") && !modsInit) {
            modsInit = true;
            refreshProfiles(null);
        }
    }

    void exit() {
        saveSettings();
        dispose();
        System.exit(0);
    }

    void openFolder(Path p) {
        try {
            Files.createDirectories(p);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(p.toFile());
        } catch (Exception e) { msg(I18n.t("Не вдалося відкрити теку: ") + p); }
    }

    /* ---------- каркас ---------- */
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

    /** Вузька панель з іконками зліва, як у Modrinth App. */
    JPanel sidebar() {
        JPanel side = new JPanel();
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(new EmptyBorder(8, 0, 10, 0));
        side.setPreferredSize(new Dimension(Theme.SIDE_W, 0));

        String[][] top = {{"play", I18n.t("Головна"), "0"}, {"mods", I18n.t("Моди"), "1"}, {"console", I18n.t("Журнал"), "3"}};
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
        NavBtn add = new NavBtn("", I18n.t("Додати версію"), 4);
        add.addActionListener(e -> openLoaders());
        nav.put("loaders", add);
        side.add(add);

        side.add(Box.createVerticalGlue());
        NavBtn st = new NavBtn("", I18n.t("Налаштування"), 2);
        st.addActionListener(e -> showPage("settings"));
        nav.put("settings", st);
        side.add(st);
        AvatarBtn av = new AvatarBtn();
        av.addActionListener(e -> showLogin());
        avatarBtn = av;
        side.add(av);
        makeDraggable(side);
        return side;
    }

    /** Рядок заголовка вікна: логотип, «назад/вперед», назва сторінки, статус гри, кнопки керування. */
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

    /* ---------- головна сторінка ---------- */
    static JLabel header(String text) {
        return label(text, 16, true, Theme.TEXT);
    }

    /** Плитка акаунта («Грає як») у правій панелі. */
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
        rp.row(header(I18n.t("Грає як")), 0);
        rp.row(accountTile(), 12);

        rp.row(header(I18n.t("Швидкі дії")), 26);
        JPanel acts = new JPanel(new GridLayout(0, 1, 0, 2));
        acts.setOpaque(false);
        ListBtn b1 = new ListBtn(I18n.t("Тека гри"), 5);
        ListBtn b2 = new ListBtn(I18n.t("Моди"), 1);
        ListBtn b3 = new ListBtn(I18n.t("Журнал"), 3);
        ListBtn b4 = new ListBtn(I18n.t("Налаштування"), 2);
        b1.addActionListener(e -> openFolder(gameDir()));
        b2.addActionListener(e -> showPage("mods"));
        b3.addActionListener(e -> showPage("console"));
        b4.addActionListener(e -> showPage("settings"));
        for (ListBtn b : new ListBtn[]{b1, b2, b3, b4}) acts.add(b);
        rp.row(acts, 8);

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

        // картка «продовжити»: версія, Грати, фільтри, статус, прогрес
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

        // «Відкрий для себе» — картки з картинками гри
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
                I18n.t("Пам'ять, власна Java, роздільна здатність вікна, тека гри та стан у Discord."),
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

    /* ---------- тема: світла / темна + довільний колір ---------- */
    static final int[] PRESETS = {0x1BD96A, 0x2FA4FF, 0x6C7BFF, 0xB26BFF, 0xFF5C9A, 0xFF5A5F, 0xFF9A3C, 0xFFD23F, 0x1FD3C6};
    final JPanel themeModeP = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    final JPanel themeColorP = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));

    final JPanel langP = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));

    /** Вибір мови інтерфейсу (випадаючий список): зберігається в налаштуваннях і діє після перезапуску запускача. */
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
        if (code.equals(I18n.code())) return; // повернули ту мову, що вже запущена — перезапуск не потрібен
        String name = I18n.nameOf(code);
        String now = I18n.restartNote(I18n.code(), name); // мовою, яка діє зараз
        String next = I18n.restartNote(code, name);       // мовою, яку обрано
        msg(now.equals(next) ? now : now + "\n\n" + next);
    }

    void buildThemeControls() {
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
        themeModeP.revalidate();
        themeModeP.repaint();
        themeColorP.revalidate();
        themeColorP.repaint();
    }

    /** Застосовує тему «на льоту»: без перезапуску запускача, і зберігає вибір. */
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

    /* ---------- сторінка «Налаштування» ---------- */
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
            File[] jRes = FilePick.show(this, I18n.t("Обери java (або теку JDK)"), JFileChooser.FILES_AND_DIRECTORIES, false, jStart);
            if (jRes != null && jRes.length > 0) javaF.setText(jRes[0].getAbsolutePath());
        });
        javaC.setPreferredSize(new Dimension(300, 40));
        javaF.setToolTipText(I18n.t("Шлях до java (або до теки JDK). Порожньо — вбудована Java запускача."));
        JPanel javaP = new JPanel(new BorderLayout(10, 0));
        javaP.setOpaque(false);
        javaP.add(javaC, BorderLayout.WEST);
        javaP.add(javaF, BorderLayout.CENTER);
        javaC.addItem(javaBuiltin);
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
            File[] dRes = FilePick.show(this, I18n.t("Ігрова тека (saves, mods)"), JFileChooser.DIRECTORIES_ONLY, false, dStart);
            if (dRes != null && dRes.length > 0) dirF.setText(dRes[0].getAbsolutePath());
        });
        row(c, 4, I18n.t("Ігрова тека (saves, mods)"), dirF, dirBrowse);

        JPanel hideP = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        hideP.setOpaque(false);
        hideP.add(hideC);
        discC.setBorder(new EmptyBorder(0, 24, 0, 0));
        hideP.add(discC);
        row(c, 8, I18n.t("Поведінка"), hideP, null);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        btns.setOpaque(false);
        Btn openGame = small(new Btn(I18n.t("Тека гри"), Btn.Kind.SECONDARY));
        Btn openMods = small(new Btn(I18n.t("Тека модів"), Btn.Kind.SECONDARY));
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

    /* ---------- сторінка «Консоль» ---------- */
    /** Консоль для головної сторінки: темне «віконце» всередині картки. */
    JPanel consolePanel() {
        Card c = new Card(new BorderLayout(0, 10), 14);

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(label(I18n.t("Журнал"), 14, true, Theme.TEXT), BorderLayout.WEST);
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

    /* ---------- вибір Java ---------- */
    void fillJava(List<JavaFinder.Inst> found) {
        javaSync = true;
        javaC.removeAllItems();
        javaC.addItem(javaBuiltin);
        for (JavaFinder.Inst i : found) javaC.addItem(i);
        javaC.addItem(javaCustom);
        javaSync = false;
        syncJavaCombo();
    }

    /** Підсвічує у списку пункт, що відповідає шляху в полі (порожньо — вбудована, невідомий — «Свій шлях»). */
    void syncJavaCombo() {
        if (javaSync) return;
        String t = javaF.getText().trim();
        JavaFinder.Inst pick = javaCustom;
        if (t.isEmpty()) pick = javaBuiltin;
        else for (int i = 0; i < javaC.getItemCount(); i++) {
            JavaFinder.Inst it = javaC.getItemAt(i);
            if (it.path() != null && !it.path().isEmpty() && it.path().equalsIgnoreCase(t)) { pick = it; break; }
        }
        javaSync = true;
        javaC.setSelectedItem(pick);
        javaSync = false;
    }

    /** Шлях до Java з поля: прибирає лапки та, якщо вказано папку JDK, додає bin/java. */
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

    /* ---------- збереження / завантаження налаштувань ---------- */
    void loadSettings() {
        String did = cfg.get("discord", "").trim();
        discF.setText(did.isEmpty() ? Theme.DISCORD_ID : did);
        discC.setSelected(cfg.getb("discordOn", true));
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

    /* ---------- версії ---------- */
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

    /** Обирає найновіший офіційний реліз Minecraft (а не першу-ліпшу збірку чи встановлену версію). */
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

    /** Плавно гортає головну сторінку вгору — до вибору версії, щоб було видно зміну. */
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

    void fillVersions(String want) {
        if (want == null) { Object so = versions.getSelectedItem(); if (so instanceof VItem) want = ((VItem) so).key(); }
        if (want == null) want = cfg.get("version", "");
        filling = true;
        versions.removeAllItems();
        List<String> installed = engine.installedIds();
        Set<String> inst = new HashSet<>(installed);
        List<VItem> items = new ArrayList<>();
        for (Prof pf : loadProfiles()) items.add(new VItem(pf.versionId(), "custom", true, pf.name()));
        for (String id : installed) if (!engine.urls.containsKey(id)) items.add(new VItem(id, "custom", true, null));
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
        ib.setBorder(new EmptyBorder(0, -12, 0, 0)); // прибирає зайвий відступ зліва, лишаючи проміжок між кнопками
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
                if (my != lSeq.get()) return; // застарілий запит
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
                SwingUtilities.invokeLater(() -> {
                    fillVersions(id);
                    lStatus.setText(type + I18n.t(" встановлено: ") + id);
                    status.setText(type + I18n.t(" встановлено: ") + id + I18n.t(". Натискай «Грати»!"));
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

    /* ---------- збірки (профілі з власними модами) ---------- */
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
            mInfo.setText(I18n.t("Збірок ще немає — натисни «Нова збірка»."));
            iModel.clear();
            return;
        }
        cfg.set("modProfile", p.name());
        ModApi.Kind k = curKind();
        mInfo.setText(k.isMod() && p.loader().equals("Vanilla")
                ? I18n.t("Чиста збірка не підтримує моди. Створи збірку на основі Fabric / Quilt / Forge / NeoForge (набори ресурсів працюють і в чистій).")
                : "Minecraft " + p.mc() + " • " + p.loader() + I18n.t("  ·  версія ") + p.versionId());
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
                try (Writer w = Files.newBufferedWriter(d.resolve("profile.properties"), StandardCharsets.UTF_8)) {
                    pr.store(w, "PaxLauncher profile");
                }
                log(I18n.t("Створено збірку «") + n + "» (" + loader + " " + mcId + ")");
                SwingUtilities.invokeLater(() -> {
                    refreshProfiles(n);
                    fillVersions(null);
                    mBar.set(0);
                    mStatus.setText(I18n.t("Збірка «") + n + I18n.t("» створена й з'явилась у списку версій"));
                    status.setText(I18n.t("Збірка «") + n + I18n.t("» з'явилась у списку версій"));
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
        } catch (IOException e) { msg(I18n.t("Не вдалося видалити теку: ") + e.getMessage()); }
        refreshProfiles(null);
        fillVersions(null);
    }

    /* ---------- сторінка «Моди» ---------- */
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
        mProf.setPreferredSize(new Dimension(300, 44));

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
        JPanel r1b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        r1b.setOpaque(false);
        r1b.add(newB);
        r1b.add(delB);
        r1b.add(dirB);
        Btn impB = small(new Btn(I18n.t("Імпорт модів"), Btn.Kind.SECONDARY));
        impB.addActionListener(e -> importMods());
        r1b.add(impB);
        r1b.add(mToggleView);
        row1.add(r1b, BorderLayout.CENTER);

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
        for (JComponent c : new JComponent[]{row1, mInfo, row2}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(c);
            top.add(Box.createVerticalStrut(8));
        }

        /* --- пошук --- */
        mList.setBackground(Theme.CARD2);
        mList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mList.setFixedCellHeight(54);
        mList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, false);
                ModApi.Hit h = (ModApi.Hit) v;
                String full = api.cached(h.desc());
                String d = full.length() > 50 ? full.substring(0, 50) + "…" : full;
                setText("<html><b>" + esc(h.title()) + "</b>  <font color='" + Theme.hex(Theme.SUB) + "'>" + fmt(h.downloads()) + I18n.t(" завант.")
                        + "</font><br><font color='" + Theme.hex(Theme.SUB) + "'>" + esc(d) + "</font></html>");
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
        lsp.setPreferredSize(new Dimension(330, 100));
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

        JPanel dHead = new JPanel();
        dHead.setOpaque(false);
        dHead.setLayout(new BoxLayout(dHead, BoxLayout.Y_AXIS));
        dTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        dMeta.setAlignmentX(Component.LEFT_ALIGNMENT);
        dHead.add(dTitle);
        dHead.add(Box.createVerticalStrut(4));
        dHead.add(dMeta);

        mFiles.setPreferredSize(new Dimension(300, 44));
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        btnRow.setOpaque(false);
        btnRow.add(mInstall);
        btnRow.add(mCancel);
        btnRow.add(mPage);
        dFoot = new JPanel(new BorderLayout(0, 10));
        dFoot.setOpaque(false);
        dFoot.add(mVerLabel, BorderLayout.NORTH);
        JPanel fp = new JPanel(new BorderLayout(0, 10));
        fp.setOpaque(false);
        fp.add(mFiles, BorderLayout.NORTH);
        fp.add(btnRow, BorderLayout.CENTER);
        dFoot.add(fp, BorderLayout.CENTER);
        dFoot.setVisible(false);

        JPanel details = new JPanel(new BorderLayout(0, 10));
        details.setOpaque(false);
        details.add(dHead, BorderLayout.NORTH);
        details.add(dsp, BorderLayout.CENTER);
        details.add(dFoot, BorderLayout.SOUTH);

        JPanel searchView = new JPanel(new BorderLayout(16, 0));
        searchView.setOpaque(false);
        searchView.add(left, BorderLayout.WEST);
        searchView.add(details, BorderLayout.CENTER);

        /* --- встановлені --- */
        iList.setBackground(Theme.CARD2);
        iList.setFixedCellHeight(34);
        iList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, false);
                String n = (String) v;
                boolean off = n.endsWith(".disabled");
                setText(off ? n.substring(0, n.length() - 9) + I18n.t("   (вимкнено)") : n);
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
        Btn togB = small(new Btn(I18n.t("Вимкнути / увімкнути"), Btn.Kind.SECONDARY));
        Btn rmB = small(mRmB);
        JPanel ib = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ib.setOpaque(false);
        ib.add(togB);
        ib.add(rmB);
        Btn backI = small(new Btn(I18n.t("Назад до пошуку"), Btn.Kind.SECONDARY));
        backI.addActionListener(e -> showInstalledView(false));
        JPanel ihead = new JPanel(new BorderLayout(10, 0));
        ihead.setOpaque(false);
        ihead.add(mInstTitle, BorderLayout.CENTER);
        ihead.add(backI, BorderLayout.EAST);
        JPanel installedView = new JPanel(new BorderLayout(0, 10));
        installedView.setOpaque(false);
        installedView.add(ihead, BorderLayout.NORTH);
        installedView.add(isp, BorderLayout.CENTER);
        installedView.add(ib, BorderLayout.SOUTH);

        mCenter.setOpaque(false);
        mCenter.add(searchView, "search");
        mCenter.add(installedView, "installed");

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

    void showInstalledView(boolean installed) {
        mInstalledView = installed;
        mCards.show(mCenter, installed ? "installed" : "search");
        applyKindTexts();
        if (mToggleView.getParent() != null) mToggleView.getParent().revalidate();
        if (installed) refreshInstalled();
    }

    ModApi.Kind curKind() {
        Object o = mKind.getSelectedItem();
        return o instanceof ModApi.Kind ? (ModApi.Kind) o : ModApi.Kind.MOD;
    }

    /** Підписи кнопок і заголовків залежно від режиму (мод / ресурс-пак). */
    void applyKindTexts() {
        ModApi.Kind k = curKind();
        mDirB.setText(k.folderBtn);
        mRmB.setText(k.removeBtn);
        mPage.setText(k.pageBtn);
        mInstTitle.setText(k.installedTitle);
        mVerLabel.setText(k.versionLabel);
        mToggleView.setText(mInstalledView ? k.searchTitle : k.installedTitle);
        for (JComponent c : new JComponent[]{mDirB, mRmB, mPage, mToggleView}) {
            if (c.getParent() != null) { c.getParent().revalidate(); c.getParent().repaint(); }
        }
    }

    /** Для модів — «Fabric 1.20.1», для ресурс-паків — лише версія гри. */
    static String target(ModApi.Kind k, Prof p) { return k.isMod() ? p.loader() + " " + p.mc() : p.mc(); }

    void setDesc(ModApi.Hit h) {
        dDesc.setText(api.cached(h.desc()));
        dDesc.setCaretPosition(0);
    }

    /** Перекладає описи всіх знайдених позицій у фоні й оновлює список по мірі готовності. */
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
        if (h == null) {
            dTitle.setText(I18n.t("Обери зі списку"));
            dMeta.setText(" ");
            dDesc.setText("");
            mPage.setEnabled(false);
            setFootVisible(false);
            return;
        }
        setFootVisible(true);
        dTitle.setText(h.title());
        dMeta.setText("Modrinth • " + h.author() + " • " + fmt(h.downloads()) + I18n.t(" завантажень")
                + (h.loaders().isEmpty() ? "" : I18n.t("  ·  Є для: ") + h.loaders()));
        setDesc(h);
        if (api.needsTranslation(h.desc())) {
            new Thread(() -> {
                api.translate(h.desc());
                SwingUtilities.invokeLater(() -> { if (h == mList.getSelectedValue()) setDesc(h); });
            }, "mod-translate-one").start();
        }
        mPage.setEnabled(true);
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
                    + I18n.t("Завантаж файл на сайті й поклади в теку збірки (кнопка «") + h.kind().folderBtn + "»).");
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
                    mBar.set(1);
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

    /** Імпорт модів із наявної збірки / теки з модами: кожен мод ставиться під версію й завантажувач поточної збірки. */
    void importMods() {
        Prof p = curProf();
        if (p == null) { msg(I18n.t("Спочатку створи збірку («Нова збірка»).")); return; }
        if (mBusy) return;
        File[] picked = FilePick.show(this, I18n.t("Обери теку збірки (або теку mods) чи окремі .jar"),
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
                mBar.set(wasCancelled ? 0 : 1);
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
        Dlg.show(this, I18n.t("Імпорт модів"), sp, I18n.t("Закрити"), I18n.t("Закрити"));
    }

    void refreshInstalled() {
        iModel.clear();
        Prof p = curProf();
        if (p == null) return;
        ModApi.Kind k = curKind();
        Path d = instDir(p.name()).resolve(k.folder);
        if (!Files.isDirectory(d)) return;
        try (var st = Files.list(d)) {
            st.filter(x -> Files.isRegularFile(x)).map(x -> x.getFileName().toString())
                    .filter(k::matches)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(iModel::addElement);
        } catch (IOException ignored) { }
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

    /* ---------- вхід / акаунти ---------- */
    final CardLayout rootCards = new CardLayout();
    final JPanel rootPanel = new JPanel(rootCards);
    final Accounts accounts = new Accounts(root.resolve("accounts.properties"), engine.http);
    final JLabel accName = label("—", 14, true, Theme.TEXT), accType = label(" ", 12, false, Theme.MUTED);
    final Btn contBtn = new Btn(I18n.t("Продовжити"), Btn.Kind.PRIMARY);

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
                Art.cover(g, Art.get(Art.FIREWORKS), 0, y0, w, h - y0, 0.35);
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

        col.add(ms);
        col.add(Box.createVerticalStrut(10));
        col.add(off);
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
        if (avatarBtn != null) avatarBtn.repaint();
        getContentPane().repaint();
        welcomeLbl.setText(a == null ? I18n.t("Ласкаво просимо!") : I18n.t("Ласкаво просимо, ") + a.name() + "!");
    }

    void enterApp() {
        updateAccountUi();
        bg.side = true;
        bg.repaint();
        rootCards.show(rootPanel, "app");
        showPage("play");
    }

    void showLogin() {
        Accounts.Acc a = accounts.current();
        bg.side = false;
        bg.repaint();
        rootCards.show(rootPanel, "login");
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

    /* ---------- запуск гри ---------- */
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
                String line = String.join(" ", cmd);
                if (a.token().length() > 3) line = line.replace(a.token(), "***");
                log(I18n.t("Запуск: ") + (line.length() > 500 ? line.substring(0, 500) + " …" : line));
                Process pr = new ProcessBuilder(cmd).directory(gd.toFile()).redirectErrorStream(true).start();
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
                pg.update(code == 0 ? I18n.t("Гру закрито") : I18n.t("Гра завершилась з помилкою (код ") + code + I18n.t("), дивись Журнал"), 0);
            } catch (Engine.Cancelled cx) {
                log(I18n.t("Завантаження скасовано"));
                SwingUtilities.invokeLater(() -> { status.setText(I18n.t("Завантаження скасовано")); bar.set(0); });
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

/* ======================================================================
 *  Акаунти: офлайн, Microsoft (device code), Ely.by (Yggdrasil)
 * ====================================================================== */
final class Accounts {
    record Acc(String type, String name, String uuid, String token) {
        String label() { return type.equals("ms") ? "Microsoft" : type.equals("ely") ? "Ely.by" : I18n.t("Автономна"); }
        String userType() { return type.equals("ms") ? "msa" : type.equals("ely") ? "mojang" : "legacy"; }
    }

    record Resp(int code, String body) { }

    record Dev(String deviceCode, String userCode, String url, int interval, int expires) { }

    static final String ELY = "https://authserver.ely.by/auth/";
    static final String MS = "https://login.microsoftonline.com/consumers/oauth2/v2.0/";
    /** Стандартний Client ID для входу через ліцензію (працює через login.live.com). */
    static final String LEGACY_CID = "00000000402b5328";
    static final String LIVE = "https://login.live.com/";
    static final String LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    static boolean legacy(String cid) { return LEGACY_CID.equals(cid); }
    static final String APPJSON = "application/json", FORM = "application/x-www-form-urlencoded";

    private final Path file;
    private final HttpClient http;
    private final Properties p = new Properties();

    Accounts(Path file, HttpClient http) {
        this.file = file;
        this.http = http;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) { }
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(w, "PaxLauncher accounts"); }
        } catch (IOException ignored) { }
    }

    static Acc offline(String name) {
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return new Acc("offline", name, uuid, "0");
    }

    synchronized List<String> offlineNames() {
        List<String> r = new ArrayList<>();
        for (String x : p.getProperty("offline", "").split(",")) if (!x.trim().isEmpty()) r.add(x.trim());
        return r;
    }

    synchronized boolean addOffline(String name) {
        List<String> l = offlineNames();
        for (String x : l) if (x.equalsIgnoreCase(name)) return false;
        l.add(name);
        p.setProperty("offline", String.join(",", l));
        save();
        return true;
    }

    synchronized void removeOffline(String name) {
        List<String> l = offlineNames();
        l.remove(name);
        p.setProperty("offline", String.join(",", l));
        if (p.getProperty("active", "").equals("offline:" + name)) p.remove("active");
        save();
    }

    private synchronized Acc saved(String type) {
        String n = p.getProperty(type + ".name");
        if (n == null) return null;
        return new Acc(type, n, p.getProperty(type + ".uuid", ""), p.getProperty(type + ".token", "0"));
    }

    private synchronized String prop(String k) { return p.getProperty(k, ""); }

    private synchronized void store(Acc a, String extra) {
        p.setProperty(a.type() + ".name", a.name());
        p.setProperty(a.type() + ".uuid", a.uuid());
        p.setProperty(a.type() + ".token", a.token());
        p.setProperty(a.type() + ".extra", extra);
        save();
    }

    synchronized void setActive(Acc a) {
        p.setProperty("active", a.type().equals("offline") ? "offline:" + a.name() : a.type());
        save();
    }

    synchronized Acc current() {
        String a = p.getProperty("active", "");
        if (a.startsWith("offline:")) {
            String n = a.substring(8);
            return offlineNames().contains(n) ? offline(n) : null;
        }
        return a.equals("ms") || a.equals("ely") ? saved(a) : null;
    }

    private Resp post(String url, String body, String ct) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "PaxLauncher/1.0").header("Content-Type", ct).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> x = http.send(r, HttpResponse.BodyHandlers.ofString());
        return new Resp(x.statusCode(), x.body());
    }

    private Resp get(String url, String bearer) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "PaxLauncher/1.0").header("Authorization", "Bearer " + bearer)
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> x = http.send(r, HttpResponse.BodyHandlers.ofString());
        return new Resp(x.statusCode(), x.body());
    }

    private static Map<String, Object> jm(String b) {
        try {
            Map<String, Object> m = Json.map(Json.parse(b));
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private static String str(Object o) { String x = Json.string(o); return x == null ? "" : x; }

    private static int num(Object o, int d) { return o instanceof Number ? ((Number) o).intValue() : d; }

    private static String enc(String x) { return URLEncoder.encode(x, StandardCharsets.UTF_8); }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < ' ') b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String err(Map<String, Object> j, String d) {
        String x = str(j.get("errorMessage"));
        if (x.isEmpty()) x = str(j.get("error_description"));
        if (x.isEmpty()) x = str(j.get("error"));
        return x.isEmpty() ? d : x;
    }

    Acc loginEly(String user, String pass, String totp) throws Exception {
        String client = UUID.randomUUID().toString().replace("-", "");
        String pw = totp.isEmpty() ? pass : pass + ":" + totp;
        Resp r = post(ELY + "authenticate", "{\"username\":" + q(user) + ",\"password\":" + q(pw)
                + ",\"clientToken\":" + q(client) + ",\"requestUser\":true}", APPJSON);
        Map<String, Object> j = jm(r.body());
        if (r.code() != 200) {
            String m = err(j, "HTTP " + r.code());
            if (m.toLowerCase().contains("two factor"))
                m = I18n.t("Обліківку захищено двоетапною перевіркою — введи код у поле «Код двоетапної перевірки».");
            else if (m.toLowerCase().contains("invalid credentials"))
                m = I18n.t("Неправильне ім'я користувача або пароль.");
            throw new IOException(m);
        }
        Map<String, Object> prof = Json.map(j.get("selectedProfile"));
        if (prof == null) throw new IOException(I18n.t("В обліківці Ely.by немає профілю гравця."));
        Acc a = new Acc("ely", str(prof.get("name")), str(prof.get("id")).replace("-", ""), str(j.get("accessToken")));
        store(a, client);
        return a;
    }

    private Acc refreshEly(Acc a) throws Exception {
        String client = prop("ely.extra");
        Resp v = post(ELY + "validate", "{\"accessToken\":" + q(a.token()) + "}", APPJSON);
        if (v.code() == 200 || v.code() == 204) return a;
        Resp r = post(ELY + "refresh", "{\"accessToken\":" + q(a.token()) + ",\"clientToken\":" + q(client) + "}", APPJSON);
        if (r.code() != 200) throw new IOException(I18n.t("Сесія Ely.by закінчилась — увійди знову («Змінити обліківку»)."));
        Acc n = new Acc("ely", a.name(), a.uuid(), str(jm(r.body()).get("accessToken")));
        store(n, client);
        return n;
    }

    Dev msStart(String cid) throws Exception {
        Resp r = legacy(cid)
                ? post(LIVE + "oauth20_connect.srf", "client_id=" + enc(cid) + "&scope=" + enc(LIVE_SCOPE) + "&response_type=device_code", FORM)
                : post(MS + "devicecode", "client_id=" + enc(cid) + "&scope=" + enc("XboxLive.signin offline_access"), FORM);
        Map<String, Object> j = jm(r.body());
        if (r.code() != 200) throw new IOException(err(j, "HTTP " + r.code()));
        return new Dev(str(j.get("device_code")), str(j.get("user_code")), str(j.get("verification_uri")),
                num(j.get("interval"), 5), num(j.get("expires_in"), 900));
    }

    Acc msFinish(String cid, Dev d, BooleanSupplier cancelled) throws Exception {
        long end = System.currentTimeMillis() + d.expires() * 1000L;
        int iv = Math.max(d.interval(), 2);
        while (System.currentTimeMillis() < end) {
            Thread.sleep(iv * 1000L);
            if (cancelled.getAsBoolean()) throw new IOException(I18n.t("Скасовано"));
            Resp r = post(legacy(cid) ? LIVE + "oauth20_token.srf" : MS + "token", "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                    + "&client_id=" + enc(cid) + "&device_code=" + enc(d.deviceCode()), FORM);
            Map<String, Object> j = jm(r.body());
            if (r.code() == 200) return msXbox(str(j.get("access_token")), str(j.get("refresh_token")), legacy(cid));
            String e = str(j.get("error"));
            if (e.equals("authorization_pending")) continue;
            if (e.equals("slow_down")) { iv += 5; continue; }
            throw new IOException(err(j, "HTTP " + r.code()));
        }
        throw new IOException(I18n.t("Час очікування вийшов."));
    }

    private Acc msRefresh(String cid, String refresh) throws Exception {
        Resp r = post(legacy(cid) ? LIVE + "oauth20_token.srf" : MS + "token",
                "grant_type=refresh_token&client_id=" + enc(cid) + "&refresh_token=" + enc(refresh)
                + "&scope=" + enc(legacy(cid) ? LIVE_SCOPE : "XboxLive.signin offline_access"), FORM);
        Map<String, Object> j = jm(r.body());
        if (r.code() != 200) throw new IOException(I18n.t("Сесія Microsoft закінчилась — увійди знову («Змінити обліківку»)."));
        String nr = str(j.get("refresh_token"));
        return msXbox(str(j.get("access_token")), nr.isEmpty() ? refresh : nr, legacy(cid));
    }

    /** Microsoft token -> Xbox Live -> XSTS -> Minecraft. Зберігає акаунт. */
    private Acc msXbox(String msAccess, String refresh, boolean legacy) throws Exception {
        Resp r = post("https://user.auth.xboxlive.com/user/authenticate",
                "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":" + q((legacy ? "t=" : "d=") + msAccess)
                        + "},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}", APPJSON);
        if (r.code() != 200) throw new IOException("Xbox Live: HTTP " + r.code());
        Map<String, Object> x = jm(r.body());
        String xbl = str(x.get("Token"));
        List<Object> xui = Json.list(Json.get(x, "DisplayClaims", "xui"));
        String uhs = xui.isEmpty() ? "" : str(Json.get(xui.get(0), "uhs"));

        r = post("https://xsts.auth.xboxlive.com/xsts/authorize",
                "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[" + q(xbl)
                        + "]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}", APPJSON);
        Map<String, Object> xs = jm(r.body());
        if (r.code() != 200) {
            Object xe = xs.get("XErr");
            long code = xe instanceof Number ? ((Number) xe).longValue() : 0;
            if (code == 2148916233L) throw new IOException(I18n.t("До цієї обліківки Microsoft не прив'язано профіль Xbox."));
            if (code == 2148916238L) throw new IOException(I18n.t("Дитяча обліківка: її треба додати до сім'ї Microsoft."));
            throw new IOException("XSTS: HTTP " + r.code() + (code != 0 ? " (XErr " + code + ")" : ""));
        }
        String xsts = str(xs.get("Token"));

        r = post("https://api.minecraftservices.com/authentication/login_with_xbox",
                "{\"identityToken\":" + q("XBL3.0 x=" + uhs + ";" + xsts) + "}", APPJSON);
        if (r.code() == 403)
            throw new IOException(I18n.t("Minecraft відхилив застосунок (403). Client ID має бути схвалений Mojang: aka.ms/AppRegistration."));
        if (r.code() != 200) throw new IOException("Minecraft login: HTTP " + r.code());
        String mc = str(jm(r.body()).get("access_token"));

        r = get("https://api.minecraftservices.com/minecraft/profile", mc);
        if (r.code() == 404) throw new IOException(I18n.t("На цій обліківці Microsoft немає Minecraft Java Edition."));
        if (r.code() != 200) throw new IOException(I18n.t("Профіль Minecraft: HTTP ") + r.code());
        Map<String, Object> pr = jm(r.body());
        Acc a = new Acc("ms", str(pr.get("name")), str(pr.get("id")).replace("-", ""), mc);
        store(a, refresh);
        return a;
    }

    /** Оновлює токен перед запуском (Microsoft / Ely.by). */
    Acc ensureFresh(Acc a, String msClient) throws Exception {
        switch (a.type()) {
            case "ms": {
                String rt = prop("ms.extra");
                if (rt.isEmpty() || msClient.isEmpty())
                    throw new IOException(I18n.t("Потрібен вхід через Microsoft і Client ID у Налаштуваннях."));
                return msRefresh(msClient, rt);
            }
            case "ely": return refreshEly(a);
            default: return a;
        }
    }
}

final class PassField extends JPasswordField {
    PassField() {
        setOpaque(false);
        setForeground(Theme.TEXT);
        setCaretColor(Theme.ACC2);
        setSelectionColor(Theme.SEL_FIELD);
        setSelectedTextColor(Theme.SEL_FIELD_FG);
        setFont(Theme.font(Font.PLAIN, 14));
        setBorder(new EmptyBorder(11, 14, 11, 14));
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.setColor(Theme.INPUT);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
        g.setColor(Theme.LINE);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        if (hasFocus()) {
            g.setColor(Theme.ACC2);
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 12, 12);
        }
        g.dispose();
        super.paintComponent(g0);
    }
}

/** Заокруглені вікна: прозорий фон (якщо ОС дозволяє), інакше — обрізання за формою. */
final class Round {
    /** Звичайне непрозоре вікно (напівпрозорі вікна дають білі краї й баги перемальовування). */
    static void apply(Window w, java.util.function.IntSupplier arc) {
        w.setBackground(Theme.BG2);
        Runnable upd = () -> {
            try {
                int a = arc.getAsInt();
                if (a <= 0 || w.getWidth() <= 0) w.setShape(null);
                else w.setShape(new RoundRectangle2D.Double(0, 0, w.getWidth(), w.getHeight(), a * 2, a * 2));
            } catch (RuntimeException ignored) { } // ОС не підтримує форму вікна — лишається прямокутним
        };
        w.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { upd.run(); }
            @Override public void componentShown(ComponentEvent e) { upd.run(); }
        });
    }
}

/* ======================================================================
 *  Discord Rich Presence (локальний IPC, без бібліотек)
 * ====================================================================== */
final class DiscordRpc {
    private final Consumer<String> log;
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "discord-rpc");
        t.setDaemon(true);
        return t;
    });
    private volatile String clientId = "", details = "", state = "";
    private volatile long start;
    // нижче — лише з потоку ex
    private Closeable conn;
    private InputStream in;
    private OutputStream out;
    private boolean told;

    DiscordRpc(Consumer<String> log) {
        this.log = log;
        ex.scheduleWithFixedDelay(this::push, 20, 20, TimeUnit.SECONDS); // перепідключення, якщо Discord запустили пізніше
    }

    void configure(String id) {
        String n = id.trim();
        if (n.equals(clientId)) return;
        clientId = n;
        ex.execute(() -> { close0(); told = false; push(); });
    }

    void update(String d, String s, long startSec) {
        details = d;
        state = s;
        start = startSec;
        ex.execute(this::push);
    }

    private void close0() {
        try { if (conn != null) conn.close(); } catch (IOException ignored) { }
        conn = null; in = null; out = null;
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < ' ') b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private void push() {
        try {
            if (clientId.isEmpty()) return;
            if (conn == null && !connect()) return;
            frame(1, "{\"cmd\":\"SET_ACTIVITY\",\"nonce\":\"" + UUID.randomUUID() + "\",\"args\":{\"pid\":" + ProcessHandle.current().pid()
                    + ",\"activity\":{\"details\":" + q(details) + ",\"state\":" + q(state)
                    + ",\"timestamps\":{\"start\":" + start + "},\"assets\":{\"large_image\":\"pl_logo\",\"large_text\":\"PaxLauncher\"}}}}");
            String r = readFrame();
            if (r.contains("\"evt\":\"ERROR\"")) log.accept("Discord RPC: " + r);
        } catch (Throwable e) {
            close0();
        }
    }

    private boolean connect() throws IOException {
        if (!open()) return false;
        frame(0, "{\"v\":1,\"client_id\":" + q(clientId) + "}");
        String r = readFrame();
        if (r.contains("\"evt\":\"ERROR\"")) { log.accept("Discord RPC: " + r); close0(); return false; }
        if (!told) { log.accept(I18n.t("Discord Rich Presence підключено")); told = true; }
        return true;
    }

    private boolean open() {
        boolean win = Engine.osName().equals("windows");
        List<String> dirs = new ArrayList<>();
        if (!win) {
            String xdg = System.getenv("XDG_RUNTIME_DIR");
            if (xdg != null) { dirs.add(xdg); dirs.add(xdg + "/app/com.discordapp.Discord"); dirs.add(xdg + "/snap.discord"); }
            for (String v : new String[]{"TMPDIR", "TMP", "TEMP"}) if (System.getenv(v) != null) dirs.add(System.getenv(v));
            dirs.add("/tmp");
        }
        for (int i = 0; i < 10; i++) {
            try {
                if (win) {
                    RandomAccessFile raf = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
                    conn = raf;
                    in = new InputStream() {
                        @Override public int read() throws IOException { return raf.read(); }
                        @Override public int read(byte[] b, int o, int l) throws IOException { return raf.read(b, o, l); }
                    };
                    out = new OutputStream() {
                        @Override public void write(int b) throws IOException { raf.write(b); }
                        @Override public void write(byte[] b, int o, int l) throws IOException { raf.write(b, o, l); }
                    };
                    return true;
                }
                for (String d : dirs) {
                    Path sock = Path.of(d, "discord-ipc-" + i);
                    if (!Files.exists(sock)) continue;
                    java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
                    try { ch.connect(java.net.UnixDomainSocketAddress.of(sock)); } catch (IOException e) { ch.close(); continue; }
                    conn = ch;
                    in = java.nio.channels.Channels.newInputStream(ch);
                    out = java.nio.channels.Channels.newOutputStream(ch);
                    return true;
                }
            } catch (IOException ignored) { }
        }
        return false;
    }

    private void frame(int op, String json) throws IOException {
        byte[] p = json.getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 + p.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(p.length).put(p);
        out.write(b.array());
        out.flush();
    }

    private String readFrame() throws IOException {
        byte[] h = in.readNBytes(8);
        if (h.length < 8) throw new EOFException();
        int len = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4);
        if (len < 0 || len > 1 << 20) throw new IOException("bad frame");
        return new String(in.readNBytes(len), StandardCharsets.UTF_8);
    }
}

/* ======================================================================
 *  Вбудовані картинки гри (JPEG у base64)
 * ====================================================================== */
final class Art {
    static final int FIREWORKS = 0, STEVE = 1, CAVE = 2, MEADOW = 3;
    private static final String[][] DATA = {
        { // fireworks
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAcFBQYFBAcGBgYIBwcICxILCwoKCxYPEA0SGhYbGhkWGRgcICgiHB4mHhgZIzAkJiorLS4tGyIyNTEsNSgsLSz/2wBDAQcICAsJCxULCxUsHRkdLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCz/wAARCAGsAvgDASIAAhEBAxEB/8QAHAAAAQUBAQEAAAAAAAAAAAAAAwECBAUGAAcI/8QASBAAAgEDAwEFBgMHAgUDAgUFAQIDAAQRBRIhMQYTQVFhFCJxgZGhMrHBByNCUtHh8BViM0NygvEWJJKi0jRTVHOywhc1Y4P/xAAbAQABBQEBAAAAAAAAAAAAAAACAAEDBAUGB//EADURAAICAQMCAwYGAgIDAQEAAAECAAMRBBIhMUEFE1EiMmFxkaEUgbHB0fBC4SPxBhUzUoL/2gAMAwEAAhEDEQA/APNq6lrq9QnKTqXFcKXFKKJS4pcUuKUUTFdilxS4p4omKXFLiuxSjxMUuKXFLimiiAUuKUClxShRMUuKXFLimjxAKXFLilpoUTFLilxS4pRxFjVDIokYqhPJAyQPhSYpcUuKaEIgHFLilAp2KaFExS4pQKXFNCAjcU7FPYJ7uzd+Ebt3n6eldihzDAjcUuKcBSgU2YQEbilxTgKUCmzDAjcUuKdil20OYQEaBS7aeFpQKbMPbGbaXbT8UuKbMILFt44nnVZpTFGfxOF3Y+VM280/bS7aHMMLGba7FEC0u2mJhBYPFLtom2uC02YW2M2122ibaXFLMfbB4pzEuFBA90bRgYp22l202YQWD20u2iBa7bTZhbYPbS7aJtrttNmPtg9tdtou2u20sxbYPaa7bRcV2KWY+2C2122i4pdtLMW2B20u00XbXbaWYtsFtrttFxXbaWYtsFtrttF2122lmLbBba7bRghYgAEk8AAda4xlWIYEEcEHwpZjbYHbXbaLtrttLMbbBba7FF2122lmLbBbaXbRNtdtp8xtsHijXDwyd33EJi2oA2WzubxNN2122mzG2we2u2mi7a7bT5jbYLbXbaLtrttLMW2C20u2i4rsUsxtsFtpQCDkHBom2l20sxbYLbXbaLtrttLMbbBba7bRttdtpZi2wW2l20TbS7aW6LbBba7bRdtdilujbYLbS7aLtrttLdFsgttdtou2u20t0bbBba6jba6lui2TD4pcUuKWtOc7ExXYpQKXFKPExSijQ2ss8M0sagpAoZzkDAJx86HimzFEpa7FLininYpcVwFLilHiYpcUuKUClHiYpcUuKXFNHiYpcUuKXFNCiYpQKXFGE3/tBB3UfD7+8x7/AExjPlTGOIHFLilxS4pjCEQCnYpQKXFNCAiYpQKcBS4poYEQClAohYNEiCNVK5ywzls+fwpAKbMkAjQKdinAUuKEmGBGgUuKdinAUOYYWMxTgtOC08Idu7BxnGccU2YYWD207FOApQtCTJAs54mjYBgAWAYcg8HpSBaeFpQtDmGFjAtKFogWnBKHdJAsHtpdtF7ul7uh3QwkEFrttF2V2ym3QgkHtpdtE20oWlmOFgttLtom2l202YYWD20u2ibaXbQ7o+2D2122ibaXbS3QtkHtrttF2122m3R9kHtrttE20u2m3R9kHtFdtouyu20t0fZBba7bRdprttLdFsg9tdtou2u2026LZBbK7ZRttLtpbo+yB2V2yjba7bS3RtkCAVYEEgjkEeFcQWYkkknkk+NF2122lujbIErXbaLtrsU+6Nsgttdtou2uxSzG2QW2u20XFdin3RtsSSKNYomSXe7Al1242fPxoe2i4rttNujbYLbT1RDG5ZiHGNoAyD58+FO20u2lui2wW2u20XbXbaW6NsgttLtou2u20t0W2C20u2ibaXbS3RbILbXbaLtrttLdFsg9tdtou2u20t0bZBbaXbRNtLtpbotkFtrttF2122lui2Qe2u20XbXbaW6LZBba7bRttdtpbo2yC211F211LdFsmBxXYpcUuK2pyUTFFjhLxSOHRe7AOGbBbJxwPGmAUuKUeNxThS4pcUoo3FLinYpcUo8bilxS4pcU0eJilApcUuKUUTFLiivKZY4kKIoiXaCq4J5zz5mmAU0KJinYrgKdimhRuKdilxSgU2YQETFOxS4p2KbMMCNApQKdilAocwwIgFKBTgKdihzJAI0ClApwWnbabMMLGhaXFPC0oWgzJQsaBTgtO204LTEyQLGYp4LbAm47Qc7c8Z86ULTgtDmGFjAtO208LTglATJQsYFpwWnhKIqZ8KAtJAkYqZoixelHSLNSorckgAZJ8KrvaBLKVZkIQ0vc8dKu5dHuLcZmRU4zgsM9cV3+lOY3bcmEAJ+JHAqt+KXrmTimURippSra5sZLaQxyKAR5EEfUVEeLHhUyXBhkRjVIe2u20cpTdlSboOyD2122ibKXbSzCCQe2l20TbS7abdCCQYWu20XbXbKbdCCQe2uC0XZTglMWhCuC20uyjrHnwoyWzuDtUnaMnA6CgL4hiqQtlJsqYYceFMMdIPH8qR9npXbaNsNdsot8by4HbShKMEpwShLxxXA7KUJUgR+lOEWfChNgELy5G7sUnd1M7k+Vd3XpQ+YIvLkLu6TbUsxUwx0YeAa5GK0m2jlKaUow0Dy4HFdii7aTbRboOyDxXYom2u20swdkHil20/bXbaWY2yM2122iba7bS3RbIPbS7aJtrttLdFsg8Uu2iba7bTbo2yD212KJtrttLMWyCYqiFmIVVGSScACqp+0VhtzCzTjwKDg/M0btJC0vZq+VCQwiLcHGcc4+lYXSH3WZX+Vj9+aqai9qx7MdEBbBmxttcNzfRRCFUR225JyausViYJDDMkg6owb6VrtOvRqEDybO72ttxnNDptQXyHPMJ6wORJOK7FPxS4q7mRbYPFLin4rsUsxbYzFdin4rttNmLZG4rgKftrgtLMbZG7a6n4rqWYts8+id4ZVkjYq6HKkdQaTHOaXFLiuhnExMUuKXFLilFExXYp2KXFKKNxTsUuKXFNHjcUuKUCnAUo8aBTsUoFLimjxMUuKUCnYpoQEaBSgU4CnAU0MCNxTgKUCnAUJMMCNxTgKXFOAoSYYEbtpwWnYpwFCTJAI0LShacBTgtDmShY0CnYpwWnBaEmSgRoWlAp4WnBaAmSBYwCnhacFp4WhJkoWMC04LRVjogjqMtJ1SDjRAH3qxJX3cHGD5n0rglSBFxTxDUZeTLVI4joyR0URUVI+aiZ5OtUfBAXdVVcknAFX1tbQwwKpjfverspPug8Z46VF0qzWef33ZFUE7gCcHHFaCMS+0BjtQkAs55DDxznzrF1d/O2XUQQQ2LcQmNRIGOWGMhiRz1+VAk0+a5DSuXZBwW4JA+HXqKtHgiiwyKQrMCvj8fGoLqySPtPByDjjIrKW459kyyqZ5Er0tFkh9nAG52yzMOEHnnw8Kqb2zaB8EMUP4GKldw8xmtM8TzAkuisBgFkycHxwOfHrVVdWrdwcyIu0btjPkn4CtLT389YBSZ905oZWpbpzQilbCtISkBtpdtG20oWn3RwkDtpdtF2Uuym3QxXBbaULRdlKEpt0cVwQSnqnNPCUVE5oGeGEhLe1eQEqpOOuKvUsgsBTaQdnljnHXn6fKo2nXEyhYUjWVQcgbOfr1q+ixckKY2Q+e7cPrVGyw5k4QCZoWWW5Xd6ZxUO6t0ik2I+/A5I6Z9K1V1axQxDcCyjrhsZqnuo45EkMUMCgAHO/3h9adLDEygyjKU3ZUlkpNlWt8i2QIjoix0VY6MkRJAAyTUbWYjhIFYqm2dsziTbbmYsu0cZAJ8fQ1Ngte8LW0yNDIoyvu4AOP4h68c0WzLieWIghJwVkVR98elULdRwcQ9nEr4hi4VkUQyKAFYNgBumTRrixWzdbeWRkcg99j3hnPAoghKSjA/CfGi3qGSYszhj09fj96i872hgxzXzKWWIMxIUKD4DoKC0VXHcpHCzOoZmGEBB/+VRJLdlUMUYKehI4NXK78wCkrGjobJU146C6VcV5CUkYrTdtHK00rUwaRlILbXYouKTbT5g7IPFdiibaULS3RtsHtpdtEC07bTbotkFtpQlGCelLspt0WyB2Umyj7K4pTbotkBtpNtH20hWiDRtkjTQie3khbpIpQ/MYrynSMxzTwN1Xr8QcGvTta1WPRNON3JE8oDBQq8cnpk+Ary+1uRPrkkwQRidnbaDkDPOKq6n2lkR4YS7FaDszJ71xFnwDD8qz61b9n5Nmqqv8AOjL+v6VToO2wGTEZE1OK7FPx6V2K2MyLbIt3e22nwGe7nSGPplj1PkPOq09prWSMPbxSSqwyC3uj+tA7dQCTs2ZDjMUqsM+vB/Os1pqyR2EaSKUZcjB+NU9RcycLGAy2DNDLr11IcIEiHoMn71Ga9uJWDSTO2DnBNQwac0ixjLuqD/ccVRNjt1MlwBNN/r1o0qIm99xAzjAGfjVpisDFKrYZGDLngj41d6ZqVxNqkQmnZgxK7TwOR5Vbr1RJw8jKDqJo8V1OrqvZg7Z53ilxS4pcH5V0s4GJilxS4pcU0UQClxS4p2KUeNxS4p2KXFNHjcUuKdilxTR43FOApQKcBTQsRuKcBTgKULTQwI0CnAU4LTgtNmGBGAU7bTgtOAocyQCPuLWS0mMUm0sAD7rBhyM9RTAtOApwFBnjmSARoWnBacBTgKEmSgRgWnBaeBTgtATJQsYFpwWnhacFoCZMFjAtOC08LTwtATJQsYFoqpzTlTNHSOomaWESNSKpCQcdKlWcFu6sJpJEbjaVXcPnU6OBoFYKyyxPjOOh8vUVVe3Ev105kI2qdwjpnPIcHz9PlTkgURSFlBOAB6HNWpUywCNudgwp8dv9qD3HuYx1NVjbLi0SuFsSrMBwoya5Y8Grie2t4rIJubvs5wOnzqK9lMkImaNlQnAJ4zTeZmF5YEfZQmVZQH2lVDDLYB5/vV1bd5HKHkkQEDPutnB+45/SqaxeOO4XvcCNvdYkE7R58VbojG3YSRgtjcAqe8R4E+Q5zWZqeTgwgMSWkiowkKZUnPX8X+ZpQFkiMaLGrZOMnBHjjNRomyxcqdsYOVHGP9v9fhTnkUoxlCIMcAYAORwMfGsw18w4C8Hs8m2Rm5AI4ByPjUU+yC2uTtfayYGU6HwOfA54qQ8uIZD3Y7ssD+E7R4fI9aBemCOwYxRFe/cYz4AckfU9fSr1QPCmIjPEoHWhFaksvNN2elbAePsgNlLso2yl2U++PsgdlKEo2yl2UBshbYHZS7KPsrttDvi2wQSnquDT9tOC0JaPiTbJlVNzye6hyE86srSWR5ZCHxtXlegGegqkiYxurAAlTnmplpfG3LbkDhiGz45H/moG5hSdcXDzKvcsd23Pz6Gqu7ljlxiERyDhtvQ/KlW4dSMdASwBoEhLuWY5J6k0l4ixAlaQJzRdtOC1Jvg4jVSpdvGAGfdtdAGU5wc5oSrUmOJihcKdo4Jqta/EICSpJzdNmVcMcDdkkgeXqKm2CPDdoY07yQryu3O4HqPp41ELLIBxtKqFAHPFSCxYB8AeBwetZFr8bRFt4xG3MIFwSoIVjkA9RzQZU3OTip5UmIBsbwc8nnGKCYyc8VXW4whILopUA7sg/LFBk2lHjLOVXmPPhz5VaG2URbyNxxyM8UKRllhaJ5IoUOGComefU9av1W5gGUU0JRsHxGajOlWd3AkUpCSrKhGQwGM/LwqGy5rYqsyAYBAMhlKaUxUkpTTHVoPAKyNtpNtSSnpTCtGHgbYHbShaJilC0+6NtjQtOCU9Vo8cDP8AhUnnHSoy+I+2Mgt2mlCKVBPixwKd7MfeAOSuSQB4edSWUOkYyPdXwXH/AJ/vTu5coAoBGdvu+P8AWoDaY+yREtt7hQw56ny9aZLF3NuZ5cJEFLFmIAwOpqwaNUwO7ZGHDZNOntjP3ylBnuyvTqCMdD5Ch84xFZiX7X6dJvFmHutp27h7qk+hPJ+lSY+0GnukOZh3soXKKCxUnwNeVW11NYTXFsoAZW2ksM4IOKeLi7lfcjSMQcgLwPtUm5wfhMz8UOmOZ6Z2ttfaOy18oGSid4P+0g15PDKILmOQ/wALA8V7WyC/0wqRxcQ4/wDkv968NMbKxVuCpwfj0qRwDzFqcqQRLx9XI/4UPzY/oKENWvRIGjmMTDoYxgipVrp9s0SOys+5QfeNT4oY4h+7RU+AxVPci9BB2Wt7zYkjsZPeNrrtcGZ1miK7pCTyCCOvzreVi9Kl7rU7dz/OAfnx+tbWrtFm9ZPWm0YnmfbxJF7RsGdijxK6AnIXqDjy5FIuoW6wIzSrkqCQOT0qw/aTanvbC5HirRH6gj8zWZtLB5rEyiQDAOBjJJFQ3KucsZAWdXIUSfcatG0TJEr5YYDfhxUKG1nupSqIWcAE7uMA/Go9jctBdI/BDEKc+AJFapI0WZpNv7wjaW8SBUJby+FEYIbeWMrXNzpdgpZY297HUnGeaZYavd/6nbOG4WVSVRfDPNWtyiSwEOoYAg4NCjwi4UBR5AYoVcDkjmSGtjwGwJ6KRycdK6h2knf2cMv86A/aurVBzJcTBbadubu9m47M7tueM+ddilxXTTz2JilAou8G3WLukBDFt4HvHPgfSmgUo8aBS4pwFKBTRYjQKcBSgU4ClCxG4pQtOxS4poWI6IxqH7yMvlSFw2Np8/X4U0CnAU7bQmGBGgU7FKFpwWmzDAjQKcBTgtOC0JhgRoFOC04LTgtATJQI0LTgtOC0oWgJkoEQLTgtOC04LQkyULGhacFp4WnBajJk6rGhacFpwWnhaAtJlWMC09UpwWnqtRFpOqTlTmpMSc0NF5qVAqmRQ5IUnkgZIFQs0tVpDwx1bWcUXdZaOYvnquMYoURsEjBSKaR89HbA+1XUMtxcYmnxDBjhV4JHpWfa80kXEixwwmPA7wS5zz0FIbZ4tpdcEjK5/OrOJ1UNMI17pOgI6nwrnYXELADMoOVOM8Y5FVC3MsgynKhDnKqf5iMn6Uh7iVgJ5pGx4tmrCOSKQGOaMMpGB5j1HnVY9s5ZgoJK8kdCPlRq0cqJCYDcccDPFSYJyTh2zzuGScE9KAykHkUgFE2GEDEvU7u3hli4lZgMuzYGTz8z/SmXoTv5IyzvGnugrjHP+fWqlJpUACyMAOgB4FFlu7ifHeSs2AF8uKpeSQ2cxghBzJ0ssCRlZyMsgfbgnLfDPwBqouJHnmaRzlm/zFOwfOkKVMihIariAK0m00bZS7Kl3wsQG2l20bZXbaYvHxBbaXZRttdtod8fEFsrtlF20u2m3x8QWyl20XZTxHnwoTZGxAbDXbakd16V3dHypvMikfFdtoxTFN204aKD205Vp22nAUi0U5RU+Fn9mVVDe65YHwHHNQ1FSYdveLvzszzg84qtbyI0l20EtyjbcBEOWLEADNSYrYMhZW3Y6noPrRHjiE/cROhQr+HfhVPmx8T8KC7uqCEEhM5xnx6Zx4Vk2ZbpBDZ6Rx5LEDJ6kgdKQOCffXNDLcnByBXSSZ8AAeeBUIQ5hyVDCJFYxOiEkD3+MD5UK9icRd1Lao0i8B4yQ2PA46EetBt53jnUoeR5jPHiMeVMlnlhnaZG2nkBQfwZ8vSrdSMGkRB3SrmQq7KeoOKAVqdcTrNGN0YEoPLj+L4+Z9ajBcmtmtjjmF1ge6z0oospDAZcLsHrz9KsrS07sLKx2vgFc+B8KsBCxEZC5Lt0I6ev50fmRTNS2UkcSyMmFY4BqM0eK1EidwGXG6FQfLk+fyqlvIAkpKjCnkeX96kSzMbErdlcForLzSAVPugYnKtWMSOisVTKAdGOMqevH61FgRGkAdtowecZ+FSo3ZCrAKOMdOtV7TmLEVYzIyxI3GfdDHpmjmMoWQ5BT8JHXI9afFAshJTJK9RjqKMSZDk53Zxyc1Sa3mFiQxE29WIypORnpRCowDjOc7c1KeAruZgfMAeFBfPJySreHnihW3MRGZ4RrtnFZ/tC1GHuxsklZ0Dc8MAw/OnP7r8cAirT9plv7J23tLvaFWeJCcdOCVP2xWb1DUXhuu6RVG3GWPOavctgzLOKyw+M9K0zUre27L293dzpDHEuxnY8ZBwBXk+qSQS6tdyWxLQPKzISMcE56VoXnlewNuZGMAO8Rk+7nzxWWlTZM6eTEVKtm4Y9IN7ZAl7pkoaxjG4blBGPHrU9ST0H1qg0d9tyy/zL+VX6VA4wYVZysLGxRw3ipBFb5SHRXHRgDWCVS7hVBYnjCjJ+lbfTxKNOgWZHjkCAFXUgj5GrOmPJElWZ/t/b972cWXGTDMp+RyP6VjNHlUQSIxAAbPPqP7V6P2ltvauzN/EBk90WHxXn9K8r05sTMP5lorxmQv7NgMiuux2X+UkVsIJO8gjcfxKD9qyl2u27k9TmtBpT95pkP+0FT8jVezkAwKuGIk9veRh5imx4BGABRIY3nfZCjSv/ACopY/ahNHJBM0UqNHIh2srDBU+RFQyebHQZO80aEeKZT6GurMW2q3lshtraTAZt2FXc2T/4rqvJqFCgGKBxSgU7FKBXYzzzEQClApwFKBSzHxGgUuKcBTsU2YQEaFpQKcBTgKHMICN20uKfil202YYEaBTgKULTgtCTDAjQtPC0oWn5bZs3HbnOM8Z86EmGBGAU4LTgtOC0JMlCxoWnBaeFpwWgJkyrGBaeFpwWnhajLSVVjAtOC08JTwlAWk6rGBacFp4WnhaiLSdUjAtP2EeFOC/OpkdyXiWG4G+MEkHA3Ln18vSoXZh0GZYVBIQWnqtS5LEd2ZIHEqKoZz02njIx8Tj1oIWgDhuRJ1SIq81IjHNDVaOg5oGMsosn2piRCzAtJn3VP4fif6VbW873MQFw+5E6L4yHwHyqmttneL3hITxIHNXunx5BunULlf3ajjaPP86pWy4pwI8l96xE8/ibxxinYPeoBkM8YIx51Gt5Ef2iVjyE9350SWdt1rMeCFGMehqowk3wjJF3LvUk4/EB5eddJNHLErOhYjq4/Evl8aOXjS9l2ZMYy3XkjxFQXcJK/ck7Dxz5eVLpGzmRZCZHLNyTTAlH2ZoiQljgAk+gpjZiKRgnpThHU9rEoEy6EsxUY5+fwohsHQuG2nZwdpz/AIKrteI+RK3uqXuvSrMWRJQE7N4yC/ApfYXV8MMLnG4ciojqBHBEqu69KQx+lXE1kscIIBzuwTUc2xIyB44+NP50IESu7uk2VLaIqSCORTClGLIUBtpdtF2V2ylvigttKEom2nKnNCXihLe1WYhQdpAJJPSnrasu0sp2np606AFTwcZ4q1ht2YiSMAJ4BiDiqNt5Q9eJGxxIcunN3i7UwH6Dy9Kfd2C29kADlyRkFeQcdM1aLEsce4jnHPIPwps1v3lqNzYJ97ms8atiwyeJDv5EzDxelBKVcy2QXOZExzz54qudOa2arg44k4OZGxXAUQrTmhkNq8icEEAHAPXx561OXwI8YKIpwc0gWnAGhYxQm/JyTkk5JqaIljQyhmbC+AxgngfWoAo63G21MWz3i4bdnwHhVZ0z0jHPaETeuCCoJwRkiiFGltVmbGOVznHIGfyoJnZoCzqGVpM48M4oPfyLGUDsEPJGeKAVkxuTCYiUFZmZWz/CM4pLa4xJsJAfPuSHwPTBz/DUZmoTH1q0tfERGesJdhVuXVY+7wcFc5wfHnyoI60hpBVoDAxHAl4qK3d++roQAEPQfOpUUjRoAgLDPAPw8KgWTC6jWINhx18MY6EVKDiIYfc2zjI8fP51EwOeI3HSPLxMpCkZP4geoqr1lUCx7GU4yMKeAKmSqGh34yoyCx5K+tU97c9/L5KowBnNTVZ7xu/EhNTQOac1NFWxBMlWjIhdmxnb7p9c1JV4SnvJlyclgSKh26hpQpbaG4zUvuk3/u2LDwUA5x6+VVrAMxhJcUKSZ2GQbfHH3oqouxg8vvg8DGc/Oo6OoABGMDGAM0/PvZJB4zWe4OYUkNI7wpGSfd5xjr60JULliqgqOSAeKI5U4AxGwUEetRpJMKMZV/Px9KFAe0aeb/tgtB/p2l3OGWSOV4iD5EAjHzU15tqB7xopf505/wA+daD9pMdxF27vhcyyOW2OneMTgFRwPIZBoei9n5NStYpru2uFtUyA4G0M3gM9fpWvUp2rMi5tztGWriSyjJP4kH5UO60W9ubPvrewmfDDLiMgHPHU16h2cm7OaHoi+0PYWUsbsuZCO8I6jrlvGqjWf2gaIbKaCE3N2xXAKR7V49Wx5eVGKwp5MA2MwwFmX7N9jpJdUtvb7nuFd9hSMBmGfXp+depWfYrQrRA7WjXGOr3Dlh9OBXi9322v2mDWKrZqpDBuHfI8cngfSjXF/qmrNuu7q6u8/wD5shI+nSisKrziNWjPwDie0zdoezeixiIX1lblT/w4ME/RAaqTrdjrtzNc2DSNGpCMXTaSceXlXl0djJt94og8q0XZu7g0pLlZZHZZNpGF8Rmhrt9rngSxXSlZyDzNbLGJYXjbo6lT8xivPtG7Fv8A6jCt7diNWJXbEMnp5n+laPtHrrWHZ+LUYI3dZHC7d23Gc9T8q89uO1mrXEgMc/syqeBCMEf93WrLlT1gXtyAJr9e7CW0WowtDcSxW7RDIYbnZgTk54A4xWm7H9ntAtdMna6hibuZM95dSe7gjyJA8D4V5T/qerWqSOt1MrTEb3dtzHGccnJFdpe68u5fapXmYruy7Z6H1+NQll25AkO0lsEz3Cftr2Y0qMxxXsTbeO7s493/APEAfesVqXa3QpdQuL237PG6mlbeXvJTtJx4IuR4edZk2KtJkNtXyAzR0s4V6gsfU1XNhMsJTWvPM9Nftr2U01EMV3ADsHuWsJJHp7ox966vJZrEd4xL4G44AHQV1P5pg/h65e7aXbTsU4Cu4zOFxGAUoWn7aUCmzHxGgUoWngU7bTEwgIwLShaeBShaHMICNApwWnAU7bTZkgEYBTgtPC04LQEwwsYFpwWiBacFoCZKFgwtPC08LRAtAWkwWDC04LRAtPCVGWkypBhKeEogSnhKjLScJBBaeFogSnBaiLSdUgwtOC08LTgtAWk6pD2z2axET27O+QQQ+BjxGMfeuZbRwShkRiSQuMgeQzQgtOUYNQbcHOT9ZZUQ4t5I33W778+6Mfi5HlSuvfySFlCSDnaB1Pl6U+K8migWFSuxTkAqPpny46UU3STle+hXjOWTIY5NVyXHJH9+0mCiQgtEUVNe2S4UyRyBnyc7uCeKbb2E9xv7pN3dnDcjil5oI5koEGlSUlfuwm47RzjNSLXSxIyd5LgMSMKOTjwBPU0eO2iSSRVVG2sEDMep8QM8ZxnrVd7l7SUHEBbyNskjSMuXAGRk45qTcOvs1sobLKpDDxBz0pzTLNMkke9IUHIBKBm8Fx4fGhzwGK5EczOWZsBgudw48fE+HGeg9aq+Zkww3PM43B71ZF/EFAOR14xQgMmnGMd4wjbcq+fBGOox/nFKooDYCIYOY+GLfIq/zHFWcEMqWpeFWGJMBsYIx6+vSg2UYkDR+4GbozcAY9atYDuMYuGGACr/AO7Pl51m6i7HEBziK6JHAJoQBFu3EgZbd4/rQJD3bFVkL8YJI655qfEgjPc5DL/L5f5zQreKJZmBBAGfAHFZjX4kQOIxyWIZhyVAzw2PhmjxWMSMm12YgHI6c/lT44I5G3ALhT7zGpI7sABlAjAwC3jUQvI6QGfHAlXc2bd37qnaTx41HNu6EgFVwPxE9PhV0VQAhdqhjjHn8Kr5wGcKG2HwOODViu4HnMlrsLcSlngCc7lbPl4VGZKs7oEHYwXjnKjrUJl5q8tktKcyPtpNtG20m2pd8KC209Vp22nAUJaKFiTNWtspT90wPvcFcciq+3ZFXJ/F4DHFWdsYt5chsfw5rN1LEiQWQrqoIjBHHjTpAVhIkByBxg9a5TDLlUG09QSftTZrl402k8YPGOvPhWcMkgCV+TxK24IlLH8LE8AdKrpoijkHnHGR0qxupSxLLwp4qBLI7ZyScnmtzT5AlpZGCF3Cgck4FEkyG7oMSqnAHr406McliMhRmkVfeq5uhd5JjgthDH3ok3MCcr4fI0k1qsYUqSwYZ5GKlRpDMFBcoQMcrx9qPJZ5c7XRtoxjOD96pG4g8mRlsGVvsMrKpUKSR0DDP0qNJG0TbXUqfI1a3cMjTnKEAAD5VXXw/eOI8eS5+3Sp6rCxhA5jJLmKPTtjnaRIWLNgDGMdaob7tXo1irGW+jcqMlYvfP24ryO713UtW1iRtSnaRsMoj6ImD0C+H502V/cx4GtNaQOswLvFypK1r9f4m1m/aLNcx77KzSJCSAZTubr5DiqqbthrZJYXm3fxgRrgfDisxp7kQvGeqOfv/ho8re5nyINS7QOkyrNdqHblz+XH6Tadi9XubvWriK7uZJ2khyu9s4IPh8jW4zXk3ZW69l7T2Tk4V37s/BgR/SvV6WJ0PhVpsowxyQYSOV4nDIxVh4g1IOozF9zYZiMZIqHyelM71d5Teu8clc8j5U4WaklT3s04O9+DjIHANR9jMjuB7qDLHwA8zTokDnLkrGD7zAZxST3BdTEgCwg5C46/GnGeiwT6CR858COSORilFSIkhiIe5LHxCL1PxPh5edRRnJJOSfp9KlDdo3WERipBBwRyCKsTL3q7oyxRV2uMcgep8earAcURJMHrx4jzoXTdzFiTUlOWJPUdKkLIAMSHdnHU849KivOjKpRmzyCOM5xwfhinEYiWXJyeeOg8h8aqMmYsw4k2yqTnbwDznikuDHlZEwQRgjkYPnQ49m0vgEKAWLcjxyfypJSZhs2DfGuDtwP/ADyaZU5jEzIdtBpllqMN/dNBHI0ewSSYJIUn8Pj4+FY7VO2lpP2cvdNtBctLKwKTg7AvIPx8CPDrV/8AtXtlutCs7pMF4JyGPPRhj9BXmNlAjysjEnK8VoKxVMTKvT/lIMNpUMkzygEA8MSx5NT/APTV3sHkJBPQDFVltdSWkjOgUsV2nPSr6wk9ptUmkAZz1+OaicEHMet8jbM13WwsMfhOCauYLuR9JMm4h4nVTt/lGOPpUjVlDWcoUAbcNxVXpjlL5RnhgRRZ3LmR42Nj1miUjNFVqjqaKpqCWJa6jGL39n12mMmA7x8mB/ImvOcAA4FelaVNA2l6ha3EqRrKhA3HHUEf0rANpl3FbtNJGFVBzlhn6Vb3DashuUnBEPYlL+XuJlIULuG08kiiJElnrcaRjajrjHXz/UVXWtw1rOJVAJAIwauoFjukhu5F98DOc4AOajb2flGQ7vnDXd8lkilkZy+cAUtjfG8hZ9oTDbcA5qt1eeJ48BwSjDpzQdJvu5EqBd2cMMnHpQbRtzD3HftmjAUnJGT611VZv5m6YUegrqjljYZp9tKFp+KUCu6zOAxGhaULTwKULQkwgI0LTgtOC04LTZhYjAtOC04LTwtCTJAsGFp4WnBacFocyQLHM0bW0cawhZFJLSZ5YeAx6U0LTwtOC1HnElCxoWnBKIFp6pUZaSqsGEogSnhKKsdRFpYVIIJRFSirHRRHUJeWFSAEdPCUcR0vd+lRl5OEgAlLso+z0rtnpQbpKEgQlKEo2w0u2hLSYLBBacFogWnBaEtJVWMC04LTwvpRre2M9wELlUwfw4LE+AANRM+JKBiEtTam3kjkTfOTlB5+Ax4deufrQo9UmguJre3liZY4FZix4EhJOBgZBwQM9PWmR2XezTpvWYL+6DFioZj/AA+XnwfWn3D2Upi7pUgSFgJGwFZFHAXA/EeM+7zVZsEyUYHEt7u3dLaJFnLiTCCMYAc5zkEdecetPBgsw4lRSknuDbllO0DKY6g58aqtLupJdLWVhJDtYGQlC4CZPu+YJ45GPGpmm3Vvcyu16yFgNsYkI9xR158+Rz41XZSOsbacHPSPtu7hjkbf++Vi/wC6fcUx+EDHDc9fLFFHtAeOW6jEMij3FJ90Ajrjru8cg0e3QL3gbfCM7ghHKIOdzL4joOKjvqcc3szTAIjzbETOFkfqcn+AYxUZ56RZJMgagLmTtD3FojXO623IRwUdc7Sx8c5IPnxnpU62Eb7e8kWIkZK9dvpmo91q6xapbTd0XWRHjd5E2s/Ibg+mMZ8jUsXscNzcvOX7zcqy+6PEZ2AdHBGMnw+dRlCYY3AdJbQC3Mbd4pBACnauMkHwpzzRiZJJMYYgDHXPy6f2rOW+rLcQzQuwEsUoAiH8QPK48cYI+lWbCZog6wlFV8lyMYB8gf6VRbTA++Ytp7y+WZNoUg46Ak5/zmjN/wAMPhcHPHyqiaAuid5IXKPwuMcn4/0FTn1DuYQhBboB5nPTH+ZrK1GhsUDy+c/31kJXkYk0FYo1A8+R5mnpIhX3yDt/Dx+lBiKywb+Bny5p4CouWO3JweOazBuVip7QSAYV2SS2JBAIPJPJzmoFw0JUgA56/OpYlj6Lt2nk7qgXrRR+8CqqM854x5mr1Z5hVDBxIEnjQGFGWSOeJZYnWSNxuVlOQR5g00itBDiXVgCtJtouK4ipN0KC212KJiuxS3RTl4o4nO0KOB5UDFdGwkl7pGDSA4KjrnGfyNAwB6wTjrJ9rPtJOSDjIwM06W4WcFmOHxg4HX1qMYZEbGQrY6bhTAHUnBAPoag8pSdwke0E5gzIQ2fsaDIuDxnB5Gae6kHBFJgspGMkdKurxJPjGKPcbp4eNco5pyqSrYHTmmjg1JHlhCxit92PxHx6U/v8ybmA65wOKHC6iIEmNgoPuk85p6tDKsjbNhUZADVSZeSSJER3iXD7FWRGKs+ScGqyUkknPPWpd2/vBc8KoFQJGCgsxCqoySTgCrdC4EJek8H7T2v+m9uL6LGE9oLD/pfn/wDqqNI3Srnt9c6fqvbANp13HOWiVJHTlQ658fHjHSqwWgI9+Qn/AKRitpegnE6sKlzAHvINu+y+lTwYBv8APrUstvUquWJGOOaMtrbq+7ulLebcmjBsDHhSlQtnpI9tHcRTxTKoVo2DjcfEHNbO67cXspPs1tDAD4tlz/Ssrurt1KS1aq6oFazjMs7nXNSuwRNeykH+FTtH0FTuyMxXtCkeeZkZeT1OM/pWamuEt4jJIcKOOBmnaXrLwXcF9bIN0L7l3nqR6ClzCpvcWra5Jwe89jkK93GiHOeTxjmhXd3baaCzzxoOneSEL9M15xcdsdYuM7bhbdfKJQD9Tk1kb67nl1UXE80krBw252LED5049Jvv4tX0rBPznrN12t0yNiRM9wx692pOfmcVUXPbkx+9DYAqP55OfsKym7Bpsp3ROo8RT7zMt/FdQ3TA+Q/mXFz211WfPdtFbjyjTJ+pzXoNtOLi0hmHSRFf6jNeMLJzmvUOy1yJ+y9o7Nju1MbEnAG0kflijU5PM0PDNTZZYwsOeJeAnw5oU+rWtoMXF1EgBztZ+fpXm/7QNS1KDWvZY72ZLOWFXREbaOcg9OvIqBZv3kEcniygk0n4mmdR7RUDpPXk1T2+1jkjkEkJHunH3+Nc0xZh0XHHFZ/stPv0UJ/+U7L8jz+tSdX1uy0S3Wa+lZFclVCqWLHGccUaKMZEmDDbkzHa32nk1t7/AEeWzSCKJ8E7yXJVsZ8h4VkpYltdVVU4Q4wPiP61eXsKT9orzUYGxBcEsFI55A/UVTat7s0MvTw+hzQZy2JnWEn2j2kGddk8g/3GrbRZP/auh/hf7GqO5uHlndhtAzjinJK9uuOGLc5zRlCyyutiq2SZo7p43SRdwO5SOOapER7W5hZ8ZyDx8aRbiRmBV8Jjw45o0cM03KRu/rj9aZUK9YnuVvdBzLT22BP493oozVQ+p3IuVLyMyh/wg4GM+OKnxaTcuctsjHqcn7VWanaNaXzR538BgcY60lVM4zGayzGSJYNqrIuRhR/tGaDNfPNGyNuIYY5b9K46ZI9k0pdeV3ADnNTLTTrZ4kdgz7lB5OB09KHFa8yTdc3A4lFOO7ClSeetFtrhRCEdjkHgdauNYs4l0zMUapscHgfL9aoUj99cnAyKkG2wZMiy9TYHMkSgS7scA0GIGGchT1HWtBDpVsvLBpD6nH5VEu4ltdWt2RAqk44Hr/ehV06AQmSw+0xxIyW9zP8AhjdvU8CurQQRyTOEiR5X/lRSx+grqDzW7CH+HX/IzQbacFpwWnBa7DM5MLFjkkjikjU4SUAMMdcHNNC08LTgtDmGFjAtKFp4WnBaHdDCxgWnBaeFpwWgLSQLGBaeFp4WnBaEtJAkYFp4SnhaeqVEWkyrGhKIqU5UoqJUTNJ1SNWOipHT0SpEUJb8Kk+FVmslpK8wSxZqSlnJvRWUoHOAxHFSLZe6PG1xINh9M+n+dKMiCJGjI5U5zwDkdMZ8KqPae0tLXIAg45IB54PWn+zdMkAnw8allIyhJLbyfl9a7ZI45UuT7oJ6j/BQG2ThJFmtWgkKPtJHkc0LZVhDHhiTsbKnhzx0oDRYyfCnWzsYQSRSoHUgU5YmcZVGYeeOPrVhZz2UCSrcBS5973h1A8FPUEmnwnuI5DM7RSxt3jrnJiA6ZXxyf1py5ikCWzmgcLMojJ8zn8qkQ6ckkUpe4xsbAYDAUDqWHUDmh/6kJ2s3mUZZysUef3bMB7xY9V6jGB5VxW51C+Rlt7juUXMrNhJHHUk8+9z0pju78SQAjrINxYy3eiztDtSdgWhbcd52jJKnp4VR2XaE3enBblNlwmGSZc4bnGCPA4J5HHwrUx2t7LaPHcXMcUhz3ojT3W97IAGMLjxx14po7I6bpio0avPIWwO9bcS3+3HGM0t6jgwwwBlfc6jJZugJlh7wjCOm3u4scgno2ck5PI+daCSGwPcCyWKNCQS68KoPQnyJoE+nWiazZPcxxtbENHtBJXvQA2CPPqM1Zx20Md4DAmyNB/ywPeP8qHoeoqs7AgYjFhxiRmlnjUywRO6Sk4kYAGNunJ6Nxzj1qPbaRJLd2bpcKkUIYuu3eW94EsRnjgAYqyluhFHNbswVYkzJKFJ2r/FlepOeOKDPfRJZII+H2q0f+0HneG9fI9Ki57RKT0EYumxpcvBI3eRs7TnL4VSfXwHA49Kk9xbx2vs8Vv8A+6A4jAxJuIyT6qBVbJryhZ47W6tLqcx94Ich553GGdSpIyMAgHI8OtLrksGgyNc39z/7K9KpvLsJUlLKFVAowE8Sc8fCsnV+JVaawVMCWPOAIJs2+8YftBpoj0OC5kmKm3lQg5zuycNgeGAelCuoI5mtYbaYshPA3bo1X+bn8JJ8D6Vh+1fbHV11B7JorcKi7RlSxxkqR1/29eTRbbtnbRWiBbK6eSM72AKqjsRgLJnO5Rz4eNaNTC2sWIcgyeskLknmelaYbW0ln7wBnmw/vR7W29ApA5BOM5HgalgRJZybmBb3m9188jgAeDc9cdMV5/2L1nUNW104CRKibt28u4IIx146Z4xWz7sR3UcCiJYUBcBW2qvqc5wSRk0Lpg4MjdRu6yPbTm7hW5LqEkQOnBGR1HT7+flS9zG0izTyOshjwF3Yzzn9QOtUN5cX+m2M0ccSk2Nx3W2UHCqeVJx5KygVG0C+vNQ1Gaa9lRLa3XZsjXC84HU5Y+vXr0NIr3lgLxkT0PSpAYmAKnjJAPX/AD9Kh9oe1eidmoY5dVvltRK2EUgs0pHUKoBJ6j61H0t3srhpJPcWQ/xgL7vh6D5dKx/7UtPsNau9EuheMrQTSRFYnGcsAykdccoBn1rntRps3FgDt68Si7KLAoYZMfd/tQvrzcugdnZyuCFuNRbuYj6hRliPpWZ1OPXe0UOzWtcKx8n2axjEUWfDJOWYfGpV1cLbQLc3DGKKR9iyMDtZvIHxNAu7mW2RGitnuSZArKhwQPE1ECR7oxNFdPWvvHP99BNF2P1AaL2RgsdTmR7i1yqiMnBTOV5I8OR08KjTdpZtW1NY4sRWyEsoRj75HmfEVS/tBa0s7KeXQbmeW2hMZLyrhiMgPxjp8vGqvs7cLDbQuzYW3kKMf9ueP/pIq95yrUAg9vnJ/iVV0zvYXdvYGMDp27z0rTNTnub4RTFSrKcYXHI5/rVyRWO0S69u1OP2KOWURMGdyu1VHqT+VbTFQ1k45l1iO0HiuxTyKqta7R6XoEedQuljkIysK+9I3wUfmcCpMwCQOTLHFLkr0JFeT65+0vUdQ3waXH/p8B435DSkfHovy+tZsdttYslCRaxeOR4CXcB8zmh3jOJAdQonvRprbgpKKGbwBOPvXnP7Ne299rOqXOmatc99K6d7bswAPH4l468YPyNek4qUGSo4cZEIlu8kSthDnPu7hkUIxOG4RvhjmkZgi5NH0a7Y6tHucsrhk65x/hFTV1s3PaQ22+V8Z3+nTmMyNF3aKMlpCFAHnk1DaS07wRQ3sNxN4pFlsD44xWkuZJJNTSzkiD2k0bBxtJzweCfAf1rOaj2mTRb6exsdLgiaFtu88A8ZzgD186spVnjvKn4t89JzBkOGBU+RGK5ZGQ5Vip9DTdImvO0kk80rwh4yoOBtwCOOPkaUxuNx2thTgnHAoGr2nBl6u1bB8ZT9q9fk0LQ5tSFv7U6sq7S20ZJxknyrxXXu1er9oHIvLkiDwgj92MfLx+ea9n7WWf8AqHZLU7cDLNAzKPVfeH5V4A3IzV3TquJi+K2WKwUH2SI2B+6uYn6bWH0q/LYOKzrVdpL3kaPn8Sg/arJnPWDoYfdSbqFu4z4UJ7uFDgyKT5Dk/amgBSeBJW6u3U/T9N1fWIxJpulT3EROO9YhEz48k1obH9nesTkNqF7bWSfyQDvX+pwB96bIluvQ32e6syl8plsZR5DP05qFpUn7mRP5Wz9R/avXLHsLolouJIHvJCCO8uXL4yOoXhR9K8ftka01Ge2fhkyh+KnFEDmTajRPpkG89ZZM2FNVt8vvKfMYqZI3AqLd8xA+RpCUl4Msopd8KN/MoP2p4aoVjJutFH8pK/59akbqWIBGIi2yDqzN9qkK22IRgnYDnbkkUHdS7qUWTJ/a9faNC0O+HJ7trdj6qePyNQNIffZKM/hJX/PrT/bZpYxZSqbi1jYSRx7chWOcmujjmWSRkjWIOQdp8OMUTsCMToUur2rYzAcCanQNXh02OdZ95D4KhRnkU7tsU1PsUt5GCBHKsmD1AyVP51mO4kcfvZ2PovFdca1PFpkmjsym1ycqVyxBOetPW5HElTWVWg1g9pGj1KMabGTuZtgBwPlVJdt3108gBwxyOc4oocBJI1GFDHHoOtCPSpEUqSZFY62AKIIRnxqbNaqLCKYEkk4Ppx/ao2RU63dbiyFoCRKWyuRx5/1omY9YCVr0kvRVjNu+UUurdSOeRVnLPHbwmWQnavkM1AsbVrJZC0gbcBwB0xQbG7kvLtoLva0ZU+7gAZBquRuJMtr7IC95a2V6l7GzxAgK233utR9W0/2mLvkGZl45OBtGSatrDSru6AWxsZZR/wD6ojj69K0Fn2D1m5I75YbVPHe+5sfBc/nTAHOVETFcYczDac3faZGDzwVpdM//AASL4oSn0Nb61/ZpZafbi1Ovb7mR/cBjUDnqNucn6im6Z2R0HTtQuLbUbq4lkM47tc7Q24D+UefrR+WWziRi1QAZjLyFrixlhUZd1wo6ZPhUCPsjqbjMohgH+98n7Zr3aHR9F0uM7LS2twQQWZRn/wCTc15V200/TNFksxZ6lPeiVWEjSNu94EYxgY6GlnyhjqYBcWH0l/pfY/Smt7Zr7Ww7zKpEUAA5Phnnx9Kldo+ymi6bFFItqmVYe9O5Y8j1OPCsbpvaS3ttNhUJIWjyMghRweKPDrdnrcs0d6OFAcZkLMTn0qoupdCc1wypb/KbTstrmm2Vpcw9+gKuGAiXJ5GPD4V1ZSG50+1BFrakFup6Z+uTXUzanUMcquB/fjH8le8sgtLiibaULXbEzmgsYBTgKeFpQtDuhhY0LShaeFpwWgLSQLGAU8LTgtPC0BaSBYwLTgtPC04LQFpIFjQtEVaULRFWomaTBYirRkSkVaOi1AzSwixUSrGKE26IwYh3GQB0A9aBawGadIwcbjjPlU0R9yHQxsy9M/w+hqla/O2W0SJaomA29gwPIA8PMfrUjZgsjHORyfM0OFFxuDBT4ZHWpC4K4EZAXkAeH9apWPzLapI8bNE4K4BB64p7R5UJgFj73oM+n0oxTGUYD+bI8eOKdEEVw5A68ZOPvURs7yTbIP8AwwwZR0xyOlPnbulWNonyF6sNu4Hy9B4Ud4pJ5HduT4k9PhQ5r1LmGEzfuUaULtPR3P8ACp/h486nqw5kb8YkeHTHlnW471EMBBBADqxPIB9KDa6PCIWtu7MywudgZ8+8Tzt8/hUye7Z7pFt+8EoJ7xo0O5R024HBwPH402F7nZKkcUSKrsUH8O3GA/XIf+tWssBI8t1hZY7Z0VbRFTb+NkXaEXplwPHxoks/szJsG/vSMKzZzjgMGHgTUeTSpYrRGllBMcizHaNrK3gMnqPGpENlbJIss0QPeLkd5ypYnxxwPOozgd8xuPnAxXsUbTjMjushWTKjczAfhK+K5zz6UHF2tssqxFd0gx3g9zZ1YL4qc+IqwiiUTuYhIItuECkbnPT3fAjNDnvES3njZwsUKgSTKmRtGB+HrnPjQ554EIH0lRqy3M0unhXR91wFTP4l6kZIHJPI+dTLiGeCdG7wFGQQgJ+EkEEsAejdOeK7V7hW0pliVu9iCSxhedm3D5DDzoMN7JKYrjcjsoO6QYzAgHHu/wAec/HrS5IkoyRmOtLeRO0FzbySSABFeFGI3sckEA4wwBGSKUW5uLi8LSpMgPdmV14Zz4eh68nyqm1S+zNa3br3EEQaLgkqoIz+Y+9RLrt9plvueyubm4cnESxxbTCgGNu44DZz1xkUxRpJyvXrMjqnZyODVTD7Q9kiSlZJIU3GNlP4lAIz0rS9o+1/Zu/0+w02+sJNVaw2Mkk0hTc23YXO0jPmRmqG71JdTSeZbeS2YESDcwPTgnIqA1zZxyCZTHJIuQhCZKjxz6+tZ3iHhg1ro7MQV9Pj1+P3gNUrHdJV/qcfaPUmvYYJbcEt3occR5YHr49TU220a9mnSOC3kYycgkY2/M4B4FU0esLC6Mlv3hRt6s5xhvPjr8KLddqdWuScXAgB8IkwfqcmrOm0w0tS0oeBJFAAxN/oEKaFo0xnARrgArvwNq8+82fwt5Dyq+XUXjjE/eZLoPxqd8j5BJJxhkAAGK8Tma+1Ema4ee4L8d5K5bPzJr1TTdXF92atfaFWBYxHHIyjKluiqPFckE56UTr3MMAHqJW9qdZuLKR7d4RMt+gd/fwBjr08wF+gqkstb1OKcW9g4tjOVAC448BlsZ6nHp4Vou10A1J7NbSJRPDIyNuAXCkY25HBxtzkdcmgWPZFbVIpJ5u9nMuAiEqFIOfienpgeHNJW2iNbQl6BX4/PH6RlxZdrWQ97Z3G2PqWUBfiSx6eOT8aycWs3Oqxagkzq5tXJhKgYyo3Kw49DXrPbq+V+wmpDgb7cIpz5kfoa8ZtJ9KsDKRqGJbkjgoeGwRgcc9azj4sxbZcvs4IIAzMmrwMFC9TAMCMFjgcczZz6pNqXZ620ucrLp6Se0xwugO1iM5z18Tx6mqwi/a6JDRR26NnJ/iH+fCs5f6prujvNpS28EUtiuxpZUYmRsZAUHA6EefWqGU63rjGK7vcYXLKrdB+Q+VZtdDWd+Jd1XiNGjXL8Z5+c22oa9pkgl0yPvdRuJkZDbWkZlbBHOccCidkuwes6kIzqJn06zXBfcm2SQgAcA+OAMkion7NrdNK7a2LsY8yI9upC84K/wBR969e13XLPs7o82p35kFvFgN3aFjknA+/ieKY1BOIek8QTVK1lbZA49P1h7KwttOtEtrWIRRJ0A8fUnxPrUDW+02kdno86hdqkpGVhT3pG+Cj8zgV5jrP7VNU1nfDpIXTrY8b0YNMR6t0X5fWsNcX8SyM7O1zMxyzbiQT5ljyf85pRPqQPdm+1z9puqakXh0qP/ToP5wd0xHx6L8vrWEuL6PvGd5GuJmOWbdnJ9WPWq6a6luBtZsJ/IvA/v8AOmCljPWU2tLGHluZZxgnan8q8D+9DxUvTNKvtXn7mwtnnYfiKj3V+J6D51vtF/Ztbw7ZtXm9ofr3ERIQfFurfLFC7qnWFXU1nSYHStRm0nVbbULY/vraQSKM9cdQfiMj519K2F7BqenW99bNuguIxIh9CP8ABXmvavsYNVgsRpUNtbSQExlcbE7s854HUH5nJrV9jLJuz+jppEt091tLSRtsxjJ95QPIHnnzNMlymXaq2rJB6S+vI554fZ7RoxdSkLFv/Dn1qgl02bs4o7/WAbqy/epaQRtjJ55c48CfOrTWLeae2R/aJLNUb+A++58Bx08+tZR9TNmsrTxxvsQu7Ou8jGcjk4rW0dqOCinnrK+qDA7j0luv7Q7uCxSJJLa0QEhWlIZjznqxxnnyqiuu0lrd3TXF3qdvLK5G5jKvOPh6VTWXY3Ve39r/AKjpT20FnDI8Q9qcqxbgnAUHjBAqan7De0Lfi1LS1/7pD/8A0Vb8xUPEymuOfZEsV/aSkF2semvaWsX4MBVO7wBIHU/HNSoO017eXsMl1qMs0AcblDe4B/0rx9qzusfsy1DsZpZ16/1GyuIbV0/cwhw7sxwoBYAdTn4A1R6Z2hWXURbpaNvlA94uMAAE88UvPrHv9PWKqxi445nsFvc2+oQM0RZoiSh3Lgnz4+dfPV7YyW2rXNiqM8kMrR7VBJ4JHQV7R2eu5LawaWePEMjZBVwdvOMkHFS5de0SykkMU0TSliXNumST6kdT86jouV2Pk+0JpamgalF3ttInjll2H7R6hhodKmRD/HOREv8A9XP2rUaf+y3VDGi32q29soGNtvGZG/8Ak2B9q9BvtcstP0yK/uHYRSlAoAyxLdOKr9V1svLJplvKbS5lYwLMSCY3PQgffmpw7ucCVrNJo9Mu605/vwmH7a9grLQ+yMl/bT3lxcQyJueaTI2k4OFAx1IrCrEJGUL7xPAUeOf/ADUzWLy+vFeG+upp5kYq3eOT7ynB6/Cm2uRaR4TEijB9cZGR9qTggcyGu6qw4rXaPSel/sxmZtCubZhzFLvH/cOfuPvWyPFeJWWoz2zOsVxLGJeHCsVDY5Gcen5VuNM7b2sFhY2csMrSgLE8hYKq84z68UlBIzL1eurRvKfjA6+s2RrxHtbbewdvL1QMK8veD4OM/qa9Tv8Atl2f04sJdTidx/BDmQ/bivN+1V3adqNZTUbAvDGIxGxlXDEqTzgHyIqZJD4pZW1WNwyDKiQ5AoUvvQt8Kni0T+Ni3w4oqQxJ+FFB8+poszmNwErNPDlXAjYgkEHFT1glbwVfif6VI3Uu6lmCWzBrbD+JyfgMURYYl/gB+PNdurt1NA5hRwK7ND3V26lGxCZqDd2YnuO87zbkAYxkmpe6jWem3Oq3qW9qEMhUn322jApKSDxLWkwLRu6Sqt7WGK8kRlD5QMCwz6Gop0u9vr5ksrK4uSecQxM/5Ct8vZhtHC3F4beWR8qpUE7Mc9T5/pVhour21prtsZrxApJjIMmcZBHQeuKshSPaYzdYjG1BMLF2A7TSJubS3t1wT+/dY/sTn7UXTex90L63a4u4YF3qCVBbAJx6Dxr1XUe0VkZGEYllG3blRt6+prxyeXa5WWbJBwdz5qFtQnReYyq3U8T0c9lOzlnEfaL+S8k/lV8A/Jf61FsLTS9L1lby00+AojEhSgyQQR1OfOqXTNXtV0mAGTcyLsIUZ6HFGOsM/EFszep5/Ks86jUHIRQP78ZYFYbknM2U3au9ddsKRQL6DcR9ePtVdcaneXAzPdysvkWwv06Vl5tQvf4iYgegC4qOZGkbLsXP+45oPKus994QrVegmiXVIbSZJY5l7yNgwwN3Iqm7R6lqFxqInFxIS6g/u+OQeOnyqODzTUbHHlUtdHl+6xiKgy8fVLcnfJPvc8nqxqg7UTjUrGFYIZGeOTI45wRjp9KEfdlZR4GjJ05OKVWnStt/UxyNwxMeylWKsMEHBB8KmaRII9QXJwGUr/n0qZqFhbQzPcXFywErEqiLyfTJqoRu6lAHTORWjuDDEzrbPJImwVq6oFl2liinY3NuiRY47tctn4muqkzODgLLyX0sM7pvQtLtp+2nba64tMALGBaULTwtOC0JaSBIwLTgtOC08LQFoYWMC04LTgtOC0BaSBY0LTwKcBTgtATJAsQCnqK4CnqKjJkqrHKKMgpiijIKhYywohogOhXPw6ip1oFYtGRnd+Hnof8AOKDZRiRyvi3HJwPn9KPHbjJV5FVsZHiD86oWsORLSCGEZDnCMoGWywwR/WnqAcDoAPE4NECwxHdJK0knHulevFJgBs42pnr+IkelZ7NmWVht8Xs+erHGU8OOlRZJFiQM+SMgAAedSI4nnA2hiM4UE9KHPcT9ztDYaNSUBGcGoVI3YMM8dJDvL2VJFgNv3WDjLHdn6dflTbeFjHPIbna25sttyqDxLKeR1wD60EXZlktZnjxwxjXP7pdo5Zm6qc4x4dKEZZL+6WaOG4ktohiR8AMfH3j0PPnW2lIQYUYlUlm4MNpF2lkyCYEG73BNudxVcZx4Y5xVmskJmneRotxID+7lYs5ypHg2Bxis4un3t1qNrdSXcSvCCroowJSxPQdFHIHHlVzqEUibcOsjO2GO0CRm8zjqPAU7qC3XkwWALTnumljk2IsgVv3UMj7evjv9B5mqrU+12lrP7J/qELyPIATGdzOeg3BcgY86ou3mlJvhupI1MIG3IO9SV6j/AOoZ+FY6O3CXaNGSiocgqeCw8F8KlSkMN0IL3E9cu2eBDIY5EaULxuBRABxt8VJ5J+NZvtLe6ppstu2n3nchhudu6Bbz/i+VXUOpvqWnW9xPGxDOoKxrnfIeSXXwUDPIHnVd2tuYr7TvZmYIyyZ3OwOD09w9SoGMfCo1GDgiGoPSYaU32p6ikd5eTXZkOT3shwMnqwHA+lekpFbwWKWBh3XIUBEP4mYjClW8sc81j4To+lyLdzarDLJGoOzP7wy56YGdyAAZ6dfSg3Xbm2E8riB7rvlZHG0Iu09QPEenHFE43cDtJAAO81l/bQns9KHkJeMbsq2f3nXZt8MAdR515/eRKLl41UbnG8Z6c+OfDFC1DtzPO4WGKC1SNQkaZMrIo6DJ6/MUPStUOs3LRugQww++QvEik9MZ8/zqMEA4Jg+YrHGeZptL0K3HZ6TUJJm77lY4wRyONxI64xnBFecapLPYzd2hAAZlPGTkGvZtO09/ZjasqOY4gg2vhckcKvn1IxXn3aXsnNJpF/qglUG1mUPDtO4dFY5qGxsKeYrlOw7TzMR7VMZlkaRyQem7P2rRd0otxKHByeB+Q+PjVCluijLuGPXA4qYLqUKo3k7V2ZBxgYxyRUFb7c5lGm3ZndNfp6yzWTWkcDu0Y5ESF269Nvic+NXqRahok8M13Jc6XZK+2Uh0jbBH8OeTkePHNV/7P+0DWmlXFm0kuI5t65uWjUBhzwo3N0PjV5rsNtd9nbhILV5JBmYTC32K2DnHvncenhWHf4rbXqPJ2YXPXr+fwmirllzjiR7XtHoMFxGsSrPdLyJIImcEk8KpfkHHHUjJPUVeWg1vVFa4htLe3jf3Q9xMSRjjG1PD0J8q89ttOiFyjy3e1SiTKEQlmyM8fAjrWugnDozKt6C3TbIUTGP5euT55FWm1LdhgSE6oLwOT6d/tmatdBF5oxsNXn9tBkLkoDGPh1JPzoOmditD0jUVvbaz/fp+BpDu2HzHr61X2epXEFo1rbxJ77FuSSy5HQbf6/OrnR2v0icXeWzgrnjHnxyfrWTqNXTT7VrgSeuxrD7plVq3YaPW+0M2oXN60cUgUbIkAc4GOWP6VT9qP2f2VnYQ3mkQ921tkTAksXU9WJPXH5c+Fb52KxM7HoOg4rraQS243cke6c+NVdL4tVqbDXUTwJDb4ZQ6PuUe11Pf6zzLszoMranFLYotzJDKpluGb91Fg8jcPxt6DgV6VqWn2+q6Zc2F0m+3uY2icehH5jr8qPDBFbQJDDEkUSDaiIu1VHkBT6vZJ5JhabSU6WvyqlwP1+c+TNY0m50HXrrTbkfvrSUoTj8QHRvgRg/OmDB5HQ8ivWP23dmt0dr2jt05XFtdY8v4G/NfmK8kgfKlT1HIqcncMzNdPLcrJ+m6TfavcdzY2rzsOpUe6vxboPnW+0T9m9vDtm1eb2h+vcREqg+LdT8sVK/Z5eTT9m/Z5YXRLeQiOQphZFPPB8SDkZ+FazdxnwHNULbWBKjiaNFCbQx5nW8EFpbrBbwpDCn4UjXao+VHDcZ8uai291DdRl7eZJkDFSyMGGR1GRULtDYjU+z93bb9jFNyMW2jcOQD6Hp86iRAbArnHPPwlsnC5Xma3spo47Wd9LZX1ube3k7qV1beVbAOAB6Hr0qPfW02karJCwzLaycf7h/dT96y/wCy1tc7FTXt04hWG9hCm2ckkMDlXOOBgFhjPj6Vpb2/udSu2urqTvJWwCcAYA6AAVb1tWnpbZS27Hf+/tK2na+wk2jAP1gJtQbUH3nhE4VfIefxNZjtNp7XCSIpYJMhLFTyOMH9PrV8E7m8IHCyDI/z4/nQ9TjD2ZY/wHPyPB/OjR1q1SWoMK4/Xgj8jKOgNtmkfTXnL1kgk9+6n81I/PMwmj/tF1zsnYto+mixe2glchpLclmJOSSd3n+VTm/bV2uVSQuncDP/AOEP/wB1Vo0ixub/AGXFsGJJBKkqc+uKZqOk6BZwTIFHtQjJji799zHHGBnnmt5qQOTM01tngz33UuzcXa3stZafr/fAusU83s7dzmULz58AseK+ee0MNj2e1GS40ndzNJHGZX7zEYz98Ac16X+xXUoLTRLmDVdXQape3gWK2urnMxVUAXarHPJLdOuKiftj0i7vda0xLPSZf9PtYGkuLmK3xEm5+SzAYGFXJz0zWa1fnAYPEkVxVk45mftddvJ9Mh0qaOI70UF1BUjjceOnXiq2MG3ErOAEzkcgVKsJIb2+lnhkiZUDHhvMjgfKoutzWaRNahh7WcSInP59Bxmtbw2tdPUQO5klmoyu8xkl7ZTWZaZ5nlQq0UUXO/Dcgt4cZ6A81c3Fzol4sGqIL6e+LiPe5S2jDKgYn+IngjBGCQOlY2CzlmIjZkBGSPGtX2f7MWr2khdxcxxwtcJDKvurKM88EZHXj1NK69Q4J6ymrWalDkcTEancQ317dXkS7u+kZ8Ak8k89efqKHYaVqN0QIIGIJwCTgZ6gV6Dr1haRyW1zFaQQpMgIWNAqg8ZGPQg0bRZsGWNQMHDYA4PhVezWgAgCSU6F+G3YmZsuyN/MwkfZApyMv4kVmtRtZbPUJ7WfJkhcocnNewcA4IOcePhXm/bSDue0srAY75Ek+eMH/wDjTaXUF32mHrNPtUWZyZm26VY6W/7mRPJgfqP7VAYUfTn2zuv8y/kf/NaMyW6S23V26hbq7dTSDELupd1B3Uu6lFiG3V26ghqXdTR8Qu6l3UHeB1NFghludxiXcF6nIAHzNMSB1jqjNwozF3VYaHfmw1q2uAN2G2kZxkEY/Wo6WA/5lzEvomXP9PvRHitrZA6CWRwcgsQo49B/Wg3/AP56y7To7twYjEuO2GrXF/pQj7qENHKGVOpPUHqfWsva6ZrEjRzxiGIAhlO8efpmjXU8t9cjvHXyAJwATV7YxSW1gkcmNyZ6HPjxVVrXRfbwTOiroVpT3FxNPKxklducYJrOXqd3fTLj+LI+fNaKZdlzIvkxqn1OHdfJggGRQOfStCogDjpIbV4xHafNiydM8h/sat9IkZdSCFiQ6kcn5/pUPT9H/wDYz3RuNwTgoFwTjnrn1q00q3glkMuHWSJhj3uOR8Kp6h1bOJYoVgBmXBjR8FkViOmRmod+gV4yoAyCOBU4dKjagubYN/KwqnS2HEs2D2TIAphOJDTlBPQE/AUOXiQeorTlKWVjh4HQ+o+oqPBpsxA3ui/c0bTQ6ltysARkEirBYwkTSMThcnAHPFUGdkche8s4BUFpTalocM9snezEBGzkkKBx5msXewiG9dI5UkjGMOhyOnnWg1ntNaX9jLZw20hSTGJHOMYOc4rOvgYBya0KFsxl5g6y6tmxWM/GWayQXvs1rKREgCgyHChTjk8eFdVeGA8hXUZqP+JxIV1X/wC0DT2bbShafilArfLQgsZtpwWnYp2KHdDCxoWlAp2KUChJhhYmKcBSgU4LQkyQLEApwFKFp4WgLQwsaBT1FcFp4FATJQsUCjJTAKLEhdyqglgM4x1GMnFQOwHWTKsNCcOuKmFO6Zo8+/ncCc5HpUJRg1PWUPbjcR7uBjdyaqW5k44hAQ0QJdmfPAx0FEjO/qDtB6eFBWUdwccHwxxz/wCKkJKI7fCIWGeW5wTj+9UXEmBju9A7whguANu2nshnUzNgHGeeOPOosbbDkHnw8vvRI5V3ENkDBz/aq7J3EI/CU01rBb2FwqzuHGd3dsckn8KFfEeoomkXbWjR2QTvJ5UE6hcAgscLz49M4NR9XgubTUWuIrS6mZo9oa2Xc8TngMPXFZnTru7m7eRo9ne2ltIvc97cx92TtXO5iMgMSvXPU1v1MtlfJ7Zldx1Bm3ivbWK1dlfvkLsCSnuyMMe6V6rgnrVc2sWtskRudQtoXjb3vaJQrRxjB/dn+YnwxUm7gd9SggV+8O3Ox15jQeeOvHNYjtbpy22pC5IQLKN6SIMjaSRn/wCnp60aIrHGeTBAGJJ1vtPY6yiWkbSXJ3AARxEAjoSTxyc9RVHedoNLs7ea3h0po7juvZy7FQmDyXIOTv6cgjpRezenpc6/AxVO7zuAL4XAB5J8OnQ1me39nFB2mkeAu0EmQrN1ODkZ+R+1HaRUuAOkVjlEyBF/9VX1vCIrbU54I1UqFgcg4PUZHP3qrGpJcT4fvCzHAZ+SaqgCfAj0riNpyCMqc48qonUsTmUvxDE5l5nggcV3PGDmjwXthF70lxgOc7ANzbceOPjVmvaLstYRj2PQZb2UKMveSYXPwGasWWqnxlzeg6mZnVbRra9AYg94oO4DxHBFTuyl0th2ktpWZUD7kJJ6ZHByfUCq64upbyRd23CZC45wPLmhrGzKdoIzyMdAaoMw37llLeBZvWezS9rdOtFcWt9DOXb3I4YzI20A+9IuMF8ngg1nm7XaY9nq1pOl4y3cRQgRZKNg+8efP58Vr+zUlnd6QkzLFCJYgz4Az74GSePPI9TxWEg0zTWu71L28ulSFmVY7e37wuPD3iQF+dK1/Y4HWX7bgiZJAzMpBpt7LEsncMqMM73IRT65JphsZheeywqLidgCEgBkz9smtdY6SDaKsGmRZB3LLcsXLLnoeQPAdMePnV1NZ3N60IkNvaQpHju7WBYR08ccsfiSPzqm16qfaImbv3D/AI1J/L+cTD6TqupdndWYQkwyyrtZZF5wRwcH41ewNc6rvF5dGNZc4kbcdmOTlFBJBBwPhWgh0axjkaTu8u+S5YDLHOc9KsLeKOBQIkVNo6jqfn86zrraWfeF5llE1ONvAH1+3H6yFZL7Jbw2tvbPfe6dkso7pfP8IJPT1FW+i2xvrmUXCbRAcOATgkgEAHywaGrDoMDjgedOa/uoU7hHMSjn3Rgn4nrWJ4rqXFBFQwTxn0mhodMxs9t8/DjE0mbWwT3jHAv0J/U1Gm7QW8eVgjaU+Z91f61mjIWYkkknqTVnY6ULqBZmmwjfwqOfnXJU6JrWx1M3GrrrGXMlwaxPd3axzFFjfgKowAfD19PnVZ2xvNS0/Qzd6feSW3dsO92Y5B6H5H86LcafNbd5KCO7RvdOeTzwasLq3j1XSZbeZcJdRFGBHTIx+dT1l/DtVXcy8A8/Lv8AbMg1NS30slZxkdvWeZWWr6pba1b6nc3Etw9u+/bLKSW45HjjrWmue32rT/8ABMFuOuETcfqc1cdmf2f6YIop+0MtzKSAe6gQ90fiy5Y/QVuptG7G3FgkaaVY3EcY2KsUe0rjwLDBB+PNelPq9M5BUbpwFKavaVV8TE9vdURexQzaR3VtqQELl2ICBkLA8eORx618+adHPba3CEt0uZoJA3cuARLjkrg8HIr2Ht/KlvrK6VbNIljBFGVt2kLopxkYB8hivK9bRrfUxdQ+6VYHI8D1B/SnFA8jcP6Jas1hs1O09B+s9psZ7a8itp0kK2kwVg4XJVD6eYHh5itN+0/9nlsf2azT6Q8z3dqy3BPec3CdGTA4PB3AeY9a837HXwubCSIHCEmaNCMFM/jX5Nk/9xrV3F9dXccUdxcSSpCoSNWbIQDpgVlae8aSzdtyR0m9bU2oVcNgd5iNAtdX7M6NdT3EMfdyMrLCX96M9CxxwB0GOvSg3F5e6rLiV2l8kUYUfL+tE7YdqrrTbh9MgtEXfGCZpfeDKR/CPqOfKkTX9LtdMgnLqneoGEKctnxGPjnrTX2vfYbnHJ9JPp0rUeXuwF9YmsdqdU0extrGKKOORo8e0n3zgHGAOmQMcnPhWw7Paqus6JbXgwHZdsgHg44b+vzrynWu0DavsTuEihjbcueWz06/oKt+yGs3ujQ3SC2MkM2GUMSNrDjOPIj4dBQChrMKo5kVuqrqZnLewJ6dcoDD3nQxnIP6f55VR9odWWBordVLkjvGAOAPIfmfpWVl1zU5tVjnnleQROHWNThQPEeXTNdd3S3Fw8spGXP8R6eVbdXhzUqvmNkg5+X94nKN40tmqaypDsZcE+pB44+RP1HpJE2oW1jbyanMz8ttMUQBIJPmfrWevrhW1hddcMLZ9ojjJzJ+HAz4eZ60O2mfV2uobl2NpG4dVA2nIyBz8M1A7y71O1WMgCGI4UhcAcefjxVbUXtZ7BPHea4xwVHyl52W7QWWl/tDtNev4LiS1gLMqRKpfd3ZVepA6nNe09u+0UV3+xq51O3SWCPVYY44klADhZHHXBI/CD414VqWny21nYqWjaW5TcqRqRjgAdSfOvVf2zMNK7Idn9Bi42t+H0ijCD7tU9CbVGRKV7HcZi9Gt8aJbxlQTOd2P+psD7YqbrXZy0vtTgu/bFtBEmwqF3FuT6+RqLeJ3FtFbKzJs2oCp5G0f1FQ/Zo3z3jSyEn+JiflUwtCHrElbumFUHPqY240z2XVo/ZJjJahRueVgpzznj6VpdGu5bGORBAZhIjxA5Khdw86CzQw6SZkhRWKAnGACcY5+fjVNado0iusm4V1JwCVxx05PwyfmPgBBN+XUdI7K9BCO4UH0H85l+9rc3sMcdyyJEjMyjG4gnr9adHpECN77McrjapwCasktJSwX90mV/5kqg+eQM5p/srBdvexkk9Acj05xVTda3QYlwafTj3m3fM5+3T7SHJF3tvJCcrvBiJ6FSeK8hkDKxD8sDhs+Y4r1zOHwcAbs5HJrzzVtDv21q7FvYzyRtKWVlQ7SDzwfnVrQuFJBkfiFZYKVGZQtXW7bLpD64+vFWN1oOq2lu009jIiKQDyCefQHNCTRbx+cIniNzYrVDKwyDMsUWHjaYfdXZqQ2m3AiZu8iDAE4wWz+VLBBE0aOctuAPJpZjpobW68SKXC9SB8aYZ0HmenQefFVkw7jtBhTwZCoz5H/wA1OK7gpAPAwUz4DOf0qJrCDiTpoF/yMsNMgGoXbQl+7wu4EDOR/n5VfQaHaR8yF5j/ALjgfQVntFkMepwkAEFgufIMCRn51rriB5rcok3dMedwzxVK2192MzV0+joC525MgatpqPpdxHa2wEgUMoROcg/WqPStJ1JJX720lRWHBkwOfnUi9M9vdFGnkcphgSx58fOtMrB1DDoRmiFzVJgc5lnyVZsjjEpZbaW2294AN3TBzUeVt0mPADFaCe3S5VVkzhTng4qNdWFtHbOyw+8FJzkk9KJNSMe11jmo54maPifEVrI272FXHR1B+orOWsNvcXKxvJIpkPG1Bj6k1pbaFIYEjXc4RcAmotQQTxDq4HMo79dt4T/MAf0qJcwuqpI0bBTkBiK0Fol0sTtcogcE7WULnHyoN/bS3VoQiM7hgQPOjF2AEjeXk7pX6Q25biA9HAP5j9ador7bmSM/xL+R/vRNP0y9gut7w7FKkElh/WjQaZcW+o98DGY8ngNzg0Y09lmdik5gG6tMbmAj7/V4dOkVJY5GLDcNoGKiSdoS/EcCj/rOftTO0kLSi3ZVy4JUgHJx1qohMkQKt7pHw/Sr1GjVVHmrzMnU62wuRW3E2Wn3JuraNyw7wjlR4c+VGW0UHcFCHJyccmqHTbrTIImleS8nnxgxrGsaD/uLEn6VbqEdFbaORnpmnXwo2sSrYEceKBFAZeZL2Rr1YZ9TT/dC84wahbVB4UfSiKrSHCqzH0Gasp4GM+0/0Ehfxc44X7zN3sfZWwZ4jG00wyCEZm2n4kgVkyOOTn71urrsYl3dzXc00kIcl2zgAedVuoaBpMOmyizve/u+NmW93rzzx4UZ0L05JP1Mzn1S2kDA/ITNoFUDx+1dWg0C1TTr7v7x7Z02FdhJYg+B6V1PXUjDLPiQ2WFTgAmengUuK7FOxVvM2wsTFLilpcU0MLEApwFcBT1FCTDCxAtPC05VoqpUTNiSBcxgSnBKlw2U8sJlSMmNereFESykd9gxu3BcZ6moDavrJQkhBKcFqZJZSRoXK5QNt3A8ZoaxrvAYNtPUjnHyofNGMwwkBijQyPFIHRirDoRwRR2gtgWCzMSCQCV4PlTfZTjMbLJgZIXr9KA2KRzCAhG2SoZAQrge8uOvqKajFWBHUUNCVORxT7mWGENOzLFCOSXYAL8TUR4OIY46w7XUMYUSyxRAAf8AFYKD9aJZalb3cO+1uo5YIX2l4nDAkHnHrXhetMs2sXXdz+1IJSY5CxYEZyOT6cVI0btBd6FqNmPaXWxe6DyxhsLzgMSPHj8qiarPMyF8VBt2MvHTrPc5QdpO3flR7w55z500KDC0gGCDlSMnnGcVWwa9aXE/slvf280hBfu0cNwOvTw5o241AKzNxTkcGSzIicsqnKAYXx9T61Du83EMqgY3A4A6Dyp75CKfOhknriiQY5EISrj1FdoRGmkdFJkhWJmd5DkAhwOAB1BOKhX1nf6rMLi5twSMs3eFcvwOAq58q0BJPU03r16Vd/EMDkCMFwcyq1GCe1tWYpEplZRO8eVLED3VK9OMdQOtZftf2TuNWubO3t9sErMMNMfd/DkjK58xxWkhnmeVY5ZEnntpdqh3CNEgOWkBPDHjofOoN9qUtwYplTEVtKH3KpAZieWI8CR4VbKll2/34QWXcNh6TzLtN2Wn7MT28U1wlyJoi+9UKgEHBHPyqkK+7jk44r1b9oGnPq+m2YtGW4u45GIQOo/dkHPpnIXx8+tYy27Da/Oy5tBbxs20PK/GfgM5rONLA4xM22gh8IOJniArH4558qcASR0I56Vp7bsDqMryC7kjs1QbmkmkSKMDJw27JOPTHNS7ns1oumFIfa11aVGJlNo7oh44BkYEHBGPdHjTNWV6yF1FQzYQPnMgu3chPIHiasILHUbqD2i30+drfkmUjauB1wefP8q2lrFBaRy2um9n9P7x+Ir2+R5pCvidjEoD9uPGiWmlahLYtDcXBMRZeZPeb3SWG3OcYzjjHHHhVV7lQdZElu7hFJ+Q/c8QI0jtRYdmrYahNFpVopKruuO7klzyFC53MRk4Ax15qZFd6VZ6ZbObku+3Y0rqqY4VhkDJIbfwSeqsDyMUW37M2UCthFYdFbbhlJzn5Z5+IqZLpdo1hJAsIbchBLck85/QVRt1FbjByZbT8SxGFAHxOf0/mBguYLgfuriObjju3DfrUgNkEnJ6DOazR0ixkfeYAjHB3JwR8KvjMm0MxCk4+dUGUZ9mX1dgCbMD84PU9VXTYkZoJZ2fgCPA6fGs7c9tL+V2FrYpCOgaZi7D8hV5rcRl05mPDRODjGPQ/pWKuF23LDz5qalEYciJye0kTatrV6pWbU5I0PVIfcH2xVj2Wf2W7li7x3E4BJds+8OlUoIUZZgvqTipFpq8Ng4ljkJlUhl2LnkfGi1FAuqarHWNTaanDz0Wa0u7bR5tWktJvYYMF5dnHUD58kVpNJvLXUNKt7u0UJBKu4KABtPQgjzBoul6yNa7KoFCtZ38eZInUHORhlPzFRb5bqztootLtolXJUqqABPUDgVjU6SunhCc98y41119ntYC9vX847slc6tpz6ouvRw3kc5xbMpUMoGQeAOARg+eRU+CFZ7eZc4kiUyLn+IDqP1qvslu4LZjqEyMxO4HPQeXl9KnIs8MbXIgJjRCx3naGBGMc+YqTVKt1RrsxiQ01eQcIST8TmZuWa4h7QSCOSWOJ03bVcgNxjOPjWo7Nrt0CFzx3jPKfm5/SslcsLW2kvGRn7lSTt6kVldR7W39/p8NgJPZ7SKMJ3cZ/HjxY+Pw6VB4LpGuTI+RPymd4rrBTqyp5CrwB6k85+ghu11xDe9rL+dCsqb9isDkEAAcfSqK5hiurSS3kA7uQYIHHzoZl4prTBRkkAetdwqBVCek5F2LuX75zJ+jSvp6wMshlMPukt1K9MH1xxW3V1dVdDuVgCD5ivM4Jc3Es0b5zhcg+QrQWXaX2TTWieHfJHnZzhcevz/OsXV+GWqN6cj+9Z0+h8aodvKsO1v1+X8Qvb3SfbdEF9GuZbI7j6xn8X0OD9axml9mdQ1OLvlVYIT0eXgt8B/WtfCt7q7CfUZCIeqQL7qn1I8vvVwgwAAMDwrPBNa7e81mRbG3TM2nZJ7cgq0Jf+dsk/lx8qtrTs1PcyCE3ManBOTnHFWoFGhX8Z8hiir1d1J3VnB+UC/w/T6pAly5HXqf2md1jRLixKd0qzIiYZomDEnJycDmsdfd5qSBxG0cVsT3glG1h0zx8K9PlIVSTwBya8y1O7mm1C+t413LOxkdjktk4z8ugq5+OtuXa2JknwrT6Mg15+EBqFxFqF9blGKwKNu4qeOfL6VoOzei2spneVSwACjBbk854GKh6NpyiOKaUwxrHIA+WyzAEE8Y+XWtvFfTXZ22Nlc3P/QhxSrRuNq5H2jW2pgg2BT8OT9JT2lkNV/apomm7T3cc0IZSOiqe8b7LV7+1q6/1L9p+m2B5S1gj3DyLMZG/wDpC1F7OI3ZL9oK6/2qiewtZElEEgXvRvKhQCFyR7uTyKqdX1e31r9pWq6oky9zKXFsz+5vUKqLjOP4cnFaIzjmUz0xkn59YK/uVFwhk7zkFjsXdyTUhbCduFgcMD0kYL18fh86A2953Khj0XKjPT/zWh062u7yGPuoJWXHJ2kAeGScVVsfBwFl+nTMwBLkD0GBM/fC9twbQXMfcMpBATOQcgjJoml6Tbm3Dl8FSwKqoUEcY5HOa0U/ZK4uthkuoYymc4Usf0qws+w86F2h1GCS2fBDkHdnHPA4x86EWPtwvEuP4cuQ1i5+ZzKbTrS2spQ0MQD4wzk+82T/AOKtlkA5LKOc8r160XULHT9EuI1uxdXLMNw2MqgAHHxqXY6nYPCZLTTgpB6uQT5+tTVBsZaVbkVDtUYxKCTcZ2KocEHG1M55/oaUW91t3C0uXx/LCzfpitMdVuZBgfuw2cbBzx681Ge4eVv3kkj45PjnrxQ+SCcmGdQ2MATF6vc7VewlglhuJRlUcbT5g4+Rqmt7C6nLGOB9inDuy7Qv+YrfagEJidkVx4hl6Y5HPXxNRSQyBWbKtnIB6eFGLxT7KCNWlzncz8egAmS9hlDFfdI/mzgUC20Ro4wkk4wMgbBnjPHWrScmIAkc4x8SKgpqRnKCEKd43A5z7pwA3wyTUottccS+/lJyxmQ7WWK6fqkTRMzK0YfLdcg/2FHW1O0sze6uOB4KeQfy+lWHba1eTTbS9K4Utt+RGR+VQLCcS6dCZD7ojUsR1G04x9qPcSoJlVgN5x0ktGjhn7yMbdre8o8MHAP1H3q8uNaigmaIwyNj+IMBwazqP3UkYJUHiM4/iz6+hFaKz0q11CwguJzL3jRgMFOBkceXpSShrmwozJksCA5h5rG1uJN8kRZsYzuPSpMabUVEU4UYA68UdIY41AVc4GMsc0m0K2RkYPmasr4Vcw9ogR21aDoJywufDHxoV00MOFnnRQw6cZP1o0juMbQG+JxUO5sYr8p7SGGzO3Y3n8qkbwvYvBJPw4/WRDV7jzwJXHREKxvaX2f9xHh6Yq1gRkhRHkMjqoBI8aSK1itYVjh3BVz+Ihj+VH3kKAPyof8A1eof3iB/fhHOtqXpkxdjMCAu3jzouY1HLZNALE+JNNq/R4SE95z+XH3lK3XFui/WFMi+AB/7cUMsT4/am11atWmrq90c+vU/WZ9lzv1My3aaILqSSY/HGPscU62utGigBGmzXMvQm5utqfJYwD9WqdrWh6zeXg7qNp7YANH764XIGQMkeVbvsTplvb6evfafbxXiom9u7UsDjB5+WapWj2iZUawATBw6bqWsupsNECIP/wBNblE+bMefma0VroGrrGsDafO0sYCtsXcAfiOK9I3E4ySfjTmu+4hw84ij6+820UNd7Vn2RIjbu4xMXb9idVmwZ+5tFPXvGJP0ANXcHYnT4YFae4vLokZAgTA+gH5103a3S4yQszysP5VOPqap7ztILqRh7XKsJPuxgnCjy4q9WuqtPJ2j+/nK7sPTMnahpum2kTpHp9wu5SpM0ioeeOg5rHDslAAAbuU48lFWjalark78/BaA+sIP+HET8TVxtDVaB5w3Eeuf5gV32158s4zIf/pK08bif/6f6V1EbVbgtncqL5Bc11D/AOt03/4Ek/F6j/8ARmjpcVWf+oLBNPhu5Ze7EoHuEe8CfP71JstTsdQkdLW5SZkGWC54FZe4HvOpVlPAMl0orsVzMsaF3IVQMkmkZKBHAURRUSDUbKeYQx3UTSn+Dd73TPSpyigJhLg8iPRKsrK3T/iTKe7xx8ahRKWYDzq/WHuYAinvVUhhnjaSOn5Vn6i3AxJ1WdEIjbn3myudoJPGRg8fCnGJ7mOOOOPbleVVRyfOpSwLIgkcbpV/EQPD19ajygbUKHpkVlG7niTKoPSR1gENxmUknGSCp6+RoFxZtGgfH4hkqAfdHrVkpeYkOcljyW/r4Ux4JAxO5QD12ttXy5zUiXHPWJhiUjLQ5JRAOZUiYkAFzgZqp7eT3GnaA7QTPFJ3qrvjfHBzn4j4eleVSmWUvKJGaY4IZjnkdDzWgDuEy9VrxpnCbcz3ota3EYXDQSgdSQVP0ArHdte0EGnQXeiS28s8k8WQysAnPKsPPpQez/be31OT2K7Ror2GEvLIxURuwxnbz65rO9qrqTXNTiuIYwIEiCqx90jk5DE+v2NCq4PB4javVL5Oajyf6Zn9Nh9s1q3spZBbpO4Bc+8VBOM4ru1WnPo+uy6e0jywLtkQsoUspHBz9aVNNWS/RzqMcbh1CCMFyrAgjngDnFaftER2k1iO71FEMpiZAUG3kHPPn1qTaWBAEwErzzM5pF9NpF7b3tsdpiYMEz+JT1U+hzW40Tt1c6nrlrZXFpDBHO7R7lLElsEr1+Fedqf3hRmw68EUR7j2ZRKpy6EEe9jB8/SmKgiS0aq3TttU8Z6T0Ht5rOq6Tc2sdrcm3t50JyigNkHB56+Iqg7N9qJ9O1jvtRuZri2liKyuxZymOQQPt86y0U8pJWaSWUA5UsS3XwGaJJmZQoZlwRkDqR5UIHGIdutsN/mqfyzPa9M1O11jT472ykMkEhIBK7TkHB4NTlhkkUuqEqCATnAyazfYWzurXs4lpLazROJnKLJHsJViCDz1HPWtJH3sJdncQqOOW6sOQPzHn5UKoSeZ1VdpepXPUgSuvhZWNxJBcxrJLcASlGGRIc7QoOMqcjJ8ORUCOytoNLmjupdsqktIFflTyFAGdr5PUjkc1M1GG4lmtrqCYe1IS2XXcu0jB9D06dKjX2iLHCkSRybLaIskCNtXzOCdzAk9fh0rQXAAGYYPYmE015JNEt+6tSxkXupHIABYHAGev5/WjXNvqDb3iaIN3ZkjjX3gCARg54BOfsRzzVFba/PBCkVpbwwR537CzPyep5PU/wCeNW8dwktpFLLdqjzEO+4hRtPXnPmCPl4DNJlIOTHwRKW50q2a4huVuZZTOFn3vwuSPeGMeYrobW3hZmSFQWG3J8Bx/SjNcWjWcdvFKjyQSyJuVs70zlSB5eFIG9wDI5PTHNcvr963MCeJD+HqDbtvMLkqcMM8cZ8M04YBBJBz1wear727e1RCkYbcSCeTj5VXnU7m4OFk2g8AIu35nGagq0j2jcOkjt1SVHaes0YPPHyzRN5VzjjHBwaqdInEkUsbOXZXB3EngHr+VWQYKSOD4f3qvbWa2KGTVuLFDCZvV1uILe6Fq5WWMkqcZyBzj6UnZbZrssC3JkkbGGRDjcR1+HHNWepIVug/GHUH9KzUkF12fJ1DT5uFl3sAv4B06/PH0rT8OvStsP3nL/8Aknh1+sqzpzyJpNY0LUXt3/0q7d45XcdzgblUc8lsnpxx6edYaaGbvP3zOSeAx4z8K2ei9uLa4RIrzcrxxuqSK26V26gnw5GfnV7qGmWGsQsWKOypFEtxFzsB55XPXB/KtmzRVWjdVwZxeh/8j1vhrCnXAsvr3+vf9Z486Kh944JOOTyavey3Z9tdupNkNxMEHurCu5i35AD1oPavsbf6dMb9XNzZhztAOQig4Lkjp4HHrXq37Ip7dNM1e1G6OaKdJZIwcx7SgCsgxxkqc/AVka2p6aST1na6TxSrxCwJSfZYH55GOJXQDUv2e6BI2pWkl5bPIGiEDAmEnqJD0AOAQRnnNU8/bfWNYtHexMWnqp2kRr3kh9MkYHHjivWriCK9hkhuI1limBV0boQa8tv9Mtuxva+3t3I7nUNwhc46DxI8GBOM+IOawOvQczW1ZuprHlHgS/7N6rZR2dlJqhla9lJDBlLBQP8AmMfEfDpzUy/18a1KkVpbN7IhJM8nBc48B5fH7VCUf0o6AKB4AcVTtbzEKHvKC+MXKQyjkR6wo0bI65VgQw8xXj+tM+k9optMeMgRybQ5PVTypA+BFenap2js9JkW3Cvd378R2kA3SMfX+UfH6Vj9Q7IXs0J1XVDALiWQRiPJkMOTxubPJrQ8KcaUkMcKRx8/WUyz6q7ewyWP9HP0EzdyJPZpe7d94UldvXz/ACqDb6ZfzgvIvdr/ADztt/PmrzcI1wMKP4h5EGjSXdpZ3tuyRoBsViX5b79ea6BL2HGMmaOp0owChCjvC6ToH/sUaW4VlYkjuxnPzNXEOl2luhYQhiMDc/vHrVFrOtXNlqITT7hO47tSU2hlyck06017U5rZWaO2kDEnBUr048DV92a7TkIOSJxaU2afxEWXuNitnj6jtNKoo6VQR67cblU6ZvYnAEcvJ+HBqd/rDwf/AInSNShx1IjDj9K5SzSX1+8hno1Piekt92wfXB+hluoqRGuLct5tj6D+9UkfaXSScPPNCfKWBh+WavNOvdJ1CFFi1O3Zucx7trZz/uxVGwFeomrW6tyDKjtBBdXGh3UFkoeeVNgBYLwevJ8cZrOdm+yly3aGyivbW7jhYkyko8YKBSSC3yA616rFZw2+CsQB/mYZP1ot1MYdPnlz+CNj9qZLzjYo6wbdOrHzGPSUmjdj5bEb1ntI0Zi6/wDthNIAeQNz+npWrhiaKMI0zS+pCr9gAKxcfbHUUADR2zADHKEfkaZJ2t1JlOe7A/2ZWupCEACcWuu0wJIJ5+cs+2vZR+0tvbbL2O1a33ELIvuvnHj1HTyPWqrQdDGla4k88UIiRWP7txJlsYAzx5nrihf+o2Y5kt92PHf+pFCPaq1QgPGc/wAW0g5p9rSUarTschv1mz/1VUAW3ttueAOn2FRLzU5Z7eSOSRkjdCpVODjGDgjnPzqrtLoXsUU0asQ65CYIIGelPawu5JMiAqAOC7YoMS6PWcmuGK9AkuWVY5NgKrkd2F2gHzOPHrV9p95Yro2bfMNrFjgERKoxjxxheKy9xpSe091Oy7pFzhQc5PA+/jVrplgh0m6sljdO/tzGXdCMt0HPxquVwcTqktRqw5wOJH7TSxXun2t5bujRxu0RYMTyeevj0qq0ucBpYy6kMu4g/Sr3TGjsrWSC6ktyNwIWNu8wQMEcCiSazYwgmGFC4PGQF4+WTVqmt3GFGZz3iGooSzcXGPpI8cE0vMdu7Njg9B19eKlRaVceJSNQMDByag3HaSYjEahf+kc/U1Qahr+otcCRJJo8jHEpA+gOK0qfCdTb0GPmZgP4xpUOA2fkP5xNm+kQMMzPLLg544FRptS0TTPxSWkbKMYJDN9Bk15rrF9f30oNxeXpUqAFE528elVqQSDOLi4H/cv9Ks/+h1A5YCRv4yhGE6fGbftR21tZNHngsVkZnGDKI9uzqQy55yGCnp4GvNbjX2ur9rmODunL95mNzwSQSVwfdJZQeABjA8M1aG1kkBVrqVlIwQUU5+1PtrXuW2m77sbeMQopH0o18LuQe79xKh8TDe8f1/iQ7691XWezslvPNcyrGyukRBCZz/COnnx4VVWFvLDbCOeN0IkIwfI8/nn61qjDZSKgme4kccM4PXrzgn4fSn9p7a3h06Ga0RY1Ev8AD0O4cZ9KztXU9GA6EZ+U2fDLfNLbWBA575+8zYdRF3cmDy3IPQ9M/DnNa3s5KZNMKN+KJyp/rWQJVpGCZ2scMo8R1JH/AMquuzOorEZRMXKuingZO4Zz9iDT6FwloJ6GatrBVJM1ZphFA/1O1IzuYfFcU6G7t7pmWGQOyjJFdCrIxwDKPnVtwrCPY5pBRFt5n6Rn58Uvsk4/5efmKsCtvSRNfWDjcIFjSDpRxZTt/CF9WNO9lij/AOLcKPRaMVMZA2or7HPy5kWuo0htF/C0jH0xQCyk+6CB6mi8siCLN3adTc4IOM+lPWN5OERm+AqRHptxJ1AQf7jUirInsUdTFXU2RFXuV4GPxGpFr2hms3ZooY8su33iSKRdDyObjn0X+9Mk0mGIZe9RfiP70JorbqJV319o647R6lPnN2Yx5RAL/eq2SVpnLSMzt5scn70+VLWI4W7Eh/2xn+tByCARyKmSpU90YiyO0aUBNd3Y9adXbqmxBJje7UedO2qPCkLU0sB1OKfoMmATHMFbqK6mbs9Mmuqu2roU4Lj6wZCuf/eaMXAySof5jr+tQtD1U6Rfm5EZlVkKFQcZqTos3e2bRn+BsY9D/hoOr2aRxLcRKEwQhAAAx4VzdiMvJ6ibKsRhlm50TWY9Zt5JViMTRttZSc+Gc1S37MmoTR7zIVbjLZ4PSqzsjfPZ3khdWa3kG2QgdCOQallkllcLuCZI29PHxpwxZZast31j1jEtnj1Rb+KTZKnKrjIJxjn0rUdmdXk1jTWlmVFmjkKMEGB5j/PSsLPI9lrae+3dZGFLHAU+FaDslN7L2jvrI8LOveKPUc/kT9KjzzC0jlXC9jN5bnEqHJHI5HUVcxxXEW9NwKqTyXzn09apI+tX1uwkKSIEZSMvnOEPlk+NUNTkTdHEl5BwBlig6jFEUxmRcoDgbSW6H+lRQAjPGqkEnHIwW9PhUjjOwIDt90t4k4rFsUyWA1OaLTNPlvruTubWMgs2NyqD0PHrisPqH7UNFjOLa3u72UAkZURoRnpySftUjtl210S3gvtGxJdyyRtG5gxtUkYwST1B8K8gVSzqxPK+VX9LRkZaYWu8QapgtRH+5s+2ek3es6+mqaRZz3FpfW8cy7OQhK8r6VAt+xOstazTXCRWndoXCSNlm8hhemelU69q9a0iazghvpfY4NpWDgAqDypOM4q00LtDLDdatcKWjkntZu7yO+Mbg7hjd6AjPkelX6/Z9k9pl3NTZ7YzkygtLGaa9jvNjlkIYbVwOPU1aa1IjLEisrOrHIHJAxVcZZZnDySs3B3A5IPyqILmQCATDBYBFI4GBgAn5YqTIUYlYN7JUQ0isFdgdu7HIPIPTP5VcaNfvc3BsrlUdoI1ZHPLP4EnPjyKn2PZSO80a8mlu27+HjuolHIxnOT6Z+1Ds9OtbeUTRoxlxt3s2Tjy8qkCEHMkqRgeZQXtssN/MmOjHocceFay0ihvP2U3+Qgaxm5CpljkjBPp7xGag3ekpeXfetIyAgAhRyahalDc6dbdxZTTm2uSFnhWQ4YjOCwHBHWmIK5IktoBGQJU7AUIBH4NuDz8Kfp/s7apa2t5JJDC5XvmUDcoJ5I+tWemaDqOpCUwW5CxLvYudnHP9K0/YXszpV3DNJer3mq2cmQxYtvDDK4HT06daHaesj01DXPt/Wbu2lUWGySeVmVRGkj4G5VO3LeR5HrkiklubUTdzGgcxsGyvLfDz+P5Zyad7Hp9mAqRgSALhlJIA4HQcf5nNJ7ckriK1h3xxtkbFxn7efHrx4CjHPInWDHaR3ur12VltWVRuMQm93Ix/wCc+lRWsb1t0Ut2EJUFmh5wpPTn0yB4YHA8anouoTznKLAykqolOdoPQcfA/HjGKHFph7phdXThNxVmj4zz8/E4zjnAFHkCGCBKmLR9JtDJMf8A3ESYKiR8lhjyHx8ufQc0HWLe3l0x7e2ty05k3xxxpuwBxx5jGefpwKm6lDb2C6fdErIsU+142bduUggZ6g4wPTw86NNqUFko7sbHllATaMrzxgY4Pjjz9MUeSeeskBz0mNMNxYmCWeAqjNhckeHXIzmrDjgDJY9Rj8qd2i1SK5s5LGJkaSB9zBQWYNkgjPnzyfP0AqpXW7dEUkSFlGCBgYI+PrWN4nUbmVkGT0P7SJtRUDywz85M1RTNp8rE5ZMNg+P+CqPdg+8z89eMZ+VSZtXmljZFhVQwIO4En+lQFxgZGemWDZ/tTaSp60IeZGrsSxwUltpl0sFywkbauwgLnjz605O1UTWlw7RGARYYlzuGPPAHn+dVuQcAAM3XAIAFQozFDrKpOgNtP7jg5xtbj7Go9Tp1Ylz1mR4hqdRXpwtTEDPOOv1/vWXN52onl0+0m0+yF+l04AWME7QM7sgD0PljxrGdr5rlNWkga5le33Hu1JIXb1HHToadq+n3kWvzGC7h0+KGEd0IwRvUg+6AvqDnNG7SGTVVs5TGUlaMcueDjy9P7UOlVVcYEy/Dia7gFbIcHPJz6jPrxxxMr7TNazK8TlckEgHGcVpez/bm40yWNd+wIQ2zO1DtORk+PBIrKz52EdCp/tUQ81sdORNbU6KnUrttGZ7tD2y0i90i49uk7pWhdHwOJ9xztVeuSp+3pWx/ZvZQWHYW17uUSzXeJpScblT/AJatjxxz86+ddKu5LwWNmbSN/Z3IWTGM7iDhj4jjHwNeudhNT1CK+viYTHbwsqIrHg8nK/kcjpxWZ4k72U88YP1lTwPw6nQa4115ORn5fl9sz1lRnivnD9p/aM612/uWgkzb6eRbQkHjKn3iPi2fpXuPabtJDo3Ym/1mOQK0cRWLd1Ep91QfUE5+VfKscjMpLMWbJJJ6k9c1S8Lp3FrD8p1Ous4Fc9u7P9qba+0i1edm9oI2y4UnGB+I/H08c0O51y+1WWSC0k/0eyQfvLucfvmH+xPD4/lVRpiQ2mlW8alMpEu7BGScUs0k9zZPPGzQRopDI3Vv8zWS1SByVEJPA6iNzMftGP2g07SJW07RNsdxLxLdTP8AvX8y7/wj0HNdcz9zbQwx3RuDkzsyNld3oB06fHnmsf7EbfULgxRlkDYGMcZGSKvdDO8lAAWjbeV9fAH/ALsVZ/DnIPr3l2nyaEZUHI+stNM0tdQvyZILx0djua2h3kE9evFby07E6HCEkktpLhwBgzt09MDGKNDr9lZafBC8rzSRxqrBF6kDzOBVTr3bt9L02S4htEDYxH3jZ3MenA+vyrYVWdgqDrOXFtQO2195+v8AMwn7QJLGHt09pZrEkcdvHFIka4CvycfHkfWpugaVNeafEyqEjy2Xbp1PTzrz68nklujdTOzySMTI56kk5z9a9M7N9oNOtuzFsJ58TZctGiliDuPy+9dH+GNeFHPE53x6q3aGoXJJxwM9jL+00+CyX92uXIwXPU/0o5OOhxWdue2UQyLazdvWVto+gz+dZ697a6k97HCkkcMZba/doBjPqcmpxprOpGJzVPgWuvOXGPmf4zPQJRuX94AV/wB/I+9U13NoIz3yWkjeSRhj9qy0s01y26eWSU/72JpFFXB4Yjf/AEOZ0Wk/8eNPLWn/APnj7zS6dcCNWl0+5u7WPcQFjlIA5/lJIq0k1q+ls5Lea4SVJBglogG+q/0rM6RLtmeInhxuHxH9vyq3DEcbjj61yGu0FVOoK7R6iaNuq1WnY1LYcfE54/OAk7zb+7VGP+5iPyqL3epEgs3dRno0YznB5qwcA871+GMUkVyT/wC2JLRlug6g+YPmKOirzTtEymuNQ3YBx1zI1vYW7XLpdXQuEZgVJVgxwenPmKu9M7JbIn3WzzGRQpMoCp1yeDzzx9PWqmfvImAZ8gEEHzGfCryy129tML3nfRjjbJz9D1FHXoNxItY/KHqvG7KEX8PWmfU5P7/rNLaWdxb2qxGSNcH+Fc/LmpKmVMmSPcP5o+ft1/Oo8WqCWzjn2rEJFB945wfKhe1vcthVeXnHkPpWoPDqmQALgTmU/wDJvEVtL7t3wIGPtj7STLd2pXHtJQkZ/d/i/Liqe8lsS3vXVyxz1ZgSPgDWc16/ubfXLmGKSN4g24KWx1A9arTqdwB79rn1Q1Tq0mnWzD2Yx8P+52J8YvvpVkrHIB6+v0/WW89z+8KiQuB0Jz+VRzPgeFVbamjN7yuvxArvbYT/AMzHxBFdZp/wxAAcfUTnLEuLFmUye0+c1Gmfeo9DQe+VvwurfA0hOa1q6lHIkGCDzBXCBwvpQGhJ/C+3/tz+tSjyKFJwvBwfD41M4CqTJ0Y8CQ2hc8G4z5gA/pTBbFCCOefHjio8u1sPEpHeswxnBU7geB4jIz8KXczxuyPIMjC93J1OSSBkdf04rkLvEbw2FIH9/v1m2lAxJgjPgVzT7mSS60lrFwCNwZTu6YPwqLFbzKctfTOvlgfqKlAcdfrWZfqrdQnl2nI+Q/iT0sdM/mVHmU40m4jYMCmduOD4+fSpllpwjm3STGAEc92u4jw4+QFTeOhHPpTWKpyWA+eKo+WO0ttr7n4fken/AFiHjs9NkhVbi4kZwfx7SCRz8eefsKubW80y0b9wUjU9cA56cZ49KzTXMfQFnPkBmnQGW4ulgVO7Zxld4Jz7uQPnjilWDTYLdx45ky6hrE8taxzx/eZqm1u1/wCW6sfVsUM6nJLnuynyOaz6aTqc90kOwjcU3FeNu4Z+351Z22kDTTJIZTIzttB6YXwz61tVeLiyxaz3h06RsFmTgesO8sznLO31oezzohPFNJFbOCYeQBgRNoFNEskTHYB8wDTt1N3UYWRMQeDCf6pdqPxD/wCIpRrN2v8AIf8AtoJamlqkAlcqnpHvf3k/BmbHkOKAUZjljn4nNPLU0tRgRuB0nCMeOTTs44FD3V26nxBJg7y9jsrfvpt2wED3RnrUEdobBv8Amsvxjaiaune6RcL5LuHy5rGVieJai6lwqHAIk1VauMmbKPWdPkYKbwLnzR/yAqU15aRN/wAQzLjP7oY+5FYy1JDwtbs4vBMNg42keHXxzV9E9s2jWzDvfbMkSgj3RjIwPXgfesOxncZdifnJm0yqu7P9/n4S0vJZ7RYS0aATIJFO7dwfPFdUG5tRBKixTpc70DExDO0nwNdUJGDIrK1ViMY+8fpzlJipGAwqbdxd/ZSx+JU4+I5FUSRPBq4iWTaVkwC3Ix/4qwvbqWK5S3ZUKOAd3Of8zWw9q3uMjGeJZXKLiRNJuxBLIpGQ4zj1FXdu25C+fxnOPKsuP/b3/oG+x/8ANXVvctE4DE935YqcaTFeF6iCLfa56RNciysMw8CUP5j9aNDfex6jpmqclR7smPEdD+Zp10Vu9MmKg+77wz5iqgTtJprw8YjbcPP/ADrVMUM7lRLHmBMMJ7HGwIBByDyDU62uHjIAJ25yQPhisd2P14anpwt5mjW5twECg8ugA97FTO1rSL2bneGdoXQhsq+0sM4I9eD9qq3V4JVp0AvXyvNXnibMahZWSJcXd7DEqnaoeYKSfman3EiR2sko90MzDauGOAK+aXR5VkdSe9dcbj5/GvQrf9rvcrDZTWE8mAqzuCp5A5IGOefPFZlmnOQRKVHiSWE7/ZmR7V3dhqnaa6vdP70QTSB1MibSWI548s80fsFbQan2obT7+2W5Z4mMSE4AdefmMZ+n1qLgLLNIYQVjL7k3eAzx9qLY6zcaBcG/tYLdrlJPd76MsOhGPDgirijbg+k58vvclu8TUtNfTtWvLeSFlFvOVD44I6rz48EV0+n3dvEJxC21mwwB558eK13azUrXWbNr62EjpNBHIxKhQr8E48wCcfKs7bTtJCmWJwMdauUacXNjOI7oFXdE0bSrzV732W1QBwNx7w7QADyaiHSDLNLZTy909u38A3EjkcVf9m72Gx7VWc05URNJtk3MVAVgRkkcgZxUrXIID20mn0x1nt5EO8rwAfTOMjPj61JZTWpKZgVYJBk/sxrJ0cXaT28s8VxEF27lXcw6Zz4cnoKy1zqs1vdyxpAibWIG4luPDyqzWci8a3YAEIHBDZzzjFU+txbL8SAcSKD8xx/SoD0yJoKATmLe3ss+n28m4qzMyvtOAcY8KgrNPZCeW0k2STFQcgEcGm20M81wY4ot4AzkGr1Oy17Nos188sUcaHbtzls/l61HgsciU7N+/IE0nYO4GoahfyCJO7tIh3iSSbd27kEY8Bjxq/s+z8OmST3C3Dia4UL3TKu0r5HOem7n08KyHYy1Sx7RhxcFWuIzExJwOORj1z05rb59/vrhmKljEgXnPPugAjkHr0564xzUwz3M6DQ1qVDke1CXAtY3YEb5VYSNLksceXX0+AHUip66ik8C3KKCZ0VkwcdRn48efy86ymo3kFrpjqpSee5jZTxuxnPOfH9cdRU/Qr+ZNAtxPE4FmDGA6kcY4OcdcePpnmieskZ6zSK5lzPLcEd3tIaYFunOABnp/g4GKFKrPKR3uY0G8srYGc+GfIdPLjpUYy3Sw96MB2fu1AIOMkYAHp4D544BrHa3qt/a3NxZGbudh/dsigFl/ECW5497Ofv4UyVk8CEqzYSy2sZkuXKlF2tGkg9Mj4Z6j681Sa3rOmrp0scUkZuJXEkRibIUqRjGOniOcHrkDisbdXYlxFcXDSBfe3SOeOeQMnjp/ahQ6jEJo5MM0SsHQKM7sdf89evSpxUB3hgCa5FwRLNCFmdd2SBuO7nJwPHyrMa0FXWbhVC8hXOMDkqPGrODV/arieSTZGpXvQQ3ugZ6DwHXNVk8CXOoz3U84jjdiFCR7mI8PID61zwravWNxx/PMz9aFFeOhkQMAMNswD0HiaerAfjC+gLfpVnp9pp881yi/igClmuJcBtwOOFxxwQeaDZaz7Lf3q3FvBbQ2EYd1tI9zNuOFYOce75kkdfjU76hVJHcTPShnAbtBCGUWxmeIxQr1eQbV/8AkartRRL3Te8hIBjOVOTkjPSomsXxvtTnkZpO5kQTW4lbcQpx7owSB7yP4nrUrS332bQtjAJ/FwMGnRvNUmRX04G31hdP0dWRZJf3xkwwQdD48+dG1eAy6bvXGLcggeQPHH2qVo8+yy7t0ZvZpMZVuDnoT96l3VtDJFJGoLiZSFxzg5rI81kty3Y/37Tz0al6dUGf/E/9/aeWaimy+mTj3jkD480BdKv3gecWcwiQbi7LtAHzr1WDSY7BIz7OA7Ae+QCzfP8ATwrPa5qYu5e4jbdBHwWz+M9Pp5f3rdWwPyvSd6li2AMhyPWYmxuzbTq2SFBrdaL+0uDRtFlgNlNd3bSsys7gLtPTJ69c8V5+1vIsrIEPukirvs12fXVNQK3m9LdE3EqcEnPAz9aC6lLB7fSOHGmY3jg4xJ+o9rNX7XRvb3rolkjC",
            "QW8I2pu5AJPUkAnrUW00gT3EaJCSGYA7Qcda2FjoWnWAxb2qjJzliWJ+tWTWjXQa3DGEv4qPw+PTio961rtQYlMeIG65RjOSIyLS7aORXVGypyOTR9RJGnSdfeKj70JOzE3hqcv/AMT/APdUbV9CurOyRhqLy7327SCOikjxPjisQ6Qk+9O0GvAGNn3lbJpF4+6WJY5El97Afaw/SpOjWl3bXjLNC0aMvV1y5I6ZYcEcn7VdW6EW8QbGdgzjzxR8EkdTWkowoE4GzxO0u3xJiKBu97JHTjwrM9sLd5rMTKxK20gG3wIYfi+uBWp427SvI6GqvXIWk0+YIoYyxMmD0Jxkfr9qsad/LtVvjKejYLcMzzZ13qVPiKl2WqdxZJCIHllUnOOnWojMFUuT7oGahS3c0oKl2CfyrwP711b2eWcjrOiK5lpdazdg7cJDnwXlh/SoKszqGJOfM1N7OdnptcvgvMdrHgyyDy8h6n+9X3bXS4rI2U1vEI4dncbVHAxyPsT9Kpfit1orY9ZXa5FsFQ6x9pKLi1jmH8a5Px8fvUlRVPoM262khJ5jbcPgf7j71atPHGcM4B8q6FH3IGMsiSIpDBKky9UO6tGJVZQSqsDyD0rKw3aSyBACM9CavtMnY2uwHmM7fl4f56VgeNUhlW5e3BmX4jXkCwSQ2M5UYHqc1EWB433JKQQ2R/nnU0sjfiTB814+1MaNT+CQH0PBrApuspOUOJisqsMMJFLtFbOsrd4mCRnqPHNFa7IlGw8DqeoNRru09oKYkZNpwceI8RVOdSe1vWtyhuY047xByP0NbWlvW0f8hy0p3aTdyo6dp7T2BtbXW7i1srtN8Co7e62MkE4+XNa3s3BbWN/r1n7kaJkAsR7oyw6mvGtA7ZJ2bt1uVuHguFLhVMZLYPoRimN2s1XWZZryCxma23bprl/eJJPkOAT61Lei8q9gCkAfnn95X0SPSA1VJLKzEnpkEY69ZE7ToBrb45yq/lVM7rFyziP4nFTu1NldPEuqQXUk1o4CsOhQ9MHH61mDFL3PfGJ+7zjftOM/Gs7VjFzE95s+HJv064PQYPwx6y0fUkUYErv8s/nUVr859xAPUnNQd1XmgaPFqXfG5WZFUAoQNqtnPjjnwqnY61ruaalGl81widZWPcyP+JuPhirK1uv/AGkYC7iowSTUjTezN/HdQzzGCMRuGKsd5OD0wOKsNegT25HKgh0Hh5cf0qbTeJtp7NtB6iFqfDCdObbRjBHEq2mcjIYKPQf1oE5EkLAyHPmW/wAxUpbdD0yvwNKbLK/u5grf7hmrFmsvu/8Ao5P6fSZSJWnuiZxvaZmbYsm4Hgqp4w2PyNHEU0UuWUBVZmAZgOSOP1FWr6XeSMN1xvTPOMjj5VPtNK02GXdMsko37gHXoMEbTjOevX0qm9oQdM/KaVIFxwGUfMyphmf2fc0bSDeVUxrx0zj44pYXurveLWAvsGT4n5VpLOysrZWNuU2kj3S2SCARkZ55BqXFHHBH3cUaopCjgeA6DPpVJ9bjICzVq8IVsMW+eP2meGgahLCWaeNDke6TjqMnp5HipP8A6ehljjdx3TnaXVT0wuGAPqec1dU4lpHGSMnA8qqtqrT3mmnh1CdpEtdMtLWOALGHkiXHeMOcnrUzCHYHAKoPdAA4wOMUgwG94ZAPIB60lVyxY5Jlxa1QYUR2aHcputJGyPdwcZ5omWWMnHutxnHlTWG9CvmCKOl/KsV/Qgx3XepX1lTupC1CLY4pC1ergA8zjCxhN9IWoe6mlqPbALQm6kLUPfTS1PiBuhd1N3UMtSbqfEHMjnV7ISNGbhFZSQQ3GDT1vYJPw3MJ/wClway+rx93qs4HRju+oqFXLanXagOybsYPaWxQrAEGbeSBLhSCzsp8Ffj7VH/0Sz6shA9SB+lZEcdOPhxV/Yyd5YQliSQCuTz0NZjs7nLHMFqmToZLOm6TGc7SxB/gY/pinxm0t12w22eScyMWoGa7NRxsHGCZIN5MRgMEHkgxXVF72MOFaWNCeRuYCupRto9IS6geS+tyhCu4xk9Ny/4KTWJGNwiMgBQZDA9c/wBxTRePcMMoFKMHBB8cf2ouqstxDDOoII91h5ZGR+taDrtUN6/tL6qWBI7SBeqGdJR0dalxPvhRvMc1X3FyqwpEVJYcg+GKfbX0UdtiQkHJ4AzWzVerHOeolVkIEtorh4kZRjDdc1XW/uXTxN0bKmpSnIHOc0PuUafvu86HoPOp2QA7x1g7jjBkjsxdf6f2otHY4VnMT/BuPzxWn7ZaFfahqdvNZ27zbo9r4PCkHjr6H7ViIywvwzgN1cY4z5Efatx2M1+F9KuFvrxVaObKtNJyQ3Qc9eaxtSEflT0mlpWS1Tp36HmQLTsJqckTvPNBBsUttzvJ+lY+Ze6lkuJAysCF46dMV6va9o3te1z2l9DENPCsBMsZlIOODx0PUfPyrBa+kCdoLprdHEEr96gcAEA8njJ8c1nsARxKd9So5FXIEh6ZHFd6jaQSSFYppVjZk5KBuM/XH3rQa92etNOneEJJLFgOjSHqPl1rN6HbgapOqyOkkfvJtOM8/wDivTu3XdahpGm6mHXvpACQXBdwRzwPAHA6VNpsFhukYQKpaYPMTwm1V1A2FQgPQfCodrDPa2cjSIPd94DdzTDA2n35leN1DMSnGNy+NWizIoV2ZQOGGa2ltQg7eokJBHXoY/RpZJLeQmNoieQSKsEgheUSsm+TH4m5NQzq1oJAokZzvCEgHAJOPH4ipXfxRkq0igg9M1lEm1s4zLwChcE5xKcqLLtGwACo7eHkw/rVhf2S3sabpO77sk7seFVuvToZoZ4wSQCuenIORVqZ4ZLMPJIqJKn8Rx1FC1bJ7wwJIHDDgykvFjspY/YrtmLAqxU+PxFarsFqFmthrFlqsipHKo2PJliTjBVeuT+E/rWJHLkNgjII+NaDStC7RtbSvpunFkmcMHuP3aLxgkFiM9KrA8ysjtYdoH7x094NMeORldjk7MDrj40a27TT6lqkYuUAiGW2IcbjnJ+vNXI7Gz6lZ20N/fwRXKLvcQKZfDnngc8eNHseymiafdIZBcSuAWWS4k2ISOMbVx05z73gamXdnia9FF4HBI+0n2t7Al7MpUE3yF0L8kBQOhJwTz0zgHAzwRUq71KODEbZhtyuPeG4ORjkDgkYHh1OM5FCiuFs5Y5Et4kiePEfs6KJPDgE+8c54wTyeehrKW8N3qN8Ut45ruYk+8Msfmf1NWlQMcmbgHrNbHczXge8LoiPHuQEkbgCePX16+A48Mh2wsbsyW04Z+/uIiSd/vbh/wCRWrt9GvUggS+v7eFLVS3dp+9cDpnC+QOB5eFZ/ULiwuZd1uk0zK2RJK2wAf7UHh8SfWgAzkL/AH84Fi71KrPLmcs25ySf93Jq507UtOt7RY54Lu5myfdjcIuPj1P2qNqOlzrqtwkFuzRb8q3QYPPU/H7UW10O6WRWk2KHyAPxHHj0qildysdolBFtVjtEsrbVrd7oQwae9tbybkLSzbioIPAAAHXxOfjR4Ze8CSLuJIBwOfDzqhkDRs8RyrKSp+I4rVaJ2c1i/wBPjkS1aKNuQ0z92CPh1+1QlsOS5/okF+5wCeog7FLm61yO0s2hhuL63e3hkmcKEkHvocnp/FzUvUpJNE/aNZSvdW88s0Jgk9lxLGXTDqviGHh8qsG7BXuIpzdW0s8UqSLGynYQDggk/wC0nwqwn7Fm/e0e8vu69lmE0YtUAw2MYy2ePlWfdg2ZXvJKH2phpQftPvIb3W9N1u2iEfttp7PcCQAFHjOfwg8ZAbHTINUmk2ot4TNczrApHug8sfXHgK9Eu+x2m3iRpMZmCNv3M29s+BGeB8hToeyei2A3C2idmbaGnJYk+XPj8qkqs2DmV7ssQtY4+M80XU2tr5pkgE1qx2vhznjoc+vwqxudcaB0uktVSNGz7uSzKT7wJ+Hp1xV92n7F3er34l02SK3RAqzAKVVivgBwDx8hiqmP9m2tX9yYtT1SK2skYKEt8uXUf/ED5k1C6q7biJy9/hL23biPmfUfKQB21Gr6rBp0NpHDBcyNAWA7yXaRjjoFP9DzVxpH7O7a7uo4p7qRwT77LhEUeJJ58PhWj0bsLoWiSd9FZ+03IJPfTks4+Q4Fau4sWsdKe5ZsqihhFEpO70AHj8KibUJpsJnGZ0Gh0K6any6xwJ5l2n03RrPURb6bpsFuiYJbbljxxknJyR7x+IqJZ92scrnPBworr9Lz/UZzeQPHcsxZkfjaTzzSJGY4kB8uvn51oDlRzmYOpsLucw6z4P4ae818zK+nxiWUEl9+OB8yKAuAwJGQD0qTb6nbWDuZlk/edNq5HHzpm6cQtCqnULk4jkue03/6KI/Jf/uqPqNzq8z28V/bpAgLSAqMZ4APifMVZx9p9LHVpx//AMSf1qFqWsWmo39rHA7MhUp7ylTuznGPh+VQc+k6ougUkNJFuzyKRtzt8qOOOlcg7v8ABx8KXFSTgjzFJLHJ60yaET27p0fGUPqOR96fiiFP3e4HI8fSlECQcieczdkb+/v5e5j7m13llMnu9fvx06Vdab2BsYiDdztK48FG1fr1rU4rqsPqbH6mXLNdc/Q4greyhsIRBBEIkHOB4+vrVZ2psvbeztyqjLxDvV/7ev2zV0Hyu1xuHh5ihsoZSrDKkYIPiKhRijBx2lVXKsH7zynR5u61KMZ92XKH59PvitBJb9/IG3beMHis1f2r6fqc9t0aCQqp+B4P0xWohmE8STL0kUP9f75rudMwdcdus6pCGGRCQ2sUbBsEsOQSasrCXu7oA9JBt+fh+v1qCp4ooJ8DhhyD61JfSLqmr9Y1tfmIU9ZfY5649cZpGiJ5Uhx6cH6U2KQTRJIOjjPwp3TpXCEFTgzlsEcGCI5x0PlUH2Rbe77+IBEIPeDJ5yevyq0LkjDAN8aCy1LVa1bbljMNwKmJqktlNp1tDeMntCttQMNxZehI/wA8K0mh3GjJZCxEUaPIoEMhnMRCn+YHhhkeVZJ7JZLmLHeNJI6pkAuQvoB5DwqwnudLh7Pz6atmHve8IE+wgnawwxB5XIzxUXilo1LKFUnJHTjAz1JyOB6d/SbHglAopbJ9ev6Ca64sIH0OSS90+CdC5YyyHLtt9PEf1FYWa3iuez72NtMssg95FJCkHduxg9B1FTbO4igsv3KXEUZtWEhafesjMQoIUHC4971+lQWt0kGFZX9GHNUqaCc845z/AHk/Yj5CaV+uXTHaVzuGD/cfqO0SLQFl0JLSZIoLrdlpVXceG8/hxWjtLW2tNCgT2tpbiNQgVsDgHHT4VnBJc234ZJEHgD7y/epEeqyLjvIlceaHH2P9aa2i457iWtN4jozjqpGB88fL/UuQ1V+sqDDE5UHaxH1/8UsWp2zkAyd2T4ONv36fema0ZDoly8DYdE3qwAPTn8s1WqDVWruGOZp6kJqtM4rIORK0AYyAR8TQZL60gP7y4jB8gcn7Vl7l7rdi6abJG4CTI488VZWlgl52ble3t995HMB7vJYccff7VuswQAnpOOq0DWErnkDOJNk7Q2yf8KOSQ+Z90f1qHL2gu5DiJI4/kWP3/pXX2hi3urGCKRg10MEyfwt4jj41a9nuzlyurTSMkp9kO5HUAK45yeaA31hdwlpPDDv2kd+T95Q99fajcLbmWSSRztVC2Bn4dK3kBb2WHvBiQIA/OefGgRaZYpdtdC3Qzs2/eeSD5jyqT0ZvjWZqNQLsYHSdDpNF+FByeseWJxk5wMD0rhjncSOOMedNYFGIOMjyNKcMWK+6o5wTz/eq8uxaUDOeRwM8mmhtucY5GORSU0eOzTuNoIJ3eI8KaWJAB6KMClYFTg4yPI5pRplb+RrbUbhO+KgOTjd4Hnp86jjUWOQhaUgE4C+A6mtJPpkM+ozXGG2vGEdCeCOMfPIp9vY21tM8scKiR85byyc/56VuV+K2VKAGPT1mA3hTPYW3YBMoopJHhV5E2FhkD08DTi1TNYGLiN/5lx9Krt1dx4fedTpktPU/9TJ1NXk2mv0hN1JuoZapFobJ5AkxnZv5Y1z/AHq/0lYwW6nRxyzHEcbOf9ozWktbCyCBktGH/wC6pz96nABRgDA8hxURs9IBeeYdp7Ge0u4JJoynepxnxwf71UpENrGRjFlC8eV4cg4x+f0r0ntPoEmu29uIZY4pIWJy4OCCOnHwrCalp0+j3YtL/EmIiY+7bhc5wfDxHIrmNfSwtazHBmlpbFZQp6ytq20l827pn8LZ+o/tVTU/SWxcOn8y5+h/vWWZI49mW1FntZ7aOF5UKLMu9DkHI/wikS3mk/DG3zGKP7JMhR5rgR7MbdzZ246YzQ8d5W3IAcyi168e/wBTed4khIAQIi7QAB5V1Wl/a219cyXFzdzTSyHJKoFFdREgmWWtUsTkn8pAtm2XGD48YqwbfLatHGqurjrnkEdK0Paq90HULQ39uwjvxt75YgCGY9WPORjwrD6tdJb6fbixV47qbc824hlIP4SB4EdD4cZ8ajs8UfVVhdhQgnIP/X/c6PRaGmmtrbPbDcccEffB5xNxrPZ/S/8A+39prFrbxxzhR3haR/fxwcAjk58eBxxmsVYWwkbvt2NjfhxQk1C5bRltJpHdSwcgtwCBjp96l2kM1rG7soKkAgA5NavhK6hahXqG3YJIPcA9AT8O0wNYavMLU8AywWojHu3nQcBiGH61JVxv2Z97GcelAvY8xiUZBQ5OPEVs6kMazs6yjBsrHa64DIDgefHSoF0WgKhCyGRcsCOnNa7sppdrqevR2l3HJMkkbFFjJBLAZxxz0zXalo1rp2pTrPE7bHIVSc7QeQK51tO+dneOgPWPsNTbULT2l8GYfj28AnA59OtDvrZLxllncQhF25HOefEmjWyK0eYYY4lbrzy3qcVA1ZpLULDHMHWQEsDjIIP2p2BXhpc3kjJjUk0i0nDIrSSuCN4ycjyzwKt9OuY7q1E0cfd+8VI4yCDj/PjWVMbSMpBA2tnny8aVNWubCyf2cqhkkydy5wcYOPpUauFOSJBXYM9Je9oId9lHMBzG+D8D/cCqVn3RQjI90EevWrS31GK+7Nf+6nXv2VlOepYHg4HyqmgjmecqMFccZ4qTcW4TnMku9pOIk8fuyE/gkwD5gnjP5U+G6mW/htfdMe0Akjk4HX7Vf2PZdtS0K8uzdqphBXulXJJxkfL19KqRawq6zbSzr0YnpVnTU2h8g4xjMp/OOv4+9sWxyVIYCiJbzzaJHEYnEsbEAEc7T/5+1GtmAmQnGM45re6b2Ugkx7Zcbtyb1CHap+fU/LyNTa1tx29pb0tDXA7fzidl9O06x0ey1A2kKSsmJixDMH6Hk8dfDI6jzqz/ANQie6VodzXKkoyEbiBnpk+XmDnAPpRbfT7Owl3Jb90hXKmMkkHA8yeengeopUliiw0LRyrIm3u8HaPp0HBzkeHqKqACdRUgRQoEgI93dOAbfunjyTLnDEZ5IA94Z58+SOOMUy0sbiQtFPdBxEmQoG0Y8AW/iGBzkYwDU+BpJyDbRyxuVw7H3cjHXngny6dfSiGxuWiU3zQ26Ivo2R4j0HToR09TRlscSfOJXW2mWtmH7yOQJsyGbL/UdAefLqeoqQrpbRFUMUkMie7GDtQHGfDjHB5ODx61T3eu2sX7uyeS+ZBhZp8qqnkZUD3vE9SBzTtNkX2KK6MqB1JEkfPvAc8gZPhnGCDgeeKMqSMtCx6ywjvIt+bR2WR1AYHGSccEDxPwP8XTisXNG0N1JCwKMjEDIwcVrY5RcXBW3tZfaY3bb3f4B6bgCoB8cjqSenFVT6d7fq13Nc3X7hHAdowGcnyAzgDHqcUSEAmEOspRYXl9d91ZW0twSu91QZwQep8KS/0i901W9smgiun94W6Sb5FHqBkL8z9a1msXraBbwWelwx2izLueYNumbHr8+vxxisjcgygMMsxPLHrz/eiVi3PQRgSeZfaP2d0yxumvJ4TeXTHd7/CIfQeJ9TWpjmeU5cpCpXPJ97Hn8PWqq1FvFbRLPGbiVANxJKqSPTx+dSWu7ybeLGOBZAQX3LkYHOCfPHSuWdi7ZMxWbqTAT67JF3a9w8LpKhkDrkdyzFQ+Tg88eFQ2u577UJrMMxT24bRIOQqjLYPH4SAcetUN9ObGM6gSZNMu4BbiISbnt2JyePQjI86i2faCS2HtFyJb2D24OLoqBvUgqwK+e0gkVYWrjiZbXHIyeP7/AHpPR7aZbuJbpe+RXBVVf3fHG7A658/LpTwsNvEMbI1jBIJ4CDxOax+m9tLSxtBYGCad7dAEcEKHBzjryMdPlUe+7R3Os27RvElvaAglVJLSnwBPlUPlNmSPrq0TJOTLy47X6egIgimuDjg7dg+p5x8qr5O1l5O57uKC3XGSeXP34+1Z8ku5Y9TT1U+FSitRMh9fc3Q4+U0OiSX+sawntPf3dmjbrhA5CqnPJAxWzeHZGl5bEmFXCxxgHKgdB9q8+stWutJMgsZQhdNjNtBz41dat27ttKNpPZWUkhCYKs2xM458z41ia/Tai24eWMjt/v0z6zc8J11FdZ81va+P6DvJ3bq00lUkk7tl1O5KyK+CQVHBHkOKw0qNhRsO1RjNWV12ju+0scVxdiJdhYKka4C8+fU+FAHFX9DQ+npFdhyfnn6TC8R1CXagtWMD5Y+sgU2WJZoyjfI+VWaRxs4LgD1pDbRbieTz51dzKIswciU3+mr/APmH6VJ0/RQ91HMZlxE27bt5P3qzWKMHhVoqll5QgGmMlbVWkYzFAPj1oioDGTn3h4Goshm5LE8+IoaOVfJ586bErYkgzRj+LPwFPSUHhXqGxyxPTNJS2x9sn05AhyGyM9DmoAlcDAY0hZm6kmltjbZOZChwfkfOm1FE7qu0Hj1py3R/jXPqKWItpmJ7c2Xc6rFdqMLcJg/9S8fkRQtDm7ywaMnmF8f9rcj75rUdpLOHUtLXcT+5kD+uOh/OqXs3o8c/ak20khhtrobF2YzuxuUc9ORj51v6LXJWgFh6Cb+hfdUAe3EOp4o8EUtw4SGJ5WPgilj9q3Nn2Z0m2wfZe/YfxTMX+3T7VcxKkaBI1VF/lUYH0FPb48g/+SZ+fH8zRAzMNb2N5YwhLuB4d5LIGxkjx48OfzohHFajXLcT6fv6GE78+nQ/1+VZt4mQZPI8xWE1xvY2EYJnO66ry7cjoeY3KlcMi/EDBpVtZJT+5VpPQDJotg7RX9vMsZcJIrEBcgjNer+2WkQ4miQeAUj9Kytf4g2kICpuz8f9S14d4Z+NBYtjHwnj00EsEqrKksEq++ucow9RTk1C/i1Nb8XHfTAkkSjhuMc7ceFbXtRpM2v6jDLpu2YpHtky20DnjrWQ1LTbjSrw2tyFEgUN7pyMH1qXT30axALANxByO+O4+Rivp1Ph7nYSFBGD2kF5priaaWWKKNpSGbu1Chjz5fGhkUY0xhWkihBhekz7bmube/WNEjp0bj1och3nO1V+Ao/dKw4k58iKY8LoOV48xzRyIEZkcrSBdqsqFkDAghTjIomK4inwCMGSq7IcqcGNypkSSS3hmkRdod197Hx/tRLH2SzeZlSSMzPvbPvKD6Y6daZXBSegJqB9OjDHSaNPit9RBOGx6j9+stI5Y5eY2V/+k5oquVztYrng4OKoZZreJsySxq3x5+3NCOuCLiN5JR5OOPqeaptomHumblHjaPxYhHy5E0gNIT+8+IqLY3ftljFOVClxkgc4OcH8qm3CeyzIFmSQ4/EhyBnwqltIODN3cGAI7xowT1A48a6m5pc8UoMfkbeh3Z+1duO3bk4HOKaWJxnHAx0pSCuPUZ60o0cuNw3EgeJFdnFIBuOF445ya5WKsGGMjzGaUaNf8YPmCK7I2jrmkk6A+TVxYnGfAYpR5A1nLWkbHnY2B6A1SFq0OpJusJlBDYG7j05rN5r0D/xy3fpSh/xP6/0zmPFUxcG9RHrcGEhxGj48GGR9KsrftPJCu020QH+wbfyqoJyMU0oDXRkA9ZkFQZpou1Nq3EkTp8OamJrunuhYT4x4EYrFFD503YaHyxB8oTWz9p7SPIjV5D6dKx3anUDqd1BOYhHtUp8ec0XYajX9q9xAFjALqd2CccYOaqa6kNp2AHMnoTDjHWUtKrFTlSVI8QcGrKz0ptSgkls0ZlgjDTFjjacZJHmODSHR32lu8wBxnaSPrXG4l9vZ6wml31yzvE9xKylcgFycc/3qeTzk9ar7awltrlZNyMuCDjg9KnAMzBeNx6Acmrn/AK/UZ5XHzxiVjgn2Yua6jnR724t5AqNFlT77ttx611VbUSo4Lg/I5lmvS2OM4xM9FeyLctLNuZH4ZVOM+I+4FBb95O0xHvMc/DyFIT7uD1o0UDrtMiFVJ/i4yKutp1tfcwyZKmqsqr8tTgRvMlz3aYw7cA8cmr+3Dpbxo+CyjBwaHa90VbuYtqr/ABbcZ+dHRSxwASfQVs0VLSNxPWUHbPE4orSK5zlQQOajXQLZjJwPD6cVKkyjMhBDgZwRUNnLvliDiquvuAQBD19IByIXTdTudE1ayvoVVnhBJJJ2+HXGOP61ef6+3aq4k9pCC7XJYxJtRkz7uPUZIPyqkm07UpYE7q0cQy5Uu6FR8iabBpFzpcq3xkTbG49xck4J8TWIpcMCJNWrMMTSRRC0QRly2eRxzVLrkRS+WUIcSL5eI4/LFX85ARZAeAevoardTC31uqwHvJY2ztXrg8H9KmcZHMnKrgKTgRuj9npdSvIYHuYrcS9GPveGfSpl52as7e7e3nZ7hY2BG73QflwaJo0j6fJaSzquYXBKnByAeh8OlaDtTe6deX8cthL37BcOyIVTzGM/P+9ACvTrIAagcpzj05mP1eyhhs4fZ4kiVCRhBjr/AOKDpdmrESOr55xlfd/vVzJB7RFukUGNOSSTgfE08hWUlpCc+INIk5yvETNdYMKuB8f9S37N6xpujPdJfzJBDLH7paPcxYeC45GQft9M5JZWpuZe5VzCXOzcduBnih2uiJu3Mr3kh6ls7foOvzPyq2SzleQIxAb+VePstOj2DOD19P7mI6dmAXGflwP3MFY9n5r2VO6SGKM+6JXBOT5DqSf84rawtdwQQWk4End4jDIvDeWRzwQPmB18arrBTplq1tcQOGz3ob+Tw8DkdPv6CpyPfFu+SFp4QVlYgcnpkDjPOOhGcYqZU/yP3M6LQ6NKE3AcnrzDw2j7BI0zSQo3Axjb6jPQjPUdM9aLD3cKyd1GltJGcbDyx+vPPApXWYqxiAgBO4B22ksD128jGOTjxI8Kh6lJJBo095IO+EYwEjGFHIG47vAZ6jzNLlpe5PWTJbiSMTLcO78ZTYcY6+AwfDrjGB60BZo4HeW2kWQuvvErkL1OSeqj0IxkgVCs980NtPJukhlTJHBIyMceIU48DnArl9mR1nhfDxOf3bKWbAz1AwxPGePQdaLaBxDAEz1lokl3clZpltod5VWKlmfn+FR+ZIFaSwSwsdSNhb6eTPFH3wuLggljnqPAD1oE2owd7IskeyZJMqyNwv8A3DkHwPHUk9RVdcz3UGuWt8ryBZfc7wcg58sDkZzjgEgDipTus4PELBMtu1V3O3Z9V72SJxIDJEF2BlyQQfMZx8SDmqrsxb6lLDPJZWyGCQYaSU7VGPI+Pr4dM9KsJ0j9mnjv/dAIIO33ZMcDJ6HpjHHj1xTbq5aKOaOVkvIpY8Ihf3QME7QOmMDPBBwB54qMcLtURsYGBM9rs1rJdpHbXAnaMe86JhM+SnxA4GQADjxJNQFPAVZhAznAORkjxx+Wfj5VCLlUILDYo/ERnpxwB1NbXQuzjCLv7yIRK+GMJUFm8VDt1AHkCPHNNqblpUKTI7bAgwZK022e+hWRgyIwB3fmBU/UpJNN0SeexQB4F3kFc5AI3fPGeangKPdXoOBgYHyqv1u+tbDT5jPyZVZFi/ifIx8gPE+Fc4OW4Ex7WAUknE84u9Rg01r2SzlLx6jOk9q6EZiZCwDMp5GRkc+hrP8A+oCe4t50QIw/dyKo2jdjOcepBol/Fe314WG+ciNEVyMAbOFH0osWiOXEbuAzv3gCdQPU/X5VpgBZgWWqRyeI7TFeeIOWPd5JB8T6f1q2VaQRLCBGmNq8DHSnqPpQHmUXbccwix4jDk4yeB50opSWlcYXnoFHhSgYOKCQxGDmNhH+PB2/Gs5qE17vVbtmzjKqfAfKtIZFXjOT6VGu0iuInzEpk2FVY9R8KdTgyWttp5Ez+n6peIHt4m7rc+AenXjrWutUkjtY0mYNIq4Yg5yay+i2ftF+vfQO8IB97kAHw5rXCntxnAhakjOBHCl8KQU4CoZVh5AO6QgY4oYpd5KBeMCuFNGj9jCMPxtPrUKRWVzu61ZRe/CyfKo7oJEweD+VKIHEh11cw2sR1xSsjIAWAwfI0UkiU+NUbIZefA0PNTLdCIveHU5poxOJEZSjYP186aanzQpKmANrefhUOSF06jI8xTxA5gZEEkTI3RgQazqvLbTpKh2zQuGB8mU/1FaOqu7sJ5bt2hkjiD87yclfPA8/Wnl/SXCskMeJuLrtNo1solm1CCPvAHESkvIuRnaVXJBHTnHSqO9/aNaodlhYTTt4POwjH/xXJP1FZ+Hs/bRHMrvKfL8I+1T4baC34hhVD/tHNRCod5bfxAD3RJkGra7qqmS8nFpbN0ghTYXH+4nLY+fNSojIWCxqzn+UDJqLGJupOB/uq00a8FhrNrcs21Ecbj6dDSfKISgyQOkzTYdRaBacDP0my7J3QtNJaK6DWxWQsokUqWB54+dOOnTzO8kChoWJKtuAyM0k+q2eu3EdtZSmSdNze8Cox8TUyO7/ANKt47e4Qu/LAoRjGfWuKtawObAuHbnBnomjCLSqVNuA4zI2jS7L8L4SKR8+tJr/AGWXXL+O49q7jZHsI2biecjx9ak2WnAvFdrN7pO8Lt+2c0najU7nSdDe8te73xuobvBkbTxn64qMWv8AiVOnOGPH5xtalT1HzhlRyfynnuu6X/o2qvZiQyqFVg5GMgjyqsNJrPakandLcXdzE0irs/dL4fKqpu0EWCI7dnPmx2j7ZrvNOtgqUW+9jmefXVhrGNI9ntLQinK0ijjJH2rPza1dvwhSL/pXn6nNQpbiac/vZXk/6mJqwFMYadj1momvbFf+PKiP/tO4/aq+bWLVOIUll9WAQfqao66iAkg06jrLCTWLhv8AhpHH8tx+9RZbq4n/AOJM7DyJ4+lBqfp2j3OphmgMYVDhi7Yx8qZmVBubgSzVRubagyZB6UtGvrV7G9ltnYM0ZxkdDxn9ak6CsUmtQRzRLKjkqQwyM4OD9aTOAm8dOskWomwVng5xLTs7Nu054857uQ/QgH+tWxbC58ua7U2gsjG+FijIKgKvj8B8aq31qIZEcbOfXgVieW97F0XrOvRkorCO3QS5BXBznPhXBjtK54PhQYJBNaxSgj31Bx5UUkEAYxgfWoSCDgyQc8xxIJ90YHxzXLgkAkD1PhSJndlTgjnrXbsnJ6mlF8Iv3pwK4Oc58KaKXcSoXjA56U0eI5JhdQSARnHwpoJkb3VGT0AomOATjB9aAmNuDwRwKUb4xxCvG6NnDKRx8KyRJBIPUcVrAcVmL5O7vplxxuJH5113/jNuLLK/UA/T/uYvi6ZVXgM12abmuzXbzn47NJSZrqUWItAvF32Uo/25+nNGpCAQQfEYoHXepX1jjg5kHS7o7niUd3mMK2wkb8E8n6/arY3Uxshab/3AfvNuB1881nrHdDforAjOV5q43Dz+lcDtYttHWW7GOc56xyAttVQSTwAOpNQdWsLq5mt7e3t5XuzL3axKp3liOmOueKl7mVSyOY2ByGA5HPWre6utUe7e9i1FbsRxL3lzaqFm7osFY4bJDZOM8muk1+pAoFbjlhD0lZLhx2MxsSXc6iGSSUiJyjLIxwpHOCD06H6V1WNzaR6RrVzZx7hGFSQK0qyuoI5VioA3c8jwz1NdXItkHE3cY4MbaWOybe7kPG2No6fWrFYTOwAQORzz4VYroqb2Zpm3NjO0ccVNhsIEYFIizAeZJPyrsVbapVF+s5fljkyrW32OveSpu6hcZz8qlwpcG4kVosRADY2MZqeVOMx7VP8A05/UUsckUoOyVSwOCh4bPj96qvS1jbnaTKdpwJDbT1mmLyMB7u0YGTTmsobe3BjQblwC2Bk1PWM+VOkgYoMbNp67iBmmepEQ5k6jnMv4bRtX7BpMju8tqCu3GANvPXoSRjyP64/37q3eJ4gI5Bzk8461aJMYoDD7SwjJ3GOPIUnzxwM1GCAE4JIzxgeFZ/lWsOhjmvPfHynQxuyCJdu0Dbz5epNPTSypO+6gjQngB88fBRXCG3yxeR/eH4UUfmaVTEnSMsf9x/QUw09jdFjDTV9SM/Pn9YAy2cOoC0RJriXqSBsXpnjqT9qmrvXlYYYfVuT98n7UFHZGJU7SRgkcE1wqcaRv8jLSoBEvbSLUO69puZHWMk7UHX68D6UbMUcapBAse3jc3vsfmaYKXFTLpkHaSqiwhlkce87EeWeKk6c8K3eLhyiOhXPr8fD/AAVEUZp64V1Y8BSGzjPT08al2ADAlmttrCajT8XZWEqrrHGSmVG0jgc9R448OcnHjVm00Yiiidmj4Kn3sL8Mjgc9D4AetZizuoYpwtnKTE0o5UZYE8YGMHx4yMcHnpm1cKsE3tQIZWBV0HusegJYcHpjnwU4qk6czQIyZJlvFtoUUgPEr8MV27s5Ix1B8z8gahXTe32txbWynDKf3YJznpyDx5Hg5yenFKWSzEvdGKdJVO3dnaDgnGeRjAzzg9B1qsn7SaTaMH9uW2fZ7+5xuI88ch2GPTJPpSAA9oQsAcyJpBuZ9MdVHeCJiGicBlOeSMHqeOnkKsLiG0mmkAuWil2CRmlyHB65wcHpyevUYzWJt+0sj6i8GmvxcPtLyZVT48jx+daJ4y1ukt2QyJ7u4KUjBJzx6kknr41Y4ckqeIJvUdDJ11qNrC6JewKkfdlDsJ5YEZBBweOgPPJJ5FU+ramJrWAW908wiOVAYNjGACTgHPgAeQAPOq/X5RZyLI7bYyoVQ3vDj+EePrVK2rRTuQqsrHnd8+uetL2KyNxji1AASZoZtfvpI5RO6I7DYGI5wRg89DkeeccnrzQbaK7lDW6d7J7RlBjIV+hwfDjg/Sp6x2trEyzbX3ocMi++3TAz0wcDkjw4xmoALEEZOMc88Co7rlq9kDmZ+s8Uq0y4XlvT+Zo9KstJ0JhPfX0M15j8K++I/gBnn1+lTJ+2NtGp7mCWXn3d2EB+XNY4fD5U4rlsdWPXyFYrJ5jbnOTOYt8VusORgS6ue2GozE90sNuD4qu4/U/0qnuLq5vZTJPK0shHLN1x/ShEAHrmkkcJD0OM4Pmx8BThVXoJRe+y33zmDd9hGAGY9FP+dKLjuFIJzM/LN4io6bg+8n3z4jw9BRBzyeTTyMiKBxT1FNFPApRiZIj/AHUJf+J+B8KYK5nZz72OOgFEt03SZ8F5oTAnNCEwGxk+ApVVQOABSu26QmuHlQxo9fKnCmj6U4U0aFhQOxBzgCk6EinQMAWJOOlNzyfjTGNHCnCmCnClFDQNh8edNnXBcbtoPOaapwQR1pWO8Hdz4U2I0gUdCJIip8OKf7PHuzg/DNSI2CuDgY+FPmHukWGAg7pByPCpOaLOvRx8DQaaCeZ1dXZpM0sRoOSFH6jB8xQhCV90AMD/AJzUimmnhAyO1iU95jhfIeFPWNU/CMetSY5f4W+tMli2cr+Hy8v7U8WSYI02lJzSGnjSZpWoHS9Tiuwm/ZnK5xkEYqV2g7eSCOGRdOQ4JXmU/Hy9KyWoau9neND3StgAgknnIqru9VlvIu6dVC5DDA6VA+gpusFti5I+JmvpNXqtOu2s4Xr2msj/AGo6vDAkUNpZIq+LB3OM/EVSav297QavZz2N3cQm3l910WFRkA+fXwqi3eFS10p7y1NxA2ZAfeRuM/A0Q8P0qHetYzLL664++5wZVk09DgZIyBzjzprqyOUdSrKcEHqKcuQgJBwfGrsi+Imzv9Ns/wDQZ2tbeNCYxIrAZPGD1+FZjTrB9Suu4jkRG2lst5Ctbo8gu9AgDcjYYm+WR+VRdK7Pf6fdpctcl3QEbVXA5GKyK7/KV1Y89p0N2k/EPXYq+yRz2/vWUOr6W2lSQoZO9Eik5xjBB6flUKKKWZgsUbux8FUmtV2qtmnsYJI0Z2jk24UZOCP6gUfszFcW+mNHPC8REhK7hgkED9anGqIoDnkyo2gDao1jhesyFvbvcXcduuFd22DdwAa2WhaPLpXfd7Msne491QcDGfH51E/9M51V7s3Xdp3veqqrkjnNbi50mGCxlmR5HcLuBJAH0HpUWov81cIeO8saTTDTtutHOeJnZtIsbi6NzNbrJIQASxOOPSpUUUUC7YokjHkqgUuaXNZxYkYJmyEVTkCVnaOPfo7PjJjYH9KyY2uiiNGL+OMnNbm+j76wnjxnKHA9etYU3kvThceFbPhzZQr6GZeuXDhpa2OpMlmsWFGzI3HJ+3zqRBeTyXMZZyYy2CAuB6VUWMoBdWZ8cMAKliTL7y7qFIIB5zVpqKznA5Mqi+wEZPAmgIwSAdwHiKVSOcgnjjHnTQxxlScMPrS493ORnOMVz83IoJGcEjPBpxIwMDBxzz1pmfdAwM56+NdkDG5lXPixwKWOwi46mPzQ3AV3AYHB49c0N76Ilm3mQjrtGfvQY7tbicqqsuFzz48/3qTyXC7iOJGLUJCg8yQGIBHgaotbjMd+GIxvQNV3LIsQAkKx48zjNUuszwzGExvuYZBwK2fAmZNYvHByPt/Mp+IBWoIzzKzNdmkrq9HnL4i5rs0ma7NKKLmupM12aUU4gHGQDjzrq7NDE6BsSh05PKjIqtqNRXpxufvHwccCJJLscqRkEeHFWXde29nNl2PZre3idY544wWctztI4JUnjIzzt6c1FSO2nYFHEjY6Zyfoan3Fzd3VmtpPP3qIMRd4oZo/+k9QPTpXLa3U16htwB/M8fT/AHJNPrFp3AjqJUaxHjULactaxvcQbfZLeIqYBtyN742s5PXknzxXVeTXLTWkMbwW0RiUAvGmwsfEk55rqzGXJyJZbxbB4XP5/wCo3TFvoy73OW7zB95uRV6DKlrBtOyKXcZCMgyYIATPXHicelR1hz1oyoFOR1rqaqDWoUnMrHnrFEagDAxTggz16V2a7NTbZIJ0pCwsPOonJNOkB3nJzXKtCRJQYgFKaeBjmmfiNNiSAzhTgK4CnAU22GGiDrTwK4LTxim2ww0QLTwtcCBXbhS2wt8eFp3FC312+lthb4UHb+H3cdCOMUPVtV1BbQvHKplY7C5TLBSMEA+uAOaTfQLvMlpKoPvFTj40xrB5IktV+xh6SkWe6ncia7f3zgpkjxzg+BHxBHSq/tFpsNrbWlxbgkSAq5Y556j9amISGJmZlAwwYDr/AE69OPtU66sxqmkm1Zwjq4YORnH+c1DfV5iFR1mhqzhMzHWN9cafcC4tmRJV/C5QOVPmMggH1xUibV9Rv7hGvb64umByveyFgp9AeB8qt5+y0EGnzuJpZZ1QlegGR6VmM+pGeeuKw7aXpI3zPVwek2HaHUbS80XYZAJ8q4U9c+I+5qj0vTJryQS47uFersOvoPM0fStBMxFxeDbH1EfRn+PkK0YAVVVVCqowqgcCmttNrbjMnV+IBPYq6+saBtABJOABknJwKVj4AYHlTj7nq35f3ph689ajJz1mCSScmcCR0pSQF2r8z50mKVl28E8+PpTRRvugFmYKo5JPhUdmMr7iNoHCr/KP6nxrmbvWDfwA+76+v9KXFPCAxzFAosUZkfHQdSfKhkbBlsKPNjgfeufUbO3h2LOJGP4u7Bb79KaLk9IVgoYhckeZ8aUVWvrCD/hQE+rtj7D+tR31W6cYVljH+xcffrSwYYqYy96DJ4HmeBSf6jaW8JBnVnbqF94j6VnGkeU5kZnPmxzXA+FLbD8j1MuV1QSSpHDExLMACxxVvb8B2+VZ7SI992XP4Y1z8zx/WtEvuWwz1I/OhPEgtAU4EHnJpRTRTgaCRRwpwpoNLmlFCIjOSFwMeJrujEU+2PvOPgabJxI3xpo3ecDTs00c9ATTWljj/HIinyzzSihBTgahtfwr+Hc5+GKjXWqvDCXRFz4DqTSxHCEy2LsRgsSKTNCilSaFJYzuSRQynzB5FPzSxBi5rjSZpM0opxNITXZpKePOJp6zYGGyfKh00sB1NKKc2NxIGAfCkJpC6/zCml1/mFPHkW6022vJhLMrFgNvDEVmdShW01CWFeFByoJ8CK15df5h9aaY4nbcURm8yoJo1YiT12lOswlySbdipII54plhPIA6l2xweanalH3Gp3EY4AckfA8/rQoYpLiVY41LuxwBnrU00QQVkiGK5mw0cckig84GRV3qEUuoafawyLHBKjD32OEwRg58j04+9do9nd2cci3EJRTgg5B/KrIgEEEZBqCwBiD3EhXWNUSoGVPX4x2j2Emm2RgkkWQ7yw2ggDOOKn5qujkktsCP34x/yyen/SfD4dPhUyGdJ1JQnK/iU8MvxFY19TqxZufjOw0GtovQJVwR2PX/AHDA0tMzS5qtNOPpzSO+N7s2OBk5oeaXNKNHClpua6lFH8Hg9K8+njSDUJ4pQTscgYPrW/FY3tIO51aVAgAkAkz4/wCcVpeHNiwr6iUNauUBkWGeLv0CxADkfGpjNu2lm3D+UHpVVCG3q4UlQwJOOBVsCCOqbVP8p5rbmOZaQX8QgRcMzheVAzj5mmSaoS21IgoA5Ynd+VVgCYbAZvXoB60qsikqXYr/ALeM1TGkqBJIzLB1VhGAcSXJd3EikrKwUfyrtoTF1GCFLHqfxNTS/eHLFwg8eWpFPUK4jXpk9TVhUVfdGJAzM3vHMKigqMjCY8Xxk0OaR1USIpVQdpIY8/OkATd+PIHpjPpSSncjFcR7RkZJpyMweQciDjLMSy78k8kc4HzodyMoAqsdh5PX8vjXEKW/4uc8k4NKWyMKSigZ95utS1ua3DjtBcZUiRKcY3C7ijY+FWQHVh41FvLSaaP91gtnOCeK0bPGrCPYUD7/AMTEF2TiRAyt0INLSRabfKfeMJXPI/wVYmxRo1Jyj497acjPzqXTeMDpcPzkrWqveV9dUmSxmT8OHHpwaj91KW2922fIityjUVaj/wCTZj+YuM5iUPHvMB8alLZyH8RVfvSTWEW5WbLnGOeKDX6A6qnZnGDmHTrUpfPWQniRycqCamaUm+4eJ5pdhQkAP4jH96rtRWeyIliO6E8ENztPx64NT9OfbfwMDwx2/Uf3rhtTRZpmKN1m5UaNUA23MvEtIEfdsDtjGTXUQHjHOfGurLLMe80FqrUYAEnZrs0PdTJJ44h+8kVPicV6ORjkzlQYfdXbqrJtdsIEZ3mOFOOFPJ9POpscqyxJIhyrgMPgajV0c4Ugw84hTgnNdxTN1duqTbC3QmabgU3dSbqbbC3wtdmhbqQtS2wt8Nurt1B30x5JA42qrLxkchvl/wCKBhtGcZjh8yS8V9HNETZsYJIxJ3oYEYIPl4jHI8OKTfUa47YadZXV9FELi8kaIQxJGm/2c9NvoT0I8/nUm7SK2fYJ1kYdVXkj04yMg+tYnh+ua2xqrupPs8dvjjgY46+sv6lEVQ1Z+c7fSb6AJMjNdvrf2TP8yG312+gb6TfRbIJslTLHIl0ysWKbiM45A/pj9an2Mo7sFWLKeMnrxTJrVJpi7sxB6r4GixokIOwYyckk5JqMVENmalviFb0bMHOJJMgwc9KqU07TLJu8aOKMg5BkbJ+9D1xpFtY5EkZQrYbDY61Bl0TUIrkRC3aZ2UNuj94c+tYvilwVxUcCY5FtqkJnHwlrJrNmhO12lP8AsX9TUSTXGP8AwoFX1c5/KrCy0+PWNL0yULEPZmZJlPG8A9OPHp9aHcaBGuoXalXjjbm22kYJPXI64FYK3oW2Hr/vH+47+G+Wnm/4+v5Z/wBfOQIbi+vpdiylV8SowAPlVtDCsEe1cnzY9SfM06G2itYRDCMgclj1Jp9TkzIdwx46TlYKCQPe8D5VW3uoQxTGB952gFgB1zyPlVm+2FPeIBPUngCs5rMkMl5G8UquShVtpzyDx+ZpCPUoZuYeXWl24htgvmztkn6VFbUbpx/xSg8kG2uTSb17BrwQ/uFXfuLDkegqID4U4ZW6dpfNHl4JXGfWWulaRLrLyMJ0XuyAxfLNzUOSCSO7e32lnRymAMkkGrTspcd1qzwk8TRkfMc/1q1k0S4/9R+3wyRpGJFkwSck497iqb3mu1lY8Y4mpXpBdQjVjnODMxNbzWxUTRPEWG4BhgkUOtR2stmkitp0VmKsYyAMnB5H5GnxaPDcdnQEtFiu3jHLAhtwPr0zj70hq12K7d4L+Ht5rVp2GfnKPSbYz6hButmng3gPhSRj41oNV7PG6mhNmsMCqpVhjA68HjrVt2T0G4S1lgmuIxtfeAoLYBHy8qNfsbFLgnlocgep6D74qpde5sDJ8hNHT6SoUlLevU/CZuzshaCSLeHzIVLgYBA4/rU+V1IABzUdU7qIBjwBjJoEmo2kXDTqT5L7x+1aQB7zin/5HLKJKFOBqqk1uIf8KFmPmxxUaTWLp/w7Ix/tXn6mn2mIUuZoACRwKHJd28Rw86A+QO4/as5LLPIA0zSMG6FicGmDiltko0+PeM0A1u3iLFI5JCRjnCj9aBLrE8jEokcefIbj96qc04NSxH8pRJr3c0p/eSsw8iePpSK4qMG5q90/Q0u7NLhrggODhVXoenWgssWoZaWaNM97bKxK4OKjTs804SNWbb4KM804syhhtJYeAGeatezsVzC0/fQvGkgDBmGMn/DQ22CtcyTR6U32BTkD1xInZu4ZYrnTZgVls34VuDsbkfTP3FXWazmtyHSO2VvqJ4guEVJfh+En5YU1ojwaSNvUN6yr4hp/w9xXtFpK6kNFKEWkNdXU8UfEkbDkEn1NClhCNnGVPQ/pTlYq2RR/dlTpkHwpRpCMSeX3pjQ56YFHdSjbTz5Hzppp4eZGNuf5hTgkqj3WX6UauxSizM/q2k3l3fd9EiEMoBJYDkUG10S/guY5cxKUYH8f9q0tcRhcngeZ6UYYjiTC9gu2GSUNweDTHg8U4P8AL4GoMupWcP4rhCfJPeP2qM3aWNBiOB5PIuQv9abEjFbnoJZeJHQjqD4U1owxDcqy9HHBHzqhuNevJz7vdxAdNi5P1NQZbiaY/vJXf/qbNPtz1k6UupDA4M1sGpx+0JbTSxtK5wrIep8iPA/ap+awMU3s08U4/wCU6v8AQ8/bNb74dKx9XSK2G3oZ23hmoe6siw5Ii5pc0zNLmqc1Y/NLmmZpc0oo/NZjtdD79vcDyKH8631tp1m+nrM5JZ0zl3wAfQceNZXWoEmskLqrbHB5GevFW9P/AMVqkyjfYHpfA939pikklZdkeSPIDNWyPJJEDI5QYAII8fLFSkYKu0qCvljGK54YpFyCOPA8EfOt7fOT/H5PK8SPlpCMLlc4GBjNK52jGyNcY4ByfrSPBIBhW3L5dDTMoAVCtu8cnGPlRDmXK7Us90x5csoJcYzwgzxTlcs2EjXnz5/OuUAgqrDaOp2cmkGwucKXzzjpTyWP2sXAJ3H/AG84pxRptxG98jqR1oWVVvxEgjnHFP3d4B7rhF8clqaKQ/eYKNwZj4Dwp5UuM+9jGMsRimuF75gqs2Gzz5dfCuzuXJVVUeA4Jp48mQZa2Q+mM04yRqA24HPHujPNdp0EM0ZYxhmDYAJyB/masUVR+FVUk5OOBVCy4KxGJWr8INntM2AZCVJ3ORAwBOMuQPt1od1HcxWrv3qIw/CFXP51Z5PXnNRL/I7vywfWoPPYy+nhOnTqM/OVunXV1du8ZSIsi7g2SuecdPnVgbJ5HYSS7U/lVcHNVelHutUCn+IMn6/pV/4UVlzA8Qq/DdP722VpZEJXaSV45oMrb8YUDHhS3zMl7Jz+L3vrUZnZhgk16fpLDdQlmeoH6Tk76vLtZfQmPkjjkiaOUAowwQTUGJfZ3VA4doccjrx0OKlAYqm1hNt6jjI3IOR6cf0rM8b0osoFncH7GX/DbTXYV7GbPcOSeF65rqy8ErzW0TOzOdozu5rq4oaL1M6I6zngSe2tXMozkID4LxVPLdzPqUiSSEg9B6dRSxyjketdsjM3elQXAwCatPa9nvnMxwoWMuV3Wz+nOa03Zy67/RIlJyYSYz8un2NZXUnaSGNixwrYx4c/+KnaDdT6fbzPIojhfBDScDP/AIq1or1os3N0IicZSbDdXbqx952knkysDn/qxgfIf1qR2ZvpJLu4ilkZ2kXeCxzyP/NbNXiFdtgrUHmQmtgu4zT7q7dQs+tdmtPbIt0Lu9aTdQtwrt1LEbfCbxXLLtbcArYPRuhqOJ23jYp4Od2cUrliPdIWmavcNp6GOLMciHNxPl9jrEHO5+6UJuPmcYyfjTAcEknJPJJ8aCpcfifd8qcWqOrS1UDFSgfIQnuZ/eOYTdXF8Dk4qFe3jWluJAm/nHXGKqZNYmc/gQfHJx+lVtVrU0x2kZMhawjpNAZ0/mz8KHLdxwx95IwRPNjis62o3Rx++25490AVAvJJHdJJGZmOR7zZNZT+K2n3QBIg1jHHAmotdWju7owxqcBchj4/Kpe6sdptyYL6F2P8Xn4dDWuDxbSQ0kpHXulJA+dWaPFEWrdeecyYbjwASfhCLIFdWYAhWBINaOS5kmCCRgQi7VwMcVm4ohKm5oTHnoHOTj18qtrdy0CZ6gYPyrm/G9QupZbFGMcfx+83PBLwxes9uf2P7Q13NFp1mkrNCgkzsTcB06kjwFUE+uWiszGcyu3Uouc+nlig9roATa3PA6xMT9R+tVtzoF/b3QhSJrjKht8anb8MmqulFaqGJ5MHxOu6+w1/4rjgfGTZNakMLSw2b92DjvHzjPy/rUF9ZvJDxMI//wBsY+/WrTRrd7nTNR0eYiOVGDAHnYf/ACB9al6loTyaVZ29usck0DYZsBN4xyT9BUp1KK+xvX7Y6ysnhea96Dt984ImVeRpG3SOWJ8XOasZdAuY9QgtpiAGwxljUsqg5HJrSWfZy0j1uCZWSG2C7WR17znnnn5Va3wQRyxRSb0Puq2MZ9cVBbrenlzQ0/hgGRb/AKx/v6x+haHYx6M9tdXLOqllO4hQQ3/k1Cg02ytDiG2iUj+ILk/U0WN+8jR/Fhmnis5rCZspSqHj8vhKoaEBrzaiLjb+87wRhfHHOT9auBT7e2lupNkKhiBk5IGBXTRPbztFJjcvBwcimdmcAt24irSuslU6nkxuaXNNpc1HJo9XdM7WZc8HBxmq7XzINCuHjcq8YEgI68EE/bNT6DchZUMLDKupVh6HiiRtrA+kjsQOhU9xiefPI8jZkdnPmxJrhSwQFrqO2kbYd/dMxGcHOKuNT0FdO07v1meRlcBsgAAH+9dA1qKQp7zjU01jKzAcL1lODVjokyxazblgCrNsORnrx+eKgQwTTnEMMkh/2qTRJop7GcLLG0cq4cA9fMU7YYFMwKw9ZFuOARNR2pi3WMM2OYn2n4Ef1Aqv0PSrfUo5XlkcGNgNq8cEdfzrRTxQ6np+xyRHMqvlTyOhpLLTrbT93s6MC/DEsTmshdRsp8scGdJZohbqRcQCuP7+0yeqWy2WpzQLkIpBXJzwRkURdIvjbNP3G2NV35YgZGM8CrbXdJuL6+jlt0UgptclgAMHj7GrazgeLTYrechmWPYxB4Ph+VWG1W2tSDk95QTw0Pc4cEDt6TLaRZx6jeGGSQp7pYYHXGOK2Flax2NsII2YqCT7xyeaj6PolhZahA5V397aS7+B48MVoNV9hS3RbcxB1bkJ5Y8TUGot83lTx6S5otN+Gwrr7R7j0lLKI4/djRVycnAxQ80I3UUlyUDgvycDwomaqYI6zTBB6TPdtLbvtGWUDJjYg/AjP6UvZrUP9Q0SMs2ZYP3L+fA90/Mfkas9Xh9o0i4QjPu7vpzWH7MXbaZrvssrYjuh3Xpuz7p+vHzrU0ZyhX0nPeNUb1DjtN1XUnJ48fWidw2PxjPkBVychGV1NBz558qSR1hTdK6xDzdgv50ooppyOUbPh41XS61YRZxMZSPCNSfucCoM3aXwgtR8ZHz9h/WnwZKtTt0E0rqsi4PQ8g1GkUxAmXCAfxMdoP1rLya9qMikLcd0p8IgF+/WoEkjytukdpG82JJ+9Ptkq6Zu5mpl1awh63Ac+UYLf2qHL2ijHENuW9ZGx9h/WqCuotokw06DrzLGXW72ThZFiH+xcH6nmoUs0kzbpZHkP+5iaHXGnxJgqr0EXNJSVaW3Z7ULlVbu1jRhkM7Y4oXdUGWOJNXU9pwgzKyuolzA1rdSwPjdGxU4oY60QORkQCCDgxyRNNlERnLcYUZrbWDyPp9uZVKyd2oYHqCBg03s9IJdEhwAGTKHA64P9MVNtu6XUdsyqYu8ywbpgjNY2puNjbCMYM6jw/TiivzAc7gDGZGetLmrDU5rN4I0tim5G6IuBjFVuaqsMHE0a2LrkjEdmlzTc0uaGSR2c9ecUG7TvLOVf9uR8uaLmu6gg9DxSBwcwWUMCp7zOUXuiVyh3jxA6j5UhTazKfA4pRkHI4NdIDkZnmjgoxU9omKRolkGGGf0o+5X/wCIOf5h1ppABwDkU+YOcciRXt5OgkLL5HrQWLnKgHavmuPrVmhAXDKCPofrXPbpMuBhx5HqKINLtWtZeH5lczbQVxGvHhyfrTS29TlguOigdakSWRQHuxuPkxwRQlVg3d++vHvDZzRggzTrvS33TIs7jeAMgEdAevr+VMXLKcjdgYGTjFFuWyymMsDyCTgf50oKlMAsV48MZNFJxJEF7Jb7k2hiwBGfCufUblx/xAgP8nFRXbO1sqBnG0cUgbJPn64GKEVITuxzHNjgbc8Sfp0x9uBZtxZSCSc/50rtU1Aq+Il3qmMjxOen5EH5VCWZoGWYAFkPAPSmPDDqE7tIZo3UZKKufmDVW+rNgPaI6k11Y56xYZ19qimQ8BweR15Ga03Q8cEeOaoItIKviESspGcuQAR4dKvlyEAJBOAD8aqXAcYMu6KxnU7lI+crdUQd9Gw/iXB+RqGKstUQGBHAPutjPxFVor0LwGzzNEg9Mj7/AMTm/FE26lvjgx1Vetxnu4ZB/CSp48x/arMUG/ANjITj3cNz0rR8RXdpLPln6cynpm23KZTwXrQ24QRZOTyW4rqWOFXlXKvuJ5HieOuf0rq4BLQwnSjaesKtk64LTRg9CBk0ZbeJfxO7fDim7/Wk31FKPMOBEuNsS8HIJG4/eq7WNzvDKWJGCvP1qVvqPfDvLRuOVw1KOvWVg61N0q49m1SCXOAGw3wPFDsdOub9v3Se4Dy7cKK0tho9tZYcjvZh/G3h8BWlo9HdawsXgDvFdaijBltnFJuoZakLV1uJmZhN1IXoe6k3UsRZjlHvbmOT+VO30LdSbqLEWYUtXBgWG44GecULdSbqbEWZbdqbKKy7OKtrECXlUSSsN7kckY/l5A6Vg9p2YGAehPnW4l1W2fT+5kt2ZzHsJLcZx15rJtBDEQZGCZ5wxrzdaddp1ZtWpPPU85/idHfp9NrXRdOwHHIx8pDB2ggEcDgdMUO6TdbE/idTnA+9Te9tIrYT717syNGG65YAZH0IqVBZalfW6SWejXk8c+VUiEgMcDkHpjrzQec56LEvhFCYNln0mfgjkyP4QDn416DF312iyKyJ7obDAkgn0qrg/Z/qRiT/AFAskr8+y243uF82YcL8Oa0sOiXMGO8eG2AXGJJMtj4DJNAylhuJ57QbbqlYUVISv+WO/wCchtaZGHuJW+BCD7cii2uyPfGgwM7sZz161LNhaKT313POfKNNi/U8/ahTi1i29xbiHnBYyFifienWq9iMyks2Za0pWtwErCj7/bP6xXCsFLAEKwPIz6frU+e7ludglIPdrtGBiqiS7gVSGkB4xgcmjSyTy2Kva4MjAEZqr5bcA8AzTN1RJKncR2HJkwRbIZbgIqxpzJJwOfU+JoN1cG3tWmVQ+ADjNQ5bpruzu7QhlaMBmXPDEeP+edBkvGGgxsFDZ/dvnwxUyUcjPqM/rKdmswHweNpIPxBwfoZKvLlZrWCMM0bXOMMOg+NSpcpHGhJYgcnzqqSMv7JY3AKMPfV1IPHUCjGY3Opu6SMEiG1kI6mnesAYHQZP34jU3FmLMOTgfbJ4+GZNtm9xk/kYj5HkfnRs1FhOLgj+dfuP/NSaqHrNMdIaC5ltpC8LbWIxnGeKSSaSaQvIxdj1JoYNLmmz2iwM5js0oNNzS54pR4+orNucnzo0jYjJB69KjA04gmVv+gW1xf3E8kkmWk37VwACRnr8c1tZbDTW0ssyxlpI9wMrZOevQ+tZxDtuR5OpHzHP6mpGfrUptbo3Mr/h16px3Pxj190YAAHkOKrtR0WLUrpJpJWTau0hQOec1YZpc1GjMhyp5k1laWrtcZEbbQrbW0cEZJSNdoycnFGBpDHIqByjqhOAxUgE0goTnOTCUDGF6R4NOBqDeagln7uwu5GQOg+tSYpRLEki9HUMPnRGtgAxHBka3IzmsHkdYOfUIIJREzEuSAQB0z5/WjTPsTHiao9bjxdBx/Gv36f0o99MbqwWUE4wpPr5/c1YNCkIQeszl1r77lce5yPlAWsEgvw8ajukYjdngjnp51a7qrtKkzBIh/hbPyI/81OzS1BJfDduIfh6IKAyf5cwnDqVPQjB+FeZa5AYZUYZV43KEjqCDwftXpQNZDtFaBtRmUj8ZWQfE/4afTPsfMm1NfmVlZPg7S2sunwTyiVppEy6qv8AEODyT5jPzpkvayXbtgtI1/3SMXP0GBWbiQxJLCf+XJuHwYZ/MGnVsgAjM4xtKiOQR0ljPreoXBO65ZAeojAQfaoJYs24nJPieTTK7NF0hhQOgj81K02yGoXotzL3WVJBxnOPCoWal6VOLfVbaQnAEgB+B4P50Nmdh29ZNSF8xd/TIlvqOgW9npcs0byPKmDlumM88Vn6391D39rNAf40K/avP1ZkIbxU5+Yqpo7WdTuOTNHxOhKnUoMAj+/rDRWs8/8AwYJJP+lSaDmvRIpFkiSRcbGAYAdMHmsDew+zX88P8jlR8M8Uen1PnEgjGJHrNENMqspzmBzXZq80zs8l9Zx3L3JCvn3VXkYOMZNQNYsV0/UWgjLFNqspbryP65qVbkZyg6ys+lsrrFrDgyIis7bVUsfIDNbvRzIdHthKjI6ptIYYPHA+1V/ZiVW0pl90NHIVJ6HB5H61echQcHB6HHBrN1dxc7MdDNzw7TCtRaGzuHSUd/2d9u1SW5NwI0kwSAuTnGD+VHg7NafFy6PMf97cfQVa5rqrG+zAXPEvDR0Bi+0ZMbDBFbx93DGsaddqjAoNwMXAP8y/kf71JoF2PdRvJsfUf+Khzk8yyBjpBClzTc0uaePHZpc0wU4dKUUcKdUd7qCL8cqg+Wcmo76tCPwK7/apUosf3VkTXVp7xke7TbdyDzOfrQ8Uj3Zubs5QKNgIwfI/3FOrbqVlQK3WcBr9o1LlehOfrOxTlRjkhSceVcKcpIORwaOUYgFKB408sGHvL73mKQClGJjg+eHG4efjXPAkq4wHHketcBSjilECRzK+6092j2RsdoOdjHH3/rVXIpgk2vEUbHAJ+/rWkpHijlTbIiuvkRRhyJep1zpw/I+8zMjdSxyRjBU1w5Yj9M4q1utHDKTA4GR+Fv0NVcsEsDBZkZD5Edf0qZWBmpXqK7fdMj3TYjVQeM+VWGlSYvkwfxqV+2f0qBPzEeOhzwOKJYybJIJM/gcfTNQakZWaOlbDTTdef0pRgt5CkPDY8AelLnHwPhWRNSBvV32Unp730qoAq7cAxkMeCpHlVOK7j/xizNdlfoQfr/1OY8bTDo/wx9P+4mK4orKVZdwIwR5040q8EGuudQ6lT3mADg5Ep0RkKmNcBDvwevx9fhXVeMJNamllhslWO1ULKYoyqoBxuP8AWurxF9a1LFCcEdR6TukpyMr0ma7wDqaG90iPtJJbyxSggdBiol4MSq/mPyremQoBPMPLcuiOVUZUEgdc4qToUqX07x3Kh8rlR0FRI0ACsxwpwa0Oj9ln0vu7vU7q3s1vLY3Fjz3jSc4ClV/CSOhPHh54l0lipcrWdBLeo048o7BzD3NytnEoRBjwAHArrW8juIiwYZHXPFJLolxfmP2mSOJQeQp3cfLFSZez9naWKSrlykmMYAHI4JA64P51p2eO1pZlOVlCvwy9xyMfOIWpN1R5LqOJCztgDqfWnJJ3iBwCARnBGCK6VLq3OFYEzN2kQpauzQ91JuqbEaELUm6mZpN1KLEfupC1MzXZzSj4j44+8lRCwG5gMnwrVewaFFZLBJpEN665JluuTnxwB0HzonZ7slwt3qadeUgP5t/T61a/6BDEm66uyF8xhR9TXGeM69bXFVTeyOvoT+82tFRsG5xzKhtQkjto4LeGC1gh4jSGFVC/AkE1LsNUmWzVo33u/LyNl2POMZPQDyq4g0jT41DJAsniGf3j96U6XatK0hi68kDhR8hXPZBmhxKSW9nmJDzyMPIsfyocdndTD93bv8SMD6mrua90vTB+8mtrcjwyAftzVRedudKhJEIluSPFRhT8zU9Wnut/+aEwGtRepjxoswjZ7iaKJAMs34iB9hWdvZEeY92zNCv/AAwwxu/3EVMvteudVgCmL2aEnOzOWI8N39KrtjNnHPqalSoofa6zD1ut8z/jr6frIzKT61MtHuJrNFtnA7uQhs+I6/1qvup4UjKmVM+IByftQtN1AJeKUkYJuBfPAI6ZpXoXTI6iN4db5dmWyAeOPmJK0zUxeaw8hi7reTGyZz4cflUW+uriyAtdo9mkmILEePoaPeWVmtpLdWUm4SyMHcNkA+nwqnWW8jS0s72PbDgtErryT51HWFYhl6dMH4TQu3IHR+oycjoQ2OJoLvUbaLTdPuGbFxC+1+c5Hhx4dDQ7u+XTtUubqQP7JsyzIM4JPAqqksrNoEs7e5LO2DIWH4CTTZ7iG39v0qeRn7xFVWA6HA978jQtWCMD6fDP7SSmwhstjgjnPfb+/eaqO4jk7qWNwwBUnB6Bh4/Wp4NYrS5otOup7WJzcRvGArH3ckeP3rYxSCWFJB0cBqztRSaj8Jt6XUreCB1HWEzS5puaXNVpcjs1Jsp47e6SSVN6DORgHw9ai0opA4ORBYBhgwurXiXl2HSMxjaAQfGoQNNZ9zk+Zrs0fXkwAABgTpGC7X/kYE/DofzqTUVhvRk/mBFFik7yFH8SAT8fGmMMQ2aJBKYbiOUdUYNQc0uaGORkYk7Xu0UBtxCkMjsx3KzcAY+9QbWf2i0jlwAWGSB4Go19aG8RArBCp5JGeP8AAKrb+J7VVt1kdoiMgE+Jzn71dVEvAUH2pjW3W6JncjNYxj5nEm6ynuRSeRKn8/61J0mXfpyLnmMlPvx9jQrhvatGEnjtV/p1/WgaHJh5oj6OPyP6U5BbTkH/ABMEEV+IBh0sX+/pO1a5gmCJG25kJyR0+vxFNs2EumSIxA27lyfDxH51CvIxFeyJnau/GfIE/wBDRbu2S2jTuyxBzuJOcnz+lWSibEqB68zPW63zrtSyjA4I+0kabFKjvIy7UdeAep58qr9TuZo9ftUUu+2QOFHPBx4fDNWUEzf6WHTBdFI58xUPRb5ru9vQ7bmGCpPXbyMfDx+dRZbc9rAccS8qVhKtOhIz7QP3l5uAbGRmqLtHF78Ew8QUP5j9a2YvLBdN7sbEd48MqJznHjWX1yPvdLZscxsH/Q/nVQAKwwZqBi4ORiZS8Tbeq4Huzw8fFTn/AO6m2NsLy9itzII+8ONxGfCpOwzLGnVlfK59eMfc/WpVnoF/FdxyP3cJicH3myeD6VqLcq14JwZiXaV2vBC5BxmSbjs5Db6dPIskkkqIWXoBx6VnMivW4tBVxia4yrDBCLjIPqazqaJY2UhRbVCyMRl/ePHxqtXqWrB8znMsW6JNQ48jAA6zFw281w22GJ5D/tXNMIZGI6Mp+hr0FcKMKAo8hxWWvdDvJ9VuGhiAiZyyszADnmp6dYHJDcCV9R4a1Sg15YzUW84ntopx/wAxFf6iosejafHIz+zK7MS2X55p+m28lpp0NvKys8YIyvTGeKlVkklSQh4m+qh1U2DmKoCqFAAAGAB4VmtZ0i7utYeS2h3JIqksSAAcYP5Vpc11FVa1Tblg36ddQmx5B0WznsLDuJyhO8sNpzgHH60W70u0vp0luIy7Iu0DJAxnNSqWhNjFi+eYQpQIKyMgessezdraW8ssUVvEmVDDCjw/81N1t4ntUAkUyI/4d2TiqIMR0yK4UXmHbtMDyB5m8HEdXCm5pc1FLMdQ7ld1s4HUDI+XP6U/NKPXpSilS99bR/imX4Dk/ao8msxD/hxs3qeKqrhO6uZI/wCRiKLZiJywePeRyMk4+grcr0VW0MeZj2a23JA4kh9WnY4XZHnpgZP3o6aZrN7EJfZbpojxvdSqfU4FbrQNV7PaZ2ftZ5pLCzuNpD7VXvGIJGeAWqfqGsWl9YlITJJvwyuRgefjzQGxajhExHWtrRlnzMBB2XumwZp4Yh5Llz+g+9Vt5b+x301uWLd22M4xkedazVLqa0t1eHby20kjOOKyeoSyTXhlkbc7AZP2qxp3ss9pukr3pXX7K9YyCTu7yJiMhsoQfUZ/MCpzBeqE48j4VVM+xQ/8jBvoatiu0kHqKmec7r1w4b1EQcMDgH40UBJPJG+xoYHNc7pEMyOqD/ccUEzY5kKHBGDSgVEk1q0jUr3hl9FXP3OKhvrp6RW4Hq7Z+wpYMPy2PQS4p3eGJcsQq/7uB96zy6he3c6RCcRd4wUbRtAyfPrXX+n3FtqCWzObiVwCpGTknwGfhQkgHaTzLCaOxl39ukuZdSsUB/fZYfwoC1QpNbX/AJUBPq7foKq7i3ltJe6nQo4AOCfA0sMEtxKIoY2eQjIUDmj9nG7PEP8ADYbaQcyU+q3T8BxGP9ox96iymadd57yTYck8nFaK47OJLb2/s6rBJjMu9ix6D9c1MttJ9j0m7te+MvfKT0xg4qqdZWBles2afCrFs5GAO/8AeZi5MuhB646c0OA5jZc9KLnjnr5ZqLHKsEhEh2jp6Vo2rlTiTUnDczXRyb4kk/nQMPmKBfySRWweN9pDDOPKm6fKH06IhhgZXOeOD5029ubc20iCQEnoBzznzrIrU+YOM8zUsYbDzjiVxlaU5kcufIkmiw/8Jec44+nFRslj1yD4Zo8BO1gTnBrtPBX23lPUTlteM1g+hhT0pVrvClFddMWSIZUnRdLghijuriTLXDykZRh+FgeAP85rqoNXE0dxHJEyqGXkE4yQf711eLeL+G3V620VNgFifXrz6/Gdto9Sr0JvGcDH0lS0+MbVZyTtAUck1bRaNHNb99cu7CNe8aKEbmx49KrtLwdTtNyhwZlBDcgjdXrAACGIKAhBXaBgYNX7L9nGI34A2+62B9/9Tyu6iEFwwWLuQeQhO7ap/Orzs/qUENrFBcyLFC11HLLPFbiSeFI+eCDkAsQOOvPPhQZ7CG8uoVkLD3SMqcVPt7YWcE8NrLNAJ9okMbkFsNuH0IBo68sMyLValNNis5MI97LI7sm3BY+82cnnrio11qc5Hsocyu3/AClwMeOWPgKFrF3OgjYSsZbiYq8p5bzJz5nzp8NvHbqUjXGTkknJJ8yfGgcJV0HMr6Y364lnsO30HH6RtvZe+JrkiWUdB/CnwH60aUkSH15p6mmT9EPxFXPCL2XWKT3yJoazToumKoMY5g812aSkrv5zGIua7NNzXUo8XNWGl6tb6RL7QbP2m6B9wucLH6geJquoc3hUV9S2oVbpJEYqciXl5231m5yElS2U+ES8/U0LS9bgt7g3upe0X9yrZjjZvcU/zHzNUZpB1qh+CoC7FXHy4P16yfznzkmau77fajNkW8MVuPPG41SXeu6nesTPeysD4A7R9BUCkNPVotPT7iD+/OM1rt1MUkk5PJ8zSJcrbTBmi70jlQTgZrqDcnaqsAMg+NLW58hsGR4B4MlvrV1IfdKxj/aufuc0a4tXk0GPUmuZJSZNjo3Rev8Ab61om7O6feXKzOjJ7i+5Gdq/ao/ZxQp1C2wDFFce6Dzjr/QVwbaoFdyjpialXhoV9jY5yB8CO8o7nSbq2uobbasskyb0EfOaFaWcoE8pZAsQAZSeTnp+VbqS1gluIZ5Ig0sP4GP8NYvVrl7O/niixskm2kEev96fT6hruO4/mDrtGum56g5A+n8xdNkFrpV1azsoVn3Rkt0/zAocOpul9NDdSKYxDtjLge6PIH/OlR9bVUhO0ABSMYqDqPJhc9WTn/PnVsVr1xKC3WOB7XbH5SUSI9U6+7MnX/PhUCadrzUnnLcqNpGMfCizufZLWTPvAdahJxqDgdCvNSx617ywgv1s5EBiVu9cLvPVR41u9Kk32IU9YyV/X9awWiWUWoQubnc+x+BnFbrTziaZBwoVcD6iqGuXNefSX/DnCarYO4P8yfS0ldWLOrjs1IuLGe3sTcPtCnAADZPPjVTfXklsBsC/MVXX2rX9zbpFJdSd0BgIDgfarmn0ptGZQ1Gq8pgBLJ5Y4hl3VPicVGfU7ZOjGQ/7RVJ15PJqya1hiiibZvLqGO4k9R5VeXQIPeOZUbWsfdGJz6y5OI4lHqxyftU/Srg3FmS2NyuQcfX9agSEiJ1HuqVIwox4elP0JjmdfDCn86DVUVpUSgxiHpb3e3DGXVLTBThWRNaRrjUYreTYcswOGxwBQtYTMKP/ACkqf8+VQdYAW6kIHVQftVTNq15c38cUkv7tZCoQcDjIyfM1qU6cAo6fnOb1OsLpfTaOnAx+fX6TT6QwlsGhb+Fih+B5/Wq+ymFnqKtIwVcFWJP+eIqRojHvZ18CoPzyahaqg9puV8OfyzRogNtlXY/395DbYRptPqe6nH9+kS6u7e8uJO5kD8AEgePP9qnzN3+mJJ44DfoayuksfaXHmmT9a1Fn7+nMp6e+KfUIKlQjsY2kc33XIf8AMf39Z2lsGjliboGz8iOfyqm0djadoe6PG7dEf0+4qx09iL7aOjoc/Ij+tVmo/uu0YdOCJUPz4pbf+R09RDpcnTU291OJrM02ePv7aSL+dSv2qFDeySa5c2ZC93FErqQOckmp+cVkkYnSDmY1M9OhrYiQTxpN4Sor/Uc/fNZW7QJeTBeAJGH3rQaYxbSLcnw3L8gxqe7oDAq64l8NclSBESJMqoBZjnPyqBLK00rSPjc5ycDFCparlieskWpU5URc0opKUU0knUoNJXCmjRc12a6upRRc0oNJSilFFzXA0lLSii5pabS0oouaWmilpRTMa3H3eqOfCQBqid1KsZkMbhOm4ggVfasSk8TKSrFcEjr1qquCTA7E5OOp5ro9I26lZz+pG21hIYwKuI+0l3BZxQRpEvdqF3sMk/pVnofZmxvdDS/neZpC+3YGAXofIZ+9WVtp9lbcw2kKEeO3cfqcmgvurB2sMySiqwjKHEyct3qeor78k0qdcAYX9BTFsZP45I0+e4/ap9//AP5CcEkgOcZOcVcdldDttauWW5eVVU9IyBn7VYDBVyBgSqQzNgnmZS4hWNu7DFlZcZIx1oD9oJzEqCGMMoALtknitl290Wx0ZrBbKIpv7wOSxYtjbjr8TXnU4xcygfzmmVhYoYSprKQOG7SRJqV3L+KdlHknu/lUchs5cNk8gsOtNQkOpBwQQa3F1pltfyrLcKzmNcAbiB51BdeKSAR1i0mi/EK2w4IxMounTtprXwKd0pxjPPXHSp40CaXRoru3WWWRyNyldoXqOvxrRW8EVvD3cMaxoDnAqZ7bObX2XcO6HOMc9c9azzrWPT+/CbC+FIAM88YP7kTD3WlXenwJcShRlwAFOSD1H5Vq5rGC+ube8k3h4wGXBx455qTSiq9mpazBPUZ+8tUaGunIHIOOD8JAutMa41i3vVkRViA3KVzuwT+hqZBp9n/rSX0/eZ/CwU4GMY6USnCo/Nfjn4flLH4evk46nP5+sPcPC07G3Vli4wG60MdeelNFLUfWSgYGJgLyLuL2eHpskI6+tVV8uVkAH+4DNaDXUH+uzgcbtpP0FUd0B5fwmurpbdWrfCc3YNthHxjdJfMMsfPDAjHqP7VYZwOmPDjGTVPpTFbmQDxT9RVsoy23PWpMyNuOY/cRySQTRrY/vGX0/wA/OhXEYgmCKcjbu5p8JPtKD0P5f2q94c+3UoR6/rxKeow9BMliupRSV3M5+VuuDFrFJj8L4+o/tXVK1CJZtPkVs4GDx8RXVwHj4VNXn1AP7ftOh8Of/hwexn//2Q==",
        },
        { // steve
            "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",
        },
        { // cave
            "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",
        },
        { // meadow
            "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",
        },
    };
    // розкодовані картинки тримаємо «м'яко»: якщо пам'яті мало, збирач їх викине й вони розкодуються знову
    @SuppressWarnings("unchecked")
    private static final java.lang.ref.SoftReference<java.awt.image.BufferedImage>[] CACHE = new java.lang.ref.SoftReference[DATA.length];

    static synchronized java.awt.image.BufferedImage get(int i) {
        java.awt.image.BufferedImage im = CACHE[i] == null ? null : CACHE[i].get();
        if (im == null) {
            try {
                byte[] raw = java.util.Base64.getDecoder().decode(String.join("", DATA[i]));
                im = javax.imageio.ImageIO.read(new ByteArrayInputStream(raw));
            } catch (Exception e) {
                im = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            }
            CACHE[i] = new java.lang.ref.SoftReference<>(im);
        }
        return im;
    }

    /** Малює картинку «cover» у прямокутнику (з обрізанням країв), нижню частину можна зсунути через focusY 0..1. */
    static void cover(Graphics2D g, java.awt.image.BufferedImage im, int x, int y, int w, int h, double focusY) {
        double sc = Math.max((double) w / im.getWidth(), (double) h / im.getHeight());
        int dw = (int) Math.ceil(im.getWidth() * sc), dh = (int) Math.ceil(im.getHeight() * sc);
        Shape old = g.getClip();
        g.clipRect(x, y, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(im, x + (w - dw) / 2, y - (int) ((dh - h) * focusY), dw, dh, null);
        g.setClip(old);
    }
}

/** Кружечок-пресет кольору теми. */
final class Swatch extends JComponent {
    private final Color col;
    private final Fx fx = new Fx(this);

    Swatch(Color col, Runnable click) {
        this.col = col;
        setPreferredSize(new Dimension(32, 32));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(Theme.hex(col));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); }
            @Override public void mouseClicked(MouseEvent e) { click.run(); }
        });
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        boolean sel = (Theme.accent.getRGB() & 0xFFFFFF) == (col.getRGB() & 0xFFFFFF);
        int in = Math.round(5 - 2 * fx.h());
        if (sel) {
            g.setColor(Theme.TEXT);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(1, 1, w - 3, h - 3);
            in = 6;
        }
        g.setColor(col);
        g.fillOval(in, in, w - 2 * in, h - 2 * in);
        g.dispose();
    }
}

/** Вибір довільного кольору: квадрат насиченість/яскравість + смужка відтінку. */
final class ColorPick extends JComponent {
    private float h, s, v;
    private int drag; // 0 — нічого, 1 — квадрат, 2 — відтінок
    private final Rectangle sv = new Rectangle(0, 0, 280, 170), hb = new Rectangle(0, 184, 280, 16);
    Consumer<Color> onChange;

    ColorPick(Color c) {
        set(c);
        setPreferredSize(new Dimension(280, 204));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                drag = hb.contains(e.getPoint()) ? 2 : sv.contains(e.getPoint()) ? 1 : 0;
                upd(e);
            }
            @Override public void mouseDragged(MouseEvent e) { upd(e); }
            @Override public void mouseReleased(MouseEvent e) { drag = 0; }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    void set(Color c) {
        float[] f = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        h = f[0]; s = f[1]; v = f[2];
        repaint();
    }

    Color get() { return Color.getHSBColor(h, s, v); }

    private static float cl(float x) { return Math.max(0f, Math.min(1f, x)); }

    private void upd(MouseEvent e) {
        if (drag == 1) {
            s = cl((e.getX() - sv.x) / (float) sv.width);
            v = 1f - cl((e.getY() - sv.y) / (float) sv.height);
        } else if (drag == 2) {
            h = cl((e.getX() - hb.x) / (float) hb.width);
        } else return;
        repaint();
        if (onChange != null) onChange.accept(get());
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.setClip(new RoundRectangle2D.Float(sv.x, sv.y, sv.width, sv.height, 14, 14));
        g.setColor(Color.getHSBColor(h, 1f, 1f));
        g.fill(sv);
        g.setPaint(new GradientPaint(sv.x, 0, Color.WHITE, sv.x + sv.width, 0, new Color(255, 255, 255, 0)));
        g.fill(sv);
        g.setPaint(new GradientPaint(0, sv.y, new Color(0, 0, 0, 0), 0, sv.y + sv.height, Color.BLACK));
        g.fill(sv);
        g.setClip(null);
        int kx = sv.x + Math.round(s * sv.width), ky = sv.y + Math.round((1f - v) * sv.height);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0, 0, 0, 110));
        g.drawOval(kx - 8, ky - 8, 16, 16);
        g.setColor(Color.WHITE);
        g.drawOval(kx - 7, ky - 7, 14, 14);

        g.setClip(new RoundRectangle2D.Float(hb.x, hb.y, hb.width, hb.height, 16, 16));
        for (int x = 0; x < hb.width; x++) {
            g.setColor(Color.getHSBColor(x / (float) hb.width, 1f, 1f));
            g.fillRect(hb.x + x, hb.y, 1, hb.height);
        }
        g.setClip(null);
        int hx = hb.x + Math.round(h * hb.width), hy = hb.y + hb.height / 2;
        g.setColor(new Color(0, 0, 0, 90));
        g.fillOval(hx - 9, hy - 8, 18, 18);
        g.setColor(Color.WHITE);
        g.fillOval(hx - 9, hy - 9, 18, 18);
        g.setColor(Color.getHSBColor(h, 1f, 1f));
        g.fillOval(hx - 6, hy - 6, 12, 12);
        g.dispose();
    }
}

/* ======================================================================
 *  Мова інтерфейсу (uk / crh / gag). Вибір зберігається в launcher.properties (ключ lang) і діє після перезапуску.
 *  Базова мова — українська (рядки в коді). Словник кримськотатарської чи гагаузької створюється лише тоді, коли її обрано.
 * ====================================================================== */
final class I18n {
    private static volatile String code = "uk";
    private static Map<String, String> dict = Map.of();

    /** Мови інтерфейсу: код -> назва (рідною мовою). */
    static final String[][] LANGS = {
            {"uk", "Українська"}, {"crh", "Qırımtatarca"}, {"gag", "Gagauzça"}};

    static String norm(String c) {
        c = c == null ? "" : c.trim().toLowerCase(Locale.ROOT);
        for (String[] l : LANGS) if (l[0].equals(c)) return c;
        return "uk";
    }

    static void init(String c) {
        code = norm(c);
        switch (code) {
            case "crh": dict = buildCrh(); break;
            case "gag": dict = buildGag(); break;
            default: dict = Map.of();
        }
    }

    static String code() { return code; }

    /** Назва мови її ж мовою. */
    static String nameOf(String c) {
        String n = norm(c);
        for (String[] l : LANGS) if (l[0].equals(n)) return l[1];
        return n;
    }

    /** Мова, якою перекладаємо описи модів. Для гагаузької онлайн-перекладача немає — українська. */
    static String descLang() {
        return code.equals("crh") ? "crh" : "uk";
    }

    /** Український рядок -> рядок обраною мовою (якщо перекладу немає, лишається український). */
    static String t(String uk) {
        if (dict.isEmpty()) return uk;
        String r = dict.get(uk);
        return r != null ? r : uk;
    }

    /** Повідомлення про зміну мови й перезапуск — мовою langCode (target — назва нової мови). */
    static String restartNote(String langCode, String target) {
        switch (norm(langCode)) {
            case "crh": return "Interfeys tili «" + target + "» tiline deñişecek. Tercime tatbiq olunsın içün launcher'nı qaytadan aç.";
            case "gag": return "Interfeys dili «" + target + "» diline deeşecek. Çeviri uygulansın diye launcher'ı yenidän aç.";
            default: return "Мову інтерфейсу буде змінено на «" + target + "». Щоб переклад застосувався, перезапусти запускач.";
        }
    }

    private static Map<String, String> pairs(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Кримськотатарська (латиниця). Чого тут немає — лишається українською. */
    private static Map<String, String> buildCrh() {
        return pairs(
                "Скасовано користувачем", "Qullanıcı tarafından ret etildi",
                "Контрольна сума не збігається: ", "Kontrol summası tüşmey: ",
                "Версію ", "Versiya ",
                " не знайдено (немає інтернету?)", " tapılmadı (internet yoqmı?)",
                "Завантаження опису версії ", "Versiya tarifini endirmek ",
                "  · без модів", "  · modsız",
                "  · стабільна", "  · stabil",
                "  · бета", "  · beta",
                "  · рекомендована", "  · tevsiye etilgen",
                "  · остання", "  · soñki",
                "Невідомий завантажувач: ", "Belgisiz yükleyici: ",
                "Fabric не підтримує версію ", "Fabric şu versiyanı desteklemey: ",
                "Fabric профіль збережено: ", "Fabric profili saqlandı: ",
                "Quilt не підтримує версію ", "Quilt şu versiyanı desteklemey: ",
                "Quilt профіль збережено: ", "Quilt profili saqlandı: ",
                "Завантаження Minecraft ", "Minecraft endirmek ",
                "Готово", "Azır",
                "Завантаження профілю Fabric…", "Fabric profilini endirmek…",
                "Завантаження профілю Quilt…", "Quilt profilini endirmek…",
                "Завантаження встановлювача ", "Qurıcını endirmek: ",
                "Встановлення ", "Quruv ",
                " (це може зайняти кілька хвилин)…", " (bu bir qaç daqqa alabilir)…",
                "Встановлювач завершився з помилкою (код ", "Qurıcı hata ile bitti (kod ",
                "). Деталі дивись у Журналі.", "). Tafsilâtnı Jurnaldan baq.",
                "Встановлювач відпрацював, але нову версію не знайдено в ", "Qurıcı işini tamamladı, lâkin yañı versiya tapılmadı: ",
                "Завантаження файлів: ", "Fayllarnı endirmek: ",
                "Файл Java не знайдено:\n", "Java faylı tapılmadı:\n",
                "\nОбери Java в Налаштуваннях або очисти поле, щоб використати вбудовану.", "\nSazlamalarda Java'nı sayla yaki launcher'nıñ öz Java'sını qullanmaq içün alanı temizle.",
                "Ця версія гри вимагає Java ", "Bu oyun versiyası Java ",
                "+, а запускач працює на Java ", "+ talap eter, amma launcher Java ",
                ".\nВстанови потрібну Java і обери її (або вкажи шлях) у Налаштуваннях.", " ile işley.\nKerekli Java'nı qur ve onı Sazlamalarda sayla (yaki yolunı kösterip).",
                "Підготовка файлів…", "Fayllar azırlanmaqta…",
                "Немає клієнтського jar для ", "Mijoz jar'ı yoq: ",
                "Розпакування…", "Paketten çıqarmaq…",
                "authlib-injector недоступний: ", "authlib-injector mevcut degil: ",
                "Моди", "Modlar",
                "Пошук модів", "Modlarnı qıdırmaq",
                "Встановлені моди", "Qurulğan modlar",
                "Тека модів", "Modlar papkası",
                "Сторінка мода", "Modnıñ sahifesi",
                "Видалити мод", "Modnı silmek",
                "Версія мода (для цієї збірки)", "Modnıñ versiyası (bu cıyma içün)",
                "Набори ресурсів", "Resurs paketleri",
                "Пошук наборів ресурсів", "Resurs paketlerini qıdırmaq",
                "Встановлені набори ресурсів", "Qurulğan resurs paketleri",
                "Тека наборів ресурсів", "Resurs paketleri papkası",
                "Сторінка набору ресурсів", "Resurs paketiniñ sahifesi",
                "Видалити набір ресурсів", "Resurs paketini silmek",
                "Версія набору ресурсів (для цієї збірки)", "Resurs paketiniñ versiyası (bu cıyma içün)",
                "  · тільки з сайту", "  · faqat saytta",
                "Автор заборонив завантаження сторонніми запускачами — візьми файл на сайті.", "Müellif üçünci taraf launcher'lerinden endirmekni yasaq etti — faylnı saytından al.",
                "Залежність: ", "Bağlılıq: ",
                "Завантаження: ", "Endirmek: ",
                "Встановлено: ", "Qurıldı: ",
                "Залежність ", "Bağlılıq ",
                " недоступна автоматично — встанови вручну.", " avtomatik olaraq mevcut degil — qolnen qur.",
                "Не вдалося поставити залежність ", "Bağlılıqnı qurmaq mümkün olmadı: ",
                "1.16.5 і новіші", "1.16.5 ve yañılar",
                "PaxPolit - це український політичний сервер в майнкрафті: танки, зброя, політика та багато чого іншого. Приєднуйся до нас в дискорді.", "PaxPolit — Minecraft'taki ukrain siyasiy serveri: tanklar, silâ, siyaset ve daa çoq şey. Bizge Discord'ta qoşul.",
                "PaxCiv - це підсервер PaxPolit, який існує для проведення цивілізаційних експериментів типу Ish'a тощо", "PaxCiv — PaxPolit'niñ Ish'a kibi medeniyet tecribelerini keçirmek içün yaratılğan alt serveri",
                "Версія ", "Versiya ",
                "Версія", "Versiya",
                "Підтримуються", "Destekleneler",
                "Відкрити Discord", "Discord'nı açmaq",
                "Копіювати IP", "IP'ni kopiyalamaq",
                "Закрити", "Qapatmaq",
                "Завантаження новин…", "Haberlerni endirmek…",
                "Новини недоступні без інтернету", "İnternetsiz haberler mevcut degil",
                "  · збірка (", "  · cıyma (",
                "  · знімок", "  · snapshot",
                "  · давня", "  · eski",
                "  · з модами", "  · modlı",
                "  · встановлено", "  · qurılğan",
                "Випуски", "Relizler",
                "Знімки", "Snapshotlar",
                "Давні (бета/альфа)", "Eski (beta/alfa)",
                "Готово до запуску", "Başlamağa azır",
                "Грати", "Oynamaq",
                "Додати версію", "Versiya qoşmaq",
                "Встановити", "Qurmaq",
                "Обери версію Minecraft і завантажувач", "Minecraft versiyasını ve yükleyicini sayla",
                "Скасувати", "Ret etmek",
                "Пауза", "Pauza",
                "Вбудована (Java ", "İçki (Java ",
                "Свій шлях…", "Öz yol…",
                "Ховати запускач, поки триває гра", "Oyun devam etkenden launcher'nı gizlemek",
                "Створи збірку і шукай моди", "Cıyma yarat ve modlarnı qıdır",
                "Обери мод зі списку", "Tizimden mod sayla",
                "Зупинити", "Toqtatmaq",
                "Знайти", "Qıdırmaq",
                "Ще результати", "Daa çoq netice",
                "Тека запускача: ", "Launcher papkası: ",
                "Ласкаво просимо!", "Hoş keldiñiz!",
                "Показувати гру в Discord", "Oyunnı Discord'ta kösterüv",
                "У вкладці «Моди»", "«Modlar» sekmesinde",
                "Шукає й встановлює моди", "Modlarnı qıdıra ve qura",
                "У налаштуваннях", "Sazlamalarda",
                "Налаштовує запускач", "Launcher'nı sazlay",
                "У журналі", "Jurnalda",
                "Читає записи гри", "Oyun yazılarını oquy",
                "Додає версію", "Versiya qoşa",
                "Обирає завантажувач", "Yükleyicini saylay",
                "На головній сторінці", "Baş sahifede",
                "Обирає версію", "Versiyanı saylay",
                "Журнал", "Jurnal",
                "Налаштування", "Sazlamalar",
                "Головна", "Baş sahife",
                "Не вдалося відкрити теку: ", "Papkanı açmaq mümkün olmadı: ",
                "Приєднатися до нашого Discord", "Discord'ımızğa qoşulmaq",
                "Підтримати запускач", "Launcher'nı desteklemek",
                "Грає як", "Oyuncı",
                "Швидкі дії", "Tez areketler",
                "Тека гри", "Oyun papkası",
                "Сервери Minecraft", "Minecraft serverleri",
                "Новини Minecraft", "Minecraft haberleri",
                "Відкрий для себе  ›", "Keşf et  ›",
                "Остання версія", "Soñki versiya",
                "Запускай свіжий випуск Minecraft: Java Edition одним натисканням — версії завантажуються самі.", "Minecraft: Java Edition'nıñ eñ yañı relizini bir basuv ile başlat — versiyalar özü endirile.",
                "Моди та збірки", "Modlar ve cıymalar",
                "Шукай моди та набори ресурсів на Modrinth і став їх у власну збірку прямо із запускача.", "Modrinth'te modlar ve resurs paketlerini qıdır ve olarnı launcher'dan doğrudan öz cıymañğa qoş.",
                "Встанови завантажувач модів для будь-якої версії гри — і він з'явиться у списку версій.", "Oyunnıñ istegen versiyası içün mod yükleyicisini qur — ve ol versiyalar tizimiñde peyda olur.",
                "Завантажувачі", "Yükleyiciler",
                "Версії", "Versiyalar",
                "Налаштуй під себе", "Öz zevqiñe köre sazla",
                "Пам'ять, власна Java, роздільна здатність вікна, тека гри та стан у Discord.", "Hafıza, öz Java'ñ, pencere ölçüsi, oyun papkası ve Discord'taki hal.",
                "Темна", "Qara",
                "Світла", "Açıq",
                "Свій колір…", "Öz renk…",
                "Колір теми", "Tema rengi",
                "Застосувати", "Tatbiq etmek",
                "Огляд", "Körmek",
                "Обери java (або теку JDK)", "java sayla (yaki JDK papkası)",
                "Шлях до java (або до теки JDK). Порожньо — вбудована Java запускача.", "java'ğa yol (yaki JDK papkasına). Boş — launcher'nıñ ichki Java'sı.",
                "Додаткові JVM-аргументи", "Ilâveli JVM argumentleri",
                "Виділена пам'ять (ОЗП)", "Ayrılğan hafıza (RAM)",
                "Роздільна здатність вікна", "Pencere çözünürlüği",
                "Ігрова тека (saves, mods)", "Oyun papkası (saves, mods)",
                "Поведінка", "Areket",
                "Зберегти", "Saqlamaq",
                "Збережено, але файл Java не знайдено: ", "Saqlandı, amma Java faylı tapılmadı: ",
                "Налаштування збережено", "Sazlamalar saqlandı",
                "Тема інтерфейсу", "İnterfeys teması",
                "Очистити", "Temizlemek",
                "Завантаження списку версій…", "Versiyalar tizimini endirmek…",
                "Не вдалося отримати список версій: ", "Versiyalar tizimini almaq mümkün olmadı: ",
                "Продовжити", "Devam etmek",
                "Завантаження на паузі", "Endirüv pauzada",
                "Продовжую завантаження…", "Endirmege devam etem…",
                "Скасовую завантаження…", "Endirüvni ret etem…",
                "Список версій ще завантажується — спробуй за мить", "Versiyalar tizimi daa endirile — bir azdan sına",
                "Обрано останній випуск: ", "Soñki reliz saylandı: ",
                "Останній випуск: ", "Soñki reliz: ",
                " — його ще немає у списку", " — o daa tizimde yoq",
                "Зачекай…", "Bekle…",
                "Назад", "Keri",
                "Версія Minecraft", "Minecraft versiyası",
                "Завантажувач", "Yükleyici",
                "Версія завантажувача", "Yükleyici versiyası",
                "Завантаження списку версій ", "Versiyalar tizimini endirmek: ",
                "Не вдалося отримати список: ", "Tizimni almaq mümkün olmadı: ",
                "Доступно версій: ", "Mevcut versiyalar: ",
                " не підтримує Minecraft ", " Minecraft'nı desteklemey: ",
                "Обери версію Minecraft, завантажувач і його версію.", "Minecraft versiyasını, yükleyicini ve onıñ versiyasını sayla.",
                "Завантаження файлів гри для ", "Oyun fayllarını endirmek: ",
                "Попереднє завантаження файлів не вдалося (буде при запуску): ", "Fayllarnı ögünden endirmek olmadı (başlağanda olacaq): ",
                " встановлено: ", " qurıldı: ",
                ". Натискай «Грати»!", ". «Oynamaq» düymesine bas!",
                "Встановлення скасовано", "Quruv ret etildi",
                "Завантаження скасовано", "Endirüv ret etildi",
                "Помилка встановлення: ", "Quruv hatası: ",
                "Помилка встановлення", "Quruv hatası",
                "Не вдалося встановити ", "Qurmaq mümkün olmadı: ",
                "Відкрий вручну: ", "Qolnen aç: ",
                "Збірок ще немає — натисни «Нова збірка».", "Daa cıymalar yoq — «Yañı cıyma»nı bas.",
                "Чиста збірка не підтримує моди. Створи збірку на основі Fabric / Quilt / Forge / NeoForge (набори ресурсів працюють і в чистій).", "Saf cıyma modlarnı desteklemey. Fabric / Quilt / Forge / NeoForge esasında cıyma yarat (resurs paketleri saf cıymada da işley).",
                "  ·  версія ", "  ·  versiya ",
                "Список версій Minecraft ще завантажується — спробуй за мить.", "Minecraft versiyaları tizimi daa endirile — bir azdan sına.",
                "Обрати версію гри й завантажувач", "Oyun versiyasını ve yükleyicini saylamaq",
                "Взяти вже встановлену версію", "Onsuz da qurılğan versiyanı qullanmaq",
                "Встановлена версія", "Qurılğan versiya",
                "Назва збірки", "Cıyma adı",
                "Звідки взяти версію", "Versiyanı neredin almaq",
                "Нова збірка", "Yañı cıyma",
                "Створити", "Yaratmaq",
                "Назва: до 32 символів — літери, цифри, пробіл, _ - .", "Ad: 32 işaretke qadar — arifler, sayılar, boşluq, _ - .",
                "Збірка з такою назвою вже є.", "Bu adlı cıyma bar.",
                "Обери встановлену версію.", "Qurılğan versiyanı sayla.",
                "Створено збірку «", "Cıyma yaratıldı «",
                "Збірка «", "Cıyma «",
                "» створена й з'явилась у списку версій", "» yaratıldı ve versiyalar tizimine qoşuldı",
                "» з'явилась у списку версій", "» versiyalar tizimine qoşuldı",
                "Створення збірки скасовано", "Cıyma yaratuv ret etildi",
                "Помилка створення збірки: ", "Cıyma yaratuv hatası: ",
                "Не вдалося створити збірку", "Cıymanı yaratmaq mümkün olmadı",
                "Не вдалося створити збірку:\n", "Cıymanı yaratmaq mümkün olmadı:\n",
                "Видалити збірку", "Cıymanı silmek",
                "Видалити збірку «", "Cıymanı silmek «",
                "» разом з модами та світами?\n", "» modlar ve dünyalar bilen birlikte?\n",
                "Видалити", "Silmek",
                "Не вдалося видалити теку: ", "Papkanı silmek mümkün olmadı: ",
                "Зупиняю завантаження…", "Endirüvni toqtatam…",
                " завант.", " endir.",
                "   (вимкнено)", "   (sönik)",
                "Вимкнути / увімкнути", "Söndürmek / yandırmaq",
                "Назад до пошуку", "Qıdıruvğa keri",
                "Відкрити на сайті", "Saytta açmaq",
                "Спочатку створи збірку.", "Evelâ cıyma yarat.",
                "Обери зі списку", "Tizimden sayla",
                " завантажень", " endirüv",
                "  ·  Є для: ", "  ·  Mevcut: ",
                "Спочатку створи збірку («Нова збірка»).", "Evelâ cıyma yarat («Yañı cıyma»).",
                "Чиста збірка не підтримує моди.\nСтвори збірку на основі Fabric / Quilt / Forge / NeoForge.", "Saf cıyma modlarnı desteklemey.\nFabric / Quilt / Forge / NeoForge esasında cıyma yarat.",
                "Пошук: ", "Qıdıruv: ",
                " на Modrinth…", " Modrinth'te…",
                "Помилка пошуку: ", "Qıdıruv hatası: ",
                "Нічого не знайдено для ", "Hiç bir şey tapılmadı: ",
                "Знайдено: ", "Tapıldı: ",
                "Завантаження версій…", "Versiyalarnı endirmek…",
                "Помилка: ", "Hata: ",
                "Версій для ", "Versiyalar içün ",
                "Немає файлів для ", "Fayllar yoq: ",
                " — не підтримує цю збірку", " — bu cıymanı desteklemey",
                "Автор заборонив завантаження сторонніми запускачами.\n", "Müellif üçünci taraf launcher'lerinden endirmekni yasaq etti.\n",
                "Завантаж файл на сайті й поклади в теку збірки (кнопка «", "Faylnı saytından endir ve cıyma papkasına qoy (düyme «",
                " у збірку «", " cıymağa «",
                "Встановлення скасовано.", "Quruv ret etildi.",
                "Не вдалося встановити:\n", "Qurmaq mümkün olmadı:\n",
                "Не вдалося перейменувати: ", "Yañıdan adlandırmaq mümkün olmadı: ",
                "Видалити ", "Silmek ",
                "Не вдалося видалити: ", "Silmek mümkün olmadı: ",
                "Увійти через Microsoft", "Microsoft ile kirmek",
                "Грати без ліцензії", "Lisenziyasız oynamaq",
                "Ласкаво просимо, ", "Hoş keldiñiz, ",
                "Ім'я користувача або е-пошта", "Qullanıcı adı yaki e-poçta",
                "Пароль", "Parol",
                "Код двоетапної перевірки (лише якщо ввімкнено)", "Eki basqıçlı tekşerüv kodu (faqat qoşulğan olsa)",
                "Вхід через Ely.by", "Ely.by ile kiriş",
                "Увійти", "Kirmek",
                "Введи ім'я користувача й пароль.", "Qullanıcı adını ve parolni yaz.",
                "Ely.by: вхід як ", "Ely.by: şu adnen kirildi: ",
                "Не вдалося увійти через Ely.by:\n", "Ely.by ile kirmek mümkün olmadı:\n",
                "Не вдалося почати вхід через Microsoft:\n", "Microsoft ile kirüvni başlamaq mümkün olmadı:\n",
                "Відкрий сторінку й введи цей код:", "Sahifeni aç ve bu kodnı yaz:",
                "Очікую підтвердження…", "Tasdıq kütilmekte…",
                "Відкрити сторінку", "Sahifeni açmaq",
                "Копіювати код", "Kodnı kopiyalamaq",
                "Microsoft: вхід як ", "Microsoft: şu adnen kirildi: ",
                "Не вдалося увійти через Microsoft:\n", "Microsoft ile kirmek mümkün olmadı:\n",
                "Вхід через Microsoft", "Microsoft ile kiriş",
                "Додати", "Qoşmaq",
                "Ім'я гравця: 3–16 символів — латиниця, цифри, _", "Oyuncı adı: 3–16 işaret — Latin arifleri, sayılar, _",
                "Ім'я гравця має містити 3–16 символів: латиниця, цифри, _", "Oyuncı adı 3–16 işaretten ibaret olmalı: Latin arifleri, sayılar, _",
                "Така обліківка вже є", "Bu hesap onsuz da bar",
                "Додано: ", "Qoşuldı: ",
                "Спочатку обери обліківку у списку", "Evelâ tizimden hesap sayla",
                "Видалено: ", "Silindi: ",
                "Неліцензійні обліківки", "Lisenziyasız hesaplar",
                "Грати з цією обліківкою", "Bu hesap ile oynamaq",
                "Обери обліківку у списку або додай нову.", "Tizimden hesap sayla yaki yañısını qoş.",
                "Спочатку обери обліківку.", "Evelâ hesap sayla.",
                "Спочатку обери версію гри.", "Evelâ oyun versiyasını sayla.",
                "Запуск: ", "Başlatuv: ",
                "Версія: ", "Versiya: ",
                "  ·  завантажувач: ", "  ·  yükleyici: ",
                "Перевірка обліківки…", "Hesapnı tekşermek…",
                "Гра запущена. Приємної гри!", "Oyun başlandı. Hoş oyun!",
                "Грає в Minecraft ", "Minecraft oynay ",
                "Чиста гра · через PaxLauncher", "Saf oyun · PaxLauncher vastasınen",
                " · збірка «", " · cıyma «",
                " · через PaxLauncher", " · PaxLauncher vastasınen",
                "Гра завершилась з кодом ", "Oyun kod ile bitti ",
                "Гру закрито", "Oyun qapatıldı",
                "Гра завершилась з помилкою (код ", "Oyun hata ile bitti (kod ",
                "), дивись Журнал", "), Jurnalğa baq",
                "ПОМИЛКА: ", "HATA: ",
                "Помилка запуску", "Başlatuv hatası",
                "Не вдалося запустити гру:\n", "Oyunnı başlatmaq mümkün olmadı:\n",
                "Автономна", "Oflayn",
                "Обліківку захищено двоетапною перевіркою — введи код у поле «Код двоетапної перевірки».", "Hesap eki basqıçlı tekşerüv ile qorçalğan — kodnı «Eki basqıçlı tekşerüv kodu» sahasına yaz.",
                "Неправильне ім'я користувача або пароль.", "Qullanıcı adı yaki parol yañlış.",
                "В обліківці Ely.by немає профілю гравця.", "Ely.by hesabında oyuncı profili yoq.",
                "Сесія Ely.by закінчилась — увійди знову («Змінити обліківку»).", "Ely.by seansı bitti — yañıdan kir («Hesapnı deñiştirmek»).",
                "Скасовано", "Ret etildi",
                "Час очікування вийшов.", "Küteyüv vaqtı bitti.",
                "Сесія Microsoft закінчилась — увійди знову («Змінити обліківку»).", "Microsoft seansı bitti — yañıdan kir («Hesapnı deñiştirmek»).",
                "До цієї обліківки Microsoft не прив'язано профіль Xbox.", "Bu Microsoft hesabına Xbox profili bağlanmağan.",
                "Дитяча обліківка: її треба додати до сім'ї Microsoft.", "Balalar hesabı: onı Microsoft ailesine qoşmaq kerek.",
                "Minecraft відхилив застосунок (403). Client ID має бути схвалений Mojang: aka.ms/AppRegistration.", "Minecraft ilâveni ret etti (403). Client ID Mojang tarafından tasdıqlanmalı: aka.ms/AppRegistration.",
                "На цій обліківці Microsoft немає Minecraft Java Edition.", "Bu Microsoft hesabında Minecraft Java Edition yoq.",
                "Профіль Minecraft: HTTP ", "Minecraft profili: HTTP ",
                "Потрібен вхід через Microsoft і Client ID у Налаштуваннях.", "Microsoft ile kiriş ve Sazlamalarda Client ID kerek.",
                "Discord Rich Presence підключено", "Discord Rich Presence qoşuldı",
                "Імпорт модів", "Modlarnı aktarmaq",
                "Імпорт", "Aktarmaq",
                "Обери теку збірки (або теку mods) чи окремі .jar", "Cıyma papkasını (yaki mods papkasını) yaki ayrı .jar'larnı sayla",
                "Файлів .jar не знайдено.", ".jar fayllar tapılmadı.",
                "у збірці без завантажувача моди не працюють", "yükleyicisiz cıymada modlar işlemey",
                "не знайдено на Modrinth (власний мод або з іншого сайту) — постав вручну", "Modrinth'te tapılmadı (öz mod yaki başqa saytta) — qolnen qur",
                "не вдалося визначити проєкт", "proyektni tayin etmek mümkün olmadı",
                "немає версії для Minecraft ", "Minecraft içün versiya yoq ",
                "автор заборонив завантаження сторонніми запускачами — візьми файл на сайті", "müellif üçünci taraf launcher'lerinden endirmekni yasaq etti — faylnı saytından al",
                "помилка: ", "hata: ",
                "не оброблено через скасування: ", "ret etilgeni sebebinden işlenmedi: ",
                "Не вдалося встановити:", "Qurmaq mümkün olmadı:",
                "Вгору", "Yuqarı",
                "Обрати", "Saylamaq",
                "Нічого не виділено — буде обрано поточну теку.", "Hiç bir şey saylanmağan — cari papka saylanır.",
                "Мова", "Til");
    }

    /** Гагаузька (латиниця). Чого тут немає — лишається українською. */
    private static Map<String, String> buildGag() {
        return pairs(
                "Скасовано користувачем", "Kullanıcı tarafından vazgeçildi",
                "Контрольна сума не збігається: ", "Kontrol toplamı uymaz: ",
                "Версію ", "Versiya ",
                " не знайдено (немає інтернету?)", " bulunmadı (internet yok mu?)",
                "Завантаження опису версії ", "Versiya tasvirini indirmää ",
                "  · без модів", "  · modsuz",
                "  · стабільна", "  · stabil",
                "  · бета", "  · beta",
                "  · рекомендована", "  · tavsiye edilmiş",
                "  · остання", "  · son",
                "Невідомий завантажувач: ", "Bilinmeyen yükleyici: ",
                "Fabric не підтримує версію ", "Fabric şu versiyayı desteklemez: ",
                "Fabric профіль збережено: ", "Fabric profili saklandı: ",
                "Quilt не підтримує версію ", "Quilt şu versiyayı desteklemez: ",
                "Quilt профіль збережено: ", "Quilt profili saklandı: ",
                "Завантаження Minecraft ", "Minecraft indirmää ",
                "Готово", "Hazır",
                "Завантаження профілю Fabric…", "Fabric profilini indirmää…",
                "Завантаження профілю Quilt…", "Quilt profilini indirmää…",
                "Завантаження встановлювача ", "Kurucuyu indirmää: ",
                "Встановлення ", "Kurulum ",
                " (це може зайняти кілька хвилин)…", " (bu birkaç dakika alabilir)…",
                "Встановлювач завершився з помилкою (код ", "Kurucu hata ilä bitti (kod ",
                "). Деталі дивись у Журналі.", "). Ayrıntıları Jurnaldan bak.",
                "Встановлювач відпрацював, але нову версію не знайдено в ", "Kurucu işledi, ama yeni versiya bulunmadı: ",
                "Завантаження файлів: ", "Dosyaları indirmää: ",
                "Файл Java не знайдено:\n", "Java dosyası bulunmadı:\n",
                "\nОбери Java в Налаштуваннях або очисти поле, щоб використати вбудовану.", "\nAyarlarda Java'yı seç yada launcher'ın kendi Java'sını kullanmaa için alanı temizle.",
                "Ця версія гри вимагає Java ", "Bu oyun versiyası Java ",
                "+, а запускач працює на Java ", "+ ister, ama launcher Java ",
                ".\nВстанови потрібну Java і обери її (або вкажи шлях) у Налаштуваннях.", " ilä çalışêr.\nGerekli Java'yı kur da Ayarlarda seç (yada yolunu göster).",
                "Підготовка файлів…", "Dosyalar hazırlanêr…",
                "Немає клієнтського jar для ", "Müşteri jar'ı yok: ",
                "Розпакування…", "Paketten çıkarmaa…",
                "authlib-injector недоступний: ", "authlib-injector mevcut degil: ",
                "Моди", "Modlar",
                "Пошук модів", "Modları aramaa",
                "Встановлені моди", "Kurulu modlar",
                "Тека модів", "Modlar papkası",
                "Сторінка мода", "Modun sayfası",
                "Видалити мод", "Modu silmää",
                "Версія мода (для цієї збірки)", "Modun versiyası (bu toplam için)",
                "Набори ресурсів", "Resurs paketleri",
                "Пошук наборів ресурсів", "Resurs paketlerini aramaa",
                "Встановлені набори ресурсів", "Kurulu resurs paketleri",
                "Тека наборів ресурсів", "Resurs paketleri papkası",
                "Сторінка набору ресурсів", "Resurs paketinin sayfası",
                "Видалити набір ресурсів", "Resurs paketini silmää",
                "Версія набору ресурсів (для цієї збірки)", "Resurs paketinin versiyası (bu toplam için)",
                "  · тільки з сайту", "  · yalnız saytta",
                "Автор заборонив завантаження сторонніми запускачами — візьми файл на сайті.", "Yazar üçüncü taraf launcher'lerden indirmää yasakladı — dosyayı saytından al.",
                "Залежність: ", "Bağımlılık: ",
                "Завантаження: ", "İndirmää: ",
                "Встановлено: ", "Kuruldu: ",
                "Залежність ", "Bağımlılık ",
                " недоступна автоматично — встанови вручну.", " otomatik olarak yok — elnän kur.",
                "Не вдалося поставити залежність ", "Bağımlılığı kurmaa mümkün olmadı: ",
                "1.16.5 і новіші", "1.16.5 ve daa yeni",
                "PaxPolit - це український політичний сервер в майнкрафті: танки, зброя, політика та багато чого іншого. Приєднуйся до нас в дискорді.", "PaxPolit — Minecraft'ta Ukrayna politika serveri: tanklar, silää, politika ve daa çok şey. Bizä Discord'ta katıl.",
                "PaxCiv - це підсервер PaxPolit, який існує для проведення цивілізаційних експериментів типу Ish'a тощо", "PaxCiv — PaxPolit'in alt serveri, Ish'a gibi medeniyet denemelerini yapmak için",
                "Версія ", "Versiya ",
                "Версія", "Versiya",
                "Підтримуються", "Desteklener",
                "Відкрити Discord", "Discord'u açmaa",
                "Копіювати IP", "IP'yi kopiyalamaa",
                "Закрити", "Kapamaa",
                "Завантаження новин…", "Haberleri indirmää…",
                "Новини недоступні без інтернету", "İnternetsiz haberlär mevcut degil",
                "  · збірка (", "  · toplam (",
                "  · знімок", "  · snapshot",
                "  · давня", "  · eski",
                "  · з модами", "  · modlu",
                "  · встановлено", "  · kurulu",
                "Випуски", "Relizlär",
                "Знімки", "Snapshotlar",
                "Давні (бета/альфа)", "Eski (beta/alfa)",
                "Готово до запуску", "Başlamaa hazır",
                "Грати", "Oynamaa",
                "Додати версію", "Versiya eklemää",
                "Встановити", "Kurmaa",
                "Обери версію Minecraft і завантажувач", "Minecraft versiyasını ve yükleyiciyi seç",
                "Скасувати", "Vazgeçmää",
                "Пауза", "Pauza",
                "Вбудована (Java ", "İçindeki (Java ",
                "Свій шлях…", "Kendi yol…",
                "Ховати запускач, поки триває гра", "Oyun devam ederken launcher'ı gizlemää",
                "Створи збірку і шукай моди", "Toplam yarat da modları ara",
                "Обери мод зі списку", "Listadan mod seç",
                "Зупинити", "Durdurmaa",
                "Знайти", "Aramaa",
                "Ще результати", "Daa çok sonuç",
                "Тека запускача: ", "Launcher papkası: ",
                "Ласкаво просимо!", "Hoş geldiniz!",
                "Показувати гру в Discord", "Oyunu Discord'ta göstermää",
                "У вкладці «Моди»", "«Modlar» sekmesindä",
                "Шукає й встановлює моди", "Modları arar ve kurar",
                "У налаштуваннях", "Ayarlarda",
                "Налаштовує запускач", "Launcher'ı ayarlar",
                "У журналі", "Jurnalda",
                "Читає записи гри", "Oyun kayıtlarını okur",
                "Додає версію", "Versiya ekler",
                "Обирає завантажувач", "Yükleyiciyi seçer",
                "На головній сторінці", "Baş sayfada",
                "Обирає версію", "Versiyayı seçer",
                "Журнал", "Jurnal",
                "Налаштування", "Ayarlar",
                "Головна", "Baş sayfa",
                "Не вдалося відкрити теку: ", "Papkayı açmaa mümkün olmadı: ",
                "Приєднатися до нашого Discord", "Discord'umuza katılmaa",
                "Підтримати запускач", "Launcher'ı desteklemää",
                "Грає як", "Oyuncu",
                "Швидкі дії", "Çabuk işlär",
                "Тека гри", "Oyun papkası",
                "Сервери Minecraft", "Minecraft serverleri",
                "Новини Minecraft", "Minecraft haberleri",
                "Відкрий для себе  ›", "Keşfet  ›",
                "Остання версія", "Son versiya",
                "Запускай свіжий випуск Minecraft: Java Edition одним натисканням — версії завантажуються самі.", "Minecraft: Java Edition'ın en yeni relizini bir tıklamayla başlat — versiyalar kendi kendinä iner.",
                "Моди та збірки", "Modlar ve toplamlar",
                "Шукай моди та набори ресурсів на Modrinth і став їх у власну збірку прямо із запускача.", "Modrinth'ta modları ve resurs paketlerini ara da onnarı launcher'dan direkt kendi toplamına ekle.",
                "Встанови завантажувач модів для будь-якої версії гри — і він з'явиться у списку версій.", "Oyunun herhangi bir versiyası için mod yükleyicisini kur — o versiyalar listasında görünär.",
                "Завантажувачі", "Yükleyiciler",
                "Версії", "Versiyalar",
                "Налаштуй під себе", "Kendinä göre ayarla",
                "Пам'ять, власна Java, роздільна здатність вікна, тека гри та стан у Discord.", "Hafıza, kendi Java'n, pencerenin ölçüsü, oyun papkası ve Discord'taki durum.",
                "Темна", "Karanlık",
                "Світла", "Aydınnık",
                "Свій колір…", "Kendi renk…",
                "Колір теми", "Tema rengi",
                "Застосувати", "Uygulamaa",
                "Огляд", "Bakmaa",
                "Обери java (або теку JDK)", "java seç (yada JDK papkası)",
                "Шлях до java (або до теки JDK). Порожньо — вбудована Java запускача.", "java'ya yol (yada JDK papkasına). Boş — launcher'ın içindeki Java.",
                "Додаткові JVM-аргументи", "Ek JVM argumentleri",
                "Виділена пам'ять (ОЗП)", "Ayrılmış hafıza (RAM)",
                "Роздільна здатність вікна", "Pencere çözünürlüğü",
                "Ігрова тека (saves, mods)", "Oyun papkası (saves, mods)",
                "Поведінка", "Davranış",
                "Зберегти", "Saklamaa",
                "Збережено, але файл Java не знайдено: ", "Saklandı, ama Java dosyası bulunmadı: ",
                "Налаштування збережено", "Ayarlar saklandı",
                "Тема інтерфейсу", "İnterfeys teması",
                "Очистити", "Temizlemää",
                "Завантаження списку версій…", "Versiyalar listasını indirmää…",
                "Не вдалося отримати список версій: ", "Versiyalar listasını almaa mümkün olmadı: ",
                "Продовжити", "Devam etmää",
                "Завантаження на паузі", "İndirmää pauzada",
                "Продовжую завантаження…", "İndirmää devam ederim…",
                "Скасовую завантаження…", "İndirmää vazgeçerim…",
                "Список версій ще завантажується — спробуй за мить", "Versiyalar listası daa iner — birazdan dene",
                "Обрано останній випуск: ", "Son reliz seçildi: ",
                "Останній випуск: ", "Son reliz: ",
                " — його ще немає у списку", " — o daa listada yok",
                "Зачекай…", "Bekle…",
                "Назад", "Geri",
                "Версія Minecraft", "Minecraft versiyası",
                "Завантажувач", "Yükleyici",
                "Версія завантажувача", "Yükleyici versiyası",
                "Завантаження списку версій ", "Versiyalar listasını indirmää: ",
                "Не вдалося отримати список: ", "Listayı almaa mümkün olmadı: ",
                "Доступно версій: ", "Mevcut versiyalar: ",
                " не підтримує Minecraft ", " Minecraft'ı desteklemez: ",
                "Обери версію Minecraft, завантажувач і його версію.", "Minecraft versiyasını, yükleyiciyi ve onun versiyasını seç.",
                "Завантаження файлів гри для ", "Oyun dosyalarını indirmää: ",
                "Попереднє завантаження файлів не вдалося (буде при запуску): ", "Dosyaları önceden indirmää olmadı (başlayanda olacek): ",
                " встановлено: ", " kuruldu: ",
                ". Натискай «Грати»!", ". «Oynamaa» tuşuna bas!",
                "Встановлення скасовано", "Kurulum vazgeçildi",
                "Завантаження скасовано", "İndirmää vazgeçildi",
                "Помилка встановлення: ", "Kurulum hatası: ",
                "Помилка встановлення", "Kurulum hatası",
                "Не вдалося встановити ", "Kurmaa mümkün olmadı: ",
                "Відкрий вручну: ", "Elnän aç: ",
                "Збірок ще немає — натисни «Нова збірка».", "Daa toplam yok — «Yeni toplam»ı bas.",
                "Чиста збірка не підтримує моди. Створи збірку на основі Fabric / Quilt / Forge / NeoForge (набори ресурсів працюють і в чистій).", "Saf toplam modları desteklemez. Fabric / Quilt / Forge / NeoForge temelinde toplam yarat (resurs paketleri saf toplamda da çalışêr).",
                "  ·  версія ", "  ·  versiya ",
                "Список версій Minecraft ще завантажується — спробуй за мить.", "Minecraft versiyaları listası daa iner — birazdan dene.",
                "Обрати версію гри й завантажувач", "Oyun versiyasını ve yükleyiciyi seçmää",
                "Взяти вже встановлену версію", "Zaten kurulu versiyayı kullanmaa",
                "Встановлена версія", "Kurulu versiya",
                "Назва збірки", "Toplam adı",
                "Звідки взяти версію", "Versiyayı nereden almaa",
                "Нова збірка", "Yeni toplam",
                "Створити", "Yaratmaa",
                "Назва: до 32 символів — літери, цифри, пробіл, _ - .", "Ad: 32 işaretä kadar — harfler, sayılar, boşluk, _ - .",
                "Збірка з такою назвою вже є.", "Bu adlı toplam var.",
                "Обери встановлену версію.", "Kurulu versiyayı seç.",
                "Створено збірку «", "Toplam yaratıldı «",
                "Збірка «", "Toplam «",
                "» створена й з'явилась у списку версій", "» yaratıldı da versiyalar listasına eklendi",
                "» з'явилась у списку версій", "» versiyalar listasına eklendi",
                "Створення збірки скасовано", "Toplam yaratmaa vazgeçildi",
                "Помилка створення збірки: ", "Toplam yaratma hatası: ",
                "Не вдалося створити збірку", "Toplamı yaratmaa mümkün olmadı",
                "Не вдалося створити збірку:\n", "Toplamı yaratmaa mümkün olmadı:\n",
                "Видалити збірку", "Toplamı silmää",
                "Видалити збірку «", "Toplamı silmää «",
                "» разом з модами та світами?\n", "» modlar ve dünyalar ilä birlikte?\n",
                "Видалити", "Silmää",
                "Не вдалося видалити теку: ", "Papkayı silmää mümkün olmadı: ",
                "Зупиняю завантаження…", "İndirmää durdururım…",
                " завант.", " indir.",
                "   (вимкнено)", "   (kapalı)",
                "Вимкнути / увімкнути", "Kapatmaa / açmaa",
                "Назад до пошуку", "Aramaya geri",
                "Відкрити на сайті", "Saytta açmaa",
                "Спочатку створи збірку.", "Önce toplam yarat.",
                "Обери зі списку", "Listadan seç",
                " завантажень", " indirmää",
                "  ·  Є для: ", "  ·  Var: ",
                "Спочатку створи збірку («Нова збірка»).", "Önce toplam yarat («Yeni toplam»).",
                "Чиста збірка не підтримує моди.\nСтвори збірку на основі Fabric / Quilt / Forge / NeoForge.", "Saf toplam modları desteklemez.\nFabric / Quilt / Forge / NeoForge temelinde toplam yarat.",
                "Пошук: ", "Arama: ",
                " на Modrinth…", " Modrinth'ta…",
                "Помилка пошуку: ", "Arama hatası: ",
                "Нічого не знайдено для ", "Hiç bir şey bulunmadı: ",
                "Знайдено: ", "Bulundu: ",
                "Завантаження версій…", "Versiyaları indirmää…",
                "Помилка: ", "Hata: ",
                "Версій для ", "Versiyalar için ",
                "Немає файлів для ", "Dosyalar yok: ",
                " — не підтримує цю збірку", " — bu toplamı desteklemez",
                "Автор заборонив завантаження сторонніми запускачами.\n", "Yazar üçüncü taraf launcher'lerden indirmää yasakladı.\n",
                "Завантаж файл на сайті й поклади в теку збірки (кнопка «", "Dosyayı saytından indir da toplam papkasına koy (tuş «",
                " у збірку «", " toplama «",
                "Встановлення скасовано.", "Kurulum vazgeçildi.",
                "Не вдалося встановити:\n", "Kurmaa mümkün olmadı:\n",
                "Не вдалося перейменувати: ", "Yeniden adlandırmaa mümkün olmadı: ",
                "Видалити ", "Silmää ",
                "Не вдалося видалити: ", "Silmää mümkün olmadı: ",
                "Увійти через Microsoft", "Microsoft ilä girmää",
                "Грати без ліцензії", "Lisenziyasız oynamaa",
                "Ласкаво просимо, ", "Hoş geldiniz, ",
                "Ім'я користувача або е-пошта", "Kullanıcı adı yada e-poşta",
                "Пароль", "Parola",
                "Код двоетапної перевірки (лише якщо ввімкнено)", "İki basamaklı kontrol kodu (yalnız açıksa)",
                "Вхід через Ely.by", "Ely.by ilä giriş",
                "Увійти", "Girmää",
                "Введи ім'я користувача й пароль.", "Kullanıcı adını ve parolayı yaz.",
                "Ely.by: вхід як ", "Ely.by: şu adlan girildi: ",
                "Не вдалося увійти через Ely.by:\n", "Ely.by ilä girmää mümkün olmadı:\n",
                "Не вдалося почати вхід через Microsoft:\n", "Microsoft ilä girişi başlamaa mümkün olmadı:\n",
                "Відкрий сторінку й введи цей код:", "Sayfayı aç da bu kodu yaz:",
                "Очікую підтвердження…", "Onay beklener…",
                "Відкрити сторінку", "Sayfayı açmaa",
                "Копіювати код", "Kodu kopiyalamaa",
                "Microsoft: вхід як ", "Microsoft: şu adlan girildi: ",
                "Не вдалося увійти через Microsoft:\n", "Microsoft ilä girmää mümkün olmadı:\n",
                "Вхід через Microsoft", "Microsoft ilä giriş",
                "Додати", "Eklemää",
                "Ім'я гравця: 3–16 символів — латиниця, цифри, _", "Oyuncu adı: 3–16 işaret — Latin harfleri, sayılar, _",
                "Ім'я гравця має містити 3–16 символів: латиниця, цифри, _", "Oyuncu adı 3–16 işaretten ibaret olmalı: Latin harfleri, sayılar, _",
                "Така обліківка вже є", "Bu hesap zaten var",
                "Додано: ", "Eklendi: ",
                "Спочатку обери обліківку у списку", "Önce listadan hesap seç",
                "Видалено: ", "Silindi: ",
                "Неліцензійні обліківки", "Lisenziyasız hesaplar",
                "Грати з цією обліківкою", "Bu hesapnan oynamaa",
                "Обери обліківку у списку або додай нову.", "Listadan hesap seç yada yenisini ekle.",
                "Спочатку обери обліківку.", "Önce hesap seç.",
                "Спочатку обери версію гри.", "Önce oyun versiyasını seç.",
                "Запуск: ", "Başlatma: ",
                "Версія: ", "Versiya: ",
                "  ·  завантажувач: ", "  ·  yükleyici: ",
                "Перевірка обліківки…", "Hesabı kontrol etmää…",
                "Гра запущена. Приємної гри!", "Oyun başladı. İyi eglenceler!",
                "Грає в Minecraft ", "Minecraft oynêr ",
                "Чиста гра · через PaxLauncher", "Saf oyun · PaxLauncher üstündän",
                " · збірка «", " · toplam «",
                " · через PaxLauncher", " · PaxLauncher üstündän",
                "Гра завершилась з кодом ", "Oyun kod ilä bitti ",
                "Гру закрито", "Oyun kapandı",
                "Гра завершилась з помилкою (код ", "Oyun hata ilä bitti (kod ",
                "), дивись Журнал", "), Jurnala bak",
                "ПОМИЛКА: ", "HATA: ",
                "Помилка запуску", "Başlatma hatası",
                "Не вдалося запустити гру:\n", "Oyunu başlatmaa mümkün olmadı:\n",
                "Автономна", "Oflayn",
                "Обліківку захищено двоетапною перевіркою — введи код у поле «Код двоетапної перевірки».", "Hesap iki basamaklı kontrolnan korunêr — kodu «İki basamaklı kontrol kodu» alanına yaz.",
                "Неправильне ім'я користувача або пароль.", "Kullanıcı adı yada parola yanlış.",
                "В обліківці Ely.by немає профілю гравця.", "Ely.by hesabında oyuncu profili yok.",
                "Сесія Ely.by закінчилась — увійди знову («Змінити обліківку»).", "Ely.by seansı bitti — yeniden gir («Hesabı deñiştirmää»).",
                "Скасовано", "Vazgeçildi",
                "Час очікування вийшов.", "Bekleme zamanı bitti.",
                "Сесія Microsoft закінчилась — увійди знову («Змінити обліківку»).", "Microsoft seansı bitti — yeniden gir («Hesabı deñiştirmää»).",
                "До цієї обліківки Microsoft не прив'язано профіль Xbox.", "Bu Microsoft hesabına Xbox profili bağlanmamış.",
                "Дитяча обліківка: її треба додати до сім'ї Microsoft.", "Çocuk hesabı: onu Microsoft ailesinä eklemää gerek.",
                "Minecraft відхилив застосунок (403). Client ID має бути схвалений Mojang: aka.ms/AppRegistration.", "Minecraft uygulamayı reddetti (403). Client ID Mojang tarafından onaylanmalı: aka.ms/AppRegistration.",
                "На цій обліківці Microsoft немає Minecraft Java Edition.", "Bu Microsoft hesabında Minecraft Java Edition yok.",
                "Профіль Minecraft: HTTP ", "Minecraft profili: HTTP ",
                "Потрібен вхід через Microsoft і Client ID у Налаштуваннях.", "Microsoft ilä giriş ve Ayarlarda Client ID gerek.",
                "Discord Rich Presence підключено", "Discord Rich Presence bağlandı",
                "Імпорт модів", "Modları aktarmaa",
                "Імпорт", "Aktarmaa",
                "Обери теку збірки (або теку mods) чи окремі .jar", "Toplam papkasını (yada mods papkasını) yada ayrı .jar'ları seç",
                "Файлів .jar не знайдено.", ".jar dosyaları bulunmadı.",
                "у збірці без завантажувача моди не працюють", "yükleyicisiz toplamda modlar çalışmaz",
                "не знайдено на Modrinth (власний мод або з іншого сайту) — постав вручну", "Modrinth'ta bulunmadı (kendi mod yada başka saytta) — elnän kur",
                "не вдалося визначити проєкт", "projeyi belirlemää mümkün olmadı",
                "немає версії для Minecraft ", "Minecraft için versiya yok ",
                "автор заборонив завантаження сторонніми запускачами — візьми файл на сайті", "yazar üçüncü taraf launcher'lerden indirmää yasakladı — dosyayı saytından al",
                "помилка: ", "hata: ",
                "не оброблено через скасування: ", "vazgeçildiği için işlenmedi: ",
                "Не вдалося встановити:", "Kurmaa mümkün olmadı:",
                "Вгору", "Yukarı",
                "Обрати", "Seçmää",
                "Нічого не виділено — буде обрано поточну теку.", "Hiç bir şey seçilmedi — şimdiki papka seçilir.",
                "Мова", "Dil");
    }
}
