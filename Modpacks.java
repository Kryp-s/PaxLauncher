import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Модові збірки (modpacks) з Modrinth (.mrpack) і CurseForge (.zip з manifest.json).
 *
 * Збірка ставиться у ВЖЕ ІСНУЮЧУ збірку запускача (нову не створює і завантажувач не ставить).
 * Порядок: pick() — обирає версію модпака, найближчу до версії гри твоєї збірки; open() — завантажує архів
 * і читає його опис (версія гри, завантажувач, список файлів); install() — викачує всі файли й розпаковує overrides.
 *
 * Безпека: усі файли беруться лише з довірених доменів і лише з перевіркою контрольної суми,
 * шляхи не можуть виходити за межі папки збірки, виконувані файли з overrides не розпаковуються.
 */
final class Modpacks {
    static final long MAX_MANIFEST = 16L << 20;
    static final long MAX_UNPACKED = 3L << 30;
    static final int MAX_ENTRIES = 100_000;
    static final int THREADS = 6;
    private static final Pattern SAFE_VER = Pattern.compile("[A-Za-z0-9._+\\-]{1,64}");
    private static final Pattern SAFE_DIR = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");
    private static final Set<String> BAD_EXT = Set.of(".exe", ".bat", ".cmd", ".com", ".msi", ".scr", ".ps1", ".vbs", ".sh", ".lnk", ".dll", ".so", ".dylib");
    private static final Set<String> MR_HOSTS = Set.of("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com");

    record MrFile(String path, List<String> urls, String hash) { }

    record Plan(boolean cf, Path archive, String name, String mc, String loader, String loaderVer,
                List<MrFile> mrFiles, List<long[]> cfFiles, List<String> overrideDirs) { }

    record Report(int total, int ok, List<String> failed, List<String> manual) {
        boolean clean() { return failed.isEmpty() && manual.isEmpty(); }
    }

    private final ModApi api;
    private final Engine eng;

    Modpacks(ModApi api) { this.api = api; this.eng = api.eng; }

    // ---------- вибір версії модпака під версію гри збірки ----------

    /** index — позиція обраного файлу у списку; exact — чи є файл саме для версії гри збірки;
     *  nearestMc — версія гри обраного файлу; newestMc — найновіша версія гри серед усіх файлів;
     *  loaderMismatch — жоден файл не підходить за завантажувачем (обрано найближчий за версією). */
    record Pick(int index, boolean exact, String nearestMc, String newestMc, boolean loaderMismatch) { }

    private static final Pattern MC_VER = Pattern.compile("\\d+(\\.\\d+){1,2}");

    private static long[] mcParts(String v) {
        if (v == null || !MC_VER.matcher(v).matches()) return null;
        String[] a = v.split("\\.");
        long[] r = new long[3];
        for (int i = 0; i < a.length && i < 3; i++) r[i] = Long.parseLong(a[i]);
        return r;
    }

    private static long mcKey(long[] p) { return p[0] * 1_000_000_000L + p[1] * 1_000_000L + p[2]; }

    private static long mcDist(long[] a, long[] b) {
        return Math.abs(mcKey(a) - mcKey(b));
    }

    static boolean loaderOk(ModApi.ModFile f, String loader) {
        if (loader == null || loader.isEmpty() || loader.equalsIgnoreCase("Vanilla") || f.loaders().isEmpty()) return true;
        return f.loaders().contains(loader.toLowerCase(Locale.ROOT));
    }

    static boolean sameLoader(String a, String b) { return a != null && a.equalsIgnoreCase(b); }

    /**
     * Обирає з усіх версій модпака ту, що підходить до збірки: спершу файл саме для версії гри збірки
     * (найновіший, віддаючи перевагу release), інакше — для найближчої версії гри.
     * Файли для іншого завантажувача пропускаються, якщо є хоч один для потрібного. Список очікується від новішого до старішого.
     */
    static Pick pick(List<ModApi.ModFile> files, String mc, String loader) {
        if (files == null || files.isEmpty()) return null;
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) if (loaderOk(files.get(i), loader)) cand.add(i);
        boolean mismatch = cand.isEmpty();
        if (mismatch) for (int i = 0; i < files.size(); i++) cand.add(i);

        String newest = null;
        long[] newestP = null;
        for (int i : cand) for (String v : files.get(i).mcs()) {
            long[] p = mcParts(v);
            if (p != null && (newestP == null || mcKey(p) > mcKey(newestP))) { newestP = p; newest = v; }
        }

        int exact = -1;
        for (int i : cand) {
            ModApi.ModFile f = files.get(i);
            if (!f.mcs().contains(mc)) continue;
            if (exact < 0) exact = i;
            if (f.channel().equals("release")) { exact = i; break; }
        }
        if (exact >= 0) return new Pick(exact, true, mc, newest, mismatch);

        long[] want = mcParts(mc);
        int best = -1;
        String bestMc = null;
        if (want != null) {
            long bestD = Long.MAX_VALUE, bestK = -1;
            for (int i : cand) for (String v : files.get(i).mcs()) {
                long[] p = mcParts(v);
                if (p == null) continue;
                long d = mcDist(want, p), k = mcKey(p);
                // ближче — краще; при однаковій відстані — новіша версія гри; далі — release, потім новіший файл
                boolean better = d < bestD || (d == bestD && k > bestK)
                        || (d == bestD && k == bestK && best >= 0 && !files.get(best).channel().equals("release") && files.get(i).channel().equals("release"));
                if (better) { bestD = d; bestK = k; best = i; bestMc = v; }
            }
        }
        if (best < 0) {
            best = cand.get(0);
            for (int i : cand) if (files.get(i).channel().equals("release")) { best = i; break; }
            List<String> mcs = files.get(best).mcs();
            bestMc = mcs.isEmpty() ? null : mcs.get(0);
        }
        return new Pick(best, false, bestMc, newest, mismatch);
    }

    // ---------- helpers ----------

    private static Map<String, Object> m(Object o) { return Json.map(o); }
    private static List<Object> l(Object o) { return Json.list(o); }
    private static String str(Object o) { String x = Json.string(o); return x == null ? "" : x; }
    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }

    /** Назва для нової збірки на основі назви модпака (за правилами імен збірок). */
    static String suggestName(String title) {
        String s = title == null ? "" : title.replaceAll("[^\\p{L}\\p{N} _.-]", "").replaceAll("\\s+", " ").trim();
        if (s.length() > 32) s = s.substring(0, 32).trim();
        return s.replaceFirst("^[ _.-]+", "");
    }

    private static boolean blocked(String rel) {
        String n = rel.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot >= 0 && BAD_EXT.contains(n.substring(dot));
    }

    private static boolean trustedMr(String url) {
        try {
            String h = URI.create(Engine.httpsOnly(url)).getHost().toLowerCase(Locale.ROOT);
            return MR_HOSTS.contains(h) || ModApi.trustedHost(url, false);
        } catch (Exception e) { return false; }
    }

    private static String bestHash(Map<String, Object> h) {
        if (h == null) return null;
        String x = Json.string(h.get("sha512"));
        if (x != null && x.matches("[0-9a-fA-F]{128}")) return x;
        x = Json.string(h.get("sha1"));
        return x != null && x.matches("[0-9a-fA-F]{40}") ? x : null;
    }

    // ---------- крок 1: завантажити архів і прочитати опис ----------

    private static String readText(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) {
            byte[] b = in.readNBytes((int) MAX_MANIFEST + 1);
            if (b.length > MAX_MANIFEST) throw new IOException(I18n.t("Опис збірки надто великий: ") + name);
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static String needVer(String v) throws IOException {
        if (v == null || !SAFE_VER.matcher(v).matches()) throw new IOException(I18n.t("Некоректна версія в описі збірки: ") + v);
        return v;
    }

    Plan open(boolean cf, String title, String pageUrl, ModApi.ModFile v, Engine.Progress pg, BooleanSupplier cancel) throws Exception {
        if (v.url() == null || v.url().isEmpty())
            throw new IOException(I18n.t("Автор заборонив завантаження сторонніми запускачами — візьми збірку на сайті: ") + pageUrl);
        if (v.sha1() == null) throw new IOException(I18n.t("Немає контрольної суми файлу — відмовляюсь завантажувати неперевірений файл."));
        if (cf ? !ModApi.trustedHost(v.url(), true) : !trustedMr(v.url()))
            throw new IOException(I18n.t("Файл лежить на недовіреному сервері — відмовляюсь завантажувати."));
        pg.update(I18n.t("Завантаження збірки: ") + v.filename(), 0.05);
        Path arc = api.cacheDir("modpacks").resolve(Long.toHexString(System.nanoTime()) + ".zip");
        try {
            eng.download(v.url(), arc, v.sha1(), cancel);
            pg.update(I18n.t("Читаю опис збірки…"), 0.8);
            Plan plan = cf ? parseCurse(title, arc) : parseModrinth(title, arc);
            pg.update(I18n.t("Готово"), 1);
            return plan;
        } catch (Exception e) {
            try { Files.deleteIfExists(arc); } catch (IOException ignored) { }
            throw e;
        }
    }

    /**
     * Відкриває ЛОКАЛЬНИЙ файл збірки модів (.mrpack Modrinth або .zip CurseForge). Тип визначається за вмістом архіву.
     * Файл копіюється до кешу, тож оригінал користувача після встановлення не видаляється.
     */
    Plan openLocal(Path file, String title, Engine.Progress pg) throws Exception {
        if (!Files.isRegularFile(file)) throw new IOException(I18n.t("Файл не знайдено: ") + file);
        pg.update(I18n.t("Читаю файл збірки модів…"), 0.1);
        Path arc = api.cacheDir("modpacks").resolve(Long.toHexString(System.nanoTime()) + ".zip");
        try {
            Files.copy(file, arc, StandardCopyOption.REPLACE_EXISTING);
            boolean mr, cf;
            try (ZipFile z = new ZipFile(arc.toFile())) {
                mr = z.getEntry("modrinth.index.json") != null;
                cf = z.getEntry("manifest.json") != null;
            } catch (ZipException ze) {
                throw new IOException(I18n.t("Це не архів збірки модів (.mrpack або .zip)."));
            }
            if (!mr && !cf) throw new IOException(I18n.t("У файлі немає опису збірки модів (modrinth.index.json або manifest.json)."));
            Plan plan = mr ? parseModrinth(title, arc) : parseCurse(title, arc);
            pg.update(I18n.t("Готово"), 1);
            return plan;
        } catch (Exception e) {
            try { Files.deleteIfExists(arc); } catch (IOException ignored) { }
            throw e;
        }
    }

    private Plan parseModrinth(String title, Path arc) throws Exception {
        try (ZipFile z = new ZipFile(arc.toFile())) {
            String txt = readText(z, "modrinth.index.json");
            if (txt == null) throw new IOException(I18n.t("Це не збірка Modrinth (.mrpack): немає modrinth.index.json"));
            Map<String, Object> root = m(Json.parse(txt));
            if (root == null) throw new IOException("bad index");
            Map<String, Object> deps = m(root.get("dependencies"));
            if (deps == null) throw new IOException(I18n.t("В описі збірки немає версії Minecraft"));
            String mc = needVer(str(deps.get("minecraft")));
            String loader = "Vanilla", lv = mc;
            if (deps.containsKey("fabric-loader")) { loader = "Fabric"; lv = needVer(str(deps.get("fabric-loader"))); }
            else if (deps.containsKey("quilt-loader")) { loader = "Quilt"; lv = needVer(str(deps.get("quilt-loader"))); }
            else if (deps.containsKey("neoforge")) { loader = "NeoForge"; lv = needVer(str(deps.get("neoforge"))); }
            else if (deps.containsKey("forge")) { loader = "Forge"; lv = mc + "-" + needVer(str(deps.get("forge"))); }
            List<MrFile> files = new ArrayList<>();
            for (Object o : l(root.get("files"))) {
                Map<String, Object> f = m(o);
                if (f == null) continue;
                Map<String, Object> env = m(f.get("env"));
                if (env != null && "unsupported".equals(str(env.get("client")))) continue;
                List<String> urls = new ArrayList<>();
                for (Object u : l(f.get("downloads"))) urls.add(str(u));
                files.add(new MrFile(str(f.get("path")).replace('\\', '/'), urls, bestHash(m(f.get("hashes")))));
            }
            String name = str(root.get("name"));
            return new Plan(false, arc, name.isEmpty() ? title : name, mc, loader, lv, files, List.of(),
                    List.of("overrides", "client-overrides"));
        }
    }

    private Plan parseCurse(String title, Path arc) throws Exception {
        try (ZipFile z = new ZipFile(arc.toFile())) {
            String txt = readText(z, "manifest.json");
            if (txt == null) throw new IOException(I18n.t("Це не збірка CurseForge: немає manifest.json"));
            Map<String, Object> root = m(Json.parse(txt));
            Map<String, Object> mcm = root == null ? null : m(root.get("minecraft"));
            if (mcm == null) throw new IOException(I18n.t("В описі збірки немає версії Minecraft"));
            String mc = needVer(str(mcm.get("version")));
            String lid = null;
            for (Object o : l(mcm.get("modLoaders"))) {
                Map<String, Object> ml = m(o);
                if (ml == null) continue;
                if (lid == null || Boolean.TRUE.equals(ml.get("primary"))) lid = str(ml.get("id"));
            }
            String loader = "Vanilla", lv = mc;
            if (lid != null && !lid.isEmpty()) {
                int dash = lid.indexOf('-');
                String kind = (dash < 0 ? lid : lid.substring(0, dash)).toLowerCase(Locale.ROOT);
                String rest = dash < 0 ? null : needVer(lid.substring(dash + 1));
                switch (kind) {
                    case "forge": loader = "Forge"; lv = mc + "-" + rest; break;
                    case "neoforge": loader = "NeoForge"; lv = rest; break;
                    case "fabric": loader = "Fabric"; lv = rest; break;
                    case "quilt": loader = "Quilt"; lv = rest; break;
                    default: throw new IOException(I18n.t("Невідомий завантажувач: ") + lid);
                }
            }
            List<long[]> files = new ArrayList<>();
            for (Object o : l(root.get("files"))) {
                Map<String, Object> f = m(o);
                if (f == null) continue;
                long pid = num(f.get("projectID")), fid = num(f.get("fileID"));
                if (pid > 0 && fid > 0) files.add(new long[]{pid, fid});
            }
            String ov = str(root.get("overrides"));
            if (!SAFE_DIR.matcher(ov).matches() || ov.equals("..")) ov = "overrides";
            String name = str(root.get("name"));
            return new Plan(true, arc, name.isEmpty() ? title : name, mc, loader, lv, List.of(), files, List.of(ov));
        }
    }

    // ---------- крок 2: файли та overrides ----------

    private String[] runBatch(List<Callable<String>> jobs, int threads, Engine.Progress pg, BooleanSupplier cancel, String label) throws Exception {
        int n = jobs.size();
        String[] res = new String[n];
        if (n == 0) return res;
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(threads, n), r -> {
            Thread t = new Thread(r, "pack-download");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger done = new AtomicInteger();
        List<Future<String>> fs = new ArrayList<>();
        try {
            for (Callable<String> j : jobs) {
                fs.add(ex.submit(() -> {
                    try { return j.call(); }
                    finally {
                        int d = done.incrementAndGet();
                        pg.update(label + d + " / " + n, (double) d / n);
                    }
                }));
            }
            for (int i = 0; i < n; i++) {
                try {
                    res[i] = fs.get(i).get();
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof Engine.Cancelled) throw (Engine.Cancelled) c;
                    res[i] = String.valueOf(c == null ? e.getMessage() : c.getMessage());
                }
            }
            if (cancel.getAsBoolean()) throw new Engine.Cancelled();
            return res;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new Engine.Cancelled();
        } finally {
            ex.shutdownNow();
        }
    }

    /** Качає все; те, що не вдалося (обмеження серверів, обрив мережі), ще двічі повторює повільніше й меншою кількістю потоків. */
    private List<String> parallel(List<Callable<String>> jobs, Engine.Progress pg, BooleanSupplier cancel) throws Exception {
        String[] res = runBatch(jobs, THREADS, pg, cancel, I18n.t("Завантаження файлів збірки: "));
        for (int round = 0; round < 2; round++) {
            List<Integer> bad = new ArrayList<>();
            for (int i = 0; i < res.length; i++) if (res[i] != null) bad.add(i);
            if (bad.isEmpty()) break;
            try { Thread.sleep(2500L * (round + 1)); } catch (InterruptedException ie) { throw new Engine.Cancelled(); }
            List<Callable<String>> again = new ArrayList<>();
            for (int i : bad) again.add(jobs.get(i));
            String[] r2 = runBatch(again, round == 0 ? 2 : 1, pg, cancel, I18n.t("Повторна спроба: "));
            for (int k = 0; k < bad.size(); k++) res[bad.get(k)] = r2[k];
        }
        List<String> fails = new ArrayList<>();
        for (String r : res) if (r != null) fails.add(r);
        return fails;
    }

    Report install(Plan plan, Path inst, Engine.Progress pg, BooleanSupplier cancel) throws Exception {
        try {
            Files.createDirectories(inst);
            Report rep = plan.cf() ? installCurse(plan, inst, pg, cancel) : installModrinth(plan, inst, pg, cancel);
            pg.update(I18n.t("Розпаковую налаштування збірки…"), 0.98);
            extractOverrides(plan.archive(), inst, plan.overrideDirs(), cancel);
            writeReport(inst, rep);
            return rep;
        } finally {
            try { Files.deleteIfExists(plan.archive()); } catch (IOException ignored) { }
        }
    }

    private Report installModrinth(Plan plan, Path inst, Engine.Progress pg, BooleanSupplier cancel) throws Exception {
        List<Callable<String>> jobs = new ArrayList<>();
        for (MrFile f : plan.mrFiles()) {
            jobs.add(() -> {
                try {
                    if (f.path().isEmpty() || blocked(f.path())) return f.path() + I18n.t(" — тип файлу не дозволено");
                    Path dest = Engine.under(inst, f.path());
                    if (f.hash() == null) return f.path() + I18n.t(" — немає контрольної суми");
                    Exception last = null;
                    boolean any = false;
                    for (String u : f.urls()) {
                        if (!trustedMr(u)) continue;
                        any = true;
                        try { eng.download(u, dest, f.hash(), cancel); return null; }
                        catch (Engine.Cancelled c) { throw c; }
                        catch (Exception e) { last = e; }
                    }
                    if (!any) return f.path() + I18n.t(" — немає довіреного джерела");
                    throw last;
                } catch (Engine.Cancelled c) {
                    throw c;
                } catch (Exception e) {
                    return f.path() + " — " + e.getMessage();
                }
            });
        }
        pg.update(I18n.t("Завантаження файлів збірки…"), 0);
        List<String> fails = parallel(jobs, pg, cancel);
        return new Report(jobs.size(), jobs.size() - fails.size(), fails, new ArrayList<>());
    }

    private static String folderFor(int classId) {
        switch (classId) {
            case 12: return "resourcepacks";
            case 6552: return "shaderpacks";
            default: return "mods";
        }
    }

    private static String cfPath(int classId) {
        switch (classId) {
            case 12: return "texture-packs";
            case 6552: return "shaders";
            default: return "mc-mods";
        }
    }

    private Report installCurse(Plan plan, Path inst, Engine.Progress pg, BooleanSupplier cancel) throws Exception {
        CurseForge cf = api.cf;
        if (!cf.available()) throw new IOException(I18n.t("Ключ CurseForge не налаштовано"));
        pg.update(I18n.t("Отримую список файлів з CurseForge…"), 0);
        List<Long> fileIds = new ArrayList<>(), modIds = new ArrayList<>();
        for (long[] f : plan.cfFiles()) { fileIds.add(f[1]); modIds.add(f[0]); }
        Map<Long, CurseForge.FileInfo> infos = cf.filesById(fileIds);
        if (cancel.getAsBoolean()) throw new Engine.Cancelled();
        Map<Long, CurseForge.ModMeta> metas = cf.modMeta(modIds);

        List<Callable<String>> jobs = new ArrayList<>();
        List<String> manual = new ArrayList<>(), failed = new ArrayList<>();
        Queue<String> lateManual = new ConcurrentLinkedQueue<>();
        Properties reg = new Properties();
        Path modsDir = inst.resolve("mods");
        for (long[] f : plan.cfFiles()) {
            CurseForge.FileInfo fi0 = infos.get(f[1]);
            if (fi0 == null) fi0 = cf.fileOne(f[0], f[1]);   // пакетний запит міг його не повернути
            final CurseForge.FileInfo fi = fi0;
            CurseForge.ModMeta mm = metas.get(f[0]);
            String label = mm != null && !mm.name().isEmpty() ? mm.name() : "projectID " + f[0];
            int cls = mm == null ? 6 : mm.classId();
            String slug = mm == null ? "" : mm.slug().replaceAll("[^A-Za-z0-9._-]", "");
            String page = slug.isEmpty() ? "https://www.curseforge.com/projects/" + f[0]
                    : "https://www.curseforge.com/minecraft/" + cfPath(cls) + "/" + slug + "/files/" + f[1];
            if (cls == 17) { manual.add(label + " — " + I18n.t("мапа, постав вручну") + ": " + page); continue; }
            if (fi == null) { failed.add(label + I18n.t(" — файл більше не існує на CurseForge")); continue; }
            String folder = folderFor(cls);
            String fn = ModApi.safeName(fi.filename());
            if (blocked(fn)) { failed.add(label + I18n.t(" — тип файлу не дозволено")); continue; }
            Path dir = inst.resolve(folder);
            if (folder.equals("mods")) reg.setProperty("mod:cf:" + f[0], fn);
            final long modId = f[0], fileId = f[1];
            jobs.add(() -> {
                // Адреси по черзі: з API → окремий запит API → прямий CDN (для модів із забороною «сторонніх запускачів»).
                // Усе лише з довірених доменів CurseForge, контрольна сума з API перевіряється завжди, коли вона є.
                List<String> urls = new ArrayList<>();
                if (fi.url() != null && !fi.url().isEmpty()) urls.add(fi.url());
                if (urls.isEmpty()) { String u = cf.apiDownloadUrl(modId, fileId); if (u != null) urls.add(u); }
                urls.addAll(CurseForge.cdnUrls(fileId, fi.filename()));
                Exception last = null;
                for (String u : urls) {
                    if (!ModApi.trustedHost(u, true)) continue;
                    try {
                        eng.download(u, ModApi.child(dir, fn), fi.sha1(), cancel);
                        return null;
                    } catch (Engine.Cancelled c) {
                        throw c;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (fi.url() == null || fi.url().isEmpty()) {
                    // справді закрито автором — лишається тільки вручну
                    lateManual.add(label + " (" + fi.filename() + "): " + page);
                    return null;
                }
                return label + " — " + (last == null ? "" : last.getMessage());
            });
        }
        int total = plan.cfFiles().size();
        List<String> fails = parallel(jobs, pg, cancel);
        failed.addAll(fails);
        manual.addAll(lateManual);
        if (!reg.isEmpty()) {
            Files.createDirectories(modsDir);
            Properties cur = ModApi.loadReg(modsDir);
            cur.putAll(reg);
            ModApi.saveReg(modsDir, cur);
        }
        return new Report(total, total - failed.size() - manual.size(), failed, manual);
    }

    /** Збірка ставиться в існуючу — не чіпаємо світи й особисті налаштування гри, якщо вони вже є. */
    private static boolean keepUserData(Path inst, String rel) {
        String r = rel.toLowerCase(Locale.ROOT);
        boolean mine = r.startsWith("saves/") || r.equals("options.txt") || r.equals("optionsof.txt") || r.equals("servers.dat");
        if (!mine) return false;
        try { return Files.exists(Engine.under(inst, rel)); } catch (IOException e) { return true; }
    }

    private void extractOverrides(Path archive, Path inst, List<String> prefixes, BooleanSupplier cancel) throws Exception {
        long total = 0;
        int count = 0;
        try (ZipFile z = new ZipFile(archive.toFile())) {
            for (String pre : prefixes) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    if (cancel.getAsBoolean()) throw new Engine.Cancelled();
                    ZipEntry e = en.nextElement();
                    String name = e.getName().replace('\\', '/');
                    if (e.isDirectory() || !name.startsWith(pre + "/")) continue;
                    String rel = name.substring(pre.length() + 1);
                    if (rel.isEmpty() || blocked(rel)) continue;
                    if (keepUserData(inst, rel)) continue;
                    if (++count > MAX_ENTRIES) throw new IOException(I18n.t("В архіві забагато файлів"));
                    Path dest;
                    try { dest = Engine.under(inst, rel); } catch (IOException bad) { continue; }
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = z.getInputStream(e); OutputStream out = Files.newOutputStream(dest)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            total += n;
                            if (total > MAX_UNPACKED) throw new IOException(I18n.t("Збірка після розпаковування завелика"));
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
        }
    }

    private void writeReport(Path inst, Report rep) {
        if (rep.clean()) return;
        try {
            StringBuilder sb = new StringBuilder();
            if (!rep.manual().isEmpty()) {
                sb.append("# Автори заборонили завантаження сторонніми запускачами — завантаж ці файли на сайті й поклади в папку mods:\n");
                for (String s : rep.manual()) sb.append(s).append('\n');
                sb.append('\n');
            }
            if (!rep.failed().isEmpty()) {
                sb.append("# Не вдалося завантажити:\n");
                for (String s : rep.failed()) sb.append(s).append('\n');
            }
            Files.writeString(inst.resolve("PaxLauncher-missing-mods.txt"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }
}
