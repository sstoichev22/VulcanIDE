package VulcanIDE;

import Intel8080.cpu.Intel8080;
import Intel8080.cpu.Memory;
import java.util.function.Consumer;

/** The single CPU and memory shared by the debugger, display, and console, with ownership tracking. */
final class EmulationSession {
    static final EmulationSession SHARED = new EmulationSession();
    final Memory memory = new Memory();
    final Intel8080 cpu = new Intel8080(memory);
    private volatile Object owner;
    private volatile Consumer<Integer> output = value -> {};
    private volatile long revision;
    private volatile java.nio.file.Path programPath;
    private final java.util.ArrayDeque<Integer> input = new java.util.ArrayDeque<>();
    private static final java.util.concurrent.ScheduledThreadPoolExecutor INPUT_WORKER = createInputWorker();
    private java.util.concurrent.ScheduledFuture<?> inputTask;
    private boolean closed;

    private static java.util.concurrent.ScheduledThreadPoolExecutor createInputWorker() {
        var worker = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "Vulcan-console-input");
                thread.setDaemon(true);
                return thread;
            });
        worker.setRemoveOnCancelPolicy(true);
        return worker;
    }

    EmulationSession() {

        cpu.addPortOutputListener((port, value) -> {
            if (port == Intel8080.PORT_KEY_DATA) output.accept(value);
        });
    }

    synchronized long load(Object nextOwner, byte[] image, long start, long end, long entry, Consumer<Integer> sink) {
        return load(nextOwner, image, start, end, entry, sink, null);
    }

    synchronized long load(Object nextOwner, byte[] image, long start, long end, long entry,
                           Consumer<Integer> sink, java.nio.file.Path path) {
        if (start < 0 || end < start || end >= Memory.MEM_SIZE || image.length != Memory.MEM_SIZE)
            throw new IllegalArgumentException("Invalid program memory range.");
        cpu.stop();
        cancelInput();
        output = value -> {};
        cpu.reset();
        for (long address = start; address <= end; address++) memory.set(address, image[(int)address]);
        cpu.setEntryAddress(entry);
        owner = nextOwner;
        programPath = path == null ? null : path.toAbsolutePath().normalize();
        output = sink == null ? value -> {} : sink;
        return ++revision;
    }

    synchronized long loadExecutable(Object nextOwner, VulcanExecutable executable,
                                     Consumer<Integer> sink, java.nio.file.Path path) {
        cpu.stop();
        cancelInput();
        output = value -> {};
        cpu.reset();
        memory.clear();
        for (VulcanExecutable.Segment segment : executable.segments())
            memory.load(segment.address(), segment.bytes());
        cpu.setEntryAddress(executable.entryAddress());
        owner = nextOwner;
        programPath = path == null ? null : path.toAbsolutePath().normalize();
        output = sink == null ? value -> {} : sink;
        return ++revision;
    }

    boolean owns(Object candidate) { return owner == candidate; }
    long revision() { return revision; }
    boolean inputAvailable(Object candidate) { return owner == candidate && cpu.isRunning() && !cpu.isStopped(); }
    boolean hasProgramPath(java.nio.file.Path path) {
        java.nio.file.Path current = programPath;
        return path != null && current != null && current.equals(path.toAbsolutePath().normalize());
    }

    synchronized long claim(Object nextOwner, java.nio.file.Path path, Consumer<Integer> sink) {
        if (!hasProgramPath(path)) return -1;
        owner = nextOwner;
        output = sink == null ? value -> {} : sink;
        return ++revision;
    }

    synchronized boolean run(Object candidate, long expectedRevision) {
        return run(candidate, expectedRevision, null);
    }

    synchronized boolean run(Object candidate, long expectedRevision, Runnable beforeStart) {
        if (owner != candidate || revision != expectedRevision) return false;
        if (beforeStart != null) beforeStart.run();
        cpu.run();
        return true;
    }

    synchronized void stop(Object candidate) {
        if (owner == candidate) { revision++; cpu.stop(); cancelInput(); }
    }

    private void cancelInput() {
        input.clear();
        if (inputTask != null) { inputTask.cancel(false); inputTask = null; }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        revision++;
        cancelInput();
        output = value -> {};
        owner = null;
        cpu.close();
    }

    synchronized void resume(Object candidate, long expectedRevision) {
        if (owner != candidate || revision != expectedRevision) return;
        cpu.continueExecution(); cpu.run();
    }

    synchronized void reset(Object candidate, long entry) {
        if (owner != candidate) return;
        revision++; cancelInput(); cpu.reset(); cpu.setEntryAddress(entry);
    }

    synchronized boolean submitInput(Object candidate, String line) {
        if (closed || owner != candidate || cpu.isStopped() || line.length() + input.size() >= 8192) return false;
        for (int i = 0; i < line.length(); i++) if (line.charAt(i) > 255) return false;
        for (int i = 0; i < line.length(); i++) input.addLast((int) line.charAt(i));
        input.addLast(10);
        pumpInput();
        if (!input.isEmpty() && inputTask == null)
            inputTask = INPUT_WORKER.scheduleAtFixedRate(this::pumpInput, 1, 1, java.util.concurrent.TimeUnit.MILLISECONDS);
        return true;
    }

    private synchronized void pumpInput() {
        if (input.isEmpty() || cpu.isStopped()) { cancelInput(); return; }
        synchronized (cpu) {
            if ((cpu.getIOPorts()[Intel8080.PORT_KEY_STATUS] & 1) == 0) cpu.offerKeyInput(input.removeFirst());
        }
    }
}
