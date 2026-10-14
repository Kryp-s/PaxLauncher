import java.awt.*;
import java.util.function.Supplier;
import javax.swing.*;

/**
 * Легкий рядок списку (іконка + назва + додатковий текст + опис).
 * Малюється напряму, без HTML: HTML-мітка в кожному рядку змушувала Swing розбирати розмітку
 * для всього списку на кожне перекомпонування, через що інтерфейс підвисав під час завантаження.
 * Розмір рядка сталий, тож порахувати розмір списку майже безкоштовно.
 */
final class RowCell extends JComponent {
    Supplier<Icon> icon;
    String title = "", meta = "", sub = "";
    boolean sel, dim;
    private final int h;

    RowCell(int h) {
        this.h = h;
        setOpaque(false);
    }

    @Override public Dimension getPreferredSize() {
        return new Dimension(10, h);
    }

    private static String fit(String s, FontMetrics fm, int w) {
        if (s == null || s.isEmpty() || w <= 0) return "";
        if (s.length() > 260) s = s.substring(0, 260);
        s = s.replace('\n', ' ').replace('\r', ' ');
        if (fm.stringWidth(s) <= w) return s;
        int ell = fm.stringWidth("…");
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (fm.stringWidth(s.substring(0, mid)) + ell <= w) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo).stripTrailing() + "…";
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.setColor(sel ? Theme.SEL : Theme.CARD2);
        g.fillRect(0, 0, getWidth(), getHeight());
        int pad = 12, x = pad;
        Icon ic = icon == null ? null : icon.get();
        if (ic != null) {
            ic.paintIcon(this, g, x, (getHeight() - ic.getIconHeight()) / 2);
            x += ic.getIconWidth() + 12;
        }
        int w = getWidth() - x - pad;
        Font bold = Theme.font(Font.BOLD, 13), plain = Theme.font(Font.PLAIN, 13);
        FontMetrics fb = g.getFontMetrics(bold), fp = g.getFontMetrics(plain);
        int total = fb.getHeight() + 2 + fp.getHeight();
        int y1 = (getHeight() - total) / 2 + fb.getAscent();
        int y2 = (getHeight() - total) / 2 + fb.getHeight() + 2 + fp.getAscent();

        g.setFont(bold);
        g.setColor(dim && !sel ? Theme.MUTED : Theme.TEXT);
        int metaW = meta.isEmpty() ? 0 : fp.stringWidth(meta) + 14;
        String t = fit(title, fb, Math.max(40, w - metaW));
        g.drawString(t, x, y1);
        if (!meta.isEmpty()) {
            g.setFont(plain);
            g.setColor(Theme.SUB);
            int mx = x + fb.stringWidth(t) + 12;
            if (mx + fp.stringWidth(meta) <= x + w) g.drawString(meta, mx, y1);
        }
        g.setFont(plain);
        g.setColor(Theme.SUB);
        g.drawString(fit(sub, fp, w), x, y2);
        g.dispose();
    }
}
