package VulcanIDE;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Main Vulcan window with custom title bar, menus, and the Editor, Debugger, and Display tabs. */
public class MainWindow extends JFrame {
    static final boolean NATIVE_WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    private static final int FULLSCREEN_SNAP_ZONE_PX = 14;
    private final SettingsManager settings;
    private final JTabbedPane tabs;
    private final EditorTab editorTab;
    private final DebuggerTab debuggerTab;
    private final DisplayPanel displayPanel;
    private Rectangle restoreBounds;
    private Rectangle lastNormalBounds;
    private boolean fullscreen;
    private int previousMainTab = -1;
    private JButton fullscreenButton;
    private JPanel captionButtons;
    private JLabel titleLabel;
    private Point dragOffset;
    private Point dragStartScreen;
    private GraphicsConfiguration snapCandidateConfiguration;
    private Timer deletionTimer;
    private long assemblyGeneration;
    private final Map<ConsolePanel, Long> assemblyGenerations = new IdentityHashMap<>();
    private final JPanel deletionBanner = new JPanel(new BorderLayout(8, 0));

    /** Builds the window and restores its saved bounds and tabs. */
    public MainWindow() {
        super("Vulcan " + Vulcan.VERSION);
        settings = SettingsManager.load();
        Theme.configureUI(settings);
        setUndecorated(true);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setIconImage(iconImage());
        setLayout(new BorderLayout());
        getContentPane().setBackground(settings.getColor("applicationBackground"));

        tabs = new JTabbedPane();
        tabs.setUI(new ModernTabbedPaneUI(settings));
        editorTab = new EditorTab(settings);
        debuggerTab = new DebuggerTab(settings);
        debuggerTab.setBeforeProgramLoad(() -> saveAllBeforeDebuggerLoad(editorTab, settings));
        displayPanel = new DisplayPanel(settings, debuggerTab.getCpu(), debuggerTab.getMemory());
        editorTab.setDebugListener(path -> openDebugger(path));
        editorTab.setSettingsChangedListener(this::applyTheme);
        tabs.addTab("Editor", editorTab);
        tabs.addTab("Debugger", debuggerTab);
        tabs.addTab("Display", displayPanel);
        tabs.addChangeListener(e -> {
            int selectedTab = tabs.getSelectedIndex();
            Path current = settings.getCurrentFile();
            if (selectedTab == 1 && isDebuggerFile(current) && !debuggerTab.selectFileIfAllowed(current)) {
                tabs.setSelectedIndex(previousMainTab < 0 ? 0 : previousMainTab);
                return;
            }
            if (selectedTab == 1 && isDebuggerFile(current)) settings.setCurrentFile(current);
            previousMainTab = selectedTab;
            settings.setSelectedMainTab(selectedTab);
            if (selectedTab == 2) displayPanel.selectFile(current);
            displayPanel.setActive(selectedTab == 2);
        });

        JPanel chrome = new JPanel(new BorderLayout());
        JPanel customTitle=createTitleBar();
        chrome.add(customTitle, BorderLayout.NORTH);
        chrome.add(createMenuBar(), BorderLayout.SOUTH);
        add(chrome, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        createDeletionBanner();
        editorTab.setDeletionListener(this::handleDeletedFile);
        debuggerTab.setBeforeDeletionListener(editorTab::closeDeletedFiles);
        debuggerTab.setDeletionListener(this::handleDeletedFile);
        displayPanel.setBeforeDeletionListener(editorTab::closeDeletedFiles);
        displayPanel.setDeletionListener(this::handleDeletedFile);
        editorTab.setMoveListener(this::handleMovedFile);
        debuggerTab.setMoveListener(this::handleMovedFile);
        displayPanel.setMoveListener(this::handleMovedFile);
        debuggerTab.setBeforeMoveListener(editorTab::prepareOpenFilesForMove);
        displayPanel.setBeforeMoveListener(editorTab::prepareOpenFilesForMove);

        GraphicsConfiguration initialScreen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        setBounds(initialWindowBounds(initialScreen));
        lastNormalBounds = new Rectangle(getBounds());
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent event) { rememberNormalBounds(); }
            @Override public void componentResized(ComponentEvent event) { rememberNormalBounds(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (!editorTab.prepareToClose()) return;
                editorTab.shutdown();
                debuggerTab.shutdown();
                displayPanel.close();
                editorTab.saveAll();
                saveWindowState();
                dispose();
                System.exit(0);
            }
            @Override public void windowDeactivated(WindowEvent e) {
                editorTab.saveAll();
                saveWindowState();
            }
        });
        addWindowStateListener(event -> {
            if (isMaximizedState(event.getNewState())) SwingUtilities.invokeLater(() -> {
                if (!isMaximizedState(getExtendedState())) return;
                Rectangle normal = lastNormalBounds;
                setExtendedState(Frame.NORMAL);
                if (normal != null && !fullscreen) setBounds(normal);
                if (!fullscreen) enterFullscreen();
                else setBounds(usableBounds(getGraphicsConfiguration()));
            });
        });
        WindowResize.install(this);
        WindowResize.pinCaption(this, customTitle, captionButtons);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"exitFullscreen");
        getRootPane().getActionMap().put("exitFullscreen",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){if(fullscreen)exitFullscreen();}});
        applyTheme();

        displayPanel.setActive(false);
        updateWindowControlVisibility();
        SwingUtilities.invokeLater(() -> {
            int saved = settings.getSelectedMainTab();
            if (saved >= 0 && saved < tabs.getTabCount()) tabs.setSelectedIndex(saved);
            if (settings.isWindowFullscreen()) enterFullscreen();
        });

    }

    private void createDeletionBanner() {
        deletionBanner.setBackground(new Color(150, 55, 55));
        deletionBanner.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JLabel text = new JLabel();
        text.setForeground(Color.WHITE);
        deletionBanner.add(text, BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(e -> deletionBanner.setVisible(false));
        deletionBanner.add(close, BorderLayout.EAST);
        deletionBanner.setVisible(false);
        getLayeredPane().add(deletionBanner, JLayeredPane.POPUP_LAYER);
        deletionBanner.putClientProperty("messageLabel", text);
    }

    private void handleDeletedFile(Path deleted) {
        if (deleted == null) return;
        Path target = deleted.toAbsolutePath().normalize();
        editorTab.closeDeletedFiles(target);
        if(debuggerTab.getProgramFile()!=null&&debuggerTab.getProgramFile().startsWith(target))debuggerTab.clearProgram();
        displayPanel.clearDeletedFile(target);
        if (settings.getCurrentFile() != null && settings.getCurrentFile().startsWith(target)) settings.setCurrentFile(null);
        showFileNotification("Deleted " + target.getFileName());
    }

    private void handleMovedFile(Path source, Path destination) {
        Path from = source.toAbsolutePath().normalize();
        Path to = destination.toAbsolutePath().normalize();
        Path current = settings.getCurrentFile();
        editorTab.relocateOpenFiles(from, to);
        debuggerTab.relocateFile(from, to);
        displayPanel.relocateFile(from, to);
        if (current != null && current.startsWith(from)) settings.setCurrentFile(to.resolve(from.relativize(current)));
        showFileNotification((java.util.Objects.equals(from.getParent(),to.getParent())?"Renamed ":"Moved ")+from.getFileName()+" to "+to.getFileName());
    }

    static void notifyFileChange(Component source, String message) { Window window=SwingUtilities.getWindowAncestor(source); if(window instanceof MainWindow main)main.showFileNotification(message); }
    private void showFileNotification(String message) {
        ((JLabel)deletionBanner.getClientProperty("messageLabel")).setText(message);
        if(deletionTimer!=null)deletionTimer.stop();
        Dimension size=deletionBanner.getPreferredSize();
        size.width=Math.min(size.width,Math.max(160,getLayeredPane().getWidth()-24));
        deletionBanner.setBounds(Math.max(12,(getLayeredPane().getWidth()-size.width)/2),Math.max(0,getLayeredPane().getHeight()-size.height-16),size.width,size.height);
        deletionBanner.setVisible(true);deletionBanner.revalidate();deletionBanner.repaint();
        deletionTimer=new Timer(3500,event->deletionBanner.setVisible(false));deletionTimer.setRepeats(false);deletionTimer.start();
    }
    private Image iconImage() {
        return Icons.load("vulcan-icon.png");
    }

    @Override protected JRootPane createRootPane() { return WindowResize.rootPane(); }

    private JPanel createTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, settings.getColor("splitDivider")));
        bar.setPreferredSize(new Dimension(0, 32));

        JPanel titleArea = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 5));
        JLabel logo = new JLabel(Icons.fit("vulcan-icon.png", 16, 16));
        titleLabel = new JLabel("Vulcan " + Vulcan.VERSION);
        titleLabel.setFont(new Font(settings.getApplicationFont(), Font.BOLD, settings.getFontSize("applicationTitle")));
        titleArea.setOpaque(false);
        titleArea.add(logo);
        titleArea.add(titleLabel);
        bar.add(titleArea, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        controls.setOpaque(false);
        captionButtons = controls;
        JButton minimize = titleButton("mz-icon.png", "Minimize");
        fullscreenButton = titleButton("fs-icon.png", "Fullscreen");
        JButton close = titleButton("x-icon.png", "Close");
        minimize.addActionListener(e -> setState(Frame.ICONIFIED));
        fullscreenButton.addActionListener(e -> {
            if (fullscreen) exitFullscreen();
            else enterFullscreen();
        });
        close.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        controls.add(minimize);
        controls.add(fullscreenButton);
        controls.add(close);
        bar.add(controls, BorderLayout.EAST);

        MouseAdapter drag = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragOffset = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), MainWindow.this);
                    dragStartScreen = e.getLocationOnScreen();
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOffset == null) return;
                Point screen = e.getLocationOnScreen();
                if (fullscreen) {
                    if (dragStartScreen != null && dragStartScreen.distance(screen) < 5) return;
                    int maximizedWidth = getWidth();
                    exitFullscreen();
                    dragOffset.x = maximizedWidth <= 0 ? 0
                            : (int) Math.round((double) dragOffset.x * getWidth() / maximizedWidth);
                }
                int x = screen.x - dragOffset.x;
                int y = screen.y - dragOffset.y;
                GraphicsConfiguration target = graphicsConfigurationAt(screen);
                if (target != null) {
                    Rectangle usable = usableBounds(target);
                    snapCandidateConfiguration = y <= usable.y + FULLSCREEN_SNAP_ZONE_PX ? target : null;
                    int titleHeight = Math.max(1, bar.getHeight());
                    int lowestVisibleTop = usable.y + Math.max(0, usable.height - titleHeight);
                    y = Math.max(usable.y, Math.min(y, lowestVisibleTop));
                } else snapCandidateConfiguration = null;
                setLocation(x, y);
            }
            @Override public void mouseReleased(MouseEvent e) {
                dragOffset = null;
                dragStartScreen = null;
                if (snapCandidateConfiguration != null && !fullscreen) {
                    enterFullscreen();
                } else if (!fullscreen && SwingUtilities.isLeftMouseButton(e)) {
                    Point screen = e.getLocationOnScreen();
                    GraphicsConfiguration target = graphicsConfigurationAt(screen);
                    Rectangle half = target == null ? null : halfScreenSnap(usableBounds(target), screen.x);
                    if (half != null) setBounds(half);
                }
                snapCandidateConfiguration = null;
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    if (fullscreen) exitFullscreen(); else enterFullscreen();
                }
            }
        };
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
        logo.addMouseListener(drag);
        logo.addMouseMotionListener(drag);
        titleArea.addMouseListener(drag);
        titleArea.addMouseMotionListener(drag);
        titleLabel.addMouseListener(drag);
        titleLabel.addMouseMotionListener(drag);
        return bar;
    }

    private JButton titleButton(String iconName, String tooltip) {
        return Icons.windowControl(iconName, tooltip);
    }

    private GraphicsConfiguration graphicsConfigurationAt(Point point) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            if (configuration.getBounds().contains(point)) return configuration;
        }
        return getGraphicsConfiguration();
    }

    private Rectangle initialWindowBounds(GraphicsConfiguration preferredScreen) {
        Rectangle saved = settings.getWindowBounds();
        String requestedDevice = settings.getWindowDeviceId();
        if (requestedDevice != null && !requestedDevice.isBlank()) {
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                if (requestedDevice.equals(device.getIDstring())) {
                    preferredScreen = device.getDefaultConfiguration();
                    break;
                }
            }
        }
        Rectangle preferredUsable = usableBounds(preferredScreen);
        if (saved == null) return centeredWindowBounds(preferredUsable);

        Rectangle bestUsable = null;
        long bestVisibleArea = 0;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle usable = usableBounds(device.getDefaultConfiguration());
            Rectangle visible = saved.intersection(usable);
            long visibleArea = Math.max(0L, (long) visible.width) * Math.max(0L, (long) visible.height);
            if (visibleArea > bestVisibleArea) {
                bestVisibleArea = visibleArea;
                bestUsable = usable;
            }
        }

        if (bestUsable == null || bestVisibleArea < 100L * 100L) {
            return centeredWindowBounds(preferredUsable);
        }
        return clampToUsableBounds(saved, bestUsable);
    }

    private void saveWindowState() {
        boolean maximized = fullscreen || isMaximizedState(getExtendedState());
        Rectangle bounds = boundsForPersistence(maximized, restoreBounds, lastNormalBounds, getBounds());
        GraphicsConfiguration configuration = maximized ? getGraphicsConfiguration() : graphicsConfigurationAt(new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
        settings.setWindowState(bounds, maximized, configuration == null ? "" : configuration.getDevice().getIDstring());
    }

    static Rectangle boundsForPersistence(boolean maximized, Rectangle restoreBounds, Rectangle lastNormalBounds,
                                          Rectangle currentBounds) {
        if (!maximized) return new Rectangle(currentBounds);
        if (lastNormalBounds != null) return new Rectangle(lastNormalBounds);
        if (restoreBounds != null) return new Rectangle(restoreBounds);
        return new Rectangle(currentBounds);
    }

    private void rememberNormalBounds() {
        if (fullscreen || isMaximizedState(getExtendedState())) return;
        lastNormalBounds = new Rectangle(getBounds());
    }

    static Rectangle clampToUsableBounds(Rectangle saved, Rectangle usable) {
        int width = Math.min(saved.width, usable.width);
        int height = Math.min(saved.height, usable.height);
        int x = Math.max(usable.x, Math.min(saved.x, usable.x + usable.width - width));
        int y = Math.max(usable.y, Math.min(saved.y, usable.y + usable.height - height));
        return new Rectangle(x, y, width, height);
    }

    static Rectangle centeredWindowBounds(Rectangle usable) {
        int width = Math.min(usable.width, Math.min(1440, Math.max(800, usable.width * 3 / 4)));
        int height = Math.min(usable.height, Math.min(900, Math.max(600, usable.height * 3 / 4)));
        int x = usable.x + Math.max(0, (usable.width - width) / 2);
        int y = usable.y + Math.max(0, (usable.height - height) / 2);
        return new Rectangle(x, y, width, height);
    }

    private static Rectangle usableBounds(GraphicsConfiguration configuration) {
        return workAreaBounds(configuration.getBounds(), Toolkit.getDefaultToolkit().getScreenInsets(configuration));
    }

    static Rectangle workAreaBounds(Rectangle screenBounds, Insets screenInsets) {
        return new Rectangle(screenBounds.x + screenInsets.left, screenBounds.y + screenInsets.top,
                Math.max(0, screenBounds.width - screenInsets.left - screenInsets.right),
                Math.max(0, screenBounds.height - screenInsets.top - screenInsets.bottom));
    }

    /** Returns the left or right half of the work area when a title-bar drag ends at that edge, else null. */
    static Rectangle halfScreenSnap(Rectangle usable, int pointerX) {
        int half = usable.width / 2;
        if (pointerX <= usable.x + FULLSCREEN_SNAP_ZONE_PX) return new Rectangle(usable.x, usable.y, half, usable.height);
        if (pointerX >= usable.x + usable.width - 1 - FULLSCREEN_SNAP_ZONE_PX)
            return new Rectangle(usable.x + usable.width - half, usable.y, half, usable.height);
        return null;
    }

    static boolean isMaximizedState(int extendedState) {
        return (extendedState & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }

    private void enterFullscreen() {
        if (fullscreen) return;
        restoreBounds = getBounds();
        lastNormalBounds = new Rectangle(restoreBounds);
        setExtendedState(Frame.NORMAL);
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        setBounds(usableBounds(gc));
        fullscreen = true;
        setResizable(false);
        updateWindowControlVisibility();
    }

    private void exitFullscreen() {
        fullscreen = false;
        setResizable(true);
        setExtendedState(Frame.NORMAL);
        if (restoreBounds != null) {
            setBounds(restoreBounds);
            lastNormalBounds = new Rectangle(restoreBounds);
        }
        restoreBounds = null;
        updateWindowControlVisibility();
    }

    private void updateWindowControlVisibility() {
        if (fullscreenButton != null) {
            fullscreenButton.setVisible(true);
            fullscreenButton.putClientProperty("vulcan.windowed", fullscreen);
            fullscreenButton.repaint();
            fullscreenButton.setToolTipText(fullscreen ? "Windowed" : "Fullscreen");
        }
    }

    private static boolean isDebuggerFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".asm") || name.endsWith(".exe") || name.endsWith(".c");
    }
    private void openDebugger(Path path) {
        if (!isDebuggerFile(path)) return;
        if (!debuggerTab.debugAsync(path)) return;
        settings.setCurrentFile(path);
        tabs.setSelectedIndex(1);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu file = buildFileMenu(editorTab, () -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));

        JMenu edit = new JMenu("Edit");
        edit.add(actionItem("Undo", e -> editorTab.performAction("vulcan-undo")));
        edit.add(actionItem("Redo", e -> editorTab.performAction("vulcan-redo")));
        edit.addSeparator();
        edit.add(actionItem("Cut", e -> editorTab.performAction("cut-to-clipboard")));
        edit.add(actionItem("Copy", e -> editorTab.performAction("copy-to-clipboard")));
        edit.add(actionItem("Paste", e -> editorTab.paste()));
        edit.add(actionItem("Select All", e -> editorTab.performAction("select-all")));

        JMenu run = new JMenu("Run");
        run.add(actionItem("Run", e -> runActiveProgram()));
        run.add(actionItem("Assemble", e -> assembleActiveFile()));
        run.add(actionItem("Stop", e -> { editorTab.stopAllRunning(); debuggerTab.stop(); displayPanel.stopProgram(); }));
        run.add(actionItem("Reset", e -> {
            if (tabs.getSelectedIndex() == 1) debuggerTab.reset(); else runActiveProgram();
        }));

        JMenu view = new JMenu("View");
        view.addMenuListener(new javax.swing.event.MenuListener() {
            public void menuSelected(javax.swing.event.MenuEvent event) { populateViewMenu(view); }
            /** Unused menu callback. */
            public void menuDeselected(javax.swing.event.MenuEvent event) { }
            /** Unused menu callback. */
            public void menuCanceled(javax.swing.event.MenuEvent event) { }
        });
        populateViewMenu(view);

        JMenu settingsMenu = new JMenu("Settings");
        settingsMenu.add(actionItem("Open settings.json", e -> openSettingsFile()));

        JMenu tools = new JMenu("Tools");
        tools.add(actionItem("Assembler", e -> assembleActiveFile()));
        tools.add(actionItem("Disassembler", e -> showDebuggerView("Instructions")));
        tools.add(actionItem("Memory Viewer", e -> showDebuggerView("Memory")));
        tools.add(actionItem("CPU Information", e -> openDocument("asm.md")));
        tools.addSeparator();
        tools.add(buildThemeMenu());

        JMenu help = new JMenu("Help");
        help.add(actionItem("Documentation", e -> openDocument("vulcan.md")));
        help.add(actionItem("Assembly and CPU Reference", e -> openDocument("asm.md")));
        help.add(actionItem("C Compiler Reference", e -> openDocument("c.md")));

        menuBar.add(file);
        menuBar.add(edit);
        menuBar.add(run);
        menuBar.add(view);
        menuBar.add(settingsMenu);
        menuBar.add(tools);
        menuBar.add(help);
        return menuBar;
    }

    static JMenu buildFileMenu(EditorTab editorTab, Runnable exitAction) {
        JMenu file = new JMenu("File");
        file.add(item("New", KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK, e -> editorTab.newFile()));
        file.add(item("Open Project", KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK, e -> editorTab.openFile()));
        file.add(actionItem("Close File", e -> editorTab.closeFile()));
        file.addSeparator();
        file.add(actionItem("Exit", e -> exitAction.run()));
        return file;
    }

    private static JMenuItem item(String name, int key, int modifiers, java.awt.event.ActionListener listener) {
        JMenuItem item = new JMenuItem(name);
        item.setAccelerator(KeyStroke.getKeyStroke(key, modifiers));
        item.addActionListener(listener);
        return item;
    }

    private void populateViewMenu(JMenu menu) {
        menu.removeAll();
        WorkspaceViews views = switch (tabs.getSelectedIndex()) {
            case 1 -> debuggerTab.getViews();
            case 2 -> displayPanel.getViews();
            default -> editorTab.getViews();
        };
        views.options().forEach((name, visible) -> {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(name, visible);
            item.addActionListener(event -> views.set(name, item.isSelected()));
            menu.add(item);
        });
        menu.addSeparator();
        JMenu workspace = new JMenu("Workspace");
        for (int i = 0; i < tabs.getTabCount(); i++) {
            int index = i;
            workspace.add(actionItem(tabs.getTitleAt(i), event -> tabs.setSelectedIndex(index)));
        }
        menu.add(workspace);
        menu.addSeparator();String windowModeLabel=fullscreen?"Exit fullscreen":"Fullscreen";JMenuItem full=actionItem(windowModeLabel,e->{if(fullscreen)exitFullscreen();else enterFullscreen();});full.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F11,0));menu.add(full);
    }

    private static JMenuItem actionItem(String name, java.awt.event.ActionListener listener) {
        JMenuItem item = new JMenuItem(name);
        item.addActionListener(listener);
        return item;
    }

    private Path activeFile() {
        return switch (tabs.getSelectedIndex()) {
            case 1 -> debuggerTab.getProgramFile();
            case 2 -> displayPanel.getSelectedFile();
            default -> editorTab.getCurrentPath();
        };
    }

    private void runActiveProgram() {
        switch (tabs.getSelectedIndex()) {
            case 1 -> { if (debuggerTab.getCpu().isPaused()) debuggerTab.continueExecution(); else debuggerTab.run(); }
            case 2 -> displayPanel.performRun();
            default -> editorTab.runCurrentFile();
        }
    }

    private void assembleActiveFile() {
        if (tabs.getSelectedIndex() == 0) { editorTab.assembleCurrentFile(); return; }
        Path file = activeFile();
        if (file == null) return;
        String name=file.toString().toLowerCase(java.util.Locale.ROOT);
        if(!name.endsWith(".asm")&&!name.endsWith(".c"))return;
        ConsolePanel target=tabs.getSelectedIndex()==1?debuggerTab.getConsole():displayPanel.getConsole();
        long generation = ++assemblyGeneration;
        assemblyGenerations.put(target, generation);
        target.setBuildStatus((name.endsWith(".c") ? "Compiling " : "Assembling ") + file.getFileName());
        new SwingWorker<Path,Void>() {
            protected Path doInBackground() throws Exception {
                return ProgramRunner.assembleToBinary(file, null, status -> publishAssemblyStatus(generation, target, status));
            }
            protected void done() {
                if (!ownsAssemblyStatus(generation, target)) return;
                target.setBuildStatus(null);
                try { get(); }
                catch (Exception error) { target.appendError("Build error: " + VulcanDialog.errorSummary(error) + "\n"); }
            }
        }.execute();
    }

    private void publishAssemblyStatus(long generation, ConsolePanel target, String status) {
        Runnable update = () -> { if (ownsAssemblyStatus(generation, target)) target.setBuildStatus(status); };
        if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update);
    }

    private boolean ownsAssemblyStatus(long generation, ConsolePanel target) {
        return assemblyGenerations.getOrDefault(target, -1L) == generation;
    }

    private void showDebuggerView(String name) {
        Path file = activeFile();
        if (isDebuggerFile(file)) {
            if (!debuggerTab.selectFileIfAllowed(file)) return;
            settings.setCurrentFile(file);
        }
        tabs.setSelectedIndex(1);
        debuggerTab.getViews().set(name, true);
    }

    /** Saves all editor buffers before a debugger load while preserving the current editor selection. */
    static boolean saveAllBeforeDebuggerLoad(EditorTab editors, SettingsManager settings) {
        return saveAllBeforeDebuggerLoad(settings, editors::saveAllAndReportSuccess);
    }

    /** Runs a save callback and restores the editor selection if saving changes it. */
    static boolean saveAllBeforeDebuggerLoad(SettingsManager settings, BooleanSupplier saveAll) {
        Path previousFile = settings.getCurrentFile();
        boolean saved = false;
        try {
            saved = saveAll.getAsBoolean();
        } finally {
            if (!java.util.Objects.equals(previousFile, settings.getCurrentFile())) settings.setCurrentFile(previousFile);
        }
        return saved;
    }

    private void openDocument(String name) {
        try {
            Path document = ProjectDocuments.find(name);
            tabs.setSelectedIndex(0);
            editorTab.openPath(document);
        } catch (Exception error) {
            VulcanDialog.message(this, settings, "Documentation", VulcanDialog.errorSummary(error));
        }
    }

    private void openSettingsFile() {
        try {
            Path path = settings.getPath();
            if (!Files.exists(path)) settings.save();
            tabs.setSelectedIndex(0);
            editorTab.openPathFromSettings(path);
        } catch (Exception error) {
            VulcanDialog.message(this, settings, "Settings", VulcanDialog.errorSummary(error));
        }
    }

    /** Tools > Theme: one radio item per built-in theme; choosing one recolors the whole window and saves it. */
    private JMenu buildThemeMenu() {
        JMenu menu = new JMenu("Theme");
        ButtonGroup group = new ButtonGroup();
        for (String name : Themes.names()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(name, Themes.swatch(name), name.equals(settings.getTheme()));
            item.addActionListener(e -> { settings.applyTheme(name); applyTheme(); });
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    private void applyTheme() {
        Theme.configureUI(settings);
        SwingUtilities.updateComponentTreeUI(this);
        Theme.apply(this, settings);
        titleLabel.setFont(new Font(settings.getApplicationFont(), Font.BOLD, settings.getFontSize("applicationTitle")));
        editorTab.refreshTheme();
        debuggerTab.refreshTheme();
        displayPanel.refreshTheme();
        getContentPane().setBackground(settings.getColor("applicationBackground"));
        repaint();
    }

    /** Starts Vulcan; equivalent to Vulcan.main. */
    public static void main(String[] args) {
        Vulcan.configureRenderingDefaults();
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
