package VulcanIDE;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Project file tree with create, rename, move, delete, undo/redo, and drag and drop. */
public class FileSystemPanel extends JPanel {
    private final SettingsManager settings;
    private final JTree tree;
    private final DefaultTreeModel model;
    private final DefaultMutableTreeNode rootNode;
    private final Consumer<Path> onOpen;
    private final LocalFileRenderer renderer;
    private final JLabel pathLabel;
    private final JLabel title;
    private final String fontKey;
    private Consumer<Path> beforeDeletionListener = path -> {};
    public void setBeforeDeletionListener(Consumer<Path> listener) { beforeDeletionListener=listener==null?path->{}:listener; }
    private final String directoryKey;
    private final JButton backButton;
    private final Clipboard clipboardOverride;
    private final ProjectFileOperations.History fileHistory;
    private java.util.function.BiConsumer<Path, Path> moveListener = (from, to) -> {};
    private java.util.function.Predicate<Path> beforeMoveListener = path -> true;
    private static final DataFlavor FILE_OPERATION = new DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=java.lang.Object", "Vulcan file operation");
    private Consumer<Path> directoryListener = path -> {};
    private Consumer<Path> deletionListener = path -> {};
    private java.util.function.Predicate<Path> errorStatusProvider = path -> false;
    private Path projectDirectory;
    private final java.util.Set<Path> cutPaths = new java.util.HashSet<>();
    private Timer folderClickTimer;

    /** Creates a file tree that opens files through onOpen. */
    public FileSystemPanel(SettingsManager settings, Consumer<Path> onOpen) {
        this(settings, onOpen, "editorFilePanel", "editor");
    }

    /** Creates a file tree whose font size is stored under fontKey. */
    public FileSystemPanel(SettingsManager settings, Consumer<Path> onOpen, String fontKey) {
        this(settings, onOpen, fontKey, directoryKeyFor(fontKey));
    }

    /** Creates a file tree whose project folder is stored under directoryKey. */
    public FileSystemPanel(SettingsManager settings, Consumer<Path> onOpen, String fontKey, String directoryKey) {
        this(settings, onOpen, fontKey, directoryKey, null);
    }

    FileSystemPanel(SettingsManager settings, Consumer<Path> onOpen, String fontKey, Clipboard clipboard) {
        this(settings, onOpen, fontKey, directoryKeyFor(fontKey), clipboard);
    }

    private FileSystemPanel(SettingsManager settings, Consumer<Path> onOpen, String fontKey, String directoryKey, Clipboard clipboard) {
        this.clipboardOverride = clipboard;
        this.settings = settings;
        fileHistory = new ProjectFileOperations.History(settings.getPath().getParent().resolve("file-undo-history"));
        this.onOpen = onOpen == null ? path -> {} : onOpen;
        this.fontKey = fontKey == null ? "editorFilePanel" : fontKey;
        this.directoryKey = directoryKey == null ? "editor" : directoryKey;
        setLayout(new BorderLayout());
        setMinimumSize(new Dimension(0, 0));
        setBorder(null);
        setBackground(settings.getColor("panelBackground"));

        title = new JLabel("Files");
        title.setFont(new Font(settings.getApplicationFont(), Font.BOLD, settings.getFontSize(this.fontKey + "Title")));
        title.setForeground(settings.getColor("text"));
        pathLabel = new JLabel("");
        pathLabel.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize(this.fontKey + "Path")));
        pathLabel.setForeground(settings.getColor("tabTextMuted"));
        pathLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 1, 0));

        JPanel labels = new JPanel();
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        labels.setOpaque(false);
        labels.add(title);
        labels.add(pathLabel);

        backButton = new JButton(Icons.vector("back", 16, settings.getColor("text")));
        backButton.setToolTipText("Back");
        backButton.setPreferredSize(new Dimension(30, 25));
        backButton.setFocusPainted(false);
        backButton.addActionListener(event -> navigateBack());
        JButton browse = new JButton("...");
        browse.setToolTipText("Choose project folder in File Explorer");
        browse.setPreferredSize(new Dimension(30, 25));
        browse.setFocusPainted(false);
        JButton explorer = new JButton("↗");
        explorer.setToolTipText("Open folder in File Explorer");
        explorer.setPreferredSize(new Dimension(30, 25));
        explorer.setFocusPainted(false);
        JButton copy = new JButton(Icons.fit("ctc-icon.png", 16, 16));
        copy.setToolTipText("Copy project path");
        copy.setPreferredSize(new Dimension(30, 25));
        copy.setFocusPainted(false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        buttons.setOpaque(false);
        buttons.add(backButton);
        buttons.add(copy);
        buttons.add(browse);
        buttons.add(explorer);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 9, 6, 6));
        top.setBackground(settings.getColor("panelBackground"));
        top.add(labels, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        rootNode = new DefaultMutableTreeNode();
        model = new DefaultTreeModel(rootNode);
        tree = new JTree(model) {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                if (getParent() instanceof JViewport viewport && getRowCount() > 0) {
                    int extent = viewport.getExtentSize().height;
                    size.height = Math.max(size.height, size.height + Math.max(0, extent - getRowHeight()));
                }
                return size;
            }
        };
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(Math.max(18, settings.getFontSize(this.fontKey) + 9));
        tree.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize(this.fontKey)));
        tree.setBorder(null);
        tree.setOpaque(true);
        renderer = new LocalFileRenderer(settings, this.fontKey);
        tree.setCellRenderer(renderer);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        installFileActions();
        tree.setTransferHandler(new FileDropHandler());
        tree.setDropMode(DropMode.ON);
        tree.setToggleClickCount(0);
        if (!GraphicsEnvironment.isHeadless()) tree.setDragEnabled(true);
        tree.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent event) {
                if (!event.isTemporary()) tree.clearSelection();
            }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(null);
        SmoothScroll.install(scroll);
        scroll.getViewport().setBackground(settings.getColor("inputBackground"));
        Theme.styleScrollBar(scroll.getVerticalScrollBar(), settings);
        Theme.styleScrollBar(scroll.getHorizontalScrollBar(), settings);
        add(scroll, BorderLayout.CENTER);

        projectDirectory = settings.getProjectDirectory(this.directoryKey);
        if (!Files.isDirectory(projectDirectory)) projectDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        setProjectDirectory(projectDirectory, false);

        browse.addActionListener(e -> chooseProjectFolder());
        explorer.addActionListener(e -> openInExplorer());
        copy.addActionListener(e -> copyPath());
        tree.addMouseWheelListener(this::handleZoom);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                loadChildren(node);
            }
            @Override public void treeWillCollapse(TreeExpansionEvent event) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                if (node.getChildCount() == 0 && node.getUserObject() instanceof File f && isTreeDirectory(f)) node.add(new DefaultMutableTreeNode());
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() == 4) return;
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                DefaultMutableTreeNode node = nodeAt(e);
                if (node == null || !(node.getUserObject() instanceof File file)) { tree.clearSelection(); return; }
                TreePath path = new TreePath(node.getPath());
                if (isTreeDirectory(file)) {
                    if (folderClickTimer != null) folderClickTimer.stop();
                    if (e.getClickCount() >= 2) {
                        setProjectDirectory(file.toPath(), true);
                    } else {
                        Object delay = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
                        folderClickTimer = new Timer(delay instanceof Integer ms ? ms : 350, event -> {
                            if (tree.isExpanded(path)) tree.collapsePath(path); else tree.expandPath(path);
                        });
                        folderClickTimer.setRepeats(false);
                        folderClickTimer.start();
                    }
                } else if (e.getClickCount() >= 2) onOpen.accept(file.toPath());
            }
            @Override public void mousePressed(MouseEvent e) {

                if (folderClickTimer != null) folderClickTimer.stop();
                if (e.isPopupTrigger()) showPopup(e);
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showPopup(e); }
        });
        installMouseBack(this);
    }

    private void navigateBack() {
        Path parent = projectDirectory == null ? null : projectDirectory.getParent();
        if (parent != null && Files.isDirectory(parent)) setProjectDirectory(parent, true);
    }

    private void installMouseBack(Component component) {
        component.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (event.getButton() == 4 && !event.isConsumed()) { event.consume(); navigateBack(); }
            }
        });
        if (component instanceof Container container)
            for (Component child : container.getComponents()) installMouseBack(child);
    }

    private void installFileActions() {
        bindTreeAction("control C", "copy-files", () -> copySelectedFiles(false));
        bindTreeAction("control X", "cut-files", () -> copySelectedFiles(true));
        bindTreeAction("control V", "paste-files", () -> pasteClipboard(selectedDirectory()));
        bindTreeAction("control Z", "undo-files", this::undoFileOperation);
        bindTreeAction("control shift Z", "redo-files", this::redoFileOperation);
        bindTreeAction("control N", "new-file", () -> createFile(selectedDirectory()));
        bindTreeAction("alt N", "new-folder", () -> createFolder(selectedDirectory()));
        bindTreeAction("F2", "rename-file", this::renameSelected);
        bindTreeAction("control D", "duplicate-files", this::duplicateSelected);
        bindTreeAction("DELETE", "delete-files", this::deleteSelected);
        bindTreeAction("control DELETE", "permanent-delete-files", this::permanentlyDeleteSelected);
        bindTreeAction("control alt R", "reveal-files", this::openInExplorer);
        bindTreeAction("control shift ENTER", "open-default-files", this::openInDefaultApp);
        bindTreeAction("alt shift C", "copy-file-path", this::copyPath);
        bindTreeAction("control shift C", "copy-relative-file-path", this::copyRelativePath);
        bindTreeAction("ESCAPE", "cancel-files", () -> {
            if (folderClickTimer != null) folderClickTimer.stop();
            MenuSelectionManager.defaultManager().clearSelectedPath();
            cutPaths.clear(); tree.clearSelection(); tree.repaint();
        });
    }

    private void bindTreeAction(String key, String name, Runnable action) {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        Action binding = new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { action.run(); }
        };
        tree.getActionMap().put(name, binding);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, binding);
    }

    private Clipboard clipboard() { return clipboardOverride == null ? Toolkit.getDefaultToolkit().getSystemClipboard() : clipboardOverride; }

    public void setMoveListener(java.util.function.BiConsumer<Path, Path> listener) { moveListener = listener == null ? (from, to) -> {} : listener; }
    public void setBeforeMoveListener(java.util.function.Predicate<Path> listener) { beforeMoveListener = listener == null ? path -> true : listener; }

    public void setDirectoryListener(Consumer<Path> listener) { directoryListener = listener == null ? path -> {} : listener; }

    public void setDeletionListener(Consumer<Path> listener) { deletionListener = listener == null ? path -> {} : listener; }

    void setErrorStatusProvider(java.util.function.Predicate<Path> provider) {
        errorStatusProvider = provider == null ? path -> false : provider;
        tree.repaint();
    }

    List<Rectangle> errorDividerSegments(Component splitPane) {
        List<Rectangle> segments = new java.util.ArrayList<>();
        Rectangle visible = tree.getVisibleRect();
        int firstRow = Math.max(0, tree.getClosestRowForLocation(0, visible.y));
        int lastRow = Math.min(tree.getRowCount() - 1,
                tree.getClosestRowForLocation(0, Math.max(visible.y, visible.y + visible.height - 1)));
        for (int row = firstRow; row <= lastRow; row++) {
            TreePath path = tree.getPathForRow(row);
            Rectangle bounds = path == null ? null : tree.getPathBounds(path);
            if (bounds == null) continue;
            Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (!(value instanceof File file)) continue;
            Path filePath = file.toPath().toAbsolutePath().normalize();
            if (!errorStatusProvider.test(filePath)) continue;
            segments.add(SwingUtilities.convertRectangle(tree, bounds, splitPane));
        }
        return segments;
    }

    /** Lets the user pick the project folder. */
    public void chooseProjectFolder() {
        Path chosen = chooseFolder(projectDirectory);
        if (chosen != null) setProjectDirectory(chosen, true);
    }

    private Path chooseFolder(Path initial) {
        JFileChooser chooser = new JFileChooser(initial == null ? new File(System.getProperty("user.dir")) : initial.toFile());
        chooser.setDialogTitle("Select Vulcan project folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile().toPath().toAbsolutePath().normalize() : null;
    }

    /** Expands the tree to reveal and select a file. */
    public void openIfNeeded(Path path) {
        if (path == null) return;
        Path target = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) target = target.getParent();
        if (target == null || projectDirectory == null || !target.startsWith(projectDirectory)) return;
        expandTo(target);
    }

    private void setProjectDirectory(Path directory, boolean notify) {
        try {
            Path absolute = directory.toAbsolutePath().normalize();
            if (!Files.isDirectory(absolute)) return;
            if (folderClickTimer != null) folderClickTimer.stop();
            projectDirectory = absolute;
            backButton.setEnabled(projectDirectory.getParent() != null);
            settings.setProjectDirectory(directoryKey, projectDirectory);
            pathLabel.setText(projectDirectory.toString());
            pathLabel.setToolTipText(projectDirectory.toString());
            rootNode.setUserObject(projectDirectory.toFile());
            loadChildren(rootNode);
            model.reload();
            TreePath root = new TreePath(rootNode.getPath());
            tree.expandPath(root);
            if (notify) directoryListener.accept(projectDirectory);
        } catch (Exception error) {
            VulcanDialog.message(this, settings, "Open Project Folder", VulcanDialog.errorSummary(error));
        }
    }

    private static String directoryKeyFor(String fontKey) {
        if (fontKey != null && fontKey.startsWith("debugger")) return "debugger";
        if (fontKey != null && fontKey.startsWith("display")) return "display";
        return "editor";
    }

    private void expandTo(Path target) {
        try {
            if (target.equals(projectDirectory)) return;
            Path relative = projectDirectory.relativize(target);
            DefaultMutableTreeNode node = rootNode;
            Path current = projectDirectory;
            for (Path part : relative) {
                current = current.resolve(part);
                if (node.getChildCount() == 1 && ((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject() == null) loadChildren(node);
                node = findChild(node, current);
                if (node == null) return;
                tree.expandPath(new TreePath(node.getPath()));
            }
        } catch (Exception ignored) { }
    }

    private DefaultMutableTreeNode findChild(DefaultMutableTreeNode parent, Path path) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (child.getUserObject() instanceof File file && file.toPath().toAbsolutePath().normalize().equals(path)) return child;
        }
        return null;
    }

    private void loadChildren(DefaultMutableTreeNode node) {
        Object value = node.getUserObject();
        if (!(value instanceof File directory) || !isTreeDirectory(directory)) return;
        node.removeAllChildren();
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::isFile).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            DefaultMutableTreeNode child = new DefaultMutableTreeNode(file);
            if (isTreeDirectory(file)) child.add(new DefaultMutableTreeNode());
            node.add(child);
        }
        if (files.length == 0) node.add(new DefaultMutableTreeNode());
        model.nodeStructureChanged(node);
    }

    /** Reloads the tree while keeping expanded folders. */
    public void refresh() {
        if (projectDirectory == null) return;
        if (folderClickTimer != null) folderClickTimer.stop();
        List<Path> expanded = new java.util.ArrayList<>();
        java.util.Enumeration<TreePath> paths = tree.getExpandedDescendants(new TreePath(rootNode.getPath()));
        if (paths != null) while (paths.hasMoreElements()) {
            Object value = ((DefaultMutableTreeNode) paths.nextElement().getLastPathComponent()).getUserObject();
            if (value instanceof File file) expanded.add(file.toPath().toAbsolutePath().normalize());
        }
        List<Path> selection = selectedPaths();
        loadChildren(rootNode);
        model.reload(rootNode);
        expanded.sort(Comparator.comparingInt(Path::getNameCount));
        for (Path path : expanded) {
            if (path.equals(projectDirectory)) tree.expandPath(new TreePath(rootNode.getPath()));
            else expandTo(path);
        }
        if (!selection.isEmpty()) selectEntries(selection);
    }

    private void handleZoom(MouseWheelEvent e) {
        if (!e.isControlDown()) {
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, tree);
            if (scroll != null) {
                Point point = SwingUtilities.convertPoint(tree, e.getPoint(), scroll);
                scroll.dispatchEvent(new MouseWheelEvent(scroll, e.getID(), e.getWhen(), e.getModifiersEx(),
                        point.x, point.y, e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), e.isPopupTrigger(),
                        e.getScrollType(), e.getScrollAmount(), e.getWheelRotation(), e.getPreciseWheelRotation()));
                e.consume();
            }
            return;
        }
        e.consume();
        int size = Math.max(8, Math.min(48, renderer.getFontSize() - e.getWheelRotation()));
        if (size != renderer.getFontSize()) {
            renderer.setFontSize(size);
            settings.setFontSize(fontKey, size);
            tree.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, size));
            tree.setRowHeight(Math.max(18, size + 9));
            tree.revalidate();
            tree.repaint();
        }
    }

    private DefaultMutableTreeNode nodeAt(MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        return path == null ? null : (DefaultMutableTreeNode) path.getLastPathComponent();
    }

    private Path selectedDirectory() {
        TreePath selected = tree.getSelectionPath();
        if (selected != null) {
            Object value = ((DefaultMutableTreeNode) selected.getLastPathComponent()).getUserObject();
            if (value instanceof File f && isTreeDirectory(f)) return f.toPath();
            if (value instanceof File f) return f.toPath().getParent();
        }
        return projectDirectory;
    }

    private void showPopup(MouseEvent e) {
        DefaultMutableTreeNode node = nodeAt(e);
        if (node != null) {
            TreePath clickedPath = new TreePath(node.getPath());
            if (!tree.isPathSelected(clickedPath)) tree.setSelectionPath(clickedPath);
        } else tree.clearSelection();
        createContextMenu().show(tree, e.getX(), e.getY());
    }

    JPopupMenu createContextMenu() {
        Path selected = tree.getSelectionPath() == null ? null : selectedPath();
        Path directory = selected == null ? projectDirectory : isTreeDirectory(selected.toFile()) ? selected : selected.getParent();
        List<Path> items = selectedPaths();
        boolean oneSelected = items.size() == 1;
        boolean hasSelection = !items.isEmpty();
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(settings.getColor("panelBackground"));
        menu.setForeground(settings.getColor("text"));
        menu.setBorder(BorderFactory.createLineBorder(settings.getColor("splitDivider")));

        JMenuItem newFile = menuItem("New File", "control N", () -> createFile(directory));
        JMenuItem newFolder = menuItem("New Folder", "alt N", () -> createFolder(directory));
        JMenuItem reveal = menuItem("Reveal in File Explorer", "control alt R", this::openInExplorer);
        JMenuItem open = menuItem("Open in Default App", "control shift ENTER", this::openInDefaultApp);
        JMenuItem terminal = menuItem("Open in Terminal", null, this::openInTerminal);
        JMenuItem cut = menuItem("Cut", "control X", () -> copySelectedFiles(true));
        JMenuItem copy = menuItem("Copy", "control C", () -> copySelectedFiles(false));
        JMenuItem duplicate = menuItem("Duplicate", "control D", this::duplicateSelected);
        JMenuItem paste = menuItem("Paste", "control V", () -> pasteClipboard(directory));
        JMenuItem undo = menuItem("Undo", "control Z", this::undoFileOperation);
        JMenuItem redo = menuItem("Redo", "control shift Z", this::redoFileOperation);
        JMenuItem copyPath = menuItem("Copy Path", "alt shift C", this::copyPath);
        JMenuItem copyRelativePath = menuItem("Copy Relative Path", "control shift C", this::copyRelativePath);
        JMenuItem rename = menuItem("Rename", "F2", this::renameSelected);
        JMenuItem delete = menuItem("Delete", "control DELETE", this::permanentlyDeleteSelected);
        JMenuItem expand = menuItem("Expand All", null, this::expandAll);
        JMenuItem collapse = menuItem("Collapse All", null, this::collapseAll);

        newFile.setEnabled(directory != null);
        newFolder.setEnabled(directory != null);
        reveal.setEnabled(selected == null || oneSelected);
        open.setEnabled(oneSelected && Files.exists(items.get(0)));
        terminal.setEnabled(directory != null);
        cut.setEnabled(hasSelection);
        copy.setEnabled(hasSelection);
        duplicate.setEnabled(hasSelection);
        paste.setEnabled(directory != null && clipboardHasFiles());
        undo.setEnabled(!fileHistory.isEmpty());
        redo.setEnabled(fileHistory.canRedo());
        copyPath.setEnabled(oneSelected);
        copyRelativePath.setEnabled(oneSelected);
        rename.setEnabled(oneSelected);
        delete.setEnabled(hasSelection);

        menu.add(newFile);
        menu.add(newFolder);
        menu.addSeparator();
        menu.add(reveal);
        menu.add(open);
        menu.add(terminal);
        menu.addSeparator();
        menu.add(cut);
        menu.add(copy);
        menu.add(duplicate);
        menu.add(paste);
        menu.add(undo);
        menu.add(redo);
        menu.addSeparator();
        menu.add(copyPath);
        menu.add(copyRelativePath);
        menu.add(rename);
        menu.addSeparator();
        menu.add(delete);
        menu.addSeparator();
        menu.add(expand);
        menu.add(collapse);
        return menu;
    }

    private JMenuItem menuItem(String label, String shortcut, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        if (shortcut != null) item.setAccelerator(KeyStroke.getKeyStroke(shortcut));
        item.addActionListener(event -> action.run());
        return item;
    }

    private boolean clipboardHasFiles() {
        try {
            Transferable contents = clipboard().getContents(null);
            return contents != null && contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        } catch (Exception ignored) { return false; }
    }

    private void expandAll() {
        for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
    }

    private void collapseAll() {
        for (int row = tree.getRowCount() - 1; row >= 0; row--) tree.collapseRow(row);
    }

    private void createFile(Path dir) {
        String name = VulcanDialog.prompt(this, settings, "Create File", "File name:", "");
        if (name == null || name.isBlank()) return;
        try {
            Path path = createChildEntry(dir, name, false);
            fileHistory.record(List.of(ProjectFileOperations.Change.created(path)));
            refresh();
            onOpen.accept(path);
            MainWindow.notifyFileChange(this,"Created " + path.getFileName());
        } catch (Exception ex) { VulcanDialog.message(this, settings, "Create File", VulcanDialog.errorSummary(ex)); }
    }

    private void createFolder(Path dir) {
        String name = VulcanDialog.prompt(this, settings, "Create Folder", "Folder name:", "");
        if (name == null || name.isBlank()) return;
        try { Path path = createChildEntry(dir, name, true); fileHistory.record(List.of(ProjectFileOperations.Change.created(path))); refresh(); MainWindow.notifyFileChange(this,"Created " + path.getFileName()); } catch (Exception ex) { VulcanDialog.message(this, settings, "Create Folder", VulcanDialog.errorSummary(ex)); }
    }

    static Path createChildEntry(Path directory, String name, boolean folder) throws IOException {
        if (directory == null || name == null || name.isBlank()) throw new IllegalArgumentException("Enter a name.");
        String fileName = name.strip();
        if (fileName.equals(".") || fileName.equals("..") || fileName.endsWith(".") || fileName.endsWith(" ")
                || fileName.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("Enter a valid name without folder separators or reserved characters.");
        }
        String deviceName = fileName.substring(0, fileName.indexOf('.') < 0 ? fileName.length() : fileName.indexOf('.')).strip();
        if (deviceName.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
            throw new IllegalArgumentException("That name is reserved by Windows.");
        Path supplied;
        try { supplied = Path.of(fileName); }
        catch (java.nio.file.InvalidPathException invalid) { throw new IllegalArgumentException("Enter a valid name.", invalid); }
        if (supplied.isAbsolute() || supplied.getRoot() != null || supplied.getNameCount() != 1 || supplied.getParent() != null)
            throw new IllegalArgumentException("Enter a name, not a path.");
        Path parent = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(parent)) throw new IOException("The target folder does not exist.");
        Path target = parent.resolve(supplied).normalize();
        if (!target.startsWith(parent) || !parent.equals(target.getParent()))
            throw new IllegalArgumentException("The new entry must stay inside the selected folder.");
        if (folder) return Files.createDirectory(target);
        Path created = Files.createFile(target);
        BuildRun.created(created);
        return created;
    }

    private void renameSelected() {
        List<Path> selection = selectedPaths();
        if (selection.size() != 1) return;
        Path selected = selection.get(0);
        String name = VulcanDialog.prompt(this, settings, "Rename", "New name:", selected.getFileName().toString());
        if (name == null || name.isBlank()) return;
        try {
            Path destination = selected.resolveSibling(name.trim());
            if (selected.equals(destination)) return;
            if (!beforeMoveListener.test(selected)) throw new IOException("An open file could not be saved before renaming it.");
            ProjectFileOperations.moveNew(selected, destination);
            fileHistory.record(List.of(ProjectFileOperations.Change.moved(selected, destination)));
            moveListener.accept(selected, destination);
            refresh();
        } catch (Exception ex) { VulcanDialog.message(this, settings, "Rename", VulcanDialog.errorSummary(ex)); }
    }

    private void pasteClipboard(Path directory) {
        try {
            Transferable contents = clipboard().getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return;
            Object metadata = contents.isDataFlavorSupported(FILE_OPERATION) ? contents.getTransferData(FILE_OPERATION) : null;
            ClipboardOperation operation = metadata instanceof ClipboardOperation value ? value : null;
            if (operation != null && operation.cut && operation.consumed) return;
            Object data = contents.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(data instanceof List<?> list)) return;
            List<Path> sources = new java.util.ArrayList<>();
            for (Object item : list) if (item instanceof File file) sources.add(file.toPath().toAbsolutePath().normalize());
            boolean move = operation != null && operation.cut;
            if (transferEntries(sources, directory, move, false) && move) { operation.consumed = true; cutPaths.clear(); tree.repaint(); }
        } catch (Exception ex) { VulcanDialog.message(this, settings, "Paste", VulcanDialog.errorSummary(ex)); }
    }

    private boolean transferEntries(List<Path> sources, Path directory, boolean move, boolean duplicate) {
        List<ProjectFileOperations.Change> changes = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();
        List<Path> destinations = new java.util.ArrayList<>();
        for (Path entry : sources) {
            Path source = entry.toAbsolutePath().normalize();
            Path folder = duplicate ? source.getParent() : directory.toAbsolutePath().normalize();
            if (move && folder.resolve(source.getFileName()).equals(source)) continue;
            try {
                if (folder.startsWith(source)) throw new IOException("A folder cannot be pasted into itself.");
                Path destination = ProjectFileOperations.uniqueTarget(source, folder, duplicate);
                if (move) {
                    if (!beforeMoveListener.test(source)) throw new IOException("An open file could not be saved before moving it.");
                    ProjectFileOperations.moveNew(source, destination);
                    changes.add(ProjectFileOperations.Change.moved(source, destination));
                    moveListener.accept(source, destination);
                } else {
                    ProjectFileOperations.copyNew(source, destination);
                    changes.add(ProjectFileOperations.Change.created(destination));
                }
                destinations.add(destination);
            } catch (IOException error) { errors.add(source.getFileName() + ": " + VulcanDialog.errorSummary(error)); }
        }
        fileHistory.record(changes);
        refresh();
        selectEntries(destinations);
        if (!errors.isEmpty()) VulcanDialog.message(this, settings, move ? "Move" : "Copy", String.join("\n", errors));
        return errors.isEmpty();
    }

    private void duplicateSelected() { transferEntries(selectedPaths(), projectDirectory, false, true); }

    private void undoFileOperation() {
        try {
            List<ProjectFileOperations.Change> undone = fileHistory.undo(change -> {
                if (change.original() == null) beforeDeletionListener.accept(change.destination());
                else if (!beforeMoveListener.test(change.destination())) throw new IOException("An open file could not be saved before undoing its move.");
            }, change -> {
                if (change.original() != null) moveListener.accept(change.destination(), change.original());
                else {
                    deletionListener.accept(change.destination());
                }
            });
            refresh();
            selectEntries(undone.stream().map(ProjectFileOperations.Change::original).filter(java.util.Objects::nonNull).toList());
        } catch (IOException error) { VulcanDialog.message(this, settings, "Undo", VulcanDialog.errorSummary(error)); }
    }

    private void redoFileOperation() {
        try {
            List<ProjectFileOperations.Change> redone = fileHistory.redo(change -> {
                Path from = change.original();
                Path to = change.destination();
                if (from.startsWith(projectDirectory) && !to.startsWith(projectDirectory)) beforeDeletionListener.accept(from);
                else if (!beforeMoveListener.test(from)) throw new IOException("An open file could not be saved before redoing its operation.");
            }, change -> {
                Path from = change.original();
                Path to = change.destination();
                if (from.startsWith(projectDirectory) && !to.startsWith(projectDirectory)) deletionListener.accept(from);
                else moveListener.accept(from, to);
            });
            refresh();
            selectEntries(redone.stream().map(ProjectFileOperations.Change::destination).toList());
        } catch (IOException error) { VulcanDialog.message(this, settings, "Redo", VulcanDialog.errorSummary(error)); }
    }

    private void selectEntries(List<Path> paths) {
        List<TreePath> selection = new java.util.ArrayList<>();
        tree.expandPath(new TreePath(rootNode.getPath()));
        for (Path path : paths) {
            if (!path.startsWith(projectDirectory)) continue;
            expandTo(path.getParent());
            for (int row = 0; row < tree.getRowCount(); row++) {
                TreePath candidate = tree.getPathForRow(row);
                Object value = ((DefaultMutableTreeNode) candidate.getLastPathComponent()).getUserObject();
                if (value instanceof File file && file.toPath().toAbsolutePath().normalize().equals(path)) selection.add(candidate);
            }
        }
        tree.setSelectionPaths(selection.toArray(TreePath[]::new));
    }

    private void deleteSelected() {
        List<Path> selected = selectedPaths();
        if (selected.isEmpty()) return;
        String description = selected.size() == 1 ? "Delete " + selected.get(0).getFileName() + "? You can undo this with Undo."
                : "Delete these " + selected.size() + " items? You can undo this with Undo.";
        if (!VulcanDialog.confirm(this, settings, "Delete", description)) return;
        List<String> failures = new java.util.ArrayList<>();
        List<ProjectFileOperations.Change> changes = new java.util.ArrayList<>();
        Path backup;
        try {
            Path storage = settings.getPath().getParent().resolve("file-undo");
            Files.createDirectories(storage);
            backup = Files.createTempDirectory(storage, "deleted-");
        } catch (IOException error) { VulcanDialog.message(this, settings, "Delete", VulcanDialog.errorSummary(error)); return; }
        for (Path path : selected) {
            try {
                beforeDeletionListener.accept(path.toAbsolutePath().normalize());
                Path stored = backup.resolve(changes.size() + "-" + path.getFileName());
                ProjectFileOperations.moveNew(path, stored);
                changes.add(ProjectFileOperations.Change.moved(path, stored));
                deletionListener.accept(path.toAbsolutePath().normalize());
            } catch (Exception ex) {
                failures.add(path.getFileName() + ": " + VulcanDialog.errorSummary(ex));
            }
        }
        fileHistory.record(changes);
        refresh();
        if (!failures.isEmpty()) VulcanDialog.message(this, settings, "Delete", String.join("\n", failures));
    }

    private void permanentlyDeleteSelected() {
        List<Path> selected = selectedPaths();
        if (selected.isEmpty()) return;
        String target = selected.size() == 1 ? selected.get(0).getFileName().toString() : selected.size() + " items";
        if (!VulcanDialog.confirm(this, settings, "Delete Permanently", "Permanently delete " + target + "? This cannot be undone.")) return;
        List<String> failures = new java.util.ArrayList<>();
        for (Path path : selected) {
            try {
                beforeDeletionListener.accept(path);
                ProjectFileOperations.deleteRecursively(path);
                deletionListener.accept(path);
            } catch (Exception error) {
                failures.add(path.getFileName() + ": " + VulcanDialog.errorSummary(error));
            }
        }
        refresh();
        if (!failures.isEmpty()) VulcanDialog.message(this, settings, "Delete Permanently", String.join("\n", failures));
    }

    private void copySelectedFiles(boolean cut) {
        List<Path> selected = selectedPaths();
        if (selected.isEmpty()) return;
        List<File> files = selected.stream().map(Path::toFile).toList();
        try {
            clipboard().setContents(new FileListTransferable(files, new ClipboardOperation(cut)), null);
            cutPaths.clear();
            if (cut) cutPaths.addAll(selected);
            tree.repaint();
        } catch (Exception error) {
            VulcanDialog.message(this, settings, cut ? "Cut" : "Copy", VulcanDialog.errorSummary(error));
        }
    }

    private List<Path> selectedPaths() {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null || paths.length == 0) return List.of();
        List<Path> candidates = new java.util.ArrayList<>();
        for (TreePath treePath : paths) {
            Object value = ((DefaultMutableTreeNode) treePath.getLastPathComponent()).getUserObject();
            if (!(value instanceof File file)) continue;
            Path path = file.toPath().toAbsolutePath().normalize();
            if (!path.equals(projectDirectory) && path.startsWith(projectDirectory) && !candidates.contains(path)) candidates.add(path);
        }
        candidates.sort(Comparator.comparingInt(Path::getNameCount));
        List<Path> roots = new java.util.ArrayList<>();
        for (Path candidate : candidates) {
            boolean covered = roots.stream().anyMatch(candidate::startsWith);
            if (!covered) roots.add(candidate);
        }
        return List.copyOf(roots);
    }

    private static final class FileListTransferable implements Transferable {
        private final List<File> files;
        private final String paths;
        private final ClipboardOperation operation;

        private FileListTransferable(List<File> files, ClipboardOperation operation) {
            this.files = List.copyOf(files);
            this.paths = String.join(System.lineSeparator(), files.stream().map(File::getAbsolutePath).toList());
            this.operation = operation;
        }

        @Override public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor, FILE_OPERATION};
        }

        @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
            return flavor.equals(DataFlavor.javaFileListFlavor) || flavor.equals(DataFlavor.stringFlavor) || flavor.equals(FILE_OPERATION);
        }

        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (flavor.equals(DataFlavor.javaFileListFlavor)) return files;
            if (flavor.equals(DataFlavor.stringFlavor)) return paths;
            if (flavor.equals(FILE_OPERATION)) return operation;
            throw new UnsupportedFlavorException(flavor);
        }
    }

    private static final class ClipboardOperation {
        private final boolean cut;
        private boolean consumed;
        private ClipboardOperation(boolean cut) { this.cut = cut; }
    }

    private void copyPath() {
        Path selected = selectedPath();
        if (selected == null || !Files.exists(selected)) return;
        final Path copied = selected;
        try {
            clipboard().setContents(new StringSelection(copied.toString()), null);
        } catch (Exception error) {
            VulcanDialog.message(this, settings, "Copy Path", VulcanDialog.errorSummary(error));
        }
    }

    private void copyRelativePath() {
        Path selected = selectedPath();
        if (selected == null || !selected.startsWith(projectDirectory)) return;
        String relative = projectDirectory.relativize(selected).toString();
        try { clipboard().setContents(new StringSelection(relative.isEmpty() ? "." : relative), null); }
        catch (Exception error) { VulcanDialog.message(this, settings, "Copy Relative Path", VulcanDialog.errorSummary(error)); }
    }

    private Path selectedPath() {
        TreePath selected = tree.getSelectionPath();
        if (selected == null) return projectDirectory;
        Object value = ((DefaultMutableTreeNode) selected.getLastPathComponent()).getUserObject();
        return value instanceof File f ? f.toPath().toAbsolutePath().normalize() : projectDirectory;
    }

    private static boolean isTreeDirectory(File file) {
        Path path = file.toPath();
        return !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private void openInExplorer() {
        Path path = selectedPath();
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("win"))
                new ProcessBuilder("explorer.exe", Files.isDirectory(path) ? path.toString() : "/select," + path).start();
            else if (Desktop.isDesktopSupported())
                Desktop.getDesktop().open(Files.isDirectory(path) ? path.toFile() : path.getParent().toFile());
        } catch (Exception error) { VulcanDialog.message(this, settings, "Reveal in File Explorer", VulcanDialog.errorSummary(error)); }
    }

    private void openInDefaultApp() {
        Path path = selectedPath();
        try {
            if (!Files.exists(path)) throw new IOException("The selected file no longer exists.");
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                throw new IOException("Opening files in the default app is not supported on this system.");
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception error) { VulcanDialog.message(this, settings, "Open in Default App", VulcanDialog.errorSummary(error)); }
    }

    private void openInTerminal() {
        Path selected = selectedPath();
        Path directory = Files.isDirectory(selected) ? selected : selected.getParent();
        if (directory == null || !Files.isDirectory(directory)) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                try { new ProcessBuilder("wt.exe", "-d", directory.toString()).start(); }
                catch (IOException unavailable) {
                    String command = "Set-Location -LiteralPath '" + directory.toString().replace("'", "''") + "'";
                    new ProcessBuilder("powershell.exe", "-NoExit", "-Command", command).directory(directory.toFile()).start();
                }
            } else if (os.contains("mac")) new ProcessBuilder("open", "-a", "Terminal", directory.toString()).start();
            else new ProcessBuilder("x-terminal-emulator", "--working-directory=" + directory).start();
        } catch (IOException error) { VulcanDialog.message(this, settings, "Open in Terminal", VulcanDialog.errorSummary(error)); }
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        setBackground(settings.getColor("panelBackground"));
        title.setForeground(settings.getColor("text"));
        pathLabel.setForeground(settings.getColor("tabTextMuted"));
        title.setFont(new Font(settings.getApplicationFont(), Font.BOLD, settings.getFontSize(fontKey + "Title")));
        pathLabel.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize(fontKey + "Path")));
        tree.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize(fontKey)));
        renderer.setFontSize(settings.getFontSize(fontKey));
        tree.setRowHeight(Math.max(18, settings.getFontSize(fontKey) + 9));
        renderer.updateColors();
        backButton.setIcon(Icons.vector("back", 16, settings.getColor("text")));
        tree.repaint();
        repaint();
    }

    private final class FileDropHandler extends TransferHandler {
        @Override public int getSourceActions(JComponent component) { return MOVE; }
        @Override protected Transferable createTransferable(JComponent component) {
            List<File> files = selectedPaths().stream().map(Path::toFile).toList();
            if (files.isEmpty()) return null;
            JLabel preview = new JLabel(files.size() == 1 ? files.get(0).getName() : files.size() + " files");
            preview.setIcon(Icons.file(files.get(0), 18));
            preview.setSize(preview.getPreferredSize());
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(Math.max(1, preview.getWidth()), Math.max(1, preview.getHeight()), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics(); preview.paint(g); g.dispose();
            setDragImage(image);
            return new FileListTransferable(files, new ClipboardOperation(true));
        }
        @Override public boolean canImport(TransferSupport support) { return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                @SuppressWarnings("unchecked") List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                Path target = selectedDirectory();
                if (support.isDrop() && support.getDropLocation() instanceof JTree.DropLocation location && location.getPath() != null) {
                    Object value = ((DefaultMutableTreeNode) location.getPath().getLastPathComponent()).getUserObject();
                    if (value instanceof File file) target = isTreeDirectory(file) ? file.toPath() : file.toPath().getParent();
                }
                boolean move = support.isDrop() && support.getDropAction() == MOVE;
                return transferEntries(files.stream().map(File::toPath).toList(), target, move, false);
            } catch (Exception error) {
                VulcanDialog.message(FileSystemPanel.this, settings, "Paste", VulcanDialog.errorSummary(error));
                return false;
            }
        }
    }

    public Path getProjectDirectory() { return projectDirectory; }

    private final class LocalFileRenderer extends DefaultTreeCellRenderer {
        private final SettingsManager settings;
        private final String fontKey;
        private int fontSize;
        LocalFileRenderer(SettingsManager settings, String fontKey) {
            this.settings = settings;
            this.fontKey = fontKey;
            this.fontSize = settings.getFontSize(fontKey);
            updateColors();
        }
        int getFontSize() { return fontSize; }
        void setFontSize(int size) { fontSize = size; }
        void updateColors() {
            setBackgroundSelectionColor(settings.getColor("fileSelectionBackground"));
            setTextSelectionColor(settings.getColor("fileSelectionText"));
            setBackgroundNonSelectionColor(settings.getColor("inputBackground"));
            setTextNonSelectionColor(settings.getColor("text"));
            setBorderSelectionColor(settings.getColor("fileSelectionBackground"));
        }
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setFont(new Font(settings.getApplicationFont(), Font.PLAIN, fontSize));
            setIcon(null);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof File file) {
                setText(file.getName());
                if (cutPaths.contains(file.toPath().toAbsolutePath().normalize())) setForeground(settings.getColor("tabTextMuted"));
                int iconSize = Math.max(12, fontSize + 2);
                if (isTreeDirectory(file)) setIcon(Icons.fit(expanded ? "dir-open-icon.png" : "dir-icon.png", iconSize, iconSize));
                else {
                    setIcon(Icons.file(file, iconSize));
                }
            }
            return this;
        }
    }
}
