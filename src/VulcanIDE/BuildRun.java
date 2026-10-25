package VulcanIDE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** build.run: a plain list of .c/.asm files, one path per line relative to the file, # for comments.
    Running it compiles and links every listed file; running a source below it links only listed files. */
final class BuildRun {
    static final String NAME = "build.run";

    private BuildRun() {}

    static boolean is(Path path) {
        return path != null && path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase(NAME);
    }

    static boolean isSource(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".c") || name.endsWith(".asm");
    }

    /** The nearest build.run in the folder or any folder above it, or null. */
    static Path find(Path folder) {
        for (Path dir = folder == null ? null : folder.toAbsolutePath().normalize(); dir != null && C8080.SourceAccess.allows(dir); dir = dir.getParent()) {
            Path candidate = dir.resolve(NAME);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /** The listed source files, resolved against the build.run folder; a bad entry names its line. */
    static List<Path> units(Path buildRun, String text) {
        Path folder = buildRun.toAbsolutePath().normalize().getParent();
        List<Path> units = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String entry = lines[i].replaceFirst("#.*", "").strip();
            if (entry.isEmpty()) continue;
            Path unit = folder.resolve(entry).normalize();
            C8080.SourceAccess.check(unit);
            if (!isSource(unit) || !Files.isRegularFile(unit))
                throw new IllegalArgumentException(NAME + " line " + (i + 1) + ": " + entry + " is not an existing .c or .asm file.");
            if (!units.contains(unit)) units.add(unit);
        }
        return units;
    }

    static List<Path> units(Path buildRun) throws IOException {
        return units(buildRun, C8080.SourceAccess.readString(buildRun));
    }

    /** Keeps build.run in step with new files: a new build.run lists every source under its folder,
        and a new source is appended to the nearest build.run above it. Other files are ignored. */
    static void created(Path path) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        if (is(file)) {
            Path folder = file.getParent();
            try (var files = Files.walk(folder)) {
                List<String> lines = files.filter(Files::isRegularFile).filter(BuildRun::isSource)
                        .map(source -> relative(folder, source)).sorted().toList();
                Files.writeString(file, lines.isEmpty() ? "" : String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            }
        } else if (isSource(file)) {
            Path buildRun = find(file.getParent());
            if (buildRun == null) return;
            String entry = relative(buildRun.getParent(), file);
            String text = Files.readString(buildRun, StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) if (line.replaceFirst("#.*", "").strip().equals(entry)) return;
            String separator = text.isEmpty() || text.endsWith("\n") ? "" : "\n";
            Files.writeString(buildRun, separator + entry + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    private static String relative(Path folder, Path file) {
        return folder.relativize(file).toString().replace('\\', '/');
    }
}
