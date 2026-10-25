package VulcanIDE;

import C8080.Compiler;
import Intel8080.assembler.Assembler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Links Vulcan objects and required runtime libraries into an executable. */
public final class VulcanLinker {
    private static final Pattern SYMBOL_TOKEN=Pattern.compile("(?<![A-Za-z_0-9])[A-Za-z_$][A-Za-z_$0-9]*");
    /** Linked executable with its layout and warnings. */
    public record Result(VulcanExecutable executable,Assembler.AssemblyInfo assemblyInfo,List<String> warnings){
        public Result{warnings=warnings==null?List.of():List.copyOf(warnings);}
    }
    private VulcanLinker(){}
    /** Links objects, appending runtime libraries; writes the executable when output is not null. */
    public static Result link(List<VulcanObject> input,Path output,List<Path> includeDirectories)throws IOException{
        if(input==null||input.isEmpty())throw new IllegalArgumentException("At least one object file is required.");
        List<VulcanObject> units=new ArrayList<>(input);
        String source=String.join("\n",input.stream().map(VulcanObject::assembly).toList());
        Compiler compiler=new Compiler(includeDirectories==null?List.of():includeDirectories);
        String runtime=compiler.linkRuntime(source).substring(source.length());
        if(!runtime.isBlank())units.add(VulcanObject.assembleSource("<runtime>",runtime));
        Map<String,String> exports=new LinkedHashMap<>();
        String entry=null;
        for(VulcanObject object:units){
            Set<String> publicSymbols=new LinkedHashSet<>(object.publicSymbols());
            if(object.entrySymbol()!=null)publicSymbols.add(object.entrySymbol());
            for(String symbol:publicSymbols){
                if(!object.definedSymbols().contains(symbol))throw new IllegalArgumentException("Public symbol "+symbol+" is not defined in "+object.sourceName()+".");
                String previous=exports.putIfAbsent(symbol,object.sourceName());
                if(previous!=null)throw new IllegalArgumentException("Duplicate symbol "+symbol+" in "+previous+" and "+object.sourceName()+".");
            }
            if(object.entrySymbol()!=null){
                if(entry!=null)throw new IllegalArgumentException("Multiple global entry points: "+entry+" and "+object.entrySymbol()+".");
                entry=object.entrySymbol();
            }
        }
        for(VulcanObject object:units)for(String symbol:object.externalSymbols())
            if(!exports.containsKey(symbol)&&!object.definedSymbols().contains(symbol))
                throw BuildDiagnostics.object(new IllegalArgumentException("Unresolved external symbol "+symbol+". Add a public definition or link the required library."),object);
        Set<String> reservedSymbols=new HashSet<>();
        for(VulcanObject unit:units){
            reservedSymbols.addAll(unit.definedSymbols());
            reservedSymbols.addAll(unit.externalSymbols());
            for(Assembler.ObjectAtom atom:unit.code().atoms()){
                if(atom.origin())reserveSymbolTokens(atom.operand(),reservedSymbols);
                for(Assembler.ObjectRelocation relocation:atom.relocations())reserveSymbolTokens(relocation.expression(),reservedSymbols);
            }
            for(Assembler.ObjectEquate equate:unit.code().equates())reserveSymbolTokens(equate.expression(),reservedSymbols);
        }
        List<Assembler.ObjectCode> codes=new ArrayList<>();
        for(int index=0;index<units.size();index++){
            VulcanObject object=units.get(index);Map<String,String> names=new LinkedHashMap<>();
            Set<String> publicSymbols=new HashSet<>(object.publicSymbols());if(object.entrySymbol()!=null)publicSymbols.add(object.entrySymbol());
            for(String symbol:object.definedSymbols()){
                if(publicSymbols.contains(symbol))continue;
                String base="_VO"+index+"_"+symbol;
                String renamed=base;
                for(int suffix=1;!reservedSymbols.add(renamed);suffix++)renamed=base+"_"+suffix;
                names.put(symbol,renamed);
            }
            codes.add(Assembler.renameObject(object.code(),names));
        }
        Assembler.FlatResult assembled=Assembler.assembleFlatObjects(codes);
        if(assembled.segments().isEmpty()||codes.stream().flatMap(c->c.atoms().stream()).noneMatch(a->a.section().equals(".text")&&a.bytes().length>0))throw new IllegalArgumentException("No executable code was generated.");
        Long address=entry==null?assembled.initialCodeAddress():assembled.symbols().get(entry);
        if(address==null)throw new IllegalArgumentException("Unresolved entry point "+entry);
        VulcanExecutable executable=new VulcanExecutable(address,
                assembled.segments().stream().map(segment->new VulcanExecutable.Segment(segment.address(),segment.bytes())).toList());
        if(output!=null)executable.write(output);
        return new Result(executable,assembled.info(),List.of());
    }
    private static void reserveSymbolTokens(String expression,Set<String> reservedSymbols){
        Matcher matcher=SYMBOL_TOKEN.matcher(expression);
        while(matcher.find())reservedSymbols.add(matcher.group());
    }
    /** Removes link-only directives from assembly source. */
    public static String stripLinkDirectives(String source){
        StringBuilder out=new StringBuilder();
        for(String line:source.split("\\R",-1)){
            String trim=line.trim().toLowerCase(Locale.ROOT);
            if(trim.equals("extern")||trim.startsWith("extern ")||trim.equals("public")||trim.startsWith("public ")||trim.equals("global")||trim.startsWith("global "))continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
