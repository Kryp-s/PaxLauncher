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
    String placeholder;
    boolean wideList;
    /** Для не-iconed списків: за текстом пункту повертає назву іконки (завантажувач/платформа) або null. */
    java.util.function.Function<String, String> iconOf;

    String iconKey(String full) {
        if (iconed) return LoaderIcons.loaderOf(full);
        return iconOf == null ? null : iconOf.apply(full);
    }

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
            private boolean selRow;
            private String loader;

            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                boolean ph = v == null && i < 0 && placeholder != null;
                String full = v == null ? (ph ? placeholder : "") : String.valueOf(v), main = full;
                sub = "";
                int k = full.indexOf("  · ");
                if (k >= 0) { main = full.substring(0, k); sub = full.substring(k + 4); }
                super.getListCellRendererComponent(l, main, i, sel, false);
                selRow = sel && i >= 0;
                setOpaque(i < 0);
                setBackground(Theme.CARD2);
                setForeground(ph ? Theme.MUTED : Theme.TEXT);
                setFont(Theme.font(Font.PLAIN, 14));
                loader = v != null && i >= 0 ? iconKey(full) : null;
                setBorder(new EmptyBorder(8, loader != null ? 14 + Theme.px(22) + 10 : 20, 8, 20));
                return this;
            }

            @Override protected void paintComponent(Graphics g0) {
                if (selRow) {
                    Graphics2D gs = (Graphics2D) g0.create();
                    Theme.aa(gs);
                    gs.setColor(Theme.SEL);
                    gs.fillRoundRect(6, 1, getWidth() - 12, getHeight() - 2, 10, 10);
                    gs.dispose();
                }
                super.paintComponent(g0);
                if (loader != null) {
                    Graphics2D gi = (Graphics2D) g0.create();
                    Theme.aa(gi);
                    int is = Theme.px(22);
                    LoaderIcons.draw(gi, loader, 14, (getHeight() - is) / 2, is);
                    gi.dispose();
                }
                if (sub.isEmpty()) return;
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setFont(getFont().deriveFont(Font.PLAIN, (float) (12 * Theme.S)));
                FontMetrics fm = g.getFontMetrics();
                int x = getWidth() - 20 - fm.stringWidth(sub);
                if (x > getInsets().left + getFontMetrics(getFont()).stringWidth(getText()) + 12) {
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
            if (v == null) {
                String ph = ((Combo<?>) comboBox).placeholder;
                if (ph == null || ph.isEmpty()) return;
                Graphics2D gp = (Graphics2D) g.create();
                Theme.aa(gp);
                gp.clipRect(r.x, r.y, r.width, r.height);
                gp.setFont(comboBox.getFont());
                gp.setColor(Theme.MUTED);
                FontMetrics pm = gp.getFontMetrics();
                gp.drawString(ph, r.x + 12, r.y + (r.height - pm.getHeight()) / 2 + pm.getAscent());
                gp.dispose();
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            Theme.aa(g2);
            g2.clipRect(r.x, r.y, r.width, r.height);
            if (((Combo<?>) comboBox).iconed) {
                String full = String.valueOf(v), main = full, sub = "";
                int k = full.indexOf("  · ");
                if (k >= 0) { main = full.substring(0, k); sub = full.substring(k + 4); }
                int is = Theme.px(38);
                LoaderIcons.draw(g2, LoaderIcons.loaderOf(full), r.x + 8, r.y + (r.height - is) / 2, is);
                int tx = r.x + 8 + is + 12;
                g2.setFont(comboBox.getFont().deriveFont(Font.BOLD, (float) (15 * Theme.S)));
                FontMetrics fm1 = g2.getFontMetrics();
                g2.setColor(comboBox.isEnabled() ? Theme.TEXT : Theme.MUTED);
                if (sub.isEmpty()) g2.drawString(main, tx, r.y + (r.height - fm1.getHeight()) / 2 + fm1.getAscent());
                else {
                    g2.drawString(main, tx, r.y + r.height / 2 - 2);
                    g2.setFont(comboBox.getFont().deriveFont(Font.PLAIN, (float) (12 * Theme.S)));
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
            int ox = 0;
            String ik = ((Combo<?>) comboBox).iconKey(all);
            if (ik != null) {
                int is = Theme.px(20);
                LoaderIcons.draw(g2, ik, r.x + 10, r.y + (r.height - is) / 2, is);
                ox = is + 8;
            }
            g2.drawString(main, r.x + 12 + ox, ty);
            if (!note.isEmpty()) {
                g2.setFont(comboBox.getFont().deriveFont(Font.PLAIN, (float) (12 * Theme.S)));
                g2.setColor(Theme.MUTED);
                g2.drawString(note, r.x + 12 + ox + fm.stringWidth(main) + 10, ty);
            }
            g2.dispose();
        }

        @Override protected ComboPopup createPopup() {
            BasicComboPopup p = new BasicComboPopup(comboBox) {
                @Override protected Rectangle computePopupBounds(int px, int py, int pw, int ph) {
                    if (comboBox instanceof Combo && ((Combo<?>) comboBox).wideList) {
                        FontMetrics m1 = comboBox.getFontMetrics(Theme.font(Font.PLAIN, 14));
                        FontMetrics m2 = comboBox.getFontMetrics(Theme.font(Font.PLAIN, 12));
                        int need = pw;
                        for (int i = 0; i < comboBox.getItemCount(); i++) {
                            String full = String.valueOf(comboBox.getItemAt(i)), main = full, sub = "";
                            int k = full.indexOf("  · ");
                            if (k >= 0) { main = full.substring(0, k); sub = full.substring(k + 4); }
                            need = Math.max(need, m1.stringWidth(main) + (sub.isEmpty() ? 0 : m2.stringWidth(sub) + 24) + 28 + 24 + (((Combo<?>) comboBox).iconed ? Theme.px(22) + 10 : 0));
                        }
                        Window w = SwingUtilities.getWindowAncestor(comboBox);
                        if (w != null) need = Math.min(need, Math.max(pw, w.getWidth() - 80));
                        pw = need;
                    }
                    return super.computePopupBounds(px, py, pw, ph);
                }

                private static final int ARC = 14;

                private void clearOpaqueParents() {
                    Container pc = getParent();
                    while (pc != null && !(pc instanceof JLayeredPane) && !(pc instanceof JRootPane)) {
                        if (pc instanceof JComponent) ((JComponent) pc).setOpaque(false);
                        pc = pc.getParent();
                    }
                }

                @Override public void show() {
                    super.show();
                    clearOpaqueParents();
                    SwingUtilities.invokeLater(() -> {
                        clearOpaqueParents();
                        repaint();
                        Window w = SwingUtilities.getWindowAncestor(this);
                        if (w != null && w.getClass().getName().contains("HeavyWeight")) {
                            try {
                                w.setBackground(new Color(0, 0, 0, 0));
                                if (w instanceof RootPaneContainer) {
                                    JRootPane rp = ((RootPaneContainer) w).getRootPane();
                                    rp.setOpaque(false);
                                    rp.getContentPane().setBackground(new Color(0, 0, 0, 0));
                                    if (rp.getContentPane() instanceof JComponent) ((JComponent) rp.getContentPane()).setOpaque(false);
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
                }

                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    Theme.aa(g);
                    g.setColor(Theme.CARD2);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
                    g.dispose();
                }

                @Override protected void paintChildren(Graphics g0) {
                    int w = getWidth(), h = getHeight();
                    if (w <= 0 || h <= 0) return;
                    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    Graphics2D ig = img.createGraphics();
                    super.paintChildren(ig);
                    ig.setComposite(AlphaComposite.DstIn);
                    Theme.aa(ig);
                    ig.setColor(Color.WHITE);
                    ig.fillRoundRect(0, 0, w, h, ARC, ARC);
                    ig.dispose();
                    Graphics2D g = (Graphics2D) g0.create();
                    g.drawImage(img, 0, 0, null);
                    Theme.aa(g);
                    g.setColor(Theme.LINE);
                    g.setStroke(new BasicStroke(1f));
                    g.drawRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
                    g.dispose();
                }

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
            p.setBorder(new EmptyBorder(5, 1, 5, 1));
            p.setBackground(Theme.CARD2);
            p.setOpaque(false);
            JList<?> l = p.getList();
            l.setBackground(Theme.CARD2);
            l.setForeground(Theme.TEXT);
            l.setSelectionBackground(Theme.SEL);
            l.setSelectionForeground(Theme.TEXT);
            return p;
        }
    }
}
