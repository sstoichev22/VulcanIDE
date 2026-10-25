package VulcanIDE;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/** Editor gutter showing line numbers, breakpoints, and the current line. */
public class LineNumberView extends JComponent {
    private static final int GUTTER_WIDTH = 60;
    private final JTextComponent editor;
    private final SettingsManager settings;

    /** Creates a gutter for the editor. */
    public LineNumberView(JTextComponent editor, SettingsManager settings) {
        this.editor = editor;
        this.settings = settings;

        setOpaque(true);
        setPreferredSize(new Dimension(GUTTER_WIDTH, 1));

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { textChanged(e); }
            @Override public void removeUpdate(DocumentEvent e) { textChanged(e); }
            @Override public void changedUpdate(DocumentEvent e) { repaint(); }
            private void textChanged(DocumentEvent event) {
                if (event.getChange(editor.getDocument().getDefaultRootElement()) != null) refreshGeometry();
                else repaint();
            }
        });

        editor.addPropertyChangeListener("font", e -> refreshGeometry());
        editor.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { refreshGeometry(); }
        });

    }

    private void refreshGeometry() {
        revalidate();
        repaint();
    }

    private int gutterWidth() {
        int digits = Math.max(4, Integer.toString(editor.getDocument().getDefaultRootElement().getElementCount()).length());
        return Math.max(GUTTER_WIDTH, editor.getFontMetrics(editor.getFont()).charWidth('0') * digits + 24);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {

            Object antialiasing = editor.getClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING);
            if (antialiasing != null) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, antialiasing);
            Object contrast = editor.getClientProperty(RenderingHints.KEY_TEXT_LCD_CONTRAST);
            if (contrast != null) g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, contrast);
            g.setColor(settings.getColor("editorBackground"));
            g.fillRect(0, 0, getWidth(), getHeight());

            FontMetrics metrics = editor.getFontMetrics(editor.getFont());
            Element root = editor.getDocument().getDefaultRootElement();
            if (root == null || root.getElementCount() == 0) {
                drawDivider(g);
                return;
            }

            Rectangle clip = g.getClipBounds();
            int firstLine = lineFromY(clip.y);
            int lastLine = lineFromY(clip.y + clip.height + metrics.getHeight());

            firstLine = Math.max(0, firstLine - 1);
            lastLine = Math.min(root.getElementCount() - 1, lastLine + 1);

            g.setFont(editor.getFont());

            for (int lineIndex = firstLine; lineIndex <= lastLine; lineIndex++) {
                Element line = root.getElement(lineIndex);
                int start = Math.min(line.getStartOffset(), editor.getDocument().getLength());
                Rectangle2D row;
                try {
                    row = editor.modelToView2D(start);
                } catch (BadLocationException | IllegalArgumentException e) {
                    continue;
                }
                if (row == null) continue;

                double y = row.getY();
                double height = Math.max(1.0, row.getHeight());
                if (y + height < clip.y - metrics.getHeight() || y > clip.y + clip.height + metrics.getHeight()) continue;

                Font rowFont = editor.getFont();
                if (editor.getDocument() instanceof StyledDocument document) {
                    rowFont = document.getFont(document.getCharacterElement(start).getAttributes());
                }
                FontMetrics rowMetrics = editor.getFontMetrics(rowFont);
                int baseline = (int) Math.round(y + height - rowMetrics.getDescent());

                int lineNumber = lineIndex + 1;
                g.setColor(settings.getColor("lineNumber"));
                String text = Integer.toString(lineNumber);
                int textWidth = metrics.stringWidth(text);
                g.drawString(text, getWidth() - textWidth - 10, baseline);
            }

            drawDivider(g);
        } finally {
            g.dispose();
        }
    }

    private int lineFromY(int y) {
        try {
            Point2D point = new Point2D.Double(Math.max(0, editor.getInsets().left + 1), Math.max(0, y));
            int model = editor.viewToModel2D(point);
            if (model < 0) return 0;
            return editor.getDocument().getDefaultRootElement().getElementIndex(model);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** Returns a lower-left scroll-pane corner that continues the gutter so the horizontal scrollbar track ends at the code area. */
    JComponent corner() {
        return new JComponent() {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics;
                g.setColor(settings.getColor("editorBackground"));
                g.fillRect(0, 0, getWidth(), getHeight());
                drawDivider(g, getWidth(), getHeight());
            }
        };
    }

    private void drawDivider(Graphics2D g, int width, int height) {
        g.setColor(settings.getColor("splitDivider"));
        g.fillRect(width - 1, 0, 1, height);
    }

    private void drawDivider(Graphics2D g) {
        g.setColor(settings.getColor("splitDivider"));
        g.fillRect(getWidth() - 1, 0, 1, getHeight());
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(gutterWidth(), Math.max(1, editor.getPreferredSize().height));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(gutterWidth(), 1);
    }

    /** Reapplies the current theme colors and fonts. */
    public void refreshTheme() {
        refreshGeometry();
    }
}
