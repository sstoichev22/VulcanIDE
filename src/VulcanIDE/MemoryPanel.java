package VulcanIDE;

import Intel8080.cpu.Intel8080;
import Intel8080.cpu.CPUStateListener;
import Intel8080.cpu.Memory;

import javax.swing.*;
import java.awt.*;

/** Hex view of CPU memory around the loaded program. */
public class MemoryPanel extends JPanel implements CPUStateListener {
    private final Intel8080 cpu;
    private final SettingsManager settings;
    private final JTextArea memoryView;
    private final Timer refreshTimer;
    private long programStart = -1;
    private long programEnd = -1;
    private boolean programLoaded;
    private final JTextField addressField=new JTextField(20);

    /** Creates a memory view of the CPU memory. */
    public MemoryPanel(Intel8080 cpu, SettingsManager settings) {
        this.cpu = cpu;
        this.settings = settings;
        setLayout(new BorderLayout());
        JPanel addressBar=new JPanel(new FlowLayout(FlowLayout.LEFT,6,3));addressBar.add(new JLabel("Address"));addressBar.add(addressField);add(addressBar,BorderLayout.NORTH);
        addressField.addActionListener(event->{try{String text=addressField.getText().trim();long address=text.startsWith("0x")||text.startsWith("0X")?Long.parseUnsignedLong(text.substring(2),16):text.contains(":")?Long.parseUnsignedLong(text.replace(":",""),16):Long.parseUnsignedLong(text);
            setProgramRange(address,address+Math.min(511L,~address>=0?~address:511L));
        }catch(NumberFormatException error){addressField.setToolTipText("Enter a 64-bit address, for example 0x0000000100000000.");}});
        memoryView = new JTextArea();
        memoryView.setEditable(false);
        memoryView.setFocusable(true);
        TextContextMenu.install(memoryView);
        memoryView.setLineWrap(false);
        memoryView.setFont(new Font(settings.getEditorFont(), Font.PLAIN, settings.getFontSize("debuggerMemory")));
        memoryView.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        JScrollPane scroll = new JScrollPane(memoryView);
        scroll.setBorder(null);
        Theme.styleScrollBar(scroll.getVerticalScrollBar(), settings);
        Theme.styleScrollBar(scroll.getHorizontalScrollBar(), settings);
        add(scroll, BorderLayout.CENTER);
        refreshTimer = new Timer(120, e -> refresh());
        refreshTimer.setRepeats(false);
        cpu.addStateListener(this);
    }

    /** Sets the displayed range from 32-bit addresses. */
    public void setProgramRange(int start, int end) {
        setProgramRange((long) start, end);
    }

    /** Sets the inclusive 64-bit address range to display. */
    public void setProgramRange(long start, long end) {
        programStart = start;
        programEnd = programStart + Math.min(end-start,Memory.MEM_SIZE-1L);
        programLoaded = end-start>=0;
        addressField.setText(String.format("0x%016X",programStart));
        refresh();
    }

    /** Removes all rows. */
    public void clear() {
        programStart = -1;
        programEnd = -1;
        programLoaded = false;
        memoryView.setText("");
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        memoryView.setBackground(settings.getColor("editorBackground"));
        memoryView.setForeground(settings.getColor("editorText"));
        memoryView.setFont(new Font(settings.getEditorFont(), Font.PLAIN, settings.getFontSize("debuggerMemory")));
        repaint();
    }

    /** Re-reads memory and highlights changed bytes. */
    public void refresh() {
        if (!programLoaded) {
            memoryView.setText("");
            return;
        }
        StringBuilder output = new StringBuilder();
        long length=programEnd-programStart+1;
        for(long row=0;row<length;row+=16){
            output.append(String.format("0x%016X  ",programStart+row));
            for(long n=row;n<Math.min(length,row+16);n++)output.append(String.format("%02X ",cpu.getMemory().get(programStart+n)&255));
            output.append('\n');
        }
        TextContextMenu.setTextPreservingSelection(memoryView, output.toString());
    }

    @Override
    public void cpuStateChanged() {
        Runnable schedule = () -> { if (!refreshTimer.isRunning()) refreshTimer.start(); };
        if (SwingUtilities.isEventDispatchThread()) schedule.run();
        else SwingUtilities.invokeLater(schedule);
    }
}
