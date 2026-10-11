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

final class Check extends JCheckBox {
    Check(String text, boolean selected) {
        super(text, selected);
        setOpaque(false);
        setForeground(Theme.TEXT);
        setFont(Theme.font(Font.PLAIN, 14));
        setFocusPainted(false);
        setIconTextGap(8);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setIcon(new Icon() {
            @Override public int getIconWidth() { return 20; }
            @Override public int getIconHeight() { return 20; }

            @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                if (((AbstractButton) c).isSelected()) {
                    g.setColor(Theme.ACC2);
                    g.fillRoundRect(x, y, 20, 20, 7, 7);
                    g.setColor(Theme.ACC_ON);
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawPolyline(new int[]{x + 5, x + 9, x + 15}, new int[]{y + 10, y + 14, y + 6}, 3);
                } else {
                    g.setColor(Theme.INPUT);
                    g.fillRoundRect(x, y, 20, 20, 7, 7);
                    g.setColor(Theme.CHECK_OFF);
                    g.setStroke(new BasicStroke(1f));
                    g.drawRoundRect(x, y, 19, 19, 7, 7);
                }
                g.dispose();
            }
        });
    }
}
