# Vulcan MCP

Vulcan's local MCP server lets an AI app build, run, observe, stop, and test this project. Every program uses one private display-capable CPU session. Console output still works through `OUT 0`; display images come from that same CPU's framebuffer. You do not need to open the IDE.

The MCP uses the official Python SDK for the connection and the existing Java compiler, assembler, linker, CPU, and display decoder for execution. The CPU starts idle when a client connects. It targets Vulcan's extended 8080 with 32-bit registers and 64-bit pairs/addresses.

## How do I connect?

Install JDK 17 or newer and Python 3.10 or newer. From the Intel8080 project folder, run once:

```powershell
.\mcp\setup.ps1
```

If Python is not on PATH, pass its full path with `-Python`. `JAVA_HOME` can select a JDK; otherwise the server checks installed Windows JDKs and then PATH.

Add a local **stdio** MCP server in your AI app with these fields:

| Field | Value for this checkout |
| --- | --- |
| Name | `vulcan` |
| Command | `C:\Users\stefa\Documents\Intel8080\mcp\.venv\Scripts\python.exe` |
| Argument | `C:\Users\stefa\Documents\Intel8080\mcp\server.py` |

`mcp/client.example.json` contains the equivalent `mcpServers` configuration for clients that use that format. Other clients may ask for the same command and argument in separate settings fields. No server URL or port is needed. Reconnect after changing Java source: each connection compiles the current Java source into its own temporary classes directory.

The server waits for MCP messages, so launching it by hand will appear idle. The AI app normally launches it and closes it on disconnect. There is no need to run `build.ps1` or package a JAR first. Normal client file tools remain responsible for editing C, ASM, headers, and manifests.

## What tools are available?

| Tool | Use |
| --- | --- |
| `run(path, preset="vos", timeout_ms=30000, wait_ms=200)` | Build and run C/ASM/`build.run`, or load a Vulcan `.exe`. Returns a run ID, console text, CPU state and a display PNG. |
| `observe(id, cursor=0, include_display=true)` | Read new console output, CPU status and a fresh display image, or read check-job progress. |
| `build(path, output_name="program.exe")` | Compile/link C, ASM or `build.run` into `builds/mcp-programs/`. Return the executable path or build diagnostic. |
| `send_input(id, text, line=true)` | Supply Latin-1 keyboard text. The default appends LF; `line=false` sends bytes without a newline. Returns whether input was accepted. |
| `stop(id)` | Stop the identified run or check job and retain its results. |
| `check_all()` | Start the full existing `build.ps1` suite and return a job ID. Use `observe` and `stop` on that ID. |

Paths can be relative to this project or absolute within it. `.exe` means the Vulcan VEXE2 guest format: it never launches a Windows executable. `build.run` must have that exact filename; other `.run` files are not shell scripts and are not accepted.

Running C/ASM preserves the current IDE rules: C needs an entry definition, and needed sibling providers come from the nearest `build.run` or the source folder. Running a manifest compiles **every listed source** and links one program. Manifest paths are relative to its folder, and `#` starts a comment. Header, source, preset, and output paths are constrained to this project, including existing symbolic links and junctions.

## How does a model see the console and screen?

For example, ask the model:

> Run `src/programs/asm/setPixel.asm` with the `32x32 RAM 2-bit` preset. Show its display and report any console output or errors.

Or:

> Run `work/my-project/build.run` with the `vos` display preset. Read the console, inspect the returned image, and stop it after checking the result.

The second path is an example; create your own manifest at the desired location. Available checked-in presets are `vos` (1280x720), `16x16 Video 2-bit`, and `32x32 RAM 2-bit`. Pass a preset's name without `.preset`. The server applies the same effective palette and legacy VOS migration as the IDE, in memory, without rewriting the preset.

`run` always includes the first display image, including a genuinely blank image for a console-only program. A client/model with image support can inspect it directly. `observe` captures a new frame from the current run, including after it has stopped. Images are bounded to 1280x720 while preserving aspect ratio; the response includes original and returned dimensions, the preset, and framebuffer address.

Console text is separate from compiler diagnostics and server logs. Guest bytes are mapped one-to-one to Latin-1 characters, identified by `console_encoding`. The server retains the latest one MiB and returns up to 64 KiB per call. Pass the returned `cursor` to the next observation. Continue reading while `cursor < console_total`, even after execution finishes. `dropped_bytes` reports output lost because the caller fell behind retention. Set `include_display=false` when only new console text is needed.

The run ID prevents accidentally inspecting a later replacement program. CPU addresses and register pairs are hexadecimal strings, preserving all 64 bits in JSON clients. Build failures and runtime errors are explicit errors; they are not inferred by parsing console messages. A failed build preserves an existing executable and never runs it as if the new build succeeded.

## How do interactive and long-running programs work?

Only one run or check job is active at once. Stop it before starting another build, run, or check job. `wait_ms` is just the initial observation interval (0..1000 ms); it is not the execution deadline. Programs continue between tool calls until they complete, stop, or reach `timeout_ms` (1..60000 ms, default 30 seconds).

An interrupt-enabled `HLT` returns state `interrupt_wait` and keeps the machine alive for input. A loop waiting on a keyboard port can remain `running`; the server does not guess whether every loop is waiting for input. Use `send_input` with the same ID, then `observe`. Rejected input is reported as `accepted=false` and should not be assumed delivered.

Completion, runtime failure, user stop, and timeout have separate states. A timeout returns the final console and image along with an error result. Disconnecting closes the CPU and owned workers. No arbitrary host-shell or generic filesystem tools are exposed. This is a local development interface for the trusted project, not an operating-system sandbox for hostile source code or tests.

## What does checking everything mean?

`check_all` launches the existing full `build.ps1` suite, including `McpRuntimeCheck`. It captures logs and has a 15-minute deadline. The existing suite stops on its first failure; the result explicitly reports whether the full suite completed, exit status, and reported skipped cases. Stopped, timed-out, and failed jobs are never reported as a completed full suite. Check logs use the same cursor/retention limits as console observations.

Some existing UI checks require an interactive desktop or a second monitor. The MCP runtime itself runs headlessly, but the original checks retain their requirements. Avoid manually running another `build.ps1` against the same project while `check_all` owns its shared check output directories.

For focused runtime verification, run:

```powershell
.\build.ps1 -Check McpRuntimeCheck
```

To exercise a real SDK connection with all six tools:

```powershell
.\mcp\.venv\Scripts\python.exe .\test\McpServerCheck.py
```

Add `--checks` to run the complete regression suite through MCP as well. The adapter test creates and removes its own program fixtures and writes the full-suite log to `builds/mcp-full-checks.log`.
