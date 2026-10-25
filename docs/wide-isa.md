# Wide Intel 8080 target

This revision preserves the documented Intel 8080 mnemonics and opcode bytes, with STOP (FD), LDBC/STBC, LDBC32/STBC32, and ADDBC32/SUBBC32 as Vulcan extensions. BANK, MX, INX32 and PCHL32 are removed. Existing binaries and older objects must be rebuilt.

| State | Width | Representation |
| --- | ---: | --- |
| A, B, C, D, E, H, L | 32 bits | Java int; arithmetic wraps modulo 2^32 |
| BC, DE, HL | 64 bits | High register supplies bits 63–32; low register supplies bits 31–0 |
| PC, SP | 64 bits | Java long carries all unsigned address bits |
| One memory location | 8 bits | Sparse flat memory; only touched pages allocate storage |

ADD B adds the complete 32-bit B register to the complete 32-bit accumulator. ADC, SUB, SBB, logical operations, comparisons, increments, decrements and accumulator rotations use the same register width. Zero tests all 32 bits; sign tests bit 31; parity is even parity of all 32 bits. Carry reports unsigned overflow or borrow at bit 32. Auxiliary carry retains the original low-nibble rule. DAD uses 64-bit pair addition and reports carry at bit 64. INX/DCX operate on full pairs or SP. DAA corrects the eight packed BCD nibbles, carrying between nibbles.

M means the single byte at the full HL address. MOV/LDAX/LDA/IN reads zero-extend a byte into a register; MOV/STAX/STA/OUT stores the low byte. Arithmetic with M uses a zero-extended byte. MVI M consumes a 32-bit immediate and stores its low byte. INR/DCR M use the ALU result flags before truncating the stored byte.

`LDBC offset` and `STBC offset` add a signed 32-bit immediate to the full 64-bit BC address, wrapping modulo 2^64, then load one byte into A or store A's low byte. They preserve BC and every flag; LDBC replaces A and STBC leaves A unchanged. Each costs 11 guest cycles, accounting for the displacement fetch, address calculation, and byte access.

`LDBC32 offset` and `STBC32 offset` use the same address rule for four little-endian bytes. LDBC32 replaces A with the signed 32-bit value represented by those bytes; STBC32 stores A's low 32 bits. Both preserve BC and every flag. Each costs 32 guest cycles: 11 for the first byte and seven more for each additional byte.

`RLC count`, `RRC count`, `RAL count`, and `RAR count` fetch an unsigned 8-bit count and repeat the corresponding 32-bit accumulator rotate that many times. A zero count leaves A and the carry flag unchanged. The other flags remain unchanged for every count. Each instruction costs seven guest cycles plus one cycle per requested rotation step, so counts from 0 through 255 cost 7 through 262 cycles.

`ADDBC32 offset` and `SUBBC32 offset` add or subtract A from the four-byte little-endian value at BC plus the signed offset, wrapping modulo 2^32. They store and return the result in A, update the same arithmetic flags as the corresponding ALU operation, and preserve BC. Each costs 53 guest cycles, covering the displacement, four-byte read-modify-write, and 32-bit arithmetic.

SHLD/LHLD transfer all eight little-endian bytes of HL. PUSH/POP and XTHL transfer eight bytes. PSW places the accumulator in the upper 32 bits and the original flag byte in the lower 32 bits, with the unused flag bits zero. CALL, conditional calls, RST and accepted interrupts push an eight-byte return address. RET and conditional returns pop it. PCHL and SPHL copy the full HL pair. RST n jumps to n × 0x10: 0x00, 0x10, …, 0x70. Each sixteen-byte vector slot fits one nine-byte JMP. Hardware keyboard interrupts enter RST 1 at 0x10, mouse-button presses enter RST 2 at 0x20; `triggerInterrupt` accepts an absolute address.

| Encoding | Bytes |
| --- | ---: |
| Register-only instruction / STOP | 1 |
| IN / OUT, including port byte | 2 |
| MVI / immediate ALU, including four-byte immediate | 5 |
| LDBC / STBC with signed BC-relative displacement | 5 |
| LDBC32 / STBC32 with signed BC-relative displacement | 5 |
| ADDBC32 / SUBBC32 with signed BC-relative displacement | 5 |
| LXI / direct memory / jump / call, including eight-byte operand | 9 |

Operands are little-endian. DB, DW, DD and DQ emit 1, 2, 4 and 8 bytes per numeric value. DB also accepts strings. DS reserves a byte count; ORG selects a flat 64-bit address. All hexadecimal address bits are accepted, including addresses above 0x7FFFFFFFFFFFFFFF. Sections and relocations use the same sizes as the CPU decoder and disassembler. The legacy Assemble snapshot API returns a 64 KiB image; larger or high-address programs use assembleFlat or the executable linker.

```asm
global start
.text
start:
    LXI SP, 0x100000000
    MVI A, 70000
    MVI B, 30000
    ADD B
    STOP
.data
answer: DD 100000
pointer: DQ answer
```

The versioned formats are VOBJ4 and VEXE2. Executable entries and segment addresses occupy eight bytes; relocations may occupy 1, 2, 4 or 8 bytes. Old versions are rejected instead of executing their incompatible operands. A segment may span storage pages; pages have no ISA-visible bank semantics.

The default reset stack and compiled C initialize SP to 0x0000000100000000. The preferred 4 KiB stack occupies 0x00000000FFFFF000 through 0x00000000FFFFFFFF. The standard allocator reserves a separate 32 KiB arena at 0x0000010000000000 through 0x0000010000007FFF. The VulcanOS kernel gives its heap (heap_init/kmalloc) a separate 2 MiB arena at 0x0000000000200000 through 0x00000000003FFFFF; its cursor and size arguments are 64 bits. SP can be set to any address and wraps at 64 bits; the application no longer imposes the old bank-zero stack bounds. The compiler still bounds each generated local frame to 3900 bytes.

Memory is flat RAM plus a configurable VRAM range beginning at `0xF0000000`, with 5 MiB reserved by default. Pages allocate when touched; unused VRAM consumes no backing array. The old program/RAM/video partitions do not restrict addressing. `0x0100` (code) and `0x8000` (data) remain default assembler placement conventions; stack and allocator addresses below are software conventions in RAM. Set `vramBytes` in settings.json or use Display's VRAM MiB control (1 KiB through 256 MiB). Display presets select any base address. Presets are stored as individual .preset files and renaming a preset renames its file. Width and height each range from 1 through 65535 (uint16 maximum). The vos preset uses 1280 by 720, little-endian 16-bit RGB565 at Memory.VRAM_START (red 11:5, green 5:6, blue 0:5).

Display pixels use one byte for 1/2/4/8-bit palette formats, two for 16-bit RGB565, three for 24-bit RGB and four for 32-bit RGB (unused fourth byte). Multi-byte pixels are little endian. Sequential RGB maps bits 0–7 to red, 8–15 to green and 16–23 to blue; selected RGB bits use independent `offset:width` ranges, scaled to 0–255. These settings persist in each preset. The stock `vos` preset is 1280x720 with 8-bit palette-indexed pixels at 0xF0000000: index 0x00 is black, 0xE0 red, and 0xFF white. The display can detach into branded window chrome; the image preserves its aspect ratio with black letterboxing. Detached editor tabs accept up to four split panes by dropping on an edge.

Each editor and terminal runner owns its CPU/memory and keyboard queue. The editor run bar retains the active filename across tab changes, with per-program rerun/stop controls. Debugger and display retain their shared inspection session. Builds run off the Swing thread with transient compiling/assembling status; program output replaces that status. File create/rename/delete notices animate above the content without changing split-panel geometry.
