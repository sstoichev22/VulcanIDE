package VulcanIDE;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Index of C declarations visible from an editor position, including included headers. */
final class CSymbolIndex {
    /** A declared name with its file, declaration text, offset, and lexical scope. */
    record Symbol(String name, Path file, String declaration, int offset, int scopeStart, int scopeEnd, boolean external) {}
    private final List<Symbol> symbols=new ArrayList<>();
    private final Map<Path,String> sources=new HashMap<>();
    private final Map<Path,String> dependencies = new HashMap<>();
    private Set<String> macros=Set.of();
    private C8080.Preprocessor preprocessor;
    static CSymbolIndex build(String source,Path file,List<Path> includes) {
        return build(source,file,includes,()->false);
    }
    static CSymbolIndex build(String source,Path file,List<Path> includes,BooleanSupplier cancelled) {
        return buildAt(source,file,includes,source.length(),cancelled);
    }
    static CSymbolIndex buildAt(String source,Path file,List<Path> includes,int caret) {
        return buildAt(source,file,includes,caret,()->false);
    }
    static CSymbolIndex buildAt(String source,Path file,List<Path> includes,int caret,BooleanSupplier cancellation) {
        BooleanSupplier cancelled=cancellation==null?()->false:cancellation;
        checkCancelled(cancelled);
        CSymbolIndex index=new CSymbolIndex();if(file==null)return index;
        Path current=file.toAbsolutePath().normalize();
        C8080.Preprocessor preprocessor=new C8080.Preprocessor(includes);
        index.preprocessor=preprocessor;
        try {preprocessor.process(source.substring(0,declarationLookahead(source,caret,cancelled)),current,cancelled);}catch(IllegalArgumentException incomplete) {

        }
        index.macros=preprocessor.definedMacroNames();
        Map<Path,Set<Integer>> active=new LinkedHashMap<>();
        for(var line:preprocessor.activeSourceLines()){checkCancelled(cancelled);if(line.path()!=null)
            active.computeIfAbsent(line.path(),ignored->new HashSet<>()).add(line.line());
        }
        for (var entry : active.entrySet()) {
            checkCancelled(cancelled);
            Path dependency = entry.getKey();
            if (dependency.equals(current)) {
                continue;
            }
            try {
                String contents = Files.readString(dependency);
                checkCancelled(cancelled);
                index.dependencies.put(dependency, contents);
                index.read(activeText(contents, entry.getValue(),cancelled), dependency, true,cancelled);
            } catch (java.io.IOException unavailable) {
            }
        }        checkCancelled(cancelled);index.read(activeText(source,active.getOrDefault(current,Set.of()),cancelled),file,false,cancelled);
        checkCancelled(cancelled);
        return index;
    }
    private static void checkCancelled(BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new CancellationException("Symbol indexing was cancelled.");}
    private static int declarationLookahead(String source,int caret,BooleanSupplier cancelled){
        checkCancelled(cancelled);
        int cutoff=Math.min(caret,source.length());if(cutoff==source.length())return cutoff;
        int lineStart=source.lastIndexOf('\n',Math.max(0,cutoff-1))+1,lineEnd=source.indexOf('\n',cutoff);if(lineEnd<0)lineEnd=source.length();
        String line=source.substring(lineStart,lineEnd).strip();
        if(line.startsWith("#define")){int end=lineEnd;while(end>lineStart&&source.charAt(end-1)=='\\'&&end<source.length()){checkCancelled(cancelled);int next=source.indexOf('\n',end+1);end=next<0?source.length():next;}return end;}
        String clean=CIndentation.code(source,cancelled);
        Matcher aggregate=Pattern.compile("\\s*(?:typedef\\s+)?(?:struct|union|enum)\\s*(?:[A-Za-z_]\\w*)?\\s*\\{").matcher(clean.substring(lineStart));if(aggregate.lookingAt()){int brace=lineStart+aggregate.end()-1;int close=Math.min(source.length(),matching(clean,brace,cancelled)+1);int semi=clean.indexOf(';',close);return semi>=0&&clean.substring(close,semi).matches("[\\w\\s,*\\[\\]]*")?semi+1:close;}

        java.util.regex.Matcher declaration=java.util.regex.Pattern.compile("\\s*(?:(?:typedef|static|extern|const|volatile|inline|unsigned|signed|long|short)\\s+)*(?:void|int|char|float|double|u?int[0-9]+(?:_t)?|(?:struct|union|enum)\\s+\\w+|[A-Za-z_]\\w*)\\s+[*\\s]*[A-Za-z_]\\w*\\s*(?:\\(|=|;|,)").matcher(clean.substring(lineStart));
        if(declaration.lookingAt()){int end=declarationEnd(clean,lineStart,cancelled);if(end>=cutoff)return Math.min(source.length(),end+1);}
        return cutoff;
    }
    private static String activeText(String source,Set<Integer> active,BooleanSupplier cancelled) {
        StringBuilder masked=new StringBuilder(source);int line=1;
        for(int i=0;i<source.length();i++) {
            if((i&255)==0)checkCancelled(cancelled);
            char c=source.charAt(i);
            if(c=='\r') {
                line++;
                if(i+1<source.length()&&source.charAt(i+1)=='\n')i++;
            } else if(c=='\n') line++;
            else if(!active.contains(line))masked.setCharAt(i,' ');
        }
        return masked.toString();
    }
    private void read(String source,Path file,boolean external,BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        sources.put(file, source);
        String clean=CIndentation.code(source,cancelled);
        Matcher macro=Pattern.compile("(?m)^[ \\t]*#\\s*define\\s+([A-Za-z_]\\w*)[^\n]*").matcher(clean);
        while(macro.find()){checkCancelled(cancelled);int end=macro.end();while(end>macro.start()&&source.charAt(end-1)=='\\'&&end<source.length()){checkCancelled(cancelled);int next=source.indexOf('\n',end+1);end=next<0?source.length():next;}symbols.add(new Symbol(macro.group(1),file,source.substring(macro.start(),end).strip(),macro.start(),0,source.length(),external));}
        Set<String> types=new HashSet<>(List.of("int","char","void","short","long","float","double","signed","unsigned","bool"));
        for(Symbol symbol:symbols){checkCancelled(cancelled);if(symbol.declaration.startsWith("typedef"))types.add(symbol.name);}
        Matcher aliases=Pattern.compile("\\btypedef\\b").matcher(clean);
        while(aliases.find()){checkCancelled(cancelled);int end=declarationEnd(clean,aliases.start(),cancelled);if(end<0)continue;String decl=source.substring(aliases.start(),end);String tail=clean.substring(aliases.start(),end);int brace=tail.lastIndexOf('}');if(brace>=0)tail=tail.substring(brace+1);
            Matcher name=Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\[[^]]*\\]\\s*)?;\\s*$").matcher(tail);
            int[] scope=scope(clean,aliases.start(),cancelled);
            if(name.find()){types.add(name.group(1));symbols.add(new Symbol(name.group(1),file,decl,aliases.start(),scope[0],scope[1],external));}
            Matcher pointer=Pattern.compile("\\(\\s*\\*\\s*([A-Za-z_]\\w*)\\s*\\)").matcher(decl);if(pointer.find()){types.add(pointer.group(1));symbols.add(new Symbol(pointer.group(1),file,decl,aliases.start(),scope[0],scope[1],external));}
        }
        String type="(?:"+String.join("|",types)+"|u?int[0-9]+|(?:struct|union|enum)\\s+[A-Za-z_]\\w*)";
        Matcher pointers=Pattern.compile("\\b(?:(?:static|extern|const|volatile|register|signed|unsigned|long|short)\\s+)*"+type+"\\s*\\(\\s*\\*\\s*(?:(?:const|volatile)\\s+)*([A-Za-z_]\\w*)\\s*\\)\\s*\\(").matcher(clean);
        while(pointers.find()) {checkCancelled(cancelled);
            if(insideTypedef(file,pointers.start(),cancelled))continue;
            checkCancelled(cancelled);int end=declarationEnd(clean,pointers.start(),cancelled);if(end<0)continue;
            int[] scope=scope(clean,pointers.start(),cancelled);
            symbols.add(new Symbol(pointers.group(1),file,source.substring(pointers.start(),end).strip(),pointers.start(),scope[0],scope[1],external));
        }
        Matcher declarations=Pattern.compile("\\b(?:(?:static|extern|const|volatile|register|inline|signed|unsigned|long|short)\\s+)*"+type+"(?:\\s+|(?=\\*))(?:\\*\\s*(?:const\\s+|volatile\\s+)*)*([A-Za-z_]\\w*)").matcher(clean);
        while(declarations.find()) {checkCancelled(cancelled);
            int parentheses=0;
            for(int p=0;p<declarations.start();p++){if((p&255)==0)checkCancelled(cancelled);char ch=clean.charAt(p);if(ch=='(')parentheses++;else if(ch==')')parentheses--;}
            int[] loopScope=forScope(clean,declarations.start(),cancelled);
            if(parentheses>0&&loopScope==null)continue;
            if(insideTypedef(file,declarations.start(),cancelled))continue;
            int end=declarationEnd(clean,declarations.start(),cancelled);if(end<0)continue;
            String name=declarations.group(1),decl=source.substring(declarations.start(),end).strip();
            int[] scope=loopScope==null?scope(clean,declarations.start(),cancelled):loopScope;int scopeStart=scope[0],scopeEnd=scope[1];
            symbols.add(new Symbol(name,file,decl,declarations.start(),scopeStart,scopeEnd,external));

            if(!decl.contains("(")&&!decl.contains("{")){String tail=clean.substring(declarations.end(),end);Matcher more=Pattern.compile(",\\s*(\\*\\s*)?([A-Za-z_]\\w*)").matcher(tail);while(more.find()){checkCancelled(cancelled);symbols.add(new Symbol(more.group(2),file,decl,declarations.start(),scopeStart,scopeEnd,external));}}
        }
        Matcher tags=Pattern.compile("\\b(?:struct|union|enum)\\s+([A-Za-z_]\\w*)\\s*\\{").matcher(clean);
        while(tags.find()){checkCancelled(cancelled);int end=matching(clean,tags.end()-1,cancelled);int[] scope=scope(clean,tags.start(),cancelled);symbols.add(new Symbol(tags.group(1),file,source.substring(tags.start(),Math.min(source.length(),end+1)),tags.start(),scope[0],scope[1],external));}
        Matcher parameters=Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(([^()]*)\\)\\s*\\{").matcher(clean);
        while(parameters.find()) {checkCancelled(cancelled);if(Set.of("for","if","while","switch").contains(parameters.group(1)))continue;int body=parameters.end()-1,end=matching(clean,body,cancelled);Matcher param=Pattern.compile("(?:^|,)\\s*((?:(?:const|volatile|unsigned|signed)\\s+)*"+type+"(?:\\s+|(?=\\*))(?:\\*\\s*)*([A-Za-z_]\\w*)(?:\\s*\\[[^]]*\\])?)").matcher(parameters.group(2));
            while(param.find()) {checkCancelled(cancelled);int offset=parameters.start(2)+param.start(1);symbols.add(new Symbol(param.group(2),file,source.substring(offset,parameters.start(2)+param.end(1)),offset,body,end,external));}}
    }
    private boolean insideTypedef(Path file,int offset,BooleanSupplier cancelled){for(Symbol alias:symbols){checkCancelled(cancelled);if(alias.file.equals(file)&&alias.declaration.startsWith("typedef")&&offset>=alias.offset&&offset<alias.offset+alias.declaration.length())return true;}return false;}
    private static int[] forScope(String clean,int offset,BooleanSupplier cancelled) {
        Matcher loops=Pattern.compile("\\bfor\\s*\\(").matcher(clean);
        while(loops.find()){checkCancelled(cancelled);int open=loops.end()-1,close=matchingParen(clean,open,cancelled);if(offset>open&&offset<close)return new int[]{loops.start(),statementEnd(clean,close+1,cancelled)};}
        return null;
    }
    private static int matchingParen(String clean,int open){return matchingParen(clean,open,()->false);}
    private static int matchingParen(String clean,int open,BooleanSupplier cancelled){int depth=0;for(int i=open;i<clean.length();i++){if((i&255)==0)checkCancelled(cancelled);if(clean.charAt(i)=='(')depth++;else if(clean.charAt(i)==')'&&--depth==0)return i;}return clean.length();}
    private static int statementEnd(String clean,int start,BooleanSupplier cancelled) {
        while(start<clean.length()&&Character.isWhitespace(clean.charAt(start))){checkCancelled(cancelled);start++;}
        if(start>=clean.length())return clean.length();
        if(clean.charAt(start)=='{')return Math.min(clean.length(),matching(clean,start,cancelled)+1);
        Matcher control=Pattern.compile("(?:if|for|while|switch)\\b\\s*\\(").matcher(clean);control.region(start,clean.length());
        if(control.lookingAt()) {
            int end=statementEnd(clean,matchingParen(clean,control.end()-1,cancelled)+1,cancelled),next=end;
            while(next<clean.length()&&Character.isWhitespace(clean.charAt(next))){checkCancelled(cancelled);next++;}
            if(clean.startsWith("if",start)&&clean.startsWith("else",next)&&(next+4==clean.length()||!Character.isJavaIdentifierPart(clean.charAt(next+4))))return statementEnd(clean,next+4,cancelled);
            return end;
        }
        int paren=0;for(int i=start;i<clean.length();i++){if((i&255)==0)checkCancelled(cancelled);char c=clean.charAt(i);if(c=='(')paren++;else if(c==')')paren--;else if(c==';'&&paren==0)return i+1;else if(c=='{'&&paren==0)return Math.min(clean.length(),matching(clean,i,cancelled)+1);}
        return clean.length();
    }
    private static int[] scope(String clean,int offset,BooleanSupplier cancelled){int depth=0;for(int p=offset-1;p>=0;p--){if((p&255)==0)checkCancelled(cancelled);char ch=clean.charAt(p);if(ch=='}')depth++;else if(ch=='{'&&depth--==0)return new int[]{p,matching(clean,p,cancelled)};}return new int[]{0,clean.length()};}
    private static int matching(String s,int open){return matching(s,open,()->false);}
    private static int matching(String s,int open,BooleanSupplier cancelled){int depth=0;for(int i=open;i<s.length();i++){if((i&255)==0)checkCancelled(cancelled);if(s.charAt(i)=='{')depth++;if(s.charAt(i)=='}'&&--depth==0)return i;}return s.length();}
    static int declarationEnd(String s,int start){return declarationEnd(s,start,()->false);}
    private static int declarationEnd(String s,int start,BooleanSupplier cancelled){int paren=0,brace=0;boolean typedef=s.startsWith("typedef",start);for(int i=start;i<s.length();i++){if((i&255)==0)checkCancelled(cancelled);char c=s.charAt(i);if(c=='(')paren++;if(c==')')paren--;if(c=='{'){if(paren==0&&!typedef)return i;brace++;}if(c=='}'){if(brace==0)return -1;brace--;}if(c==';'&&paren==0&&brace==0)return i+1;}return -1;}
    Symbol find(String name,int offset){Symbol best=null;for(Symbol s:symbols)if(s.name.equals(name)&&(!s.declaration.startsWith("#")||macros.contains(s.name))&&(s.external?s.scopeStart==0:(s.offset<=offset&&((offset>=s.scopeStart&&offset<=s.scopeEnd)||offset<s.offset+s.declaration.length())))){if(best==null||(!s.external&&(best.external||s.scopeStart>best.scopeStart||s.offset>best.offset)))best=s;}return best;}
    boolean functionDeclaration(Symbol symbol){if(symbol.declaration.startsWith("#"))return false;int name=symbol.declaration.indexOf(symbol.name);if(name<0)return false;int next=name+symbol.name.length();while(next<symbol.declaration.length()&&Character.isWhitespace(symbol.declaration.charAt(next)))next++;return next<symbol.declaration.length()&&symbol.declaration.charAt(next)=='(';}
    boolean functionDefinition(Symbol symbol){if(!functionDeclaration(symbol))return false;String source=sources.get(symbol.file);if(source==null)return false;String clean=CIndentation.code(source);Matcher name=Pattern.compile("\\b"+Pattern.quote(symbol.name)+"\\s*\\(").matcher(clean);name.region(Math.min(symbol.offset,clean.length()),clean.length());if(!name.find())return false;int open=clean.indexOf('(',name.start()),close=matchingParen(clean,open),next=close+1;while(next<clean.length()&&Character.isWhitespace(clean.charAt(next)))next++;return next<clean.length()&&clean.charAt(next)=='{';}
    /** Finds the unique counterpart in the same source file or through a unique header-to-implementation include relationship. */
    Symbol functionCounterpart(Symbol source,boolean searchFiles){
        if(!functionDeclaration(source))return null;
        boolean definition=functionDefinition(source);
        Symbol local=symbols.stream().filter(candidate->candidate.name.equals(source.name)&&sameFile(candidate,source)&&!sameLocation(candidate,source)
                &&functionDeclaration(candidate)&&functionDefinition(candidate)!=definition
                &&hasStaticLinkage(candidate)==hasStaticLinkage(source))
                .min(Comparator.comparingInt(candidate->candidate.offset)).orElse(null);
        if(local!=null)return local;
        if(!searchFiles)return null;
        if(isHeader(source.file)&&!definition)return searchFunction(source,true);
        if(isImplementation(source.file)&&definition)return includedDeclarationCounterpart(source);
        return null;
    }
    private Symbol includedDeclarationCounterpart(Symbol definition){
        Map<Path,Symbol> headers=new LinkedHashMap<>();
        for(Symbol candidate:symbols)if(!sameFile(candidate,definition)&&includes(candidate.file)
                &&isHeader(candidate.file)&&functionDeclaration(candidate)&&!functionDefinition(candidate)
                &&candidate.name.equals(definition.name)&&hasStaticLinkage(candidate)==hasStaticLinkage(definition))
            headers.putIfAbsent(candidate.file.toAbsolutePath().normalize(),candidate);
        List<Symbol> matches=new ArrayList<>();
        for(Symbol header:headers.values()){
            Symbol implementation=searchFunction(header,true);
            if(implementation!=null&&sameFile(implementation,definition))matches.add(header);
        }
        return matches.size()==1?matches.get(0):null;
    }
    private static boolean hasStaticLinkage(Symbol symbol){int name=symbol.declaration.indexOf(symbol.name);return name>=0&&Pattern.compile("\\bstatic\\b").matcher(CIndentation.code(symbol.declaration.substring(0,name))).find();}
    private static boolean isHeader(Path file){return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".h");}
    private static boolean isImplementation(Path file){return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".c");}
    private static boolean sameLocation(Symbol left,Symbol right){return left.offset==right.offset&&sameFile(left,right);}
    private static boolean sameFile(Symbol left,Symbol right){return samePath(left.file,right.file);}
    private Symbol searchFunction(Symbol source,boolean wantDefinition){
        Path searchRoot=sourceRoot(source.file);if(searchRoot==null)searchRoot=source.file.toAbsolutePath().normalize().getParent();if(searchRoot==null)return null;
        String extension=wantDefinition?".c":".h";Map<Path,Symbol> matches=new LinkedHashMap<>();
        Path sibling=source.file.resolveSibling(source.file.getFileName().toString().replaceFirst("(?i)\\.[ch]$",extension));
        Symbol adjacent=readFunction(sibling,source,wantDefinition);
        if(adjacent!=null&&(wantDefinition||includes(adjacent.file)))matches.put(adjacent.file.toAbsolutePath().normalize(),adjacent);
        try(var files=Files.walk(searchRoot)){
            for(Path file:files.filter(Files::isRegularFile).filter(path->path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)).toList()){
                if(Thread.currentThread().isInterrupted())return null;
                Symbol found=readFunction(file,source,wantDefinition);if(found!=null&&(wantDefinition||includes(found.file)))matches.putIfAbsent(found.file.toAbsolutePath().normalize(),found);
            }
        }catch(java.io.IOException ignored){return null;}
        return matches.size()==1?matches.values().iterator().next():null;
    }
    private static Symbol readFunction(Path file,Symbol source,boolean wantDefinition){
        Path normalized=file.toAbsolutePath().normalize();if(normalized.equals(source.file.toAbsolutePath().normalize())||!Files.isRegularFile(normalized))return null;
        try{String text=Files.readString(normalized);if(!Pattern.compile("\\b"+Pattern.quote(source.name)+"\\b").matcher(CIndentation.code(text)).find())return null;
            List<Path> includeRoots=new ArrayList<>();if(normalized.getParent()!=null)includeRoots.add(normalized.getParent());if(source.file.getParent()!=null){includeRoots.add(source.file.getParent());includeRoots.add(C8080.Preprocessor.includeRootFor(source.file.toAbsolutePath().normalize().getParent()));}Path tree=sourceTree(normalized);if(tree!=null)includeRoots.add(tree.resolve("stdlib/include"));
            CSymbolIndex candidate=buildAt(text,normalized,includeRoots,text.length());Symbol found=candidate.find(source.name,text.length());
            return found!=null&&found.file.toAbsolutePath().normalize().equals(normalized)&&candidate.functionDeclaration(found)&&candidate.functionDefinition(found)==wantDefinition&&hasStaticLinkage(found)==hasStaticLinkage(source)&&(!wantDefinition||candidate.includes(source.file))?found:null;
        }catch(java.io.IOException ignored){return null;}
    }
    private boolean includes(Path file){return dependencies.keySet().stream().anyMatch(dependency->samePath(dependency,file));}
    private static boolean samePath(Path left,Path right){return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());}
    private static Path sourceRoot(Path file){Path tree=sourceTree(file);return tree;}
    private static Path sourceTree(Path file){Path path=file.toAbsolutePath().normalize();for(Path parent=path.getParent();parent!=null;parent=parent.getParent())if(parent.getFileName()!=null&&parent.getFileName().toString().equalsIgnoreCase("src"))return parent;return null;}
    Symbol find(String name,int offset,String source) {
        String before=source.substring(0,Math.min(offset,source.length()));
        Matcher member=Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\.|->)\\s*[A-Za-z_]*$").matcher(before);
        if(member.find()) {Symbol object=find(member.group(1),offset);if(object!=null){
            Matcher type=Pattern.compile("^(?:(?:const|volatile|static|extern)\\s+)*(?:(?:struct|union)\\s+)?([A-Za-z_]\\w*)").matcher(object.declaration);
            if(type.find()){Symbol record=aggregate(type.group(1),offset);if(record!=null){String declaration=record.declaration;Matcher field=Pattern.compile("(?m)([^;{}]+\\b"+Pattern.quote(name)+"\\b[^;{}]*;)").matcher(declaration);if(field.find())return new Symbol(name,record.file,field.group(1).strip(),record.offset,0,source.length(),record.external);}}
        }return null;}
        return find(name,offset);
    }
    private Symbol aggregate(String name,int offset){Set<String> visited=new HashSet<>();while(visited.add(name)){Symbol symbol=find(name,offset);if(symbol==null)return null;if(symbol.declaration.contains("{"))return symbol;Matcher alias=Pattern.compile("^typedef\\s+(?:(?:const|volatile)\\s+)*(?:(?:struct|union)\\s+)?([A-Za-z_]\\w*)").matcher(symbol.declaration);if(!alias.find())return null;name=alias.group(1);}return null;}
    List<Symbol> visible(int offset){LinkedHashMap<String,Symbol> result=new LinkedHashMap<>();for(Symbol candidate:symbols){Symbol visible=find(candidate.name,offset);if(visible!=null)result.put(visible.name,visible);}return List.copyOf(result.values());}
    List<Symbol> members(String object,int offset){
        Symbol variable=find(object,offset);if(variable==null)return List.of();Matcher type=Pattern.compile("^(?:(?:const|volatile|static|extern)\\s+)*(?:(?:struct|union)\\s+)?([A-Za-z_]\\w*)").matcher(variable.declaration);if(!type.find())return List.of();Symbol record=aggregate(type.group(1),offset);if(record==null)return List.of();List<Symbol> result=new ArrayList<>();
        int body=record.declaration.indexOf('{')+1;Matcher field=Pattern.compile("([^;{}]+;)").matcher(record.declaration.substring(body));
        while(field.find()){
            String declaration=field.group(1).strip();Matcher pointer=Pattern.compile("\\(\\s*\\*\\s*([A-Za-z_]\\w*)\\s*\\)").matcher(declaration);if(pointer.find()){result.add(new Symbol(pointer.group(1),record.file,declaration,record.offset+body+field.start(),0,Integer.MAX_VALUE,record.external));continue;}
            int depth=0,start=0;for(int i=0;i<declaration.length();i++){char c=declaration.charAt(i);if(c=='('||c=='[')depth++;if(c==')'||c==']')depth--;if((c==','||c==';')&&depth==0){String part=declaration.substring(start,i);Matcher name=Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\[[^]]*\\])?\\s*(?:=.*)?$").matcher(part);if(name.find())result.add(new Symbol(name.group(1),record.file,declaration,record.offset+body+field.start()+start+name.start(),0,Integer.MAX_VALUE,record.external));start=i+1;}}
        }return result;
    }
    String documentation(Symbol symbol){String source=sources.get(symbol.file);if(source==null)return "";int end=Math.min(symbol.offset,source.length());String before=source.substring(0,end).stripTrailing();if(before.endsWith("*/")){int start=before.lastIndexOf("/*");if(start>=0)return before.substring(start+2,before.length()-2).replaceAll("(?m)^\\s*\\* ?", "").strip();}String[] lines=before.split("\\R");StringBuilder result=new StringBuilder();for(int i=lines.length-1;i>=0&&lines[i].strip().startsWith("//");i--)result.insert(0,lines[i].strip().replaceFirst("^/{2,3} ?", "").strip()+"\n");return result.toString().strip();}
    String expansion(String expression){try{return preprocessor.expandForEditor(expression);}catch(IllegalArgumentException incomplete){return expression;}}
    boolean dependenciesChanged() {
        return dependenciesChanged(()->false);
    }
    boolean dependenciesChanged(BooleanSupplier cancelled) {
        for (var dependency : dependencies.entrySet()) {
            checkCancelled(cancelled);
            try {
                String current=Files.readString(dependency.getKey());
                checkCancelled(cancelled);
                if (!sameContents(current,dependency.getValue(),cancelled)) {
                    return true;
                }
            } catch (java.io.IOException missing) {
                return true;
            }
        }
        return false;
    }
    private static boolean sameContents(String left,String right,BooleanSupplier cancelled){if(left.length()!=right.length())return false;for(int index=0;index<left.length();index++){if((index&255)==0)checkCancelled(cancelled);if(left.charAt(index)!=right.charAt(index))return false;}return true;}
    void retainCurrentDeclaration(CSymbolIndex complete,Path file,int offset){retainCurrentDeclaration(complete,file,offset,()->false);}
    void retainCurrentDeclaration(CSymbolIndex complete,Path file,int offset,BooleanSupplier cancelled){
        if(complete==null)return;
        Symbol currentFunction=null;
        for(Symbol candidate:complete.symbols){
            checkCancelled(cancelled);
            if(candidate.file.equals(file)&&!candidate.external&&candidate.offset<=offset&&offset<candidate.offset+candidate.declaration.length()&&!candidate.declaration.startsWith("#")){
                if(!symbols.contains(candidate))symbols.add(candidate);
                if(functionDeclaration(candidate))currentFunction=candidate;
            }
        }
        if(currentFunction!=null)for(Symbol candidate:complete.symbols){
            checkCancelled(cancelled);
            if(candidate.file.equals(file)&&!candidate.external&&candidate.name.equals(currentFunction.name)&&functionDeclaration(candidate)&&!symbols.contains(candidate))symbols.add(candidate);
        }
        for(var source:complete.sources.entrySet()){checkCancelled(cancelled);sources.put(source.getKey(),source.getValue());}
    }
}
