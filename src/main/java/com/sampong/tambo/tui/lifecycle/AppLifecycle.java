package com.sampong.tambo.tui.lifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.springframework.core.task.AsyncTaskExecutor;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo.cli.TamboCommand;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo.mise.implement.MiseSdkBackend;
import com.sampong.tambo.mise.implement.MiseShellActivationServiceImp;
import com.sampong.tambo.tui.features.AutoInstallPrompt;
import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.UiState;
import com.sampong.tambo.vfox.VfoxSdkBackend;
import com.sampong.tambo.vfox.VfoxShellActivationServiceImp;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * Everything about getting a session from "process just started" to "panels can render":
 * resolving mise vs vfox (an explicit {@code --backend} flag, an existing project config, or —
 * failing both — a first-run picker the caller shows once the TUI is running), rebuilding
 * {@link #actions()} if that picker resolves a choice, and the log line + initial data load
 * once the backend is settled either way.
 * <p>
 * This is the only place that still deals in "mise or vfox" as a choice. Once it has resolved
 * one, it hands the chosen {@link SdkVersionBackend} to {@link UiState} and everything
 * downstream asks that what it supports rather than which one it is.
 * <p>
 * {@code MiseTuiApp} owns the process lifecycle itself (it must — {@code configure()}/
 * {@code onStart()} are {@code ToolkitApp} overrides that can't live outside the subclass) but
 * delegates everything it decides to this class.
 */
public final class AppLifecycle {

    @NonNull
    private final MiseShellActivationServiceImp miseActivation;
    @NonNull
    private final VfoxShellActivationServiceImp vfoxActivation;
    @NonNull
    private final CancelRegistry cancelRegistry;
    @NonNull
    private final MiseSdkBackend miseSdkBackend;
    @NonNull
    private final VfoxSdkBackend vfoxSdkBackend;
    @NonNull
    private final AsyncTaskExecutor executor;
    @NonNull
    private final UiState state;
    @NonNull
    private final Consumer<Runnable> renderThreadRunner;
    /** How the auto-install flow asks about near-misses; see {@link AutoInstallPrompt}. */
    @NonNull
    private final AutoInstallPrompt autoInstallPrompt;
    /** From {@code --auto-install}: apply the project config once the session is up. */
    private final boolean autoInstallOnStart;
    /**
     * Sets the terminal's window/tab title. A callback rather than the runner itself because
     * the runner only exists once the TUI has started, after this class is constructed.
     */
    @NonNull
    private final Consumer<String> windowTitle;

    /**
     * Not final: rebuilt by {@link #onBackendPicked} if the first-run backend picker resolves
     * a choice that differs from the provisional {@code mise} default it was first built with.
     */
    private BackendActions actions;

    /** True until {@link #onBackendPicked} resolves a first-run choice. */
    private boolean pendingBackendChoice;

    public AppLifecycle(@NonNull MiseShellActivationServiceImp miseActivation,
                        @NonNull VfoxShellActivationServiceImp vfoxActivation,
                        @NonNull CancelRegistry cancelRegistry, @NonNull MiseSdkBackend miseSdkBackend,
                        @NonNull VfoxSdkBackend vfoxSdkBackend, @NonNull AsyncTaskExecutor executor,
                        @NonNull UiState state, @NonNull Consumer<Runnable> renderThreadRunner,
                        @NonNull AutoInstallPrompt autoInstallPrompt, @NonNull Consumer<String> windowTitle,
                        @NonNull TamboCommand command) {
        this.miseActivation = miseActivation;
        this.vfoxActivation = vfoxActivation;
        this.cancelRegistry = cancelRegistry;
        this.miseSdkBackend = miseSdkBackend;
        this.vfoxSdkBackend = vfoxSdkBackend;
        this.executor = executor;
        this.state = state;
        this.renderThreadRunner = renderThreadRunner;
        this.autoInstallPrompt = autoInstallPrompt;
        this.autoInstallOnStart = command.autoInstall();
        this.windowTitle = windowTitle;

        Boolean decided = resolveBackendChoice(command);
        this.pendingBackendChoice = decided == null;
        boolean useVfox = decided != null && decided; // provisional default while undecided: mise
        this.actions = buildActions(useVfox);
    }

    public BackendActions actions() {
        return actions;
    }

    /** True until {@link #onBackendPicked} resolves a first-run choice; see {@code MiseTuiApp#onStart()}. */
    public boolean pendingBackendChoice() {
        return pendingBackendChoice;
    }

    /**
     * Builds the actions layer for a backend and publishes that backend into {@link UiState},
     * which is where every panel reads it from. The two must move together — a session with the
     * actions of one backend and the capabilities of the other would run the wrong CLI behind
     * the right-looking UI — so nothing else sets either.
     */
    private BackendActions buildActions(boolean useVfox) {
        SdkVersionBackend sdkBackend = useVfox ? vfoxSdkBackend : miseSdkBackend;
        ShellActivationService activation = useVfox ? vfoxActivation : miseActivation;
        state.backend(sdkBackend);
        return new BackendActions(sdkBackend, activation, cancelRegistry, executor, state, renderThreadRunner);
    }

    /**
     * Resolves the SDK backend for this project when it's determinable without asking —
     * used consistently for both SDK install/use/list and shell activation.
     * {@code --backend=mise|vfox} is an explicit override; otherwise this detects an existing
     * project config in the working directory. Returns null when neither applies, meaning it's
     * a first run: the caller shows the backend picker once the TUI is running instead of
     * asking on a raw console before it starts — a prompt used to read {@code System.in}
     * directly, which raced the TUI backend for ownership of stdin and crashed depending on
     * which backend was active (see git history / README troubleshooting).
     */
    private static @Nullable Boolean resolveBackendChoice(TamboCommand command) {
        TamboCommand.Backend backend = command.backend();
        if (backend == TamboCommand.Backend.vfox) {
            return true;
        }
        if (backend == TamboCommand.Backend.mise) {
            return false;
        }

        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve(MiseSdkBackend.PROJECT_CONFIG_FILE))) {
            return false;
        }
        if (Files.exists(cwd.resolve(VfoxSdkBackend.PROJECT_CONFIG_FILE))) {
            return true;
        }
        return null;
    }

    /**
     * Callback from the first-run backend picker once the user picks mise or vfox. Creates
     * the project config so the choice sticks next launch, rebuilds {@link #actions} if the
     * pick differs from the provisional {@code mise} default the session started with, and
     * then begins the session exactly as an already-decided launch would.
     */
    public void onBackendPicked(boolean useVfox) {
        pendingBackendChoice = false;
        createProjectConfig(Path.of("").toAbsolutePath(), useVfox);
        this.actions = buildActions(useVfox);
        beginSession();
    }

    /**
     * Creates an empty project-scope config file for the chosen backend, if one doesn't
     * already exist. Both mise and vfox populate it themselves on the first {@code use}.
     */
    private static void createProjectConfig(Path cwd, boolean vfox) {
        Path file = cwd.resolve(vfox ? VfoxSdkBackend.PROJECT_CONFIG_FILE : MiseSdkBackend.PROJECT_CONFIG_FILE);
        try {
            if (Files.notExists(file)) {
                Files.writeString(file, vfox ? "" : "[tools]\n");
            }
        } catch (IOException e) {
            // Best-effort — mise/vfox create their own config on the first `use` anyway.
        }
    }

    /**
     * Runs once the backend is settled — either it was already decided at startup, or
     * {@link #onBackendPicked} just resolved a first-run choice.
     */
    public void beginSession() {
        windowTitle.accept(windowTitleText());
        state.addLog(LogLevel.INFO, "tambo — a lazygit-style TUI for " + state.backend().name()
                + ". Press ? for help, a to add an SDK, I to apply "
                + state.backend().projectConfigFileName() + ".");
        actions.loadInitial();
        if (autoInstallOnStart) {
            // --auto-install only moves the I key to startup; everything it does, including
            // asking about a version that is close but not equal, is the same flow.
            actions.autoInstall(autoInstallPrompt);
        }
    }

    /**
     * {@code "tambo — vfox — my-project"}: the app, the backend this session drives, and the
     * project directory, so a row of terminal tabs says which tambo is which. Set here rather
     * than at startup because a first run only knows its backend once the picker is answered.
     */
    private String windowTitleText() {
        Path project = Path.of("").toAbsolutePath().getFileName();
        return "tambo — " + state.backend().name()
                + (project != null ? " — " + project : "")
                + (state.offline() ? " (offline)" : "");
    }
}
