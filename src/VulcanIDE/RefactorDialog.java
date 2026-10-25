package VulcanIDE;

import C8080.SourceRefactoring;
import java.awt.BorderLayout;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.*;

/** Refactor window for the editor selection: preview a semantic C refactoring, then apply it to every affected file. */
final class RefactorDialog {
    private static final SourceRefactoring.Operation[] OPERATIONS = SourceRefactoring.Operation.values();
    private static final String[] LABELS = {"Rename…", "Introduce Variable…", "Inline Variable"};

    private RefactorDialog() {}

    static void show(EditorPanel panel, SettingsManager settings, Supplier<List<EditorPanel>> panels) {
        Path file = panel.getCurrentPath();
        String name = file == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".c") && !name.endsWith(".h")) {
            VulcanDialog.message(panel, settings, "Refactor", "Refactoring is available in C sources and headers.");
            return;
        }
        Path path = file.toAbsolutePath().normalize();
        Path workspace = settings.getCurrentDirectory();
        Path root = workspace != null && path.startsWith(workspace.toAbsolutePath().normalize())
                ? workspace.toAbsolutePath().normalize() : path.getParent();
        int start = panel.refactoringSelectionStart(), end = panel.refactoringSelectionEnd();
        String selected = panel.projectText(path);
        String initial = selected == null ? "" : selected.substring(start, end).strip();

        Window window = SwingUtilities.getWindowAncestor(panel);
        JDialog dialog = new JDialog(window, "Refactor", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel shell = VulcanDialog.shell(dialog, settings, "Refactor");
        JPanel content = VulcanDialog.body(settings);
        JComboBox<String> operation = new JComboBox<>(LABELS);
        JTextField input = new JTextField(initial.matches("[A-Za-z_]\\w*") ? initial : "");
        VulcanDialog.styleInput(settings, input);
        JLabel status = VulcanDialog.label(settings, "Choose an operation and preview the changes.");
        JPanel fields = new JPanel(new BorderLayout(6, 6));
        fields.setOpaque(false);
        fields.add(operation, BorderLayout.NORTH);
        fields.add(input, BorderLayout.CENTER);
        fields.add(status, BorderLayout.SOUTH);
        JPanel buttons = VulcanDialog.buttons();
        JButton cancel = new JButton("Cancel"), preview = new JButton("Preview"), apply = new JButton("Apply");
        apply.setEnabled(false);
        buttons.add(cancel);
        buttons.add(preview);
        buttons.add(apply);
        content.add(fields, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        shell.add(content, BorderLayout.CENTER);

        AtomicBoolean cancelled = new AtomicBoolean();
        Object[] current = new Object[2]; // snapshot, preview
        Runnable invalidate = () -> { current[0] = current[1] = null; apply.setEnabled(false); };
        operation.addActionListener(event -> {
            input.setEnabled(OPERATIONS[operation.getSelectedIndex()] != SourceRefactoring.Operation.INLINE_VARIABLE);
            invalidate.run();
        });
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { invalidate.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { invalidate.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { invalidate.run(); }
        });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { cancelled.set(true); }
        });
        preview.addActionListener(event -> {
            invalidate.run();
            preview.setEnabled(false);
            status.setText("Analyzing project…");
            SourceRefactoring.Operation chosen = OPERATIONS[operation.getSelectedIndex()];
            String replacement = input.getText().strip();
            List<ProjectRefactoring.OpenDocument> documents = ProjectRefactoring.capture(panels);
            run(() -> {
                List<Path> includes = includes(path, workspace);
                ProjectRefactoring.Snapshot snapshot = ProjectRefactoring.scan(root, documents, includes, cancelled::get);
                SourceRefactoring.Preview result = SourceRefactoring.preview(new SourceRefactoring.Request(snapshot.sources(),
                        root, includes, path, start, end, chosen, replacement), cancelled::get);
                return new Object[]{snapshot, result};
            }, value -> {
                preview.setEnabled(true);
                Object[] pair = (Object[]) value;
                SourceRefactoring.Preview result = (SourceRefactoring.Preview) pair[1];
                if (!result.conflicts().isEmpty()) {
                    SourceRefactoring.Conflict conflict = result.conflicts().get(0);
                    status.setText(html("Cannot refactor: " + conflict.message()));
                } else if (result.edits().isEmpty()) {
                    status.setText("Nothing to change.");
                } else {
                    current[0] = pair[0];
                    current[1] = result;
                    List<String> names = new ArrayList<>();
                    for (SourceRefactoring.FileEdit edit : result.edits()) names.add(root.relativize(edit.path()).toString());
                    status.setText(html(result.edits().size() + " file(s) will change: " + String.join(", ", names)));
                    apply.setEnabled(true);
                }
                dialog.pack();
            }, error -> { preview.setEnabled(true); status.setText(html(VulcanDialog.errorSummary(error))); dialog.pack(); });
        });
        apply.addActionListener(event -> {
            ProjectRefactoring.Snapshot snapshot = (ProjectRefactoring.Snapshot) current[0];
            SourceRefactoring.Preview result = (SourceRefactoring.Preview) current[1];
            if (snapshot == null || result == null) return;
            apply.setEnabled(false);
            preview.setEnabled(false);
            status.setText("Applying…");
            run(() -> { ProjectRefactoring.apply(snapshot, result, panels, cancelled::get); return null; },
                    value -> dialog.dispose(),
                    error -> { preview.setEnabled(true); status.setText(html(VulcanDialog.errorSummary(error))); dialog.pack(); });
        });
        input.addActionListener(event -> (apply.isEnabled() ? apply : preview).doClick());
        dialog.getRootPane().setDefaultButton(preview);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setSize(Math.max(460, dialog.getWidth()), dialog.getHeight());
        dialog.setLocationRelativeTo(window);
        SwingUtilities.invokeLater(input::requestFocusInWindow);
        dialog.setVisible(true);
    }

    /** The same include search order the editor diagnostics use, limited to directories that exist. */
    private static List<Path> includes(Path file, Path workspace) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(file.getParent());
        if (workspace != null) candidates.add(workspace.resolve("src/stdlib/include"));
        candidates.add(Path.of("src/stdlib/include"));
        Path bundled = StandardHeaders.directory();
        if (bundled != null) candidates.add(bundled);
        return candidates.stream().map(path -> path.toAbsolutePath().normalize()).filter(Files::isDirectory).distinct().toList();
    }

    private static String html(String text) {
        return "<html><body style='width:400px'>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>";
    }

    @FunctionalInterface private interface Work { Object call() throws Exception; }

    private static void run(Work work, java.util.function.Consumer<Object> done, java.util.function.Consumer<Throwable> failed) {
        new SwingWorker<Object, Void>() {
            @Override protected Object doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                try { done.accept(get()); }
                catch (java.util.concurrent.ExecutionException error) { failed.accept(error.getCause()); }
                catch (Exception error) { failed.accept(error); }
            }
        }.execute();
    }
}
