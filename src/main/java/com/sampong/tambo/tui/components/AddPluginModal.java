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
 * A one-line prompt for {@code vfox add}: registers a plugin standalone, without installing
 * any version — the syntax typed here is passed straight through to the CLI, so it mirrors
 * vfox's own {@code add [--alias <name>] [--source <url/path>] <plugin-name>}. vfox-only —
 * mise has no equivalent in this app (mise tools are always added implicitly via the registry
 * "Add SDK" flow instead). Enter submits, Esc cancels.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}.
 */
@RequiredArgsConstructor
public final class AddPluginModal {

    private static final int WIDTH = 76;

    @NonNull
    private final UiContext ctx;

    private boolean open;
    private final TextInputState input = new TextInputState();
    private @Nullable String preOpenFocus;

    public boolean isOpen() {
        return open;
    }

    public void open() {
        preOpenFocus = ctx.focusedId();
        input.clear();
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
        return "type plugin name [--alias x] [--source url]   enter add   esc cancel";
    }

    public Element build() {
        return dialog("Add vfox plugin",
                textInput(input)
                        .placeholder("nodejs   or   myplugin --alias foo --source https://…")
                        .placeholderColor(Color.DARK_GRAY)
                        .id(PanelIds.ADD_PLUGIN_INPUT)
                        .focusable(true)
                        .onKeyEvent(this::handleKey),
                text(""),
                text("enter add   esc cancel").dim()
        ).rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private EventResult handleKey(KeyEvent event) {
        if (event.isCancel()) {
            close();
            return EventResult.HANDLED;
        }
        if (event.isConfirm()) {
            String typed = input.text();
            close();
            ctx.actions().addPlugin(typed);
            return EventResult.HANDLED;
        }
        // Typing is applied by the input itself; swallow the rest so no panel
        // shortcut fires under the prompt.
        return EventResult.HANDLED;
    }
}
