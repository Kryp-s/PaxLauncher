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

final class Shots {
    static BufferedImage read(Path f, int maxW, int maxH, boolean cover) throws IOException {
        try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(f.toFile())) {
            if (in == null) return null;
            Iterator<javax.imageio.ImageReader> it = javax.imageio.ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            javax.imageio.ImageReader rd = it.next();
            try {
                rd.setInput(in, true, true);
                int w = rd.getWidth(0), h = rd.getHeight(0);
                int sub = Math.max(1, Math.min(w / (maxW * 2), h / (maxH * 2)));
                javax.imageio.ImageReadParam p = rd.getDefaultReadParam();
                if (sub > 1) p.setSourceSubsampling(sub, sub, 0, 0);
                BufferedImage src = rd.read(0, p);
                int sw = src.getWidth(), sh = src.getHeight();
                BufferedImage out;
                if (cover) {
                    double k = Math.max((double) maxW / sw, (double) maxH / sh);
                    int dw = (int) Math.ceil(sw * k), dh = (int) Math.ceil(sh * k);
                    out = new BufferedImage(maxW, maxH, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = out.createGraphics();
                    quality(g);
                    g.drawImage(src, (maxW - dw) / 2, (maxH - dh) / 2, dw, dh, null);
                    g.dispose();
                } else {
                    double k = Math.min(1.0, Math.min((double) maxW / sw, (double) maxH / sh));
                    int dw = Math.max(1, (int) Math.round(sw * k)), dh = Math.max(1, (int) Math.round(sh * k));
                    out = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = out.createGraphics();
                    quality(g);
                    g.drawImage(src, 0, 0, dw, dh, null);
                    g.dispose();
                }
                return out;
            } finally {
                rd.dispose();
            }
        }
    }

    private static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
