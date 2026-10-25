package VulcanIDE;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Cut, copy, paste, and select-all menu for text components. */
final class TextContextMenu {
    private TextContextMenu() {}

    static void install(JTextComponent text) { install(text, null); }

    /** Adds a Refactor item for the selection when {@code refactor} is not null. */
    static void install(JTextComponent text, Runnable refactor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem refactorItem = new JMenuItem("Refactor...");
        if (refactor != null) refactorItem.addActionListener(event -> refactor.run());
        JMenuItem cut = new JMenuItem("Cut");
        JMenuItem copy = new JMenuItem("Copy");
        JMenuItem paste = new JMenuItem("Paste");
        JMenuItem selectAll = new JMenuItem("Select All");
        cut.addActionListener(event -> text.cut());
        copy.addActionListener(event -> text.copy());
        paste.addActionListener(event -> text.paste());
        selectAll.addActionListener(event -> text.selectAll());
        menu.add(cut);
        menu.add(copy);
        menu.add(paste);
        menu.addSeparator();
        menu.add(selectAll);
        if (refactor != null) { menu.addSeparator(); menu.add(refactorItem); }
        updateFamily(menu,text.getFont());
        text.addPropertyChangeListener("font",event->updateFamily(menu,text.getFont()));
        text.addMouseListener(new MouseAdapter() {
            private void show(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                if (text.viewToModel2D(event.getPoint()) < text.getSelectionStart()
                        || text.viewToModel2D(event.getPoint()) > text.getSelectionEnd())
                    text.setCaretPosition(text.viewToModel2D(event.getPoint()));
                boolean hasSelection = text.getSelectionStart() != text.getSelectionEnd();
                cut.setEnabled(text.isEditable() && hasSelection);
                copy.setEnabled(hasSelection || text.getDocument().getLength() > 0);
                paste.setEnabled(text.isEditable());
                selectAll.setEnabled(text.getDocument().getLength() > 0);
                refactorItem.setEnabled(text.isEditable() && hasSelection);
                menu.show(text, event.getX(), event.getY());
            }
            @Override public void mousePressed(MouseEvent event) { show(event); }
            @Override public void mouseReleased(MouseEvent event) { show(event); }
        });
    }

    private static void updateFamily(Component component,Font sourceFont) {
        if(sourceFont==null)return;
        Font existing=component.getFont();
        if(existing!=null)component.setFont(new Font(sourceFont.getFamily(),existing.getStyle(),existing.getSize()));
        if(component instanceof Container container)for(Component child:container.getComponents())updateFamily(child,sourceFont);
    }

    static void setTextPreservingSelection(JTextComponent component, String value) {
        if (value.equals(component.getText())) return;
        int start = component.getSelectionStart();
        int end = component.getSelectionEnd();
        component.setText(value);
        int length = component.getDocument().getLength();
        start = Math.min(start, length);
        end = Math.min(end, length);
        component.setCaretPosition(start);
        if (end > start) component.moveCaretPosition(end);
    }
}
