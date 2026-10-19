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

final class ImgIcon implements Icon {
    private final BufferedImage img;
    private final int size;
    private final boolean dflt;

    /** Іконка за замовчуванням (куб) для файлів без власної іконки. */
    static ImgIcon fallback(int size) { return new ImgIcon(size); }

    private ImgIcon(int size) { this.size = size; this.img = null; this.dflt = true; }

    ImgIcon(BufferedImage img, int size) {
        this.dflt = false;
        this.size = size;
        this.img = img == null ? null : render(img, size * 2);
    }

    static BufferedImage smooth(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        if (cur.getType() != BufferedImage.TYPE_INT_ARGB) {
            BufferedImage c = new BufferedImage(cur.getWidth(), cur.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = c.createGraphics();
            g.drawImage(cur, 0, 0, null);
            g.dispose();
            cur = c;
        }
        int cw = cur.getWidth(), ch = cur.getHeight();
        while (cw > w * 2 || ch > h * 2) {
            int nw = Math.max(w, cw / 2), nh = Math.max(h, ch / 2);
            cur = step(cur, nw, nh, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            cw = nw;
            ch = nh;
        }
        if (cw != w || ch != h) cur = step(cur, w, h, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return cur;
    }

    private static BufferedImage step(BufferedImage in, int w, int h, Object interp) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(in, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage render(BufferedImage src, int s) {
        BufferedImage sc = smooth(src, s, s);
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setComposite(AlphaComposite.Src);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, s / 4f, s / 4f));
        g.setComposite(AlphaComposite.SrcAtop);
        g.drawImage(sc, 0, 0, null);
        g.dispose();
        return out;
    }

    @Override public int getIconWidth() { return size; }

    @Override public int getIconHeight() { return size; }

    @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        if (img == null) {
            g.setColor(Theme.INPUT);
            g.fillRoundRect(x, y, size, size, size / 4, size / 4);
            g.setColor(Theme.ov(30));
            g.drawRoundRect(x, y, size - 1, size - 1, size / 4, size / 4);
            if (dflt) {
                float u = size / 40f, cx = x + size / 2f, cy = y + size / 2f;
                g.setColor(Theme.ov(70));
                g.setStroke(new BasicStroke(Math.max(1.4f, 1.8f * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                java.awt.geom.Path2D.Float cube = new java.awt.geom.Path2D.Float();
                cube.moveTo(cx, cy - 11 * u); cube.lineTo(cx + 10 * u, cy - 5.5f * u); cube.lineTo(cx + 10 * u, cy + 5.5f * u);
                cube.lineTo(cx, cy + 11 * u); cube.lineTo(cx - 10 * u, cy + 5.5f * u); cube.lineTo(cx - 10 * u, cy - 5.5f * u); cube.closePath();
                cube.moveTo(cx - 10 * u, cy - 5.5f * u); cube.lineTo(cx, cy); cube.lineTo(cx + 10 * u, cy - 5.5f * u);
                cube.moveTo(cx, cy); cube.lineTo(cx, cy + 11 * u);
                g.draw(cube);
            }
        } else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, x, y, size, size, null);
        }
        g.dispose();
    }
}
