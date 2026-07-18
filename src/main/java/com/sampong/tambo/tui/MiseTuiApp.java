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

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyEvent;

import com.sampong.tambo.mise.MiseService;
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

    private final UiState state;
    private final MiseActions actions;

    private final StatusPanel statusPanel;
    private final ToolsPanel toolsPanel;
    private final EnvPanel envPanel;
    private final TasksPanel tasksPanel;
    private final DetailPanel detailPanel;
    private final LogPanel logPanel;
    private final RegistryModal registryModal;
    private final HelpOverlay helpOverlay;

    public MiseTuiApp(MiseService mise, @Qualifier("miseTaskExecutor") AsyncTaskExecutor executor) {
        this.state = new UiState();
        this.actions = new MiseActions(mise, executor, state, r -> runner().runOnRenderThread(r));

        this.statusPanel = new StatusPanel(this);
        this.toolsPanel = new ToolsPanel(this);
        this.envPanel = new EnvPanel(this);
        this.tasksPanel = new TasksPanel(this);
        this.detailPanel = new DetailPanel(this, toolsPanel, tasksPanel);
        this.logPanel = new LogPanel(this);
        this.registryModal = new RegistryModal(this);
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
        return !registryModal.isOpen();
    }

    // ==================== Lifecycle ====================

    @Override
    protected TuiConfig configure() {
        return TuiConfig.builder().mouseCapture(true).build();
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
            if (registryModal.isOpen()) {
                // The modal's input box is focused and consumes everything it needs;
                // never let panel shortcuts fire underneath it.
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
        return body;
    }

    private Column buildSidebar() {
        return column(
                statusPanel.build().constraint(length(7)),
                toolsPanel.build().constraint(fill(3)),
                envPanel.build().constraint(fill(1)),
                tasksPanel.build().constraint(fill(2))
        );
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
        } else {
            String focus = focusedId();
            hints = switch (focus) {
                case PanelIds.TOOLS -> "↑/↓ j/k select   i install   u use in project   x uninstall   g set global";
                case PanelIds.TASKS -> "↑/↓ j/k select   enter run task";
                case PanelIds.ENV -> "↑/↓ j/k scroll";
                case null, default -> "1-4 jump   tab cycle";
            };
        }
        return row(
                text(" " + hints).fg(Color.CYAN),
                spacer(),
                text("a add sdk   r refresh   ? help   q quit ").dim()
        );
    }
}
