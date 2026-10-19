import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;

/** Вибір іконки збірки: сітка вбудованих іконок + кнопка «Свій файл…» для власної картинки. */
final class IconPicker extends JPanel {
    private String id;
    private BufferedImage custom;
    private final Component owner;
    private final Runnable changed;
    private final String dflt;
    private final JComponent preview;
    private final List<Cell> cells = new ArrayList<>();

    IconPicker(String id, BufferedImage custom, Component owner, String dflt) { this(id, custom, owner, dflt, null); }

    IconPicker(String id, BufferedImage custom, Component owner, String dflt, Runnable changed) {
        super(new BorderLayout(0, 14));
        this.dflt = BuildIcons.valid(dflt) ? dflt : BuildIcons.IDS[0];
        this.id = BuildIcons.valid(id) ? id : BuildIcons.IDS[0];
        this.custom = custom;
        this.owner = owner;
        this.changed = changed;
        setOpaque(false);

        preview = new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                paintCurrent(g, 0, 0, Math.min(getWidth(), getHeight()));
                g.dispose();
            }
        };
        preview.setPreferredSize(new Dimension(Theme.px(84), Theme.px(84)));

        JLabel t = LauncherWindow.label(I18n.t("Обери іконку зі списку або постав свою картинку"), 13, false, Theme.MUTED);
        Btn own = LauncherWindow.small(new Btn(I18n.t("Свій файл…"), Btn.Kind.SECONDARY));
        Btn reset = LauncherWindow.small(new Btn(I18n.t("Скинути"), Btn.Kind.GHOST));
        own.addActionListener(e -> pickFile());
        reset.addActionListener(e -> { this.custom = null; this.id = this.dflt; refresh(); });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btns.setOpaque(false);
        btns.add(own);
        btns.add(reset);
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        btns.setAlignmentX(Component.LEFT_ALIGNMENT);
        right.add(Box.createVerticalGlue());
        right.add(t);
        right.add(Box.createVerticalStrut(10));
        right.add(btns);
        right.add(Box.createVerticalGlue());
        JPanel top = new JPanel(new BorderLayout(16, 0));
        top.setOpaque(false);
        top.add(preview, BorderLayout.WEST);
        top.add(right, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(0, 7, Theme.px(6), Theme.px(6)));
        grid.setOpaque(false);
        for (String ic : BuildIcons.IDS) {
            Cell c = new Cell(ic);
            cells.add(c);
            grid.add(c);
        }
        JPanel gw = new JPanel(new BorderLayout());
        gw.setOpaque(false);
        gw.setBorder(new EmptyBorder(4, 4, 4, 8));
        gw.add(grid, BorderLayout.NORTH);
        JScrollPane sp = LauncherWindow.vscroll(gw, Theme.CARD2);
        sp.setPreferredSize(new Dimension(Theme.px(440), Theme.px(236)));
        add(sp, BorderLayout.CENTER);
    }

    /** Id вбудованої іконки (якщо власної картинки не обрано). */
    String id() { return id; }

    /** Власна картинка або null. */
    BufferedImage custom() { return custom; }

    void paintCurrent(Graphics2D g, int x, int y, int s) {
        if (custom != null) g.drawImage(BuildIcons.fit(custom, s), x, y, null);
        else BuildIcons.paint(g, id, x, y, s);
    }

    private void refresh() {
        preview.repaint();
        for (Cell c : cells) c.repaint();
        if (changed != null) changed.run();
    }

    private void pickFile() {
        File pick = FilePick.nativeOpen(SwingUtilities.getWindowAncestor(this) != null ? this : owner,
                I18n.t("Обери картинку для іконки (png, jpg, gif)"), "*.png;*.jpg;*.jpeg;*.gif",
                (d, n) -> n.toLowerCase().matches(".*\\.(png|jpe?g|gif)$"));
        if (pick == null) return;
        try {
            custom = BuildIcons.readImage(pick);
            refresh();
        } catch (Exception ex) {
            Dlg.show(owner, I18n.t("Іконка"), Dlg.text(I18n.t("Не вдалося відкрити картинку: ") + ex.getMessage()), I18n.t("Закрити"), null);
        }
    }

    private final class Cell extends JComponent {
        final String ic;
        boolean hov;

        Cell(String ic) {
            this.ic = ic;
            setPreferredSize(new Dimension(Theme.px(54), Theme.px(54)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hov = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hov = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { IconPicker.this.id = ic; IconPicker.this.custom = null; refresh(); }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            boolean sel = custom == null && ic.equals(id);
            int w = Math.min(getWidth(), getHeight());
            int pad = Math.max(3, w / 11) + (hov && !sel ? 0 : 1);
            if (sel) {
                g.setColor(Theme.acc(60));
                g.fillRoundRect(0, 0, w, w, w / 3, w / 3);
                g.setColor(Theme.ACC2);
                g.setStroke(new BasicStroke(2.2f));
                g.drawRoundRect(1, 1, w - 3, w - 3, w / 3, w / 3);
            }
            BuildIcons.paint(g, ic, pad, pad, w - pad * 2);
            g.dispose();
        }
    }
}
