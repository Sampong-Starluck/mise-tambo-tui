package com.sampong.tambo.tui;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.task.support.TaskExecutorAdapter;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo.mise.ShellActivationService.ActivationOutcome;
import com.sampong.tambo.tui.commands.CommandLineCompleter;
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
import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.features.ConfigHistory;
import com.sampong.tambo.tui.features.TamboConfig;
import com.sampong.tambo.tui.features.Theme;
import com.sampong.tambo.tui.keys.GlobalKeyBindings;
import com.sampong.tambo.tui.layout.AppLayout;
import com.sampong.tambo.tui.state.UiContext;
import com.sampong.tambo.tui.state.UiState;

import org.jspecify.annotations.Nullable;

/**
 * The whole TUI — every panel, modal, the layout and the key router — wired the way
 * {@link MiseTuiApp} wires it, minus Spring and the terminal: background work runs inline, the
 * "render thread" is the caller, and {@link #render} draws a frame into an in-memory buffer and
 * returns it as plain text. Lets a test press keys and read the screen.
 */
public final class ScreenHarness implements UiContext {

    private final UiState state = new UiState();
    private final BackendActions actions;
    private final TuiComponents ui;
    private final GlobalKeyBindings keys;
    private final AppLayout layout;
    private @Nullable String focused;
    private int width;
    private int height;
    public boolean quit;

    public ScreenHarness(SdkVersionBackend backend, int width, int height) {
        this.width = width;
        this.height = height;
        state.backend(backend);
        this.actions = new BackendActions(backend, () -> new ActivationOutcome(true, false, "already active"),
                new CancelRegistry(), new TaskExecutorAdapter(Runnable::run), state, Runnable::run);

        StatusPanel statusPanel = new StatusPanel(this);
        ToolsPanel toolsPanel = new ToolsPanel(this);
        VersionsPanel versionsPanel = new VersionsPanel(this);
        TasksPanel tasksPanel = new TasksPanel(this);
        EnvPanel envPanel = new EnvPanel(this);
        SidePanels sidePanels = new SidePanels(this, statusPanel::rowCount);
        CommandLineBar commandLine = new CommandLineBar(this,
                () -> new CommandLineCompleter("mise", CommandLineCompleter.DEFAULT_SUBCOMMANDS,
                        tool -> state.sdks().stream().filter(s -> s.name().equals(tool)).map(SdkVersion::version).toList(),
                        () -> state.sdks().stream().map(SdkVersion::name).distinct().toList()),
                actions::exec);
        MenuBar menuBar = new MenuBar(this);
        this.ui = new TuiComponents(statusPanel, toolsPanel, versionsPanel, envPanel, tasksPanel,
                new DetailPanel(this, sidePanels, toolsPanel, tasksPanel, envPanel, versionsPanel),
                new LogPanel(this), sidePanels, new RegistryModal(this), new ConfigEditorModal(this),
                new ConfirmModal(this), new BatchConfirmModal(this), new TaskArgsModal(this),
                new AddPluginModal(this), new HelpOverlay(this, () -> this.height, () -> this.width),
                new SelectBackendModal(), new SwitchBackendModal(this), new CommandPalette(this, commandLine::open),
                new LeaderMenu(this), commandLine,
                new JobsView(this, state.jobs(), actions::cancelJob,
                        () -> state.history().recent(8).stream().map(ConfigHistory::line).toList()),
                menuBar);
        this.keys = new GlobalKeyBindings(this, ui, new TamboConfig());
        menuBar.onKeyboardHelp(keys::openHelp);
        this.layout = new AppLayout(this, ui, () -> this.height, () -> this.width);
        actions.loadInitial();
        actions.ensureOutdated();
        focus(com.sampong.tambo.tui.state.PanelIds.TOOLS);
    }

    public TuiComponents ui() {
        return ui;
    }

    public GlobalKeyBindings keys() {
        return keys;
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
    }

    /** Presses each key through the global router, as the app does for keys no element claims. */
    public ScreenHarness press(KeyEvent... events) {
        for (KeyEvent event : events) {
            keys.handle(event);
        }
        return this;
    }

    /** A left click at column {@code x}, row {@code y}, as the global mouse handler receives it. */
    public ScreenHarness click(int x, int y) {
        keys.handleMouse(MouseEvent.press(MouseButton.LEFT, x, y));
        return this;
    }

    /** Renders one frame and returns it as text, one line per terminal row, trailing blanks trimmed. */
    public String render() {
        Element root = layout.render();
        Rect area = new Rect(0, 0, width, height);
        Buffer buffer = Buffer.empty(area);
        Frame frame = Frame.forTesting(buffer);
        // Two passes: the first lets lazily computed sizes (status rows, preferred widths) settle.
        root.render(frame, area, DefaultRenderContext.createEmpty());
        buffer = Buffer.empty(area);
        frame = Frame.forTesting(buffer);
        layout.render().render(frame, area, DefaultRenderContext.createEmpty());
        List<String> lines = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < width; x++) {
                line.append(buffer.get(x, y).symbol());
            }
            lines.add(line.toString().stripTrailing());
        }
        return String.join("\n", lines);
    }

    // ==================== UiContext ====================

    @Override
    public UiState state() {
        return state;
    }

    @Override
    public BackendActions actions() {
        return actions;
    }

    @Override
    public Theme theme() {
        return Theme.defaults();
    }

    @Override
    public String uiBackend() {
        return "jline3";
    }

    @Override
    public @Nullable String focusedId() {
        return focused;
    }

    @Override
    public void focus(String id) {
        focused = id;
    }

    @Override
    public void clearFocus() {
        focused = null;
    }

    @Override
    public boolean noModalOpen() {
        return ui == null || ui.modals().stream().noneMatch(Modal::isOpen);
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
        ui.batchConfirm().open("Upgrade " + sdks.size() + " tools?",
                sdks.stream().map(s -> new BatchConfirmModal.Item(s.name(), s.version(),
                        state.outdated().getOrDefault(s.name(), "newest"), "mise.toml")).toList(),
                "Runs 2 at a time in the background — keep working, press w to watch.",
                () -> sdks.forEach(actions::upgradeSdk));
    }

    @Override
    public void requestUninstall(List<SdkVersion> sdks) {
        ui.batchConfirm().open("Uninstall " + sdks.size() + " tools?",
                sdks.stream().map(s -> new BatchConfirmModal.Item(s.label(), "", "", "mise.toml")).toList(),
                "Deletes the installed files.", () -> sdks.forEach(actions::uninstallSdk));
    }

    @Override
    public void quit() {
        quit = true;
    }
}
