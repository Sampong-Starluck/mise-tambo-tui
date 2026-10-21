package com.sampong.tambo.tui.keys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo.tui.TuiComponents;
import com.sampong.tambo.tui.commands.Command;
import com.sampong.tambo.tui.commands.Command.Group;
import com.sampong.tambo.tui.features.TamboConfig;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Every action the app offers, as {@link Command}s — the single list the Ctrl+P palette, the
 * Space menu, and the beginner menu bar all read. It replaces the Advanced panel and the
 * {@code V}-gated maintenance letters: instead of hiding the less common actions behind a toggle,
 * each is listed once with its key, so the palette teaches the key and the Space menu makes the
 * key unnecessary to remember.
 * <p>
 * Built fresh on every open rather than once: titles carry live numbers ("Upgrade all outdated
 * (3)", "Beginner mode: on"), and a backend's missing features simply leave their commands out
 * rather than listing something that would only explain it cannot run.
 */
@RequiredArgsConstructor
public final class CommandCatalog {

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final TuiComponents ui;
    @NonNull
    private final TamboConfig config;
    @NonNull
    private final GlobalKeyBindings keys;

    public List<Command> all() {
        String name = ctx.backend().name();
        String configFile = ctx.backend().projectConfigFileName();
        List<Command> list = new ArrayList<>();

        // ----- Tools: the selection in the Tools panel -----
        list.add(cmd("tools.add", "Add an SDK…", Group.TOOLS, "a", null, false, keys::openAddSdk));
        list.add(cmd("tools.install", "Install the selected version", Group.TOOLS, "i", null, false,
                ui.toolsPanel()::installSelected));
        list.add(cmd("tools.use", "Use the selected version in " + configFile, Group.TOOLS, "u", null, false,
                () -> ui.toolsPanel().useSelected(false)));
        if (ctx.supports(BackendFeature.GLOBAL_SCOPE)) {
            list.add(cmd("tools.global", "Set the selected version as global", Group.TOOLS, "g", null, false,
                    () -> ui.toolsPanel().useSelected(true)));
        }
        if (ctx.supports(BackendFeature.UPGRADE)) {
            int marked = ctx.state().marked().size();
            list.add(cmd("tools.upgrade", marked > 0 ? "Upgrade marked tools (" + marked + ")" : "Upgrade the selected tool",
                    Group.TOOLS, "U", null, true, ui.toolsPanel()::upgradeSelected));
            list.add(cmd("tools.markOutdated", "Mark all outdated tools", Group.TOOLS, "Ctrl+A", null, false,
                    ui.toolsPanel()::markAllOutdated));
        }
        list.add(cmd("tools.uninstall", "Uninstall the selected or marked tools…", Group.TOOLS, "x", null, true,
                ui.toolsPanel()::uninstallSelected));
        if (ctx.supports(BackendFeature.PLUGIN_REGISTRY)) {
            list.add(cmd("tools.plugin", "Add a plugin from the catalog…", Group.TOOLS, "p", null, false,
                    keys::openAddPlugin));
        }
        list.add(cmd("tools.cancelAll", "Cancel everything running", Group.TOOLS, "C", null, false,
                ctx.actions()::cancelAll));

        // ----- Project -----
        if (ctx.supports(BackendFeature.TRUST)) {
            list.add(cmd("project.trust", "Trust " + configFile, Group.PROJECT, "Space t", 't', false,
                    ctx.actions()::trustProject));
        }
        if (ctx.supports(BackendFeature.INSTALL_ALL)) {
            list.add(cmd("project.installAll", "Install everything in config", Group.PROJECT, "Space i", 'i', false,
                    ctx.actions()::installAll));
        }
        list.add(cmd("project.edit", "Edit " + configFile, Group.PROJECT, "e", 'e', false, keys::openProjectConfig));
        if (ctx.supports(BackendFeature.GLOBAL_CONFIG)) {
            list.add(cmd("project.editGlobal", "Edit global config", Group.PROJECT, "Space E", 'E', false,
                    () -> ui.configEditor().open(globalConfigPath(), "global config.toml")));
        }
        list.add(cmd("project.refresh", "Refresh everything", Group.PROJECT, "r", null, false, ctx.actions()::refresh));

        // ----- Updates -----
        if (ctx.supports(BackendFeature.UPGRADE)) {
            int outdated = ctx.state().outdated().size();
            list.add(cmd("updates.upgradeAll", "Upgrade all outdated" + (outdated > 0 ? " (" + outdated + ")" : ""),
                    Group.UPDATES, "Space u", 'u', true, this::upgradeAll));
            list.add(cmd("updates.check", "Check for updates now", Group.UPDATES, "Space C", 'C', false,
                    ctx.actions()::checkForUpdates));
        }
        if (ctx.supports(BackendFeature.SELF_UPDATE) && !ctx.state().selfUpdateDisabled()) {
            list.add(cmd("updates.self", "Self-update " + name, Group.UPDATES, "Space s", 's', false,
                    ctx.actions()::selfUpdate));
        }

        // ----- Clean-up -----
        if (ctx.supports(BackendFeature.PRUNE)) {
            list.add(cmd("cleanup.prune", "Prune unused versions", Group.CLEANUP, "Space p", 'p', true,
                    () -> ctx.confirm("Prune unused/old tool versions?", ctx.actions()::prune)));
        }
        if (ctx.supports(BackendFeature.CACHE_CLEAR)) {
            list.add(cmd("cleanup.cache", "Clear download cache", Group.CLEANUP, "Space c", 'c', true,
                    () -> ctx.confirm("Clear " + name + "'s download cache?", ctx.actions()::clearCache)));
        }
        if (ctx.supports(BackendFeature.DOCTOR)) {
            list.add(cmd("cleanup.doctor", "Run " + name + " doctor", Group.CLEANUP, "Space d", 'd', false,
                    ctx.actions()::runDoctor));
        }
        list.add(cmd("cleanup.undo", "Undo the last config change", Group.CLEANUP, "z", null, false,
                ctx.actions()::undo));
        list.add(cmd("cleanup.redo", "Redo the config change just undone", Group.CLEANUP, "Z", null, false,
                ctx.actions()::redo));

        // ----- View -----
        list.add(cmd("view.jobs", "Jobs — installs, upgrades and their output", Group.VIEW, "w", null, false,
                keys::openJobs));
        list.add(cmd("view.command", "Type a " + name + " command", Group.VIEW, ":", null, false,
                () -> ui.commandLine().open("")));
        list.add(cmd("view.jump", "Jump to a panel", Group.VIEW, "Ctrl+O", null, false,
                () -> ctx.state().jumpMode(true)));
        list.add(cmd("view.help", "Help for the focused panel", Group.VIEW, "F1", null, false, keys::openHelp));
        list.add(cmd("view.outdatedOnly", (ctx.state().outdatedOnly() ? "Show all tools" : "Show only outdated tools"),
                Group.VIEW, "o", null, false, () -> ctx.state().outdatedOnly(!ctx.state().outdatedOnly())));
        list.add(cmd("view.fullscreen", "Full-screen tools table", Group.VIEW, "+", null, false,
                () -> ctx.state().toolsFullScreen(!ctx.state().toolsFullScreen())));
        list.add(cmd("view.log", "Command log", Group.VIEW, "5", null, false,
                () -> ctx.focus(PanelIds.LOG)));

        // ----- App -----
        list.add(cmd("app.beginner", ctx.state().beginnerMode() ? "Beginner mode off" : "Beginner mode (menus)",
                Group.APP, "Space m", 'm', false, keys::toggleBeginnerMode));
        list.add(cmd("app.activate", "Activate " + name + " in your shell", Group.APP, "Space a", 'a', false,
                ctx.actions()::activateShell));
        if (ctx.supports(BackendFeature.PLUGIN_REGISTRY)) {
            list.add(cmd("app.pluginRaw", "Add plugin by alias/source…", Group.APP, "Space P", 'P', false,
                    keys::openAddPlugin));
        }
        list.add(cmd("app.terminal", "Terminal backend (" + config.backend() + ")…", Group.APP, "Space B", 'B', false,
                keys::openTerminalBackendPicker));
        list.add(cmd("app.quit", "Quit", Group.APP, "q", null, false, keys::quit));
        return list;
    }

    private void upgradeAll() {
        if (ctx.state().offline()) {
            ctx.state().addLog(LogLevel.INFO, "Offline mode — can't check for outdated tools");
        } else if (ctx.state().outdated().isEmpty()) {
            ctx.state().addLog(LogLevel.INFO, ctx.state().outdatedLazy().everLoaded()
                    ? "All tools are up to date" : "Still checking for updates…");
        } else {
            ctx.confirm("Upgrade all " + ctx.state().outdated().size() + " outdated tool(s)?",
                    ctx.actions()::upgradeAll);
        }
    }

    /**
     * Only reached behind a {@link BackendFeature#GLOBAL_CONFIG} check, which is exactly when the
     * backend promises a path — a null here is a contract violation worth failing on.
     */
    private Path globalConfigPath() {
        return Objects.requireNonNull(ctx.backend().globalConfigPath(),
                () -> ctx.backend().name() + " declares GLOBAL_CONFIG but reports no path");
    }

    private static Command cmd(String id, String title, Group group, String key,
                               @Nullable Character leader, boolean asks, Runnable action) {
        return new Command(id, title, group, key, leader, asks, action);
    }
}
