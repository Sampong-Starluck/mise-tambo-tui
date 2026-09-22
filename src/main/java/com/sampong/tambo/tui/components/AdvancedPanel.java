package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.List;

import dev.tamboui.layout.Alignment;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Panel 6 — an interactive menu for the maintenance/config actions gated behind
 * {@code advancedFeatures}, only in the stack while that flag is on ({@code V} toggles it,
 * {@code --advanced-features} starts with it on). {@code ↑}/{@code ↓} moves the selection, Enter
 * runs it. The same letter shortcuts (T/E/D/U/X/B/P) still work globally too — this is a
 * discoverable, directly actionable substitute for having to remember them, not a replacement.
 * <p>
 * Only the letter and the label are listed here. Each entry's risk/feature blurb is shown in
 * {@link DetailPanel} as the selection moves, which is what makes these entries safe to browse:
 * a menu of destructive one-keypress actions should explain the highlighted one before it is
 * confirmed, and the sidebar has no room to explain all of them at once.
 * <p>
 * {@code x}/{@code R}/{@code d} (uninstall / remove from config / remove plugin) are deliberately
 * not listed: they act on whatever tool is selected in the Tools panel, which has no meaning from
 * this menu, so they stay exactly where that context lives.
 */
@RequiredArgsConstructor
public final class AdvancedPanel {

    /** One menu entry: the letter shortcut it mirrors, its label, a risk/feature blurb, and what running it does. */
    public record Action(String key, String label, String description, Runnable action) {
    }

    @NonNull
    private final UiContext ctx;
    private int index;

    /** The entry the selection sits on, or null when there is nothing to do. */
    public @Nullable Action selected(List<Action> actions) {
        return actions.isEmpty() ? null : actions.get(Ui.clamp(index, actions.size()));
    }

    public Panel build(List<Action> actions) {
        index = Ui.clamp(index, actions.size());

        ListElement<?> menu = list()
                .highlightColor(ctx.theme().accent())
                .highlightSymbol("> ")
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .selected(index);

        if (actions.isEmpty()) {
            menu.add(row(text("Nothing to do here right now").dim()));
        } else {
            for (Action a : actions) {
                menu.add(row(text(a.key() + " ").bold().yellow(), text(a.label())));
            }
        }

        Panel block = panel(SidePanels.title(SidePanels.Side.ADVANCED, ""), menu.constraint(fill()))
                .rounded()
                .id(PanelIds.ADVANCED).focusable(ctx.modalOpen())
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus())
                .onKeyEvent(event -> handleKey(event, actions));

        String position = SidePanels.positionLabel(index, actions.size());
        if (!position.isEmpty() && PanelIds.ADVANCED.equals(ctx.focusedId())) {
            block.bottomTitle(position).bottomTitleAlignment(Alignment.RIGHT);
        }
        return block;
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
