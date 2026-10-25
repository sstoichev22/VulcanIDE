# Compiler and canonical library changes

Request items 7, 10, 14, 15, 16, 19, 23, 24 and 32 are covered by `RequestedCompilerCheck` and `RequestedStdlibCheck`.

Function calls require a declaration visible in source order. A later definition, another source file, or a known runtime symbol cannot implicitly declare a function. Recursion is legal because the definition's signature is visible inside its body. Function pointer references also require a visible function declaration. Statements after a return are checked too. Non-void functions must return explicitly on paths that reach the end; intentional infinite loops remain legal. Type conflicts display expected and presented signatures. C comma declarators preserve their individual pointer operators. The anonymous typedef self-reference fails, while a visible struct tag can refer to itself.

Quoted .c includes are processed as source text, with nested includes relative to that file and original file/line diagnostics. The runtime test includes add.c and executes add(4) == 6.

## O1 constant arithmetic

32-bit and 64-bit constant division/remainder use a reciprocal multiplier. For N-bit unsigned n and positive d, let m=floor(2^N/d), q=floor(n*m/2^N). Since n<2^N, the error is less than one: q equals floor(n/d) or is one smaller. Then r=n-q*d is nonnegative, and a single `r>=d` correction produces the exact quotient and remainder. Products retain 2N bits. A 32x32 product uses native 64-bit pair shifts/adds; 64x64 uses four exact limb products accumulated with carries in a 128-bit temporary. Signed inputs first become unsigned magnitudes, then signs are restored so division truncates toward zero and the remainder has the numerator's sign. Powers of two use shifts and masks. Zero divisors keep the existing stop behavior. Integer overflow retains the target's established wrapping behavior.

This is a conservative reciprocal reduction variant of the invariant-division technique explained in [Labor of Division](https://ridiculousfish.com/blog/posts/labor-of-division-episode-iii.html) and its [reference implementation](https://github.com/ridiculousfish/libdivide/blob/master/doc/divide_by_constants_codegen_reference.c). The proof above describes the exact algorithm used here; it does not copy libdivide's generator. Constant multiplication also uses pair arithmetic, and safe +0, -0, |0, ^0, &all-bits and *1 identities preserve evaluation and side effects. O0 and integers wider than 64 bits retain the ordinary arithmetic implementation.

The regression suite compares 780 boundary/random cases against independent BigInteger results and O0. The 64-bit cases compare all 64 result bits. Cases cover signed and unsigned zero, extrema, 1, -1, powers of two, 3, 7, 10 and negative divisors, plus multiplication, single numerator evaluation, stack balance and zero-divisor trapping.

Measured whole-program instruction counts for x=123456789: unsigned int /7 and %7 each fall from 1107 to 1056; unsigned long /7 and %7 each fall from 60962 to 10836. These counts include generated prologue, loads and return overhead; optimization speed depends on the divisor and operand width.

## Additional O1 size and loop optimizations

These local passes follow the constant/copy propagation, induction-variable, store-merging, and constant-merging families listed in the [GCC optimization options](https://gcc.gnu.org/onlinedocs/gcc/Optimize-Options.html), adapted to this compiler's direct syntax-tree lowering.

O1 narrows a `for` loop's signed 32-bit induction variable when it starts from a constant, compares that same variable with a constant using `<` or `<=`, and increments it by one. It uses an 8-bit counter for up to 256 iterations and a 16-bit counter for up to 65,536 iterations; a descending counter handles the exact power-of-two range without needing a wider terminal value. Reads in the body are reconstructed from the narrow counter and a constant bias. Loops that assign to the counter, take its address, use a volatile counter, or fall outside those bounds retain their existing lowering. The regression covers the 255/256/257 and 65,535/65,536 iteration boundaries, negative and nonzero starts, `break`, `continue`, writes, and address escape. The focused 256-iteration empty-loop fixture falls from 510 to 181 bytes and from 534,474 to 46,612 guest cycles.

O1 batches local constant array and aggregate initialization into one frame-address setup followed by the encoded bytes. It keeps the ordinary path for volatile objects, pointer fields, nonconstant expressions, and aggregates larger than 65,536 bytes. The array and nested-struct fixture falls from 2,951 to 2,833 bytes and from 31,185 to 30,809 guest cycles.

O1 also folds top-level constant unary, cast, and conditional expressions through the existing side-effect-free constant evaluator. A fixture combining negation, complement, cast, and a constant conditional falls from 2,006 to 1,518 bytes and from 13,770 to 11,121 guest cycles. The pass leaves nonconstant expressions on the normal code-generation path.

O1 merges identical string literals within a translation unit, removes constant shifts by zero, and reduces integer division or remainder by ±1 to copies, negation, or zero while retaining evaluation of the numerator. Other O1 paths already in the generator include constant folding, multiplication/division strength reduction, zero and all-ones bitwise identities, and direct fixed-step local updates. These are local lowering passes; GCC's global data-flow, function inlining, vectorization, and loop interchange require whole-function or target-specific analyses that this compiler does not currently have.

## Standard library

`src/stdlib/include` and `src/stdlib/lib` are the canonical source directories. Removed obsolete duplicates: programs/include/{stdbool.h,stdio.h,string.h} and programs/lib/{putchar.asm,stdio.c,stdio.asm,string.c,string.asm}. Compiler, UI/runtime callers, resource packaging, WideBuildTool and documentation use stdlib. VulcanOS heap.h explicitly includes stdbool.h. Generated stdio.asm and string.asm snapshots are removed; their C files are the sole providers. timer.asm implements `time_t time(void)`, which returns the wall clock in milliseconds; time.c builds clock() and difftime() (seconds) on it. timer.asm sits alongside putchar.asm, getchar.asm, abort.asm and exit.asm. The runtime loader reads the same provider names from source paths or packaged resources. WideBuildTool compiles the C examples into builds/programs, never beside the sources or as library snapshots.

Existing implementations cover printf/putchar/puts; memory and string copy, comparison, search, spans and tokenizer; character classification/case conversion; integer parsing, random numbers, allocation, binary search/sort; and clock/time. Additions include:

- `getchar` waits for queued console input through the emulator ports. It is for direct console input; an OS that consumes the same queue in an interrupt handler should provide its own stream adapter.
- `div` and `ldiv` return quotient/remainder aggregates.
- `strtod`, `strtof` and `atof` parse decimal and hexadecimal input, exponents, signs, infinity and NaN, and supply end pointers. They do not implement errno or locale handling. Floating input is accumulated in target arithmetic; these routines do not promise correctly rounded conversion of every decimal string.
- `abort` and `exit` terminate the emulator program; exit supplies the low byte of its status.
- assert.h evaluates enabled assertions once and stops on failure; NDEBUG suppresses evaluation.
- float.h supplies target IEEE binary32/binary64 limits.
- math.h provides fabs/fabsf, copysign/copysignf, fmin/fmax variants, isnan, isinf, isfinite and signbit, with NaN and signed-zero behavior.

Standard library tests compile every canonical C library and execute string search/copy/spans, tokenizer state, overlapping memmove, numeric base parsing, aggregate returns, decimal/hex floating input, IEEE classification/zero/NaN, keyboard FIFO, assertion behavior and exit status. This is a supported embedded subset of C, not a hosted filesystem/locale implementation.
