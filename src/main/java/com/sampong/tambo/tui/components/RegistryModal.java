package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textInput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.features.Fuzzy;
import com.sampong.tambo.tui.state.Lazy;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The "Add SDK" wizard (Charm huh's step-by-step form): Tool → Version → Where → Confirm, with a
 * strip across the top showing what is chosen and what is left. Tool and Version fuzzy-find by
 * typing into a real input box; Where is a short list of the places the choice can land, each
 * naming the file it writes; Confirm shows the exact command before anything runs.
 * <p>
 * The step this replaced was a single Ctrl+G toggle on the version list. That made the scope the
 * least visible part of the flow, when it is the part with consequences: "this project" and
 * "everywhere" write different files, and "just install" writes none. Spelling the three out as
 * choices — and the command as text — means nobody has to know mise's flags to pick right.
 * <p>
 * Both backends get all three choices. What differs is declared, not branched on: a backend with
 * its own plugin registry ({@link BackendFeature#PLUGIN_REGISTRY}) already has a separate flow
 * for registering plugins, so the Tool step lists SDKs already added; a backend without one
 * browses its whole catalog. A backend that cannot install and pin in one command
 * ({@link BackendFeature#PIN_ON_INSTALL}) previews the two commands it will run instead of one.
 * <p>
 * {@code Shift+Tab} steps back, {@code Esc} cancels. Owns all of its own state — the rest of the
 * app only asks {@link #isOpen()}, and routes keys here via {@link #handleKey} while
 * {@link #ownsKeyboard()} (the two steps with no input box to receive them).
 */
@RequiredArgsConstructor
public final class RegistryModal implements Modal {

    private static final int VISIBLE_ROWS = 12;
    private static final int WIDTH = 78;
    /** Columns the dialog's border and padding leave for text. */
    private static final int TEXT_WIDTH = WIDTH - 4;

    /** The wizard's steps, in order. */
    public enum Step {
        TOOL("Tool"), VERSION("Version"), WHERE("Where"), CONFIRM("Confirm");

        private final String label;

        Step(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Where a chosen version lands. */
    public enum Where {
        PROJECT("This project", "project"),
        GLOBAL("Everywhere (global)", "global"),
        INSTALL_ONLY("Just install it", "install only");

        private final String label;
        /** The one-word answer the step strip shows once this step is done. */
        private final String shortLabel;

        Where(String label, String shortLabel) {
            this.label = label;
            this.shortLabel = shortLabel;
        }

        public String label() {
            return label;
        }

        public String shortLabel() {
            return shortLabel;
        }
    }

    @NonNull
    private final UiContext ctx;

    @Getter
    private boolean open;
    private Step step = Step.TOOL;
    private final TextInputState search = new TextInputState();
    private String lastQuery = "";
    private int index;
    private @Nullable CatalogEntry tool;
    private @Nullable String version;
    private List<SdkRelease> releases = List.of();
    private boolean versionsLoading;
    /** True when the wizard was opened at the Version step, so Shift+Tab there closes instead. */
    private boolean startedAtVersion;
    private Where where = Where.PROJECT;
    private @Nullable String preOpenFocus;

    /** The step on screen. */
    public Step step() {
        return step;
    }

    /** The place the version will land, as chosen so far. */
    public Where where() {
        return where;
    }

    public void open() {
        // The catalog (mise's ~200 KB of JSON, or vfox's `available` listing) is fetched here
        // on first open rather than at startup — a session that never adds an SDK never pays for
        // it at all. Reopening after a failed fetch is the user asking to try again; a loaded
        // catalog is reused, since nothing done locally changes what is installable.
        ctx.state().catalogLazy().retryIfFailed();
        ctx.actions().ensureCatalog();
        preOpenFocus = ctx.focusedId();
        open = true;
        startedAtVersion = false;
        tool = null;
        version = null;
        where = Where.PROJECT;
        enterStep(Step.TOOL);
    }

    /**
     * Opens straight at the version step for an already-registered SDK, skipping the
     * plugin-picking step — the Tools panel's "install" action for a plugin that has no
     * version installed yet. The entry is synthesised from the name so this works even for a
     * plugin added by {@code --source} that the catalog has never heard of.
     */
    public void openAtVersion(String toolName) {
        open();
        startedAtVersion = true;
        chooseTool(CatalogEntry.of(toolName, null));
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /**
     * True on the Where and Confirm steps, which have no input box to receive keys — the global
     * key handler hands them to {@link #handleKey} instead, the way {@link ConfirmModal} reads
     * its y/n.
     */
    public boolean ownsKeyboard() {
        return open && (step == Step.WHERE || step == Step.CONFIRM);
    }

    /** The context-sensitive hint line the footer shows while the wizard is open. */
    public String footerHint() {
        return switch (step) {
            case TOOL -> "type to fuzzy find  ↑↓ select  Enter next  Esc cancel";
            case VERSION -> "type to fuzzy find  ↑↓ select  Enter next  Shift+Tab back  Esc cancel";
            case WHERE -> "↑↓ choose  Enter next  Shift+Tab back  Esc cancel";
            case CONFIRM -> "Enter run  Shift+Tab back  Esc cancel";
        };
    }

    /**
     * True when the Tool step lists only SDKs already added rather than the whole catalog — the
     * case for a backend whose catalog is reached through its own plugin-registry flow instead.
     */
    private boolean scopedToAdded() {
        return ctx.supports(BackendFeature.PLUGIN_REGISTRY);
    }

    /** The places this backend can put a version, in the order the Where step lists them. */
    private List<Where> choices() {
        return ctx.supports(BackendFeature.GLOBAL_SCOPE)
                ? List.of(Where.PROJECT, Where.GLOBAL, Where.INSTALL_ONLY)
                : List.of(Where.PROJECT, Where.INSTALL_ONLY);
    }

    // ==================== Pure helpers (tested directly) ====================

    /**
     * The command(s) a choice will run, as the preview prints them. A backend whose {@code use}
     * cannot install (no {@link BackendFeature#PIN_ON_INSTALL}) installs first, then pins — two
     * commands, and the preview says so rather than pretending it is one.
     */
    static List<String> plan(String backend, boolean pinsOnInstall, String toolAtVersion, Where where) {
        return switch (where) {
            case INSTALL_ONLY -> List.of(backend + " install " + toolAtVersion);
            case PROJECT -> pinsOnInstall
                    ? List.of(backend + " use " + toolAtVersion)
                    : List.of(backend + " install " + toolAtVersion, backend + " use -p " + toolAtVersion);
            case GLOBAL -> pinsOnInstall
                    ? List.of(backend + " use -g " + toolAtVersion)
                    : List.of(backend + " install " + toolAtVersion, backend + " use -g " + toolAtVersion);
        };
    }

    /** What a choice changes on disk, in the words the Where step uses under each option. */
    static String consequence(Where where, String projectFile, @Nullable Path globalConfig, String backend) {
        return switch (where) {
            case PROJECT -> "writes ./" + projectFile + " · only this folder uses it";
            case GLOBAL -> (globalConfig == null ? "sets " + backend + "'s global default"
                    : "writes " + home(globalConfig)) + " · default for every folder";
            case INSTALL_ONLY -> "downloads now, doesn't pin it anywhere";
        };
    }

    private static String home(Path path) {
        String text = path.toString().replace('\\', '/');
        String home = System.getProperty("user.home", "").replace('\\', '/');
        return !home.isEmpty() && text.startsWith(home) ? "~" + text.substring(home.length()) : text;
    }

    // ==================== Rendering ====================

    public Element build() {
        String query = search.text();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            index = 0;
        }

        List<Element> content = new ArrayList<>();
        content.add(stepStrip());
        content.add(text(""));
        switch (step) {
            case TOOL -> buildToolStep(content, query);
            case VERSION -> buildVersionStep(content, query);
            case WHERE -> buildWhereStep(content);
            case CONFIRM -> buildConfirmStep(content);
        }
        content.add(text(""));
        content.add(row(
                text(footerHint()).dim(),
                spacer(),
                text("step " + (step.ordinal() + 1) + " of " + Step.values().length).dim()));

        return dialog("Add SDK", content.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    /**
     * {@code ✓ Tool python ── ✓ Version 3.12.7 ── ● Where ── ○ Confirm}: done steps carry their
     * answer, so the strip doubles as a summary of what has been chosen so far.
     */
    private Element stepStrip() {
        List<Element> parts = new ArrayList<>();
        for (Step s : Step.values()) {
            if (s.ordinal() > 0) {
                parts.add(text(" ── ").dim());
            }
            if (s.ordinal() < step.ordinal()) {
                String answer = switch (s) {
                    case TOOL -> tool == null ? "" : " " + tool.name();
                    case VERSION -> version == null ? "" : " " + version;
                    case WHERE -> " " + where.shortLabel();
                    case CONFIRM -> "";
                };
                parts.add(text("✓ ").fg(Color.GREEN));
                parts.add(text(s.label()));
                parts.add(text(answer).bold());
            } else if (s == step) {
                parts.add(text("● " + s.label()).cyan().bold());
            } else {
                parts.add(text("○ " + s.label()).dim());
            }
        }
        return row(parts.toArray(new Element[0]));
    }

    private void buildToolStep(List<Element> content, String query) {
        List<CatalogEntry> matches = fuzzySdks(query);
        index = Ui.clamp(index, matches.size());

        String source = scopedToAdded()
                ? "added plugins (" + addedSdks().size() + ")"
                : ctx.backend().name() + " catalog (" + ctx.state().catalog().size() + ")";
        content.add(row(text("Which tool?").bold(), spacer(), text(source).dim()));
        content.add(searchInputRow("Search", scopedToAdded()
                ? "type to fuzzy find an added plugin"
                : "type to fuzzy find, e.g. \"node\" or \"jdk\""));
        content.add(text(""));
        String emptyMessage = emptyToolListMessage(query, matches);
        if (emptyMessage != null) {
            addWrapped(content, emptyMessage);
        } else {
            addWindowedRows(content, matches.size(), i -> toolRow(matches, i));
            // The rows cut descriptions to fit; the highlighted tool's is worth reading whole.
            addWrapped(content, Ui.nullToDash(matches.get(index).description()));
        }
    }

    /** Why the tool list has nothing to show, or null when it does. */
    private @Nullable String emptyToolListMessage(String query, List<CatalogEntry> matches) {
        if (scopedToAdded()) {
            if (addedSdks().isEmpty()) {
                return "No plugins added yet — press p to add one from the catalog";
            }
            return matches.isEmpty() ? "No plugin matches \"" + query + "\"" : null;
        }
        if (ctx.state().catalog().isEmpty()) {
            Lazy<List<CatalogEntry>> catalog = ctx.state().catalogLazy();
            return catalog.everLoaded() || catalog.failed() ? "Catalog unavailable" : "Loading catalog…";
        }
        return matches.isEmpty() ? "No SDK matches \"" + query + "\"" : null;
    }

    private Element toolRow(List<CatalogEntry> matches, int i) {
        CatalogEntry e = matches.get(i);
        boolean sel = i == index;
        return row(
                text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                sel ? text(e.name()).bold().cyan() : text(e.name()).bold(),
                spacer(),
                text(Ui.truncate(Ui.nullToDash(e.description()), 40) + " ").dim()
        );
    }

    private void buildVersionStep(List<Element> content, String query) {
        List<SdkRelease> matches = matchingReleases(query);
        index = Ui.clamp(index, matches.size());
        String toolName = tool == null ? "" : tool.name();

        content.add(text("Which version of " + toolName + "?").bold());
        content.add(searchInputRow("Search", "type to fuzzy find a version"));
        content.add(text(""));
        if (versionsLoading) {
            addWrapped(content, "Fetching versions of " + toolName + " from " + ctx.backend().name() + "…");
        } else if (matches.isEmpty()) {
            addWrapped(content, "No version matches \"" + query + "\"");
        } else {
            addWindowedRows(content, matches.size(), i -> {
                SdkRelease r = matches.get(i);
                boolean sel = i == index;
                String label = r.latest() ? r.version() + "  (newest)" : r.version();
                return row(
                        text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                        sel ? text(label).bold().cyan() : text(label),
                        spacer(),
                        r.installed() ? text("installed ").fg(Color.GREEN).dim() : text("")
                );
            });
        }
    }

    private void buildWhereStep(List<Element> content) {
        content.add(text("Where should " + toolAtVersion().replace('@', ' ') + " apply?").bold());
        content.add(text(""));
        for (Where choice : choices()) {
            boolean sel = choice == where;
            content.add(row(
                    sel ? text(" (●) ").cyan().bold() : text(" ( ) ").dim(),
                    sel ? text(choice.label()).bold().cyan() : text(choice.label()).bold()));
            content.add(row(text("     "), text(consequence(choice, ctx.backend().projectConfigFileName(),
                    ctx.backend().globalConfigPath(), ctx.backend().name())).dim()));
        }
        content.add(text(""));
        addPlan(content);
    }

    private void buildConfirmStep(List<Element> content) {
        content.add(text("Ready to go").bold());
        content.add(text(""));
        content.add(row(text("Tool       ").dim(), text(tool == null ? "" : tool.name()).bold()));
        content.add(row(text("Version    ").dim(), text(version == null ? "" : version).bold()));
        content.add(row(text("Where      ").dim(), text(where.label()).bold()));
        content.add(row(text("           ").dim(), text(consequence(where, ctx.backend().projectConfigFileName(),
                ctx.backend().globalConfigPath(), ctx.backend().name())).dim()));
        content.add(text(""));
        addPlan(content);
        if (where != Where.INSTALL_ONLY) {
            content.add(text(""));
            content.add(text("Afterwards, z puts the config file back the way it was.").dim());
        }
    }

    /** The "will run" preview: the exact command(s), so the flags are never a guess. */
    private void addPlan(List<Element> content) {
        List<String> commands = plan(ctx.backend().name(), ctx.supports(BackendFeature.PIN_ON_INSTALL),
                toolAtVersion(), where);
        for (int i = 0; i < commands.size(); i++) {
            content.add(row(text(i == 0 ? " will run  " : " then      ").dim(), text("$ " + commands.get(i)).cyan()));
        }
    }

    private String toolAtVersion() {
        return (tool == null ? "" : tool.name()) + "@" + (version == null ? "" : version);
    }

    /** Fuzzy-matches the fetched releases by version string. */
    private List<SdkRelease> matchingReleases(String query) {
        return Fuzzy.filter(query, releases, SdkRelease::version, null);
    }

    /** The typed input box shared by the Tool and Version steps. */
    private Element searchInputRow(String label, String placeholder) {
        return row(
                text(label + " ").dim().constraint(length(8)),
                textInput(search)
                        .placeholder(placeholder)
                        .placeholderColor(Color.DARK_GRAY)
                        .id(PanelIds.MODAL_INPUT)
                        .focusable(true)
                        .onKeyEvent(this::handleKey)
        );
    }

    /** Dimmed free text, word-wrapped to the dialog — one element per line. */
    private static void addWrapped(List<Element> content, String text) {
        for (String line : Ui.wordWrap(text, TEXT_WIDTH)) {
            content.add(text(line).dim());
        }
    }

    /** Renders a window of VISIBLE_ROWS rows that follows the selection. */
    private void addWindowedRows(List<Element> content, int total, IntFunction<Element> rowAt) {
        int start = Math.max(0, index - VISIBLE_ROWS + 1);
        int end = Math.min(total, start + VISIBLE_ROWS);
        List<Element> rows = new ArrayList<>();
        for (int i = start; i < end; i++) {
            rows.add(rowAt.apply(i));
        }
        content.add(column(rows.toArray(new Element[0])));
        int hidden = total - (end - start);
        content.add(hidden > 0 ? text("… " + hidden + " more (keep typing to narrow)").dim() : text(""));
    }

    private List<CatalogEntry> fuzzySdks(String query) {
        // Match on the name first, then fall back to the description and install methods, so
        // typing one of those (e.g. "cargo", "npm", "ubi") narrows the list too. A backend that
        // reports no install methods contributes a harmless "-" to that second field.
        List<CatalogEntry> source = scopedToAdded() ? addedSdks() : ctx.state().catalog();
        return Fuzzy.filter(query, source, CatalogEntry::name, CatalogEntry::searchableDetail);
    }

    /**
     * The SDKs the user already has, derived from the installed listing rather than the full
     * catalog. Enriches each with the catalog's description when one is available (the catalog
     * may not have loaded yet, or the SDK may not be in it at all — either way that is
     * cosmetic, so a bare entry is a fine fallback).
     */
    private List<CatalogEntry> addedSdks() {
        Map<String, CatalogEntry> byName = ctx.state().catalog().stream()
                .collect(Collectors.toMap(e -> e.name().toLowerCase(Locale.ROOT), e -> e, (a, b) -> a));
        return ctx.state().sdks().stream()
                .map(SdkVersion::name)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(name -> byName.getOrDefault(name.toLowerCase(Locale.ROOT),
                        CatalogEntry.of(name, null)))
                .toList();
    }

    // ==================== Key handling ====================

    /**
     * Every key the wizard reacts to: list or choice navigation, Enter (next / run), Shift+Tab
     * (back) and Esc (cancel). Reached from the input box on the first two steps and from the
     * global key handler on the last two. Everything else is swallowed so no global shortcut
     * fires under the wizard — except plain typing, which the input box has already consumed.
     */
    public EventResult handleKey(@NonNull KeyEvent event) {
        if (event.isCancel()) {
            close();
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.TAB && event.hasShift()) {
            back();
            return EventResult.HANDLED;
        }
        if (event.isConfirm()) {
            next();
            return EventResult.HANDLED;
        }
        if (step == Step.WHERE) {
            List<Where> choices = choices();
            int at = choices.indexOf(where);
            if (event.code() == KeyCode.UP || event.isChar('k')) {
                where = choices.get(Math.max(0, at - 1));
            } else if (event.code() == KeyCode.DOWN || event.isChar('j')) {
                where = choices.get(Math.min(choices.size() - 1, at + 1));
            }
            return EventResult.HANDLED;
        }
        if (step == Step.CONFIRM) {
            return EventResult.HANDLED;
        }
        int total = step == Step.TOOL
                ? fuzzySdks(search.text()).size()
                : matchingReleases(search.text()).size();
        int move = Ui.listStep(event, VISIBLE_ROWS);
        if (move != 0) {
            index = Ui.clamp(index + move, total);
        }
        // Swallow everything else (Tab, stray chars with modifiers, …) — the wizard is modal.
        return EventResult.HANDLED;
    }

    /** Enter: accept this step's answer and move on; on Confirm, run it. */
    private void next() {
        switch (step) {
            case TOOL -> {
                List<CatalogEntry> matches = fuzzySdks(search.text());
                if (!matches.isEmpty()) {
                    chooseTool(matches.get(Ui.clamp(index, matches.size())));
                }
            }
            case VERSION -> {
                List<SdkRelease> matches = matchingReleases(search.text());
                if (!versionsLoading && !matches.isEmpty()) {
                    version = matches.get(Ui.clamp(index, matches.size())).version();
                    enterStep(Step.WHERE);
                }
            }
            case WHERE -> enterStep(Step.CONFIRM);
            case CONFIRM -> run();
        }
    }

    /** Shift+Tab: one step back, keeping what was chosen there so it is not typed twice. */
    private void back() {
        switch (step) {
            case TOOL -> close();
            case VERSION -> {
                if (startedAtVersion) {
                    close();
                } else {
                    enterStep(Step.TOOL);
                }
            }
            case WHERE -> enterStep(Step.VERSION);
            case CONFIRM -> enterStep(Step.WHERE);
        }
    }

    private void chooseTool(CatalogEntry entry) {
        tool = entry;
        version = null;
        releases = List.of();
        versionsLoading = true;
        enterStep(Step.VERSION);

        String toolName = entry.name();
        ctx.actions().fetchReleases(toolName, fetched -> {
            // Ignore stale responses if the user has since picked a different tool or closed.
            if (open && tool != null && toolName.equals(tool.name())) {
                releases = fetched;
                versionsLoading = false;
            }
        });
    }

    /**
     * Moves to {@code target}. The two search steps share one input box, so it is cleared on the
     * way in; the two choice steps have none, so focus is cleared and the global key handler
     * routes keys here instead.
     */
    private void enterStep(Step target) {
        step = target;
        search.clear();
        lastQuery = "";
        index = 0;
        if (target == Step.TOOL || target == Step.VERSION) {
            ctx.focus(PanelIds.MODAL_INPUT);
        } else {
            ctx.clearFocus();
        }
    }

    private void run() {
        if (tool == null || version == null) {
            return;
        }
        String target = toolAtVersion();
        Where chosen = where;
        close();
        switch (chosen) {
            case PROJECT -> ctx.actions().useSdk(target, false);
            case GLOBAL -> ctx.actions().useSdk(target, true);
            case INSTALL_ONLY -> ctx.actions().install(target);
        }
    }
}
