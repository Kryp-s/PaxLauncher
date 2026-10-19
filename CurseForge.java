import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Клієнт CurseForge Core API (https://docs.curseforge.com).
 *
 * Безпека ключа API:
 *  - у вихідному коді ключа у відкритому вигляді немає (він XOR-обфускований, щоб його не знаходив простий пошук по рядках / grep);
 *  - ключ можна підмінити своїм: змінна середовища PAX_CURSEFORGE_KEY або файл «curseforge.key» у папці запускача;
 *  - ключ іде лише в заголовку x-api-key на https://api.curseforge.com, ніколи не потрапляє в логи, URL чи повідомлення про помилки;
 *  - завантаження файлів дозволене лише з довірених доменів CurseForge (див. ModApi.trustedHost).
 * Зверни увагу: будь-який ключ, що поставляється всередині програми, теоретично можна витягти з пам'яті процесу.
 * Справжній захист — проксі на власному сервері, який додає ключ сам (тоді в програмі ключа немає взагалі).
 */
final class CurseForge {
    static final String BASE = "https://api.curseforge.com/v1";
    static final int GAME_MINECRAFT = 432;
    private static final String SALT = "PaxLauncher/cf/v1";
    private static final String[] PARTS = {
            "/oXcQR68SmllghUC6+BOdBswGpR", "NTvCLWZJXi+aX4YRjAbPtD11pwJ", "80uMMdMu3bDd0dJMDqiDCAUKxA"};
    private static final Pattern KEY_RE = Pattern.compile("\\$2[aby]\\$\\d\\d\\$[./A-Za-z0-9]{53}");

    private final ModApi api;
    private final Engine eng;
    private volatile String key;
    private volatile boolean keyLoaded;

    CurseForge(ModApi api) { this.api = api; this.eng = api.eng; }

    static boolean validKey(String k) { return k != null && KEY_RE.matcher(k).matches(); }

    private static byte[] keystream(int n) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] out = new byte[n];
        int pos = 0;
        for (int i = 0; pos < n; i++) {
            byte[] blk = md.digest((SALT + i).getBytes(StandardCharsets.US_ASCII));
            for (int j = 0; j < blk.length && pos < n; j++) out[pos++] = blk[j];
        }
        return out;
    }

    private static String builtIn() {
        try {
            byte[] enc = Base64.getDecoder().decode(String.join("", PARTS));
            byte[] ks = keystream(enc.length);
            byte[] plain = new byte[enc.length];
            for (int i = 0; i < enc.length; i++) plain[i] = (byte) (enc[i] ^ ks[i]);
            return new String(plain, StandardCharsets.US_ASCII);
        } catch (Exception e) { return null; }
    }

    private String key() {
        if (keyLoaded) return key;
        synchronized (this) {
            if (keyLoaded) return key;
            String k = null;
            String env = System.getenv("PAX_CURSEFORGE_KEY");
            if (validKey(env == null ? null : env.trim())) k = env.trim();
            if (k == null) {
                try {
                    Path f = eng.root.resolve("curseforge.key");
                    if (Files.isRegularFile(f) && Files.size(f) < 256) {
                        Vault.restrict(f);
                        String t = Files.readString(f, StandardCharsets.UTF_8).trim();
                        if (validKey(t)) k = t;
                    }
                } catch (Exception ignored) { }
            }
            if (k == null) { String b = builtIn(); if (validKey(b)) k = b; }
            key = k;
            keyLoaded = true;
            return k;
        }
    }

    boolean available() { return key() != null; }

    // ---------- HTTP ----------

    private String call(String path, String postBody) throws IOException {
        String k = key();
        if (k == null) throw new IOException(I18n.t("Ключ CurseForge не налаштовано"));
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                        .header("x-api-key", k).header("Accept", "application/json")
                        .header("User-Agent", "PaxLauncher/1.0").timeout(Duration.ofSeconds(30));
                if (postBody != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(postBody));
                else b.GET();
                HttpResponse<String> r = eng.sendRetry(b.build(), HttpResponse.BodyHandlers.ofString());
                int sc = r.statusCode();
                if (sc == 429) { Thread.sleep(1500L * (attempt + 1)); continue; }
                if (sc == 401 || sc == 403) throw new IOException(I18n.t("CurseForge не прийняв ключ API (HTTP ") + sc + ")");
                if (sc != 200) throw new IOException("HTTP " + sc);
                return r.body();
            }
            throw new IOException(I18n.t("CurseForge: забагато запитів, спробуй за хвилину"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(String.valueOf(e.getMessage()).replace(k, "***"));
        }
    }

    // ---------- helpers ----------

    private static Map<String, Object> m(Object o) { return Json.map(o); }
    private static List<Object> l(Object o) { return Json.list(o); }
    private static String str(Object o) { String x = Json.string(o); return x == null ? "" : x; }
    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
    private static String enc(String x) { return URLEncoder.encode(x, StandardCharsets.UTF_8); }

    static boolean isCf(String id) { return id != null && id.startsWith("cf:"); }

    private static String numId(String id) throws IOException {
        String n = isCf(id) ? id.substring(3) : id;
        if (n == null || !n.matches("\\d{1,10}")) throw new IOException("bad CurseForge id");
        return n;
    }

    static int loaderType(String loader) {
        switch (loader) {
            case "Forge": return 1;
            case "Fabric": case "Quilt": return 4;
            case "NeoForge": return 6;
            default: return 0;
        }
    }

    private static String prettyLoader(int t) {
        switch (t) {
            case 1: return "Forge";
            case 4: return "Fabric";
            case 5: return "Quilt";
            case 6: return "NeoForge";
            default: return null;
        }
    }

    // ---------- search / details ----------

    List<ModApi.Hit> search(ModApi.Kind kind, String q, String mc, String loader, int offset) throws IOException {
        StringBuilder u = new StringBuilder("/mods/search?gameId=" + GAME_MINECRAFT + "&classId=" + kind.cfClass
                + "&pageSize=" + ModApi.PAGE + "&index=" + offset + "&sortOrder=desc");
        if (!kind.isPack()) u.append("&gameVersion=").append(enc(mc));
        if (kind.isMod()) {
            int lt = loaderType(loader);
            if (lt > 0) u.append("&modLoaderType=").append(lt);
        }
        if (q.isEmpty()) u.append("&sortField=6"); else u.append("&searchFilter=").append(enc(q)).append("&sortField=2");
        List<ModApi.Hit> out = new ArrayList<>();
        Map<String, Object> root = m(Json.parse(call(u.toString(), null)));
        if (root == null) return out;
        for (Object o : l(root.get("data"))) {
            Map<String, Object> h = m(o);
            if (h == null) continue;
            long idn = num(h.get("id"));
            if (idn <= 0) continue;
            String id = "cf:" + idn;
            String author = "";
            List<Object> au = l(h.get("authors"));
            if (!au.isEmpty() && m(au.get(0)) != null) author = str(m(au.get(0)).get("name"));
            List<String> ld = new ArrayList<>();
            if (kind.isMod()) {
                for (Object fo : l(h.get("latestFilesIndexes"))) {
                    Map<String, Object> fi = m(fo);
                    if (fi == null || !mc.equals(str(fi.get("gameVersion")))) continue;
                    String p = prettyLoader((int) num(fi.get("modLoader")));
                    if (p != null && !ld.contains(p)) ld.add(p);
                }
            }
            String icon = "";
            Map<String, Object> logo = m(h.get("logo"));
            if (logo != null) { icon = str(logo.get("thumbnailUrl")); if (icon.isEmpty()) icon = str(logo.get("url")); }
            out.add(new ModApi.Hit(kind, id, str(h.get("slug")), str(h.get("name")), author, str(h.get("summary")),
                    num(h.get("downloadCount")), String.join(", ", ld), icon));
            if (!icon.isEmpty()) api.iconByProject.put(id, icon);
        }
        return out;
    }

    private static String htmlToText(String html) {
        if (html == null) return "";
        String t = html.replaceAll("(?i)<\\s*(br|/p|/li|/h[1-6]|/div|/tr)\\s*/?>", "\n");
        t = t.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
        t = t.replaceAll("<[^>]+>", "");
        return t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    ModApi.Detail project(String id) throws IOException {
        String n = numId(id);
        Map<String, Object> root = m(Json.parse(call("/mods/" + n, null)));
        Map<String, Object> h = root == null ? null : m(root.get("data"));
        if (h == null) throw new IOException("bad project");
        String body = "";
        try {
            Map<String, Object> d = m(Json.parse(call("/mods/" + n + "/description", null)));
            if (d != null) body = htmlToText(str(d.get("data")));
        } catch (IOException ignored) { }
        return new ModApi.Detail(id, str(h.get("name")), str(h.get("summary")), body, num(h.get("downloadCount")),
                num(h.get("thumbsUpCount")), str(h.get("dateModified")), "", "");
    }

    Map<String, String> logos(Collection<String> ids) throws IOException {
        Map<String, String> out = new HashMap<>();
        List<String> all = new ArrayList<>();
        for (String id : ids) if (isCf(id) && id.substring(3).matches("\\d{1,10}")) all.add(id.substring(3));
        for (int i = 0; i < all.size(); i += 50) {
            List<String> part = all.subList(i, Math.min(all.size(), i + 50));
            String body = "{\"modIds\":[" + String.join(",", part) + "]}";
            Map<String, Object> root = m(Json.parse(call("/mods", body)));
            if (root == null) continue;
            for (Object o : l(root.get("data"))) {
                Map<String, Object> h = m(o);
                if (h == null) continue;
                Map<String, Object> logo = m(h.get("logo"));
                String u = logo == null ? "" : str(logo.get("thumbnailUrl"));
                String id = "cf:" + num(h.get("id"));
                api.iconByProject.put(id, u);
                out.put(id, u);
            }
        }
        return out;
    }

    // ---------- files ----------

    List<ModApi.ModFile> files(ModApi.Kind kind, String id, String mc, String loader) throws IOException {
        String n = numId(id);
        StringBuilder u = new StringBuilder("/mods/" + n + "/files?pageSize=50");
        if (!kind.isPack()) u.append("&gameVersion=").append(enc(mc));
        if (kind.isMod()) {
            int lt = loaderType(loader);
            if (lt > 0) u.append("&modLoaderType=").append(lt);
        }
        Map<String, Object> root = m(Json.parse(call(u.toString(), null)));
        List<ModApi.ModFile> out = new ArrayList<>();
        if (root == null) return out;
        for (Object o : l(root.get("data"))) {
            Map<String, Object> f = m(o);
            if (f == null) continue;
            String fn = str(f.get("fileName"));
            if (fn.isEmpty()) continue;
            if (kind.isPack() && !fn.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
            String url = Json.string(f.get("downloadUrl"));
            if (url != null && url.isBlank()) url = null;
            String sha1 = null;
            for (Object ho : l(f.get("hashes"))) {
                Map<String, Object> hm = m(ho);
                if (hm != null && num(hm.get("algo")) == 1 && str(hm.get("value")).matches("[0-9a-fA-F]{40}")) sha1 = str(hm.get("value"));
            }
            long rt = num(f.get("releaseType"));
            String channel = rt == 1 ? "release" : rt == 2 ? "beta" : "alpha";
            String date = str(f.get("fileDate"));
            List<String> deps = new ArrayList<>();
            for (Object d : l(f.get("dependencies"))) {
                Map<String, Object> dm = m(d);
                if (dm != null && num(dm.get("relationType")) == 3 && num(dm.get("modId")) > 0) deps.add("cf:" + num(dm.get("modId")));
            }
            String name = str(f.get("displayName"));
            List<String> packMcs = new ArrayList<>(), packLds = new ArrayList<>();
            if (kind.isPack()) {
                String mcv = "";
                List<String> ld = new ArrayList<>();
                for (Object gv : l(f.get("gameVersions"))) {
                    String g = str(gv);
                    if (g.matches("\\d+\\.\\d+(\\.\\d+)?")) { if (mcv.isEmpty()) mcv = g; packMcs.add(g); }
                    else if (g.equals("Forge") || g.equals("Fabric") || g.equals("NeoForge") || g.equals("Quilt")) { ld.add(g); packLds.add(g.toLowerCase(Locale.ROOT)); }
                }
                name = (name.isEmpty() ? fn : name) + (mcv.isEmpty() && ld.isEmpty() ? "" : "  · " + mcv + (ld.isEmpty() ? "" : " " + String.join("/", ld)));
            }
            out.add(new ModApi.ModFile(name.isEmpty() ? fn : name, fn, url, sha1, channel,
                    date.length() >= 10 ? date.substring(0, 10) : "", deps, packMcs, packLds));
        }
        out.sort((a, b) -> b.date().compareTo(a.date()));
        return out;
    }

    // ---------- відбиток файлу (для імпорту) ----------

    /** CurseForge fingerprint: MurmurHash2 (seed 1) від байтів файлу без пробільних символів. */
    static long fingerprint(Path f) throws IOException {
        final int M = 0x5bd1e995;
        long count = 0;
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f), 1 << 16)) {
            int n;
            while ((n = in.read(buf)) > 0)
                for (int i = 0; i < n; i++) { byte b = buf[i]; if (b != 9 && b != 10 && b != 13 && b != 32) count++; }
        }
        int h = 1 ^ (int) count;
        int k = 0, shift = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f), 1 << 16)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == 9 || b == 10 || b == 13 || b == 32) continue;
                    k |= (b & 0xff) << shift;
                    shift += 8;
                    if (shift == 32) {
                        k *= M; k ^= k >>> 24; k *= M;
                        h *= M; h ^= k;
                        k = 0; shift = 0;
                    }
                }
            }
        }
        if (shift > 0) { h ^= k; h *= M; }
        h ^= h >>> 13; h *= M; h ^= h >>> 15;
        return Integer.toUnsignedLong(h);
    }

    /** Повертає "cf:<modId>" або null, якщо CurseForge такого файлу не знає. */
    String identify(Path file) throws IOException {
        long fp = fingerprint(file);
        Map<String, Object> root = m(Json.parse(call("/fingerprints/" + GAME_MINECRAFT, "{\"fingerprints\":[" + fp + "]}")));
        Map<String, Object> data = root == null ? null : m(root.get("data"));
        if (data == null) return null;
        List<Object> ex = l(data.get("exactMatches"));
        if (ex.isEmpty() || m(ex.get(0)) == null) return null;
        long id = num(m(ex.get(0)).get("id"));
        return id > 0 ? "cf:" + id : null;
    }

    // ---------- модові збірки (modpacks) ----------

    record FileInfo(long fileId, long modId, String filename, String url, String sha1) { }

    record ModMeta(long id, String name, String slug, int classId) { }

    Map<Long, FileInfo> filesById(Collection<Long> ids) throws IOException {
        Map<Long, FileInfo> out = new HashMap<>();
        List<Long> all = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int i = 0; i < all.size(); i += 50) {
            List<Long> part = all.subList(i, Math.min(all.size(), i + 50));
            StringBuilder b = new StringBuilder("{\"fileIds\":[");
            for (int k = 0; k < part.size(); k++) b.append(k > 0 ? "," : "").append(part.get(k));
            b.append("]}");
            Map<String, Object> root = m(Json.parse(call("/mods/files", b.toString())));
            if (root == null) continue;
            for (Object o : l(root.get("data"))) {
                Map<String, Object> f = m(o);
                if (f == null || num(f.get("id")) <= 0) continue;
                String url = Json.string(f.get("downloadUrl"));
                if (url != null && url.isBlank()) url = null;
                String sha1 = null;
                for (Object ho : l(f.get("hashes"))) {
                    Map<String, Object> hm = m(ho);
                    if (hm != null && num(hm.get("algo")) == 1 && str(hm.get("value")).matches("[0-9a-fA-F]{40}")) sha1 = str(hm.get("value"));
                }
                out.put(num(f.get("id")), new FileInfo(num(f.get("id")), num(f.get("modId")), str(f.get("fileName")), url, sha1));
            }
        }
        return out;
    }

    /** Один файл за id (коли пакетний запит його не повернув). */
    FileInfo fileOne(long modId, long fileId) {
        try {
            Map<String, Object> root = m(Json.parse(call("/mods/" + modId + "/files/" + fileId, null)));
            Map<String, Object> f = root == null ? null : m(root.get("data"));
            if (f == null || num(f.get("id")) <= 0) return null;
            String url = Json.string(f.get("downloadUrl"));
            if (url != null && url.isBlank()) url = null;
            String sha1 = null;
            for (Object ho : l(f.get("hashes"))) {
                Map<String, Object> hm = m(ho);
                if (hm != null && num(hm.get("algo")) == 1 && str(hm.get("value")).matches("[0-9a-fA-F]{40}")) sha1 = str(hm.get("value"));
            }
            return new FileInfo(num(f.get("id")), modId, str(f.get("fileName")), url, sha1);
        } catch (Exception e) { return null; }
    }

    /** Посилання на завантаження через окремий запит API (інколи є, коли в списку downloadUrl порожній). */
    String apiDownloadUrl(long modId, long fileId) {
        try {
            Map<String, Object> root = m(Json.parse(call("/mods/" + modId + "/files/" + fileId + "/download-url", null)));
            String u = root == null ? null : Json.string(root.get("data"));
            return u == null || u.isBlank() ? null : u;
        } catch (Exception e) { return null; }
    }

    /** Прямі адреси CDN CurseForge: потрібні для модів, автори яких вимкнули «завантаження сторонніми запускачами»
     *  (тоді API не віддає downloadUrl, але файл за цією адресою лежить так само, як і для сайту). */
    static List<String> cdnUrls(long fileId, String filename) {
        String fn = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String tail = (fileId / 1000) + "/" + (fileId % 1000) + "/" + fn;
        return List.of("https://edge.forgecdn.net/files/" + tail, "https://mediafilez.forgecdn.net/files/" + tail);
    }

    Map<Long, ModMeta> modMeta(Collection<Long> ids) throws IOException {
        Map<Long, ModMeta> out = new HashMap<>();
        List<Long> all = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int i = 0; i < all.size(); i += 50) {
            List<Long> part = all.subList(i, Math.min(all.size(), i + 50));
            StringBuilder b = new StringBuilder("{\"modIds\":[");
            for (int k = 0; k < part.size(); k++) b.append(k > 0 ? "," : "").append(part.get(k));
            b.append("]}");
            Map<String, Object> root = m(Json.parse(call("/mods", b.toString())));
            if (root == null) continue;
            for (Object o : l(root.get("data"))) {
                Map<String, Object> h = m(o);
                if (h == null || num(h.get("id")) <= 0) continue;
                out.put(num(h.get("id")), new ModMeta(num(h.get("id")), str(h.get("name")), str(h.get("slug")), (int) num(h.get("classId"))));
            }
        }
        return out;
    }
}
