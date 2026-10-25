package VulcanIDE;

import C8080.Compiler;
import Intel8080.assembler.Assembler;
import Intel8080.cpu.Memory;
import javax.swing.*;
import javax.swing.text.Document;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Integrated terminal: built-in file commands, the Vulcan toolchain, and host shell commands. */
public class TerminalPanel extends JPanel {
    static final String STARTUP_BANNER = "██╗   ██╗██╗   ██╗██╗      ██████╗ █████╗ ███╗   ██╗\n"
            + "██║   ██║██║   ██║██║     ██╔════╝██╔══██╗████╗  ██║\n"
            + "██║   ██║██║   ██║██║     ██║     ███████║██╔██╗ ██║\n"
            + "╚██╗ ██╔╝██║   ██║██║     ██║     ██╔══██╗██║╚██╗██║\n"
            + " ╚████╔╝ ╚██████╔╝███████╗╚██████╗██║  ██║██║ ╚████║\n"
            + "  ╚═══╝   ╚═════╝ ╚══════╝ ╚═════╝╚═╝  ╚═╝╚═╝  ╚═══╝\n\n";
    private static final int STARTUP_BANNER_ROWS = 6;
    private static final Color TERMINAL_RED = new Color(245, 72, 72);
    private final SettingsManager settings;
    private final TerminalTextArea terminal;
    private final ProgramRunner runner = new ProgramRunner();
    private final TerminalHistory commandHistory = new TerminalHistory();
    private final List<String> history = new ArrayList<>(commandHistory.load());
    private final ExecutorService processExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Vulcan-Terminal");
        t.setDaemon(true);
        return t;
    });
    private final Object outputLock = new Object();
    private final StringBuilder pendingOutput = new StringBuilder();
    private final AtomicLong commandGeneration = new AtomicLong();
    private boolean outputFlushScheduled;
    private final Timer outputTimer = new Timer(33, event -> flushPendingOutput());
    private static final int MAX_OUTPUT_CHARS = 65_536;

    private final JLabel buildActivity=new JLabel();
    private int spinnerStep;
    private String buildPhase="";
    private final Timer spinner=new Timer(100,e->{char[] frames={'-','/','|','\\'};buildActivity.setText(frames[spinnerStep++%4]+" "+buildPhase);});
    private void buildPhase(String phase){SwingUtilities.invokeLater(()->buildPhase=phase);}
    private void runBuild(Runnable action,String phase){commandRunning=true;terminal.setEditable(false);buildPhase=phase;buildActivity.setVisible(true);spinner.start();processExecutor.submit(()->{try{action.run();}catch(Exception error){appendFailure(formatError(error)+"\n");}finally{finishCommand();}});}
    private int historyIndex;
    private int promptStart;
    private int promptPathStart;
    private Path workingDirectory;
    private volatile Process activeProcess;
    private volatile boolean closed;
    private Runnable refreshFiles = () -> {};
    private java.util.function.Consumer<Path> deletionListener = path -> {};
    private java.util.function.Consumer<Path> beforeDeletionListener = path -> {};
    private java.util.function.BiConsumer<Path, Path> moveListener = (from, to) -> {};
    private java.util.function.Predicate<Path> beforeMoveListener = path -> true;
    public void setBeforeDeletionListener(java.util.function.Consumer<Path> listener) { beforeDeletionListener=listener==null?path->{}:listener; }
    private boolean commandRunning;
    private boolean changingTranscript;
    private List<TerminalCommands.Stage> commandStages = List.of();
    private int stageIndex;
    private volatile int lastExitCode;
    private String pipelineInput;
    private volatile boolean captureOutput;
    private volatile boolean pipeOutputOverflowed;
    private final StringBuilder pipelineOutput = new StringBuilder();
    private static final int MAX_PIPE_CHARS = 1_048_576;

    /** Creates a terminal in the saved working directory. */
    public TerminalPanel(SettingsManager settings) {
        this.settings = settings;
        outputTimer.setRepeats(false);
        setLayout(new BorderLayout());
        workingDirectory = settings.getTerminalWorkingDirectory();
        if (!Files.isDirectory(workingDirectory)) workingDirectory = settings.getCurrentDirectory();

        historyIndex = history.size();
        terminal = new TerminalTextArea();
        ((AbstractDocument) terminal.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes) throws BadLocationException {
                replace(bypass, offset, 0, text, attributes);
            }
            @Override public void remove(FilterBypass bypass, int offset, int length) throws BadLocationException {
                replace(bypass, offset, length, "", null);
            }
            @Override public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes) throws BadLocationException {
                if (changingTranscript) { bypass.replace(offset, length, text, attributes); return; }
                if (commandRunning) return;
                int start = Math.max(promptStart, offset);
                int end = Math.max(start, offset + length);
                bypass.replace(start, end - start, text, attributes);
                if (offset < promptStart) terminal.setCaretPosition(start + (text == null ? 0 : text.length()));
            }
        });
        terminal.setEditable(true);
        terminal.setLineWrap(false);
        terminal.setFont(new Font(settings.getEditorFont(), Font.PLAIN, settings.getFontSize("terminal")));
        Theme.preserveEditorFont(terminal);
        terminal.setMargin(new Insets(7, 9, 7, 9));
        terminal.setBorder(null);
        terminal.setTabSize(4);
        TextContextMenu.install(terminal);
        terminal.addMouseWheelListener(event -> {
            if (!event.isControlDown()) {
                JScrollPane parent = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, terminal);
                if (parent != null) {
                    Point point = SwingUtilities.convertPoint(terminal, event.getPoint(), parent);
                    parent.dispatchEvent(new java.awt.event.MouseWheelEvent(parent, event.getID(), event.getWhen(), event.getModifiersEx(),
                            point.x, point.y, event.getXOnScreen(), event.getYOnScreen(), event.getClickCount(), event.isPopupTrigger(),
                            event.getScrollType(), event.getScrollAmount(), event.getWheelRotation(), event.getPreciseWheelRotation()));
                    event.consume();
                }
                return;
            }
            settings.setFontSize("terminal", Math.max(8, Math.min(48,
                    settings.getFontSize("terminal") - Integer.signum(event.getWheelRotation()))));
            settings.save();
            refreshTheme();
            event.consume();
        });

        JScrollPane scroll = new JScrollPane(terminal);
        scroll.setBorder(null);
        // Resizing or reopening the panel keeps the prompt line in view while the caret is on it.
        java.awt.event.ComponentAdapter keepPromptVisible = new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { revealPrompt(); }
            @Override public void componentShown(java.awt.event.ComponentEvent event) { revealPrompt(); }
            private void revealPrompt() {
                if (terminal.getCaretPosition() >= promptStart)
                    SwingUtilities.invokeLater(() -> terminal.scrollRectToVisible(new Rectangle(0, terminal.getHeight() - 1, 1, 1)));
            }
        };
        scroll.getViewport().addComponentListener(keepPromptVisible);
        addComponentListener(keepPromptVisible);
        SmoothScroll.install(scroll);
        Theme.styleScrollBar(scroll.getVerticalScrollBar(), settings);
        Theme.styleScrollBar(scroll.getHorizontalScrollBar(), settings);
        add(scroll, BorderLayout.CENTER);
        buildActivity.setVisible(false);add(buildActivity,BorderLayout.SOUTH);

        InputMap input = terminal.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = terminal.getActionMap();
        input.put(KeyStroke.getKeyStroke("TAB"), "vulcan-complete");
        actions.put("vulcan-complete", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { autocomplete(); }
        });
        input.put(KeyStroke.getKeyStroke("ENTER"), "vulcan-enter");
        actions.put("vulcan-enter", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { executeCommand(); }
        });
        input.put(KeyStroke.getKeyStroke("UP"), "vulcan-up");
        actions.put("vulcan-up", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { showPreviousHistory(); }
        });
        input.put(KeyStroke.getKeyStroke("DOWN"), "vulcan-down");
        actions.put("vulcan-down", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { showNextHistory(); }
        });
        input.put(KeyStroke.getKeyStroke("HOME"), "vulcan-home");
        actions.put("vulcan-home", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { terminal.setCaretPosition(promptStart); }
        });
        input.put(KeyStroke.getKeyStroke("LEFT"), "vulcan-left");
        actions.put("vulcan-left", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (terminal.getCaretPosition() > promptStart) terminal.setCaretPosition(terminal.getCaretPosition() - 1);
            }
        });
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "vulcan-bs");
        terminal.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "vulcan-bs");
        terminal.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE && e.getModifiersEx() == 0 &&
                        terminal.getSelectionStart() == terminal.getSelectionEnd() &&
                        terminal.getCaretPosition() <= promptStart) e.consume();
            }
        });
        actions.put("vulcan-bs", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (commandRunning) return;
                if (terminal.getSelectionStart() != terminal.getSelectionEnd()) { terminal.replaceSelection(""); return; }
                int caret = terminal.getCaretPosition();
                if (caret > promptStart) {
                    try { terminal.getDocument().remove(caret - 1, 1); }
                    catch (Exception ignored) { }
                }
            }
        });
        input.put(KeyStroke.getKeyStroke("DELETE"), "vulcan-del");
        actions.put("vulcan-del", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (commandRunning) return;
                if (terminal.getSelectionStart() != terminal.getSelectionEnd()) { terminal.replaceSelection(""); return; }
                int caret = terminal.getCaretPosition();
                if (caret >= promptStart && caret < terminal.getDocument().getLength()) {
                    try { terminal.getDocument().remove(caret, 1); }
                    catch (Exception ignored) { }
                }
            }
        });

        terminal.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { enforceCommandArea(e); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { enforceCommandArea(e); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });
        appendTerminal(STARTUP_BANNER);
        terminal.markBanner(0, STARTUP_BANNER.length(), terminal.getFont());
        terminal.markRed(0, terminal.getDocument().getLength());
        appendPrompt();
    }

    private void enforceCommandArea(javax.swing.event.DocumentEvent event) {
        if (commandRunning || event.getOffset() >= promptStart) return;
        SwingUtilities.invokeLater(() -> {
            try {
                if (terminal.getCaretPosition() < promptStart) terminal.setCaretPosition(promptStart);
            } catch (Exception ignored) { }
        });
    }

    public void setRefreshListener(Runnable listener) { refreshFiles = listener == null ? () -> {} : listener; }

    public void setDeletionListener(java.util.function.Consumer<Path> listener) { deletionListener = listener == null ? path -> {} : listener; }
    public void setMoveListener(java.util.function.BiConsumer<Path, Path> listener) { moveListener = listener == null ? (from, to) -> {} : listener; }
    public void setBeforeMoveListener(java.util.function.Predicate<Path> listener) { beforeMoveListener = listener == null ? path -> true : listener; }

    /** Terminates the shell's descendants so cancelled commands cannot orphan workers. */
    private static void destroyProcessTree(Process process) {
        try (var descendants = process.toHandle().descendants()) {
            descendants.forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
        }
        finally { process.destroyForcibly(); }
    }

    /** Stops the running command and its child processes. */
    public void stop() {
        commandGeneration.incrementAndGet();
        commandStages = List.of();
        captureOutput = false;
        lastExitCode = 130;
        runner.stop();
        Process process = activeProcess;
        if (process != null) destroyProcessTree(process);
    }

    /** Stops commands and releases terminal resources. */
    public void close() {
        closed = true;
        spinner.stop();
        stop();
        runner.close();
        processExecutor.shutdownNow();
        outputTimer.stop();
    }

    /** Changes to the folder selected in the file panel. */
    public void setWorkingDirectoryFromFilePanel(Path path) {
        if (path == null || !Files.isDirectory(path)) return;
        workingDirectory = path.toAbsolutePath().normalize();
        settings.setTerminalWorkingDirectory(workingDirectory);
        if (!commandRunning) SwingUtilities.invokeLater(() -> {
            if (closed || commandRunning) return;
            try {
                String typed = terminal.getDocument().getText(promptStart, terminal.getDocument().getLength() - promptStart);
                boolean previous = changingTranscript;
                changingTranscript = true;
                try { terminal.redRanges.removeIf(range -> range[0] >= promptPathStart);
                    terminal.getDocument().remove(promptPathStart, terminal.getDocument().getLength() - promptPathStart);
                    promptStart = promptPathStart; appendPrompt(); replaceCurrentCommand(typed);
                } finally { changingTranscript = previous; }
            } catch (BadLocationException ignored) { }
        });
    }

    /** Changes the working directory. */
    public void setWorkingDirectory(Path path) {
        if (path == null || !Files.isDirectory(path)) return;
        workingDirectory = path.toAbsolutePath().normalize();
        settings.setTerminalWorkingDirectory(workingDirectory);
        appendPromptAfterCommand();
        focusTerminal();
    }

    private void autocomplete() {
        if (commandRunning) return;
        try {
            int caret = terminal.getCaretPosition();
            if (caret < promptStart) return;
            String before = terminal.getDocument().getText(promptStart, caret - promptStart);
            TerminalCommands.Completion completion = TerminalCommands.complete(workingDirectory, before);
            if (completion == null) return;
            int start = promptStart + completion.start();
            terminal.getDocument().remove(start, caret - start);
            terminal.getDocument().insertString(start, completion.replacement(), null);
            terminal.setCaretPosition(start + completion.replacement().length());
        } catch (Exception ignored) { }
    }

    private void executeCommand() {
        if (commandRunning) return;
        terminal.setCaretPosition(terminal.getDocument().getLength());
        String command;
        try {
            Document doc = terminal.getDocument();
            command = doc.getText(promptStart, doc.getLength() - promptStart).trim();
        } catch (Exception e) { return; }

        appendTerminal("\n");
        if (command.isEmpty()) {
            finishCommand();
            return;
        }

        history.add(command);
        while (history.size() > 50) history.remove(0);
        commandHistory.save(history);
        historyIndex = history.size();
        try { commandStages = TerminalCommands.parse(command); }
        catch (IllegalArgumentException error) { appendFailure(error.getMessage() + "\n"); finishCommand(); return; }
        stageIndex = 0;
        pipelineInput = null;
        executeStage();
    }

    private void executeStage() {
        String command = commandStages.get(stageIndex).text();
        pipeOutputOverflowed = false;
        captureOutput = commandStages.get(stageIndex).pipeToNext();
        synchronized (pipelineOutput) { pipelineOutput.setLength(0); }
        lastExitCode = 0;
        List<String> args = tokenize(command);
        if (args.isEmpty()) {
            finishCommand();
            return;
        }

        String name = args.get(0).toLowerCase(Locale.ROOT);
        boolean asynchronous = false;
        try {
            if (pipelineInput != null && java.util.Set.of("vcc", "vasm", "vld", "vload", "build", "cd", "mkdir", "rm", "mv", "cp").contains(name))
                throw new IllegalArgumentException(name + " requires named files and does not accept pipe input.");
            switch (name) {
                case "clear", "cls" -> clearTerminalAndPrompt();
                case "pwd", "get-location" -> appendOutput(workingDirectory + "\n");
                case "cd", "set-location", "sl" -> changeDirectory(args);
                case "dir", "ls", "gci", "get-childitem" -> listDirectory(args);
                case "mkdir", "md", "ni", "new-item" -> newItem(args);
                case "rm", "del", "rmdir", "ri", "remove-item" -> removeItem(args);
                case "cp", "copy", "copy-item" -> copyOrMove(args, false);
                case "mv", "move", "move-item" -> copyOrMove(args, true);
                case "ren", "rename-item" -> renameItem(args);
                case "cat", "type", "gc", "get-content" -> cat(args);
                case "touch" -> touch(args);
                case "test-path" -> testPath(args);
                case "echo" -> echo(args);
                case "vasm" -> {runBuild(()->assembleFile(args),"Assembling "+(args.size()>1?args.get(1):""));asynchronous=true;}
                case "vcc" -> {runBuild(()->compileC(args),"Compiling "+(args.size()>1?args.get(1):""));asynchronous=true;}
                case "vld" -> {runBuild(()->linkObjects(args),"Linking");asynchronous=true;}
                case "build" -> {runBuild(()->buildRunFile(args),"Building");asynchronous=true;}
                case "vload" -> {
                    if (args.size() != 2) appendFailure("usage: vload program.exe\n");
                    else { Path executable = workingDirectory.resolve(stripQuotes(args.get(1))).normalize(); if (!Files.isRegularFile(executable)) appendFailure("file not found\n"); else { runBinary(executable); asynchronous = true; } }
                }
                case "help" -> appendOutput(args.size() > 1 ? commandHelp(args.get(1)) : HELP_TEXT);
                default -> {
                    Path local = findExecutable(args.get(0));
                    if (local != null) { runBinary(local); asynchronous = true; }
                    else { runShell(command, workingDirectory); asynchronous = true; }
                }
            }
        } catch (Exception e) {
            appendFailure(formatError(e) + "\n");
        }
        if (!asynchronous && !commandRunning) finishCommand();
        focusTerminal();
    }

    static final String HELP_TEXT = String.join("\n",
            "Vulcan terminal commands (combine with && and |):",
            "  cd <dir>              change the working directory",
            "  ls / pwd              list files / print the working directory",
            "  mkdir rm cp mv        create, delete, copy, and move files",
            "  cat touch echo        print, create, or echo text",
            "  vcc <file.c...> [-S|-c] [-O0|-O1] [-Wall] [-o out]   compile C to .asm/.o or link an .exe",
            "  vasm <file.asm...> [-o out.o]             assemble to Vulcan objects",
            "  vld <file.o...> [-o program.exe]          link objects into an executable",
            "  build [build.run] [-o program.exe]        compile and link every file listed in build.run",
            "  vload <program.exe>   run an executable in the emulator",
            "  ./<program>           run a program in the working directory",
            "  help <command>        show help for one command",
            "");

    /** Returns one-line help for a terminal command, or a hint when the command is unknown. */
    static String commandHelp(String command) {
        String name = command.toLowerCase(java.util.Locale.ROOT);
        for (String line : HELP_TEXT.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(name + " ") || trimmed.startsWith(name + "/") || trimmed.matches("(?i).*\\b" + java.util.regex.Pattern.quote(name) + "\\b.*") && !trimmed.startsWith("help") && !trimmed.startsWith("Vulcan"))
                return trimmed + "\n";
        }
        return "No help for '" + command + "'. Type help for the command list.\n";
    }

    private Path findExecutable(String command) {
        String target = stripQuotes(command);
        if (target.startsWith("./") || target.startsWith(".\\")) target = target.substring(2);
        Path exact = workingDirectory.resolve(target).normalize();
        if (Files.isRegularFile(exact) && exact.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) return exact;
        Path exe = exact.resolveSibling(exact.getFileName() + ".exe");
        if (Files.isRegularFile(exe)) return exe;
        return null;
    }

    private void runBinary(Path path) {
        if (pipelineInput != null) throw new IllegalArgumentException("Emulated programs do not expose a standard-input stream; pipe their output to a host command instead.");
        commandRunning = true;
        terminal.setEditable(false);
        runner.runBinary(path, text -> { if (text.contains("\u001B[31m")) appendFailure(text); else appendOutput(text); },
                value -> appendOutput(Character.toString((char) (value & 0xFF))), this::finishCommand);
    }

    private void assembleFile(List<String> args) {
        List<String> sources = new ArrayList<>(); String output = null;
        for (int i = 1; i < args.size(); i++) {
            if (args.get(i).equalsIgnoreCase("-o")) { if (++i >= args.size()) { appendFailure("usage: vasm file.asm [file.asm ...] [-o output.o]\n"); return; } output = stripQuotes(args.get(i)); }
            else sources.add(stripQuotes(args.get(i)));
        }
        if (sources.isEmpty() || (output != null && sources.size() != 1)) { appendFailure("usage: vasm file.asm [file.asm ...] [-o output.o]\n"); return; }
        for (String name : sources) try {
            Path source = workingDirectory.resolve(name).normalize(); if (!Files.isRegularFile(source)) throw new IllegalArgumentException("file not found: " + name);
            String extension = extensionOf(source);
            if (extension.equals("o") || extension.equals("exe")) {
                if (extension.equals("o")) VulcanObject.read(source); else VulcanExecutable.read(source);
                if (output != null) {
                    Path target = resolvePath(output);
                    if (!source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize()))
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                appendOutput("accepted " + source.getFileName() + "\n");
                continue;
            }
            if (!extension.equals("asm")) throw new IllegalArgumentException("vasm accepts .asm, .o and .exe inputs.");
            String assembly = Files.readString(source);
            Path object = workingDirectory.resolve(output == null ? replaceExtension(source.getFileName().toString(), ".o") : output).normalize();
            VulcanObject.assembleSource(source.toAbsolutePath().normalize().toString(), assembly).write(object);
            appendOutput("assembled " + source.getFileName() + " -> " + object.getFileName() + "\n");
        } catch (Exception e) { appendFailure("assembly error: " + formatError(e) + "\n"); return; }
    }

    private void compileC(List<String> args) {
        List<String> sources = new ArrayList<>(); List<Path> extraIncludes = new ArrayList<>(); String output = null; boolean assemblyOnly = false, compileOnly = false, warnings = false;
        int optimizationLevel = 1;
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equalsIgnoreCase("-S")) {
                assemblyOnly = true;
            }
            else if (arg.equals("-Wall")) warnings = true;
            else if (arg.equals("-O0")) optimizationLevel = 0;
            else if (arg.equals("-O1")) optimizationLevel = 1;
            else if (arg.equalsIgnoreCase("-c")) compileOnly = true;
            else if (arg.equalsIgnoreCase("-I")) { if (++i >= args.size()) { appendFailure("vcc: -I requires an include directory.\n"); return; } extraIncludes.add(resolvePath(stripQuotes(args.get(i)))); }
            else if (arg.startsWith("-I") && arg.length() > 2) extraIncludes.add(resolvePath(stripQuotes(arg.substring(2))));
            else if (arg.equalsIgnoreCase("-o")) { if (++i >= args.size()) { appendFailure("usage: vcc file.c [-S|-c] [-o output]\n"); return; } output = stripQuotes(args.get(i)); }
            else sources.add(stripQuotes(arg));
        }
        if (sources.isEmpty() || (output != null && sources.size() > 1 && (compileOnly || assemblyOnly))) { appendFailure("usage: vcc file.c [file.c ...] [-S|-c] [-o output]\n"); return; }
        List<VulcanObject> units = new ArrayList<>();
        boolean hasCSource = false;
        boolean hasCEntry = false;
        try {
            for (String name : sources) {
                Path source = workingDirectory.resolve(name).normalize(); if (!Files.isRegularFile(source)) throw new IllegalArgumentException("file not found: " + name);
                String ext = extensionOf(source);
                if (ext.equals("exe")) {
                    if (sources.size() != 1 || assemblyOnly || compileOnly) throw new IllegalArgumentException("An .exe is already linked; provide it alone without -S or -c.");
                    VulcanExecutable.read(source);
                    if (output != null && !source.toAbsolutePath().normalize().equals(resolvePath(output).toAbsolutePath().normalize()))
                        Files.copy(source, resolvePath(output), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    appendOutput("accepted " + source.getFileName() + "\n");
                    return;
                }
                if (ext.equals("o")) {
                    if (assemblyOnly) throw new IllegalArgumentException("An object cannot be converted back to assembly with -S.");
                    VulcanObject object = VulcanObject.read(source);
                    if (compileOnly) {
                        if (output != null) object.write(resolvePath(output));
                        appendOutput("accepted " + source.getFileName() + "\n");
                    } else units.add(object);
                    continue;
                }
                String assembly;
                List<Path> includes = new ArrayList<>();
                Path parent = source.toAbsolutePath().normalize().getParent();
                if (parent != null) includes.add(parent);
                includes.add(Path.of("src", "stdlib", "include"));
                includes.addAll(extraIncludes);
                if (ext.equals("c")) {
                    hasCSource = true;
                    Compiler compiler = new Compiler(includes);
                    compiler.setWarningsEnabled(warnings);
                    buildPhase("Compiling "+source.getFileName());
                    assembly = compiler.compile(Files.readString(source), source, optimizationLevel);
                    if (warnings) for (C8080.SourceDiagnostic warning : compiler.warnings()) appendOutput(warning.getMessage() + "\n");
                } else if (ext.equals("asm")) assembly = Files.readString(source);
                else throw new IllegalArgumentException("vcc accepts .c, .asm, .o and .exe inputs: " + name);
                if (assemblyOnly) {
                    Path target = workingDirectory.resolve(output == null ? replaceExtension(source.getFileName().toString(), ".asm") : output).normalize();
                    Files.writeString(target, assembly, StandardCharsets.UTF_8);
                } else if (compileOnly) {
                    Path target = workingDirectory.resolve(output == null ? replaceExtension(source.getFileName().toString(), ".o") : output).normalize();
                    VulcanObject.assembleSource(source.toAbsolutePath().normalize().toString(), assembly).write(target);
                    appendOutput("compiled " + source.getFileName() + " -> " + target.getFileName() + "\n");
                } else {
                    buildPhase("Assembling " + source.getFileName());
                    VulcanObject object = VulcanObject.assembleSource(source.toAbsolutePath().normalize().toString(), assembly);
                    units.add(object);
                    if (ext.equals("c") && "$start".equals(object.entrySymbol())) hasCEntry = true;
                }
            }
            if (assemblyOnly) {
                if (sources.size() == 1) {
                    String target = output == null ? replaceExtension(Path.of(sources.get(0)).getFileName().toString(), ".asm") : output;
                    appendOutput("compiled into " + Path.of(target).getFileName() + "\n");
                } else {
                    appendOutput("compiled " + sources.size() + " files into .asm outputs\n");
                }
            }
            if (!assemblyOnly && !compileOnly) {
                if (hasCSource && !hasCEntry) throw new IllegalArgumentException("undefined reference to main.");
                buildPhase("Linking");
                Path target = workingDirectory.resolve(output == null ? defaultProgramName(sources) + ".exe" : ensureExtension(output, ".exe")).normalize();
                VulcanLinker.link(units, target, List.of(Path.of("src", "stdlib", "include")));
                appendOutput("linked -> " + target.getFileName() + "\n");
            }
        } catch (Exception e) { appendFailure("vcc: " + formatError(e) + "\n"); }
    }

    /** build [build.run] [-o program.exe]: links every listed file; the default output is named after the folder. */
    private void buildRunFile(List<String> args) {
        String file = BuildRun.NAME, output = null;
        for (int i = 1; i < args.size(); i++) {
            if (args.get(i).equalsIgnoreCase("-o")) { if (++i >= args.size()) { appendFailure("usage: build [build.run] [-o program.exe]\n"); return; } output = ensureExtension(stripQuotes(args.get(i)), ".exe"); }
            else file = stripQuotes(args.get(i));
        }
        try {
            Path buildRun = resolvePath(file);
            if (Files.isDirectory(buildRun)) buildRun = buildRun.resolve(BuildRun.NAME);
            if (!BuildRun.is(buildRun) || !Files.isRegularFile(buildRun)) throw new IllegalArgumentException("build expects a build.run file: " + file);
            Path folder = buildRun.getParent();
            String name = folder.getFileName() == null ? "program" : folder.getFileName().toString();
            Path target = output == null ? folder.resolve(name + ".exe") : resolvePath(output);
            VulcanLinker.Result linked = ProgramRunner.linkBuildRun(buildRun, Files.readString(buildRun), target, this::buildPhase);
            for (String warning : linked.warnings()) appendOutput("Warning: " + warning + "\n");
            appendOutput("built -> " + target.getFileName() + "\n");
        } catch (Exception e) { appendFailure("build: " + formatError(e) + "\n"); }
    }

    private void linkObjects(List<String> args) {
        List<String> inputs = new ArrayList<>(); String output = "program.exe";
        for (int i = 1; i < args.size(); i++) {
            if (args.get(i).equalsIgnoreCase("-o")) { if (++i >= args.size()) { appendFailure("usage: vld file.o [file.o ...] [-o program.exe]\n"); return; } output = ensureExtension(stripQuotes(args.get(i)), ".exe"); }
            else inputs.add(stripQuotes(args.get(i)));
        }
        if (inputs.isEmpty()) { appendFailure("usage: vld file.o [file.o ...] [-o program.exe]\n"); return; }
        try {
            List<VulcanObject> objects = new ArrayList<>();
            for (String input : inputs) {
                Path path = workingDirectory.resolve(input).normalize();
                VulcanObject object;
                if (VulcanObject.isObject(path)) object = VulcanObject.read(path);
                else {
                    String legacy = Files.readString(path, StandardCharsets.UTF_8);
                    if (!legacy.startsWith("VOBJ1\n")) throw new IllegalArgumentException("invalid object file: " + input);
                    object = VulcanObject.assembleSource(path.getFileName().toString(), legacy.substring(6));
                }
                objects.add(object);
            }
            Path target = workingDirectory.resolve(output).normalize();
            VulcanLinker.link(objects, target, List.of(Path.of("src", "stdlib", "include")));
            appendOutput("linked -> " + target.getFileName() + "\n");
        } catch (Exception e) { appendFailure("link error: " + formatError(e) + "\n"); }
    }
    private String extensionOf(Path path) { String name = path.getFileName().toString(); int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private Path resolvePath(String value) { Path path = Path.of(value); return (path.isAbsolute() ? path : workingDirectory.resolve(path)).normalize(); }
    private String replaceExtension(String file, String extension) { int dot = file.lastIndexOf('.'); return (dot < 0 ? file : file.substring(0, dot)) + extension; }
    private String ensureExtension(String file, String extension) { return file.toLowerCase(Locale.ROOT).endsWith(extension) ? file : file + extension; }
    private String defaultProgramName(List<String> sources) { String first = Path.of(sources.get(0)).getFileName().toString(); return replaceExtension(first, ""); }

    private void changeDirectory(List<String> args) {
        String target = args.size() < 2 ? "" : stripQuotes(String.join(" ", args.subList(1, args.size()))).trim();
        if (target.equals("~")) target = System.getProperty("user.home");
        if (target.isEmpty()) target = System.getProperty("user.home");
        Path path = Path.of(target);
        if (!path.isAbsolute()) path = workingDirectory.resolve(path);
        path = path.normalize().toAbsolutePath();
        if (!Files.isDirectory(path)) { appendFailure("directory not found\n"); return; }
        workingDirectory = path;
        settings.setTerminalWorkingDirectory(workingDirectory);
    }

    private void listDirectory(List<String> args) {
        Path directory = workingDirectory;
        if (args.size() > 1) {
            Path requested = Path.of(stripQuotes(String.join(" ", args.subList(1, args.size()))));
            if (!requested.isAbsolute()) requested = workingDirectory.resolve(requested);
            directory = requested.normalize();
        }
        if (!Files.isDirectory(directory)) { appendFailure("directory not found\n"); return; }
        try (var stream = Files.list(directory)) {
            stream.sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .forEach(path -> appendOutput((Files.isDirectory(path) ? "d " : "  ") + path.getFileName() + "\n"));
        } catch (IOException e) { appendFailure("directory error: " + VulcanDialog.errorSummary(e) + "\n"); }
    }

    private void newItem(List<String> args) {
        boolean directory = args.get(0).equalsIgnoreCase("mkdir") || args.get(0).equalsIgnoreCase("md");
        String name = args.size() > 1 ? stripQuotes(args.get(args.size() - 1)) : "";
        if (name.isBlank()) return;
        Path path = workingDirectory.resolve(name).normalize();
        try { if (directory) Files.createDirectories(path); else { Files.createFile(path); BuildRun.created(path); } MainWindow.notifyFileChange(this,"Created " + path.getFileName()); }
        catch (Exception e) { appendFailure("create error: " + VulcanDialog.errorSummary(e) + "\n"); }
    }

    private void removeItem(List<String> args) {
        if (args.size() < 2) return;
        Path path = workingDirectory.resolve(stripQuotes(args.get(1))).normalize();
        try {
            beforeDeletionListener.accept(path.toAbsolutePath().normalize());
            ProjectFileOperations.deleteRecursively(path);
            deletionListener.accept(path.toAbsolutePath().normalize());
            refreshFiles.run();
        } catch (Exception e) { appendFailure("remove error: " + VulcanDialog.errorSummary(e) + "\n"); }
    }

    private void copyOrMove(List<String> args, boolean move) {
        if (args.size() < 3) return;
        Path source = workingDirectory.resolve(stripQuotes(args.get(1))).normalize();
        Path destination = workingDirectory.resolve(stripQuotes(args.get(2))).normalize();
        try {
            if (move) {
                if (!beforeMoveListener.test(source)) throw new IOException("An open file could not be saved before moving it.");
                Files.move(source, destination);
                moveListener.accept(source.toAbsolutePath().normalize(), destination.toAbsolutePath().normalize());
            }
            else ProjectFileOperations.copy(source, destination, false);
            refreshFiles.run();
        } catch (Exception e) { appendFailure((move ? "move" : "copy") + " error: " + VulcanDialog.errorSummary(e) + "\n"); }
    }

    private void renameItem(List<String> args) {
        if (args.size() < 3) return;
        Path source = workingDirectory.resolve(stripQuotes(args.get(1))).normalize();
        Path destination = workingDirectory.resolve(stripQuotes(args.get(2))).normalize();
        try {
            if (!beforeMoveListener.test(source)) throw new IOException("An open file could not be saved before renaming it.");
            Files.move(source, destination);
            moveListener.accept(source.toAbsolutePath().normalize(), destination.toAbsolutePath().normalize());
            refreshFiles.run();
        }
        catch (IOException e) { appendFailure("rename error: " + VulcanDialog.errorSummary(e) + "\n"); }
    }

    private void cat(List<String> args) {
        if (args.size() < 2) { if (pipelineInput != null) appendOutput(pipelineInput); else appendFailure("cat: provide a file or pipe input.\n"); return; }
        try { appendOutput(Files.readString(workingDirectory.resolve(stripQuotes(args.get(1))).normalize())); }
        catch (Exception e) { appendFailure("read error: " + VulcanDialog.errorSummary(e) + "\n"); }
    }

    private void touch(List<String> args) {
        if (args.size() < 2) return;
        Path path = workingDirectory.resolve(stripQuotes(args.get(1))).normalize();
        try { if (!Files.exists(path)) { Files.createFile(path); BuildRun.created(path); } else Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); }
        catch (IOException e) { appendFailure("touch error: " + VulcanDialog.errorSummary(e) + "\n"); }
    }

    private void testPath(List<String> args) {
        appendOutput(Boolean.toString(args.size() > 1 && Files.exists(workingDirectory.resolve(stripQuotes(args.get(1))).normalize())) + "\n");
    }

    private void echo(List<String> args) {
        if (args.size() <= 1) appendOutput("\n");
        else appendOutput(String.join(" ", args.subList(1, args.size())) + "\n");
    }

    private void runShell(String command, Path directory) {
        if (closed) throw new RejectedExecutionException("The terminal is closed.");
        commandRunning = true;
        terminal.setEditable(false);
        long generation = commandGeneration.incrementAndGet();
        try {
            processExecutor.submit(() -> {
                Process process = null;
                try {
                    if (closed || commandGeneration.get() != generation || Thread.currentThread().isInterrupted()) return;
                    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                    ProcessBuilder builder = windows
                            ? new ProcessBuilder("cmd.exe", "/d", "/s", "/c", command)
                            : new ProcessBuilder("/bin/sh", "-c", command);
                    builder.directory(directory.toFile());
                    builder.redirectErrorStream(true);
                    process = builder.start();
                    activeProcess = process;
                    if (closed || commandGeneration.get() != generation || Thread.currentThread().isInterrupted()) {
                        destroyProcessTree(process);
                        return;
                    }
                    final Process inputProcess = process;
                    final String inputText = pipelineInput;
                    processExecutor.submit(() -> {
                        try (var stdin = inputProcess.getOutputStream()) {
                            if (inputText != null) stdin.write(inputText.getBytes(StandardCharsets.UTF_8));
                        } catch (IOException ignored) {                                           }
                    });
                    StringBuilder buffer = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        char[] chars = new char[1024];
                        int count;
                        while ((count = reader.read(chars)) != -1) {
                            if (closed || commandGeneration.get() != generation) {
                                destroyProcessTree(process);
                                break;
                            }
                            buffer.append(chars, 0, count);
                            if (buffer.length() >= 1024) flushProcessOutput(buffer);
                        }
                    }
                    if (!buffer.isEmpty()) appendOutput(buffer.toString());
                    lastExitCode = process.waitFor();
                    if (pipeOutputOverflowed) lastExitCode = 1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (process != null) destroyProcessTree(process);
                } catch (Exception e) {
                    appendFailure("shell error: " + VulcanDialog.errorSummary(e) + "\n");
                } finally {
                    if (process != null && process.isAlive()) destroyProcessTree(process);
                    if (activeProcess == process) activeProcess = null;
                    finishCommand();
                }
            });
        } catch (RejectedExecutionException e) {
            commandRunning = false;
            terminal.setEditable(true);
            throw e;
        }
    }

    private void flushProcessOutput(StringBuilder buffer) {
        String text = buffer.toString();
        buffer.setLength(0);
        appendOutput(text);
    }

    private void clearTerminalAndPrompt() {
        changingTranscript = true;
        try { terminal.setText(""); }
        finally { changingTranscript = false; }
        terminal.clearRedRanges();
        promptStart = 0;
    }

    private void finishCommand() {
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            spinner.stop();buildActivity.setText("");buildActivity.setVisible(false);
            captureOutput = false;
            commandRunning = false;
            if (!commandStages.isEmpty() && stageIndex + 1 < commandStages.size()) {
                boolean piped = commandStages.get(stageIndex).pipeToNext();
                if (piped || lastExitCode == 0) {
                    synchronized (pipelineOutput) { pipelineInput = piped ? pipelineOutput.toString() : null; }
                    stageIndex++;
                    executeStage();
                    return;
                }
            }
            commandStages = List.of();
            pipelineInput = null;
            flushPendingOutput();
            terminal.setEditable(true);
            refreshFiles.run();
            appendPrompt();
            focusTerminal();
        });
    }

    private void appendFailure(String text) {
        lastExitCode = 1;
        boolean capture = captureOutput;
        captureOutput = false;
        appendOutput(text);
        captureOutput = capture;
    }

    private void appendOutput(String text) {
        if (text == null || text.isEmpty()) return;
        if (captureOutput) {
            boolean overflow = false;
            synchronized (pipelineOutput) {
                if (pipeOutputOverflowed) return;
                if (pipelineOutput.length() + text.length() > MAX_PIPE_CHARS) {
                    pipeOutputOverflowed = true;
                    overflow = true;
                } else pipelineOutput.append(text);
            }
            if (overflow) {
                appendFailure("Pipe output exceeds the 1 MiB safety limit.\n");
                commandStages = List.of();
                Process process = activeProcess;
                if (process != null) destroyProcessTree(process);
            }
            return;
        }
        synchronized (outputLock) {
            pendingOutput.append(text);
            if (pendingOutput.length() > MAX_OUTPUT_CHARS)
                pendingOutput.delete(0, pendingOutput.length() - MAX_OUTPUT_CHARS);
            if (outputFlushScheduled) return;
            outputFlushScheduled = true;
        }
        SwingUtilities.invokeLater(() -> { if (!closed) outputTimer.restart(); });
    }

    private void flushPendingOutput() {
        String text;
        synchronized (outputLock) {
            if (pendingOutput.isEmpty()) {
                outputFlushScheduled = false;
                return;
            }
            text = pendingOutput.toString();
            pendingOutput.setLength(0);
            outputFlushScheduled = false;
        }
        int oldLength = terminal.getDocument().getLength();
        int selectionStart = terminal.getSelectionStart();
        int selectionEnd = terminal.getSelectionEnd();
        boolean caretAtEnd = terminal.getCaretPosition() == oldLength;
        appendTerminal(text);
        int excess = terminal.getDocument().getLength() - MAX_OUTPUT_CHARS;
        if (excess > 0) {
            changingTranscript = true;
            try { terminal.getDocument().remove(0, excess); promptStart = Math.max(0, promptStart - excess); terminal.trimRedRanges(excess); }
            catch (javax.swing.text.BadLocationException ignored) { }
            finally { changingTranscript = false; }
        }
        if (caretAtEnd) terminal.setCaretPosition(terminal.getDocument().getLength());
        else {
            terminal.setCaretPosition(Math.min(selectionStart, terminal.getDocument().getLength()));
            terminal.moveCaretPosition(Math.min(selectionEnd, terminal.getDocument().getLength()));
        }
    }

    private void appendPromptAfterCommand() {
        SwingUtilities.invokeLater(() -> {
            if (terminal.getDocument().getLength() > promptStart && !terminal.getText().endsWith("\n")) appendTerminal("\n");
            appendPrompt();
        });
    }

    private void appendPrompt() {
        if (terminal.getDocument().getLength() > 0 && !terminal.getText().endsWith("\n")) appendTerminal("\n");
        String path = workingDirectory.toString();
        int pathStart = terminal.getDocument().getLength();
        promptPathStart = pathStart;
        appendTerminal(path + "\n> ");
        terminal.markRed(pathStart, pathStart + path.length());
        promptStart = terminal.getDocument().getLength();
        terminal.moveCaretPosition(promptStart);
        terminal.setCaretPosition(promptStart);
    }

    private void appendTerminal(String text) {
        boolean previous = changingTranscript;
        changingTranscript = true;
        try { terminal.getDocument().insertString(terminal.getDocument().getLength(), text, null); }
        catch (javax.swing.text.BadLocationException ignored) { }
        finally { changingTranscript = previous; }
    }

    private void showPreviousHistory() {
        if (commandRunning || history.isEmpty()) return;
        historyIndex = Math.max(0, historyIndex - 1);
        replaceCurrentCommand(history.get(historyIndex));
    }

    private void showNextHistory() {
        if (commandRunning || history.isEmpty()) return;
        historyIndex++;
        replaceCurrentCommand(historyIndex >= history.size() ? "" : history.get(historyIndex));
        if (historyIndex > history.size()) historyIndex = history.size();
    }

    private void replaceCurrentCommand(String command) {
        try {
            Document doc = terminal.getDocument();
            doc.remove(promptStart, doc.getLength() - promptStart);
            doc.insertString(doc.getLength(), command, null);
            terminal.setCaretPosition(doc.getLength());
        } catch (Exception ignored) { }
    }

    private void focusTerminal() {
        SwingUtilities.invokeLater(() -> {
            terminal.requestFocusInWindow();
            terminal.setCaretPosition(terminal.getDocument().getLength());
        });
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        // Reinstalling the UI on a theme change resets the caret to 0, which scrolls the prompt out of view.
        if (terminal.getCaretPosition() < promptStart) terminal.setCaretPosition(terminal.getDocument().getLength());
        terminal.setBackground(settings.getColor("editorBackground"));
        terminal.setForeground(settings.getColor("editorText"));
        terminal.setCaretColor(settings.getColor("editorText"));
        terminal.setFont(new Font(settings.getEditorFont(), Font.PLAIN, settings.getFontSize("terminal")));
        terminal.setBannerFont(terminal.getFont());
        repaint();
    }

    private String formatError(Exception e) { return VulcanDialog.errorSummary(e); }

    private static final class TerminalTextArea extends JTextArea {
        private final List<int[]> redRanges = new ArrayList<>();
        private int bannerStart = -1;
        private int bannerEnd = -1;
        private Font bannerFont;

        void markRed(int start, int end) { if (end > start) redRanges.add(new int[]{start, end}); repaint(); }
        void clearRedRanges() { redRanges.clear(); bannerStart = bannerEnd = -1; }
        void markBanner(int start, int end, Font font) { bannerStart = start; bannerEnd = end; bannerFont = font; }
        void setBannerFont(Font font) { bannerFont = font; repaint(); }
        void trimRedRanges(int removed) {
            for (int i = redRanges.size() - 1; i >= 0; i--) {
                int[] range = redRanges.get(i);
                if (range[1] <= removed) redRanges.remove(i);
                else { range[0] = Math.max(0, range[0] - removed); range[1] -= removed; }
            }
            if (bannerEnd <= removed || bannerStart < removed) bannerStart = bannerEnd = -1;
            else if (bannerStart >= 0) { bannerStart -= removed; bannerEnd -= removed; }
        }

        /** Repaints only the red output lines and banner rows that intersect the current clip. */
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setFont(getFont());
                FontMetrics metrics = g.getFontMetrics();
                Rectangle clip = g.getClipBounds();
                if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());
                javax.swing.text.Document document = getDocument();
                javax.swing.text.Element root = document.getDefaultRootElement();
                int visibleStart = root.getElement(root.getElementIndex(Math.max(0, viewToModel2D(new Point(0, clip.y))))).getStartOffset();
                int visibleEnd = root.getElement(root.getElementIndex(Math.max(0, viewToModel2D(new Point(clip.x + clip.width, clip.y + clip.height))))).getEndOffset();
                for (int[] range : redRanges) {
                    int from = Math.max(range[0], visibleStart);
                    int to = Math.min(Math.min(range[1], visibleEnd), document.getLength());
                    while (from < to) {
                        javax.swing.text.Element lineElement = root.getElement(root.getElementIndex(from));
                        int lineEnd = Math.min(to, lineElement.getEndOffset() - 1);
                        if (lineEnd > from) {
                            String line = document.getText(from, lineEnd - from);
                            java.awt.geom.Rectangle2D box = modelToView2D(from);
                            if (box != null) {
                                int x = (int) box.getX();
                                int y = (int) box.getY();
                                g.setColor(getBackground());
                                g.fillRect(x, y, metrics.stringWidth(line) + 2, metrics.getHeight());
                                g.setColor(TERMINAL_RED);
                                g.drawString(line, x, y + metrics.getAscent());
                            }
                        }
                        from = Math.max(lineEnd, from) + 1;
                    }
                }
                if (bannerStart >= 0 && bannerStart < visibleEnd && bannerEnd > visibleStart) paintBanner(g);
            } catch (Exception ignored) { }
            finally { g.dispose(); }
        }

        /** Paints the historical logo on fixed character columns despite proportional font metrics. */
        private void paintBanner(Graphics2D graphics) throws BadLocationException {
            if (bannerFont == null || bannerStart < 0 || bannerStart + STARTUP_BANNER.length() > getDocument().getLength()
                    || !getDocument().getText(bannerStart, STARTUP_BANNER.length()).equals(STARTUP_BANNER)) return;
            String[] rows = STARTUP_BANNER.split("\n", -1);
            FontMetrics logoMetrics = graphics.getFontMetrics(bannerFont);
            FontMetrics textMetrics = graphics.getFontMetrics(getFont());
            int cellWidth = logoCellWidth(logoMetrics);
            int maxColumns = java.util.Arrays.stream(rows).mapToInt(String::length).max().orElse(0);
            int rowStart = bannerStart;
            graphics.setFont(bannerFont);
            for (int row = 0; row < STARTUP_BANNER_ROWS; row++) {
                String line = rows[row];
                java.awt.geom.Rectangle2D bounds = modelToView2D(rowStart);
                if (bounds != null) {
                    int x = (int) Math.round(bounds.getX());
                    int y = (int) Math.round(bounds.getY());
                    int height = (int) Math.round(bounds.getHeight());
                    int coverWidth = Math.max(maxColumns * cellWidth, textMetrics.stringWidth(line) + 2);
                    graphics.setColor(getBackground());
                    graphics.fillRect(x, y, coverWidth, height);
                    graphics.setColor(TERMINAL_RED);
                    drawFixedCellLine(graphics, line, x, y + (height - logoMetrics.getHeight()) / 2 + logoMetrics.getAscent(), cellWidth);
                }
                rowStart += line.length() + 1;
            }
        }
    }

    /** Returns a cell wide enough for every glyph in the historical banner. */
    static int logoCellWidth(FontMetrics metrics) {
        int width = 1;
        for (int i = 0; i < STARTUP_BANNER.length(); i++) {
            char character = STARTUP_BANNER.charAt(i);
            if (character != '\n') width = Math.max(width, metrics.charWidth(character));
        }
        return width;
    }

    /** Draws each logo glyph at its column anchor instead of relying on proportional advances. */
    static void drawFixedCellLine(Graphics2D graphics, String line, int x, int baseline, int cellWidth) {
        for (int column = 0; column < line.length(); column++) {
            graphics.drawString(String.valueOf(line.charAt(column)), x + column * cellWidth, baseline);
        }
    }

    private String stripQuotes(String text) {
        return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")
                ? text.substring(1, text.length() - 1) : text;
    }

    private List<String> tokenize(String command) { return TerminalCommands.tokens(command); }
}
