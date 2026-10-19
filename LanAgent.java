import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.jar.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Java-агент, який лаунчер підключає до гри хоста. Коли хост зайшов у обраний світ, агент сам відкриває його для мережі
 * на порту сесії (як кнопка «Відкрити для мережі»). Назви класів Minecraft шукаються за формою методів, тож це працює
 * і з обфускованими, і з модовими збірками. Агент збирається один раз із вбудованого коду (потрібна Java з компілятором).
 */
final class LanAgent {
    private static final String VER = "8";

    private LanAgent() { }

    static Path ensure(Path root) throws Exception {
        Path jar = root.resolve("runtime").resolve("pax-lan-agent-" + VER + ".jar");
        if (Files.isRegularFile(jar) && Files.size(jar) > 0) return jar;
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new IOException("для авто-відкриття світу лаунчер має працювати на Java з компілятором (JDK)");
        Path tmp = Files.createTempDirectory("paxagent");
        try {
            Path src = tmp.resolve("PaxLanAgent.java");
            Files.writeString(src, SOURCE, StandardCharsets.UTF_8);
            Path out = tmp.resolve("out");
            Files.createDirectories(out);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int rc = jc.run(null, null, err, "-encoding", "UTF-8", "--release", "8", "-d", out.toString(), src.toString());
            if (rc != 0) rc = jc.run(null, null, err, "-encoding", "UTF-8", "--release", "11", "-d", out.toString(), src.toString());
            if (rc != 0) rc = jc.run(null, null, err, "-encoding", "UTF-8", "-d", out.toString(), src.toString());
            if (rc != 0) throw new IOException("не вдалося зібрати агент: " + err.toString(StandardCharsets.UTF_8));
            Manifest mf = new Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            mf.getMainAttributes().putValue("Premain-Class", "pax.lan.PaxLanAgent");
            mf.getMainAttributes().putValue("Agent-Class", "pax.lan.PaxLanAgent");
            Files.createDirectories(jar.getParent());
            Path part = jar.resolveSibling(jar.getFileName() + ".part");
            try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(part), mf);
                 var st = Files.walk(out)) {
                for (Path c : (Iterable<Path>) st::iterator) {
                    if (!c.getFileName().toString().endsWith(".class")) continue;
                    String entry = out.relativize(c).toString().replace('\\', '/');
                    jo.putNextEntry(new JarEntry(entry));
                    jo.write(Files.readAllBytes(c));
                    jo.closeEntry();
                }
            }
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
            return jar;
        } finally {
            try (var st = Files.walk(tmp)) {
                st.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }

    private static final String SOURCE = """
package pax.lan;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PaxLanAgent {
    public static void premain(String args, Instrumentation inst) { begin(args, inst); }
    public static void agentmain(String args, Instrumentation inst) { begin(args, inst); }

    private static Instrumentation INST;

    private static void say(String s) { System.out.println("[PaxLan] " + s); }

    private static void begin(final String args, final Instrumentation inst) {
        if (args == null) return;
        final String[] a;
        try {
            List<String> ls = Files.readAllLines(Paths.get(args), StandardCharsets.UTF_8);
            if (ls.size() < 4) return;
            a = new String[] { ls.get(0).trim(), ls.get(1).trim(), ls.get(2).trim(), ls.get(3).trim(),
                    ls.size() > 4 ? ls.get(4).trim() : "0" };
        } catch (Throwable e) {
            say("FAIL config: " + e);
            return;
        }
        INST = inst;
        say("LOADED");
        Thread t = new Thread(new Runnable() {
            public void run() { loop(a, inst); }
        }, "pax-lan");
        t.setDaemon(true);
        t.start();
    }

    private static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Throwable e) { return -1L; }
    }

    private static void loop(String[] a, Instrumentation inst) {
        int port;
        try { port = Integer.parseInt(a[0]); } catch (Throwable e) { say("FAIL port"); return; }
        Path lock = Paths.get(a[1], a[2], "session.lock");
        Path flag = Paths.get(a[3]);
        long start = System.currentTimeMillis() - 5000L;
        boolean offline = "1".equals(a[4]);
        long end = System.currentTimeMillis() + 8L * 60L * 60L * 1000L;
        Method pub = null;
        Method getSrv = null;
        Method gameType = null;
        Field self = null;
        boolean legacy = false;
        Method auth = null;
        boolean authTried = false;
        Object cur = null;
        long seenAt = 0L;
        long lastLocate = 0L;
        long nextTry = 0L;
        boolean done = false;
        int fails = 0;
        int lastPort = -1;
        int[] base = null;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(1000L); } catch (InterruptedException e) { return; }
            long now = System.currentTimeMillis();
            try {
                // агент «озбройюється», коли гравець уже у світі: або лаунчер побачив «joined the game», або свіжий session.lock
                long fm = mtime(flag);
                long lm = mtime(lock);
                boolean armed = fm >= start || (lm >= start && now - lm > 60000L);
                if (!armed) continue;
                if (pub == null) {
                    if (now - lastLocate < 8000L) continue;
                    lastLocate = now;
                    Object[] f = locate(inst);
                    if (f == null) { say("NOCLASS"); continue; }
                    pub = (Method) f[0];
                    getSrv = (Method) f[1];
                    self = (Field) f[2];
                    legacy = ((Boolean) f[3]).booleanValue();
                    gameType = legacy ? findGameType(pub.getDeclaringClass(), pub.getParameterTypes()[0]) : null;
                    say("READY " + (legacy ? "legacy " : "") + pub.getDeclaringClass().getName());
                }
                Object mc = self.get(null);
                Object server = mc == null ? null : getSrv.invoke(mc);
                if (server == null) { cur = null; continue; }
                if (server != cur) {
                    cur = server;
                    seenAt = now;
                    done = false;
                    fails = 0;
                    lastPort = -1;
                    nextTry = 0L;
                    base = snapshot(server, pub.getDeclaringClass());
                    say("WORLD");
                }
                // світ могли відкрити вручну (Пауза → Відкрити для мережі): беремо порт із самого сервера, мова чату не важлива
                int pp = livePort(server, pub.getDeclaringClass(), base);
                if (pp > 0 && pp != lastPort) { lastPort = pp; done = true; say("PORT " + pp); }
                if (done || now < nextTry || now - seenAt < 2500L) continue;
                if (offline && !authTried) {
                    authTried = true;
                    try {
                        auth = findAuthSetter(pub.getDeclaringClass());
                        if (auth == null) say("NOOFFLINE setter not found");
                    } catch (Throwable e) { say("NOOFFLINE " + e); }
                }
                if (offline && auth != null) {
                    try { auth.invoke(server, Boolean.FALSE); say("OFFLINE on"); }
                    catch (Throwable e) { say("NOOFFLINE " + e); }
                }
                if (legacy) {
                    Object gt = gameType == null ? null : gameType.invoke(server);
                    if (gt == null) {
                        Object[] cs = pub.getParameterTypes()[0].getEnumConstants();
                        gt = cs != null && cs.length > 1 ? cs[1] : (cs != null && cs.length > 0 ? cs[0] : null);
                    }
                    Object res = pub.invoke(server, gt, Boolean.FALSE);
                    int p = 0;
                    try { p = Integer.parseInt(String.valueOf(res).trim()); } catch (Throwable ignored) { }
                    done = true;
                    if (p > 0) { lastPort = p; say("PORT " + p); }
                    else say("PUBLISHED");
                } else {
                    Object res = pub.invoke(server, null, Boolean.FALSE, Integer.valueOf(port));
                    if (Boolean.TRUE.equals(res)) { done = true; lastPort = port; say("PUBLISHED " + port); say("PORT " + port); }
                    else {
                        int now2 = livePort(server, pub.getDeclaringClass(), base);
                        if (now2 > 0) { done = true; lastPort = now2; say("PORT " + now2); }
                        else {
                            fails++;
                            say("RETRY publish returned false (port " + port + " busy?)");
                            if (fails >= 2) port = freePort();
                            nextTry = now + (fails < 30 ? 2000L : 10000L);
                        }
                    }
                }
            } catch (Throwable e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                fails++;
                if (fails <= 3 || fails % 10 == 0) say("RETRY " + c);
                nextTry = System.currentTimeMillis() + (fails < 30 ? 2000L : 10000L);
            }
        }
    }

    private static int freePort() {
        try {
            java.net.ServerSocket s = new java.net.ServerSocket(0);
            try { return s.getLocalPort(); } finally { s.close(); }
        } catch (Throwable e) { return 25565; }
    }

    /** Значення int-полів сервера на момент появи світу (publishedPort там -1, лічильники — 0 і більше). */
    private static int[] snapshot(Object server, Class<?> c) {
        try {
            Field[] fs = c.getDeclaredFields();
            int[] r = new int[fs.length];
            for (int i = 0; i < fs.length; i++) {
                r[i] = Integer.MIN_VALUE;
                if (fs[i].getType() != int.class || Modifier.isStatic(fs[i].getModifiers())) continue;
                try { fs[i].setAccessible(true); r[i] = fs[i].getInt(server); } catch (Throwable ignored) { }
            }
            return r;
        } catch (Throwable e) { return null; }
    }

    /** Порт, на якому світ відкрито для мережі, або -1. Шукає int-поле, що було -1, а стало портом. */
    private static int livePort(Object server, Class<?> c, int[] base) {
        if (base == null) return -1;
        try {
            Field[] fs = c.getDeclaredFields();
            for (int i = 0; i < fs.length && i < base.length; i++) {
                if (base[i] != -1) continue;
                try {
                    int v = fs[i].getInt(server);
                    if (v >= 1024 && v <= 65535) return v;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    /** Метод без аргументів, що повертає ігровий режим (для старих версій, де shareToLAN не приймає null). */
    private static Method findGameType(Class<?> c, Class<?> type) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == type && !Modifier.isStatic(m.getModifiers())) {
                    try { open(null, k); m.setAccessible(true); return m; } catch (Throwable ignored) { }
                }
            }
        }
        return null;
    }

    private static void skip(DataInputStream d, int n) throws java.io.IOException {
        while (n > 0) {
            int s = d.skipBytes(n);
            if (s <= 0) { d.readByte(); s = 1; }
            n -= s;
        }
    }

    /**
     * Шукає метод setUsesAuthentication(boolean) без знання назв: у методі запуску вбудованого сервера
     * він завжди викликається першим у ланцюжку «this.setX(true); this.setY(true); ...».
     */
    private static Method findAuthSetter(Class<?> srv) throws Exception {
        ClassLoader cl = srv.getClassLoader();
        if (cl == null) return null;
        InputStream in = cl.getResourceAsStream(srv.getName().replace('.', '/') + ".class");
        if (in == null) return null;
        DataInputStream d = new DataInputStream(new BufferedInputStream(in));
        d.readInt();
        d.readUnsignedShort();
        d.readUnsignedShort();
        int n = d.readUnsignedShort();
        String[] utf = new String[n];
        int[] a = new int[n];
        int[] b = new int[n];
        for (int i = 1; i < n; i++) {
            int t = d.readUnsignedByte();
            switch (t) {
                case 1: utf[i] = d.readUTF(); break;
                case 3: case 4: d.readInt(); break;
                case 5: case 6: d.readLong(); i++; break;
                case 7: case 8: case 16: case 19: case 20: a[i] = d.readUnsignedShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18: a[i] = d.readUnsignedShort(); b[i] = d.readUnsignedShort(); break;
                case 15: d.readUnsignedByte(); d.readUnsignedShort(); break;
                default: return null;
            }
        }
        d.readUnsignedShort();
        d.readUnsignedShort();
        d.readUnsignedShort();
        int ic = d.readUnsignedShort();
        for (int i = 0; i < ic; i++) d.readUnsignedShort();
        int fc = d.readUnsignedShort();
        for (int i = 0; i < fc; i++) {
            d.readUnsignedShort();
            d.readUnsignedShort();
            d.readUnsignedShort();
            int ac = d.readUnsignedShort();
            for (int k = 0; k < ac; k++) { d.readUnsignedShort(); skip(d, d.readInt()); }
        }
        String found = null;
        int mc = d.readUnsignedShort();
        for (int m = 0; m < mc; m++) {
            d.readUnsignedShort();
            d.readUnsignedShort();
            String desc = utf[d.readUnsignedShort()];
            int ac = d.readUnsignedShort();
            for (int k = 0; k < ac; k++) {
                String an = utf[d.readUnsignedShort()];
                int len = d.readInt();
                if (found == null && "Code".equals(an) && "()Z".equals(desc)) {
                    d.readUnsignedShort();
                    d.readUnsignedShort();
                    int cl2 = d.readInt();
                    byte[] code = new byte[cl2];
                    d.readFully(code);
                    skip(d, len - 8 - cl2);
                    found = chain(code, a, b, utf);
                } else {
                    skip(d, len);
                }
            }
        }
        if (found == null) return null;
        for (Class<?> c = srv; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(found) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == boolean.class
                        && !Modifier.isStatic(m.getModifiers())) {
                    open(null, c);
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static String boolSetter(byte[] code, int i, int[] a, int[] b, String[] utf) {
        if (i + 4 > code.length) return null;
        if (code[i] != 0x04 || (code[i + 1] & 0xFF) != 0xB6) return null;
        int idx = ((code[i + 2] & 0xFF) << 8) | (code[i + 3] & 0xFF);
        if (idx <= 0 || idx >= a.length) return null;
        int nat = b[idx];
        if (nat <= 0 || nat >= a.length) return null;
        String nm = utf[a[nat]];
        String ds = utf[b[nat]];
        return "(Z)V".equals(ds) ? nm : null;
    }

    private static String chain(byte[] code, int[] a, int[] b, String[] utf) {
        for (int i = 0; i + 9 <= code.length; i++) {
            String first = boolSetter(code, i, a, b, utf);
            if (first == null) continue;
            if ((code[i + 4] & 0xFF) == 0x2A && boolSetter(code, i + 5, a, b, utf) != null) return first;
        }
        return null;
    }

    private static boolean candidate(Class<?> c) {
        if (c.isArray() || c.isPrimitive() || c.isInterface()) return false;
        String n = c.getName();
        return n.indexOf('.') < 0 || n.startsWith("net.minecraft.");
    }

    private static Object[] locate(Instrumentation inst) {
        Class<?>[] all = inst.getAllLoadedClasses();
        Class<?> srv = null;
        Method pub = null;
        boolean legacy = false;
        // 1.16+: publishServer(GameType, boolean, int) -> boolean; 1.12–1.15: shareToLAN(GameType, boolean) -> String
        for (int pass = 0; pass < 2 && pub == null; pass++) {
            for (Class<?> c : all) {
                if (!candidate(c)) continue;
                try {
                    for (Method m : c.getDeclaredMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        boolean ok = pass == 0
                                ? p.length == 3 && p[0].isEnum() && p[1] == boolean.class && p[2] == int.class && m.getReturnType() == boolean.class
                                : p.length == 2 && p[0].isEnum() && p[1] == boolean.class && m.getReturnType() == String.class;
                        if (ok) { srv = c; pub = m; legacy = pass == 1; break; }
                    }
                } catch (Throwable ignored) { }
                if (pub != null) break;
            }
        }
        if (pub == null) return null;
        open(inst, srv);
        try { pub.setAccessible(true); } catch (Throwable ignored) { }
        for (Class<?> c : all) {
            if (!candidate(c) || c == srv) continue;
            try {
                Field self = null;
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && f.getType() == c) { self = f; break; }
                }
                if (self == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 0 && m.getReturnType() == srv && !Modifier.isStatic(m.getModifiers())) {
                        open(inst, c);
                        self.setAccessible(true);
                        m.setAccessible(true);
                        return new Object[] { pub, m, self, Boolean.valueOf(legacy) };
                    }
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void open(Instrumentation inst, Class<?> target) {
        if (inst == null) inst = INST;
        if (inst == null) return;
        try {
            Class<?> modC = Class.forName("java.lang.Module");
            Method getModule = Class.class.getMethod("getModule");
            Object mod = getModule.invoke(target);
            if (!((Boolean) modC.getMethod("isNamed").invoke(mod)).booleanValue()) return;
            Object agentMod = getModule.invoke(PaxLanAgent.class);
            Set<String> pk = (Set<String>) modC.getMethod("getPackages").invoke(mod);
            Map<String, Set<Object>> open = new HashMap<String, Set<Object>>();
            for (String p : pk) open.put(p, Collections.singleton(agentMod));
            Method redef = Instrumentation.class.getMethod("redefineModule", modC, Set.class, Map.class, Map.class, Set.class, Map.class);
            redef.invoke(inst, mod, Collections.emptySet(), open, open, Collections.emptySet(), Collections.emptyMap());
        } catch (Throwable ignored) { }
    }
}
""";
}
