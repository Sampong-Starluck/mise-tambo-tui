package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import dev.tamboui.layout.Alignment;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.TableElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.TableState;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.features.Clipboard;
import com.sampong.tambo.tui.features.PanelFilter;
import com.sampong.tambo.tui.state.Job;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.ProgressSnapshot;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Panel 2 — the installed and configured SDK versions, and the install / uninstall / pin actions
 * that act on them. The busiest panel in the app, and the one the sidebar's spare room goes to.
 * <p>
 * Every row leads with a {@link ToolStatus} glyph — mason.nvim's status column — so the state of
 * the whole project reads at a glance down one column: {@code ✓} installed, {@code ➜} being worked
 * on, {@code ✗} in the config but missing from disk. A {@code ↑} after the version says a newer one
 * exists, and a {@code *} in front marks the row for a batch action (yazi's selection: {@code m}
 * marks, {@code U} / {@code x} then act on every marked row at once).
 * <p>
 * {@code Enter} moves into the Versions column to the right — yazi's Miller columns — which is
 * where a <em>different</em> version of the selected tool is picked; this panel only ever acts on
 * the version a row already names.
 * <p>
 * A {@link TableElement} rather than a plain list, for the reason lazygit pads its own list rows
 * into columns: a version that starts at a different column on every row is far harder to scan.
 * {@code +} widens it to the whole body with latest and where columns added, the view a batch
 * upgrade is planned from.
 */
@RequiredArgsConstructor
public final class ToolsPanel {

    @NonNull
    private final UiContext ctx;
    private final PanelFilter filter = new PanelFilter(PanelIds.TOOLS_FILTER, PanelIds.TOOLS);
    /**
     * Selection and viewport offset, held here rather than in the element: a fresh element tree
     * is built every frame, so anything the widget tracked internally would be discarded before
     * the next frame showed it.
     */
    private final TableState tableState = new TableState();
    private String lastQuery = "";

    /** The SDKs currently shown — the full list, narrowed by the fuzzy filter and {@code o}. */
    private List<SdkVersion> visibleItems() {
        List<SdkVersion> items = filter.apply(ctx.state().sdks(), SdkVersion::name, SdkVersion::version);
        if (!ctx.state().outdatedOnly()) {
            return items;
        }
        Map<String, String> outdated = ctx.state().outdated();
        return items.stream().filter(sdk -> outdated.containsKey(sdk.name())).toList();
    }

    /** The SDK the selection sits on, or null when the (filtered) list is empty. */
    public @Nullable SdkVersion selected() {
        List<SdkVersion> items = visibleItems();
        return items.isEmpty() ? null : items.get(selectedIndex(items.size()));
    }

    private int selectedIndex(int size) {
        Integer selected = tableState.selected();
        return Ui.clamp(selected == null ? 0 : selected, size);
    }

    public Panel build() {
        // The outdated check feeds nothing but the "↑" markers, and it is the slowest call in the
        // app (network). Pulling it in from here lets the table paint from the SDK listing alone
        // and gain the markers later. It is a no-op on a backend that cannot report outdated
        // versions, so there is nothing to guard here.
        ctx.actions().ensureOutdated();
        int total = ctx.state().sdks().size();
        List<SdkVersion> items = visibleItems();
        boolean wide = ctx.state().toolsFullScreen();

        String query = filter.query();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            tableState.select(0);
        }
        int index = selectedIndex(items.size());
        tableState.select(items.isEmpty() ? 0 : index);

        List<Row> rows = new ArrayList<>();
        for (SdkVersion sdk : items) {
            rows.add(toolRow(sdk, wide));
        }

        TableElement tableElement = table()
                .rows(rows)
                .state(tableState)
                .highlightColor(ctx.theme().accent())
                .highlightSymbol("> ")
                .columnSpacing(1);
        if (items.isEmpty()) {
            // The empty state is sentences, not columns: give them the whole width.
            tableElement.widths(fill());
        } else if (wide) {
            tableElement.header("  STATUS  TOOL", "CURRENT", "LATEST", "WHERE", "")
                    .widths(fill(3), length(14), length(16), fill(2), fill(3));
        } else {
            tableElement.widths(fill(3), length(14), fill(2));
        }

        if (items.isEmpty()) {
            for (Line line : emptyLines()) {
                tableElement.row(Row.from(Cell.from(line)));
            }
        }

        Element body = filter.isActive()
                ? column(filter.inputRow(ctx).constraint(length(1)), tableElement.constraint(fill()))
                : column(tableElement.constraint(fill()));

        Panel block = panel(SidePanels.title(SidePanels.Side.TOOLS, titleDetail(total, items.size())), body)
                .rounded()
                .id(PanelIds.TOOLS).focusable(ctx.noModalOpen())
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus())
                .onKeyEvent(event -> handleKey(event, items));

        String position = SidePanels.positionLabel(index, items.size());
        if (PanelIds.TOOLS.equals(ctx.focusedId()) && !position.isEmpty()) {
            block.bottomTitle(position).bottomTitleAlignment(Alignment.RIGHT);
        } else if (!items.isEmpty()) {
            // Unfocused, the bottom border has nothing better to say than what the glyphs mean.
            block.bottomTitle(Line.styled(ToolStatus.LEGEND, Style.create().fg(Color.DARK_GRAY)));
        }
        return block;
    }

    /**
     * What rides in the title after the name: the count — {@code 12}, or {@code 3/12} while
     * narrowed — then how many rows are marked, then whether {@code o} is hiding up-to-date ones.
     */
    private String titleDetail(int total, int shown) {
        if (total == 0) {
            return "";
        }
        boolean narrowed = filter.isActive() || ctx.state().outdatedOnly();
        StringBuilder detail = new StringBuilder("· ").append(narrowed ? shown + "/" + total : total);
        int marked = ctx.state().marked().size();
        if (marked > 0) {
            detail.append(" · ").append(marked).append(" marked");
        }
        if (ctx.state().outdatedOnly()) {
            detail.append(" · outdated only");
        }
        return detail.toString();
    }

    /**
     * The empty state names the key that fixes it — a blank panel teaches nothing, and the first
     * thing a new project needs is exactly what this says to press.
     */
    private List<Line> emptyLines() {
        Style dim = Style.create().fg(Color.DARK_GRAY);
        Style key = Style.create().fg(Color.CYAN).bold();
        if (filter.isActive()) {
            return List.of(Line.styled("No tools match \"" + filter.query() + "\"", dim));
        }
        if (ctx.state().outdatedOnly()) {
            return List.of(Line.styled("Everything is up to date", dim),
                    Line.from(Span.styled("o ", key), Span.styled("show all tools", dim)));
        }
        if (!ctx.state().sdksLazy().everLoaded()) {
            return List.of(Line.styled("Loading…", dim));
        }
        List<Line> lines = new ArrayList<>();
        lines.add(Line.styled("No tools in this project yet", Style.create().bold()));
        lines.add(Line.empty());
        lines.add(Line.from(Span.styled("a       ", key), Span.raw("add an SDK")));
        if (ctx.supports(BackendFeature.INSTALL_ALL)) {
            lines.add(Line.from(Span.styled("Space i ", key), Span.raw("install everything in config")));
        }
        lines.add(Line.from(Span.styled("Ctrl+P  ", key), Span.raw("browse all commands")));
        return lines;
    }

    /**
     * One table row. The status column carries the live progress bar or streamed line while an
     * operation runs, which is what makes an install visible without opening the command log.
     * Offline, everything but the glyph is dimmed: the data is the last known state, still worth
     * reading, and the dimming is how it admits it might be out of date.
     */
    private Row toolRow(SdkVersion sdk, boolean wide) {
        boolean busy = isBusy(sdk);
        ToolStatus status = ToolStatus.of(sdk, busy);
        boolean offline = ctx.state().offline();
        String latest = ctx.state().outdated().get(sdk.name());
        boolean marked = ctx.state().isMarked(sdk.name());

        Style nameStyle = offline ? Style.create().fg(Color.DARK_GRAY)
                : sdk.active() ? Style.create().bold() : Style.create();
        Line name = Line.from(
                Span.styled(marked ? "* " : "  ", Style.create().fg(Color.YELLOW).bold()),
                Span.styled(status.glyph() + " ", Style.create().fg(status.color())),
                Span.styled(sdk.name(), nameStyle));

        Line version = Line.from(versionText(sdk));

        Cell statusCell = Cell.from(statusText(sdk, status, latest, busy, wide))
                .style(Style.create().fg(statusColor(status, latest, offline)));

        if (!wide) {
            return Row.from(Cell.from(name), Cell.from(version), statusCell);
        }
        Cell latestCell = latest == null
                ? Cell.from(versionText(sdk)).style(Style.create().fg(Color.DARK_GRAY))
                : Cell.from(latest + " ↑").style(Style.create().fg(Color.YELLOW));
        return Row.from(Cell.from(name), Cell.from(version), latestCell,
                Cell.from(where(sdk)).style(Style.create().fg(Color.DARK_GRAY)), statusCell);
    }

    private static String versionText(SdkVersion sdk) {
        return sdk.hasVersion() ? sdk.version() : "-";
    }

    private String statusText(SdkVersion sdk, ToolStatus status, @Nullable String latest, boolean busy, boolean wide) {
        if (busy) {
            return busyText(sdk);
        }
        return switch (status) {
            case MISSING -> "missing";
            case NO_VERSION -> "no version";
            default -> latest != null && !wide ? "↑ " + latest : "";
        };
    }

    private static Color statusColor(ToolStatus status, @Nullable String latest, boolean offline) {
        if (offline) {
            return Color.DARK_GRAY;
        }
        return switch (status) {
            case BUSY -> Color.CYAN;
            case MISSING -> Color.RED;
            case NO_VERSION -> Color.DARK_GRAY;
            case INSTALLED -> latest != null ? Color.YELLOW : Color.GREEN;
        };
    }

    /** Which config pinned the row, in the few characters the wide table has for it. */
    private String where(SdkVersion sdk) {
        if (!sdk.pinned()) {
            return "";
        }
        String path = sdk.sourcePath() == null ? "" : sdk.sourcePath().replace('\\', '/');
        if (path.contains("/.config/mise/") || "global".equalsIgnoreCase(sdk.sourceType())) {
            return "global";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private boolean isBusy(SdkVersion sdk) {
        return ctx.state().isBusy(sdk.label()) || ctx.state().isBusy("upgrade:" + sdk.name())
                || ctx.state().isBusy("registry:" + sdk.name())
                || ctx.state().isBusy("remove-plugin:" + sdk.name())
                // Upgrade-all is one busy key for every tool, so only the rows it is actually
                // working on — the ones it has reported progress for — count as busy.
                || ctx.state().progressFor(BackendActions.UPGRADE_ALL_KEY, sdk.name()) != null;
    }

    /**
     * The progress animation for whichever operation this SDK is busy with, else its latest
     * streamed line, else "working…".
     */
    private String busyText(SdkVersion sdk) {
        ProgressSnapshot progress = progressFor(ctx, sdk);
        if (progress != null) {
            return ProgressBar.inline(progress);
        }
        for (String key : progressKeys(sdk)) {
            Job job = ctx.actions().jobs().activeFor(key);
            if (job != null && job.state() == Job.State.QUEUED) {
                return "queued · waits for a free slot";
            }
        }
        for (String key : progressKeys(sdk)) {
            String status = ctx.state().busyStatusFor(key);
            if (status != null && !status.isBlank()) {
                return status;
            }
        }
        return "working…";
    }

    /** The busy keys under which an install of {@code sdk} can be running, most specific first. */
    private static String[] progressKeys(SdkVersion sdk) {
        return new String[]{sdk.label(), "upgrade:" + sdk.name(), "registry:" + sdk.name()};
    }

    /** Download progress of whichever install of {@code sdk} is running, or null. Shared with the detail pane. */
    static @Nullable ProgressSnapshot progressFor(UiContext ctx, SdkVersion sdk) {
        for (String key : progressKeys(sdk)) {
            ProgressSnapshot progress = ctx.state().progressFor(key);
            if (progress != null) {
                return progress;
            }
        }
        return ctx.state().progressFor(BackendActions.UPGRADE_ALL_KEY, sdk.name());
    }

    /**
     * What a batch key acts on: every marked tool when anything is marked — one row per tool, the
     * first the list shows — otherwise just the selected row. Marks win over the cursor because
     * marking is a deliberate act and the cursor merely happens to be somewhere.
     */
    private List<SdkVersion> targets(SdkVersion selected) {
        List<String> marked = ctx.state().marked();
        if (marked.isEmpty()) {
            return List.of(selected);
        }
        Map<String, SdkVersion> byName = new LinkedHashMap<>();
        for (SdkVersion sdk : ctx.state().sdks()) {
            byName.putIfAbsent(sdk.name(), sdk);
        }
        return marked.stream().map(byName::get).filter(Objects::nonNull).toList();
    }

    private EventResult handleKey(KeyEvent event, List<SdkVersion> items) {
        if (event.hasCtrl() && event.isCharIgnoreCase('a')) {
            markAllOutdated();
            return EventResult.HANDLED;
        }
        if (event.hasCtrl()) {
            return EventResult.UNHANDLED; // Ctrl+P, Ctrl+O and friends are global
        }
        if (Ui.applyTableNav(event, tableState, items.size())) {
            return EventResult.HANDLED;
        }
        if (event.isChar('/')) {
            filter.activate(ctx);
            return EventResult.HANDLED;
        }
        if (event.isCancel()) {
            if (filter.isActive()) {
                filter.clear(ctx);
                return EventResult.HANDLED;
            }
            if (!ctx.state().marked().isEmpty()) {
                ctx.state().clearMarks();
                return EventResult.HANDLED;
            }
            if (ctx.state().toolsFullScreen()) {
                ctx.state().toolsFullScreen(false);
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        }
        if (event.isChar('o')) {
            ctx.state().outdatedOnly(!ctx.state().outdatedOnly());
            tableState.select(0);
            return EventResult.HANDLED;
        }
        if (event.isChar('+')) {
            ctx.state().toolsFullScreen(!ctx.state().toolsFullScreen());
            return EventResult.HANDLED;
        }
        if (items.isEmpty()) {
            return EventResult.UNHANDLED;
        }
        SdkVersion sdk = items.get(selectedIndex(items.size()));
        if (event.isConfirm() || event.code() == KeyCode.RIGHT) {
            // Into the Versions column. Full screen has no column to move into, so leave it first.
            ctx.state().toolsFullScreen(false);
            ctx.focus(PanelIds.VERSIONS);
            return EventResult.HANDLED;
        }
        if (event.isChar('m')) {
            ctx.state().toggleMark(sdk.name());
            Ui.applyTableNav(KeyEvent.ofKey(KeyCode.DOWN), tableState, items.size());
            return EventResult.HANDLED;
        }
        if (event.isChar('i')) {
            install(sdk);
            return EventResult.HANDLED;
        }
        if (event.isChar('u') || event.isChar('g')) {
            use(sdk, event.isChar('g'));
            return EventResult.HANDLED;
        }
        if (event.isChar('U')) {
            upgrade(sdk);
            return EventResult.HANDLED;
        }
        if (event.isChar('x') || event.code() == KeyCode.DELETE) {
            uninstall(sdk);
            return EventResult.HANDLED;
        }
        if (event.isChar('R')) {
            if (sdk.pinned()) {
                ctx.confirm("Remove " + sdk.label() + " from " + ctx.backend().projectConfigFileName() + "?",
                        () -> ctx.actions().removeSdk(sdk));
            } else {
                ctx.state().addLog(LogLevel.INFO, sdk.label() + " is not pinned in any config");
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('d')) {
            // How destructive this is differs per backend, so the warning is the backend's own
            // sentence rather than a branch on which one is active.
            ctx.confirm(ctx.backend().removePluginWarning(sdk.name()),
                    () -> ctx.actions().removePlugin(sdk));
            return EventResult.HANDLED;
        }
        if (event.isChar('y')) {
            String path = sdk.installPath();
            if (path == null || path.isBlank()) {
                ctx.state().addLog(LogLevel.INFO, sdk.label() + " has no install path to copy");
            } else {
                Clipboard.copy(path);
                ctx.state().addLog(LogLevel.OK, "Copied " + path);
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('c')) {
            ctx.actions().cancelSdk(sdk);
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    // ==================== Actions on the selection ====================
    // Public so the command palette, the menu bar and the F-key bar run exactly what the keys
    // here run, rather than a second copy of each rule.

    /** {@code i}: installs the selected row's version. */
    public void installSelected() {
        withSelection(this::install);
    }

    /** {@code u} / {@code g}: pins the selected row's version in the project, or globally. */
    public void useSelected(boolean global) {
        withSelection(sdk -> use(sdk, global));
    }

    /** {@code U}: upgrades the marked tools, or the selected one when nothing is marked. */
    public void upgradeSelected() {
        withSelection(this::upgrade);
    }

    /** {@code x}: uninstalls the marked tools, or the selected one when nothing is marked. */
    public void uninstallSelected() {
        withSelection(this::uninstall);
    }

    private void withSelection(Consumer<SdkVersion> action) {
        SdkVersion sdk = selected();
        if (sdk == null) {
            ctx.state().addLog(LogLevel.INFO, "No tool selected — press 2 for the Tools panel");
        } else {
            action.accept(sdk);
        }
    }

    private void install(SdkVersion sdk) {
        // An SDK registered with no version installed has no name@version to install, so
        // "install" means "pick a version" — the catalog's version step does exactly that.
        if (sdk.hasVersion()) {
            ctx.actions().installSdk(sdk);
        } else {
            ctx.promptVersionFor(sdk.name());
        }
    }

    private void use(SdkVersion sdk, boolean global) {
        if (sdk.hasVersion()) {
            ctx.actions().useSdk(sdk.label(), global);
        } else {
            ctx.state().addLog(LogLevel.INFO,
                    "No version of " + sdk.name() + " installed yet — press Enter to pick one");
        }
    }

    private void upgrade(SdkVersion sdk) {
        if (ctx.supports(BackendFeature.UPGRADE)) {
            ctx.requestUpgrade(targets(sdk));
        } else {
            ctx.state().addLog(LogLevel.INFO, ctx.backend().unsupportedReason(BackendFeature.UPGRADE));
        }
    }

    private void uninstall(SdkVersion sdk) {
        List<SdkVersion> installed = targets(sdk).stream().filter(SdkVersion::installed).toList();
        if (installed.isEmpty()) {
            ctx.state().addLog(LogLevel.INFO, sdk.label() + " is not installed — nothing to uninstall");
        } else {
            ctx.requestUninstall(installed);
        }
    }

    /** Ctrl+A: marks every tool with a newer version available — the usual batch, in one key. */
    public void markAllOutdated() {
        Map<String, String> outdated = ctx.state().outdated();
        int count = 0;
        for (SdkVersion sdk : ctx.state().sdks()) {
            if (outdated.containsKey(sdk.name()) && !ctx.state().isMarked(sdk.name())) {
                ctx.state().mark(sdk.name());
                count++;
            }
        }
        ctx.state().addLog(LogLevel.INFO, count == 0
                ? (ctx.state().outdatedLazy().everLoaded() ? "Nothing is outdated" : "Still checking for updates…")
                : "Marked " + count + " outdated tool(s) — U upgrades them");
    }
}
