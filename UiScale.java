import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.UIResource;

final class UiScale {
    private static final String K_FONT = "ui.font0", K_PREF = "ui.pref0", K_MIN = "ui.min0", K_MAX = "ui.max0",
            K_BORDER = "ui.border0", K_GBC = "ui.gbc0", K_GAP = "ui.gap0";

    private UiScale() { }

    static double pick(int w, int h) {
        double s = Math.min(w / 1240.0, h / 780.0);
        if (s < 1.08) return 1.0;
        s = 1 + (s - 1) * 0.5;
        s = Math.min(1.3, s);
        return Math.round(s * 20) / 20.0;
    }

    static void apply(Component root, double from, double to) {
        if (Math.abs(from - to) < 0.001) return;
        walk(root, from, to, true);
    }

    static void layoutOnly(Component root, double to) {
        if (to <= 1.001) return;
        walk(root, 1.0, to, false);
    }

    private static int sc(int v, double from, double to) {
        if (v <= 0 || v >= 100000) return v;
        return (int) Math.round(v / from * to);
    }

    private static Dimension dim(JComponent c, String key, Dimension cur, double from, double to) {
        Dimension base = (Dimension) c.getClientProperty(key);
        if (base == null) {
            base = new Dimension(cur.width >= 100000 ? cur.width : (int) Math.round(cur.width / from),
                    cur.height >= 100000 ? cur.height : (int) Math.round(cur.height / from));
            c.putClientProperty(key, base);
        }
        return new Dimension(base.width >= 100000 ? base.width : (int) Math.round(base.width * to),
                base.height >= 100000 ? base.height : (int) Math.round(base.height * to));
    }

    private static void walk(Component c, double from0, double to0, boolean fonts) {
        final double from = c instanceof Btn ? Theme.bs(from0) : from0, to = c instanceof Btn ? Theme.bs(to0) : to0;
        if (c instanceof JList) {
            JList<?> jl = (JList<?>) c;
            Integer base = (Integer) jl.getClientProperty("ui.cell0");
            if (base == null && jl.getFixedCellHeight() > 0) {
                base = (int) Math.round(jl.getFixedCellHeight() / from);
                jl.putClientProperty("ui.cell0", base);
            }
            if (base != null) jl.setFixedCellHeight((int) Math.round(base * to));
        }
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;

            if (fonts && jc.isFontSet() && jc.getFont() != null && !(jc.getFont() instanceof UIResource)) {
                Font base = (Font) jc.getClientProperty(K_FONT);
                if (base == null) {
                    Font f = jc.getFont();
                    base = f.deriveFont((float) (f.getSize2D() / from));
                    jc.putClientProperty(K_FONT, base);
                }
                jc.setFont(base.deriveFont((float) (base.getSize2D() * to)));
            }
            if (jc.isPreferredSizeSet()) jc.setPreferredSize(dim(jc, K_PREF, jc.getPreferredSize(), from, to));
            if (jc.isMinimumSizeSet()) jc.setMinimumSize(dim(jc, K_MIN, jc.getMinimumSize(), from, to));
            if (jc.isMaximumSizeSet()) jc.setMaximumSize(dim(jc, K_MAX, jc.getMaximumSize(), from, to));

            if (jc.getBorder() != null && jc.getBorder().getClass() == EmptyBorder.class) {
                EmptyBorder eb = (EmptyBorder) jc.getBorder();
                Insets base = (Insets) jc.getClientProperty(K_BORDER);
                if (base == null) {
                    Insets in = eb.getBorderInsets();
                    base = new Insets((int) Math.round(in.top / from), (int) Math.round(in.left / from),
                            (int) Math.round(in.bottom / from), (int) Math.round(in.right / from));
                    jc.putClientProperty(K_BORDER, base);
                }
                jc.setBorder(new EmptyBorder((int) Math.round(base.top * to), (int) Math.round(base.left * to),
                        (int) Math.round(base.bottom * to), (int) Math.round(base.right * to)));
            }
        }

        if (c instanceof Container) {
            Container ct = (Container) c;
            LayoutManager lm = ct.getLayout();
            JComponent holder = c instanceof JComponent ? (JComponent) c : null;
            if (lm instanceof GridBagLayout) {
                GridBagLayout gb = (GridBagLayout) lm;
                for (Component ch : ct.getComponents()) {
                    if (!(ch instanceof JComponent)) continue;
                    JComponent jch = (JComponent) ch;
                    GridBagConstraints gc = gb.getConstraints(ch);
                    int[] base = (int[]) jch.getClientProperty(K_GBC);
                    if (base == null) {
                        base = new int[]{(int) Math.round(gc.insets.top / from), (int) Math.round(gc.insets.left / from),
                                (int) Math.round(gc.insets.bottom / from), (int) Math.round(gc.insets.right / from),
                                (int) Math.round(gc.ipadx / from), (int) Math.round(gc.ipady / from)};
                        jch.putClientProperty(K_GBC, base);
                    }
                    gc.insets = new Insets((int) Math.round(base[0] * to), (int) Math.round(base[1] * to),
                            (int) Math.round(base[2] * to), (int) Math.round(base[3] * to));
                    gc.ipadx = (int) Math.round(base[4] * to);
                    gc.ipady = (int) Math.round(base[5] * to);
                    gb.setConstraints(ch, gc);
                }
            } else if (holder != null && (lm instanceof GridLayout || lm instanceof FlowLayout || lm instanceof BorderLayout)) {
                int[] base = (int[]) holder.getClientProperty(K_GAP);
                int h, v;
                if (lm instanceof GridLayout) { h = ((GridLayout) lm).getHgap(); v = ((GridLayout) lm).getVgap(); }
                else if (lm instanceof FlowLayout) { h = ((FlowLayout) lm).getHgap(); v = ((FlowLayout) lm).getVgap(); }
                else { h = ((BorderLayout) lm).getHgap(); v = ((BorderLayout) lm).getVgap(); }
                if (base == null) {
                    base = new int[]{(int) Math.round(h / from), (int) Math.round(v / from)};
                    holder.putClientProperty(K_GAP, base);
                }
                int nh = (int) Math.round(base[0] * to), nv = (int) Math.round(base[1] * to);
                if (lm instanceof GridLayout) { ((GridLayout) lm).setHgap(nh); ((GridLayout) lm).setVgap(nv); }
                else if (lm instanceof FlowLayout) { ((FlowLayout) lm).setHgap(nh); ((FlowLayout) lm).setVgap(nv); }
                else { ((BorderLayout) lm).setHgap(nh); ((BorderLayout) lm).setVgap(nv); }
            }
            for (Component ch : ct.getComponents()) walk(ch, from0, to0, fonts);
            if (c instanceof JComponent) ((JComponent) c).revalidate();
        }
    }
}
