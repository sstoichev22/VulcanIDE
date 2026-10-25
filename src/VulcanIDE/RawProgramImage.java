package VulcanIDE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads raw binary program images that have no executable header. */
final class RawProgramImage {
    private RawProgramImage() {}

    static byte[] read(Path path, int maximumBytes) throws IOException {
        if (maximumBytes < 0 || maximumBytes == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid raw executable size limit.");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] contents = input.readNBytes(maximumBytes + 1);
            if (contents.length > maximumBytes)
                throw new IllegalArgumentException("Executable is too large for memory.");
            return contents;
        }
    }
}
