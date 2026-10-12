import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

final class Vault {
    static final String PREFIX = "dpapi:";
    private static final Map<String, String> cache = new ConcurrentHashMap<>();

    private static boolean win() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    static boolean available() { return win(); }

    static boolean sensitive(String key, String val) {
        return (key.endsWith(".token") || key.endsWith(".extra"))
                && val != null && !val.isEmpty() && !val.equals("0");
    }

    private static String script(String mode) {
        return "$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Security;"
                + "$e=[Text.Encoding]::UTF8.GetBytes('PaxLauncher');"
                + "foreach($l in ([Console]::In.ReadToEnd() -split [char]10)){$l=$l.Trim();if($l -eq ''){continue};"
                + "try{$r=[Security.Cryptography.ProtectedData]::" + mode
                + "([Convert]::FromBase64String($l),$e,'CurrentUser');"
                + "[Console]::Out.WriteLine([Convert]::ToBase64String($r))}catch{[Console]::Out.WriteLine('ERR')}}";
    }

    private static List<String> run(String mode, List<String> lines) {
        if (!win() || lines.isEmpty()) return null;
        try {
            Process pr = new ProcessBuilder(powershell(), "-NoProfile", "-NonInteractive", "-Command", script(mode))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (Writer w = new OutputStreamWriter(pr.getOutputStream(), StandardCharsets.US_ASCII)) {
                for (String l : lines) w.write(l + "\n");
            }
            CompletableFuture<byte[]> rd = CompletableFuture.supplyAsync(() -> {
                try { return pr.getInputStream().readAllBytes(); } catch (IOException e) { return new byte[0]; }
            });
            byte[] out;
            try { out = rd.get(30, TimeUnit.SECONDS); }
            catch (TimeoutException te) { pr.destroyForcibly(); return null; }
            if (!pr.waitFor(5, TimeUnit.SECONDS) || pr.exitValue() != 0) return null;
            List<String> res = new ArrayList<>();
            for (String l : new String(out, StandardCharsets.US_ASCII).split("\\r?\\n")) if (!l.trim().isEmpty()) res.add(l.trim());
            return res.size() == lines.size() ? res : null;
        } catch (Exception e) { return null; }
    }

    private static String powershell() {
        String root = System.getenv("SystemRoot");
        if (root != null && !root.isEmpty()) {
            Path p = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(p)) return p.toString();
        }
        return "powershell.exe";
    }

    private static volatile boolean warned;
    static volatile boolean insecure;
    static volatile Runnable onInsecure;

    static void writeProps(Path file, Properties p, String comment, boolean secret) throws IOException {
        Files.createDirectories(file.getParent());
        if (secret && !win()) restrictDir(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (secret && !win()) {
            try { Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException ignored) { }
        }
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { p.store(w, comment); }
        try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    static void decryptAll(Properties p) {
        List<String> keys = new ArrayList<>(), lines = new ArrayList<>();
        for (String k : p.stringPropertyNames()) {
            String v = p.getProperty(k);
            if (v.startsWith(PREFIX)) { keys.add(k); lines.add(v.substring(PREFIX.length())); }
        }
        List<String> res = run("Unprotect", lines);
        if (res == null) return;
        for (int i = 0; i < keys.size(); i++) {
            if (res.get(i).equals("ERR")) continue;
            try {
                String plain = new String(Base64.getDecoder().decode(res.get(i)), StandardCharsets.UTF_8);
                cache.put(plain, p.getProperty(keys.get(i)));
                p.setProperty(keys.get(i), plain);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    static boolean hasPlain(Properties p) {
        for (String k : p.stringPropertyNames()) {
            String v = p.getProperty(k);
            if (sensitive(k, v) && !v.startsWith(PREFIX)) return true;
        }
        return false;
    }

    static void encryptAll(Properties out) {
        List<String> keys = new ArrayList<>(), lines = new ArrayList<>();
        for (String k : out.stringPropertyNames()) {
            String v = out.getProperty(k);
            if (!sensitive(k, v) || v.startsWith(PREFIX)) continue;
            String c = cache.get(v);
            if (c != null) { out.setProperty(k, c); continue; }
            keys.add(k);
            lines.add(Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8)));
        }
        List<String> res = run("Protect", lines);
        if (res == null) {
            if (!warned && win() && !lines.isEmpty()) {
                warned = true;
                insecure = true;
                PaxLauncher.warn("Vault: PowerShell/DPAPI недоступні — токени збережено без шифрування", null);
                Runnable h = onInsecure;
                if (h != null) h.run();
            }
            return;
        }
        for (int i = 0; i < keys.size(); i++) {
            if (res.get(i).equals("ERR")) continue;
            String plain = out.getProperty(keys.get(i));
            String enc = PREFIX + res.get(i);
            cache.put(plain, enc);
            out.setProperty(keys.get(i), enc);
        }
    }

    static void restrictDir(Path d) {
        try { Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException | IOException ignored) { }
    }

    static void restrict(Path f) {
        try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException | IOException ignored) { }
    }
}
