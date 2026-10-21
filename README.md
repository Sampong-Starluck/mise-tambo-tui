# A TUI for mise and vfox

A lazygit-style terminal UI for polyglot runtime version managers. It gives you a keyboard-driven, multi-panel workspace for inspecting tools, managing versions, running tasks, and editing configuration without manually composing long CLI commands.

Two backends are supported:

- **[mise](https://mise.jdx.dev)** — the default; full feature set (tools, tasks, env, doctor, trust, prune, self-update, upgrade)
- **[vfox](https://vfox.dev)** — a lighter alternative; tool install/use/list plus plugin management and self-update, with no task runner or env inspection

## What it does

This TUI application is a Spring Boot CLI application built with [TamboUI](https://tamboui.dev). It wraps `mise` (or `vfox`) in a terminal interface heavily inspired by `lazygit`:

- browse installed and available tool versions, per-project or globally
- fuzzy-find and install new SDKs from the registry
- refresh status and inspect environment details (mise)
- run project tasks and review live logs (mise)
- open the config editor and registry/plugin modals from the UI
- activate shell integration or run diagnostics from the keyboard

## Features

### Main panels

- `StatusPanel` — shows the current `mise` status and health signals (mise only)
- `ToolsPanel` — lists tools with a status glyph per row (`✓` installed, `➜` installing or queued, `✗` in the config but missing, `↑` newer version available, `*` marked), filtering, batch marks, and actions
- `VersionsPanel` — the column right of Tools (yazi-style Miller columns): every installed and available version of the selected tool
- `EnvPanel` — surfaces environment-related data and shell activation context (mise only)
- `TasksPanel` — discovers and runs project-defined tasks (mise only)
- `DetailPanel` — shows detailed information for the current selection
- `LogPanel` — captures operation output and severity levels
- `JobsView` (`w`) — installs, upgrades and other long operations with progress, output, retry and cancel; downloads run two at a time and the rest queue

In vfox mode, only the **Tools** and **Log** panels are shown — vfox has no task runner, env inspection, doctor, trust, or prune equivalent.

### Extra UI flows

- `RegistryModal` — the `a` "Add SDK" wizard: Tool → Version → Where → Confirm. Fuzzy-find a tool, then a version; choose where it applies (this project, everywhere, or just install it — each says which file it writes); the confirm step shows the exact command before it runs. `Shift+Tab` goes back a step, `Esc` cancels. mise browses the full `mise registry` catalog; vfox is scoped to plugins you've already added (from `vfox list`) — install a new plugin from the full catalog with `p` first
- `AddPluginModal` — vfox only: fuzzy-finds and registers a plugin standalone via `vfox add`, without installing a version (`p`, or `Space P` to type the raw `--alias`/`--source` syntax)
- `CommandPalette` (`Ctrl+P`) — every action, fuzzy-searchable, with its key shown beside it
- `LeaderMenu` (`Space`) — a which-key popup for the less common actions (trust, doctor, prune, self-update, …); it names each second key as you need it
- `CommandLineBar` (`:`) — type a raw `mise`/`vfox` command with completion and a "will run" preview
- `BatchConfirmModal` — lists every tool an upgrade or uninstall will touch, flags major-version jumps, and defaults to **No**
- `MenuBar` + `FKeyBar` — optional beginner mode (`Space m`): a menu bar on top (`F9`) and Midnight Commander-style F-keys at the bottom
- `ConfigEditorModal` — in-app editor for the project config (`mise.toml` / `.vfox.toml`) and, for mise, the global `config.toml`
- `TaskArgsModal` — mise only: run the selected task with extra arguments
- `ConfirmModal` — confirmation prompts for destructive actions (prune, upgrade-all, …)
- `HelpOverlay` (`F1` / `?`) — help for the focused panel first, then the global keys and what each symbol means; `/` searches it, `Tab` switches panel

## Prerequisites

### To run the JAR

- Java 25+
- `mise` and/or `vfox` installed and available on your `PATH`, depending on which backend you use

### To build the native image

- GraalVM JDK 25+ (or a compatible polyglot runtime)
- Maven 3.6+
- `mise` (recommended) or the tools above activated in your shell
- Linux, macOS, or Windows with appropriate build tools

## Choosing a version-manager backend: mise vs vfox

tambo picks a version-manager backend once at startup and uses it for the whole session:

1. `--backend=mise` or `--backend=vfox` on the command line, if given, wins outright.
2. Otherwise it looks for `mise.toml` or `.vfox.toml` in the current directory and uses whichever it finds.
3. If neither exists, it shows a picker in the TUI itself on first launch — `m`/Enter for mise (the default), `v` for vfox — and creates an empty config file for the chosen backend so the choice sticks next time. This runs inside the TUI's own input loop rather than a raw console prompt before the TUI starts, so it never competes with the TUI backend for stdin.

Other flags:

- `--offline` — skips anything that needs the network (install, use, self-update, Add SDK); shows only already-installed tools.
- `--advanced-features` — accepted for compatibility but no longer does anything: every action is always available from the `Space` menu and the `Ctrl+P` palette.
- `-h`, `--help` — prints usage and exits.
- `-V`, `--version` — prints the app version and exits.

Command-line parsing is handled by [picocli](https://picocli.info): invalid flags or values (e.g. `--backend=bogus`) print a validated error and usage instead of silently falling through.

## Choosing a UI backend: jline3 vs Panama vs Aesh

This is unrelated to the mise/vfox choice above — it's which TamboUI library renders the terminal itself. `tamboui-jline3-backend`, `tamboui-panama-backend`, and `tamboui-aesh-backend` all ship on tambo's classpath (see [pom.xml](pom.xml)); only one is active per run, picked once at process startup and unable to change mid-session:

1. The `tamboui.backend` system property or `TAMBOUI_BACKEND` environment variable, if either is set externally, wins outright.
2. Otherwise, the persisted `ui.backend` in `tambo.properties` is used — `jline3` if unset.

Press `Space B` to switch; it persists the choice to `tambo.properties` and logs a reminder that it takes effect on the next restart, not the running session.

**jline3** — pure-Java terminal I/O:

- ✅ Most portable: works under IDE run consoles, Windows Git Bash/MinTTY, and GraalVM native-image alike
- ✅ No known crashes or startup failures in this app
- ❌ Goes through the JVM's own I/O stack rather than talking to the OS terminal directly — the (marginally) heavier of the two

**panama** — native FFM (Foreign Function & Memory API) terminal I/O:

- ✅ Talks to the OS terminal directly instead of through jline's Java I/O layer — billed as the lower-overhead, faster path
- ✅ No bundled JNI native library to load — sidesteps jline's own JNI/native-image config issues entirely (see [Troubleshooting](#troubleshooting))
- ❌ Built on Java's comparatively new FFM API — less battle-tested in the wild than jline's long-established native terminal handling

**aesh** — pure-Java terminal I/O via the [aesh-readline](https://github.com/aeshell/aesh-readline) library (`org.aesh:terminal-tty`), a different terminal stack from jline3's:

- ✅ Pure Java like jline3, so it carries no FFM native-access requirement the way Panama does
- ✅ A genuinely independent implementation — useful as a fallback if a jline3/native-image interaction (like the JNI typo in [Troubleshooting](#troubleshooting)) ever blocks the default backend
- ❌ Not exercised by this app's own testing the way jline3 and Panama are (see the failure modes documented for each above) — no known issues here, but none ruled out either; treat it as experimental

**jline3 is the default and the recommended choice.** Portability is the deciding factor: it runs correctly everywhere tambo ships — JVM, native-image, every terminal emulator tested so far — while Panama's two known issues are both hard failures (a fatal crash, a refusal to start) rather than degraded behavior. The resize crash is the more serious of the two: it's a **fatal, unrecoverable native-image crash** (`SIGWINCH` interrupting an FFM upcall mid-safepoint) that terminates the process outright and can't be caught by a `try`/`catch` anywhere in this app's own code — the session is just gone, no graceful fallback to jline3 mid-run. Switch to Panama only if you have a specific reason to and can rule out both failure modes for your setup — a resize during a native-image session, or launching from Git Bash/MinTTY on Windows. Switch to aesh only to work around a jline3-specific problem — it's on the classpath as a fallback option, not a validated alternative.

## Quick start

### Build

```bash
./mvnw clean package
```

This produces an executable JAR at `target/mise-tambo-<version>.jar`.

### Build a native image

#### Linux

To build a self-contained native image on Linux, use the deploy script:

```bash
./deploy-tambo.sh
```

This compiles a GraalVM native image and installs it to `~/.local/bin/mise-tambo`, then prints instructions for running it. The script:

- checks for `./mvnw` and a GraalVM JDK
- runs Maven with the `native` profile to build the image
- validates the binary is complete before installing
- handles atomic install (so you can upgrade while a binary is running)
- detects leftover files from older install layouts

**Build options:**

- `./deploy-tambo.sh --clean` — wipe `target/` first, slower but safer after dependency changes
- `./deploy-tambo.sh --mise TASK` — build by running a `mise.toml` task instead of invoking `./mvnw` directly (see below)
- `./deploy-tambo.sh --no-mise` — force use of `./mvnw` even if `mise.toml` is present
- `PREFIX=/usr/local ./deploy-tambo.sh` — install to a custom prefix (default: `~/.local`)

**Using mise to build:**

When `mise.toml` is present and you're on an interactive terminal, the script asks whether to build through `mise` or use the java on your PATH:

```
This project pins its toolchain in mise.toml.
Build through mise, or with the java on your PATH? [M/j]
```

Choosing mise (the default) runs a task from `mise.toml` instead of calling `./mvnw` directly. This ensures you get the exact pinned versions (Java, Maven) without needing to activate them in your shell first. If a task isn't specified, it defaults to `compile-native`. Pass `--mise TASK` or `--no-mise` to skip the prompt in CI/unattended builds.

For a profile-guided-optimized build (a further optimized binary built from a runtime profile of a real session), see the `pgo-instrument` / `pgo-optimize` Maven profiles in `pom.xml`.

#### macOS and Windows

On macOS and Windows, build the native image manually:

```bash
./mvnw clean package -Pnative
```

The compiled binary will be in `target/`. Copy it to your preferred location and make it executable:

```bash
# macOS / Windows PowerShell
cp target/mise-tambo ~/.local/bin/mise-tambo
chmod +x ~/.local/bin/mise-tambo
```

If `mise.toml` is present in your project, you can run a build task instead:

```bash
mise run compile-native
```

### Run from source

```bash
./mvnw spring-boot:run
```

### Run the built artifact

```bash
java -jar target/mise-tambo-<version>.jar
# or, after building the native image:
mise-tambo
```

## Keyboard shortcuts

The UI is keyboard-first, and nothing needs memorising up front: the footer shows the keys for the
focused panel, `Ctrl+P` lists every action with its key, `Space` pops a menu of the less common
ones, and `F1` explains the panel you are in. The summary below matches them.

### Finding things

- `Ctrl+P` — command palette: every action, searchable, with its key
- `Space` — more actions (which-key menu): `t` trust, `i` install everything in config, `e`/`E` edit project/global config, `u` upgrade all outdated, `C` check for updates, `s` self-update, `p` prune, `c` clear download cache, `d` doctor, `m` beginner mode, `a` activate shell, `B` terminal backend, `P` add plugin by alias/source (vfox)
- `:` — type a `mise`/`vfox` command, with `Tab` completion for subcommands, tools and `tool@version`
- `Ctrl+O` — jump mode: panels show a label; type it to move there (`1`-`5` panels, `v` Versions, `w` Jobs)
- `F1` / `?` — help for the focused panel
- `1`-`5` — jump to Status / Tools / Env / Tasks / Log; `Tab` / `Shift+Tab` — next / previous panel
- `j` / `k`, arrow keys, `Home`, `End`, `Page Up`, `Page Down` — navigate lists

### Global

- `a` — mise: fuzzy-find and install an SDK from the full registry. vfox: install another version of an already-added plugin
- `p` — vfox only: fuzzy-find and register a new plugin from the full catalog (`vfox add`)
- `w` — jobs: running, queued, failed and finished operations, with output; `r` retry, `c` cancel, `l` full log
- `z` / `Z` — undo / redo the last config change tambo made (restores `mise.toml` or the global config; installs are not undone)
- `e` — edit the project config (`mise.toml` / `.vfox.toml`)
- `A` — activate shell integration for the active backend
- `r` — refresh
- `C` — cancel every running or queued operation
- `q` — quit

### Tools panel

- `Enter` / `→` — open the Versions column for the selected tool (`Esc` / `←` goes back)
- `i` — install the selected tool version
- `u` — use (install and pin) the selected version at project scope (`-p` for vfox)
- `g` — install and pin the selected version globally (`-g` for vfox)
- `U` — upgrade the marked tools, or the selected one (mise only; asks first, defaulting to No)
- `x` — uninstall the marked tools, or the selected one (asks first, defaulting to No)
- `R` — remove the selected tool from the project config (asks to confirm)
- `d` — remove the selected tool's plugin (asks to confirm). vfox: `vfox remove -y` also deletes **every installed version** of it. mise: `mise plugins uninstall` keeps installed versions, and only works for external plugins — core and registry tools have no plugin to remove
- `m` — mark / unmark for a batch action; `Ctrl+A` marks every outdated tool; `Esc` clears the marks
- `o` — show only outdated tools; `+` — full-screen table with latest and where columns
- `/` — filter; `y` — copy the install path; `c` — cancel the selected tool's operation

### Versions column

- `i` install only, `u` use here, `g` set global, `y` copy `tool@version`, `/` filter
- On a terminal narrower than 96 columns the columns become a drill-down under a breadcrumb (`tambo › Tools › node`); `Enter` opens the detail, `Esc` goes back

### Tasks panel (mise only)

- `Enter` — run the selected task
- `>` — run the selected task with arguments
- `.` — re-run the last task
- `c` — cancel the selected task

### Beginner mode

`Space m` adds a menu bar and an F-key bar. Open a menu by clicking its name, with `F9`, or with
`Alt` + its underlined letter (`Alt+F` File, `Alt+T` Tools, `Alt+P` Project, `Alt+A` Tasks, `Alt+V`
View, `Alt+H` Help — these also switch beginner mode on). Arrows move, `Enter` or a click runs an
entry, `Esc` or a click elsewhere closes it. VS Code's integrated terminal keeps `F9`/`F10` for its
debugger by default, so use a click or `Alt` there. The F-key bar:
`F1` help, `F2` versions, `F3` install, `F4` use, `F5` global, `F6` upgrade, `F7` add SDK, `F8` remove, `F9` menu, `F10` quit.
Every menu entry shows the key that does the same thing without the menu.

### `pick` — choose a version without opening the full UI

`mise-tambo pick <tool>` opens a small fzf-style picker under your shell prompt instead of the full
screen: type to filter the versions, then `Enter` uses it in this project, `Ctrl+G` sets it
globally, `Alt+I` only installs it, `Esc` cancels. It then disappears and leaves one line, e.g.
`✓ node 22.11.0 → mise.toml (project)`, and exits 0 on success, 1 otherwise.

### Small terminals

Below 60×16 tambo shows how big the window is and how big it needs to be, and comes back by itself
when the terminal is resized.

## Runtime behavior

### Task execution (mise only)

- When you run a task, its output streams live to the **Command Log** panel (5)
- The **Tasks** panel (4) shows the task with a spinner and `running…` indicator, then returns to its description when done
- All streamed output is captured and available in the log, even if you switch panels

### Cancellation

- `c` on the **Tools** or **Tasks** panel cancels the selected item's operation
- If you move the cursor or switch panels after starting a task, `c` still cancels the only running operation (no need to move back)
- `C` from any panel cancels **all** running operations at once — useful when multiple installs/builds are in flight
- Cancelled operations exit immediately and clean up their processes, including descendants (background jobs spawned by the task)

### Environment and diagnostics (mise only)

- The **Status** panel shows `mise` health and configuration
- The **Env** panel displays environment variables and shell activation context
- The **Detail** panel provides additional information for the current selection
- Use `D` to run `mise doctor` if something seems wrong

## Project structure

```text
.
├── deploy-tambo.sh                          (Linux-only native image builder)
├── pom.xml
├── README.md
└── src/main/java/com/sampong/tambo/
    ├── TamboApplication.java                 (single entry point: main() + native-image bootstrap hints)
    ├── config/
    │   └── AppConfig.java                    (Spring @Bean wiring: ObjectMapper, executor, app runner)
    ├── _common/                               (everything shared by both backends; tui/ is excluded on purpose, see below)
    │   ├── model/
    │   │   ├── CliResult.java                (exit code + stdout/stderr, shared by mise and vfox)
    │   │   └── Shell.java                    (supported shells)
    │   ├── service/
    │   │   └── SdkVersionBackend.java        (install/use/list/registry contract mise and vfox both implement)
    │   ├── base/
    │   │   ├── CliProcessRunner.java         (spawns/streams version-manager subprocesses)
    │   │   └── CancelRegistry.java           (tracks and cancels running operations, shared by mise and vfox)
    │   └── util/
    │       ├── ShellDetector.java            (detects the running shell from the process tree)
    │       └── ShellFileWriter.java          (idempotent shell-profile activation-line writer)
    ├── mise/
    │   ├── MiseCli.java
    │   ├── MiseMaintenanceService.java
    │   ├── MiseQueryService.java
    │   ├── MiseToolService.java
    │   ├── ShellActivationService.java
    │   ├── implement/                        (mise service implementations, incl. MiseSdkBackend)
    │   └── model/
    ├── vfox/
    │   ├── VfoxCli.java                      (runs the vfox CLI as a subprocess)
    │   ├── VfoxSdkBackend.java               (SdkVersionBackend over vfox; parses plain-text output)
    │   └── VfoxShellActivationServiceImp.java
    └── tui/
        ├── MiseTuiApp.java                   (Spring wiring + the ToolkitApp process hooks, each one
        │                                       line deferring to layout/keys/lifecycle below)
        ├── TuiComponents.java                (bundles every panel/modal instance for layout/ and keys/)
        ├── layout/
        │   └── AppLayout.java                (turns UiState into what's on screen: header, sidebar
        │                                       accordion, main column, footer, modal stacking)
        ├── keys/
        │   ├── GlobalKeyBindings.java        (turns a keypress into an action: the global key
        │   │                                   handler, jump mode, F-keys, nav bindings)
        │   └── CommandCatalog.java           (every action as a Command: what the palette, the
        │                                       Space menu and the menu bar list)
        ├── lifecycle/
        │   └── AppLifecycle.java             (mise/vfox backend selection, first-run picker
        │                                       callback, and session-start data load)
        ├── components/
        │   ├── StatusPanel.java              (mise status and health)
        │   ├── ToolsPanel.java               (tool browser, version selector)
        │   ├── EnvPanel.java                 (environment variables, shell activation)
        │   ├── TasksPanel.java               (task runner with running… indicator)
        │   ├── DetailPanel.java              (detailed info for current selection)
        │   ├── LogPanel.java                 (streaming operation output)
        │   ├── VersionsPanel.java            (Versions column: installed and available versions)
        │   ├── JobsView.java                 (w — jobs with progress, output, retry)
        │   ├── CommandPalette.java           (Ctrl+P — searchable list of every action)
        │   ├── LeaderMenu.java               (Space — which-key menu)
        │   ├── CommandLineBar.java           (: — raw command line with completion)
        │   ├── BatchConfirmModal.java        (confirm that lists every tool, defaults to No)
        │   ├── MenuBar.java / FKeyBar.java   (beginner mode)
        │   ├── HelpOverlay.java              (F1 / ? — help for the focused panel)
        │   ├── RegistryModal.java            (a — Add SDK wizard: tool, version, where, confirm)
        │   ├── AddPluginModal.java           (vfox-only plugin registration modal)
        │   ├── ConfigEditorModal.java        (in-app project/global config editor)
        │   ├── TaskArgsModal.java            (run a task with extra arguments)
        │   ├── ConfirmModal.java             (confirmation prompts)
        │   ├── SelectBackendModal.java       (first-run mise/vfox picker)
        │   ├── SwitchBackendModal.java       (jline3/panama/aesh picker, B key)
        │   └── Ui.java                       (common UI utilities)
        ├── features/
        │   ├── MiseActions.java              (operations: run, cancel, install, upgrade, self-update)
        │   ├── PanelFilter.java, Fuzzy.java  (list filtering / fuzzy matching)
        │   ├── Theme.java, TamboConfig.java  (~/.config/tambo/tambo.properties)
        │   └── Clipboard.java, WindowsConsoleMouse.java
        └── state/
            ├── PanelIds.java                 (panel identifiers)
            ├── UiState.java                  (reactive state for tools, tasks, logs)
            └── UiContext.java                (shared context for panels)
```

## Architecture notes

Layering follows a plain Spring Boot MVC-style split rather than the panel-only view it might look like at a glance. The organizing question for top-level packages is "does this depend on a specific backend?":

- `_common/` — anything used by **both** `mise/` and `vfox/` lives here, so a package path alone answers whether code is backend-specific:
  - `model/` — shared value types (`CliResult`, `Shell`); backend-specific data (`mise/model/`) stays with its own backend
  - `service/` — the cross-backend contract (`SdkVersionBackend`); the mise-only service interfaces stay in `mise/` since vfox has no equivalent for them
  - `base/` — foundational infrastructure both backends build on (`CliProcessRunner`, `CancelRegistry`)
  - `util/` — stateless helpers with no Spring wiring of their own (`ShellDetector`, `ShellFileWriter`)
- `tui/` is backend-agnostic too but deliberately **not** under `_common/` — it's the app's UI layer, not shared backend plumbing, so it stays its own top-level package. Within it, panels/modals (`components/`), the action layer (`features/MiseActions`), and shared UI state (`state/`) stay together rather than splitting into view/controller packages — an immediate-mode TUI renders and handles input in the same `build()` pass, so that split would be artificial here rather than a real separation of concerns
- `config/` — the app's Spring `@Bean` wiring (`AppConfig`), kept out of `TamboApplication` so that class is just the entry point; it configures the whole app rather than a specific backend, so it stays outside `_common/` too

### Core components

1. `SdkVersionBackend`
   - the shared install/use/list/registry contract implemented by `MiseSdkBackend` and `VfoxSdkBackend`
   - lets `MiseTuiApp` and `MiseActions` treat tool operations uniformly regardless of the active backend

2. `MiseCli` / `VfoxCli` (built on `CliProcessRunner`)
   - launch `mise`/`vfox` subprocesses
   - capture combined output safely
   - support timeout-aware execution and streaming output

3. `MiseQueryService`, `MiseToolService`, `MiseMaintenanceService`
   - expose the higher-level mise-only operations that the UI performs (tasks, env, doctor, trust, prune, self-update)
   - translate between `mise` command results and structured TUI state

4. `MiseTuiApp`
   - owns the top-level layout, key bindings, backend selection, and lifecycle
   - coordinates panels through the shared `UiState` and `MiseActions`

5. `tui/components/`
   - contains the UI panels and modal overlays used by the TUI

### Runtime model

- Spring Boot manages application wiring and startup.
- An `AsyncTaskExecutor` is configured for virtual-thread-backed background work.
- The app uses lazy initialization and a virtual-thread-enabled Spring configuration for efficient startup and background task handling.

### Build profiles

- **default** — produces an executable JAR (`./mvnw package`)
- **native** — produces a GraalVM native image (used by `./deploy-tambo.sh`), faster startup with no JVM overhead
- **pgo-instrument** / **pgo-optimize** — profile-guided optimization on top of the native profile for additional runtime performance

## Configuration

The main app settings live in `src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: tambo
  main:
    banner-mode: off
    log-startup-info: false
    lazy-initialization: true
  threads:
    virtual:
      enabled: true

logging:
  level:
    root: warn
```

tambo's own optional settings live in `<config-dir>/tambo.properties`, where `<config-dir>` is `$TAMBO_CONFIG_DIR` or `~/.config/tambo`. Everything is optional:

- `theme.*` — palette colours
- `keys.*` — navigation binding overrides merged onto the standard set, e.g. `keys.moveUp = Up, k, w`
- `ui.backend` — which TamboUI terminal backend to render with, `jline3` (default), `panama`, or `aesh`; see [Choosing a UI backend](#choosing-a-ui-backend-jline3-vs-panama-vs-aesh) below

## Development

### Compile only

```bash
./mvnw clean compile
```

### Run tests

```bash
./mvnw test
```

### IDE notes

- open the project as a Maven project
- use JDK 25+
- ensure Lombok annotation processing is enabled in your IDE

## Troubleshooting

### `mise: command not found` / `vfox: command not found`

- confirm that `mise` and/or `vfox` (whichever backend you're using) is installed and on your `PATH`
- run `mise --help` / `vfox --help` to verify the CLI is available

### UI is not rendering cleanly

- use a terminal with reasonable width and height
- the TUI supports a compact accordion layout for smaller terminals

### `BackendException: All backend providers failed to create a backend`

See [Choosing a UI backend](#choosing-a-ui-backend-jline3-vs-panama-vs-aesh) — this means neither TamboUI terminal backend could open a terminal. With the default `jline3` backend, this usually means stdin/stdout isn't a real TTY (e.g. piped input, or a terminal emulator that doesn't allocate one); run tambo from an interactive terminal.

### `Unable to create a system terminal, creating a dumb terminal` / garbled `^[[?2027;0$y` on screen (Windows native image)

Only happens in the **native-image** build, not the JVM/`spring-boot:run` build — and happens regardless of which terminal you launch from (Windows Terminal, PowerShell, `cmd.exe`), which rules out the "not a real TTY" cause above. Enable `--logging.level.org.jline=DEBUG` to see why: every jline terminal provider fails to construct, and the one that matters — `jni`, jline's native Win32-console provider — fails with `java.lang.NoSuchFieldError: org.jline.nativ.Kernel32.INVALID_HANDLE_VALUE` during `Kernel32`'s native static initializer. jline3 doesn't propagate that failure as an error; it silently falls back to a "dumb terminal" and logs this warning, then TamboUI's terminal-capability probe (querying DECRQM mode 2027) gets sent anyway — since the dumb terminal never reads the reply back in, it leaks onto the screen as literal escape-code text instead of being consumed.

Root cause: `org.jline:jline:3.25.1`'s own bundled GraalVM metadata (`META-INF/native-image/org.jline/jline-native/jni-config.json`, inside the jar) has a typo — the field is registered as the literal string `"INVALID_HANDLE_VALUE,"` (trailing comma baked into the name) instead of `"INVALID_HANDLE_VALUE"`, so native-image never wires up JNI access to the real field. This project ships a corrective override at [`src/main/resources/META-INF/native-image/com.sampong/mise-tambo/jni-config.json`](src/main/resources/META-INF/native-image/com.sampong/mise-tambo/jni-config.json), which GraalVM merges additively with jline's own (broken) entry to register the correctly-spelled field. If this resurfaces after a `tamboui-jline3-backend` / jline version bump, check whether upstream jline has fixed the typo before assuming the override is still needed.

### Native binary crashes on terminal resize (`Fatal error: Must either be at a safepoint or in native mode`)

Only happens on the **Panama** UI backend (`B` to switch, see [above](#choosing-a-ui-backend-jline3-vs-panama-vs-aesh)), not the default `jline3`. On Linux/macOS, Panama's `UnixTerminal.onResize()` installs a `SIGWINCH` handler via a raw `sigaction()` call whose C-side handler is itself a Java upcall. Under GraalVM Native Image, a `SIGWINCH` can interrupt the main thread while it's already executing AOT-compiled Java; Substrate's upcall stub assumes the interrupted thread was cleanly in "native" state first, and when it isn't, `SafepointSlowpath.enterSlowPathTransitionFromNativeToNewStatus` aborts with this fatal error — an unrecoverable native crash that no try/catch in `tambo` can intercept. Switch back to `jline3` to avoid it.

### `panama: Failed to get input console mode` (Windows)

Only happens on the **Panama** UI backend. Happens when launched from **Git Bash (MinTTY)**: MinTTY emulates a terminal over a pipe rather than allocating a real Win32 console, so Panama's `GetConsoleMode()` probe on stdin fails and it refuses to start. Switch to `jline3` (the default), or if you need Panama specifically: run from Windows Terminal, PowerShell, or `cmd.exe` instead, or prefix the launch with `winpty` (ships with Git for Windows, or `pacman -S winpty` in MSYS2).

## License

Check the repository for the project license details.

## References

- [mise documentation](https://mise.jdx.dev)
- [vfox documentation](https://vfox.dev)
- [TamboUI documentation](https://tamboui.dev)
- [Spring Boot documentation](https://docs.spring.io/spring-boot/)
- [Java virtual threads](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/VirtualThread.html)
