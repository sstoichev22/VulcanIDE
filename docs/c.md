# Vulcan C toolchain

The terminal, Editor Run, Debugger, and Display use the same compiler, object assembler, linker, and executable loader. The wide ISA uses the original Intel 8080 mnemonics; source operands, binary encodings and C assembly interfaces must follow the new widths.

## Commands and build flow

```text
vcc main.c helper.c -o program.exe
vcc -Wall main.c helper.c -o program.exe
vcc main.c -c
vcc main.c -S
vasm main.asm helper.asm
vasm main.asm -o main.o
vld main.o helper.o -o program.exe
vload program.exe
```

-S writes assembly; -c writes one relocatable object per C input. vasm writes one object per assembly input. A single explicit output name with either compile-only mode requires one input. Editor Run and Debug discover sibling .c/.asm providers for external symbols, including transitive dependencies. Use explicit terminal inputs for sources in different directories. Failed builds stop execution and report errors.

`vcc` warnings are disabled by default. Add `-Wall` to print available compiler warnings, including constant integer range overflow, division by zero, invalid shift counts, and unreachable code. Integer overflow is reported as a warning and does not by itself prevent compilation; errors still stop the build.

## Target data model

| Type | Bytes | Representation |
| --- | ---: | --- |
| char / unsigned char | 1 | Unsigned byte |
| signed char | 1 | Two's complement |
| short | 2 | Signed unless unsigned |
| int / enum | 4 | Signed two's complement |
| unsigned int | 4 | Unsigned |
| size_t / uintptr_t | 8 | Unsigned |
| ptrdiff_t / intptr_t | 8 | Signed |
| long / long long | 8 | Signed unless unsigned |
| float | 4 | IEEE 754 binary32 |
| double | 8 | IEEE 754 binary64 |
| Object/function pointer | 8 | Flat unsigned HL address |
| bool | 1 | Zero or one |
| intN / uintN | ceil(N / 8) | Signed / unsigned; N is a positive bit count, rounded up to a whole byte for storage and value semantics |

For example, uint1 occupies 1 byte, uint9 occupies 2 bytes and behaves as a 16-bit unsigned integer, int9 behaves as a 16-bit signed integer, uint17 occupies 3 bytes, and uint256 occupies 32 bytes. Arithmetic, conversions, representable ranges, and overflow warnings use the rounded byte width; unused high bits in the final byte are not masked. Both intN and uintN accept 1 to 2048 bits. Scalars align to the largest power of two no greater than their size, capped at eight bytes. Arrays inherit element alignment and remain contiguous across sparse memory pages. Structs align each field, include interior and trailing padding, and align to their most strictly aligned field. Union fields start at offset zero; union size covers its largest field plus padding.

Four-byte integer addition, subtraction and bitwise operators use full 32-bit registers. Complex operations use CALL to namespace-qualified assembly helpers appended below the user functions. Other integer sizes use byte-addressed objects and shared software routines. Software floating operations execute on the emulated CPU and implement arithmetic, conversions, nearest-even rounding, subnormals, infinities, NaNs, and signed zero. They do not call a host arithmetic shortcut.

## Language features

Declarations retain their target types through parsing, semantic checks, symbol resolution, and code generation. Supported features include:

- Local/global/static objects, extern linkage, static functions, typedefs, enums, prototypes, and tentative global definitions.
- Signed/unsigned conversions, casts, sizeof, arithmetic, bitwise and compound operators, comparisons, short-circuit logic, conditional and comma expressions.
- Physical pointers, dereference, the address-of operator `&` (variables, array elements, `.`/`->` members, `&*p`, functions and whole arrays), pointer-to-array declarators such as `int (*rows)[4]`, indexing, multidimensional arrays, strings, nested structs/unions, . and -> members, aggregate assignment, parameters, returns, and initializer lists.
- Direct and indirect function calls, typed function pointers, recursion, variadic definitions, and default variadic argument promotions.
- if/else, for, while, do/while, switch/case/default, break, continue, and return.
- Object/function macros, recursive expansion suppression, stringification, token pasting, variadic macros, line continuations, comments, nested includes, include guards, conditional directives, undef, error, and pragma.

`asm { ... }` embeds raw Intel 8080 instructions and labels in a function. Source spelling and whitespace are retained, local labels are made unique per block, and label matching preserves case. A `$name` reference resolves to a local label first, then to a file-scope C object; an unresolved name remains an assembler symbol. Bare local names are rewritten only as label definitions and direct branch targets, so a label named `A` cannot rewrite the accumulator operand in `MVI A, 1`. Inline instructions bypass byte-assembly rewriting and are skipped by O1. This escape hatch has no operands or constraints for automatic locals: BC is saved and restored for the C frame pointer, while other registers and flags are treated as clobbered. The author must balance SP, keep direct branches inside the block, and let control return to C normally. Section, storage, and layout directives are rejected.

Quoted includes search the including file's directory, configured include directories, then the translation unit's sibling `include/` directory. System includes search configured directories, that same `include/` directory, then the bundled standard headers. Nested includes keep the original translation unit's `include/` root, so a source under `src/VulcanOS` can include `<vulcan/video.h>` from `src/VulcanOS/include/vulcan/video.h`; standard headers also work from the packaged JAR when Vulcan launches outside the repository.

## Calling convention

C uses eight-byte argument slots, a 64-bit BC frame pointer, and HL for scalar results up through eight bytes. Larger results use a hidden eight-byte output pointer. The startup stub initializes SP to 0x100000000, calls main and executes STOP; a translation unit without main may instead define `_start` (no parameters, returning int or void) as its entry point. See [c-runtime.md](c-runtime.md) for the full ABI and software helper interfaces.

## Objects and execution

VOBJ4 stores section bytes and 1/2/4/8-byte relocations. VEXE2 stores a 64-bit entry and flat load segments. Linking namespaces private symbols and checks exports, unresolved references and overlapping sections. Eight-byte control operands directly reach other sections; code has no bank transitions. Old formats require rebuilding.

## Runtime libraries

Standard library C sources use the same target and ABI. printf accepts integer formats, strings, characters, pointers, and floating values with bounded precision; its variadic slots are eight bytes. memcpy and allocation routines use eight-byte size_t and pointers. putchar writes a byte through OUT 0. The clock peripheral supplies a coherent eight-byte time snapshot; `time()` takes no argument and returns it as milliseconds since the Unix epoch, and `difftime()` converts a difference of two stamps to seconds.

## Limits and checks

The supported language subset excludes bitfields, designated initializers, variable-length arrays, goto, complex types and a complete const-qualified type system. Automatic local frames are bounded to 3900 bytes. Integer suffixes support at most 2048 bits. Division by zero executes STOP; invalid C shift counts and out-of-range floating conversions are not portable. Runtime helpers may produce large assembly programs, so use versioned executables for code exceeding a 64 KiB snapshot.

The build checks cover register and operand widths, byte-addressed memory, high-address linking and execution, file-format round trips, 2000 randomized helper cases, C stack cleanup, wide integers, floating operations, aggregate returns, recursion, function pointers and standard library output.

## Declarations and diagnostics

Include a matching function prototype before calling a function, even when its definition appears later in the same file. A definition declares its own name for recursion. Sibling source files supply linked definitions; they do not supply C declarations. `bool` and `intN`/`uintN` are built-in types and need no typedef header. `true` and `false` are built-in constants (1 and 0); there is no `<stdbool.h>`. Include `<stdint.h>` for `intptr_t` and `uintptr_t`, and `<stddef.h>` for `size_t`. Source diagnostics preserve file, line and column. Conflicting declarations show the expected and presented signatures. Implicit floating-to-integer assignment is rejected; use an explicit cast to request truncation.

Every non-void function, including main, must explicitly return a value on every path that reaches its end. Intentional infinite loops can avoid reaching the end. A bare return is valid only in a void function. No implicit zero return is generated. `uint8 *a, b;` declares pointer a and scalar b; write `uint8 *a, *b;` for two pointers. A typedef name is visible after its declarator: `typedef struct { Block *next; } Block;` is invalid unless Block was already declared, while `typedef struct Block { struct Block *next; } Block;` uses a valid visible struct tag.

`#include "implementation.c"` is a textual include: declarations and definitions appear exactly where that directive is written. It does not make a separate linker input necessary; do not also link the included implementation as another translation unit.

See [wide-isa.md](wide-isa.md) for the machine's full register, memory and instruction semantics.
