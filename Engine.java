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

final class Engine {
    interface Progress { void update(String text, double frac); }

    record VInfo(String id, String type, String url) { }

    record Task(String url, Path dest, String sha1, boolean lazy) {
        Task(String url, Path dest, String sha1) { this(url, dest, sha1, false); }
    }

    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String RES = "https://resources.download.minecraft.net/";
    static final String LIBS = "https://libraries.minecraft.net/";

    final Path root;
    final Consumer<String> log;
    final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    final Map<String, String> urls = new ConcurrentHashMap<>();
    final Map<String, String> shas = new ConcurrentHashMap<>();
    volatile List<VInfo> manifest = new ArrayList<>();
    volatile String latestRelease = "";
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

    static String osName() {
        String n = System.getProperty("os.name").toLowerCase();
        if (n.contains("win")) return "windows";
        if (n.contains("mac") || n.contains("darwin")) return "osx";
        return "linux";
    }

    static boolean is64() { return System.getProperty("os.arch").contains("64"); }

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

    static final int MAX_FETCH = 64 * 1024 * 1024;

    static String httpsOnly(String url) throws IOException {
        if (url == null || url.isEmpty()) throw new IOException("empty url");
        if (url.regionMatches(true, 0, "http://", 0, 7)) url = "https://" + url.substring(7);
        URI u;
        try { u = URI.create(url); } catch (IllegalArgumentException e) { throw new IOException("bad url: " + url); }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) throw new IOException("only https allowed: " + url);
        return url;
    }

    static final Pattern ID_RE = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._+\\- ()]{0,127}");
    static String safeId(String id) throws IOException {
        if (id == null || !ID_RE.matcher(id).matches() || id.contains("..") || id.endsWith(" ") || id.endsWith("."))
            throw new IOException("Некоректний ідентифікатор версії: " + id);
        return id;
    }

    static Path under(Path base, String rel) throws IOException {
        if (rel == null || rel.isEmpty()) throw new IOException("empty path");
        Path b = base.toAbsolutePath().normalize();
        Path p;
        try { p = b.resolve(rel).normalize(); } catch (java.nio.file.InvalidPathException e) { throw new IOException("bad path: " + rel); }
        if (!p.startsWith(b) || p.equals(b)) throw new IOException("шлях виходить за межі " + base + ": " + rel);
        return p;
    }

    static final Pattern MAIN_RE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private static final Set<String> TAKES_VALUE = Set.of("-p", "--module-path", "-cp", "-classpath", "--class-path",
            "--add-modules", "--add-opens", "--add-exports", "--add-reads", "--limit-modules");
    private static final Set<String> BAD_PROPS = Set.of("java.system.class.loader", "java.security.manager", "java.security.policy",
            "java.security.auth.login.config", "java.ext.dirs", "java.endorsed.dirs", "java.class.path", "sun.boot.class.path",
            "javax.net.ssl.truststore", "javax.net.ssl.keystore", "javax.net.ssl.truststorepassword", "javax.net.ssl.keystorepassword",
            "jdk.attach.allowattachself", "sun.java.command");
    private static final Pattern JVM_MEM = Pattern.compile("-X(mx|ms|mn|ss)\\d{1,8}[kKmMgG]?");
    private static final Pattern JVM_MISC = Pattern.compile("-XstartOnFirstThread|-Xdock:(name|icon)=.{1,200}");
    private static final Pattern JVM_MODULE = Pattern.compile("--(add-opens|add-exports|add-reads|add-modules|limit-modules|module-path|class-path)(=.+)?|-p|-cp|-classpath");
    private static final Pattern JVM_XX = Pattern.compile("-XX:[+-]?(UseG1GC|UseZGC|UseShenandoahGC|UseParallelGC|UseSerialGC|UseConcMarkSweepGC|UseStringDeduplication"
            + "|G1\\w+|MaxGCPauseMillis|ParallelGCThreads|ConcGCThreads|MaxMetaspaceSize|MetaspaceSize|DisableExplicitGC|AlwaysPreTouch"
            + "|ParallelRefProcEnabled|HeapDumpOnOutOfMemoryError|PerfDisableSharedMem|OmitStackTraceInFastThrow|UseCompressedOops"
            + "|Tiered\\w*|MaxTenuringThreshold|SurvivorRatio|NewRatio|NewSize|MaxNewSize|InitiatingHeapOccupancyPercent|MinHeapFreeRatio"
            + "|MaxHeapFreeRatio|ReservedCodeCacheSize|UseNUMA|UseLargePages|IgnoreUnrecognizedVMOptions|UnlockExperimentalVMOptions"
            + "|UseCodeCacheFlushing|ShowCodeDetailsInExceptionMessages|DontCompileHugeMethods)(=[A-Za-z0-9_.\\-]{1,16})?"
            + "|-XX:HeapDumpPath=[A-Za-z0-9_.\\-]{1,128}");

    static boolean jvmArgAllowed(String a) {
        if (a == null || a.isEmpty()) return false;
        if (a.startsWith("-D")) {
            int eq = a.indexOf('=');
            String k = a.substring(2, eq < 0 ? a.length() : eq).toLowerCase(Locale.ROOT);
            return !k.isEmpty() && !BAD_PROPS.contains(k) && !k.startsWith("com.sun.management.") && !k.startsWith("jdk.internal.");
        }
        return JVM_MEM.matcher(a).matches() || JVM_MISC.matcher(a).matches()
                || JVM_MODULE.matcher(a).matches() || JVM_XX.matcher(a).matches();
    }

    String fetch(String url) throws IOException, InterruptedException {
        url = httpsOnly(url);
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "PaxLauncher/1.0").timeout(Duration.ofSeconds(30)).build();
        HttpResponse<java.io.InputStream> resp = http.send(r, HttpResponse.BodyHandlers.ofInputStream());
        try (java.io.InputStream in = resp.body()) {
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " — " + url);
            byte[] b = in.readNBytes(MAX_FETCH + 1);
            if (b.length > MAX_FETCH) throw new IOException("відповідь завелика — " + url);
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    static String sha1(Path f) throws Exception { return digest(f, "SHA-1"); }

    static String hashFor(Path f, String expected) throws Exception {
        int n = expected == null ? 0 : expected.length();
        return digest(f, n == 128 ? "SHA-512" : n == 64 ? "SHA-256" : "SHA-1");
    }

    static String digest(Path f, String algo) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algo);
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

    static final class JavaMissing extends IOException {
        final int major;
        JavaMissing(int major) { super(I18n.t("Потрібна Java ") + major); this.major = major; }
    }

    Path managedJava(int n) {
        Path d = root.resolve("runtime").resolve("java-" + n);
        if (!Files.isDirectory(d)) return null;
        String exe = osName().equals("windows") ? "java.exe" : "java";
        try (var st = Files.walk(d, 7)) {
            return st.filter(x -> x.getFileName() != null && x.getFileName().toString().equals(exe)
                    && x.getParent() != null && x.getParent().getFileName() != null
                    && x.getParent().getFileName().toString().equals("bin")).findFirst().orElse(null);
        } catch (IOException e) { return null; }
    }

    String autoJava(int need) throws IOException {
        List<JavaFinder.Inst> all = JavaFinder.cached();
        if (need <= 0) {
            for (JavaFinder.Inst i : all) if (i.major() > 0) return i.path();
            Path mj = managedJava(17);
            if (mj != null) return mj.toString();
            String own = ownRuntimeJava(0);
            if (own != null) return own;
            throw new JavaMissing(17);
        }
        for (JavaFinder.Inst i : all) if (i.major() == need) return i.path();
        Path mj = managedJava(need);
        if (mj != null) return mj.toString();
        JavaFinder.Inst best = null;
        for (JavaFinder.Inst i : all) if (i.major() > need && (best == null || i.major() < best.major())) best = i;
        if (best != null) return best.path();
        String own = ownRuntimeJava(need);
        if (own != null) return own;
        throw new JavaMissing(need);
    }

    private String ownRuntimeJava(int need) {
        if (Runtime.version().feature() < need) return null;
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        for (String n : new String[]{"java.exe", "java", "javaw.exe"}) {
            Path p = bin.resolve(n);
            if (Files.isRegularFile(p)) return p.toString();
        }
        return null;
    }

    static void deleteTree(Path p) {
        if (!Files.exists(p)) return;
        try (var w = Files.walk(p)) { w.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete()); } catch (IOException ignored) { }
    }

    Path downloadJava(int n, Progress pg) throws Exception {
        Path have = managedJava(n);
        if (have != null) return have;
        Path base = root.resolve("runtime");
        Files.createDirectories(base);
        boolean zip = osName().equals("windows");
        String os = osName().equals("osx") ? "mac" : osName();
        String arch = isArm() ? "aarch64" : is64() ? "x64" : "x32";
        String api = "https://api.adoptium.net/v3/assets/latest/" + n + "/hotspot?architecture=" + arch
                + "&image_type=jre&os=" + os + "&vendor=eclipse";
        String url = null, sum = null;
        String apiBody = null;
        Exception apiErr = null;
        for (int t = 0; t < 4 && apiBody == null; t++) {
            if (stop) throw new Cancelled();
            if (t > 0) { try { Thread.sleep(700L * t); } catch (InterruptedException ie) { throw new Cancelled(); } }
            try { apiBody = fetch(api); } catch (Exception ex) { apiErr = ex; }
        }
        if (apiBody == null) throw new IOException(I18n.t("Не вдалося отримати відомості про Java з api.adoptium.net.") + "\n" + apiErr);
        for (Object o : l(Json.parse(apiBody))) {
            Map<String, Object> pk = m(g(o, "binary", "package"));
            if (pk == null) continue;
            String link = s(pk.get("link")), cs = s(pk.get("checksum"));
            if (link == null || cs == null) continue;
            if (zip ? link.endsWith(".zip") : link.endsWith(".tar.gz")) { url = link; sum = cs; break; }
        }
        if (url == null || !url.startsWith("https://") || sum == null || !sum.matches("[0-9a-fA-F]{64}"))
            throw new IOException(I18n.t("Не вдалося отримати контрольну суму Java — відмовляюсь ставити неперевірений файл."));
        Path part = base.resolve("java-" + n + (zip ? ".zip" : ".tar.gz") + ".part");
        String title = I18n.t("Завантаження Java ") + n + "…";
        pg.update(title, 0.02);
        Exception lastErr = null;
        boolean got = false;
        for (int t = 0; t < 8 && !got; t++) {
            if (stop) { Files.deleteIfExists(part); throw new Cancelled(); }
            if (t > 0) { try { Thread.sleep(Math.min(5000L, 800L * t)); } catch (InterruptedException ie) { throw new Cancelled(); } }
            try {
                long have0 = Files.exists(part) ? Files.size(part) : 0;
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "PaxLauncher/1.0")
                        .timeout(Duration.ofMinutes(10));
                if (have0 > 0) rb.header("Range", "bytes=" + have0 + "-");
                HttpResponse<java.io.InputStream> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (java.io.InputStream in = resp.body()) {
                    int sc = resp.statusCode();
                    if (sc != 200 && sc != 206) {
                        if (sc == 416) Files.deleteIfExists(part);
                        throw new IOException("HTTP " + sc + " — Java " + n);
                    }
                    boolean append = sc == 206 && have0 > 0;
                    long done = append ? have0 : 0;
                    long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (total > 0 && append) total += have0;
                    java.nio.file.OpenOption[] oo = append
                            ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND}
                            : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE};
                    try (java.io.OutputStream out = Files.newOutputStream(part, oo)) {
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = in.read(buf)) > 0) {
                            if (stop) throw new Cancelled();
                            waitPause(() -> stop);
                            out.write(buf, 0, k);
                            done += k;
                            if (total > 0) pg.update(title, 0.9 * done / total);
                        }
                    }
                }
                got = true;
            } catch (Cancelled c) {
                Files.deleteIfExists(part);
                throw c;
            } catch (Exception ex) {
                lastErr = ex;
            }
        }
        if (!got) {
            Files.deleteIfExists(part);
            throw new IOException(I18n.t("З'єднання обірвалось під час завантаження Java. Перевір інтернет і спробуй ще раз.") + "\n" + lastErr);
        }
        if (!digest(part, "SHA-256").equalsIgnoreCase(sum)) {
            Files.deleteIfExists(part);
            throw new IOException(I18n.t("Контрольна сума не збігається: ") + url);
        }
        pg.update(I18n.t("Розпакування…"), 0.92);
        Path tmp = base.resolve("java-" + n + ".tmp");
        deleteTree(tmp);
        Files.createDirectories(tmp);
        try {
            if (zip) {
                try (ZipInputStream z = new ZipInputStream(Files.newInputStream(part))) {
                    ZipEntry ze;
                    while ((ze = z.getNextEntry()) != null) {
                        Path out = tmp.resolve(ze.getName()).normalize();
                        if (!out.startsWith(tmp)) continue;
                        if (ze.isDirectory()) { Files.createDirectories(out); continue; }
                        Files.createDirectories(out.getParent());
                        Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } else {
                Process pr = new ProcessBuilder("tar", "--no-same-owner", "-xzf", part.toString(), "-C", tmp.toString()).redirectErrorStream(true).start();
                pr.getInputStream().readAllBytes();
                if (pr.waitFor() != 0) throw new IOException(I18n.t("Не вдалося розпакувати Java"));
            }
            Path fin = base.resolve("java-" + n);
            deleteTree(fin);
            Files.move(tmp, fin);
        } finally {
            deleteTree(tmp);
            Files.deleteIfExists(part);
        }
        Path exe = managedJava(n);
        if (exe == null) throw new IOException(I18n.t("Не вдалося розпакувати Java"));
        exe.toFile().setExecutable(true);
        pg.update(I18n.t("Готово"), 1);
        return exe;
    }

    static final class Cancelled extends IOException {
        Cancelled() { super(I18n.t("Скасовано користувачем")); }
    }

    void download(String url, Path dest, String sha1) throws Exception {
        download(url, dest, sha1, () -> stop);
    }

    void download(String url, Path dest, String sha1, java.util.function.BooleanSupplier cancel) throws Exception {
        download(url, dest, sha1, cancel, false);
    }

    void download(String url, Path dest, String sha1, java.util.function.BooleanSupplier cancel, boolean trustExisting) throws Exception {
        if (Files.exists(dest) && Files.size(dest) > 0 && (sha1 == null || trustExisting || hashFor(dest, sha1).equalsIgnoreCase(sha1))) return;
        url = httpsOnly(url);
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".part");
        Exception last = null;
        for (int t = 0; t < 5; t++) {
            if (cancel.getAsBoolean()) throw new Cancelled();
            waitPause(cancel);
            if (t > 0) { try { Thread.sleep(400L * t); } catch (InterruptedException ie) { throw new Cancelled(); } }
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
                if (sha1 != null && !hashFor(tmp, sha1).equalsIgnoreCase(sha1)) throw new IOException(I18n.t("Контрольна сума не збігається: ") + url);
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
        throw new IOException(url + " — " + last, last);
    }

    List<VInfo> loadManifest() throws Exception {
        Map<String, Object> mf = m(Json.parse(fetch(MANIFEST)));
        List<VInfo> out = new ArrayList<>();
        for (Object o : l(mf.get("versions"))) {
            Map<String, Object> v = m(o);
            String id = s(v.get("id"));
            out.add(new VInfo(id, s(v.get("type")), s(v.get("url"))));
            urls.put(id, s(v.get("url")));
            String vsha = s(v.get("sha1"));
            if (vsha != null && !vsha.isEmpty()) shas.put(id, vsha);
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

    Map<String, Object> loadVersion(String id) throws Exception {
        safeId(id);
        Path jf = root.resolve("versions").resolve(id).resolve(id + ".json");
        if (!Files.exists(jf)) {
            String url = urls.get(id);
            if (url == null) throw new IOException(I18n.t("Версію ") + id + I18n.t(" не знайдено (немає інтернету?)"));
            log.accept(I18n.t("Завантаження опису версії ") + id);
            Files.createDirectories(jf.getParent());
            download(url, jf, shas.get(id));
        }
        Map<String, Object> v = m(Json.parse(Files.readString(jf)));
        String parent = s(v.get("inheritsFrom"));
        if (parent == null) {
            v.put("_jar", id);
            return v;
        }
        return merge(loadVersion(parent), v);
    }

    private String libKey(Map<String, Object> lib) {
        String name = s(lib.get("name"));
        if (name == null) return null;
        String[] pr = name.split(":");
        if (pr.length < 2) return null;
        return pr[0] + ":" + pr[1] + (pr.length > 3 ? ":" + pr[3] : "");
    }

    private Map<String, Object> merge(Map<String, Object> p, Map<String, Object> c) {
        Map<String, Object> r = new LinkedHashMap<>(p);
        for (var e : c.entrySet()) {
            String k = e.getKey();
            if (k.equals("arguments") || k.equals("libraries")) continue;
            r.put(k, e.getValue());
        }
        // Бібліотеки батьківської версії (Vanilla) лишаємо як є — саме з таким списком Vanilla запускається.
        // Прибираємо лише ті, що Fabric/Forge перевизначає своїми (той самий group:artifact[:classifier]).
        Set<String> childKeys = new HashSet<>();
        List<Object> all = new ArrayList<>();
        for (Object o : l(c.get("libraries"))) {
            String k = libKey(m(o));
            if (k != null && !childKeys.add(k)) continue;
            all.add(o);
        }
        for (Object o : l(p.get("libraries"))) {
            String k = libKey(m(o));
            if (k != null && childKeys.contains(k)) continue;
            all.add(o);
        }
        Map<String, Object> uniq = new LinkedHashMap<>();
        int n = 0;
        for (Object o : all) uniq.put("#" + (n++), o);
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

    record LV(String version, String label) {
        @Override public String toString() { return label; }
    }

    private static List<String> xmlVersions(String xml) {
        List<String> out = new ArrayList<>();
        var mt = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (mt.find()) out.add(mt.group(1).trim());
        return out;
    }

    static String neoPrefix(String mc) {
        String[] p = mc.split("\\.");
        if (p.length >= 2 && p[0].equals("1")) return p[1] + "." + (p.length > 2 ? p[2] : "0") + ".";
        return null;
    }

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
        String id = safeId(s(m(Json.parse(profile)).get("id")));
        Path f = root.resolve("versions").resolve(id).resolve(id + ".json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, profile);
        log.accept(I18n.t("Fabric профіль збережено: ") + id);
        return id;
    }

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
        String id = safeId(s(m(Json.parse(profile)).get("id")));
        Path f = root.resolve("versions").resolve(id).resolve(id + ".json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, profile);
        log.accept(I18n.t("Quilt профіль збережено: ") + id);
        return id;
    }

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
        pg.update(I18n.t("Завантаження Minecraft ") + mc + "…", 0.1);
        Map<String, Object> v = loadVersion(mc);
        Map<String, Object> client = m(g(v, "downloads", "client"));
        if (client != null)
            download(s(client.get("url")), root.resolve("versions").resolve(mc).resolve(mc + ".jar"), s(client.get("sha1")));
        Path profiles = root.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) Files.writeString(profiles, "{\"profiles\":{},\"version\":3}");

        pg.update(I18n.t("Завантаження встановлювача ") + loader + "…", 0.25);
        Path inst = root.resolve("installers").resolve(name);
        download(url, inst, remoteHash(url));

        pg.update(I18n.t("Встановлення ") + loader + I18n.t(" (це може зайняти кілька хвилин)…"), 0.45);
        Set<String> before = new HashSet<>(installedIds());
        String java = javaPath;
        if (java.isEmpty()) {
            int need = 0;
            try { Object nv = g(v, "javaVersion", "majorVersion"); if (nv instanceof Number) need = ((Number) nv).intValue(); } catch (Exception ignored) { }
            java = autoJava(need);
        }
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
        if (code != 0) throw new IOException(I18n.t("Встановлювач завершився з помилкою (код ") + code + I18n.t("). Деталі дивись у Консолі."));
        pg.update(I18n.t("Готово"), 1);

        List<String> after = installedIds();
        for (String id : after) if (!before.contains(id)) return id;
        String tail = loader.equals("Forge") ? ver.substring(Math.min(ver.length(), mc.length() + 1)) : ver;
        for (String id : after) if (id.contains(tail)) return id;
        throw new IOException(I18n.t("Встановлювач відпрацював, але нову версію не знайдено в ") + root.resolve("versions"));
    }

    String remoteHash(String url) throws IOException {
        for (String ext : new String[]{".sha256", ".sha1"}) {
            try {
                String b = fetch(url + ext).trim();
                int sp = b.indexOf(' ');
                if (sp > 0) b = b.substring(0, sp);
                if (b.matches("[0-9a-fA-F]{64}") || b.matches("[0-9a-fA-F]{40}")) return b.toLowerCase();
            } catch (Exception ignored) { }
        }
        throw new IOException(I18n.t("Не вдалося отримати контрольну суму встановлювача — відмовляюсь запускати неперевірений файл."));
    }

    private String guessSha1(String url, Path dest) {
        try { if (Files.isRegularFile(dest) && Files.size(dest) > 0) return null; } catch (IOException ignored) { }
        try {
            String b = fetch(url + ".sha1").trim();
            int sp = b.indexOf(' ');
            if (sp > 0) b = b.substring(0, sp);
            if (b.matches("[0-9a-fA-F]{40}")) return b.toLowerCase();
        } catch (Exception ignored) { }
        return null;
    }

    Path authlib(String pin) throws Exception {
        String want = pin == null ? "" : pin.trim().toLowerCase(Locale.ROOT);
        if (!want.isEmpty() && !want.matches("[0-9a-f]{64}"))
            throw new IOException(I18n.t("authlibSha256 у launcher.properties має бути SHA-256 (64 hex-символи)."));
        Path f = root.resolve("authlib-injector.jar");
        Path sc = root.resolve("authlib-injector.jar.sha256");
        if (Files.isRegularFile(f) && Files.size(f) > 0 && Files.isRegularFile(sc)) {
            String have = Files.readString(sc).trim();
            if (have.matches("[0-9a-fA-F]{64}") && digest(f, "SHA-256").equalsIgnoreCase(have)
                    && (want.isEmpty() || want.equalsIgnoreCase(have))) return f;
        }
        Map<String, Object> j = m(Json.parse(fetch("https://authlib-injector.yushi.moe/artifact/latest.json")));
        String dl = s(j.get("download_url"));
        Map<String, Object> cks = m(j.get("checksums"));
        String sum = cks == null ? null : s(cks.get("sha256"));
        if (dl == null || !dl.startsWith("https://") || sum == null || !sum.matches("[0-9a-fA-F]{64}"))
            throw new IOException(I18n.t("Немає контрольної суми authlib-injector — відмовляюсь завантажувати."));
        if (!want.isEmpty() && !want.equalsIgnoreCase(sum))
            throw new IOException(I18n.t("Контрольна сума authlib-injector не збігається із закріпленою (authlibSha256)."));
        String host = URI.create(dl).getHost();
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        boolean known = host.equals("yushi.moe") || host.endsWith(".yushi.moe") || host.equals("github.com") || host.endsWith(".githubusercontent.com");
        if (!known && want.isEmpty())
            throw new IOException(I18n.t("Невідомий хост завантаження authlib-injector (") + host + I18n.t(") — вкажи очікувану SHA-256 в authlibSha256 (launcher.properties)."));
        Files.deleteIfExists(f);
        download(dl, f, sum);
        Files.writeString(sc, sum.toLowerCase(Locale.ROOT));
        return f;
    }

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

    static String mavenPath(String name) throws IOException {
        if (name == null || name.contains("..") || name.contains("/") || name.contains("\\")) throw new IOException("bad library name: " + name);
        String[] p = name.split(":");
        if (p.length < 3) throw new IOException("bad library name: " + name);
        String cl = p.length > 3 ? "-" + p[3] : "";
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + cl + ".jar";
    }

    private void runTasks(List<Task> tasks, Progress pg) throws Exception {
        if (tasks.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger done = new AtomicInteger();
        int total = tasks.size();
        List<Future<Object>> fs = new ArrayList<>();
        for (Task t : tasks) {
            fs.add(pool.submit(() -> {
                String sh = t.sha1();
                if ("?".equals(sh)) {
                    boolean have = Files.isRegularFile(t.dest()) && Files.size(t.dest()) > 0;
                    sh = have ? null : guessSha1(t.url(), t.dest());
                    if (!have && sh == null)
                        throw new IOException(I18n.t("Немає контрольної суми для ") + t.url() + I18n.t(" — відмовляюсь завантажувати неперевірений файл."));
                }
                download(t.url(), t.dest(), sh, () -> stop, t.lazy());
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

    List<String> build(Map<String, Object> v, Settings cfg, Accounts.Acc acc, Progress pg, Path gameDir) throws Exception {
        String id = safeId(s(v.get("id"))), jar = safeId(s(v.get("_jar")));
        String mainClass = s(v.get("mainClass"));
        if (mainClass == null || !MAIN_RE.matcher(mainClass).matches())
            throw new IOException(I18n.t("Некоректний mainClass в описі версії: ") + mainClass);
        Path vroot = root.resolve("versions"), libRoot = root.resolve("libraries"), assetsRoot = root.resolve("assets");
        Files.createDirectories(gameDir);

        String javaPath = cfg.get("java", "").trim();
        if (!javaPath.isEmpty() && (javaPath.contains("/") || javaPath.contains("\\")) && !Files.isRegularFile(Path.of(javaPath)))
            throw new IOException(I18n.t("Файл Java не знайдено:\n") + javaPath + I18n.t("\nОбери Java в Налаштуваннях або вибери «Автоматично»."));
        String javaExe = javaPath;
        if (javaPath.isEmpty()) {
            Object need = g(v, "javaVersion", "majorVersion");
            javaExe = autoJava(need instanceof Number ? ((Number) need).intValue() : 0);
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
                Path f = under(libRoot, s(art.get("path")));
                String url = s(art.get("url"));
                if (url != null && !url.isEmpty()) tasks.add(new Task(url, f, s(art.get("sha1"))));
                cp.add(f);
                String lname = s(lib.get("name"));
                String[] lp = lname == null ? new String[0] : lname.split(":");
                if (lp.length > 3 && lp[3].startsWith("natives-") && !nativeJars.contains(f)) nativeJars.add(f);
            } else if (lib.get("downloads") == null && s(lib.get("name")) != null) {
                String path = mavenPath(s(lib.get("name")));
                String base = s(lib.get("url"));
                if (base == null) base = LIBS;
                if (!base.endsWith("/")) base += "/";
                Path f = under(libRoot, path);
                tasks.add(new Task(base + path, f, "?"));
                cp.add(f);
            }
            Map<String, Object> nat = m(lib.get("natives"));
            if (nat != null) {
                String cls = s(nat.get(osName()));
                if (cls != null) {
                    cls = cls.replace("${arch}", is64() ? "64" : "32");
                    Map<String, Object> na = m(g(lib, "downloads", "classifiers", cls));
                    if (na != null) {
                        Path f = under(libRoot, s(na.get("path")));
                        tasks.add(new Task(s(na.get("url")), f, s(na.get("sha1"))));
                        nativeJars.add(f);
                    }
                }
            }
        }
        cp.add(clientJar);

        Map<String, Object> ai = m(v.get("assetIndex"));
        String aid = s(ai.get("id"));
        if (aid == null || !aid.matches("[A-Za-z0-9._\\-]{1,64}") || aid.contains(".."))
            throw new IOException(I18n.t("Некоректний id індексу ресурсів: ") + aid);
        Path idxFile = assetsRoot.resolve("indexes").resolve(aid + ".json");
        download(s(ai.get("url")), idxFile, s(ai.get("sha1")));
        Map<String, Object> idx = m(Json.parse(Files.readString(idxFile)));
        Map<String, Object> objs = m(idx.get("objects"));
        Set<String> seen = new HashSet<>();
        for (var e : objs.entrySet()) {
            String h = s(m(e.getValue()).get("hash"));
            if (h == null || !h.matches("[0-9a-fA-F]{40}")) throw new IOException(I18n.t("Некоректний хеш ресурсу: ") + h);
            if (seen.add(h)) {
                String pre = h.substring(0, 2);
                tasks.add(new Task(RES + pre + "/" + h, assetsRoot.resolve("objects").resolve(pre).resolve(h), h, true));
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
                Path dst = under(virt ? vd : gameDir.resolve("resources"), e.getKey());
                if (!Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
            if (virt) gameAssets = vd;
        }

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
                    try {
                        Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException busy) {
                        if (!Files.exists(out)) throw busy;
                    }
                }
            }
        }

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
        cmd.add(javaExe);
        int ram = cfg.geti("ram", 4096);
        cmd.add("-Xmx" + ram + "M");
        cmd.add("-Xms" + Math.min(ram, 1024) + "M");
        if (acc.type().equals("ely")) {
            try { cmd.add("-javaagent:" + authlib(cfg.get("authlibSha256", "")) + "=ely.by"); }
            catch (Exception e) { log.accept(I18n.t("authlib-injector недоступний: ") + e.getMessage()); }
        }
        String extra = cfg.get("jvm", "").trim();
        if (!extra.isEmpty()) cmd.addAll(Arrays.asList(extra.split("\\s+")));

        Map<String, Object> args = m(v.get("arguments"));
        if (args != null) {
            List<String> jv = expand(l(args.get("jvm")), ph, feats);
            boolean valueNext = false;
            for (int ji = 0; ji < jv.size(); ji++) {
                String a = jv.get(ji);
                if (valueNext) { valueNext = false; cmd.add(a); continue; }
                if (jvmArgAllowed(a)) {
                    cmd.add(a);
                    valueNext = TAKES_VALUE.contains(a);
                    continue;
                }
                log.accept(I18n.t("Пропущено JVM-аргумент з опису версії (не у білому списку): ") + a);
                if (a.startsWith("--") && !a.contains("=") && ji + 1 < jv.size() && !jv.get(ji + 1).startsWith("-")) ji++;
            }
            cmd.add(mainClass);
            cmd.addAll(expand(l(args.get("game")), ph, feats));
        } else {
            cmd.add("-Djava.library.path=" + nd);
            cmd.add("-cp");
            cmd.add(cpStr);
            cmd.add(mainClass);
            for (String a : sub(s(v.get("minecraftArguments")), ph).split(" ")) if (!a.isEmpty()) cmd.add(a);
            if (rw > 0 && rh > 0) { cmd.add("--width"); cmd.add(String.valueOf(rw)); cmd.add("--height"); cmd.add(String.valueOf(rh)); }
        }
        if (rw > 0 && rh > 0 && !cmd.contains("--width")) { cmd.add("--width"); cmd.add(String.valueOf(rw)); cmd.add("--height"); cmd.add(String.valueOf(rh)); }
        return cmd;
    }
}
