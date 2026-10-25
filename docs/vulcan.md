# Vulcan

Vulcan is an Intel8080-V C and assembly editor, compiler, assembler, linker, debugger, and flat 64-bit memory display.

## Editor

Double-click a file to open it in the main editor's top-left region. Drag tabs to split the editor or detach them. Creating a new file prompts for its name and stores it in the current working directory. Named files autosave as you edit. Right-click a tab for close options. File and window state are saved at application close.

.c and .h files have C highlighting using the same palette as assembly. C/header files pair quotes, braces, parentheses, and brackets; assembly pairs quotes. Typing an existing closer advances the caret. A quote after a backslash is inserted literally. Enter keeps indentation and indents a new brace block. Ctrl+mouse wheel changes the font size and shows a persistent "Font size:" notice until scrolling ends.

Executable tabs show a read-only preview of up to 64 KiB. Running or debugging loads the complete executable.

Tabs use four columns at the current font size, including after zooming. Source editors and the embedded terminal use the configured code font. Program output and the rest of the application use the configured application font; their sizes and emphasis remain independent.

C autocomplete includes visible declarations from the current file and its active includes, structure members, and the standard library. Suggestions for declarations not yet included identify the required header. Accepting a suggestion inserts its name without adding an include.

C and assembly information cards appear after one second and stay while the pointer is on the token or card. They show declaration details, documentation, and links to functions and aggregate definitions. Long struct and union definitions show their first five lines followed by an ellipsis and closing brace. Numeric cards show decimal, binary and hexadecimal; floating-point binary and hexadecimal values are labeled IEEE float32 or double64 storage bits.

Markdown files open as one tab containing an editable source half and a textured live preview half. Move or detach the whole tab; its halves cannot receive separate file tabs.

## Running and debugging

Run compiles the current C or assembly file, links its needed sibling source providers (or, below a `build.run` file, only the files it lists), loads the executable, and starts the CPU. A build failure reports its diagnostic and prevents stale execution. Run reveals and selects Console. STOP terminates execution. HLT finishes an ordinary program with interrupts disabled; enabled interrupts allow it to wait and resume.

`build.run` is a minimal make file: one `.c` or `.asm` path per line, relative to the `build.run` folder, with `#` starting a comment. Creating a `build.run` lists every source in its folder and subfolders; creating a source file anywhere below an existing `build.run` appends it. Running `build.run` (Run button or `build build.run` in the terminal) compiles and links every listed file into one program; the terminal command writes `<folder>.exe` unless `-o` names the output.

Debugger uses the same build path. Step executes a whole instruction. Reset restores the complete physical entry. Disassembly follows full 64-bit execution addresses. Double-click an instruction to toggle its breakpoint. The memory Address field accepts 64-bit addresses such as 0x0000000100000000.

Debug and Assemble load the newly built code. Step also accepts an enabled pending interrupt, including waking the processor from HLT; the next step executes the interrupt handler.

Each running program has its own processor and controls. The running-program selector sits left of the filename and Run button. C files need an active `int main` definition; unrelated syntax errors keep Run available so compilation can report them. Assembly can start at its first instruction without an explicit entry label. Each workspace retains its own project path and output console.

## Terminal

```text
vasm main.asm helper.asm
vld main.o helper.o -o program.exe
vcc main.c helper.c -o program.exe
vcc main.c -c
vcc main.c -S
vload program.exe
```

vasm and vcc -c write relocatable VOBJ4 objects. vcc -S writes assembly. vld and normal vcc builds write segmented VEXE2 executables. vload validates and executes them. Legacy raw low-address executable files remain accepted. help lists commands.

## C data model

int is four bytes, long eight, float four, double eight, and object and function pointers eight. intN/uintN specify N bits, rounded up to whole bytes. Scalar alignment is capped at eight bytes; union fields share offset zero. Four-byte integer addition, subtraction, and bitwise operations use full 32-bit registers. Other integer widths use byte-addressed objects and shared software routines; complex operations use namespace-qualified assembly helpers. Floating-point operations run in software on the emulated CPU.

The bundled C runtime includes stdio, string, ctype, stdlib, time, math, and assert support. The compiler supports arrays, nested aggregates, initialization, prototypes, recursion, function pointers, and normal structured control flow. It is a documented C subset; consult [C documentation](c.md) and the [runtime calling convention](c-runtime.md) for data layout, ABI details, and library limits.

## Input and output

OUT 0 sends an accumulator byte to the program's console. IN 1 bit 0 indicates unread input; IN 0 consumes that byte. Enter in the console sends a complete line ending in LF. Input is queued byte by byte.

The Java display receives mouse-button events and queues RST 2 at 0x20 on the emulated CPU. The CPU accepts a pending interrupt between instructions when interrupts are enabled, including while it waits in HLT. Interrupt entry pushes only the 64-bit return PC and disables interrupts. A handler must preserve every register and flag it changes, then execute `EI` before `RET` if it should allow later interrupts. `EI` becomes active after the following instruction; the VulcanOS default mouse handler uses `EI; RET`. The keyboard uses the same CPU-side path for RST 1 at 0x10 when unread input exists. Polling programs can consume the keyboard FIFO with interrupts disabled. See `src/VulcanOS/README.md` for the kernel vector layout and build command.

Mouse ports are byte-wide: OUT `0x22` selects a snapshot byte, IN `0x23` reads it, and IN `0x24` reads the button paired with that snapshot (`0` none, `1` left, `2` middle, `3` right). OUT command `0` captures normalized binary32 X and Y as eight little-endian bytes: X occupies bytes 0–3 and Y bytes 4–7. Commands `1` through `7` select the corresponding byte. Coordinates range from -1 to +1; X=-1 is left and X=+1 is right, while Y=+1 is top and Y=-1 is bottom. After a button press, the next command-zero snapshot returns the click-time coordinates and button even if the pointer moved or the button was released. Later snapshots report the current pointer and held button. Multiple presses while one mouse interrupt is pending coalesce to the latest click snapshot.

# Keyboard shortcuts

| Shortcut | Action |
| --- | --- |
| Ctrl+N | Create a new file after choosing its name |
| Ctrl+O | Open project directory |
| Ctrl+Z / Ctrl+Y | Undo / Redo in the editor |
| Ctrl+X / Ctrl+C / Ctrl+V | Cut / Copy / Paste |
| Ctrl+A | Select all text |
| Ctrl+F / Ctrl+R | Find / Replace in the current file |
| Ctrl+Shift+F / Ctrl+Shift+R | Find / Replace in the project |
| Ctrl+mouse wheel | Change this editor file's font size |
| Tab / Shift+Tab | Indent / Unindent |
| Up / Down while suggestions appear | Cycle autocomplete choices |
| Enter / Tab while suggestions appear | Insert the highlighted suggestion |
| Escape while suggestions appear | Dismiss autocomplete |
| Enter in console input | Submit a line to the current program |
| Ctrl+click in Files | Add/remove a selected file |
| Shift+click in Files | Select a range |
| Ctrl+C / Ctrl+X / Ctrl+V in Files | Copy / Cut / Paste selected files |
| Ctrl+Z in Files | Undo the last supported file operation |
| Ctrl+D in Files | Duplicate selected files |
| Mouse wheel over editor tabs | Scroll the tab strip |

Use the Run, Stop, Pause, Step, and Reset icons for program controls; hover an icon to see its name.

## File and panel management

File tabs can be split or detached. Dropping into an empty editor uses its full area. Deleting a file closes its open editor without saving it again, and shows a short fading notice. Renaming a display preset changes the preset filename and stored name together. The Files, Editor, Console, Terminal, debugger, and display views can be hidden and restored through View. Resizing the window keeps the bottom console inside the workspace.

Vulcan is useful for learning CPU instructions, writing mixed C and assembly programs, inspecting registers and memory, experimenting with flat 64-bit memory, and drawing memory-backed displays. The emulator's current guest clock cap is 100 MHz. See [assembly and CPU reference](asm.md) and [C compiler reference](c.md).

## Build and resources

Run `./build.ps1` with a Java 17 or newer JDK to compile and run regression checks. Add `-PackageJar` to produce `builds/latest/VulcanIDE <version>.jar` (it replaces older JARs there), containing docs, icons, headers and runtime sources. Editable C headers live in `src/stdlib/include` and runtime libraries in `src/stdlib/lib`. Each library has one source provider; native port and clock routines use assembly. Help opens the same documentation shipped in the JAR.

Registers display eight hexadecimal digits; PC, SP and register pairs display sixteen. See [wide-isa.md](wide-isa.md) for the current ISA.

