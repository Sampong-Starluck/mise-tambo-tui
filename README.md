# A TUI for Mise

A lazygit-style terminal UI for [mise](https://mise.jdx.dev), the polyglot runtime manager. It gives you a keyboard-driven, multi-panel workspace for inspecting tools, managing versions, running tasks, and editing configuration without manually composing long `mise` commands.

## What it does

This TUI application is a Spring Boot CLI application built with [TamboUI](https://tamboui.dev). It wraps `mise` in a terminal interface heavily inspired by `lazygit`:

- browse installed and available tool versions
- refresh status and inspect environment details
- run project tasks and review live logs
- open the registry and config editor from the UI
- activate `mise` shell integration or run diagnostics from the keyboard

## Features

### Main panels

- `StatusPanel` — shows the current `mise` status and health signals
- `ToolsPanel` — lists tools and versions, with filtering and actions
- `EnvPanel` — surfaces environment-related data and shell activation context
- `TasksPanel` — discovers and runs project-defined tasks
- `DetailPanel` — shows detailed information for the current selection
- `LogPanel` — captures operation output and severity levels

### Extra UI flows

- `RegistryModal` for registry-related actions
- `ConfigEditorModal` for editing `mise.toml` and the global `config.toml`
- `HelpOverlay` with the in-app shortcut reference

## Prerequisites

### To run the JAR

- Java 25+
- `mise` installed and available on your `PATH`

### To build the native image

- GraalVM JDK 25+ (or a compatible polyglot runtime)
- Maven 3.6+
- `mise` (recommended) or the tools above activated in your shell
- Linux, macOS, or Windows with appropriate build tools

## Quick start

### Build

```bash
./mvnw clean package
```

This produces an executable JAR in `target/tambo-0.0.1.jar`.

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

#### macOS and Windows

On macOS and Windows, build the native image manually:

```bash
./mvnw clean package -Pnative
```

The compiled binary will be in `target/`. Copy it to your preferred location and make it executable:

```bash
# macOS / Windows PowerShell
cp target/tambo-0.0.1 ~/.local/bin/mise-tambo
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
java -jar target/tambo-0.0.1.jar
```

## Keyboard shortcuts

The UI is designed to be keyboard-first.

### Global

- `?` — open help overlay
- `a` — open the registry modal
- `A` — activate `mise` shell integration
- `e` — edit the project `mise.toml`
- `E` — edit the global `mise` config
- `D` — run `mise doctor`
- `U` — self-update the `mise` backend
- `P` — upgrade all outdated tools
- `X` — prune unused/old tool versions
- `r` — refresh the current UI state
- `1` / `2` / `3` / `4` / `5` — jump to status, tools, env, tasks, and log panels
- `j` / `k`, arrow keys, `Home`, `End`, `Page Up`, `Page Down` — navigate lists

### Tools panel

- `i` — install the selected tool version
- `u` — use (install and set) the selected tool version
- `x` — uninstall the selected tool
- `g` — toggle global installation
- `p` — upgrade the selected tool
- `c` — cancel the selected tool's operation
- `C` — cancel all running operations

### Tasks panel

- `Enter` — run the selected task
- `:` — run the selected task with arguments
- `.` — re-run the last task
- `c` — cancel the selected task
- `C` — cancel all running operations

## Runtime behavior

### Task execution

- When you run a task, its output streams live to the **Command Log** panel (5)
- The **Tasks** panel (4) shows the task with a spinner and `running…` indicator, then returns to its description when done
- All streamed output is captured and available in the log, even if you switch panels

### Cancellation

- `c` on the **Tools** or **Tasks** panel cancels the selected item's operation
- If you move the cursor or switch panels after starting a task, `c` still cancels the only running operation (no need to move back)
- `C` from any panel cancels **all** running operations at once — useful when multiple builds are in flight
- Cancelled operations exit immediately and clean up their processes, including descendants (background jobs spawned by the task)

### Environment and diagnostics

- The **Status** panel shows `mise` health and configuration
- The **Env** panel displays environment variables and shell activation context
- The **Detail** panel provides additional information for the current selection
- Use `D` to run `mise doctor` if something seems wrong

## Project structure

```text
src/main/java/com/sampong/tambo/
├── TamboApplication.java
├── mise/
│   ├── MiseCli.java
│   ├── MiseMaintenanceService.java
│   ├── MiseQueryService.java
│   ├── MiseToolService.java
│   ├── ShellActivationService.java
│   └── model/
└── tui/
    ├── MiseTuiApp.java
    ├── UiContext.java
    ├── UiState.java
    ├── MiseActions.java
    └── panel/
```

## Architecture notes

### Core components

1. `MiseCli`
   - launches `mise` subprocesses
   - captures combined output safely
   - supports timeout-aware execution and streaming output

2. `MiseQueryService`, `MiseToolService`, `MiseMaintenanceService`
   - expose the higher-level operations that the UI performs
   - translate between `mise` command results and structured TUI state

3. `MiseTuiApp`
   - owns the top-level layout, key bindings, and lifecycle
   - coordinates panels through the shared `UiState` and `MiseActions`

4. `panel/`
   - contains the UI panels and modal overlays used by the TUI

### Runtime model

- Spring Boot manages application wiring and startup.
- An `AsyncTaskExecutor` is configured for virtual-thread-backed background work.
- The app uses lazy initialization and a virtual-thread-enabled Spring configuration for efficient startup and background task handling.

### Build profiles

- **default** — produces an executable JAR (`./mvnw package`)
- **native** — produces a GraalVM native image (used by `./deploy-tambo.sh`), faster startup with no JVM overhead
- **pgo** — uses profile-guided optimization on top of the native profile for additional runtime performance

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

### `mise: command not found`

- confirm that `mise` is installed and on your `PATH`
- run `mise --help` to verify the CLI is available

### UI is not rendering cleanly

- use a terminal with reasonable width and height
- the TUI supports a compact accordion layout for smaller terminals

## License

Check the repository for the project license details.

## References

- [mise documentation](https://mise.jdx.dev)
- [TamboUI documentation](https://tamboui.dev)
- [Spring Boot documentation](https://docs.spring.io/spring-boot/)
- [Java virtual threads](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/VirtualThread.html)
