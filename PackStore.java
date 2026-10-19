import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Облік файлів, які додала збірка модів (Modrinth / CurseForge) у збірку-інстанс.
 * Перед встановленням робиться знімок файлів, після — порівняння; різниця пишеться у .pax-packs/&lt;id&gt;.txt.
 * Завдяки цьому збірку модів можна прибрати окремо, не видаляючи всю збірку зі світами.
 */
final class PackStore {
    private PackStore() { }

    private static final Set<String> SKIP_TOP = Set.of("saves", "logs", "crash-reports", "screenshots", ".pax-packs", "backups");

    static Path dir(Path inst) { return inst.resolve(".pax-packs"); }

    static String key(String id) { return id.replaceAll("[^A-Za-z0-9._-]", "_"); }

    /** Усі звичайні файли збірки (без світів, логів, знімків екрана) — відносні шляхи з '/'. */
    static Set<String> snapshot(Path inst) {
        Set<String> out = new HashSet<>();
        if (!Files.isDirectory(inst)) return out;
        try {
            Files.walkFileTree(inst, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                    if (d.equals(inst)) return FileVisitResult.CONTINUE;
                    return d.getParent().equals(inst) && SKIP_TOP.contains(d.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                    if (a.isRegularFile()) out.add(inst.relativize(f).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { }
        return out;
    }

    static void record(Path inst, String id, Set<String> before, Set<String> after) {
        List<String> added = new ArrayList<>();
        for (String s : after) if (!before.contains(s) && !s.startsWith("PaxLauncher-missing")) added.add(s);
        Collections.sort(added);
        try {
            Files.createDirectories(dir(inst));
            Files.write(dir(inst).resolve(key(id) + ".txt"), added, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    /** Кількість записаних файлів або -1, якщо списку немає (збірку модів поставила стара версія запускача). */
    static int count(Path inst, String id) {
        Path f = dir(inst).resolve(key(id) + ".txt");
        if (!Files.isRegularFile(f)) return -1;
        try { return (int) Files.readAllLines(f, StandardCharsets.UTF_8).stream().filter(s -> !s.isBlank()).count(); }
        catch (IOException e) { return -1; }
    }

    /** Видаляє файли збірки модів і порожні папки, що після них лишилися. Повертає кількість видалених файлів. */
    static int remove(Path inst, String id) throws IOException {
        Path f = dir(inst).resolve(key(id) + ".txt");
        if (!Files.isRegularFile(f)) return 0;
        List<String> rels = Files.readAllLines(f, StandardCharsets.UTF_8);
        int n = 0;
        Set<Path> parents = new TreeSet<>(Comparator.comparingInt((Path p) -> p.getNameCount()).reversed().thenComparing(Path::toString));
        Set<String> modNames = new HashSet<>();
        Path base = inst.toAbsolutePath().normalize();
        for (String rel : rels) {
            if (rel.isBlank()) continue;
            Path p;
            try { p = Engine.under(inst, rel); } catch (IOException bad) { continue; }
            if (p.startsWith(dir(inst).toAbsolutePath().normalize())) continue;
            for (String ext : new String[]{"", ".disabled"}) {
                Path q = p.resolveSibling(p.getFileName() + ext);
                if (Files.isRegularFile(q) && Files.deleteIfExists(q)) n++;
            }
            if (rel.startsWith("mods/")) modNames.add(rel.substring(5));
            for (Path d = p.getParent(); d != null && d.startsWith(base) && !d.equals(base); d = d.getParent()) parents.add(d);
        }
        for (Path d : parents) {
            if (d.getParent() != null && d.getParent().equals(base)) continue;   // mods, config… лишаємо
            try { Files.deleteIfExists(d); } catch (IOException ignored) { }   // лише порожні
        }
        Path modsDir = base.resolve("mods");
        if (!modNames.isEmpty() && Files.isDirectory(modsDir)) {
            Properties reg = ModApi.loadReg(modsDir);
            if (reg.values().removeIf(v -> modNames.contains(String.valueOf(v)))) ModApi.saveReg(modsDir, reg);
        }
        Files.deleteIfExists(f);
        return n;
    }

    static final String[] WIPE_DIRS = {"mods", "config", "defaultconfigs", "kubejs", "scripts", "resourcepacks", "shaderpacks"};

    /** Для збірок модів без списку файлів: очищає папки модів і налаштувань, світи та options лишаються. */
    static int wipe(Path inst) throws IOException {
        int[] n = {0};
        for (String dn : WIPE_DIRS) {
            Path d = inst.resolve(dn);
            if (!Files.isDirectory(d)) continue;
            try (var w = Files.walk(d)) {
                for (Path x : (Iterable<Path>) w.sorted(Comparator.reverseOrder())::iterator) {
                    if (x.equals(d)) continue;
                    boolean f = Files.isRegularFile(x, LinkOption.NOFOLLOW_LINKS);
                    if (Files.deleteIfExists(x) && f) n[0]++;
                }
            }
        }
        return n[0];
    }

    static void forget(Path inst, String id) {
        try { Files.deleteIfExists(dir(inst).resolve(key(id) + ".txt")); } catch (IOException ignored) { }
    }
}
