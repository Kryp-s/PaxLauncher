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

final class Combo<T> extends JComboBox<T> {
    boolean iconed;

    void refreshTheme() {
        setUI(new ComboUI());
    }

    Combo() {
        setUI(new ComboUI());
        setOpaque(false);
        setBackground(Theme.INPUT);
        setForeground(Theme.TEXT);
        setFont(Theme.font(Font.PLAIN, 14));
        setBorder(new EmptyBorder(2, 2, 2, 2));
        setMaximumRowCount(10);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRenderer(new DefaultListCellRenderer() {
            private String sub = "";

            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                String full = v == null ? "" : String.valueOf(v), main = full;
                sub = "";
                int k = full.indexOf("  · ");
                if (k >= 0) { main = full.substring(0, k); sub = full.substring(k + 4); }
                super.getListCellRendererComponent(l, main, i, sel, false);
                setOpaque(true);
                setBackground(sel ? Theme.SEL : Theme.CARD2);
                setForeground(Theme.TEXT);
                setFont(Theme.font(Font.PLAIN, 14));
                setBorder(new EmptyBorder(8, 14, 8, 14));
                return this;
            }

            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                if (sub.isEmpty()) return;
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
                FontMetrics fm = g.getFontMetrics();
                int x = getWidth() - 14 - fm.stringWidth(sub);
                if (x > 14 + getFontMetrics(getFont()).stringWidth(getText()) + 12) {
                    g.setColor(Theme.MUTED);
                    g.drawString(sub, x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                }
                g.dispose();
            }
        });
    }

    private static final class ComboUI extends BasicComboBoxUI {
        @Override protected JButton createArrowButton() {
            JButton b = new JButton() {
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    Theme.aa(g);
                    g.setColor(isPopupVisible(comboBox) ? Theme.TEXT : Theme.MUTED);
                    g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int cx = getWidth() / 2, cy = getHeight() / 2;
                    g.drawPolyline(new int[]{cx - 7, cx, cx + 7}, new int[]{cy - 3, cy + 4, cy - 3}, 3);
                    g.dispose();
                }
            };
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setOpaque(false);
            b.setBorder(null);
            b.setFocusable(false);
            return b;
        }

        @Override public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = (Graphics2D) g.create();
            Theme.aa(g2);
            int w = c.getWidth(), h = c.getHeight();
            g2.setColor(c.isEnabled() ? Theme.INPUT : Theme.DIS_INPUT);
            g2.fillRoundRect(0, 0, w, h, 12, 12);
            g2.setColor(Theme.LINE);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
            if (c.isEnabled() && (comboBox.hasFocus() || isPopupVisible(comboBox))) {
                g2.setColor(Theme.ACC2);
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawRoundRect(1, 1, w - 3, h - 3, 12, 12);
            }
            g2.dispose();
            paintCurrentValue(g, rectangleForCurrentValue(), false);
        }

        @Override public void paintCurrentValueBackground(Graphics g, Rectangle r, boolean hasFocus) { }

        @Override public void paintCurrentValue(Graphics g, Rectangle r, boolean hasFocus) {
            Object v = comboBox.getSelectedItem();
            if (v == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            Theme.aa(g2);
            g2.clipRect(r.x, r.y, r.width, r.height);
            if (((Combo<?>) comboBox).iconed) {
                String full = String.valueOf(v), main = full, sub = "";
                int k = full.indexOf("  · ");
                if (k >= 0) { main = full.substring(0, k); sub = full.substring(k + 4); }
                int is = 38;
                g2.drawImage(Theme.icon(is), r.x + 8, r.y + (r.height - is) / 2, null);
                int tx = r.x + 8 + is + 12;
                g2.setFont(comboBox.getFont().deriveFont(Font.BOLD, 15f));
                FontMetrics fm1 = g2.getFontMetrics();
                g2.setColor(comboBox.isEnabled() ? Theme.TEXT : Theme.MUTED);
                if (sub.isEmpty()) g2.drawString(main, tx, r.y + (r.height - fm1.getHeight()) / 2 + fm1.getAscent());
                else {
                    g2.drawString(main, tx, r.y + r.height / 2 - 2);
                    g2.setFont(comboBox.getFont().deriveFont(Font.PLAIN, 12f));
                    g2.setColor(Theme.MUTED);
                    g2.drawString(sub, tx, r.y + r.height / 2 + 14);
                }
                g2.dispose();
                return;
            }
            String all = String.valueOf(v), main = all, note = "";
            int dk = all.indexOf("  · ");
            if (dk >= 0) { main = all.substring(0, dk); note = all.substring(dk + 4); }
            g2.setFont(comboBox.getFont());
            g2.setColor(comboBox.isEnabled() ? Theme.TEXT : Theme.MUTED);
            FontMetrics fm = g2.getFontMetrics();
            int ty = r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(main, r.x + 12, ty);
            if (!note.isEmpty()) {
                g2.setFont(comboBox.getFont().deriveFont(Font.PLAIN, 12f));
                g2.setColor(Theme.MUTED);
                g2.drawString(note, r.x + 12 + fm.stringWidth(main) + 10, ty);
            }
            g2.dispose();
        }

        @Override protected ComboPopup createPopup() {
            BasicComboPopup p = new BasicComboPopup(comboBox) {
                @Override protected JScrollPane createScroller() {
                    JScrollPane sp = super.createScroller();
                    sp.setBorder(null);
                    sp.setBackground(Theme.CARD2);
                    sp.getViewport().setBackground(Theme.CARD2);
                    sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
                    Smooth.install(sp);
                    return sp;
                }
            };
            p.setBorder(new LineBorder(Theme.LINE, 1));
            p.setBackground(Theme.CARD2);
            p.setOpaque(true);
            JList<?> l = p.getList();
            l.setBackground(Theme.CARD2);
            l.setForeground(Theme.TEXT);
            l.setSelectionBackground(Theme.SEL);
            l.setSelectionForeground(Theme.TEXT);
            return p;
        }
    }
}
