package com.sampong.tambo.tui;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import dev.tamboui.picocli.TuiMixin;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.TuiConfig;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo.cli.TamboCommand;
import com.sampong.tambo.mise.MiseMaintenanceService;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;
import com.sampong.tambo.mise.implement.MiseSdkBackend;
import com.sampong.tambo.mise.implement.MiseShellActivationServiceImp;
import com.sampong.tambo.vfox.VfoxSdkBackend;
import com.sampong.tambo.vfox.VfoxShellActivationServiceImp;
import com.sampong.tambo.tui.components.AddPluginModal;
import com.sampong.tambo.tui.components.BatchConfirmModal;
import com.sampong.tambo.tui.components.CommandLineBar;
import com.sampong.tambo.tui.components.CommandPalette;
import com.sampong.tambo.tui.components.ConfigEditorModal;
import com.sampong.tambo.tui.components.ConfirmModal;
import com.sampong.tambo.tui.components.DetailPanel;
import com.sampong.tambo.tui.components.EnvPanel;
import com.sampong.tambo.tui.components.HelpOverlay;
import com.sampong.tambo.tui.components.JobsView;
import com.sampong.tambo.tui.components.LeaderMenu;
import com.sampong.tambo.tui.components.LogPanel;
import com.sampong.tambo.tui.components.MenuBar;
import com.sampong.tambo.tui.components.Modal;
import com.sampong.tambo.tui.components.RegistryModal;
import com.sampong.tambo.tui.components.SelectBackendModal;
import com.sampong.tambo.tui.components.SidePanels;
import com.sampong.tambo.tui.components.StatusPanel;
import com.sampong.tambo.tui.components.SwitchBackendModal;
import com.sampong.tambo.tui.components.TaskArgsModal;
import com.sampong.tambo.tui.components.TasksPanel;
import com.sampong.tambo.tui.components.ToolsPanel;
import com.sampong.tambo.tui.components.VersionsPanel;
import com.sampong.tambo.tui.commands.CommandLineCompleter;
import com.sampong.tambo.tui.features.ConfigHistory;
import com.sampong.tambo.tui.state.JobTracker;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.features.TamboConfig;
import com.sampong.tambo.tui.features.Theme;
import com.sampong.tambo.tui.keys.GlobalKeyBindings;
import com.sampong.tambo.tui.layout.AppLayout;
import com.sampong.tambo.tui.lifecycle.AppLifecycle;
import com.sampong.tambo.tui.state.UiContext;
import com.sampong.tambo.tui.state.UiState;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * A terminal UI for <a href="https://mise.jdx.dev">mise</a> and vfox.
 * <p>
 * This class is a thin orchestrator: it owns Spring wiring and the {@code ToolkitApp} process
 * hooks ({@code configure}/{@code onStart}/{@code render}) — Java requires overrides to live on
 * the subclass itself, so these can't move out, but each is just one line deferring to a sibling
 * package that owns the actual logic:
 * <ul>
 *   <li>{@code state/} — {@link UiState}, the shared data every panel renders
 *       from, plus the {@link UiContext} interface panels see the app through</li>
 *   <li>{@code features/} — {@link BackendActions} and other background/feature logic</li>
 *   <li>{@code components/} — one class per panel: {@link StatusPanel}, {@link ToolsPanel},
 *       {@link VersionsPanel}, {@link EnvPanel}, {@link TasksPanel}, {@link DetailPanel},
 *       {@link LogPanel}, plus the modal/overlay components</li>
 *   <li>{@code layout/} — {@link AppLayout}: turns state into what's on screen</li>
 *   <li>{@code keys/} — {@link GlobalKeyBindings}: turns a keypress into an action</li>
 *   <li>{@code lifecycle/} — {@link AppLifecycle}: backend selection and session start</li>
 * </ul>
 * {@link #ui} bundles every panel/modal instance so {@code layout} and {@code keys} can reach
 * them without this class exposing a getter per component.
 */
@Component
public final class MiseTuiApp extends ToolkitApp implements UiContext {

    private final UiState state;
    private final TamboConfig config;
    private final AppLifecycle lifecycle;
    private final TuiComponents ui;
    private final AppLayout layout;
    private final GlobalKeyBindings keyBindings;
    private final TuiMixin tuiOptions;

    public MiseTuiApp(@NonNull MiseShellActivationServiceImp miseActivation,
                      @NonNull VfoxShellActivationServiceImp vfoxActivation,
                      @NonNull CancelRegistry cancelRegistry, @NonNull TamboConfig config,
                      @NonNull MiseSdkBackend miseSdkBackend, @NonNull VfoxSdkBackend vfoxSdkBackend,
                      @Qualifier("miseTaskExecutor") @NonNull AsyncTaskExecutor executor,
                      @NonNull TamboCommand command) {
        this.config = config;
        this.state = new UiState();
        this.state.offline(command.offline());
        this.tuiOptions = command.tuiOptions();

        this.lifecycle = new AppLifecycle(miseActivation, vfoxActivation,
                cancelRegistry, miseSdkBackend, vfoxSdkBackend, executor, state,
                this::runOnRenderThreadIfRunning, command);

        StatusPanel statusPanel = new StatusPanel(this);
        ToolsPanel toolsPanel = new ToolsPanel(this);
        VersionsPanel versionsPanel = new VersionsPanel(this);
        TasksPanel tasksPanel = new TasksPanel(this);
        EnvPanel envPanel = new EnvPanel(this);
        // Status is the one panel sized to its content rather than to a share of the sidebar,
        // so the stack has to be able to ask it how tall it currently is.
        SidePanels sidePanels = new SidePanels(this, statusPanel::rowCount);
        CommandLineBar commandLine = new CommandLineBar(this, this::completer, args -> actions().exec(args));
        HelpOverlay helpOverlay = new HelpOverlay(this, this::terminalHeight, this::terminalWidth);
        MenuBar menuBar = new MenuBar(this);
        this.ui = new TuiComponents(
                statusPanel,
                toolsPanel,
                versionsPanel,
                envPanel,
                tasksPanel,
                new DetailPanel(this, sidePanels, toolsPanel, tasksPanel, envPanel, versionsPanel),
                new LogPanel(this),
                sidePanels,
                new RegistryModal(this),
                new ConfigEditorModal(this),
                new ConfirmModal(this),
                new BatchConfirmModal(this),
                new TaskArgsModal(this),
                new AddPluginModal(this),
                helpOverlay,
                new SelectBackendModal(),
                new SwitchBackendModal(this),
                new CommandPalette(this, commandLine::open),
                new LeaderMenu(this),
                commandLine,
                new JobsView(this, state.jobs(), job -> actions().cancelJob(job), this::recentChanges),
                menuBar);
        this.keyBindings = new GlobalKeyBindings(this, ui, config);
        menuBar.onKeyboardHelp(keyBindings::openHelp);
        this.layout = new AppLayout(this, ui, this::terminalHeight, this::terminalWidth);
    }

    // ==================== UiContext ====================

    @Override
    public UiState state() {
        return state;
    }

    @Override
    public BackendActions actions() {
        return lifecycle.actions();
    }

    @Override
    public Theme theme() {
        return config.theme();
    }

    @Override
    public String uiBackend() {
        return config.backend();
    }

    @Override
    public @Nullable String focusedId() {
        return runner() != null ? runner().focusManager().focusedId() : null;
    }

    @Override
    public void focus(String id) {
        runner().focusManager().setFocus(id);
    }

    @Override
    public void clearFocus() {
        runner().focusManager().clearFocus();
    }

    @Override
    public boolean noModalOpen() {
        return ui.modals().stream().noneMatch(Modal::isOpen);
    }

    @Override
    public void confirm(String message, Runnable onConfirm) {
        ui.confirmModal().open(message, onConfirm);
    }

    @Override
    public void promptTaskArgs(String taskName, String initialArgs) {
        ui.taskArgsModal().open(taskName, initialArgs);
    }

    @Override
    public void promptVersionFor(String tool) {
        ui.registryModal().openAtVersion(tool);
    }

    @Override
    public void requestUpgrade(List<SdkVersion> sdks) {
        List<BatchConfirmModal.Item> items = sdks.stream()
                .map(sdk -> new BatchConfirmModal.Item(sdk.name(), sdk.version(),
                        state.outdated().getOrDefault(sdk.name(), "newest allowed"), where(sdk)))
                .toList();
        String title = sdks.size() == 1 ? "Upgrade " + sdks.getFirst().name() + "?" : "Upgrade " + sdks.size() + " tools?";
        ui.batchConfirm().open(title, items,
                "Runs " + JobTracker.CONCURRENCY + " at a time in the background — keep working, press w to watch.\n"
                        + "Old versions stay installed until you prune them, so z can undo.",
                () -> {
                    sdks.forEach(sdk -> actions().upgradeSdk(sdk));
                    state.clearMarks();
                });
    }

    @Override
    public void requestUninstall(List<SdkVersion> sdks) {
        List<BatchConfirmModal.Item> items = sdks.stream()
                .map(sdk -> new BatchConfirmModal.Item(sdk.label(), "", "", where(sdk)))
                .toList();
        String title = sdks.size() == 1 ? "Uninstall " + sdks.getFirst().label() + "?" : "Uninstall " + sdks.size() + " tools?";
        ui.batchConfirm().open(title, items,
                "Deletes the installed files. Config files are not changed, so a pinned version\n"
                        + "shows as ✗ missing afterwards until it is installed again.",
                () -> {
                    sdks.forEach(sdk -> actions().uninstallSdk(sdk));
                    state.clearMarks();
                });
    }

    /** Ends the session; widens {@code ToolkitApp}'s protected quit for the palette and F10. */
    @Override
    public void quit() {
        super.quit();
    }

    /** Short "which config" label for a confirmation row. */
    private static String where(SdkVersion sdk) {
        String path = sdk.sourcePath() == null ? "" : sdk.sourcePath().replace('\\', '/');
        if (path.contains("/.config/mise/") || "global".equalsIgnoreCase(sdk.sourceType())) {
            return "global config";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * What the {@code :} line completes from: the backend's subcommands, the tools on screen,
     * and for {@code tool@} the versions already known locally — never a network call per
     * keystroke.
     */
    private CommandLineCompleter completer() {
        return new CommandLineCompleter(backend().name(), CommandLineCompleter.DEFAULT_SUBCOMMANDS,
                tool -> {
                    List<String> versions = new ArrayList<>();
                    String latest = state.outdated().get(tool);
                    if (latest != null) {
                        versions.add(latest);
                    }
                    state.sdks().stream().filter(sdk -> sdk.name().equals(tool) && sdk.hasVersion())
                            .map(SdkVersion::version).forEach(versions::add);
                    return versions.stream().distinct().toList();
                },
                () -> state.sdks().stream().map(SdkVersion::name).distinct().toList());
    }

    /** The jobs view's "Recent changes" column, newest first. */
    private List<String> recentChanges() {
        return state.history().recent(8).stream().map(ConfigHistory::line).toList();
    }

    // ==================== ToolkitApp hooks ====================
    // Each just defers to the sibling package that owns the actual logic — see the class
    // javadoc. These can't move out themselves: Java requires overrides to live on the
    // subclass extending ToolkitApp.

    @Override
    protected TuiConfig configure() {
        // mouseCapture forced on regardless of --mouse: this app has always shipped with mouse
        // support on by default, unlike TuiMixin's opt-in default, so the flag is a no-op here.
        return tuiOptions.toConfig().toBuilder()
                .mouseCapture(true)
                .bindings(keyBindings.navBindings())
                .build();
    }

    @Override
    protected void onStart() {
        keyBindings.register(runner().eventRouter());
        if (lifecycle.pendingBackendChoice()) {
            ui.selectBackendModal().open(lifecycle::onBackendPicked);
        } else {
            lifecycle.beginSession();
        }
    }

    @Override
    protected Element render() {
        return layout.render();
    }

    /**
     * Marshals background results onto the render thread, or drops them when there is no
     * runner: before the TUI has started, or after it has quit while a load or install was
     * still finishing. Such a result has nowhere to go, and calling through the null runner
     * printed a NullPointerException over the user's shell after exit.
     */
    private void runOnRenderThreadIfRunning(Runnable r) {
        ToolkitRunner runner = runner();
        if (runner != null) {
            runner.runOnRenderThread(r);
        }
    }

    /** Counterpart to {@link #terminalHeight()}: only the help overlay sizes itself by width too. */
    private int terminalWidth() {
        try {
            return runner().tuiRunner().terminal().size().width();
        } catch (Exception e) {
            return Integer.MAX_VALUE; // size unavailable — fall back to a fixed dialog width
        }
    }

    private int terminalHeight() {
        try {
            return runner().tuiRunner().terminal().size().height();
        } catch (Exception e) {
            return Integer.MAX_VALUE; // size unavailable — keep the normal layout
        }
    }
}
