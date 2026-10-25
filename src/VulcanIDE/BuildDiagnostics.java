package VulcanIDE;

import C8080.SourceDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats compiler and assembler failures for the console with file, line, and caret context. */
final class BuildDiagnostics {
    private BuildDiagnostics() {}

    static RuntimeException source(Throwable failure, Path file, String source) {
        if (failure instanceof SourceDiagnostic diagnostic) return diagnostic;
        String message = failure.getMessage() == null ? "The build could not finish." : failure.getMessage();
        int line = 1, column = 1, width = 1;
        Matcher location = Pattern.compile("(?i)line (\\d+)(?:, (?:character|column) (\\d+))?").matcher(message);
        if (location.find()) {
            line = Integer.parseInt(location.group(1));
            if (location.group(2) != null) column = Integer.parseInt(location.group(2));
        }
        String[] lines = (source == null ? "" : source).replace("\r", "").split("\n", -1);
        Matcher symbol = Pattern.compile("(?i)(?:external symbol|unknown symbol|unresolved symbol) ['\"]?([A-Za-z_][A-Za-z_0-9]*)").matcher(message);
        if (symbol.find()) {
            String name = symbol.group(1);
            Pattern token = Pattern.compile("(?<![A-Za-z_0-9])" + Pattern.quote(name) + "(?![A-Za-z_0-9])");
            for (int i = 0; i < lines.length; i++) {
                Matcher match = token.matcher(lines[i]);
                if (match.find()) { line = i + 1; column = match.start() + 1; width = name.length(); break; }
            }
            message += "\nA declaration names a symbol, but the linker also needs its implementation. Define the label or export the function with public, and include its .c/.asm file or object in the build.";
        } else if (message.contains("Duplicate")) {
            message += "\nEach exported name needs one definition. Rename one definition or remove the duplicate input from the link command.";
        } else {
            message += "\nCheck this instruction's operands and section against Help > Assembly and CPU Reference.";
        }
        int index = Math.max(0, Math.min(line - 1, lines.length - 1));
        return new SourceDiagnostic(file, line, column, width, lines[index], message);
    }

    static RuntimeException object(Throwable failure, VulcanObject object) {
        Path file;
        try { file = Path.of(object.sourceName()); }
        catch (RuntimeException invalid) { file = Path.of("source.asm"); }
        String source = object.assembly();
        try { if (Files.isRegularFile(file)) source = Files.readString(file); }
        catch (java.io.IOException ignored) {                                                          }
        return source(failure, file, source);
    }
}
