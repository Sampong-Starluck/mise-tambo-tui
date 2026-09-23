package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo.tui.state.UiContext;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The {@code ?} key reference: every key the app binds, grouped by where it applies, plus the
 * launch flags and config notes that don't belong to any one panel. Remembers and restores
 * focus around itself.
 * <p>
 * The full reference is far longer than any terminal, so this scrolls: ↑/↓ or j/k by a line,
 * PgUp/PgDn by a screen, Home/End to the ends, and the mouse wheel. The window is sized from
 * the live terminal height and the dialog from its width, so the text is never cut off at
 * either edge — long descriptions are wrapped by hand with {@link Ui#wordWrap} for the same
 * reason that helper exists at all (see its javadoc). Like {@link ConfirmModal} it has no
 * focusable element of its own, so {@code GlobalKeyBindings} feeds it keys and wheel events.
 */
@RequiredArgsConstructor
public final class HelpOverlay {

    /** Width of the bolded key column, wide enough for the longest label ({@code Tab / Shift+Tab}). */
    private static final int KEY_WIDTH = 17;
    /**
     * The launch-flag section gets its own, wider column: {@code --advanced-features} is longer
     * than {@link #KEY_WIDTH} and would otherwise be clipped by its own length constraint.
     */
    private static final int FLAG_WIDTH = 21;
    /** Border + padding the dialog spends on each side, deducted before wrapping descriptions. */
    private static final int DIALOG_CHROME_COLUMNS = 4;
    /** Rows the dialog spends on its own border, the sticky footer, and the app's header/footer. */
    private static final int CHROME_ROWS = 9;
    private static final int MIN_ROWS = 6;
    private static final int MIN_WIDTH = 46;
    private static final int MAX_WIDTH = 84;
    private static final int MIN_DESC_WIDTH = 20;
    /** Used when the terminal won't report its size — a conservative 80x30. */
    private static final int FALLBACK_WIDTH = 80;
    private static final int FALLBACK_HEIGHT = 30;
    /** Rows scrolled per mouse wheel tick, matching {@code LogPanel}. */
    private static final int WHEEL_STEP = 3;

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final IntSupplier terminalHeight;
    @NonNull
    private final IntSupplier terminalWidth;

    @Getter
    private boolean open;
    private @Nullable String preOpenFocus;

    /**
     * First visible line, and the bounds the key handler clamps it against. Both are recomputed
     * in {@link #build()} — which always runs before the next keypress is read — so the handler
     * can clamp against the layout actually on screen rather than guessing at it.
     */
    private int scroll;
    private int maxScroll;
    private int pageRows = MIN_ROWS;

    public void open() {
        preOpenFocus = ctx.focusedId();
        ctx.clearFocus();
        scroll = 0;
        open = true;
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    // ==================== Rendering ====================

    public Element build() {
        int width = dialogWidth();
        List<Element> lines = document(width);
        int rows = visibleRows();

        maxScroll = Math.max(0, lines.size() - rows);
        scroll = Math.clamp(scroll, 0, maxScroll);
        // Leave one line of overlap between screens so the eye has an anchor when paging.
        pageRows = Math.max(1, rows - 1);

        int from = scroll;
        int to = Math.min(lines.size(), scroll + rows);
        List<Element> body = new ArrayList<>(lines.subList(from, to));
        body.add(text(""));
        // The full hint plus the position counter needs roughly 60 columns; below that the
        // counter is what earns its place, so the hint drops to the two keys worth knowing.
        body.add(row(
                text(width >= 64 ? "↑/↓ scroll  PgUp/PgDn  Home/End  ? Esc q close" : "↑/↓ scroll  Esc close").dim(),
                spacer(),
                text((from + 1) + "-" + to + " of " + lines.size()).dim()
        ));

        return dialog("Help", body.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(width);
    }

    /** Dialog width: as wide as the terminal allows, within readable bounds. */
    private int dialogWidth() {
        int reported = terminalWidth.getAsInt();
        int usable = reported == Integer.MAX_VALUE ? FALLBACK_WIDTH : reported;
        return Math.clamp(usable - 4, MIN_WIDTH, MAX_WIDTH);
    }

    /** How many lines of the reference fit on screen at once. */
    private int visibleRows() {
        int reported = terminalHeight.getAsInt();
        int usable = reported == Integer.MAX_VALUE ? FALLBACK_HEIGHT : reported;
        return Math.max(MIN_ROWS, usable - CHROME_ROWS);
    }

    // ==================== Key / mouse handling ====================

    /** Called by {@code GlobalKeyBindings} for every key while the overlay is up. */
    public void handleKey(@NonNull KeyEvent key) {
        if (key.isCancel() || key.isConfirm() || key.isChar('?') || key.isChar('q')) {
            close();
            return;
        }
        if (key.code() == KeyCode.UP || key.isChar('k')) {
            scrollBy(-1);
        } else if (key.code() == KeyCode.DOWN || key.isChar('j')) {
            scrollBy(1);
        } else if (key.code() == KeyCode.PAGE_UP) {
            scrollBy(-pageRows);
        } else if (key.code() == KeyCode.PAGE_DOWN) {
            scrollBy(pageRows);
        } else if (key.code() == KeyCode.HOME) {
            scroll = 0;
        } else if (key.code() == KeyCode.END) {
            scroll = maxScroll;
        }
    }

    /** Called by {@code GlobalKeyBindings} for wheel events while the overlay is up. */
    public void handleMouse(@NonNull MouseEvent event) {
        if (event.kind() == MouseEventKind.SCROLL_UP) {
            scrollBy(-WHEEL_STEP);
        } else if (event.kind() == MouseEventKind.SCROLL_DOWN) {
            scrollBy(WHEEL_STEP);
        }
    }

    private void scrollBy(int delta) {
        scroll = Math.clamp(scroll + delta, 0, maxScroll);
    }

    // ==================== Content ====================

    /**
     * The whole reference as flat lines, ready to be windowed. Rebuilt each frame so it tracks
     * the live state it mentions — the active backend, whether self-update is available, and
     * which features the backend actually has.
     */
    private List<Element> document(int width) {
        Doc doc = new Doc(width);
        doc.title("tambo — a lazygit-style TUI for " + ctx.backend().name());
        // Each section asks for the capability it documents rather than for a backend name, so
        // the reference describes the session the user is actually in.
        addLayout(doc);
        addNavigation(doc);
        addToolsPanel(doc);
        addOtherPanels(doc);
        addGlobalKeys(doc);
        addAdvancedActions(doc);
        addModals(doc);
        addLaunchFlags(doc);
        addConfig(doc);
        return doc.lines();
    }

    /** How the screen is laid out, and the keys that move between panels. */
    private void addLayout(Doc doc) {
        doc.section("LAYOUT");
        doc.note("A stack of numbered panels down the left, all on screen at once, and one big "
                + "pane on the right showing everything about whatever is selected in the "
                + "focused panel. The command log sits under that pane. The focused panel has "
                + "the bright border and takes the spare room; the others shrink but stay "
                + "visible, so an install running in Tools is still in sight while you read "
                + "Tasks. A panel this backend cannot serve keeps its slot, marked (n/a), and "
                + "explains itself in the main pane when you press its number.");
        doc.key("1-4", "Focus a panel: 1 Status, 2 Tools, 3 Env, 4 Tasks");
        doc.key("5", "Focus the command log, under the main pane");
        doc.key("6", "[advanced] Focus the Advanced panel");
        doc.key("Tab / Shift+Tab", "Move to the next / previous panel");
    }

    private void addNavigation(Doc doc) {
        boolean tasks = ctx.supports(BackendFeature.TASKS);
        boolean env = ctx.supports(BackendFeature.ENV);
        doc.section("NAVIGATION");
        doc.key("Up/Down, j/k", "Move the selection / scroll one line");
        doc.key("PgUp / PgDn", "Page the focused list by ten rows");
        doc.key("Home / End", "Jump to the first / last entry");
        doc.key("/", !(env && tasks)
                ? "Filter the focused list; Esc clears the filter"
                : "Filter the focused list (Tools, Env, Tasks); Esc clears the filter");
        doc.key("Mouse", "Click to focus, wheel to scroll");
    }

    private void addToolsPanel(Doc doc) {
        String configFile = ctx.backend().projectConfigFileName();
        boolean upgrade = ctx.supports(BackendFeature.UPGRADE);
        boolean plugins = ctx.supports(BackendFeature.PLUGIN_REGISTRY);
        boolean pins = ctx.supports(BackendFeature.PIN_ON_INSTALL);
        doc.section("TOOLS PANEL (2)");
        doc.key("i", !pins
                ? "Install the selected tool — with no version installed yet, applies the version "
                        + configFile + " declares, or opens the version picker if it declares none"
                : "Install the selected tool");
        doc.key("u", "Apply the selected tool to the project " + configFile);
        doc.key("g", "Install and set as the global default");
        if (upgrade) {
            doc.key("p", "Upgrade the selected tool to the newest version");
        }
        doc.key("x or Delete", "[advanced] Uninstall the selected version (asks to confirm)");
        doc.key("R", "[advanced] Remove the selected tool from " + configFile + " (asks to confirm)");
        doc.key("d", plugins
                ? "[advanced] Remove the selected tool's plugin AND all its versions (asks to confirm)"
                : "[advanced] Remove the selected tool's plugin, keeping installed versions (asks to confirm)");
        doc.key("c", "Cancel whatever the selected tool is doing");
    }

    /** Env and Tasks (where the backend has them), the command log, and the Advanced panel. */
    private void addOtherPanels(Doc doc) {
        String tool = ctx.backend().name();
        boolean env = ctx.supports(BackendFeature.ENV);
        if (env) {
            doc.section("ENV PANEL (3)");
            doc.key("y", "Copy the selected variable's value to the clipboard");

            doc.section("TASKS PANEL (4)");
            doc.key("Enter", "Run the selected task");
            doc.key(":", "Run the selected task with arguments");
            doc.key(".", "Re-run the last task — works even with nothing selected");
            doc.key("c", "Cancel the selected task");
        }

        doc.section("COMMAND LOG (5)");
        doc.note("Every " + tool + " command this app runs, echoed the way lazygit echoes git.");
        doc.key("Up/Down, j/k", "Scroll; PgUp/PgDn pages, Home jumps to the oldest entry");
        doc.key("End", "Resume following the newest entry");
        doc.note("Long lines wrap to the panel's width, so nothing is cut off at the edge.");

        doc.section("ADVANCED PANEL (6)");
        doc.note("Only in the stack once V is on. The highlighted entry is explained in full in the main pane before you run it.");
        doc.key("Up/Down + Enter", "Run the highlighted action");
        doc.key("V", "Hide the panel again");
    }

    /** Keys that work from any panel. */
    private void addGlobalKeys(Doc doc) {
        String tool = ctx.backend().name();
        String configFile = ctx.backend().projectConfigFileName();
        boolean upgrade = ctx.supports(BackendFeature.UPGRADE);
        boolean plugins = ctx.supports(BackendFeature.PLUGIN_REGISTRY);
        doc.section("ANYWHERE");
        doc.key("a", plugins
                ? "Add SDK — install another version of a plugin you already have"
                : "Add SDK — fuzzy-find the mise registry");
        if (plugins) {
            doc.key("p", "Add plugin — fuzzy-find the " + tool + " catalog and register it");
        }
        if (upgrade) {
            doc.key("P", "Upgrade every outdated tool (asks to confirm)");
        }
        doc.key("I", "Auto-install — read " + configFile + " and make the project match it. "
                + "Tools it declares that are already installed are pinned; ones that are "
                + "missing are installed. Where a version is installed that differs from the "
                + "config only within the same major release (25.0.3 on disk, 25.0.4 in the "
                + "config), it asks per tool whether to keep what you have or download what the "
                + "config says. Offline, nothing is downloaded: for each tool whose config "
                + "version is missing it offers the versions already installed, closest first, "
                + "and pins the one you pick.");
        doc.key("e", "Edit the project " + configFile + " in-app");
        doc.key("A", "Activate " + tool + " in your shell profile — detects PowerShell, bash, "
                + "zsh, fish and Nushell");
        doc.key("V", "Toggle advanced features, and with them the Advanced panel");
        doc.key("C", "Cancel every running operation, from any panel");
        doc.key("r", "Refresh");
        doc.key("?", "Toggle this help");
        doc.key("q", "Quit");
    }

    private void addAdvancedActions(Doc doc) {
        String tool = ctx.backend().name();
        String configFile = ctx.backend().projectConfigFileName();
        boolean plugins = ctx.supports(BackendFeature.PLUGIN_REGISTRY);
        boolean globalConfig = ctx.supports(BackendFeature.GLOBAL_CONFIG);
        boolean trust = ctx.supports(BackendFeature.TRUST);
        boolean doctor = ctx.supports(BackendFeature.DOCTOR);
        boolean prune = ctx.supports(BackendFeature.PRUNE);
        doc.section("ADVANCED ACTIONS — press V first");
        if (plugins) {
            doc.key("P", "Add a plugin by name, with explicit --alias / --source");
        }
        if (trust) {
            doc.key("T", "Trust this project's " + configFile + " (" + tool + " trust)");
        }
        if (globalConfig) {
            doc.key("E", "Edit the global " + tool + " config.toml in-app");
        }
        if (doctor) {
            doc.key("D", "Run " + tool + " doctor — full report in the command log");
        }
        doc.key("U", ctx.state().selfUpdateDisabled()
                ? tool + " self-update — unavailable in this install, update " + tool
                        + " via your package manager"
                : tool + " self-update — update the " + tool + " binary itself");
        if (prune) {
            doc.key("X", "Prune unused/old tool versions (asks to confirm)");
        }
        doc.key("B", "Switch the UI backend between jline3, panama and aesh (now: "
                + ctx.uiBackend() + ") — takes effect on restart");
    }

    private void addModals(Doc doc) {
        String tool = ctx.backend().name();
        String configFile = ctx.backend().projectConfigFileName();
        boolean tasks = ctx.supports(BackendFeature.TASKS);
        boolean plugins = ctx.supports(BackendFeature.PLUGIN_REGISTRY);
        boolean pins = ctx.supports(BackendFeature.PIN_ON_INSTALL);
        doc.section("MODALS");
        doc.note("Add SDK (a): type to fuzzy find, Up/Down and PgUp/PgDn move, Enter chooses. "
                + (pins
                ? "Pick the SDK, then the version. Ctrl+G toggles between this directory and global."
                : "Pick the plugin, then the version — Enter only installs it, pin it afterwards with u or g.")
                + " Esc steps back a stage, then closes.");
        if (plugins) {
            doc.note("Add plugin (p): type to fuzzy find the catalog, Enter registers the "
                    + "highlighted plugin. [advanced] Typing a full \"<name> --alias <x> --source <url>\" "
                    + "is submitted verbatim instead.");
        }
        doc.note("Config editor (e, E): Ctrl+S saves and refreshes so " + tool + " picks the "
                + "change up; Esc closes, asking once first if there are unsaved changes.");
        doc.note("Auto-install questions (I): k keeps the version already installed and pins it "
                + "to this project, d downloads the one " + configFile + " asks for, s skips that "
                + "tool. One question per tool, then everything runs at once; Esc abandons the "
                + "whole run.");
        doc.note("Confirmations: y or Enter to go ahead, n or Esc to back out.");
        doc.note("Backend picker (B): Up/Down then Enter applies and persists the choice; Esc cancels.");
        if (tasks) {
            doc.note("Task arguments (:): Enter runs the task with what you typed, Esc cancels.");
        }
    }

    private void addLaunchFlags(Doc doc) {
        String configFile = ctx.backend().projectConfigFileName();
        boolean upgrade = ctx.supports(BackendFeature.UPGRADE);
        doc.section("LAUNCH FLAGS");
        doc.flag("--backend", "Force mise or vfox instead of detecting it from "
                + configFile + " in the current directory");
        doc.flag("--offline", "Installed tools only — blocks install, use, "
                + (upgrade ? "upgrade, self-update" : "self-update") + " and Add SDK, all of which need the network");
        doc.flag("--advanced-features", "Start with the [advanced] keys already unlocked");
        doc.flag("--auto-install", "Run the I flow once the session is up, instead of waiting "
                + "for the key");
        doc.flag("--mouse", "Mouse capture — already on by default in tambo");
        doc.flag("--[no-]alt-screen", "Render on the alternate screen (default) or inline");
        doc.flag("--show-cursor", "Leave the terminal cursor visible");
        doc.flag("--tick-rate", "Animation tick in milliseconds; 0 disables animation");
        doc.flag("--poll-timeout", "Event poll timeout in milliseconds");
    }

    private void addConfig(Doc doc) {
        boolean globalConfig = ctx.supports(BackendFeature.GLOBAL_CONFIG);
        doc.section("CONFIG");
        doc.note("~/.config/tambo/tambo.properties holds theme.* colours and keys.* navigation "
                + "overrides. $TAMBO_CONFIG_DIR overrides that path.");
        if (globalConfig) {
            doc.note("$MISE_CONFIG_DIR, when set, is where E looks for the global config.toml.");
        }
    }

    /**
     * Accumulates the reference as flat lines. Everything is wrapped as it is added, so the
     * caller never has to think about the dialog's width and the windowing above can treat the
     * result as a plain list of rows.
     */
    private static final class Doc {

        private final List<Element> lines = new ArrayList<>();
        private final int descWidth;
        private final int flagDescWidth;
        private final int noteWidth;

        Doc(int width) {
            this.descWidth = Math.max(MIN_DESC_WIDTH, width - KEY_WIDTH - DIALOG_CHROME_COLUMNS);
            this.flagDescWidth = Math.max(MIN_DESC_WIDTH, width - FLAG_WIDTH - DIALOG_CHROME_COLUMNS);
            this.noteWidth = Math.max(MIN_DESC_WIDTH, width - DIALOG_CHROME_COLUMNS);
        }

        void title(String text) {
            lines.add(text(text).bold());
        }

        void section(String heading) {
            lines.add(text(""));
            lines.add(text(heading).bold().fg(Color.CYAN));
        }

        /** A key and what it does, the description wrapped under itself with the key column blank. */
        void key(String key, String description) {
            entry(key, description, KEY_WIDTH, descWidth);
        }

        /** A launch flag and what it does, in the wider {@link #FLAG_WIDTH} column. */
        void flag(String flag, String description) {
            entry(flag, description, FLAG_WIDTH, flagDescWidth);
        }

        private void entry(String label, String description, int labelWidth, int wrapWidth) {
            List<String> wrapped = Ui.wordWrap(description, wrapWidth);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(row(
                        text(i == 0 ? label : "").bold().yellow().constraint(length(labelWidth)),
                        text(wrapped.get(i))
                ));
            }
        }

        /** Prose that belongs to a section rather than to one key. */
        void note(String text) {
            for (String line : Ui.wordWrap(text, noteWidth)) {
                lines.add(text(line).dim());
            }
        }

        List<Element> lines() {
            return lines;
        }
    }
}
