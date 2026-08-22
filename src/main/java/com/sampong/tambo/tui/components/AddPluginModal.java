package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
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
import com.sampong.tambo.tui.features.Fuzzy;
import com.sampong.tambo.tui.state.Lazy;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Registers a vfox plugin standalone, without installing any version. Two entry points share
 * this one modal: {@code p} opens it unconditionally for the everyday flow, {@code P} opens it
 * only once {@code advancedFeatures} is on ({@code V} to toggle, {@code --advanced-features} to
 * start with it on) — the modal's own behavior doesn't otherwise depend on which key opened it.
 * Fuzzy-finds across the
 * full {@code vfox available} catalog the same way the "Add SDK" modal does; Enter adds
 * whichever entry is highlighted. Since the query is matched as a subsequence, anything longer
 * than every candidate name necessarily fails to match — so typing the advanced
 * {@code <name> --alias <x> --source <url>} syntax (longer than any bare plugin name) always
 * falls through to submitting the raw typed text instead of a highlighted match. That fallback
 * is also where the syntax is gated: it only runs when {@code advancedFeatures} is on —
 * otherwise a catalog miss just logs a nudge instead of submitting anything. vfox-only — mise
 * has no equivalent in this app (mise tools are always added implicitly via the registry "Add
 * SDK" flow instead). Esc cancels.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}.
 */
@RequiredArgsConstructor
public final class AddPluginModal {

    private static final int VISIBLE_ROWS = 10;
    private static final int WIDTH = 76;

    @NonNull
    private final UiContext ctx;

    @Getter
    private boolean open;
    private final TextInputState search = new TextInputState();
    private String lastQuery = "";
    private int index;
    private @Nullable String preOpenFocus;

    public void open() {
        preOpenFocus = ctx.focusedId();
        // Same P3 tier as the "Add SDK" modal — fetched here on first open, reused after.
        ctx.state().registryLazy().retryIfFailed();
        ctx.actions().ensureRegistry();
        search.clear();
        lastQuery = "";
        index = 0;
        open = true;
        ctx.focus(PanelIds.ADD_PLUGIN_INPUT);
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The context-sensitive hint line the footer shows while the prompt is open. */
    public String footerHint() {
        return ctx.state().advancedFeatures()
                ? "type to fuzzy find, or name [--alias x] [--source url]   enter add   esc cancel"
                : "type to fuzzy find a catalog plugin   enter add   esc cancel   (V: --alias/--source)";
    }

    public Element build() {
        String query = search.text();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            index = 0;
        }
        List<RegistryEntry> matches = fuzzyPlugins(query);
        index = Ui.clamp(index, matches.size());

        List<Element> content = new ArrayList<>();
        content.add(row(
                text("Search ").dim(),
                textInput(search)
                        .placeholder(ctx.state().advancedFeatures()
                                ? "nodejs   or   myplugin --alias foo --source https://…"
                                : "nodejs")
                        .placeholderColor(Color.DARK_GRAY)
                        .id(PanelIds.ADD_PLUGIN_INPUT)
                        .focusable(true)
                        .onKeyEvent(this::handleKey)
        ));
        content.add(text(""));

        Lazy<List<RegistryEntry>> registry = ctx.state().registryLazy();
        if (ctx.state().registry().isEmpty()) {
            content.add(text(registry.everLoaded() || registry.failed()
                    ? (ctx.state().advancedFeatures()
                            ? "Catalog unavailable — type a name and flags to add it directly"
                            : "Catalog unavailable")
                    : "Loading catalog…").dim());
        } else if (matches.isEmpty()) {
            content.add(text(ctx.state().advancedFeatures()
                    ? "No catalog match — enter adds \"" + query + "\" as typed"
                    : "No catalog match — --alias/--source needs advanced features (V)").dim());
        } else {
            addWindowedRows(content, matches.size(), i -> pluginRow(matches, i));
        }

        content.add(text(""));
        content.add(text("enter add   esc cancel").dim());

        return dialog("Add vfox plugin — available (" + ctx.state().registry().size() + ")",
                content.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private Element pluginRow(List<RegistryEntry> matches, int i) {
        RegistryEntry e = matches.get(i);
        boolean sel = i == index;
        return row(
                text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                sel ? text(e.shortName()).bold().cyan() : text(e.shortName()).bold(),
                spacer(),
                text(Ui.truncate(Ui.nullToDash(e.description()), 40) + " ").dim()
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

    private List<RegistryEntry> fuzzyPlugins(String query) {
        return Fuzzy.filter(query, ctx.state().registry(), RegistryEntry::shortName, RegistryEntry::description);
    }

    private EventResult handleKey(KeyEvent event) {
        if (event.isCancel()) {
            close();
            return EventResult.HANDLED;
        }
        if (event.isConfirm()) {
            confirm();
            return EventResult.HANDLED;
        }
        int total = fuzzyPlugins(search.text()).size();
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
        // Typing is applied by the input itself; swallow the rest so no panel
        // shortcut fires under the prompt.
        return EventResult.HANDLED;
    }

    private void confirm() {
        String typed = search.text();
        List<RegistryEntry> matches = fuzzyPlugins(typed);
        if (matches.isEmpty()) {
            // Nothing in the catalog matched — that's always true for the advanced
            // --alias/--source syntax, since it's longer than any bare candidate name, so
            // this is also where that syntax is gated behind advancedFeatures.
            if (!ctx.state().advancedFeatures()) {
                ctx.state().addLog(LogLevel.INFO,
                        "No catalog match — --alias/--source syntax is an advanced feature, press V to enable it");
                return;
            }
            close();
            ctx.actions().addPlugin(typed);
            return;
        }
        String toSubmit = matches.get(Ui.clamp(index, matches.size())).shortName();
        close();
        ctx.actions().addPlugin(toSubmit);
    }
}
