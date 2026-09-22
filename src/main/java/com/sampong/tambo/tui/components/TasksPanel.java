package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.table;

import java.util.ArrayList;
import java.util.List;

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

import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo.tui.features.PanelFilter;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Panel 4 — the project's tasks; Enter runs the selected one.
 * <p>
 * Name and run state only. A task's description, dependencies, source file and the command it
 * actually runs are all in {@link DetailPanel} one pane to the right, where the script has room
 * to be read rather than truncated into a column.
 */
@RequiredArgsConstructor
public final class TasksPanel {

    /**
     * Shown beside a running task instead of its latest output line. Streaming the build's own
     * log here duplicated the command log, and a wide, fast-changing line made the whole row
     * jitter — this panel only needs to say "still going".
     */
    private static final String RUNNING_TEXT = "running…";

    @NonNull
    private final UiContext ctx;
    private final PanelFilter filter = new PanelFilter(PanelIds.TASKS_FILTER, PanelIds.TASKS);
    /** Selection and viewport offset — held here so they survive the per-frame element rebuild. */
    private final TableState tableState = new TableState();
    private String lastQuery = "";

    /** The tasks currently shown — the full list, or the fuzzy-filtered view. */
    private List<ProjectTask> visibleItems() {
        return filter.apply(ctx.state().tasks(), ProjectTask::name, ProjectTask::description);
    }

    /** The task the selection sits on, or null when the (filtered) list is empty. */
    public @Nullable ProjectTask selected() {
        List<ProjectTask> items = visibleItems();
        return items.isEmpty() ? null : items.get(selectedIndex(items.size()));
    }

    private int selectedIndex(int size) {
        Integer selected = tableState.selected();
        return Ui.clamp(selected == null ? 0 : selected, size);
    }

    public Panel build() {
        int total = ctx.state().tasks().size();
        List<ProjectTask> items = visibleItems();

        String query = filter.query();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            tableState.select(0);
        }
        int index = items.isEmpty() ? 0 : selectedIndex(items.size());
        tableState.select(index);

        List<Row> rows = new ArrayList<>();
        for (ProjectTask task : items) {
            boolean busy = ctx.state().isBusy("task:" + task.name());
            rows.add(Row.from(
                    Cell.from((busy ? Ui.spinner() : "▷") + " " + task.name())
                            .style(Style.create().fg(busy ? Color.YELLOW : Color.GREEN)),
                    Cell.from(busy ? RUNNING_TEXT : Ui.nullToDash(task.description()))
                            .style(Style.create().fg(busy ? Color.YELLOW : Color.DARK_GRAY))
            ));
        }

        TableElement tableElement = table()
                .widths(fill(2), fill(3))
                .rows(rows)
                .state(tableState)
                .highlightColor(ctx.theme().accent())
                .highlightSymbol("> ")
                .columnSpacing(1);

        if (items.isEmpty()) {
            tableElement.row(Row.from(Cell.from(emptyText()).style(Style.create().gray())));
        }

        Element body = filter.isActive()
                ? column(filter.inputRow(ctx).constraint(length(1)), tableElement.constraint(fill()))
                : column(tableElement.constraint(fill()));

        Panel block = panel(SidePanels.title(SidePanels.Side.TASKS, countLabel(total, items.size())), body)
                .rounded()
                .id(PanelIds.TASKS).focusable(ctx.modalOpen())
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus())
                .onKeyEvent(event -> handleKey(event, items));

        String position = SidePanels.positionLabel(index, items.size());
        if (!position.isEmpty() && PanelIds.TASKS.equals(ctx.focusedId())) {
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
            return "No tasks match \"" + filter.query() + "\"";
        }
        return ctx.state().tasksLazy().everLoaded()
                ? "No tasks defined in this project"
                : "Loading…";
    }

    private EventResult handleKey(KeyEvent event, List<ProjectTask> items) {
        if (Ui.applyTableNav(event, tableState, items.size())) {
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
        ProjectTask task = items.get(selectedIndex(items.size()));
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
