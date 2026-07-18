package com.sampong.tambo.tui.panel;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;

import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.tui.PanelIds;
import com.sampong.tambo.tui.Ui;
import com.sampong.tambo.tui.UiContext;

/**
 * The main view: contextual detail for whatever is selected in the focused
 * sidebar panel (lazygit's right-hand pane).
 */
public final class DetailPanel {

    private final UiContext ctx;
    private final ToolsPanel toolsPanel;
    private final TasksPanel tasksPanel;

    public DetailPanel(UiContext ctx, ToolsPanel toolsPanel, TasksPanel tasksPanel) {
        this.ctx = ctx;
        this.toolsPanel = toolsPanel;
        this.tasksPanel = tasksPanel;
    }

    public Panel build() {
        String focus = ctx.focusedId();
        List<Element> lines = new ArrayList<>();

        ToolVersion tool = toolsPanel.selected();
        MiseTask task = tasksPanel.selected();

        if (PanelIds.TOOLS.equals(focus) && tool != null) {
            addToolDetail(lines, tool);
        } else if (PanelIds.TASKS.equals(focus) && task != null) {
            addTaskDetail(lines, task);
        } else if (PanelIds.ENV.equals(focus)) {
            lines.add(text("Environment variables mise would export in this directory.").bold());
            lines.add(text(""));
            lines.add(row(text("Variables ").dim(), text(String.valueOf(ctx.state().env().size()))));
        } else {
            addWelcome(lines);
        }

        return panel("Details", column(lines.toArray(new Element[0])))
                .rounded().borderColor(Color.DARK_GRAY);
    }

    private void addToolDetail(List<Element> lines, ToolVersion t) {
        lines.add(row(text("Tool         ").dim(), text(t.tool()).bold().cyan()));
        lines.add(row(text("Version      ").dim(), text(t.version())));
        lines.add(row(text("Requested    ").dim(), text(Ui.nullToDash(t.requestedVersion()))));
        lines.add(row(text("Installed    ").dim(), Ui.badge(t.installed())));
        lines.add(row(text("Active       ").dim(), Ui.badge(t.active())));
        lines.add(row(text("Source       ").dim(), text(Ui.nullToDash(t.sourceType())).dim()));
        lines.add(row(text("Install path ").dim(), text(Ui.nullToDash(t.installPath())).dim()));
        lines.add(text(""));
        lines.add(text("[i] install   [u] use in project (mise.toml)   [x] uninstall   [g] set global").dim());
    }

    private void addTaskDetail(List<Element> lines, MiseTask t) {
        lines.add(row(text("Task        ").dim(), text(t.name()).bold().cyan()));
        lines.add(row(text("Description ").dim(), text(Ui.nullToDash(t.description()))));
        lines.add(row(text("Source      ").dim(), text(Ui.nullToDash(t.source())).dim()));
        lines.add(row(text("Aliases     ").dim(), text(t.aliasSummary())));
        lines.add(row(text("Depends on  ").dim(), text(t.dependsSummary())));
        lines.add(text(""));
        lines.add(row(text("Run         ").dim(), text(t.runSummary())));
        lines.add(text(""));
        lines.add(text("[Enter] run task").dim());
    }

    private void addWelcome(List<Element> lines) {
        lines.add(text("tambo").bold().cyan());
        lines.add(text("mise " + ctx.state().doctor().version()).dim());
        lines.add(text(""));
        lines.add(text("Select a panel (1-4 or Tab) to see details.").dim());
        lines.add(text("Press a to fuzzy-find and install an SDK from the registry.").dim());
        lines.add(text("Press ? for the full key reference.").dim());
    }
}
