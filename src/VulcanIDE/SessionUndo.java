package VulcanIDE;

import javax.swing.*;
import javax.swing.text.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Undo and redo history of editor text changes made during this session. */
final class SessionUndo extends DocumentFilter {
    private record Edit(int offset, String before, String after) {}
    private final JTextPane editor;
    private final BooleanSupplier suspended;
    private final ArrayList<Edit> history = new ArrayList<>();
    private int cursor;
    private boolean replaying;
    SessionUndo(JTextPane editor, BooleanSupplier suspended) {
        this.editor = editor; this.suspended = suspended;
        ((AbstractDocument) editor.getDocument()).setDocumentFilter(this);
    }
    public void insertString(FilterBypass fb,int offset,String text,AttributeSet attrs)throws BadLocationException { replace(fb,offset,0,text,attrs); }
    public void remove(FilterBypass fb,int offset,int length)throws BadLocationException { replace(fb,offset,length,"",null); }
    /** Records the replaced text before applying the edit. */
    public void replace(FilterBypass fb,int offset,int length,String text,AttributeSet attrs)throws BadLocationException {
        String after=text==null?"":text;
        String before=fb.getDocument().getText(offset,length);
        fb.replace(offset,length,after,attrs);
        if(!replaying&&!suspended.getAsBoolean()&&!before.equals(after)) {
            history.subList(cursor,history.size()).clear();
            history.add(new Edit(offset,before,after)); cursor++;
        }
    }
    record Checkpoint(List<Edit> history, int cursor) {}
    Checkpoint checkpoint(){return new Checkpoint(List.copyOf(history),cursor);}
    void restore(Checkpoint checkpoint){history.clear();history.addAll(checkpoint.history());cursor=checkpoint.cursor();}
    void clear(){history.clear();cursor=0;}
    void undo(){if(cursor>0){replay(history.get(cursor-1),false);cursor--;}}
    void redo(){if(cursor<history.size()){replay(history.get(cursor),true);cursor++;}}
    private void replay(Edit edit,boolean forward){
        replaying=true;
        try {
            String from=forward?edit.before:edit.after,to=forward?edit.after:edit.before;
            ((AbstractDocument)editor.getDocument()).replace(edit.offset,from.length(),to,null);
            editor.setCaretPosition(edit.offset+to.length());
        }catch(BadLocationException error){throw new IllegalStateException("Undo history no longer matches this buffer",error);}
        finally{replaying=false;}
    }
}
