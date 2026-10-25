# Vulcan Project Coding Practices

This guide sets a recommended quality bar for new code and substantial changes in VulcanIDE, its compiler, the Intel 8080 runtime, and VulcanOS. It is language-aware: Java, C, and Intel 8080 assembly have different rules, while keeping the same goals of clarity, predictable behavior, and verifiable changes. Use it for new and substantially changed code even when nearby legacy code follows another pattern; preserve existing behavior and interfaces unless their migration is part of the change. It is forward-looking and does not imply that every existing file already follows it.

## What belongs where?

- Put editor, application-window, settings, and project-workflow behavior in `src/VulcanIDE/`.
- Put instruction execution, memory, ports, and machine-state behavior in `src/Intel8080/`.
- Put C parsing, code generation, optimization, linking, and language support in `src/C8080/`.
- Put operating-system code and its headers in `src/VulcanOS/`. Public VulcanOS headers belong under `src/VulcanOS/include/vulcan/`; keep private implementation details beside the implementation that owns them.
- Put reusable C headers and libraries in `src/stdlib/include/` and `src/stdlib/lib/` respectively.
- Keep checked-in C and header examples under `src/programs/c/`, and hand-authored assembly examples under `src/programs/asm/`. Put generated assembly, object files, executables, and temporary build output in a build or project-output directory rather than beside source inputs.
- Put tests in `test/`, grouped by the behavior they verify. Keep generated binaries, class files, and temporary build output out of source directories.

Dependencies should point toward the code that owns a behavior. UI code may call compiler and runtime APIs; compiler internals should not depend on Swing; the CPU core should not depend on the IDE. Put shared contracts at a narrow boundary, and avoid duplicating one rule in multiple layers.

## How should code be documented?

Use UTF-8 text and Markdown for project documentation. Give a document a title, state its purpose near the start, and organize it around questions a maintainer needs answered. Use fenced code blocks for code and tables only when they improve comparison. Keep examples small enough to verify against the real API.

Document public types and methods with JavaDoc or an equivalent language-appropriate contract. A function or method description should say what observable behavior it provides, not paraphrase its name. Include the details that cannot be inferred safely from the signature:

- Meaning, units, valid range, and ownership of each parameter.
- Return value and any state, memory, files, ports, or UI components changed.
- Errors, boundary cases, and whether the caller must handle failure.
- Thread or interrupt requirements, where relevant.
- Addressing, byte order, register use, and timing assumptions for machine-level code.

Do not write line comments. Use concise doc comments (`/** ... */`) on classes, public methods, and C header declarations, and nothing else: no `//` comments, no trailing `/* */` remarks, and no `;` comments in assembly. When code needs an explanation, prefer a precise name, a small helper, or a doc comment on the enclosing type or function. Rewrite a doc comment whenever the behavior it describes changes.

For assembly, put the calling convention, register preservation, stack effects, and ports of an externally visible routine in the documentation of the C header or Markdown page that declares it. For C headers, document contracts at the declaration with `/** */` so editor hover shows them. For Java, document public API and non-obvious lifecycle or threading rules on the class or method.

Start a contract with the observable behavior, then state only the details a caller cannot infer from the signature. Use the language's normal documentation form and put the contract at the public boundary. For example, a Java method can document range, ownership, and failure conditions with standard JavaDoc tags:

```java
interface MemoryView {
    /** Returns an independent byte snapshot; unallocated memory reads as zero.
     * @param start first byte address, interpreted as an unsigned 64-bit value
     * @param length number of bytes, from 0 through 256 MiB
     * @return a new array containing the requested bytes
     * @throws IllegalArgumentException if the length is invalid or a non-empty range wraps
     */
    byte[] readRange(long start, int length);
}
```

A C header should give callers the same contract they need to use the function safely:

```c
/** Write one palette index at (x, y) in the 1280x720 row-major framebuffer.
    Coordinates outside that range leave memory unchanged. */
void graphics_pixel(uint16 x, uint16 y, uint8 color);
```

An assembly routine called from C is declared in a header, and that declaration carries its contract:

```c
/** Return the next queued keyboard byte (0..255), or -1 when the queue is empty.
    Follows the C ABI: result in HL with the low 32 bits in A; BC is preserved. */
int keyboard_getchar(void);
```

Keep examples accurate to the real API and small enough to verify.

## How should functions and methods be designed?

Give each function one coherent job and make its inputs and effects visible. Keep the normal path easy to read from top to bottom. Split a function when a named subtask, separate validation boundary, or independently testable behavior makes the result clearer; do not split merely to reach a line-count target.

Prefer explicit parameters and return values over hidden global state. Validate external or user-controlled input at the boundary where it enters the system. Preserve existing error information when passing failures upward. Avoid silently converting an invalid address, size, instruction, or file into a plausible success value.

Keep loops bounded when processing files, memory, user input, or emulated instructions. Check arithmetic before it can overflow an address or buffer length. Use the narrowest correct type, and state units in names such as `byteCount`, `address`, or `cycleCount` when ambiguity is possible.

## How should variables and constants be named?

Choose names that express the value's role, not just its type. Use consistent casing within each language: Java types use `UpperCamelCase`, Java methods and variables use `lowerCamelCase`, C functions and variables use the surrounding C convention, and assembly labels use the established assembler convention. Keep acronyms readable and consistent.

Prefer named constants for architectural facts, format fields, limits, and repeated values. Place constants near the subsystem that owns their meaning. Use a namespace or subsystem prefix in C headers to avoid collisions. Give bit masks and addresses names that state their purpose and width; do not duplicate a hardware address as unrelated literals across files.

Use immutable values when they should not change. Keep scope narrow, initialize a variable close to its first meaningful use, and avoid reusing one variable for unrelated phases of an algorithm. A boolean should read naturally at the point of use.

## How should functions be declared, defined, and called?

In C, place a function declaration in the owning public header when other translation units call it. Put the implementation in one `.c` file. Include that header from the implementation so the compiler checks that the declaration and definition agree. Give file-private functions `static` linkage. Include guards must be unique and stable. Callers include the public header instead of copying prototypes.

Keep declarations, definitions, and calls consistent in parameter order, pointer constness, signedness, and return type. Use `const` when a function promises not to modify data through a pointer. Make ownership clear: say who allocates and releases memory, and whether a returned pointer remains valid after the call.

For generated 8080 code and hand-written assembly, treat the calling convention as an interface. Document argument locations, return-value locations, clobbered and preserved registers, stack cleanup, and interrupt assumptions. Every call must leave the stack balanced and preserve the registers required by the convention. Verify generated code against the convention at both call sites and function entries; changing one side alone is an ABI break.

In Java, expose the smallest useful visibility. Keep method contracts stable when multiple panels or services rely on them. Prefer a return value or a small immutable result object over a sequence of loosely related mutations.

## How should classes and modules be structured?

Give a class one clear responsibility and keep the state it owns beside the methods that maintain its invariants. Group fields first, then constructors and lifecycle methods, then public behavior, then private helpers. Keep constructors predictable: validate and establish state, but avoid starting background work before the object is ready to be observed.

Use records or immutable value objects for data passed between layers. Keep UI event handling on Swing's event-dispatch thread; move compilation, file scanning, and long-running emulation off that thread. Publish background results only if they still belong to the current document or run. Cancel obsolete work when practical.

Avoid classes that become catch-all owners of unrelated behavior. When splitting a class, introduce a boundary that makes ownership or testing clearer; do not add wrappers that only rename a call. Keep package-private implementation details private to their subsystem.

## How should errors, memory, and machine state be handled?

State failure clearly and close to its source. In Java, use exceptions for exceptional failures and typed results or explicit status for normal alternatives. Do not catch and ignore an error unless the operation is optional and the reason is clear. In C, return a documented status or value and define how callers obtain error detail. In assembly, define failure flags, result registers, and cleanup effects in the routine contract.

For memory operations, distinguish byte counts from element counts and virtual addresses from host offsets. Check ranges before copying or indexing. Preserve the project's byte order explicitly at serialization, framebuffer, and instruction boundaries. For VRAM, use one documented pixel layout and calculate an address from the framebuffer base, row stride, coordinates, and bytes per pixel.

Treat interrupts, ports, and asynchronous UI updates as shared state. Define who owns a resource, when it can be accessed, and what must be preserved across the operation. Avoid blocking the UI thread or holding a broad lock while calling code that can re-enter the subsystem.

## What tests should accompany a change?

Test behavior at the layer where users observe it, then add a narrower check for the root cause when that makes failures easier to diagnose. Prefer boundary and regression cases over tests that merely repeat implementation details. Cover invalid input, minimum and maximum supported values, cancellation or lifecycle changes, and relevant platform behavior.

Name tests after the behavior they prove. Isolate temporary files and global settings, and close resources in `finally` blocks so failures do not contaminate later checks. Keep correctness checks deterministic; use performance thresholds only for explicit performance checks, with enough tolerance for normal machine variation.

Compiler and runtime changes should include representative source programs and assert both output and machine state where relevant. Assembly and ABI changes should check register, stack, and memory effects. Display changes should verify the bytes written, address calculation, pixel decoding, and rendered result. UI changes should exercise the actual component and input path when feasible.

For a focused change, run its behavior-specific check from the project root in PowerShell, for example: `& (Join-Path (Get-Location) 'build.ps1') -Check CheckName`. Run the full `build.ps1` suite when changes span subsystems, affect shared interfaces, or prepare a release. Verify source and packaged resources when the change touches them. Keep a failing regression in the suite when it captures a fixed bug.

## What should a change review confirm?

Review the caller paths, error handling, resource ownership, platform assumptions, and saved-data compatibility touched by the change. Prefer a small diff that fully fixes the behavior. Avoid adding a dependency for functionality already available in the JDK, compiler, or operating system. Update documentation and tests when an interface or user-visible behavior changes.

