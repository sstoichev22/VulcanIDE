package VulcanIDE;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Builds hover card content for C and assembly symbols, numbers, includes, and keywords. */
final class HoverContent {
    /** Hover card content; the optional second destination offers a parallel jump such as declaration and implementation. */
    record Info(int start,int end,String title,String text,Path destination,int destinationOffset,String link,
                Path secondDestination,int secondDestinationOffset,String secondLink) {
        Info(int start,int end,String title,String text,Path destination,int destinationOffset,String link){
            this(start,end,title,text,destination,destinationOffset,link,null,0,null);
        }
    }
    private static final Pattern NUMBER=Pattern.compile("(?<![\\w.])(?:0[xXhH][0-9a-fA-F][0-9a-fA-F_]*|0[bB][01][01_]*|[0-9][0-9a-fA-F]*[hH]|[01][01_]*[bB]|[0-9][0-9_]*)(?:[uU](?:[lL]{1,2})?|[lL]{1,2}[uU]?)?(?![\\w.])");
    private static final Pattern FLOAT=Pattern.compile("(?<![\\w.])(?:(?:[0-9][0-9_]*\\.[0-9_]*|\\.[0-9][0-9_]*)(?:[eE][+-]?[0-9][0-9_]*)?|[0-9][0-9_]*[eE][+-]?[0-9][0-9_]*|[0-9][0-9_]*[fF])[fFlL]?(?![\\w.])");
    static Info at(String source,int offset,Path file,CSymbolIndex index,List<Path> includes){
        return at(source,offset,file,index,includes,true);
    }
    static Info at(String source,int offset,Path file,CSymbolIndex index,List<Path> includes,boolean searchFunctionFiles){
        if(offset<0||offset>=source.length()||file==null)return null;
        if(!file.toString().toLowerCase(Locale.ROOT).matches(".*\\.(c|h|asm)$"))return null;
        boolean asm=file.toString().toLowerCase(Locale.ROOT).endsWith(".asm");
        int lineStart=source.lastIndexOf('\n',offset)+1,lineEnd=source.indexOf('\n',offset);if(lineEnd<0)lineEnd=source.length();
        String line=source.substring(lineStart,lineEnd);
        if(!asm){Matcher directive=Pattern.compile("^[ \\t]*#\\s*(include)\\s+(.+)").matcher(line);if(directive.find()&&offset>=lineStart+directive.start(1)&&offset<lineStart+directive.end()){
            String expanded=index==null?directive.group(2):index.expansion(directive.group(2));Matcher argument=Pattern.compile("^\\s*([<\"])([^>\"]+)[>\"]").matcher(expanded);
            if(argument.find()){Path target=null;List<Path> roots=new ArrayList<>();if(argument.group(1).equals("\"")&&file.getParent()!=null)roots.add(file.getParent());roots.addAll(includes);for(Path root:roots){Path candidate=root.resolve(argument.group(2)).normalize();if(Files.isRegularFile(candidate)){target=candidate;break;}}
                return new Info(lineStart+directive.start(1),lineStart+directive.end(),"Include",argument.group(2)+(target==null?"\nHeader unavailable":"\n"+target),target,0,"Jump to header");}
        }}
        String clean=asm?asmCode(source):CIndentation.code(source);if(Character.isWhitespace(clean.charAt(offset)))return null;
        boolean numericFile=asm||file.toString().toLowerCase(Locale.ROOT).endsWith(".c");
        if(numericFile&&!asm){Matcher floats=FLOAT.matcher(clean);floats.region(lineStart,lineEnd);while(floats.find())if(offset>=signedStart(source,floats.start(),lineStart,false)&&offset<floats.end()){int sign=signedStart(source,floats.start(),lineStart,false);String text=C8080.EditorConstant.floatingLiteral(source.substring(sign,floats.end()));if(text!=null)return new Info(sign,floats.end(),"Floating constant",text,null,0,null);}}
        if(numericFile){Matcher numbers=NUMBER.matcher(clean);numbers.region(lineStart,lineEnd);while(numbers.find())if(offset>=signedStart(source,numbers.start(),lineStart,asm)&&offset<numbers.end()){int sign=signedStart(source,numbers.start(),lineStart,asm);return new Info(sign,numbers.end(),"Integer constant",numeric(source.substring(sign,numbers.end()),asm),null,0,null);}}
        int start=offset,end=offset;while(start>0&&identifier(source.charAt(start-1)))start--;while(end<source.length()&&identifier(source.charAt(end)))end++;
        if(start==end||!Character.isJavaIdentifierStart(source.charAt(start)))return null;
        String word=source.substring(start,end);
        if(asm){if(AsmCompletion.words("").contains(word.toUpperCase(Locale.ROOT)))return new Info(start,end,word.toUpperCase(Locale.ROOT),AsmCompletion.description(word,line),null,0,null);
            Matcher definition=Pattern.compile("(?im)^\\s*("+Pattern.quote(word)+")\\s*(?:(:)\\s*|(?:EQU)\\s+([^;\\n]+))").matcher(clean);if(definition.find())return new Info(start,end,definition.group(2)==null?"Equate "+word:"Label "+word,definition.group(2)==null?word+" = "+definition.group(3).strip():"Label defined at line "+(1+source.substring(0,definition.start(1)).chars().filter(c->c=='\n').count()),file,definition.start(1),"Jump to "+(definition.group(2)==null?"equate":"label"));return null;}
        CSymbolIndex.Symbol symbol=index==null?null:index.find(word,offset,source);
        if(word.equals("true")||word.equals("false"))
            return new Info(start,end,"Boolean constant "+word,"bool "+word+" = "+(word.equals("true")?"1":"0"),null,0,null);
        if(symbol!=null){String declaration=symbol.declaration(),display=declaration,title=word,link="Jump to declaration";Path destination=symbol.file();int destinationOffset=symbol.offset();
            if(declaration.startsWith("#")){
                title="Macro "+word;
                String expression=word;
                int next=end;
                while(next<clean.length()&&Character.isWhitespace(clean.charAt(next)))next++;
                if(next<clean.length()&&clean.charAt(next)=='('){
                    try {
                        int closing=C8080.Preprocessor.matchingParenthesis(clean,next);
                        expression=withoutComments(source.substring(start,closing+1));
                    } catch(IllegalArgumentException incomplete) {
                        expression=word;
                    }
                }
                String expansion=index.expansion(expression);
                if(!expansion.equals(expression)&&!expansion.isBlank())display+="\n\nExpands to: "+expansion;
            }
            else if(declaration.contains("{")&&(declaration.contains("struct")||declaration.contains("union"))){link="Jump to "+(declaration.contains("union")?"union":"struct");String[] lines=declaration.split("\\R");if(lines.length>7)display=String.join("\n",Arrays.copyOf(lines,5))+"\n...\n}";}
            else if(index.functionDeclaration(symbol)){display=declaration.replaceAll("[;\\s]+$", "");if(Boolean.TRUE.equals(EditorPanel.functionHasNoValueParameters(index,word,offset)))display=showExplicitVoidParameter(display,word);CSymbolIndex.Symbol counterpart=index.functionCounterpart(symbol,searchFunctionFiles);boolean selfHover=file!=null&&symbol.file()!=null&&file.toAbsolutePath().normalize().equals(symbol.file().toAbsolutePath().normalize())&&start>=symbol.offset()&&start<symbol.offset()+declaration.length();String own="Jump to "+(index.functionDefinition(symbol)?"implementation":"declaration");if(counterpart!=null){destination=counterpart.file();destinationOffset=counterpart.offset();link="Jump to "+(index.functionDefinition(symbol)?"declaration":"implementation");if(!selfHover){String docs=index.documentation(symbol);if(!docs.isBlank())display+="\n\n"+docs;display+="\n\nDefined in: "+symbol.file();return new Info(start,end,title,display,destination,destinationOffset,link,symbol.file(),symbol.offset(),own);}}else if(!selfHover){destination=symbol.file();destinationOffset=symbol.offset();link=own;}else{destination=null;link=null;}}
            else if(declaration.matches("(?s).*\\bconst\\b.*")&&declaration.contains("=")){String expanded=index.expansion(declaration);String value=C8080.EditorConstant.value(expanded);display+="\n\nValue: "+(value==null?expanded.substring(expanded.indexOf('=')+1).replaceAll(";\\s*$", "").strip():value);}
            String docs=index.documentation(symbol);if(!docs.isBlank())display+="\n\n"+docs;display+="\n\nDefined in: "+symbol.file();return new Info(start,end,title,display,destination,destinationOffset,link);}
        String keyword=EditorDiagnostics.keywordDescription(word);if(keyword!=null)return new Info(start,end,word,keyword,null,0,null);return null;
    }
    private static String showExplicitVoidParameter(String declaration,String name){
        Matcher function=Pattern.compile("\\b"+Pattern.quote(name)+"\\s*\\(").matcher(declaration);
        if(!function.find())return declaration;
        int opening=declaration.indexOf('(',function.start()),depth=0,closing=-1;
        for(int position=opening;position<declaration.length();position++){
            if(declaration.charAt(position)=='(')depth++;
            else if(declaration.charAt(position)==')'&&--depth==0){closing=position;break;}
        }
        if(closing<0||!CIndentation.code(declaration.substring(opening+1,closing)).isBlank())return declaration;
        return declaration.substring(0,opening+1)+"void"+declaration.substring(closing);
    }
    private static String withoutComments(String source){
        StringBuilder result=new StringBuilder();char quote=0;
        for(int i=0;i<source.length();i++){
            char c=source.charAt(i);
            if(quote!=0){result.append(c);if(c=='\\'&&i+1<source.length())result.append(source.charAt(++i));else if(c==quote)quote=0;}
            else if(c=='"'||c=='\''){quote=c;result.append(c);}
            else if(c=='/'&&i+1<source.length()&&source.charAt(i+1)=='/'){result.append(' ');i++;while(i+1<source.length()&&source.charAt(i+1)!='\n')i++;}
            else if(c=='/'&&i+1<source.length()&&source.charAt(i+1)=='*'){result.append(' ');i+=2;while(i<source.length()&&!(source.charAt(i)=='*'&&i+1<source.length()&&source.charAt(i+1)=='/')){if(source.charAt(i)=='\n')result.append('\n');i++;}if(i+1<source.length())i++;}
            else result.append(c);
        }
        return result.toString();
    }
    static Info atCaret(String source,int caret,Path file,CSymbolIndex index,List<Path> includes){
        return atCaret(source,caret,file,index,includes,true);
    }
    static Info atCaret(String source,int caret,Path file,CSymbolIndex index,List<Path> includes,boolean searchFunctionFiles){
        if(caret<0||caret>source.length()||file==null||index==null
                ||!file.toString().toLowerCase(Locale.ROOT).matches(".*\\.(c|h)$"))return null;
        CIndentation.LexicalView view=CIndentation.lexicalView(source,caret);
        if(!view.caretIsCode())return null;
        String clean=view.code();
        int nestedCalls=0,nestedBlocks=0;
        for(int position=caret-1;position>=0;position--){
            char character=clean.charAt(position);
            if(character=='}'){nestedBlocks++;continue;}
            if(character=='{'){if(nestedBlocks>0){nestedBlocks--;continue;}if(nestedCalls==0)return null;}
            if(character==')'){nestedCalls++;continue;}
            if(character=='('){
                if(nestedCalls>0){nestedCalls--;continue;}
                int nameEnd=position-1;while(nameEnd>=0&&Character.isWhitespace(clean.charAt(nameEnd)))nameEnd--;
                int nameStart=nameEnd;while(nameStart>=0&&Character.isJavaIdentifierPart(clean.charAt(nameStart)))nameStart--;nameStart++;
                if(nameStart>=0&&nameStart<=nameEnd&&Character.isJavaIdentifierStart(clean.charAt(nameStart))){
                    String name=clean.substring(nameStart,nameEnd+1);
                    CSymbolIndex.Symbol symbol=index.find(name,caret,source);
                    if(symbol!=null&&index.functionDeclaration(symbol))return at(source,nameStart,file,index,includes,searchFunctionFiles);
                }
                continue;
            }
            if(character==';'&&nestedCalls==0&&nestedBlocks==0)return null;
        }
        return null;
    }
    private static int signedStart(String source,int start,int lineStart,boolean asm){int minus=start-1;while(minus>=lineStart&&Character.isWhitespace(source.charAt(minus)))minus--;if(minus<lineStart||source.charAt(minus)!='-')return start;int previous=minus-1;while(previous>=lineStart&&Character.isWhitespace(source.charAt(previous)))previous--;String before=source.substring(lineStart,minus).strip();return previous<lineStart||"=([{,:?+-*/%&|^!~<>".indexOf(source.charAt(previous))>=0||before.matches("(?s).*\\b(?:return|case)$")||(asm&&before.matches("[A-Za-z]+"))?minus:start;}
    private static boolean identifier(char c){return Character.isJavaIdentifierPart(c);}
    static String numeric(String literal,boolean asm){try{String s=literal.replace("_", "").replaceAll("\\s+", "");boolean negative=s.startsWith("-");if(negative)s=s.substring(1);int radix=10;if(s.startsWith("0x")||s.startsWith("0X")||(asm&&(s.startsWith("0h")||s.startsWith("0H")))){s=s.substring(2).replaceAll("[uUlL]+$","");radix=16;}else if(s.startsWith("0b")||s.startsWith("0B")){s=s.substring(2).replaceAll("[uUlL]+$","");radix=2;}else if(asm&&s.matches("[0-9a-fA-F][0-9a-fA-F_]*[hH]")){s=s.substring(0,s.length()-1);radix=16;}else if(asm&&s.matches("[01][01_]*[bB]")){s=s.substring(0,s.length()-1);radix=2;}else{s=s.replaceAll("[uUlL]+$","");if(!asm&&s.length()>1&&s.startsWith("0"))radix=8;}BigInteger magnitude=new BigInteger(s,radix);BigInteger value=negative?magnitude.negate():magnitude;return "Decimal: "+value+"\nBinary: "+(negative?"-":"")+"0b"+magnitude.toString(2)+"\nHex: "+(negative?"-":"")+"0x"+magnitude.toString(16).toUpperCase(Locale.ROOT)+"\nMagnitude: "+Math.max(1,magnitude.bitLength())+" bits"+(negative?" (sign and magnitude; no assumed storage width)":"");}catch(NumberFormatException malformed){return literal;}}
    private static String asmCode(String source){StringBuilder clean=new StringBuilder(source);boolean comment=false;char quote=0;for(int i=0;i<source.length();i++){char c=source.charAt(i);if(c=='\n'){comment=false;quote=0;}else if(comment)clean.setCharAt(i,' ');else if(quote!=0){clean.setCharAt(i,' ');if(c==quote)quote=0;}else if(c==';'){comment=true;clean.setCharAt(i,' ');}else if(c=='\''||c=='"'){quote=c;clean.setCharAt(i,' ');}}return clean.toString();}
}
