package VulcanIDE;

import Intel8080.cpu.Intel8080;
import Intel8080.cpu.CPUStateListener;

import javax.swing.*;
import java.awt.*;

/** Live view of CPU registers and flags. */
public class RegistersPanel extends JPanel implements CPUStateListener {

    private final Intel8080 cpu;
    private final SettingsManager settings;
    private final JTextArea registers;
    private boolean updatePending;

    /** Creates a register view of the CPU. */
    public RegistersPanel(Intel8080 cpu, SettingsManager settings) {
        this.cpu = cpu;
        this.settings = settings;

        setLayout(new BorderLayout());

        registers = new JTextArea();
        registers.setEditable(false);
        registers.setFocusable(true);
        TextContextMenu.install(registers);
        registers.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize("debuggerRegisters")));
        registers.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JScrollPane scroll = new JScrollPane(registers);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);

        cpu.addStateListener(this);
        updateRegisters();
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        registers.setFont(new Font(settings.getApplicationFont(), Font.PLAIN, settings.getFontSize("debuggerRegisters")));
        registers.setBackground(settings.getColor("editorBackground"));
        registers.setForeground(settings.getColor("editorText"));
        repaint();
    }

    private void updateRegisters() {
        TextContextMenu.setTextPreservingSelection(registers,
                String.format("A   0x%08X\nB   0x%08X\nC   0x%08X\nD   0x%08X\nE   0x%08X\nH   0x%08X\nL   0x%08X\n\nBC  0x%016X\nDE  0x%016X\nHL  0x%016X\nSP  0x%016X\nPC  0x%016X",
                    cpu.getA(),cpu.getB(),cpu.getC(),cpu.getD(),cpu.getE(),cpu.getH(),cpu.getL(),cpu.getBC(),cpu.getDE(),cpu.getHL(),cpu.getMemory().getSp(),cpu.getPC()));

    }

    @Override
    public synchronized void cpuStateChanged() {
        if (updatePending) return;
        updatePending = true;
        SwingUtilities.invokeLater(() -> {
            updateRegisters();
            synchronized (this) {
                updatePending = false;
            }
        });
    }
}
