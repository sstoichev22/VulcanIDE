package VulcanIDE;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** C editor completion: keywords, visible symbols, members, standard-library entries, and generated headers. */
final class CCompletion {
    /** One completion candidate; requiresHeader marks a standard-library name needing an #include. */
    record Entry(String name,String detail,boolean requiresHeader) {}
    private static final List<String> KEYWORDS=List.of("asm","break","case","char","const","continue","default","do","double","else","enum","extern","float","for","if","int","long","return","short","signed","sizeof","static","struct","switch","typedef","union","unsigned","void","volatile","while","bool","uint8","uint16","uint32","uint64","int8","int16","int32","int64");
    private static final Pattern FUNCTION_CALL=Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern FUNCTION_HEADER_PREFIX=Pattern.compile("(?s)\\s*(?:(?:static|extern|inline|const|volatile|unsigned|signed|short|long|register|auto)\\s+)*(?:(?:struct|union|enum)\\s+[A-Za-z_]\\w*|[A-Za-z_]\\w*)(?:\\s*\\*+)?\\s*");
    private static final Pattern INTEGER_LITERAL=Pattern.compile("(?i)[+-]?(?:0x[0-9a-f]+|0b[01]+|[0-9]+)[uUlL]*");
    private static final Pattern FLOAT_LITERAL=Pattern.compile("(?i)[+-]?(?:(?:[0-9]+\\.[0-9]*|\\.[0-9]+)(?:e[+-]?[0-9]+)?|[0-9]+e[+-]?[0-9]+)[fFlL]?");
    private record Cache(long stamp,List<Entry> entries) {}
    private static final Map<Path,Cache> LIBRARY=new HashMap<>();
    static String prefixAt(String source,int caret){
        if(caret<=0||caret>source.length()||(caret<source.length()&&Character.isJavaIdentifierPart(source.charAt(caret))))return "";
        int start=caret;while(start>0&&Character.isJavaIdentifierPart(source.charAt(start-1)))start--;
        if(start==caret||!Character.isJavaIdentifierStart(source.charAt(start)))return "";
        String clean=CIndentation.code(source);if(clean.substring(start,caret).isBlank())return "";
        return source.substring(start,caret);
    }
    static List<Entry> suggestions(String source,int caret,CSymbolIndex index,List<Entry> library){
        String prefix=prefixAt(source,caret);if(prefix.isEmpty()||index==null)return List.of();
        LinkedHashMap<String,Entry> matches=new LinkedHashMap<>();
        Matcher member=Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\.|->)\\s*[A-Za-z_]*$").matcher(CIndentation.code(source).substring(0,caret));
        if(member.find()){for(var symbol:index.members(member.group(1),caret))if(symbol.name().startsWith(prefix))matches.put(symbol.name(),new Entry(symbol.name(),symbol.declaration(),false));return matches.values().stream().filter(entry->!entry.name().equals(prefix)).toList();}
        List<Entry> generated=new ArrayList<>(functionHeaderSuggestions(source,caret,prefix,index,library));
        List<Entry> argumentFavorites=argumentSuggestions(source,caret,prefix,index);
        for(var symbol:index.visible(caret))if(symbol.name().startsWith(prefix))matches.put(symbol.name(),new Entry(symbol.name(),symbol.declaration().replaceAll("\\s+"," "),false));
        for(String word:KEYWORDS)if(word.startsWith(prefix))matches.putIfAbsent(word,new Entry(word,"C keyword or native type",false));
        List<Entry> visible=new ArrayList<>(matches.values());visible.sort(Comparator.comparing(Entry::name));
        List<Entry> missing=new ArrayList<>();for(Entry entry:library)if(entry.name.startsWith(prefix)&&!matches.containsKey(entry.name)){matches.put(entry.name,entry);missing.add(entry);}missing.sort(Comparator.comparing(Entry::name));visible.addAll(missing);
        List<Entry> ranked=new ArrayList<>(argumentFavorites);Set<String> rankedNames=new HashSet<>();for(Entry entry:argumentFavorites)rankedNames.add(entry.name());for(Entry entry:visible)if(rankedNames.add(entry.name()))ranked.add(entry);
        generated.addAll(ranked);return generated.stream().filter(entry->!entry.name().equals(prefix)).toList();
    }
    private record CallContext(String name,int parameterIndex,int opening) {}
    private static List<Entry> argumentSuggestions(String source,int caret,String prefix,CSymbolIndex index){
        String clean=CIndentation.code(source);CallContext context=callContext(clean,caret);if(context==null)return List.of();
        Map<String,Entry> preferred=new LinkedHashMap<>();Matcher calls=FUNCTION_CALL.matcher(clean);calls.region(0,context.opening());
        while(calls.find()){
            if(!calls.group(1).equals(context.name()))continue;int opening=calls.end()-1,closing=matchingCallClose(clean,opening,context.opening());if(closing<0)continue;
            List<Argument> arguments=arguments(source,clean,opening,closing);if(context.parameterIndex()>=arguments.size())continue;
            Argument argument=arguments.get(context.parameterIndex());String candidate=clean.substring(argument.start(),argument.end()).strip();if(!candidate.matches("[A-Za-z_]\\w*")||!candidate.startsWith(prefix))continue;
            CSymbolIndex.Symbol symbol=index.find(candidate,caret,source);if(symbol==null)continue;
            preferred.putIfAbsent(candidate,new Entry(candidate,symbol.declaration().replaceAll("\\s+"," "),false));
        }
        return List.copyOf(preferred.values());
    }
    private static CallContext callContext(String clean,int caret){
        Deque<Integer> openings=new ArrayDeque<>();for(int position=0;position<Math.min(caret,clean.length());position++){char character=clean.charAt(position);if(character=='(')openings.push(position);else if(character==')'&&!openings.isEmpty())openings.pop();}
        for(int opening:openings){int nameEnd=opening;while(nameEnd>0&&Character.isWhitespace(clean.charAt(nameEnd-1)))nameEnd--;int nameStart=nameEnd;while(nameStart>0&&Character.isJavaIdentifierPart(clean.charAt(nameStart-1)))nameStart--;if(nameStart==nameEnd||!Character.isJavaIdentifierStart(clean.charAt(nameStart)))continue;String name=clean.substring(nameStart,nameEnd);if(KEYWORDS.contains(name))continue;
            int parameterIndex=0,parens=0,brackets=0,braces=0;for(int position=opening+1;position<caret;position++){char character=clean.charAt(position);if(character=='(')parens++;else if(character==')'&&parens>0)parens--;else if(character=='[')brackets++;else if(character==']'&&brackets>0)brackets--;else if(character=='{')braces++;else if(character=='}'&&braces>0)braces--;else if(character==','&&parens==0&&brackets==0&&braces==0)parameterIndex++;}
            return new CallContext(name,parameterIndex,opening);
        }
        return null;
    }
    private record Argument(int start,int end) {}
    private static List<Entry> functionHeaderSuggestions(String source,int caret,String prefix,CSymbolIndex index,List<Entry> library){
        int prefixStart=caret-prefix.length();String clean=CIndentation.code(source);
        int boundary=Math.max(clean.lastIndexOf(';',Math.max(0,prefixStart-1)),Math.max(clean.lastIndexOf('{',Math.max(0,prefixStart-1)),clean.lastIndexOf('}',Math.max(0,prefixStart-1))));
        String declarationPrefix=clean.substring(boundary+1,prefixStart);
        if(!FUNCTION_HEADER_PREFIX.matcher(declarationPrefix).matches()||declarationPrefix.matches("(?s).*\\b(?:return|if|else|for|while|do|switch|case|sizeof|goto)\\s*"))return List.of();
        Map<String,Entry> generated=new LinkedHashMap<>();Matcher calls=FUNCTION_CALL.matcher(clean);calls.region(0,caret);
        while(calls.find()){
            String name=calls.group(1);int nameStart=calls.start(1),opening=calls.end()-1;
            if(KEYWORDS.contains(name)||!name.startsWith(prefix)||index.find(name,nameStart,source)!=null
                    ||library.stream().anyMatch(entry->entry.name().equals(name)))continue;
            int closing=matchingCallClose(clean,opening,caret);if(closing<0)continue;
            List<Argument> arguments=arguments(source,clean,opening,closing);List<String> parameters=new ArrayList<>();Set<String> names=new HashSet<>();
            for(int i=0;i<arguments.size();i++){
                Argument argument=arguments.get(i);String parameterName=parameterName(clean,argument,i+1);String baseName=parameterName;int suffix=2;
                while(!names.add(parameterName))parameterName=baseName+suffix++;
                String type=argumentType(source,clean,argument,nameStart,index).stripTrailing();String declarator=type.endsWith("*")?type+parameterName:type+" "+parameterName;parameters.add(declarator);
            }
            String signature=name+"("+String.join(", ",parameters)+")";
            generated.putIfAbsent(signature,new Entry(signature,"Generated from an unresolved call; choose the return type before completing the header.",false));
        }
        List<Entry> result=new ArrayList<>(generated.values());result.sort(Comparator.comparing(Entry::name));return result;
    }
    private static int matchingCallClose(String clean,int opening,int limit){
        int depth=1;for(int position=opening+1;position<limit;position++){char character=clean.charAt(position);if(character=='(')depth++;else if(character==')'&&--depth==0)return position;}return -1;
    }
    private static List<Argument> arguments(String source,String clean,int opening,int closing){
        List<Argument> result=new ArrayList<>();int start=opening+1,parens=1,brackets=0,braces=0;
        for(int position=opening+1;position<closing;position++){char character=clean.charAt(position);if(character=='(')parens++;else if(character==')')parens--;else if(character=='[')brackets++;else if(character==']')brackets--;else if(character=='{')braces++;else if(character=='}')braces--;else if(character==','&&parens==1&&brackets==0&&braces==0){addArgument(result,source,clean,start,position);start=position+1;}}
        addArgument(result,source,clean,start,closing);return result;
    }
    private static void addArgument(List<Argument> result,String source,String clean,int start,int end){
        if(!clean.substring(start,end).isBlank()||firstTokenAfterTrivia(source,start,end)<end)result.add(new Argument(start,end));
    }
    private static int firstTokenAfterTrivia(String source,int start,int end){
        int position=start;while(position<end){while(position<end&&Character.isWhitespace(source.charAt(position)))position++;if(position+1<end&&source.startsWith("//",position)){position+=2;while(position<end&&source.charAt(position)!='\n'&&source.charAt(position)!='\r')position++;continue;}if(position+1<end&&source.startsWith("/*",position)){int close=source.indexOf("*/",position+2);if(close<0||close+2>end)return end;position=close+2;continue;}return position;}return end;
    }
    private static String parameterName(String clean,Argument argument,int ordinal){
        String expression=clean.substring(argument.start(),argument.end()).strip();if(expression.matches("[A-Za-z_]\\w*")&&!KEYWORDS.contains(expression))return expression;return "argument"+ordinal;
    }
    private static String argumentType(String source,String clean,Argument argument,int callOffset,CSymbolIndex index){
        int token=firstTokenAfterTrivia(source,argument.start(),argument.end());if(token<argument.end()){int quote=token;if(source.startsWith("u8\"",token))quote=token+2;else if("uUL\"".indexOf(source.charAt(token))>=0&&token+1<argument.end()&&source.charAt(token+1)=='\"')quote=token+1;if(source.charAt(quote)=='\"')return "const char *";if(source.charAt(quote)=='\'')return "char";}
        String expression=clean.substring(argument.start(),argument.end()).strip();if(FLOAT_LITERAL.matcher(expression).matches())return "double";if(INTEGER_LITERAL.matcher(expression).matches())return "int";
        if(expression.matches("[A-Za-z_]\\w*")){CSymbolIndex.Symbol symbol=index.find(expression,callOffset,source);String type=symbol==null?null:declaredType(symbol);if(type!=null&&!type.equals("void"))return type;}
        Matcher call=FUNCTION_CALL.matcher(expression);if(call.lookingAt()){CSymbolIndex.Symbol symbol=index.find(call.group(1),callOffset,source);String type=symbol==null?null:declaredType(symbol);if(type!=null&&!type.equals("void"))return type;}
        return "int";
    }
    private static String declaredType(CSymbolIndex.Symbol symbol){
        String declaration=symbol.declaration().strip();if(declaration.startsWith("#"))return null;int name=declaration.lastIndexOf(symbol.name());if(name<=0)return null;
        String type=declaration.substring(0,name).replaceAll("\\b(?:static|extern|register|auto|inline)\\b"," ").replaceAll("\\s+"," ").strip();if(type.isEmpty())return null;
        if(declaration.substring(name+symbol.name().length()).stripLeading().startsWith("["))type=type.replaceAll("\\s*\\*$", "")+" *";
        return type.replaceAll("\\s*\\*\\s*"," *").strip();
    }
    static synchronized List<Entry> standardLibrary(Path directory){
        Path key=directory.toAbsolutePath().normalize();long stamp=stamp(key);Cache cached=LIBRARY.get(key);if(cached!=null&&cached.stamp==stamp)return cached.entries;
        if(!Files.isDirectory(key))return List.of();
        List<Entry> result=new ArrayList<>();try(var files=Files.list(key)){
            for(Path header:files.filter(p->p.toString().endsWith(".h")).sorted().toList()){
                String code="#include \""+header.getFileName()+"\"\n";CSymbolIndex index=CSymbolIndex.build(code,key.resolve("__completion__.c"),List.of(key));
                for(var symbol:index.visible(code.length()))if(symbol.file().equals(header))result.add(new Entry(symbol.name(),symbol.declaration().replaceAll("\\s+"," ")+" | requires <"+header.getFileName()+">",true));
            }
        }catch(java.io.IOException unavailable){return List.of();}
        List<Entry> complete=List.copyOf(result);LIBRARY.put(key,new Cache(stamp,complete));return complete;
    }
    private static long stamp(Path directory){long result=0;try(var files=Files.list(directory)){for(Path file:files.filter(p->p.toString().endsWith(".h")).sorted().toList())result=31*result+Files.getLastModifiedTime(file).toMillis()+Files.size(file);}catch(java.io.IOException unavailable){}return result;}
}
