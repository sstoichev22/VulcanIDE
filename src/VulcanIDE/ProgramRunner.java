package VulcanIDE;

import Intel8080.assembler.Assembler;
import Intel8080.cpu.Intel8080;
import Intel8080.cpu.Memory;
import C8080.Compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Builds and runs one program at a time on a private CPU, with stop and close lifecycles. */
public class ProgramRunner {
    private static final int EXECUTABLE_LOAD_ADDRESS = Memory.PROGRAM_START;
    private final EmulationSession session = new EmulationSession();
    /** Package-private test barrier invoked between run reservation and asynchronous work. */
    private final Runnable afterRunStarted;
    private volatile boolean building;
    private Consumer<String> lifecycleOutput=text->{};
    private boolean endReported=true;
    private void reportEnd(long generation,String detail){synchronized(lifecycleLock){if(generation!=runGeneration||endReported)return;endReported=true;lifecycleOutput.accept("\nProgram ended "+detail+"\n");}}
    private volatile Consumer<String> buildStatus = text -> {};
    public void setBuildStatusListener(Consumer<String> listener) { buildStatus = listener == null ? text -> {} : listener; }
    private volatile Runnable executionStartedListener = () -> {};
    public void setExecutionStartedListener(Runnable listener) { executionStartedListener = listener == null ? () -> {} : listener; }
    private final Intel8080 cpu = session.cpu;
    private final Object lifecycleLock = new Object();
    private volatile long runGeneration;
    private ByteOutputGate byteOutputGate;
    private boolean closed;
    private static final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "Vulcan-8080-Runner");
        thread.setDaemon(true);
        return thread;
    });

    /** Admits byte sinks until stop closes the gate, then lets stop await admitted callbacks without holding lifecycleLock. */
    private static final class ByteOutputGate {
        private boolean accepting = true;
        private int inFlight;
        private final ThreadLocal<Integer> callbackDepth = ThreadLocal.withInitial(() -> 0);

        /** Admits one callback unless stop has already closed this run's output gate. */
        synchronized boolean enter() {
            if (!accepting) return false;
            inFlight++;
            callbackDepth.set(callbackDepth.get() + 1);
            return true;
        }

        /** Releases one admitted callback and wakes a stop waiting for the final sink to return. */
        void exit() {
            int depth = callbackDepth.get();
            if (depth <= 1) callbackDepth.remove();
            else callbackDepth.set(depth - 1);
            synchronized (this) {
                inFlight--;
                notifyAll();
            }
        }

        /** Prevents new byte sinks from starting while preserving already-admitted callbacks. */
        synchronized void closeAdmission() {
            accepting = false;
        }

        /** Reports whether stop was called from inside this gate's current byte sink. */
        boolean isCallbackThread() {
            int depth = callbackDepth.get();
            if (depth == 0) callbackDepth.remove();
            return depth > 0;
        }

        /** Waits for every admitted sink to return and restores interruption after draining completes. */
        void awaitDrained() {
            boolean interrupted = false;
            synchronized (this) {
                while (inFlight > 0) {
                    try {
                        wait();
                    } catch (InterruptedException error) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Creates a runner with no lifecycle test hook. */
    public ProgramRunner() {
        this(() -> {});
    }

    /**
     * Creates a runner with an optional post-reservation hook for deterministic lifecycle checks.
     * Production callers use the public constructor; the hook runs before asynchronous build work.
     */
    ProgramRunner(Runnable afterRunStarted) {
        this.afterRunStarted = afterRunStarted == null ? () -> {} : afterRunStarted;
    }

    public void runSource(String source, Consumer<String> output) { runSource(source, output, b -> {}, () -> {}); }

    /** Assembles and runs source, sending text output and calling finished on normal completion. */
    public void runSource(String source, Consumer<String> output, Runnable finished) {
        runSource(source, output, b -> {}, finished);
    }

    /** Assembles and runs source with a separate raw byte output sink. */
    public void runSource(String source, Consumer<String> output, Consumer<Integer> byteOutput, Runnable finished) {
        runSource(source, output, byteOutput, finished, null);
    }

    /** Assembles and runs source from a file path used in diagnostics. */
    public void runSource(String source, Consumer<String> output, Consumer<Integer> byteOutput, Runnable finished, Path path) {
        Consumer<String> textOutput = output == null ? text -> { } : output;
        Consumer<Integer> byteListener = byteOutput == null ? value -> { } : byteOutput;
        Runnable completion = finished == null ? () -> { } : finished;
        long generation = beginRun(textOutput);
        if (generation != runGeneration) return;
        afterRunStarted.run();
        if (generation != runGeneration) return;
        ByteOutputGate outputGate = byteOutputGateFor(generation);
        if (outputGate == null) return;
        Consumer<String> currentTextOutput = text -> {
            synchronized (lifecycleLock) { if (generation == runGeneration) textOutput.accept(text); }
        };
        Consumer<Integer> currentByteOutput = value -> {
            if (generation != runGeneration || !outputGate.enter()) return;
            try {
                if (generation == runGeneration) byteListener.accept(value);
            } finally {
                outputGate.exit();
            }
        };
        Consumer<String> status = text -> { synchronized(lifecycleLock) { if(generation==runGeneration)buildStatus.accept(text); } };
        executor.submit(() -> { try {
            if(generation!=runGeneration)return;
            String input = source == null ? "" : source;
            if (BuildRun.is(path)) {
                VulcanLinker.Result linked = linkBuildRun(path, input, null, status);
                for (String warning : linked.warnings()) currentTextOutput.accept("Warning: " + warning + "\n");
                startExecutable(linked.executable(), currentTextOutput, currentByteOutput, completion, generation, path);
                return;
            }
            if (path != null) {
                String extension = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                Path parent = path.toAbsolutePath().normalize().getParent();
                List<Path> includes = new java.util.ArrayList<>();
                if (parent != null) includes.add(parent);
                includes.add(Path.of("src", "stdlib", "include"));
                if (extension.endsWith(".c")) {
                    ProgramEligibility.Analysis eligibility=ProgramEligibility.analyze(path,input,includes);
                    if(eligibility.preprocessingFailure()!=null)throw eligibility.preprocessingFailure();
                    if(!eligibility.runnable()) {
                        currentTextOutput.accept("This C translation unit has no valid int main definition. Library source cannot run directly.\n");
                        status.accept(null);
                        completeIfCurrent(generation, completion, "with a build error.");
                        return;
                    }
                }
                String assemblySource;
                if (extension.endsWith(".c")) {
                    status.accept("Compiling " + path.getFileName());
                    assemblySource = new Compiler(includes).compile(input, path, 1);
                }
                else if (extension.endsWith(".asm")) assemblySource = input;
                else assemblySource = null;
                if (assemblySource != null) {
                    VulcanLinker.Result linked = linkWorkspace(path, assemblySource, includes, null, status);
                    for (String warning : linked.warnings()) currentTextOutput.accept("Warning: " + warning + "\n");
                    startExecutable(linked.executable(), currentTextOutput, currentByteOutput, completion, generation, path);
                    return;
                }
            }
            if (path != null) status.accept("Assembling " + path.getFileName());
            Assembler.AssemblyResult assembly;
            assembly = Assembler.assembleWithInfo(input);
            byte[] image = assembly.image();
            for (String warning : assembly.warnings()) currentTextOutput.accept("Warning: " + warning + "\n");
            startImage(image, Math.toIntExact(assembly.info().programStart()), Math.toIntExact(assembly.info().programEnd()), Math.toIntExact(assembly.info().codeStart()), currentTextOutput, currentByteOutput, completion, generation, path);
        } catch (Exception e) {
            status.accept(null);
            currentTextOutput.accept("\u001B[31m" + formatError(path==null?e:BuildDiagnostics.source(e,path,source)) + "\u001B[0m");
            completeIfCurrent(generation, completion, "with a build error.");
        } });
    }

    public void runBinary(Path path, Consumer<String> output) { runBinary(path, output, b -> {}, () -> {}); }

    /** Runs an executable or raw program file. */
    public void runBinary(Path path, Consumer<String> output, Runnable finished) {
        runBinary(path, output, b -> {}, finished);
    }

    /** Runs a program file with a separate raw byte output sink. */
    public void runBinary(Path path, Consumer<String> output, Consumer<Integer> byteOutput, Runnable finished) {
        runBinary(path, output, byteOutput, finished, path);
    }

    /** Runs a program file whose console output is attributed to a source path. */
    public void runBinary(Path path, Consumer<String> output, Consumer<Integer> byteOutput, Runnable finished, Path programPath) {
        Consumer<String> textOutput = output == null ? text -> { } : output;
        Consumer<Integer> byteListener = byteOutput == null ? value -> { } : byteOutput;
        Runnable completion = finished == null ? () -> { } : finished;
        long generation = beginRun(textOutput);
        if (generation != runGeneration) return;
        afterRunStarted.run();
        if (generation != runGeneration) return;
        ByteOutputGate outputGate = byteOutputGateFor(generation);
        if (outputGate == null) return;
        Consumer<String> currentTextOutput = text -> {
            synchronized (lifecycleLock) { if (generation == runGeneration) textOutput.accept(text); }
        };
        Consumer<Integer> currentByteOutput = value -> {
            if (generation != runGeneration || !outputGate.enter()) return;
            try {
                if (generation == runGeneration) byteListener.accept(value);
            } finally {
                outputGate.exit();
            }
        };
        try {
            if (!Files.isRegularFile(path)) {
                setBuildingFalseIfCurrent(generation);
                currentTextOutput.accept("File not found.\n");
                completeIfCurrent(generation, completion, null);
                return;
            }
            if (VulcanExecutable.isExecutable(path)) {
                startExecutable(VulcanExecutable.read(path), currentTextOutput, currentByteOutput,
                        completion, generation, programPath);
                return;
            }
            long maximumRawSize = Memory.MEM_SIZE - (long) EXECUTABLE_LOAD_ADDRESS;
            if (Files.size(path) > maximumRawSize) throw new IllegalArgumentException("Executable is too large for a raw 64 KiB snapshot.");
            byte[] bytes = Files.readAllBytes(path);
            byte[] image = new byte[Memory.MEM_SIZE];
            if (bytes.length > image.length - EXECUTABLE_LOAD_ADDRESS) {
                throw new IllegalArgumentException("Executable is too large for memory.");
            }
            System.arraycopy(bytes, 0, image, EXECUTABLE_LOAD_ADDRESS, bytes.length);
            startImage(image, EXECUTABLE_LOAD_ADDRESS, EXECUTABLE_LOAD_ADDRESS + bytes.length - 1, EXECUTABLE_LOAD_ADDRESS, currentTextOutput, currentByteOutput, completion, generation, programPath);
        } catch (Exception e) {
            setBuildingFalseIfCurrent(generation);
            currentTextOutput.accept("\u001B[31m" + VulcanDialog.errorSummary(e) + "\n\u001B[0m");
            completeIfCurrent(generation, completion, null);
        }
    }

    private void startExecutable(VulcanExecutable executable, Consumer<String> output,
                                  Consumer<Integer> byteOutput, Runnable finished,
                                  long generation, Path path) {
        final long loadedRevision;
        synchronized (lifecycleLock) {
            if (generation != runGeneration) return;
            loadedRevision = session.loadExecutable(this, executable, byteOutput, path);
        }
        executor.submit(() -> {
            try {
                synchronized (lifecycleLock) {
                    if (generation != runGeneration) return;
                    boolean started = session.run(this, loadedRevision, () -> {
                        building=false;
                        buildStatus.accept(null);
                        executionStartedListener.run();
                    });
                    if (!started) return;
                }
                while (generation == runGeneration && session.owns(this)
                        && session.revision() == loadedRevision && cpu.isRunning()) Thread.sleep(10);
                if (generation == runGeneration && session.owns(this) && cpu.getRuntimeError() != null)
                    output.accept("\u001B[31m" + cpu.getRuntimeError() + "\n\u001B[0m");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (lifecycleLock) {
                    if (generation == runGeneration) {
                        reportEnd(generation, cpu.getRuntimeError() == null
                                ? "with exit code " + cpu.getA() + "."
                                : "with error: " + cpu.getRuntimeError());
                        if (generation == runGeneration) finished.run();
                    }
                }
            }
        });
    }

    static VulcanLinker.Result linkWorkspace(Path sourceFile, String currentAssembly,
                                                     List<Path> includes, Path output) throws Exception {
        return linkWorkspace(sourceFile, currentAssembly, includes, output, null);
    }

    static VulcanLinker.Result linkWorkspace(Path sourceFile, String currentAssembly,
                                                     List<Path> includes, Path output, Consumer<String> status) throws Exception {
        Path source = sourceFile.toAbsolutePath().normalize();
        Path parent = source.getParent();
        if (parent == null) throw new IllegalArgumentException("Source file has no parent directory.");
        if (status != null) status.accept("Assembling " + source.getFileName());
        VulcanObject current = VulcanObject.assembleSource(source.toAbsolutePath().normalize().toString(), currentAssembly);
        List<VulcanObject> selected = new ArrayList<>();
        selected.add(current);

        Map<Path, VulcanObject> candidates = new LinkedHashMap<>();
        Map<Path, String> candidateErrors = new LinkedHashMap<>();
        for (Path candidate : sourceUnits(parent)) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (absolute.equals(source)) continue;
            try {
                candidates.put(absolute, compileUnit(candidate, includes, status));
            } catch (Exception error) {
                candidateErrors.put(absolute, VulcanDialog.errorSummary(error));
            }
        }

        Set<Path> linkedPaths = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            Set<String> definitions = new HashSet<>();
            for (VulcanObject object : selected) {
                definitions.addAll(object.publicSymbols());
                if (object.entrySymbol() != null) definitions.add(object.entrySymbol());
            }
            Set<String> needed = new java.util.LinkedHashSet<>();
            for (VulcanObject object : selected) {
                for (String symbol : object.externalSymbols())
                    if (!object.definedSymbols().contains(symbol)) needed.add(symbol);
            }
            needed.removeAll(definitions);
            for (String symbol : needed) {
                List<Map.Entry<Path, VulcanObject>> providers = candidates.entrySet().stream()
                        .filter(entry -> !linkedPaths.contains(entry.getKey()))
                        .filter(entry -> entry.getValue().publicSymbols().contains(symbol)).toList();
                if (providers.size() > 1) throw new IllegalArgumentException("Multiple sibling source files export " + symbol + ". Link the intended unit explicitly with vcc.");
                if (providers.size() == 1) {
                    Map.Entry<Path, VulcanObject> provider = providers.get(0);
                    selected.add(provider.getValue());
                    linkedPaths.add(provider.getKey());
                    changed = true;
                }
            }
        } while (changed);
        if (status != null) status.accept("Linking " + source.getFileName());
        try {
            return VulcanLinker.link(selected, output, includes);
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().contains("Unresolved external") && !candidateErrors.isEmpty()) {
                StringBuilder detail = new StringBuilder(error.getMessage()).append(" Sibling source candidates that failed to compile:");
                candidateErrors.forEach((path, message) -> detail.append("\n  ").append(path.getFileName()).append(": ").append(message));
                throw new IllegalArgumentException(detail.toString(), error);
            }
            throw error;
        }
    }

    /** Link candidates: the files in the nearest build.run at or above the folder, or every .c/.asm file in the folder. */
    static List<Path> sourceUnits(Path folder) throws java.io.IOException {
        Path buildRun = BuildRun.find(folder);
        if (buildRun != null) return BuildRun.units(buildRun);
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).filter(ProgramRunner::isSourceUnit).sorted().toList();
        }
    }

    /** Compiles (C) or reads (assembly) one source file into an object. */
    private static VulcanObject compileUnit(Path unit, List<Path> includes, Consumer<String> status) throws Exception {
        String assembly;
        if (unit.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".c")) {
            if (status != null) status.accept("Compiling " + unit.getFileName());
            assembly = new Compiler(includes).compile(C8080.SourceAccess.readString(unit), unit, 1);
        } else assembly = C8080.SourceAccess.readString(unit);
        if (status != null) status.accept("Assembling " + unit.getFileName());
        return VulcanObject.assembleSource(unit.toAbsolutePath().normalize().toString(), assembly);
    }

    /** Builds every file listed in build.run text, like make, and links them into one executable. */
    static VulcanLinker.Result linkBuildRun(Path buildRun, String text, Path output, Consumer<String> status) throws Exception {
        List<Path> units = BuildRun.units(buildRun, text);
        if (units.isEmpty()) throw new IllegalArgumentException(BuildRun.NAME + " lists no .c or .asm files.");
        List<Path> stdlib = List.of(Path.of("src", "stdlib", "include"));
        List<VulcanObject> objects = new ArrayList<>();
        for (Path unit : units) {
            try {
                objects.add(compileUnit(unit, List.of(unit.getParent(), stdlib.get(0)), status));
            } catch (Exception error) {
                throw BuildDiagnostics.source(error, unit, C8080.SourceAccess.readString(unit));
            }
        }
        if (status != null) status.accept("Linking " + BuildRun.NAME);
        return VulcanLinker.link(objects, output, stdlib);
    }

    private static boolean isSourceUnit(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".c") || name.endsWith(".asm");
    }

    private void startImage(byte[] image, int start, int end, int entry, Consumer<String> output,
                            Consumer<Integer> byteOutput, Runnable finished, long generation, Path path) {
        final long loadedRevision;
        synchronized (lifecycleLock) {
            if (generation != runGeneration) return;
            loadedRevision = session.load(this, image, start, end, entry, byteOutput, path);
        }
        executor.submit(() -> {
            try {
                synchronized (lifecycleLock) {
                    if (generation != runGeneration) return;
                    boolean started = session.run(this, loadedRevision, () -> {
                        building=false;
                        buildStatus.accept(null);
                        executionStartedListener.run();
                    });
                    if (!started) return;
                }
                while (generation == runGeneration && session.owns(this) &&
                        session.revision() == loadedRevision && cpu.isRunning()) Thread.sleep(10);
                if (generation == runGeneration && session.owns(this) && cpu.getRuntimeError() != null)
                    output.accept("\u001B[31m" + cpu.getRuntimeError() + "\n\u001B[0m");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (lifecycleLock) {
                    if (generation == runGeneration) {
                        reportEnd(generation, cpu.getRuntimeError() == null
                                ? "with exit code " + cpu.getA() + "."
                                : "with error: " + cpu.getRuntimeError());
                        if (generation == runGeneration) finished.run();
                    }
                }
            }
        });
    }

    /** Stops the current run and emits the user-stop marker. */
    public void stop() {
        long generation;
        ByteOutputGate outputGate;
        synchronized (lifecycleLock) {
            generation = runGeneration;
            outputGate = byteOutputGate;
            if (outputGate != null) outputGate.closeAdmission();
        }
        if (outputGate != null && outputGate.isCallbackThread()) {
            executor.submit(() -> finishStop(generation, outputGate));
            return;
        }
        finishStop(generation, outputGate);
    }

    /** Drains admitted byte sinks, then stops only the captured run while preserving marker-callback replacements. */
    private void finishStop(long generation, ByteOutputGate outputGate) {
        if (outputGate != null) outputGate.awaitDrained();
        synchronized (lifecycleLock) {
            if (generation != runGeneration) return;
            session.stop(this);
            reportEnd(generation, "by user stop.");
            if (generation != runGeneration) return;
            runGeneration++;
            byteOutputGate = null;
            building = false;
            lifecycleOutput = text -> {};
            endReported = true;
            buildStatus.accept(null);
        }
    }

    /** Stops silently and releases the runner's threads. */
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            runGeneration++;
            if (byteOutputGate != null) byteOutputGate.closeAdmission();
            byteOutputGate = null;
            building = false;
            session.close();
            lifecycleOutput = text -> {};
            endReported = true;
            buildStatus.accept(null);
            buildStatus = text -> {};
            executionStartedListener = () -> {};
        }
    }

    /** Replaces active execution and publishes this run's output and build state as one transition. */
    private long beginRun(Consumer<String> output) {
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("The program runner is closed.");
            long generation = ++runGeneration;
            session.stop(this);
            byteOutputGate = new ByteOutputGate();
            building = true;
            lifecycleOutput = output;
            endReported = false;
            buildStatus.accept(null);
            return generation;
        }
    }

    /** Returns the byte-output gate only while the supplied generation is still current. */
    private ByteOutputGate byteOutputGateFor(long generation) {
        synchronized (lifecycleLock) {
            if (generation != runGeneration) return null;
            return byteOutputGate;
        }
    }

    /**
     * Completes only the current generation and reports an optional end detail before its callback.
     * The generation is checked again after reporting because output can stop or replace this run.
     */
    private void completeIfCurrent(long generation, Runnable completion, String endDetail) {
        synchronized (lifecycleLock) {
            if (generation != runGeneration) return;
            building = false;
            if (endDetail != null) reportEnd(generation, endDetail);
            if (generation == runGeneration) completion.run();
        }
    }

    /** Clears the build indicator only while the given generation still owns the lifecycle. */
    private void setBuildingFalseIfCurrent(long generation) {
        synchronized (lifecycleLock) {
            if (generation != runGeneration) return;
            building = false;
        }
    }

    /** Assembles a source file to a raw binary beside it and returns the binary path. */
    public static Path assembleToBinary(Path sourceFile) throws Exception {
        return assembleToBinary(sourceFile, null, text -> {});
    }

    /** Assembles a source file to a raw binary with the requested name. */
    public static Path assembleToBinary(Path sourceFile, String requestedName) throws Exception {
        return assembleToBinary(sourceFile, requestedName, text -> {});
    }

    static Path assembleToBinary(Path sourceFile, String requestedName, Consumer<String> status) throws Exception {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) throw new IllegalArgumentException("Source file not found.");
        String extension = sourceFile.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!extension.endsWith(".asm") && !extension.endsWith(".c")) throw new IllegalArgumentException("Build accepts .asm and .c source files.");
        String source = Files.readString(sourceFile);
        Path sourceParent = sourceFile.toAbsolutePath().normalize().getParent();
        List<Path> includes = new java.util.ArrayList<>();
        if (sourceParent != null) includes.add(sourceParent);
        includes.add(Path.of("src", "stdlib", "include"));
        String assembly;
        if (extension.endsWith(".c")) {
            status.accept("Compiling " + sourceFile.getFileName());
            assembly = new Compiler(includes).compile(source, sourceFile, 1);
        } else assembly = source;

        String base;
        if (requestedName == null || requestedName.isBlank()) {
            String name = sourceFile.getFileName().toString();
            int dot = name.lastIndexOf('.');
            base = dot > 0 ? name.substring(0, dot) : name;
        } else base = requestedName.trim();
        if (base.toLowerCase().endsWith(".exe")) base = base.substring(0, base.length() - 4);
        if (base.isBlank()) base = "program";
        if (base.equals(".") || base.equals("..") || base.matches(".*[\\\\/:*?\"<>|].*")) {
            throw new IllegalArgumentException("The output name must be a file name without path separators or reserved characters.");
        }

        Path output = sourceFile.resolveSibling(base + ".exe");
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path temporary = Files.createTempFile(parent, ".vulcan-link-", ".tmp");
        try {
            linkWorkspace(sourceFile, assembly, includes, temporary, status);
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return output;
    }

    public boolean isExecuting() { return session.owns(this) && cpu.isRunning(); }
    /** Reports whether a run is building or executing. */
    public boolean isRunning() {
        Intel8080 instance = cpu;
        return building || session.owns(this) && instance.isRunning();
    }

    public boolean submitInput(String line) { return session.submitInput(this, line); }

    private static String formatError(Throwable e) {
        return VulcanDialog.errorSummary(e) + "\n";
    }
}
