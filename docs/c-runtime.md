# C runtime and calling convention

The target uses 32-bit A/B/C/D/E/H/L registers, 64-bit BC/DE/HL pairs and PC/SP, and one-byte memory locations. See [wide-isa.md](wide-isa.md) for instruction encodings.

C retains byte-addressed objects: char is one byte, short two, int four, long eight, float four and double eight. Object and function pointers, size_t, ptrdiff_t, intptr_t and uintptr_t are eight bytes. Scalar alignment is capped at eight bytes. intN/uintN support 1–2048 bits, rounded up to whole bytes for storage and value semantics. Arithmetic, conversions, representable ranges, and overflow diagnostics use that rounded width: uint9 behaves as uint16, and int9 as int16. Structs, arrays and pointer offsets use these sizes.

Function arguments are pushed right to left into slots rounded up to multiples of eight bytes, with a minimum slot size of eight. At entry, SP points to the eight-byte return address. A function preserves BC, its frame pointer. The usual prologue pushes BC and sets BC to SP+16, so parameter zero is at BC+0. Local objects occupy negative offsets below BC-16. The caller releases argument slots after return.

Scalar results up to eight bytes return in HL, with the low 32 bits mirrored in A. Larger scalars and aggregates use a hidden eight-byte destination pointer as the first argument. A, flags, DE and HL are caller-saved. Assembly routines linked with C must follow this convention; older assembly calling conventions have no automatic adapters.

Four-byte integer addition, subtraction and bitwise operations use full register instructions. Multiplication and division use ordinary CALL instructions to assembly routines appended below the user's functions. The namespace-qualified helpers end in mul32, udiv32, sdiv32, umod32 or smod32: B is the left operand, C the right, and A the result. The caller saves its BC frame pointer around this register ABI. These routines use shift/add or restoring division; the emulator has no multiply/divide shortcut. Division by zero executes STOP.

Other integer widths use callable MULn, UDIVMODn, SDIVMODn, SHLn, SHRn and SARn routines, generated only when needed. Binary32 arithmetic and comparisons use register helpers with raw operands in B/C and results in A. Conversions choose their actual input/output integer widths. Wider floating operations also use callable routines. Wide helper arguments follow eight-byte stack slots, with a hidden output pointer; division returns contiguous quotient and remainder bytes. Word-sized objects use native 32-bit limb kernels; narrower tails retain byte kernels. These helpers add no helper-specific instructions: they use the documented Intel mnemonics and Vulcan extensions listed in [wide-isa.md](wide-isa.md).

Each translation unit emits its required helper dependency closure once under a private namespace, including widths such as 24, 256 and 2048. The generated helpers share the same backend and ABI as the user program, so their frame sizes, pointers and operand encodings cannot remain on an older target. The standard C libraries are compiled under this convention, including printf's eight-byte variadic slots. putchar and the clock leaf routine use matching assembly interfaces.

Build verification covers the ISA widths and memory transfers, flat addresses above 4 GiB and above the signed long boundary, object/executable round trips, 2000 randomized register-helper cases, C arithmetic and stack cleanup, wider integers, floating arithmetic, recursion, function pointers, struct returns and standard library output.

See [register-float.md](register-float.md) for the register helper interfaces, IEEE behavior and assembly-size comparisons.

