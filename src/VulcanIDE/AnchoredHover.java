package VulcanIDE;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.BiConsumer;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Delayed, persistent hover card anchored below a source token, with clickable navigation links. */
final class AnchoredHover {
    private final JTextPane editor;
    private final java.util.function.Supplier<Point> pointer;
    private final BooleanSupplier ownerIsActive;
    private final javax.swing.Timer delay,exitGrace,watch;
    private HoverContent.Info target;
    private JWindow window;
    private JEditorPane card;
    private boolean caretTarget;
    private BiConsumer<Path,Integer> navigate=(file,offset)->{};
    AnchoredHover(JTextPane editor) {
        this(editor, () -> {
            PointerInfo info = MouseInfo.getPointerInfo();
            return info == null ? null : info.getLocation();
        }, null);
    }

    AnchoredHover(JTextPane editor, java.util.function.Supplier<Point> pointer) {
        this(editor, pointer, null);
    }

    AnchoredHover(JTextPane editor, java.util.function.Supplier<Point> pointer,
                  BooleanSupplier ownerIsActive) {
        this.editor = editor;
        this.pointer = pointer;
        this.ownerIsActive = ownerIsActive == null ? this::windowOwnerIsActive : ownerIsActive;
        delay = new javax.swing.Timer(1000, event -> show());
        delay.setRepeats(false);
        exitGrace = new javax.swing.Timer(280, event -> {
            if (!overTokenOrCard()) {
                hide();
            }
        });
        exitGrace.setRepeats(false);
        watch = new javax.swing.Timer(80, event -> {
            if (!editor.isShowing() || !this.ownerIsActive.getAsBoolean()) {
                hide();
            } else if (overTokenOrCard()) {
                exitGrace.stop();
            } else if (!exitGrace.isRunning()) {
                exitGrace.start();
            }
        });
        editor.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent event) {
                exitGrace.restart();
            }
        });
        editor.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !editor.isShowing()) {
                hide();
            }
        });
        editor.addPropertyChangeListener("font", event -> hide());
        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    hide();
                }
            }
        });
    }
    void setNavigator(BiConsumer<Path,Integer> navigator){navigate=navigator;}
    void targetAtCaret(HoverContent.Info info){
        if(info==null){hide();return;}
        boolean sameTarget=target!=null&&target.start()==info.start()&&target.end()==info.end()&&target.text().equals(info.text());
        target(info);
        caretTarget=true;
        if(sameTarget&&(window==null||!window.isVisible()))delay.restart();
    }
    void target(HoverContent.Info info){
        caretTarget=false;
        if(info==null){exitGrace.restart();return;}
        if(target!=null&&target.start()==info.start()&&target.end()==info.end()&&target.text().equals(info.text())){exitGrace.stop();return;}
        if(window!=null&&window.isVisible()&&overCardOrBridge())return;
        hide();target=info;editor.setToolTipText(html(info));ToolTipManager.sharedInstance().unregisterComponent(editor);delay.restart();
    }
    int anchorOffset(){return target==null?-1:target.start();}
    void refresh(HoverContent.Info info){
        if(target==null)return;
        if(info==null)info=new HoverContent.Info(target.start(),target.end(),target.title(),"This declaration is no longer present in the included files.",null,0,null);
        target=info;
        if(window!=null&&window.isVisible()&&card!=null){card.setText(html(info));positionAtLeadingEdge(card,((JScrollPane)window.getContentPane()).getViewport());}
        editor.setToolTipText(html(info));ToolTipManager.sharedInstance().unregisterComponent(editor);
    }
    int contentWidth(HoverContent.Info info){
        FontMetrics metrics=editor.getFontMetrics(editor.getFont());
        int width=0;for(String line:info.text().split("\\R"))width=Math.max(width,metrics.stringWidth(line));
        int editorWidth=editor.getWidth();if(editor.getParent() instanceof JViewport viewport)editorWidth=viewport.getExtentSize().width;if(editorWidth<=0)editorWidth=editor.getVisibleRect().width;if(editorWidth<=0)editorWidth=480;
        int limit=Math.max(1,editorWidth*2/3);GraphicsConfiguration configuration=editor.getGraphicsConfiguration();if(configuration!=null)limit=Math.min(limit,Math.max(1,configuration.getBounds().width-32));
        return Math.min(limit,Math.max(160,width+42));
    }
    static void positionAtLeadingEdge(JEditorPane card,JViewport viewport){
        card.setCaretPosition(0);
        viewport.setViewPosition(new Point(0,0));
    }
    String html(HoverContent.Info info){
        int width=contentWidth(info)-42,columns=Math.max(1,width/Math.max(1,editor.getFontMetrics(editor.getFont()).charWidth('m')));
        return "<html><body style='margin:0;font-family:"+escape(editor.getFont().getFamily())+";font-size:"+editor.getFont().getSize()+"pt;color:#e1e4eb;background:#26292e'><b>"+escape(info.title())+"</b><hr><div style='width:"+width+"px'>"+wrapped(info.text(),columns)+"</div>"+links(info)+"</body></html>";
    }
    private static String links(HoverContent.Info info){
        String first=info.destination()==null?"":"<a style='color:#8dbbf7' href='declaration'>"+escape(info.link())+"</a>";
        String second=info.secondDestination()==null?"":"<a style='color:#8dbbf7' href='second'>"+escape(info.secondLink())+"</a>";
        if(first.isEmpty()&&second.isEmpty())return "";
        return "<p>"+first+(first.isEmpty()||second.isEmpty()?"":"&nbsp;&nbsp;&middot;&nbsp;&nbsp;")+second+"</p>";
    }
    private static String wrapped(String text,int columns){
        StringBuilder result=new StringBuilder();
        for(String line:text.split("\\R",-1)){
            if(result.length()>0)result.append("<br>");
            for(String token:line.split("(?<=\\s)|(?=\\s)")){
                while(token.length()>columns){
                    int split=Math.max(token.lastIndexOf('/',columns-1),token.lastIndexOf('\\',columns-1))+1;
                    if(split<columns/2)split=columns;
                    result.append(escape(token.substring(0,split))).append("<br>");token=token.substring(split);
                }
                result.append(escape(token));
            }
        }
        return result.toString();
    }
    private void show(){
        if(target==null||GraphicsEnvironment.isHeadless()||!editor.isShowing())return;
        if(!overTokenOrCard()){hide();return;}
        Window owner=SwingUtilities.getWindowAncestor(editor);window=new JWindow(owner);window.setFocusableWindowState(false);window.setAutoRequestFocus(false);
        card=new JEditorPane("text/html",html(target));card.setEditable(false);card.setFont(editor.getFont());card.setBackground(new Color(38,41,46));card.setForeground(editor.getForeground());card.setBorder(BorderFactory.createEmptyBorder(10,12,10,12));
        card.addHyperlinkListener(event->{if(event.getEventType()!=javax.swing.event.HyperlinkEvent.EventType.ACTIVATED||target==null)return;HoverContent.Info destination=target;boolean second="second".equals(event.getDescription());Path file=second?destination.secondDestination():destination.destination();if(file==null)return;int offset=second?destination.secondDestinationOffset():destination.destinationOffset();hide();navigate.accept(file,offset);});
        card.addMouseListener(new MouseAdapter(){public void mouseEntered(MouseEvent e){exitGrace.stop();}public void mouseExited(MouseEvent e){exitGrace.restart();}});
        JScrollPane scroll=new JScrollPane(card);scroll.setBorder(BorderFactory.createLineBorder(new Color(100,106,116)));
        int available=editor.getGraphicsConfiguration().getBounds().width-32;
        int width=Math.min(available,contentWidth(target));
        card.setSize(new Dimension(width-2,Integer.MAX_VALUE/1024));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        int contentHeight=card.getPreferredSize().height+2;
        if(contentHeight>430)width=Math.min(available,width+scroll.getVerticalScrollBar().getPreferredSize().width);
        scroll.setPreferredSize(new Dimension(width,Math.min(430,Math.max(80,contentHeight))));window.setContentPane(scroll);window.pack();positionAtLeadingEdge(card,scroll.getViewport());
        try {Rectangle token=editor.modelToView2D(target.start()).getBounds();Point point=new Point(token.x,token.y+token.height+2);SwingUtilities.convertPointToScreen(point,editor);Rectangle screen=editor.getGraphicsConfiguration().getBounds();point.x=Math.max(screen.x,Math.min(point.x,screen.x+screen.width-window.getWidth()));if(point.y+window.getHeight()>screen.y+screen.height)point.y=Math.max(screen.y,point.y-token.height-window.getHeight()-4);window.setLocation(point);window.setVisible(true);watch.start();}catch(Exception gone){hide();}
    }
    private boolean windowOwnerIsActive(){Window owner=SwingUtilities.getWindowAncestor(editor);return owner==null||owner.isActive();}
    private boolean overTokenOrCard(){
        if(GraphicsEnvironment.isHeadless())return target!=null;
        if(target==null||!editor.isShowing())return false;
        Point location=pointer.get();if(location==null)return false;Point point=new Point(location);
        if(caretTarget&&editor.isFocusOwner())return true;
        if(overCardOrBridge())return true;
        SwingUtilities.convertPointFromScreen(point,editor);int offset=CodeTextPane.characterOffsetAt(editor,point);return offset>=target.start()&&offset<target.end();
    }
    private boolean overCardOrBridge(){
        if(target==null||window==null||!window.isVisible()||!editor.isShowing())return false;
        Point point=pointer.get();if(point==null)return false;
        {
            Rectangle cardBounds=window.getBounds();cardBounds.grow(6,6);if(cardBounds.contains(point))return true;
            try{
                Rectangle token=editor.modelToView2D(target.start()).getBounds();Point origin=token.getLocation();SwingUtilities.convertPointToScreen(origin,editor);
                int top=cardBounds.y>=origin.y?origin.y+token.height:cardBounds.y+cardBounds.height;
                int bottom=cardBounds.y>=origin.y?cardBounds.y:origin.y;
                int left=Math.min(origin.x,cardBounds.x),right=Math.max(origin.x+token.width,cardBounds.x+cardBounds.width);
                Rectangle bridge=new Rectangle(left,top,right-left,Math.max(0,bottom-top));
                if(bridge.contains(point))return true;
            }catch(Exception ignored){}
        }
        return false;
    }
    void hide(){caretTarget=false;delay.stop();exitGrace.stop();watch.stop();target=null;editor.setToolTipText(null);if(window!=null){window.dispose();window=null;card=null;}}
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
}
