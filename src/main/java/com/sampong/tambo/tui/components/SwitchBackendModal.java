package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The {@code B} picker for which TamboUI terminal backend to render with. Lists all three
 * backends with a short feature/risk blurb each; the cursor starts on whichever backend
 * is active now, {@code ↑}/{@code ↓} moves it, and Enter is the confirmation — it applies
 * the choice (persisted to {@code tambo.properties}, effective on restart) and closes.
 * Esc cancels without changing anything.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}. Has no
 * focusable input of its own, so like {@link ConfirmModal} it reads its keys through the
 * global handler in {@code MiseTuiApp} rather than an {@code onKeyEvent}.
 */
@RequiredArgsConstructor
public final class SwitchBackendModal {

    private static final int WIDTH = 76;

    private record Choice(String id, String label, String description) {
    }

    private static final List<Choice> CHOICES = List.of(
            new Choice("jline3", "jline3 (recommended, default)",
                    "Pure-Java terminal I/O. Most portable — works under IDE run consoles, "
                            + "Git Bash/MinTTY, and GraalVM native-image alike."),
            new Choice("panama", "panama",
                    "Native FFM terminal I/O. Can crash the process on terminal resize under "
                            + "GraalVM native-image, and fails to start under Windows Git Bash/MinTTY."),
            new Choice("aesh", "aesh",
                    "Pure-Java terminal I/O via the aesh-readline library. An alternative to "
                            + "jline3 built on a different terminal stack; less exercised by this app "
                            + "than the other two, so treat it as experimental here."));

    @NonNull
    private final UiContext ctx;

    private boolean open;
    private int index;
    private @Nullable Consumer<String> onConfirm;
    private @Nullable String preOpenFocus;

    public boolean isOpen() {
        return open;
    }

    /**
     * Opens the picker with the cursor on the currently active backend.
     * {@code onConfirm} runs with the chosen backend id ({@code "jline3"}/{@code "panama"})
     * on the render thread if the user presses Enter; nothing runs on Esc.
     */
    public void open(@NonNull Consumer<String> onConfirm) {
        this.onConfirm = onConfirm;
        this.index = indexOf(ctx.uiBackend());
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

    /** The context-sensitive hint line the footer shows while the modal is open. */
    public String footerHint() {
        return "↑/↓ select   enter switch   esc cancel";
    }

    /**
     * Handles list navigation and confirm/cancel. Returns true when the event was
     * consumed; {@code MiseTuiApp}'s global handler calls this while the modal is open.
     */
    public boolean handleKey(KeyEvent key) {
        if (key.isCancel()) {
            close();
            return true;
        }
        if (key.isConfirm()) {
            Consumer<String> action = onConfirm;
            String chosen = CHOICES.get(index).id();
            close();
            if (action != null) {
                action.accept(chosen);
            }
            return true;
        }
        if (key.code() == KeyCode.UP) {
            index = Math.max(0, index - 1);
            return true;
        }
        if (key.code() == KeyCode.DOWN) {
            index = Math.min(CHOICES.size() - 1, index + 1);
            return true;
        }
        return true; // modal: swallow everything else
    }

    public Element build() {
        String active = ctx.uiBackend();
        List<Element> content = new ArrayList<>();
        content.add(text("Terminal rendering backend — takes effect on restart.").dim());
        content.add(text(""));
        for (int i = 0; i < CHOICES.size(); i++) {
            Choice c = CHOICES.get(i);
            boolean sel = i == index;
            boolean current = c.id().equals(active);
            content.add(row(
                    text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                    sel ? text(c.label()).bold().cyan() : text(c.label()).bold(),
                    text(current ? "  (current)" : "").dim()));
            for (String line : Ui.wordWrap(c.description(), WIDTH - 8)) {
                content.add(text("    " + line).dim());
            }
            content.add(text(""));
        }
        content.add(text("↑/↓ select   enter switch   esc cancel").dim());

        return dialog("Switch UI Backend", content.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private static int indexOf(String id) {
        for (int i = 0; i < CHOICES.size(); i++) {
            if (CHOICES.get(i).id().equals(id)) {
                return i;
            }
        }
        return 0;
    }
}
