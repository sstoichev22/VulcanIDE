package VulcanIDE;

import javax.swing.*;
import java.awt.Point;
import java.awt.event.MouseWheelEvent;

/** Time-based pixel wheel scrolling shared by editors, trees, panels, and tab strips. */
final class SmoothScroll {
    private static final double PIXELS_PER_NOTCH = 40;
    private static final double RESPONSE_NANOS = 28_000_000.0;
    private static final String ANIMATION_PROPERTY = "vulcan.smoothAnimation";

    private SmoothScroll() {}

    /** Re-dispatches a wheel event from a nested component to its enclosing smooth-scrolling pane. */
    static void forward(JComponent source, MouseWheelEvent event) {
        JScrollPane pane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, source);
        if (pane == null) return;
        Point point = SwingUtilities.convertPoint(source, event.getPoint(), pane);
        pane.dispatchEvent(new MouseWheelEvent(pane, event.getID(), event.getWhen(), event.getModifiersEx(),
                point.x, point.y, event.getXOnScreen(), event.getYOnScreen(), event.getClickCount(), event.isPopupTrigger(),
                event.getScrollType(), event.getScrollAmount(), event.getWheelRotation(), event.getPreciseWheelRotation()));
        event.consume();
    }

    /** Replaces stepwise wheel scrolling with elapsed-time smoothing; direct viewport moves cancel it. */
    static void install(JScrollPane pane) {
        if (Boolean.TRUE.equals(pane.getClientProperty("smoothWheel"))) return;
        pane.putClientProperty("smoothWheel", true);
        pane.setWheelScrollingEnabled(false);
        pane.getViewport().setScrollMode(JViewport.BLIT_SCROLL_MODE);
        pane.addMouseWheelListener(event -> {
            if (event.isControlDown() || event.isConsumed()) return;
            JScrollBar bar = event.isShiftDown() ? pane.getHorizontalScrollBar() : pane.getVerticalScrollBar();
            if (bar.isVisible()) scroll(pane.getViewport(), event.isShiftDown(), event);
        });
    }

    /** Scrolls a real viewport in pixels without restarting the response for every wheel sample. */
    static void scroll(JViewport viewport, boolean horizontal, MouseWheelEvent event) {
        if (event.isConsumed() || event.isControlDown() || event.getPreciseWheelRotation() == 0) return;
        Animation animation = (Animation) viewport.getClientProperty(ANIMATION_PROPERTY);
        if (animation == null) {
            animation = new Animation(viewport);
            viewport.putClientProperty(ANIMATION_PROPERTY, animation);
        }
        animation.scroll(horizontal, event.getPreciseWheelRotation() * PIXELS_PER_NOTCH);
        event.consume();
    }

    /** Stops pending wheel motion before an explicit editor navigation or drag begins. */
    static void cancel(JScrollPane pane) {
        if (pane.getViewport().getClientProperty(ANIMATION_PROPERTY) instanceof Animation animation) animation.cancel();
    }

    private static final class Animation {
        private final JViewport viewport;
        private final Timer timer;
        private Point observed;
        private boolean horizontal;
        private boolean moving;
        private double position;
        private double target;
        private long previous;

        Animation(JViewport viewport) {
            this.viewport = viewport;
            observed = viewport.getViewPosition();
            timer = new Timer(5, event -> advance());
            timer.setCoalesce(true);
            viewport.addChangeListener(event -> {
                Point current = viewport.getViewPosition();
                if (!moving && !current.equals(observed)) cancel();
                observed = current;
            });
            viewport.addHierarchyListener(event -> { if (!viewport.isShowing()) cancel(); });
        }

        void scroll(boolean axis, double pixels) {
            int current = axis ? viewport.getViewPosition().x : viewport.getViewPosition().y;
            if (horizontal != axis || (!timer.isRunning() && current != (int) Math.round(target))) {
                position = target = current;
            }
            horizontal = axis;
            target = Math.max(0, Math.min(maximum(), target + pixels));
            if (!timer.isRunning()) {
                previous = System.nanoTime();
                timer.start();
            }
        }

        private int maximum() {
            return Math.max(0, horizontal ? viewport.getViewSize().width - viewport.getExtentSize().width
                    : viewport.getViewSize().height - viewport.getExtentSize().height);
        }

        void cancel() {
            timer.stop();
            position = target = horizontal ? viewport.getViewPosition().x : viewport.getViewPosition().y;
        }

        private void advance() {
            long now = System.nanoTime();
            target = Math.max(0, Math.min(maximum(), target));
            position += (target - position) * -Math.expm1(-(now - previous) / RESPONSE_NANOS);
            previous = now;
            if (Math.abs(target - position) < 0.5) {
                position = target;
                timer.stop();
            }
            Point next = viewport.getViewPosition();
            if (horizontal) next.x = (int) Math.round(position);
            else next.y = (int) Math.round(position);
            moving = true;
            try { viewport.setViewPosition(next); }
            finally { moving = false; }
        }
    }
}
