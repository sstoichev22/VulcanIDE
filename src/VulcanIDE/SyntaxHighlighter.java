package VulcanIDE;

import Intel8080.assembler.OpcodeTable;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Syntax coloring for C, assembly, and text files, with incremental restyling. */
public final class SyntaxHighlighter {

    private static final Set<String> DIRECTIVES = Set.of("ORG", "EQU", "DB", "DW", "DD", "DQ", "DS", "END", "EXTERN", "PUBLIC", "GLOBAL");
    private static final Set<String> SECTIONS = Set.of(".TEXT", ".CODE", ".DATA");
    private static final Set<String> CALL_JUMP = Set.of(
            "CALL", "RET", "RST", "PCHL",
            "JNZ", "JZ", "JNC", "JC", "JPO", "JPE", "JP", "JM", "JMP",
            "CNZ", "CZ", "CNC", "CC", "CPO", "CPE", "CP", "CM",
            "RNZ", "RZ", "RNC", "RC", "RPO", "RPE", "RP", "RM"
    );
    private static final Set<String> STACK = Set.of("PUSH", "POP", "XTHL", "SPHL", "SP", "PSW");
    private static final Set<String> REGISTERS = Set.of("A", "B", "C", "D", "E", "H", "L", "M");
    private static final Set<String> INSTRUCTIONS = instructionNames();

    private static final Pattern TOKEN = Pattern.compile(
            "\\.?(?:[A-Za-z_][A-Za-z0-9_-]*|(?:0x|0X|0h|0H|0b|0B)[0-9A-Fa-f]+|\\d+)"
    );
    // C keywords are case-sensitive, matching the C8080 lexer: "If" or "INT" is an identifier.
    private static final Set<String> C_KEYWORDS = Set.of(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
            "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
            "register", "return", "short", "signed", "sizeof", "static", "struct", "switch",
            "typedef", "union", "unsigned", "void", "volatile", "while", "asm"
    );
    private static final Pattern C_INLINE_ASM = Pattern.compile("\\basm\\s*\\{");

    private static Set<String> instructionNames() {
        Set<String> names = new HashSet<>();
        for (String opcode : OpcodeTable.OPCODES.getMnemonics()) {
            int operand = opcode.indexOf(' ');
            names.add(operand < 0 ? opcode : opcode.substring(0, operand));
        }
        return Set.copyOf(names);
    }

    private static final Pattern C_TOKEN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*|(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|[0-9]+(?:\\.[0-9]+)?)(?:[uU](?:[lL]{1,2})?|[lL]{1,2}[uU]?|[fF])?"
    );
    private static final Pattern C_ESCAPE = Pattern.compile("\\\\(?:x[0-9A-Fa-f]+|[0-7]{1,3}|.)");
    private static final Pattern C_INTEGER_TYPE = Pattern.compile("u?int[0-9]+");
    private static final Pattern C_NUMBER = Pattern.compile("(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|[0-9]+(?:\\.[0-9]+)?)(?:[uU](?:[lL]{1,2})?|[lL]{1,2}[uU]?|[fF])?");
    private static final Pattern C_IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final Set<String> C_LITERALS = Set.of("NULL", "true", "false");
    private static final Set<String> C_PRIMITIVES = Set.of("char", "short", "int", "long", "signed", "unsigned", "float", "double", "void", "bool", "size_t");

    private SyntaxHighlighter() {
    }

    /** Restyles the whole document for its file type. */
    public static void apply(JTextPane editor, String extension, boolean enabled, SettingsManager settings) {
        applyRange(editor, extension, enabled, settings, 0, Integer.MAX_VALUE, true);
    }

    /** Restyles only the lines affected by an edit, extending to lexical changes such as comment openers. */
    public static void applyRange(JTextPane editor, String extension, boolean enabled,
                                  SettingsManager settings, int changedStart, int changedEnd) {
        applyRange(editor, extension, enabled, settings, changedStart, changedEnd, false);
    }

    private static void applyRange(JTextPane editor, String extension, boolean enabled,
                                   SettingsManager settings, int changedStart, int changedEnd,
                                   boolean fullReset) {
        StyledDocument document = editor.getStyledDocument();

        Style base = document.getStyle("vulcan-base");
        if (base == null) base = document.addStyle("vulcan-base", null);
        StyleConstants.setFontFamily(base, editor.getFont().getFamily());
        StyleConstants.setFontSize(base, editor.getFont().getSize());
        StyleConstants.setForeground(base, Theme.text(settings));
        StyleConstants.setBold(base, false);

        int start;
        int end;
        if (fullReset) {
            start = 0;
            end = document.getLength();
        } else {
            int safeStart = Math.max(0, Math.min(changedStart, document.getLength()));
            int safeEnd = Math.max(safeStart, Math.min(changedEnd, document.getLength()));
            var root = document.getDefaultRootElement();
            var startElement = root.getElement(root.getElementIndex(safeStart));
            var endElement = root.getElement(root.getElementIndex(Math.max(safeStart, safeEnd - 1)));
            start = startElement.getStartOffset();
            end = Math.min(document.getLength(), endElement.getEndOffset());
        }

        boolean cSource = extension.equalsIgnoreCase("c") || extension.equalsIgnoreCase("h");
        Set<String> asmEquates=Set.of();
        if(extension.equalsIgnoreCase("asm"))try{
            asmEquates=collectEquates(document.getText(0,document.getLength()));
            if(!asmEquates.equals(document.getProperty("vulcan-asm-equates"))){start=0;end=document.getLength();}
            document.putProperty("vulcan-asm-equates",asmEquates);
        }catch(BadLocationException ignored){}
        if (cSource) {
            try {
                String text=document.getText(0,document.getLength()),clean=CIndentation.code(text);
                String previous=(String)document.getProperty("vulcan-syntax-text"),oldClean=(String)document.getProperty("vulcan-syntax-clean");
                if(!fullReset&&previous!=null&&oldClean!=null){

                    int prefix=0,limit=Math.min(text.length(),previous.length());
                    while(prefix<limit&&text.charAt(prefix)==previous.charAt(prefix))prefix++;
                    int newTail=text.length(),oldTail=previous.length();
                    while(newTail>prefix&&oldTail>prefix&&text.charAt(newTail-1)==previous.charAt(oldTail-1)){newTail--;oldTail--;}
                    String lexicalChange=text.substring(prefix,newTail)+previous.substring(prefix,oldTail);
                    String newBoundary=text.substring(Math.max(0,prefix-1),Math.min(text.length(),newTail+1));
                    String oldBoundary=previous.substring(Math.max(0,prefix-1),Math.min(previous.length(),oldTail+1));
                    boolean commentBoundaryEdit=List.of("/*","*/","//").stream().anyMatch(marker->newBoundary.contains(marker)!=oldBoundary.contains(marker));
                    boolean delimiterEdit=commentBoundaryEdit||lexicalChange.chars().anyMatch(c->c=='\''||c=='"'||c=='/'||c=='*'||c=='\\'||c=='\n'||c=='\r');
                    int oldEnd=end-(text.length()-previous.length());
                    if(delimiterEdit||oldEnd<0||oldEnd>oldClean.length()||!clean.substring(end).equals(oldClean.substring(oldEnd)))end=document.getLength();
                    String changed=text.substring(start,Math.min(end,text.length()))+previous.substring(Math.min(start,previous.length()),Math.min(Math.max(start,oldEnd),previous.length()));
                    if(changed.matches("(?s).*\\b(?:typedef|define|undef|struct|union|enum)\\b.*")){start=0;end=document.getLength();}
                }
                document.putProperty("vulcan-syntax-text",text);document.putProperty("vulcan-syntax-clean",clean);
            }catch(BadLocationException ignored){}
        }
        if (end > start) document.setCharacterAttributes(start, end - start, base, true);
        if (!enabled || end <= start) return;
        if (cSource) {
            applyCSource(document, settings, start, end);
            editor.getInputAttributes().addAttribute(StyleConstants.Foreground, Theme.text(settings));
            editor.getInputAttributes().addAttribute(StyleConstants.Bold, false);
            return;
        }
        if (!extension.equalsIgnoreCase("asm")) return;

        var root = document.getDefaultRootElement();
        int firstLine = root.getElementIndex(start);
        int lastLine = root.getElementIndex(Math.max(start, end - 1));
        int lineStart = root.getElement(firstLine).getStartOffset();
        int lineEnd = Math.min(document.getLength(), root.getElement(lastLine).getEndOffset());

        String localText;
        try {
            localText = document.getText(lineStart, Math.max(0, lineEnd - lineStart));
        } catch (BadLocationException e) {
            return;
        }

        Set<String> equates = asmEquates;
        String[] lines = localText.split("\\n", -1);
        int offset = lineStart;
        for (String line : lines) {
            applyLine(document, line, offset, settings, equates);
            offset += line.length() + 1;
        }
    }

    private static void applyCSource(StyledDocument document, SettingsManager settings, int rangeStart, int rangeEnd) {
        String text;
        try { text = document.getText(0, document.getLength()); }
        catch (BadLocationException error) { return; }

        Set<String> typedefs = new HashSet<>();
        Set<String> macros = new HashSet<>();
        Matcher defines = Pattern.compile("(?m)^\\s*#\\s*define\\s+([A-Za-z_]\\w*)").matcher(text);
        while (defines.find()) macros.add(defines.group(1));
        String declarations = CIndentation.code(text);
        Matcher tags = Pattern.compile("\\b(?:struct|union|enum)\\s+([A-Za-z_]\\w*)").matcher(declarations);
        while (tags.find()) typedefs.add(tags.group(1));
        Matcher aliases = Pattern.compile("\\btypedef\\b").matcher(declarations);
        while (aliases.find()) {
            int end=CSymbolIndex.declarationEnd(declarations,aliases.start());
            if(end<0)continue;
            String declaration = declarations.substring(aliases.end(),end-1);
            Matcher functionPointer = Pattern.compile("\\(\\s*\\*\\s*([A-Za-z_]\\w*)\\s*\\)").matcher(declaration);
            if (functionPointer.find()) typedefs.add(functionPointer.group(1));
            else {
                int brace=declaration.lastIndexOf('}');if(brace>=0)declaration=declaration.substring(brace+1);
                for(String part:declaration.split(",")) {
                    Matcher alias=Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\[[^]]*\\]\\s*)*$").matcher(part.strip());
                    if(alias.find())typedefs.add(alias.group(1));
                }
            }
        }

        boolean blockComment = false;
        int lineOffset = 0;
        for (String line : text.split("\\n", -1)) {
            if(lineOffset>=rangeEnd)break;
            int i = 0;
            boolean preprocessor = line.stripLeading().startsWith("#");
            if (preprocessor) {
                int directive = line.indexOf('#');
                applyCColor(document,rangeStart,rangeEnd, lineOffset + directive, line.length() - directive, settings.getColor("cMacro"), true);
            }
            while (i < line.length()) {
                if (blockComment) {
                    int close = line.indexOf("*/", i);
                    int end = close < 0 ? line.length() : close + 2;
                    applyCColor(document,rangeStart,rangeEnd, lineOffset + i, end - i, settings.getColor("cComment"), false);
                    i = end;
                    blockComment = close < 0;
                } else if (i + 1 < line.length() && line.startsWith("/*", i)) {
                    int close = line.indexOf("*/", i + 2);
                    int end = close < 0 ? line.length() : close + 2;
                    applyCColor(document,rangeStart,rangeEnd, lineOffset + i, end - i, settings.getColor("cComment"), false);
                    i = end;
                    blockComment = close < 0;
                } else if (i + 1 < line.length() && line.startsWith("//", i)) {
                    applyCColor(document,rangeStart,rangeEnd, lineOffset + i, line.length() - i, settings.getColor("cComment"), false);
                    break;
                } else if (preprocessor && line.stripLeading().matches("#\\s*include\\b.*") && line.charAt(i) == '<') {
                    int close = line.indexOf('>', i + 1);
                    int end = close < 0 ? line.length() : close + 1;
                    applyCColor(document,rangeStart,rangeEnd, lineOffset + i, end - i, settings.getColor("cString"), false);
                    i = end;
                } else if (line.charAt(i) == '"' || line.charAt(i) == '\'') {
                    char quote = line.charAt(i);
                    int end = i + 1;
                    while (end < line.length()) {
                        if (line.charAt(end) == '\\') { end += Math.min(2, line.length() - end); continue; }
                        if (line.charAt(end++) == quote) break;
                    }
                    applyCColor(document,rangeStart,rangeEnd, lineOffset + i, end - i,
                            quote == '"' ? settings.getColor("cString") : settings.getColor("cCharacter"), false);
                    Matcher escapes=C_ESCAPE.matcher(line.substring(i,end));
                    while(escapes.find())applyCColor(document,rangeStart,rangeEnd,lineOffset+i+escapes.start(),escapes.end()-escapes.start(),settings.getColor("escape"),false);
                    i = end;
                } else {
                    Matcher matcher = C_TOKEN.matcher(line);
                    matcher.region(i, line.length());
                    if (!matcher.lookingAt()) { i++; continue; }
                    String token = matcher.group();
                    Color color = null;
                    if (C_PRIMITIVES.contains(token) || C_INTEGER_TYPE.matcher(token).matches()) color = settings.getColor("cPrimitive");
                    else if (macros.contains(token)) color = settings.getColor("cMacro");
                    else if (typedefs.contains(token)) color = settings.getColor("cType");
                    else if (C_KEYWORDS.contains(token)) color = settings.getColor("cKeyword");
                    else if (C_NUMBER.matcher(token).matches() || C_LITERALS.contains(token)) color = settings.getColor("cNumber");
                    else if(C_IDENTIFIER.matcher(token).matches()) {
                        int next=matcher.end();while(next<line.length()&&Character.isWhitespace(line.charAt(next)))next++;
                        color=settings.getColor(next<line.length()&&line.charAt(next)=='(' ? "cFunction" : "cVariable");
                    }
                    if (preprocessor) color = settings.getColor("cMacro");
                    if (color != null) applyCColor(document,rangeStart,rangeEnd, lineOffset + i, token.length(), color, true);
                    i = matcher.end();
                }
            }
            lineOffset += line.length() + 1;
        }
        applyInlineAssembly(document, settings, text, declarations, macros, rangeStart, rangeEnd);
    }

    private static void applyInlineAssembly(StyledDocument document, SettingsManager settings, String text,
                                            String declarations, Set<String> macros, int rangeStart, int rangeEnd) {
        Matcher blocks = C_INLINE_ASM.matcher(declarations);
        while (blocks.find()) {
            int bodyStart = blocks.end();
            int bodyEnd = inlineAssemblyEnd(declarations, bodyStart);
            if (bodyEnd < 0) bodyEnd = declarations.length();

            for (int lineStart = bodyStart; lineStart < bodyEnd;) {
                int lineEnd = text.indexOf('\n', lineStart);
                if (lineEnd < 0 || lineEnd > bodyEnd) lineEnd = bodyEnd;
                if (lineStart < rangeEnd && lineEnd > rangeStart) {
                    document.setCharacterAttributes(lineStart, lineEnd - lineStart, document.getStyle("vulcan-base"), true);
                    applyLine(document, text.substring(lineStart, lineEnd), lineStart, settings, macros);
                }
                lineStart = lineEnd + 1;
            }
            if (bodyEnd == declarations.length()) break;
            blocks.region(bodyEnd + 1, declarations.length());
        }
    }

    private static int inlineAssemblyEnd(String declarations, int start) {
        boolean comment = false;
        for (int i = start; i < declarations.length(); i++) {
            char ch = declarations.charAt(i);
            if (comment) {
                if (ch == '\n') comment = false;
            } else if (ch == ';') {
                comment = true;
            } else if (ch == '}') {
                return i;
            }
        }
        return -1;
    }
    /** Computes styles for an edit off the event thread and applies them when still current. */
    public static void applyRangeAsync(JTextPane editor, String extension, boolean enabled,
                                       SettingsManager settings, int changedStart, int changedEnd,
                                       Runnable finished) {
        SwingUtilities.invokeLater(() -> {
            try { applyRange(editor, extension, enabled, settings, changedStart, changedEnd); }
            finally { if (finished != null) finished.run(); }
        });
    }

    private static Set<String> collectEquates(String text) {
        Set<String> result = new HashSet<>();
        for (String line : text.split("\n", -1)) {
            Matcher m = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+EQU\\b", Pattern.CASE_INSENSITIVE).matcher(line);
            if (m.find()) result.add(m.group(1).toUpperCase());
        }
        return result;
    }

    private static void applyLine(StyledDocument document, String line, int offset, SettingsManager settings, Set<String> equates) {
        int commentStart = findCommentStart(line);
        int codeEnd = commentStart >= 0 ? commentStart : line.length();

        if (commentStart >= 0) {
            applyColor(document, offset + commentStart, line.length() - commentStart,
                    Theme.comment(settings), false);
        }

        List<int[]> strings = findStrings(line, codeEnd);
        for (int[] range : strings) {
            applyColor(document, offset + range[0], range[1] - range[0], Theme.string(settings), false);
            for (int i = range[0] + 1; i + 1 < range[1] - 1; i++) {
                if (line.charAt(i) != '\\') continue;
                int length = (line.charAt(i + 1) == 'x' || line.charAt(i + 1) == 'X') && i + 3 < range[1] - 1 ? 4 : 2;
                applyColor(document, offset + i, length, settings.getColor("escape"), false);
                i += length - 1;
            }
        }

        String code = line.substring(0, codeEnd);
        String upperCode = code.toUpperCase();
        int equIndex = upperCode.indexOf(" EQU ");
        String equateName = equIndex > 0 ? code.substring(0, equIndex).trim() : null;
        Matcher matcher = TOKEN.matcher(code);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (insideAny(start, strings)) continue;

            String token = matcher.group();
            String upper = token.toUpperCase();
            Color color = null;
            boolean bold = true;

            if (equates.contains(upper)) color = settings.getColor("equate");
            else if (token.startsWith(".") && SECTIONS.contains(upper)) color = Theme.section(settings);
            else if (DIRECTIVES.contains(upper)) color = Theme.directive(settings);
            else if (CALL_JUMP.contains(upper)) color = Theme.callJump(settings);
            else if (STACK.contains(upper)) color = Theme.stack(settings);
            else if (REGISTERS.contains(upper)) color = Theme.register(settings);
            else if (INSTRUCTIONS.contains(upper)) color = Theme.instruction(settings);
            else if (isNumber(token)) color = Theme.immediate(settings);
            else bold = false;

            if (color != null) applyColor(document, offset + start, end - start, color, bold);
        }
    }

    private static int findCommentStart(String line) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '\\') { i++; continue; }
            if (c == '\'' || c == '"') {
                if (!quoted) {
                    quoted = true;
                    quote = c;
                } else if (quote == c) {
                    quoted = false;
                }
            } else if (c == ';' && !quoted) return i;
        }
        return -1;
    }

    private static List<int[]> findStrings(String line, int end) {
        List<int[]> result = new ArrayList<>();
        boolean quoted = false;
        char quote = 0;
        int start = -1;
        for (int i = 0; i < end; i++) {
            char c = line.charAt(i);
            if (quoted && c == '\\') { i++; continue; }
            if (c == '\'' || c == '"') {
                if (!quoted) {
                    quoted = true;
                    quote = c;
                    start = i;
                } else if (quote == c) {
                    quoted = false;
                    result.add(new int[]{start, i + 1});
                }
            }
        }
        return result;
    }

    private static boolean insideAny(int position, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (position >= range[0] && position < range[1]) return true;
        }
        return false;
    }

    private static boolean isNumber(String token) {
        return token.matches("(?i)(0x[0-9a-f]+|0h[0-9a-f]+|0b[01]+|\\d+)");
    }

    private static void applyCColor(StyledDocument document,int rangeStart,int rangeEnd,int start,int length,Color color,boolean bold){
        int end=Math.min(rangeEnd,start+length);start=Math.max(start,rangeStart);
        if(end>start)applyColor(document,start,end-start,color,bold);
    }
    private static void applyColor(StyledDocument document, int start, int length, Color color, boolean bold) {
        if (length <= 0) return;
        String key = "vulcan-color-" + color.getRGB() + "-" + bold;
        Style style = document.getStyle(key);
        if (style == null) style = document.addStyle(key, null);
        StyleConstants.setForeground(style, color);
        StyleConstants.setBold(style, bold);
        document.setCharacterAttributes(start, length, style, false);
    }
}
