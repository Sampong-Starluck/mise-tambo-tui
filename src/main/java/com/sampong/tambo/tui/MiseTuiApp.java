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
import org.springframework.boot.ApplicationArguments;
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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo.mise.MiseMaintenanceService;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo.mise.implement.MiseSdkBackend;
import com.sampong.tambo.mise.implement.MiseShellActivationServiceImp;
import com.sampong.tambo._common.service.SdkVersionBackend;
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

    private static final int SIDEBAR_WIDTH = 56;
    /** Below this terminal height the sidebar collapses unfocused panels to their title bar. */
    private static final int ACCORDION_HEIGHT = 28;
    /** A collapsed panel: just the top border with the title, plus the bottom border. */
    private static final int COLLAPSED_HEIGHT = 2;
    private static final int STATUS_HEIGHT = 8;
    /** Fixed height of the Advanced panel — only shown once {@code V} / {@code --advanced-features} is on. */
    private static final int ADVANCED_HEIGHT = 9;

    private final UiState state;
    private final MiseActions actions;

    private final StatusPanel statusPanel;
    private final ToolsPanel toolsPanel;
    private final EnvPanel envPanel;
    private final TasksPanel tasksPanel;
    private final DetailPanel detailPanel;
    private final LogPanel logPanel;
    private final AdvancedPanel advancedPanel;
    private final RegistryModal registryModal;
    private final ConfigEditorModal configEditor;
    private final ConfirmModal confirmModal;
    private final TaskArgsModal taskArgsModal;
    private final AddPluginModal addPluginModal;
    private final HelpOverlay helpOverlay;

    private final TamboConfig config;

    public MiseTuiApp(@NonNull MiseQueryService query, @NonNull MiseToolService tools,
                      @NonNull MiseMaintenanceService maintenance,
                      @NonNull MiseShellActivationServiceImp miseActivation,
                      @NonNull VfoxShellActivationServiceImp vfoxActivation,
                      @NonNull CancelRegistry cancelRegistry, @NonNull TamboConfig config,
                      @NonNull MiseSdkBackend miseSdkBackend, @NonNull VfoxSdkBackend vfoxSdkBackend,
                      @Qualifier("miseTaskExecutor") @NonNull AsyncTaskExecutor executor,
                      @NonNull ApplicationArguments arguments) {
        this.config = config;
        this.state = new UiState();
        this.state.offline(arguments.containsOption("offline"));
        this.state.advancedFeatures(arguments.containsOption("advanced-features"));
        boolean useVfox = resolveUseVfox(arguments);
        this.state.vfox(useVfox);
        SdkVersionBackend sdkBackend = useVfox ? vfoxSdkBackend : miseSdkBackend;
        ShellActivationService activation = useVfox ? vfoxActivation : miseActivation;
        this.actions = new MiseActions(query, tools, maintenance, activation, cancelRegistry,
                executor, state, r -> runner().runOnRenderThread(r), sdkBackend, vfoxSdkBackend);

        this.statusPanel = new StatusPanel(this);
        this.toolsPanel = new ToolsPanel(this);
        this.envPanel = new EnvPanel(this);
        this.tasksPanel = new TasksPanel(this);
        this.detailPanel = new DetailPanel(this, toolsPanel, tasksPanel);
        this.logPanel = new LogPanel(this);
        this.advancedPanel = new AdvancedPanel(this);
        this.registryModal = new RegistryModal(this);
        this.configEditor = new ConfigEditorModal(this);
        this.confirmModal = new ConfirmModal(this);
        this.taskArgsModal = new TaskArgsModal(this);
        this.addPluginModal = new AddPluginModal(this);
        this.helpOverlay = new HelpOverlay(this);
    }

    private static final String MISE_CONFIG_FILE = "mise.toml";
    /** vfox's actual project-scope config filename — dot-prefixed, per vfox's own convention. */
    private static final String VFOX_CONFIG_FILE = ".vfox.toml";

    /** The project-scope config filename for whichever backend is active — used by the 'e' key. */
    private String projectConfigFileName() {
        return state.vfox() ? VFOX_CONFIG_FILE : MISE_CONFIG_FILE;
    }

    /**
     * Picks the SDK backend for this project — used consistently for both tool
     * install/use/list and shell activation. {@code --backend=mise|vfox} is an explicit
     * override; otherwise this detects an existing {@link #MISE_CONFIG_FILE} or
     * {@link #VFOX_CONFIG_FILE} in the working directory. If neither exists, it asks once on
     * the console — before the TUI takes over the terminal, so plain stdin/stdout still work —
     * and creates the chosen config file so the choice sticks on the next launch.
     */
    private static boolean resolveUseVfox(ApplicationArguments arguments) {
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

        boolean useVfox = promptForVfox();
        createProjectConfig(cwd, useVfox);
        return useVfox;
    }

    /** Asks once on the console which backend to use. Blank input or closed stdin defaults to mise. */
    private static boolean promptForVfox() {
        System.out.println();
        System.out.println("No " + MISE_CONFIG_FILE + " or " + VFOX_CONFIG_FILE + " found in this project.");
        System.out.print("Which version manager should tambo use here? [mise/vfox] (default mise): ");
        System.out.flush();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                String line = in.readLine();
                if (line == null || line.isBlank()) {
                    return false;
                }
                String answer = line.strip().toLowerCase();
                if (answer.equals("vfox")) {
                    return true;
                }
                if (answer.equals("mise")) {
                    return false;
                }
                System.out.print("Please type \"mise\" or \"vfox\": ");
                System.out.flush();
            }
        } catch (IOException e) {
            return false;
        }
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
                && !taskArgsModal.isOpen() && !addPluginModal.isOpen();
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
        state.addLog(LogLevel.INFO, "tambo — a lazygit-style TUI for " + (state.vfox() ? "vfox" : "mise")
                + ". Press ? for help, a to add an SDK.");
        registerGlobalKeys();
        actions.loadInitial();
    }

    // ==================== Global keys ====================

    /**
     * Gates a maintenance/config action behind {@link UiState#advancedFeatures()}; logs a nudge
     * toward {@code V} and returns false instead of running it when the flag is off.
     */
    private boolean requireAdvanced(String action) {
        if (state.advancedFeatures()) {
            return true;
        }
        state.addLog(LogLevel.INFO, action + " is an advanced feature — press V to enable it");
        return false;
    }

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
            if (registryModal.isOpen() || configEditor.isOpen() || taskArgsModal.isOpen() || addPluginModal.isOpen()) {
                // The modal's input box / text area is focused and consumes everything
                // it needs; never let panel shortcuts fire underneath it.
                return EventResult.UNHANDLED;
            }
            if (key.isChar('?')) {
                helpOverlay.open();
                return EventResult.HANDLED;
            }
            if (key.isChar('V')) {
                state.advancedFeatures(!state.advancedFeatures());
                state.addLog(LogLevel.INFO, state.advancedFeatures()
                        ? "Advanced features enabled — see the Advanced panel"
                        : "Advanced features hidden");
                return EventResult.HANDLED;
            }
            if (key.isChar('a')) {
                if (state.offline()) {
                    state.addLog(LogLevel.INFO, "Offline mode — Add SDK needs network access");
                } else {
                    registryModal.open();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('A')) {
                actions.activateMise();
                return EventResult.HANDLED;
            }
            if (key.isChar('T') && !state.vfox()) {
                if (requireAdvanced("Trust")) {
                    actions.trustProject();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('e')) {
                configEditor.open(Path.of(projectConfigFileName()), "./" + projectConfigFileName());
                return EventResult.HANDLED;
            }
            if (key.isChar('E') && !state.vfox()) {
                if (requireAdvanced("Editing the global config")) {
                    configEditor.open(globalConfigPath(), "global config.toml");
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('D') && !state.vfox()) {
                if (requireAdvanced("mise doctor")) {
                    actions.runDoctor();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('U')) {
                if (requireAdvanced(state.vfox() ? "vfox upgrade" : "mise self-update")) {
                    actions.selfUpdate();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('p') && state.vfox()) {
                // vfox-only: 'p' is free (mise's ToolsPanel binds it to per-tool upgrade
                // instead) — used here for the everyday "add plugin" flow, fuzzy-finding
                // the catalog. The [advanced] --alias/--source raw syntax stays behind 'P'.
                if (state.offline()) {
                    state.addLog(LogLevel.INFO, "Offline mode — Add plugin needs network access");
                } else {
                    addPluginModal.open();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('P')) {
                if (state.vfox()) {
                    if (requireAdvanced("Add plugin with --alias/--source")) {
                        if (state.offline()) {
                            state.addLog(LogLevel.INFO, "Offline mode — Add plugin needs network access");
                        } else {
                            addPluginModal.open();
                        }
                    }
                } else if (state.offline()) {
                    state.addLog(LogLevel.INFO, "Offline mode — can't check for outdated tools");
                } else if (state.outdated().isEmpty()) {
                    state.addLog(LogLevel.INFO, "All tools are up to date");
                } else {
                    confirm("Upgrade all " + state.outdated().size() + " outdated tool(s)?", actions::upgradeAll);
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('C')) {
                // Panel-local 'c' only cancels what the cursor is on; this always
                // stops everything, however the user has moved around since.
                actions.cancelAll();
                return EventResult.HANDLED;
            }
            if (key.isChar('X') && !state.vfox()) {
                if (requireAdvanced("Prune")) {
                    confirm("Prune unused/old tool versions?", actions::prune);
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('1') && !state.vfox()) {
                focus(PanelIds.STATUS);
                return EventResult.HANDLED;
            }
            if (key.isChar('2')) {
                focus(PanelIds.TOOLS);
                return EventResult.HANDLED;
            }
            if (key.isChar('3') && !state.vfox()) {
                focus(PanelIds.ENV);
                return EventResult.HANDLED;
            }
            if (key.isChar('4') && !state.vfox()) {
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
        if (addPluginModal.isOpen()) {
            return stack(body, addPluginModal.build());
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
        // Status/Env/Tasks are entirely mise-derived (doctor/trust/env/tasks) with no vfox
        // equivalent, so vfox mode shows Tools alone rather than three panels of nothing.
        if (state.vfox()) {
            return state.advancedFeatures()
                    ? column(
                            toolsPanel.build().constraint(fill()),
                            advancedPanel.build().constraint(length(ADVANCED_HEIGHT)))
                    : column(toolsPanel.build().constraint(fill()));
        }
        if (terminalHeight() >= ACCORDION_HEIGHT) {
            Column expanded = column(
                    statusPanel.build().constraint(length(STATUS_HEIGHT)),
                    toolsPanel.build().constraint(fill(3)),
                    envPanel.build().constraint(fill(1)),
                    tasksPanel.build().constraint(fill(2))
            );
            return state.advancedFeatures()
                    ? column(expanded.constraint(fill()), advancedPanel.build().constraint(length(ADVANCED_HEIGHT)))
                    : expanded;
        }
        // lazygit-style accordion for cramped terminals: the focused panel gets all
        // the space, every other panel collapses to just its title bar. The Advanced
        // panel is skipped here — there is no room to spare once panels are this tight.
        String focus = focusedId();
        String expandedId = switch (focus) {
            case PanelIds.STATUS, PanelIds.ENV, PanelIds.TASKS -> focus;
            case null, default -> PanelIds.TOOLS;
        };
        return column(
                statusPanel.build().constraint(sidebarConstraint(expandedId, PanelIds.STATUS, length(STATUS_HEIGHT))),
                toolsPanel.build().constraint(sidebarConstraint(expandedId, PanelIds.TOOLS, fill())),
                envPanel.build().constraint(sidebarConstraint(expandedId, PanelIds.ENV, fill())),
                tasksPanel.build().constraint(sidebarConstraint(expandedId, PanelIds.TASKS, fill()))
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
        if (state.vfox()) {
            // No `vfox doctor` equivalent exists — nothing to query, so this is a static badge.
            return row(
                    text(" tambo ").bold().cyan(),
                    text("— a TUI for vfox").dim(),
                    spacer(),
                    text("vfox").fg(Color.GREEN)
            );
        }
        // Both fields here come from `mise doctor`, which loads lazily; until it
        // answers the header stays neutral rather than announcing "not activated".
        actions.ensureDoctor();
        if (!state.doctorLazy().everLoaded()) {
            return row(
                    text(" tambo ").bold().cyan(),
                    text("— a TUI for mise").dim(),
                    spacer(),
                    text("checking mise…").dim()
            );
        }
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
        } else if (addPluginModal.isOpen()) {
            hints = addPluginModal.footerHint();
        } else if (registryModal.isOpen()) {
            hints = registryModal.footerHint();
        } else if (configEditor.isOpen()) {
            hints = configEditor.footerHint();
        } else {
            String focus = focusedId();
            hints = switch (focus) {
                case PanelIds.TOOLS -> state.vfox()
                        ? "↑/↓ select   / filter   ←/→ pan   i install   u use   x uninstall   R remove   g global   c cancel   C all"
                        : "↑/↓ select   / filter   ←/→ pan   i install   u use   x uninstall   g global   p upgrade   c cancel   C all";
                case PanelIds.TASKS -> "↑/↓ select   / filter   ←/→ pan   enter run   : args   . re-run   c cancel   C all";
                case PanelIds.ENV -> "↑/↓ scroll   / filter   ←/→ pan   y copy value";
                case PanelIds.LOG -> "↑/↓ j/k scroll   ←/→ h/l pan   PgUp/PgDn page   End follow newest";
                case null, default -> state.vfox() ? "2,5 jump   tab cycle" : "1-5 jump   tab cycle";
            };
        }
        return row(
                text(" " + hints).fg(Color.CYAN),
                spacer(),
                text(globalKeyHints()).dim()
        );
    }

    /**
     * The always-available keys. "U update" drops out once mise has told us its
     * self-update was compiled out, so the footer stops advertising an action
     * that cannot succeed on this install.
     */
    private String globalKeyHints() {
        if (state.vfox()) {
            return "a add   p add plugin   e edit   A activate   P alias/source   U update   r refresh   ? help   q quit ";
        }
        String update = state.selfUpdateDisabled() ? "" : "U update   ";
        return "a add   e edit   A activate   T trust   D doctor   " + update
                + "P upgrade-all   X prune   r refresh   ? help   q quit ";
    }
}
