package VulcanIDE;

import C8080.SourceRefactoring;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Immutable project snapshots and guarded, recoverable refactoring transactions; filesystem work runs off the EDT. */
final class ProjectRefactoring {
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final long MAX_PROJECT_BYTES = 128L * 1024 * 1024;
    private static final int MAX_FILES = 10000;
    private static final Set<String> EXCLUDED = Set.of("work", "builds", "out", ".git");

    record OpenDocument(EditorPanel panel, Path path, String text) {}
    record DiskFile(byte[] bytes, Object fileKey) {}
    record Snapshot(Path root, Map<Path, String> sources, Map<Path, DiskFile> files,
                    List<OpenDocument> documents, List<Path> includeDirectories, Set<Path> projectFiles) {}
    @FunctionalInterface
    interface BeforeWrite { void check(Path path, int index) throws IOException; }

    private ProjectRefactoring() {}

    static List<OpenDocument> capture(Supplier<List<EditorPanel>> panels) {
        requireEdt();
        List<OpenDocument> documents = new ArrayList<>();
        Set<EditorPanel> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EditorPanel panel : panels.get()) {
            Path path = panel.getCurrentPath();
            if (!seen.add(panel) || path == null || !isSource(path)) continue;
            String text = panel.projectText(path);
            if (text != null) documents.add(new OpenDocument(panel, path.toAbsolutePath().normalize(), text));
        }
        return List.copyOf(documents);
    }

    static Snapshot scan(Path root, List<OpenDocument> documents, BooleanSupplier cancelled) throws IOException {
        return scan(root, documents, List.of(), cancelled);
    }

    static Snapshot scan(Path root, List<OpenDocument> documents, List<Path> includes,
                         BooleanSupplier cancelled) throws IOException {
        requireWorker();
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute)) {
            throw new IOException("Project root is not a real directory: " + absolute);
        }
        LinkedHashSet<Path> projectFiles = sourcePaths(absolute, false, cancelled);
        LinkedHashSet<Path> allFiles = new LinkedHashSet<>(projectFiles);
        List<Path> includeDirectories = includes.stream().filter(java.util.Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        for (Path include : includeDirectories) {
            checkCancelled(cancelled);
            if (Files.isDirectory(include, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(include)) {
                allFiles.addAll(sourcePaths(include, true, cancelled));
            }
        }
        Map<Path, DiskFile> files = new LinkedHashMap<>();
        Map<Path, String> sources = new LinkedHashMap<>();
        long total = 0;
        for (Path path : allFiles) {
            checkCancelled(cancelled);
            if (files.size() >= MAX_FILES) throw new IOException("Refactoring scope exceeds " + MAX_FILES + " source files.");
            Path boundary = projectFiles.contains(path) ? absolute : includeDirectories.stream()
                    .filter(path::startsWith).findFirst().orElseThrow();
            validatePath(path, boundary, false);
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.size() > MAX_FILE_BYTES || (total += attributes.size()) > MAX_PROJECT_BYTES) {
                throw new IOException("Refactoring source snapshot is too large: " + path);
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Source grew while reading: " + path);
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException error) {
                throw new IOException("Source is not valid UTF-8; no files were changed: " + path, error);
            }
            files.put(path, new DiskFile(bytes, attributes.fileKey()));
            sources.put(path, text);
        }
        List<OpenDocument> relevant = documents.stream().filter(document -> files.containsKey(document.path())).toList();
        Map<Path, String> overlays = new LinkedHashMap<>();
        for (OpenDocument document : relevant) {
            String previous = overlays.putIfAbsent(document.path(), document.text());
            if (previous != null && !previous.equals(document.text())) {
                throw new IOException("Open editors disagree about " + document.path() + "; close one view and preview again.");
            }
            sources.put(document.path(), document.text());
        }
        for (OpenDocument document : documents) {
            if (document.path().startsWith(absolute) && !files.containsKey(document.path()) && !excluded(absolute, document.path())) {
                throw new IOException("Open source is absent from the project snapshot: " + document.path());
            }
        }
        return new Snapshot(absolute, Collections.unmodifiableMap(sources), Collections.unmodifiableMap(files),
                List.copyOf(relevant), includeDirectories, Set.copyOf(projectFiles));
    }

    static void apply(Snapshot snapshot, SourceRefactoring.Preview preview, Supplier<List<EditorPanel>> panels,
                      BooleanSupplier cancelled) throws IOException {
        apply(snapshot, preview, panels, cancelled, (path, index) -> {});
    }

    static void apply(Snapshot snapshot, SourceRefactoring.Preview preview, Supplier<List<EditorPanel>> panels,
                      BooleanSupplier cancelled, BeforeWrite beforeWrite) throws IOException {
        requireWorker();
        if (!preview.applicable() || preview.edits().isEmpty()) throw new IOException("There is no applicable current preview.");
        checkCancelled(cancelled);
        Map<Path, byte[]> updates = new LinkedHashMap<>();
        Set<Path> openPaths = new LinkedHashSet<>();
        snapshot.documents().forEach(document -> openPaths.add(document.path()));
        for (SourceRefactoring.FileEdit edit : preview.edits()) {
            Path path = edit.path().toAbsolutePath().normalize();
            if (!snapshot.projectFiles().contains(path) || !edit.expectedText().equals(snapshot.sources().get(path))) {
                throw new IOException("Preview does not match an editable project source: " + path);
            }
            validatePath(path, snapshot.root(), true);
            if (updates.putIfAbsent(path, encode(edit.updatedText(), path)) != null) {
                throw new IOException("Preview contains duplicate file edits: " + path);
            }
        }
        Path staging = Files.createTempDirectory("vulcan-refactoring-");
        List<EditorPanel.RefactoringLease> leases = new ArrayList<>();
        List<LockedFile> locked = new ArrayList<>();
        List<LockedFile> written = new ArrayList<>();
        boolean recoveryFailed = false;
        Throwable failure = null;
        try {
            int index = 0;
            for (Map.Entry<Path, byte[]> update : updates.entrySet()) {
                if (openPaths.contains(update.getKey())) continue;
                Files.write(staging.resolve(index + ".before"), snapshot.files().get(update.getKey()).bytes());
                Files.write(staging.resolve(index + ".after"), update.getValue());
                index++;
            }
            onEdt(() -> {
                List<OpenDocument> current = capture(panels).stream()
                        .filter(document -> snapshot.files().containsKey(document.path()) || document.path().startsWith(snapshot.root()))
                        .filter(document -> !excluded(snapshot.root(), document.path())).toList();
                if (!current.equals(snapshot.documents())) throw new IOException("Open documents changed since preview; preview again.");
                for (OpenDocument document : snapshot.documents()) {
                    leases.add(document.panel().beginRefactoring(document.path(), document.text()));
                }
            });
            checkCancelled(cancelled);
            for (Map.Entry<Path, DiskFile> file : snapshot.files().entrySet()) {
                boolean write = updates.containsKey(file.getKey()) && !openPaths.contains(file.getKey());
                LockedFile opened = lock(file.getKey(), file.getValue(), write);
                locked.add(opened);
                verify(opened, file.getValue().bytes());
            }
            verifyManifest(snapshot, cancelled);
            checkCancelled(cancelled);
            index = 0;
            for (LockedFile file : locked) {
                byte[] updated = updates.get(file.path);
                if (updated == null || openPaths.contains(file.path)) continue;
                checkCancelled(cancelled);
                beforeWrite.check(file.path, index++);
                verify(file, file.original.bytes());
                written.add(file);
                write(file.channel, updated);
                file.updated = updated;
            }
            verifyManifest(snapshot, cancelled);
            for (LockedFile file : locked) verify(file, file.updated == null ? file.original.bytes() : file.updated);
            checkCancelled(cancelled);
            onEdt(() -> {
                List<OpenDocument> current = captureLeased(snapshot.documents());
                if (!current.equals(snapshot.documents())) throw new IOException("An editor changed while applying; transaction was rolled back.");
                for (int documentIndex = 0; documentIndex < snapshot.documents().size(); documentIndex++) {
                    OpenDocument document = snapshot.documents().get(documentIndex);
                    for (SourceRefactoring.FileEdit edit : preview.edits()) {
                        if (edit.path().toAbsolutePath().normalize().equals(document.path())) leases.get(documentIndex).replace(edit.updatedText());
                    }
                }
            });
        } catch (Throwable error) {
            failure = error;
            try {
                onEdt(() -> { for (EditorPanel.RefactoringLease lease : leases) lease.rollback(); });
            } catch (Throwable rollbackError) {
                error.addSuppressed(rollbackError);
                recoveryFailed = true;
            }
            for (int index = written.size() - 1; index >= 0; index--) {
                LockedFile file = written.get(index);
                try {
                    byte[] expected = file.updated;
                    if (expected == null) throw new IOException("Incomplete write requires manual recovery: " + file.path);
                    verify(file, expected);
                    write(file.channel, file.original.bytes());
                } catch (Throwable rollbackError) {
                    error.addSuppressed(rollbackError);
                    recoveryFailed = true;
                }
            }
        } finally {
            for (LockedFile file : locked) {
                try { file.close(); }
                catch (IOException closeError) {
                    if (failure != null) failure.addSuppressed(closeError);
                    else failure = closeError;
                }
            }
            try {
                onEdt(() -> { for (EditorPanel.RefactoringLease lease : leases) lease.close(); });
            } catch (Throwable closeError) {
                if (failure != null) failure.addSuppressed(closeError);
                else failure = closeError;
            }
            if (!recoveryFailed) {
                try (var staged = Files.list(staging)) {
                    for (Path path : staged.toList()) Files.delete(path);
                } catch (IOException cleanupError) {
                    if (failure != null) failure.addSuppressed(cleanupError);
                    else failure = cleanupError;
                }
                try { Files.delete(staging); }
                catch (IOException cleanupError) {
                    if (failure != null) failure.addSuppressed(cleanupError);
                    else failure = cleanupError;
                }
            }
        }
        if (recoveryFailed) throw new IOException("Refactoring failed and recovery was incomplete. Do not save affected editors. Backups: "
                + staging + ". " + details(failure), failure);
        if (failure instanceof CancellationException cancellation) throw cancellation;
        if (failure != null) throw new IOException("Refactoring did not complete; changed files were restored. " + details(failure), failure);
    }

    private static String details(Throwable failure) {
        StringBuilder text = new StringBuilder(VulcanDialog.errorSummary(failure));
        if (failure != null) for (Throwable suppressed : failure.getSuppressed()) text.append(" ").append(VulcanDialog.errorSummary(suppressed));
        return text.toString();
    }

    private static List<OpenDocument> captureLeased(List<OpenDocument> documents) {
        List<OpenDocument> current = new ArrayList<>();
        for (OpenDocument document : documents) {
            current.add(new OpenDocument(document.panel(), document.panel().getCurrentPath(), document.panel().projectText(document.path())));
        }
        return current;
    }

    private static byte[] encode(String text, Path path) throws IOException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IOException("Refactoring produced invalid Unicode: " + path, error);
        }
    }

    private static LockedFile lock(Path path, DiskFile expected, boolean write) throws IOException {
        FileChannel channel = write ? FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS) : FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock(0, Long.MAX_VALUE, !write);
            if (lock == null) throw new IOException("Source is busy; preview again after the other operation finishes: " + path);
            return new LockedFile(path, expected, channel, lock);
        } catch (Throwable error) {
            channel.close();
            if (error instanceof IOException io) throw io;
            throw new IOException("Cannot lock source safely: " + path, error);
        }
    }

    private static void verify(LockedFile file, byte[] expected) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                || !java.util.Objects.equals(attributes.fileKey(), file.original.fileKey())
                || file.channel.size() != expected.length) throw new IOException("Source changed since preview: " + file.path);
        ByteBuffer buffer = ByteBuffer.allocate(expected.length);
        file.channel.position(0);
        while (buffer.hasRemaining()) {
            if (file.channel.read(buffer) < 0) throw new IOException("Source changed while reading: " + file.path);
        }
        if (!Arrays.equals(buffer.array(), expected)) throw new IOException("Source changed since preview: " + file.path);
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.truncate(bytes.length);
        channel.force(true);
    }

    private static void verifyManifest(Snapshot snapshot, BooleanSupplier cancelled) throws IOException {
        if (!sourcePaths(snapshot.root(), false, cancelled).equals(snapshot.projectFiles())) {
            throw new IOException("Project sources were added, moved or removed since preview; preview again.");
        }
        Set<Path> all = new LinkedHashSet<>(snapshot.projectFiles());
        for (Path include : snapshot.includeDirectories()) {
            if (Files.isDirectory(include, LinkOption.NOFOLLOW_LINKS)) all.addAll(sourcePaths(include, true, cancelled));
        }
        if (!all.equals(snapshot.files().keySet())) throw new IOException("Include headers changed since preview; preview again.");
    }

    private static LinkedHashSet<Path> sourcePaths(Path root, boolean headersOnly, BooleanSupplier cancelled) throws IOException {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                checkCancelled(cancelled);
                return !directory.equals(root) && EXCLUDED.contains(directory.getFileName().toString().toLowerCase(Locale.ROOT))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                checkCancelled(cancelled);
                if (attributes.isRegularFile() && !attributes.isSymbolicLink() && isSource(file)
                        && (!headersOnly || file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".h"))) {
                    result.add(file.toAbsolutePath().normalize());
                    if (result.size() > MAX_FILES) throw new IOException("Refactoring scope exceeds " + MAX_FILES + " source files.");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static boolean excluded(Path root, Path file) {
        if (!file.startsWith(root)) return false;
        Path relative = root.relativize(file);
        for (int index = 0; index + 1 < relative.getNameCount(); index++) {
            if (EXCLUDED.contains(relative.getName(index).toString().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isSource(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".c") || name.endsWith(".h");
    }

    private static void validatePath(Path path, Path root, boolean writable) throws IOException {
        if (!path.startsWith(root) || !path.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Source lies outside the allowed root: " + path);
        }
        Path current = root;
        if (Files.isSymbolicLink(current)) throw new IOException("Symbolic-link roots cannot be refactored: " + current);
        for (Path component : root.relativize(path)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic-link source cannot be refactored: " + current);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || (writable && !Files.isWritable(path))) {
            throw new IOException("Source is missing or read-only: " + path);
        }
        if (writable && Files.getFileStore(path).supportsFileAttributeView("dos") && Files.readAttributes(path,
                java.nio.file.attribute.DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isReadOnly()) {
            throw new IOException("Source is read-only: " + path);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Refactoring cancelled.");
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Editor snapshots require the EDT.");
    }

    private static void requireWorker() {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Filesystem work must not run on the EDT.");
    }

    private static void onEdt(EdtAction action) throws IOException {
        Throwable[] failure = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { action.run(); }
                catch (Throwable error) { failure[0] = error; }
            });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while coordinating open editors.", error);
        } catch (InvocationTargetException error) {
            throw new IOException("Open editor coordination failed.", error.getCause());
        }
        if (failure[0] instanceof IOException io) throw io;
        if (failure[0] != null) throw new IOException("Open editor changed or is busy: " + VulcanDialog.errorSummary(failure[0]), failure[0]);
    }

    @FunctionalInterface
    private interface EdtAction { void run() throws Exception; }

    private static final class LockedFile implements AutoCloseable {
        private final Path path;
        private final DiskFile original;
        private final FileChannel channel;
        private final FileLock lock;
        private byte[] updated;

        private LockedFile(Path path, DiskFile original, FileChannel channel, FileLock lock) {
            this.path = path;
            this.original = original;
            this.channel = channel;
            this.lock = lock;
        }

        @Override public void close() throws IOException {
            try { lock.release(); }
            finally { channel.close(); }
        }
    }
}
