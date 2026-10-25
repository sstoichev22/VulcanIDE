package VulcanIDE;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Safe copy, move, delete, and unique naming of project files, with undoable history. */
final class ProjectFileOperations {
    private ProjectFileOperations() { }

    static void copy(Path source, Path target, boolean replaceExisting) throws IOException {
        if (source == null || target == null) throw new IllegalArgumentException("Source and destination are required.");
        Path from = source.toAbsolutePath().normalize();
        Path to = target.toAbsolutePath().normalize();
        if (from.equals(to)) return;
        if (!replaceExisting && Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(to.toString());

        if (Files.isSymbolicLink(from) || !Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            copyEntry(from, to, replaceExisting);
            return;
        }
        if (to.startsWith(from)) {
            throw new IOException("A folder cannot be copied into itself or one of its subfolders.");
        }

        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                copyEntry(file, to.resolve(from.relativize(file)), replaceExisting);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void copyNew(Path source, Path target) throws IOException {
        Path to = target.toAbsolutePath().normalize();
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(to.toString());
        if (to.startsWith(source.toAbsolutePath().normalize())) throw new IOException("A folder cannot be copied into itself.");
        Path staging = Files.createTempDirectory(to.getParent(), ".vulcan-copy-");
        try {
            Path entry = staging.resolve(to.getFileName());
            copy(source, entry, false);
            Files.move(entry, to);
        } finally { deleteRecursively(staging); }
    }

    static void moveNew(Path source, Path target) throws IOException {
        Path from = source.toAbsolutePath().normalize();
        Path to = target.toAbsolutePath().normalize();
        if (from.equals(to)) return;
        if (to.startsWith(from)) throw new IOException("A folder cannot be moved into itself.");
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(to.toString());
        try { Files.move(from, to); }
        catch (IOException moveError) {
            copyNew(from, to);
            deleteRecursively(from);
        }
    }

    static Path uniqueTarget(Path source, Path directory, boolean alwaysDuplicate) {
        String name = source.getFileName().toString();
        Path direct = directory.resolve(name);
        if (!alwaysDuplicate && !Files.exists(direct, LinkOption.NOFOLLOW_LINKS)) return direct;
        int dot = !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) ? name.lastIndexOf('.') : -1;
        String extension = dot > 0 ? name.substring(dot) : "";
        String stem = (dot > 0 ? name.substring(0, dot) : name).replaceFirst(" \\(\\d+\\)$", "");
        for (int suffix = 1; ; suffix++) {
            Path candidate = directory.resolve(stem+" ("+suffix+")"+extension);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
    }

    /** One undoable file change from original to destination, with a content signature. */
    record Change(Path original, Path destination, String signature) {
        static Change created(Path path) throws IOException { return new Change(null, path, fingerprint(path)); }
        static Change moved(Path from, Path to) { return new Change(from, to, null); }
    }

    /** Undo and redo stacks of file-change batches. */
    static final class History {
        /** Veto hook called before a change is undone. */
        @FunctionalInterface interface BeforeUndo { void accept(Change change) throws IOException; }
        @FunctionalInterface interface BeforeRedo { void accept(Change change) throws IOException; }
        private record RedoBatch(List<Change> changes, List<Path> artifacts) { }
        private final Deque<List<Change>> batches = new ArrayDeque<>();
        private final Deque<RedoBatch> redoBatches = new ArrayDeque<>();
        private final Path redoStorage;

        History(Path redoStorage) { this.redoStorage = redoStorage.toAbsolutePath().normalize(); }
        void record(List<Change> changes) {
            clearRedo();
            if (!changes.isEmpty()) batches.push(new ArrayList<>(changes));
        }
        boolean isEmpty() { return batches.isEmpty(); }
        boolean canRedo() { return !redoBatches.isEmpty(); }
        List<Change> undo(java.util.function.Consumer<Change> listener) throws IOException { return undo(change -> {}, listener); }

        List<Change> undo(BeforeUndo beforeRemoval, java.util.function.Consumer<Change> listener) throws IOException {
            if (batches.isEmpty()) return List.of();
            List<Change> changes = batches.peek();
            for (Change change : changes) {
                if (change.original != null && Files.exists(change.original, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Undo would overwrite " + change.original.getFileName() + ".");
                if (!Files.exists(change.destination, LinkOption.NOFOLLOW_LINKS) ||
                        (change.original == null && !change.signature.equals(fingerprint(change.destination))))
                    throw new IOException("Cannot undo: " + change.destination.getFileName() + " changed since this operation.");
            }
            for (int i = changes.size() - 1; i >= 0; i--) beforeRemoval.accept(changes.get(i));
            List<Change> undone = new ArrayList<>();
            List<Change> redo = new ArrayList<>(java.util.Collections.nCopies(changes.size(), null));
            List<Path> artifacts = new ArrayList<>();
            for (int i = changes.size() - 1; i >= 0; i--) {
                Change change = changes.get(i);
                if (change.original == null) {
                    Files.createDirectories(redoStorage);
                    Path artifact = Files.createTempDirectory(redoStorage, "entry-");
                    Path stored = artifact.resolve(change.destination.getFileName());
                    moveNew(change.destination, stored);
                    artifacts.add(artifact);
                    redo.set(i, Change.moved(stored, change.destination));
                } else {
                    moveNew(change.destination, change.original);
                    redo.set(i, Change.moved(change.original, change.destination));
                }
                undone.add(change);
                changes.remove(i);
                listener.accept(change);
            }
            batches.pop();
            redoBatches.push(new RedoBatch(redo, artifacts));
            return undone;
        }

        List<Change> redo(java.util.function.Consumer<Change> listener) throws IOException { return redo(change -> {}, listener); }

        List<Change> redo(BeforeRedo beforeMove, java.util.function.Consumer<Change> listener) throws IOException {
            if (redoBatches.isEmpty()) return List.of();
            RedoBatch batch = redoBatches.peek();
            List<Change> changes = batch.changes;
            for (Change change : changes) {
                if (!Files.exists(change.original, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Cannot redo: " + change.original.getFileName() + " no longer exists.");
                if (Files.exists(change.destination, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Redo would overwrite " + change.destination.getFileName() + ".");
            }
            for (Change change : changes) beforeMove.accept(change);
            List<Change> undoneAgain = new ArrayList<>(changes.size());
            for (Change change : changes) {
                moveNew(change.original, change.destination);
                undoneAgain.add(change.original.startsWith(redoStorage)
                        ? Change.created(change.destination) : change);
                listener.accept(change);
            }
            redoBatches.pop();
            for (Path artifact : batch.artifacts) deleteRecursively(artifact);
            batches.push(undoneAgain);
            return List.copyOf(changes);
        }

        private void clearRedo() {
            while (!redoBatches.isEmpty()) {
                for (Path artifact : redoBatches.pop().artifacts) {
                    try { deleteRecursively(artifact); }
                    catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                }
            }
        }
    }

    private static String fingerprint(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted().toList()) {
                    digest.update(root.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    if (Files.isSymbolicLink(path)) {
                        digest.update((byte) 'L');
                        digest.update(Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8));
                    } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) digest.update((byte) 'D');
                    else {
                        digest.update((byte) 'F');
                        try (var input = Files.newInputStream(path)) {
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
                        }
                    }
                    digest.update((byte) 0);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void deleteRecursively(Path target) throws IOException {
        if (target == null) throw new IllegalArgumentException("A path is required.");
        Path root = target.toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyEntry(Path source, Path target, boolean replaceExisting) throws IOException {
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        if (replaceExisting) {
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS);
        }
    }
}
