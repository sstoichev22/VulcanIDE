package VulcanIDE;

import C8080.Lexer;
import C8080.Token;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import static C8080.Token.TokenType.*;

/** Decides whether a source file can run: assembly, build.run, and executables always, C when it defines int main or _start. */
public final class ProgramEligibility {
    private ProgramEligibility() {}
    /** Reports whether a file can be run: assembly and executables always, C when it has an entry point. */
    public static boolean canRun(Path path, String source) {
        if (path == null) return false;
        Path fileName = path.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".asm") || name.endsWith(".exe") || BuildRun.is(path) || name.endsWith(".c") && hasIntMain(source);
    }
    /** Reports whether C source defines int main or _start at file scope. */
    public static boolean hasIntMain(String source) {

        String input = (source == null ? "" : source).replaceAll("(?m)^\\s*#.*(?:\\\\\\r?\\n.*)*", "");
        try {

            List<Token> tokens = new java.util.ArrayList<>();
            var matcher=java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[^\\s]").matcher(CIndentation.code(input));
            while(matcher.find()) {
                String word=matcher.group();
                Token.TokenType type=switch(word){case "int"->INT;case "void"->VOID;case "static"->STATIC;case "extern"->EXTERN;case "signed"->SIGNED;case "const"->CONST;case "("->OPEN_PAREN;case ")"->CLOSE_PAREN;case "{"->OPEN_BRACE;case "}"->CLOSE_BRACE;case ";"->SEMICOLON;default->IDENTIFIER;};
                tokens.add(new Token(type,word));
            }
            int depth = 0, declaration = 0;
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                boolean start = token.data.equals("_start");
                if (depth == 0 && token.type == IDENTIFIER && (token.data.equals("main") || start)
                        && i + 1 < tokens.size() && tokens.get(i+1).type == OPEN_PAREN) {
                    boolean explicitInt = false, valid = true;
                    for (int j=declaration; j<i; j++) {
                        Token.TokenType type = tokens.get(j).type;
                        if (type == INT || start && type == VOID) explicitInt = true;
                        else if (type != STATIC && type != EXTERN && type != SIGNED && type != CONST) valid = false;
                    }
                    int parens=1, end=i+2;
                    while (end<tokens.size() && parens>0) {
                        if (tokens.get(end).type == OPEN_PAREN) parens++;
                        if (tokens.get(end).type == CLOSE_PAREN) parens--;
                        end++;
                    }
                    if (valid && explicitInt && parens == 0 && end<tokens.size() && tokens.get(end).type == OPEN_BRACE) return true;
                }
                if (token.type == OPEN_BRACE) depth++;
                if (token.type == CLOSE_BRACE) { depth--; if (depth == 0) declaration=i+1; }
                if (token.type == SEMICOLON && depth == 0) declaration=i+1;
            }
        } catch (RuntimeException ignored) {                                                }
        return false;
    }
    /** Runnable decision for a C file plus the dependencies whose changes invalidate it. */
    record Analysis(boolean runnable, java.util.Map<Path,String> dependencies, RuntimeException preprocessingFailure) {}
    static Analysis analyze(Path path,String source,List<Path> includes) {
        java.util.Map<Path,String> dependencies=new java.util.LinkedHashMap<>();
        C8080.Preprocessor preprocessor=new C8080.Preprocessor(includes);
        boolean runnable=false;
        RuntimeException preprocessingFailure=null;
        String expanded=null;
        try { expanded=preprocessor.process(source,path); }
        catch(RuntimeException failure){preprocessingFailure=failure;}
        try {
            if(expanded!=null){var unit=new C8080.Parser(new Lexer(expanded).tokenize()).parse();
            runnable=unit.functions().stream().anyMatch(fn->fn.body()!=null&&(fn.name().equals("main")&&fn.type().name.equals("int")||fn.name().equals("_start")&&(fn.type().name.equals("int")||fn.type().kind==C8080.CType.Kind.VOID)));}
        }catch(RuntimeException incomplete){

            if(expanded!=null)runnable=hasIntMain(expanded);
        }
        Path mainPath=path==null?null:path.toAbsolutePath().normalize();
        for(var line:preprocessor.activeSourceLines())if(line.path()!=null){
            if(!line.path().equals(mainPath))dependencies.put(line.path(),stamp(line.path()));
            Path directory=line.path().getParent();if(directory!=null)dependencies.put(directory,stamp(directory));
        }
        for(Path include:includes)dependencies.put(include.toAbsolutePath().normalize(),stamp(include));
        return new Analysis(runnable,java.util.Map.copyOf(dependencies),preprocessingFailure);
    }
    static String stamp(Path path){try{return java.nio.file.Files.getLastModifiedTime(path)+":"+java.nio.file.Files.size(path);}catch(Exception absent){return "missing";}}
}
