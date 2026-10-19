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

final class ModApi {
    enum Kind {
        MOD("mod", "mods", I18n.t("Моди"), I18n.t("Пошук модів"), I18n.t("Встановлені моди"), I18n.t("Папка модів"),
                I18n.t("Сторінка мода"), I18n.t("Видалити мод"), I18n.t("Версія мода (для цієї збірки)"), 6, "mc-mods"),
        PACK("resourcepack", "resourcepacks", I18n.t("Набори ресурсів"), I18n.t("Пошук наборів ресурсів"), I18n.t("Встановлені набори ресурсів"), I18n.t("Папка наборів ресурсів"),
                I18n.t("Сторінка набору ресурсів"), I18n.t("Видалити набір ресурсів"), I18n.t("Версія набору ресурсів (для цієї збірки)"), 12, "texture-packs"),
        SHADER("shader", "shaderpacks", I18n.t("Шейдери"), I18n.t("Пошук шейдерів"), I18n.t("Встановлені шейдери"), I18n.t("Папка шейдерів"),
                I18n.t("Сторінка шейдера"), I18n.t("Видалити шейдер"), I18n.t("Версія шейдера (для цієї збірки)"), 6552, "shaders"),
        WORLD("world", "saves", I18n.t("Мапи"), I18n.t("Пошук мап"), I18n.t("Встановлені мапи"), I18n.t("Папка мап"),
                I18n.t("Сторінка мапи"), I18n.t("Видалити мапу"), I18n.t("Версія мапи (для цієї збірки)"), 17, "worlds"),
        MODPACK("modpack", "", I18n.t("Збірки модів"), I18n.t("Пошук збірок модів"), I18n.t("Встановлені збірки"), I18n.t("Папка збірки"),
                I18n.t("Сторінка збірки"), I18n.t("Видалити збірку"), I18n.t("Версія збірки"), 4471, "modpacks");

        final String type, folder, title, searchTitle, installedTitle, folderBtn, pageBtn, removeBtn, versionLabel, cfPath;
        final int cfClass;

        Kind(String type, String folder, String title, String searchTitle, String installedTitle, String folderBtn,
             String pageBtn, String removeBtn, String versionLabel, int cfClass, String cfPath) {
            this.cfClass = cfClass; this.cfPath = cfPath;
            this.type = type; this.folder = folder; this.title = title; this.searchTitle = searchTitle;
            this.installedTitle = installedTitle; this.folderBtn = folderBtn; this.pageBtn = pageBtn;
            this.removeBtn = removeBtn; this.versionLabel = versionLabel;
        }

        boolean isMod() { return this == MOD; }

        boolean isWorld() { return this == WORLD; }

        /** Модові збірки (modpack): ставляться у вже існуючу збірку (профіль), яку вибрано зверху. */
        boolean isPack() { return this == MODPACK; }

        /** Мапи на Modrinth відсутні — їх беруть лише з CurseForge. */
        boolean onModrinth() { return this != WORLD; }

        /** Що можна «переносити» (імпортувати) зі старих збірок: моди (.jar), набори ресурсів і шейдери (.zip). */
        boolean importable() { return this != WORLD && this != MODPACK; }

        boolean matches(String fileName) {
            String n = fileName.toLowerCase(Locale.ROOT);
            if (n.endsWith(".disabled")) n = n.substring(0, n.length() - 9);
            if (isWorld()) return !fileName.startsWith(".");
            return isMod() ? n.endsWith(".jar") : n.endsWith(".zip");
        }

        @Override public String toString() { return title; }
    }

    enum Source {
        MODRINTH("Modrinth"), CURSEFORGE("CurseForge");

        final String label;

        Source(String label) { this.label = label; }

        @Override public String toString() { return label; }

        static Source parse(String v) { return "CURSEFORGE".equalsIgnoreCase(v) ? CURSEFORGE : MODRINTH; }
    }

    record Hit(Kind kind, String id, String slug, String title, String author, String desc,
               long downloads, String loaders, String iconUrl) {
        @Override public String toString() { return title; }

        Source source() { return CurseForge.isCf(id) ? Source.CURSEFORGE : Source.MODRINTH; }

        String pageUrl() {
            String sl = slug == null ? "" : slug.replaceAll("[^A-Za-z0-9._-]", "");
            return source() == Source.CURSEFORGE
                    ? "https://www.curseforge.com/minecraft/" + kind.cfPath + "/" + sl
                    : "https://modrinth.com/" + kind.type + "/" + sl;
        }
    }

    record Detail(String id, String title, String desc, String body, long downloads, long followers,
                  String updated, String client, String server) { }

    private final Map<String, Detail> detailCache = new ConcurrentHashMap<>();

    Detail project(String id) throws Exception {
        Detail c = detailCache.get(id);
        if (c != null) return c;
        if (CurseForge.isCf(id)) {
            Detail cd = cf.project(id);
            detailCache.put(id, cd);
            return cd;
        }
        Map<String, Object> h = m(Json.parse(get(MR + "/project/" + enc(id))));
        if (h == null) throw new IOException("bad project");
        Detail d = new Detail(str(h.get("id")), str(h.get("title")), str(h.get("description")), str(h.get("body")),
                num(h.get("downloads")), num(h.get("followers")), str(h.get("updated")),
                str(h.get("client_side")), str(h.get("server_side")));
        detailCache.put(id, d);
        return d;
    }

    static String excerpt(String md, int max) {
        if (md == null) return "";
        String t = md.replace("\r", "");
        t = t.replaceAll("(?s)<!--.*?-->", "");
        t = t.replaceAll("(?s)```.*?```", "");
        t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("(?i)<br\\s*/?>", "\n");
        t = t.replaceAll("<[^>]+>", "");
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        t = t.replaceAll("(?m)^\\s*([-*_]\\s*){3,}$", "");
        t = t.replaceAll("(?m)^\\s*\\|.*\\|\\s*$", "");
        t = t.replaceAll("\\*\\*|__|`", "");
        t = t.replaceAll("(?m)^\\s*[-*+]\\s+", "• ");
        t = t.replaceAll("https?://\\S+", "");
        t = t.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (t.length() > max) {
            int cut = t.lastIndexOf("\n\n", max);
            if (cut < max / 2) cut = t.lastIndexOf(". ", max) + 1;
            if (cut < max / 2) cut = max;
            t = t.substring(0, cut).trim() + "…";
        }
        return t;
    }

    /** mcs / loaders — версії гри та завантажувачі, для яких зроблено файл (заповнюється для модових збірок). */
    record ModFile(String name, String filename, String url, String sha1, String channel, String date, List<String> deps,
                   List<String> mcs, List<String> loaders) {
        ModFile(String name, String filename, String url, String sha1, String channel, String date, List<String> deps) {
            this(name, filename, url, sha1, channel, date, deps, List.of(), List.of());
        }
        @Override public String toString() {
            return name + "  · " + channel + (date.isEmpty() ? "" : "  · " + date) + (url == null ? I18n.t("  · тільки з сайту") : "");
        }
    }

    static final String MR = "https://api.modrinth.com/v2";
    static final int PAGE = 30;

    final Engine eng;
    final Map<String, String> iconByProject = new ConcurrentHashMap<>();

    final CurseForge cf;

    ModApi(Engine eng) { this.eng = eng; this.cf = new CurseForge(this); }

    Map<String, String> iconUrls(Collection<String> ids) throws Exception {
        Map<String, String> out = new HashMap<>();
        List<String> need = new ArrayList<>(), needCf = new ArrayList<>();
        for (String id : ids) {
            String c = iconByProject.get(id);
            if (c != null) out.put(id, c); else if (CurseForge.isCf(id)) needCf.add(id); else need.add(id);
        }
        if (!needCf.isEmpty()) { try { out.putAll(cf.logos(needCf)); } catch (IOException ignored) { } }
        for (int i = 0; i < need.size(); i += 50) {
            List<String> part = need.subList(i, Math.min(need.size(), i + 50));
            for (Object o : l(Json.parse(get(MR + "/projects?ids=" + enc(jsonArr(part)))))) {
                Map<String, Object> h = m(o);
                String pid = str(h.get("id")), ic = str(h.get("icon_url"));
                iconByProject.put(pid, ic);
                out.put(pid, ic);
            }
        }
        return out;
    }

    private static Path onDisk(Path dir, String base) {
        Path f = dir.resolve(base);
        if (Files.isRegularFile(f)) return f;
        f = dir.resolve(base + ".disabled");
        return Files.isRegularFile(f) ? f : null;
    }

    private static String sha1(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    Map<String, String> identify(Path dir, Collection<String> files) {
        Map<String, String> out = new HashMap<>();
        Properties cache = new Properties();
        Path cf = dir.resolve(".pax-icons.properties");
        if (Files.exists(cf)) try (Reader r = Files.newBufferedReader(cf, StandardCharsets.UTF_8)) { cache.load(r); } catch (IOException ignored) { }
        Map<String, String> byHash = new LinkedHashMap<>();
        Map<String, String> keyOf = new HashMap<>();
        for (String f : files) {
            Path p = onDisk(dir, f);
            if (p == null) continue;
            try {
                String ck = f + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
                String v = cache.getProperty(ck);
                if (v != null) { if (!v.equals("-")) out.put(f, v); continue; }
                keyOf.put(f, ck);
                byHash.put(sha1(p), f);
            } catch (Exception ignored) { }
        }
        if (byHash.isEmpty()) return out;
        boolean changed = false;
        List<String> hs = new ArrayList<>(byHash.keySet());
        for (int i = 0; i < hs.size(); i += 100) {
            List<String> part = hs.subList(i, Math.min(hs.size(), i + 100));
            try {
                String body = "{\"hashes\":" + jsonArr(part) + ",\"algorithm\":\"sha1\"}";
                HttpRequest rq = HttpRequest.newBuilder(URI.create(MR + "/version_files"))
                        .header("User-Agent", "PaxLauncher/1.0").header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> r = eng.sendRetry(rq, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) continue;
                Map<String, Object> res = m(Json.parse(r.body()));
                if (res == null) continue;
                for (String h : part) {
                    String f = byHash.get(h);
                    Map<String, Object> v = m(res.get(h));
                    String pid = v == null ? "" : str(v.get("project_id"));
                    cache.setProperty(keyOf.get(f), pid.isEmpty() ? "-" : pid);
                    if (!pid.isEmpty()) out.put(f, pid);
                    changed = true;
                }
            } catch (Exception ignored) { }
        }
        if (changed) try (Writer w = Files.newBufferedWriter(cf, StandardCharsets.UTF_8)) { cache.store(w, "PaxLauncher icons"); } catch (IOException ignored) { }
        return out;
    }

    private static String zipText(java.util.zip.ZipFile z, String name) {
        try {
            java.util.zip.ZipEntry e = z.getEntry(name);
            if (e == null || e.isDirectory()) return null;
            try (InputStream in = z.getInputStream(e)) {
                return new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static void pickIcon(Object ic, List<String> out) {
        if (ic instanceof String) { out.add((String) ic); return; }
        Map<String, Object> mp = m(ic);
        if (mp == null) return;
        String best = null;
        int bestSz = -1;
        for (var e : mp.entrySet()) {
            if (!(e.getValue() instanceof String)) continue;
            int sz;
            try { sz = Integer.parseInt(e.getKey().trim()); } catch (NumberFormatException ex) { sz = 0; }
            if (sz > bestSz) { bestSz = sz; best = (String) e.getValue(); }
        }
        if (best != null) out.add(best);
    }

    static BufferedImage embeddedIcon(Path file, Kind kind) {
        if (file == null) return null;
        if (Files.isDirectory(file)) {
            try {
                Path ic = file.resolve("icon.png");
                if (!Files.isRegularFile(ic) || Files.size(ic) > 8_000_000) return null;
                return readImage(Files.readAllBytes(ic));
            } catch (Throwable e) { return null; }
        }
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(file.toFile())) {
            List<String> cand = new ArrayList<>();
            if (kind.isMod()) {
                try {
                    String t = zipText(z, "fabric.mod.json");
                    if (t != null) { Map<String, Object> j = m(Json.parse(t)); if (j != null) pickIcon(j.get("icon"), cand); }
                } catch (Exception ignored) { }
                try {
                    String t = zipText(z, "quilt.mod.json");
                    if (t != null) pickIcon(Json.get(Json.parse(t), "quilt_loader", "metadata", "icon"), cand);
                } catch (Exception ignored) { }
                for (String toml : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                    String t = zipText(z, toml);
                    if (t == null) continue;
                    java.util.regex.Matcher mt = Pattern.compile("(?m)^\\s*logoFile\\s*=\\s*[\"']([^\"']+)[\"']").matcher(t);
                    if (mt.find()) cand.add(mt.group(1));
                }
                String mi = zipText(z, "mcmod.info");
                if (mi != null) {
                    java.util.regex.Matcher mt = Pattern.compile("\"logoFile\"\\s*:\\s*\"([^\"]+)\"").matcher(mi);
                    if (mt.find()) cand.add(mt.group(1));
                }
            }
            cand.add("pack.png");
            if (kind.isMod()) {
                cand.add("icon.png");
                cand.add("logo.png");
                for (java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    String n = en.nextElement().getName();
                    if (n.matches("assets/[^/]+/(icon|logo)\\.png")) { cand.add(n); break; }
                }
            }
            for (String c : cand) {
                String name = c.startsWith("/") ? c.substring(1) : c;
                java.util.zip.ZipEntry e = z.getEntry(name);
                if (e == null || e.isDirectory() || e.getSize() > 8_000_000) continue;
                try (InputStream in = z.getInputStream(e)) {
                    byte[] data = in.readNBytes(8_000_001);
                    if (data.length > 8_000_000) continue;
                    BufferedImage im = readImage(data);
                    if (im != null) return im;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    static Map<String, String> fileToProject(Path dir, Kind kind) {
        Map<String, String> r = new HashMap<>();
        Properties p = loadReg(dir);
        String pre = kind.type + ":";
        for (String k : p.stringPropertyNames()) if (k.startsWith(pre)) r.put(p.getProperty(k), k.substring(pre.length()));
        return r;
    }

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
        HttpRequest rq = b.build();
        HttpResponse<String> r = eng.sendRetry(rq, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 429) {   // Modrinth обмежує частоту запитів — чекаємо трохи й пробуємо ще раз
            long wait = 1500;
            try { wait = Math.min(5000, Long.parseLong(r.headers().firstValue("Retry-After").orElse("1")) * 1000); } catch (Exception ignored) { }
            Thread.sleep(wait);
            r = eng.sendRetry(rq, HttpResponse.BodyHandlers.ofString());
        }
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return r.body();
    }

    private final Map<String, String> trCache = new ConcurrentHashMap<>();
    private volatile long trFailUntil;
    private volatile boolean trDirty;
    private volatile boolean trLoaded;

    private static String esc2(String v) { return v.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", ""); }

    private static String unesc2(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    private synchronized void loadTranslations() {
        if (trLoaded) return;
        trLoaded = true;
        String lang = I18n.descLang();
        if (lang == null) return;
        Path f = cacheDir("tr").resolve("desc-" + lang + ".tsv");
        try {
            if (Files.isRegularFile(f))
                for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    int t = ln.indexOf('\t');
                    if (t > 0) trCache.put(unesc2(ln.substring(0, t)), unesc2(ln.substring(t + 1)));
                }
        } catch (Throwable ignored) { }
        Thread w = new Thread(() -> {
            while (true) {
                try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
                if (!trDirty) continue;
                trDirty = false;
                try {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> e : trCache.entrySet())
                        sb.append(esc2(e.getKey())).append('\t').append(esc2(e.getValue())).append('\n');
                    Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
                } catch (Throwable ignored) { }
            }
        }, "tr-save");
        w.setDaemon(true);
        w.start();
    }

    /** Текст уже мовою інтерфейсу (для української — кирилиця без суто російських літер). */
    private static boolean alreadyTarget(String t) {
        if (!"uk".equals(I18n.descLang())) return false;
        int cyr = 0, lat = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("ыэъёЫЭЪЁ".indexOf(c) >= 0) return false;
            if (c >= '\u0400' && c <= '\u04FF') cyr++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) lat++;
        }
        return cyr > lat;
    }

    void preloadTranslations() {
        if (!trLoaded) loadTranslations();
    }

    String cached(String text) {
        if (text == null) return "";
        String c = trCache.get(text);
        return c != null ? c : text;
    }

    static volatile boolean translateOn = true;

    boolean needsTranslation(String text) {
        if (!trLoaded) loadTranslations();
        return translateOn && I18n.descLang() != null && text != null && !text.isBlank() && !trCache.containsKey(text) && !alreadyTarget(text);
    }

    /** Ділить довгий текст на шматки (по абзацах і реченнях), щоб запит до перекладача не був задовгим. */
    private static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("(?<=\n)")) {
            while (line.length() > max) {
                int cut = line.lastIndexOf(". ", max);
                if (cut < max / 3) cut = line.lastIndexOf(' ', max);
                if (cut < max / 3) cut = max - 1;
                String head = line.substring(0, cut + 1);
                line = line.substring(cut + 1);
                if (cur.length() + head.length() > max && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                cur.append(head);
            }
            if (cur.length() + line.length() > max && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            cur.append(line);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static final class TrTemp extends IOException {
        TrTemp(String m) { super(m); }
    }

    /** Один запит до перекладача. null — цей шматок перекласти неможливо (не повторювати); TrTemp — тимчасова помилка. */
    private String translateChunk(String part) throws IOException {
        try {
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + I18n.descLang() + "&dt=t&q=" + enc(part);
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 PaxLauncher/1.0").timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> r = eng.http.send(rq, HttpResponse.BodyHandlers.ofString());
            int sc = r.statusCode();
            if (sc == 429 || sc >= 500) throw new TrTemp("HTTP " + sc);
            if (sc != 200) return null;
            List<Object> root = l(Json.parse(r.body()));
            StringBuilder sb = new StringBuilder();
            if (!root.isEmpty()) {
                for (Object seg : l(root.get(0))) {
                    List<Object> sl = l(seg);
                    if (!sl.isEmpty() && sl.get(0) instanceof String) sb.append((String) sl.get(0));
                }
            }
            String out = sb.toString();
            return out.isBlank() ? null : out;
        } catch (TrTemp e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrTemp("interrupted");
        } catch (IOException e) {
            throw new TrTemp(String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    String translate(String text) {
        if (I18n.descLang() == null) return text == null ? "" : text;
        if (text == null || text.isBlank()) return text == null ? "" : text;
        String c = trCache.get(text);
        if (c != null) return c;
        if (!translateOn) return text;
        if (alreadyTarget(text)) { trCache.put(text, text); return text; }
        if (System.currentTimeMillis() < trFailUntil) return text;
        StringBuilder all = new StringBuilder();
        boolean any = false;
        for (String part : chunks(text, 900)) {
            if (part.isBlank()) { all.append(part); continue; }
            String out = null;
            try {
                out = translateChunk(part);
            } catch (TrTemp e) {
                try { out = translateChunk(part); } catch (TrTemp e2) {
                    trFailUntil = System.currentTimeMillis() + 5_000;
                    return text;
                } catch (IOException e2) { return text; }
            } catch (IOException e) { return text; }
            if (out == null) all.append(part); else { all.append(out); any = true; }
        }
        String res = any ? all.toString().trim() : text;
        if (res.isEmpty()) res = text;
        if (trCache.size() > 20000) trCache.clear();
        trCache.put(text, res);
        if (any) trDirty = true;
        return res;
    }

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

    private final Map<String, java.util.concurrent.CompletableFuture<List<Hit>>> searchCache = new ConcurrentHashMap<>();
    private final Map<String, Long> searchTime = new ConcurrentHashMap<>();

    List<Hit> search(Source src, Kind kind, String q, String mc, String loader, int offset) throws Exception {
        if (src == Source.MODRINTH && !kind.onModrinth())
            throw new IOException(I18n.t("Цієї категорії на Modrinth немає — обери CurseForge."));
        String key = src.name() + "|" + kind.type + "|" + q + "|" + mc + "|" + loader + "|" + offset;
        Long at = searchTime.get(key);
        if (at != null && System.currentTimeMillis() - at > 1_200_000) { searchCache.remove(key); searchTime.remove(key); }
        java.util.concurrent.CompletableFuture<List<Hit>> have = searchCache.get(key);
        if (have != null) {
            try { return new ArrayList<>(have.get(40, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) { searchCache.remove(key, have); }
        }
        java.util.concurrent.CompletableFuture<List<Hit>> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<List<Hit>> prev = searchCache.putIfAbsent(key, mine);
        if (prev != null) {
            try { return new ArrayList<>(prev.get(40, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) { return searchNet(src, kind, q, mc, loader, offset); }
        }
        boolean first = offset == 0 && q.isEmpty();
        if (first) {
            long[] age = new long[1];
            List<Hit> d = diskLoad(kind, key, age);
            if (d != null) {
                searchTime.put(key, System.currentTimeMillis());
                mine.complete(d);
                if (age[0] > 6 * 3600_000L) {
                    Thread t = new Thread(() -> {
                        try {
                            List<Hit> fresh = searchNet(src, kind, q, mc, loader, 0);
                            diskSave(key, fresh);
                            searchCache.put(key, java.util.concurrent.CompletableFuture.completedFuture(fresh));
                            searchTime.put(key, System.currentTimeMillis());
                        } catch (Throwable ignored) { }
                    }, "mods-refresh");
                    t.setDaemon(true);
                    t.start();
                }
                return new ArrayList<>(d);
            }
        }
        try {
            List<Hit> r = searchNet(src, kind, q, mc, loader, offset);
            if (first) diskSave(key, r);
            searchTime.put(key, System.currentTimeMillis());
            mine.complete(r);
            return new ArrayList<>(r);
        } catch (Exception e) {
            mine.completeExceptionally(e);
            searchCache.remove(key, mine);
            throw e;
        }
    }

    static String sha1s(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(text.hashCode()); }
    }

    Path cacheDir(String sub) {
        Path d = eng.root.resolve("cache").resolve(sub);
        try { Files.createDirectories(d); } catch (IOException ignored) { }
        return d;
    }

    private static String flat(String v) { return v == null ? "" : v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    void diskSave(String key, List<Hit> hits) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(System.currentTimeMillis()).append('\n');
            for (Hit h : hits) {
                sb.append(flat(h.id())).append('\t').append(flat(h.slug())).append('\t').append(flat(h.title())).append('\t')
                  .append(flat(h.author())).append('\t').append(flat(h.desc())).append('\t').append(h.downloads()).append('\t')
                  .append(flat(h.loaders())).append('\t').append(flat(h.iconUrl())).append('\n');
            }
            Path f = cacheDir("search").resolve(sha1s(key) + ".tsv");
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) { }
    }

    List<Hit> diskLoad(Kind kind, String key, long[] age) {
        try {
            Path f = cacheDir("search").resolve(sha1s(key) + ".tsv");
            if (!Files.isRegularFile(f)) return null;
            List<String> ls = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (ls.size() < 2) return null;
            long at = Long.parseLong(ls.get(0).trim());
            if (System.currentTimeMillis() - at > 7L * 24 * 3600_000L) return null;
            age[0] = System.currentTimeMillis() - at;
            List<Hit> out = new ArrayList<>();
            for (int i = 1; i < ls.size(); i++) {
                String[] c = ls.get(i).split("\t", -1);
                if (c.length < 8) return null;
                out.add(new Hit(kind, c[0], c[1], c[2], c[3], c[4], Long.parseLong(c[5]), c[6], c[7]));
                if (!c[7].isEmpty()) iconByProject.put(c[0], c[7]);
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable e) { return null; }
    }

    void warm() {
        try { get(MR + "/"); } catch (Throwable ignored) { }
        try { cf.available(); } catch (Throwable ignored) { }
    }

    private List<Hit> searchNet(Source src, Kind kind, String q, String mc, String loader, int offset) throws Exception {
        if (src == Source.CURSEFORGE) return cf.search(kind, q, mc, loader, offset);
        StringBuilder f = new StringBuilder("[[\"project_type:" + kind.type + "\"]" + (kind.isPack() ? "" : ",[\"versions:" + mc + "\"]"));
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
                    str(h.get("author")), str(h.get("description")), num(h.get("downloads")), String.join(", ", ld),
                    str(h.get("icon_url"))));
            if (!str(h.get("icon_url")).isEmpty()) iconByProject.put(str(h.get("project_id")), str(h.get("icon_url")));
        }
        return out;
    }

    List<ModFile> files(Kind kind, String id, String mc, String loader) throws Exception {
        if (CurseForge.isCf(id)) return cf.files(kind, id, mc, loader);
        String url = MR + "/project/" + id + "/version" + (kind.isPack() ? "" : "?game_versions=" + enc("[\"" + mc + "\"]"));
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
            if (kind.isPack()) {
                Map<String, Object> mr = null;
                for (Object fo : fl) {
                    Map<String, Object> x = m(fo);
                    if (x != null && str(x.get("filename")).toLowerCase(Locale.ROOT).endsWith(".mrpack") && (mr == null || Boolean.TRUE.equals(x.get("primary")))) mr = x;
                }
                if (mr == null) continue;
                file = mr;
            }
            String channel = str(v.get("version_type"));
            String date = str(v.get("date_published"));
            List<String> deps = new ArrayList<>();
            for (Object d : l(v.get("dependencies"))) {
                Map<String, Object> dm = m(d);
                if ("required".equals(s(dm.get("dependency_type"))) && s(dm.get("project_id")) != null)
                    deps.add(s(dm.get("project_id")));
            }
            List<String> mcs = new ArrayList<>(), lds = new ArrayList<>();
            if (kind.isPack()) {
                for (Object g : l(v.get("game_versions"))) if (!str(g).isEmpty()) mcs.add(str(g));
                for (Object g : l(v.get("loaders"))) if (!str(g).isEmpty()) lds.add(str(g).toLowerCase(Locale.ROOT));
            }
            out.add(new ModFile(packName(kind, v), str(file.get("filename")), s(file.get("url")),
                    bestHash(m(file.get("hashes"))),
                    channel.isEmpty() ? "release" : channel, date.length() >= 10 ? date.substring(0, 10) : "", deps, mcs, lds));
        }
        return out;
    }

    /** Для модпаків у назві версії показуємо ще й версію гри та завантажувач. */
    private static String packName(Kind kind, Map<String, Object> v) {
        String name = str(v.get("version_number"));
        if (!kind.isPack()) return name;
        List<Object> gv = l(v.get("game_versions"));
        String mcv = gv.isEmpty() ? "" : str(gv.get(gv.size() - 1));
        StringBuilder ld = new StringBuilder();
        for (Object g : l(v.get("loaders"))) {
            String x = str(g);
            if (x.isEmpty()) continue;
            if (ld.length() > 0) ld.append('/');
            ld.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1));
        }
        return name + (mcv.isEmpty() && ld.length() == 0 ? "" : "  · " + mcv + (ld.length() > 0 ? " " + ld : ""));
    }

    private static String bestHash(Map<String, Object> h) {
        if (h == null) return null;
        String x = s(h.get("sha512"));
        if (x != null && x.matches("[0-9a-fA-F]{128}")) return x;
        x = s(h.get("sha1"));
        return x != null && x.matches("[0-9a-fA-F]{40}") ? x : null;
    }

    record ImportRow(String file, String title, String reason) {
        boolean ok() { return reason == null; }
    }

    static final long IMPORT_MAX_BYTES = 512L << 20;

    static ModFile pickBest(List<ModFile> fs) {
        for (ModFile x : fs) if (x.url() != null && x.channel().equals("release")) return x;
        for (ModFile x : fs) if (x.url() != null) return x;
        return null;
    }

    ImportRow importJar(Path jar, String mc, String loader, Path dir, Set<String> seen,
                        Consumer<String> status, java.util.function.BooleanSupplier cancel) throws Exception {
        return importFile(Kind.MOD, jar, mc, loader, dir, seen, status, cancel);
    }

    /**
     * Перенесення чужого файлу (мод .jar / набір ресурсів .zip / шейдер .zip) у збірку:
     * файл не копіюється «як є» — за його хешем шукається проєкт на Modrinth (а якщо нема — на CurseForge за відбитком),
     * і з офіційного джерела ставиться версія, яка підходить саме цій збірці. Недовірені / невідомі файли пропускаються.
     */
    ImportRow importFile(Kind kind, Path file, String mc, String loader, Path dir, Set<String> seen,
                         Consumer<String> status, java.util.function.BooleanSupplier cancel) throws Exception {
        String fn = file.getFileName().toString();
        String title = fn;
        try {
            if (!kind.importable())
                return new ImportRow(fn, title, I18n.t("цей тип не переноситься"));
            if (kind.isMod() && modrinthLoaders(loader).isEmpty())
                return new ImportRow(fn, title, I18n.t("у збірці без завантажувача моди не працюють"));
            if (!Files.isRegularFile(file) || !kind.matches(fn))
                return new ImportRow(fn, title, kind.isMod() ? I18n.t("це не файл .jar") : I18n.t("це не файл .zip"));
            if (Files.size(file) > IMPORT_MAX_BYTES)
                return new ImportRow(fn, title, I18n.t("файл завеликий (понад 512 МБ)"));
            status.accept(I18n.t("Пошук: ") + fn);
            String hash = Engine.sha1(file);

            String pid = null;
            try {
                Map<String, Object> ver = m(Json.parse(get(MR + "/version_file/" + hash + "?algorithm=sha1")));
                if (ver != null) pid = str(ver.get("project_id"));
            } catch (IOException e) {
                if (!String.valueOf(e.getMessage()).contains("404")) throw e;
            }

            ModFile pick = null;
            String useId = null;
            boolean known = false;
            if (pid != null && !pid.isEmpty()) {
                known = true;
                try {
                    String t = str(m(Json.parse(get(MR + "/project/" + pid))).get("title"));
                    if (!t.isEmpty()) title = t;
                } catch (IOException ignored) { }
                List<ModFile> fs = files(kind, pid, mc, loader);
                pick = pickBest(fs);
                if (pick != null) useId = pid;
                else if (fs.isEmpty()) useId = null;
                else useId = "";
            }
            if (pick == null && cf.available()) {
                try {
                    status.accept(I18n.t("Пошук на CurseForge: ") + fn);
                    String cid = cf.identify(file);
                    if (cid != null) {
                        known = true;
                        try { String t = cf.project(cid).title(); if (!t.isEmpty() && title.equals(fn)) title = t; } catch (IOException ignored) { }
                        List<ModFile> fs = files(kind, cid, mc, loader);
                        ModFile cp = pickBest(fs);
                        if (cp != null) { pick = cp; useId = cid; }
                        else if (!fs.isEmpty() && useId == null) useId = "";
                    }
                } catch (Engine.Cancelled c) {
                    throw c;
                } catch (IOException ignored) { }
            }
            if (!known)
                return new ImportRow(fn, title, I18n.t("не знайдено на Modrinth / CurseForge (власний файл або з іншого сайту) — постав вручну"));
            if (pick == null) {
                if ("".equals(useId))
                    return new ImportRow(fn, title, I18n.t("автор заборонив завантаження сторонніми запускачами — візьми файл на сайті"));
                return new ImportRow(fn, title, I18n.t("немає версії для Minecraft ") + mc + (kind.isMod() ? " (" + loader + ")" : ""));
            }
            install(kind, useId, pick, mc, loader, dir, seen, false, status, cancel);
            return new ImportRow(pick.filename(), title, null);
        } catch (Engine.Cancelled c) {
            throw c;
        } catch (Exception e) {
            return new ImportRow(fn, title, I18n.t("помилка: ") + e.getMessage());
        }
    }

    static Properties loadReg(Path dir) {
        Properties p = new Properties();
        Path f = dir.resolve(".pax-mods.properties");
        if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) { }
        return p;
    }

    static void saveReg(Path dir, Properties p) {
        try (Writer w = Files.newBufferedWriter(dir.resolve(".pax-mods.properties"), StandardCharsets.UTF_8)) { p.store(w, "PaxLauncher mods"); }
        catch (IOException ignored) { }
    }

    static void forget(Path dir, String fileName) {
        String base = fileName.endsWith(".disabled") ? fileName.substring(0, fileName.length() - 9) : fileName;
        Properties p = loadReg(dir);
        if (p.values().removeIf(v -> v.equals(base))) saveReg(dir, p);
    }

    static String safeName(String n) {
        String s = (n == null ? "" : n).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        s = s.replaceAll("[. ]+$", "");
        if (s.isEmpty()) s = "_";
        if (s.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) s = "_" + s;
        if (s.length() > 150) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 10 ? s.substring(dot) : "";
            s = s.substring(0, 150 - ext.length()) + ext;
        }
        return s;
    }

    static Path child(Path dir, String name) throws IOException {
        Path d = dir.toAbsolutePath().normalize();
        Path p = d.resolve(name).normalize();
        if (name.isEmpty() || !d.equals(p.getParent())) throw new IOException("bad file name: " + name);
        return p;
    }

    static BufferedImage readImage(byte[] data) {
        if (data == null || data.length == 0) return null;
        try (javax.imageio.stream.ImageInputStream is = javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            java.util.Iterator<javax.imageio.ImageReader> it = javax.imageio.ImageIO.getImageReaders(is);
            if (!it.hasNext()) return null;
            javax.imageio.ImageReader r = it.next();
            try {
                r.setInput(is, true, true);
                if ((long) r.getWidth(0) * r.getHeight(0) > 16_000_000L) return null;
                return r.read(0);
            } finally { r.dispose(); }
        } catch (Throwable e) { return null; }
    }

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
        if (old != null) { try { child(dir, old); } catch (IOException bad) { old = null; } }
        if (isDep && old != null && (Files.exists(dir.resolve(old)) || Files.exists(dir.resolve(old + ".disabled")))) return;
        if (f.url() == null) throw new IOException(I18n.t("Автор заборонив завантаження сторонніми запускачами — візьми файл на сайті."));
        if (f.sha1() == null) throw new IOException(I18n.t("Немає контрольної суми файлу — відмовляюсь завантажувати неперевірений файл."));
        if (!trustedHost(f.url(), CurseForge.isCf(id)))
            throw new IOException(I18n.t("Файл лежить на недовіреному сервері — відмовляюсь завантажувати."));
        if (kind.isWorld()) { installWorld(key, f, dir, reg, status, cancel); return; }
        String fn = safeName(f.filename());
        child(dir, fn);
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

    // ---------- безпека завантажень ----------

    /** Файли дозволено брати лише з офіційних CDN: Modrinth (cdn.modrinth.com) та CurseForge (*.forgecdn.net). */
    static boolean trustedHost(String url, boolean curse) {
        try {
            String h = URI.create(Engine.httpsOnly(url)).getHost().toLowerCase(Locale.ROOT);
            if (curse) return h.equals("forgecdn.net") || h.endsWith(".forgecdn.net") || h.equals("curseforge.com") || h.endsWith(".curseforge.com");
            return h.equals("modrinth.com") || h.endsWith(".modrinth.com");
        } catch (Exception e) { return false; }
    }

    static void deleteTree(Path p) throws IOException {
        if (p == null || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(f);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ---------- мапи ----------

    private void installWorld(String key, ModFile f, Path saves, Properties reg, Consumer<String> status,
                              java.util.function.BooleanSupplier cancel) throws Exception {
        String zipName = safeName(f.filename());
        if (!zipName.toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new IOException(I18n.t("Мапа має бути архівом .zip"));
        Path tmpZip = cacheDir("worlds").resolve(Long.toHexString(System.nanoTime()) + ".zip");
        try {
            status.accept(I18n.t("Завантаження: ") + zipName);
            eng.download(f.url(), tmpZip, f.sha1(), cancel);
            status.accept(I18n.t("Розпаковування: ") + zipName);
            String folder = extractWorld(tmpZip, saves, zipName.substring(0, zipName.length() - 4), cancel);
            reg.setProperty(key, folder);
            saveReg(saves, reg);
            eng.log.accept(I18n.t("Встановлено мапу: ") + folder);
        } finally {
            try { Files.deleteIfExists(tmpZip); } catch (IOException ignored) { }
        }
    }

    static final int WORLD_MAX_ENTRIES = 100_000;
    static final long WORLD_MAX_BYTES = 4L << 30;

    /**
     * Розпаковує мапу в saves/. Захист: шляхи з «..» / абсолютні відкидаються (zip-slip), ліміти на кількість файлів
     * і розмір після розпакування (zip-bomb), наявні світи ніколи не перезаписуються (додається « (2)»), розпаковка
     * йде у тимчасову папку й переноситься на місце лише повністю.
     */
    static String extractWorld(Path zip, Path saves, String fallbackName,
                               java.util.function.BooleanSupplier cancel) throws IOException {
        Files.createDirectories(saves);
        Path tmp = null;
        boolean done = false;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            String prefix = null;
            int bestDepth = 99, count = 0;
            for (Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements();) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (++count > WORLD_MAX_ENTRIES) throw new IOException(I18n.t("В архіві забагато файлів"));
                String n = e.getName().replace('\\', '/');
                if (e.isDirectory() || n.startsWith("__MACOSX/") || !(n.equals("level.dat") || n.endsWith("/level.dat"))) continue;
                int depth = n.split("/").length - 1;
                if (depth <= 2 && depth < bestDepth) { bestDepth = depth; prefix = n.substring(0, n.length() - "level.dat".length()); }
            }
            if (prefix == null) throw new IOException(I18n.t("Це не мапа Minecraft: в архіві немає level.dat"));
            String name = fallbackName;
            if (!prefix.isEmpty()) {
                String[] seg = prefix.split("/");
                name = seg[seg.length - 1];
            }
            name = safeName(name);
            if (name.startsWith(".")) name = "_" + name;
            Path fin = child(saves, name);
            for (int i = 2; Files.exists(fin, LinkOption.NOFOLLOW_LINKS); i++) fin = child(saves, name + " (" + i + ")");

            tmp = saves.resolve(".pax-tmp-" + Long.toHexString(System.nanoTime()));
            Files.createDirectories(tmp);
            Path root = tmp.toAbsolutePath().normalize();
            long total = 0;
            byte[] buf = new byte[65536];
            for (Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements();) {
                if (cancel.getAsBoolean()) throw new Engine.Cancelled();
                java.util.zip.ZipEntry e = en.nextElement();
                String n = e.getName().replace('\\', '/');
                if (!n.startsWith(prefix) || n.startsWith("__MACOSX/")) continue;
                String rel = n.substring(prefix.length());
                if (rel.isEmpty()) continue;
                if (rel.startsWith("/") || rel.equals("..") || rel.startsWith("../") || rel.contains("/../") || rel.endsWith("/..") || rel.matches("^[A-Za-z]:.*"))
                    throw new IOException(I18n.t("Небезпечний шлях в архіві: ") + n);
                Path out = root.resolve(rel).normalize();
                if (!out.startsWith(root) || out.equals(root)) throw new IOException(I18n.t("Небезпечний шлях в архіві: ") + n);
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                try (InputStream in = z.getInputStream(e); OutputStream os = Files.newOutputStream(out)) {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        total += r;
                        if (total > WORLD_MAX_BYTES) throw new IOException(I18n.t("Архів завеликий після розпакування (понад 4 ГБ)"));
                        os.write(buf, 0, r);
                    }
                }
            }
            Files.move(tmp, fin);
            done = true;
            return fin.getFileName().toString();
        } catch (java.nio.file.InvalidPathException e) {
            throw new IOException(I18n.t("Некоректна назва файлу в архіві"));
        } finally {
            if (!done && tmp != null) { try { deleteTree(tmp); } catch (IOException ignored) { } }
        }
    }
}
