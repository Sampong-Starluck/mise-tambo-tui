package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.tui.features.PanelFilter;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Panel 4 — the project's mise tasks; Enter runs the selected one. */
@RequiredArgsConstructor
public final class TasksPanel {

    @NonNull
    private final UiContext ctx;
    private final PanelFilter filter = new PanelFilter(PanelIds.TASKS_FILTER, PanelIds.TASKS);
    private int index;
    private String lastQuery = "";

    /** The tasks currently shown — the full list, or the fuzzy-filtered view. */
    private List<MiseTask> visibleItems() {
        return filter.apply(ctx.state().tasks(), MiseTask::name, MiseTask::description);
    }

    /** The task the selection sits on, or null when the (filtered) list is empty. */
    public @Nullable MiseTask selected() {
        List<MiseTask> items = visibleItems();
        return items.isEmpty() ? null : items.get(Ui.clamp(index, items.size()));
    }

    public Column build() {
        int total = ctx.state().tasks().size();
        List<MiseTask> items = visibleItems();

        String query = filter.query();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            index = 0;
        }
        index = Ui.clamp(index, items.size());

        ListElement<?> list = list()
                .title(title(total, items.size()))
                .rounded().id(PanelIds.TASKS).focusable(ctx.modalOpen())
                .borderColor(PanelIds.TASKS.equals(ctx.focusedId()) ? ctx.theme().focus() : ctx.theme().idle())
                .highlightColor(ctx.theme().accent())
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .autoScroll()
                .selected(index)
                .onKeyEvent(event -> handleKey(event, items));

        if (items.isEmpty()) {
            list.add(row(text(emptyText()).dim()));
        } else {
            for (MiseTask t : items) {
                boolean busy = ctx.state().isBusy("task:" + t.name());
                String trailing = busy ? busyText(t.name())
                        : t.description() == null ? "" : Ui.truncate(t.description(), 28);
                list.add(row(
                        text((busy ? Ui.spinner() : "▷") + " ").fg(busy ? Color.YELLOW : Color.GREEN),
                        text(t.name()).bold(),
                        spacer(),
                        text(trailing + " ").fg(busy ? Color.YELLOW : Color.DARK_GRAY).dim()
                ));
            }
        }

        if (filter.isActive()) {
            return column(filter.inputRow(ctx).constraint(length(1)), list.constraint(fill()));
        }
        return column(list.constraint(fill()));
    }

    /** The latest streamed line for a running task, else "running…". */
    private String busyText(String taskName) {
        String status = ctx.state().busyStatusFor("task:" + taskName);
        return status != null && !status.isBlank() ? Ui.truncate(status, 28) : "running…";
    }

    private String title(int total, int shown) {
        if (total == 0) {
            return "4 Tasks";
        }
        return filter.isActive() ? "4 Tasks (" + shown + "/" + total + ")" : "4 Tasks (" + total + ")";
    }

    private String emptyText() {
        if (filter.isActive()) {
            return "No tasks match \"" + filter.query() + "\"";
        }
        return ctx.state().loading() ? "Loading…" : "No tasks defined in this project";
    }

    private EventResult handleKey(KeyEvent event, List<MiseTask> items) {
        if (Ui.isNavKey(event)) {
            index = Ui.applyNav(event, index, items.size());
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
        if (event.isChar('.')) {
            ctx.actions().reRunLastTask();
            return EventResult.HANDLED;
        }
        if (items.isEmpty()) {
            return EventResult.UNHANDLED;
        }
        MiseTask task = items.get(Ui.clamp(index, items.size()));
        if (event.isConfirm()) {
            ctx.actions().runTask(task);
            return EventResult.HANDLED;
        }
        if (event.isChar(':')) {
            ctx.promptTaskArgs(task.name(), "");
            return EventResult.HANDLED;
        }
        if (event.isChar('c')) {
            ctx.actions().cancelTask(task.name());
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
