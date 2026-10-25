# Float addition and output comparison

The C example is [float_add_printf.c](examples/float_add_printf.c). It adds two `float` constants and prints the result with the standard `%f` format. The corresponding fixed-input assembly example is [float_add_printf.asm](examples/float_add_printf.asm); it emits the known text `0.600000` through port 0.

| Build | Compiler caller lines | Linked assembly lines | Linked image bytes |
| --- | ---: | ---: | ---: |
| C at `-O0` | 1,189 | 13,721 | 42,554 |
| C at `-O1` | 793 | 13,325 | 41,372 |
| Handwritten fixed-input assembly | 9 instructions | — | 42 |

O1 folds pure constant float arithmetic and comparisons. The sample loses its generated binary32 addition helper, reducing the caller and linked assembly by 396 lines and the linked image by 1,182 bytes. Local float constants are written directly to their stack slots with fused frame stores, avoiding a temporary plus `LOAD32`/`STORE32` helper calls.

The handwritten assembly specializes the two known operands into their final printed text, so it does not perform floating-point addition or call `printf`. It is the shortest implementation for this fixed input, not a replacement for a general program whose operands can change. The C build retains the normal `printf` call: the compiler supports a `printf` implementation supplied by a separate object, and bypassing that symbol would break that behavior.

The x86 listing in the request contains the caller and an unresolved `printf` call. It can use hardware floating-point instructions, while the 8080 target computes floating-point values in software and links its formatter. Those listings therefore do not include comparable runtime work. The side-by-side C and assembly files here make the fixed-input specialization explicit.

`FloatCodeSizeComparisonCheck` executes the C builds at both optimization levels, compares their text and return status with the assembly example, exercises constant add/subtract/multiply/divide/comparison folding, and verifies that a used `printf` return value and a separate-object `printf` override still take the regular call path.
