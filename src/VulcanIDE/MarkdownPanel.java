package VulcanIDE;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Side-by-side Markdown source and live rendered preview. */
final class MarkdownPanel extends JSplitPane {
    private final JEditorPane preview=new JEditorPane("text/html", "");
    private final JTextPane source;
    private final SettingsManager settings;
    private final Supplier<Path> file;
    private Consumer<Path> openFile=path->{};
    private final Timer refresh;
    private boolean initialized;
    MarkdownPanel(JScrollPane sourceScroll,JTextPane source,SettingsManager settings,Supplier<Path> file) {
        super(JSplitPane.HORIZONTAL_SPLIT);
        this.source=source;this.settings=settings;this.file=file;
        preview.setEditable(false);preview.setOpaque(false);
        preview.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,Boolean.TRUE);
        preview.setTransferHandler(null);
        JScrollPane rendered=new JScrollPane();
        JViewport paper=new JViewport(){@Override protected void paintComponent(Graphics g){
            g.setColor(new Color(42,46,54));g.fillRect(0,0,getWidth(),getHeight());
            g.setColor(new Color(47,51,59));for(int y=0;y<getHeight();y+=4)for(int x=(y%8);x<getWidth();x+=8)g.fillRect(x,y,1,1);
        }};
        rendered.setViewport(paper);paper.setView(preview);
        rendered.getViewport().setBackground(new Color(42,46,54));
        rendered.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));
        Theme.styleScrollBar(rendered.getVerticalScrollBar(),settings);
        Theme.styleScrollBar(rendered.getHorizontalScrollBar(),settings);
        SmoothScroll.install(rendered);
        setLeftComponent(sourceScroll);setRightComponent(rendered);setResizeWeight(0.5);
        setContinuousLayout(true);setBorder(null);setTransferHandler(null);
        setDividerSize(3);
        setUI(new javax.swing.plaf.basic.BasicSplitPaneUI(){
            @Override public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider(){
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this){
                    @Override public void paint(Graphics g){g.setColor(settings.getColor("panelBackground"));g.fillRect(0,0,getWidth(),getHeight());}
                };
            }
        });
        putClientProperty("vulcan.markdownPair",Boolean.TRUE);
        addComponentListener(new java.awt.event.ComponentAdapter(){@Override public void componentResized(java.awt.event.ComponentEvent event){
            if(!initialized&&getWidth()>100){setDividerLocation(0.5);initialized=true;}
        }});
        refresh=new Timer(140,event->render());refresh.setRepeats(false);
        source.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){schedule();}public void removeUpdate(DocumentEvent e){schedule();}public void changedUpdate(DocumentEvent e){}});
        source.addPropertyChangeListener("font",event->schedule());
        preview.addHyperlinkListener(event->{
            if(event.getEventType()!=HyperlinkEvent.EventType.ACTIVATED)return;
            String href=event.getDescription();if(!MarkdownRenderer.safeLink(href))return;
            try {
                if(href.startsWith("#"))preview.scrollToReference(href.substring(1));
                else if(href.matches("(?i)https?://.*")){if(Desktop.isDesktopSupported())Desktop.getDesktop().browse(java.net.URI.create(href));}
                else {Path target=MarkdownRenderer.localPath(file.get(),href);if(target!=null)openFile.accept(target);}
            } catch(Exception error){JOptionPane.showMessageDialog(this,VulcanDialog.errorSummary(error),"Cannot open link",JOptionPane.ERROR_MESSAGE);}
        });
    }
    void setOpenFile(Consumer<Path> callback){openFile=callback==null?path->{}:callback;}
    void activate(JScrollPane sourceScroll){setLeftComponent(null);setLeftComponent(sourceScroll);sourceScroll.setVisible(true);initialized=false;if(getWidth()>100){setDividerLocation(0.5);initialized=true;}render();}
    void schedule(){if(file.get()!=null&&file.get().toString().toLowerCase().endsWith(".md"))refresh.restart();}
    void render(){int caret=preview.getCaretPosition();preview.setFont(source.getFont());preview.setText(MarkdownRenderer.render(source.getText(),file.get(),source.getFont().getSize(),settings.getApplicationFont()));preview.setCaretPosition(Math.min(caret,preview.getDocument().getLength()));}
    JEditorPane preview(){return preview;}
    @Override public void removeNotify(){refresh.stop();super.removeNotify();}
    @Override public void addNotify(){super.addNotify();schedule();}
}
