package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;

import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A reusable yes/no confirmation dialog. Callers open it with a message and a
 * {@link Runnable} to run when the user confirms; {@code y}/Enter runs the
 * callback and closes, {@code n}/Esc just closes.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}.
 * Because it has no focusable input of its own, it consumes keys through the
 * global handler in {@code MiseTuiApp} rather than an {@code onKeyEvent}.
 */
@RequiredArgsConstructor
public final class ConfirmModal {

    private static final int WIDTH = 60;

    @NonNull
    private final UiContext ctx;

    private boolean open;
    private String message = "";
    private @Nullable Runnable onConfirm;
    private @Nullable String preOpenFocus;

    public boolean isOpen() {
        return open;
    }

    /** Opens the dialog; {@code onConfirm} runs on the render thread if the user confirms. */
    public void open(@NonNull String message, @NonNull Runnable onConfirm) {
        this.message = message;
        this.onConfirm = onConfirm;
        this.preOpenFocus = ctx.focusedId();
        this.open = true;
        // No input to focus — clear focus so no panel handles keys underneath.
        ctx.clearFocus();
    }

    public void close() {
        open = false;
        onConfirm = null;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The context-sensitive hint line the footer shows while the dialog is open. */
    public String footerHint() {
        return "y / enter confirm   n / esc cancel";
    }

    /**
     * Handles the confirm/cancel keys. Returns true when the event was consumed;
     * {@code MiseTuiApp}'s global handler calls this while the dialog is open.
     */
    public boolean handleKey(KeyEvent key) {
        if (key.isChar('y') || key.isConfirm()) {
            Runnable action = onConfirm;
            close();
            if (action != null) {
                action.run();
            }
            return true;
        }
        if (key.isChar('n') || key.isCancel()) {
            close();
            return true;
        }
        return true; // modal: swallow everything else
    }

    public Element build() {
        return dialog("Confirm",
                text(message),
                text(""),
                text("y / enter confirm   n / esc cancel").dim()
        ).rounded().borderColor(Color.YELLOW).width(WIDTH);
    }
}
