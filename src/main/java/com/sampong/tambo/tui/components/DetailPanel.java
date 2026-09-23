package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import dev.tamboui.layout.Padding;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The main pane: everything about whatever row is selected in the focused side panel. lazygit's
 * right-hand pane, and the counterweight that lets {@link SidePanels} stay narrow — each list
 * over there carries only what identifies a row, because the rest of it is here.
 * <p>
 * It reads {@link SidePanels#focused()} rather than {@link UiContext#focusedId()} directly, which
 * is what keeps it showing the tool you were looking at while focus sits in the command log or a
 * filter box. A pane that blanked itself every time focus moved off the list would be useless
 * for the thing it is for: reading the detail while doing something else.
 */
@RequiredArgsConstructor
public final class DetailPanel {

    /** Columns of chrome to subtract before wrapping: the border, the padding, and the scrollbar. */
    private static final int CHROME_WIDTH = 6;
    /**
     * Width of the label column in the key/value rows below — one wider than the longest label
     * ("Shims on PATH"), so even that one keeps a space before its value.
     */
    private static final int LABEL_WIDTH = 15;
    /** Width of the key column in the welcome text's key/description rows. */
    private static final int WELCOME_KEY_WIDTH = 10;
    /** Space between two key hints on one row. */
    private static final String HINT_GAP = "   ";
    /** Rows the mouse wheel scrolls the pane by, matching {@code LogPanel}. */
    private static final int WHEEL_STEP = 3;

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final SidePanels sidePanels;
    @NonNull
    private final ToolsPanel toolsPanel;
    @NonNull
    private final TasksPanel tasksPanel;
    @NonNull
    private final EnvPanel envPanel;
    @NonNull
    private final AdvancedPanel advancedPanel;

    /**
     * First visible line of the pane, and the panel it belongs to. Reset when the stack moves to
     * a different panel: a scroll position from a long PATH means nothing in a three-line task
     * description, and leaving it would open the next panel part-way down.
     */
    private int scroll;
    private SidePanels.Side lastSide = SidePanels.Side.TOOLS;

    /**
     * {@code width} is the pane's rendered column count, used to wrap the long free text this
     * pane exists to show — a {@code PATH} value, a task's command line, an advanced action's
     * warning.
     */
    public Panel build(int width, List<AdvancedPanel.Action> advancedActions) {
        SidePanels.Side side = sidePanels.focused();
        int wrap = Math.max(20, width - CHROME_WIDTH);
        List<Element> lines = new ArrayList<>();
        // Each body fills the lines for its panel's selection and names the pane after it.
        String title = !sidePanels.available(side)
                ? addUnsupported(lines, side, wrap)
                : switch (side) {
                    case STATUS -> addBackendReport(lines, wrap);
                    case TOOLS -> addToolDetail(lines, wrap);
                    case ENV -> addEnvDetail(lines, wrap);
                    case TASKS -> addTaskDetail(lines, wrap);
                    case ADVANCED -> addActionDetail(lines, wrap, advancedActions);
                };

        // A scrolling list rather than a plain column: a capability report, a PATH or a task's
        // script can all be taller than the pane, and a column would simply cut the rest off
        // with nothing on screen to say it had. The list's own scrollbar says so, and the wheel
        // works over the pane without focusing it — focus belongs to the stack, which is what
        // you are steering while you read this.
        if (side != lastSide) {
            lastSide = side;
            scroll = 0;
        }
        scroll = Ui.clamp(scroll, lines.size());

        ListElement<?> body = list()
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .displayOnly()
                .selected(scroll)
                .onMouseEvent(this::handleWheel);
        for (Element line : lines) {
            body.add((StyledElement<?>) line);
        }

        return panel(" " + title + " ", body.constraint(fill()))
                .rounded()
                .padding(Padding.symmetric(0, 1))
                .borderColor(ctx.theme().idle());
    }

    /** Wheel scrolling for the pane, which has no focus of its own to drive a key handler. */
    private EventResult handleWheel(MouseEvent event) {
        if (event.kind() == MouseEventKind.SCROLL_UP) {
            scroll = Math.max(0, scroll - WHEEL_STEP);
            return EventResult.HANDLED;
        }
        if (event.kind() == MouseEventKind.SCROLL_DOWN) {
            scroll += WHEEL_STEP;
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    // ==================== per-panel bodies ====================

    /**
     * What the backend is and what it can do. The capability list is the honest answer to a
     * question this app used to leave the user guessing at — whether a missing panel means the
     * backend has no such concept or the app failed to load it.
     */
    private String addBackendReport(List<Element> lines, int wrap) {
        ctx.actions().ensureBackendInfo();
        String name = ctx.backend().name();
        boolean known = ctx.state().backendInfoLazy().everLoaded();
        BackendInfo info = ctx.state().backendInfo();

        lines.add(field("Backend", text(name).bold().cyan()));
        lines.add(field("Version", known ? text(info.version()) : text("checking…").dim()));
        lines.add(field("UI backend", text(ctx.uiBackend()).dim()));
        addField(lines, "Config file", ctx.backend().projectConfigFileName(), wrap, TextElement::dim);
        if (known && info.reportsHealth()) {
            lines.add(field("Activated", Ui.badge(info.activated())));
            lines.add(field("Shims on PATH", Ui.badge(info.shimsOnPath())));
            lines.add(field("Config files", text(String.valueOf(info.configFileCount()))));
        }
        lines.add(text(""));
        addWrapped(lines, "What " + name + " can do here", wrap, TextElement::bold);
        for (BackendFeature feature : BackendFeature.values()) {
            boolean supported = ctx.supports(feature);
            lines.addAll(Ui.hanging(
                    text(supported ? " ✓ " : " · ").fg(supported ? Color.GREEN : Color.DARK_GRAY), 3,
                    feature.label(), wrap, supported ? t -> t : TextElement::dim));
        }
        return name;
    }

    private String addToolDetail(List<Element> lines, int wrap) {
        SdkVersion t = toolsPanel.selected();
        if (t == null) {
            addWelcome(lines, wrap);
            return "Tools";
        }
        Color statusColor = t.active() ? Color.CYAN : t.installed() ? Color.GREEN : Color.DARK_GRAY;
        boolean pendingChange = t.requested() != null && !t.requested().isBlank()
                && !t.requested().equals(t.version());
        String latest = ctx.state().outdated().get(t.name());

        lines.add(field("Tool", text(t.name()).bold().cyan()));
        lines.add(field("Version", text(t.hasVersion() ? t.version() : "-").fg(statusColor)));
        addField(lines, "Requested", Ui.nullToDash(t.requested()), wrap,
                pendingChange ? r -> r.bold().yellow() : r -> r);
        lines.add(field("Installed", Ui.badge(t.installed())));
        lines.add(field("Active", Ui.badge(t.active())));
        addField(lines, "Source", Ui.nullToDash(t.sourceType()), wrap, TextElement::dim);
        addField(lines, "Install path", Ui.nullToDash(t.installPath()), wrap, TextElement::dim);
        if (latest != null) {
            addField(lines, "Upgrade", "↑ " + latest + " available", wrap, TextElement::yellow);
        }
        if (pendingChange) {
            lines.add(text(""));
            addWrapped(lines, ctx.backend().projectConfigFileName() + " asks for "
                    + t.requested() + ", which is not what is installed.", wrap, TextElement::yellow);
        }
        lines.add(text(""));
        addHints(lines, wrap,
                new Hint("i", "install"),
                new Hint("u", "use in " + ctx.backend().projectConfigFileName()),
                new Hint("g", "set global"));
        addHints(lines, wrap,
                new Hint("x", "uninstall"),
                new Hint("R", "remove from config"),
                new Hint("d", "remove plugin"));
        return t.name();
    }

    /**
     * The whole value, wrapped. This is the pane's clearest justification: a {@code PATH} is
     * routinely several hundred characters, and every attempt to show it in the sidebar — a
     * clipped column, a sideways pan — was worse than simply giving it the room.
     */
    private String addEnvDetail(List<Element> lines, int wrap) {
        Map.Entry<String, String> entry = envPanel.selected();
        if (entry == null) {
            addWrapped(lines, "No environment variable selected.", wrap, TextElement::dim);
            return "Env";
        }
        String value = entry.getValue();
        List<String> wrapped = Ui.wrapValue(value, wrap);
        lines.add(field("Variable", text(entry.getKey()).bold().yellow()));
        lines.add(field("Length", text(value.length() + " characters").dim()));
        // Only for a genuine list — a single value that merely wrapped onto several lines is
        // one entry, not several, and saying otherwise would be worse than saying nothing.
        int entries = value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)).length;
        if (entries > 1) {
            lines.add(field("Entries", text(entries + " path entries").dim()));
        }
        lines.add(text(""));
        for (String line : wrapped) {
            lines.add(text(line));
        }
        lines.add(text(""));
        addHints(lines, wrap, new Hint("y", "copy value"));
        return entry.getKey();
    }

    private String addTaskDetail(List<Element> lines, int wrap) {
        ProjectTask t = tasksPanel.selected();
        if (t == null) {
            addWrapped(lines, "No task selected.", wrap, TextElement::dim);
            return "Tasks";
        }
        boolean running = ctx.state().isBusy("task:" + t.name());
        lines.add(field("Task", text(t.name()).bold().cyan()));
        addField(lines, "Description", Ui.nullToDash(t.description()), wrap, d -> d);
        addField(lines, "Source", Ui.nullToDash(t.source()), wrap, TextElement::dim);
        addField(lines, "Aliases", t.aliasSummary(), wrap, TextElement::dim);
        addField(lines, "Depends on", t.dependsSummary(), wrap, TextElement::dim);
        if (running) {
            lines.add(field("State", text(Ui.spinner() + " running").yellow()));
        }
        lines.add(text(""));
        lines.add(text("Runs").bold());
        for (String line : Ui.wordWrap(t.runSummary(), wrap)) {
            lines.add(text(line).fg(Color.CYAN));
        }
        lines.add(text(""));
        addHints(lines, wrap,
                new Hint("Enter", "run"),
                new Hint(":", "run with args"),
                new Hint(".", "re-run last"),
                new Hint("c", "cancel"));
        return t.name();
    }

    private String addActionDetail(List<Element> lines, int wrap, List<AdvancedPanel.Action> actions) {
        AdvancedPanel.Action action = advancedPanel.selected(actions);
        if (action == null) {
            addWrapped(lines, "Nothing to do here right now.", wrap, TextElement::dim);
            return "Advanced";
        }
        lines.addAll(Ui.hanging(text(" " + action.key() + " ").bold().yellow(), action.key().length() + 2,
                action.label(), wrap, TextElement::bold));
        lines.add(text(""));
        for (String line : Ui.wordWrap(action.description(), wrap)) {
            lines.add(text(line).dim());
        }
        lines.add(text(""));
        addHints(lines, wrap, new Hint("Enter", "run this"), new Hint(action.key(), "same, from anywhere"));
        return action.label();
    }

    /** The backend's own explanation for a panel it cannot serve, rather than an empty pane. */
    private String addUnsupported(List<Element> lines, SidePanels.Side side, int wrap) {
        addWrapped(lines, side.title() + " is not available with " + ctx.backend().name(), wrap,
                t -> t.bold().fg(Color.YELLOW));
        lines.add(text(""));
        for (String line : Ui.wordWrap(sidePanels.unsupportedReason(side), wrap)) {
            lines.add(text(line).dim());
        }
        lines.add(text(""));
        addWrapped(lines, "The panel keeps its slot so the number keys mean the same thing "
                + "under either backend.", wrap, TextElement::dim);
        return side.title();
    }

    private void addWelcome(List<Element> lines, int wrap) {
        lines.add(text("tambo").bold().cyan());
        String backendName = ctx.backend().name();
        ctx.actions().ensureBackendInfo();
        lines.add(ctx.state().backendInfoLazy().everLoaded()
                ? text(backendName + " " + ctx.state().backendInfo().version()).fg(Color.GREEN)
                : text("checking " + backendName + "…").dim());
        lines.add(text(""));
        // Key-first rows rather than "Press a to …" sentences: a sentence with the key styled
        // mid-way is several spans that cannot wrap as one, while a key column can.
        lines.addAll(Ui.hanging(text(Ui.fixedWidth("1-5, Tab", WELCOME_KEY_WIDTH)).bold().yellow(),
                WELCOME_KEY_WIDTH, "jump to a panel, or cycle through them", wrap, TextElement::dim));
        lines.addAll(Ui.hanging(text(Ui.fixedWidth("a", WELCOME_KEY_WIDTH)).bold().yellow(),
                WELCOME_KEY_WIDTH, "fuzzy-find and install an SDK from the registry", wrap, TextElement::dim));
        lines.addAll(Ui.hanging(text(Ui.fixedWidth("?", WELCOME_KEY_WIDTH)).bold().yellow(),
                WELCOME_KEY_WIDTH, "the full key reference", wrap, TextElement::dim));
    }

    // ==================== small builders ====================

    /** A {@code label   value} row, labels padded to one column so the values line up. */
    private static Element field(String label, Element value) {
        return row(text(Ui.fixedWidth(label, LABEL_WIDTH)).dim(), value);
    }

    /**
     * A {@code label   value} field whose value word-wraps under itself, the label column left
     * blank on the continuation lines — for the values that can outgrow the pane, a path or a
     * task's description.
     */
    private static void addField(List<Element> lines, String label, String value, int wrap,
                                 UnaryOperator<TextElement> style) {
        lines.addAll(Ui.hanging(text(Ui.fixedWidth(label, LABEL_WIDTH)).dim(), LABEL_WIDTH, value, wrap, style));
    }

    /** Free text, word-wrapped to the pane — one element per line, each styled by {@code style}. */
    private static void addWrapped(List<Element> lines, String text, int wrap, UnaryOperator<TextElement> style) {
        for (String line : Ui.wordWrap(text, wrap)) {
            lines.add(style.apply(text(line)));
        }
    }

    /** A {@code [key] label} hint, as {@link Ui#keyHint} renders it. */
    private record Hint(String key, String label) {

        /** Columns {@link Ui#keyHint} spends on it: the brackets, the space, and both texts. */
        int width() {
            return key.length() + 3 + label.length();
        }
    }

    /**
     * {@code [key] label} hints, spaced apart and flowed onto as many rows as {@code wrap}
     * needs — a narrow pane gets two short rows rather than one row cut off at the edge.
     */
    private static void addHints(List<Element> lines, int wrap, Hint... hints) {
        List<Element> parts = new ArrayList<>();
        int used = 0;
        for (Hint hint : hints) {
            if (!parts.isEmpty() && used + HINT_GAP.length() + hint.width() > wrap) {
                lines.add(row(parts.toArray(new Element[0])));
                parts.clear();
                used = 0;
            }
            if (!parts.isEmpty()) {
                parts.add(text(HINT_GAP));
                used += HINT_GAP.length();
            }
            parts.add(Ui.keyHint(hint.key(), hint.label()));
            used += hint.width();
        }
        if (!parts.isEmpty()) {
            lines.add(row(parts.toArray(new Element[0])));
        }
    }
}
