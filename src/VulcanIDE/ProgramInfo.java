package VulcanIDE;

import java.util.Collections;
import java.util.Map;

/** Loaded program layout with source-line and address maps; -1 marks an absent range. */
public record ProgramInfo(
        long start,
        long end,
        long codeStart,
        long codeEnd,
        long dataStart,
        long dataEnd,
        Map<Long, Integer> addressToLine,
        Map<Integer, Long> lineToAddress,
        boolean present
) {

    /** Creates program info with explicit code and data ranges. */
    public ProgramInfo(long start,long end,long codeStart,long codeEnd,long dataStart,long dataEnd,
                       Map<Long,Integer> addressToLine,Map<Integer,Long> lineToAddress) {
        this(start,end,codeStart,codeEnd,dataStart,dataEnd,addressToLine,lineToAddress,true);
    }

    /** Creates program info whose code range equals the whole program. */
    public ProgramInfo(long start, long end,
                       Map<Long, Integer> addressToLine,
                       Map<Integer, Long> lineToAddress) {
        this(start, end, start, end, -1, -1, addressToLine, lineToAddress);
    }

    /** Returns the info of no loaded program. */
    public static ProgramInfo empty() {
        return new ProgramInfo(
                -1, -1,
                -1, -1,
                -1, -1,
                Collections.emptyMap(),
                Collections.emptyMap(),
                false
        );
    }

    /** Reports whether a program is loaded. */
    public boolean loaded() {
        return present && Long.compareUnsigned(end, start) >= 0;
    }

    /** Reports whether a code range exists. */
    public boolean codeLoaded() {
        return loaded() && Long.compareUnsigned(codeEnd, codeStart) >= 0;
    }

    /** Reports whether a data range exists. */
    public boolean dataLoaded() {
        return dataStart != -1 && Long.compareUnsigned(dataEnd, dataStart) >= 0;
    }
}
