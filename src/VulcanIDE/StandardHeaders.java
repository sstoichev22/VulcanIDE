package VulcanIDE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/**
 * Locates the bundled C standard headers for editor indexing and hover. Source checkouts use
 * src/stdlib/include directly; a packaged JAR copies its headers once into a stable folder under
 * the Vulcan home so hover "Defined in" paths never point into a temporary directory.
 */
final class StandardHeaders {
    private static final List<String> HEADERS = List.of("assert.h", "ctype.h", "float.h", "limits.h", "math.h",
            "stddef.h", "stdint.h", "stdio.h", "stdlib.h", "string.h", "time.h");
    private static Path directory;

    private StandardHeaders() {}

    /** Returns the standard-header directory, or null when neither sources nor packaged resources exist. */
    static synchronized Path directory() {
        if (directory != null) return directory;
        Path source = Path.of("src", "stdlib", "include").toAbsolutePath().normalize();
        if (Files.isRegularFile(source.resolve("string.h"))) return directory = source;
        try {
            var url = StandardHeaders.class.getResource("/stdlib/include/string.h");
            if (url == null) return null;
            if (url.getProtocol().equals("file")) return directory = Path.of(url.toURI()).getParent();
            return directory = extract(home().resolve("stdlib").resolve("include"));
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static Path extract(Path target) throws IOException {
        Files.createDirectories(target);
        for (String name : HEADERS) {
            try (InputStream stream = StandardHeaders.class.getResourceAsStream("/stdlib/include/" + name)) {
                if (stream == null) continue;
                byte[] bytes = stream.readAllBytes();
                Path file = target.resolve(name);
                if (!Files.isRegularFile(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) Files.write(file, bytes);
            }
        }
        return target;
    }

    private static Path home() {
        String configured = System.getProperty("vulcan.home");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".vulcan")
                : Path.of(configured);
    }
}
