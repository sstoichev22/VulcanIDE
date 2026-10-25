# Assembly toolchain

Vulcan assembles the documented Intel 8080 mnemonics plus STOP and the fused `LDBC`/`STBC`, `LDBC32`/`STBC32`, and `ADDBC32`/`SUBBC32` instructions. `RLC`, `RRC`, `RAL`, and `RAR` each take an unsigned 8-bit rotate count. Individual registers are 32 bits; register pairs, PC, and SP are 64 bits. BANK and the removed width/prefix extensions are not part of the current ISA.

See [wide-isa.md](wide-isa.md) for all instruction sizes, memory-transfer rules, flags, directives, examples and migration notes. DB emits bytes, DW words, DD double words and DQ quad words. Memory remains byte-addressed. The flat linker resolves eight-byte address operands and relocations directly; no bank bridges are inserted.

Terminal commands remain vasm, vld and vload. vasm produces VOBJ4 objects; vld produces VEXE2 executables. Old binary/object formats require rebuilding. A raw memory snapshot is limited to 64 KiB; versioned executable segments support high addresses and larger code.

Assembly routines called from C must follow the eight-byte stack-slot and HL-result convention in [c-runtime.md](c-runtime.md).
