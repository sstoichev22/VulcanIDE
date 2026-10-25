package VulcanIDE;

import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** Dimly highlights every other whole-word occurrence of the identifier currently selected in an editor. */
final class OccurrenceHighlighter {
    static final Color OCCURRENCE_COLOR = new Color(255, 255, 255, 34);
    private static final int MAXIMUM_OCCURRENCES = 2_000;

    private final JTextComponent editor;
    private final Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(OCCURRENCE_COLOR);
    private final List<Object> tags = new ArrayList<>();
    private final Timer refresh;

    private OccurrenceHighlighter(JTextComponent editor) {
        this.editor = editor;
        refresh = new Timer(120, event -> update());
        refresh.setRepeats(false);
        editor.addCaretListener(event -> refresh.restart());
    }

    /** Installs occurrence highlighting on the editor and returns the controller. */
    static OccurrenceHighlighter install(JTextComponent editor) {
        return new OccurrenceHighlighter(editor);
    }

    /** Recomputes highlights immediately for the current selection. */
    void update() {
        clear();
        String selected = editor.getSelectedText();
        if (selected == null || !isIdentifier(selected)) return;
        String text;
        try {
            text = editor.getDocument().getText(0, editor.getDocument().getLength());
        } catch (BadLocationException unavailable) {
            return;
        }
        int selectionStart = editor.getSelectionStart();
        if (!wholeWordAt(text, selectionStart, selected.length())) return;
        for (int offset : occurrences(text, selected)) {
            if (offset == selectionStart) continue;
            try {
                tags.add(editor.getHighlighter().addHighlight(offset, offset + selected.length(), painter));
            } catch (BadLocationException ignored) {
            }
        }
    }

    /** Removes all occurrence highlights. */
    void clear() {
        for (Object tag : tags) editor.getHighlighter().removeHighlight(tag);
        tags.clear();
    }

    /** Number of occurrence highlights currently painted. */
    int highlightCount() {
        return tags.size();
    }

    /** Returns the start offsets of whole-word matches of word in text, capped for very large files. */
    static List<Integer> occurrences(String text, String word) {
        List<Integer> result = new ArrayList<>();
        if (word.isEmpty()) return result;
        for (int index = text.indexOf(word); index >= 0 && result.size() < MAXIMUM_OCCURRENCES;
             index = text.indexOf(word, index + word.length())) {
            if (wholeWordAt(text, index, word.length())) result.add(index);
        }
        return result;
    }

    private static boolean wholeWordAt(String text, int start, int length) {
        boolean before = start == 0 || !Character.isJavaIdentifierPart(text.charAt(start - 1));
        boolean after = start + length >= text.length() || !Character.isJavaIdentifierPart(text.charAt(start + length));
        return before && after;
    }

    private static boolean isIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) return false;
        for (int index = 1; index < value.length(); index++)
            if (!Character.isJavaIdentifierPart(value.charAt(index))) return false;
        return true;
    }
}
