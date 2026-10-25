package VulcanIDE;

import C8080.SourceDiagnostic;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Runs the compiler repeatedly over an editor snapshot to collect recoverable C diagnostics. */
final class CDiagnosticAnalyzer {
    /** A diagnostic message for the source range [start, end). */
    record Problem(int start,int end,String message) {}
    static List<Problem> analyze(String source,Path path,List<Path> includes,BooleanSupplier cancelled) {
        try { return analyzeCancellable(source,path,includes,cancelled); }
        catch(java.util.concurrent.CancellationException ignored) { return List.of(); }
    }
    private static List<Problem> analyzeCancellable(String source,Path path,List<Path> includes,BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        List<Problem> problems=new ArrayList<>();String working=source;
        ArrayList<Integer> origins=new ArrayList<>();for(int i=0;i<=source.length();i++){if((i&255)==0)checkCancelled(cancelled);origins.add(i);}
        Set<String> seen=new HashSet<>();
        for(int attempt=0;attempt<64;attempt++) {
            checkCancelled(cancelled);
            try {new C8080.Compiler(includes).compile(working,path,0,cancelled);break;}
            catch(SourceDiagnostic error) {
                checkCancelled(cancelled);
                String message=error.explanation();
                boolean external=error.file()!=null&&!error.file().toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize());
                int start=offset(working,error.line(),error.column()),end=Math.min(working.length(),start+Math.max(1,error.length()));
                if(external){
                    Matcher include=Pattern.compile("(?m)^\\s*#\\s*include\\s*([<\"])([^>\"]+)[>\"]").matcher(source);
                    if(include.find())problems.add(new Problem(include.start(2),include.end(2),error.file()+":"+error.line()+":"+error.column()+"\n"+message));
                    break;
                }
                int original=origins.get(Math.min(start,origins.size()-1));
                int originalEnd=origins.get(Math.min(end,origins.size()-1));
                if(!seen.add(original+":"+message))break;
                Problem visible=visibleProblem(source,original,originalEnd,message);
                int highlightStart=visible.start(),highlightEnd=visible.end();
                String lower=message.toLowerCase(Locale.ROOT);

                if(lower.contains("expected")&&(lower.contains("semicolon")||lower.contains("';'"))){
                    int preceding=Math.min(original-1,source.length()-1);
                    while(preceding>=0&&Character.isWhitespace(source.charAt(preceding)))preceding--;
                    if(preceding>=0){highlightStart=preceding;highlightEnd=preceding+1;}
                }
                if(lower.contains("include file not found")){
                    Matcher missing=Pattern.compile("#\\s*include\\s*[<\"]([^>\"]+)[>\"]").matcher(source);
                    while(missing.find())if(original>=missing.start()&&original<=missing.end()){
                        highlightStart=missing.start(1);highlightEnd=missing.end(1);break;
                    }
                }
                problems.add(new Problem(highlightStart,highlightEnd,message));
                String replacement=null;int replaceEnd=end;
                int lineStart=working.lastIndexOf('\n',Math.max(-1,start-1))+1;
                int lineEnd=working.indexOf('\n',start);if(lineEnd<0)lineEnd=working.length();
                String line=working.substring(lineStart,lineEnd);
                if(line.stripLeading().startsWith("#include")&&(lower.contains("not found")||lower.contains("cannot")||lower.contains("include"))){start=lineStart;replaceEnd=lineEnd;replacement=" ".repeat(replaceEnd-start);}
                else if(lower.contains("expected")&&(lower.contains("semicolon")||lower.contains("';'"))){replacement=";";replaceEnd=start;}
                else if(message.startsWith("Unknown variable ")){
                    String name=message.substring("Unknown variable ".length()).split("[\\s.]")[0];
                    int at=wordAt(working,name,start);if(at>=0){start=at;replaceEnd=at+name.length();replacement="0";}
                } else if(message.startsWith("Duplicate variable ")) {
                    String name=message.substring("Duplicate variable ".length()).split("[\\s.]")[0];
                    int at=wordAt(working,name,start);
                    if(at>=0){start=at;replaceEnd=at+name.length();replacement="__vulcan_recovered_"+attempt;}
                } else if(message.startsWith("Function '")&&lower.contains("not been declared")) {
                    String name=message.substring(10,message.indexOf('\'',10));int at=wordAt(working,name,start);
                    if(at>=0){int open=at+name.length();while(open<working.length()&&Character.isWhitespace(working.charAt(open)))open++;
                        if(open<working.length()&&working.charAt(open)=='('){int depth=1,p=open+1;for(;p<working.length()&&depth>0;p++){if(working.charAt(p)=='(')depth++;else if(working.charAt(p)==')')depth--;}
                            if(depth==0){start=at;replaceEnd=p;replacement="0";}}
                    }
                }
                if(replacement==null)break;
                int origin=origins.get(start);
                for(int i=replaceEnd-1;i>=start;i--){if((i&255)==0)checkCancelled(cancelled);origins.remove(i);}
                for(int i=0;i<replacement.length();i++){if((i&255)==0)checkCancelled(cancelled);origins.add(start+i,origin);}
                working=working.substring(0,start)+replacement+working.substring(replaceEnd);
            }catch(IllegalArgumentException error){checkCancelled(cancelled);problems.add(new Problem(0,Math.min(1,source.length()),error.getMessage()));break;}
        }
        return List.copyOf(problems);
    }
    private static Problem visibleProblem(String source,int start,int end,String message){
        start=Math.max(0,Math.min(start,source.length()));
        end=Math.max(start,Math.min(end,source.length()));
        if(start==source.length()||Character.isWhitespace(source.charAt(start))){
            int preceding=start-1;
            while(preceding>=0&&Character.isWhitespace(source.charAt(preceding)))preceding--;
            if(preceding<0)return new Problem(0,0,message);
            end=preceding+1;
            start=preceding;
            if(Character.isJavaIdentifierPart(source.charAt(start)))
                while(start>0&&Character.isJavaIdentifierPart(source.charAt(start-1)))start--;
        }
        if(start<source.length()&&Character.isJavaIdentifierPart(source.charAt(start))){
            while(start>0&&Character.isJavaIdentifierPart(source.charAt(start-1)))start--;
            while(end<source.length()&&Character.isJavaIdentifierPart(source.charAt(end)))end++;
        }
        return new Problem(start,Math.max(end,Math.min(source.length(),start+1)),message);
    }
    private static void checkCancelled(BooleanSupplier cancelled){if(Thread.currentThread().isInterrupted()||(cancelled!=null&&cancelled.getAsBoolean()))throw new java.util.concurrent.CancellationException("Diagnostics were cancelled.");}
    private static int wordAt(String text,String name,int near){
        Matcher m=Pattern.compile("\\b"+Pattern.quote(name)+"\\b").matcher(text);
        int best=-1,distance=Integer.MAX_VALUE;while(m.find())if(Math.abs(m.start()-near)<distance){best=m.start();distance=Math.abs(best-near);}return best;
    }
    private static int offset(String text,int line,int column){int offset=0;for(int n=1;n<line;n++){int next=text.indexOf('\n',offset);if(next<0)return text.length();offset=next+1;}int end=text.indexOf('\n',offset);if(end<0)end=text.length();return Math.min(end,offset+Math.max(0,column-1));}
}
