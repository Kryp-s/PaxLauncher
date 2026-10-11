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
                I18n.t("Сторінка мода"), I18n.t("Видалити мод"), I18n.t("Версія мода (для цієї збірки)")),
        PACK("resourcepack", "resourcepacks", I18n.t("Набори ресурсів"), I18n.t("Пошук наборів ресурсів"), I18n.t("Встановлені набори ресурсів"), I18n.t("Папка наборів ресурсів"),
                I18n.t("Сторінка набору ресурсів"), I18n.t("Видалити набір ресурсів"), I18n.t("Версія набору ресурсів (для цієї збірки)"));

        final String type, folder, title, searchTitle, installedTitle, folderBtn, pageBtn, removeBtn, versionLabel;

        Kind(String type, String folder, String title, String searchTitle, String installedTitle, String folderBtn,
             String pageBtn, String removeBtn, String versionLabel) {
            this.type = type; this.folder = folder; this.title = title; this.searchTitle = searchTitle;
            this.installedTitle = installedTitle; this.folderBtn = folderBtn; this.pageBtn = pageBtn;
            this.removeBtn = removeBtn; this.versionLabel = versionLabel;
        }

        boolean isMod() { return this == MOD; }

        boolean matches(String fileName) {
            String n = fileName.toLowerCase(Locale.ROOT);
            if (n.endsWith(".disabled")) n = n.substring(0, n.length() - 9);
            return isMod() ? n.endsWith(".jar") : n.endsWith(".zip");
        }

        @Override public String toString() { return title; }
    }

    record Hit(Kind kind, String id, String slug, String title, String author, String desc,
               long downloads, String loaders, String iconUrl) {
        @Override public String toString() { return title; }

        String pageUrl() { return "https://modrinth.com/" + kind.type + "/" + slug; }
    }

    record Detail(String id, String title, String desc, String body, long downloads, long followers,
                  String updated, String client, String server) { }

    private final Map<String, Detail> detailCache = new ConcurrentHashMap<>();

    Detail project(String id) throws Exception {
        Detail c = detailCache.get(id);
        if (c != null) return c;
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

    record ModFile(String name, String filename, String url, String sha1, String channel, String date, List<String> deps) {
        @Override public String toString() {
            return name + "  · " + channel + (date.isEmpty() ? "" : "  · " + date) + (url == null ? I18n.t("  · тільки з сайту") : "");
        }
    }

    static final String MR = "https://api.modrinth.com/v2";
    static final int PAGE = 30;

    final Engine eng;
    final Map<String, String> iconByProject = new ConcurrentHashMap<>();

    ModApi(Engine eng) { this.eng = eng; }

    Map<String, String> iconUrls(Collection<String> ids) throws Exception {
        Map<String, String> out = new HashMap<>();
        List<String> need = new ArrayList<>();
        for (String id : ids) {
            String c = iconByProject.get(id);
            if (c != null) out.put(id, c); else need.add(id);
        }
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
                HttpResponse<String> r = eng.http.send(rq, HttpResponse.BodyHandlers.ofString());
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
            for (String c : cand) {
                String name = c.startsWith("/") ? c.substring(1) : c;
                java.util.zip.ZipEntry e = z.getEntry(name);
                if (e == null || e.isDirectory() || e.getSize() > 8_000_000) continue;
                try (InputStream in = z.getInputStream(e)) {
                    BufferedImage im = javax.imageio.ImageIO.read(in);
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
        HttpResponse<String> r = eng.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return r.body();
    }

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

    String cached(String text) {
        if (text == null) return "";
        String c = trCache.get(text);
        return c != null ? c : text;
    }

    boolean needsTranslation(String text) {
        return I18n.descLang() != null && text != null && !text.isBlank() && !trCache.containsKey(text) && !mostlyCyrillic(text);
    }

    String translate(String text) {
        if (I18n.descLang() == null) return text == null ? "" : text;
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
            trFailUntil = System.currentTimeMillis() + 20_000;
            return text;
        }
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
                    str(h.get("author")), str(h.get("description")), num(h.get("downloads")), String.join(", ", ld),
                    str(h.get("icon_url"))));
            if (!str(h.get("icon_url")).isEmpty()) iconByProject.put(str(h.get("project_id")), str(h.get("icon_url")));
        }
        return out;
    }

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

    record ImportRow(String file, String title, String reason) {
        boolean ok() { return reason == null; }
    }

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

    static void forget(Path dir, String fileName) {
        String base = fileName.endsWith(".disabled") ? fileName.substring(0, fileName.length() - 9) : fileName;
        Properties p = loadReg(dir);
        if (p.values().removeIf(v -> v.equals(base))) saveReg(dir, p);
    }

    static String safeName(String n) {
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
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
