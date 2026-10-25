package VulcanIDE;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Editor workspace: file tree, editor tabs, console, and terminal. */
public class EditorTab extends JPanel {
    private final SettingsManager settings;
    private final RunningPrograms runningPrograms = new RunningPrograms(this);
    private final FileSystemPanel fileSystem;
    private final ConsolePanel console;
    private final TerminalPanel terminal;
    private final List<JTabbedPane> editorSections = new ArrayList<>(4);
    private final List<JSplitPane> sectionSplits = new ArrayList<>(3);
    private final JPanel editorGrid = new JPanel(new BorderLayout());
    private final JSplitPane fileEditor;
    private final JSplitPane mainSplit;
    private final JTabbedPane bottomTabs = new JTabbedPane();
    private final WorkspaceViews views = new WorkspaceViews();
    private final Map<EditorPanel, Component> tabHeaders = new HashMap<>();
    private final Map<EditorPanel, JTabbedPane> panelTabs = new IdentityHashMap<>();
    private final Map<EditorPanel, DetachedEditorWindow> detachedByPanel = new IdentityHashMap<>();
    private final Map<JTabbedPane, EditorPanel> previousSelection = new IdentityHashMap<>();
    private final Set<DetachedEditorWindow> detachedWindows = new LinkedHashSet<>();
    private boolean shuttingDown;
    private final Set<EditorPanel> closingPanels = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Path, EditorPanel> openFiles = new HashMap<>();
    private Consumer<Path> debugListener = path -> {};
    private Runnable settingsChangedListener = () -> {};
    private JTabbedPane activeSection;
    private EditorPanel lastSelectedPanel;
    private boolean restoringFiles;
    private EditorDropPreview dragPreview;
    private long assemblyGeneration;
    private EditorPanel assemblyPanel;

    /** Creates the workspace and restores previously open files. */
    public EditorTab(SettingsManager settings) {
        this.settings = settings;
        setLayout(new BorderLayout());
        setBorder(null);
        setBackground(settings.getColor("panelBackground"));

        console = new ConsolePanel(settings);
        terminal = new TerminalPanel(settings);
        fileSystem = new FileSystemPanel(settings, this::openPath, "editorFilePanel", "editor");
        fileSystem.setMoveListener(this::relocateOpenFiles);
        terminal.setMoveListener(this::relocateOpenFiles);
        fileSystem.setBeforeMoveListener(this::prepareOpenFilesForMove);
        terminal.setBeforeMoveListener(this::prepareOpenFilesForMove);
        fileSystem.setBeforeDeletionListener(this::closeDeletedFiles);
        fileSystem.setDirectoryListener(terminal::setWorkingDirectoryFromFilePanel);
        terminal.setRefreshListener(fileSystem::refresh);
        terminal.setBeforeDeletionListener(this::closeDeletedFiles);

        editorSections.add(createEditorSection());
        activeSection = editorSections.get(0);
        editorGrid.add(editorSections.get(0), BorderLayout.CENTER);

        bottomTabs.setUI(new ModernTabbedPaneUI(settings));
        bottomTabs.setMinimumSize(new Dimension(0, 76));
        bottomTabs.addTab("Console", console);
        bottomTabs.addTab("Terminal", terminal);

        fileEditor = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileSystem, editorGrid);
        editorGrid.setMinimumSize(new Dimension(0, 0));
        fileEditor.setDividerLocation(settings.getFilePanelDividerLocation());
        fileEditor.setContinuousLayout(true);
        fileEditor.setResizeWeight(0.0);
        fileEditor.setBorder(null);
        fileSystem.setErrorStatusProvider(this::fileHasErrors);
        Theme.setErrorDividerSegmentsProvider(fileEditor, () -> fileSystem.errorDividerSegments(fileEditor));

        mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, fileEditor, bottomTabs);
        mainSplit.setDividerLocation(settings.getConsoleDividerLocation());
        mainSplit.setContinuousLayout(true);
        mainSplit.setResizeWeight(0.75);
        mainSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> keepBottomPanelVisible());
        mainSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                keepBottomPanelVisible();
            }
        });
        mainSplit.setBorder(null);
        views.addSplit("Files", fileEditor, true);
        views.addSplit("Editor", fileEditor, false);
        views.add("Console", () -> bottomTabs.indexOfComponent(console) >= 0,
                visible -> setBottomVisible("Console", console, visible));
        views.add("Terminal", () -> bottomTabs.indexOfComponent(terminal) >= 0,
                visible -> setBottomVisible("Terminal", terminal, visible));

        add(mainSplit, BorderLayout.CENTER);
        add(runningPrograms, BorderLayout.NORTH);
        SwingUtilities.invokeLater(() -> {
            fileEditor.setDividerLocation(settings.getFilePanelDividerLocation());
            mainSplit.setDividerLocation(settings.getConsoleDividerLocation());
        });
        SwingUtilities.invokeLater(this::restoreOpenFiles);
    }

    private void keepBottomPanelVisible() {
        int height = mainSplit.getHeight();
        if (mainSplit.getBottomComponent() != null && height > 0)
            mainSplit.setDividerLocation(Math.min(mainSplit.getDividerLocation(), Math.max(0, height - 100)));
    }

    private JTabbedPane createEditorSection() {
        JTabbedPane section = new JTabbedPane() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getTabCount() != 0) return;
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setFont(new Font(settings.getApplicationFont(), Font.PLAIN,
                            Math.max(12, settings.getFontSize("editorTab"))));
                    g.setColor(settings.getColor("tabTextMuted"));
                    String hint = "Drop a tab here or open a file";
                    FontMetrics metrics = g.getFontMetrics();
                    g.drawString(hint, Math.max(12, (getWidth() - metrics.stringWidth(hint)) / 2),
                            Math.max(48, getHeight() / 2));
                } finally {
                    g.dispose();
                }
            }
        };
        section.setUI(new ModernTabbedPaneUI(settings));
        section.setMinimumSize(new Dimension(0, 0));
        section.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        section.addChangeListener(e -> onTabChanged(section));
        section.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { activeSection = section; }
        });
        installTabContextMenu(section);
        return section;
    }

    WorkspaceViews getViews() { return views; }
    ConsolePanel getConsole() { return console; }

    void showConsole() {
        views.set("Console", true);
        bottomTabs.setSelectedComponent(console);
        mainSplit.setDividerLocation(Math.min(mainSplit.getDividerLocation(), Math.max(0, mainSplit.getHeight() - 160)));
        revalidate();
    }

    /** Closes editors for a deleted file or any file under a deleted folder. */
    public void closeDeletedFiles(Path deleted) {
        Path target = deleted.toAbsolutePath().normalize();
        for (EditorPanel panel : new ArrayList<>(allOpenPanels())) {
            Path path = panel.getCurrentPath();
            if (path != null && path.startsWith(target)) {
                panel.discardUnsavedChanges();
                closePanel(panel);
            }
        }
    }

    private void setBottomVisible(String name, Component panel, boolean visible) {
        if (panel == console) console.clear();
        if (visible) {
            bottomTabs.insertTab(name, null, panel, null, "Console".equals(name) ? 0 : bottomTabs.getTabCount());
            bottomTabs.setSelectedComponent(panel);
        } else bottomTabs.remove(panel);
        views.group(mainSplit).set(false, bottomTabs.getTabCount() > 0);
    }

    private JSplitPane sectionSplit(int orientation, Component first, Component second) {
        JSplitPane split = new JSplitPane(orientation, first, second);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        Theme.apply(split, settings);
        sectionSplits.add(split);
        return split;
    }

    private JTabbedPane splitSection(JTabbedPane section, EditorDropPreview.Side side) {
        Container parent = section.getParent();
        boolean first = parent instanceof JSplitPane split && split.getLeftComponent() == section;
        JTabbedPane added = createEditorSection();
        added.setMinimumSize(new Dimension(0, 0));
        editorSections.add(added);
        parent.remove(section);
        boolean horizontal = side == EditorDropPreview.Side.LEFT || side == EditorDropPreview.Side.RIGHT;
        boolean before = side == EditorDropPreview.Side.LEFT || side == EditorDropPreview.Side.TOP;
        JSplitPane replacement = sectionSplit(horizontal ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT,
                before ? added : section, before ? section : added);
        if (parent instanceof JSplitPane split) {
            if (first) split.setLeftComponent(replacement); else split.setRightComponent(replacement);
        } else parent.add(replacement, BorderLayout.CENTER);
        editorGrid.revalidate();
        SwingUtilities.invokeLater(() -> replacement.setDividerLocation(0.5));
        return added;
    }

    private void removeEmptySection(JTabbedPane section) {
        if (section == null || section.getTabCount() != 0 || editorSections.size() <= 1 ||
                !editorSections.contains(section) || !(section.getParent() instanceof JSplitPane split)) return;
        Component remaining = split.getLeftComponent() == section ? split.getRightComponent() : split.getLeftComponent();
        Container parent = split.getParent();
        boolean first = parent instanceof JSplitPane outer && outer.getLeftComponent() == split;
        split.setLeftComponent(null);
        split.setRightComponent(null);
        parent.remove(split);
        if (parent instanceof JSplitPane outer) {
            if (first) outer.setLeftComponent(remaining); else outer.setRightComponent(remaining);
        } else parent.add(remaining, BorderLayout.CENTER);
        sectionSplits.remove(split);
        editorSections.remove(section);
        previousSelection.remove(section);
        if (activeSection == section) activeSection = editorSections.get(0);
        editorGrid.revalidate();
        editorGrid.repaint();
    }

    public void setSettingsChangedListener(Runnable listener) { settingsChangedListener = listener == null ? () -> {} : listener; }

    public void setDeletionListener(Consumer<Path> listener) { fileSystem.setDeletionListener(listener); terminal.setDeletionListener(listener); }

    /** Sets the listener notified after files move. */
    public void setMoveListener(java.util.function.BiConsumer<Path, Path> listener) {
        fileSystem.setMoveListener(listener);
        terminal.setMoveListener(listener);
    }

    /** Saves editors under source before a move; returns false to cancel. */
    public boolean prepareOpenFilesForMove(Path source) {
        Path from = source.toAbsolutePath().normalize();
        for (EditorPanel panel : allOpenPanels()) {
            Path path = panel.getCurrentPath();
            if (path != null && path.startsWith(from) && !panel.prepareForFileMove()) return false;
        }
        return true;
    }

    /** Updates open editors after a file or folder move. */
    public void relocateOpenFiles(Path source, Path destination) {
        Path from = source.toAbsolutePath().normalize();
        Path to = destination.toAbsolutePath().normalize();
        for (EditorPanel panel : allOpenPanels()) {
            Path path = panel.getCurrentPath();
            if (path != null && path.startsWith(from)) panel.relocateFile(to.resolve(from.relativize(path)));
        }
        syncOpenFilesAndTitles();
    }

    /** Sets the action that opens a file in the debugger. */
    public void setDebugListener(Consumer<Path> listener) {
        debugListener = listener == null ? path -> {} : listener;
    }

    private EditorPanel createEditorPanel() {
        EditorPanel[] holder = new EditorPanel[1];
        holder[0] = new EditorPanel(settings, console,
                () -> {
                    Path path = holder[0] == null ? null : holder[0].getCurrentPath();
                    if (path != null) debugListener.accept(path);
                },
                () -> SwingUtilities.invokeLater(() -> {
                    syncOpenFilesAndTitles();
                    if (settings.isSettingsEditing()) { refreshTheme(); settingsChangedListener.run(); }
                }));
        holder[0].setBuildStatusListener(text->runningPrograms.setBuildStatus(holder[0],text));
        holder[0].setDiagnosticsChangedListener(fileEditor::repaint);
        holder[0].setRunListener(() -> { showConsole(); runningPrograms.started(holder[0]); });
        holder[0].setProjectTextAccess(SearchBar.openEditors(this::allOpenPanels));
        holder[0].setRefactoringPanels(this::allOpenPanels);
        holder[0].setRefactoringPanels(this::allOpenPanels);
        holder[0].setMarkdownNavigation(this::openPath);
        holder[0].setDeclarationNavigator((path, offset) -> {
            openPath(path);
            EditorPanel target = openFiles.get(path.toAbsolutePath().normalize());
            if (target != null) target.selectDeclaration(offset);
        });
        holder[0].setSelectionListener(() -> {
            lastSelectedPanel = holder[0];
            JTabbedPane tabs = panelTabs.get(holder[0]);
            if (editorSections.contains(tabs)) activeSection = tabs;
            persistOpenFiles();
        });
        return holder[0];
    }

    /** Prompts for a name and creates a file in the working directory. */
    public void newFile() {
        String name = JOptionPane.showInputDialog(this, "File name:", "New File", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        try {
            openPath(createTrackedFile(settings.getCurrentDirectory(), name));
        } catch (IOException | IllegalArgumentException error) {
            VulcanDialog.message(this, settings, "New File", error.getMessage() == null ? "Enter a valid, unused filename." : error.getMessage());
        }
    }

    static Path createTrackedFile(Path directory, String name) throws IOException {
        if (directory == null || name == null || name.isBlank()) throw new IllegalArgumentException("Enter a filename.");
        String fileName = name.strip();
        if (fileName.isEmpty() || fileName.equals(".") || fileName.equals("..") || fileName.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("Enter a valid filename without folder separators or reserved characters.");
        }
        if (fileName.endsWith(".") || fileName.endsWith(" ")) throw new IllegalArgumentException("A filename cannot end with a dot or space.");
        String deviceName = fileName.substring(0, fileName.indexOf('.') < 0 ? fileName.length() : fileName.indexOf('.')).strip();
        if (deviceName.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IllegalArgumentException("That filename is reserved by Windows.");
        Path root = directory.toAbsolutePath().normalize();
        Path supplied;
        try { supplied = Path.of(fileName); } catch (InvalidPathException invalid) { throw new IllegalArgumentException("Enter a valid filename.", invalid); }
        if (supplied.isAbsolute() || supplied.getRoot() != null || supplied.getNameCount() != 1 || supplied.getParent() != null) throw new IllegalArgumentException("Enter a filename, not a path.");
        Path target = root.resolve(supplied).normalize();
        if (!target.startsWith(root) || !root.equals(target.getParent())) throw new IllegalArgumentException("The new file must stay inside the working directory.");
        if (!Files.isDirectory(root)) throw new IOException("The working directory does not exist.");
        Path created = Files.createFile(target);
        BuildRun.created(created);
        return created;
    }

    public void openFile() { fileSystem.chooseProjectFolder(); }

    /** Opens a file in an editor tab, or selects its tab when already open. */
    public void openPath(Path path) {
        if (path == null || !Files.isRegularFile(path)) return;
        Path absolute = path.toAbsolutePath().normalize();
        EditorPanel existing = openFiles.get(absolute);
        if (existing != null) {
            JTabbedPane owner = panelTabs.get(existing);
            if (owner != null) {
                if (owner != editorSections.get(0)) {
                    transferPanel(existing, editorSections.get(0), null);
                    owner = editorSections.get(0);
                }
                owner.setSelectedComponent(existing);
                lastSelectedPanel = existing;
                if (editorSections.contains(owner)) activeSection = owner;
                settings.setCurrentFile(absolute);
                persistOpenFiles();
                Window window = SwingUtilities.getWindowAncestor(owner);
                if (window != null) window.toFront();
                existing.focusEditor();
            }
            return;
        }

        EditorPanel panel = createEditorPanel();
        panel.openFile(absolute);
        openFiles.put(absolute, panel);
        addPanelToTabs(panel, targetMainSection(), absolute.getFileName().toString());
        fileSystem.openIfNeeded(absolute);
    }

    private void addPanelToTabs(EditorPanel panel, JTabbedPane tabs, String title) {
        panelTabs.put(panel, tabs);
        tabs.addTab(title, panel);
        openTabHeader(tabs, panel, title, true);
        tabs.setSelectedComponent(panel);
        if (editorSections.contains(tabs)) activeSection = tabs;
    }

    private void openTabHeader(JTabbedPane tabs, EditorPanel panel, String title, boolean closeable) {
        JPanel header = new JPanel(new BorderLayout(5, 0));
        header.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setForeground(settings.getColor("tabText"));
        label.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize("editorTab")));
        label.setOpaque(false);
        installTabPopupListener(header, panel, true);
        installTabPopupListener(label, panel, true);
        JButton close = Icons.tabClose();
        close.addActionListener(e -> closePanel(panel));
        installTabPopupListener(close, panel, false);
        header.add(label, BorderLayout.CENTER);
        if (closeable) header.add(close, BorderLayout.EAST);
        tabHeaders.put(panel, header);
        int index = tabs.indexOfComponent(panel);
        if (index >= 0) tabs.setTabComponentAt(index, header);
    }

    private boolean closePanel(EditorPanel panel) {
        if (panel == null || !saveBeforeClosing(panel)) return false;
        Path path = panel.getCurrentPath();
        panel.closeFile();
        runningPrograms.removed(panel);
        if (path != null) openFiles.remove(path.toAbsolutePath().normalize());
        JTabbedPane tabs = panelTabs.get(panel);
        DetachedEditorWindow detached = detachedByPanel.remove(panel);
        if (tabs != null) {
            int index = tabs.indexOfComponent(panel);
            if (index >= 0) {
                closingPanels.add(panel);
                tabs.removeTabAt(index);
                closingPanels.remove(panel);
            }
        }
        panelTabs.remove(panel);
        tabHeaders.remove(panel);
        if (detached != null) {
            detached.refreshWindowTitle();
            if (detached.tabCount() == 0) {
                detachedWindows.remove(detached);
                detached.dispose();
            }
        }
        persistOpenFiles();
        removeEmptySection(tabs);
        if (tabs != null && tabs == activeSection && tabs.getTabCount() == 0) settings.setSelectedEditorTab(0);
        return true;
    }

    void installTabContextMenu(JTabbedPane tabs) {
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { maybeShowTabContextMenu(event, tabs, null); }
            @Override public void mouseReleased(MouseEvent event) { maybeShowTabContextMenu(event, tabs, null); }
        });
    }

    private void installTabPopupListener(Component target, EditorPanel panel, boolean draggable) {
        MouseAdapter listener = new MouseAdapter() {
            private Point pressPoint;
            private boolean dragStarted;

            @Override public void mousePressed(MouseEvent event) {
                maybeShowTabContextMenu(event, panelTabs.get(panel), panel);
                if (!draggable || !SwingUtilities.isLeftMouseButton(event)) return;
                JTabbedPane tabs = panelTabs.get(panel);
                if (tabs == null) return;
                tabs.setSelectedComponent(panel);
                lastSelectedPanel = panel;
                if (editorSections.contains(tabs)) activeSection = tabs;
                persistOpenFiles();
                pressPoint = event.getLocationOnScreen();
                dragStarted = false;

            }

            @Override public void mouseDragged(MouseEvent event) {
                if (pressPoint == null) return;
                Point location = event.getLocationOnScreen();
                if (!dragStarted && pressPoint.distance(location) >= 7) {
                    dragStarted = true;
                    dragPreview = new EditorDropPreview(SwingUtilities.getWindowAncestor(EditorTab.this), panel.getCurrentPath() == null ? "Untitled" : panel.getCurrentPath().getFileName().toString(), settings);
                }
                if (dragStarted && dragPreview != null) {
                    DropDestination destination = findDropDestination(location);
                    EditorDropPreview.Side side = dropSide(panel, destination, location);
                    dragPreview.update(location, destination.tabs, side);
                }
            }
            @Override public void mouseReleased(MouseEvent event) {
                maybeShowTabContextMenu(event, panelTabs.get(panel), panel);
                if (dragStarted) moveDraggedPanel(panel, event.getLocationOnScreen());
                if (dragPreview != null) { dragPreview.close(); dragPreview = null; }
                pressPoint = null;
                dragStarted = false;

            }
        };
        target.addMouseListener(listener);
        if (draggable) target.addMouseMotionListener(listener);
    }

    private void maybeShowTabContextMenu(MouseEvent event, JTabbedPane tabs, EditorPanel knownPanel) {
        if (!event.isPopupTrigger()) return;
        if (tabs == null) return;
        int index = knownPanel == null
                ? tabs.indexAtLocation(event.getX(), event.getY())
                : tabs.indexOfComponent(knownPanel);
        if (index < 0) return;
        EditorPanel clickedPanel = panelAt(tabs, index);
        if (clickedPanel == null) return;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem closeTab = new JMenuItem("Close Tab");
        closeTab.addActionListener(e -> closePanel(clickedPanel));
        menu.add(closeTab);

        JMenuItem closeRight = new JMenuItem("Close Tabs to the Right");
        closeRight.setEnabled(index < tabs.getTabCount() - 1);
        closeRight.addActionListener(e -> closeTabsInRange(tabs, index + 1, tabs.getTabCount()));
        menu.add(closeRight);

        JMenuItem closeLeft = new JMenuItem("Close Tabs to the Left");
        closeLeft.setEnabled(index > 0);
        closeLeft.addActionListener(e -> closeTabsInRange(tabs, 0, index));
        menu.add(closeLeft);

        JMenuItem closeAll = new JMenuItem("Close All Tabs");
        closeAll.addActionListener(e -> closeTabsInRange(tabs, 0, tabs.getTabCount()));
        menu.add(closeAll);
        menu.show(event.getComponent(), event.getX(), event.getY());
    }

    private void closeTabsInRange(JTabbedPane tabs, int fromInclusive, int toExclusive) {
        List<EditorPanel> panels = new ArrayList<>();
        int last = Math.min(toExclusive, tabs.getTabCount()) - 1;
        for (int i = last; i >= Math.max(0, fromInclusive); i--) {
            EditorPanel panel = panelAt(tabs, i);
            if (panel != null) panels.add(panel);
        }
        for (EditorPanel panel : panels) {
            if (!closePanel(panel)) break;
        }
    }

    private EditorDropPreview.Side dropSide(EditorPanel panel, DropDestination destination, Point screenPoint) {
        JTabbedPane tabs = destination.tabs;
        if (tabs != null && tabs.getSelectedComponent() instanceof EditorPanel selected && selected.isMarkdownDocument())
            return EditorDropPreview.Side.CENTER;
        if (tabs == null || tabs.getTabCount() == 0 || (destination.detachedWindow != null ? destination.detachedWindow.getPanes().size() >= 4 : editorSections.size() >= 4) ||
                (tabs == panelTabs.get(panel) && tabs.getTabCount() <= 1)) return EditorDropPreview.Side.CENTER;
        Point local = new Point(screenPoint);
        SwingUtilities.convertPointFromScreen(local, tabs);
        return EditorDropPreview.sideAt(local, tabs.getSize());
    }

    private void moveDraggedPanel(EditorPanel panel, Point screenPoint) {
        if (panel == null || !panelTabs.containsKey(panel)) return;
        DropDestination destination = findDropDestination(screenPoint);
        if (destination.tabs != null) {
            EditorDropPreview.Side side = dropSide(panel, destination, screenPoint);
            JTabbedPane target = side == EditorDropPreview.Side.CENTER ? destination.tabs : destination.detachedWindow == null ? splitSection(destination.tabs, side) : destination.detachedWindow.split(destination.tabs, side);
            transferPanel(panel, target, destination.detachedWindow);
        } else if (!destination.insideVulcanWindow) {
            DetachedEditorWindow detached = new DetachedEditorWindow(this, settings);
            detachedWindows.add(detached);
            transferPanel(panel, detached.getEditorTabs(), detached);
            detached.showNear(screenPoint);
        }
    }
    private DropDestination findDropDestination(Point screenPoint) {
        List<DetachedEditorWindow> windows = new ArrayList<>(detachedWindows);
        for (int i = windows.size() - 1; i >= 0; i--) {
            DetachedEditorWindow window = windows.get(i);
            if (window.isVisible() && window.getBounds().contains(screenPoint)) {
                return new DropDestination(window.tabsAt(screenPoint), window, true);
            }
        }
        Window mainWindow = SwingUtilities.getWindowAncestor(this);
        if (mainWindow != null && mainWindow.getBounds().contains(screenPoint)) {
            JTabbedPane target = findSectionAt(mainWindow, screenPoint);
            return new DropDestination(target, null, true);
        }
        return new DropDestination(null, null, false);
    }

    private JTabbedPane findSectionAt(Window window, Point screenPoint) {
        Point point = new Point(screenPoint);
        SwingUtilities.convertPointFromScreen(point, window);
        Component component = SwingUtilities.getDeepestComponentAt(window, point.x, point.y);
        while (component != null) {
            if (component instanceof JTabbedPane tabs && editorSections.contains(tabs)) return tabs;
            component = component.getParent();
        }
        return null;
    }

    private void transferPanel(EditorPanel panel, JTabbedPane target, DetachedEditorWindow targetWindow) {
        JTabbedPane source = panelTabs.get(panel);
        if (source == null || target == null || source == target) return;
        int sourceIndex = source.indexOfComponent(panel);
        if (sourceIndex < 0) return;
        String title = source.getTitleAt(sourceIndex);
        DetachedEditorWindow oldWindow = detachedByPanel.get(panel);
        source.removeTabAt(sourceIndex);
        tabHeaders.remove(panel);
        panelTabs.put(panel, target);
        if (targetWindow == null) detachedByPanel.remove(panel);
        else detachedByPanel.put(panel, targetWindow);
        target.addTab(title, panel);
        openTabHeader(target, panel, title, true);
        target.setSelectedComponent(panel);
        if (editorSections.contains(target)) activeSection = target;
        if (targetWindow != null) targetWindow.refreshWindowTitle();
        if (oldWindow != null && oldWindow != targetWindow) {
            oldWindow.refreshWindowTitle();
            if (oldWindow.tabCount() == 0) {
                detachedWindows.remove(oldWindow);
                oldWindow.dispose();
            }
        }
        removeEmptySection(source);
        persistOpenFiles();
    }

    void closeDetachedWindow(DetachedEditorWindow window) {
        if (window == null || !detachedWindows.contains(window)) return;
        List<EditorPanel> panels = new ArrayList<>();
        for(JTabbedPane tabs : window.getPanes())
        for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
            EditorPanel panel = panelAt(tabs, i);
            if (panel != null) panels.add(panel);
        }
        for (EditorPanel panel : panels) if (!closePanel(panel)) return;
        detachedWindows.remove(window);
        window.dispose();
    }

    private static final class DropDestination {
        private final JTabbedPane tabs;
        private final DetachedEditorWindow detachedWindow;
        private final boolean insideVulcanWindow;

        private DropDestination(JTabbedPane tabs, DetachedEditorWindow detachedWindow, boolean insideVulcanWindow) {
            this.tabs = tabs;
            this.detachedWindow = detachedWindow;
            this.insideVulcanWindow = insideVulcanWindow;
        }
    }

    void onTabChanged(JTabbedPane tabs) {
        EditorPanel previous = previousSelection.get(tabs);
        EditorPanel current = panelAt(tabs, tabs.getSelectedIndex());
        if (previous != null && previous != current && !closingPanels.contains(previous) && previous.isDirty()) previous.saveFile();
        previousSelection.put(tabs, current);
        if (current != null) { lastSelectedPanel = current;runningPrograms.selected(current.getCurrentPath()); }
        if (editorSections.contains(tabs)) {
            activeSection = tabs;
            if (!restoringFiles) persistOpenFiles();
        }
        refreshCurrentTabTitle();
        refreshTabHeaderColors();
        if (!restoringFiles && current != null && current.getCurrentPath() != null &&
                !current.getCurrentPath().toAbsolutePath().normalize().equals(settings.getPath())) {
            settings.setCurrentFile(current.getCurrentPath());
        }
    }

    private void refreshTabHeaderColors() {
        for (JTabbedPane tabs : allTabPanes()) {
            int selected = tabs.getSelectedIndex();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                Component header = tabHeaders.get(panelAt(tabs, i));
                if (header instanceof JPanel panel && panel.getComponentCount() > 0 &&
                        panel.getComponent(0) instanceof JLabel label) {
                    label.setForeground(settings.getColor(i == selected ? "tabText" : "tabTextMuted"));
                }
            }
        }
    }

    private EditorPanel currentEditor() {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        for (Component component = focused; component != null; component = component.getParent()) {
            if (component instanceof EditorPanel editor) return editor;
        }
        JTabbedPane section = activeSection;
        return section == null ? null : panelAt(section, section.getSelectedIndex());
    }

    private EditorPanel panelAt(JTabbedPane tabs, int index) {
        if (tabs == null || index < 0 || index >= tabs.getTabCount()) return null;
        Component component = tabs.getComponentAt(index);
        return component instanceof EditorPanel editor ? editor : null;
    }

    private JTabbedPane targetMainSection() {
        return editorSections.get(0);
    }

    private List<JTabbedPane> allTabPanes() {
        List<JTabbedPane> panes = new ArrayList<>(editorSections);
        for (DetachedEditorWindow window : detachedWindows) panes.addAll(window.getPanes());
        return panes;
    }

    private List<EditorPanel> allOpenPanels() {
        List<EditorPanel> panels = new ArrayList<>();
        for (JTabbedPane tabs : allTabPanes()) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                EditorPanel panel = panelAt(tabs, i);
                if (panel != null) panels.add(panel);
            }
        }
        return panels;
    }

    private void syncOpenFilesAndTitles() {
        openFiles.clear();
        for (EditorPanel panel : allOpenPanels()) {
            if (panel == null) continue;
            Path path = panel.getCurrentPath();
            if (path != null) openFiles.put(path.toAbsolutePath().normalize(), panel);
        }
        refreshCurrentTabTitle();
        refreshTabHeaderColors();
        persistOpenFiles();
        fileEditor.repaint();
    }

    private boolean fileHasErrors(Path path) {
        EditorPanel panel = openFiles.get(path.toAbsolutePath().normalize());
        return panel != null && panel.hasErrors();
    }

    private void persistOpenFiles() {
        if (restoringFiles) return;
        List<Path> paths = new ArrayList<>();
        EditorPanel selected = lastSelectedPanel;
        int selectedIndex = 0;
        for (EditorPanel panel : allOpenPanels()) {
            if (panel.getCurrentPath() != null) {
                if (panel == selected) selectedIndex = paths.size();
                paths.add(panel.getCurrentPath());
            }
        }
        settings.setOpenFiles(paths);
        settings.setSelectedEditorTab(selectedIndex);
    }

    private void restoreOpenFiles() {
        java.util.List<Path> paths = settings.getOpenFiles();
        int savedIndex = settings.getSelectedEditorTab();
        Path selectedPath = savedIndex >= 0 && savedIndex < paths.size() ? paths.get(savedIndex) : null;
        if (paths.isEmpty() && settings.getCurrentFile() != null) paths = java.util.List.of(settings.getCurrentFile());
        restoringFiles = true;
        try {
            for (Path path : paths) if (Files.isRegularFile(path)) openPath(path);
            JTabbedPane firstSection = editorSections.get(0);
            EditorPanel selected = selectedPath == null ? null : openFiles.get(selectedPath.toAbsolutePath().normalize());
            if (selected != null) firstSection.setSelectedComponent(selected);
            else if (firstSection.getTabCount() > 0) firstSection.setSelectedIndex(0);
        } finally {
            restoringFiles = false;
        }
        onTabChanged(editorSections.get(0));
    }

    private void refreshCurrentTabTitle() {
        for (JTabbedPane tabs : allTabPanes()) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                EditorPanel panel = panelAt(tabs, i);
                if (panel == null) continue;
                Path path = panel.getCurrentPath();
                String title = path == null ? tabs.getTitleAt(i) : path.getFileName().toString();
                tabs.setTitleAt(i, title);
                Component header = tabHeaders.get(panel);
                if (header instanceof JPanel p && p.getComponentCount() > 0 &&
                        p.getComponent(0) instanceof JLabel label) {
                    label.setText(title);
                    label.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize("editorTab")));
                }
            }
        }
        for (DetachedEditorWindow window : detachedWindows) window.refreshWindowTitle();
    }

    public void paste() { EditorPanel panel = currentEditor(); if (panel != null) panel.paste(); }

    public void performAction(String actionName) { EditorPanel panel = currentEditor(); if (panel != null) panel.performAction(actionName); }

    public void closeFile() { closePanel(currentEditor()); }

    /** Runs the current file. */
    public void runCurrentFile() {
        EditorPanel panel = currentEditor();
        if (panel != null) panel.performRun();
    }

    /** Builds the current file without running it. */
    public void assembleCurrentFile() {
        EditorPanel panel = currentEditor();
        Path path = panel == null ? null : panel.getCurrentPath();
        if (path == null || !path.getFileName().toString().toLowerCase().endsWith(".asm")) return;
        panel.saveFile();if(panel.isDirty())return;
        long generation = ++assemblyGeneration;
        if (assemblyPanel != null && assemblyPanel != panel) runningPrograms.setBuildStatus(assemblyPanel, null);
        assemblyPanel = panel;
        publishAssemblyStatus(generation, panel, "Assembling " + path.getFileName());
        new SwingWorker<Path,Void>() {
            protected Path doInBackground() throws Exception {
                return ProgramRunner.assembleToBinary(path, null, status -> publishAssemblyStatus(generation, panel, status));
            }
            protected void done() {
                if (generation != assemblyGeneration) return;
                publishAssemblyStatus(generation, panel, null);
                if (assemblyPanel == panel) assemblyPanel = null;
                try { get(); fileSystem.refresh(); if (settings.isSettingsEditing()) settings.reloadFromDisk(); }
                catch (Exception error) { console.appendError("Assembly error: " + VulcanDialog.errorSummary(error) + "\n"); }
            }
        }.execute();
    }

    private void publishAssemblyStatus(long generation, EditorPanel panel, String status) {
        Runnable update = () -> {
            if (generation != assemblyGeneration) return;
            runningPrograms.setBuildStatus(panel, status);
            console.setBuildStatus(status);
        };
        if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update);
    }

    /** Stops the current file's program. */
    public void stopRunning() {
        EditorPanel panel = currentEditor();
        if (panel != null) panel.stopRunning();
    }

    /** Stops every running program. */
    public void stopAllRunning() {
        for (EditorPanel panel : allOpenPanels()) panel.stopRunning();
        terminal.stop();
    }

    /** Saves state and stops background work before exit. */
    public void shutdown() {
        if (shuttingDown) return;
        saveAll();
        shuttingDown = true;
        stopAllRunning();
        for (EditorPanel panel : allOpenPanels()) panel.closeRunner();
        runningPrograms.clear();
        terminal.close();
        console.clear();
        for (DetachedEditorWindow window : new ArrayList<>(detachedWindows)) window.dispose();
        detachedWindows.clear();
    }

    /** Reopens a file remembered in settings. */
    public void openPathFromSettings(Path path) {
        if (path != null) openPath(path);
    }

    /** Saves every open editor. */
    public void saveAll() {
        if (shuttingDown) return;
        settings.setEditorDividerLocations(views.group(fileEditor).savedDivider(), views.group(mainSplit).savedDivider());
        for (EditorPanel panel : allOpenPanels()) {
            if (panel.getCurrentPath() != null) {
                settings.setFileScrollPosition(panel.getCurrentPath(), panel.getScrollPosition());
            }
            if (panel.isDirty()) panel.saveFile();
        }
        for (DetachedEditorWindow window : detachedWindows) window.refreshWindowTitle();
        persistOpenFiles();
    }

    boolean saveAllAndReportSuccess() {
        saveAll();
        for (EditorPanel panel : allOpenPanels()) {
            if (panel.isDirty()) return false;
        }
        return true;
    }

    /** Saves all editors; returns false when the window must stay open. */
    public boolean prepareToClose() {
        for (EditorPanel panel : allOpenPanels()) if (!saveBeforeClosing(panel)) return false;
        return true;
    }

    private boolean saveBeforeClosing(EditorPanel panel) {
        return panel.saveOnClose();
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        setBackground(settings.getColor("panelBackground"));
        fileSystem.refreshTheme();
        console.refreshTheme();
        terminal.refreshTheme();
        for (EditorPanel panel : allOpenPanels()) panel.refreshTheme();
        for (DetachedEditorWindow window : detachedWindows) window.refreshTheme();
        refreshCurrentTabTitle();
        refreshTabHeaderColors();
        repaint();
    }

    /** Returns the file of the selected editor, or null. */
    public Path getCurrentPath() {
        EditorPanel p = currentEditor();
        return p == null ? null : p.getCurrentPath();
    }

    public SettingsManager getSettings() { return settings; }

    /** Reports whether a file is open in any editor. */
    public boolean hasOpenPath(Path path) {
        if (path == null) return false;
        return openFiles.containsKey(path.toAbsolutePath().normalize());
    }

    /** Reports whether the selected file can be run. */
    public boolean canRunCurrentFile() {
        EditorPanel p = currentEditor();
        return p != null && p.canRunCurrentFile();
    }
}
