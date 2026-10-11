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

final class SlimScrollBarUI extends BasicScrollBarUI {
    @Override protected void configureScrollBarColors() {
        thumbColor = Theme.THUMB;
        trackColor = Theme.BG2;
    }

    private JButton zero() {
        JButton b = new JButton();
        Dimension d = new Dimension(0, 0);
        b.setPreferredSize(d); b.setMinimumSize(d); b.setMaximumSize(d);
        return b;
    }

    @Override protected JButton createDecreaseButton(int o) { return zero(); }
    @Override protected JButton createIncreaseButton(int o) { return zero(); }

    @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
        if (r.isEmpty() || !scrollbar.isEnabled()) return;
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        g2.setColor(isDragging ? Theme.THUMB_DRAG : isThumbRollover() ? Theme.THUMB_HOV : Theme.THUMB);
        boolean vert = scrollbar.getOrientation() == Adjustable.VERTICAL;
        int pad = 3;
        int x = vert ? r.x + pad : r.x + 1, y = vert ? r.y + 1 : r.y + pad;
        int w = vert ? r.width - 2 * pad : r.width - 2, h = vert ? r.height - 2 : r.height - 2 * pad;
        int arc = Math.max(2, Math.min(w, h));
        g2.fillRoundRect(x, y, w, h, arc, arc);
        g2.dispose();
    }

    @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
        Color bg = Theme.BG2;
        Container p = c.getParent();
        if (p instanceof JScrollPane) {
            Color vb = ((JScrollPane) p).getViewport().getBackground();
            if (vb != null) bg = vb;
        }
        g.setColor(bg);
        g.fillRect(r.x, r.y, r.width, r.height);
    }

    @Override public Dimension getPreferredSize(JComponent c) { return new Dimension(12, 12); }
}
