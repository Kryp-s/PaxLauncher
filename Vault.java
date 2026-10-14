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

    static boolean available() { return win() || (linux() && linuxKey() != null); }

    // ───────── Linux: AES-256-GCM, майстер-ключ лежить у системній в'язці ключів (libsecret / secret-tool) ─────────

    static final String LPREFIX = "lnx:";
    private static byte[] lkey;
    private static boolean lkeyTried;
    private static boolean lwarned;

    private static boolean linux() { return System.getProperty("os.name", "").toLowerCase().contains("linux"); }

    private static String secretTool() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String d : path.split(File.pathSeparator)) {
            if (d.isEmpty()) continue;
            Path f = Path.of(d, "secret-tool");
            if (Files.isRegularFile(f) && Files.isExecutable(f)) return f.toString();
        }
        return null;
    }

    /** {код виходу, stdout, stderr} або null, якщо процес не вдалося запустити чи він завис. */
    private static String[] exec(List<String> cmd, String stdin) {
        try {
            Process pr = new ProcessBuilder(cmd).start();
            if (stdin != null) try (OutputStream o = pr.getOutputStream()) { o.write(stdin.getBytes(StandardCharsets.UTF_8)); }
            else pr.getOutputStream().close();
            CompletableFuture<byte[]> so = CompletableFuture.supplyAsync(() -> {
                try { return pr.getInputStream().readAllBytes(); } catch (IOException e) { return new byte[0]; } });
            CompletableFuture<byte[]> se = CompletableFuture.supplyAsync(() -> {
                try { return pr.getErrorStream().readAllBytes(); } catch (IOException e) { return new byte[0]; } });
            if (!pr.waitFor(30, TimeUnit.SECONDS)) { pr.destroyForcibly(); return null; }
            return new String[]{String.valueOf(pr.exitValue()),
                    new String(so.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim(),
                    new String(se.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim()};
        } catch (Exception e) { return null; }
    }

    /** Майстер-ключ із в'язці ключів (створюється при першому зверненні). null — в'язки немає / вона недоступна. */
    private static synchronized byte[] linuxKey() {
        if (lkeyTried) return lkey;
        lkeyTried = true;
        String st = secretTool();
        if (st == null) return null;
        String[] r = exec(List.of(st, "lookup", "service", "PaxLauncher", "account", "master-key"), null);
        if (r == null) return null;
        try {
            if (r[0].equals("0") && !r[1].isEmpty()) {
                byte[] k = Base64.getDecoder().decode(r[1]);
                if (k.length == 32) return lkey = k;
                return null;
            }
            // Ключ створюємо лише коли його точно немає (тиха відповідь «не знайдено»), а не коли в'язка недоступна чи заблокована —
            // інакше можна затерти справжній ключ і втратити доступ до збережених токенів.
            if (!r[2].isEmpty()) return null;
            byte[] k = new byte[32];
            new java.security.SecureRandom().nextBytes(k);
            String[] w = exec(List.of(st, "store", "--label=PaxLauncher", "service", "PaxLauncher", "account", "master-key"),
                    Base64.getEncoder().encodeToString(k));
            if (w == null || !w[0].equals("0")) return null;
            return lkey = k;
        } catch (IllegalArgumentException e) { return null; }
    }

    private static String lEncrypt(byte[] key, String plain) throws Exception {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
        c.updateAAD("PaxLauncher".getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        return LPREFIX + Base64.getEncoder().encodeToString(all);
    }

    private static String lDecrypt(byte[] key, String enc) throws Exception {
        byte[] all = Base64.getDecoder().decode(enc.substring(LPREFIX.length()));
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, all, 0, 12));
        c.updateAAD("PaxLauncher".getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }

    private static void decryptLinux(Properties p) {
        List<String> keys = new ArrayList<>();
        for (String k : p.stringPropertyNames()) if (p.getProperty(k).startsWith(LPREFIX)) keys.add(k);
        if (keys.isEmpty()) return;
        byte[] key = linuxKey();
        if (key == null) return;
        for (String k : keys) {
            String enc = p.getProperty(k);
            try {
                String plain = lDecrypt(key, enc);
                cache.put(plain, enc);
                p.setProperty(k, plain);
            } catch (Exception ignored) { }
        }
    }

    private static void encryptLinux(Properties out) {
        List<String> keys = new ArrayList<>();
        for (String k : out.stringPropertyNames()) {
            String v = out.getProperty(k);
            if (sensitive(k, v) && !v.startsWith(PREFIX) && !v.startsWith(LPREFIX)) keys.add(k);
        }
        if (keys.isEmpty()) return;
        byte[] key = linuxKey();
        if (key == null) {
            if (!lwarned) {
                lwarned = true;
                PaxLauncher.warn("Vault: в'язка ключів (secret-tool / libsecret) недоступна — токени збережено у файлі з правами 0600 без шифрування. "
                        + "Встанови libsecret-tools і gnome-keyring або KWallet, щоб вони шифрувалися.", null);
            }
            return;
        }
        for (String k : keys) {
            String plain = out.getProperty(k);
            String c = cache.get(plain);
            try {
                if (c == null) { c = lEncrypt(key, plain); cache.put(plain, c); }
                out.setProperty(k, c);
            } catch (Exception ignored) { }
        }
    }

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
        if (linux()) { decryptLinux(p); return; }
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
            if (sensitive(k, v) && !v.startsWith(PREFIX) && !v.startsWith(LPREFIX)) return true;
        }
        return false;
    }

    static void encryptAll(Properties out) {
        if (linux()) { encryptLinux(out); return; }
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
