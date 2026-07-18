package com.sampong.tambo.tui.panel;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textInput;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.tui.Fuzzy;
import com.sampong.tambo.tui.PanelIds;
import com.sampong.tambo.tui.Ui;
import com.sampong.tambo.tui.UiContext;

/**
 * The "Add SDK" modal: step 1 fuzzy-finds an SDK in the mise registry by typing
 * into a real input box, step 2 fuzzy-finds the version the same way. Enter
 * installs via {@code mise use}; Ctrl+G toggles local/global; Esc steps back.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}.
 */
public final class RegistryModal {

    private static final int VISIBLE_ROWS = 12;
    private static final int WIDTH = 72;

    private enum Step { TOOL, VERSION }

    private final UiContext ctx;

    private boolean open;
    private Step step = Step.TOOL;
    private final TextInputState search = new TextInputState();
    private String lastQuery = "";
    private int index;
    private RegistryEntry tool;
    private List<String> remoteVersions = List.of();
    private boolean versionsLoading;
    private boolean installGlobal;
    private String preOpenFocus;

    public RegistryModal(UiContext ctx) {
        this.ctx = ctx;
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        preOpenFocus = ctx.focusedId();
        open = true;
        step = Step.TOOL;
        search.clear();
        lastQuery = "";
        index = 0;
        tool = null;
        remoteVersions = List.of();
        versionsLoading = false;
        installGlobal = false;
        ctx.focus(PanelIds.MODAL_INPUT);
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The context-sensitive hint line the footer shows while the modal is open. */
    public String footerHint() {
        return step == Step.TOOL
                ? "type to fuzzy find   ↑/↓ select   enter choose sdk   esc close"
                : "type to fuzzy find   ↑/↓ select   enter install   ctrl+g local/global   esc back";
    }

    // ==================== Rendering ====================

    public Element build() {
        String query = search.text();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            index = 0;
        }

        List<Element> content = new ArrayList<>();
        if (step == Step.TOOL) {
            buildToolStep(content, query);
        } else {
            buildVersionStep(content, query);
        }

        content.add(text(""));
        content.add(text(step == Step.TOOL
                ? "enter choose SDK   esc close"
                : "enter install   ctrl+g toggle local/global   esc back").dim());

        return dialog("Add SDK — registry (" + ctx.state().registry().size() + ")",
                content.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private void buildToolStep(List<Element> content, String query) {
        List<RegistryEntry> matches = fuzzyTools(query);
        index = Ui.clamp(index, matches.size());

        content.add(searchInputRow("Search SDK", "type to fuzzy find, e.g. \"node\" or \"jdk\""));
        content.add(text(""));
        if (ctx.state().registry().isEmpty()) {
            content.add(text(ctx.state().loading() ? "Loading registry…" : "Registry unavailable").dim());
        } else if (matches.isEmpty()) {
            content.add(text("No SDK matches \"" + query + "\"").dim());
        } else {
            addWindowedRows(content, matches.size(), i -> {
                RegistryEntry e = matches.get(i);
                boolean sel = i == index;
                return row(
                        text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                        sel ? text(e.shortName()).bold().cyan() : text(e.shortName()).bold(),
                        spacer(),
                        text(Ui.truncate(Ui.nullToDash(e.description()), 40) + " ").dim()
                );
            });
        }
    }

    private void buildVersionStep(List<Element> content, String query) {
        List<String> matches = Fuzzy.filter(query, remoteVersions, v -> v, null);
        index = Ui.clamp(index, matches.size());

        content.add(row(
                text("SDK ").dim(),
                text(tool.shortName()).bold().cyan(),
                spacer(),
                text("target: ").dim(),
                installGlobal ? text("global (ctrl+g)").yellow() : text("this directory (ctrl+g)").green()
        ));
        content.add(searchInputRow("Search version", "type to fuzzy find a version"));
        content.add(text(""));
        if (versionsLoading) {
            content.add(text("Fetching versions via mise ls-remote " + tool.shortName() + "…").dim());
        } else if (matches.isEmpty()) {
            content.add(text("No version matches \"" + query + "\"").dim());
        } else {
            addWindowedRows(content, matches.size(), i -> {
                String v = matches.get(i);
                boolean sel = i == index;
                return row(
                        text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                        sel ? text(v).bold().cyan() : text(v)
                );
            });
        }
    }

    /** The typed input box shared by both steps; owns all modal key handling. */
    private Element searchInputRow(String label, String placeholder) {
        return row(
                text(label + " ").dim().constraint(length(15)),
                textInput(search)
                        .placeholder(placeholder)
                        .placeholderColor(Color.DARK_GRAY)
                        .id(PanelIds.MODAL_INPUT)
                        .focusable(true)
                        .onKeyEvent(this::handleKey)
        );
    }

    /** Renders a window of VISIBLE_ROWS rows that follows the selection. */
    private void addWindowedRows(List<Element> content, int total, IntFunction<Element> rowAt) {
        int start = Math.max(0, index - VISIBLE_ROWS + 1);
        int end = Math.min(total, start + VISIBLE_ROWS);
        for (int i = start; i < end; i++) {
            content.add(rowAt.apply(i));
        }
        int hidden = total - (end - start);
        content.add(hidden > 0 ? text("… " + hidden + " more (keep typing to narrow)").dim() : text(""));
    }

    private List<RegistryEntry> fuzzyTools(String query) {
        return Fuzzy.filter(query, ctx.state().registry(), RegistryEntry::shortName, RegistryEntry::description);
    }

    // ==================== Key handling ====================

    /**
     * Handles the keys the input box itself doesn't consume: list navigation,
     * Enter (choose/install), Ctrl+G (local/global), and Escape (back/close).
     * Everything else is swallowed so no global shortcut fires under the modal.
     */
    private EventResult handleKey(KeyEvent event) {
        int total = step == Step.TOOL
                ? fuzzyTools(search.text()).size()
                : Fuzzy.filter(search.text(), remoteVersions, v -> v, null).size();

        if (event.isCancel()) {
            if (step == Step.VERSION) {
                backToToolStep();
            } else {
                close();
            }
            return EventResult.HANDLED;
        }
        if (event.isConfirm()) {
            confirm();
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.UP) {
            index = Ui.clamp(index - 1, total);
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.DOWN) {
            index = Ui.clamp(index + 1, total);
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.PAGE_UP) {
            index = Ui.clamp(index - VISIBLE_ROWS, total);
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.PAGE_DOWN) {
            index = Ui.clamp(index + VISIBLE_ROWS, total);
            return EventResult.HANDLED;
        }
        if (event.hasCtrl() && event.isCharIgnoreCase('g')) {
            installGlobal = !installGlobal;
            return EventResult.HANDLED;
        }
        // Swallow everything else (Tab, stray chars with modifiers, …) — the modal is modal.
        return EventResult.HANDLED;
    }

    private void backToToolStep() {
        step = Step.TOOL;
        search.clear();
        lastQuery = "";
        index = 0;
        remoteVersions = List.of();
        versionsLoading = false;
    }

    private void confirm() {
        if (step == Step.TOOL) {
            confirmTool();
        } else {
            confirmVersion();
        }
    }

    private void confirmTool() {
        List<RegistryEntry> matches = fuzzyTools(search.text());
        if (matches.isEmpty()) {
            return;
        }
        tool = matches.get(Ui.clamp(index, matches.size()));
        step = Step.VERSION;
        search.clear();
        lastQuery = "";
        index = 0;
        remoteVersions = List.of();
        versionsLoading = true;

        String toolName = tool.shortName();
        ctx.actions().fetchRemoteVersions(toolName, versions -> {
            // Ignore stale responses if the user already left the version step.
            if (open && step == Step.VERSION && tool != null && toolName.equals(tool.shortName())) {
                remoteVersions = versions;
                versionsLoading = false;
            }
        });
    }

    private void confirmVersion() {
        if (versionsLoading) {
            return;
        }
        List<String> matches = Fuzzy.filter(search.text(), remoteVersions, v -> v, null);
        if (matches.isEmpty()) {
            return;
        }
        String version = matches.get(Ui.clamp(index, matches.size()));
        String toolAtVersion = tool.shortName() + "@" + version;
        close();
        ctx.actions().useTool(toolAtVersion, installGlobal);
    }
}
