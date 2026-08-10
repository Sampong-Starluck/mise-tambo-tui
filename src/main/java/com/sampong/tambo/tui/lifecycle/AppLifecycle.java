package com.sampong.tambo.tui.lifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.boot.ApplicationArguments;
import org.springframework.core.task.AsyncTaskExecutor;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo.mise.MiseMaintenanceService;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo.mise.implement.MiseSdkBackend;
import com.sampong.tambo.mise.implement.MiseShellActivationServiceImp;
import com.sampong.tambo.tui.features.MiseActions;
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
 * {@code MiseTuiApp} owns the process lifecycle itself (it must — {@code configure()}/
 * {@code onStart()} are {@code ToolkitApp} overrides that can't live outside the subclass) but
 * delegates everything it decides to this class.
 */
public final class AppLifecycle {

    public static final String MISE_CONFIG_FILE = "mise.toml";
    /** vfox's actual project-scope config filename — dot-prefixed, per vfox's own convention. */
    public static final String VFOX_CONFIG_FILE = ".vfox.toml";

    @NonNull
    private final MiseQueryService query;
    @NonNull
    private final MiseToolService tools;
    @NonNull
    private final MiseMaintenanceService maintenance;
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

    /**
     * Not final: rebuilt by {@link #onBackendPicked} if the first-run backend picker resolves
     * a choice that differs from the provisional {@code mise} default it was first built with.
     */
    private MiseActions actions;

    /** True until {@link #onBackendPicked} resolves a first-run choice. */
    private boolean pendingBackendChoice;

    public AppLifecycle(@NonNull MiseQueryService query, @NonNull MiseToolService tools,
                         @NonNull MiseMaintenanceService maintenance,
                         @NonNull MiseShellActivationServiceImp miseActivation,
                         @NonNull VfoxShellActivationServiceImp vfoxActivation,
                         @NonNull CancelRegistry cancelRegistry, @NonNull MiseSdkBackend miseSdkBackend,
                         @NonNull VfoxSdkBackend vfoxSdkBackend, @NonNull AsyncTaskExecutor executor,
                         @NonNull UiState state, @NonNull Consumer<Runnable> renderThreadRunner,
                         @NonNull ApplicationArguments arguments) {
        this.query = query;
        this.tools = tools;
        this.maintenance = maintenance;
        this.miseActivation = miseActivation;
        this.vfoxActivation = vfoxActivation;
        this.cancelRegistry = cancelRegistry;
        this.miseSdkBackend = miseSdkBackend;
        this.vfoxSdkBackend = vfoxSdkBackend;
        this.executor = executor;
        this.state = state;
        this.renderThreadRunner = renderThreadRunner;

        Boolean decided = resolveBackendChoice(arguments);
        this.pendingBackendChoice = decided == null;
        boolean useVfox = decided != null && decided; // provisional default while undecided: mise
        state.vfox(useVfox);
        this.actions = buildActions(useVfox);
    }

    public MiseActions actions() {
        return actions;
    }

    /** True until {@link #onBackendPicked} resolves a first-run choice; see {@code MiseTuiApp#onStart()}. */
    public boolean pendingBackendChoice() {
        return pendingBackendChoice;
    }

    private MiseActions buildActions(boolean useVfox) {
        SdkVersionBackend sdkBackend = useVfox ? vfoxSdkBackend : miseSdkBackend;
        ShellActivationService activation = useVfox ? vfoxActivation : miseActivation;
        return new MiseActions(query, tools, maintenance, activation, cancelRegistry,
                executor, state, renderThreadRunner, sdkBackend, vfoxSdkBackend);
    }

    /**
     * Resolves the SDK backend for this project when it's determinable without asking —
     * used consistently for both tool install/use/list and shell activation.
     * {@code --backend=mise|vfox} is an explicit override; otherwise this detects an
     * existing {@link #MISE_CONFIG_FILE} or {@link #VFOX_CONFIG_FILE} in the working
     * directory. Returns null when neither applies, meaning it's a first run: the caller
     * shows the backend picker once the TUI is running instead of asking on a raw console
     * before it starts — a prompt used to read {@code System.in} directly, which raced the
     * TUI backend for ownership of stdin and crashed depending on which backend was active
     * (see git history / README troubleshooting).
     */
    private static @Nullable Boolean resolveBackendChoice(ApplicationArguments arguments) {
        List<String> values = arguments.getOptionValues("backend");
        if (values != null) {
            if (values.stream().anyMatch(v -> v.equalsIgnoreCase("vfox"))) {
                return true;
            }
            if (values.stream().anyMatch(v -> v.equalsIgnoreCase("mise"))) {
                return false;
            }
        }

        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve(MISE_CONFIG_FILE))) {
            return false;
        }
        if (Files.exists(cwd.resolve(VFOX_CONFIG_FILE))) {
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
        state.vfox(useVfox);
        this.actions = buildActions(useVfox);
        beginSession();
    }

    /**
     * Creates an empty project-scope config file for the chosen backend, if one doesn't
     * already exist. Both mise and vfox populate it themselves on the first {@code use}.
     */
    private static void createProjectConfig(Path cwd, boolean vfox) {
        Path file = cwd.resolve(vfox ? VFOX_CONFIG_FILE : MISE_CONFIG_FILE);
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
        state.addLog(LogLevel.INFO, "tambo — a lazygit-style TUI for " + (state.vfox() ? "vfox" : "mise")
                + ". Press ? for help, a to add an SDK.");
        actions.loadInitial();
    }
}
