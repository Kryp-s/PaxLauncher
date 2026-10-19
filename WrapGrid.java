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

final class WrapGrid implements LayoutManager {
    private final int bcw, bch, bgap;
    private int lastRows = -1;

    WrapGrid(int cw, int ch, int gap) { this.bcw = cw; this.bch = ch; this.bgap = gap; }

    private int cw() { return Theme.px(bcw); }
    private int ch() { return Theme.px(bch); }
    private int gap() { return Theme.px(bgap); }

    private int cols(Container t) {
        int w = t.getWidth();
        Container v = SwingUtilities.getAncestorOfClass(JViewport.class, t);
        if (v != null && v.getWidth() > 0) w = v.getWidth();
        return Math.max(1, (w + gap()) / (cw() + gap()));
    }

    @Override public void addLayoutComponent(String n, Component c) { }

    @Override public void removeLayoutComponent(Component c) { }

    @Override public Dimension preferredLayoutSize(Container t) {
        int n = t.getComponentCount(), c = cols(t), rows = (n + c - 1) / c;
        return new Dimension(c * (cw() + gap()) - gap(), rows == 0 ? 0 : rows * (ch() + gap()) - gap());
    }

    @Override public Dimension minimumLayoutSize(Container t) { return new Dimension(cw(), 0); }

    @Override public void layoutContainer(Container t) {
        int n = t.getComponentCount(), c = cols(t);
        int tw = t.getWidth();
        Container vp = SwingUtilities.getAncestorOfClass(JViewport.class, t);
        if (vp != null && vp.getWidth() > 0) tw = vp.getWidth();
        // зайве місце по ширині розподіляємо між колонками, щоб праворуч не лишалось порожнечі
        int cwe = Math.max(cw(), (tw - (c - 1) * gap()) / c);
        for (int i = 0; i < n; i++) t.getComponent(i).setBounds((i % c) * (cwe + gap()), (i / c) * (ch() + gap()), cwe, ch());
        int rows = (n + c - 1) / c;
        if (rows != lastRows) {
            lastRows = rows;
            SwingUtilities.invokeLater(t::revalidate);
        }
    }
}
