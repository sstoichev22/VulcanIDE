package VulcanIDE;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Computes C indentation and masks comments and literals while preserving offsets. */
final class CIndentation {
    /** Source with comments and literals masked, and whether the caret lies in code. */
    record LexicalView(String code, boolean caretIsCode) {}
    private static final int NORMAL = 0;
    private static final int LINE_COMMENT = 1;
    private static final int BLOCK_COMMENT = 2;
    private static final int LITERAL = 3;

    private CIndentation() {}

    static String leading(String line) {
        int length = 0;
        while (length < line.length() && (line.charAt(length) == ' ' || line.charAt(length) == '\t')) length++;
        return line.substring(0, length);
    }

    static String code(String source) {
        return code(source, () -> false);
    }

    static String code(String source, BooleanSupplier cancelled) {
        return lexicalView(source, -1, cancelled).code();
    }

    static LexicalView lexicalView(String source, int caret) {
        return lexicalView(source, caret, () -> false);
    }

    private static LexicalView lexicalView(String source, int caret, BooleanSupplier cancelled) {
        StringBuilder output = new StringBuilder(source);
        int state = NORMAL;
        char quote = 0;
        boolean escaped = false;
        boolean caretIsCode = false;
        for (int index = 0; index < source.length(); index++) {
            checkCancelled(cancelled, index);
            char character = source.charAt(index);
            if (index == caret) caretIsCode = state == NORMAL;

            if (state == LINE_COMMENT) {
                int spliceEnd = spliceEnd(source, index);
                if (spliceEnd > index) {
                    output.setCharAt(index, ' ');
                    index = spliceEnd - 1;
                    continue;
                }
                if (isNewline(character)) {
                    state = NORMAL;
                    if (character == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') index++;
                    continue;
                }
                output.setCharAt(index, ' ');
                continue;
            }

            if (state == BLOCK_COMMENT) {
                if (character == '*') {
                    int slash = nextLogicalIndex(source, index + 1, cancelled);
                    if (slash < source.length() && source.charAt(slash) == '/') {
                        mask(output, source, index, slash + 1, cancelled);
                        index = slash;
                        state = NORMAL;
                        continue;
                    }
                }
                if (!isNewline(character)) output.setCharAt(index, ' ');
                continue;
            }

            if (state == LITERAL) {
                int spliceEnd = spliceEnd(source, index);
                if (spliceEnd > index) {
                    output.setCharAt(index, ' ');
                    index = spliceEnd - 1;
                    continue;
                }
                if (isNewline(character)) {
                    escaped = false;
                    if (character == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') index++;
                    continue;
                }
                output.setCharAt(index, ' ');
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == quote) state = NORMAL;
                continue;
            }

            if (character == '/') {
                int delimiter = nextLogicalIndex(source, index + 1, cancelled);
                if (delimiter < source.length() && (source.charAt(delimiter) == '/' || source.charAt(delimiter) == '*')) {
                    state = source.charAt(delimiter) == '/' ? LINE_COMMENT : BLOCK_COMMENT;
                    if (caret >= index && caret <= delimiter + 1) caretIsCode = false;
                    mask(output, source, index, delimiter + 1, cancelled);
                    index = delimiter;
                    continue;
                }
            }

            if (character == '"' || character == '\'') {
                quote = character;
                state = LITERAL;
                if (caret == index) caretIsCode = false;
                output.setCharAt(index, ' ');
            }
        }
        checkCancelled(cancelled, source.length());
        if (caret == source.length()) caretIsCode = state == NORMAL;
        return new LexicalView(output.toString(), caretIsCode);
    }

    private static void mask(StringBuilder output, String source, int start, int end, BooleanSupplier cancelled) {
        for (int index = start; index < end; index++) {
            checkCancelled(cancelled, index);
            if (!isNewline(source.charAt(index))) output.setCharAt(index, ' ');
        }
    }

    private static int nextLogicalIndex(String source, int index, BooleanSupplier cancelled) {
        while (index < source.length()) {
            checkCancelled(cancelled, index);
            int end = spliceEnd(source, index);
            if (end <= index) return index;
            index = end;
        }
        return source.length();
    }

    private static int spliceEnd(String source, int index) {
        if (index >= source.length() || source.charAt(index) != '\\' || index + 1 >= source.length()) return index;
        char newline = source.charAt(index + 1);
        if (newline == '\n') return index + 2;
        if (newline == '\r') return index + (index + 2 < source.length() && source.charAt(index + 2) == '\n' ? 3 : 2);
        return index;
    }

    private static boolean isNewline(char character) {
        return character == '\r' || character == '\n';
    }

    private static void checkCancelled(BooleanSupplier cancelled, int index) {
        if ((index & 255) == 0 && cancelled.getAsBoolean()) throw new CancellationException();
    }

    static boolean control(String line) {
        String text = line.strip();
        if (text.equals("else") || text.equals("do")) return true;
        if (!text.matches("(?:else\\s+)?(?:if|for|while)\\b.*")) return false;
        int open = text.indexOf('(');
        int depth = 0;
        for (int index = open; index >= 0 && index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '(') depth++;
            if (character == ')' && --depth == 0) return text.substring(index + 1).isBlank();
        }
        return false;
    }

    static int lineStart(String source, int offset) {
        int end = Math.max(0, Math.min(offset, source.length()));
        if (end > 0 && end < source.length() && source.charAt(end - 1) == '\r' && source.charAt(end) == '\n') end--;
        for (int index = end - 1; index >= 0; index--) {
            if (isNewline(source.charAt(index))) return index + 1;
        }
        return 0;
    }

    private static int previousLineCharacter(String source, int lineStart) {
        int index = lineStart - 1;
        if (index >= 0 && source.charAt(index) == '\n' && index > 0 && source.charAt(index - 1) == '\r') index--;
        if (index >= 0 && isNewline(source.charAt(index))) index--;
        return index;
    }

    static String nextIndent(String source, int caret) {
        String clean = code(source.substring(0, caret));
        int start = lineStart(source, caret);
        String line = clean.substring(start);
        String indent = leading(line);
        if (line.stripTrailing().endsWith("{") || control(line)) return indent + "\t";
        if (line.strip().equals("break;") && directlyInsideSwitch(clean, start + indent.length()) && indent.endsWith("\t")) {
            return indent.substring(0, indent.length() - 1);
        }

        if (line.stripTrailing().endsWith(";") || line.stripTrailing().endsWith("}")) {
            int prior = previousLineCharacter(clean, start);
            if (line.stripTrailing().endsWith("}")) {
                int depth = 0;
                for (int index = clean.length() - 1; index >= 0; index--) {
                    if (clean.charAt(index) == '}') depth++;
                    else if (clean.charAt(index) == '{' && --depth == 0) {
                        prior = previousLineCharacter(clean, lineStart(clean, index));
                        break;
                    }
                }
            }
            while (prior >= 0) {
                int lineStart = lineStart(clean, prior + 1);
                String previous = clean.substring(lineStart, prior + 1);
                if (!previous.isBlank()) {
                    if (control(previous)) indent = leading(previous);
                    else break;
                }
                prior = previousLineCharacter(clean, lineStart);
            }
        }
        return indent;
    }

    private static boolean directlyInsideSwitch(String source, int offset) {
        int nestedBraces = 0;
        for (int index = offset - 1; index >= 0; index--) {
            if (source.charAt(index) == '}') nestedBraces++;
            else if (source.charAt(index) == '{') {
                if (nestedBraces == 0) return opensSwitchBlock(source, index);
                nestedBraces--;
            }
        }
        return false;
    }

    private static boolean opensSwitchBlock(String source, int brace) {
        int closeParen = brace - 1;
        while (closeParen >= 0 && Character.isWhitespace(source.charAt(closeParen))) closeParen--;
        if (closeParen < 0 || source.charAt(closeParen) != ')') return false;
        int parentheses = 0;
        int openParen = -1;
        for (int index = closeParen; index >= 0; index--) {
            if (source.charAt(index) == ')') parentheses++;
            else if (source.charAt(index) == '(' && --parentheses == 0) {
                openParen = index;
                break;
            }
        }
        if (openParen < 0) return false;
        int keywordEnd = openParen - 1;
        while (keywordEnd >= 0 && Character.isWhitespace(source.charAt(keywordEnd))) keywordEnd--;
        int keywordStart = keywordEnd - "switch".length() + 1;
        return keywordStart >= 0 && source.regionMatches(keywordStart, "switch", 0, "switch".length())
                && (keywordStart == 0 || !Character.isJavaIdentifierPart(source.charAt(keywordStart - 1)));
    }

    static String closingIndent(String source, int caret) {
        String clean = code(source.substring(0, caret));
        int depth = 0;
        for (int index = clean.length() - 1; index >= 0; index--) {
            if (clean.charAt(index) == '}') depth++;
            else if (clean.charAt(index) == '{' && depth-- == 0) {
                int start = lineStart(source, index);
                return leading(source.substring(start, index));
            }
        }
        return null;
    }
}
