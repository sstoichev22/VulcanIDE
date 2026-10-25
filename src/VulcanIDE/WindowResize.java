package VulcanIDE;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Edge and corner resizing and work-area maximizing for undecorated windows. */
final class WindowResize {
    /** Installs root-edge resizing and clears inherited cursors when resizing ends or becomes unavailable. */
    static void install(JFrame frame) {
        frame.setMinimumSize(new Dimension(320, 240));
        JRootPane root = frame.getRootPane();
        root.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        class ResizeHandler extends MouseAdapter {
            private int edges;
            private Point origin;
            private Rectangle bounds;

            private int edge(Point point) {
                if (!frame.isResizable() || (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0
                        || root.getClientProperty("vulcan.restoreBounds") != null) return 0;
                int value = 0;
                if (point.x < 6) value |= 1;
                if (point.x >= root.getWidth() - 6) value |= 2;
                if (point.y < 6) value |= 4;
                if (point.y >= root.getHeight() - 6) value |= 8;
                return value;
            }

            private void reset() {
                edges = 0;
                origin = null;
                bounds = null;
                root.setCursor(Cursor.getDefaultCursor());
            }

            @Override public void mouseMoved(MouseEvent event) {
                int value = edge(event.getPoint());
                root.setCursor(Cursor.getPredefinedCursor(value == 0 ? Cursor.DEFAULT_CURSOR
                        : (value == 5 || value == 10) ? Cursor.NW_RESIZE_CURSOR
                        : (value == 6 || value == 9) ? Cursor.NE_RESIZE_CURSOR
                        : (value & 3) != 0 ? Cursor.E_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR));
            }

            @Override public void mouseExited(MouseEvent event) {
                root.setCursor(Cursor.getDefaultCursor());
            }

            @Override public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                edges = edge(event.getPoint());
                if (edges == 0) return;
                origin = event.getLocationOnScreen();
                bounds = frame.getBounds();
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (edges == 0 || origin == null) return;
                if (!frame.isResizable() || (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0
                        || root.getClientProperty("vulcan.restoreBounds") != null) {
                    reset();
                    return;
                }
                Point point = event.getLocationOnScreen();
                int dx = point.x - origin.x, dy = point.y - origin.y;
                Rectangle next = new Rectangle(bounds);
                if ((edges & 1) != 0) { next.x += dx; next.width -= dx; }
                if ((edges & 2) != 0) next.width += dx;
                if ((edges & 4) != 0) { next.y += dy; next.height -= dy; }
                if ((edges & 8) != 0) next.height += dy;
                Dimension minimum = frame.getMinimumSize();
                if (next.width < minimum.width) {
                    if ((edges & 1) != 0) next.x = bounds.x + bounds.width - minimum.width;
                    next.width = minimum.width;
                }
                if (next.height < minimum.height) {
                    if ((edges & 4) != 0) next.y = bounds.y + bounds.height - minimum.height;
                    next.height = minimum.height;
                }
                frame.setBounds(next);
            }

            @Override public void mouseReleased(MouseEvent event) { reset(); }
        }
        ResizeHandler resize = new ResizeHandler();
        root.addMouseListener(resize);
        root.addMouseMotionListener(resize);
        frame.addPropertyChangeListener("resizable", event -> resize.reset());
        frame.addWindowStateListener(event -> resize.reset());
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent event) { resize.reset(); }
        });
        root.addPropertyChangeListener("vulcan.restoreBounds", event -> resize.reset());
    }

    /** A root pane whose children may overlap, so caption buttons pinned by pinCaption paint over the content. */
    static JRootPane rootPane() {
        JRootPane root = new JRootPane() {
            @Override public boolean isOptimizedDrawingEnabled() { return false; }
        };
        root.setOpaque(true); // as JFrame's own root pane, so the resize border paints the theme color
        return root;
    }

    /** Windows 10 style caption: moves the buttons from the title bar's east side onto the root pane's top-right
        corner, over the resize border, so the close button reaches the window corner. Needs a rootPane() frame. */
    static void pinCaption(JFrame frame, JPanel bar, JPanel controls) {
        JRootPane root = frame.getRootPane();
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        bar.remove(controls);
        bar.add(spacer, BorderLayout.EAST);
        controls.setOpaque(true);
        root.add(controls, 0);
        Runnable place = () -> {
            Insets inset = root.getInsets();
            int height = inset.top + bar.getPreferredSize().height - bar.getInsets().bottom;
            for (Component button : controls.getComponents()) button.setPreferredSize(new Dimension(46, height));
            int width = controls.getComponentCount() * 46;
            controls.setBounds(root.getWidth() - width, 0, width, height);
            spacer.setPreferredSize(new Dimension(Math.max(0, width - inset.right), 0));
            controls.validate();
            bar.revalidate();
        };
        root.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { place.run(); }
        });
        place.run();
    }

    /** Toggles a frame between its normal bounds and the work area of its monitor, keeping the taskbar visible. */
    static void toggleWorkAreaMaximized(JFrame frame) {
        Object saved = frame.getRootPane().getClientProperty("vulcan.restoreBounds");
        if (saved instanceof Rectangle restore) {
            frame.getRootPane().putClientProperty("vulcan.restoreBounds", null);
            frame.setBounds(restore);
            return;
        }
        GraphicsConfiguration configuration = frame.getGraphicsConfiguration();
        if (configuration == null) return;
        frame.getRootPane().putClientProperty("vulcan.restoreBounds", frame.getBounds());
        frame.setBounds(MainWindow.workAreaBounds(configuration.getBounds(), Toolkit.getDefaultToolkit().getScreenInsets(configuration)));
    }
}
