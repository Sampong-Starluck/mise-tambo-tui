package com.sampong.tambo.tui.panel;

import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.tui.PanelIds;
import com.sampong.tambo.tui.Ui;
import com.sampong.tambo.tui.UiContext;

/** Panel 3 — the environment variables mise would export in this directory. */
public final class EnvPanel {

    private final UiContext ctx;
    private int index;

    public EnvPanel(UiContext ctx) {
        this.ctx = ctx;
    }

    public ListElement<?> build() {
        List<Map.Entry<String, String>> entries = new ArrayList<>(ctx.state().env().entrySet());
        index = Ui.clamp(index, entries.size());

        ListElement<?> list = list()
                .title("3 Env (" + entries.size() + ")")
                .rounded().id(PanelIds.ENV).focusable(ctx.modalOpen())
                .borderColor(PanelIds.ENV.equals(ctx.focusedId()) ? Color.GREEN : Color.DARK_GRAY)
                .highlightColor(Color.CYAN)
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .selected(index)
                .onKeyEvent(event -> {
                    if (Ui.isNavKey(event)) {
                        index = Ui.applyNav(event, index, entries.size());
                        return EventResult.HANDLED;
                    }
                    return EventResult.UNHANDLED;
                });

        if (entries.isEmpty()) {
            list.add(row(text(ctx.state().loading() ? "Loading…" : "No environment variables active").dim()));
        } else {
            for (Map.Entry<String, String> e : entries) {
                list.add(row(text(e.getKey() + "=").fg(Color.YELLOW), text(Ui.truncate(e.getValue(), 60)).dim()));
            }
        }
        return list;
    }
}
