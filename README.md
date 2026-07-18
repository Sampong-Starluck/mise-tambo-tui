# A TUI for Mise (mise-en-place)

A lazygit-style terminal UI for [mise](https://mise.jdx.dev), a polyglot runtime manager. Tambo provides an interactive, keyboard-driven interface to manage tools, versions, tasks, and environments without typing `mise` commands manually.

## Overview

Tambo is a **Spring Boot CLI application** that wraps the `mise` command-line tool with a modern terminal user interface (TUI). It's built with Java 25, Spring Boot 4.1.0, and [TamboUI](https://tamboui.dev) — a fluent Java DSL for building terminal applications.

The application is designed for users who prefer graphical terminal interfaces (like `lazygit` for Git) over traditional command-line tools.

### Design Inspiration: Lazygit

Tambo's UI design is **heavily inspired by [lazygit](https://github.com/jesseduffield/lazygit)**, a popular Git TUI. Like lazygit, Tambo features:

- **Multi-panel layout** with focused views for different concerns (tools, tasks, environment, logs)
- **Vim-style keybindings** for fast, productive navigation
- **Real-time operation feedback** with status indicators and comprehensive logging
- **Keyboard-first workflow** — no mouse required; discover commands via the help overlay
- **Stateful UI** that remembers selections and context between operations

The goal is to bring the same "TUI-first developer experience" that made lazygit so successful for Git workflows to the `mise` polyglot runtime manager.

## Features

### Panels & Views
- **Tools Panel** — Browse, install, uninstall, and manage tool versions
- **Environment Panel** — View and modify tool environment settings
- **Tasks Panel** — Discover and run tasks defined in your project
- **Status Panel** — Monitor `mise` status and health
- **Detail Panel** — View detailed information about selected items
- **Log Panel** — Track all operations and their results with severity levels
- **Registry Modal** — Manage the mise registry
- **Help Overlay** — Discover keyboard shortcuts and commands

### User Interactions
- **Fuzzy Search/Filtering** — fzf-style fuzzy matching for finding tools, tasks, and other items
- **Vim-Style Navigation** — j/k keys alongside arrow keys, Home, End, Page Up/Down support
- **Operation Status** — Real-time indicators for in-flight `mise` operations
- **Command Logging** — Comprehensive log of all executed operations with severity levels (ERROR, INFO, WARN, SUCCESS)
- **Batched Data Loading** — Efficient snapshot-based loading of tools, tasks, environment, and status in a single background pass

## Prerequisites

- **Java 25+** (for virtual threads support)
- **Maven 3.6+**
- **mise** installed and available in `$PATH`

## Building

```bash
./mvnw clean package
```

This generates a JAR file at `target/tambo-0.0.1.jar.original`.

## Running

```bash
java -jar target/tambo-0.0.1.jar.original
```

Or use Spring Boot Maven plugin:

```bash
./mvnw spring-boot:run
```

## Project Structure

```
src/main/java/com/sampong/tambo/
├── TamboApplication.java           # Spring Boot entry point
├── mise/
│   ├── MiseCli.java               # Subprocess executor for `mise` CLI
│   ├── MiseService.java           # High-level facade over MiseCli
│   └── model/                      # Data models (ToolVersion, RegistryEntry, etc.)
└── tui/
    ├── MiseTuiApp.java            # Main TUI orchestrator
    ├── UiContext.java             # Interface for panels to interact with app
    ├── UiState.java               # Shared state for all panels
    ├── MiseActions.java           # Background operations
    ├── panel/                      # Individual UI panels
    │   ├── StatusPanel.java
    │   ├── ToolsPanel.java
    │   ├── EnvPanel.java
    │   ├── TasksPanel.java
    │   ├── DetailPanel.java
    │   ├── LogPanel.java
    │   ├── RegistryModal.java
    │   └── HelpOverlay.java
    └── Ui.java                     # UI utilities and helpers
```

## Architecture

### Key Components

1. **MiseCli** — Subprocess manager
   - Executes `mise` commands with timeout support
   - Captures stdout/stderr
   - Returns structured `Result` objects

2. **MiseService** — Business logic layer
   - Parses JSON output from `mise` CLI
   - Provides type-safe operations (listTools, installTool, runTask, etc.)
   - Runs blocking operations on background threads

3. **MiseTuiApp** — TUI orchestrator
   - Lifecycle management
   - Top-level layout and key bindings
   - Component coordination through `UiContext`

4. **Panels** — Stateless, composable UI components
   - Render from shared `UiState`
   - React to user input
   - Delegate mutations through `MiseActions`

5. **Fuzzy Matcher** — fzf-style substring matching
   - Scores queries with consecutive matches and word boundaries weighted higher
   - Used across panels for fast filtering

6. **Log System** — Command execution tracking
   - `LogEntry` records represent each operation with severity (`LogLevel`)
   - Circular buffer (max 300 entries) prevents memory bloat
   - Synchronized between background operations and render thread

### Threading Model

- Uses **Java 25 virtual threads** for efficient async operations
- UI operations run on the main thread
- Long-running operations (CLI calls) dispatched to background threads via `AsyncTaskExecutor`
- Configuration in `application.yaml` enables virtual threads via `spring.threads.virtual.enabled`

## Technology Stack

- **Spring Boot 4.1.0** — Dependency injection, auto-configuration
- **TamboUI 0.4.0** — Terminal UI framework with fluent Java DSL
- **Jackson** — JSON parsing for `mise` output
- **Java 25** — Virtual threads, Project Panama (FFM backend)
- **Maven** — Build and dependency management

## Configuration

Application settings are in `src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: tambo
  main:
    lazy-initialization: true  # Defer bean instantiation until needed
  threads:
    virtual:
      enabled: true            # Use Java 25 virtual threads

logging:
  level:
    root: warn
```

## Keyboard Controls

Tambo supports flexible keyboard navigation inspired by vim and standard terminal UIs:

| Key(s)             | Action                                 |
|--------------------|----------------------------------------|
| `j` / `↓`          | Move down in list                      |
| `k` / `↑`          | Move up in list                        |
| `Home` / `g`       | Jump to start                          |
| `End` / `G`        | Jump to end                            |
| `Page Up`          | Scroll up 10 rows                      |
| `Page Down`        | Scroll down 10 rows                    |
| `?`                | Show help overlay                      |
| **Panel-specific** | Check help overlay for panel shortcuts |

## Development

### Building from Source

```bash
./mvnw clean compile
```

### Running Tests

```bash
./mvnw test
```

### IDE Setup

- Import as Maven project in IntelliJ IDEA, VS Code, or Eclipse
- Set project JDK to Java 25+
- Enable annotation processing for Lombok

## Troubleshooting

**"mise: command not found"**
- Ensure `mise` is installed and in your `$PATH`
- Run `which mise` to verify

**"OpenGL not available" (FFM-related)**
- Some systems may require additional graphics libraries
- Check TamboUI documentation for platform-specific requirements

**Slow startup**
- Spring lazy initialization is enabled to improve startup time
- First interaction may load beans on-demand

## License

Check the project repository for licensing information.

## References

- [Mise Documentation](https://mise.jdx.dev)
- [TamboUI Documentation](https://tamboui.dev)
- [Spring Boot Documentation](https://docs.spring.io/spring-boot/)
- [Java 25 Virtual Threads](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/VirtualThread.html)
