package VulcanIDE;

import C8080.Compiler;
import Intel8080.assembler.Assembler;
import Intel8080.cpu.CPUStateListener;
import Intel8080.cpu.Intel8080;
import Intel8080.cpu.Memory;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Debugger tab: loads programs into the shared session and shows registers, memory, and disassembly. */
public class DebuggerTab extends JPanel implements CPUStateListener {
    private final SettingsManager settings;
    private final WorkspaceViews views = new WorkspaceViews();
    private final EmulationSession session = EmulationSession.SHARED;
    private long loadedRevision;
    private boolean executionStarted;
    private final Memory memory;
    private final Intel8080 cpu;
    private final DisassemblerPanel disassembler;
    private final MemoryPanel memoryPanel;
    private final DebugConsolePanel debugConsole;
    private final JLabel fileLabel;
    private final RegistersPanel registersPanel;
    private final JButton runButton;
    private final JButton stopButton;
    private final JButton stepButton;
    private final JButton resetButton;
    private final Timer controlsTimer;
    private final FileSystemPanel fileSystem;
    private java.util.function.Consumer<Path> deletionListener = path -> {};
    private BooleanSupplier beforeProgramLoad = () -> true;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Vulcan-Debugger-CPU");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ProgramInfo programInfo = ProgramInfo.empty();
    private volatile Path programFile;
    private final AtomicBoolean runtimeErrorShown = new AtomicBoolean();
    private long entryAddress=Memory.PROGRAM_START;
    private volatile boolean shuttingDown;

    /** Creates the debugger with its file panel and CPU views. */
    public DebuggerTab(SettingsManager settings) {
        this.settings = settings;
        memory = session.memory;
        cpu = session.cpu;
        setLayout(new BorderLayout());
        setBackground(settings.getColor("panelBackground"));

        registersPanel = new RegistersPanel(cpu, settings);
        disassembler = new DisassemblerPanel(cpu, settings);
        memoryPanel = new MemoryPanel(cpu, settings);
        debugConsole = new DebugConsolePanel(settings);
        debugConsole.setInputHandler(line -> session.submitInput(this, line));
        debugConsole.setInputAvailability(() -> session.inputAvailable(this));
        fileLabel = new JLabel("");
        fileLabel.setFont(new Font(settings.getApplicationFont(), Font.BOLD, settings.getFontSize("debuggerFilename")));
        fileLabel.setForeground(settings.getColor("text"));
        fileSystem = new FileSystemPanel(settings, this::debugAsync, "debuggerFilePanel");
        fileSystem.setDeletionListener(path -> deletionListener.accept(path));

        JPanel header = new JPanel(new BorderLayout());
        fileLabel.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(fileLabel, BorderLayout.CENTER);
        runButton = controlButton(Icons.vector("run", 16, new Color(47, 184, 92)), "Run", () -> {
            if (cpu.isPaused()) continueExecution(); else run();
        });
        stopButton = controlButton(Icons.vector("stop", 16, settings.getColor("stopButton")), "Stop", this::stop);
        stepButton = controlButton(Icons.fit(Icons.STEP_ICON, 16, 16), "Step", this::step);
        resetButton = controlButton(Icons.fit(Icons.RESET_ICON, 16, 16), "Reset", this::reset);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        controls.setOpaque(false);
        for (JButton button : new JButton[]{runButton, stopButton, stepButton, resetButton}) controls.add(button);
        header.add(controls, BorderLayout.EAST);
        header.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        controlsTimer = new Timer(120, event -> refreshControlState());
        addHierarchyListener(event -> { if (isShowing()) controlsTimer.start(); else controlsTimer.stop(); });
        refreshControlState();

        JSplitPane codeSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, disassembler, memoryPanel);
        codeSplit.setDividerLocation(650);
        codeSplit.setContinuousLayout(true);
        codeSplit.setBorder(null);
        Theme.initializeDivider(codeSplit, 0.45);

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeSplit, debugConsole);
        right.setDividerLocation(600);
        right.setContinuousLayout(true);
        right.setBorder(null);
        right.setResizeWeight(0.78);
        Theme.initializeDivider(right, 0.78);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, registersPanel, right);
        main.setDividerLocation(220);
        main.setContinuousLayout(true);
        main.setBorder(null);

        add(header, BorderLayout.NORTH);
        JSplitPane workspace = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileSystem, main);
        workspace.setDividerLocation(220);
        workspace.setResizeWeight(0.0);
        workspace.setBorder(null);
        views.addSplit("Files", workspace, true);
        views.addSplit("Registers", main, true);
        views.addSplit("Instructions", codeSplit, true);
        views.addSplit("Memory", codeSplit, false);
        views.addConsole("Console", right, false, debugConsole);
        add(workspace, BorderLayout.CENTER);
        cpu.addStateListener(this);
    }

    private JButton controlButton(Icon icon, String name, Runnable action) {
        JButton button = new JButton(icon);
        button.setToolTipText(name);
        button.setPreferredSize(new Dimension(31, 27));
        button.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.addActionListener(event -> { action.run(); refreshControlState(); if(button==runButton)RunningPrograms.flash(button); });
        return button;
    }

    private volatile boolean wasRunning;
    private void refreshControlState() {
        boolean loaded = programInfo.loaded();
        boolean running = cpu.isRunning();
        boolean paused = cpu.isPaused();
        if(wasRunning&&!running&&session.owns(this))debugConsole.appendProgramEnded(cpu.getRuntimeError()==null?"Program ended with exit code "+cpu.getA()+".":"Program ended with error: "+cpu.getRuntimeError());
        wasRunning=running;
        runButton.setEnabled(loaded && (!running || paused));
        runButton.setIcon(Icons.vector(running && !paused ? "running" : "run", 16, new Color(47, 184, 92)));
        runButton.setToolTipText(paused ? "Continue" : "Run");
        stopButton.setEnabled(loaded && (running || paused));
        stepButton.setEnabled(loaded && (!running || paused) && !cpu.isHalted());
        resetButton.setEnabled(loaded);
    }

    /** Loads a program for debugging after the save gate allows it. */
    public void debug(Path path) {
        if (path == null || !Files.isRegularFile(path)) return;
        Path absolute = path.toAbsolutePath().normalize();
        String name = absolute.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        boolean assembly = name.endsWith(".asm");
        boolean executable = name.endsWith(".exe");
        boolean cSource = name.endsWith(".c");
        if (!assembly && !executable && !cSource) return;

        long generation = ++buildGeneration;
        debugConsole.setBuildStatus((cSource ? "Compiling " : "Assembling ") + absolute.getFileName());
        try {
            PreparedProgram prepared=prepare(absolute, text -> publishBuildStatus(generation, text));
            if (generation != buildGeneration || shuttingDown) return;
            debugConsole.setBuildStatus(null);
            applyProgram(absolute,prepared);
        } catch (Exception e) {
            clearProgram();
            debugConsole.setBuildStatus(null);
            debugConsole.appendError(VulcanDialog.errorSummary(e) + "\n");
        }
    }

    private record PreparedProgram(byte[] image,ProgramInfo info,VulcanExecutable executable){}
    private PreparedProgram prepare(Path absolute, Consumer<String> status)throws Exception{
        String name=absolute.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        boolean assembly=name.endsWith(".asm"),cSource=name.endsWith(".c");
            byte[] image;
            String sourceText = null;
            ProgramInfo info;
            VulcanExecutable executableImage = null;

            if (assembly || cSource) {
                sourceText = Files.readString(absolute, StandardCharsets.UTF_8);
                if (cSource) {
                    Path parent = absolute.getParent();
                    java.util.List<Path> includes = new java.util.ArrayList<>();
                    if (parent != null) includes.add(parent);
                    includes.add(Path.of("src", "stdlib", "include"));
                    Compiler compiler = new Compiler(includes);
                    sourceText = compiler.compile(sourceText, absolute);
                }
                java.util.List<Path> includes=new java.util.ArrayList<>();if(absolute.getParent()!=null)includes.add(absolute.getParent());includes.add(Path.of("src","stdlib","include"));
                VulcanLinker.Result result=ProgramRunner.linkWorkspace(absolute,sourceText,includes,null,status);
                executableImage=result.executable();image=null;
                long entry=executableImage.entryAddress();
                info=new ProgramInfo(entry,entry,entry,entry,-1,-1,Map.of(),Map.of());
            } else if (name.endsWith(".exe")) {
                if (VulcanExecutable.isExecutable(absolute)) {
                    executableImage = VulcanExecutable.read(absolute);
                    long entry = executableImage.entryAddress();
                    info = new ProgramInfo(entry, entry, entry, entry, -1, -1, Map.of(), Map.of());
                    image = null;
                } else {
                    byte[] bytes = RawProgramImage.read(absolute, Memory.MEM_SIZE - Memory.PROGRAM_START);
                    image = new byte[Memory.MEM_SIZE];
                    System.arraycopy(bytes, 0, image, Memory.PROGRAM_START, bytes.length);
                    info = new ProgramInfo(Memory.PROGRAM_START,
                            Memory.PROGRAM_START + bytes.length - 1,
                            Memory.PROGRAM_START,
                            Memory.PROGRAM_START + bytes.length - 1,
                            -1, -1, Map.of(), Map.of());
                }
            } else throw new IllegalArgumentException("Unsupported debugger file");

        return new PreparedProgram(image,info,executableImage);
    }
    private void applyProgram(Path absolute,PreparedProgram prepared){
        if(shuttingDown)return;
        byte[] image=prepared.image();ProgramInfo info=prepared.info();VulcanExecutable executableImage=prepared.executable();
        settings.setCurrentFile(absolute);
            runtimeErrorShown.set(false);

            stop();
            loadedRevision = executableImage == null
                    ? session.load(this, image, info.start(), info.end(), info.codeStart(), debugConsole::appendByte, absolute)
                    : session.loadExecutable(this, executableImage, debugConsole::appendByte, absolute);
            executionStarted = false;
            entryAddress=executableImage==null?info.codeStart():executableImage.entryAddress();
            programFile = absolute;
            programInfo = info;
            fileLabel.setText(absolute.getFileName().toString());
            if(executableImage==null){disassembler.setProgramRange(programInfo.codeStart(), programInfo.codeEnd());memoryPanel.setProgramRange(programInfo.start(), programInfo.end());}
            else {disassembler.setExecutable(executableImage);memoryPanel.setProgramRange(entryAddress,entryAddress+511);}
            refreshControlState();
    }
    private volatile long buildGeneration;
    private Path buildingPath;
    /**
     * Sets the predicate checked synchronously on the caller thread before every disk-backed program load.
     * The main window uses it to save open editors; a null predicate restores standalone debugger behavior.
     * @param check returns true only when loading from disk is safe to continue
     */
    void setBeforeProgramLoad(BooleanSupplier check) { beforeProgramLoad = check == null ? () -> true : check; }
    /**
     * Starts loading a supported program after the approval predicate succeeds.
     * @param path existing assembly, C source, or executable to load
     * @return true when an asynchronous load was accepted; false for an invalid path or denied approval
     */
    boolean debugAsync(Path path){return debugAsync(path,()->{});}
    private boolean debugAsync(Path path,Runnable after){
        if(path==null||!Files.isRegularFile(path))return false;
        Path absolute=path.toAbsolutePath().normalize();String name=absolute.toString().toLowerCase(java.util.Locale.ROOT);
        if(!name.endsWith(".asm")&&!name.endsWith(".c")&&!name.endsWith(".exe"))return false;
        if(!beforeProgramLoad.getAsBoolean())return false;
        long generation=++buildGeneration;buildingPath=absolute;
        debugConsole.setBuildStatus((name.endsWith(".c")?"Compiling ":"Assembling ")+absolute.getFileName());
        new SwingWorker<PreparedProgram,Void>(){
            protected PreparedProgram doInBackground()throws Exception{return prepare(absolute, text -> publishBuildStatus(generation, text));}
            protected void done(){if(shuttingDown||generation!=buildGeneration)return;buildingPath=null;debugConsole.setBuildStatus(null);try{applyProgram(absolute,get());after.run();}catch(Exception error){clearProgram();debugConsole.appendError(VulcanDialog.errorSummary(error)+"\n");} }
        }.execute();
        return true;
    }

    private void publishBuildStatus(long generation, String status) {
        Runnable update = () -> {
            if (!shuttingDown && generation == buildGeneration) debugConsole.setBuildStatus(status);
        };
        if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update);
    }

    /** Reloads the selected program. */
    public void assemble() {
        Path source = settings.getCurrentFile();
        if (source != null) debugAsync(source);
    }

    /** Unloads the program and clears every view. */
    public void clearProgram() {
        runtimeErrorShown.set(false);
        buildGeneration++;buildingPath=null;
        programInfo = ProgramInfo.empty();
        programFile = null;
        fileLabel.setText("");
        session.stop(this);
        if (session.owns(this)) cpu.reset();
        disassembler.clear();
        memoryPanel.clear();
        refreshControlState();
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        Theme.apply(this, settings);
        fileLabel.setFont(new Font(settings.getApplicationFont(), Font.BOLD, settings.getFontSize("debuggerFilename")));
        fileLabel.setForeground(settings.getColor("text"));
        fileSystem.refreshTheme();
        registersPanel.refreshTheme();
        disassembler.refreshTheme();
        memoryPanel.refreshTheme();
        debugConsole.refreshTheme();
        stopButton.setIcon(Icons.vector("stop", 16, settings.getColor("stopButton")));
        repaint();
        revalidate();
    }

    public Memory getMemory() { return memory; }
    WorkspaceViews getViews() { return views; }
    ConsolePanel getConsole() { return debugConsole; }
    public Path getProgramFile() { return programFile; }

    public Intel8080 getCpu() { return cpu; }

    public void stop() { buildGeneration++;buildingPath=null;debugConsole.setBuildStatus(null);boolean wasRunning=session.owns(this)&&cpu.isRunning();this.wasRunning=false;session.stop(this);if(wasRunning)debugConsole.appendProgramEnded("Program ended by user stop."); }

    /** Stops execution and releases debugger resources. */
    public void shutdown() {
        shuttingDown = true;
        controlsTimer.stop();
        stop();
        executionStarted = false;
        debugConsole.clear();
        executor.shutdownNow();
    }

    /** Reloads the current program and resets the CPU. */
    public void reset() {
        stop();
        executionStarted = false;
        runtimeErrorShown.set(false);
        if (!session.owns(this)) { if (programFile != null) debugAsync(programFile); return; }
        session.reset(this, programInfo.loaded() ? entryAddress : Memory.PROGRAM_START);
        if (programInfo.loaded()) {
            memoryPanel.refresh();
            disassembler.refresh();
        }
    }

    /** Runs the loaded program until a breakpoint, STOP, or error. */
    public void run() {
        RunningPrograms.flash(runButton);
        if (!programInfo.loaded()) return;
        if (!session.owns(this) && programFile != null) {debugAsync(programFile,this::run);return;}
        views.set("Console",true);
        if (cpu.isPaused()) { continueExecution(); return; }
        if (cpu.isRunning()) reset();
        if (cpu.isStopped() || cpu.isHalted()) reset();
        debugConsole.clear();
        executionStarted = true;
        runtimeErrorShown.set(false);
        long revision = session.revision();
        executor.submit(() -> {
            try { wasRunning=true;session.run(this, revision); }
            catch (RuntimeException e) { SwingUtilities.invokeLater(() -> debugConsole.appendError(VulcanDialog.errorSummary(e) + "\n")); }
        });
    }

    /** Executes one instruction. */
    public void step() {
        if (!programInfo.loaded()) return;
        if (!session.owns(this) && programFile != null) {debugAsync(programFile,this::step);return;}
        views.set("Console",true);
        if (!executionStarted) { debugConsole.clear(); executionStarted = true; }
        try { cpu.step(); }
        catch (RuntimeException error) { debugConsole.appendError(VulcanDialog.errorSummary(error) + "\n"); }
    }

    public void pause() { if (session.owns(this)) cpu.pause(); }

    /** Continues a paused program. */
    public void continueExecution() {
        RunningPrograms.flash(runButton);
        if (!programInfo.loaded()) return;
        long revision = session.revision();
        executor.submit(() -> {
            try {
                session.resume(this, revision);
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> debugConsole.appendError(VulcanDialog.errorSummary(e) + "\n"));
            }
        });
    }

    public void setDeletionListener(java.util.function.Consumer<Path> listener) { deletionListener = listener == null ? path -> {} : listener; }

    public void setBeforeDeletionListener(java.util.function.Consumer<Path> listener) { fileSystem.setBeforeDeletionListener(listener); }

    public void setMoveListener(java.util.function.BiConsumer<Path, Path> listener) { fileSystem.setMoveListener(listener); }
    public void setBeforeMoveListener(java.util.function.Predicate<Path> listener) { fileSystem.setBeforeMoveListener(listener); }

    /** Updates the selected program after a file move or rename. */
    public void relocateFile(Path source, Path destination) {
        if (programFile != null && programFile.startsWith(source)) {
            programFile = destination.resolve(source.relativize(programFile));
            fileLabel.setText(programFile.getFileName().toString());
        }
    }

    public void selectFile(Path path) { selectFileIfAllowed(path); }
    /** Selects a program file and reports false when its load is invalid or the shared save gate denies it. */
    boolean selectFileIfAllowed(Path path) {
        if (path == null || !Files.isRegularFile(path)) return false;
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.equals(programFile) && session.owns(this)) return beforeProgramLoad.getAsBoolean();
        return absolute.equals(buildingPath) || debugAsync(absolute);
    }

    @Override
    public void cpuStateChanged() {
        if (shuttingDown || !session.owns(this)) return;
        String runtimeError = cpu.getRuntimeError();
        if (runtimeError != null && runtimeErrorShown.compareAndSet(false, true)) {
            String errorSummary = VulcanDialog.errorSummary(new IllegalStateException(runtimeError));
            SwingUtilities.invokeLater(() -> {
                if (!shuttingDown) debugConsole.appendError(errorSummary + "\n");
            });
        }

    }
}
