package com.sampong.tambo.tui.features;

import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textInput;

import java.util.List;
import java.util.function.Function;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

/**
 * A reusable {@code /} fuzzy filter for list panels: an optional one-line input
 * that narrows a panel's items with {@link Fuzzy}. Shared by the Tools, Tasks
 * and Env panels so each stays thin.
 * <p>
 * Interaction: the panel calls {@link #activate} when {@code /} is pressed
 * (focusing the input); typing narrows the list live; Enter or an arrow key
 * commits focus back to the list — the filter stays active — so the panel's own
 * navigation and action keys operate on the filtered view; Esc (from either the
 * input or the list) clears the filter and refocuses the list.
 */
public final class PanelFilter {

    private final String inputId;
    private final String listId;
    private final TextInputState search = new TextInputState();
    private boolean active;

    public PanelFilter(String inputId, String listId) {
        this.inputId = inputId;
        this.listId = listId;
    }

    public boolean isActive() {
        return active;
    }

    public String query() {
        return search.text();
    }

    /** Turns the filter on and focuses its input. */
    public void activate(UiContext ctx) {
        active = true;
        ctx.focus(inputId);
    }

    /** Turns the filter off, clears the query, and refocuses the list. */
    public void clear(UiContext ctx) {
        active = false;
        search.clear();
        ctx.focus(listId);
    }

    /** The filtered, ranked view of {@code items} (unchanged when inactive). */
    public <T> List<T> apply(List<T> items, Function<T, String> primary, @Nullable Function<T, String> secondary) {
        return active ? Fuzzy.filter(search.text(), items, primary, secondary) : items;
    }

    /** The filter input row; render this only when {@link #isActive()}. */
    public Row inputRow(UiContext ctx) {
        return row(
                text("/ ").fg(Color.CYAN).bold(),
                textInput(search)
                        .placeholder("filter…")
                        .placeholderColor(Color.DARK_GRAY)
                        .id(inputId)
                        .focusable(true)
                        .onKeyEvent(event -> handleKey(event, ctx))
        );
    }

    private EventResult handleKey(KeyEvent event, UiContext ctx) {
        if (event.isCancel()) {
            clear(ctx);
            return EventResult.HANDLED;
        }
        if (event.isConfirm() || event.code() == KeyCode.DOWN || event.code() == KeyCode.UP) {
            // Commit: hand focus to the list so j/k nav and action keys work,
            // leaving the current filter query in place.
            ctx.focus(listId);
            return EventResult.HANDLED;
        }
        // Typing / backspace is applied by the input itself; swallow the rest so
        // no panel shortcut fires while the filter box is focused.
        return EventResult.HANDLED;
    }
}
