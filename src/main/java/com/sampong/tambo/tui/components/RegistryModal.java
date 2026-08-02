package com.sampong.tambo.tui.components;

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
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.tui.features.Fuzzy;
import com.sampong.tambo.tui.state.Lazy;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The "Add SDK" modal: step 1 fuzzy-finds a tool by typing into a real input box, step 2
 * fuzzy-finds the version the same way. Esc steps back.
 * <p>
 * Step 1 browses the full catalog for both backends — {@code mise registry} or {@code vfox
 * available} — so a plugin shows up here whether or not it was registered beforehand via the
 * separate {@code P} "add plugin" flow; {@link com.sampong.tambo.vfox.VfoxSdkBackend#install}
 * re-runs {@code vfox add} itself before installing, so picking an unregistered plugin here
 * just works. Behavior still forks on step 2: mise's Enter runs {@code mise use} (install +
 * pin; Ctrl+G toggles local/global), vfox's Enter only runs {@code vfox install} — already-
 * installed versions are marked, and pinning stays a separate step in the Tools panel.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}.
 */
@RequiredArgsConstructor
public final class RegistryModal {

    private static final int VISIBLE_ROWS = 12;
    private static final int WIDTH = 72;

    private enum Step { TOOL, VERSION }

    @NonNull
    private final UiContext ctx;

    private boolean open;
    private Step step = Step.TOOL;
    private final TextInputState search = new TextInputState();
    private String lastQuery = "";
    private int index;
    private @Nullable RegistryEntry tool;
    private List<String> remoteVersions = List.of();
    private boolean versionsLoading;
    private boolean installGlobal;
    private @Nullable String preOpenFocus;

    public boolean isOpen() {
        return open;
    }

    public void open() {
        // The registry (mise's ~200 KB of JSON, or vfox's `available` catalog) is fetched
        // here on first open rather than at startup — a session that never adds an SDK never
        // pays for it at all. Reopening after a failed fetch is the user asking to try again;
        // a loaded registry is reused, since nothing done locally changes what's installable.
        ctx.state().registryLazy().retryIfFailed();
        ctx.actions().ensureRegistry();
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
        boolean vfox = ctx.state().vfox();
        if (step == Step.TOOL) {
            return vfox
                    ? "type to fuzzy find   ↑/↓ select   enter choose plugin   esc close"
                    : "type to fuzzy find   ↑/↓ select   enter choose sdk   esc close";
        }
        return vfox
                ? "type to fuzzy find   ↑/↓ select   enter install   esc back"
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

        boolean vfox = ctx.state().vfox();
        content.add(text(""));
        content.add(text(step == Step.TOOL
                ? (vfox ? "enter choose plugin   esc close" : "enter choose SDK   esc close")
                : (vfox ? "enter install   esc back" : "enter install   ctrl+g toggle local/global   esc back")).dim());

        String title = (vfox ? "Add SDK — vfox catalog (" : "Add SDK — registry (")
                + ctx.state().registry().size() + ")";
        return dialog(title, content.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private void buildToolStep(List<Element> content, String query) {
        boolean vfox = ctx.state().vfox();
        List<RegistryEntry> matches = fuzzyTools(query);
        index = Ui.clamp(index, matches.size());

        content.add(searchInputRow("Search SDK", "type to fuzzy find, e.g. \"node\" or \"jdk\""));
        content.add(text(""));
        if (ctx.state().registry().isEmpty()) {
            Lazy<List<RegistryEntry>> registry = ctx.state().registryLazy();
            content.add(text(registry.everLoaded() || registry.failed()
                    ? (vfox ? "Catalog unavailable" : "Registry unavailable")
                    : (vfox ? "Loading catalog…" : "Loading registry…")).dim());
        } else if (matches.isEmpty()) {
            content.add(text((vfox ? "No plugin matches \"" : "No SDK matches \"") + query + "\"").dim());
        } else {
            addWindowedRows(content, matches.size(), i -> toolRow(matches, i));
        }
    }

    private Element toolRow(List<RegistryEntry> matches, int i) {
        RegistryEntry e = matches.get(i);
        boolean sel = i == index;
        return row(
                text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                sel ? text(e.shortName()).bold().cyan() : text(e.shortName()).bold(),
                spacer(),
                text(Ui.truncate(Ui.nullToDash(e.description()), 40) + " ").dim()
        );
    }

    private void buildVersionStep(List<Element> content, String query) {
        boolean vfox = ctx.state().vfox();
        List<String> matches = Fuzzy.filter(query, remoteVersions, v -> v, null);
        index = Ui.clamp(index, matches.size());

        if (vfox) {
            // Selecting a version here only installs it (see confirmVersion) — vfox's
            // install has no local/global scope, so there is nothing to toggle.
            content.add(row(
                    text("Plugin ").dim(),
                    text(tool.shortName()).bold().cyan()
            ));
        } else {
            content.add(row(
                    text("SDK ").dim(),
                    text(tool.shortName()).bold().cyan(),
                    spacer(),
                    text("target: ").dim(),
                    installGlobal ? text("global (ctrl+g)").yellow() : text("this directory (ctrl+g)").green()
            ));
        }
        content.add(searchInputRow("Search version", "type to fuzzy find a version"));
        content.add(text(""));
        if (versionsLoading) {
            String via = vfox ? "vfox search " + tool.shortName() + " all" : "mise ls-remote " + tool.shortName();
            content.add(text("Fetching versions via " + via + "…").dim());
        } else if (matches.isEmpty()) {
            content.add(text("No version matches \"" + query + "\"").dim());
        } else {
            addWindowedRows(content, matches.size(), i -> {
                String v = matches.get(i);
                boolean sel = i == index;
                boolean installed = vfox && isVersionInstalled(tool.shortName(), v);
                return row(
                        text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                        sel ? text(v).bold().cyan() : text(v),
                        spacer(),
                        installed ? text("installed ").fg(Color.GREEN).dim() : text("")
                );
            });
        }
    }

    /** Whether {@code version} (as returned by {@code vfox search ... all}) is already installed for {@code toolName}. */
    private boolean isVersionInstalled(String toolName, String version) {
        String normalized = (version.startsWith("v") || version.startsWith("V"))
                ? version.substring(1) : version;
        return ctx.state().tools().stream()
                .anyMatch(t -> t.tool().equalsIgnoreCase(toolName) && t.version().equals(normalized));
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
        // Both backends browse the full catalog now. Match on the short name first, then
        // fall back to description + backends so typing a backend (e.g. "cargo", "npm",
        // "ubi") narrows the mise list too; vfox entries carry no backends (see
        // VfoxSdkBackend#listAvailable), so backendSummary() is just a harmless "-" there.
        return Fuzzy.filter(query, ctx.state().registry(), RegistryEntry::shortName,
                e -> Ui.nullToDash(e.description()) + " " + e.backendSummary());
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
        String shortName = tool.shortName();
        close();
        if (ctx.state().vfox()) {
            // vfox: this modal only installs a version — pinning it (project/global "use")
            // stays a separate step in the Tools panel ('u'/'g'), same as any other install.
            ctx.actions().installTool(new ToolVersion(shortName, version, null, null, null, null, false, false));
        } else {
            ctx.actions().useTool(shortName + "@" + version, installGlobal);
        }
    }
}
