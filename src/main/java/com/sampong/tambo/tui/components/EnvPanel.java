package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.tui.features.Clipboard;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.features.PanelFilter;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Panel 3 — the environment variables mise would export in this directory. */
@RequiredArgsConstructor
public final class EnvPanel {

    @NonNull
    private final UiContext ctx;
    private final PanelFilter filter = new PanelFilter(PanelIds.ENV_FILTER, PanelIds.ENV);
    private int index;
    private String lastQuery = "";

    /** The env entries currently shown — all of them, or the fuzzy-filtered view. */
    private List<Map.Entry<String, String>> visibleItems() {
        List<Map.Entry<String, String>> all = new ArrayList<>(ctx.state().env().entrySet());
        return filter.apply(all, Map.Entry::getKey, Map.Entry::getValue);
    }

    public Column build() {
        int total = ctx.state().env().size();
        List<Map.Entry<String, String>> entries = visibleItems();

        String query = filter.query();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            index = 0;
        }
        index = Ui.clamp(index, entries.size());

        ListElement<?> list = list()
                .title(title(total, entries.size()))
                .rounded().id(PanelIds.ENV).focusable(ctx.modalOpen())
                .borderColor(PanelIds.ENV.equals(ctx.focusedId()) ? ctx.theme().focus() : ctx.theme().idle())
                .highlightColor(ctx.theme().accent())
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .autoScroll()
                .selected(index)
                .onKeyEvent(event -> handleKey(event, entries));

        if (entries.isEmpty()) {
            list.add(row(text(emptyText()).dim()));
        } else {
            for (Map.Entry<String, String> e : entries) {
                list.add(row(text(e.getKey() + "=").fg(Color.YELLOW), text(Ui.truncate(e.getValue(), 60)).dim()));
            }
        }

        if (filter.isActive()) {
            return column(filter.inputRow(ctx).constraint(length(1)), list.constraint(fill()));
        }
        return column(list.constraint(fill()));
    }

    private String title(int total, int shown) {
        return filter.isActive() ? "3 Env (" + shown + "/" + total + ")" : "3 Env (" + total + ")";
    }

    private String emptyText() {
        if (filter.isActive()) {
            return "No env vars match \"" + filter.query() + "\"";
        }
        return ctx.state().loading() ? "Loading…" : "No environment variables active";
    }

    private EventResult handleKey(KeyEvent event, List<Map.Entry<String, String>> entries) {
        if (Ui.isNavKey(event)) {
            index = Ui.applyNav(event, index, entries.size());
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
            Map.Entry<String, String> e = entries.get(Ui.clamp(index, entries.size()));
            Clipboard.copy(e.getValue());
            ctx.state().addLog(LogLevel.OK, "Copied " + e.getKey() + " value to clipboard");
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
