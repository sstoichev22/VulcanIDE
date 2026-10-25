package VulcanIDE;

import Intel8080.cpu.Memory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Linked VEXE2 executable: entry address and loadable segments. */
public final class VulcanExecutable {
    private static final byte[] MAGIC = "VEXE2\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 2;
    private static final int MAX_SEGMENTS = 65_536;
    private final long entryAddress;
    private final List<Segment> segments;

    /** Bytes loaded at an address. */
    public record Segment(long address, byte[] bytes) {
        public Segment {
            if (bytes == null || bytes.length > 32*1024*1024 || bytes.length>0 && Long.compareUnsigned(address+bytes.length-1,address)<0)
                throw new IllegalArgumentException("Executable segment wraps the 64-bit address space.");
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** Creates an executable; segments must not overlap or wrap. */
    public VulcanExecutable(long entryAddress, List<Segment> segments) {
        if (segments == null || segments.isEmpty() || segments.size() > MAX_SEGMENTS)
            throw new IllegalArgumentException("Executable must contain between 1 and " + MAX_SEGMENTS + " segments.");
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort((a,b)->Long.compareUnsigned(a.address(),b.address()));
        long previousEnd = 0;
        boolean hasPreviousSegment = false;
        boolean entryCovered = false;
        for (Segment segment : sorted) {
            long end = segment.address() + segment.bytes.length;
            if (segment.bytes.length == 0 || hasPreviousSegment && Long.compareUnsigned(segment.address(), previousEnd) < 0)
                throw new IllegalArgumentException("Executable segments must be nonempty and must not overlap.");
            if (Long.compareUnsigned(entryAddress-segment.address(),segment.bytes.length)<0) entryCovered = true;
            previousEnd = end;
            hasPreviousSegment = true;
        }
        if (!entryCovered) throw new IllegalArgumentException("Executable entry does not belong to a loaded segment.");
        this.entryAddress = entryAddress;
        this.segments = List.copyOf(sorted);
    }

    public long entryAddress() { return entryAddress; }
    public List<Segment> segments() { return segments; }

    /** Writes the executable file. */
    public void write(Path path) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.write(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(entryAddress);
            out.writeInt(segments.size());
            for (Segment segment : segments) {
                out.writeLong(segment.address());
                out.writeInt(segment.bytes.length);
                out.write(segment.bytes);
            }
        }
    }

    /** Reports whether a file starts with the executable header. */
    public static boolean isExecutable(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) < MAGIC.length) return false;
        try (var in = Files.newInputStream(path)) {
            return java.util.Arrays.equals(in.readNBytes(MAGIC.length), MAGIC);
        }
    }

    /** Reads and validates an executable file. */
    public static VulcanExecutable read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (!java.util.Arrays.equals(in.readNBytes(MAGIC.length), MAGIC))
                throw new IOException("Not a Vulcan VEXE2 executable.");
            int version = in.readInt();
            if (version != VERSION) throw new IOException("Unsupported Vulcan executable version " + version + ".");
            long entry = in.readLong();
            int count = in.readInt();
            if (count < 1 || count > MAX_SEGMENTS) throw new IOException("Invalid executable segment count.");
            List<Segment> segments = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long address = in.readLong();
                int length = in.readInt();
                if (length <= 0 || length > 32*1024*1024 || length > in.available())
                    throw new IOException("Invalid executable segment length.");
                byte[] bytes = in.readNBytes(length);
                if (bytes.length != length) throw new IOException("Truncated executable segment.");
                try { segments.add(new Segment(address, bytes)); }
                catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
            }
            if (in.read() != -1) throw new IOException("Executable contains trailing data.");
            try { return new VulcanExecutable(entry, segments); }
            catch (IllegalArgumentException error) { throw new IOException(error.getMessage(), error); }
        } catch (java.io.EOFException error) {
            throw new IOException("Executable header is truncated.", error);
        }
    }
}
