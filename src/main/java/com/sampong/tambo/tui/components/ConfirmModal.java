package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

import com.sampong.tambo.tui.state.UiContext;

import lombok.Getter;
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
    /** Columns the dialog's border and padding leave for the message. */
    private static final int TEXT_WIDTH = WIDTH - 4;

    @NonNull
    private final UiContext ctx;

    @Getter
    private boolean open;
    private String message = "";
    private @Nullable Runnable onConfirm;
    private @Nullable String preOpenFocus;

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
        // The message is whatever the caller asked about — often a path or a list of tools —
        // so it is wrapped to the dialog rather than trusted to fit on one line.
        List<Element> content = new ArrayList<>();
        for (String line : Ui.wordWrap(message, TEXT_WIDTH)) {
            content.add(text(line));
        }
        content.add(text(""));
        content.add(text("y / enter confirm   n / esc cancel").dim());
        return dialog("Confirm", content.toArray(new Element[0]))
                .rounded().borderColor(Color.YELLOW).width(WIDTH);
    }
}
