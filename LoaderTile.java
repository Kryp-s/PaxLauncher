import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import javax.swing.*;

/** Плитка вибору завантажувача (іконка + назва), як у створенні збірки в Modrinth App. */
final class LoaderTile extends JComponent {
    final String name;
    private boolean sel, hov;
    private final Fx fx = new Fx(this);

    LoaderTile(String name, Runnable click) {
        this.name = name;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(Theme.px(84), Theme.px(64)));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); if (isEnabled() && SwingUtilities.isLeftMouseButton(e)) click.run(); }
        });
    }

    void setSel(boolean b) { sel = b; fx.sel(b, false); }

    boolean isSel() { return sel; }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        double k = Theme.S;
        g.scale(k, k);
        int w = (int) Math.round(getWidth() / k), h = (int) Math.round(getHeight() / k);
        float hv = fx.h(), sl = fx.s(), pr = fx.p();
        Fx.squeeze(g, w, h, pr);
        g.setColor(Fx.mix(Theme.INPUT, Theme.CARD_HOV, hv));
        g.fillRoundRect(0, 0, w, h, 14, 14);
        if (sl > 0) {
            g.setColor(Theme.acc(Math.round(46 * sl)));
            g.fillRoundRect(0, 0, w, h, 14, 14);
            g.setColor(Fx.alpha(Theme.ACC2, sl));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(1, 1, w - 3, h - 3, 14, 14);
        }
        icon(g, name, w / 2, 24, Math.max(sl, hv));
        g.setFont(Theme.font(Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Fx.mix(Theme.MUTED, Theme.TEXT, Math.max(sl, hv * 0.6f)));
        g.drawString(name, (w - fm.stringWidth(name)) / 2, h - 11);
        g.dispose();
    }

    /** Іконки завантажувачів: Vanilla — блок землі з гри, решта — вбудовані картинки (LoaderIcons). */
    static void icon(Graphics2D g0, String name, int cx, int cy, float emph) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setComposite(AlphaComposite.SrcOver.derive(0.72f + 0.28f * Math.max(0f, Math.min(1f, emph))));
        int sz = 30;
        LoaderIcons.draw(g, name, cx - sz / 2, cy - sz / 2, sz);
        g.dispose();
    }
}
