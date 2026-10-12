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

final class DiscordRpc {
    private final Consumer<String> log;
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "discord-rpc");
        t.setDaemon(true);
        return t;
    });
    private volatile String clientId = "", details = "", state = "";
    private volatile long start;
    private Closeable conn;
    private InputStream in;
    private OutputStream out;
    private boolean told;

    DiscordRpc(Consumer<String> log) {
        this.log = log;
        ex.scheduleWithFixedDelay(this::push, 20, 20, TimeUnit.SECONDS);
    }

    void configure(String id) {
        String n = id.trim();
        if (n.equals(clientId)) return;
        clientId = n;
        ex.execute(() -> { close0(); told = false; push(); });
    }

    void shutdown() {
        clientId = "";
        try { ex.execute(this::close0); ex.shutdown(); } catch (RuntimeException ignored) { }
    }

    void update(String d, String s, long startSec) {
        details = d;
        state = s;
        start = startSec;
        ex.execute(this::push);
    }

    private void close0() {
        try { if (conn != null) conn.close(); } catch (IOException ignored) { }
        conn = null; in = null; out = null;
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < ' ') b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private void push() {
        try {
            if (clientId.isEmpty()) return;
            if (conn == null && !connect()) return;
            frame(1, "{\"cmd\":\"SET_ACTIVITY\",\"nonce\":\"" + UUID.randomUUID() + "\",\"args\":{\"pid\":" + ProcessHandle.current().pid()
                    + ",\"activity\":{\"details\":" + q(details) + ",\"state\":" + q(state)
                    + ",\"timestamps\":{\"start\":" + start + "},\"assets\":{\"large_image\":\"pl_logo\",\"large_text\":\"PaxLauncher\"}}}}");
            String r = readFrame();
            if (r.contains("\"evt\":\"ERROR\"")) log.accept("Discord RPC: " + r);
        } catch (Throwable e) {
            close0();
        }
    }

    private boolean connect() throws IOException {
        if (!open()) return false;
        frame(0, "{\"v\":1,\"client_id\":" + q(clientId) + "}");
        String r = readFrame();
        if (r.contains("\"evt\":\"ERROR\"")) { log.accept("Discord RPC: " + r); close0(); return false; }
        if (!told) { log.accept(I18n.t("Discord Rich Presence підключено")); told = true; }
        return true;
    }

    private boolean open() {
        boolean win = Engine.osName().equals("windows");
        List<String> dirs = new ArrayList<>();
        if (!win) {
            String xdg = System.getenv("XDG_RUNTIME_DIR");
            if (xdg != null) { dirs.add(xdg); dirs.add(xdg + "/app/com.discordapp.Discord"); dirs.add(xdg + "/snap.discord"); }
            for (String v : new String[]{"TMPDIR", "TMP", "TEMP"}) if (System.getenv(v) != null) dirs.add(System.getenv(v));
            dirs.add("/tmp");
        }
        for (int i = 0; i < 10; i++) {
            try {
                if (win) {
                    RandomAccessFile raf = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
                    conn = raf;
                    in = new InputStream() {
                        @Override public int read() throws IOException { return raf.read(); }
                        @Override public int read(byte[] b, int o, int l) throws IOException { return raf.read(b, o, l); }
                    };
                    out = new OutputStream() {
                        @Override public void write(int b) throws IOException { raf.write(b); }
                        @Override public void write(byte[] b, int o, int l) throws IOException { raf.write(b, o, l); }
                    };
                    return true;
                }
                for (String d : dirs) {
                    Path sock = Path.of(d, "discord-ipc-" + i);
                    if (!Files.exists(sock)) continue;
                    try { if (!Files.getOwner(sock).getName().equals(System.getProperty("user.name"))) continue; }   // чужий сокет у /tmp — не довіряємо
                    catch (Exception e) { continue; }
                    java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
                    try { ch.connect(java.net.UnixDomainSocketAddress.of(sock)); } catch (IOException e) { ch.close(); continue; }
                    conn = ch;
                    in = java.nio.channels.Channels.newInputStream(ch);
                    out = java.nio.channels.Channels.newOutputStream(ch);
                    return true;
                }
            } catch (IOException ignored) { }
        }
        return false;
    }

    private void frame(int op, String json) throws IOException {
        byte[] p = json.getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 + p.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(p.length).put(p);
        out.write(b.array());
        out.flush();
    }

    private String readFrame() throws IOException {
        byte[] h = in.readNBytes(8);
        if (h.length < 8) throw new EOFException();
        int len = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4);
        if (len < 0 || len > 1 << 20) throw new IOException("bad frame");
        return new String(in.readNBytes(len), StandardCharsets.UTF_8);
    }
}
