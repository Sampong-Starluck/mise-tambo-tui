package com.sampong.tambo.tui.keys;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.EventRouter;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo.tui.TuiComponents;
import com.sampong.tambo.tui.commands.Command;
import com.sampong.tambo.tui.components.FKeyBar;
import com.sampong.tambo.tui.components.HelpOverlay;
import com.sampong.tambo.tui.components.MenuBar;
import com.sampong.tambo.tui.components.SidePanels;
import com.sampong.tambo.tui.features.TamboConfig;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * Everything about which key does what: the single global handler {@code MiseTuiApp} routes
 * every {@link KeyEvent} through, and the nav bindings (arrows + vim {@code j}/{@code k} + user
 * overrides) TamboUI itself consumes.
 * <p>
 * The keyboard surface is layered so nothing needs memorising up front. The few keys on the
 * footer cover the everyday; {@code Ctrl+P} lists every action with its key beside it (Posting,
 * Helix); {@code Space} pops a menu of the less common ones that names each second key as it is
 * needed (Helix's which-key); {@code :} takes a raw backend command for everything else (k9s);
 * and {@code Ctrl+O} labels the panels for a one-key jump (Posting). Which actions exist, and
 * under what names, is {@link CommandCatalog}'s business — this class only routes keys.
 * <p>
 * Owns no rendering of its own; {@code com.sampong.tambo.tui.layout.AppLayout} is the
 * counterpart that turns state into what's on screen.
 */
public final class GlobalKeyBindings {

    /** Jump-mode labels beyond the panel numbers, which always mean the same panel. */
    public static final char JUMP_VERSIONS = 'v';
    public static final char JUMP_JOBS = 'w';

    private final UiContext ctx;
    private final TuiComponents ui;
    private final TamboConfig config;
    private final CommandCatalog catalog;

    public GlobalKeyBindings(@NonNull UiContext ctx, @NonNull TuiComponents ui, @NonNull TamboConfig config) {
        this.ctx = ctx;
        this.ui = ui;
        this.config = config;
        this.catalog = new CommandCatalog(ctx, ui, config, this);
    }

    /** Every command, freshly titled — what the palette, Space menu and menu bar list. */
    public List<Command> commands() {
        return catalog.all();
    }

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

    /** Registers the single global key handler every keypress not claimed by a focused input goes through. */
    public void register(@NonNull EventRouter router) {
        router.addGlobalHandler(event -> {
            if (ui.helpOverlay().isOpen() && event instanceof MouseEvent mouse) {
                // The overlay covers the panels, so the wheel scrolls the reference itself
                // rather than whatever is underneath it, and a stray click cannot refocus a
                // panel the user cannot even see.
                ui.helpOverlay().handleMouse(mouse);
                return EventResult.HANDLED;
            }
            if (event instanceof MouseEvent mouse) {
                return handleMouse(mouse);
            }
            if (!(event instanceof KeyEvent key)) {
                return EventResult.UNHANDLED;
            }
            return handle(key);
        });
    }

    /** Routes one keypress that no focused element claimed. Public so tests can drive it headlessly. */
    public EventResult handle(@NonNull KeyEvent key) {
        // ----- Things that own the keyboard outright while they are up -----
        if (ui.selectBackendModal().isOpen()) {
            // Nothing else is reachable until the first-run choice is made.
            ui.selectBackendModal().handleKey(key);
            return EventResult.HANDLED;
        }
        if (ui.helpOverlay().isOpen()) {
            ui.helpOverlay().handleKey(key);
            return EventResult.HANDLED;
        }
        if (ui.confirmModal().isOpen()) {
            ui.confirmModal().handleKey(key);
            return EventResult.HANDLED;
        }
        if (ui.batchConfirm().isOpen()) {
            ui.batchConfirm().handleKey(key);
            return EventResult.HANDLED;
        }
        if (ui.leaderMenu().isOpen()) {
            ui.leaderMenu().handleKey(key);
            return EventResult.HANDLED;
        }
        if (ui.menuBar().isOpen()) {
            ui.menuBar().handleKey(key);
            return EventResult.HANDLED;
        }
        if (ui.switchBackendModal().isOpen()) {
            ui.switchBackendModal().handleKey(key);
            return EventResult.HANDLED;
        }
        if (ui.registryModal().ownsKeyboard()) {
            // The Add SDK wizard's Where and Confirm steps have no input box to receive keys.
            ui.registryModal().handleKey(key);
            return EventResult.HANDLED;
        }
        if (ui.palette().isOpen() || ui.commandLine().isOpen() || ui.registryModal().isOpen()
                || ui.configEditor().isOpen() || ui.taskArgsModal().isOpen() || ui.addPluginModal().isOpen()) {
            // A focused input box consumes everything it needs; never let panel shortcuts fire
            // underneath it.
            return EventResult.UNHANDLED;
        }
        if (ctx.state().jumpMode()) {
            jump(key);
            return EventResult.HANDLED;
        }
        if (ui.jobsView().isOpen() && ui.jobsView().handleKey(key)) {
            return EventResult.HANDLED;
        }
        if (ctx.state().narrowDetail() && key.isCancel()) {
            ctx.state().narrowDetail(false);
            return EventResult.HANDLED;
        }

        // ----- Ways to find an action -----
        if (key.hasAlt() && key.code() == KeyCode.CHAR) {
            MenuBar.Menu menu = MenuBar.menuForMnemonic(key.codePoint());
            if (menu != null) {
                openMenu(menu);
                return EventResult.HANDLED;
            }
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('p')) {
            ui.palette().open(commands());
            return EventResult.HANDLED;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('o')) {
            ctx.state().jumpMode(true);
            return EventResult.HANDLED;
        }
        if (key.hasCtrl()) {
            return EventResult.UNHANDLED;
        }
        if (key.isChar(' ')) {
            ui.leaderMenu().open(commands());
            return EventResult.HANDLED;
        }
        if (key.isChar(':')) {
            ui.commandLine().open("");
            return EventResult.HANDLED;
        }
        if (key.isChar('?')) {
            openHelp();
            return EventResult.HANDLED;
        }
        String fkey = FKeyBar.actionFor(key.code());
        if (fkey != null) {
            runFKey(fkey);
            return EventResult.HANDLED;
        }

        // ----- Versions column: Enter drills into the detail on a terminal too narrow to show both -----
        if (ui.sidePanels().versionsFocused() && key.isConfirm()) {
            ctx.state().narrowDetail(true);
            return EventResult.HANDLED;
        }

        // ----- Everyday keys -----
        if (key.isChar('w')) {
            openJobs();
            return EventResult.HANDLED;
        }
        if (key.isChar('z')) {
            ctx.actions().undo();
            return EventResult.HANDLED;
        }
        if (key.isChar('Z')) {
            ctx.actions().redo();
            return EventResult.HANDLED;
        }
        if (key.isChar('a')) {
            openAddSdk();
            return EventResult.HANDLED;
        }
        if (key.isChar('A')) {
            ctx.actions().activateShell();
            return EventResult.HANDLED;
        }
        if (key.isChar('e')) {
            openProjectConfig();
            return EventResult.HANDLED;
        }
        if (key.isChar('p') && ctx.supports(BackendFeature.PLUGIN_REGISTRY)) {
            openAddPlugin();
            return EventResult.HANDLED;
        }
        if (key.isChar('C')) {
            // Panel-local 'c' only cancels what the cursor is on; this always stops everything,
            // however the user has moved around since.
            ctx.actions().cancelAll();
            return EventResult.HANDLED;
        }
        if (key.isChar('5')) {
            // The command log is not in the stack — it sits under the main pane — so it is the
            // one number key that does not go through SidePanels.
            ctx.focus(PanelIds.LOG);
            return EventResult.HANDLED;
        }
        SidePanels.Side jump = sideFor(key);
        if (jump != null) {
            focusSide(jump);
            return EventResult.HANDLED;
        }
        if (key.code() == KeyCode.TAB) {
            // lazygit's panel walk: Tab moves to the next panel in the stack, shift+Tab back.
            ui.sidePanels().cycle(key.hasShift() ? -1 : 1);
            return EventResult.HANDLED;
        }
        if (key.isChar('r')) {
            ctx.actions().refresh();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    /**
     * Resolves the key typed in jump mode, then leaves it. Numbers mean what they always mean;
     * letters are handed out to whatever has no number. Anything else just cancels, so a
     * mistyped label costs one keypress rather than an action.
     */
    private void jump(KeyEvent key) {
        ctx.state().jumpMode(false);
        if (key.isChar(JUMP_VERSIONS)) {
            ctx.focus(PanelIds.VERSIONS);
        } else if (key.isChar(JUMP_JOBS)) {
            openJobs();
        } else if (key.isChar('5')) {
            ctx.focus(PanelIds.LOG);
        } else {
            SidePanels.Side side = sideFor(key);
            if (side != null) {
                focusSide(side);
            }
        }
    }

    private void focusSide(SidePanels.Side side) {
        if (ui.sidePanels().available(side)) {
            ui.sidePanels().focus(side);
        } else {
            // The panel is in the stack but this backend cannot serve it, so there is nothing to
            // focus. Say why instead of letting the key look broken.
            ctx.state().addLog(LogLevel.INFO, ui.sidePanels().unsupportedReason(side));
        }
    }

    /** The beginner-mode F-key bar, which also works outside beginner mode for anyone who finds it. */
    private void runFKey(String action) {
        switch (action) {
            case "help" -> openHelp();
            case "versions" -> ctx.focus(PanelIds.VERSIONS);
            case "install" -> ui.toolsPanel().installSelected();
            case "use" -> ui.toolsPanel().useSelected(false);
            case "global" -> ui.toolsPanel().useSelected(true);
            case "upgrade" -> ui.toolsPanel().upgradeSelected();
            case "add" -> openAddSdk();
            case "remove" -> ui.toolsPanel().uninstallSelected();
            case "menu" -> openMenu(MenuBar.Menu.TOOLS);
            case "quit" -> quit();
            default -> { }
        }
    }

    /**
     * The stack panel a number key jumps to, or null when the key is not one. Read off
     * {@link SidePanels.Side#key()} so the keys, the panel order and the numbers printed in the
     * panel titles can only ever come from one place.
     */
    private SidePanels.@Nullable Side sideFor(KeyEvent key) {
        for (SidePanels.Side side : SidePanels.Side.values()) {
            if (key.isChar(side.key())) {
                return side;
            }
        }
        return null;
    }

    /**
     * Clicks on the beginner-mode menu bar and its dropdown. Everything else about the mouse —
     * clicking a panel to focus it, the wheel — is the elements' own business, so only a click
     * the bar claims is handled here.
     */
    public EventResult handleMouse(@NonNull MouseEvent mouse) {
        boolean barShowing = ctx.state().beginnerMode() && (ctx.noModalOpen() || ui.menuBar().isOpen());
        if (!barShowing || !(mouse.isClick() || ui.menuBar().isOpen())) {
            return EventResult.UNHANDLED;
        }
        return ui.menuBar().handleClick(mouse, commands()) ? EventResult.HANDLED : EventResult.UNHANDLED;
    }

    /**
     * F9 or Alt+letter. Outside beginner mode it switches beginner mode on first, so the dropdown
     * always hangs from a visible bar rather than from nothing at the top of the screen.
     */
    private void openMenu(MenuBar.Menu menu) {
        if (!ctx.state().beginnerMode()) {
            toggleBeginnerMode();
        }
        ui.menuBar().open(commands(), menu);
    }

    // ==================== Actions the catalog shares ====================

    /** F1 / ?: help about whatever has focus, not a wall of every key in the app. */
    public void openHelp() {
        ui.helpOverlay().open(helpTopic());
    }

    private HelpOverlay.Topic helpTopic() {
        if (ui.jobsView().isOpen()) {
            return HelpOverlay.Topic.JOBS;
        }
        if (ui.sidePanels().versionsFocused()) {
            return HelpOverlay.Topic.VERSIONS;
        }
        if (ui.sidePanels().logFocused()) {
            return HelpOverlay.Topic.LOG;
        }
        return switch (ui.sidePanels().focused()) {
            case STATUS -> HelpOverlay.Topic.STATUS;
            case TOOLS -> HelpOverlay.Topic.TOOLS;
            case ENV -> HelpOverlay.Topic.ENV;
            case TASKS -> HelpOverlay.Topic.TASKS;
        };
    }

    public void openJobs() {
        if (ui.jobsView().isOpen()) {
            ui.jobsView().close();
        } else {
            ui.jobsView().open();
        }
    }

    public void openAddSdk() {
        if (ctx.state().offline()) {
            ctx.state().addLog(LogLevel.INFO, "Offline mode — Add SDK needs network access");
        } else {
            ui.registryModal().open();
        }
    }

    public void openAddPlugin() {
        if (ctx.state().offline()) {
            ctx.state().addLog(LogLevel.INFO, "Offline mode — Add plugin needs network access");
        } else {
            ui.addPluginModal().open();
        }
    }

    public void openProjectConfig() {
        String file = ctx.backend().projectConfigFileName();
        ui.configEditor().open(Path.of(file), "./" + file);
    }

    public void openTerminalBackendPicker() {
        ui.switchBackendModal().open(this::applyBackendChoice);
    }

    public void toggleBeginnerMode() {
        boolean on = !ctx.state().beginnerMode();
        ctx.state().beginnerMode(on);
        ctx.state().addLog(LogLevel.INFO, on
                ? "Beginner mode on — F9 opens the menus, Space m turns it off"
                : "Beginner mode off");
    }

    public void quit() {
        ctx.quit();
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
}
