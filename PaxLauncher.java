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

public class PaxLauncher {
    static final Path ROOT = resolveRoot();

    static Path resolveRoot() {
        Path home = Path.of(System.getProperty("user.home"));
        Path now = home.resolve("paxlauncher"), old = home.resolve(".pax-launcher");
        if (!Files.exists(now) && Files.isDirectory(old)) {
            try {
                Files.move(old, now);
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
                return old;
            }
        }
        return now;
    }

    static void warn(String msg, Throwable t) {
        try {
            Files.createDirectories(ROOT);
            Path lf = ROOT.resolve("error.log");
            if (Files.exists(lf) && Files.size(lf) > 100_000) Files.delete(lf);
            Files.writeString(lf, java.time.LocalDateTime.now() + " " + msg + (t == null ? "" : " — " + t) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private static java.nio.channels.FileChannel lockCh;

    static boolean singleInstance() {
        try {
            Files.createDirectories(ROOT);
            lockCh = java.nio.channels.FileChannel.open(ROOT.resolve("launcher.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return lockCh.tryLock() != null;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    static final String[] LEAN_FLAGS = {
        "-XX:+IgnoreUnrecognizedVMOptions",
        "-XX:+UseSerialGC",
        "-Xms8m", "-Xmx256m",
        "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25",
        "-XX:TieredStopAtLevel=1",
        "-XX:CICompilerCount=1",
        "-XX:ReservedCodeCacheSize=32m",
        "-XX:MaxDirectMemorySize=64m",
        "-XX:-UsePerfData",
        "-Dsun.java2d.d3d=false",
        "-Dsun.java2d.noddraw=true",
    };

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
            return !pr.waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return false;
        }
    }

    static Path buildCacheJar(Path src) {
        try {
            Path dir = ROOT.resolve("cache");
            Files.createDirectories(dir);
            Path jar = dir.resolve("PaxLauncher-lean.jar"), stamp = dir.resolve("PaxLauncher-lean.stamp");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var ls = Files.list(src.toAbsolutePath().getParent())) {
                for (Path f : (Iterable<Path>) ls.filter(x -> x.toString().endsWith(".java")).sorted()::iterator) {
                    md.update(f.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update(Files.readAllBytes(f));
                    md.update((byte) 0);
                }
            }
            String st = new java.math.BigInteger(1, md.digest()).toString(16) + ":" + Runtime.version();
            if (Files.isRegularFile(jar) && Files.isRegularFile(stamp)) {
                String[] sl = Files.readString(stamp).split("\n", 2);
                if (sl.length == 2 && sl[0].equals(st) && sl[1].trim().equalsIgnoreCase(Engine.digest(jar, "SHA-256"))) return jar;
            }
            JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
            if (jc == null) return null;
            Path out = Files.createTempDirectory("paxc");
            try {
                List<String> args = new ArrayList<>(List.of("-nowarn", "-encoding", "UTF-8", "-d", out.toString()));
            try (var ls = Files.list(src.toAbsolutePath().getParent())) {
                ls.filter(f -> f.toString().endsWith(".java")).forEach(f -> args.add(f.toString()));
            }
            if (jc.run(null, null, null, args.toArray(new String[0])) != 0) return null;
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
                Files.writeString(stamp, st + "\n" + Engine.digest(jar, "SHA-256"));
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
        if (!singleInstance()) {
            JOptionPane.showMessageDialog(null, "PaxLauncher уже запущено.", "PaxLauncher", JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
            return;
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        System.setProperty("sun.java2d.d3d", "false");
        javax.imageio.ImageIO.setUseCache(false);
        Settings boot = new Settings(ROOT.resolve("launcher.properties"));
        I18n.init(boot.get("lang", "uk"));
        Theme.apply("light".equals(boot.get("theme", "dark")), Theme.parseHex(boot.get("accent", ""), Theme.DEFAULT_ACCENT));
        Theme.applyUI();
        SwingUtilities.invokeLater(() -> {
            LauncherWindow w = new LauncherWindow();
            w.setVisible(true);
            Vault.onInsecure = () -> SwingUtilities.invokeLater(() -> w.msg(
                    "Не вдалося зашифрувати токени (PowerShell/DPAPI недоступні) — вони збережені відкритим текстом у accounts.properties. "
                    + "Розблокуй PowerShell або вийди з акаунтів, якщо цей ПК не довірений."));
            if (Vault.insecure) Vault.onInsecure.run();
            Updater.start(w, boot);
        });
    }
}
