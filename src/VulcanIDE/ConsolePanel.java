package VulcanIDE;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Program console: buffered output, colored diagnostics, and line input for running programs. */
public class ConsolePanel extends JPanel {
    private static final int MAX_CONSOLE_BYTES = 32_768;
    private static final int MAX_PENDING_BYTES = 8_192;
    private static final int DRAIN_BATCH_BYTES = 4_096;
    private static final int OUTPUT_REFRESH_INTERVAL_MS = 33;
    private final ConsoleOutputRate outputRate = new ConsoleOutputRate();
    private final SettingsManager settings;
    private final String fontKey;
    private final JTextPane output;
    private final JComboBox<String> encoding;
    private final JLabel titleLabel = new JLabel();
    private String buildMessage;
    public void setBuildStatus(String text) { Runnable update = () -> { buildMessage = text == null || text.isBlank() ? null : text; refreshView(); }; if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update); }
    private java.util.function.Predicate<String> inputHandler = text -> false;
    private java.util.function.BooleanSupplier inputAvailable = () -> false;
    private final StringBuilder pendingInput = new StringBuilder();

    private final List<Integer> bytes = new ArrayList<>();
    private final List<int[]> errorRanges = new ArrayList<>();
    private final Deque<Integer> pendingBytes = new ArrayDeque<>();
    private final AtomicBoolean outputDrainScheduled = new AtomicBoolean();
    private final AtomicLong outputGeneration = new AtomicLong();
    private final AtomicLong pendingClearGeneration = new AtomicLong();
    private final Timer outputDrainTimer;
    private String endMessage;
    private boolean rendering;
    private boolean asciiLockedByError;
    private long droppedPendingBytes;
    private boolean truncationNoticeAdded;

    /** Creates a console using the default console font size. */
    public ConsolePanel(SettingsManager settings) {
        this(settings, "editorConsole");
    }

    /** Creates a console whose font size is stored under fontKey. */
    public ConsolePanel(SettingsManager settings, String fontKey) {
        this.settings = settings;

        this.fontKey = fontKey == null ? "editorConsole" : fontKey;
        outputDrainTimer = new Timer(OUTPUT_REFRESH_INTERVAL_MS, e -> drainPendingOutput());
        outputDrainTimer.setCoalesce(true);
        setLayout(new BorderLayout());
        setBorder(null);
        output = new JTextPane();
        output.setEditable(true);
        Theme.preserveEditorFont(output);
        output.setFont(new Font(settings.getEditorFont(), Font.PLAIN, settings.getFontSize(this.fontKey)));
        output.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        output.setBackground(settings.getColor("editorBackground"));
        output.setForeground(settings.getColor("editorText"));
        TextContextMenu.install(output);
        output.addMouseWheelListener(event -> {
            if (!event.isControlDown()) {SmoothScroll.forward(output,event);return;}
            settings.setFontSize(fontKey, Math.max(8, Math.min(48,
                    settings.getFontSize(fontKey) - Integer.signum(event.getWheelRotation()))));
            settings.save();
            refreshTheme();
            event.consume();
        });

        encoding = new JComboBox<>(new String[]{"ASCII", "Hex", "Decimal"});
        encoding.addActionListener(e -> refreshView());
        JPanel controls = new JPanel(new BorderLayout(5, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 5));
        controls.add(titleLabel, BorderLayout.WEST);
        JPanel outputControls=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));
        JSpinner rate=new JSpinner(new SpinnerNumberModel(256,0,16384,64));rate.setToolTipText("Sustained output KiB/s after first 100 lines; 0 = unlimited");rate.addChangeListener(e->outputRate.setKiBPerSecond(((Number)rate.getValue()).intValue()));
        outputControls.add(new JLabel("KiB/s"));outputControls.add(rate);outputControls.add(encoding);controls.add(outputControls,BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(output);
        scroll.setBorder(null);
        SmoothScroll.install(scroll);
        Theme.styleScrollBar(scroll.getVerticalScrollBar(), settings);
        Theme.styleScrollBar(scroll.getHorizontalScrollBar(), settings);
        add(controls, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        installConsoleInput();
    }

    private void installConsoleInput() {
        if (!(output.getDocument() instanceof AbstractDocument document)) return;
        document.setDocumentFilter(new DocumentFilter() {
            private int outputEnd() { return Math.max(0, output.getDocument().getLength() - pendingInput.length()); }
            private boolean writable(int offset, int length) {
                if (rendering) return true;
                if (inputAvailable.getAsBoolean() && offset >= outputEnd()
                        && offset + length <= output.getDocument().getLength()) return true;
                showReadOnlyNotice();
                return false;
            }
            @Override public void insertString(FilterBypass bypass, int offset, String string, AttributeSet attributes) throws BadLocationException {
                if (rendering) { super.insertString(bypass, offset, string, attributes); return; }
                if (string == null || string.indexOf('\n') >= 0 || !writable(offset, 0)) return;
                int inputOffset = offset - outputEnd();
                super.insertString(bypass, offset, string, attributes);
                pendingInput.insert(Math.max(0, Math.min(inputOffset, pendingInput.length())), string);
            }
            @Override public void remove(FilterBypass bypass, int offset, int length) throws BadLocationException {
                if (rendering) { super.remove(bypass, offset, length); return; }
                if (!writable(offset, length) || offset + length > output.getDocument().getLength()) return;
                int inputOffset = offset - outputEnd();
                super.remove(bypass, offset, length);
                if (inputOffset >= 0 && inputOffset + length <= pendingInput.length()) pendingInput.delete(inputOffset, inputOffset + length);
            }
            @Override public void replace(FilterBypass bypass, int offset, int length, String value, AttributeSet attributes) throws BadLocationException {
                if (rendering) { super.replace(bypass, offset, length, value, attributes); return; }
                if (value != null && value.indexOf('\n') >= 0) { showReadOnlyNotice(); return; }
                if (!writable(offset, length) || offset + length > output.getDocument().getLength()) return;
                int inputOffset = offset - outputEnd();
                super.replace(bypass, offset, length, value, attributes);
                if (inputOffset >= 0 && inputOffset + length <= pendingInput.length()) {
                    pendingInput.replace(inputOffset, inputOffset + length, value == null ? "" : value);
                }
            }
        });
        InputMap input = output.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = output.getActionMap();
        input.put(KeyStroke.getKeyStroke("ENTER"), "vulcan-console-submit");
        actions.put("vulcan-console-submit", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!inputAvailable.getAsBoolean() || !inputHandler.test(pendingInput.toString())) {
                    showReadOnlyNotice();
                    return;
                }
                for (int i = 0; i < pendingInput.length(); i++) bytes.add((int) pendingInput.charAt(i) & 0xFF);
                bytes.add(10);
                pendingInput.setLength(0);
                trimOutputBuffer();
                refreshView();
            }
        });
    }

    /** Sets the receiver of submitted input lines; it returns false to reject a line. */
    public void setInputHandler(java.util.function.Predicate<String> handler) {
        inputHandler = handler == null ? text -> false : handler;
    }

    /** Sets the predicate that decides whether input is currently accepted. */
    public void setInputAvailability(java.util.function.BooleanSupplier availability) {
        inputAvailable = availability == null ? () -> false : availability;
    }

    private void showReadOnlyNotice() {
        if (!output.isShowing()) return;
        JWindow notice = new JWindow(SwingUtilities.getWindowAncestor(output));
        JLabel label = new JLabel("read-only");
        label.setOpaque(true);
        label.setBackground(settings.getColor("panelBackground"));
        label.setForeground(settings.getColor("text"));
        label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(settings.getColor("splitDivider")),
                BorderFactory.createEmptyBorder(3, 7, 3, 7)));
        notice.add(label);
        notice.pack();
        try {
            java.awt.geom.Rectangle2D caretShape = output.modelToView2D(output.getCaretPosition());
            if (caretShape == null) { notice.dispose(); return; }
            Rectangle caret = caretShape.getBounds();
            Point point = new Point(caret.x, caret.y + caret.height);
            SwingUtilities.convertPointToScreen(point, output);
            notice.setLocation(point);
            notice.setAlwaysOnTop(true);
            notice.setVisible(true);
            Timer timer = new Timer(700, event -> notice.dispose());
            timer.setRepeats(false);
            timer.start();
        } catch (BadLocationException ignored) { notice.dispose(); }
    }

    public void setTitle(String title) { titleLabel.setText(title); }

    /** Appends one program output byte. */
    public void appendByte(int value) {
        outputRate.accept(value & 0xFF);
        synchronized (pendingBytes) {
            if (pendingBytes.size() >= MAX_PENDING_BYTES) {
                pendingBytes.removeFirst();
                droppedPendingBytes++;
            }
            pendingBytes.addLast(value & 0xFF);
        }
        scheduleOutputDrain();
    }

    public void append(String text) { appendInternal(text, false); }

    public void appendError(String text) { appendInternal(text, true); }

    private void appendInternal(String text, boolean error) {
        if (text == null || text.isEmpty()) return;
        if(text.startsWith("\nProgram ended")){appendProgramEnded(text.strip());return;}
        long generation = outputGeneration.get();
        SwingUtilities.invokeLater(() -> {
            if (generation != outputGeneration.get()) return;
            int start = bytes.size();
            for (int i = 0; i < text.length(); i++) bytes.add((int) text.charAt(i));

            if (error || text.contains("\u001B[31m") || text.contains("\u001B[91m")) errorRanges.add(new int[]{start, bytes.size()});
            if (error || text.contains("\u001B[31m") || text.contains("\u001B[91m")) lockEncodingToAscii();
            trimOutputBuffer();
            refreshView();
        });
    }

    public void appendProgramEnded(String message){long generation=outputGeneration.get();Runnable update=()->{if(generation!=outputGeneration.get())return;drainPendingOutput();endMessage=message;buildMessage=null;refreshView();};if(SwingUtilities.isEventDispatchThread())update.run();else SwingUtilities.invokeLater(update);}

    /** Removes all output and pending input. */
    public void clear() {
        outputRate.reset();
        long generation;
        synchronized (pendingBytes) {
            generation = outputGeneration.incrementAndGet();
            pendingClearGeneration.set(generation);
            pendingBytes.clear();
            droppedPendingBytes = 0;
        }
        Runnable action = () -> {
            if (generation != outputGeneration.get()) return;
            outputDrainTimer.stop();
            boolean hasPendingOutput;
            synchronized (pendingBytes) {
                hasPendingOutput = !pendingBytes.isEmpty();
                outputDrainScheduled.set(hasPendingOutput);
            }
            bytes.clear();
            endMessage=null;
            buildMessage=null;
            pendingInput.setLength(0);
            errorRanges.clear();
            rendering = true;
            output.setText("");
            rendering = false;
            asciiLockedByError = false;
            truncationNoticeAdded = false;
            encoding.setSelectedItem("ASCII");
            encoding.setEnabled(true);
            encoding.setVisible(true);
            Container parent = encoding.getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
            if (pendingClearGeneration.compareAndSet(generation, 0) && hasPendingOutput) startOutputDrainTimer();
        };
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }

    private void scheduleOutputDrain() {
        if (!outputDrainScheduled.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            if (pendingClearGeneration.get() == 0) startOutputDrainTimer();
        });
    }

    private void startOutputDrainTimer() {
        if (outputDrainTimer.isRunning()) return;
        outputDrainTimer.setInitialDelay(outputRate.startup() ? 0 : OUTPUT_REFRESH_INTERVAL_MS);
        outputDrainTimer.start();
    }

    private void drainPendingOutput() {
        if (pendingClearGeneration.get() != 0) return;
        List<Integer> batch = new ArrayList<>(DRAIN_BATCH_BYTES);
        long dropped;
        synchronized (pendingBytes) {
            if (pendingClearGeneration.get() != 0) return;
            while (batch.size() < DRAIN_BATCH_BYTES && !pendingBytes.isEmpty()) batch.add(pendingBytes.removeFirst());
            dropped = droppedPendingBytes;
            droppedPendingBytes = 0;
        }
        if (dropped > 0 && !truncationNoticeAdded) {
            appendTextBytes("\n[console output truncated]\n");
            truncationNoticeAdded = true;
        }
        for (int value : batch) bytes.add(value);
        if (dropped > 0 || !batch.isEmpty()) {
            trimOutputBuffer();
            refreshView();
        }

        boolean hasMore;
        synchronized (pendingBytes) { hasMore = !pendingBytes.isEmpty(); }
        if (!hasMore) {
            outputDrainTimer.stop();
            outputDrainScheduled.set(false);
            synchronized (pendingBytes) { hasMore = !pendingBytes.isEmpty(); }
            if (hasMore) scheduleOutputDrain();
        }
    }

    private void appendTextBytes(String text) {
        for (int i = 0; i < text.length(); i++) bytes.add(text.charAt(i) & 0xFF);
    }

    private void trimOutputBuffer() {
        int excess = bytes.size() - MAX_CONSOLE_BYTES;
        if (excess <= 0) return;
        if (excess < bytes.size() && Character.isHighSurrogate((char) (int) bytes.get(excess - 1))
                && Character.isLowSurrogate((char) (int) bytes.get(excess))) excess++;
        bytes.subList(0, excess).clear();
        for (int i = errorRanges.size() - 1; i >= 0; i--) {
            int[] range = errorRanges.get(i);
            if (range[1] <= excess) errorRanges.remove(i);
            else {
                range[0] = Math.max(0, range[0] - excess);
                range[1] -= excess;
            }
        }
    }

    private void copySelectedOrAll() {
        String text = output.getSelectedText();
        if (text == null || text.isEmpty()) text = output.getText();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        titleLabel.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize("application")));
        titleLabel.setForeground(settings.getColor("text"));
        output.setBackground(settings.getColor("editorBackground"));
        output.setForeground(settings.getColor("editorText"));
        output.setFont(new Font(settings.getEditorFont(), Font.PLAIN, settings.getFontSize(fontKey)));
        refreshView();
        repaint();
    }

    private void lockEncodingToAscii() {
        if (asciiLockedByError) return;
        asciiLockedByError = true;
        encoding.setSelectedItem("ASCII");
        encoding.setEnabled(false);
        encoding.setVisible(false);
        Container parent = encoding.getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }
    private void refreshView() {
        if (rendering) return;
        rendering = true;
        try {
            String mode = (String) encoding.getSelectedItem();
            if ("Hex".equals(mode)) renderPlain(formatHex());
            else if ("Decimal".equals(mode)) renderPlain(formatDecimal());
            else renderAscii();
            if (buildMessage != null) appendLifecycleLine(buildMessage);
            if (endMessage != null) appendLifecycleLine(endMessage);
        } finally { rendering = false; }
    }

    private void appendLifecycleLine(String message) {
        StyledDocument document = output.getStyledDocument();
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, Color.WHITE);
        StyleConstants.setBold(style, true);
        try { document.insertString(document.getLength(), "\n" + message + "\n", style); }
        catch (BadLocationException ignored) { }
    }

    private String formatHex() {
        StringBuilder s = new StringBuilder();
        for (int b : bytes) { if (!s.isEmpty()) s.append(' ');s.append(Character.forDigit((b >>> 4)&15,16)).append(Character.forDigit(b&15,16)); }
        return s.toString().toUpperCase(java.util.Locale.ROOT);
    }
    private String formatDecimal() {
        StringBuilder s = new StringBuilder();
        for (int b : bytes) { if (!s.isEmpty()) s.append(' '); s.append(b); }
        return s.toString();
    }
    private void renderPlain(String text) {
        output.setText(text);
        appendPendingInput();
    }
    private void renderAscii() {
        StyledDocument doc = output.getStyledDocument();
        try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {}
        Style normal = baseStyle();
        Style ansi = normal;
        Style runStyle = null;
        Style errorStyle = null;
        StringBuilder run = new StringBuilder();
        int errorRangeIndex = 0;
        int i = 0;
        while (i < bytes.size()) {
            if (bytes.get(i) == 0x1B && i + 1 < bytes.size() && bytes.get(i + 1) == '[') {
                int end = i + 2; while (end < bytes.size() && bytes.get(end) != 'm') end++;
                if (end < bytes.size()) {
                    appendStyledRun(doc, run, runStyle);
                    runStyle = null;
                    ansi = styleForAnsi(bytes.subList(i + 2, end), normal);
                    i = end + 1;
                    continue;
                }
            }
            while (errorRangeIndex < errorRanges.size() && errorRanges.get(errorRangeIndex)[1] <= i) errorRangeIndex++;
            boolean isError = errorRangeIndex < errorRanges.size()
                    && i >= errorRanges.get(errorRangeIndex)[0] && i < errorRanges.get(errorRangeIndex)[1];
            Style nextStyle = ansi;
            if (isError) {
                if (errorStyle == null) errorStyle = createErrorStyle();
                nextStyle = errorStyle;
            }
            if (runStyle != nextStyle) {
                appendStyledRun(doc, run, runStyle);
                runStyle = nextStyle;
            }
            run.append((char) (int) bytes.get(i));
            if (bytes.get(i) == 0) run.setLength(run.length() - 1);
            i++;
        }
        appendStyledRun(doc, run, runStyle);
        appendPendingInput();
    }

    private void appendPendingInput() {
        if (pendingInput.isEmpty()) { output.setCaretPosition(output.getDocument().getLength()); return; }
        try {
            StyledDocument document = output.getStyledDocument();
            document.insertString(document.getLength(), pendingInput.toString(), baseStyle());
            output.setCaretPosition(document.getLength());
        } catch (BadLocationException ignored) { }
    }
    private void appendStyledRun(StyledDocument doc, StringBuilder run, Style style) {
        if (run.isEmpty()) return;
        try { doc.insertString(doc.getLength(), run.toString(), style); }
        catch (BadLocationException ignored) { }
        run.setLength(0);
    }
    private Style createErrorStyle() {
        Style error = output.addStyle("error", null);
        StyleConstants.setForeground(error, new Color(255, 90, 90));
        return error;
    }
    private Style styleForAnsi(List<Integer> codes, Style base) {
        if (codes.isEmpty()) return base;
        StringBuilder sequence = new StringBuilder(codes.size());
        for (int value : codes) sequence.append((char) (value & 0xFF));
        String[] parameters = sequence.toString().split(";", -1);
        Style style = output.addStyle("ansi-" + sequence, base);
        boolean changed = false;
        for (int i = 0; i < parameters.length; i++) {
            int code;
            try { code = parameters[i].isEmpty() ? 0 : Integer.parseInt(parameters[i]); }
            catch (NumberFormatException ignored) { continue; }
            if (code == 0) {
                StyleConstants.setForeground(style, settings.getColor("editorText"));
                StyleConstants.setBold(style, false);
                changed = false;
            } else if (code >= 30 && code <= 37) {
                StyleConstants.setForeground(style, ansiColor(code - 30));
                changed = true;
            } else if (code >= 90 && code <= 97) {
                StyleConstants.setForeground(style, ansiBrightColor(code - 90));
                changed = true;
            } else if (code == 39) {
                StyleConstants.setForeground(style, settings.getColor("editorText"));
                changed = true;
            } else if (code == 1) {
                StyleConstants.setBold(style, true);
                changed = true;
            } else if (code == 22) {
                StyleConstants.setBold(style, false);
                changed = true;
            } else if (code == 38 && i + 2 < parameters.length && "5".equals(parameters[i + 1])) {
                try { StyleConstants.setForeground(style, ansi256Color(Integer.parseInt(parameters[i + 2]))); changed = true; }
                catch (NumberFormatException ignored) { }
                i += 2;
            } else if (code == 38 && i + 4 < parameters.length && "2".equals(parameters[i + 1])) {
                try {
                    StyleConstants.setForeground(style, new Color(
                            clampColor(Integer.parseInt(parameters[i + 2])),
                            clampColor(Integer.parseInt(parameters[i + 3])),
                            clampColor(Integer.parseInt(parameters[i + 4]))));
                    changed = true;
                } catch (NumberFormatException ignored) { }
                i += 4;
            }
        }
        return changed ? style : base;
    }
    private static int clampColor(int value) { return Math.max(0, Math.min(255, value)); }
    private static Color ansi256Color(int value) {
        int index = clampColor(value);
        if (index < 16) {
            Color[] palette = {Color.BLACK, Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE, Color.MAGENTA, Color.CYAN, Color.WHITE,
                    Color.DARK_GRAY, Color.PINK, new Color(100, 255, 100), Color.ORANGE, new Color(100, 180, 255), Color.MAGENTA, Color.CYAN, Color.WHITE};
            return palette[index];
        }
        if (index < 232) {
            int cube = index - 16;
            return new Color(colorCube(cube / 36), colorCube((cube / 6) % 6), colorCube(cube % 6));
        }
        int gray = 8 + (index - 232) * 10;
        return new Color(gray, gray, gray);
    }
    private static int colorCube(int component) { return component == 0 ? 0 : 55 + component * 40; }
    private Color ansiColor(int n) { Color[] c={Color.BLACK,Color.RED,Color.GREEN,Color.YELLOW,Color.BLUE,Color.MAGENTA,Color.CYAN,Color.WHITE}; return c[n]; }
    private Color ansiBrightColor(int n) { Color[] c={Color.DARK_GRAY,Color.PINK,Color.GREEN,Color.ORANGE,Color.CYAN,Color.MAGENTA,Color.CYAN,Color.WHITE}; return c[n]; }
    private Style baseStyle() {
        Style s = output.addStyle("base", null);
        StyleConstants.setFontFamily(s, settings.getEditorFont());
        StyleConstants.setFontSize(s, settings.getFontSize(fontKey));
        StyleConstants.setForeground(s, settings.getColor("editorText"));
        return s;
    }
}
