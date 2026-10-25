package VulcanIDE;

import C8080.Compiler;
import C8080.SourceAccess;
import Intel8080.cpu.Intel8080;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Private framed worker for the SDK adapter; all programs own one display-capable CPU and console. */
public final class McpRuntime implements AutoCloseable {
    private final EmulationSession session = new EmulationSession();
    private final Path root;
    private final byte[] console = new byte[1024 * 1024];
    private long consoleEnd;
    private String id = "";
    private String source = "";
    private volatile String stopReason = "";
    private DisplayPanel.Preset preset;
    private ScheduledFuture<?> deadline;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Vulcan-MCP-deadline");
        thread.setDaemon(true);
        return thread;
    });

    McpRuntime(Path root) throws IOException {
        this.root = root.toRealPath();
        SourceAccess.restrictTo(this.root);
    }

    /** Serves length-prefixed internal requests; stdout is reserved for worker frames, never guest text. */
    public static void main(String[] args) throws Exception {
        DataOutputStream output = new DataOutputStream(new FileOutputStream(FileDescriptor.out));
        System.setOut(System.err);
        try (McpRuntime runtime = new McpRuntime(Path.of(args[0]));
             DataInputStream input = new DataInputStream(System.in)) {
            while (true) {
                int count;
                try { count = input.readInt(); } catch (EOFException end) { break; }
                if (count < 1 || count > 8) throw new IOException("Invalid worker field count.");
                List<String> fields = new ArrayList<>();
                for (int index = 0; index < count; index++) {
                    int length = input.readInt();
                    if (length < 0 || length > 65536) throw new IOException("Invalid worker field size.");
                    byte[] bytes = input.readNBytes(length);
                    if (bytes.length != length) throw new EOFException("Truncated worker request.");
                    fields.add(new String(bytes, StandardCharsets.UTF_8));
                }
                Map<String, byte[]> result;
                try { result = runtime.execute(fields); }
                catch (Exception error) {
                    result = new LinkedHashMap<>();
                    put(result, "error", VulcanDialog.errorSummary(error));
                }
                output.writeInt(result.size() * 2);
                for (var field : result.entrySet()) {
                    writeField(output, field.getKey().getBytes(StandardCharsets.UTF_8));
                    writeField(output, field.getValue());
                }
                output.flush();
            }
        }
    }

    private static void writeField(DataOutputStream output, byte[] field) throws IOException {
        output.writeInt(field.length);
        output.write(field);
    }

    private static void put(Map<String, byte[]> result, String key, Object value) {
        result.put(key, String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    Map<String, byte[]> execute(List<String> fields) throws Exception {
        return switch (fields.get(0)) {
            case "run" -> run(fields.get(1), fields.get(2), Integer.parseInt(fields.get(3)), Integer.parseInt(fields.get(4)));
            case "observe" -> observe(fields.get(1), Long.parseLong(fields.get(2)), Boolean.parseBoolean(fields.get(3)));
            case "input" -> input(fields.get(1), fields.get(2), Boolean.parseBoolean(fields.get(3)));
            case "stop" -> stop(fields.get(1));
            case "build" -> build(fields.get(1), fields.get(2));
            case "idle" -> {
                ensureIdle();
                Map<String, byte[]> result = new LinkedHashMap<>();
                put(result, "state", "idle");
                yield result;
            }
            default -> throw new IllegalArgumentException("Unknown worker action.");
        };
    }

    private Path file(String name) throws IOException {
        Path path = root.resolve(name).normalize();
        SourceAccess.check(path);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("File not found: " + name);
        return path.toRealPath();
    }

    private void ensureIdle() {
        if (session.cpu.isRunning()) throw new IllegalStateException("A program is active. Stop it before starting another operation.");
    }

    private void requireId(String requested) {
        if (id.isEmpty() || !id.equals(requested)) throw new IllegalArgumentException("Unknown or replaced run ID.");
    }

    private VulcanLinker.Result link(Path path) throws Exception {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String text = SourceAccess.readString(path);
        if (BuildRun.is(path)) return ProgramRunner.linkBuildRun(path, text, null, null);
        if (!name.endsWith(".c") && !name.endsWith(".asm"))
            throw new IllegalArgumentException("Expected .c, .asm or build.run.");
        List<Path> includes = List.of(path.getParent(), root.resolve("src/stdlib/include"));
        if (name.endsWith(".c")) {
            ProgramEligibility.Analysis entry = ProgramEligibility.analyze(path, text, includes);
            if (entry.preprocessingFailure() != null) throw entry.preprocessingFailure();
            if (!entry.runnable()) throw new IllegalArgumentException("C source has no valid int main or _start definition. Run its build.run to link a library.");
            text = new Compiler(includes).compile(text, path, 1);
        }
        return ProgramRunner.linkWorkspace(path, text, includes, null);
    }

    private DisplayPanel.Preset loadPreset(String name) throws Exception {
        if (name.contains("/") || name.contains("\\") || name.contains(":"))
            throw new IllegalArgumentException("Use a preset name from the project's presets directory.");
        Path path = file("presets/" + name + ".preset");
        if (Files.size(path) > 1024 * 1024) throw new IllegalArgumentException("Preset exceeds one MiB.");
        DisplayPanel.Preset loaded = DisplayPanel.Preset.load(path);
        DisplayPanel.migrateLegacyStockVosPreset(loaded);
        long bytes = (long) loaded.width * loaded.height * ((loaded.bits + 7) / 8);
        if (bytes > 16 * 1024 * 1024) throw new IllegalArgumentException("MCP framebuffer capture is limited to 16 MiB.");
        return loaded;
    }

    private Map<String, byte[]> run(String name, String presetName, int timeoutMillis, int waitMillis) throws Exception {
        ensureIdle();
        if (timeoutMillis < 1 || timeoutMillis > 60000 || waitMillis < 0 || waitMillis > 1000)
            throw new IllegalArgumentException("timeout_ms must be 1..60000; wait_ms must be 0..1000.");
        Path path = file(name);
        DisplayPanel.Preset nextPreset = loadPreset(presetName);
        if (Files.size(path) > 32 * 1024 * 1024) throw new IllegalArgumentException("Program exceeds 32 MiB.");
        VulcanExecutable executable = path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                ? VulcanExecutable.read(path) : link(path).executable();
        synchronized (session) {
            if (deadline != null) deadline.cancel(false);
            id = UUID.randomUUID().toString();
            source = path.toString();
            stopReason = "";
            preset = nextPreset;
            synchronized (console) { consoleEnd = 0; Arrays.fill(console, (byte) 0); }
            String startedId = id;
            long revision = session.loadExecutable(this, executable, this::appendByte, path);
            session.run(this, revision);
            deadline = timer.schedule(() -> {
                synchronized (session) {
                    if (id.equals(startedId) && session.cpu.isRunning()) {
                        stopReason = "timeout";
                        session.stop(this);
                    }
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        }
        long until = System.nanoTime() + waitMillis * 1_000_000L;
        while (session.cpu.isRunning() && System.nanoTime() < until) Thread.sleep(2);
        return observe(id, 0, true);
    }

    private void appendByte(int value) {
        synchronized (console) {
            console[(int) (consoleEnd % console.length)] = (byte) value;
            consoleEnd++;
        }
    }

    private Map<String, byte[]> observe(String requested, long cursor, boolean includeDisplay) throws Exception {
        requireId(requested);
        Map<String, byte[]> result = new LinkedHashMap<>();
        put(result, "id", id);
        put(result, "kind", "run");
        put(result, "path", source);
        synchronized (session.cpu) {
            Intel8080 cpu = session.cpu;
            String error = cpu.getRuntimeError();
            String state = !stopReason.isEmpty() ? stopReason : error != null ? "runtime_error"
                    : cpu.isRunning() ? cpu.isHalted() ? "interrupt_wait" : "running" : "completed";
            put(result, "state", state);
            put(result, "runtime_error", error == null ? "" : error);
            if (state.equals("completed")) put(result, "exit_code", cpu.getA());
            put(result, "cpu.A", Integer.toUnsignedString(cpu.getA()));
            put(result, "cpu.BC", String.format("0x%016X", cpu.getBC()));
            put(result, "cpu.DE", String.format("0x%016X", cpu.getDE()));
            put(result, "cpu.HL", String.format("0x%016X", cpu.getHL()));
            put(result, "cpu.PC", String.format("0x%016X", cpu.getPC()));
            put(result, "cpu.SP", String.format("0x%016X", session.memory.getSp()));
            synchronized (console) {
                if (cursor < 0 || cursor > consoleEnd) throw new IllegalArgumentException("Console cursor is outside this run.");
                long first = Math.max(0, consoleEnd - console.length);
                long start = Math.max(first, cursor);
                byte[] delta = new byte[(int) Math.min(65536, consoleEnd - start)];
                for (int index = 0; index < delta.length; index++) delta[index] = console[(int) ((start + index) % console.length)];
                result.put("console_bytes", delta);
                put(result, "cursor", start + delta.length);
                put(result, "console_total", consoleEnd);
                put(result, "dropped_bytes", Math.max(0, first - cursor));
            }
            put(result, "display.preset", preset.name);
            put(result, "display.width", preset.width);
            put(result, "display.height", preset.height);
            put(result, "display.address", String.format("0x%016X", preset.address));
            if (includeDisplay) {
                try { capture(result); }
                catch (Exception failure) { put(result, "display.error", VulcanDialog.errorSummary(failure)); }
            }
            put(result, "captured_at_ms", System.currentTimeMillis());
        }
        return result;
    }

    private void capture(Map<String, byte[]> result) throws IOException {
        int[] offsets = new int[3], widths = new int[3];
        String[] ranges = preset.mode == 2 ? new String[]{preset.red, preset.green, preset.blue}
                : preset.bits == 16 ? new String[]{"11:5", "5:6", "0:5"} : new String[]{"0:8", "8:8", "16:8"};
        for (int index = 0; index < 3; index++) {
            String[] range = ranges[index].split(":");
            offsets[index] = Integer.parseInt(range[0]);
            widths[index] = Integer.parseInt(range[1]);
        }
        double scale = Math.min(1.0, Math.min(1280.0 / preset.width, 720.0 / preset.height));
        int width = Math.max(1, (int) (preset.width * scale));
        int height = Math.max(1, (int) (preset.height * scale));
        int length = Math.toIntExact((long) preset.width * preset.height * ((preset.bits + 7) / 8));
        byte[] snapshot = session.memory.readRange(preset.address, length);
        var frame = DisplayPanel.renderFrame(session.memory, snapshot, preset.address, preset.width, preset.height,
                width, height, preset.bits, preset.mode, preset.colors, offsets, widths);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(frame, "png", png)) throw new IOException("PNG encoder unavailable.");
        result.put("png", png.toByteArray());
        put(result, "display.image_width", width);
        put(result, "display.image_height", height);
    }

    private Map<String, byte[]> input(String requested, String text, boolean line) {
        requireId(requested);
        if (!session.cpu.isRunning()) throw new IllegalStateException("Program is no longer running.");
        boolean accepted;
        if (line) accepted = session.submitInput(this, text);
        else {
            if (text.length() > 4096 || text.chars().anyMatch(value -> value > 255))
                throw new IllegalArgumentException("Raw input must contain at most 4096 Latin-1 bytes.");
            synchronized (session.cpu) {
                accepted = (session.cpu.getIOPorts()[Intel8080.PORT_KEY_STATUS] & 1) == 0;
                if (accepted) for (int index = 0; index < text.length(); index++) session.cpu.offerKeyInput(text.charAt(index));
            }
        }
        Map<String, byte[]> result = new LinkedHashMap<>();
        put(result, "id", id);
        put(result, "accepted", accepted);
        return result;
    }

    private Map<String, byte[]> stop(String requested) throws Exception {
        requireId(requested);
        synchronized (session) {
            if (session.cpu.isRunning()) { stopReason = "stopped"; session.stop(this); }
            if (deadline != null) deadline.cancel(false);
        }
        return observe(id, 0, true);
    }

    private Map<String, byte[]> build(String name, String outputName) throws Exception {
        ensureIdle();
        Path path = file(name);
        VulcanLinker.Result linked = link(path);
        if (outputName.isBlank() || !outputName.matches("[A-Za-z0-9_. -]+") || outputName.equals(".") || outputName.equals(".."))
            throw new IllegalArgumentException("Output must be a plain executable filename.");
        Path directory = root.resolve("builds/mcp-programs");
        SourceAccess.check(directory);
        Files.createDirectories(directory);
        Path output = directory.resolve(outputName.toLowerCase(Locale.ROOT).endsWith(".exe") ? outputName : outputName + ".exe");
        SourceAccess.check(output);
        Path temporary = Files.createTempFile(directory, ".mcp-", ".tmp");
        try {
            linked.executable().write(temporary);
            try { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        Map<String, byte[]> result = new LinkedHashMap<>();
        put(result, "kind", "build");
        put(result, "state", "completed");
        put(result, "path", output);
        put(result, "warnings", String.join("\n", linked.warnings()));
        return result;
    }

    /** Stops the owned machine and releases its deadline and input workers. */
    @Override public void close() {
        timer.shutdownNow();
        session.close();
    }
}
