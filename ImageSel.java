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

final class ImageSel implements java.awt.datatransfer.Transferable {
    private final Image img;

    ImageSel(Image img) { this.img = img; }

    @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
        return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor};
    }

    @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) {
        return java.awt.datatransfer.DataFlavor.imageFlavor.equals(f);
    }

    @Override public Object getTransferData(java.awt.datatransfer.DataFlavor f) throws java.awt.datatransfer.UnsupportedFlavorException {
        if (!isDataFlavorSupported(f)) throw new java.awt.datatransfer.UnsupportedFlavorException(f);
        return img;
    }
}
