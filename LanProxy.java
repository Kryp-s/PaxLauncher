import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * Невеликий TCP-проксі сесії «Гра з другом». Слухає порт із коду запрошення й пересилає гравців у світ, який хост
 * відкрив для мережі. На пінг із списку серверів відповідає сам: «Гра з друзями» + логотип лаунчера.
 */
final class LanProxy {
    private ServerSocket ss;
    private volatile int target;
    private volatile boolean closed;
    private final String host;
    private final AtomicInteger players = new AtomicInteger();
    private final Map<String, Socket> conns = new ConcurrentHashMap<>();
    private final Map<String, String> banned = new ConcurrentHashMap<>(); // нік (малими) -> IP на момент бану
    private final String favicon = "data:image/png;base64," + logoBase64();

    LanProxy(String host) { this.host = host == null ? "" : host; }

    int port() { return ss == null ? 0 : ss.getLocalPort(); }

    int players() { return players.get(); }

    void setTarget(int p) { target = p; }

    // ---------- гравці: від'єднання й бани ----------

    List<String> playerNames() {
        List<String> l = new ArrayList<>(conns.keySet());
        Collections.sort(l, String.CASE_INSENSITIVE_ORDER);
        return l;
    }

    List<String> bans() {
        List<String> l = new ArrayList<>(banned.keySet());
        Collections.sort(l);
        return l;
    }

    /** Записи «нік|ip» для збереження між сесіями. */
    List<String> banEntries() {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, String> e : banned.entrySet()) l.add(e.getKey() + "|" + e.getValue());
        Collections.sort(l);
        return l;
    }

    void setBans(Iterable<String> entries) {
        banned.clear();
        for (String e : entries) {
            if (e == null || e.trim().isEmpty()) continue;
            String[] p = e.trim().split("[|]", 2);
            banned.put(p[0].toLowerCase(Locale.ROOT), p.length > 1 ? p[1] : "");
        }
    }

    private static boolean loopback(String ip) {
        return ip == null || ip.isEmpty() || ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1");
    }

    private boolean isBanned(String name, String ip) {
        if (name != null && banned.containsKey(name.toLowerCase(Locale.ROOT))) return true;
        return !loopback(ip) && banned.containsValue(ip);
    }

    boolean kick(String name) {
        Socket s = name == null ? null : conns.get(name);
        if (s == null) return false;
        try { s.close(); } catch (IOException ignored) { }
        return true;
    }

    /** Бан за ніком і за IP-адресою, з якої гравець підключений зараз (локальні адреси не банимо). */
    void ban(String name) {
        if (name == null || name.trim().isEmpty()) return;
        Socket s = conns.get(name);
        String ip = s == null || s.getInetAddress() == null ? "" : s.getInetAddress().getHostAddress();
        banned.put(name.trim().toLowerCase(Locale.ROOT), loopback(ip) ? "" : ip);
        kick(name);
    }

    void unban(String name) {
        if (name != null) banned.remove(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Нік із пакета Login Start: перший рядок після id (працює для всіх версій протоколу). */
    private static String loginName(byte[] pk) {
        try {
            ByteArrayInputStream b = new ByteArrayInputStream(pk);
            if (readVarInt(b) != 0) return null;
            int n = readVarInt(b);
            if (n <= 0 || n > 64) return null;
            return new String(b.readNBytes(n), StandardCharsets.UTF_8);
        } catch (IOException e) { return null; }
    }

    private static void sendDisconnect(OutputStream out, String msg) throws IOException {
        byte[] js = ("{\"text\":\"" + esc(msg) + "\"}").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream pl = new ByteArrayOutputStream();
        writeVarInt(pl, 0);
        writeVarInt(pl, js.length);
        pl.write(js);
        writeVarInt(out, pl.size());
        out.write(pl.toByteArray());
        out.flush();
    }

    /** Чи відкритий світ у грі хоста просто зараз. */
    boolean alive() {
        int t = target;
        if (t <= 0) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", t), 400);
            return true;
        } catch (IOException e) { return false; }
    }

    void start(int port) throws IOException {
        ss = new ServerSocket(port);
        Thread t = new Thread(() -> {
            while (!closed) {
                try {
                    Socket c = ss.accept();
                    Thread h = new Thread(() -> handle(c), "lan-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    if (closed) return;
                }
            }
        }, "lan-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        closed = true;
        target = 0;
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(c.getInputStream()));
            OutputStream out = c.getOutputStream();
            byte[] hs = readPacket(in, 4096);
            if (hs == null) { c.close(); return; }
            ByteArrayInputStream bi = new ByteArrayInputStream(hs);
            if (readVarInt(bi) != 0) { c.close(); return; }
            int proto = readVarInt(bi);
            int sl = readVarInt(bi);
            if (sl < 0 || sl > 1024) { c.close(); return; }
            bi.skip(sl);
            bi.skip(2);
            int next = readVarInt(bi);
            if (next == 1) { status(in, out, proto); c.close(); return; }
            int t = target;
            if (t <= 0 || !alive()) { c.close(); return; }
            byte[] ls = readPacket(in, 8192);
            if (ls == null) { c.close(); return; }
            String pname = loginName(ls);
            String cip = c.getInetAddress() == null ? "" : c.getInetAddress().getHostAddress();
            if (isBanned(pname, cip)) {
                sendDisconnect(out, "Тебе забанено в цій сесії");
                c.close();
                return;
            }
            Socket up = new Socket();
            boolean counted = false;
            try {
                up.connect(new InetSocketAddress("127.0.0.1", t), 3000);
                c.setSoTimeout(0);
                OutputStream uo = up.getOutputStream();
                writeVarInt(uo, hs.length);
                uo.write(hs);
                writeVarInt(uo, ls.length);
                uo.write(ls);
                uo.flush();
                if (pname != null) conns.put(pname, c);
                players.incrementAndGet();
                counted = true;
                Thread a = new Thread(() -> copy(in, uo, c, up), "lan-up");
                a.setDaemon(true);
                a.start();
                copy(up.getInputStream(), out, c, up);
            } finally {
                if (counted) players.decrementAndGet();
                if (pname != null) conns.remove(pname, c);
                try { up.close(); } catch (IOException ignored) { }
                try { c.close(); } catch (IOException ignored) { }
            }
        } catch (Exception e) {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    private static void copy(InputStream in, OutputStream out, Socket a, Socket b) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
        } catch (IOException ignored) {
        } finally {
            try { a.close(); } catch (IOException ignored) { }
            try { b.close(); } catch (IOException ignored) { }
        }
    }

    private void status(DataInputStream in, OutputStream out, int proto) throws IOException {
        readPacket(in, 64);
        boolean act = alive();
        String sec = "\u00a7";
        String desc = (host.isEmpty() ? sec + "bPaxLauncher" : sec + "b" + host + sec + "7 · PaxLauncher") + "\n"
                + (act ? sec + "aСвіт відкрито — заходь!" : sec + "cНеактивно — хост не у світі");
        String json = "{\"version\":{\"name\":\"PaxLauncher\",\"protocol\":" + proto + "},"
                + "\"players\":{\"max\":10,\"online\":" + players.get() + "},"
                + "\"description\":{\"text\":\"" + esc(desc) + "\"},"
                + "\"favicon\":\"" + favicon + "\",\"paxActive\":" + act + "}";
        byte[] js = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream pl = new ByteArrayOutputStream();
        writeVarInt(pl, 0);
        writeVarInt(pl, js.length);
        pl.write(js);
        writeVarInt(out, pl.size());
        out.write(pl.toByteArray());
        out.flush();
        byte[] ping = readPacket(in, 64);
        if (ping != null) {
            writeVarInt(out, ping.length);
            out.write(ping);
            out.flush();
        }
    }

    /** 0 — сесія активна й світ відкрито; 1 — сесія є, але світ ще не відкрито; 2 — сесії немає / недоступна. */
    static int probe(String ip, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), 4000);
            s.setSoTimeout(4000);
            OutputStream o = s.getOutputStream();
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, 0);
            writeVarInt(hs, 765);
            byte[] hb = ip.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hs, hb.length);
            hs.write(hb);
            hs.write(port >> 8);
            hs.write(port);
            writeVarInt(hs, 1);
            writeVarInt(o, hs.size());
            o.write(hs.toByteArray());
            writeVarInt(o, 1);
            o.write(0);
            o.flush();
            byte[] resp = readPacket(in, 1 << 20);
            if (resp == null) return 2;
            String txt = new String(resp, StandardCharsets.UTF_8);
            if (txt.contains("\"paxActive\":true")) return 0;
            if (txt.contains("\"paxActive\":false")) return 1;
            return 2;
        } catch (IOException e) { return 2; }
    }

    // ---------- протокол ----------

    private static int readVarInt(InputStream in) throws IOException {
        int v = 0, sh = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            v |= (b & 0x7F) << sh;
            if ((b & 0x80) == 0) return v;
            sh += 7;
            if (sh > 35) throw new IOException("varint");
        }
    }

    private static void writeVarInt(OutputStream o, int v) throws IOException {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
    }

    private static byte[] readPacket(DataInputStream in, int max) {
        try {
            int len = readVarInt(in);
            if (len < 0 || len > max) return null;
            byte[] b = new byte[len];
            in.readFully(b);
            return b;
        } catch (IOException e) { return null; }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                default: if (ch < 0x20) b.append(' '); else b.append(ch);
            }
        }
        return b.toString();
    }

    // ---------- логотип «Гра з друзями» ----------

    static String logoBase64() {
        try {
            BufferedImage im = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = im.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(0x8A90FF), 64, 64, new Color(0x5A3FD8)));
            g.fill(new RoundRectangle2D.Float(0, 0, 64, 64, 18, 18));
            g.setColor(new Color(255, 255, 255, 235));
            g.fill(new Ellipse2D.Float(13, 14, 17, 17));
            g.fill(new RoundRectangle2D.Float(8, 34, 27, 17, 14, 14));
            g.setColor(new Color(255, 255, 255, 170));
            g.fill(new Ellipse2D.Float(34, 14, 17, 17));
            g.fill(new RoundRectangle2D.Float(29, 34, 27, 17, 14, 14));
            g.dispose();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ImageIO.write(im, "png", bo);
            return Base64.getEncoder().encodeToString(bo.toByteArray());
        } catch (Exception e) { return ""; }
    }
}
