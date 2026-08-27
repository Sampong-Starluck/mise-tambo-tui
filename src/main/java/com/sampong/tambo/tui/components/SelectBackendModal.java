package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.function.Consumer;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * The first-run picker for which version manager tambo should use in this project —
 * shown in place of the normal layout when startup found neither a {@code --backend}
 * flag nor an existing {@code mise.toml}/{@code .vfox.toml} to decide it automatically.
 * <p>
 * This replaces a legacy raw-console prompt that read {@code System.in} directly
 * before the TUI backend opened the terminal: closing that reader raced the backend
 * for ownership of stdin and crashed. Routing the choice through the TUI's own input
 * loop instead means only one thing ever reads stdin, avoiding that whole bug class.
 */
public final class SelectBackendModal {

    private static final int WIDTH = 60;

    @Getter
    private boolean open;
    private @Nullable Consumer<Boolean> onPick;

    /** Opens the picker; {@code onPick} runs on the render thread with true=vfox, false=mise. */
    public void open(@NonNull Consumer<Boolean> onPick) {
        this.onPick = onPick;
        this.open = true;
    }

    private void pick(boolean vfox) {
        open = false;
        Consumer<Boolean> action = onPick;
        onPick = null;
        if (action != null) {
            action.accept(vfox);
        }
    }

    /** Consumes every key while open: {@code m}/Enter picks mise, {@code v} picks vfox. */
    public void handleKey(KeyEvent key) {
        if (key.isChar('v') || key.isChar('V')) {
            pick(true);
        } else if (key.isChar('m') || key.isChar('M') || key.isConfirm()) {
            pick(false);
        }
        // any other key: ignored, stays open until an explicit choice is made
    }

    public Element build() {
        return dialog("Welcome to tambo",
                text("No mise.toml or .vfox.toml found in this project."),
                text(""),
                text("Which version manager should tambo use here?"),
                text(""),
                text("  m   mise").bold(),
                text("  v   vfox").bold(),
                text(""),
                text("m / enter for mise, v for vfox").dim()
        ).rounded().borderColor(Color.CYAN).width(WIDTH);
    }
}
