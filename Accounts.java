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

final class Accounts {
    record Acc(String type, String name, String uuid, String token) {
        String label() { return type.equals("ms") ? "Microsoft" : type.equals("ely") ? "Ely.by" : I18n.t("Неліцензійний"); }
        String userType() { return type.equals("ms") ? "msa" : type.equals("ely") ? "mojang" : "legacy"; }
    }

    record Resp(int code, String body) { }

    record Dev(String deviceCode, String userCode, String url, int interval, int expires) { }

    static final String ELY = "https://authserver.ely.by/auth/";
    static final String MS = "https://login.microsoftonline.com/consumers/oauth2/v2.0/";
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

    private static String key(String type, String uuid) { return type.equals("ms") ? "ms." + uuid : type; }

    private synchronized Acc saved(String type) {
        String n = p.getProperty(type + ".name");
        if (n == null) return null;
        return new Acc(type, n, p.getProperty(type + ".uuid", ""), p.getProperty(type + ".token", "0"));
    }

    private synchronized Acc savedMs(String uuid) {
        String k = key("ms", uuid);
        String n = p.getProperty(k + ".name");
        if (n == null) return null;
        return new Acc("ms", n, uuid, p.getProperty(k + ".token", "0"));
    }

    private synchronized String prop(String k) { return p.getProperty(k, ""); }

    private synchronized List<String> msIds() {
        List<String> r = new ArrayList<>();
        for (String x : p.getProperty("ms.list", "").split(",")) if (!x.trim().isEmpty()) r.add(x.trim());
        return r;
    }

    synchronized List<Acc> msAccounts() {
        migrateLegacyMs();
        List<Acc> r = new ArrayList<>();
        for (String id : msIds()) { Acc a = savedMs(id); if (a != null) r.add(a); }
        return r;
    }

    private synchronized void migrateLegacyMs() {
        String n = p.getProperty("ms.name");
        String u = p.getProperty("ms.uuid", "");
        if (n == null) return;
        if (!u.isEmpty()) {
            String k = key("ms", u);
            p.setProperty(k + ".name", n);
            p.setProperty(k + ".uuid", u);
            p.setProperty(k + ".token", p.getProperty("ms.token", "0"));
            p.setProperty(k + ".extra", p.getProperty("ms.extra", ""));
            List<String> l = msIds();
            if (!l.contains(u)) { l.add(u); p.setProperty("ms.list", String.join(",", l)); }
            if (p.getProperty("active", "").equals("ms")) p.setProperty("active", "ms:" + u);
        }
        for (String f : new String[]{"name", "uuid", "token", "extra"}) p.remove("ms." + f);
        save();
    }

    synchronized void removeMs(String uuid) {
        List<String> l = msIds();
        l.remove(uuid);
        p.setProperty("ms.list", String.join(",", l));
        for (String f : new String[]{"name", "uuid", "token", "extra"}) p.remove(key("ms", uuid) + "." + f);
        if (p.getProperty("active", "").equals("ms:" + uuid)) p.remove("active");
        heads.remove(uuid);
        try { Files.deleteIfExists(headFile(uuid)); } catch (IOException ignored) { }
        save();
    }

    private synchronized void store(Acc a, String extra) {
        String k = key(a.type(), a.uuid());
        p.setProperty(k + ".name", a.name());
        p.setProperty(k + ".uuid", a.uuid());
        p.setProperty(k + ".token", a.token());
        p.setProperty(k + ".extra", extra);
        if (a.type().equals("ms")) {
            List<String> l = msIds();
            if (!l.contains(a.uuid())) { l.add(a.uuid()); p.setProperty("ms.list", String.join(",", l)); }
        }
        save();
    }

    synchronized void setActive(Acc a) {
        p.setProperty("active", a.type().equals("offline") ? "offline:" + a.name()
                : a.type().equals("ms") ? "ms:" + a.uuid() : a.type());
        save();
    }

    synchronized Acc current() {
        migrateLegacyMs();
        String a = p.getProperty("active", "");
        if (a.startsWith("offline:")) {
            String n = a.substring(8);
            return offlineNames().contains(n) ? offline(n) : null;
        }
        if (a.startsWith("ms:")) return savedMs(a.substring(3));
        return a.equals("ely") ? saved(a) : null;
    }


    private final Map<String, java.awt.image.BufferedImage> heads = new ConcurrentHashMap<>();

    private Path headFile(String uuid) { return file.getParent().resolve("heads").resolve(uuid + ".png"); }

    java.awt.image.BufferedImage headCached(String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        java.awt.image.BufferedImage im = heads.get(uuid);
        if (im != null) return im;
        try {
            Path f = headFile(uuid);
            if (Files.exists(f)) {
                im = javax.imageio.ImageIO.read(f.toFile());
                if (im != null) heads.put(uuid, im);
            }
        } catch (Exception ignored) { }
        return im;
    }

    java.awt.image.BufferedImage headFetch(String uuid) {
        try {
            Resp r = getPlain("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
            if (r.code() != 200) return headCached(uuid);
            for (Object o : Json.list(jm(r.body()).get("properties"))) {
                Map<String, Object> pr = Json.map(o);
                if (pr == null || !"textures".equals(str(pr.get("name")))) continue;
                String js = new String(Base64.getDecoder().decode(str(pr.get("value"))), StandardCharsets.UTF_8);
                String url = str(Json.get(jm(js), "textures", "SKIN", "url"));
                if (!url.isEmpty()) {
                    java.awt.image.BufferedImage h = headFromUrl(uuid, url);
                    if (h != null) return h;
                }
            }
        } catch (Exception ignored) { }
        return headCached(uuid);
    }

    private java.awt.image.BufferedImage headFromUrl(String uuid, String url) {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "PaxLauncher/1.0")
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<byte[]> x = http.send(rq, HttpResponse.BodyHandlers.ofByteArray());
            if (x.statusCode() != 200) return null;
            java.awt.image.BufferedImage sk = javax.imageio.ImageIO.read(new ByteArrayInputStream(x.body()));
            if (sk == null || sk.getWidth() < 64) return null;
            int k = sk.getWidth() / 64;
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int yy = 0; yy < 8; yy++)
                for (int xx = 0; xx < 8; xx++) {
                    int face = sk.getRGB((8 + xx) * k, (8 + yy) * k);
                    int hat = sk.getRGB((40 + xx) * k, (8 + yy) * k);
                    out.setRGB(xx, yy, (hat >>> 24) > 127 ? hat : (face | 0xFF000000));
                }
            heads.put(uuid, out);
            try {
                Files.createDirectories(headFile(uuid).getParent());
                javax.imageio.ImageIO.write(out, "png", headFile(uuid).toFile());
            } catch (Exception ignored) { }
            return out;
        } catch (Exception e) { return null; }
    }

    private Resp getPlain(String url) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "PaxLauncher/1.0")
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> x = http.send(r, HttpResponse.BodyHandlers.ofString());
        return new Resp(x.statusCode(), x.body());
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
        for (Object o : Json.list(pr.get("skins"))) {
            Map<String, Object> sk = Json.map(o);
            if (sk != null && "ACTIVE".equals(str(sk.get("state"))) && !str(sk.get("url")).isEmpty()) {
                headFromUrl(a.uuid(), str(sk.get("url")));
                break;
            }
        }
        return a;
    }

    Acc ensureFresh(Acc a, String msClient) throws Exception {
        switch (a.type()) {
            case "ms": {
                String rt = prop(key("ms", a.uuid()) + ".extra");
                if (rt.isEmpty() || msClient.isEmpty())
                    throw new IOException(I18n.t("Потрібен вхід через Microsoft і Client ID у Налаштуваннях."));
                return msRefresh(msClient, rt);
            }
            case "ely": return refreshEly(a);
            default: return a;
        }
    }
}
