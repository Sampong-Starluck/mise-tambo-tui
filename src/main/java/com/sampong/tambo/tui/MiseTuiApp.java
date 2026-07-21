package com.sampong.tambo.tui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dock;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.stack;
import static dev.tamboui.toolkit.Toolkit.text;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import dev.tamboui.layout.Constraint;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.event.KeyEvent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.sampong.tambo.mise.CancelRegistry;
import com.sampong.tambo.mise.MiseMaintenanceService;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo.tui.components.ConfigEditorModal;
import com.sampong.tambo.tui.components.ConfirmModal;
import com.sampong.tambo.tui.components.DetailPanel;
import com.sampong.tambo.tui.components.EnvPanel;
import com.sampong.tambo.tui.components.HelpOverlay;
import com.sampong.tambo.tui.components.LogPanel;
import com.sampong.tambo.tui.components.RegistryModal;
import com.sampong.tambo.tui.components.StatusPanel;
import com.sampong.tambo.tui.components.TaskArgsModal;
import com.sampong.tambo.tui.components.TasksPanel;
import com.sampong.tambo.tui.components.ToolsPanel;
import com.sampong.tambo.tui.features.MiseActions;
import com.sampong.tambo.tui.features.TamboConfig;
import com.sampong.tambo.tui.features.Theme;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;
import com.sampong.tambo.tui.state.UiState;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * A lazygit-style terminal UI for <a href="https://mise.jdx.dev">mise</a>.
 * <p>
 * This class is a thin orchestrator: it owns the lifecycle, the global key
 * bindings, and the top-level layout. Everything else lives in subpackages:
 * <ul>
 *   <li>{@code state/} — {@link UiState}, the shared data every panel renders
 *       from, plus the {@link UiContext} interface panels see the app through</li>
 *   <li>{@code features/} — {@link MiseActions} and other background/feature logic</li>
 *   <li>{@code components/} — one class per panel: {@link StatusPanel}, {@link ToolsPanel},
 *       {@link EnvPanel}, {@link TasksPanel}, {@link DetailPanel}, {@link LogPanel},
 *       plus the {@link RegistryModal} and {@link HelpOverlay} overlays</li>
 * </ul>
 */
@Component
public final class MiseTuiApp extends ToolkitApp implements UiContext {

    private static final int SIDEBAR_WIDTH = 44;
    /** Below this terminal height the sidebar collapses unfocused panels to their title bar. */
    private static final int ACCORDION_HEIGHT = 28;
    /** A collapsed panel: just the top border with the title, plus the bottom border. */
    private static final int COLLAPSED_HEIGHT = 2;
    private static final int STATUS_HEIGHT = 8;

    private final UiState state;
    private final MiseActions actions;

    private final StatusPanel statusPanel;
    private final ToolsPanel toolsPanel;
    private final EnvPanel envPanel;
    private final TasksPanel tasksPanel;
    private final DetailPanel detailPanel;
    private final LogPanel logPanel;
    private final RegistryModal registryModal;
    private final ConfigEditorModal configEditor;
    private final ConfirmModal confirmModal;
    private final TaskArgsModal taskArgsModal;
    private final HelpOverlay helpOverlay;

    private final TamboConfig config;

    public MiseTuiApp(@NonNull MiseQueryService query, @NonNull MiseToolService tools,
                      @NonNull MiseMaintenanceService maintenance, @NonNull ShellActivationService activation,
                      @NonNull CancelRegistry cancelRegistry, @NonNull TamboConfig config,
                      @Qualifier("miseTaskExecutor") @NonNull AsyncTaskExecutor executor) {
        this.config = config;
        this.state = new UiState();
        this.actions = new MiseActions(query, tools, maintenance, activation, cancelRegistry,
                executor, state, r -> runner().runOnRenderThread(r));

        this.statusPanel = new StatusPanel(this);
        this.toolsPanel = new ToolsPanel(this);
        this.envPanel = new EnvPanel(this);
        this.tasksPanel = new TasksPanel(this);
        this.detailPanel = new DetailPanel(this, toolsPanel, tasksPanel);
        this.logPanel = new LogPanel(this);
        this.registryModal = new RegistryModal(this);
        this.configEditor = new ConfigEditorModal(this);
        this.confirmModal = new ConfirmModal(this);
        this.taskArgsModal = new TaskArgsModal(this);
        this.helpOverlay = new HelpOverlay(this);
    }

    // ==================== UiContext ====================

    @Override
    public UiState state() {
        return state;
    }

    @Override
    public MiseActions actions() {
        return actions;
    }

    @Override
    public Theme theme() {
        return config.theme();
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
        return !registryModal.isOpen() && !configEditor.isOpen() && !confirmModal.isOpen()
                && !taskArgsModal.isOpen();
    }

    @Override
    public void confirm(String message, Runnable onConfirm) {
        confirmModal.open(message, onConfirm);
    }

    @Override
    public void promptTaskArgs(String taskName, String initialArgs) {
        taskArgsModal.open(taskName, initialArgs);
    }

    // ==================== Lifecycle ====================

    @Override
    protected TuiConfig configure() {
        return TuiConfig.builder().mouseCapture(true).bindings(navBindings()).build();
    }

    /**
     * Standard bindings plus j/k on moveUp/moveDown, so panels whose built-in list
     * handling does the scrolling (the command log) accept vim keys like the panels
     * that translate them by hand in {@code Ui.applyNav}. Not the full vim set:
     * vim bindings would also claim g/G/x, which are mise actions here. Any
     * {@code keys.*} entries from {@link TamboConfig} are layered on last so users
     * can remap navigation.
     */
    private Bindings navBindings() {
        String overlay = "moveUp = Up, k\nmoveDown = Down, j\n" + config.keyOverlay();
        try {
            return BindingSets.load(
                    new ByteArrayInputStream(overlay.getBytes(StandardCharsets.UTF_8)),
                    BindingSets.standard());
        } catch (IOException e) {
            return BindingSets.standard(); // unreachable: the stream is in-memory
        }
    }

    @Override
    protected void onStart() {
        state.addLog(LogLevel.INFO, "tambo — a lazygit-style TUI for mise. Press ? for help, a to add an SDK.");
        registerGlobalKeys();
        actions.loadInitial();
    }

    // ==================== Global keys ====================

    private void registerGlobalKeys() {
        runner().eventRouter().addGlobalHandler(event -> {
            if (!(event instanceof KeyEvent key)) {
                return EventResult.UNHANDLED;
            }
            if (helpOverlay.isOpen()) {
                if (key.isCancel() || key.isConfirm() || key.isChar('?')) {
                    helpOverlay.close();
                }
                return EventResult.HANDLED;
            }
            if (confirmModal.isOpen()) {
                // No focusable input of its own — the confirm dialog reads its keys here.
                confirmModal.handleKey(key);
                return EventResult.HANDLED;
            }
            if (registryModal.isOpen() || configEditor.isOpen() || taskArgsModal.isOpen()) {
                // The modal's input box / text area is focused and consumes everything
                // it needs; never let panel shortcuts fire underneath it.
                return EventResult.UNHANDLED;
            }
            if (key.isChar('?')) {
                helpOverlay.open();
                return EventResult.HANDLED;
            }
            if (key.isChar('a')) {
                registryModal.open();
                return EventResult.HANDLED;
            }
            if (key.isChar('A')) {
                actions.activateMise();
                return EventResult.HANDLED;
            }
            if (key.isChar('T')) {
                actions.trustProject();
                return EventResult.HANDLED;
            }
            if (key.isChar('e')) {
                configEditor.open(Path.of("mise.toml"), "./mise.toml");
                return EventResult.HANDLED;
            }
            if (key.isChar('E')) {
                configEditor.open(globalConfigPath(), "global config.toml");
                return EventResult.HANDLED;
            }
            if (key.isChar('D')) {
                actions.runDoctor();
                return EventResult.HANDLED;
            }
            if (key.isChar('U')) {
                actions.selfUpdate();
                return EventResult.HANDLED;
            }
            if (key.isChar('P')) {
                if (state.outdated().isEmpty()) {
                    state.addLog(LogLevel.INFO, "All tools are up to date");
                } else {
                    confirm("Upgrade all " + state.outdated().size() + " outdated tool(s)?", actions::upgradeAll);
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('X')) {
                confirm("Prune unused/old tool versions?", actions::prune);
                return EventResult.HANDLED;
            }
            if (key.isChar('1')) {
                focus(PanelIds.STATUS);
                return EventResult.HANDLED;
            }
            if (key.isChar('2')) {
                focus(PanelIds.TOOLS);
                return EventResult.HANDLED;
            }
            if (key.isChar('3')) {
                focus(PanelIds.ENV);
                return EventResult.HANDLED;
            }
            if (key.isChar('4')) {
                focus(PanelIds.TASKS);
                return EventResult.HANDLED;
            }
            if (key.isChar('5')) {
                focus(PanelIds.LOG);
                return EventResult.HANDLED;
            }
            if (key.isChar('r') && !key.hasCtrl()) {
                actions.refresh();
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        });
    }

    // ==================== Layout ====================

    @Override
    protected Element render() {
        Element body = dock()
                .top(buildHeader(), length(1))
                .bottom(buildFooter(), length(1))
                .center(row(
                        buildSidebar().constraint(length(SIDEBAR_WIDTH)),
                        buildMainColumn().constraint(fill())
                ));

        if (helpOverlay.isOpen()) {
            return stack(body, helpOverlay.build());
        }
        if (registryModal.isOpen()) {
            return stack(body, registryModal.build());
        }
        if (configEditor.isOpen()) {
            return stack(body, configEditor.build());
        }
        if (confirmModal.isOpen()) {
            return stack(body, confirmModal.build());
        }
        if (taskArgsModal.isOpen()) {
            return stack(body, taskArgsModal.build());
        }
        return body;
    }

    /** The user-level mise config file, honoring {@code MISE_CONFIG_DIR} when set. */
    private static Path globalConfigPath() {
        String configDir = System.getenv("MISE_CONFIG_DIR");
        return configDir != null && !configDir.isBlank()
                ? Path.of(configDir, "config.toml")
                : Path.of(System.getProperty("user.home"), ".config", "mise", "config.toml");
    }

    private Column buildSidebar() {
        if (terminalHeight() >= ACCORDION_HEIGHT) {
            return column(
                    statusPanel.build().constraint(length(STATUS_HEIGHT)),
                    toolsPanel.build().constraint(fill(3)),
                    envPanel.build().constraint(fill(1)),
                    tasksPanel.build().constraint(fill(2))
            );
        }
        // lazygit-style accordion for cramped terminals: the focused panel gets all
        // the space, every other panel collapses to just its title bar.
        String focus = focusedId();
        String expanded = switch (focus) {
            case PanelIds.STATUS, PanelIds.ENV, PanelIds.TASKS -> focus;
            case null, default -> PanelIds.TOOLS;
        };
        return column(
                statusPanel.build().constraint(sidebarConstraint(expanded, PanelIds.STATUS, length(STATUS_HEIGHT))),
                toolsPanel.build().constraint(sidebarConstraint(expanded, PanelIds.TOOLS, fill())),
                envPanel.build().constraint(sidebarConstraint(expanded, PanelIds.ENV, fill())),
                tasksPanel.build().constraint(sidebarConstraint(expanded, PanelIds.TASKS, fill()))
        );
    }

    private static Constraint sidebarConstraint(String expandedId, String panelId, Constraint whenExpanded) {
        return panelId.equals(expandedId) ? whenExpanded : length(COLLAPSED_HEIGHT);
    }

    private int terminalHeight() {
        try {
            return runner().tuiRunner().terminal().size().height();
        } catch (Exception e) {
            return Integer.MAX_VALUE; // size unavailable — keep the normal layout
        }
    }

    private Column buildMainColumn() {
        return column(
                detailPanel.build().constraint(fill(3)),
                logPanel.build().constraint(fill(1))
        );
    }

    private Element buildHeader() {
        Color statusColor = state.doctor().activated() ? Color.GREEN : Color.YELLOW;
        String statusText = state.doctor().activated() ? "activated" : "not activated";
        return row(
                text(" tambo ").bold().cyan(),
                text("— a TUI for mise").dim(),
                spacer(),
                text("mise " + state.doctor().version() + "  ").dim(),
                text(statusText).fg(statusColor)
        );
    }

    private Element buildFooter() {
        String hints;
        if (confirmModal.isOpen()) {
            hints = confirmModal.footerHint();
        } else if (taskArgsModal.isOpen()) {
            hints = taskArgsModal.footerHint();
        } else if (registryModal.isOpen()) {
            hints = registryModal.footerHint();
        } else if (configEditor.isOpen()) {
            hints = configEditor.footerHint();
        } else {
            String focus = focusedId();
            hints = switch (focus) {
                case PanelIds.TOOLS -> "↑/↓ select   / filter   i install   u use   x uninstall   g global   p upgrade   c cancel";
                case PanelIds.TASKS -> "↑/↓ select   / filter   enter run   : args   . re-run   c cancel";
                case PanelIds.ENV -> "↑/↓ scroll   / filter   y copy value";
                case PanelIds.LOG -> "↑/↓ j/k scroll   ←/→ h/l pan   PgUp/PgDn page   End follow newest";
                case null, default -> "1-5 jump   tab cycle";
            };
        }
        return row(
                text(" " + hints).fg(Color.CYAN),
                spacer(),
                text("a add   e edit   A activate   T trust   D doctor   U update   P upgrade-all   X prune   r refresh   ? help   q quit ").dim()
        );
    }
}
