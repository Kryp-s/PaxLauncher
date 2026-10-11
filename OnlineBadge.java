import java.awt.*;
import javax.swing.*;

/** Мітка «зараз онлайн: N». */
final class OnlineBadge extends JComponent {
    private volatile int count = -1;

    void set(Integer n) {
        count = n == null ? -1 : n;
        SwingUtilities.invokeLater(this::repaint);
    }

    @Override public Dimension getPreferredSize() { return new Dimension(Theme.px(220), Theme.px(24)); }

    @Override protected void paintComponent(Graphics g0) {
        if (count < 0) return;
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.scale(Theme.S, Theme.S);
        g.setColor(new Color(80, 200, 120));
        g.fillOval(0, 8, 8, 8);
        g.setFont(Theme.fontRaw(Font.PLAIN, 12));
        g.setColor(Theme.MUTED);
        g.drawString(I18n.t("Зараз онлайн: ") + count, 14, 16);
        g.dispose();
    }
}
