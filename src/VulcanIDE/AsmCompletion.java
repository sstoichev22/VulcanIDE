package VulcanIDE;

import Intel8080.assembler.OpcodeTable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/** Assembly completion and hover text for Vulcan mnemonics, registers, labels, and equates. */
public final class AsmCompletion {
    private static final List<String> REGISTERS = List.of("A", "B", "C", "D", "E", "H", "L", "M", "SP", "PSW");
    private AsmCompletion() {}

    /** Returns the hover/completion description of an instruction, register, or directive. */
    public static String description(String word) {
        String w=word.toUpperCase(Locale.ROOT);
        return switch(w) {
            case "MOV" -> "dst, src | copy a register or memory value";
            case "MVI" -> "register, imm32 | load a 32-bit constant";
            case "LXI" -> "pair, imm64 | load a register pair or stack pointer";
            case "LDA", "STA" -> "address | "+(w.equals("LDA")?"load accumulator from":"store accumulator at")+" memory";
            case "LHLD", "SHLD" -> "address | "+(w.equals("LHLD")?"load":"store")+" HL pair in memory";
            case "LDBC" -> "signed 32-bit offset | load one byte from BC + offset into A; preserve BC and flags";
            case "STBC" -> "signed 32-bit offset | store A's low byte at BC + offset; preserve BC and flags";
            case "LDBC32" -> "signed 32-bit offset | load four little-endian bytes from BC + offset into A; preserve BC and flags";
            case "STBC32" -> "signed 32-bit offset | store A's low 32 bits as four little-endian bytes at BC + offset; preserve BC and flags";
            case "ADDBC32" -> "signed 32-bit offset | add A to the four-byte value at BC + offset; store result in A and update flags";
            case "SUBBC32" -> "signed 32-bit offset | subtract A from the four-byte value at BC + offset; store result in A and update flags";
            case "LDAX", "STAX" -> "pair | "+(w.equals("LDAX")?"load":"store")+" accumulator through pair address";
            case "INX", "DCX" -> "pair | "+(w.equals("INX")?"increment":"decrement")+" register pair";
            case "INR", "DCR" -> "register | "+(w.equals("INR")?"increment":"decrement")+" value; update flags";
            case "ADD", "ADC", "ADI", "ACI" -> (w.endsWith("I")?"imm32":"register")+" | add to A"+(w.startsWith("AC")?" including carry":"; update flags");
            case "SUB", "SBB", "SUI", "SBI" -> (w.endsWith("I")?"imm32":"register")+" | subtract from A"+(w.startsWith("SB")?" including borrow":"; update flags");
            case "ANA", "ANI" -> (w.endsWith("I")?"imm32":"register")+" | bitwise AND with A";
            case "XRA", "XRI" -> (w.endsWith("I")?"imm32":"register")+" | bitwise XOR with A";
            case "ORA", "ORI" -> (w.endsWith("I")?"imm32":"register")+" | bitwise OR with A";
            case "CMP", "CPI" -> (w.endsWith("I")?"imm32":"register")+" | compare with A; set flags without storing";
            case "DAD" -> "pair | add register pair to HL; update carry";
            case "RLC", "RRC" -> "imm8 | rotate 32-bit A "+(w.equals("RLC")?"left":"right")+" circularly; carry is the last shifted-out bit";
            case "RAL", "RAR" -> "imm8 | rotate 32-bit A "+(w.equals("RAL")?"left":"right")+" through carry";
            case "DAA" -> "decimal-adjust A after BCD addition";
            case "CMA" -> "invert all accumulator bits";
            case "STC", "CMC" -> w.equals("STC")?"set carry flag":"complement carry flag";
            case "PUSH", "POP" -> "pair / PSW | "+(w.equals("PUSH")?"save to":"restore from")+" stack";
            case "XCHG" -> "exchange DE and HL pairs";
            case "XTHL" -> "exchange HL with the top of stack";
            case "SPHL" -> "copy HL into the stack pointer";
            case "PCHL" -> "jump to the address held in HL";
            case "RST" -> "vector 0..7 | call fixed restart vector (vector × 16; addresses 0x00, 0x10, …, 0x70)";
            case "IN", "OUT" -> "port | "+(w.equals("IN")?"read input into A":"write A to output") ;
            case "EI", "DI" -> (w.equals("EI")?"enable":"disable")+" maskable interrupts";
            case "HLT" -> "halt execution until an accepted interrupt";
            case "STOP" -> "stop the emulator program";
            case "NOP" -> "advance without changing registers or flags";
            case "ORG" -> "address | set assembly location";
            case "EQU" -> "expression | define a symbolic constant";
            case "DB", "DW", "DD", "DQ" -> "values | emit "+switch(w){case "DB"->"8";case "DW"->"16";case "DD"->"32";default->"64";}+"-bit data";
            case "DS" -> "count | reserve storage bytes";
            case "END" -> "end assembly source";
            case "EXTERN" -> "symbol | declare an external symbol";
            case "PUBLIC", "GLOBAL" -> "symbol | export a symbol";
            default -> {
                if(REGISTERS.contains(w))yield w.equals("M")?"memory at HL":"register operand";
                if(w.startsWith("J"))yield "address | jump"+condition(w.substring(1));
                if(w.startsWith("C"))yield "address | call; push return address"+condition(w.substring(1));
                if(w.equals("RET")||w.startsWith("R"))yield "return through stack"+(w.equals("RET")?"":condition(w.substring(1)));
                yield "source label or equate";
            }
        };
    }
    static String description(String word,String sourceLine) {
        String mnemonic=word.toUpperCase(Locale.ROOT);
        int comment=sourceLine.indexOf(';');
        String code=(comment<0?sourceLine:sourceLine.substring(0,comment)).trim();
        int colon=code.indexOf(':');
        if(colon>=0)code=code.substring(colon+1).trim();
        String[] parts=code.split("\\s+",2);
        if(parts.length<2||!parts[0].equalsIgnoreCase(mnemonic))return description(word);
        String[] operands=parts[1].trim().split("\\s*,\\s*",-1);
        if(operands.length==2){
            String destination=operands[0].toUpperCase(Locale.ROOT),source=operands[1];
            String value=source.equalsIgnoreCase("M")?"[HL] (zero-extended byte)":source;
            if(mnemonic.equals("MOV")){
                if(destination.equals("M"))return "[HL] = "+source+" (low byte)";
                return destination+" = "+value+(source.equalsIgnoreCase("M")?"":" (32-bit copy)");
            }
            if(mnemonic.equals("MVI"))return destination.equals("M")
                    ?"[HL] = "+source+" (low byte of 32-bit immediate)"
                    :destination+" = "+source+" (32-bit immediate)";
            if(mnemonic.equals("LXI"))return pairName(destination)+" = "+source+" (64-bit immediate)";
            if(mnemonic.equals("ADD")||mnemonic.equals("ADC"))return "A = A + "+value+(mnemonic.equals("ADC")?" + carry":"")+"; update flags";
            if(mnemonic.equals("SUB")||mnemonic.equals("SBB"))return "A = A - "+value+(mnemonic.equals("SBB")?" - borrow":"")+"; update flags";
            if(mnemonic.equals("CMP"))return "Compare A with "+value+"; set flags without changing A";
        }
        if(operands.length==1){
            String address=operands[0],register=address.toUpperCase(Locale.ROOT);
            if(mnemonic.equals("LDA"))return "A = ["+address+"] (zero-extended byte)";
            if(mnemonic.equals("STA"))return "["+address+"] = A (low byte)";
            if(mnemonic.equals("LHLD"))return "HL = 64-bit value at ["+address+"]";
            if(mnemonic.equals("SHLD"))return "["+address+"] = HL (64-bit)";
            if(mnemonic.equals("LDAX"))return "A = ["+pairName(register)+"] (zero-extended byte)";
            if(mnemonic.equals("STAX"))return "["+pairName(register)+"] = A (low byte)";
            if(mnemonic.equals("DAD"))return "HL = HL + "+pairName(register)+"; carry reports overflow";
            if(mnemonic.equals("INX"))return pairName(register)+" = "+pairName(register)+" + 1";
            if(mnemonic.equals("DCX"))return pairName(register)+" = "+pairName(register)+" - 1";
            if(mnemonic.equals("INR"))return register.equals("M")
                    ?"Increment the byte at [HL], store its low byte, and update flags"
                    :register+" = "+register+" + 1; update flags";
            if(mnemonic.equals("DCR"))return register.equals("M")
                    ?"Decrement the byte at [HL], store its low byte, and update flags"
                    :register+" = "+register+" - 1; update flags";
            if(mnemonic.equals("ADI"))return "A = A + "+address+"; update flags";
            if(mnemonic.equals("ACI"))return "A = A + "+address+" + carry; update flags";
            if(mnemonic.equals("SUI"))return "A = A - "+address+"; update flags";
            if(mnemonic.equals("SBI"))return "A = A - "+address+" - borrow; update flags";
            if(mnemonic.equals("CPI"))return "Compare A with "+address+"; set flags without changing A";
            if(mnemonic.equals("PUSH"))return "Push "+(register.equals("PSW")?"A and flags":pairName(register))+" onto the stack";
            if(mnemonic.equals("POP"))return "Pop stack value into "+(register.equals("PSW")?"A and flags":pairName(register));
            if(mnemonic.equals("JMP")||Set.of("JNZ","JZ","JNC","JC","JPO","JPE","JP","JM").contains(mnemonic))
                return "Jump to "+address+condition(mnemonic.equals("JMP")?"MP":mnemonic.substring(1));
            if(mnemonic.equals("CALL")||Set.of("CNZ","CZ","CNC","CC","CPO","CPE","CP","CM").contains(mnemonic))
                return "Call "+address+condition(mnemonic.equals("CALL")?"MP":mnemonic.substring(1))+"; push return address";
            if(mnemonic.equals("RST"))return "Call restart vector "+address+"; push return address";
        }
        return description(word);
    }
    private static String pairName(String operand){return switch(operand){case "B"->"BC";case "D"->"DE";case "H"->"HL";default->operand;};}
    private static String condition(String suffix) {return switch(suffix){case "MP", "ALL"->"";case "NZ"->" if not zero";case "Z"->" if zero";case "NC"->" if no carry";case "C"->" if carry";case "PO"->" if odd parity";case "PE"->" if even parity";case "P"->" if positive";case "M"->" if negative";default->"";};}

    /** Returns every mnemonic plus the labels and equates defined in source. */
    public static Set<String> words(String source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(List.of("ORG","EQU","DB","DW","DD","DQ","DS","END","EXTERN","PUBLIC","GLOBAL"));
        for (String mnemonic : OpcodeTable.OPCODES.getMnemonics()) {
            int space = mnemonic.indexOf(' ');
            result.add(space < 0 ? mnemonic : mnemonic.substring(0, space));
        }
        for (String line : source.split("\\R", -1)) {
            int comment = line.indexOf(';');
            String trimmed = (comment < 0 ? line : line.substring(0, comment)).trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon > 0) addIdentifier(result, trimmed.substring(0, colon));
            String[] tokens = trimmed.split("\\s+", 3);
            if (tokens.length >= 2 && tokens[1].equalsIgnoreCase("EQU")) addIdentifier(result, tokens[0]);
        }
        return result;
    }

    private static void addIdentifier(Set<String> result, String value) {
        String name = value.trim();
        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) result.add(name);
    }

    /** Returns the identifier fragment ending at the caret, or an empty string. */
    public static String prefixAt(String source, int caret) {
        if (caret <= 0 || caret > source.length() ||
                (caret < source.length() && identifierPart(source.charAt(caret)))) return "";
        int start = caret;
        while (start > 0 && identifierPart(source.charAt(start-1))) start--;
        if (start == caret || (start > 0 && source.charAt(start-1) == '.')) return "";
        int lineStart = source.lastIndexOf('\n', start-1)+1;
        char quote = 0;
        boolean escaped = false;
        for (int i = lineStart; i < start; i++) {
            char current = source.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quote != 0 && current == '\\') { escaped = true; continue; }
            if (quote != 0) { if (current == quote) quote = 0; }
            else if (current == ';') return "";
            else if (current == '\'' || current == '"') quote = current;
        }
        return quote == 0 ? source.substring(start, caret) : "";
    }

    private static boolean identifierPart(char value) { return Character.isLetterOrDigit(value) || value == '_'; }

    /** Returns completion candidates for a prefix, instructions first. */
    public static List<String> suggestions(String source, String prefix) {
        if (prefix.isEmpty()) return List.of();
        Set<String> instructions = words("");
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        String uppercase = prefix.toUpperCase(Locale.ROOT);
        boolean lowercase = Character.isLowerCase(prefix.charAt(0));
        for (String word : instructions) {
            if (word.startsWith(uppercase)) matches.add(lowercase ? word.toLowerCase(Locale.ROOT) : word);
        }
        for (String word : words(source)) {
            if (!instructions.contains(word) && word.startsWith(prefix)) matches.add(word);
        }
        String currentLine = source.substring(source.lastIndexOf('\n') + 1);
        int prefixIndex = currentLine.lastIndexOf(prefix);
        String beforePrefix = prefixIndex < 0 ? "" : currentLine.substring(0, prefixIndex).trim();
        boolean operandContext = !beforePrefix.isEmpty() && (beforePrefix.matches("[A-Za-z]+") || beforePrefix.endsWith(","));
        if (operandContext) for (String register : REGISTERS) {
            if (register.startsWith(uppercase)) matches.add(lowercase ? register.toLowerCase(Locale.ROOT) : register);
        }
        List<String> result = new ArrayList<>(matches);
        result.sort(String::compareTo);
        return result;
    }

    /** Reports whether the caret ends an instruction whose operands are all present. */
    public static boolean isAtCompleteInstructionEnd(String source, int caret) {
        if (caret < 0 || caret > source.length()) return false;
        int lineStart = source.lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
        int lineEnd = source.indexOf('\n', caret);
        if (lineEnd < 0) lineEnd = source.length();
        if (caret != lineEnd) return false;
        String line = source.substring(lineStart, caret);
        int comment = line.indexOf(';');
        line = (comment < 0 ? line : line.substring(0, comment)).trim();
        if (line.isEmpty() || line.contains(":")) return false;
        String[] parts = line.split("\\s+", 2);
        String mnemonic = parts[0].toUpperCase(Locale.ROOT);
        String operands = parts.length == 1 ? "" : parts[1].trim();
        if (operands.isEmpty()) return Set.of("HLT", "NOP", "RET", "DAA", "CMA", "STC", "CMC", "EI", "DI", "STOP").contains(mnemonic);
        boolean twoOperands = Set.of("MOV", "MVI", "LXI").contains(mnemonic);
        boolean comma = operands.indexOf(',') >= 0;
        return twoOperands ? comma && operands.substring(operands.indexOf(',') + 1).trim().length() > 0
                : !comma && !operands.isBlank();
    }
}
