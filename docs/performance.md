# Performance measurements

The compiler and glyph numbers below come from the current staged checks. Guest cycles measure emulated work; they are not host wall-clock measurements of typing or repaint latency.

| Workload | O0 | O1 | Result |
|---|---:|---:|---|
| `heap.c` + `graphics.c` output | 30,494 bytes / 8,388 instructions | 28,352 bytes / 7,682 instructions | O1 saves 2,142 bytes and 706 instructions. |
| Eight-byte zero fill | 51,604 guest cycles | 35,474 guest cycles | O1 uses 31.3% fewer cycles. |
| Graphics row loop | 28,891 guest cycles | 23,897 guest cycles | O1 uses 17.3% fewer cycles. |
| One 16×16 production glyph | 219,290 instructions / 2,102,025 guest cycles | 201,185 instructions / 1,864,376 guest cycles | At the 100 MHz guest-clock cap, 21.020 ms / 18.644 ms. O1 is below the 225,000-instruction and 2,000,000-cycle gates. |
| 1280×720 display refresh | 2,983,155 guest OUT operations/s without display | 3,150,556 guest OUT operations/s with display at 60 Hz | Median of three repeated check runs (each uses three one-second samples); ratio 1.056. This host-throughput check does not measure physical input-to-repaint latency. |
| Float-add caller plus linked runtime | 1,189 caller lines; 13,721 linked lines; 42,554 bytes | 793 caller lines; 13,325 linked lines; 41,372 bytes | O1 saves 396 caller lines, 396 linked lines, and 1,182 bytes. |

The handwritten float example is 9 instructions and 42 bytes, but it is only the caller. The C sample also links the float and `printf` runtime, so comparing its linked line count directly with that caller would omit most of the C program's work.

## IntelliJ IDEA Run diagnosis

The saved IntelliJ log for `C:\Users\stefa\Documents\Intel8080` records JPS compilation times of 104,268 ms at 15:40 and 327,378 ms at 15:48 on 2026-10-16, followed by 8,045 ms at 23:41. In the 15:43 `Vulcan` Run, IDEA logged `runconfig.running` at 15:43:22.275, JPS started at 15:43:23.287, and the Make step reported 327,378 ms at 15:48:49.548; a new run activity started 0.76 seconds later. This directly ties that five-minute delay to Make-before-run, though the logs do not explain why JPS took so long. The `Vulcan` run configuration uses IDEA's normal Make-before-run step; its Korge pre-run task is disabled. The executable identifies as IDEA 2023.3.3. The `2022.2.3` directory in the JPS classpath is the install directory's old name; the installed executable and plugins report build `IC-233.14015.106`.

The 2026-10-17 IDEA trace narrows down the later run: its full project-open scan took 4,903 ms and examined 337 project files, with zero files scheduled for indexing. JPS Make began at 22:00:50.939, compiled 33 Java files from 22:00:53.579 to javac completion at 22:01:20.678 (27,099 ms), and IDEA submitted a `runconfig.running` event at 22:01:21.516. The event is about 75 seconds after the IDE JVM started, which is consistent with the reported wait, but the telemetry event does not prove when the program window appeared or finished starting. The scan also examined 43,369 JDK files; the 337-file module scan took about 612 ms of worker time, so project indexing alone does not explain a minute-long Run.

These samples are not a controlled before/after: cache state and concurrent load differ, and the 8-second run happened before the staged module file was copied to the live project. The staged module excludes generated `builds` output and project `work` backups from indexing. JPS compilation explains about 27 seconds of the traced session; the remaining time is not attributed by the available logs. A direct IDEA Run trace with a timestamp at process launch and first rendered frame is still needed to separate IDE startup, Make, application startup, and display latency. The current 16×16 O1 glyph profile is 18.644 ms at the 100 MHz guest cap, and the display requests frames at 60 Hz; neither figure measures physical keyboard-to-repaint latency.

## Reverted renderer experiment

Caching the active glyph row while traversing sorted sparse points raised the measured single-glyph cost from 746,325 to 799,498 cycles, so that experiment was reverted. The current production numbers are listed above; the old 200,000-cycle/2 MHz target is obsolete after the user raised the CPU cap to 100 MHz.

A warmed renderer check measured a 1280×720 indexed framebuffer snapshot at a median of 0.25–0.28 ms. RGB conversion medians ranged from 1.64 to 4.31 ms across nine-sample runs, with the highest result during the full-suite run. First request-to-publication samples were 21–29 ms with 10 ms polling; they include worker scheduling and exclude Swing painting and desktop presentation. Conversion fits within a 60 Hz frame even in the slower sample; the available evidence does not identify it as the source of the reported delay.
