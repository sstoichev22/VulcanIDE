package VulcanIDE;

import Intel8080.assembler.Assembler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Relocatable VOBJ4 object: source, assembled code, symbols, and relocations. */
public final class VulcanObject {
    private static final byte[] MAGIC = "VOBJ4\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 4;
    private static final int MAX_SOURCE_BYTES = 32 * 1024 * 1024;
    private final String sourceName;
    private final String assembly;
    private final List<String> definedSymbols;
    private final List<String> publicSymbols;
    private final List<String> externalSymbols;
    private final String entrySymbol;
    private final List<Relocation> relocations;
    private final int textSize;
    private final int dataSize;
    private final Assembler.ObjectCode code;

    /** Link-time patch of width bytes at offset in a section. */
    public record Relocation(int sourceLine,String symbol,String kind,String section,int offset,int width,String expression) {}

    private VulcanObject(String sourceName, String assembly, List<String> definedSymbols,
                         List<String> publicSymbols, List<String> externalSymbols, String entrySymbol,
                         List<Relocation> relocations, int textSize, int dataSize, Assembler.ObjectCode code) {
        this.sourceName = sourceName;
        this.assembly = assembly;
        this.definedSymbols = List.copyOf(definedSymbols);
        this.publicSymbols = List.copyOf(publicSymbols);
        this.externalSymbols = List.copyOf(externalSymbols);
        this.entrySymbol = entrySymbol;
        this.relocations = List.copyOf(relocations);
        this.textSize = textSize;
        this.dataSize = dataSize;
        this.code = code;
    }

    /** Assembles source into an object. */
    public static VulcanObject assembleSource(String name, String assembly) {
        String source = assembly == null ? "" : assembly;
        Set<String> definitions = new LinkedHashSet<>();
        Set<String> exports = new LinkedHashSet<>();
        Set<String> externals = new LinkedHashSet<>();
        List<String> lines = source.lines().toList();
        boolean nextPublic=false,nextEntry=false;
        String entry = null;
        Pattern labelPattern = Pattern.compile("^\\s*([A-Za-z_$][A-Za-z_$0-9]*)\\s*:");
        Pattern equPattern = Pattern.compile("^\\s*([A-Za-z_$][A-Za-z_$0-9]*)\\s+(?i:EQU)\\b");
        Pattern declarationPattern = Pattern.compile("(?i)^(extern|public|global)(?:\\s+(.*))?$");
        for (String line : lines) {
            String clean = stripComment(line).trim();
            if (clean.isEmpty()) continue;
            String lower = clean.toLowerCase(java.util.Locale.ROOT);
            if(lower.equals("end"))break;
            Matcher declaration = declarationPattern.matcher(clean);
            if (declaration.matches()) {
                String directive = declaration.group(1).toLowerCase(java.util.Locale.ROOT);
                String argument = declaration.group(2) == null ? "" : declaration.group(2).trim();
                if (directive.equals("extern")) {
                    for (String symbol : argument.split("[,\\s]+")) if (!symbol.isBlank()) externals.add(symbol);
                } else if (directive.equals("public")) {
                    if (argument.isEmpty()) nextPublic = true;
                    else for (String symbol : argument.split("[,\\s]+")) if (!symbol.isBlank()) exports.add(symbol);
                } else if (argument.isEmpty()) nextEntry = true;
                else {
                    if (!argument.matches("[A-Za-z_$][A-Za-z_$0-9]*")) throw new IllegalArgumentException("Invalid global entry symbol.");
                    if (entry != null && !entry.equals(argument)) throw new IllegalArgumentException("Multiple global entry points in " + name);
                    entry = argument;
                }
                continue;
            }
            Matcher label = labelPattern.matcher(clean);
            if (label.find()) {
                String symbol = label.group(1);
                definitions.add(symbol);
                if (nextPublic) exports.add(symbol);
                if (nextEntry) {if(entry!=null&&!entry.equals(symbol))throw new IllegalArgumentException("Multiple global entry points in "+name);entry=symbol;}
                nextPublic=nextEntry=false;
            }
            Matcher equ = equPattern.matcher(clean);
            if (equ.find()) definitions.add(equ.group(1));
        }
        if(nextPublic||nextEntry)throw new IllegalArgumentException("public/global requires a following label in "+name);
        if (entry == null && definitions.contains("$start")) entry = "$start";
        if (entry == null && definitions.contains("_start")) entry = "_start";

        List<Relocation> relocations = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>(definitions);
        candidates.addAll(externals);
        Assembler.ObjectCode code;
        try { code=Assembler.assembleRelocatable(source); }
        catch(RuntimeException error) {
            Path path;
            try { path=Path.of(name==null?"source.asm":name); } catch(RuntimeException invalid){path=Path.of("source.asm");}
            throw BuildDiagnostics.source(error,path,source);
        }
        for (Assembler.ObjectAtom atom:code.atoms()) for (Assembler.ObjectRelocation relocation:atom.relocations()) {
            for(String symbol:candidates)if(Pattern.compile("(?<![A-Za-z_0-9])"+Pattern.quote(symbol)+"(?![A-Za-z_0-9])").matcher(relocation.expression()).find())
                relocations.add(new Relocation(relocation.sourceLine(),symbol,"ABS"+relocation.width()*8,relocation.section(),relocation.offset(),relocation.width(),relocation.expression()));
        }
        return new VulcanObject(name == null ? "<memory>" : name, source, new ArrayList<>(definitions),
                new ArrayList<>(exports), new ArrayList<>(externals), entry, relocations, code.textSize(), code.dataSize(),code);
    }

    private static String stripComment(String line) {
        boolean quoted = false; char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '\\') { i++; continue; }
            if (c == '\'' || c == '"') { if (!quoted) { quoted = true; quote = c; } else if (quote == c) quoted = false; }
            else if (c == ';' && !quoted) return line.substring(0, i);
        }
        return line;
    }

    public String sourceName() { return sourceName; }
    public String assembly() { return assembly; }
    public List<String> definedSymbols() { return definedSymbols; }
    public List<String> publicSymbols() { return publicSymbols; }
    public List<String> externalSymbols() { return externalSymbols; }
    public String entrySymbol() { return entrySymbol; }
    public List<Relocation> relocations() { return relocations; }
    public int textSize() { return textSize; }
    public int dataSize() { return dataSize; }
    public Assembler.ObjectCode code() { return code; }
    public int textAlignment(){return 1;}
    public int dataAlignment(){return 8;}
    /** Defined or external symbol with its section and offset. */
    public record Symbol(String name,String section,int offset,boolean exported,boolean external){}
    /** Returns the object's defined and external symbols. */
    public List<Symbol> symbols(){
        List<Symbol> result=new ArrayList<>();
        for(var atom:code.atoms())for(String name:atom.labels())result.add(new Symbol(name,atom.section(),atom.offset(),publicSymbols.contains(name)||name.equals(entrySymbol),false));
        for(var equate:code.equates())result.add(new Symbol(equate.name(),"ABS",-1,publicSymbols.contains(equate.name()),false));
        for(String name:externalSymbols)if(!definedSymbols.contains(name))result.add(new Symbol(name,"UND",-1,false,true));
        return List.copyOf(result);
    }

    /** Writes the object file. */
    public void write(Path path) throws IOException {
        byte[] source = assembly.getBytes(StandardCharsets.UTF_8);
        if (source.length > MAX_SOURCE_BYTES) throw new IOException("Object source exceeds the 32 MiB format limit.");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.write(MAGIC); out.writeInt(VERSION); writeString(out, sourceName); out.writeInt(source.length); out.write(source);
            out.writeInt(textSize); out.writeInt(dataSize);out.writeInt(textAlignment());out.writeInt(dataAlignment());writeString(out, entrySymbol == null ? "" : entrySymbol);
            writeStrings(out, definedSymbols); writeStrings(out, publicSymbols); writeStrings(out, externalSymbols);
            out.writeInt(relocations.size());
            for (Relocation relocation : relocations) {
                out.writeInt(relocation.sourceLine());writeString(out,relocation.symbol());writeString(out,relocation.kind());
                writeString(out,relocation.section());out.writeInt(relocation.offset());out.writeInt(relocation.width());writeString(out,relocation.expression());
            }
            out.writeInt(code.atoms().size());
            for(Assembler.ObjectAtom atom:code.atoms()){
                writeString(out,atom.section());out.writeInt(atom.offset());out.writeInt(atom.bytes().length);out.write(atom.bytes());
                writeString(out,atom.mnemonic());writeString(out,atom.operand());out.writeBoolean(atom.origin());writeStrings(out,atom.labels());
                out.writeInt(atom.relocations().size());
                for(Assembler.ObjectRelocation relocation:atom.relocations()){
                    out.writeInt(relocation.offset());out.writeInt(relocation.width());writeString(out,relocation.expression());out.writeInt(relocation.sourceLine());
                }
            }
            out.writeInt(code.equates().size());
            for(Assembler.ObjectEquate equate:code.equates()){writeString(out,equate.name());writeString(out,equate.expression());}
        }
    }

    /** Reports whether a file starts with the object header. */
    public static boolean isObject(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) < MAGIC.length) return false;
        try (var in = Files.newInputStream(path)) {byte[] magic=in.readNBytes(MAGIC.length);return java.util.Arrays.equals(magic,MAGIC);}
    }

    /** Reads and validates an object file. */
    public static VulcanObject read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            byte[] magic=in.readNBytes(MAGIC.length);
            if (!java.util.Arrays.equals(magic,MAGIC)) throw new IOException("Invalid Vulcan object magic.");
            int version = in.readInt(); if (version != VERSION) throw new IOException("Unsupported object format version " + version + ".");
            String name = readString(in); int sourceLength = in.readInt();
            if (sourceLength < 0 || sourceLength > MAX_SOURCE_BYTES) throw new IOException("Invalid object source size.");
            String source = new String(in.readNBytes(sourceLength), StandardCharsets.UTF_8);
            if (source.getBytes(StandardCharsets.UTF_8).length != sourceLength) throw new IOException("Truncated object source.");
            int text = in.readInt(), data = in.readInt();
            if(text<0||data<0||(long)text+data>MAX_SOURCE_BYTES)throw new IOException("Invalid section sizes.");
            if(in.readInt()!=1||in.readInt()!=8)throw new IOException("Unsupported section alignment.");
            String entry = readString(in);
            List<String> defs = readStrings(in), pubs = readStrings(in), exts = readStrings(in);
            int relocCount = in.readInt(); if (relocCount < 0 || relocCount > 1_000_000) throw new IOException("Invalid relocation count.");
            List<Relocation> relocs = new ArrayList<>(relocCount);
            for (int i = 0; i < relocCount; i++){
                int line=in.readInt();String symbol=readString(in),kind=readString(in);
                relocs.add(new Relocation(line,symbol,kind,readString(in),in.readInt(),in.readInt(),readString(in)));
            }
            Assembler.ObjectCode code;
            {
                int atomCount=readCount(in);List<Assembler.ObjectAtom> atoms=new ArrayList<>();long total=0;
                for(int n=0;n<atomCount;n++){
                    String section=readString(in);int offset=in.readInt(),length=in.readInt();total+=length;
                    if(!section.equals(".text")&&!section.equals(".data")||offset<0||length<0||total>MAX_SOURCE_BYTES)throw new IOException("Invalid object section.");
                    byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new IOException("Truncated object bytes.");
                    String mnemonic=readString(in),operand=readString(in);boolean origin=in.readBoolean();List<String> labels=readStrings(in);
                    int relocationCount=readCount(in);List<Assembler.ObjectRelocation> patches=new ArrayList<>();
                    for(int r=0;r<relocationCount;r++){
                        int at=in.readInt(),width=in.readInt();String expression=readString(in);int line=in.readInt();
                        if(width!=1&&width!=2&&width!=4&&width!=8||at<offset||at-offset>length-width)throw new IOException("Relocation lies outside section bytes.");
                        patches.add(new Assembler.ObjectRelocation(section,at,width,expression,line));
                    }
                    atoms.add(new Assembler.ObjectAtom(section,offset,bytes,mnemonic,operand,origin,labels,patches));
                }
                int equateCount=readCount(in);List<Assembler.ObjectEquate> equates=new ArrayList<>();
                for(int n=0;n<equateCount;n++)equates.add(new Assembler.ObjectEquate(readString(in),readString(in)));
                code=new Assembler.ObjectCode(atoms,equates,text,data);
            }
            if (in.read() != -1) throw new IOException("Object contains trailing data.");
            validateCode(code,defs,pubs,exts,entry);
            return new VulcanObject(name, source, defs, pubs, exts, entry.isEmpty() ? null : entry, relocs, text, data,code);
        }
    }

    private static void validateCode(Assembler.ObjectCode code,List<String> defs,List<String> pubs,List<String> exts,String entry)throws IOException{
        Set<String> names=new LinkedHashSet<>();int text=0,data=0;
        for(var atom:code.atoms()){
            int expected=atom.section().equals(".text")?text:data;byte[] bytes=atom.bytes();
            if(atom.offset()!=expected)throw new IOException("Object section offsets are inconsistent.");
            if(atom.origin()&&(bytes.length!=0||!atom.mnemonic().isEmpty()))throw new IOException("Invalid origin record.");
            if(!atom.mnemonic().isEmpty()){
                int opcode=Intel8080.assembler.OpcodeTable.OPCODES.geto(atom.mnemonic());
                if(opcode<0||bytes.length!=Intel8080.assembler.OpcodeTable.OPCODES.gets(opcode)||(bytes[0]&255)!=opcode)throw new IOException("Invalid object instruction bytes.");
            }
            for(String name:atom.labels())if(!name.matches("[A-Za-z_$][A-Za-z_$0-9]*")||!names.add(name))throw new IOException("Invalid or duplicate object symbol "+name);
            if(atom.section().equals(".text"))text+=bytes.length;else data+=bytes.length;
        }
        for(var equate:code.equates())if(!equate.name().matches("[A-Za-z_$][A-Za-z_$0-9]*")||!names.add(equate.name()))throw new IOException("Invalid or duplicate equate.");
        if(text!=code.textSize()||data!=code.dataSize()||defs.size()!=names.size()||!names.equals(new LinkedHashSet<>(defs)))throw new IOException("Object symbol/section metadata is inconsistent.");
        if(!names.containsAll(pubs)||!entry.isEmpty()&&!names.contains(entry))throw new IOException("Undefined public or entry symbol.");
        if(new LinkedHashSet<>(pubs).size()!=pubs.size()||new LinkedHashSet<>(exts).size()!=exts.size())throw new IOException("Duplicate symbol metadata.");
        for(String name:exts)if(!name.matches("[A-Za-z_$][A-Za-z_$0-9]*"))throw new IOException("Invalid external symbol.");
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size()); for (String value : values) writeString(out, value);
    }
    private static int readCount(DataInputStream in)throws IOException{int count=in.readInt();if(count<0||count>1_000_000)throw new IOException("Invalid object record count.");return count;}
    private static List<String> readStrings(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > 1_000_000) throw new IOException("Invalid symbol count.");
        List<String> values = new ArrayList<>(count); for (int i = 0; i < count; i++) values.add(readString(in)); return values;
    }
    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt(); if (length < 0 || length > MAX_SOURCE_BYTES) throw new IOException("Invalid object string length.");
        byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new IOException("Truncated object string.");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
