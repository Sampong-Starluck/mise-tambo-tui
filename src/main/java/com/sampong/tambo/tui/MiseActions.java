package com.sampong.tambo.tui;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.core.task.AsyncTaskExecutor;

import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseService;
import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;

/**
 * All mise operations the UI can trigger. Every call returns immediately: the
 * blocking CLI work runs on the injected executor (virtual threads), and results
 * are published back into {@link UiState} on the render thread.
 */
public final class MiseActions {

    /** Everything the dynamic panels render from, fetched in one background pass. */
    private record Snapshot(List<ToolVersion> tools, List<MiseTask> tasks,
                            Map<String, String> env, DoctorInfo doctor) {
    }

    private final MiseService mise;
    private final AsyncTaskExecutor executor;
    private final UiState state;
    /** Marshals a runnable onto the TUI render thread. */
    private final Consumer<Runnable> uiThread;

    public MiseActions(MiseService mise, AsyncTaskExecutor executor, UiState state, Consumer<Runnable> uiThread) {
        this.mise = mise;
        this.executor = executor;
        this.state = state;
        this.uiThread = uiThread;
    }

    // ==================== Loading / refresh ====================

    public void loadInitial() {
        submitBackground("initial load",
                () -> {
                    List<RegistryEntry> registry = mise.listRegistry();
                    Snapshot snapshot = takeSnapshot();
                    return Map.entry(registry, snapshot);
                },
                loaded -> {
                    state.registry(loaded.getKey());
                    applySnapshot(loaded.getValue());
                    state.loading(false);
                    state.addLog(LogLevel.OK, "Loaded " + state.tools().size() + " tools, "
                            + state.tasks().size() + " tasks, " + state.registry().size() + " registry entries");
                });
    }

    public void refresh() {
        state.addLog(LogLevel.INFO, "Refreshing…");
        submitBackground("refresh",
                this::takeSnapshot,
                snapshot -> {
                    applySnapshot(snapshot);
                    state.addLog(LogLevel.OK, "Refreshed " + state.tools().size() + " tools, "
                            + state.tasks().size() + " tasks");
                });
    }

    private Snapshot takeSnapshot() {
        return new Snapshot(mise.listTools(), mise.listTasks(), mise.listEnv(), mise.doctor());
    }

    private void applySnapshot(Snapshot snapshot) {
        state.tools(snapshot.tools());
        state.tasks(snapshot.tasks());
        state.env(snapshot.env());
        state.doctor(snapshot.doctor());
    }

    // ==================== Tool operations ====================

    public void installTool(ToolVersion t) {
        String key = t.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise install " + key);
        submitBackground("install " + key,
                () -> mise.install(key),
                result -> {
                    state.clearBusy(key);
                    logResult(result, "Installed " + key, "Install failed: " + key);
                    refresh();
                });
    }

    public void uninstallTool(ToolVersion t) {
        String key = t.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise uninstall " + key);
        submitBackground("uninstall " + key,
                () -> mise.uninstall(key),
                result -> {
                    state.clearBusy(key);
                    logResult(result, "Uninstalled " + key, "Uninstall failed: " + key);
                    refresh();
                });
    }

    /** Runs {@code mise use [-g] tool@version} — installs and writes the config entry. */
    public void useTool(String toolAtVersion, boolean global) {
        String shortName = toolAtVersion.contains("@")
                ? toolAtVersion.substring(0, toolAtVersion.indexOf('@'))
                : toolAtVersion;
        String key = "registry:" + shortName;
        if (state.markBusy(key)) {
            return;
        }
        String args = global ? "mise use -g " + toolAtVersion : "mise use " + toolAtVersion;
        state.addLog(LogLevel.CMD, "$ " + args);
        submitBackground(args,
                () -> mise.use(toolAtVersion, global),
                result -> {
                    state.clearBusy(key);
                    logResult(result, "Installed & configured " + toolAtVersion, "Failed: " + args);
                    refresh();
                });
    }

    // ==================== Tasks ====================

    public void runTask(MiseTask t) {
        String key = "task:" + t.name();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise run " + t.name());
        submitBackground("run " + t.name(),
                () -> mise.runTask(t.name()),
                result -> {
                    state.clearBusy(key);
                    state.addCommandOutput(result);
                    logResult(result, "Task \"" + t.name() + "\" finished", "Task \"" + t.name() + "\" failed");
                });
    }

    // ==================== Shell activation ====================

    /** Installs the {@code mise activate} line into the user's shell rc file. */
    public void activateMise() {
        String key = "activate";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise activate — writing the activation line to your shell rc");
        submitBackground("activate",
                mise::activateInShell,
                outcome -> {
                    state.clearBusy(key);
                    LogLevel level = !outcome.ok() ? LogLevel.ERROR : outcome.changed() ? LogLevel.OK : LogLevel.INFO;
                    state.addLog(level, outcome.message());
                    refresh();
                });
    }

    // ==================== Registry ====================

    /** Fetches installable versions of a tool; {@code onDone} runs on the render thread. */
    public void fetchRemoteVersions(String tool, Consumer<List<String>> onDone) {
        state.addLog(LogLevel.CMD, "$ mise ls-remote " + tool);
        submitBackground("ls-remote " + tool,
                () -> mise.listRemoteVersions(tool),
                versions -> {
                    state.addLog(LogLevel.OK, (versions.size() - 1) + " versions of " + tool + " available");
                    onDone.accept(versions);
                });
    }

    // ==================== Plumbing ====================

    private <T> void submitBackground(String label, Supplier<T> work, Consumer<T> onDone) {
        executor.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                uiThread.accept(() -> state.addLog(LogLevel.ERROR, label + " failed: " + e.getMessage()));
                return;
            }
            uiThread.accept(() -> onDone.accept(result));
        });
    }

    private void logResult(MiseCli.Result result, String okMessage, String failMessagePrefix) {
        if (result.ok()) {
            state.addLog(LogLevel.OK, okMessage);
        } else {
            String detail = result.summaryLine();
            state.addLog(LogLevel.ERROR, failMessagePrefix + (detail.isBlank() ? "" : ": " + detail));
        }
    }
}
