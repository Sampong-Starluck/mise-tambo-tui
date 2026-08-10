package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * An interactive menu for the maintenance/config actions that {@link UiContext#state()} gates
 * behind {@code advancedFeatures} — only rendered while that flag is on ({@code V} to toggle,
 * splitting it into the main column alongside {@link DetailPanel}; {@code --advanced-features}
 * to start with it already on). {@code ↑}/{@code ↓} moves the selection, Enter runs it. The
 * same letter shortcuts (T/E/D/U/X/B/P) still work globally too — this is a discoverable,
 * directly actionable substitute for having to remember them, not a replacement.
 * <p>
 * {@code x}/{@code R} (uninstall / remove from config) are deliberately not listed here: they
 * act on whatever tool is selected in the Tools panel, which has no meaning from this menu, so
 * they stay exactly where that context lives.
 */
@RequiredArgsConstructor
public final class AdvancedPanel {

    /** One menu entry: the letter shortcut it mirrors, its label, a risk/feature blurb, and what running it does. */
    public record Action(String key, String label, String description, Runnable action) {
    }

    @NonNull
    private final UiContext ctx;
    private int index;

    /** {@code width} is the panel's rendered column width — used to hand-wrap each description. */
    public Column build(List<Action> actions, int width) {
        index = Ui.clamp(index, actions.size());
        // 2 for the border, 4 for the description's leading indent (see the loop below).
        int wrapWidth = Math.max(20, width - 6);

        ListElement<?> list = list()
                .title("[6] Advanced")
                .rounded().id(PanelIds.ADVANCED).focusable(ctx.modalOpen())
                .borderColor(PanelIds.ADVANCED.equals(ctx.focusedId()) ? ctx.theme().focus() : ctx.theme().idle())
                .highlightColor(ctx.theme().accent())
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .autoScroll()
                .selected(index)
                .onKeyEvent(event -> handleKey(event, actions));

        if (actions.isEmpty()) {
            list.add(row(text("Nothing to do here right now").dim()));
        } else {
            for (Action a : actions) {
                List<Element> lines = new ArrayList<>();
                lines.add(row(text(" " + a.key() + " ").bold().yellow(), text(a.label()).bold()));
                for (String line : Ui.wordWrap(a.description(), wrapWidth)) {
                    lines.add(text("    " + line).dim());
                }
                list.add(column(lines.toArray(new Element[0])));
            }
        }
        return column(list.constraint(fill()));
    }

    private EventResult handleKey(KeyEvent event, List<Action> actions) {
        if (Ui.isNavKey(event)) {
            index = Ui.applyNav(event, index, actions.size());
            return EventResult.HANDLED;
        }
        if (!actions.isEmpty() && event.isConfirm()) {
            actions.get(Ui.clamp(index, actions.size())).action().run();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
