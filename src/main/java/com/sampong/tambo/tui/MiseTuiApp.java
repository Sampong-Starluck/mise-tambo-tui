package com.sampong.tambo.tui;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import dev.tamboui.picocli.TuiMixin;
import dev.tamboui.toolkit.app.ToolkitApp;
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
import com.sampong.tambo.tui.components.AdvancedPanel;
import com.sampong.tambo.tui.components.ConfigEditorModal;
import com.sampong.tambo.tui.components.ConfirmModal;
import com.sampong.tambo.tui.components.DetailPanel;
import com.sampong.tambo.tui.components.EnvPanel;
import com.sampong.tambo.tui.components.HelpOverlay;
import com.sampong.tambo.tui.components.LogPanel;
import com.sampong.tambo.tui.components.RegistryModal;
import com.sampong.tambo.tui.components.SelectBackendModal;
import com.sampong.tambo.tui.components.StatusPanel;
import com.sampong.tambo.tui.components.SwitchBackendModal;
import com.sampong.tambo.tui.components.TaskArgsModal;
import com.sampong.tambo.tui.components.TasksPanel;
import com.sampong.tambo.tui.components.ToolsPanel;
import com.sampong.tambo.tui.features.MiseActions;
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
 * A lazygit-style terminal UI for <a href="https://mise.jdx.dev">mise</a>.
 * <p>
 * This class is a thin orchestrator: it owns Spring wiring and the {@code ToolkitApp} process
 * hooks ({@code configure}/{@code onStart}/{@code render}) — Java requires overrides to live on
 * the subclass itself, so these can't move out, but each is just one line deferring to a sibling
 * package that owns the actual logic:
 * <ul>
 *   <li>{@code state/} — {@link UiState}, the shared data every panel renders
 *       from, plus the {@link UiContext} interface panels see the app through</li>
 *   <li>{@code features/} — {@link MiseActions} and other background/feature logic</li>
 *   <li>{@code components/} — one class per panel: {@link StatusPanel}, {@link ToolsPanel},
 *       {@link EnvPanel}, {@link TasksPanel}, {@link DetailPanel}, {@link LogPanel},
 *       {@link AdvancedPanel}, plus the modal/overlay components</li>
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

    public MiseTuiApp(@NonNull MiseQueryService query, @NonNull MiseToolService tools,
                      @NonNull MiseMaintenanceService maintenance,
                      @NonNull MiseShellActivationServiceImp miseActivation,
                      @NonNull VfoxShellActivationServiceImp vfoxActivation,
                      @NonNull CancelRegistry cancelRegistry, @NonNull TamboConfig config,
                      @NonNull MiseSdkBackend miseSdkBackend, @NonNull VfoxSdkBackend vfoxSdkBackend,
                      @Qualifier("miseTaskExecutor") @NonNull AsyncTaskExecutor executor,
                      @NonNull TamboCommand command) {
        this.config = config;
        this.state = new UiState();
        this.state.offline(command.offline());
        this.state.advancedFeatures(command.advancedFeatures());
        this.tuiOptions = command.tuiOptions();

        this.lifecycle = new AppLifecycle(query, tools, maintenance, miseActivation, vfoxActivation,
                cancelRegistry, miseSdkBackend, vfoxSdkBackend, executor, state,
                r -> runner().runOnRenderThread(r), command);

        ToolsPanel toolsPanel = new ToolsPanel(this);
        TasksPanel tasksPanel = new TasksPanel(this);
        this.ui = new TuiComponents(
                new StatusPanel(this),
                toolsPanel,
                new EnvPanel(this),
                tasksPanel,
                new DetailPanel(this, toolsPanel, tasksPanel),
                new LogPanel(this),
                new AdvancedPanel(this),
                new RegistryModal(this),
                new ConfigEditorModal(this),
                new ConfirmModal(this),
                new TaskArgsModal(this),
                new AddPluginModal(this),
                new HelpOverlay(this),
                new SelectBackendModal(),
                new SwitchBackendModal(this));
        this.keyBindings = new GlobalKeyBindings(this, ui, config);
        this.layout = new AppLayout(this, ui, this::terminalHeight, keyBindings::buildAdvancedActions);
    }

    // ==================== UiContext ====================

    @Override
    public UiState state() {
        return state;
    }

    @Override
    public MiseActions actions() {
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
    public boolean modalOpen() {
        return !ui.registryModal().isOpen() && !ui.configEditor().isOpen() && !ui.confirmModal().isOpen()
                && !ui.taskArgsModal().isOpen() && !ui.addPluginModal().isOpen() && !ui.selectBackendModal().isOpen()
                && !ui.switchBackendModal().isOpen();
    }

    @Override
    public void confirm(String message, Runnable onConfirm) {
        ui.confirmModal().open(message, onConfirm);
    }

    @Override
    public void promptTaskArgs(String taskName, String initialArgs) {
        ui.taskArgsModal().open(taskName, initialArgs);
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

    private int terminalHeight() {
        try {
            return runner().tuiRunner().terminal().size().height();
        } catch (Exception e) {
            return Integer.MAX_VALUE; // size unavailable — keep the normal layout
        }
    }
}
