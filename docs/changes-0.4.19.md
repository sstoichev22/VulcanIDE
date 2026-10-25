# Vulcan 0.4.19 requested changes

> Historical record of the 0.4.19 release. Later releases replaced several details described here: the CPU now runs at a 100 MHz cap, and every Vulcan window, including detached editors and the display popout, uses the branded custom title bar.

This update preserves the instruction set and 2 MHz emulated clock. The user approved native Windows title bars, Shift+wheel horizontal scrolling, and making both VRAM capacity and framebuffer address read-only. Sideways trackpad scrolling depends on the horizontal gesture events delivered by Windows and the JDK. The suggested four assembly lines per C line is an approximate efficiency goal, not a guaranteed ratio.

| Request | Result and verification |
|---|---|
| 1 | Markdown preview scrollbars use the application theme; editor render inspected. |
| 2 | Markdown divider is narrow and uses the panel color; editor render inspected. |
| 3 | Symbol hover survives pointer movement onto its card and retains a clickable declaration link; long paths wrap. Native editor regression covers persistence and link destination. |
| 4 | Terminal uses monospaced ASCII VULCAN artwork; aligned banner render inspected. |
| 5 | Removed duplicate selected filename/Run controls from the running-program bar; file-specific editor eligibility remains authoritative. |
| 6 | Created/deleted notifications use an explicitly sized opaque text overlay; notification render inspected. |
| 7 | Running-program dropdown is hidden when no CPU executes; compilation has its own progress state. Regression covers empty and compiling states. |
| 8 | Running rows contain filename, rerun icon and stop icon; rerunning preserves row order, and Save As retains the original run identity while keeping Stop available. |
| 9 | Display shows numbered colored boxes for each RGB bit, accepts the 1280x720 vos preset, and obtains VRAM capacity from Memory.java. |
| 10 | Ordinary wheel moves vertically and Shift+wheel moves horizontally; regression asserts one axis per event. Physical sideways trackpad gestures were not manually tested. |
| 11 | Numeric hover is restricted to C and assembly; focused checks reject text, Markdown and header numeric popups. |
| 12 | Native Windows title bars enable OS maximize-hover snap handling on main and detached windows. Actual Windows snap flyout was not manually exercised. |
| 13 | Removed the app's drag-to-top fullscreen prompt; native Windows title-bar dragging handles snap. F11/View fullscreen and Esc restore remain. Actual OS drag flyout was not manually exercised. |
| 14 | Display uses the canonical Memory.VRAM_START value 0xF0000000. |
| 15 | Diagnostic waves are clipped to glyph ranges and overlapping ranges do not paint repeatedly. |
| 16 | Diagnostic and hover paragraphs wrap; uninterrupted paths and long words can break within bounded popup width. |
| 17 | Ordinary C edits restyle affected lines when lexical state permits; comment/string changes restyle the affected suffix and semantic declarations refresh dependencies. Incremental/full styling equivalence and bounded local style events are tested. |
| 18 | Exact completed words dismiss autocomplete; descriptions use pipes. |
| 19 | Volatile qualification works on scalar objects, pointers, typedefs and aggregates; O0/O1 tests count actual changing-device reads and check writes, polling and sizeof. |
| 20 | Default presets are seeded as files before loading; focused check confirms three file-backed defaults. |
| 21 | Per-file compilation/assembly/link progress is shown beside the running-program dropdown; builds run off the event-dispatch thread. |
| 22 | Numeric popup width follows the longest conversion line and is bounded by screen width; focused width comparison passes. |
| 23 | Terminal compiler/assembler/linker commands run in the background with phase text and an ASCII spinner. |
| 24 | Editor, terminal and console use shared smooth wheel scrolling with precise rotations. |
| 25 | Both VRAM capacity and framebuffer address are read-only, as confirmed by the user. |
| 26 | Frame decoding runs off the event-dispatch thread; resizing paints a cached image. High-address/cross-page memory snapshots and decoded RGB pixels are tested. |
| 27 | Immutable bold white host end markers survive ASCII/hex/decimal changes and distinguish normal return, errors and user stop. Lifecycle regressions cover these outcomes. |
| 28 | Display popouts initially fit the selected pixel aspect within monitor bounds; 16x16 has a square client area. Resize handling rejects maximized/nonresizable windows. |
| 29 | Tab measurements use the editor's current zoom font; line-number/tab geometry regressions cover multiple fonts, sizes, scales and scroll offsets. |
| 30 | Quote pairing/skipping observes lexical context and odd/even escapes; comments, character literals and existing closing quotes are covered. |
| 31 | A caret within empty function parentheses triggers the function hint; leaving the call cancels it. |
| 32 | Startup console output permits the first 100 newline messages without throttling; later KiB/s is configurable (zero means unlimited). Compiler/runtime overhead is also reduced as measured below. |
| 33 | Display and debugger Run controls share the editor's dimmed pressed feedback. |
| 34 | Integer-only literal printf calls select a smaller formatter; runtime compilation uses O1, conversion avoids redundant wide division, constant pointer scaling uses additions, and indirect copies reuse calculated addresses. Dynamic/floating printf and custom implementations retain their behavior, including separately compiled custom printf definitions. |

## Measured efficiency

Same JDK 21/release 17, same checked-in OS sources and standard 2 MHz CPU. First samples include JIT warm-up; host timing is variable.

| Measurement | 0.4.18 | 0.4.19 |
|---|---:|---:|
| Simple printf caller plus runtime assembly lines | 21,083 | 8,865 |
| Simple printf executable bytes | 71,789 | 30,321 |
| Simple printf executed instructions | 1,627,346 | 198,943 |
| Simple printf clocked completion | 4.721 s | 0.598 s |
| Pointer loop executed instructions | 94,570 | 44,818 |
| Complete linked OS executable bytes | 116,148 | 71,216 |
| OS clocked startup through tenth newline | 31.607 s | 3.744 s |

The formatter executes about 87.8% fewer instructions, and the measured OS startup checkpoint is about 8.4 times faster. Assembly line figures include generated helpers and the selected standard library; they do not represent a fixed expansion per source line. The complete OS size includes all linked objects. Clocked output was captured at the CPU output port, so these figures isolate compiler/runtime execution from terminal rendering.

A representative 1,500-line ordinary identifier edit changed from 153 ms/4,503 style events to 15 ms/4 events in a single probe. Loads, theme changes and semantic declarations still require broader highlighting. A separate 720p display probe retained 99.68% of guest output throughput compared with decoding disabled; one cached 700x450 paint measured 0.73 ms. These are representative local measurements rather than universal timing guarantees.

## Verification and practical limits

The standard build integrates volatile, optimization, actual-OS performance, editor and runtime regression checks alongside existing checks. Optional styling timing, display throughput and real-clock compiler performance probes can run separately. Source resources are also checked from a fresh resource-free compile. No checks are skipped or thresholds weakened for this update.

Native window decoration and client geometry were checked. OS-owned Windows 11 snap flyouts depend on Windows settings and were not manually exercised. A physical laptop trackpad was unavailable. Volatile preserves memory access semantics; it does not provide atomic multi-byte operations or thread synchronization. The full 700-print boot duration was not measured.

Delivery contains source, regression tests, this report and the build script only. Compiled output is excluded; concurrent user edits to src/programs/fixes.txt are preserved.
