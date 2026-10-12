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

final class JavaFinder {
    record Inst(String label, String path, int major) {
        Inst(String label, String path) { this(label, path, 0); }
        @Override public String toString() { return label; }
    }

    private static volatile List<Inst> cache;

    static synchronized List<Inst> cached() {
        List<Inst> c = cache;
        return c != null ? c : scan();
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

    private static String[] info(Path exe) {
        String ver = "", vendor = "", home = "";
        try {
            Path rel = exe.getParent().getParent().resolve("release");
            if (Files.isRegularFile(rel)) {
                String t = Files.readString(rel, StandardCharsets.UTF_8);
                ver = quoted(t, "JAVA_VERSION");
                vendor = quoted(t, "IMPLEMENTOR");
                home = exe.getParent().getParent().toRealPath().toString();
            }
        } catch (Exception ignored) { }
        if ((ver.isEmpty() || home.isEmpty()) && !Files.isWritable(exe)) {
            try {
                Process pr = new ProcessBuilder(exe.toString(), "-XshowSettings:properties", "-version").redirectErrorStream(true).start();
                String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!pr.waitFor(5, TimeUnit.SECONDS)) pr.destroyForcibly();
                for (String line : out.split("\\R")) {
                    String l = line.trim();
                    if (l.startsWith("java.home = ")) home = l.substring(12).trim();
                    else if (ver.isEmpty() && l.startsWith("java.version = ")) ver = l.substring(15).trim();
                    else if (vendor.isEmpty() && l.startsWith("java.vendor = ")) vendor = l.substring(14).trim();
                }
                if (!home.isEmpty()) try { home = Path.of(home).toRealPath().toString(); } catch (Exception ignored) { }
            } catch (Exception ignored) { }
        }
        return new String[]{ver, vendor, home};
    }

    private static int rank(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.contains("appdata") || p.contains(".minecraft") || p.contains("prismlauncher") || p.contains("\\.jdks") || p.contains("/.jdks")) return 1;
        if (p.contains("common files")) return 2;
        return 0;
    }

    static List<Inst> scan() {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        String exeName = win ? "java.exe" : "java";
        String home = System.getProperty("user.home", "");

        List<Path> exes = new ArrayList<>();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) exes.add(Path.of(jh, "bin", exeName));
        try { exes.add(Path.of(System.getProperty("java.home"), "bin", exeName)); } catch (Exception ignored) { }
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(File.pathSeparator)) {
            if (!d.isBlank()) try { exes.add(Path.of(d.replace("\"", ""), exeName)); } catch (Exception ignored) { }
        }

        List<Path> roots = new ArrayList<>();
        roots.add(PaxLauncher.ROOT.resolve("runtime"));
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

        Set<String> seen = new HashSet<>();

        List<Object[]> found = new ArrayList<>();
        for (Path e : exes) {
            try {
                if (!Files.isRegularFile(e)) continue;
                if (!seen.add(e.toRealPath().toString())) continue;
                String[] inf = info(e);
                found.add(new Object[]{major(inf[0]), inf[0], inf[1], e.toString(), inf[2]});
            } catch (Exception ignored) { }
        }
        found.sort((a, b) -> {
            int c = Integer.compare((Integer) b[0], (Integer) a[0]);
            if (c != 0) return c;
            c = Integer.compare(rank((String) a[3]), rank((String) b[3]));
            return c != 0 ? c : ((String) a[3]).compareToIgnoreCase((String) b[3]);
        });

        Set<String> homes = new HashSet<>();
        Set<Integer> majors = new HashSet<>();
        List<Inst> out = new ArrayList<>();
        for (Object[] f : found) {
            int mj = (Integer) f[0];
            String ver = (String) f[1], vendor = (String) f[2], exe = (String) f[3], jhome = (String) f[4];
            if (!jhome.isEmpty() && !homes.add(jhome.toLowerCase(Locale.ROOT))) continue;
            if (mj > 0 && !majors.add(mj)) continue;
            String label = mj > 0 ? "Java " + mj : "Java";
            label += "  · " + exe;
            out.add(new Inst(label, exe, mj));
        }
        cache = out;
        return out;
    }
}
