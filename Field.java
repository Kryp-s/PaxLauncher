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

final class Field extends JTextField {
    Field(String text) {
        super(text);
        setOpaque(false);
        setForeground(Theme.TEXT);
        setCaretColor(Theme.ACC2);
        setSelectionColor(Theme.SEL_FIELD);
        setSelectedTextColor(Theme.SEL_FIELD_FG);
        setFont(Theme.font(Font.PLAIN, 14));
        setBorder(new EmptyBorder(11, 14, 11, 14));
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.setColor(Theme.INPUT);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
        g.setColor(Theme.LINE);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        if (hasFocus()) {
            g.setColor(Theme.ACC2);
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 12, 12);
        }
        g.dispose();
        super.paintComponent(g0);
        if (hint != null && !hint.isEmpty() && getText().isEmpty()) {
            Graphics2D h = (Graphics2D) g0.create();
            Theme.aa(h);
            h.setFont(getFont());
            h.setColor(Theme.MUTED);
            Insets in = getInsets();
            FontMetrics fm = h.getFontMetrics();
            h.drawString(hint, in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            h.dispose();
        }
    }

    private String hint;

    /** Сірий підпис у порожньому полі (наприклад, «Пошук…»). */
    void setHint(String hint) { this.hint = hint; repaint(); }
}
