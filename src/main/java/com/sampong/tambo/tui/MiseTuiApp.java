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

import com.sampong.tambo.mise.MiseMaintenanceService;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo.tui.panel.ConfigEditorModal;
import com.sampong.tambo.tui.panel.DetailPanel;
import com.sampong.tambo.tui.panel.EnvPanel;
import com.sampong.tambo.tui.panel.HelpOverlay;
import com.sampong.tambo.tui.panel.LogPanel;
import com.sampong.tambo.tui.panel.RegistryModal;
import com.sampong.tambo.tui.panel.StatusPanel;
import com.sampong.tambo.tui.panel.TasksPanel;
import com.sampong.tambo.tui.panel.ToolsPanel;

/**
 * A lazygit-style terminal UI for <a href="https://mise.jdx.dev">mise</a>.
 * <p>
 * This class is a thin orchestrator: it owns the lifecycle, the global key
 * bindings, and the top-level layout. Everything else lives in components:
 * <ul>
 *   <li>{@link UiState} — the shared data every panel renders from</li>
 *   <li>{@link MiseActions} — background {@code mise} operations</li>
 *   <li>{@code panel/} — one class per panel: {@link StatusPanel}, {@link ToolsPanel},
 *       {@link EnvPanel}, {@link TasksPanel}, {@link DetailPanel}, {@link LogPanel},
 *       plus the {@link RegistryModal} and {@link HelpOverlay} overlays</li>
 * </ul>
 * Panels reach back into the app through the narrow {@link UiContext} interface.
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
    private final HelpOverlay helpOverlay;

    public MiseTuiApp(MiseQueryService query, MiseToolService tools,
                      MiseMaintenanceService maintenance, ShellActivationService activation,
                      @Qualifier("miseTaskExecutor") AsyncTaskExecutor executor) {
        this.state = new UiState();
        this.actions = new MiseActions(query, tools, maintenance, activation,
                executor, state, r -> runner().runOnRenderThread(r));

        this.statusPanel = new StatusPanel(this);
        this.toolsPanel = new ToolsPanel(this);
        this.envPanel = new EnvPanel(this);
        this.tasksPanel = new TasksPanel(this);
        this.detailPanel = new DetailPanel(this, toolsPanel, tasksPanel);
        this.logPanel = new LogPanel(this);
        this.registryModal = new RegistryModal(this);
        this.configEditor = new ConfigEditorModal(this);
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
    public String focusedId() {
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
        return !registryModal.isOpen() && !configEditor.isOpen();
    }

    // ==================== Lifecycle ====================

    @Override
    protected TuiConfig configure() {
        return TuiConfig.builder().mouseCapture(true).bindings(navBindings()).build();
    }

    /**
     * Standard bindings plus j/k on moveUp/moveDown, so panels whose built-in list
     * handling does the scrolling (the command log) accept vim keys like the panels
     * that translate them by hand in {@link Ui#applyNav}. Not the full vim set:
     * vim bindings would also claim g/G/x, which are mise actions here.
     */
    private static Bindings navBindings() {
        String overlay = "moveUp = Up, k\nmoveDown = Down, j\n";
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
            if (registryModal.isOpen() || configEditor.isOpen()) {
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
        if (registryModal.isOpen()) {
            hints = registryModal.footerHint();
        } else if (configEditor.isOpen()) {
            hints = configEditor.footerHint();
        } else {
            String focus = focusedId();
            hints = switch (focus) {
                case PanelIds.TOOLS -> "↑/↓ j/k select   i install   u use in project   x uninstall   g set global";
                case PanelIds.TASKS -> "↑/↓ j/k select   enter run task";
                case PanelIds.ENV -> "↑/↓ j/k scroll";
                case PanelIds.LOG -> "↑/↓ j/k scroll   ←/→ h/l pan   PgUp/PgDn page   End follow newest";
                case null, default -> "1-5 jump   tab cycle";
            };
        }
        return row(
                text(" " + hints).fg(Color.CYAN),
                spacer(),
                text("a add sdk   e edit config   A activate   T trust   D doctor   U update   r refresh   ? help   q quit ").dim()
        );
    }
}
