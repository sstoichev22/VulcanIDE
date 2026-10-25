package VulcanIDE;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

/** Persists the shell's latest commands in the user's Vulcan settings directory. */
final class TerminalHistory {
    private static final int MAX_COMMANDS = 50;
    private static final String FORMAT_HEADER = "\u0000VULCAN_TERMINAL_HISTORY_V1";
    private final Path file;

    /** Uses the configured Vulcan home, or {@code ~/.vulcan} when none is configured. */
    TerminalHistory() {
        String configuredHome = System.getProperty("vulcan.home");
        Path home = configuredHome == null || configuredHome.isBlank()
                ? Path.of(System.getProperty("user.home"), ".vulcan")
                : Path.of(configuredHome);
        file = home.resolve("terminal-history.txt");
    }

    /** Returns the last fifty saved commands, or an empty list when no readable history exists. */
    List<String> load() {
        try {
            if (!Files.isRegularFile(file)) return List.of();
            Deque<String> recent = new ArrayDeque<>(MAX_COMMANDS);
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String first = reader.readLine();
                boolean encoded = FORMAT_HEADER.equals(first);
                if (!encoded && first != null) remember(recent, first);
                for (String line; (line = reader.readLine()) != null;) {
                    String command = line;
                    if (encoded) {
                        try {
                            command = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
                        } catch (IllegalArgumentException malformedEntry) {
                            continue;
                        }
                    }
                    remember(recent, command);
                }
            }
            return List.copyOf(recent);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    /** Replaces the saved history atomically when supported and keeps at most fifty commands. */
    void save(List<String> commands) {
        try {
            Path directory = file.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "history-", ".tmp");
            try {
                List<String> recent = commands.subList(Math.max(0, commands.size() - MAX_COMMANDS), commands.size());
                List<String> persisted = new ArrayList<>(recent.size() + 1);
                if (!recent.isEmpty()) {
                    persisted.add(FORMAT_HEADER);
                    for (String command : recent) {
                        persisted.add(Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8)));
                    }
                }
                Files.write(temporary, persisted, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicMoveUnavailable) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ignored) {
        }
    }

    /** Appends one command and evicts the oldest entry when the history limit is full. */
    private static void remember(Deque<String> recent, String command) {
        if (recent.size() == MAX_COMMANDS) recent.removeFirst();
        recent.addLast(command);
    }
}
