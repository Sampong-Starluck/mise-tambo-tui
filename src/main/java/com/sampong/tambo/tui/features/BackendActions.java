package com.sampong.tambo.tui.features;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.core.task.AsyncTaskExecutor;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo._common.model.AutoInstallStep;
import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.OutdatedSdk;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.service.AutoInstallPlanner;
import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo._common.util.ProjectToolsReader;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo.tui.state.Lazy;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.UiState;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Every operation the UI can trigger, against whichever backend this session resolved to. Each
 * call returns immediately: the blocking CLI work runs on the injected executor (virtual
 * threads), and results are published back into {@link UiState} on the render thread.
 * Long-running commands stream their output into the command log live, line by line.
 * <p>
 * This used to hold a mise service trio plus the vfox backend and pick between them per action,
 * which is why half of it read "if vfox … else mise". It now holds exactly one
 * {@link SdkVersionBackend} and asks it what it supports, so an operation the backend cannot do
 * is refused in one place, with the backend's own explanation, instead of at every call site.
 */
@RequiredArgsConstructor
public final class BackendActions {

    @NonNull
    private final SdkVersionBackend backend;
    /**
     * Writing the activation line into the user's shell startup file: a filesystem operation on
     * the user's profile rather than a CLI call, so it stays its own service rather than
     * becoming part of the backend contract.
     */
    @NonNull
    private final ShellActivationService activation;
    @NonNull
    private final CancelRegistry cancelRegistry;
    @NonNull
    private final AsyncTaskExecutor executor;
    @NonNull
    private final UiState state;
    /** Marshals a runnable onto the TUI render thread. */
    @NonNull
    private final Consumer<Runnable> uiThread;

    public SdkVersionBackend backend() {
        return backend;
    }

    // ==================== Loading / refresh ====================

    /**
     * Starts the P0 tier before the first frame is composed. Nothing else is fetched here:
     * {@link #ensureBackendInfo()}, {@link #ensureOutdated()} and {@link #ensureCatalog()} pull
     * in the slower tiers when a surface that actually needs them first renders.
     */
    public void loadInitial() {
        loadEssentials();
    }

    /**
     * Re-reads P0 and marks the derived views stale so whichever surface is on screen reloads
     * them in the background. The catalog is deliberately left alone — it lists what the backend
     * <em>can</em> install, which no local install, uninstall or trust change affects.
     */
    public void refresh() {
        state.addLog(LogLevel.INFO, "Refreshing…");
        state.sdksLazy().invalidate();
        state.tasksLazy().invalidate();
        state.envLazy().invalidate();
        state.trustLazy().invalidate();
        state.backendInfoLazy().invalidate();
        state.outdatedLazy().invalidate();
        loadEssentials();
    }

    /**
     * P0 — the handful of ~10 ms calls behind everything the user reads and acts on straight
     * away. Each publishes into {@link UiState} on its own instead of through a joined snapshot,
     * so no single call can hold up the rest, and each is skipped outright when the backend has
     * no such concept rather than shelling out to find that out.
     */
    private void loadEssentials() {
        loadLazy(state.sdksLazy(), "sdks", backend::listSdks,
                loaded -> state.addLog(LogLevel.OK, "Loaded " + loaded.size() + " tools"));
        if (backend.supports(BackendFeature.TASKS)) {
            loadLazy(state.tasksLazy(), "tasks", backend::listTasks,
                    loaded -> state.addLog(LogLevel.OK, "Loaded " + loaded.size() + " tasks"));
        }
        if (backend.supports(BackendFeature.ENV)) {
            loadLazy(state.envLazy(), "env", backend::listEnv, null);
        }
        if (backend.supports(BackendFeature.TRUST)) {
            loadLazy(state.trustLazy(), "trust", backend::trustStatus, null);
        }
    }

    // ==================== Lazy tiers ====================

    /**
     * P1 — the backend's own version and health ({@code mise doctor}, ~290 ms; {@code vfox -v},
     * much less). Fills the header and Status surface: on screen from the first frame, but
     * nothing the user acts on in that first moment, so it loads on first render rather than
     * delaying the frame.
     */
    public void ensureBackendInfo() {
        loadLazy(state.backendInfoLazy(), "backend info", backend::info, null);
    }

    /**
     * P2 — which SDKs are outdated (~1.2 s cold, network). Only decorates rows with
     * "↑ version"; every row renders correctly without it, so it is pulled in behind the
     * already-visible list. Skipped entirely by backends that cannot answer.
     */
    public void ensureOutdated() {
        if (!backend.supports(BackendFeature.UPGRADE) || state.offline()) {
            return;
        }
        loadLazy(state.outdatedLazy(), "outdated", () -> toOutdatedMap(backend.listOutdated()), null);
    }

    /**
     * P3 — the catalog browsed by the Add-SDK modal. Never fetched unless the user opens it.
     */
    public void ensureCatalog() {
        loadLazy(state.catalogLazy(), "catalog", backend::listCatalog, null);
    }

    /**
     * Runs {@code work} on the executor and publishes it into {@code lazy}, but only if the
     * holder actually wants a load — safe to call from a render method that runs every frame.
     * On failure the claim is released so the next frame retries rather than leaving the panel
     * stuck on its placeholder.
     */
    private <T> void loadLazy(Lazy<T> lazy, String label, Supplier<T> work, @Nullable Consumer<T> onLoaded) {
        int token = lazy.claim();
        if (token == Lazy.NO_LOAD) {
            return;
        }
        executor.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                uiThread.accept(() -> {
                    lazy.fail(token);
                    state.addLog(LogLevel.ERROR, label + " failed: " + e.getMessage());
                });
                return;
            }
            uiThread.accept(() -> {
                lazy.publish(token, result);
                if (onLoaded != null) {
                    onLoaded.accept(result);
                }
            });
        });
    }

    /** Flattens the outdated list into an SDK → latest-version lookup, ignoring entries without a target. */
    private static Map<String, String> toOutdatedMap(List<OutdatedSdk> outdated) {
        Map<String, String> map = new HashMap<>();
        for (OutdatedSdk o : outdated) {
            if (o.latest() != null && !o.latest().isBlank()) {
                map.put(o.name(), o.latest());
            }
        }
        return map;
    }

    // ==================== SDK operations ====================

    public void installSdk(@NonNull SdkVersion sdk) {
        if (blockedOffline("installing")) {
            return;
        }
        String key = sdk.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " install " + key);
        submitBackground("install " + key, key,
                () -> backend.install(key, liveLogLine(key), key),
                result -> {
                    logResult(result, "Installed " + key, "Install failed: " + key);
                    refresh();
                });
    }

    public void uninstallSdk(@NonNull SdkVersion sdk) {
        String key = sdk.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " uninstall " + key);
        submitBackground("uninstall " + key, key,
                () -> backend.uninstall(key),
                result -> {
                    logResult(result, "Uninstalled " + key, "Uninstall failed: " + key);
                    refresh();
                });
    }

    /**
     * Drops the entry from whichever scope (project or global) declares it, pruning the
     * installed version too where the backend does that automatically (mise; vfox's
     * {@code unuse} does not).
     */
    public void removeSdk(@NonNull SdkVersion sdk) {
        String key = sdk.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " unuse " + key);
        submitBackground("remove " + key, key,
                () -> backend.remove(key),
                result -> {
                    logResult(result, "Removed " + key, "Remove failed: " + key);
                    refresh();
                });
    }

    /**
     * Removes the plugin behind the selected SDK. Busy under {@code remove-plugin:<name>} so
     * every row of that SDK shows a spinner, not just the selected version.
     */
    public void removePlugin(@NonNull SdkVersion sdk) {
        String plugin = sdk.name();
        String key = "remove-plugin:" + plugin;
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " remove plugin " + plugin);
        submitBackground("remove plugin " + plugin, key,
                () -> backend.removePlugin(plugin),
                result -> {
                    logResult(result, "Removed plugin " + plugin, "Remove plugin failed: " + plugin);
                    refresh();
                });
    }

    /** Installs and pins {@code sdk@version} at project or global scope. */
    public void useSdk(@NonNull String sdkAtVersion, boolean global) {
        if (blockedOffline("installing")) {
            return;
        }
        if (global && !backend.supports(BackendFeature.GLOBAL_SCOPE)) {
            state.addLog(LogLevel.INFO, backend.unsupportedReason(BackendFeature.GLOBAL_SCOPE));
            return;
        }
        String shortName = sdkAtVersion.contains("@")
                ? sdkAtVersion.substring(0, sdkAtVersion.indexOf('@'))
                : sdkAtVersion;
        String key = "registry:" + shortName;
        if (state.markBusy(key)) {
            return;
        }
        String args = backend.name() + " use " + (global ? "-g " : "") + sdkAtVersion;
        state.addLog(LogLevel.CMD, "$ " + args);
        submitBackground(args, key,
                () -> backend.use(sdkAtVersion, global, liveLogLine(key), key),
                result -> {
                    logResult(result,
                            global ? "Set " + sdkAtVersion + " as global default"
                                    : "Applied " + sdkAtVersion + " at project scope",
                            "Failed: " + args);
                    refresh();
                });
    }

    /** Upgrades a single SDK to the newest version its config allows, then refreshes. */
    public void upgradeSdk(@NonNull SdkVersion sdk) {
        if (refuseUnsupported(BackendFeature.UPGRADE) || blockedOffline("upgrading")) {
            return;
        }
        String key = "upgrade:" + sdk.name();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " upgrade " + sdk.name());
        submitBackground("upgrade " + sdk.name(), key,
                () -> backend.upgrade(sdk.name(), liveLogLine(key), key),
                result -> {
                    logResult(result, "Upgraded " + sdk.name(), "Upgrade failed: " + sdk.name());
                    refresh();
                });
    }

    /** Upgrades every outdated SDK, then refreshes. */
    public void upgradeAll() {
        if (refuseUnsupported(BackendFeature.UPGRADE) || blockedOffline("upgrading")) {
            return;
        }
        String key = "upgrade:*";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " upgrade");
        submitBackground("upgrade all", key,
                () -> backend.upgrade("", liveLogLine(key), key),
                result -> {
                    logResult(result, "Upgraded all outdated tools", "Upgrade failed");
                    refresh();
                });
    }

    // ==================== Auto-install from the project config ====================

    /** Busy key for both phases of auto-install; also what {@code C} cancels it under. */
    private static final String AUTO_INSTALL_KEY = "auto-install";

    /**
     * Brings the project in line with its own config: reads the {@code [tools]} table, compares
     * it against what is installed, and applies the difference.
     * <p>
     * The comparison is the interesting part, and it is
     * {@link AutoInstallPlanner}'s: a tool whose config version is already on disk needs at most
     * a pin, one with nothing close needs a download, and one where the two differ by a patch
     * or a minor — 25.0.3 installed against a config asking for 25.0.4 — is a question rather
     * than a decision this can make, so it goes through {@code prompt} first. That case is not
     * special-cased per SDK; it is whatever the planner's same-vendor, same-major rule matches,
     * for every tool the config declares.
     * <p>
     * Nothing runs until every question is answered, so the command log reads as one operation
     * rather than as installs interleaved with dialogs.
     * <p>
     * Offline it still runs, against what is already on disk: a tool whose config version is
     * missing is offered the installed versions of that tool, closest first, and whichever the
     * user picks is pinned — a local config edit, no download. A tool with nothing installed is
     * reported as unavailable rather than attempted.
     */
    public void autoInstall(@NonNull AutoInstallPrompt prompt) {
        if (state.markBusy(AUTO_INSTALL_KEY)) {
            return;
        }
        String configName = backend.projectConfigFileName();
        boolean offline = state.offline();
        state.addLog(LogLevel.CMD, "$ auto-install — reading " + configName
                + (offline ? " (offline: using installed versions only)" : ""));
        submitBackground("auto-install", AUTO_INSTALL_KEY,
                () -> AutoInstallPlanner.plan(ProjectToolsReader.read(projectConfigPath()), backend.listSdks(), offline),
                steps -> onPlanned(steps, prompt));
    }

    /**
     * The version the project config declares for {@code sdk}, or null when it declares none.
     * Matched case-insensitively: vfox lower-cases the names it lists, while the config keeps
     * whatever spelling the project wrote. A single small file read, so fine on the render thread.
     */
    public @Nullable String declaredVersion(@NonNull String sdk) {
        for (Map.Entry<String, String> tool : ProjectToolsReader.read(projectConfigPath()).entrySet()) {
            if (tool.getKey().equalsIgnoreCase(sdk)) {
                return tool.getValue();
            }
        }
        return null;
    }

    /** The project config this backend reads, in the directory tambo was launched from. */
    private Path projectConfigPath() {
        return Path.of("").toAbsolutePath().resolve(backend.projectConfigFileName());
    }

    /**
     * Reports the plan, then either asks about its near-misses or goes straight to running it.
     * Runs on the render thread — it may open a modal.
     */
    private void onPlanned(List<AutoInstallStep> steps, AutoInstallPrompt prompt) {
        String configName = backend.projectConfigFileName();
        if (steps.isEmpty()) {
            state.addLog(LogLevel.INFO, "No tools declared in " + configName + " — nothing to install");
            return;
        }
        long satisfied = steps.stream().filter(s -> s.status() == AutoInstallStep.Status.SATISFIED).count();
        List<AutoInstallStep> undecided = steps.stream()
                .filter(s -> s.status() == AutoInstallStep.Status.SIMILAR
                        || s.status() == AutoInstallStep.Status.SUBSTITUTE)
                .toList();
        long toFetch = steps.stream().filter(s -> s.status() == AutoInstallStep.Status.MISSING).count();
        state.addLog(LogLevel.INFO, configName + " declares " + steps.size() + " tool(s): "
                + satisfied + " already installed, " + toFetch
                + (state.offline() ? " not installed and unavailable offline, " : " to install, ")
                + undecided.size() + " to choose");

        if (undecided.isEmpty()) {
            runPlan(steps, Map.of());
            return;
        }
        prompt.ask(undecided,
                choices -> runPlan(steps, choices),
                () -> state.addLog(LogLevel.INFO, "Auto-install cancelled"));
    }

    /**
     * Turns a plan plus the user's answers into commands and runs them in order: pins first,
     * then installs, so a bulk install sees a config that already reflects every reused version.
     * <p>
     * Only an explicit "keep what is installed" answer produces a pin, because a pin rewrites
     * the config. A tool the config is already satisfied by is left completely alone: pinning
     * it would replace whatever the project wrote — a {@code ["20", "18"]} fallback list, a
     * {@code "latest"} — with the single concrete version that happens to be on this machine,
     * which is a change to the project nobody asked for.
     *
     * @param chosen the version to apply per SDK for the steps that needed a question; an SDK
     *               the user skipped is absent
     */
    private void runPlan(List<AutoInstallStep> steps, Map<String, String> chosen) {
        AutoInstallCommands commands = new AutoInstallCommands();
        for (AutoInstallStep step : steps) {
            resolve(step, chosen.get(step.sdk()), commands);
        }
        if (commands.isEmpty()) {
            state.addLog(commands.skipped > 0 ? LogLevel.INFO : LogLevel.OK, commands.skipped > 0
                    ? "Nothing applied — " + commands.skipped + " tool(s) skipped or unavailable"
                    : "Nothing to do — the project already matches " + backend.projectConfigFileName());
            return;
        }
        execute(commands);
    }

    /** What a plan comes down to: versions to pin in the config, versions to install, and how many were left out. */
    private static final class AutoInstallCommands {
        private final List<String> pins = new ArrayList<>();
        private final List<String> installs = new ArrayList<>();
        private int skipped;

        boolean isEmpty() {
            return pins.isEmpty() && installs.isEmpty();
        }
    }

    /**
     * Adds one step's command to {@code commands}. {@code version} is the user's answer for a
     * step that needed a question, or null when they skipped it (or it needed none).
     */
    private void resolve(AutoInstallStep step, @Nullable String version, AutoInstallCommands commands) {
        switch (step.status()) {
            case SATISFIED -> {
                // Already answered by something on disk — see the note on runPlan.
            }
            case MISSING -> resolveMissing(step, commands);
            case SUBSTITUTE, SIMILAR -> resolveAnswered(step, version, commands);
        }
    }

    /** A tool with nothing usable on disk: installed from the config, unless offline. */
    private void resolveMissing(AutoInstallStep step, AutoInstallCommands commands) {
        if (state.offline()) {
            state.addLog(LogLevel.ERROR, "Auto-install — " + step.requestedLabel()
                    + " is not installed and cannot be downloaded offline");
            commands.skipped++;
        } else {
            commands.installs.add(step.requestedLabel());
        }
    }

    /**
     * A tool the user was asked about. A substitute is by definition already on disk, and
     * keeping an installed similar version means the config has to change (the user chose that
     * when they answered) — both are pins. Anything else is a version still to download.
     */
    private static void resolveAnswered(AutoInstallStep step, @Nullable String version, AutoInstallCommands commands) {
        if (version == null) {
            commands.skipped++;
            return;
        }
        boolean onDisk = step.status() == AutoInstallStep.Status.SUBSTITUTE
                || version.equals(step.installedVersion());
        (onDisk ? commands.pins : commands.installs).add(step.sdk() + "@" + version);
    }

    /**
     * Runs the whole plan on one background task, so its commands and their streamed output
     * reach the log in the order they happened rather than racing each other.
     * <p>
     * The install phase has two shapes. Where the backend can install a whole config at once
     * ({@code mise install}) that is one command for every missing tool, and it installs the
     * versions the config already names rather than rewriting anything — but only when nothing
     * was skipped, since it would happily install the very tool the user just declined. vfox has
     * no such command, and a skip rules the bulk form out anyway, so the fallback applies each
     * tool individually with {@code use}, which installs and pins it at project scope.
     */
    private void execute(AutoInstallCommands commands) {
        if (state.markBusy(AUTO_INSTALL_KEY)) {
            return;
        }
        boolean bulk = !commands.installs.isEmpty() && commands.skipped == 0
                && backend.supports(BackendFeature.BULK_INSTALL);
        String installPart = bulk
                ? backend.name() + " install for " + commands.installs.size()
                : commands.installs.size() + " to install";
        String skippedPart = commands.skipped > 0 ? " (" + commands.skipped + " skipped)" : "";
        state.addLog(LogLevel.CMD, "$ auto-install — " + commands.pins.size() + " to pin, "
                + installPart + skippedPart);
        Consumer<String> onLine = liveLogLine(AUTO_INSTALL_KEY);

        submitBackground("auto-install", AUTO_INSTALL_KEY,
                () -> applyAutoInstall(commands, bulk, onLine),
                this::reportAutoInstall);
    }

    /** The background half of {@link #execute}: pins, then installs. Returns one line per failure. */
    private List<String> applyAutoInstall(AutoInstallCommands commands, boolean bulk, Consumer<String> onLine) {
        List<String> failures = new ArrayList<>();
        for (String label : commands.pins) {
            CliResult result = backend.pin(label, false);
            onLine.accept(result.ok() ? "pinned " + label : "could not pin " + label);
            recordFailure(failures, label, result);
        }
        if (bulk) {
            recordFailure(failures, backend.name() + " install", backend.installAll(onLine, AUTO_INSTALL_KEY));
        } else {
            for (String label : commands.installs) {
                recordFailure(failures, label, backend.use(label, false, onLine, AUTO_INSTALL_KEY));
            }
        }
        return failures;
    }

    private static void recordFailure(List<String> failures, String what, CliResult result) {
        if (!result.ok()) {
            failures.add(what + ": " + result.summaryLine());
        }
    }

    private void reportAutoInstall(List<String> failures) {
        if (failures.isEmpty()) {
            state.addLog(LogLevel.OK, "Auto-install finished — project matches "
                    + backend.projectConfigFileName());
        } else {
            failures.forEach(failure -> state.addLog(LogLevel.ERROR, "Auto-install — " + failure));
        }
        refresh();
    }

    // ==================== Cancellation ====================

    /** Kills any in-flight install / use / upgrade for the given SDK. */
    public void cancelSdk(@NonNull SdkVersion sdk) {
        cancelFirst("Cancelled " + sdk.name(), sdk.label(), "registry:" + sdk.name(), "upgrade:" + sdk.name());
    }

    /** Kills the given task if it is currently running. */
    public void cancelTask(@NonNull String taskName) {
        cancelFirst("Cancelled task " + taskName, "task:" + taskName);
    }

    /**
     * Cancels the first of {@code keys} that is running. Failing that, falls back to the single
     * operation that <em>is</em> running, if there is exactly one: 'c' is bound per-surface and
     * cancels whatever the cursor sits on, so moving the selection off a running task — or
     * switching surfaces to watch its output — used to report "nothing running" while the build
     * carried on in the background.
     * <p>
     * The fallback stays off when several operations are in flight, since there is no way to
     * guess which one was meant; {@link #cancelAll()} handles that case.
     */
    private void cancelFirst(String message, String... keys) {
        for (String key : keys) {
            if (cancelRegistry.cancel(key)) {
                state.addLog(LogLevel.INFO, message);
                return;
            }
        }

        Set<String> live = cancelRegistry.runningKeys();
        if (live.isEmpty()) {
            state.addLog(LogLevel.INFO, "Nothing running to cancel");
            return;
        }
        if (live.size() > 1) {
            state.addLog(LogLevel.INFO, live.size() + " operations running ("
                    + live.stream().map(BackendActions::sourceLabel).sorted().collect(Collectors.joining(", "))
                    + ") — press C to cancel all");
            return;
        }

        String only = live.iterator().next();
        if (cancelRegistry.cancel(only)) {
            state.addLog(LogLevel.INFO, "Cancelled " + sourceLabel(only));
        } else {
            state.addLog(LogLevel.INFO, "Nothing running to cancel");
        }
    }

    /** Kills every in-flight operation, whatever surface or selection is active. */
    public void cancelAll() {
        Set<String> cancelled = cancelRegistry.cancelAll();
        if (cancelled.isEmpty()) {
            state.addLog(LogLevel.INFO, "Nothing running to cancel");
            return;
        }
        state.addLog(LogLevel.INFO, "Cancelled "
                + cancelled.stream().map(BackendActions::sourceLabel).sorted().collect(Collectors.joining(", ")));
    }

    // ==================== Tasks ====================

    /** Runs a task with no extra arguments. */
    public void runTask(@NonNull ProjectTask task) {
        runTask(task.name(), "");
    }

    /** Runs a task, remembering it for {@link #reRunLastTask()}. */
    public void runTask(@NonNull String taskName, @NonNull String args) {
        if (refuseUnsupported(BackendFeature.TASKS)) {
            return;
        }
        String key = "task:" + taskName;
        if (state.markBusy(key)) {
            return;
        }
        state.lastTaskName(taskName);
        state.lastTaskArgs(args);
        String display = args.isBlank() ? taskName : taskName + " -- " + args;
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " run " + display);
        submitBackground("run " + taskName, key,
                () -> backend.runTask(taskName, args, liveLogLine(key), key),
                result -> logResult(result, "Task \"" + taskName + "\" finished", "Task \"" + taskName + "\" failed"));
    }

    /** Re-runs the last task (with its previous args); logs a hint when nothing has run yet. */
    public void reRunLastTask() {
        if (refuseUnsupported(BackendFeature.TASKS)) {
            return;
        }
        String last = state.lastTaskName();
        if (last == null) {
            state.addLog(LogLevel.INFO, "No task has been run yet");
            return;
        }
        runTask(last, state.lastTaskArgs());
    }

    // ==================== Shell activation ====================

    /** Installs the backend's activation line into the user's shell startup file. */
    public void activateShell() {
        String key = "activate";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name()
                + " activate — writing the activation line to your shell profile");
        submitBackground("activate", key,
                activation::activateInShell,
                outcome -> {
                    LogLevel level = !outcome.ok() ? LogLevel.ERROR : outcome.changed() ? LogLevel.OK : LogLevel.INFO;
                    state.addLog(level, outcome.message());
                    refresh();
                });
    }

    /** Marks this project's config trusted so the backend is allowed to parse it. */
    public void trustProject() {
        if (refuseUnsupported(BackendFeature.TRUST)) {
            return;
        }
        String key = "trust";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " trust");
        submitBackground("trust", key,
                backend::trust,
                result -> {
                    logResult(result, "Config trusted — " + backend.name() + " will now load this project's "
                            + backend.projectConfigFileName(), backend.name() + " trust failed");
                    refresh();
                });
    }

    // ==================== Maintenance ====================

    /** Streams the backend's full health report into the command log. */
    public void runDoctor() {
        if (refuseUnsupported(BackendFeature.DOCTOR)) {
            return;
        }
        String key = "doctor";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " doctor");
        submitBackground("doctor", key,
                () -> backend.doctor(liveLogLine(key)),
                result -> {
                    logResult(result, backend.name() + " doctor: no problems found",
                            backend.name() + " doctor found problems");
                    // The report is also the health summary the header and Status read.
                    state.backendInfoLazy().invalidate();
                    ensureBackendInfo();
                });
    }

    /** Reclaims disk from old versions, streaming progress. */
    public void prune() {
        if (refuseUnsupported(BackendFeature.PRUNE)) {
            return;
        }
        String key = "prune";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " prune");
        submitBackground("prune", key,
                () -> backend.prune(liveLogLine(key)),
                result -> {
                    logResult(result, "Pruned unused tool versions", "Prune failed");
                    refresh();
                });
    }

    /**
     * The substring mise prints when its self-update was compiled out. Matched rather than
     * parsed: mise offers no exit code or flag distinguishing this from an ordinary update
     * failure.
     */
    private static final String SELF_UPDATE_DISABLED_MARKER = "disabled at build time";

    /** Explains why self-update can never work here, in place of the backend's raw error. */
    private String selfUpdateDisabledMessage() {
        return backend.name() + "'s self-update is disabled in this build — it was installed by a "
                + "package manager, so update it from there";
    }

    /** Updates the backend's own binary, streaming progress into the command log. */
    public void selfUpdate() {
        if (refuseUnsupported(BackendFeature.SELF_UPDATE)) {
            return;
        }
        // Once the backend has told us the feature is compiled out, that answer holds for the
        // life of this binary: explain instead of spawning a process guaranteed to fail.
        if (state.selfUpdateDisabled()) {
            state.addLog(LogLevel.INFO, selfUpdateDisabledMessage());
            return;
        }
        if (blockedOffline("self-update")) {
            return;
        }
        String key = "self-update";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " self-update");
        submitBackground("self-update", key,
                () -> backend.selfUpdate(liveLogLine(key)),
                result -> {
                    if (!result.ok() && mentionsDisabledSelfUpdate(result)) {
                        state.selfUpdateDisabled(true);
                        state.addLog(LogLevel.INFO, selfUpdateDisabledMessage());
                        return; // nothing changed, so nothing to refresh
                    }
                    logResult(result, backend.name() + " is up to date", "Self-update failed");
                    refresh();
                });
    }

    /** True when a failed self-update was refused outright rather than merely erroring. */
    private static boolean mentionsDisabledSelfUpdate(CliResult result) {
        // self-update streams with stderr merged into stdout, but check both so
        // this keeps working if that ever changes.
        return (result.stdout() + result.stderr()).toLowerCase()
                .contains(SELF_UPDATE_DISABLED_MARKER);
    }

    // ==================== Config files ====================

    /** Writes edited config content to disk, then refreshes so the backend picks it up. */
    public void saveConfig(@NonNull Path file, @NonNull String content) {
        String key = "save:" + file;
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "Saving " + file + "…");
        submitBackground("save " + file, key,
                () -> {
                    try {
                        if (file.getParent() != null) {
                            Files.createDirectories(file.getParent());
                        }
                        Files.writeString(file, content);
                        return "";
                    } catch (IOException e) {
                        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                },
                error -> {
                    if (error.isEmpty()) {
                        state.addLog(LogLevel.OK, "Saved " + file);
                        refresh();
                        validateConfigAsync();
                    } else {
                        state.addLog(LogLevel.ERROR, "Could not save " + file + ": " + error);
                    }
                });
    }

    /**
     * After a config save, parses the active config in the background and logs a warning if the
     * backend reports a TOML parse error — catching a broken edit early. Silently skipped by a
     * backend with no parse check, since there is nothing to warn about either way.
     */
    private void validateConfigAsync() {
        if (!backend.supports(BackendFeature.CONFIG_VALIDATE)) {
            return;
        }
        submitBackground("validate config",
                backend::validateConfig,
                result -> {
                    String stderr = result.stderr();
                    if (stderr.toLowerCase().contains("parse error")) {
                        state.addLog(LogLevel.ERROR, "Config parse error — " + firstLine(stderr));
                    }
                });
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    // ==================== Catalog ====================

    /** Fetches installable versions of an SDK; {@code onDone} runs on the render thread. */
    public void fetchReleases(@NonNull String sdk, @NonNull Consumer<List<SdkRelease>> onDone) {
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " ls-remote " + sdk);
        submitBackground("ls-remote " + sdk,
                () -> backend.listReleases(sdk),
                releases -> {
                    // The synthetic "latest" alias is an entry in the list but not a real
                    // release, so it should not be counted as one.
                    long realCount = releases.stream().filter(r -> !r.latest()).count();
                    state.addLog(LogLevel.OK, realCount + " versions of " + sdk + " available");
                    onDone.accept(releases);
                });
    }

    /**
     * Registers a plugin standalone, without installing any version. {@code rawInput} is split
     * on whitespace and passed straight through, so it can be a bare name ("nodejs") or include
     * the backend's own flags ({@code --alias}, {@code --source}) exactly as its CLI accepts
     * them.
     */
    public void registerPlugin(@NonNull String rawInput) {
        if (refuseUnsupported(BackendFeature.PLUGIN_REGISTRY)) {
            return;
        }
        String trimmed = rawInput.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        List<String> args = List.of(trimmed.split("\\s+"));
        String key = "add-plugin:" + args.getFirst();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ " + backend.name() + " add " + trimmed);
        submitBackground("add plugin " + trimmed, key,
                () -> backend.registerPlugin(args),
                result -> {
                    logResult(result, "Added plugin " + args.getFirst(),
                            "Add plugin failed: " + args.getFirst());
                    refresh();
                });
    }

    // ==================== Plumbing ====================

    /**
     * Logs the backend's own explanation and returns true when {@code feature} is not available
     * here. The single place an unsupported operation is refused: every action that needs a
     * feature starts with this, so the answer is always the backend's wording rather than a
     * silent no-op.
     */
    private boolean refuseUnsupported(BackendFeature feature) {
        if (backend.supports(feature)) {
            return false;
        }
        state.addLog(LogLevel.INFO, backend.unsupportedReason(feature));
        return true;
    }

    /**
     * A line consumer for streaming commands: marshals every non-blank output line onto the
     * render thread and appends it to the command log as it arrives, prefixed with a short
     * source tag so concurrently running operations stay distinguishable in the shared log.
     * Also records the (untagged) line as the live status of the operation under
     * {@code busyKey}, so its row can show progress.
     */
    private Consumer<String> liveLogLine(String busyKey) {
        String tag = "[" + sourceLabel(busyKey) + "] ";
        return line -> {
            if (!line.isBlank()) {
                uiThread.accept(() -> {
                    state.addLog(LogLevel.INFO, tag + line);
                    state.busyStatus(busyKey, line);
                });
            }
        };
    }

    /** Derives a short, human-readable source tag from an operation's busy key. */
    private static String sourceLabel(String busyKey) {
        if (busyKey.startsWith("task:")) {
            return busyKey.substring("task:".length());
        }
        if (busyKey.startsWith("registry:")) {
            return busyKey.substring("registry:".length());
        }
        if (busyKey.startsWith("upgrade:")) {
            String sdk = busyKey.substring("upgrade:".length());
            return sdk.equals("*") ? "upgrade" : "upgrade " + sdk;
        }
        if (busyKey.startsWith("save:")) {
            return "save";
        }
        return busyKey;
    }

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

    /**
     * Like {@link #submitBackground(String, Supplier, Consumer)} but also clears {@code busyKey}
     * before invoking the callback — including on the exception path, which the plain overload
     * leaves marked busy forever since nothing else would clear it. Every action that calls
     * {@link UiState#markBusy} up front must release it through this overload.
     */
    private <T> void submitBackground(String label, String busyKey, Supplier<T> work, Consumer<T> onDone) {
        executor.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                uiThread.accept(() -> {
                    state.clearBusy(busyKey);
                    state.addLog(LogLevel.ERROR, label + " failed: " + e.getMessage());
                });
                return;
            }
            uiThread.accept(() -> {
                state.clearBusy(busyKey);
                onDone.accept(result);
            });
        });
    }

    /**
     * Logs a message and returns {@code true} when the app was launched with {@code --offline}
     * and {@code what} needs the network — the caller should bail out without marking anything
     * busy or touching the CLI.
     */
    private boolean blockedOffline(String what) {
        if (state.offline()) {
            state.addLog(LogLevel.INFO, "Offline mode — " + what + " needs network access");
            return true;
        }
        return false;
    }

    private void logResult(CliResult result, String okMessage, String failMessagePrefix) {
        if (result.ok()) {
            state.addLog(LogLevel.OK, okMessage);
        } else {
            String detail = result.summaryLine();
            state.addLog(LogLevel.ERROR, failMessagePrefix + (detail.isBlank() ? "" : ": " + detail));
        }
    }
}
