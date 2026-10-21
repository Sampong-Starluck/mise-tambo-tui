package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.TextElement;
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
 * The F1 reference, opened on the panel the user was in. The first section is the keys that
 * panel answers to; the rest is the same for every panel — the keys that work anywhere, what the
 * status symbols mean, and the launch flags and config notes that belong to no one panel. Tab
 * and Shift+Tab step to another panel's reference, and {@code /} searches the keys shown, so a
 * user who knows roughly what they want can find it without reading the whole page.
 * <p>
 * The reference is built as plain {@link Entry} data and only turned into elements at the end.
 * That keeps the search a pure function over the data, testable without rendering, and lets
 * the same rows be filtered and wrapped for whatever width the terminal has.
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

    /** The panels the reference can open on. Each one's section is the first thing shown for it. */
    public enum Topic {
        STATUS("Status"),
        TOOLS("Tools"),
        VERSIONS("Versions"),
        ENV("Env"),
        TASKS("Tasks"),
        LOG("Log"),
        JOBS("Jobs");

        private final String label;

        Topic(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The next panel in Tab order, wrapping after the last. */
        Topic next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** The previous panel in Tab order, wrapping before the first. */
        Topic previous() {
            return values()[(ordinal() - 1 + values().length) % values().length];
        }
    }

    /** What a row of the reference is; decides how it renders and whether a search keeps it. */
    enum Kind {
        HEADING, KEY, FLAG, NOTE
    }

    /** The colour a status-symbol row is drawn in. Ordinary key rows carry no tone. */
    enum Tone {
        GREEN, CYAN, RED, YELLOW, DIM
    }

    /**
     * One logical line of the reference before wrapping. For a heading {@code key} is the
     * heading and {@code text} is empty; for a note {@code text} is the prose.
     */
    record Entry(Kind kind, String key, String text, @Nullable Tone tone) {

        static Entry heading(String heading) {
            return new Entry(Kind.HEADING, heading, "", null);
        }

        static Entry keyRow(String key, String description) {
            return new Entry(Kind.KEY, key, description, null);
        }

        static Entry flagRow(String flag, String description) {
            return new Entry(Kind.FLAG, flag, description, null);
        }

        static Entry note(String prose) {
            return new Entry(Kind.NOTE, "", prose, null);
        }

        static Entry symbol(String symbol, String meaning, Tone tone) {
            return new Entry(Kind.KEY, symbol, meaning, tone);
        }
    }

    /** Width of the bolded key column, wide enough for the longest label ({@code Tab / Shift+Tab}). */
    private static final int KEY_WIDTH = 17;
    /**
     * The launch-flag section gets its own, wider column: {@code --[no-]alt-screen} is as long as
     * {@link #KEY_WIDTH} allows and would otherwise run straight into its description.
     */
    private static final int FLAG_WIDTH = 21;
    /** Border + padding the dialog spends on each side, deducted before wrapping descriptions. */
    private static final int DIALOG_CHROME_COLUMNS = 4;
    /** Rows the dialog spends on its own border, the sticky footer, and the app's header/footer. */
    private static final int CHROME_ROWS = 9;
    /** Rows of search line, panel line and spacer that stay pinned above the scrolling reference. */
    private static final int HEADER_ROWS = 3;
    private static final int MIN_ROWS = 6;
    private static final int MIN_WIDTH = 46;
    private static final int MAX_WIDTH = 84;
    private static final int MIN_DESC_WIDTH = 20;
    /** Used when the terminal won't report its size — a conservative 80x30. */
    private static final int FALLBACK_WIDTH = 80;
    private static final int FALLBACK_HEIGHT = 30;
    /** Rows scrolled per mouse wheel tick, matching {@code LogPanel}. */
    private static final int WHEEL_STEP = 3;
    /** Footer hint at full width; narrower terminals get the short form. */
    private static final String FOOTER_HINT = "Esc close   Tab help for another panel   / search";

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final IntSupplier terminalHeight;
    @NonNull
    private final IntSupplier terminalWidth;

    @Getter
    private boolean open;
    private Topic topic = Topic.TOOLS;
    /** The panel the overlay was opened from, so the header can say which panel's keys come first. */
    private Topic openedFrom = Topic.TOOLS;
    private @Nullable String preOpenFocus;

    /** Text typed after {@code /}; empty when no search is active. */
    private String query = "";
    /** True while keystrokes are going into the search line rather than driving the reference. */
    private boolean searching;

    /**
     * First visible line, and the bounds the key handler clamps it against. Both are recomputed
     * in {@link #build()} — which always runs before the next keypress is read — so the handler
     * can clamp against the layout actually on screen rather than guessing at it.
     */
    private int scroll;
    private int maxScroll;
    private int pageRows = MIN_ROWS;

    /** Opens on the Tools reference, as {@code ?} always has. */
    public void open() {
        open(Topic.TOOLS);
    }

    /** Opens on {@code topic}, the panel the user was looking at when they pressed F1. */
    public void open(@NonNull Topic topic) {
        preOpenFocus = ctx.focusedId();
        ctx.clearFocus();
        this.topic = topic;
        this.openedFrom = topic;
        query = "";
        searching = false;
        scroll = 0;
        open = true;
    }

    public void close() {
        open = false;
        query = "";
        searching = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The panel whose reference is currently shown. */
    public Topic topic() {
        return topic;
    }

    // ==================== Rendering ====================

    public Element build() {
        int width = dialogWidth();
        List<Element> header = header();
        List<Entry> shown = matching(document(), query);
        List<Element> lines = shown.isEmpty()
                ? List.of(text("No keys match \"" + query + "\".").dim())
                : render(shown, width);
        int rows = Math.max(1, visibleRows() - HEADER_ROWS);

        maxScroll = Math.max(0, lines.size() - rows);
        scroll = Math.clamp(scroll, 0, maxScroll);
        // Leave one line of overlap between screens so the eye has an anchor when paging.
        pageRows = Math.max(1, rows - 1);

        int from = scroll;
        int to = Math.min(lines.size(), scroll + rows);
        List<Element> body = new ArrayList<>(header);
        body.addAll(lines.subList(from, to));
        body.add(text(""));
        body.add(footer(width, from, to, lines.size()));

        return dialog("Help · " + topic.label() + " panel", body.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(width);
    }

    /** The search line and which panel's keys are shown first; pinned above the scrolling part. */
    private List<Element> header() {
        Element search;
        if (searching) {
            search = text("/ " + query + "▏").bold();
        } else if (query.isEmpty()) {
            search = text("/ search keys and actions…").dim();
        } else {
            search = text("/ " + query).bold();
        }
        String panel = topic == openedFrom
                ? "showing keys for the panel you were in"
                : "showing keys for the " + topic.label() + " panel";
        return List.of(search, text(panel).dim(), text(""));
    }

    /**
     * The bottom line: the keys worth knowing, and the position counter when there is room for
     * it. Below the widths given the hint drops to its shortest form so it is never clipped.
     */
    private Element footer(int width, int from, int to, int total) {
        if (width < 60) {
            return text("Esc close   / search").dim();
        }
        if (width < 76) {
            return text(FOOTER_HINT).dim();
        }
        return row(
                text(FOOTER_HINT).dim(),
                spacer(),
                text((from + 1) + "-" + to + " of " + total).dim()
        );
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

    /** Turns entries into wrapped element lines for {@code width}. */
    private static List<Element> render(List<Entry> entries, int width) {
        int descWidth = Math.max(MIN_DESC_WIDTH, width - KEY_WIDTH - DIALOG_CHROME_COLUMNS);
        int flagDescWidth = Math.max(MIN_DESC_WIDTH, width - FLAG_WIDTH - DIALOG_CHROME_COLUMNS);
        int noteWidth = Math.max(MIN_DESC_WIDTH, width - DIALOG_CHROME_COLUMNS);

        List<Element> lines = new ArrayList<>();
        for (Entry e : entries) {
            switch (e.kind()) {
                case HEADING -> {
                    lines.add(text(""));
                    lines.add(text(e.key()).bold().cyan());
                }
                case KEY -> entryLines(lines, e, KEY_WIDTH, descWidth);
                case FLAG -> entryLines(lines, e, FLAG_WIDTH, flagDescWidth);
                case NOTE -> {
                    for (String line : Ui.wordWrap(e.text(), noteWidth)) {
                        lines.add(text(line).dim());
                    }
                }
            }
        }
        return lines;
    }

    /** A key and what it does, the description wrapped under itself with the key column blank. */
    private static void entryLines(List<Element> lines, Entry e, int labelWidth, int wrapWidth) {
        List<String> wrapped = Ui.wordWrap(e.text(), wrapWidth);
        for (int i = 0; i < wrapped.size(); i++) {
            lines.add(row(
                    keyLabel(i == 0 ? e.key() : "", e.tone()).constraint(length(labelWidth)),
                    text(wrapped.get(i)).dim()
            ));
        }
    }

    /** Keys are bold cyan; a status symbol takes the colour its meaning is drawn in. */
    private static TextElement keyLabel(String label, @Nullable Tone tone) {
        if (tone == null) {
            return text(label).bold().cyan();
        }
        return switch (tone) {
            case GREEN -> text(label).green();
            case CYAN -> text(label).cyan();
            case RED -> text(label).red();
            case YELLOW -> text(label).yellow();
            case DIM -> text(label).dim();
        };
    }

    // ==================== Key / mouse handling ====================

    /** Called by {@code GlobalKeyBindings} for every key while the overlay is up. */
    public void handleKey(@NonNull KeyEvent key) {
        if (searching && typeIntoSearch(key)) {
            return;
        }
        if (key.code() == KeyCode.ESCAPE) {
            // Esc backs out one level at a time: first the search, then the overlay.
            if (searching || !query.isEmpty()) {
                clearSearch();
            } else {
                close();
            }
            return;
        }
        if (key.code() == KeyCode.ENTER) {
            if (searching) {
                searching = false;
            } else {
                close();
            }
            return;
        }
        if (key.code() == KeyCode.F1 || key.isChar('?') || key.isChar('q')) {
            close();
            return;
        }
        if (key.isChar('/')) {
            searching = true;
            scroll = 0;
            return;
        }
        if (key.code() == KeyCode.TAB) {
            topic = key.hasShift() ? topic.previous() : topic.next();
            scroll = 0;
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

    /**
     * Appends printable characters to the query and deletes on Backspace while the search line
     * is taking input. Returns false for every other key, which then reaches the normal handling
     * so arrows, Tab and Esc keep working mid-search.
     */
    private boolean typeIntoSearch(KeyEvent key) {
        if (key.code() == KeyCode.BACKSPACE) {
            if (!query.isEmpty()) {
                query = query.substring(0, query.length() - 1);
            }
            scroll = 0;
            return true;
        }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            query = query + Character.toString(key.codePoint());
            scroll = 0;
            return true;
        }
        return false;
    }

    private void clearSearch() {
        query = "";
        searching = false;
        scroll = 0;
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

    // ==================== Search ====================

    /**
     * The entries a search query leaves visible. Only key and flag rows are searched, matching
     * case-insensitively against the key and the description together. Prose is dropped while a
     * query is active, and a heading survives only when a row beneath it matched. A blank query
     * keeps everything.
     */
    static List<Entry> matching(List<Entry> doc, String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return doc;
        }
        List<Entry> kept = new ArrayList<>();
        Entry pendingHeading = null;
        for (Entry e : doc) {
            if (e.kind() == Kind.HEADING) {
                pendingHeading = e;
                continue;
            }
            if (e.kind() == Kind.NOTE || !matches(e, needle)) {
                continue;
            }
            if (pendingHeading != null) {
                kept.add(pendingHeading);
                pendingHeading = null;
            }
            kept.add(e);
        }
        return kept;
    }

    private static boolean matches(Entry e, String lowerNeedle) {
        return e.key().toLowerCase(Locale.ROOT).contains(lowerNeedle)
                || e.text().toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    // ==================== Content ====================

    /**
     * The whole reference as data. Rebuilt each frame so it tracks the live state it mentions —
     * the active backend and which features it actually has.
     */
    private List<Entry> document() {
        String tool = ctx.backend().name();
        String configFile = ctx.backend().projectConfigFileName();
        // Each section asks for the capability it documents rather than for a backend name, so
        // the reference describes the session the user is actually in.
        boolean tasks = ctx.supports(BackendFeature.TASKS);
        boolean env = ctx.supports(BackendFeature.ENV);
        boolean upgrade = ctx.supports(BackendFeature.UPGRADE);
        boolean plugins = ctx.supports(BackendFeature.PLUGIN_REGISTRY);
        boolean pins = ctx.supports(BackendFeature.PIN_ON_INSTALL);
        boolean globalConfig = ctx.supports(BackendFeature.GLOBAL_CONFIG);
        boolean trust = ctx.supports(BackendFeature.TRUST);
        Doc doc = new Doc();

        panelSection(doc, tool, configFile, tasks, env, upgrade, plugins, pins, trust);

        doc.section("ANYWHERE");
        doc.key("1-5", "Jump to a panel: 1 Status, 2 Tools, 3 Env, 4 Tasks, 5 Log");
        doc.key("Tab / Shift+Tab", "Next / previous panel");
        doc.key("Ctrl+O", "Jump mode");
        doc.key("Ctrl+P", "All commands, searchable");
        doc.key(":", "Type a " + tool + " command and run it");
        doc.key("Space", "More actions");
        doc.key("a", plugins
                ? "Add SDK — install another version of a plugin you already have"
                : "Add SDK — fuzzy-find the mise registry");
        doc.key("I", "Auto-install — read " + configFile + " and make the project match it. "
                + "Tools it declares that are already installed are pinned; ones that are "
                + "missing are installed. Where a version is installed that differs from the "
                + "config only within the same major release (25.0.3 on disk, 25.0.4 in the "
                + "config), it asks per tool whether to keep what you have or download what the "
                + "config says. Offline, nothing is downloaded: for each tool whose config "
                + "version is missing it offers the versions already installed, closest first, "
                + "and pins the one you pick.");
        doc.key("w", "Jobs");
        doc.key("z / Z", "Undo / redo the last config change");
        doc.key("e", "Edit the project " + configFile + " in-app");
        doc.key("r", "Refresh");
        doc.key("C", "Cancel everything running");
        doc.key("F1 / ?", "Keyboard help");
        doc.key("q", "Quit");
        doc.note("Maintenance actions (trust, doctor, prune, self-update…) live in the Space menu.");

        doc.section("WHAT THE SYMBOLS MEAN");
        doc.symbol("✓", "installed", Tone.GREEN);
        doc.symbol("➜", "installing or queued", Tone.CYAN);
        doc.symbol("✗", "in " + configFile + ", not installed", Tone.RED);
        doc.symbol("↑", "newer version available", Tone.YELLOW);
        doc.symbol("●", "active in this folder", Tone.GREEN);
        doc.symbol("○", "installed, not active", Tone.DIM);
        doc.symbol("*", "marked for batch", Tone.YELLOW);

        doc.section("NEW HERE?");
        doc.note("Press Space m for beginner mode: menus for everything.");

        doc.section("NAVIGATION");
        doc.key("Up/Down, j/k", "Move the selection / scroll one line");
        doc.key("PgUp / PgDn", "Page the focused list by ten rows");
        doc.key("Home / End", "Jump to the first / last entry");
        doc.key("Mouse", "Click to focus, wheel to scroll");

        doc.section("LAYOUT");
        doc.note("A stack of numbered panels down the left, all on screen at once, and one big "
                + "pane on the right showing everything about whatever is selected in the "
                + "focused panel. The command log sits under that pane. The focused panel has "
                + "the bright border and takes the spare room; the others shrink but stay "
                + "visible, so an install running in Tools is still in sight while you read "
                + "Tasks. A panel this backend cannot serve keeps its slot, marked (n/a), and "
                + "explains itself in the main pane when you press its number.");

        doc.section("MODALS");
        doc.note("Add SDK (a): four steps — " + (pins ? "tool" : "plugin") + ", version, where (this "
                + "project, everywhere, or just install), confirm. Type to fuzzy find, Up/Down choose, "
                + "Enter goes on, Shift+Tab goes back, Esc cancels. The confirm step shows the exact "
                + "command before it runs.");
        if (plugins) {
            doc.note("Add plugin (p): type to fuzzy find the catalog, Enter registers the "
                    + "highlighted plugin. For a full \"<name> --alias <x> --source <url>\" use "
                    + "Space then P.");
        }
        doc.note("Config editor (e): Ctrl+S saves and refreshes so " + tool + " picks the "
                + "change up; Esc closes, asking once first if there are unsaved changes.");
        doc.note("Auto-install questions (I): k keeps the version already installed and pins it "
                + "to this project, d downloads the one " + configFile + " asks for, s skips that "
                + "tool. One question per tool, then everything runs at once; Esc abandons the "
                + "whole run.");
        doc.note("Confirmations: y or Enter to go ahead, n or Esc to back out.");
        doc.note("Backend picker (Space then B): Up/Down then Enter applies and persists the choice; Esc cancels.");
        if (tasks) {
            doc.note("Task arguments (>): Enter runs the task with what you typed, Esc cancels.");
        }

        doc.section("LAUNCH FLAGS");
        doc.flag("--backend", "Force mise or vfox instead of detecting it from "
                + configFile + " in the current directory");
        doc.flag("--offline", "Installed tools only — blocks install, use, "
                + (upgrade ? "upgrade, self-update" : "self-update") + " and Add SDK, all of which need the network");
        doc.flag("--auto-install", "Run the I flow once the session is up, instead of waiting "
                + "for the key");
        doc.flag("--mouse", "Mouse capture — already on by default in tambo");
        doc.flag("--[no-]alt-screen", "Render on the alternate screen (default) or inline");
        doc.flag("--show-cursor", "Leave the terminal cursor visible");
        doc.flag("--tick-rate", "Animation tick in milliseconds; 0 disables animation");
        doc.flag("--poll-timeout", "Event poll timeout in milliseconds");

        doc.section("CONFIG");
        doc.note("~/.config/tambo/tambo.properties holds theme.* colours and keys.* navigation "
                + "overrides. $TAMBO_CONFIG_DIR overrides that path.");
        if (globalConfig) {
            doc.note("$MISE_CONFIG_DIR, when set, is where E looks for the global config.toml.");
        }

        return doc.entries();
    }

    /** The keys of the panel the overlay opened on, first in the reference. */
    private void panelSection(Doc doc, String tool, String configFile, boolean tasks, boolean env,
                              boolean upgrade, boolean plugins, boolean pins, boolean trust) {
        doc.section("IN THE " + topic.label().toUpperCase(Locale.ROOT) + " PANEL");
        switch (topic) {
            case STATUS -> {
                if (trust) {
                    doc.key("t", "Trust this project's " + configFile + " (also Space then t)");
                }
                doc.key("e", "Look at the project " + configFile + " in the config editor");
            }
            case TOOLS -> {
                doc.key("Enter / →", "See the selected tool's versions");
                doc.key("i", !pins
                        ? "Install the selected tool — with no version installed yet, opens the version picker instead"
                        : "Install the selected tool");
                doc.key("u", "Use the selected tool in the project " + configFile);
                doc.key("g", "Install and set as the global default");
                if (upgrade) {
                    doc.key("U", "Upgrade the marked tools, or the selected one");
                }
                doc.key("x", "Uninstall the selected version (asks to confirm)");
                doc.key("R", "Remove the selected tool from " + configFile + " (asks to confirm)");
                doc.key("d", plugins
                        ? "Remove the selected tool's plugin AND all its versions (asks to confirm)"
                        : "Remove the selected tool's plugin, keeping installed versions (asks to confirm)");
                if (plugins) {
                    doc.key("p", "Add a plugin — fuzzy-find the " + tool + " catalog and register it");
                }
                doc.key("m", "Mark or unmark the selected tool for a batch");
                doc.key("Ctrl+A", "Mark every outdated tool");
                doc.key("/", "Filter the list");
                doc.key("o", "Show only outdated tools");
                doc.key("+", "Full-screen table");
                doc.key("c", "Cancel this tool's running job");
                doc.key("Esc", "Clear the marks and the filter");
            }
            case VERSIONS -> {
                doc.key("↑ / ↓", "Move between versions");
                doc.key("/", "Filter the versions");
                doc.key("i", "Install the selected version");
                doc.key("u", "Use the selected version in the project " + configFile);
                doc.key("g", "Set the selected version as the global default");
                doc.key("y", "Copy the selected version's install path");
                doc.key("Enter", "Open the version's detail");
                doc.key("Esc / ←", "Back to the Tools panel");
            }
            case ENV -> {
                if (env) {
                    doc.key("/", "Filter the variables");
                    doc.key("y", "Copy the selected variable's value to the clipboard");
                } else {
                    doc.note(tool + " has no environment panel.");
                }
            }
            case TASKS -> {
                if (tasks) {
                    doc.key("Enter", "Run the selected task");
                    doc.key(">", "Run the selected task with arguments");
                    doc.key(".", "Re-run the last task — works even with nothing selected");
                    doc.key("c", "Cancel the selected task");
                    doc.key("/", "Filter the tasks");
                } else {
                    doc.note(tool + " has no tasks.");
                }
            }
            case LOG -> {
                doc.note("Every " + tool + " command this app runs, echoed the way lazygit echoes git.");
                doc.key("↑ / ↓", "Scroll; PgUp / PgDn page; Home jumps to the oldest entry");
                doc.note("Long lines wrap to the panel's width, so nothing is cut off at the edge.");
                doc.key("End", "Resume following the newest entry");
            }
            case JOBS -> {
                doc.key("r", "Retry the selected job");
                doc.key("c", "Cancel the selected job");
                doc.key("l", "Show the job's full log");
                doc.key("y", "Copy the job's error");
                doc.key("x", "Dismiss the job");
                doc.key("Esc", "Back");
            }
        }
    }

    /**
     * Accumulates the reference as entries. Kept separate from wrapping so the search can see
     * the whole document before any of it is laid out.
     */
    private static final class Doc {

        private final List<Entry> entries = new ArrayList<>();

        void section(String heading) {
            entries.add(Entry.heading(heading));
        }

        void key(String key, String description) {
            entries.add(Entry.keyRow(key, description));
        }

        void flag(String flag, String description) {
            entries.add(Entry.flagRow(flag, description));
        }

        void symbol(String symbol, String meaning, Tone tone) {
            entries.add(Entry.symbol(symbol, meaning, tone));
        }

        void note(String prose) {
            entries.add(Entry.note(prose));
        }

        List<Entry> entries() {
            return entries;
        }
    }
}
