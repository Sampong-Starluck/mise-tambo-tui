package com.sampong.tambo.tui.keys;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.EventRouter;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.event.KeyEvent;

import com.sampong.tambo.tui.TuiComponents;
import com.sampong.tambo.tui.components.AdvancedPanel;
import com.sampong.tambo.tui.features.TamboConfig;
import com.sampong.tambo.tui.lifecycle.AppLifecycle;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Everything about which key does what: the single global handler {@code MiseTuiApp} routes
 * every {@link KeyEvent} through, the nav bindings (arrows + vim {@code j}/{@code k} + user
 * overrides) TamboUI itself consumes, and the Advanced menu's action list — the same actions
 * the maintenance/config letters (T/E/D/U/X/B/P) invoke directly, just also reachable as a
 * selectable menu (see {@code AdvancedPanel}) instead of requiring the letter to be remembered.
 * <p>
 * Owns no rendering of its own; {@code com.sampong.tambo.tui.layout.AppLayout} is the
 * counterpart that turns state into what's on screen.
 */
@RequiredArgsConstructor
public final class GlobalKeyBindings {

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final TuiComponents ui;
    @NonNull
    private final TamboConfig config;

    /**
     * Standard bindings plus j/k on moveUp/moveDown, so panels whose built-in list handling
     * does the scrolling (the command log) accept vim keys like the panels that translate them
     * by hand in {@code Ui.applyNav}. Not the full vim set: vim bindings would also claim
     * g/G/x, which are mise actions here. Any {@code keys.*} entries from {@link TamboConfig}
     * are layered on last so users can remap navigation.
     */
    public Bindings navBindings() {
        String overlay = "moveUp = Up, k\nmoveDown = Down, j\n" + config.keyOverlay();
        try {
            return BindingSets.load(
                    new ByteArrayInputStream(overlay.getBytes(StandardCharsets.UTF_8)),
                    BindingSets.standard());
        } catch (IOException e) {
            return BindingSets.standard(); // unreachable: the stream is in-memory
        }
    }

    /**
     * The project-scope config filename for whichever backend is active — used by the
     * {@code e} key.
     */
    public static String projectConfigFileName(boolean vfox) {
        return vfox ? AppLifecycle.VFOX_CONFIG_FILE : AppLifecycle.MISE_CONFIG_FILE;
    }

    /** Registers the single global key handler every keypress not claimed by a focused input goes through. */
    public void register(@NonNull EventRouter router) {
        router.addGlobalHandler(event -> {
            if (!(event instanceof KeyEvent key)) {
                return EventResult.UNHANDLED;
            }
            if (ui.selectBackendModal().isOpen()) {
                // No focusable input of its own, and nothing else should be reachable
                // until the first-run choice is made — swallow every key here.
                ui.selectBackendModal().handleKey(key);
                return EventResult.HANDLED;
            }
            if (ui.helpOverlay().isOpen()) {
                if (key.isCancel() || key.isConfirm() || key.isChar('?')) {
                    ui.helpOverlay().close();
                }
                return EventResult.HANDLED;
            }
            if (ui.confirmModal().isOpen()) {
                // No focusable input of its own — the confirm dialog reads its keys here.
                ui.confirmModal().handleKey(key);
                return EventResult.HANDLED;
            }
            if (ui.switchBackendModal().isOpen()) {
                // No focusable input of its own — the backend picker reads its keys here.
                ui.switchBackendModal().handleKey(key);
                return EventResult.HANDLED;
            }
            if (ui.registryModal().isOpen() || ui.configEditor().isOpen() || ui.taskArgsModal().isOpen()
                    || ui.addPluginModal().isOpen()) {
                // The modal's input box / text area is focused and consumes everything
                // it needs; never let panel shortcuts fire underneath it.
                return EventResult.UNHANDLED;
            }
            if (key.isChar('?')) {
                ui.helpOverlay().open();
                return EventResult.HANDLED;
            }
            if (key.isChar('V')) {
                boolean enabling = !ctx.state().advancedFeatures();
                ctx.state().advancedFeatures(enabling);
                if (enabling) {
                    // Focus the panel this key just revealed — otherwise ↑/↓ stay routed
                    // to whatever had focus before and silently do nothing.
                    ctx.focus(PanelIds.ADVANCED);
                } else if (PanelIds.ADVANCED.equals(ctx.focusedId())) {
                    ctx.focus(PanelIds.TOOLS);
                }
                ctx.state().addLog(LogLevel.INFO, enabling
                        ? "Advanced features enabled — see the Advanced panel"
                        : "Advanced features hidden");
                return EventResult.HANDLED;
            }
            if (key.isChar('a')) {
                if (ctx.state().offline()) {
                    ctx.state().addLog(LogLevel.INFO, "Offline mode — Add SDK needs network access");
                } else {
                    ui.registryModal().open();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('A')) {
                ctx.actions().activateMise();
                return EventResult.HANDLED;
            }
            if (key.isChar('T') && !ctx.state().vfox()) {
                if (requireAdvanced("Trust")) {
                    ctx.actions().trustProject();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('e')) {
                String file = projectConfigFileName(ctx.state().vfox());
                ui.configEditor().open(Path.of(file), "./" + file);
                return EventResult.HANDLED;
            }
            if (key.isChar('E') && !ctx.state().vfox()) {
                if (requireAdvanced("Editing the global config")) {
                    ui.configEditor().open(globalConfigPath(), "global config.toml");
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('D') && !ctx.state().vfox()) {
                if (requireAdvanced("mise doctor")) {
                    ctx.actions().runDoctor();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('U')) {
                if (requireAdvanced(ctx.state().vfox() ? "vfox upgrade" : "mise self-update")) {
                    ctx.actions().selfUpdate();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('B')) {
                if (requireAdvanced("Switch UI backend")) {
                    ui.switchBackendModal().open(this::applyBackendChoice);
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('p') && ctx.state().vfox()) {
                // vfox-only: 'p' is free (mise's ToolsPanel binds it to per-tool upgrade
                // instead) — used here for the everyday "add plugin" flow, fuzzy-finding
                // the catalog. The [advanced] --alias/--source raw syntax stays behind 'P'.
                if (ctx.state().offline()) {
                    ctx.state().addLog(LogLevel.INFO, "Offline mode — Add plugin needs network access");
                } else {
                    ui.addPluginModal().open();
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('P')) {
                if (ctx.state().vfox()) {
                    if (requireAdvanced("Add plugin with --alias/--source")) {
                        if (ctx.state().offline()) {
                            ctx.state().addLog(LogLevel.INFO, "Offline mode — Add plugin needs network access");
                        } else {
                            ui.addPluginModal().open();
                        }
                    }
                } else if (ctx.state().offline()) {
                    ctx.state().addLog(LogLevel.INFO, "Offline mode — can't check for outdated tools");
                } else if (ctx.state().outdated().isEmpty()) {
                    ctx.state().addLog(LogLevel.INFO, "All tools are up to date");
                } else {
                    ctx.confirm("Upgrade all " + ctx.state().outdated().size() + " outdated tool(s)?",
                            ctx.actions()::upgradeAll);
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('C')) {
                // Panel-local 'c' only cancels what the cursor is on; this always
                // stops everything, however the user has moved around since.
                ctx.actions().cancelAll();
                return EventResult.HANDLED;
            }
            if (key.isChar('X') && !ctx.state().vfox()) {
                if (requireAdvanced("Prune")) {
                    ctx.confirm("Prune unused/old tool versions?", ctx.actions()::prune);
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('1')) {
                ctx.focus(PanelIds.STATUS);
                return EventResult.HANDLED;
            }
            if (key.isChar('2')) {
                ctx.focus(PanelIds.TOOLS);
                return EventResult.HANDLED;
            }
            if (key.isChar('3') && !ctx.state().vfox()) {
                ctx.focus(PanelIds.ENV);
                return EventResult.HANDLED;
            }
            if (key.isChar('4') && !ctx.state().vfox()) {
                ctx.focus(PanelIds.TASKS);
                return EventResult.HANDLED;
            }
            if (key.isChar('5')) {
                ctx.focus(PanelIds.LOG);
                return EventResult.HANDLED;
            }
            if (key.isChar('6')) {
                if (requireAdvanced("The Advanced panel")) {
                    ctx.focus(PanelIds.ADVANCED);
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('r') && !key.hasCtrl()) {
                ctx.actions().refresh();
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        });
    }

    /**
     * Gates a maintenance/config action behind {@code advancedFeatures}; logs a nudge toward
     * {@code V} and returns false instead of running it when the flag is off.
     */
    private boolean requireAdvanced(String action) {
        if (ctx.state().advancedFeatures()) {
            return true;
        }
        ctx.state().addLog(LogLevel.INFO, action + " is an advanced feature — press V to enable it");
        return false;
    }

    /**
     * Persists the backend {@code switchBackendModal} resolved to {@code tambo.properties}.
     * TamboUI picks its backend once at process startup, so this can't take effect on the
     * running session — the log message says as much.
     */
    private void applyBackendChoice(String backend) {
        if (backend.equals(config.backend())) {
            ctx.state().addLog(LogLevel.INFO, "UI backend already set to " + backend);
            return;
        }
        config.setBackend(backend);
        ctx.state().addLog(LogLevel.INFO, "UI backend set to " + backend + " — restart tambo for this to take effect");
    }

    /** The user-level mise config file, honoring {@code MISE_CONFIG_DIR} when set. */
    private static Path globalConfigPath() {
        String configDir = System.getenv("MISE_CONFIG_DIR");
        return configDir != null && !configDir.isBlank()
                ? Path.of(configDir, "config.toml")
                : Path.of(System.getProperty("user.home"), ".config", "mise", "config.toml");
    }

    /**
     * The entries {@code AdvancedPanel} renders as a selectable menu. Each mirrors a global
     * letter shortcut (T/E/D/U/X/B/P) that keeps working on its own too — this just gives the
     * same actions a discoverable, directly actionable home instead of requiring the letter to
     * be remembered. {@code x}/{@code R} (uninstall / remove from config) are intentionally
     * absent: they act on whatever is selected in the Tools panel, which has no meaning here.
     */
    public List<AdvancedPanel.Action> buildAdvancedActions() {
        List<AdvancedPanel.Action> menu = new ArrayList<>();
        if (ctx.state().vfox()) {
            menu.add(new AdvancedPanel.Action("P", "Add plugin (--alias/--source)",
                    "Registers a plugin from a specific alias or source URL instead of the catalog.",
                    () -> {
                        if (ctx.state().offline()) {
                            ctx.state().addLog(LogLevel.INFO, "Offline mode — Add plugin needs network access");
                        } else {
                            ui.addPluginModal().open();
                        }
                    }));
            menu.add(new AdvancedPanel.Action("U", "vfox upgrade",
                    "Updates vfox itself to the latest release.", ctx.actions()::selfUpdate));
        } else {
            menu.add(new AdvancedPanel.Action("T", "Trust project config",
                    "Marks this project's mise.toml as trusted so its tasks/env can run.",
                    ctx.actions()::trustProject));
            menu.add(new AdvancedPanel.Action("E", "Edit global config",
                    "Opens the user-level mise config.toml in the built-in editor.",
                    () -> ui.configEditor().open(globalConfigPath(), "global config.toml")));
            menu.add(new AdvancedPanel.Action("D", "mise doctor",
                    "Re-runs mise's own health check and refreshes the Status panel.",
                    ctx.actions()::runDoctor));
            if (!ctx.state().selfUpdateDisabled()) {
                menu.add(new AdvancedPanel.Action("U", "mise self-update",
                        "Updates the mise binary itself to the latest release.", ctx.actions()::selfUpdate));
            }
            menu.add(new AdvancedPanel.Action("X", "Prune old versions",
                    "Removes tool versions mise no longer thinks are in use. Asks to confirm first.",
                    () -> ctx.confirm("Prune unused/old tool versions?", ctx.actions()::prune)));
        }
        menu.add(new AdvancedPanel.Action("B", "Switch UI backend (now: " + config.backend() + ")",
                "Opens a picker for jline3 (recommended, portable), panama (native, faster, "
                        + "riskier on native-image resize and Windows Git Bash/MinTTY), or aesh "
                        + "(pure-Java, experimental here).",
                () -> ui.switchBackendModal().open(this::applyBackendChoice)));
        return menu;
    }
}
