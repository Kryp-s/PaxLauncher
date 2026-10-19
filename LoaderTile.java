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
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
            @Override public void mouseClicked(MouseEvent e) { if (isEnabled()) click.run(); }
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
        Color ic = Fx.mix(Theme.NAV_IDLE, Theme.ACC2, sl);
        icon(g, name, w / 2, 24, ic);
        g.setFont(Theme.font(Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Fx.mix(Theme.MUTED, Theme.TEXT, Math.max(sl, hv * 0.6f)));
        g.drawString(name, (w - fm.stringWidth(name)) / 2, h - 11);
        g.dispose();
    }

    /** Схематичні іконки завантажувачів (власне малювання, не офіційні логотипи). */
    static void icon(Graphics2D g0, String name, int cx, int cy, Color c) {
        Graphics2D g = (Graphics2D) g0.create();
        g.translate(cx, cy);
        g.setColor(c);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (name) {
            case "Vanilla": {   // ізометричний блок
                Path2D.Float top = new Path2D.Float();
                top.moveTo(0, -12); top.lineTo(11, -6); top.lineTo(0, 0); top.lineTo(-11, -6); top.closePath();
                g.draw(top);
                g.drawLine(-11, -6, -11, 7); g.drawLine(11, -6, 11, 7); g.drawLine(0, 0, 0, 13);
                g.drawLine(-11, 7, 0, 13); g.drawLine(11, 7, 0, 13);
                break;
            }
            case "Fabric": {    // полотно зі стібками
                g.draw(new RoundRectangle2D.Float(-11, -11, 22, 22, 6, 6));
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2.5f, 3.5f}, 0));
                g.drawLine(-6, -6, 6, 6);
                g.drawLine(-6, 0, 0, 6);
                g.drawLine(0, -6, 6, 0);
                break;
            }
            case "Quilt": {     // клаптикова ковдра 2x2
                g.draw(new RoundRectangle2D.Float(-11, -11, 10, 10, 3, 3));
                g.fill(new RoundRectangle2D.Float(1, -11, 10, 10, 3, 3));
                g.fill(new RoundRectangle2D.Float(-11, 1, 10, 10, 3, 3));
                g.draw(new RoundRectangle2D.Float(1, 1, 10, 10, 3, 3));
                break;
            }
            case "Forge": {     // ковадло
                Path2D.Float a = new Path2D.Float();
                a.moveTo(-12, -8); a.lineTo(12, -8); a.lineTo(9, -2); a.lineTo(4, -1); a.lineTo(4, 4);
                a.lineTo(9, 9); a.lineTo(-9, 9); a.lineTo(-4, 4); a.lineTo(-4, -1); a.lineTo(-9, -2); a.closePath();
                g.draw(a);
                break;
            }
            default: {          // NeoForge: ковадло з іскрою
                Path2D.Float a = new Path2D.Float();
                a.moveTo(-12, -3); a.lineTo(8, -3); a.lineTo(5, 2); a.lineTo(2, 3); a.lineTo(2, 7);
                a.lineTo(6, 11); a.lineTo(-8, 11); a.lineTo(-3, 7); a.lineTo(-3, 3); a.lineTo(-7, 2); a.closePath();
                g.draw(a);
                g.drawLine(8, -12, 8, -6); g.drawLine(5, -9, 11, -9);
                g.drawLine(-2, -11, -2, -7); g.drawLine(-4, -9, 0, -9);
            }
        }
        g.dispose();
    }
}
