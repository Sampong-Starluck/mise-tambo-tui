package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textInput;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A one-line prompt for the arguments to forward to a task: Enter runs
 * {@code mise run <task> -- <args>}, Esc cancels. Opened from the Tasks panel.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}.
 */
@RequiredArgsConstructor
public final class TaskArgsModal {

    private static final int WIDTH = 72;

    @NonNull
    private final UiContext ctx;

    private boolean open;
    private String taskName = "";
    private final TextInputState input = new TextInputState();
    private @Nullable String preOpenFocus;

    public boolean isOpen() {
        return open;
    }

    /** Opens the prompt for {@code taskName}, seeding it with any previously used args. */
    public void open(@NonNull String taskName, @NonNull String initialArgs) {
        this.taskName = taskName;
        this.preOpenFocus = ctx.focusedId();
        input.setText(initialArgs);
        input.moveCursorToEnd();
        open = true;
        ctx.focus(PanelIds.TASK_ARGS_INPUT);
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The context-sensitive hint line the footer shows while the prompt is open. */
    public String footerHint() {
        return "type args   enter run   esc cancel";
    }

    public Element build() {
        return dialog("Run task: " + taskName,
                textInput(input)
                        .placeholder("arguments passed after --, e.g. --verbose build")
                        .placeholderColor(Color.DARK_GRAY)
                        .id(PanelIds.TASK_ARGS_INPUT)
                        .focusable(true)
                        .onKeyEvent(this::handleKey),
                text(""),
                text("enter run   esc cancel").dim()
        ).rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private EventResult handleKey(KeyEvent event) {
        if (event.isCancel()) {
            close();
            return EventResult.HANDLED;
        }
        if (event.isConfirm()) {
            String args = input.text();
            String task = taskName;
            close();
            ctx.actions().runTask(task, args);
            return EventResult.HANDLED;
        }
        // Typing is applied by the input itself; swallow the rest so no panel
        // shortcut fires under the prompt.
        return EventResult.HANDLED;
    }
}
