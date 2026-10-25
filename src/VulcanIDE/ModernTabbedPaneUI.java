package VulcanIDE;

import javax.swing.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Flat themed tab look with close buttons and right-click menus that do not change the selection. */
public final class ModernTabbedPaneUI extends BasicTabbedPaneUI {
    private final SettingsManager settings;
    private final MouseWheelListener tabWheel = this::scrollTabs;

    @Override protected MouseListener createMouseListener() {
        MouseListener delegate = super.createMouseListener();
        return new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && !event.isPopupTrigger()) delegate.mousePressed(event);
            }
            @Override public void mouseReleased(MouseEvent event) { delegate.mouseReleased(event); }
            @Override public void mouseClicked(MouseEvent event) { delegate.mouseClicked(event); }
            @Override public void mouseEntered(MouseEvent event) { delegate.mouseEntered(event); }
            @Override public void mouseExited(MouseEvent event) { delegate.mouseExited(event); }
        };
    }

    @Override protected void installListeners() {
        super.installListeners();
        tabPane.addMouseWheelListener(tabWheel);
    }

    @Override protected void uninstallListeners() {
        tabPane.removeMouseWheelListener(tabWheel);
        super.uninstallListeners();
    }

    private void scrollTabs(MouseWheelEvent event) {
        if (tabPane.getTabLayoutPolicy() != JTabbedPane.SCROLL_TAB_LAYOUT || tabPane.getTabCount() == 0) return;
        for (Component component : tabPane.getComponents()) {
            if (component instanceof JViewport viewport && viewport.getBounds().contains(event.getPoint())) {
                boolean horizontal = tabPane.getTabPlacement() == JTabbedPane.TOP || tabPane.getTabPlacement() == JTabbedPane.BOTTOM;
                SmoothScroll.scroll(viewport, horizontal, event);
                return;
            }
        }
    }

    /** Creates the tab UI using theme colors from settings. */
    public ModernTabbedPaneUI(SettingsManager settings) { this.settings = settings; }

    @Override protected void installDefaults() {
        super.installDefaults();
        tabAreaInsets = new Insets(2, 4, 0, 4);
        contentBorderInsets = new Insets(0, 0, 0, 0);
        selectedTabPadInsets = new Insets(0, 0, 0, 0);
        tabInsets = new Insets(7, 13, 7, 13);

        shadow = new Color(0, 0, 0, 0);
    }

    @Override protected JButton createScrollButton(int direction) {
        return new InvisibleScrollButton();
    }

    private static final class InvisibleScrollButton extends JButton implements javax.swing.plaf.UIResource {
        @Override public Dimension getPreferredSize() { return new Dimension(0, 0); }
        @Override public void setVisible(boolean visible) { super.setVisible(false); }
    }

    @Override protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
                                                 int x, int y, int w, int h, boolean isSelected) {
        g.setColor(isSelected ? settings.getColor("tabSelected") : settings.getColor("panelBackground"));
        g.fillRoundRect(x, y + 1, w, Math.max(1, h - 2), 8, 8);
    }

    @Override protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                             int x, int y, int w, int h, boolean isSelected) { }

    @Override protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) { }

    @Override protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics,
                                       int tabIndex, String title, Rectangle textRect,
                                       boolean isSelected) {
        g.setFont(font);
        g.setColor(isSelected ? settings.getColor("tabText") : settings.getColor("tabTextMuted"));
        g.drawString(title, textRect.x, textRect.y + metrics.getAscent());
    }

    @Override protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects,
                                                   int tabIndex, Rectangle iconRect, Rectangle textRect,
                                                   boolean isSelected) { }
}
