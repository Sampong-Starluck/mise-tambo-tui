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

- Java 25+
- Maven 3.6+
- `mise` installed and available on your `PATH`

## Quick start

### Build

```bash
./mvnw clean package
```

This produces an executable JAR in `target/tambo-0.0.1.jar`.

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

- `?` — open help overlay
- `a` — open the registry modal
- `A` — activate `mise` shell integration
- `e` — edit the project `mise.toml`
- `E` — edit the global `mise` config
- `D` — run `mise doctor`
- `U` — self-update the `mise` backend
- `r` — refresh the current UI state
- `1` / `2` / `3` / `4` — jump to status, tools, env, and tasks panels
- `j` / `k`, arrow keys, `Home`, `End`, `Page Up`, `Page Down` — navigate lists

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
