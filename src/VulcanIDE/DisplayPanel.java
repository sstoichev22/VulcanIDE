package VulcanIDE;

import Intel8080.assembler.Assembler;
import Intel8080.cpu.Intel8080;
import Intel8080.cpu.Memory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Framebuffer display: renders VRAM through presets and forwards keyboard and mouse input to the CPU. */
public class DisplayPanel extends JPanel {
    private final SettingsManager settings;
    private final WorkspaceViews views = new WorkspaceViews();
    private final Memory memory;
    private final Intel8080 cpu;
    private final FileSystemPanel files;
    private final ConsolePanel console;
    private final JButton runButton = new JButton();
    private final JButton stopButton = new JButton();
    private final JLabel selectedFileLabel = new JLabel("No file selected");
    private final JComboBox<String> presetBox = new JComboBox<>();
    private static final int MAX_DIMENSION = 0xFFFF;
    private final JTextField addressField = new JTextField(String.format("%08X", Memory.VRAM_START), 8);
    private final JComboBox<Integer> bitsBox = new JComboBox<>(new Integer[]{1, 2, 4, 8, 16, 24, 32});
    private final JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(16, 1, MAX_DIMENSION, 1));
    private final JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(16, 1, MAX_DIMENSION, 1));
    private final JComboBox<String> colorMode = new JComboBox<>(new String[]{"Palette", "Sequential RGB", "Selected RGB bits"});
    private final JTextField redBits = new JTextField("16:8"), greenBits = new JTextField("8:8"), blueBits = new JTextField("0:8");
    private final JPanel mappings = new JPanel();
    private final ScreenCanvas canvas = new ScreenCanvas();
    private JSplitPane screenSplit;
    private JFrame displayWindow;
    private final Map<String, Preset> presets = new LinkedHashMap<>();
    private final Map<Integer, Color> colors = new LinkedHashMap<>();
    private final javax.swing.Timer refreshTimer;
    private final java.util.function.Consumer<String> rgbBitErrorPresenter;
    private Path selectedFile;
    private java.util.function.Consumer<Path> deletionListener = path -> {};
    private boolean updating;
    private volatile long buildGeneration;
    private volatile boolean closed;
    private java.util.function.Consumer<Path> runHandler;
    private final EmulationSession session = EmulationSession.SHARED;
    private Boolean lastRunning;
    private boolean wasWorkerRunning;
    private String lastRuntimeError;
    private boolean active;

    /** Creates a display with its own memory and no CPU input. */
    public DisplayPanel(SettingsManager settings) {
        this(settings, EmulationSession.SHARED.memory, EmulationSession.SHARED.cpu, null);
    }

    /** Creates a display of the given memory without CPU input. */
    public DisplayPanel(SettingsManager settings, Memory memory) {
        this(settings, memory, null, null);
    }

    /** Creates a display of memory that sends input to the CPU. */
    public DisplayPanel(SettingsManager settings, Intel8080 cpu, Memory memory) {
        this(settings, cpu, memory, null);
    }

    /** Creates a display panel whose RGB bit-depth feedback can be captured without showing a modal dialog. */
    DisplayPanel(SettingsManager settings, Intel8080 cpu, Memory memory, java.util.function.Consumer<String> presenter) {
        this(settings, memory, cpu, presenter);
    }

    private DisplayPanel(SettingsManager settings, Memory suppliedMemory, Intel8080 suppliedCpu, java.util.function.Consumer<String> presenter) {
        this.settings = settings;
        this.memory = suppliedMemory == null ? new Memory() : suppliedMemory;
        this.cpu = suppliedCpu == null ? new Intel8080(this.memory) : suppliedCpu;
        this.rgbBitErrorPresenter = presenter == null ? message -> VulcanDialog.message(this, settings, "RGB bits", message) : presenter;
        setLayout(new BorderLayout());
        setBackground(settings.getColor("panelBackground"));

        files = new FileSystemPanel(settings, path -> { selectedFile = path.toAbsolutePath().normalize(); settings.setCurrentFile(selectedFile); selectedFileLabel.setText(path.getFileName().toString()); selectedFileLabel.setToolTipText(path.toString()); }, "displayFilePanel");
        console = new ConsolePanel(settings, "displayConsole");
        console.setTitle("Console");
        console.setInputHandler(line -> cpu == session.cpu && session.submitInput(this, line));
        console.setInputAvailability(() -> session.inputAvailable(this));
        files.setDeletionListener(path -> deletionListener.accept(path));
        loadDefaultPresets();
        loadPresetFiles(settings.getPresetsDirectory());
        selectSavedPreset();

        JPanel header = createHeader();
        JPanel controls = createControls();
        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvas, controls);
        screenSplit = center;
        center.setDividerLocation(0.78);
        center.setResizeWeight(1.0);
        center.setBorder(null);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(console, BorderLayout.CENTER);
        bottom.setPreferredSize(new Dimension(0, 150));

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, bottom);
        right.setDividerLocation(650);
        right.setResizeWeight(0.78);
        right.setBorder(null);
        Theme.initializeDivider(right, 0.78);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, files, right);
        main.setDividerLocation(220);
        main.setResizeWeight(0.0);
        main.setBorder(null);
        views.addSplit("Files", main, true);
        views.addSplit("Screen", center, true);
        views.addSplit("Display settings", center, false);
        views.addConsole("Console", right, false, console);
        add(header, BorderLayout.NORTH);
        add(main, BorderLayout.CENTER);

        refreshTimer = new javax.swing.Timer(1000 / 60, e -> { canvas.requestFrame(); canvas.repaint(); refreshRunStopButtons(); });
        refreshTimer.setCoalesce(true);

        refreshTimer.stop();
        refreshRunStopButtons();
        installOutputListener();
    }

    /** Sets the action that runs the selected program. */
    public void setRunHandler(java.util.function.Consumer<Path> handler) {
        runHandler = handler;
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(new EmptyBorder(4, 7, 4, 7));
        header.setBackground(settings.getColor("panelBackground"));
        selectedFileLabel.setHorizontalAlignment(SwingConstants.CENTER);
        selectedFileLabel.setForeground(settings.getColor("text"));
        selectedFileLabel.setFont(new Font(settings.getApplicationFont(), Font.BOLD, settings.getFontSize("displayFilename")));
        runButton.setFocusPainted(false);
        runButton.setIcon(Icons.vector("run", 18, new Color(47, 184, 92)));
        runButton.setToolTipText("Run");
        runButton.setPreferredSize(new Dimension(31, 27));
        stopButton.setIcon(Icons.vector("stop", 16, new Color(220, 65, 75)));
        stopButton.setToolTipText("Stop");
        stopButton.setPreferredSize(new Dimension(31, 27));
        stopButton.setVisible(true);
        stopButton.setEnabled(false);
        runButton.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        stopButton.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        runButton.setContentAreaFilled(false);
        stopButton.setContentAreaFilled(false);
        runButton.setOpaque(false);
        stopButton.setOpaque(false);
        runButton.addActionListener(e -> { RunningPrograms.flash(runButton);runSelected(); });
        stopButton.addActionListener(e -> stopProgram());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        JButton popout=new JButton("Pop out");popout.addActionListener(e->popOut());actions.add(popout);
        actions.add(runButton);
        actions.add(stopButton);
        header.add(Box.createRigidArea(new Dimension(actions.getPreferredSize())), BorderLayout.WEST);
        header.add(selectedFileLabel, BorderLayout.CENTER);
        header.add(actions, BorderLayout.EAST);
        return header;
    }
    private void popOut() { popOut(true); }
    void popOut(boolean visible) {
        if(displayWindow!=null){displayWindow.toFront();return;}
        displayWindow=new JFrame("Vulcan " + Vulcan.VERSION + " â€” Display"){@Override protected JRootPane createRootPane(){return WindowResize.rootPane();}};displayWindow.setUndecorated(true);WindowResize.install(displayWindow);displayWindow.setIconImage(Icons.load("vulcan-icon.png"));displayWindow.setLayout(new BorderLayout());
        JPanel bar=new JPanel(new BorderLayout());JLabel title=new JLabel("Vulcan " + Vulcan.VERSION + " â€” Display",Icons.fit("vulcan-icon.png",16,16),SwingConstants.LEFT);title.setBorder(new EmptyBorder(7,8,7,8));bar.add(title,BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,0,0));JButton min=Icons.windowControl("mz-icon.png","Minimize"),max=Icons.windowControl("fs-icon.png","Windowed / maximize"),close=Icons.windowControl("x-icon.png","Close");buttons.add(min);buttons.add(max);buttons.add(close);bar.add(buttons,BorderLayout.EAST);
        min.addActionListener(e->displayWindow.setState(Frame.ICONIFIED));max.addActionListener(e->WindowResize.toggleWorkAreaMaximized(displayWindow));close.addActionListener(e->dockDisplay());
        final Point[] drag={null};bar.addMouseListener(new MouseAdapter(){public void mousePressed(MouseEvent e){drag[0]=e.getPoint();}});bar.addMouseMotionListener(new MouseAdapter(){public void mouseDragged(MouseEvent e){if(drag[0]!=null){Point p=e.getLocationOnScreen();displayWindow.setLocation(p.x-drag[0].x,p.y-drag[0].y);}}});
        screenSplit.setLeftComponent(new JPanel());displayWindow.add(bar,BorderLayout.NORTH);WindowResize.pinCaption(displayWindow,bar,buttons);displayWindow.add(canvas,BorderLayout.CENTER);displayWindow.pack();
        Preset p=presets.get((String)presetBox.getSelectedItem());
        GraphicsConfiguration gc=getGraphicsConfiguration();if(gc==null)gc=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle available=new Rectangle(gc.getBounds());Insets reserved=Toolkit.getDefaultToolkit().getScreenInsets(gc);available.x+=reserved.left;available.y+=reserved.top;available.width-=reserved.left+reserved.right;available.height-=reserved.top+reserved.bottom;
        Insets frame=displayWindow.getInsets(),root=displayWindow.getRootPane().getInsets();Insets border=new Insets(frame.top+root.top,frame.left+root.left,frame.bottom+root.bottom,frame.right+root.right);int headerHeight=bar.getPreferredSize().height;
        double scale=Math.min((available.width-border.left-border.right)/(double)p.width,(available.height-border.top-border.bottom-headerHeight)/(double)p.height);
        int w=(int)Math.floor(p.width*scale)+border.left+border.right,h=(int)Math.floor(p.height*scale)+border.top+border.bottom+headerHeight;
        displayWindow.setBounds(available.x+(available.width-w)/2,available.y+(available.height-h)/2,w,h);
        displayWindow.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosing(java.awt.event.WindowEvent e){dockDisplay();}});Theme.apply(bar,settings);displayWindow.setVisible(visible);updateRefreshTimer();
    }
    private void dockDisplay(){if(displayWindow==null)return;displayWindow.remove(canvas);screenSplit.setLeftComponent(canvas);displayWindow.dispose();displayWindow=null;updateRefreshTimer();canvas.repaint();}
    private JPanel createControls() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(285, 0));
        panel.setBackground(settings.getColor("panelBackground"));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(new EmptyBorder(8, 8, 8, 8));
        top.setBackground(settings.getColor("panelBackground"));

        JPanel presetRow = new JPanel(new BorderLayout(4, 0));
        presetRow.setOpaque(false);
        presetRow.add(label("Preset"), BorderLayout.NORTH);
        JButton add = new JButton("+");
        JButton delete = new JButton("-");
        JButton rename = new JButton("Rename");
        rename.setToolTipText("Rename preset and its file");
        rename.addActionListener(e -> renamePreset());
        add.setToolTipText("Add preset");
        delete.setToolTipText("Delete preset");
        JPanel plusMinus = new JPanel(new GridLayout(1, 3, 2, 0));
        plusMinus.add(add); plusMinus.add(delete); plusMinus.add(rename);
        presetRow.add(presetBox, BorderLayout.CENTER);
        presetRow.add(plusMinus, BorderLayout.EAST);
        top.add(presetRow);
        top.add(Box.createVerticalStrut(7));

        top.add(label("Memory address (hex)"));
        addressField.setEditable(false);
        top.add(addressField);
        addressField.setToolTipText("Framebuffer address from the selected preset; VRAM begins at 0xF0000000");
        top.add(Box.createVerticalStrut(6));
        top.add(label("Screen dimensions"));
        JPanel dimensions = new JPanel(new GridLayout(1, 2, 4, 0));
        dimensions.setOpaque(false);
        dimensions.add(widthSpinner); dimensions.add(heightSpinner);
        top.add(dimensions);
        top.add(Box.createVerticalStrut(6));
        top.add(label("Bits per pixel"));bitsBox.setToolTipText("Pixel bytes use little-endian order");
        top.add(bitsBox);
        top.add(label("Color mapping"));top.add(colorMode);
        top.add(label("RGB ranges (offset:width)"));JPanel channelRow=new JPanel(new GridLayout(1,3,4,0));channelRow.add(redBits);channelRow.add(greenBits);channelRow.add(blueBits);top.add(channelRow);
        top.add(label("VRAM size (MiB)"));
        JTextField vram=new JTextField(String.format(java.util.Locale.ROOT,"%.2f",memory.getVramBytes()/1048576.0));
        vram.setEditable(false);vram.setToolTipText("Configured by the memory model");top.add(vram);
        JPanel bitDiagram=new JPanel(){
            {setPreferredSize(new Dimension(260,46));setMinimumSize(new Dimension(0,46));setMaximumSize(new Dimension(Integer.MAX_VALUE,46));}
            @Override protected void paintComponent(Graphics g){super.paintComponent(g);int bits=(Integer)bitsBox.getSelectedItem();for(int i=0;i<bits;i++){int bit=bits-1-i;Color c=settings.getColor("splitDivider");if(colorMode.getSelectedIndex()!=0){String[] ranges=colorMode.getSelectedIndex()==2?new String[]{redBits.getText(),greenBits.getText(),blueBits.getText()}:bits==16?new String[]{"11:5","5:6","0:5"}:new String[]{"0:8","8:8","16:8"};Color[] channelColors={new Color(205,65,65),new Color(55,165,80),new Color(65,110,210)};for(int n=0;n<3;n++){try{String[] parts=ranges[n].split(":");int offset=Integer.parseInt(parts[0]),count=Integer.parseInt(parts[1]);if(bit>=offset&&bit<offset+count)c=channelColors[n];}catch(RuntimeException ignored){}}}int x=i*getWidth()/bits,w=(i+1)*getWidth()/bits-x;g.setColor(c);g.fillRect(x+1,3,Math.max(1,w-2),22);g.setColor(settings.getColor("text"));if(w>=13)g.drawString(Integer.toString(bit),x+2,40);}}
        };top.add(bitDiagram);
        colorMode.addActionListener(e->{if(updating)return;try{if(colorMode.getSelectedIndex()==2)validateChannels();saveCurrentPreset();bitDiagram.repaint();canvas.repaint();}catch(IllegalArgumentException ex){VulcanDialog.message(this,settings,"RGB bits",ex.getMessage());colorMode.setSelectedIndex(1);}});for(JTextField field:new JTextField[]{redBits,greenBits,blueBits})field.addActionListener(e->{try{validateChannels();saveCurrentPreset();bitDiagram.repaint();canvas.repaint();}catch(IllegalArgumentException ex){VulcanDialog.message(this,settings,"RGB bits",ex.getMessage());}});
        top.add(Box.createVerticalStrut(8));
        top.add(label("Colors"));
        JPanel paletteRow=new JPanel(new BorderLayout(4,0));JTextField paletteValue=new JTextField("0");JButton paletteAdd=new JButton("Set color");paletteValue.setToolTipText("Pixel value in hexadecimal, including 32-bit values");paletteRow.add(paletteValue,BorderLayout.CENTER);paletteRow.add(paletteAdd,BorderLayout.EAST);top.add(paletteRow);paletteAdd.addActionListener(e->{try{long value=Long.parseUnsignedLong(paletteValue.getText().trim().replaceFirst("(?i)^0x",""),16);int bits=(Integer)bitsBox.getSelectedItem();long max=bits==32?0xFFFFFFFFL:(1L<<bits)-1;if(Long.compareUnsigned(value,max)>0)throw new IllegalArgumentException("Pixel value exceeds the selected bit depth.");int key=(int)value;colors.putIfAbsent(key,Color.BLACK);editColor(key);}catch(RuntimeException ex){VulcanDialog.message(this,settings,"Palette value",ex.getMessage());}});

        mappings.setLayout(new BoxLayout(mappings, BoxLayout.Y_AXIS));
        mappings.setBackground(settings.getColor("inputBackground"));
        JScrollPane mappingScroll = new JScrollPane(mappings);
        mappingScroll.setBorder(null);
        mappingScroll.setPreferredSize(new Dimension(0, 400));
        Theme.styleScrollBar(mappingScroll.getVerticalScrollBar(), settings);
        top.add(mappingScroll);
        top.add(Box.createVerticalStrut(7));

        top.add(Box.createVerticalGlue());

        add.addActionListener(e -> addPreset());
        delete.addActionListener(e -> deletePreset());
        presetBox.addActionListener(e -> { if (!updating) applyPreset(); });
        bitsBox.addActionListener(e -> {
            if (!updating) handleBitDepthSelection();
        });
        widthSpinner.addChangeListener(e -> { canvas.repaint(); saveCurrentPreset(); });
        heightSpinner.addChangeListener(e -> { canvas.repaint(); saveCurrentPreset(); });
        addressField.addActionListener(e -> { canvas.repaint(); saveCurrentPreset(); });

        panel.add(top, BorderLayout.CENTER);
        return panel;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(settings.getColor("text"));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void loadDefaultPresets() {

        Path directory = settings.getPresetsDirectory();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.preset")) {
            if (files.iterator().hasNext()) return;
        } catch (IOException error) {
            console.appendError("Could not read display presets: " + VulcanDialog.errorSummary(error) + "\n");
            return;
        }
        presets.put("16x16 Video 2-bit", new Preset("16x16 Video 2-bit", Memory.VRAM_START, 16, 16, 2, new LinkedHashMap<>()));
        presets.put("32x32 RAM 2-bit", new Preset("32x32 RAM 2-bit", 0x8000, 32, 32, 2, new LinkedHashMap<>()));
        Preset vos = new Preset("vos", Memory.VRAM_START, 1280, 720, 8, vosColors());
        vos.mode = 0;
        vos.red = "5:3";
        vos.green = "2:3";
        vos.blue = "0:2";
        presets.put(vos.name, vos);
        for (Preset preset : presets.values()) defaultColors(preset.bits).forEach(preset.colors::putIfAbsent);
        for (Preset preset : presets.values()) {
            try { preset.save(presetPath(preset.name)); }
            catch (IOException error) { console.appendError("Could not save default preset: " + VulcanDialog.errorSummary(error) + "\n"); }
        }
    }

    private static Map<Integer, Color> defaultColors(int bits) {
        Map<Integer, Color> map = new LinkedHashMap<>();
        int count = 1 << Math.min(bits, 8);
        Color[] sample = {new Color(20, 40, 20), new Color(35, 110, 45), new Color(40, 90, 190), new Color(210, 45, 45)};
        for (int i = 0; i < count; i++) map.put(i, i < sample.length ? sample[i] : Color.BLACK);
        return map;
    }

    /** The VulcanOS RGB 3:3:2 palette: bits 7..5 red, 4..2 green, 1..0 blue, each scaled to 0..255. */
    private static Map<Integer, Color> vosColors() {
        Map<Integer, Color> map = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++) map.put(i, new Color((i >> 5) * 255 / 7, (i >> 2 & 7) * 255 / 7, (i & 3) * 255 / 3));
        return map;
    }

    /** Upgrades an unedited stock VOS preset (the RGB565 one, or the 8-bit one that only colored black, red, and white). */
    static boolean migrateLegacyStockVosPreset(Preset preset) {
        if (!preset.name.equals("vos") || preset.address != Memory.VRAM_START || preset.width != 1280 || preset.height != 720) return false;
        boolean rgb565 = preset.bits == 16 && preset.mode == 2 && preset.red.equals("11:5")
                && preset.green.equals("5:6") && preset.blue.equals("0:5") && preset.colors.equals(legacyRgb565VosColors());
        boolean sparse332 = preset.bits == 8 && preset.mode == 0 && preset.red.equals("5:3")
                && preset.green.equals("2:3") && preset.blue.equals("0:2") && preset.colors.equals(legacySparseVosColors());
        if (!rgb565 && !sparse332) return false;
        preset.bits = 8;
        preset.mode = 0;
        preset.red = "5:3"; preset.green = "2:3"; preset.blue = "0:2";
        preset.colors.clear();
        preset.colors.putAll(vosColors());
        return true;
    }

    private static Map<Integer, Color> legacyRgb565VosColors() {
        return defaultColors(16);
    }

    private static Map<Integer, Color> legacySparseVosColors() {
        Map<Integer, Color> map = defaultColors(8);
        map.put(0x00, Color.BLACK);
        map.put(0xE0, Color.RED);
        map.put(0xFF, Color.WHITE);
        return map;
    }

    private void loadPresetFiles(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.preset")) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    Preset preset = Preset.load(path);
                    Path namedPath=directory.resolve(presetPath(preset.name).getFileName());
                    if(!namedPath.equals(path)){
                        if(Files.exists(namedPath))throw new IOException("Preset name conflicts with "+namedPath);
                        Files.move(path,namedPath);
                    }
                    if (migrateLegacyStockVosPreset(preset)) {
                        try { preset.save(namedPath); }
                        catch (IOException error) { console.appendError("Could not save migrated VOS preset: " + VulcanDialog.errorSummary(error) + "\n"); }
                    }
                    presets.put(preset.name, preset);
                } catch (Exception error) {
                    console.appendError("Skipped preset " + path.getFileName() + ": " + VulcanDialog.errorSummary(error) + "\n");
                }
            }
        } catch (IOException | RuntimeException error) {
            console.appendError("Could not read display presets: " + VulcanDialog.errorSummary(error) + "\n");
        }
        presetBox.removeAllItems();
        for (String name : presets.keySet()) presetBox.addItem(name);
        selectSavedPreset();
    }

    private void selectSavedPreset() {
        String wanted = settings.getDisplayPreset();
        if (wanted != null && presets.containsKey(wanted)) presetBox.setSelectedItem(wanted);
        else if (presetBox.getItemCount() > 0) presetBox.setSelectedIndex(0);
        applyPreset();
    }

    private void applyPreset() {
        Preset p = presets.get((String) presetBox.getSelectedItem());
        if (p == null) return;
        updating = true;
        addressField.setText(String.format("%08X", p.address));
        addressField.setBorder(UIManager.getBorder("TextField.border"));
        addressField.setToolTipText(null);
        widthSpinner.setValue(p.width);
        heightSpinner.setValue(p.height);
        bitsBox.setSelectedItem(p.bits);
        colors.clear();
        colors.putAll(p.colors);
        ensureColorCount(p.bits);
        colorMode.setSelectedIndex(p.mode);redBits.setText(p.red);greenBits.setText(p.green);blueBits.setText(p.blue);
        rebuildMappings();
        updating = false;
        settings.setDisplayPreset(p.name);
        canvas.repaint();
    }

    private void ensureColorCount(int bits) {
        int count = 1 << Math.min(bits, 8);
        Map<Integer, Color> defaults = defaultColors(bits);
        for (int i = 0; i < count; i++) colors.putIfAbsent(i, defaults.get(i));
        if(bits<=8)colors.keySet().removeIf(k -> k < 0 || k >= count);
    }

    private void rebuildColors(int bits) {
        ensureColorCount(bits);
        rebuildMappings();
        canvas.repaint();
    }

    /** Commits a bit-depth edit only after selected RGB channels fit the requested pixel width. */
    private void handleBitDepthSelection() {
        int selectedBits = (Integer) bitsBox.getSelectedItem();
        if (colorMode.getSelectedIndex() == 2) {
            try {
                validateChannels();
            } catch (IllegalArgumentException error) {
                Preset currentPreset = presets.get((String) presetBox.getSelectedItem());
                if (currentPreset != null) {
                    updating = true;
                    try { bitsBox.setSelectedItem(currentPreset.bits); }
                    finally { updating = false; }
                }
                rgbBitErrorPresenter.accept(error.getMessage());
                return;
            }
        }
        rebuildColors(selectedBits);
        saveCurrentPreset();
    }

    private void rebuildMappings() {
        mappings.removeAll();
        int bits = (Integer) bitsBox.getSelectedItem();
        java.util.List<Integer> keys=new java.util.ArrayList<>(colors.keySet());
        for (int key : keys) {
            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.setOpaque(true);
            row.setBackground(settings.getColor("inputBackground"));
            JLabel value = new JLabel(pattern(key, bits));
            value.setForeground(settings.getColor("text"));
            value.setPreferredSize(new Dimension(58, 25));
            JButton color = new JButton();
            color.setOpaque(true);
            color.setBackground(colors.get(key));
            color.setBorder(BorderFactory.createLineBorder(settings.getColor("splitDivider")));
            JTextField hex = new JTextField(toHex(colors.get(key)), 7);
            hex.setForeground(settings.getColor("text"));
            hex.setBackground(settings.getColor("panelBackground"));
            color.addActionListener(e -> editColor(key));
            hex.addActionListener(e -> {
                Color c = parseColor(hex.getText());
                if (c != null) { colors.put(key, c); color.setBackground(c); saveCurrentPreset(); canvas.repaint(); }
            });
            row.add(value, BorderLayout.WEST);
            row.add(color, BorderLayout.CENTER);
            row.add(hex, BorderLayout.EAST);
            mappings.add(row);
        }
        mappings.revalidate();
        mappings.repaint();
    }

    private void editColor(int key) {
        ColorEditor dialog = new ColorEditor(SwingUtilities.getWindowAncestor(this), colors.get(key));
        dialog.setVisible(true);
        if (dialog.result != null) {
            colors.put(key, dialog.result);
            rebuildMappings();
            saveCurrentPreset();
            canvas.repaint();
        }
    }

    private void addPreset() {
        String name = JOptionPane.showInputDialog(this, "Preset name:", "Add Display Preset", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        String clean = name.trim();
        if (clean.length() > 80) {
            VulcanDialog.message(this, settings, "Invalid Preset Name", "Preset names must be 80 characters or fewer.");
            return;
        }
        if (presets.containsKey(clean)) { JOptionPane.showMessageDialog(this, "A preset with that name already exists."); return; }
        Path cleanedPath = presetPath(clean);
        for (String existing : presets.keySet()) {
            if (presetPath(existing).equals(cleanedPath)) {
                JOptionPane.showMessageDialog(this, "That name maps to the same preset file as " + existing + ".");
                return;
            }
        }
        int bits = (Integer) bitsBox.getSelectedItem();
        final long baseAddress;
        try { baseAddress = address(); }
        catch (IllegalArgumentException error) {
            addressField.setBorder(BorderFactory.createLineBorder(new Color(220, 70, 70)));
            addressField.setToolTipText(error.getMessage());
            VulcanDialog.message(this, settings, "Invalid Address", error.getMessage());
            return;
        }
        Preset preset = new Preset(clean, baseAddress, (Integer) widthSpinner.getValue(), (Integer) heightSpinner.getValue(), bits, new LinkedHashMap<>(colors));
        preset.mode = colorMode.getSelectedIndex();
        preset.red = redBits.getText(); preset.green = greenBits.getText(); preset.blue = blueBits.getText();
        try {
            if (preset.mode == 2) validateChannels();
            preset.save(cleanedPath);
        } catch (IOException | IllegalArgumentException error) {
            console.appendError("Could not add preset: " + VulcanDialog.errorSummary(error) + "\n");
            return;
        }
        presets.put(clean, preset);
        presetBox.addItem(clean);
        presetBox.setSelectedItem(clean);
        saveCurrentPreset();
    }

    private void renamePreset() {
        String previous=(String)presetBox.getSelectedItem();
        if(previous==null)return;
        String chosen=VulcanDialog.prompt(this,settings,"Rename Preset","New preset name:",previous);
        renamePreset(chosen);
    }

    private void renamePreset(String chosen) {
        String previous = (String) presetBox.getSelectedItem();
        if(previous==null||chosen==null||chosen.isBlank()||chosen.trim().equals(previous))return;
        String name=chosen.trim();
        Path oldPath=presetPath(previous),newPath=presetPath(name);
        if(name.length()>80||presets.containsKey(name)||(!oldPath.equals(newPath)&&Files.exists(newPath))){
            VulcanDialog.message(this,settings,"Rename Preset","Choose an unused name of 80 characters or fewer.");return;
        }
        Preset preset=presets.get(previous);
        try {
            if(Files.exists(oldPath))Files.move(oldPath,newPath);
            preset.name=name;
            try{preset.save(newPath);}catch(IOException error){preset.name=previous;if(Files.exists(newPath))Files.move(newPath,oldPath);throw error;}
            presets.remove(previous);presets.put(name,preset);
            updating=true;try{presetBox.removeItem(previous);presetBox.addItem(name);presetBox.setSelectedItem(name);}finally{updating=false;}
            settings.setDisplayPreset(name);
        } catch(IOException error){console.appendError("Could not rename preset: "+VulcanDialog.errorSummary(error)+"\n");}
    }

    private void deletePreset() {
        String name = (String) presetBox.getSelectedItem();
        if (name == null || presets.size() <= 1) return;
        try { Files.deleteIfExists(presetPath(name)); }
        catch (IOException error) {
            console.appendError("Preset delete failed: " + VulcanDialog.errorSummary(error) + "\n");
            return;
        }
        presets.remove(name);
        presetBox.removeItem(name);
        applyPreset();
    }

    private void saveCurrentPreset() {
        if (updating) return;
        String name = (String) presetBox.getSelectedItem();
        if (name == null) return;
        Preset p = presets.get(name);
        if (p == null) return;
        if(colorMode.getSelectedIndex()==2){try{validateChannels();}catch(IllegalArgumentException invalid){return;}}
        final long parsedAddress;
        try {
            parsedAddress = address();
            validateFramebufferRange(parsedAddress, framebufferByteCount((Integer)widthSpinner.getValue(), (Integer)heightSpinner.getValue(), (Integer)bitsBox.getSelectedItem()));
            addressField.setBorder(UIManager.getBorder("TextField.border"));
            addressField.setToolTipText(null);
        } catch (IllegalArgumentException error) {
            addressField.setBorder(BorderFactory.createLineBorder(new Color(220, 70, 70)));
            addressField.setToolTipText(error.getMessage());
            return;
        }
        p.address = parsedAddress;
        p.width = (Integer) widthSpinner.getValue();
        p.height = (Integer) heightSpinner.getValue();
        p.bits = (Integer) bitsBox.getSelectedItem();
        p.mode=colorMode.getSelectedIndex();p.red=redBits.getText();p.green=greenBits.getText();p.blue=blueBits.getText();
        p.colors.clear(); p.colors.putAll(colors);
        try { p.save(presetPath(name)); }
        catch (IOException error) {
            console.appendError("Preset save failed: " + VulcanDialog.errorSummary(error) + "\n");
        }
        settings.setDisplayPreset(name);
    }

    private Path presetPath(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return settings.getPresetsDirectory().resolve(safe + ".preset");
    }

    private void publishBuildStatus(long generation, String status) {
        Runnable update = () -> {
            if (!closed && generation == buildGeneration) console.setBuildStatus(status);
        };
        if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update);
    }

    private void runSelected() {
        if (closed) return;
        if (selectedFile == null || !Files.isRegularFile(selectedFile)) {
            return;
        }
        String name = selectedFile.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean buildRun = BuildRun.is(selectedFile);
        if (!name.endsWith(".asm") && !name.endsWith(".exe") && !name.endsWith(".c") && !buildRun) return;
        views.set("Console",true);
        stopProgram();
        console.clear();
        if (runHandler != null) {
            try { runHandler.accept(selectedFile); }
            catch (RuntimeException error) {
                console.appendError("\u001B[31m" + VulcanDialog.errorSummary(error) + "\u001B[0m\n");
            }
            canvas.requestFocusInWindow();
            return;
        }
        if(name.endsWith(".c")||name.endsWith(".asm")||buildRun){
            Path sourceFile=selectedFile;long generation=++buildGeneration;
            console.setBuildStatus((name.endsWith(".c")||buildRun?"Compiling ":"Assembling ")+sourceFile.getFileName());
            new SwingWorker<VulcanExecutable,Void>(){
                protected VulcanExecutable doInBackground()throws Exception{if(buildRun)return ProgramRunner.linkBuildRun(sourceFile,Files.readString(sourceFile,StandardCharsets.UTF_8),null,status->publishBuildStatus(generation,status)).executable();java.util.List<Path> includes=new java.util.ArrayList<>();if(sourceFile.getParent()!=null)includes.add(sourceFile.getParent());includes.add(Path.of("src","stdlib","include"));String source=Files.readString(sourceFile,StandardCharsets.UTF_8);if(name.endsWith(".c"))source=new C8080.Compiler(includes).compile(source,sourceFile);if(generation==buildGeneration&&!closed)console.setBuildStatus("Assembling "+sourceFile.getFileName());return ProgramRunner.linkWorkspace(sourceFile,source,includes,null,status->publishBuildStatus(generation,status)).executable();}
                protected void done(){if(closed||generation!=buildGeneration)return;console.setBuildStatus(null);try{VulcanExecutable executable=get();if(cpu==session.cpu){long revision=session.loadExecutable(DisplayPanel.this,executable,console::appendByte,sourceFile);session.run(DisplayPanel.this,revision);}else{cpu.stop();cpu.reset();memory.clear();for(var segment:executable.segments())memory.load(segment.address(),segment.bytes());cpu.setEntryAddress(executable.entryAddress());cpu.run();}wasWorkerRunning=true;canvas.requestFocusInWindow();}catch(Exception error){console.appendError(VulcanDialog.errorSummary(error)+"\n");console.appendProgramEnded("Program ended with a build error.");}}
            }.execute();return;
        }
        try {
            byte[] image;
            int start;
            int end;
            int entry;
            if (name.endsWith(".exe")) {
                if (cpu == session.cpu && VulcanExecutable.isExecutable(selectedFile)) {
                    VulcanExecutable executable = VulcanExecutable.read(selectedFile);
                    long revision = session.loadExecutable(this, executable, console::appendByte, selectedFile);
                    wasWorkerRunning=true;session.run(this, revision);
                    canvas.requestFocusInWindow();
                    return;
                }
                byte[] bytes = RawProgramImage.read(selectedFile, Memory.MEM_SIZE - Memory.PROGRAM_START);
                image = new byte[Memory.MEM_SIZE];
                start = entry = Memory.PROGRAM_START; end = start + bytes.length - 1;
                System.arraycopy(bytes, 0, image, start, bytes.length);
            } else {
                console.appendError("Display Run supports .c, .asm and .exe files.\n");
                return;
            }
            if (cpu == session.cpu) {
                long revision = session.load(this, image, start, end, entry, console::appendByte, selectedFile);
                wasWorkerRunning=true;session.run(this, revision);
            } else {
                cpu.stop(); cpu.reset();
                for (int address = start; address <= end; address++) memory.set(address, image[address]);
                cpu.setEntryAddress(entry);wasWorkerRunning=true;cpu.run();
            }
            canvas.requestFocusInWindow();
        } catch (Exception ex) {
            console.appendError("\u001B[31m" + VulcanDialog.errorSummary(ex) + "\u001B[0m\n");
        }
    }

    private void installOutputListener() {
        if (cpu == session.cpu) return;
        cpu.addPortOutputListener((port, value) -> {
            if (!closed && port == Intel8080.PORT_KEY_DATA) console.appendByte(value);
        });
    }

    /** Stops the program started from the display tab. */
    public void stopProgram() {
        buildGeneration++;console.setBuildStatus(null);
        boolean wasRunning=(cpu!=session.cpu||session.owns(this))&&cpu.isRunning();
        if (cpu == session.cpu) session.stop(this); else cpu.stop();
        if(wasRunning){wasWorkerRunning=false;console.appendProgramEnded("Program ended by user stop.");}
        refreshRunStopButtons();
    }

    private void refreshRunStopButtons() {
        String runtimeError = cpu.getRuntimeError();
        if (!closed && (cpu != session.cpu || session.owns(this)) && runtimeError != null && !Objects.equals(runtimeError, lastRuntimeError)) {
            console.appendError(runtimeError + "\n");
        }
        lastRuntimeError = runtimeError;
        boolean workerRunning = (cpu != session.cpu || session.owns(this)) && cpu.isRunning();
        if(wasWorkerRunning&&!workerRunning)console.appendProgramEnded(runtimeError==null?"Program ended with exit code "+cpu.getA()+".":"Program ended with error: "+runtimeError);
        wasWorkerRunning=workerRunning;
        boolean running = workerRunning && !cpu.isHalted() && !cpu.isPaused();

        stopButton.setEnabled(workerRunning);
        if (lastRunning != null && lastRunning == running) return;
        lastRunning = running;
        runButton.setIcon(Icons.vector(running ? "running" : "run", 18, new Color(47, 184, 92)));
        runButton.setToolTipText(running ? "Rerun" : "Run");
        Container actions = stopButton.getParent();
        if (actions != null) {
            actions.revalidate();
            actions.repaint();
        }
    }

    private long address() {
        String value = addressField.getText().trim().replaceFirst("(?i)^0x", "");
        if (!value.matches("(?i)[0-9a-f]{1,16}")) {
            throw new IllegalArgumentException("Enter a 64-bit hexadecimal address.");
        }
        return Long.parseUnsignedLong(value, 16);
    }

    /**
     * Decodes a framebuffer into a nearest-neighbor RGB image. A supplied snapshot
     * keeps every output pixel from one memory state; a null snapshot reads pixels
     * directly for frames too large to copy. Indexed images use the preset palette,
     * while RGB images expand the selected bit ranges to eight-bit channels.
     */
    static BufferedImage renderFrame(Memory memory, byte[] snapshot, long base, int width, int height,
                                     int drawWidth, int drawHeight, int bits, int mode,
                                     Map<Integer, Color> palette, int[] offsets, int[] widths) {
        BufferedImage image = new BufferedImage(drawWidth, drawHeight, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int bytesPerPixel = (bits + 7) / 8;

        if (snapshot != null && mode == 0 && bits == 8
                && width == drawWidth && height == drawHeight) {
            int[] paletteRgb = new int[256];
            for (int index = 0; index < paletteRgb.length; index++) {
                paletteRgb[index] = palette.getOrDefault(index, Color.BLACK).getRGB();
            }
            for (int index = 0; index < pixels.length; index++) {
                pixels[index] = paletteRgb[snapshot[index] & 0xFF];
            }
            return image;
        }

        int[] indexedRgb = null;
        if (mode == 0 && bits <= 8) {
            indexedRgb = new int[1 << bits];
            for (int index = 0; index < indexedRgb.length; index++) {
                indexedRgb[index] = palette.getOrDefault(index, Color.BLACK).getRGB();
            }
        }
        long[] channelMasks = new long[3];
        if (mode != 0) {
            for (int channel = 0; channel < channelMasks.length; channel++) {
                channelMasks[channel] = (1L << widths[channel]) - 1;
            }
        }

        for (int y = 0; y < drawHeight; y++) {
            long sourceRow = ((long) y * height / drawHeight) * width;
            int outputRow = y * drawWidth;
            for (int x = 0; x < drawWidth; x++) {
                long pixelIndex = sourceRow + (long) x * width / drawWidth;
                long pixelOffset = pixelIndex * bytesPerPixel;
                long value;
                if (snapshot == null) {
                    value = readPixel(memory, base + pixelOffset, bits);
                } else {
                    int snapshotOffset = (int) pixelOffset;
                    value = 0;
                    for (int byteIndex = 0; byteIndex < bytesPerPixel; byteIndex++) {
                        value |= (snapshot[snapshotOffset + byteIndex] & 0xFFL) << (byteIndex * 8);
                    }
                    value &= bits == 32 ? 0xFFFFFFFFL : (1L << bits) - 1;
                }

                if (mode == 0) {
                    pixels[outputRow + x] = indexedRgb == null
                            ? palette.getOrDefault((int) value, Color.BLACK).getRGB()
                            : indexedRgb[(int) value];
                } else {
                    int rgb = 0;
                    for (int channel = 0; channel < 3; channel++) {
                        long component = (value >>> offsets[channel]) & channelMasks[channel];
                        int intensity = (int) (component * 255 / channelMasks[channel]);
                        rgb |= intensity << (16 - channel * 8);
                    }
                    pixels[outputRow + x] = rgb;
                }
            }
        }
        return image;
    }

    static long readPixel(Memory memory,long address,int bits){long value=0;for(int n=0;n<(bits+7)/8;n++)value|=(memory.get(address+n)&255L)<<(n*8);return value & (bits==32?0xFFFFFFFFL:(1L<<bits)-1);}
    private static long framebufferByteCount(int width,int height,int bits){return (long)width*height*((bits+7)/8);}
    static void validateFramebufferRange(long base,long byteCount){if(byteCount>0&&Long.compareUnsigned(base+(byteCount-1),base)<0)throw new IllegalArgumentException("Framebuffer range wraps the unsigned 64-bit address space.");}
    private void validateChannels(){int bits=(Integer)bitsBox.getSelectedItem();for(JTextField field:new JTextField[]{redBits,greenBits,blueBits})validateChannelRange(bits,field.getText());}
    private static void validateChannelRange(int bits,String range){channel(0,range);String[] parts=range.split(":");int offset=Integer.parseInt(parts[0]),width=Integer.parseInt(parts[1]);if(offset>bits-width)throw new IllegalArgumentException("RGB bit ranges must fit within the selected "+bits+" bits.");}
    static int channel(long value,String range){String[] parts=range.split(":");try{int offset=Integer.parseInt(parts[0]),width=Integer.parseInt(parts[1]);if(parts.length!=2||offset<0||width<1||width>32||offset>32-width)throw new NumberFormatException();long mask=(1L<<width)-1;return(int)((((value>>>offset)&mask)*255)/mask);}catch(RuntimeException ex){throw new IllegalArgumentException("Use offset:width within 32 bits, such as 16:8.");}}
    static Color decodePixel(long value,int bits,boolean custom,String red,String green,String blue){if(custom)return new Color(channel(value,red),channel(value,green),channel(value,blue));if(bits==16)return new Color(channel(value,"11:5"),channel(value,"5:6"),channel(value,"0:5"));return new Color(channel(value,"0:8"),channel(value,"8:8"),channel(value,"16:8"));}
    private String pattern(int value, int bits) { return String.format("%" + bits + "s", Integer.toBinaryString(value)).replace(' ', '0'); }
    private String toHex(Color c) { return String.format("#%02X%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha()); }
    private Color parseColor(String text) {
        try {
            String s = text.trim(); if (s.startsWith("#")) s = s.substring(1);
            if (s.length() != 6 && s.length() != 8) return null;
            long value = Long.parseLong(s, 16);
            if (s.length() == 6) return new Color((int) value);
            return new Color((int)(value >> 24) & 255, (int)(value >> 16) & 255, (int)(value >> 8) & 255, (int)value & 255);
        } catch (Exception e) { return null; }
    }

    public void setDeletionListener(java.util.function.Consumer<Path> listener) { deletionListener = listener == null ? path -> {} : listener; }

    public void setBeforeDeletionListener(java.util.function.Consumer<Path> listener) { files.setBeforeDeletionListener(listener); }

    public void setMoveListener(java.util.function.BiConsumer<Path, Path> listener) { files.setMoveListener(listener); }
    public void setBeforeMoveListener(java.util.function.Predicate<Path> listener) { files.setBeforeMoveListener(listener); }

    /** Updates the selected file after a move or rename. */
    public void relocateFile(Path source, Path destination) {
        if (selectedFile != null && selectedFile.startsWith(source)) {
            selectedFile = destination.resolve(source.relativize(selectedFile));
            selectedFileLabel.setText(selectedFile.getFileName().toString());
            selectedFileLabel.setToolTipText(selectedFile.toString());
        }
    }

    /** Clears the selection when the selected file was deleted. */
    public void clearDeletedFile(Path deleted) {
        if(selectedFile!=null&&selectedFile.startsWith(deleted)){
            stopProgram();selectedFile=null;selectedFileLabel.setText("No file selected");selectedFileLabel.setToolTipText(null);
        }
    }

    /** Selects a runnable file for the display tab. */
    public void selectFile(Path path) {
        if (path != null && Files.isRegularFile(path)) {
            selectedFile = path.toAbsolutePath().normalize();
            selectedFileLabel.setText(selectedFile.getFileName().toString());
            selectedFileLabel.setToolTipText(selectedFile.toString());
            files.openIfNeeded(selectedFile);
        }
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        setBackground(settings.getColor("panelBackground"));
        files.refreshTheme();
        console.refreshTheme();
        selectedFileLabel.setFont(new Font(settings.getApplicationFont(), Font.BOLD, settings.getFontSize("displayFilename")));
        selectedFileLabel.setForeground(settings.getColor("text"));
        mappings.setBackground(settings.getColor("inputBackground"));
        rebuildMappings();
        repaint();
    }

    /** Starts or stops frame refresh when the display tab becomes visible or hidden. */
    public void setActive(boolean active) {
        this.active = active;
        updateRefreshTimer();
        if (active) canvas.repaint();
        else refreshRunStopButtons();
    }

    private void updateRefreshTimer() {
        boolean visiblePopout = displayWindow != null && displayWindow.isVisible();
        boolean visibleDockedDisplay = displayWindow == null && active;
        if (!closed && (visibleDockedDisplay || visiblePopout)) refreshTimer.start();
        else refreshTimer.stop();
    }

    /** Stops rendering, docks any popout, and releases resources. */
    public void close() {
        dockDisplay();
        closed = true;
        canvas.renderer.shutdownNow();
        refreshTimer.stop();
        refreshRunStopButtons();
        stopProgram();
        console.clear();
    }

    public void refresh() { canvas.repaint(); }
    WorkspaceViews getViews() { return views; }
    ConsolePanel getConsole() { return console; }
    public Path getSelectedFile() { return selectedFile; }

    public void performRun() { runSelected(); RunningPrograms.flash(runButton); }

    static float normalizedMouseCoordinate(int coordinate,int origin,int extent,boolean invert){
        if(extent<=1)return 0f;
        int pixel=Math.max(0,Math.min(extent-1,coordinate-origin));
        float value=(float)(pixel*(2.0/(extent-1))-1.0);
        return invert?-value:value;
    }

    private final class ScreenCanvas extends JPanel {
        private volatile java.awt.image.BufferedImage frame;
        private final java.util.concurrent.atomic.AtomicBoolean renderingFrame = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.ExecutorService renderer = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "Vulcan-Display-Renderer");
            worker.setDaemon(true);
            return worker;
        });

        /** Captures the selected display settings, decodes a frame in the background, and queues one repaint. */
        void requestFrame() {
            Preset preset = presets.get((String) presetBox.getSelectedItem());
            if (closed || preset == null) return;

            int width = preset.width;
            int height = preset.height;
            int bits = preset.bits;
            int mode = preset.mode;
            long base = preset.address;
            long framebufferBytes = framebufferByteCount(width, height, bits);
            try {
                validateFramebufferRange(base, framebufferBytes);
            } catch (IllegalArgumentException invalidRange) {
                return;
            }
            if (!renderingFrame.compareAndSet(false, true)) return;

            int drawWidth = Math.min(width, Math.max(1, getWidth()));
            int drawHeight = Math.min(height, Math.max(1, getHeight()));
            Map<Integer, Color> palette = new HashMap<>(preset.colors);
            String[] ranges = mode == 2
                    ? new String[]{preset.red, preset.green, preset.blue}
                    : bits == 16
                    ? new String[]{"11:5", "5:6", "0:5"}
                    : new String[]{"0:8", "8:8", "16:8"};
            int[] offsets = new int[3];
            int[] widths = new int[3];
            for (int channel = 0; channel < 3; channel++) {
                String[] parts = ranges[channel].split(":");
                offsets[channel] = Integer.parseInt(parts[0]);
                widths[channel] = Integer.parseInt(parts[1]);
            }

            renderer.execute(() -> {
                try {
                    byte[] snapshot = framebufferBytes <= 16 * 1024 * 1024
                            ? memory.readRange(base, (int) framebufferBytes)
                            : null;
                    java.awt.image.BufferedImage image = renderFrame(memory, snapshot, base, width, height,
                            drawWidth, drawHeight, bits, mode, palette, offsets, widths);
                    frame = image;
                    SwingUtilities.invokeLater(this::repaint);
                } finally {
                    renderingFrame.set(false);
                }
            });
        }

        ScreenCanvas() {
            setBackground(Color.BLACK);
            setOpaque(true);
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    requestFocusInWindow();
                    offerMousePosition(event);
                    if (acceptsMouseInput() && isStandardMouseButton(event)) {
                        cpu.pressMouseButton(event.getButton());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    offerMousePosition(event);
                    if (acceptsMouseInput() && isStandardMouseButton(event)) {
                        cpu.releaseMouseButton(event.getButton());
                    }
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent event) {
                    offerMousePosition(event);
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    offerMousePosition(event);
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent event) {
                    cpu.releaseAllMouseButtons();
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyTyped(KeyEvent event) {
                    char character = event.getKeyChar();
                    boolean terminalByte = (character >= 0x20 && character <= 0x7E) || character == '\n' || character == '\b' || character == '\t';
                    boolean hasNoShortcutModifier = !event.isControlDown() && !event.isAltDown() && !event.isMetaDown();
                    boolean ownsActiveSession = !closed && (cpu != session.cpu || session.owns(DisplayPanel.this));
                    if (!ownsActiveSession || !terminalByte || !hasNoShortcutModifier) return;
                    cpu.offerKeyInput(character);
                    event.consume();
                }
            });
        }

        private boolean acceptsMouseInput() {
            return !closed && (cpu != session.cpu || session.owns(DisplayPanel.this));
        }

        private boolean isStandardMouseButton(MouseEvent event) {
            return event.getButton() >= MouseEvent.BUTTON1 && event.getButton() <= MouseEvent.BUTTON3;
        }

        private void offerMousePosition(MouseEvent event) {
            Preset preset = presets.get((String) presetBox.getSelectedItem());
            if (!acceptsMouseInput() || preset == null || preset.width <= 0 || preset.height <= 0) return;
            Rectangle bounds = imageBounds(preset.width, preset.height);
            float x = normalizedMouseCoordinate(event.getX(), bounds.x, bounds.width, false);
            float y = normalizedMouseCoordinate(event.getY(), bounds.y, bounds.height, true);
            cpu.offerMousePosition(x, y);
        }

        private Rectangle imageBounds(int width, int height) {
            double scale = Math.min(getWidth() / (double) width, getHeight() / (double) height);
            int screenWidth = Math.max(1, (int) Math.floor(width * scale));
            int screenHeight = Math.max(1, (int) Math.floor(height * scale));
            return new Rectangle((getWidth() - screenWidth) / 2, (getHeight() - screenHeight) / 2,
                    screenWidth, screenHeight);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Preset preset = presets.get((String) presetBox.getSelectedItem());
            if (preset == null) return;
            int width = preset.width;
            int height = preset.height;
            if (width <= 0 || height <= 0) return;
            Rectangle bounds = imageBounds(width, height);
            java.awt.image.BufferedImage image = frame;
            if (image == null) {
                requestFrame();
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null);
            } finally {
                g.dispose();
            }
        }
    }

    private final class ColorEditor extends JDialog {
        private Color result;
        ColorEditor(Window owner, Color initial) {
            super(owner, "Display Color", ModalityType.APPLICATION_MODAL);
            setUndecorated(true);
            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setBackground(settings.getColor("panelBackground"));
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(settings.getColor("panelBackground"));
            bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, settings.getColor("splitDivider")));
            JLabel title = new JLabel("Display Color");
            title.setForeground(settings.getColor("text"));
            title.setBorder(new EmptyBorder(7, 10, 7, 4));
            bar.add(title, BorderLayout.CENTER);
            JButton close = new JButton("Ã—");
            close.setForeground(settings.getColor("text"));
            close.setBorder(null);
            close.setContentAreaFilled(false);
            close.addActionListener(e -> dispose());
            bar.add(close, BorderLayout.EAST);
            root.add(bar, BorderLayout.NORTH);

            JColorChooser chooser = new JColorChooser(initial == null ? Color.BLACK : initial);
            root.add(chooser, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.setOpaque(false);
            JButton cancel = new JButton("Cancel");
            JButton ok = new JButton("OK");
            buttons.add(cancel); buttons.add(ok);
            root.add(buttons, BorderLayout.SOUTH);
            cancel.addActionListener(e -> dispose());
            ok.addActionListener(e -> { result = chooser.getColor(); dispose(); });
            setContentPane(root);
            setSize(620, 430);
            setLocationRelativeTo(owner);
        }
    }

    static final class Preset {
        String name; long address; int width, height, bits; int mode;String red="16:8",green="8:8",blue="0:8"; final Map<Integer, Color> colors;
        Preset(String name, long address, int width, int height, int bits, Map<Integer, Color> colors) { this.name=name; this.address=address; this.width=width; this.height=height; this.bits=bits; this.colors=colors; }
        void save(Path path) throws IOException {
            validateFramebufferRange(address,framebufferByteCount(width,height,bits));
            Properties p = new Properties(); p.setProperty("name", name); p.setProperty("address", String.format("%016X", address)); p.setProperty("width", Integer.toString(width)); p.setProperty("height", Integer.toString(height)); p.setProperty("bits", Integer.toString(bits));
            p.setProperty("mode",Integer.toString(mode));p.setProperty("red",red);p.setProperty("green",green);p.setProperty("blue",blue);
            for (var e : colors.entrySet()) p.setProperty("color." + Integer.toUnsignedString(e.getKey()), String.format("%08X", e.getValue().getRGB()));
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".vulcan-preset-", ".tmp");
            try {
                try (var out = Files.newOutputStream(temporary)) { p.store(out, "Vulcan Display Preset"); }
                try {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        static Preset load(Path path) throws Exception {
            Properties p = new Properties(); try (var in = Files.newInputStream(path)) { p.load(in); }
            String name = p.getProperty("name", path.getFileName().toString().replaceFirst("\\.preset$", ""));
            long address = Long.parseUnsignedLong(p.getProperty("address", Long.toHexString(Memory.VRAM_START)), 16); int width = Integer.parseInt(p.getProperty("width", "16")); int height = Integer.parseInt(p.getProperty("height", "16")); int bits = Integer.parseInt(p.getProperty("bits", "2"));
            if (name.isBlank() || name.length() > 80) throw new IllegalArgumentException("Preset name must contain 1 to 80 characters.");
            if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) throw new IllegalArgumentException("Preset dimensions must be between 1 and " + MAX_DIMENSION + ".");
            if (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16 && bits != 24 && bits != 32) throw new IllegalArgumentException("Preset bit depth must be 1, 2, 4, 8, 16, 24, or 32.");
            validateFramebufferRange(address,framebufferByteCount(width,height,bits));
            Map<Integer, Color> colors = new LinkedHashMap<>();
            int count = 1 << Math.min(bits, 8);
            for (int i=0;i<count;i++) {
                String value=p.getProperty("color."+i);
                if (value!=null) {
                    if (!value.matches("(?i)[0-9a-f]{8}")) throw new IllegalArgumentException("Invalid color value for color." + i + ".");
                    colors.put(i, new Color((int)Long.parseLong(value,16), true));
                }
            }
            for(String key:p.stringPropertyNames())if(key.startsWith("color.")){long index=Long.parseUnsignedLong(key.substring(6));long max=bits==32?0xFFFFFFFFL:(1L<<bits)-1;if(Long.compareUnsigned(index,max)>0)throw new IllegalArgumentException("Palette value exceeds pixel depth.");colors.put((int)index,new Color((int)Long.parseUnsignedLong(p.getProperty(key),16),true));}
            Map<Integer, Color> defaults = new DisplayPanelDefaults().defaults(bits);
            for (int i=0;i<count;i++) colors.putIfAbsent(i, defaults.get(i));
            Preset preset=new Preset(name,address,width,height,bits,colors);preset.mode=Integer.parseInt(p.getProperty("mode","0"));if(preset.mode<0||preset.mode>2)throw new IllegalArgumentException("Invalid RGB mapping mode.");preset.red=p.getProperty("red","16:8");preset.green=p.getProperty("green","8:8");preset.blue=p.getProperty("blue","0:8");channel(0,preset.red);channel(0,preset.green);channel(0,preset.blue);if(preset.mode==2){validateChannelRange(bits,preset.red);validateChannelRange(bits,preset.green);validateChannelRange(bits,preset.blue);}return preset;
        }
    }

    private static final class DisplayPanelDefaults {
        Map<Integer, Color> defaults(int bits) { Map<Integer,Color> m=new LinkedHashMap<>(); int n=1<<Math.min(bits,8); Color[] c={new Color(20,40,20),new Color(35,110,45),new Color(40,90,190),new Color(210,45,45)}; for(int i=0;i<n;i++)m.put(i,i<c.length?c[i]:Color.BLACK); return m; }
    }
}
