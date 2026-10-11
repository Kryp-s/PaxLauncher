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
