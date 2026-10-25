package VulcanIDE;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;

/** Non-wrapping source editor with four-space tab stops and scrolling past the last line. */
final class CodeTextPane extends JTextPane {
    @Override public JToolTip createToolTip() {
        JToolTip tip=new JToolTip();tip.setComponent(this);tip.setFont(getFont());
        tip.setBackground(new Color(38,41,46));tip.setForeground(getForeground());
        tip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(100,106,116)),BorderFactory.createEmptyBorder(10,12,10,12)));
        return tip;
    }
    CodeTextPane(){setEditorKit(new StyledEditorKit(){
        /** Creates views whose paragraphs never wrap and whose tabs align to four spaces. */
        public ViewFactory getViewFactory(){return element -> switch(element.getName()) {
            case AbstractDocument.ContentElementName -> new LabelView(element);
            case AbstractDocument.ParagraphElementName -> new ParagraphView(element){
                /** Returns the next four-space tab stop after x. */
                public float nextTabStop(float x,int tabOffset){

                    float step=Math.max(1,CodeTextPane.this.getFontMetrics(CodeTextPane.this.getFont()).charWidth(' ')*4);
                    float origin=CodeTextPane.this.getInsets().left+StyleConstants.getLeftIndent(getAttributes());
                    return origin+((float)Math.floor((x-origin)/step)+1)*step;
                }
                protected void layout(int width,int height){super.layout(Integer.MAX_VALUE/1024,height);}
                public float getMinimumSpan(int axis){return axis==View.X_AXIS?getPreferredSpan(axis):super.getMinimumSpan(axis);}
            };
            case AbstractDocument.SectionElementName -> new BoxView(element,View.Y_AXIS);
            case StyleConstants.ComponentElementName -> new ComponentView(element);
            case StyleConstants.IconElementName -> new IconView(element);
            default -> new LabelView(element);
        };}
    });
        setCaret(new BaselineCaret());}

    private java.util.function.BooleanSupplier caretScrollSuppressed=()->false;

    /** Suspends caret-driven scrolling while the supplier is true, for example during drag selection. */
    void setCaretScrollSuppressed(java.util.function.BooleanSupplier suppressed){caretScrollSuppressed=suppressed==null?()->false:suppressed;}

    /** Replaces Swing's minimal caret scroll with one baseline-aware viewport move so typing never scrolls twice. */
    private final class BaselineCaret extends DefaultCaret {
        BaselineCaret(){setBlinkRate(UIManager.getInt("TextPane.caretBlinkRate")>0?UIManager.getInt("TextPane.caretBlinkRate"):500);}
        @Override protected void adjustVisibility(Rectangle r){
            if(caretScrollSuppressed.getAsBoolean())return;
            if(r!=null&&getParent() instanceof JViewport viewport)keepCaretVisible(CodeTextPane.this,viewport,r);
            else super.adjustVisibility(r);
        }
    }
    /** Keeps long logical lines horizontal rather than wrapping them to the viewport. */
    @Override public boolean getScrollableTracksViewportWidth(){return false;}
    /** Uses the logical-line scroll extent even when a short document fits on screen. */
    @Override public boolean getScrollableTracksViewportHeight(){return false;}

    static int characterOffsetAt(JTextPane pane,Point point){
        try {
            int offset=pane.viewToModel2D(point);if(offset<0)return -1;
            Rectangle caret=pane.modelToView2D(offset).getBounds();
            if(offset>0&&point.x<caret.x){Rectangle previous=pane.modelToView2D(offset-1).getBounds();if(previous.y==caret.y)offset--;}
            if(offset>=pane.getDocument().getLength())return -1;
            Rectangle left=pane.modelToView2D(offset).getBounds(),right=pane.modelToView2D(offset+1).getBounds();
            return point.y>=left.y&&point.y<left.y+left.height&&point.x>=left.x&&(right.y!=left.y||point.x<right.x)?offset:-1;
        }catch(BadLocationException invalid){return -1;}
    }
    static void keepCaretVisible(JTextPane editor,JViewport viewport,Rectangle r) {
        Point current=viewport.getViewPosition();
        Dimension extent=viewport.getExtentSize();
        if(extent.width<=0||extent.height<=0)return;
        Dimension view=viewport.getViewSize();
        int left=Math.max(0,r.x-24);
        int right=left+r.width+48;
        int x=current.x;
        if(left<x)x=left;
        else if(right>x+extent.width)x=right-extent.width;
        int baseline=(int)(extent.height*0.70);
        int bottom=r.y+Math.max(1,r.height);
        int y=current.y;
        if(r.y<y)y=r.y;
        else if(bottom>y+baseline)y=bottom-baseline;
        x=Math.max(0,Math.min(Math.max(0,view.width-extent.width),x));
        y=Math.max(0,Math.min(Math.max(0,view.height-extent.height),y));
        Point target=new Point(x,y);
        if(!target.equals(current))viewport.setViewPosition(target);
    }
    /** Extends the scroll range only to the last logical row, using its actual styled view height. */
    @Override public Dimension getPreferredSize(){
        Dimension size=super.getPreferredSize();
        if(getParent() instanceof JViewport viewport && getUI()!=null){
            Dimension extent=viewport.getExtentSize();
            View section=getUI().getRootView(this).getView(0);
            int count=section.getViewCount();
            int lastTop=count==0?0:Math.max(0,(int)Math.ceil(section.getPreferredSpan(View.Y_AXIS)-section.getView(count-1).getPreferredSpan(View.Y_AXIS)));
            size.width=Math.max(size.width,extent.width);
            size.height=Math.max(size.height,extent.height+lastTop);
        }
        return size;
    }

    /** Pastes plain text, rebasing multiline blocks only when the insertion prefix is indentation.
     * Read-only and disabled editors remain unchanged; clipboard unavailability leaves the document untouched.
     */
    @Override public void paste(){
        if(!isEditable()||!isEnabled())return;
        try {
            Object data=getToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if(!(data instanceof String text))return;
            text=text.replace("\r\n","\n").replace('\r','\n');
            String before=getDocument().getText(0,getSelectionStart());
            String prefix=before.substring(CIndentation.lineStart(before,before.length()));
            if(text.indexOf('\n')>=0&&CIndentation.leading(prefix).equals(prefix)){
                String[] lines=text.split("\n",-1);
                int base=Integer.MAX_VALUE;
                for(String line:lines)if(!line.isBlank())base=Math.min(base,indentColumns(CIndentation.leading(line)));
                StringBuilder rebased=new StringBuilder(text.length());
                for(int index=0;index<lines.length;index++){
                    if(index>0)rebased.append('\n');
                    String line=lines[index];
                    if(base==Integer.MAX_VALUE){rebased.append(line);continue;}
                    if(line.isEmpty())continue;
                    if(index>0)rebased.append(prefix);
                    rebased.append(removeIndent(line,base));
                }
                text=rebased.toString();
            }
            replaceSelection(text);
        }catch(java.awt.datatransfer.UnsupportedFlavorException|java.io.IOException|IllegalStateException|BadLocationException unavailable){
            UIManager.getLookAndFeel().provideErrorFeedback(this);
        }
    }

    private static int indentColumns(String indent){
        int column=0;
        for(int index=0;index<indent.length();index++)column=indent.charAt(index)=='\t'?(column/4+1)*4:column+1;
        return column;
    }

    private static String removeIndent(String line,int columns){
        int index=0,column=0;
        while(index<line.length()&&column<columns&&(line.charAt(index)==' '||line.charAt(index)=='\t')){
            column=line.charAt(index++)=='\t'?(column/4+1)*4:column+1;
        }
        return " ".repeat(Math.max(0,column-columns))+line.substring(index);
    }
}
