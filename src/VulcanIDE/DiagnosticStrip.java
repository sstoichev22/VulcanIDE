package VulcanIDE;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Supplier;

/** Logical-line diagnostic overview with delayed source previews; all updates run on the EDT. */
final class DiagnosticStrip extends JPanel {
    private static final Color ERROR = new Color(240, 85, 85);
    private static final int MARK_HEIGHT = 3;
    private final JTextPane editor;
    private final Supplier<Point> pointer;
    private final Timer delay;
    private final Timer exitGrace;
    private final Timer watch;
    private final DocumentListener documentListener;
    private List<CDiagnosticAnalyzer.Problem> problems = List.of();
    private CDiagnosticAnalyzer.Problem target;
    private JWindow popup;
    private Window popupOwner;

    DiagnosticStrip(JTextPane editor) {
        this(editor, () -> {
            PointerInfo information = MouseInfo.getPointerInfo();
            return information == null ? null : information.getLocation();
        });
    }

    DiagnosticStrip(JTextPane editor, Supplier<Point> pointer) {
        this.editor = editor;
        this.pointer = pointer;
        setPreferredSize(new Dimension(12, 0));
        setMinimumSize(new Dimension(12, 0));
        setOpaque(true);
        setBackground(editor.getBackground());
        getAccessibleContext().setAccessibleName("C diagnostics overview");
        delay = new Timer(1000, event -> showPopup());
        delay.setRepeats(false);
        exitGrace = new Timer(280, event -> {
            if (!overTargetOrPopup()) hidePopup();
        });
        exitGrace.setRepeats(false);
        watch = new Timer(80, event -> {
            Window owner = SwingUtilities.getWindowAncestor(this);
            if (!isShowing() || owner != popupOwner || owner == null || !owner.isActive()) hidePopup();
            else if (overTargetOrPopup()) exitGrace.stop();
            else if (!exitGrace.isRunning()) exitGrace.start();
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                CDiagnosticAnalyzer.Problem problem = problemAt(event.getY());
                if (problem == target) {
                    exitGrace.stop();
                } else if (problem == null) {
                    exitGrace.restart();
                } else {
                    hidePopup();
                    target = problem;
                    delay.restart();
                }
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent event) { exitGrace.restart(); }
        });
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & (HierarchyEvent.SHOWING_CHANGED | HierarchyEvent.PARENT_CHANGED)) != 0) hidePopup();
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { hidePopup(); }
            @Override public void componentMoved(ComponentEvent event) { hidePopup(); }
        });
        editor.addPropertyChangeListener("font", event -> hidePopup());
        editor.addPropertyChangeListener("background", event -> setBackground(editor.getBackground()));
        editor.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) hidePopup();
            }
        });
        documentListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { setDiagnostics(List.of()); }
            @Override public void removeUpdate(DocumentEvent event) { setDiagnostics(List.of()); }
            @Override public void changedUpdate(DocumentEvent event) {}
        };
        editor.getDocument().addDocumentListener(documentListener);
        editor.addPropertyChangeListener("document", event -> {
            ((Document) event.getOldValue()).removeDocumentListener(documentListener);
            ((Document) event.getNewValue()).addDocumentListener(documentListener);
            setDiagnostics(List.of());
        });
    }

    /** Uses an immutable copy of the squiggle snapshot and cancels any old preview. */
    void setDiagnostics(List<CDiagnosticAnalyzer.Problem> snapshot) {
        hidePopup();
        problems = List.copyOf(snapshot);
        getAccessibleContext().setAccessibleDescription(problems.size() + " C diagnostics");
        repaint();
    }

    /** Disposes the preview and stops every hover timer, including when detached or hidden. */
    void hidePopup() {
        delay.stop();
        exitGrace.stop();
        watch.stop();
        target = null;
        if (popup != null) popup.dispose();
        popup = null;
        popupOwner = null;
    }

    @Override public void removeNotify() {
        hidePopup();
        super.removeNotify();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(ERROR);
        for (CDiagnosticAnalyzer.Problem problem : problems) {
            graphics.fillRect(2, markerY(problem), Math.max(1, getWidth() - 4), MARK_HEIGHT);
        }
    }

    private int markerY(CDiagnosticAnalyzer.Problem problem) {
        Element root = editor.getDocument().getDefaultRootElement();
        int line = root.getElementIndex(Math.min(problem.start(), editor.getDocument().getLength()));
        return (int) ((long) line * Math.max(0, getHeight() - MARK_HEIGHT) / Math.max(1, root.getElementCount() - 1));
    }

    private CDiagnosticAnalyzer.Problem problemAt(int y) {
        CDiagnosticAnalyzer.Problem closest = null;
        int distance = 6;
        for (CDiagnosticAnalyzer.Problem problem : problems) {
            int candidate = Math.abs(y - markerY(problem) - MARK_HEIGHT / 2);
            if (candidate < distance) {
                closest = problem;
                distance = candidate;
            }
        }
        return closest;
    }

    String previewHtml(CDiagnosticAnalyzer.Problem problem) {
        Document document = editor.getDocument();
        Element root = document.getDefaultRootElement();
        int line = root.getElementIndex(Math.min(problem.start(), document.getLength()));
        int first = Math.max(0, line - 3);
        int last = Math.min(root.getElementCount() - 1, line + 3);
        StringBuilder html = new StringBuilder("<html><body style='margin:0;color:#e1e4eb;background:#26292e;font-family:")
                .append(escape(editor.getFont().getFamily())).append(";font-size:")
                .append(editor.getFont().getSize()).append("pt'><b>Line ").append(line + 1)
                .append("</b><p>").append(escape(problem.message()).replace("\n", "<br>"))
                .append("</p><table cellpadding='3' cellspacing='0'>");
        try {
            for (int row = first; row <= last; row++) {
                Element element = root.getElement(row);
                String code = document.getText(element.getStartOffset(),
                        Math.min(element.getEndOffset(), document.getLength()) - element.getStartOffset());
                html.append(row == line ? "<tr style='background:#513238'><td>" : "<tr><td>")
                        .append(row + 1).append("</td><td><pre style='margin:0'>")
                        .append(escape(code.stripTrailing())).append("</pre></td></tr>");
            }
        } catch (BadLocationException invalid) {
            throw new IllegalStateException("Diagnostic preview no longer matches its document", invalid);
        }
        return html.append("</table></body></html>").toString();
    }

    private void showPopup() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (target == null || GraphicsEnvironment.isHeadless() || !isShowing() || owner == null || !owner.isActive()
                || !overTargetOrPopup()) {
            hidePopup();
            return;
        }
        popupOwner = owner;
        popup = new JWindow(owner);
        popup.setFocusableWindowState(false);
        popup.setAutoRequestFocus(false);
        JEditorPane card = new JEditorPane("text/html", previewHtml(target));
        card.setEditable(false);
        card.setBackground(new Color(38, 41, 46));
        card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        card.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent event) { exitGrace.stop(); }
            @Override public void mouseExited(MouseEvent event) { exitGrace.restart(); }
        });
        JScrollPane scroll = new JScrollPane(card);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(100, 106, 116)));
        Rectangle screen = usableScreen();
        int width = Math.min(screen.width, Math.max(160, Math.min(640, editorWidth() * 2 / 3)));
        card.setSize(Math.max(1, width - 28), Integer.MAX_VALUE / 1024);
        int height = Math.min(screen.height, Math.min(430, Math.max(80, card.getPreferredSize().height + 20)));
        scroll.setPreferredSize(new Dimension(width, height));
        popup.setContentPane(scroll);
        popup.pack();
        AnchoredHover.positionAtLeadingEdge(card, scroll.getViewport());
        Point point = new Point(-popup.getWidth() - 4, markerY(target) - popup.getHeight() / 2);
        SwingUtilities.convertPointToScreen(point, this);
        point.x = Math.max(screen.x, Math.min(point.x, screen.x + screen.width - popup.getWidth()));
        point.y = Math.max(screen.y, Math.min(point.y, screen.y + screen.height - popup.getHeight()));
        popup.setLocation(point);
        popup.setVisible(true);
        watch.start();
    }

    private int editorWidth() {
        return editor.getParent() instanceof JViewport viewport ? viewport.getExtentSize().width : editor.getWidth();
    }

    private Rectangle usableScreen() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        Rectangle screen = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        screen.x += insets.left;
        screen.y += insets.top;
        screen.width -= insets.left + insets.right;
        screen.height -= insets.top + insets.bottom;
        return screen;
    }

    private boolean overTargetOrPopup() {
        if (target == null || !isShowing()) return false;
        Point point = pointer.get();
        if (point == null) return false;
        Point local = new Point(point);
        SwingUtilities.convertPointFromScreen(local, this);
        if (contains(local) && problemAt(local.y) == target) return true;
        if (popup == null || !popup.isVisible()) return false;
        Rectangle bounds = popup.getBounds();
        bounds.grow(6, 6);
        if (bounds.contains(point)) return true;
        Point mark = new Point(0, markerY(target));
        SwingUtilities.convertPointToScreen(mark, this);
        return new Rectangle(Math.min(mark.x, bounds.x), Math.min(mark.y, bounds.y),
                Math.max(mark.x + getWidth(), bounds.x + bounds.width) - Math.min(mark.x, bounds.x),
                Math.max(mark.y + MARK_HEIGHT, bounds.y + bounds.height) - Math.min(mark.y, bounds.y)).contains(point);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
