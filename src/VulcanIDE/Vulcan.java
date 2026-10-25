package VulcanIDE;

import javax.swing.SwingUtilities;

/** Starts VulcanIDE and applies application-wide Java2D defaults. */
public final class Vulcan {
    public static final String VERSION = "0.6.11";

    private Vulcan() {}

    /**
     * Disables Java2D's Direct3D pipeline when the user has not selected one.
     * An explicit {@code sun.java2d.d3d} system property remains authoritative.
     */
    public static void configureRenderingDefaults() {
        if (System.getProperty("sun.java2d.d3d") == null) {
            System.setProperty("sun.java2d.d3d", "false");
        }
    }

    /** Creates and displays the main window on Swing's event-dispatch thread. */
    public static void main(String[] args) {
        configureRenderingDefaults();
        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }
}
