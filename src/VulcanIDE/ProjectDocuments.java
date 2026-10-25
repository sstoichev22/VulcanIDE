package VulcanIDE;

import java.nio.file.*;
import java.net.URL;
import java.io.InputStream;

/** Locates bundled help documents and copies them to the Vulcan home when packaged. */
final class ProjectDocuments {
    private ProjectDocuments() {}

    static Path find(String name) throws Exception {
        if ("cpu.md".equals(name)) name = "asm.md";
        if (!java.util.Set.of("vulcan.md", "asm.md", "c.md").contains(name))
            throw new IllegalArgumentException("Unknown project document.");
        URL resource = Vulcan.class.getResource("/docs/" + name);
        Path location = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path found = sourceDocument(location, name);
        if (found != null) return found;
        URL code = Vulcan.class.getProtectionDomain().getCodeSource().getLocation();
        if ("file".equals(code.getProtocol())) {
            found = sourceDocument(Path.of(code.toURI()), name);
            if (found != null) return found;
        }
        if (resource != null && "file".equals(resource.getProtocol())) return Path.of(resource.toURI());
        if (resource == null) throw new java.io.IOException("Missing project documentation: " + name);
        String home = System.getProperty("vulcan.home");
        Path base = home == null || home.isBlank() ? Path.of(System.getProperty("user.home"), ".vulcan") : Path.of(home);
        Path directory = base.resolve("docs");
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        try (InputStream input = resource.openStream()) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
        return target;
    }

    private static Path sourceDocument(Path start, String name) {
        for (Path root = start; root != null; root = root.getParent()) {
            Path path = root.resolve("docs").resolve(name);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }
}
