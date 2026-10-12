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

final class Settings {
    private final Path file;
    private final Properties p = new Properties();

    Settings(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) { }
        }
    }

    String get(String k, String d) { return p.getProperty(k, d); }

    int geti(String k, int d) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(d)).trim()); } catch (Exception e) { return d; }
    }

    boolean getb(String k, boolean d) { return Boolean.parseBoolean(p.getProperty(k, String.valueOf(d))); }

    void set(String k, String v) { p.setProperty(k, v); }

    void save() {
        try {
            Vault.writeProps(file, p, "PaxLauncher", false);
        } catch (IOException e) { PaxLauncher.warn("не вдалося зберегти налаштування", e); }
    }
}
