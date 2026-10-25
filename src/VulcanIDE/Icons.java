package VulcanIDE;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import java.awt.*;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/** Loads, scales, and draws the IDE icons. */
public final class Icons {
    private static final java.util.Map<String, BufferedImage> IMAGES = new java.util.concurrent.ConcurrentHashMap<>();
    public static final String DEBUG_ICON = "debug-icon.png";
    public static final String STEP_ICON = "step-icon.png";
    public static final String RESET_ICON = "reset-icon.png";

    private Icons() {}

    static java.net.URL resource(String path) {
        if (path == null || path.contains("..") || path.startsWith("/")) return null;
        java.net.URL url = Icons.class.getResource("/VulcanIDE/res/" + path);
        if (url != null) return url;

        try {
            java.nio.file.Path root = java.nio.file.Path.of(Icons.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!java.nio.file.Files.isDirectory(root)) root = root.getParent();
            for (int level=0; root != null && level<6; level++,root=root.getParent()) {
                for (String folder : new String[]{"src/VulcanIDE/res"}) {
                    java.nio.file.Path candidate=root.resolve(folder).resolve(path);
                    if(java.nio.file.Files.isRegularFile(candidate)) return candidate.toUri().toURL();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Creates a title-bar window control button. */
    /** Windows 10 style caption button: 46 px wide, full bar height, flat glyph in the button's foreground.
        A fullscreen button draws the restore glyph while its "vulcan.windowed" client property is true. */
    public static javax.swing.JButton windowControl(String resource, String tooltip) {
        boolean close = "x-icon.png".equals(resource), minimize = "mz-icon.png".equals(resource);
        javax.swing.JButton button = new javax.swing.JButton() {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                boolean hot = getModel().isRollover() || getModel().isPressed();
                Color glyph = getForeground();
                if (hot) {
                    if (close) { g.setColor(getModel().isPressed() ? new Color(241, 112, 122) : new Color(232, 17, 35)); glyph = Color.WHITE; }
                    else g.setColor(new Color(glyph.getRed(), glyph.getGreen(), glyph.getBlue(), getModel().isPressed() ? 51 : 26));
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
                g.setColor(glyph);
                int x = (getWidth() - 10) / 2, y = (getHeight() - 10) / 2;
                if (close) {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setStroke(new BasicStroke(1f));
                    g.draw(new java.awt.geom.Line2D.Double(x, y, x + 10, y + 10));
                    g.draw(new java.awt.geom.Line2D.Double(x + 10, y, x, y + 10));
                } else if (minimize) g.fillRect(x, y + 5, 10, 1);
                else if (Boolean.TRUE.equals(getClientProperty("vulcan.windowed"))) {
                    g.drawRect(x, y + 2, 7, 7);
                    g.drawLine(x + 2, y + 2, x + 2, y);
                    g.drawLine(x + 2, y, x + 9, y);
                    g.drawLine(x + 9, y, x + 9, y + 7);
                    g.drawLine(x + 9, y + 7, x + 7, y + 7);
                } else g.drawRect(x, y, 9, 9);
                g.dispose();
            }
        };
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(46, 31));
        button.setBorder(null);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setRolloverEnabled(true);
        return button;
    }

    /** Creates the close button shown on editor tabs. */
    public static javax.swing.JButton tabClose() {
        Icon cross = vector("close", 11, new Color(202, 205, 210));
        javax.swing.JButton button = new javax.swing.JButton(cross) {
            @Override protected void paintComponent(Graphics graphics) {
                if (getModel().isRollover() || getModel().isPressed()) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(93, 99, 108));
                    g.fillOval((getWidth() - 14) / 2, (getHeight() - 14) / 2, 14, 14);
                    g.dispose();
                }
                Graphics2D mark=(Graphics2D)graphics.create();
                try {
                    mark.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    mark.setColor(new Color(202,205,210));
                    mark.setStroke(new BasicStroke(1.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                    double centerX=getWidth()/2.0,centerY=getHeight()/2.0,radius=3.5;
                    mark.draw(new java.awt.geom.Line2D.Double(centerX-radius,centerY-radius,centerX+radius,centerY+radius));
                    mark.draw(new java.awt.geom.Line2D.Double(centerX+radius,centerY-radius,centerX-radius,centerY+radius));
                } finally { mark.dispose(); }
            }
        };
        button.setPreferredSize(new Dimension(18, 18));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        button.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
        button.setToolTipText("Close tab");
        button.setBorder(null);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        return button;
    }

    /** Loads an icon image from resources, or returns null when missing. */
    public static BufferedImage load(String path) {
        return IMAGES.computeIfAbsent(path, Icons::readImage);
    }

    private static BufferedImage readImage(String path) {
        java.net.URL url = resource(path);
        try (InputStream input = url == null ? null : url.openStream()) {
            if (input != null) {
                BufferedImage image = ImageIO.read(input);
                if (image != null) return image;
            }
        } catch (Exception ignored) {
        }
        BufferedImage defaultIcon = loadDefaultIcon();
        return defaultIcon == null ? fallbackImage(path) : defaultIcon;
    }

    private static BufferedImage loadDefaultIcon() {
        java.net.URL url = resource("default-icon.png");
        try (InputStream input = url == null ? null : url.openStream()) {
            return input == null ? null : ImageIO.read(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BufferedImage fallbackImage(String path) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(140, 148, 160));
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            String name = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("x-icon")) {
                g.drawLine(10, 10, 22, 22);
                g.drawLine(22, 10, 10, 22);
            } else if (name.contains("run-icon")) {
                g.fillPolygon(new int[]{11, 11, 23}, new int[]{7, 25, 16}, 3);
            } else if (name.contains("dir")) {
                g.drawRoundRect(5, 10, 22, 16, 2, 2);
                g.drawLine(6, 10, 13, 10);
                g.drawLine(13, 10, 16, 13);
            } else {
                g.drawRoundRect(5, 5, 22, 22, 5, 5);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
                g.drawString("V", 10, 22);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Loads an icon scaled to fit the given size. */
    public static Icon fit(String path, int width, int height) {
        if ("java-icon.png".equals(path)) return fit(load("intellij-java.png"), width, height);
        if ("txt-icon.png".equals(path)) return fit(load("intellij-text.png"), width, height);
        if ("dir-icon.png".equals(path)) return fit(load("intellij-folder.png"), width, height);
        if ("dir-open-icon.png".equals(path)) return fit(load("intellij-folder-open.png"), width, height);
        if ("c-icon.png".equals(path)||"h-icon.png".equals(path)) return cFileIcon(path,width, height);
        return fit(load(path), width, height);
    }

    private static Icon cFileIcon(String path,int width, int height) {
        BufferedImage image = load(path);
        int left=image.getWidth(), top=image.getHeight(), right=-1, bottom=-1;
        for (int y=0; y<image.getHeight(); y++) for (int x=0; x<image.getWidth(); x++)
            if ((image.getRGB(x,y) >>> 24) > 16) { left=Math.min(left,x); top=Math.min(top,y); right=Math.max(right,x); bottom=Math.max(bottom,y); }
        final BufferedImage glyph = right < left ? image : image.getSubimage(left,top,right-left+1,bottom-top+1);
        int padding=Math.max(2,(int)Math.round(Math.min(width,height)*0.15));
        final Icon fitted = fit(glyph, Math.max(1,width-padding*2), Math.max(1,height-padding*2));
        return new Icon() {
            public int getIconWidth() { return width; }
            public int getIconHeight() { return height; }
            public void paintIcon(Component component, Graphics graphics, int x, int y) { fitted.paintIcon(component,graphics,x+padding,y+padding); }
        };
    }

    /** Returns an action icon, drawing a vector fallback when the image is missing. */
    public static Icon action(String path, String type, int size, Color color) {
        return resource(path) == null
                ? vector(type, size, color) : fit(path, size, size);
    }

    /** Returns the file-type icon for a file. */
    public static Icon file(java.io.File file, int size) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String resource = dot < 0 ? "default-icon.png" : name.substring(dot + 1) + "-icon.png";
        if (resource(resource) == null) resource = "default-icon.png";
        return fit(resource, size, size);
    }

    /** Scales an image to fit the given size, preserving its aspect ratio. */
    public static Icon fit(BufferedImage image, int width, int height) {
        if (image == null || width <= 0 || height <= 0) return null;
        return new FittedIcon(image, width, height);
    }

    /** Draws a named vector icon such as run, stop, or fullscreen. */
    public static Icon vector(String type, int size, Color color) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(color == null ? Color.WHITE : color);
                    double s = size / 16.0;
                    g.translate(x, y);
                    g.scale(s, s);
                    switch (type) {
                        case "close" -> {
                            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            g.draw(new java.awt.geom.Line2D.Double(4, 4, 11, 11)); g.draw(new java.awt.geom.Line2D.Double(11, 4, 4, 11));
                        }
                        case "running" -> {
                            g.fillRoundRect(1, 1, 14, 14, 4, 4);
                            g.setColor(new Color(194, 199, 205));
                            Polygon p = new Polygon(new int[]{5, 5, 12}, new int[]{4, 12, 8}, 3);
                            g.fill(p);
                        }
                        case "run", "rerun" -> {
                            Polygon p = new Polygon(new int[]{4, 4, 13}, new int[]{2, 14, 8}, 3);
                            g.fill(p);
                        }
                        case "debug" -> {
                            g.drawRect(4, 4, 8, 8);
                            g.fillRect(2, 6, 2, 1); g.fillRect(12, 6, 2, 1);
                            g.fillRect(6, 2, 1, 2); g.fillRect(9, 2, 1, 2);
                            g.fillRect(6, 12, 1, 2); g.fillRect(9, 12, 1, 2);
                        }
                        case "stop" -> g.fillRoundRect(3, 3, 10, 10, 3, 3);
                        case "pause" -> { g.fillRect(3, 2, 4, 12); g.fillRect(9, 2, 4, 12); }
                        case "back" -> {
                            g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            g.drawLine(12, 8, 4, 8); g.drawLine(4, 8, 8, 4); g.drawLine(4, 8, 8, 12);
                        }
                        case "fullscreen" -> {
                            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                            g.drawLine(2, 6, 2, 2); g.drawLine(2, 2, 6, 2);
                            g.drawLine(10, 2, 14, 2); g.drawLine(14, 2, 14, 6);
                            g.drawLine(2, 10, 2, 14); g.drawLine(2, 14, 6, 14);
                            g.drawLine(10, 14, 14, 14); g.drawLine(14, 10, 14, 14);
                        }
                        case "windowed" -> {
                            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            g.drawRoundRect(2, 5, 10, 9, 1, 1);
                            g.drawLine(2, 8, 12, 8);
                            g.drawRoundRect(5, 2, 9, 9, 1, 1);
                            g.drawLine(5, 5, 14, 5);
                        }
                        case "executable" -> {
                            g.setColor(new Color(30, 54, 78));
                            g.fillRoundRect(1, 1, 14, 14, 4, 4);
                            g.setColor(color == null ? new Color(65, 170, 255) : color);
                            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            g.drawRoundRect(1, 1, 14, 14, 4, 4);
                            g.fillPolygon(new int[]{6, 6, 12}, new int[]{4, 12, 8}, 3);
                            g.setColor(new Color(255, 190, 75));
                            g.fillOval(3, 3, 2, 2);
                        }
                        default -> g.drawRect(3, 3, 10, 10);
                    }
                } finally { g.dispose(); }
            }
        };
    }

    private static final class FittedIcon implements Icon {
        private final BufferedImage image;
        private final int width;
        private final int height;

        private FittedIcon(BufferedImage image, int width, int height) {
            this.image = image;
            this.width = width;
            this.height = height;
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            double scale = Math.min(
                    (double) width / image.getWidth(),
                    (double) height / image.getHeight()
            );

            int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int drawX = x + (width - drawWidth) / 2;
            int drawY = y + (height - drawHeight) / 2;

            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
                g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                        RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
                g.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
            } finally {
                g.dispose();
            }
        }
    }
}
