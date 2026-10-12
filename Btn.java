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

final class Btn extends JButton {
    enum Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    private final Kind kind;
    private final Fx fx = new Fx(this);
    private int glyph;
    private boolean plus;

    void setPlus(boolean plus) { this.plus = plus; repaint(); }

    Btn(String text, Kind kind) {
        super(text);
        this.kind = kind;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Theme.bfont(Font.BOLD, 14));
        setForeground(Theme.TEXT);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(new EmptyBorder(11, 22, 11, 22));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { fx.hover(true); }
            @Override public void mouseExited(MouseEvent e) { fx.hover(false); fx.press(false); }
            @Override public void mousePressed(MouseEvent e) { fx.press(true); }
            @Override public void mouseReleased(MouseEvent e) { fx.press(false); }
        });
    }

    void setGlyph(int glyph) { this.glyph = glyph; repaint(); }

    @Override public Dimension getPreferredSize() {
        return glyph != 0 ? new Dimension(Theme.px(46), Theme.TITLE_H) : super.getPreferredSize();
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        float hv = isEnabled() ? fx.h() : 0, pr = isEnabled() ? fx.p() : 0;
        Color fg = kind == Kind.DANGER ? Fx.mix(Theme.TEXT, Color.WHITE, hv) : Theme.TEXT;
        if (glyph == 0) {
            Fx.squeeze(g, w, h, pr);
            int arc = h >= 44 ? 14 : 10;
            Color face = null;
            if (!isEnabled()) {
                face = Theme.DIS_BG;
                fg = Theme.DIS_FG;
            } else {
                switch (kind) {
                    case PRIMARY:
                        face = Fx.mix(Fx.mix(Theme.ACC2, Theme.ACC_HI, hv), Theme.ACC1, pr);
                        fg = Theme.ACC_ON;
                        break;
                    case SECONDARY:
                        face = Fx.mix(Fx.mix(Theme.CARD2, Theme.SEC_HOV, hv), Theme.SEC_PR, pr);
                        break;
                    case GHOST:
                        face = Theme.ov(Math.round(24 * hv + 12 * pr));
                        break;
                    case DANGER:
                        face = new Color(0xC4, 0x2B, 0x1C, Math.round(255 * hv));
                        break;
                }
            }
            if (face != null && face.getAlpha() > 0) {
                g.setColor(face);
                g.fillRoundRect(0, 0, w, h, arc, arc);
            }
            String t = getText();
            float size = getFont().getSize2D();
            if (h >= 44) size = Math.max(size, Math.min(h * 0.36f, 30f));
            Font f = getFont().deriveFont(size);
            FontMetrics fm = g.getFontMetrics(f);
            int maxW = w - 24 - (plus ? Math.round(Math.min(h * 0.46f, 20f)) + 9 : 0);
            while (size > 10 && fm.stringWidth(t) > maxW) {
                size -= 0.5f;
                f = f.deriveFont(size);
                fm = g.getFontMetrics(f);
            }
            g.setFont(f);
            g.setColor(fg);
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            if (plus) {
                int ps = Math.round(Math.min(h * 0.46f, 20f)), gap = 9;
                int tw = fm.stringWidth(t), x0 = (w - (ps + gap + tw)) / 2;
                g.setStroke(new BasicStroke(Math.max(2.4f, ps / 7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int pcx = x0 + ps / 2, pcy = h / 2;
                g.drawLine(pcx - ps / 2 + 1, pcy, pcx + ps / 2 - 1, pcy);
                g.drawLine(pcx, pcy - ps / 2 + 1, pcx, pcy + ps / 2 - 1);
                g.drawString(t, x0 + ps + gap, ty);
            } else {
                g.drawString(t, (w - fm.stringWidth(t)) / 2, ty);
            }
        } else {
            if (isEnabled() && hv > 0) {
                g.setColor(kind == Kind.DANGER ? new Color(0xC4, 0x2B, 0x1C, Math.round(255 * hv)) : Theme.ov(Math.round(26 * hv)));
                g.fillRect(0, 0, w, h);
            }
            g.setColor(fg);
            // значки кнопок вікна (згорнути / розгорнути / закрити) ростуть разом із інтерфейсом
            double k = Math.max(1.0, Theme.S);
            int cx = w / 2, cy = h / 2;
            int u = (int) Math.round(7 * k), s4 = (int) Math.round(4 * k), s3 = (int) Math.round(3 * k), s11 = (int) Math.round(11 * k);
            g.setStroke(new BasicStroke((float) (1.8 * Math.sqrt(k)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
            if (glyph == 1) g.drawLine(cx - u, cy, cx + u, cy);
            else if (glyph == 3) g.drawRect(cx - u, cy - u, 2 * u, 2 * u);
            else if (glyph == 4) {
                g.drawRect(cx - u, cy - s3, s11, s11);
                g.drawPolyline(new int[]{cx - s3, cx - s3, cx + u, cx + u, cx + s4}, new int[]{cy - s3, cy - u, cy - u, cy + s3, cy + s3}, 5);
            } else {
                g.drawLine(cx - u, cy - u, cx + u, cy + u);
                g.drawLine(cx - u, cy + u, cx + u, cy - u);
            }
        }
        g.dispose();
    }
}
