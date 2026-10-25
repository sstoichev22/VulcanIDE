package VulcanIDE;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.List;
import java.util.function.Supplier;

/** Applies the Vulcan colors, fonts, and flat component styles. */
public final class Theme {
    private static final String PRESERVE_EDITOR_FONT = "vulcan.preserveEditorFont";
    private Theme() {}

    static void preserveEditorFont(JComponent component) {
        component.putClientProperty(PRESERVE_EDITOR_FONT, Boolean.TRUE);
    }

    private static boolean preservesEditorFont(Component component) {
        return component instanceof CodeTextPane
                || component instanceof JComponent swingComponent
                && Boolean.TRUE.equals(swingComponent.getClientProperty(PRESERVE_EDITOR_FONT));
    }

    /** Installs theme defaults into the Swing UI manager. */
    public static void configureUI(SettingsManager settings) {
        Font appFont = new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize("application"));
        String[] fontKeys = {
                "defaultFont", "Label.font", "Button.font", "Menu.font", "MenuItem.font",
                "CheckBoxMenuItem.font", "TabbedPane.font", "Tree.font", "TextField.font",
                "TextArea.font", "TextPane.font", "ComboBox.font", "OptionPane.font", "List.font"
        };

        for (Object key : java.util.Collections.list(UIManager.getDefaults().keys()))
            if (key.toString().endsWith(".font") || key.equals("defaultFont"))
                UIManager.put(key, new javax.swing.plaf.FontUIResource(appFont));
        for (String key : fontKeys) UIManager.put(key, new javax.swing.plaf.FontUIResource(appFont));

        Color panel = settings.getColor("panelBackground");
        Color input = settings.getColor("inputBackground");
        Color foreground = settings.getColor("text");
        Color selected = settings.getColor("tabSelected");
        Color fileSelected = settings.getColor("fileSelectionBackground");
        Color fileSelectedText = settings.getColor("fileSelectionText");
        Color divider = settings.getColor("splitDivider");

        UIManager.put("Panel.background", panel);
        UIManager.put("Viewport.background", input);
        UIManager.put("ScrollPane.background", panel);
        UIManager.put("TextArea.background", input);
        UIManager.put("TextArea.foreground", foreground);
        UIManager.put("TextField.background", input);
        UIManager.put("TextField.foreground", foreground);
        UIManager.put("TextPane.background", input);
        UIManager.put("TextPane.foreground", foreground);
        UIManager.put("Tree.background", input);
        UIManager.put("Tree.textForeground", foreground);
        UIManager.put("Tree.selectionBackground", fileSelected);
        UIManager.put("Tree.selectionForeground", fileSelectedText);
        UIManager.put("TabbedPane.background", panel);
        UIManager.put("TabbedPane.foreground", foreground);
        UIManager.put("TabbedPane.selected", selected);
        UIManager.put("TabbedPane.unselectedBackground", panel);
        UIManager.put("TabbedPane.contentAreaColor", panel);
        UIManager.put("TabbedPane.focus", new Color(0, 0, 0, 0));
        UIManager.put("SplitPane.background", panel);
        UIManager.put("SplitPane.dividerSize", 4);
        UIManager.put("Separator.background", divider);
        UIManager.put("MenuBar.background", panel);
        UIManager.put("Menu.background", panel);
        UIManager.put("Menu.foreground", foreground);
        UIManager.put("MenuItem.background", panel);
        UIManager.put("MenuItem.foreground", foreground);
        UIManager.put("Menu.selectionBackground", fileSelected);
        UIManager.put("Menu.selectionForeground", fileSelectedText);
        UIManager.put("MenuItem.selectionBackground", fileSelected);
        UIManager.put("MenuItem.selectionForeground", fileSelectedText);
        UIManager.put("PopupMenu.background", panel);
        UIManager.put("PopupMenu.foreground", foreground);
        UIManager.put("PopupMenu.border", BorderFactory.createEmptyBorder());
        // Metal's menu borders paint white highlight lines; every theme replaces them.
        for (String key : new String[] {"Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem"})
            UIManager.put(key + ".border", BorderFactory.createEmptyBorder());
        UIManager.put("MenuItem.margin", new Insets(3, 8, 3, 8));
        UIManager.put("CheckBoxMenuItem.background", panel);
        UIManager.put("CheckBoxMenuItem.foreground", foreground);
        UIManager.put("RadioButtonMenuItem.background", panel);
        UIManager.put("RadioButtonMenuItem.foreground", foreground);
        UIManager.put("RadioButtonMenuItem.selectionBackground", fileSelected);
        UIManager.put("RadioButtonMenuItem.selectionForeground", fileSelectedText);
        UIManager.put("CheckBoxMenuItem.selectionBackground", fileSelected);
        UIManager.put("CheckBoxMenuItem.selectionForeground", fileSelectedText);
        UIManager.put("ToolBar.background", panel);
        UIManager.put("ToolBar.foreground", foreground);
        UIManager.put("ToolBar.border", BorderFactory.createEmptyBorder());
        UIManager.put("Button.border", BorderFactory.createEmptyBorder());
        UIManager.put("ToggleButton.border", BorderFactory.createEmptyBorder());
        UIManager.put("OptionPane.background", panel);
        UIManager.put("OptionPane.messageForeground", foreground);
        UIManager.put("Button.background", panel);
        UIManager.put("Button.foreground", foreground);
        // Metal's field borders paint white highlights; outline fields with the theme divider instead.
        javax.swing.border.Border outline = BorderFactory.createLineBorder(divider);
        UIManager.put("TextField.border", new javax.swing.plaf.BorderUIResource(
                BorderFactory.createCompoundBorder(outline, BorderFactory.createEmptyBorder(2, 4, 2, 4))));
        UIManager.put("FormattedTextField.border", new javax.swing.plaf.BorderUIResource(BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        UIManager.put("Spinner.border", new javax.swing.plaf.BorderUIResource(outline));
        UIManager.put("Spinner.arrowButtonBorder", new javax.swing.plaf.BorderUIResource(BorderFactory.createEmptyBorder()));
        UIManager.put("ComboBox.border", new javax.swing.plaf.BorderUIResource(outline));
        // Metal's combo box paints its theme's white highlight; the basic UI and arrow buttons read these keys.
        UIManager.put("ComboBoxUI", "javax.swing.plaf.basic.BasicComboBoxUI");
        Color arrow = settings.getColor("tabTextMuted");
        UIManager.put("ComboBox.buttonBackground", panel);
        UIManager.put("ComboBox.buttonHighlight", panel);
        UIManager.put("ComboBox.buttonShadow", divider);
        UIManager.put("ComboBox.buttonDarkShadow", arrow);
        UIManager.put("control", panel);
        UIManager.put("controlLtHighlight", panel);
        UIManager.put("controlShadow", divider);
        UIManager.put("controlDkShadow", arrow);
        UIManager.put("ComboBox.selectionBackground", fileSelected);
        UIManager.put("ComboBox.selectionForeground", fileSelectedText);
        UIManager.put("ComboBox.background", input);
        UIManager.put("ComboBox.foreground", foreground);
    }

    /** Themes a component tree, keeping each control's size and style. */
    public static void apply(Component root, SettingsManager settings) {
        applyComponent(root, settings.getColor("applicationBackground"),
                settings.getColor("panelBackground"), settings.getColor("inputBackground"),
                settings.getColor("text"), settings);
    }

    private static void applyComponent(Component component, Color background, Color panel,
                                       Color input, Color foreground, SettingsManager settings) {
        Font existing = component.getFont();
        if (existing != null && !preservesEditorFont(component)) component.setFont(new Font(settings.getApplicationFont(), existing.getStyle(), existing.getSize()));
        if (component instanceof JToolBar || component instanceof JMenuBar) {
            component.setBackground(panel);
            component.setForeground(foreground);
            if (component instanceof JToolBar toolBar) toolBar.setBorder(null);
            if (component instanceof JMenuBar menuBar) menuBar.setBorder(null);
        } else if (component instanceof JSeparator separator) {
            separator.setForeground(settings.getColor("splitDivider"));
            separator.setBackground(panel);
        } else if (component instanceof JTextComponent text) {
            text.setBackground(settings.getColor("editorBackground"));
            text.setForeground(settings.getColor("editorText"));
            text.setCaretColor(settings.getColor("editorText"));
        } else {
            component.setBackground(background);
            component.setForeground(foreground);
        }

        if (component instanceof JButton button) {
            button.setBackground(panel);
            button.setForeground(foreground);
            button.setFocusPainted(false);
            button.setBorder(null);
            button.setContentAreaFilled(false);
            button.setOpaque(false);
        }
        if (component instanceof JToggleButton button) {
            button.setBackground(panel);
            button.setForeground(foreground);
            button.setFocusPainted(false);
            button.setBorder(null);
            button.setContentAreaFilled(false);
            button.setOpaque(false);
        }
        if (component instanceof JTree tree) {
            tree.setBackground(input);
            tree.setForeground(foreground);
        }
        if (component instanceof JTabbedPane tabbedPane) {
            tabbedPane.setBackground(panel);
            tabbedPane.setForeground(foreground);
            tabbedPane.setBorder(null);
            tabbedPane.setUI(new ModernTabbedPaneUI(settings));
        }
        if (component instanceof JScrollPane scrollPane) {
            scrollPane.getViewport().setBackground(input);
            scrollPane.setBackground(input);
            scrollPane.setBorder(null);
            styleScrollBar(scrollPane.getVerticalScrollBar(), settings);
            styleScrollBar(scrollPane.getHorizontalScrollBar(), settings);
        }
        if (component instanceof JSplitPane splitPane) {
            splitPane.setMinimumSize(new Dimension(0, 0));
            if (splitPane.getLeftComponent() != null) splitPane.getLeftComponent().setMinimumSize(new Dimension(0, 0));
            if (splitPane.getRightComponent() != null) splitPane.getRightComponent().setMinimumSize(new Dimension(0, 0));
            splitPane.setContinuousLayout(true);
            splitPane.setBackground(panel);
            splitPane.setBorder(null);
            splitPane.setDividerSize(Boolean.TRUE.equals(splitPane.getClientProperty("vulcan.hiddenDivider")) ? 0 : 4);
            splitPane.setOneTouchExpandable(false);
            splitPane.setUI(new SharpSplitPaneUI(settings.getColor("splitDivider")));

            splitPane.setBorder(null);
        }
        if (component instanceof JPanel panelComponent) panelComponent.setBackground(panel);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyComponent(child, background, panel, input, foreground, settings);
            }
        }
        if (component instanceof JMenu menu)
            for (Component child : menu.getMenuComponents()) applyComponent(child, background, panel, input, foreground, settings);
    }

    /** Gives a scrollbar the flat Vulcan look. */
    public static void styleScrollBar(JScrollBar bar, SettingsManager settings) {
        bar.setUnitIncrement(24);
        bar.setBlockIncrement(120);
        bar.setOpaque(false);
        bar.setBorder(null);
        bar.setUI(new FlatScrollBarUI(settings));
    }

    static void setErrorDividerSegmentsProvider(JSplitPane split, Supplier<List<Rectangle>> provider) {
        split.putClientProperty("vulcan.errorDividerSegments", provider);
        split.repaint();
    }

    /** Places a split divider at a proportion once the split pane has a size. */
    public static void initializeDivider(JSplitPane split, double proportion) {
        java.awt.event.ComponentAdapter initialize = new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                int size = split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? split.getWidth() : split.getHeight();
                if (size <= 0 || split.getLeftComponent() == null || split.getRightComponent() == null) return;
                split.setDividerLocation(proportion);
                split.removeComponentListener(this);
            }
        };
        split.addComponentListener(initialize);
    }

    private static final class SharpSplitPaneUI extends BasicSplitPaneUI {
        private final Color dividerColor;
        private int pendingLocation;
        private final Timer dragTimer = new Timer(16, event -> applyPendingDrag());
        private SharpSplitPaneUI(Color dividerColor) { this.dividerColor = dividerColor; }

        @Override protected void dragDividerTo(int location) {
            pendingLocation = location;
            if (!dragTimer.isRunning()) dragTimer.start();
        }

        private void applyPendingDrag() { super.dragDividerTo(pendingLocation); }

        @Override protected void finishDraggingTo(int location) {
            dragTimer.stop();
            super.dragDividerTo(location);
            super.finishDraggingTo(location);
        }

        @Override public void uninstallUI(JComponent component) {
            dragTimer.stop();
            super.uninstallUI(component);
        }

        @Override
        public BasicSplitPaneDivider createDefaultDivider() {
            return new BasicSplitPaneDivider(this) {
                { setBorder(null); }
                @Override public void paint(Graphics g) {
                    g.setColor(dividerColor);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    Object providerValue = getSplitPane().getClientProperty("vulcan.errorDividerSegments");
                    if (providerValue instanceof Supplier<?> provider) {
                        Object segmentValue = provider.get();
                        if (segmentValue instanceof List<?> segments) {
                            g.setColor(new Color(236, 82, 82));
                            for (Object segment : segments) {
                                if (!(segment instanceof Rectangle bounds)) continue;
                                int top = Math.max(0, bounds.y - getY());
                                int bottom = Math.min(getHeight(), bounds.y + bounds.height - getY());
                                if (bottom > top) g.fillRect(0, top, getWidth(), bottom - top);
                            }
                        }
                    }
                }
            };
        }
    }

    private static final class FlatScrollBarUI extends BasicScrollBarUI {
        private final SettingsManager settings;
        private FlatScrollBarUI(SettingsManager settings) { this.settings = settings; }

        @Override protected void configureScrollBarColors() {
            thumbColor = settings.getColor("splitDivider");
            trackColor = settings.getColor("panelBackground");
        }

        @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
        @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }

        private JButton zeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            button.setBorder(null);
            button.setOpaque(false);
            return button;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            g.setColor(settings.getColor("panelBackground"));
            g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
        }

        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (!c.isEnabled() || thumbBounds.width <= 0 || thumbBounds.height <= 0) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(settings.getColor("splitDivider"));
            int pad = scrollbar.getOrientation() == Adjustable.VERTICAL ? 3 : 2;
            Rectangle r = new Rectangle(thumbBounds.x + (scrollbar.getOrientation() == Adjustable.VERTICAL ? pad : 1),
                    thumbBounds.y + (scrollbar.getOrientation() == Adjustable.VERTICAL ? 1 : pad),
                    Math.max(4, thumbBounds.width - (scrollbar.getOrientation() == Adjustable.VERTICAL ? pad * 2 : 2)),
                    Math.max(4, thumbBounds.height - (scrollbar.getOrientation() == Adjustable.VERTICAL ? 2 : pad * 2)));
            g2.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
            g2.dispose();
        }
    }

    public static Color section(SettingsManager settings) { return settings.getColor("section"); }

    public static Color callJump(SettingsManager settings) { return settings.getColor("callJump"); }

    public static Color stack(SettingsManager settings) { return settings.getColor("stack"); }

    public static Color immediate(SettingsManager settings) { return settings.getColor("immediate"); }

    public static Color string(SettingsManager settings) { return settings.getColor("string"); }

    public static Color register(SettingsManager settings) { return settings.getColor("register"); }

    public static Color directive(SettingsManager settings) { return settings.getColor("directive"); }

    public static Color instruction(SettingsManager settings) { return settings.getColor("instruction"); }

    public static Color comment(SettingsManager settings) { return settings.getColor("comment"); }

    public static Color text(SettingsManager settings) { return settings.getColor("editorText"); }
}
