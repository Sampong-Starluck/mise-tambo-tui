package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.table;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.tamboui.layout.Alignment;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.TableElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.TableState;

import com.sampong.tambo.tui.features.Clipboard;
import com.sampong.tambo.tui.features.PanelFilter;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Panel 3 — the environment variables the backend would export in this directory.
 * <p>
 * The list carries the name and as much of the value as the sidebar can spare; the whole value,
 * wrapped, is one pane to the right in {@link DetailPanel}. That split is what finally makes a
 * {@code PATH} readable here — it is the one value in this app that is routinely longer than any
 * terminal is wide, and clipping it in a column, panning it sideways and wrapping it in a narrow
 * panel had all been tried and all been worse than simply giving it the big pane.
 */
@RequiredArgsConstructor
public final class EnvPanel {

    @NonNull
    private final UiContext ctx;
    private final PanelFilter filter = new PanelFilter(PanelIds.ENV_FILTER, PanelIds.ENV);
    /** Selection and viewport offset — held here so they survive the per-frame element rebuild. */
    private final TableState tableState = new TableState();
    private String lastQuery = "";

    private int selectedIndex(int size) {
        Integer selected = tableState.selected();
        return Ui.clamp(selected == null ? 0 : selected, size);
    }

    /** The env entries currently shown — all of them, or the fuzzy-filtered view. */
    private List<Map.Entry<String, String>> visibleItems() {
        List<Map.Entry<String, String>> all = new ArrayList<>(ctx.state().env().entrySet());
        return filter.apply(all, Map.Entry::getKey, Map.Entry::getValue);
    }

    /** The variable the selection sits on, or null when the (filtered) list is empty. */
    public Map.@Nullable Entry<String, String> selected() {
        List<Map.Entry<String, String>> entries = visibleItems();
        return entries.isEmpty() ? null : entries.get(selectedIndex(entries.size()));
    }

    public Panel build() {
        int total = ctx.state().env().size();
        List<Map.Entry<String, String>> entries = visibleItems();

        String query = filter.query();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            tableState.select(0);
        }
        int index = entries.isEmpty() ? 0 : selectedIndex(entries.size());
        tableState.select(index);

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : entries) {
            rows.add(Row.from(
                    Cell.from(e.getKey()).style(Style.create().fg(Color.YELLOW)),
                    Cell.from(e.getValue()).style(Style.create().gray())
            ));
        }

        TableElement tableElement = table()
                .widths(fill(2), fill(3))
                .rows(rows)
                .state(tableState)
                .highlightColor(ctx.theme().accent())
                .highlightSymbol("> ")
                .columnSpacing(1);

        if (entries.isEmpty()) {
            tableElement.row(Row.from(Cell.from(emptyText()).style(Style.create().gray())));
        }

        Element body = filter.isActive()
                ? column(filter.inputRow(ctx).constraint(length(1)), tableElement.constraint(fill()))
                : column(tableElement.constraint(fill()));

        Panel block = panel(SidePanels.title(SidePanels.Side.ENV, countLabel(total, entries.size())), body)
                .rounded()
                .id(PanelIds.ENV).focusable(ctx.modalOpen())
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus())
                .onKeyEvent(event -> handleKey(event, entries));

        String position = SidePanels.positionLabel(index, entries.size());
        if (!position.isEmpty() && PanelIds.ENV.equals(ctx.focusedId())) {
            block.bottomTitle(position).bottomTitleAlignment(Alignment.RIGHT);
        }
        return block;
    }

    private String countLabel(int total, int shown) {
        if (total == 0) {
            return "";
        }
        return filter.isActive() ? "(" + shown + "/" + total + ")" : "(" + total + ")";
    }

    private String emptyText() {
        if (filter.isActive()) {
            return "No env vars match \"" + filter.query() + "\"";
        }
        return ctx.state().envLazy().everLoaded()
                ? "No environment variables active"
                : "Loading…";
    }

    private EventResult handleKey(KeyEvent event, List<Map.Entry<String, String>> entries) {
        if (Ui.applyTableNav(event, tableState, entries.size())) {
            return EventResult.HANDLED;
        }
        if (event.isChar('/')) {
            filter.activate(ctx);
            return EventResult.HANDLED;
        }
        if (event.isCancel() && filter.isActive()) {
            filter.clear(ctx);
            return EventResult.HANDLED;
        }
        if (event.isChar('y') && !entries.isEmpty()) {
            Map.Entry<String, String> e = entries.get(selectedIndex(entries.size()));
            Clipboard.copy(e.getValue());
            ctx.state().addLog(LogLevel.OK, "Copied " + e.getKey() + " value to clipboard");
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
