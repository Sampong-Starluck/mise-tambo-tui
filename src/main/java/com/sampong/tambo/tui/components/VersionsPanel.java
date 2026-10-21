package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.table;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo.tui.components.VersionRows.Kind;
import com.sampong.tambo.tui.components.VersionRows.VersionRow;
import com.sampong.tambo.tui.features.Clipboard;
import com.sampong.tambo.tui.features.PanelFilter;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * The Versions column between Tools and the detail pane: the versions of whichever tool is
 * selected in Tools, installed first and then what could be installed. It is the yazi-style
 * middle column — you drill from a tool into its versions, and act on one without leaving the
 * list you came from.
 * <p>
 * The release listing is a network call, so it is fetched once per tool on first view and kept
 * for the session. Until it arrives the installed versions are shown alone, and when the app is
 * offline it is never fetched at all: the column still answers "what do I have" from the local
 * state, and says so rather than sitting on a loading line forever.
 * <p>
 * The cursor moves over VERSION rows only, so the section labels are never the thing an action
 * acts on. Selection is held here rather than in the table element, for the same reason
 * {@link ToolsPanel} holds its own: a fresh element tree is built every frame.
 */
public final class VersionsPanel {

    /** The offline note that stands in for the loading line when nothing is cached and there is no network. */
    static final String OFFLINE_NOTE = "⚠ offline · showing installed versions only";
    private static final String NO_TOOL_TEXT = "Select a tool on the left";
    private static final String HINT =
            "↑↓ move  / filter  i install  u use here  g global  Esc back";

    @NonNull
    private final UiContext ctx;
    private final PanelFilter filter = new PanelFilter(PanelIds.VERSIONS_FILTER, PanelIds.VERSIONS);
    /** Release listings by tool, kept for the session — a tool's available versions do not change while the app is open. */
    private final Map<String, List<SdkRelease>> releases = new HashMap<>();
    /** Tools whose listing is in flight, so a render does not start the same {@code ls-remote} twice. */
    private final Set<String> fetching = new HashSet<>();
    /**
     * Selection and viewport offset, held here rather than in the element: a fresh element tree
     * is built every frame, so anything the widget tracked internally would be discarded before
     * the next frame showed it.
     */
    private final TableState tableState = new TableState();
    /** Position among the selectable rows, not among all rows, so it never rests on a label. */
    private int cursor;
    private @Nullable String tool;
    private String lastQuery = "";

    public VersionsPanel(@NonNull UiContext ctx) {
        this.ctx = ctx;
    }

    /** The tool whose versions are showing, or null when no tool is selected in Tools. */
    public @Nullable String tool() {
        return tool;
    }

    /** The row the cursor sits on, or null when there is none to act on. */
    public @Nullable VersionRow selected() {
        if (tool == null) {
            return null;
        }
        List<VersionRow> rows = displayRows(tool);
        List<Integer> selectable = VersionRows.selectable(rows);
        return selectable.isEmpty() ? null : rows.get(selectable.get(Ui.clamp(cursor, selectable.size())));
    }

    /** The key hint for the bottom line while this panel holds focus. */
    public String footerHint() {
        return HINT;
    }

    public Panel build(@Nullable String tool) {
        if (!Objects.equals(tool, this.tool)) {
            this.tool = tool;
            cursor = 0;
        }
        String query = filter.query();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            cursor = 0;
        }
        if (tool != null) {
            ensureReleases(tool);
        }

        List<VersionRow> rows = tool == null ? List.of() : displayRows(tool);
        List<Integer> selectable = VersionRows.selectable(rows);
        cursor = Ui.clamp(cursor, selectable.size());
        tableState.select(selectable.isEmpty() ? 0 : selectable.get(cursor));

        Element body = tool == null
                ? column(text(NO_TOOL_TEXT).dim())
                : tableBody(rows);

        Panel block = panel(title(tool), body)
                .rounded()
                .id(PanelIds.VERSIONS).focusable(ctx.noModalOpen())
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus())
                .onKeyEvent(event -> handleKey(event, tool, rows));

        String position = SidePanels.positionLabel(cursor, selectable.size());
        if (!position.isEmpty() && PanelIds.VERSIONS.equals(ctx.focusedId())) {
            block.bottomTitle(position).bottomTitleAlignment(Alignment.RIGHT);
        }
        return block;
    }

    private static String title(@Nullable String tool) {
        return tool == null ? " Versions " : " Versions · " + tool + " ";
    }

    private Element tableBody(List<VersionRow> rows) {
        List<Row> tableRows = new ArrayList<>();
        for (VersionRow row : rows) {
            tableRows.add(rowFor(row));
        }
        TableElement table = table()
                .widths(fill(1), length(9))
                .rows(tableRows)
                .state(tableState)
                .highlightColor(ctx.theme().accent())
                .highlightSymbol("> ")
                .columnSpacing(1);
        if (rows.isEmpty()) {
            table.row(Row.from(Cell.from(emptyText()).style(Style.create().dim())));
        }
        return filter.isActive()
                ? column(filter.inputRow(ctx).constraint(length(1)), table.constraint(fill()))
                : column(table.constraint(fill()));
    }

    private String emptyText() {
        return "No versions match \"" + filter.query() + "\"";
    }

    /**
     * The rows on screen for {@code tool}. While filtering, the matching versions are shown
     * ranked by the fuzzy score and their section labels are dropped: the ranking is the point,
     * and a label between two matches would only split it.
     */
    private List<VersionRow> displayRows(String tool) {
        List<SdkRelease> cached = releases.get(tool);
        List<VersionRow> rows = VersionRows.build(tool, ctx.state().sdks(), cached,
                ctx.state().outdated().get(tool));
        if (ctx.state().offline() && cached == null) {
            rows = rows.stream()
                    .map(row -> row.kind() == Kind.NOTE && VersionRows.LOADING.equals(row.text())
                            ? VersionRows.note(OFFLINE_NOTE)
                            : row)
                    .toList();
        }
        if (!filter.isActive()) {
            return rows;
        }
        List<VersionRow> versions = rows.stream().filter(row -> row.kind() == Kind.VERSION).toList();
        List<VersionRow> matches = filter.apply(versions, VersionRow::version, null);
        return matches.isEmpty() ? List.of(VersionRows.note(emptyText())) : matches;
    }

    /**
     * Starts the release listing for {@code tool} if nothing has asked for it yet. Offline it is
     * skipped entirely — {@code ls-remote} would only fail, and the installed list does not need it.
     */
    private void ensureReleases(String tool) {
        if (releases.containsKey(tool) || fetching.contains(tool) || ctx.state().offline()) {
            return;
        }
        fetching.add(tool);
        ctx.actions().fetchReleases(tool, list -> {
            releases.put(tool, list);
            fetching.remove(tool);
        });
    }

    /**
     * One table row. Labels are dim, notes take their colour from whether they are a warning,
     * and a version carries a marker for its state: {@code ●} active, {@code ○} installed,
     * {@code ↑} newer than the active one. The tag sits in its own column so the versions line up.
     */
    private Row rowFor(VersionRow row) {
        return switch (row.kind()) {
            case HEADER -> Row.from(Cell.from(row.text()).style(Style.create().dim()));
            case NOTE -> Row.from(Cell.from(row.text()).style(noteStyle(row.text())));
            case VERSION -> versionRow(row);
        };
    }

    private Row versionRow(VersionRow row) {
        String marker;
        Color markerColor;
        if (VersionRows.MISSING.equals(row.tag())) {
            marker = "✗ ";
            markerColor = Color.RED;
        } else if (row.active()) {
            marker = "● ";
            markerColor = Color.GREEN;
        } else if (row.installed()) {
            marker = "○ ";
            markerColor = Color.DARK_GRAY;
        } else if (row.newer()) {
            marker = "↑ ";
            markerColor = Color.YELLOW;
        } else {
            marker = "  ";
            markerColor = null;
        }
        Span markerSpan = markerColor == null
                ? Span.raw(marker)
                : Span.styled(marker, Style.create().fg(markerColor));
        Span versionSpan = row.active()
                ? Span.styled(row.text(), Style.create().fg(Color.GREEN).bold())
                : Span.raw(row.text());
        Style tagStyle = "latest".equals(row.tag())
                ? Style.create().fg(Color.YELLOW)
                : Style.create().dim();
        return Row.from(
                Cell.from(Line.from(markerSpan, versionSpan)),
                Cell.from(row.tag()).style(tagStyle)
        );
    }

    private static Style noteStyle(String text) {
        return text.startsWith("⚠")
                ? Style.create().fg(Color.YELLOW)
                : Style.create().dim();
    }

    private EventResult handleKey(KeyEvent event, @Nullable String tool, List<VersionRow> rows) {
        List<Integer> selectable = VersionRows.selectable(rows);
        if (Ui.isNavKey(event)) {
            cursor = Ui.applyNav(event, Ui.clamp(cursor, selectable.size()), selectable.size());
            return EventResult.HANDLED;
        }
        if (event.isChar('/') && tool != null) {
            filter.activate(ctx);
            return EventResult.HANDLED;
        }
        if (event.isCancel()) {
            if (filter.isActive()) {
                filter.clear(ctx);
            } else {
                ctx.focus(PanelIds.TOOLS);
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('h') || event.code() == KeyCode.LEFT) {
            ctx.focus(PanelIds.TOOLS);
            return EventResult.HANDLED;
        }
        if (selectable.isEmpty()) {
            return EventResult.UNHANDLED;
        }
        VersionRow row = rows.get(selectable.get(Ui.clamp(cursor, selectable.size())));
        if (tool == null) {
            return EventResult.UNHANDLED;
        }
        String label = tool + "@" + row.version();
        if (event.isChar('i')) {
            if (row.installed()) {
                ctx.state().addLog(LogLevel.INFO, label + " is already installed");
            } else {
                ctx.actions().install(label);
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('u')) {
            ctx.actions().useSdk(label, false);
            return EventResult.HANDLED;
        }
        if (event.isChar('g')) {
            ctx.actions().useSdk(label, true);
            return EventResult.HANDLED;
        }
        if (event.isChar('y')) {
            Clipboard.copy(label);
            ctx.state().addLog(LogLevel.OK, "Copied " + label + " to clipboard");
            return EventResult.HANDLED;
        }
        // Enter is the lead's to route (to the detail pane), so it is passed on untouched.
        return EventResult.UNHANDLED;
    }
}
