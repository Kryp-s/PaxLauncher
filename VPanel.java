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

final class VPanel extends JPanel implements Scrollable {
    private final GridBagConstraints gc = new GridBagConstraints();
    private int row;

    VPanel() {
        super(new GridBagLayout());
        setOpaque(false);
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;
    }

    void row(Component c, int top) {
        gc.gridy = row++;
        gc.weighty = 0;
        gc.insets = new Insets(top, 0, 0, 0);
        add(c, gc);
    }

    void end() {
        gc.gridy = row++;
        gc.weighty = 1;
        gc.insets = new Insets(0, 0, 0, 0);
        add(Box.createGlue(), gc);
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 28; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(60, (int) (r.height * 0.9)); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
