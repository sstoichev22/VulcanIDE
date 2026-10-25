package VulcanIDE;

import Intel8080.assembler.Assembler;
import Intel8080.cpu.Intel8080;
import Intel8080.cpu.CPUStateListener;
import Intel8080.cpu.Memory;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.*;

/** Disassembly view that follows the program counter and toggles breakpoints. */
public class DisassemblerPanel extends JPanel implements CPUStateListener {

    private final Intel8080 cpu;
    private final JTextArea disassembly;
    private final SettingsManager settings;

    private long programStart = -1;
    private long programEnd = -1;
    private boolean programLoaded;
    private Object currentHighlight;
    private boolean updatePending;
    private java.util.List<VulcanExecutable.Segment> segments=java.util.List.of();

    /** Creates a disassembly view of the CPU memory. */
    public DisassemblerPanel(Intel8080 cpu, SettingsManager settings) {
        this.cpu = cpu;
        this.settings = settings;

        setLayout(new BorderLayout());

        disassembly = new JTextArea();
        disassembly.setEditable(false);
        disassembly.setFocusable(true);
        TextContextMenu.install(disassembly);
        disassembly.setLineWrap(false);
        disassembly.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize("debuggerInstructions")));

        JScrollPane scroll = new JScrollPane(disassembly);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
        cpu.addStateListener(this);
        disassembly.setToolTipText("Double-click an instruction to toggle a breakpoint.");
        disassembly.addMouseListener(new java.awt.event.MouseAdapter(){@Override public void mouseClicked(java.awt.event.MouseEvent event){
            if(event.getClickCount()!=2)return;
            try{int line=disassembly.getLineOfOffset(disassembly.viewToModel2D(event.getPoint()));String text=disassembly.getText(disassembly.getLineStartOffset(line),disassembly.getLineEndOffset(line)-disassembly.getLineStartOffset(line));
                var address=java.util.regex.Pattern.compile("0x([0-9A-Fa-f]{16}):").matcher(text);
                if(address.find()){long physical=Long.parseUnsignedLong(address.group(1),16);cpu.setBreakpoint(physical,!cpu.hasBreakpoint(physical));refresh();}
            }catch(BadLocationException ignored){}
        }});
    }

    public void setExecutable(VulcanExecutable executable){segments=executable.segments();programLoaded=true;programStart=programEnd=-1;followExecution();refresh();}
    private void followExecution(){
        if(segments.isEmpty())return;long address=cpu.getExecutionAddress();
        for(var segment:segments)if(Long.compareUnsigned(address-segment.address(),segment.bytes().length)<0){
            if(Long.compareUnsigned(address-programStart,programEnd-programStart)<=0)return;
            programStart=address;programEnd=address+Math.min(2047,segment.bytes().length-1-(address-segment.address()));return;
        }
        programStart=address;programEnd=address+255;
    }

    /** Sets the disassembled range from 32-bit addresses. */
    public void setProgramRange(int start, int end) {
        setProgramRange((long) start, end);
    }

    /** Sets the inclusive 64-bit address range to disassemble. */
    public void setProgramRange(long start, long end) {
        segments=java.util.List.of();
        programStart = start;
        programEnd = start+Math.min(end-start,Memory.MEM_SIZE-1L);
        programLoaded = end-start>=0;
        clearCurrentHighlight();
        refresh();
    }

    /** Removes all disassembly. */
    public void clear() {
        segments=java.util.List.of();
        programStart = -1;
        programEnd = -1;
        programLoaded = false;
        clearCurrentHighlight();
        disassembly.setText("");
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        disassembly.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize("debuggerInstructions")));
        disassembly.setBackground(settings.getColor("editorBackground"));
        disassembly.setForeground(settings.getColor("editorText"));
        repaint();
    }

    /** Re-disassembles the range and highlights the current instruction. */
    public void refresh() {
        if (!programLoaded) {
            disassembly.setText("");
            clearCurrentHighlight();
            return;
        }

        String[] instructions = Assembler.Disassemble(cpu.getMemory(), programStart, programEnd);
        StringBuilder output = new StringBuilder();
        for (String instruction : instructions) {
            var address=java.util.regex.Pattern.compile("0x([0-9A-Fa-f]{16}):").matcher(instruction);
            boolean stop=address.find()&&cpu.hasBreakpoint(Long.parseUnsignedLong(address.group(1),16));
            output.append(stop?"● ":"  ").append(instruction).append('\n');
        }

        TextContextMenu.setTextPreservingSelection(disassembly, output.toString());
        clearCurrentHighlight();
        highlightCurrentInstruction();
    }

    private void highlightCurrentInstruction() {
        clearCurrentHighlight();
        if (!programLoaded) return;

        String target = String.format("0x%016X:", cpu.getPC());
        int index = disassembly.getText().indexOf(target);
        if (index < 0) return;

        try {
            int line = disassembly.getLineOfOffset(index);
            int start = disassembly.getLineStartOffset(line);
            int end = Math.max(start, disassembly.getLineEndOffset(line));

            Color base = settings.getColor("currentInstruction");
            Color color = new Color(base.getRed(), base.getGreen(), base.getBlue(), 210);

            currentHighlight = disassembly.getHighlighter().addHighlight(
                    start,
                    end,
                    new DefaultHighlighter.DefaultHighlightPainter(color)
            );

            java.awt.geom.Rectangle2D bounds = disassembly.modelToView2D(start);
            if (bounds != null) disassembly.scrollRectToVisible(bounds.getBounds());
        } catch (BadLocationException ignored) {
        }
    }

    private void clearCurrentHighlight() {
        if (currentHighlight != null) {
            disassembly.getHighlighter().removeHighlight(currentHighlight);
            currentHighlight = null;
        }
    }

    @Override
    public synchronized void cpuStateChanged() {
        if (updatePending) return;
        updatePending = true;
        SwingUtilities.invokeLater(() -> {
            try {
                long previous=programStart;followExecution();if(previous!=programStart)refresh();else highlightCurrentInstruction();
            } finally {
                synchronized (this) {
                    updatePending = false;
                }
            }
        });
    }
}
