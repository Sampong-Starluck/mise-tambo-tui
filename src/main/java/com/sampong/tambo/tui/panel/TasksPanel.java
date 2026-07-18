package com.sampong.tambo.tui.panel;

import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.tui.PanelIds;
import com.sampong.tambo.tui.Ui;
import com.sampong.tambo.tui.UiContext;

/** Panel 4 — the project's mise tasks; Enter runs the selected one. */
public final class TasksPanel {

    private final UiContext ctx;
    private int index;

    public TasksPanel(UiContext ctx) {
        this.ctx = ctx;
    }

    /** The task the selection sits on, or null when the list is empty. */
    public MiseTask selected() {
        List<MiseTask> items = ctx.state().tasks();
        return items.isEmpty() ? null : items.get(Ui.clamp(index, items.size()));
    }

    public ListElement<?> build() {
        List<MiseTask> items = ctx.state().tasks();
        index = Ui.clamp(index, items.size());

        ListElement<?> list = list()
                .title(items.isEmpty() ? "4 Tasks" : "4 Tasks (" + items.size() + ")")
                .rounded().id(PanelIds.TASKS).focusable(ctx.modalOpen())
                .borderColor(PanelIds.TASKS.equals(ctx.focusedId()) ? Color.GREEN : Color.DARK_GRAY)
                .highlightColor(Color.CYAN)
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .selected(index)
                .onKeyEvent(event -> handleKey(event, items));

        if (items.isEmpty()) {
            list.add(row(text(ctx.state().loading() ? "Loading…" : "No tasks defined in this project").dim()));
        } else {
            for (MiseTask t : items) {
                boolean busy = ctx.state().isBusy("task:" + t.name());
                list.add(row(
                        text(busy ? "… " : "▷ ").fg(busy ? Color.YELLOW : Color.GREEN),
                        text(t.name()).bold(),
                        spacer(),
                        text(t.description() == null ? "" : Ui.truncate(t.description(), 28) + " ").dim()
                ));
            }
        }
        return list;
    }

    private EventResult handleKey(KeyEvent event, List<MiseTask> items) {
        if (Ui.isNavKey(event)) {
            index = Ui.applyNav(event, index, items.size());
            return EventResult.HANDLED;
        }
        if (items.isEmpty()) {
            return EventResult.UNHANDLED;
        }
        if (event.isConfirm()) {
            ctx.actions().runTask(items.get(Ui.clamp(index, items.size())));
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
