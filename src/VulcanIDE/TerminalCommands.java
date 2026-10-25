package VulcanIDE;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

/** Parses terminal command lines into stages and completes file names. */
final class TerminalCommands {
    /** One command of a pipeline; pipeToNext is true when its output feeds the next stage. */
    record Stage(String text, boolean pipeToNext) {}

    static List<Stage> parse(String command) {
        List<Stage> stages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '\'' || ch == '"') {
                if (quote == 0) quote = ch; else if (quote == ch) quote = 0;
                current.append(ch);
            } else if (quote == 0 && (ch == '|' || ch == '&')) {
                boolean pipe = ch == '|';
                if (!pipe && (i + 1 >= command.length() || command.charAt(++i) != '&'))
                    throw new IllegalArgumentException("Use && between commands.");
                if (pipe && i + 1 < command.length() && command.charAt(i + 1) == '|')
                    throw new IllegalArgumentException("Use | for a pipe or && for a conditional command.");
                if (current.toString().isBlank()) throw new IllegalArgumentException("Missing command before operator.");
                stages.add(new Stage(current.toString().trim(), pipe)); current.setLength(0);
            } else current.append(ch);
        }
        if (quote != 0) throw new IllegalArgumentException("Unterminated quoted argument.");
        if (current.toString().isBlank()) throw new IllegalArgumentException("Missing command after operator.");
        stages.add(new Stage(current.toString().trim(), false));
        return stages;
    }

    static List<String> tokens(String command) {
        List<String> args = new ArrayList<>(); StringBuilder word = new StringBuilder();
        char quote = 0; boolean started = false;
        for (char ch : command.toCharArray()) {
            if ((ch == '\'' || ch == '"') && (quote == 0 || quote == ch)) { quote = quote == 0 ? ch : 0; started = true; }
            else if (Character.isWhitespace(ch) && quote == 0) {
                if (started) { args.add(word.toString()); word.setLength(0); started = false; }
            } else { word.append(ch); started = true; }
        }
        if (quote != 0) throw new IllegalArgumentException("Unterminated quoted argument.");
        if (started) args.add(word.toString());
        return args;
    }

    /** File-name completion replacing text from start. */
    record Completion(int start, String replacement) {}
    static Completion complete(Path directory, String text) throws java.io.IOException {
        int start = 0; char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\'') { if (quote == 0) quote = ch; else if (quote == ch) quote = 0; }
            else if (quote == 0 && (Character.isWhitespace(ch) || ch == '|' || ch == '&')) start = i + 1;
        }
        String raw = text.substring(start);
        String prefix = raw.replace("\"", "").replace("'", "");
        int slash = Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf('\\'));
        String parent = slash < 0 ? "" : prefix.substring(0, slash + 1);
        String leaf = prefix.substring(slash + 1);
        Path folder = directory.resolve(parent).normalize();
        if (!Files.isDirectory(folder)) return null;
        List<Path> matches;
        try (var paths = Files.list(folder)) {
            matches = paths.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith(leaf.toLowerCase(java.util.Locale.ROOT)))
                    .sorted().toList();
        }
        if (matches.isEmpty()) return null;
        String common = matches.get(0).getFileName().toString();
        for (Path match : matches) {
            String name = match.getFileName().toString(); int length = 0;
            while (length < common.length() && length < name.length() && Character.toLowerCase(common.charAt(length)) == Character.toLowerCase(name.charAt(length))) length++;
            common = common.substring(0, length);
        }
        String result = parent + common;
        if (matches.size() == 1 && Files.isDirectory(matches.get(0))) result += "/";
        if (result.contains(" ") || raw.startsWith("\"") || raw.startsWith("'")) result = "\"" + result + "\"";
        return new Completion(start, result);
    }
}
