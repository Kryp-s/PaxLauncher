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

final class Json {
    private final String s;
    private int i, depth;
    private static final int MAX_DEPTH = 100;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        try {
            return new Json(text).val();
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("bad JSON: " + e.getMessage(), e);
        }
    }

    private void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }

    private Object val() {
        ws();
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return str();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return num();
        }
    }

    private void enter() { if (++depth > MAX_DEPTH) throw new IllegalArgumentException("JSON too deep"); }

    private Map<String, Object> obj() {
        enter();
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; depth--; return m; }
        while (true) {
            ws(); String k = str(); ws(); i++;
            m.put(k, val()); ws();
            if (s.charAt(i++) == '}') break;
        }
        depth--;
        return m;
    }

    private List<Object> arr() {
        enter();
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; depth--; return l; }
        while (true) {
            l.add(val()); ws();
            if (s.charAt(i++) == ']') break;
        }
        depth--;
        return l;
    }

    private String str() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
        return b.toString();
    }

    private Double num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.valueOf(s.substring(st, i));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }

    static String string(Object o) { return o instanceof String ? (String) o : null; }

    static Object get(Object o, String... path) {
        for (String p : path) {
            Map<String, Object> mm = map(o);
            if (mm == null) return null;
            o = mm.get(p);
        }
        return o;
    }
}
