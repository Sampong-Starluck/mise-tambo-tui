package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.TextElement;

import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The main view: contextual detail for whatever is selected in the focused
 * sidebar panel (lazygit's right-hand pane).
 */
@RequiredArgsConstructor
public final class DetailPanel {

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final ToolsPanel toolsPanel;
    @NonNull
    private final TasksPanel tasksPanel;

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
            lines.add(row(text("Variables ").dim(), text(String.valueOf(ctx.state().env().size())).bold().cyan()));
        } else {
            addWelcome(lines);
        }

        return panel("Details", column(lines.toArray(new Element[0])))
                .rounded().borderColor(ctx.theme().idle());
    }

    private void addToolDetail(List<Element> lines, ToolVersion t) {
        Color statusColor = t.active() ? Color.CYAN : t.installed() ? Color.GREEN : Color.DARK_GRAY;
        boolean pendingChange = t.requestedVersion() != null && !t.requestedVersion().isBlank()
                && !t.requestedVersion().equals(t.version());
        TextElement requested = text(Ui.nullToDash(t.requestedVersion()));
        if (pendingChange) {
            requested = requested.bold().yellow();
        }

        lines.add(row(text("Tool         ").dim(), text(t.tool()).bold().cyan()));
        lines.add(row(text("Version      ").dim(), text(t.version()).fg(statusColor)));
        lines.add(row(text("Requested    ").dim(), requested));
        lines.add(row(text("Installed    ").dim(), Ui.badge(t.installed())));
        lines.add(row(text("Active       ").dim(), Ui.badge(t.active())));
        lines.add(row(text("Source       ").dim(), text(Ui.nullToDash(t.sourceType())).dim()));
        lines.add(row(text("Install path ").dim(), text(Ui.nullToDash(t.installPath())).dim()));
        lines.add(text(""));
        lines.add(row(
                Ui.keyHint("i", "install"), text("   "),
                Ui.keyHint("u", "use in project (mise.toml)"), text("   "),
                Ui.keyHint("x", "uninstall"), text("   "),
                Ui.keyHint("g", "set global")));
    }

    private void addTaskDetail(List<Element> lines, MiseTask t) {
        lines.add(row(text("Task        ").dim(), text(t.name()).bold().cyan()));
        lines.add(row(text("Description ").dim(), text(Ui.nullToDash(t.description()))));
        lines.add(row(text("Source      ").dim(), text(Ui.nullToDash(t.source())).dim()));
        lines.add(row(text("Aliases     ").dim(), text(t.aliasSummary())));
        lines.add(row(text("Depends on  ").dim(), text(t.dependsSummary())));
        lines.add(text(""));
        lines.add(row(text("Run         ").dim(), text(t.runSummary()).fg(Color.CYAN)));
        lines.add(text(""));
        lines.add(Ui.keyHint("Enter", "run task"));
    }

    private void addWelcome(List<Element> lines) {
        ctx.actions().ensureDoctor();
        lines.add(text("tambo").bold().cyan());
        lines.add(ctx.state().doctorLazy().everLoaded()
                ? text("mise " + ctx.state().doctor().version()).fg(Color.GREEN)
                : text("checking mise…").dim());
        lines.add(text(""));
        lines.add(row(text("Select a panel (").dim(), text("1-4").bold().yellow(),
                text(" or ").dim(), text("Tab").bold().yellow(), text(") to see details.").dim()));
        lines.add(row(text("Press ").dim(), text("a").bold().yellow(),
                text(" to fuzzy-find and install an SDK from the registry.").dim()));
        lines.add(row(text("Press ").dim(), text("?").bold().yellow(), text(" for the full key reference.").dim()));
    }
}
