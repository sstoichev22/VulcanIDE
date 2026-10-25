package VulcanIDE;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Background diagnostics, symbol indexing, and hover cards for one C editor. */
final class EditorDiagnostics {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Vulcan-C-diagnostics");t.setDaemon(true);return t;});
    private final JTextPane editor;private final Supplier<Path> path;private final SettingsManager settings;
    private final javax.swing.Timer debounce;
    private final javax.swing.Timer dependencyTimer;
    private boolean checkingDependencies;
    private Runnable refreshCompletion=()->{};
    private final AtomicLong generation=new AtomicLong();
    private Future<?> pending;
    private Future<?> pendingCompletion;
    private Future<?> pendingHover;
    private Future<?> pendingDependency;
    private final AtomicLong dependencyRequest=new AtomicLong();
    private List<CDiagnosticAnalyzer.Problem> problems=List.of();
    private Runnable diagnosticsChanged=()->{};
    private CSymbolIndex symbols;
    private final AnchoredHover hover;
    private int hoverOffset=-1; private final AtomicLong hoverRequest=new AtomicLong(),completionRequest=new AtomicLong();
    private static final ExecutorService INDEX_WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Vulcan-C-symbols");t.setDaemon(true);return t;});
    private final List<Object> highlights=new ArrayList<>();
    EditorDiagnostics(JTextPane editor,Supplier<Path> path,SettingsManager settings){this(editor,path,settings,null);}
    EditorDiagnostics(JTextPane editor,Supplier<Path> path,SettingsManager settings,AnchoredHover suppliedHover){
        this.editor=editor;this.path=path;this.settings=settings;
        debounce=new javax.swing.Timer(550,e->launch());debounce.setRepeats(false);
        hover=suppliedHover==null?new AnchoredHover(editor):suppliedHover;
        editor.addCaretListener(event->caretHint());
        dependencyTimer=new javax.swing.Timer(1000,e->checkDependencies());dependencyTimer.start();
        editor.addMouseMotionListener(new MouseMotionAdapter(){public void mouseMoved(MouseEvent event){
            int offset=CodeTextPane.characterOffsetAt(editor,event.getPoint());
            try {
                if(offset<0||offset>=editor.getDocument().getLength()) { leaveToken();return; }
                String source=editor.getDocument().getText(0,editor.getDocument().getLength());Path file=path.get();if(Character.isWhitespace(CIndentation.code(source).charAt(offset))&&!source.substring(source.lastIndexOf('\n',offset)+1,offset+1).stripLeading().startsWith("#")){leaveToken();return;}
                CSymbolIndex currentSymbols=symbols;List<CDiagnosticAnalyzer.Problem> currentProblems=problems;var content=hoverContentAt(source,offset,file,currentSymbols,List.of(),currentProblems);
                hover.target(content);
                int tokenStart=offset;while(tokenStart>0&&Character.isJavaIdentifierPart(source.charAt(tokenStart-1)))tokenStart--;
                final int token=tokenStart;
                if(file!=null&&file.toString().toLowerCase(Locale.ROOT).matches(".*\\.(c|h)$")&&token!=hoverOffset){
                    hoverOffset=token;long serial=generation.get(),request=hoverRequest.incrementAndGet();CSymbolIndex complete=currentSymbols;List<CDiagnosticAnalyzer.Problem> currentDiagnostics=currentProblems;
                    if(pendingHover!=null)pendingHover.cancel(true);
                    pendingHover=INDEX_WORKER.submit(()->{BooleanSupplier cancelled=()->Thread.currentThread().isInterrupted()||generation.get()!=serial||hoverRequest.get()!=request;if(cancelled.getAsBoolean())return;List<Path> includes=includePaths(file);var index=CSymbolIndex.buildAt(source,file,includes,offset+1,cancelled);if(cancelled.getAsBoolean())return;index.retainCurrentDeclaration(complete,file,offset,cancelled);if(cancelled.getAsBoolean())return;var resolved=hoverContentAt(source,offset,file,index,includes,currentDiagnostics);if(cancelled.getAsBoolean())return;SwingUtilities.invokeLater(()->{if(generation.get()==serial&&request==hoverRequest.get()&&file.equals(path.get())&&hoverOffset==token){if(resolved==null)hover.refresh(null);else if(hover.anchorOffset()==resolved.start())hover.refresh(resolved);else hover.target(resolved);}});});
                }
            } catch(BadLocationException ignored) {hover.target(null);}
        }});
    }
    private void caretHint(){
        Path file=path.get();if(file==null||!file.toString().toLowerCase(Locale.ROOT).matches(".*\\.(c|h)$")){hoverRequest.incrementAndGet();if(pendingHover!=null)pendingHover.cancel(true);hover.targetAtCaret(null);return;}
        String source=editor.getText();int caret=editor.getCaretPosition();CSymbolIndex currentSymbols=symbols;long serial=generation.get(),request=hoverRequest.incrementAndGet();
        if(pendingHover!=null)pendingHover.cancel(true);
        HoverContent.Info candidate=HoverContent.atCaret(source,caret,file,currentSymbols,List.of(),false);
        hover.targetAtCaret(candidate);
        if(candidate==null||currentSymbols==null)return;
        pendingHover=INDEX_WORKER.submit(()->{
            BooleanSupplier cancelled=()->Thread.currentThread().isInterrupted()||generation.get()!=serial||hoverRequest.get()!=request;
            if(cancelled.getAsBoolean())return;
            List<Path> includes=includePaths(file);
            HoverContent.Info resolved=HoverContent.at(source,candidate.start(),file,currentSymbols,includes);
            if(cancelled.getAsBoolean())return;
            SwingUtilities.invokeLater(()->{
                if(generation.get()==serial&&request==hoverRequest.get()&&file.equals(path.get())&&editor.getCaretPosition()==caret&&hover.anchorOffset()==candidate.start())hover.refresh(resolved);
            });
        });
    }
    static HoverContent.Info hoverContentAt(String source,int offset,Path file,CSymbolIndex symbols,List<Path> includes,List<CDiagnosticAnalyzer.Problem> problems){
        HoverContent.Info content=HoverContent.at(source,offset,file,symbols,includes,false);
        for(CDiagnosticAnalyzer.Problem problem:problems)if(offset>=problem.start()&&offset<problem.end())return new HoverContent.Info(problem.start(),problem.end(),"Diagnostic",problem.message(),null,0,null);
        return content;
    }
    void setCompletionRefresh(Runnable callback){refreshCompletion=callback;}
    private void checkDependencies(){
        if(checkingDependencies||symbols==null||!editor.isShowing())return;
        CSymbolIndex previous=symbols;
        Path file=path.get();
        String source=editor.getText();
        long serial=generation.get(),request=hoverRequest.get();
        long dependencySerial=dependencyRequest.incrementAndGet();
        int offset=hover.anchorOffset();
        BooleanSupplier cancelled=()->Thread.currentThread().isInterrupted()||generation.get()!=serial||dependencyRequest.get()!=dependencySerial;
        checkingDependencies=true;
        pendingDependency=INDEX_WORKER.submit(()->{
            List<Path> includes=includePaths(file);
            boolean changed=false;
            CSymbolIndex fresh=null;
            HoverContent.Info information=null;
            try{
                changed=previous.dependenciesChanged(cancelled);
                if(changed)fresh=CSymbolIndex.build(source,file,includes,cancelled);
                if(changed&&offset>=0){
                    CSymbolIndex positional=CSymbolIndex.buildAt(source,file,includes,offset+1,cancelled);
                    positional.retainCurrentDeclaration(fresh,file,offset,cancelled);
                    if(cancelled.getAsBoolean())return;
                    information=HoverContent.at(source,offset,file,positional,includes);
                }
                if(cancelled.getAsBoolean())return;
            }finally{
                SwingUtilities.invokeLater(()->{
                    if(dependencyRequest.get()==dependencySerial){
                        checkingDependencies=false;
                        pendingDependency=null;
                    }
                });
            }
            boolean hasChanged=changed;
            CSymbolIndex refreshed=fresh;
            HoverContent.Info result=information;
            SwingUtilities.invokeLater(()->{
                if(dependencyRequest.get()!=dependencySerial||serial!=generation.get()||!Objects.equals(file,path.get())||!hasChanged)return;
                symbols=refreshed;
                if(request==hoverRequest.get()&&offset==hover.anchorOffset()&&offset>=0)hover.refresh(result);
                refreshCompletion.run();
                debounce.restart();
            });
        });
    }    private void leaveToken(){hoverRequest.incrementAndGet();hoverOffset=-1;if(pendingHover!=null)pendingHover.cancel(true);hover.target(null);}
    void setNavigator(java.util.function.BiConsumer<Path,Integer> navigator){hover.setNavigator(navigator);}
    void setDiagnosticsChangedListener(Runnable listener){diagnosticsChanged=listener==null?()->{}:listener;}
    boolean hasErrors(){return !problems.isEmpty();}
    /** Returns the immutable diagnostics snapshot used by squiggles; read on the event-dispatch thread. */
    List<CDiagnosticAnalyzer.Problem> snapshot(){return problems;}
    private List<Path> includePaths(Path file){if(SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Include path discovery must run off the event-dispatch thread.");List<Path> includes=new ArrayList<>();if(file!=null&&file.getParent()!=null)includes.add(file.getParent());includes.add(settings.getCurrentDirectory().resolve("src/stdlib/include"));includes.add(Path.of("src/stdlib/include"));Path bundled=StandardHeaders.directory();if(bundled!=null)includes.add(bundled);return includes;}
    void completions(String source,int caret,java.util.function.Consumer<List<CCompletion.Entry>> publish){
        Path file=path.get();if(file==null)return;long serial=generation.get(),request=completionRequest.incrementAndGet();
        if(pendingCompletion!=null)pendingCompletion.cancel(true);
        pendingCompletion=INDEX_WORKER.submit(()->{BooleanSupplier cancelled=()->Thread.currentThread().isInterrupted()||generation.get()!=serial||completionRequest.get()!=request;if(cancelled.getAsBoolean())return;List<Path> includes=includePaths(file);var index=CSymbolIndex.buildAt(source,file,includes,caret,cancelled);if(cancelled.getAsBoolean())return;Path bundled=StandardHeaders.directory();List<CCompletion.Entry> library=bundled==null?List.of():CCompletion.standardLibrary(bundled);var entries=CCompletion.suggestions(source,caret,index,library);SwingUtilities.invokeLater(()->{if(serial==generation.get()&&request==completionRequest.get()&&file.equals(path.get())&&editor.getCaretPosition()==caret)publish.accept(entries);});});
    }
    void changed(){
        generation.incrementAndGet();if(pending!=null)pending.cancel(true);clear();debounce.restart();dependencyTimer.start();
    }
    void stop(){generation.incrementAndGet();debounce.stop();dependencyTimer.stop();if(pending!=null)pending.cancel(true);if(pendingCompletion!=null)pendingCompletion.cancel(true);clear();}
    private void clearHighlights(){for(Object tag:highlights)editor.getHighlighter().removeHighlight(tag);highlights.clear();}
    private void clear(){cancelDependencyCheck();clearHighlights();problems=List.of();symbols=null;hoverOffset=-1;hoverRequest.incrementAndGet();completionRequest.incrementAndGet();if(pendingHover!=null)pendingHover.cancel(true);if(pendingCompletion!=null)pendingCompletion.cancel(true);hover.hide();diagnosticsChanged.run();}
    private void cancelDependencyCheck(){dependencyRequest.incrementAndGet();if(pendingDependency!=null)pendingDependency.cancel(true);pendingDependency=null;checkingDependencies=false;}
    private String tooltip(String title,String message){return "<html><div style='font-family:"+escape(editor.getFont().getFamily())+";font-size:"+editor.getFont().getSize()+"pt;width:520px'><b>"+escape(title)+"</b><hr>"+escape(message).replace("\n","<br>")+"</div></html>";}
    private static String wrapPath(String path){StringBuilder out=new StringBuilder();int column=0;for(char c:path.toCharArray()){out.append(c);if(++column>=42){out.append('\n');column=0;}}return out.toString();}
    static String keywordDescription(String word){return switch(word){
        case "if","else"->"Conditional statement: select a branch using an expression.";
        case "for","while","do"->"Loop statement: repeat a body while its condition permits.";
        case "return"->"Return from this function; supply a value for a non-void function.";
        case "typedef"->"Declare an alias for a type; the alias becomes visible after its declaration.";
        case "define"->"Preprocessor directive: define an object or function macro.";
        case "include"->"Preprocessor directive: include a header and its active declarations.";
        case "undef"->"Preprocessor directive: remove a macro definition.";
        case "ifdef","ifndef","elif","endif"->"Conditional preprocessing directive: select active source using macro definitions and constant expressions.";
        case "pragma","line","error"->"Preprocessor directive: "+word;
        case "union"->"Aggregate type whose members share the same storage.";
        case "enum"->"Enumeration type with named integer constants.";
        case "static","extern","auto","register","inline"->"C declaration storage or function specifier: "+word;
        case "volatile","restrict"->"C type qualifier: "+word;
        case "switch","case","default"->"Select a statement branch using an integer expression.";
        case "goto"->"Transfer execution to a label in this function.";
        case "signed"->"Signed integer type, with positive and negative values.";
        case "struct"->"Aggregate type containing named members in declaration order.";
        case "const"->"Type qualifier: prevent modification through this declaration.";
        case "unsigned"->"Unsigned integer type: values have no sign bit.";
        case "int","char","short","long","void","float","double","bool"->"C primitive type: "+word;
        case "sizeof"->"Size of a type or expression in bytes.";
        case "break","continue"->word.equals("break")?"Exit the current loop or switch.":"Begin the next loop iteration.";
        default->word.matches("u?int[0-9]+")?"Vulcan "+word+" integer type; "+word.replaceAll("\\D","")+" bits.":null;
    };}
    private void launch(){
        Path file=path.get();if(file==null||!file.toString().toLowerCase(Locale.ROOT).matches(".*\\.(c|h)$"))return;
        final String source;try{source=editor.getDocument().getText(0,editor.getDocument().getLength());}catch(BadLocationException e){return;}
        long serial=generation.get();
        pending=WORKER.submit(()->{
            BooleanSupplier cancelled=()->Thread.currentThread().isInterrupted()||generation.get()!=serial;if(cancelled.getAsBoolean())return;List<Path> includes=includePaths(file);
            var results=CDiagnosticAnalyzer.analyze(source,file,includes,cancelled);
            if(cancelled.getAsBoolean())return;
            var index=CSymbolIndex.build(source,file,includes,cancelled);
            SwingUtilities.invokeLater(()->{publish(serial,file,results);if(serial==generation.get()&&file.equals(path.get())){symbols=index;caretHint();}});
        });
    }
    private void publish(long serial,Path file,List<CDiagnosticAnalyzer.Problem> results){
        if(serial!=generation.get()||!file.equals(path.get()))return;
        clearHighlights();problems=List.copyOf(results);
        int paintedEnd=-1;
        for(var p:problems.stream().sorted(Comparator.comparingInt(CDiagnosticAnalyzer.Problem::start)).toList())try{
            int start=Math.max(paintedEnd,p.start());
            if(p.end()>start&&p.end()<=editor.getDocument().getLength()){highlights.add(editor.getHighlighter().addHighlight(start,p.end(),new Wave()));paintedEnd=p.end();}
        }catch(BadLocationException ignored){ }
        diagnosticsChanged.run();
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static final class Wave extends LayeredHighlighter.LayerPainter {
        /** Unused: squiggles paint per layer. */
        public void paint(Graphics g,int start,int end,Shape bounds,JTextComponent c){}
        /** Paints a red squiggle under the highlighted range, scaled to the font. */
        public Shape paintLayer(Graphics g,int start,int end,Shape bounds,JTextComponent c,View view){
            try {
                Shape shape=view.modelToView(start,Position.Bias.Forward,end,Position.Bias.Backward,bounds);
                Rectangle r=shape.getBounds();Graphics2D wave=(Graphics2D)g.create();wave.setColor(new Color(240,85,85));
                float scale=Math.max(1f,c.getFont().getSize2D()/14f);
                int amplitude=Math.max(2,Math.round(2*scale)),step=amplitude*2,y=r.y+r.height-amplitude-1;
                wave.clipRect(r.x,r.y,r.width,r.height);
                wave.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                wave.setStroke(new BasicStroke(Math.max(1f,scale*0.9f),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                for(int x=r.x;x<r.x+r.width;x+=step){wave.drawLine(x,y,x+amplitude,y+amplitude);wave.drawLine(x+amplitude,y+amplitude,x+step,y);}
                wave.dispose();
                return shape;
            }catch(BadLocationException e){return null;}
        }
    }
}
