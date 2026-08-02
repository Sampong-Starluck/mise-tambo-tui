# tambo — a TUI for mise and vfox

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
- `ToolsPanel` — lists tools and versions, with filtering and actions
- `EnvPanel` — surfaces environment-related data and shell activation context (mise only)
- `TasksPanel` — discovers and runs project-defined tasks (mise only)
- `DetailPanel` — shows detailed information for the current selection
- `LogPanel` — captures operation output and severity levels

In vfox mode, only the **Tools** and **Log** panels are shown — vfox has no task runner, env inspection, doctor, trust, or prune equivalent.

### Extra UI flows

- `RegistryModal` — the `a` "Add SDK" flow: fuzzy-find a tool then a version. For mise this browses the full `mise registry`; for vfox it lists only plugins you've already added and installs a version of one (it doesn't pin/use it — that stays a separate step)
- `AddPluginModal` — vfox only (`P`): registers a plugin standalone via `vfox add`, without installing a version, supporting `--alias`/`--source`
- `ConfigEditorModal` — in-app editor for the project config (`mise.toml` / `.vfox.toml`) and, for mise, the global `config.toml`
- `TaskArgsModal` — mise only: run the selected task with extra arguments
- `ConfirmModal` — confirmation prompts for destructive actions (uninstall, prune, upgrade-all, …)
- `HelpOverlay` (`?`) — the in-app shortcut reference, backend-aware

## Prerequisites

### To run the JAR

- Java 25+
- `mise` and/or `vfox` installed and available on your `PATH`, depending on which backend you use

### To build the native image

- GraalVM JDK 25+ (or a compatible polyglot runtime)
- Maven 3.6+
- `mise` (recommended) or the tools above activated in your shell
- Linux, macOS, or Windows with appropriate build tools

## Choosing a backend: mise vs vfox

tambo picks a backend once at startup and uses it for the whole session:

1. `--backend=mise` or `--backend=vfox` on the command line, if given, wins outright.
2. Otherwise it looks for `mise.toml` or `.vfox.toml` in the current directory and uses whichever it finds.
3. If neither exists, it asks once on the console (`Which version manager should tambo use here? [mise/vfox]`, default mise) and creates an empty config file for the chosen backend so the choice sticks next time.

Other flags:

- `--offline` — skips anything that needs the network (install, use, self-update, Add SDK); shows only already-installed tools.

## Quick start

### Build

```bash
./mvnw clean package
```

This produces an executable JAR at `target/mise-tambo-0.0.2.jar`.

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
java -jar target/mise-tambo-0.0.2.jar
# or, after building the native image:
mise-tambo
```

## Keyboard shortcuts

The UI is designed to be keyboard-first. `?` opens the full, backend-aware in-app reference at any time; the summary below matches it.

### Global

- `?` — open help overlay
- `a` — mise: fuzzy-find and install an SDK from the full registry. vfox: install another version of an already-added plugin
- `A` — activate shell integration for the active backend
- `e` — edit the project config (`mise.toml` / `.vfox.toml`)
- `E` — edit the global `mise` config (mise only)
- `T` — trust this project's mise config (mise only)
- `D` — run `mise doctor` (mise only)
- `U` — self-update: `mise self-update` or `vfox upgrade`
- `P` — mise: upgrade all outdated tools (asks to confirm). vfox: add a plugin (`vfox add`, no version install)
- `X` — prune unused/old tool versions (mise only)
- `r` — refresh the current UI state
- `C` — cancel every running operation, from any panel
- `1` / `3` / `4` — jump to status / env / tasks panels (mise only)
- `2` / `5` — jump to tools / log panels
- `1`-`5` (mise) or `2`, `5` (vfox), `Tab` / `Shift+Tab` — panel navigation
- `j` / `k`, arrow keys, `Home`, `End`, `Page Up`, `Page Down` — navigate lists

### Tools panel

- `i` — install the selected tool version
- `u` — use (install and pin) the selected tool version at project scope (`-p` for vfox)
- `g` — install and pin the selected tool version globally (`-g` for vfox)
- `x` — uninstall the selected tool (asks to confirm)
- `R` — remove the selected tool from the project config (asks to confirm)
- `p` — upgrade the selected tool to its newest version (mise only)
- `c` — cancel the selected tool's operation
- `C` — cancel all running operations

### Tasks panel (mise only)

- `Enter` — run the selected task
- `:` — run the selected task with arguments
- `.` — re-run the last task
- `c` — cancel the selected task
- `C` — cancel all running operations

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
        ├── MiseTuiApp.java                   (top-level TUI, key bindings, layout, backend selection)
        ├── components/
        │   ├── StatusPanel.java              (mise status and health)
        │   ├── ToolsPanel.java               (tool browser, version selector)
        │   ├── EnvPanel.java                 (environment variables, shell activation)
        │   ├── TasksPanel.java               (task runner with running… indicator)
        │   ├── DetailPanel.java              (detailed info for current selection)
        │   ├── LogPanel.java                 (streaming operation output)
        │   ├── HelpOverlay.java              (? — keyboard reference)
        │   ├── RegistryModal.java            (add SDK fuzzy-find modal)
        │   ├── AddPluginModal.java           (vfox-only plugin registration modal)
        │   ├── ConfigEditorModal.java        (in-app project/global config editor)
        │   ├── TaskArgsModal.java            (run a task with extra arguments)
        │   ├── ConfirmModal.java             (confirmation prompts)
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

### Native binary crashes on terminal resize (`Fatal error: Must either be at a safepoint or in native mode`)

Known issue in the `dev.tamboui:tamboui-panama-backend` dependency, not in this app's own code. On Linux/macOS, `UnixTerminal.onResize()` installs a `SIGWINCH` handler via a raw `sigaction()` call (Panama FFI) whose C-side handler is itself a Java upcall. Under GraalVM Native Image, a `SIGWINCH` can interrupt the main thread while it's already executing AOT-compiled Java; Substrate's upcall stub assumes the interrupted thread was cleanly in "native" state first, and when it isn't, `SafepointSlowpath.enterSlowPathTransitionFromNativeToNewStatus` aborts with this fatal error — an unrecoverable native crash that no try/catch in `tambo` can intercept.

Only reproduces in the **native image** build (`target/mise-tambo`); it has not been observed running on the plain JVM (`java -jar target/mise-tambo-0.0.2.jar`). Triggered by resizing the terminal (e.g. growing its height) while the app is running.

No workaround shipped yet — tracked as a known crash pending a fix upstream in `tamboui-panama-backend` (the signal handler needs to avoid calling back into Java from raw signal-handler context entirely, e.g. by dispatching through `sun.misc.Signal`/a dedicated signal-dispatch thread instead of a Panama upcall).

## License

Check the repository for the project license details.

## References

- [mise documentation](https://mise.jdx.dev)
- [vfox documentation](https://vfox.dev)
- [TamboUI documentation](https://tamboui.dev)
- [Spring Boot documentation](https://docs.spring.io/spring-boot/)
- [Java virtual threads](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/VirtualThread.html)
