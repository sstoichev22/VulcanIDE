package VulcanIDE;

/** Console used by the debugger tab. */
public final class DebugConsolePanel extends ConsolePanel {

    /** Creates the debugger console. */
    public DebugConsolePanel(SettingsManager settings) {
        super(settings, "debuggerConsole");
        setTitle("Console");
    }
}
