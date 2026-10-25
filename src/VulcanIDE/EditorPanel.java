package VulcanIDE;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyleConstants;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.MouseWheelEvent;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** One open file: source editor with highlighting, completion, diagnostics, autosave, and run controls. */
public class EditorPanel extends JPanel {
    private static final double WHEEL_SCROLL_FONT_FACTOR = 2.4;
    private static final ExecutorService SAVE_POOL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Vulcan-Save"); t.setDaemon(true); return t; });
    private final SettingsManager settings;
    private final ConsolePanel console;
    private final ProgramRunner runner;
    private final JTextPane editor;
    private final LineNumberView lineNumbers;
    private final EditorDiagnostics diagnostics;
    private final DiagnosticStrip diagnosticStrip;
    private Runnable diagnosticsChangedListener = () -> {};
    private final JButton runButton;
    private final JButton debugButton;
    private final JButton stopButton;
    private final JScrollPane scrollPane;
    private final JPanel contentCards = new JPanel(new CardLayout());
    private final JLabel imagePreview = new JLabel();
    private final JScrollPane imageScroll;
    private final MarkdownPanel markdown;
    private final SearchBar searchBar;
    private final Timer presentationTimer;
    private final Timer autosaveTimer;
    private final Timer runnerStateTimer;
    private final Timer runFeedbackTimer;
    private final Runnable debugAction;
    private final Runnable pathChanged;
    private SessionUndo sessionUndo;
    private final Timer completionTimer;
    private final Timer selectionDragTimer = new Timer(16, event -> updateDraggedSelection());
    private Point selectionDragScreenPoint;
    private int selectionDragAnchor = -1;
    private boolean selectionDragging;
    private Timer zoomToastTimer;
    private JWindow zoomToast;
    private long zoomToastStarted;
    private int pairedQuoteCaret = -1;
    private char pairedQuote;
    private JWindow completionPopup;
    private JList<String> completionList;
    private int completionStart;
    private int completionEnd;
    private boolean applyingCompletion;
    private java.util.Map<String,String> completionDetails=java.util.Map.of();
    private java.util.function.Consumer<String> buildStatusListener=status->{};
    public void setBuildStatusListener(java.util.function.Consumer<String> listener){buildStatusListener=listener==null?status->{}:listener;}
    public void setDeclarationNavigator(java.util.function.BiConsumer<Path,Integer> navigator){diagnostics.setNavigator(navigator);}
    void setDiagnosticsChangedListener(Runnable listener){diagnosticsChangedListener=listener==null?()->{}:listener;}
    boolean hasErrors(){return diagnostics.hasErrors();}
    private boolean runFeedbackActive;
    private Runnable selectionListener = () -> {};
    private Runnable runListener = () -> {};
    private SearchBar.ProjectTextAccess projectTextAccess = SearchBar.openEditors(() -> java.util.List.of(this));
    private java.util.function.Supplier<java.util.List<EditorPanel>> refactoringPanels = () -> java.util.List.of(this);
    public void setRunListener(Runnable listener) { runListener = listener == null ? () -> {} : listener; }

    private volatile Path currentPath;
    private int fontSize;
    private boolean applyingStyles;
    private boolean loading;
    private boolean dirty;
    private final AtomicLong editSerial = new AtomicLong();
    private final java.util.concurrent.locks.ReentrantLock saveLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean refactoring;
    private boolean zoomChanged;
    private int changedStart = Integer.MAX_VALUE;
    private int changedEnd = 0;
    private Boolean lastRunState;
    private final EntryEligibility entryEligibility = new EntryEligibility();

    /** Creates an editor that reports program output to console. */
    public EditorPanel(SettingsManager settings, ConsolePanel console,
                       Runnable debugAction, Runnable pathChanged) {
        this.settings = settings;
        this.console = console;
        this.runner = new ProgramRunner();
        this.runner.setBuildStatusListener(status->{console.setBuildStatus(status);buildStatusListener.accept(status);});
        this.runner.setExecutionStartedListener(console::clear);
        this.debugAction = debugAction == null ? () -> {} : debugAction;
        this.pathChanged = pathChanged == null ? () -> {} : pathChanged;
        this.fontSize = settings.getEditorFontSize();

        setLayout(new BorderLayout());
        setBackground(settings.getColor("panelBackground"));

        CodeTextPane codeEditor = new CodeTextPane();
        codeEditor.setCaretScrollSuppressed(() -> selectionDragging);
        editor = codeEditor;
        editor.setOpaque(true);
        applyEditorFont();
        editor.setBackground(settings.getColor("editorBackground"));
        editor.setForeground(settings.getColor("editorText"));
        editor.setMargin(new Insets(0, 0, 0, 0));
        editor.setEditable(false);
        editor.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 10));
        editor.setCaretColor(settings.getColor("editorText"));
        editor.setDragEnabled(true);

        installEditingActions();
        TextContextMenu.install(editor, () -> RefactorDialog.show(this, settings, refactoringPanels));
        installSearchShortcuts();
        editor.addKeyListener(new KeyAdapter() {
            @Override public void keyTyped(KeyEvent event) {
                char typed = event.getKeyChar();
                boolean cFile = extension().equalsIgnoreCase("c") || extension().equalsIgnoreCase("h");
                if (!editor.isEditable() || !(cFile || extension().equalsIgnoreCase("asm"))) return;
                int caret = editor.getCaretPosition();
                String text = documentText();
                boolean escaped = escapedAt(text,caret);
                if (typed == '\'' || typed == '"') {
                    if (escaped) return;
                    event.consume();
                    if (editor.getSelectionStart() == editor.getSelectionEnd() && caret < text.length() && text.charAt(caret) == typed
                            && (insideQuote(text,caret,typed,currentPathIsAsm())||(pairedQuoteCaret==caret&&pairedQuote==typed))) {
                        editor.setCaretPosition(caret + 1);
                        pairedQuoteCaret = -1;
                    } else if (insideQuote(text,caret,typed,currentPathIsAsm())) {
                        editor.replaceSelection(String.valueOf(typed));
                        pairedQuoteCaret = -1;
                    } else {
                        editor.replaceSelection("" + typed + typed);
                        editor.setCaretPosition(editor.getCaretPosition() - 1);
                        pairedQuoteCaret = editor.getCaretPosition();
                        pairedQuote = typed;
                    }
                } else if (cFile && (typed == '{' || typed == '(' || typed == '[') && !insideLiteralOrComment(text, caret)) {
                    event.consume();
                    String selection = editor.getSelectedText();
                    Boolean noValueParameters = typed == '(' && selection == null ? functionHasNoValueParametersAt(text, caret) : null;
                    String insertion = autoPairInsertion(text, caret, typed, selection, noValueParameters);
                    editor.replaceSelection(insertion);
                    if (selection != null || insertion.length() == 2) editor.setCaretPosition(editor.getCaretPosition() - 1);
                } else if (cFile && typed == '}' && editor.getSelectionStart() == editor.getSelectionEnd() && !insideLiteralOrComment(text, caret)
                        && text.substring(text.lastIndexOf('\n', caret-1)+1,caret).isBlank()) {
                    String aligned=CIndentation.closingIndent(text,caret);
                    if(aligned!=null){event.consume();int lineStart=text.lastIndexOf('\n',caret-1)+1;
                        boolean skipExisting = shouldSkipCloser(text, caret, '}');
                        editor.select(lineStart,caret);editor.replaceSelection(aligned);
                        int position=editor.getCaretPosition();
                        if(skipExisting&&position<editor.getDocument().getLength()&&documentText().charAt(position)=='}')editor.setCaretPosition(position+1);
                        else editor.replaceSelection("}");}
                } else if (cFile && (typed == '}' || typed == ')' || typed == ']') && editor.getSelectionStart() == editor.getSelectionEnd() && !insideLiteralOrComment(text, caret)
                        && shouldSkipCloser(text, caret, typed)) {
                    event.consume();
                    editor.setCaretPosition(caret + 1);
                } else pairedQuoteCaret = -1;
            }
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE &&
                        e.getModifiersEx() == 0 &&
                        editor.getSelectionStart() == editor.getSelectionEnd() &&
                        editor.getCaretPosition() == 0) {
                    e.consume();
                }
            }
        });
        installSafePaste();
        installUndo();
        completionTimer = new Timer(90, e -> showCompletion());
        completionTimer.setRepeats(false);
        installCompletionActions();

        diagnostics = new EditorDiagnostics(editor, () -> currentPath, settings);
        diagnostics.setCompletionRefresh(()->{if(completionPopup!=null)showCompletion();});
        lineNumbers = new LineNumberView(editor, settings);

        searchBar = new SearchBar(settings);
        searchBar.bind(editor, (root, query, replace, replacement) -> SearchBar.searchProject(
                root == null ? settings.getCurrentDirectory() : root, query, replacement, replace, console::append, projectTextAccess));

        scrollPane = new JScrollPane(editor);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setBackground(settings.getColor("editorBackground"));
        scrollPane.setRowHeaderView(lineNumbers);
        scrollPane.setCorner(ScrollPaneConstants.LOWER_LEFT_CORNER, lineNumbers.corner());
        scrollPane.setBorder(null);
        Theme.styleScrollBar(scrollPane.getVerticalScrollBar(), settings);
        Theme.styleScrollBar(scrollPane.getHorizontalScrollBar(), settings);
        diagnosticStrip = new DiagnosticStrip(editor);
        scrollPane.setLayout(new javax.swing.ScrollPaneLayout() {
            @Override public void addLayoutComponent(String name, Component component) {
                if (!"diagnostics".equals(name)) super.addLayoutComponent(name, component);
            }
            @Override public void layoutContainer(Container parent) {
                super.layoutContainer(parent);
                Rectangle viewport = scrollPane.getViewport().getBounds();
                int width = diagnosticStrip.getPreferredSize().width;
                int x = viewport.x + viewport.width - width;
                scrollPane.getViewport().setBounds(viewport.x, viewport.y, Math.max(0, viewport.width - width), viewport.height);
                diagnosticStrip.setBounds(Math.max(viewport.x, x), viewport.y, width, viewport.height);
                JViewport columns = scrollPane.getColumnHeader();
                if (columns != null) columns.setSize(Math.max(0, viewport.width - width), columns.getHeight());
                JScrollBar horizontal = scrollPane.getHorizontalScrollBar();
                horizontal.setSize(Math.max(0, horizontal.getWidth() - width), horizontal.getHeight());
            }
        });
        scrollPane.add(diagnosticStrip, "diagnostics");
        diagnostics.setDiagnosticsChangedListener(() -> {
            diagnosticStrip.setDiagnostics(diagnostics.snapshot());
            diagnosticsChangedListener.run();
        });
        SmoothScroll.install(scrollPane);
        installSelectionTracking();
        imagePreview.setHorizontalAlignment(SwingConstants.CENTER);
        imagePreview.setVerticalAlignment(SwingConstants.CENTER);
        imagePreview.setHorizontalTextPosition(SwingConstants.CENTER);
        imagePreview.setVerticalTextPosition(SwingConstants.BOTTOM);
        imagePreview.setIconTextGap(12);
        imagePreview.setForeground(settings.getColor("text"));
        imagePreview.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        imageScroll = new JScrollPane(imagePreview);
        imageScroll.setBorder(null);
        Theme.styleScrollBar(imageScroll.getVerticalScrollBar(), settings);
        Theme.styleScrollBar(imageScroll.getHorizontalScrollBar(), settings);
        SmoothScroll.install(imageScroll);
        contentCards.add(imageScroll, "image");
        markdown = new MarkdownPanel(scrollPane, editor, settings, () -> currentPath);
        contentCards.add(markdown, "markdown");

        contentCards.add(scrollPane, "editor");

        runButton = iconButton(Icons.vector("run", 15, new Color(47, 184, 92)), "Run", this::performRun);
        debugButton = iconButton(Icons.action(Icons.DEBUG_ICON, "debug", 18, settings.getColor("text")), "Debug", this.debugAction);
        stopButton = iconButton(Icons.vector("stop", 15, settings.getColor("stopButton")), "Stop", this::stopRunning);

        JPanel header = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 4));
        header.setBorder(null);
        header.add(runButton);
        header.add(debugButton);
        header.add(stopButton);

        JPanel north = new JPanel(new BorderLayout());
        north.add(searchBar, BorderLayout.NORTH);
        north.add(header, BorderLayout.SOUTH);
        searchBar.setVisible(false);
        add(north, BorderLayout.NORTH);
        add(contentCards, BorderLayout.CENTER);

        presentationTimer = new Timer(180, e -> refreshChangedPresentation());
        presentationTimer.setRepeats(false);
        autosaveTimer = new Timer(900, e -> saveFileAsync());
        autosaveTimer.setRepeats(false);
        runnerStateTimer = new Timer(120, e -> updateRunDebugButtons());
        runFeedbackTimer = new Timer(500, e -> {
            runFeedbackActive = false;
            updateRunDebugButtons();
        });
        runFeedbackTimer.setRepeats(false);

        addHierarchyListener(e -> {
            if (isShowing()) runnerStateTimer.start();
            else runnerStateTimer.stop();
        });

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { documentChanged(e); }
            @Override public void removeUpdate(DocumentEvent e) { documentChanged(e); }
            @Override public void changedUpdate(DocumentEvent e) { if (!applyingStyles) dirty = true; }
        });

        editor.addMouseWheelListener(this::handleMouseWheel);
        OccurrenceHighlighter.install(editor);
        editor.addCaretListener(e -> {
            if(applyingStyles||loading)return;
            if (!applyingCompletion) hideCompletionPopup();
            if (!selectionDragging && !applyingCompletion && (currentPathIsAsm() || isCSource()) && editor.isEditable()) completionTimer.restart();
        });
        editor.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent event) { hideCompletionPopup(); stopSelectionTracking(); }
            @Override public void focusGained(java.awt.event.FocusEvent event) { selectionListener.run(); }
        });
        showEditorIfLoaded();
        updateRunDebugButtons();
    }

    private JButton iconButton(Icon icon, String tooltip, Runnable action) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setPreferredSize(new Dimension(31, 27));
        button.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    private JButton actionButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(settings.getColor("splitDivider")),
                BorderFactory.createEmptyBorder(3, 9, 3, 9)
        ));
        button.setContentAreaFilled(true);
        button.setOpaque(true);
        button.setBackground(settings.getColor("panelBackground"));
        button.setForeground(settings.getColor("text"));
        button.addActionListener(e -> action.run());
        return button;
    }

    static boolean escapedAt(String text,int caret){
        int slashes=0;while(caret>0&&text.charAt(--caret)=='\\')slashes++;
        return (slashes&1)!=0;
    }
    static boolean insideQuote(String text,int end,char wanted,boolean asm){
        char quote=0;boolean line=false,block=false;
        for(int i=0;i<end;i++){
            char c=text.charAt(i),next=i+1<end?text.charAt(i+1):0;
            if(line){if(c=='\n')line=false;continue;}
            if(block){if(c=='*'&&next=='/'){block=false;i++;}continue;}
            if(quote!=0){if(c=='\\')i++;else if(c==quote)quote=0;continue;}
            if((asm&&c==';')||(!asm&&c=='/'&&next=='/')){line=true;continue;}
            if(!asm&&c=='/'&&next=='*'){block=true;i++;continue;}
            if(c=='\''||c=='"')quote=c;
        }
        return quote==wanted;
    }
    private static boolean insideLiteralOrComment(String text, int end) {
        int boundary = Math.max(0, Math.min(end, text.length()));
        String probe = text.substring(0, boundary) + 'x';
        return CIndentation.code(probe).charAt(boundary) != 'x';
    }

    static String autoPairInsertion(String text, int caret, char opening, String selection, Boolean knownFunctionHasNoValueParameters) {
        if (caret < 0 || caret > text.length() || insideLiteralOrComment(text, caret)) return String.valueOf(opening);
        char closing = matchingCloser(opening);
        if (closing == 0) return String.valueOf(opening);
        if (opening == '(' && selection == null && Boolean.FALSE.equals(knownFunctionHasNoValueParameters)) return "(";
        return String.valueOf(opening) + (selection == null ? "" : selection) + closing;
    }

    static boolean shouldSkipCloser(String text, int caret, char closer) {
        if (caret < 0 || caret >= text.length() || text.charAt(caret) != closer) return false;
        char opening = matchingOpener(closer);
        if (opening == 0) return false;
        String code = CIndentation.code(text);
        ArrayDeque<Character> openers = new ArrayDeque<>();
        for (int i = 0; i < caret; i++) {
            char current = code.charAt(i);
            if (current == '(' || current == '{' || current == '[') openers.push(current);
            else if (current == ')' || current == '}' || current == ']') {
                char expected = matchingOpener(current);
                if (!openers.isEmpty() && openers.peek() == expected) openers.pop();
            }
        }
        return !openers.isEmpty() && openers.peek() == opening;
    }

    private static char matchingCloser(char opening) {
        return switch (opening) { case '(' -> ')'; case '{' -> '}'; case '[' -> ']'; default -> 0; };
    }

    private static char matchingOpener(char closer) {
        return switch (closer) { case ')' -> '('; case '}' -> '{'; case ']' -> '['; default -> 0; };
    }

    static Boolean functionHasNoValueParameters(CSymbolIndex symbols, String name, int offset) {
        if (symbols == null || name == null || name.isBlank()) return null;
        CSymbolIndex.Symbol symbol = symbols.find(name, offset);
        if (symbol == null || !symbols.functionDeclaration(symbol)) return null;
        String declaration = CIndentation.code(symbol.declaration());
        int nameStart = declaration.indexOf(symbol.name());
        int opening = nameStart < 0 ? -1 : declaration.indexOf('(', nameStart + symbol.name().length());
        if (opening < 0) return null;
        int depth = 0, closing = -1;
        for (int i = opening; i < declaration.length(); i++) {
            if (declaration.charAt(i) == '(') depth++;
            else if (declaration.charAt(i) == ')' && --depth == 0) { closing = i; break; }
        }
        if (closing < 0) return null;
        String parameters = declaration.substring(opening + 1, closing).strip();
        return parameters.isEmpty() || parameters.equals("void");
    }

    private Boolean functionHasNoValueParametersAt(String text, int caret) {
        String name = identifierBeforeCaret(text, caret);
        if (name == null || Set.of("if", "for", "while", "switch", "sizeof", "_Alignof", "return", "case", "do", "else").contains(name)) return null;
        Path file = currentPath;
        if (file == null) return null;
        List<Path> includes = new ArrayList<>();
        if (file.getParent() != null) includes.add(file.getParent());
        Path workingDirectory = settings.getCurrentDirectory();
        if (workingDirectory != null) includes.add(workingDirectory.resolve("src/stdlib/include"));
        includes.add(Path.of("src/stdlib/include"));
        Path bundled = StandardHeaders.directory();
        if (bundled != null) includes.add(bundled);
        CSymbolIndex symbols = CSymbolIndex.buildAt(text, file, includes, caret);
        return functionHasNoValueParameters(symbols, name, caret);
    }

    private static String identifierBeforeCaret(String text, int caret) {
        int end = Math.min(Math.max(caret, 0), text.length());
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '_')) start--;
        if (start == end || !(Character.isLetter(text.charAt(start)) || text.charAt(start) == '_')) return null;
        if (start > 0 && (text.charAt(start - 1) == '.' || text.charAt(start - 1) == '>')) return null;
        return text.substring(start, end);
    }

    private void insertIndentedLine() {
        if (!editor.isEditable()) return;
        String text = documentText(); int caret = editor.getSelectionStart();
        int start = text.lastIndexOf('\n', Math.max(-1, caret - 1)) + 1;
        int end = start; while (end < caret && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        String indent = text.substring(start, end);
        boolean cFile = extension().equalsIgnoreCase("c") || extension().equalsIgnoreCase("h");
        boolean opens = cFile && text.substring(start, caret).stripTrailing().endsWith("{") && !insideLiteralOrComment(text, caret);
        String inner = cFile && !insideLiteralOrComment(text,caret) ? CIndentation.nextIndent(text,caret) : indent;
        if (opens && caret < text.length() && text.charAt(caret) == '}') {
            editor.replaceSelection("\n" + inner + "\n" + indent);
            editor.setCaretPosition(caret + 1 + inner.length());
        } else editor.replaceSelection("\n" + inner);
    }

    private void installEditingActions() {
        InputMap input = editor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = editor.getActionMap();
        input.put(KeyStroke.getKeyStroke("pressed ENTER"), "vulcan-newline");
        actions.put("vulcan-newline", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { insertIndentedLine(); }
        });

        input.put(KeyStroke.getKeyStroke("control SLASH"), "vulcan-comment");
        actions.put("vulcan-comment", new AbstractAction() {
            /** Runs the bound editor action. */
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (java.util.Set.of("c", "h", "asm").contains(extension().toLowerCase()))
                    SelectedLines.apply(editor, SelectedLines.Operation.COMMENT, currentPathIsAsm() ? ";" : "//");
            }
        });
        input.put(KeyStroke.getKeyStroke("pressed TAB"), "vulcan-tab");
        actions.put("vulcan-tab", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { insertTabIndent(); }
        });
        input.put(KeyStroke.getKeyStroke("shift TAB"), "vulcan-shift-tab");
        actions.put("vulcan-shift-tab", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { unindentCurrentLine(); }
        });

        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "vulcan-backspace");
        editor.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "vulcan-backspace");
        actions.put("vulcan-backspace", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                int start = editor.getSelectionStart();
                int end = editor.getSelectionEnd();
                if (start != end) {
                    pairedQuoteCaret = -1;
                    editor.replaceSelection("");
                    return;
                }
                if (start > 0 && start < editor.getDocument().getLength()) {
                    try {
                        String pair = editor.getDocument().getText(start - 1, 2);
                        if (java.util.Set.of("{}", "[]", "()").contains(pair) && !insideLiteralOrComment(documentText(), start - 1)) {
                            editor.getDocument().remove(start - 1, 2); return;
                        }
                    } catch (BadLocationException ignored) { }
                }
                if (start == pairedQuoteCaret && pairedQuoteCaret >= 1
                        && start < editor.getDocument().getLength()) {
                    try {
                        String pair = editor.getDocument().getText(start - 1, 2);
                        if (pair.charAt(0) == pairedQuote && pair.charAt(1) == pairedQuote) {
                            editor.getDocument().remove(start - 1, 2);
                            pairedQuoteCaret = -1;
                            return;
                        }
                    } catch (BadLocationException ignored) { }
                    pairedQuoteCaret = -1;
                } else pairedQuoteCaret = -1;
                if (start > 0) {
                    try {
                        int lineStart = javax.swing.text.Utilities.getRowStart(editor, start);
                        int removeStart = start - 1;
                        if (lineStart >= 0 && start > lineStart) {
                            String before = editor.getDocument().getText(lineStart, start - lineStart);
                            int lastTab = before.lastIndexOf('\t');
                            if (lastTab >= 0 && before.substring(lastTab + 1).chars().allMatch(c -> c == ' '))
                                removeStart = lineStart + lastTab;
                            else if (before.charAt(before.length() - 1) == '\t') removeStart = start - 1;
                        }
                        editor.getDocument().remove(removeStart, start - removeStart);
                    }
                    catch (BadLocationException ignored) { }
                }
            }
        });

        input.put(KeyStroke.getKeyStroke("pressed DELETE"), "vulcan-delete");
        actions.put("vulcan-delete", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                pairedQuoteCaret = -1;
                int start = editor.getSelectionStart();
                int end = editor.getSelectionEnd();
                if (start != end) {
                    editor.replaceSelection("");
                    return;
                }
                if (start < editor.getDocument().getLength()) {
                    try { editor.getDocument().remove(start, 1); }
                    catch (BadLocationException ignored) { }
                }
            }
        });
    }

    private void insertTabIndent() {
        try {
            int start = editor.getSelectionStart();
            int end = editor.getSelectionEnd();
            if (start != end) { SelectedLines.apply(editor, SelectedLines.Operation.INDENT, ""); return; }
            int lineStart = javax.swing.text.Utilities.getRowStart(editor, start);
            if (lineStart < 0) { editor.replaceSelection("\t"); return; }
            String before = editor.getDocument().getText(lineStart, start - lineStart);
            int spaces = 0;
            while (spaces < before.length() && before.charAt(before.length() - 1 - spaces) == ' ') spaces++;
            if (spaces > 0 && spaces < 4) {
                editor.getDocument().remove(start - spaces, spaces);
                editor.getDocument().insertString(start - spaces, "\t", null);
                editor.setCaretPosition(start - spaces + 1);
            } else editor.replaceSelection("\t");
        } catch (BadLocationException ignored) { }
    }

    private void installSearchShortcuts() {
        InputMap input = editor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = editor.getActionMap();
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), "find-current");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), "find-project");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK), "replace-current");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), "replace-project");
        actions.put("find-current", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { searchBar.showFind(false, false); } });
        actions.put("find-project", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { searchBar.showFind(true, false); } });
        actions.put("replace-current", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { searchBar.showFind(false, true); } });
        actions.put("replace-project", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { searchBar.showFind(true, true); } });
    }

    private void unindentCurrentLine() {
        if (editor.getSelectionStart() != editor.getSelectionEnd()) { SelectedLines.apply(editor, SelectedLines.Operation.UNINDENT, ""); return; }
        try {
            int caret = editor.getCaretPosition();
            int lineStart = javax.swing.text.Utilities.getRowStart(editor, caret);
            if (lineStart < 0) return;
            int length = Math.min(4, editor.getDocument().getLength() - lineStart);
            String text = editor.getDocument().getText(lineStart, length);
            int remove = !text.isEmpty() && text.charAt(0) == '\t' ? 1 : 0;
            while (remove < text.length() && remove < 4 && text.charAt(remove) == ' ') remove++;
            if (remove > 0) editor.getDocument().remove(lineStart, remove);
        } catch (BadLocationException ignored) {
        }
    }

    private void documentChanged(DocumentEvent event) {
        if (loading || applyingStyles) return;
        dirty = true;
        editSerial.incrementAndGet();
        changedStart = Math.min(changedStart, event.getOffset());
        int end = event.getOffset() + Math.max(1, event.getLength());
        if (event.getType() == DocumentEvent.EventType.INSERT) end += 1;
        changedEnd = Math.max(changedEnd, end);
        if (event.getType() == DocumentEvent.EventType.INSERT || event.getType() == DocumentEvent.EventType.REMOVE) {
            boolean lineStructureChanged = event.getLength() > 1;
            if (event.getType() == DocumentEvent.EventType.INSERT && event.getLength() > 0) {
                try { lineStructureChanged = editor.getDocument().getText(event.getOffset(), event.getLength()).indexOf('\n') >= 0 || lineStructureChanged; } catch (BadLocationException ignored) {}
            }
            if (event.getType() == DocumentEvent.EventType.REMOVE && event.getOffset() < editor.getDocument().getLength()) {
                try { lineStructureChanged = editor.getDocument().getText(event.getOffset(), 1).indexOf('\n') >= 0 || lineStructureChanged; } catch (BadLocationException ignored) {}
            }
            if (lineStructureChanged) lineNumbers.revalidate();
            lineNumbers.repaint();
        }
        presentationTimer.restart();
        diagnostics.changed();
        autosaveTimer.restart();
        if (!applyingCompletion && (currentPathIsAsm() || isCSource())) completionTimer.restart();
    }

    private void handleMouseWheel(MouseWheelEvent event) {
        if (event.isControlDown()) {
            event.consume();
            int newSize = Math.max(8, Math.min(48, fontSize - event.getWheelRotation()));
            if (newSize != fontSize) {
                fontSize = newSize;
                settings.setFileFontSize(currentPath, fontSize);
                zoomChanged = true;
                applyEditorFont();
                lineNumbers.revalidate();
                lineNumbers.repaint();
                showZoomFeedback();
                applyingStyles = true;
                try { SyntaxHighlighter.apply(editor, extension(), settings.isSyntaxHighlighting(), settings); }
                finally { applyingStyles = false; }
            }
            return;
        }

        Point point=SwingUtilities.convertPoint(editor,event.getPoint(),scrollPane);
        scrollPane.dispatchEvent(new MouseWheelEvent(scrollPane,event.getID(),event.getWhen(),event.getModifiersEx(),point.x,point.y,event.getXOnScreen(),event.getYOnScreen(),event.getClickCount(),event.isPopupTrigger(),event.getScrollType(),event.getScrollAmount(),event.getWheelRotation(),event.getPreciseWheelRotation()));
        event.consume();
    }

    private void showZoomFeedback() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (owner == null || !owner.isShowing()) return;
        if (zoomToast == null || zoomToast.getOwner() != owner) {
            if (zoomToast != null) zoomToast.dispose();
            zoomToast = new JWindow(owner);
            JLabel label = new JLabel("Font size: " + fontSize + " px");
            label.setForeground(settings.getColor("text"));
            label.setFont(new Font(settings.getApplicationFont(), Font.BOLD, 14));
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(settings.getColor("splitDivider")),
                    BorderFactory.createEmptyBorder(7, 13, 7, 13)));
            label.setOpaque(true);
            label.setBackground(settings.getColor("panelBackground"));
            zoomToast.setContentPane(label);
            zoomToast.pack();
        }
        ((JLabel) zoomToast.getContentPane()).setText("Font size: " + fontSize + " px");
        zoomToast.pack();
        zoomToast.setLocation(owner.getX() + (owner.getWidth() - zoomToast.getWidth()) / 2, owner.getY() + 42);
        zoomToastStarted = System.currentTimeMillis();
        try { zoomToast.setOpacity(1f); } catch (UnsupportedOperationException ignored) { }
        zoomToast.setVisible(true);
        if (zoomToastTimer != null) zoomToastTimer.stop();
        zoomToastTimer = new Timer(30, event -> {
            long elapsed = System.currentTimeMillis() - zoomToastStarted;
            float opacity = elapsed < 900 ? 1f : 1f - (elapsed - 900) / 250f;
            if (elapsed >= 1150) {
                zoomToast.setVisible(false);
                zoomToastTimer.stop();
                return;
            }
            try { zoomToast.setOpacity(Math.max(0f, Math.min(1f, opacity))); }
            catch (UnsupportedOperationException ignored) { }
        });
        zoomToastTimer.start();
    }

    private void installSafePaste() {
        Action paste = new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                editor.paste();
            }
        };
        editor.getActionMap().put("vulcanPaste", paste);
        editor.getInputMap().put(KeyStroke.getKeyStroke("control V"), "vulcanPaste");
        editor.getInputMap().put(KeyStroke.getKeyStroke("shift INSERT"), "vulcanPaste");
    }

    private void installUndo() {
        sessionUndo = new SessionUndo(editor, () -> loading);
        InputMap input = editor.getInputMap(JComponent.WHEN_FOCUSED);
        input.put(KeyStroke.getKeyStroke("control Z"), "vulcan-undo");
        input.put(KeyStroke.getKeyStroke("control Y"), "vulcan-redo");
        editor.getActionMap().put("vulcan-undo", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { sessionUndo.undo(); }
        });
        editor.getActionMap().put("vulcan-redo", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { sessionUndo.redo(); }
        });
    }

    private boolean isCSource(){return extension().equalsIgnoreCase("c")||extension().equalsIgnoreCase("h");}
    private void showCompletion() {
        if (!(currentPathIsAsm() || isCSource()) || !editor.isEditable() || !editor.isFocusOwner()
                || editor.getSelectionStart() != editor.getSelectionEnd()) {
            hideCompletionPopup();
            return;
        }
        try {
            int pos = editor.getCaretPosition();
            String text = documentText();
            if (currentPathIsAsm() && AsmCompletion.isAtCompleteInstructionEnd(text, pos)) { hideCompletionPopup(); return; }
            String prefix = isCSource()?CCompletion.prefixAt(text,pos):AsmCompletion.prefixAt(text, pos);
            completionStart = pos-prefix.length();
            completionEnd = pos;
            if (prefix.isEmpty()) { hideCompletionPopup(); return; }
            if(isCSource()){
                diagnostics.completions(text,pos,entries->{
                    java.util.Map<String,String> details=new java.util.LinkedHashMap<>();for(var entry:entries)details.put(entry.name(),entry.detail());completionDetails=details;
                    displayCompletion(prefix,entries.stream().map(CCompletion.Entry::name).toList(),pos);
                });return;
            }
            completionDetails=java.util.Map.of();displayCompletion(prefix,AsmCompletion.suggestions(text,prefix),pos);
        } catch (Exception ignored) { hideCompletionPopup(); }
    }
    private void displayCompletion(String prefix,java.util.List<String> matches,int pos){
        if(!editor.isFocusOwner()||!editor.isEditable()||editor.getSelectionStart()!=editor.getSelectionEnd())return;
        try {
            boolean ignoreCase=currentPathIsAsm();
            matches=matches.stream().filter(option->ignoreCase?!option.equalsIgnoreCase(prefix):!option.equals(prefix)).toList();
            if (matches.isEmpty()) { hideCompletionPopup(); return; }
            hideCompletionPopup();
            completionPopup = new JWindow(SwingUtilities.getWindowAncestor(this));
            completionPopup.setFocusableWindowState(false);
            completionPopup.setAutoRequestFocus(false);
            completionList = new JList<>(matches.toArray(String[]::new));
            completionList.setVisibleRowCount(Math.min(8, matches.size()));
            completionList.setFocusable(false);
            completionList.setSelectedIndex(0);
            completionList.setFont(editor.getFont());
            completionList.setForeground(settings.getColor("text"));
            completionList.setBackground(settings.getColor("panelBackground"));
            completionList.setSelectionBackground(settings.getColor("tabSelected"));
            completionList.setSelectionForeground(settings.getColor("text"));
            completionList.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            completionList.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean selected, boolean focus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                    String option = String.valueOf(value);
                    int boldLength = Math.min(prefix.length(), option.length());
                    label.setText("<html><b>" + escapeHtml(option.substring(0, boldLength)) + "</b>"
                            + escapeHtml(option.substring(boldLength)) + " &nbsp; | &nbsp; <span style='font-weight:normal'>"
                            + escapeHtml(completionDetails.getOrDefault(option,AsmCompletion.description(option))) + "</span></html>");
                    return label;
                }
            });
            completionList.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent event) {
                    completionList.setSelectedIndex(completionList.locationToIndex(event.getPoint()));
                    applyCompletion();
                }
            });
            JScrollPane choices = new JScrollPane(completionList);
            choices.setBorder(null);
            completionPopup.add(choices);
            completionPopup.pack();
            Rectangle r = editor.modelToView2D(pos).getBounds();
            Point point = new Point(r.x, r.y + r.height);
            SwingUtilities.convertPointToScreen(point, editor);
            Rectangle screen = editor.getGraphicsConfiguration().getBounds();
            point.x = Math.max(screen.x, Math.min(point.x, screen.x + screen.width - completionPopup.getWidth()));
            if (point.y + completionPopup.getHeight() > screen.y + screen.height)
                point.y -= completionPopup.getHeight() + r.height;
            completionPopup.setLocation(point);
            completionPopup.setVisible(true);
        } catch (Exception ignored) { hideCompletionPopup(); }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void installCompletionActions() {
        int[] keys = {KeyEvent.VK_ENTER, KeyEvent.VK_TAB, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_ESCAPE};
        for (int key : keys) {
            KeyStroke stroke = KeyStroke.getKeyStroke(key, 0);
            Object fallbackName = editor.getInputMap().get(stroke);
            Action fallback = fallbackName == null ? null : editor.getActionMap().get(fallbackName);
            String name = "completion-" + key;
            editor.getInputMap().put(stroke, name);
            editor.getActionMap().put(name, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (key == KeyEvent.VK_TAB && editor.getSelectionStart() != editor.getSelectionEnd()) {
                        completionTimer.stop();
                        hideCompletionPopup();
                        if (fallback != null) fallback.actionPerformed(event);
                        completionTimer.stop();
                        return;
                    }
                    if (completionPopup == null && completionTimer.isRunning() &&
                            (key == KeyEvent.VK_ENTER || key == KeyEvent.VK_TAB)) showCompletion();
                    if (completionPopup != null) {
                        if (key == KeyEvent.VK_ENTER || key == KeyEvent.VK_TAB) { applyCompletion(); return; }
                        if (key == KeyEvent.VK_ESCAPE) { completionTimer.stop(); hideCompletionPopup(); return; }
                        int count = completionList.getModel().getSize();
                        int selected = Math.floorMod(completionList.getSelectedIndex() + (key == KeyEvent.VK_UP ? -1 : 1), count);
                        completionList.setSelectedIndex(selected);
                        completionList.ensureIndexIsVisible(selected);
                        return;
                    }
                    if (fallback != null) fallback.actionPerformed(event);
                }
            });
        }
        editor.setFocusTraversalKeysEnabled(false);
    }

    private void hideCompletionPopup() {
        if (completionPopup == null) return;
        completionPopup.setVisible(false);
        completionPopup.dispose();
        completionPopup = null;
        completionList = null;
    }

    private void applyCompletion() {
        if (editor.getSelectionStart() != editor.getSelectionEnd()) {
            completionTimer.stop();
            hideCompletionPopup();
            return;
        }
        if (completionList == null || completionList.getSelectedValue() == null) return;
        String match = completionList.getSelectedValue();
        completionEnd = editor.getCaretPosition();
        String prefix = isCSource()?CCompletion.prefixAt(documentText(),completionEnd):AsmCompletion.prefixAt(documentText(), completionEnd);
        if (prefix.isEmpty()) { hideCompletionPopup(); return; }
        completionStart = completionEnd - prefix.length();
        completionTimer.stop();
        hideCompletionPopup();
        applyingCompletion = true;
        try {
            editor.getDocument().remove(completionStart, completionEnd-completionStart);
            editor.getDocument().insertString(completionStart, match, null);
            editor.setCaretPosition(completionStart+match.length());
        } catch (BadLocationException ignored) { }
        finally { applyingCompletion = false; }
    }

    private boolean currentPathIsAsm() {
        return currentPath != null && currentPath.getFileName().toString().toLowerCase().endsWith(".asm");
    }

    private String documentText() {
        try { return editor.getDocument().getText(0, editor.getDocument().getLength()); }
        catch (BadLocationException ignored) { return ""; }
    }

    private void installSelectionTracking() {
        selectionDragTimer.setCoalesce(true);
        editor.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                selectionDragAnchor=Math.max(0,Math.min(editor.getDocument().getLength(),editor.viewToModel2D(event.getPoint())));
                selectionDragScreenPoint=editor.isShowing()?event.getLocationOnScreen():null;
                selectionDragging=false;
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) stopSelectionTracking();
            }
        });
        editor.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseDragged(java.awt.event.MouseEvent event) {
                if (selectionDragAnchor<0||(event.getModifiersEx()&java.awt.event.InputEvent.BUTTON1_DOWN_MASK)==0||!editor.isShowing()) return;
                selectionDragScreenPoint=event.getLocationOnScreen();
                selectionDragging=true;
                selectionDragTimer.start();
                updateDraggedSelection();
            }
        });
        editor.addHierarchyListener(event->{
            if((event.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0&&!editor.isShowing())stopSelectionTracking();
        });
    }

    private void updateDraggedSelection() {
        if(!selectionDragging||selectionDragScreenPoint==null||!editor.isShowing())return;
        Point point=new Point(selectionDragScreenPoint);
        SwingUtilities.convertPointFromScreen(point,editor);
        int endpoint=Math.max(0,Math.min(editor.getDocument().getLength(),editor.viewToModel2D(point)));
        extendSelection(editor,selectionDragAnchor,endpoint);
    }

    private void stopSelectionTracking() {
        selectionDragTimer.stop();
        selectionDragAnchor=-1;
        selectionDragScreenPoint=null;
        selectionDragging=false;
    }

    static void extendSelection(JTextPane editor,int anchor,int endpoint) {
        int length=editor.getDocument().getLength();
        int start=Math.max(0,Math.min(length,anchor)),end=Math.max(0,Math.min(length,endpoint));
        editor.getCaret().setDot(start);
        editor.getCaret().moveDot(end);
    }

    private void refreshChangedPresentation() {
        if (!java.util.Set.of("asm", "c", "h").contains(extension().toLowerCase(java.util.Locale.ROOT))) { changedStart = Integer.MAX_VALUE; changedEnd = 0; return; }
        int start = changedStart == Integer.MAX_VALUE ? 0 : changedStart;
        int end = Math.max(start + 1, changedEnd);
        changedStart = Integer.MAX_VALUE;
        changedEnd = 0;
        applyingStyles = true;
        try {
            SyntaxHighlighter.applyRange(editor, extension(), settings.isSyntaxHighlighting(), settings, start, end);
        } finally {
            applyingStyles = false;
        }
    }

    private String extension() {
        if (currentPath == null) return "";
        String name = currentPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    /** Loads a file into the editor, choosing source, image, binary, or Markdown view. */
    public void openFile(Path path) {
        if (path == null) {
            runner.stop();
            editSerial.incrementAndGet();
            currentPath = null;
            fontSize = settings.getEditorFontSize();
            applyEditorFont();
            settings.setSettingsEditing(false);
            loading = true;
            try {
                editor.setText("");
                editor.setCaretPosition(0);
                editor.setEditable(true);
                sessionUndo.clear();
            } finally {
                loading = false;
            }
            dirty = false;
            updateRunDebugButtons();
            pathChanged.run();
            return;
        }
        runner.stop();
        Path absolute = path.toAbsolutePath().normalize();
        boolean executable = isExecutableFile(absolute);
        boolean imageFile = isImageFile(absolute);
        final String text;
        BufferedImage previewImage = null;
        try {
            if (executable && !VulcanExecutable.isExecutable(absolute) && Files.size(absolute) > 0xFF00L) {
                throw new IOException("Executable is too large to display in the editor.");
            }
            if (imageFile) {
                previewImage = ImageIO.read(absolute.toFile());
                if (previewImage == null) throw new IOException("No installed image reader supports this file format.");
                text = "";
            } else if (executable) {

                try (var input = Files.newInputStream(absolute)) { text = readBinaryPreview(input); }
            } else text = Files.readString(absolute, StandardCharsets.UTF_8);
        } catch (IOException error) {
            console.appendError("Open error: " + VulcanDialog.errorSummary(error) + "\n");
            return;
        }

        if (isSettingsFile(currentPath) && !isSettingsFile(absolute)) {
            if (!saveFileInternal()) return;
            settings.setSettingsEditing(false);
        }

        currentPath = absolute;
        fontSize = settings.getFileFontSize(currentPath);
        applyEditorFont();
        editSerial.incrementAndGet();
        boolean settingsFile = isSettingsFile(absolute);
        settings.setSettingsEditing(settingsFile);
        if (!settingsFile) settings.setCurrentFile(currentPath);

        loading = true;
        try {
            if (executable) {

                editor.getDocument().remove(0, editor.getDocument().getLength());
                editor.getDocument().insertString(0, text, null);
            } else editor.setText(text);
            sessionUndo.clear();
            editor.setCaretPosition(0);
            editor.setEditable(!executable && !imageFile);
            scrollPane.setRowHeaderView(executable || imageFile ? null : lineNumbers);
            if (imageFile) {
                imagePreview.setIcon(new ImageFrameIcon(previewImage, settings.getColor("lineNumber")));
                imagePreview.setText(previewImage.getWidth() + " × " + previewImage.getHeight()
                        + " pixels · 100% · " + (previewImage.getColorModel().hasAlpha() ? "with transparency" : "opaque"));
                ((CardLayout) contentCards.getLayout()).show(contentCards, "image");
            } else {
                imagePreview.setIcon(null);
                imagePreview.setText("");
                if (extension().equalsIgnoreCase("md")) {
                    markdown.activate(scrollPane);
                    ((CardLayout) contentCards.getLayout()).show(contentCards, "markdown");
                } else {
                    contentCards.add(scrollPane, "editor");
                    ((CardLayout) contentCards.getLayout()).show(contentCards, "editor");
                }
            }
            loading = false;

            dirty = false;
            updateRunDebugButtons();
            applyingStyles = true;
            try { SyntaxHighlighter.apply(editor, extension(), settings.isSyntaxHighlighting(), settings); }
            finally { applyingStyles = false; }
            lineNumbers.revalidate();
            lineNumbers.repaint();
            diagnostics.changed();
            restoreSavedScrollPosition(absolute);
            pathChanged.run();
        } catch (BadLocationException error) {
            console.appendError("Open error: " + VulcanDialog.errorSummary(error) + "\n");
        } finally {
            loading = false;
        }
    }

    private boolean isSettingsFile(Path path) {
        return path != null && path.toAbsolutePath().normalize().equals(settings.getPath());
    }

    private boolean isImageFile(Path path) {
        if (path == null) return false;
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String suffix = name.substring(dot + 1);
        for (String supported : ImageIO.getReaderFileSuffixes())
            if (supported.equalsIgnoreCase(suffix)) return true;
        return false;
    }

    private void applyEditorFont() {
        Font font = new Font(settings.getEditorFont(), Font.PLAIN, fontSize);
        editor.setFont(font);
        editor.getInputAttributes().addAttribute(StyleConstants.FontFamily, font.getFamily());
        editor.getInputAttributes().addAttribute(StyleConstants.FontSize, fontSize);
        editor.getInputAttributes().addAttribute(StyleConstants.Bold, false);
        editor.getInputAttributes().addAttribute(StyleConstants.Italic, false);
    }

    private boolean isExecutableFile(Path path) {
        return path != null && path.getFileName().toString().toLowerCase().endsWith(".exe");
    }

    static String readBinaryPreview(java.io.InputStream input) throws IOException {
        return new String(input.readNBytes(0x10000), StandardCharsets.ISO_8859_1);
    }

    private void saveFileAsync() {
        if (refactoring || !dirty || currentPath == null || isExecutableFile(currentPath) || isSettingsFile(currentPath)) return;
        Path path = currentPath;
        String text = editor.getText();
        long serial = editSerial.get();
        SAVE_POOL.submit(() -> {
            try {
                saveLock.lock();
                try {
                    if (refactoring || editSerial.get() != serial || !path.equals(currentPath)) return;
                    writeAtomically(path, text);
                } finally { saveLock.unlock(); }
                SwingUtilities.invokeLater(() -> {
                    if (editSerial.get() == serial && path.equals(currentPath)) dirty = false;
                });
            } catch (IOException error) {
                SwingUtilities.invokeLater(() -> {
                    if (editSerial.get() == serial && path.equals(currentPath)) {
                        console.appendError("Autosave failed: " + VulcanDialog.errorSummary(error) + "\n");
                    }
                });
            }
        });
    }

    /** Writes the editor text to its file. */
    public void saveFile() {
        saveFileInternal();
    }

    void setProjectTextAccess(SearchBar.ProjectTextAccess access) { projectTextAccess = access; }

    void setRefactoringPanels(java.util.function.Supplier<java.util.List<EditorPanel>> panels) { refactoringPanels = panels; }

    void showFindInFolder(Path root) { searchBar.showFindInFolder(root); }

    String projectText(Path path) {
        return path.toAbsolutePath().normalize().equals(currentPath) && (editor.isEditable() || refactoring) ? documentText() : null;
    }

    int refactoringSelectionStart() { return editor.getSelectionStart(); }
    int refactoringSelectionEnd() { return editor.getSelectionEnd(); }

    RefactoringLease beginRefactoring(Path expectedPath, String expectedText) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Refactoring buffers must be acquired on the event thread.");
        if (!saveLock.tryLock()) throw new IllegalStateException("A file is being saved. Retry the refactoring preview.");
        try {
            if (refactoring || !editor.isEditable() || !expectedPath.toAbsolutePath().normalize().equals(currentPath)
                    || !documentText().equals(expectedText)) throw new IllegalStateException("An editor changed after the preview. Preview again.");
            RefactoringLease lease = new RefactoringLease(expectedText);
            refactoring = true;
            autosaveTimer.stop();
            completionTimer.stop();
            hideCompletionPopup();
            editSerial.incrementAndGet();
            editor.setEditable(false);
            return lease;
        } finally { saveLock.unlock(); }
    }

    /** Holds one editor snapshot stable while a previewed project refactoring is applied. */
    final class RefactoringLease implements AutoCloseable {
        private final String originalText;
        private final boolean originalDirty;
        private final int originalDot;
        private final int originalMark;
        private final SessionUndo.Checkpoint undoCheckpoint;
        private String appliedText;
        private boolean closed;

        private RefactoringLease(String text) {
            originalText = text;
            originalDirty = dirty;
            originalDot = editor.getCaret().getDot();
            originalMark = editor.getCaret().getMark();
            undoCheckpoint = sessionUndo.checkpoint();
        }

        void replace(String updatedText) {
            requireActive();
            if (!documentText().equals(originalText)) throw new IllegalStateException("The editor no longer matches the preview.");
            try {
                ((javax.swing.text.AbstractDocument) editor.getDocument()).replace(0, editor.getDocument().getLength(), updatedText, null);
                appliedText = updatedText;
                restoreSelection();
            } catch (BadLocationException error) { throw new IllegalStateException("Cannot apply refactoring to the editor.", error); }
        }

        void rollback() {
            requireActive();
            if (appliedText == null) return;
            if (!documentText().equals(appliedText)) throw new IllegalStateException("The editor changed during rollback; its newer text was preserved.");
            try {
                ((javax.swing.text.AbstractDocument) editor.getDocument()).replace(0, editor.getDocument().getLength(), originalText, null);
                sessionUndo.restore(undoCheckpoint);
                dirty = originalDirty;
                appliedText = null;
                restoreSelection();
            } catch (BadLocationException error) { throw new IllegalStateException("Cannot restore the editor after a refactoring failure.", error); }
        }

        private void restoreSelection() {
            int length = editor.getDocument().getLength();
            editor.setCaretPosition(Math.min(originalMark, length));
            editor.moveCaretPosition(Math.min(originalDot, length));
        }

        private void requireActive() {
            if (!SwingUtilities.isEventDispatchThread() || closed || !refactoring) throw new IllegalStateException("The refactoring lease is not active on the event thread.");
        }

        /** Restores editing and schedules the normal autosave for any pending text changes. */
        @Override public void close() {
            requireActive();
            closed = true;
            refactoring = false;
            editor.setEditable(true);
            autosaveTimer.stop();
            if (dirty) autosaveTimer.restart();
        }
    }

    SearchBar.DocumentReplacement replaceProjectText(Path path, String expected, String updated) {
        if (!path.toAbsolutePath().normalize().equals(currentPath)) return SearchBar.DocumentReplacement.NOT_OPEN;
        if (!editor.isEditable() || !editor.getText().equals(expected)) return SearchBar.DocumentReplacement.CONFLICT;
        autosaveTimer.stop();
        saveLock.lock();
        try { editSerial.incrementAndGet(); }
        finally { saveLock.unlock(); }
        int caret = editor.getCaretPosition();
        editor.setText(updated);
        editor.setCaretPosition(Math.min(caret, editor.getDocument().getLength()));
        return saveFileInternal() ? SearchBar.DocumentReplacement.REPLACED : SearchBar.DocumentReplacement.CONFLICT;
    }

    boolean prepareForFileMove() {
        autosaveTimer.stop();
        saveLock.lock();
        try { editSerial.incrementAndGet(); }
        finally { saveLock.unlock(); }
        boolean saved = !dirty || saveFileInternal();
        if (!saved) autosaveTimer.restart();
        return saved;
    }

    /** Points the editor at the file's new path after a move. */
    public void relocateFile(Path destination) {
        saveLock.lock();
        try {
            editSerial.incrementAndGet();
            currentPath = destination.toAbsolutePath().normalize();
        } finally { saveLock.unlock(); }
        settings.setFileFontSize(currentPath, fontSize);
        settings.setFileScrollPosition(currentPath, getScrollPosition());
        pathChanged.run();
    }

    public void setSelectionListener(Runnable listener) { selectionListener = listener == null ? () -> {} : listener; }
    void setMarkdownNavigation(java.util.function.Consumer<Path> callback) { markdown.setOpenFile(callback); }
    void selectDeclaration(int offset) { editor.setCaretPosition(Math.max(0, Math.min(offset, editor.getDocument().getLength()))); focusEditor(); }
    boolean isMarkdownDocument() { return extension().equalsIgnoreCase("md"); }

    /** Saves pending changes before closing; returns false when the editor must stay open. */
    public boolean saveOnClose() {
        autosaveTimer.stop();
        if (!dirty) return true;
        if (currentPath == null) {
            console.appendError("Cannot close edited content without a file path. Create a named file before closing this editor.\n");
                return false;
        }
        boolean saved = saveFileInternal();
        if (!saved && dirty && currentPath != null) autosaveTimer.restart();
        return saved;
    }

    private boolean saveFileInternal() {
        Path target = currentPath;
        if (target == null || isExecutableFile(target)) return true;
        try {
            saveLock.lock();
            try {
                if (refactoring) return false;
                writeAtomically(target, editor.getText());
            } finally { saveLock.unlock(); }
            dirty = false;
            if (isSettingsFile(target)) {
                settings.reloadFromDisk();
                settings.setSettingsEditing(true);
                pathChanged.run();
            } else {
                settings.setSettingsEditing(false);
                settings.setCurrentFile(target);
            }
            return true;
        } catch (IOException ex) {
            console.appendError("Save error: " + VulcanDialog.errorSummary(ex) + "\n");
            return false;
        }
    }

    private static void writeAtomically(Path target, String text) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("The file has no parent directory.");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".vulcan-save-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override public void removeNotify() {
        diagnostics.stop();
        runnerStateTimer.stop();
        if (zoomToastTimer != null) zoomToastTimer.stop();
        if (zoomToast != null) { zoomToast.dispose(); zoomToast = null; }
        presentationTimer.stop();
        autosaveTimer.stop();
        completionTimer.stop();
        hideCompletionPopup();
        super.removeNotify();
    }

    @Override public void addNotify() {
        super.addNotify();
        diagnostics.changed();
        if (isShowing()) runnerStateTimer.start();
        presentationTimer.restart();
    }

    public boolean isDirty() { return dirty; }

    void discardUnsavedChanges() {
        saveLock.lock();
        try { editSerial.incrementAndGet(); dirty = false; }
        finally { saveLock.unlock(); }
        autosaveTimer.stop();
    }

    /** Closes the current file and stops its background work. */
    public void closeFile() {
        entryEligibility.stop();
        diagnostics.stop();
        runner.close();
        runFeedbackTimer.stop();
        runFeedbackActive = false;
        autosaveTimer.stop();
        completionTimer.stop();
        hideCompletionPopup();
        boolean settingsFile = isSettingsFile(currentPath);
        editSerial.incrementAndGet();
        if (settingsFile) settings.setSettingsEditing(false);
        currentPath = null;
        sessionUndo.clear();
        loading = true;
        try {
            editor.setText("");
            editor.setEditable(false);
            editor.setCaretPosition(0);
        } finally {
            loading = false;
        }
        dirty = false;
        updateRunDebugButtons();
        pathChanged.run();
    }

    /** Runs or stops the current program. */
    public void performRun() {
        if (!canRunCurrentFile()) return;
        runListener.run();
        console.setInputHandler(runner::submitInput);
        console.setInputAvailability(runner::isRunning);
        runner.stop();
        console.clear();
        runFeedbackActive = true;
        runFeedbackTimer.restart();
        updateRunDebugButtons();
        RunningPrograms.flash(runButton);
        if (extension().equalsIgnoreCase("asm") || extension().equalsIgnoreCase("c") || BuildRun.is(currentPath)) {
            saveFile();
            runner.runSource(editor.getText(), console::append, console::appendByte, this::refreshRunDebugButtonsOnEdt, currentPath);
        } else {
            runner.runBinary(currentPath, console::append, console::appendByte, this::refreshRunDebugButtonsOnEdt, currentPath);
        }
        updateRunDebugButtons();
    }

    public boolean isProgramExecuting() { return runner.isExecuting(); }
    public boolean isProgramRunning() { return runner.isRunning(); }

    public void stopRunning() { runner.stop(); updateRunDebugButtons(); }

    void closeRunner() { runner.close(); }

    /** Reports whether the current file can be run. */
    public boolean canRunCurrentFile() {
        if (currentPath == null) return false;
        if (!extension().equalsIgnoreCase("c")) return ProgramEligibility.canRun(currentPath, documentText());
        java.util.List<Path> includes = java.util.List.of(currentPath.getParent(),settings.getCurrentDirectory().resolve("src/stdlib/include"),Path.of("src/stdlib/include"));
        return entryEligibility.request(currentPath, documentText(), includes, this::updateRunDebugButtons);
    }

    /** Reports whether the current file can be debugged. */
    public boolean canDebugCurrentFile() {
        return !BuildRun.is(currentPath) && canRunCurrentFile();
    }

    private void restoreSavedScrollPosition(Path file) {
        Point saved = settings.getFileScrollPosition(file);
        if (saved == null) return;
        Timer restore = new Timer(100, event -> {
            if (!file.equals(currentPath)) return;
            JViewport viewport = scrollPane.getViewport();
            Dimension view = viewport.getViewSize();
            Dimension extent = viewport.getExtentSize();
            int x = Math.min(saved.x, Math.max(0, view.width - extent.width));
            int y = Math.min(saved.y, Math.max(0, view.height - extent.height));
            viewport.setViewPosition(new Point(x, y));
        });
        restore.setRepeats(false);
        restore.start();
    }

    /** Returns the current viewport position. */
    public Point getScrollPosition() {
        return new Point(scrollPane.getViewport().getViewPosition());
    }

    public Path getCurrentPath() { return currentPath; }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        applyEditorFont();
        editor.setBackground(settings.getColor("editorBackground"));
        editor.setForeground(settings.getColor("editorText"));
        editor.setCaretColor(settings.getColor("editorText"));
        scrollPane.getViewport().setBackground(settings.getColor("editorBackground"));
        lineNumbers.refreshTheme();
        if (isMarkdownDocument()) markdown.render();
        applyingStyles = true;
        try { SyntaxHighlighter.apply(editor, extension(), settings.isSyntaxHighlighting(), settings); }
        finally { applyingStyles = false; }
        lastRunState = null;
        updateRunDebugButtons();
        repaint();
    }

    /** Invokes a named editor action such as cut, copy, or undo. */
    public void performAction(String actionName) {
        Action action = editor.getActionMap().get(actionName);
        if (action != null) action.actionPerformed(new java.awt.event.ActionEvent(editor,
                java.awt.event.ActionEvent.ACTION_PERFORMED, actionName));
    }

    public void focusEditor() { editor.requestFocusInWindow(); }

    public void paste() { performAction("vulcanPaste"); }

    private void updateRunDebugButtons() {
        boolean canRun = canRunCurrentFile();
        boolean running = runner.isRunning();
        if (runButton.isVisible() != canRun) runButton.setVisible(canRun);
        boolean canDebug = canDebugCurrentFile();
        if (debugButton.isVisible() != canDebug) debugButton.setVisible(canDebug);
        boolean canStop = running;
        if (stopButton.isVisible() != canStop) stopButton.setVisible(canStop);
        boolean showRunningFeedback = running || runFeedbackActive;
        if (lastRunState == null || lastRunState != showRunningFeedback) {
            runButton.setIcon(Icons.vector(showRunningFeedback ? "running" : "run", 18,
                    new Color(47, 184, 92)));
            lastRunState = showRunningFeedback;
        }
        runButton.setToolTipText(running ? "Rerun" : runFeedbackActive ? "Starting…" : "Run");
    }

    private void refreshRunDebugButtonsOnEdt() {
        Runnable completed=()->{runFeedbackTimer.stop();runFeedbackActive=false;updateRunDebugButtons();};
        if (SwingUtilities.isEventDispatchThread()) completed.run();
        else SwingUtilities.invokeLater(completed);
    }

    private void showEditorIfLoaded() {
        editor.setBackground(settings.getColor("editorBackground"));
    }

}
