package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.textInput;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

import com.sampong.tambo.tui.commands.CommandLineCompleter;
import com.sampong.tambo.tui.commands.CommandLineCompleter.Suggestion;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The k9s-style {@code :} bar: a single line at the bottom of the screen where the user types a
 * raw mise command ({@code install node@22}) and Enter runs it through the backend. Tab completes
 * from {@link CommandLineCompleter}, and Up/Down walk the suggestions or, with none showing, the
 * commands run earlier in this session.
 * <p>
 * The bar shows what Enter will do before it does it: a preview of the command and, for commands
 * that touch config or delete installs, a short note of the effect. That line is the reason the
 * bar exists rather than a plain text prompt — a typed mise command should not surprise anyone.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}. Its input has
 * focus while open, so keys reach {@link #handleKey} through the input rather than the global
 * handler.
 */
@RequiredArgsConstructor
public final class CommandLineBar implements Modal {

    /** Rows the bar occupies: a rounded border top and bottom around two content lines. */
    private static final int HEIGHT = 4;
    /** Commands kept for Up/Down recall; older ones fall off the front. */
    private static final int MAX_HISTORY = 50;
    private static final String PLACEHOLDER = "install node@22, use go@1.22, ls --json";

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final Supplier<CommandLineCompleter> completer;
    @NonNull
    private final Consumer<List<String>> onRun;

    @Getter
    private boolean open;
    private final TextInputState input = new TextInputState();
    private final List<String> history = new ArrayList<>();
    /** Position while walking history; {@code history.size()} means "back at the draft". */
    private int historyIndex;
    /** What the user had typed before they started walking history, restored on the way back. */
    private String draft = "";
    private int selected;
    /** The text {@link #selected} refers to; a different text means the list has been rebuilt. */
    private String selectedFor = "";
    private @Nullable String preOpenFocus;

    /** Opens the bar with {@code initial} in it, focused and ready to type. */
    public void open(@NonNull String initial) {
        preOpenFocus = ctx.focusedId();
        input.setText(initial);
        input.moveCursorToEnd();
        historyIndex = history.size();
        draft = "";
        selected = 0;
        selectedFor = initial;
        open = true;
        ctx.focus(PanelIds.COMMAND_LINE_INPUT);
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The text currently in the bar. */
    public String text() {
        return input.text();
    }

    /** Replaces the text in the bar and puts the cursor at its end. */
    public void setText(String text) {
        input.setText(text);
        input.moveCursorToEnd();
    }

    /**
     * The completions for the current text. Recomputed on every call, since the user types into
     * the input directly and nothing else tells us the text changed; the selection resets whenever
     * it has.
     */
    public List<Suggestion> suggestions() {
        String typed = input.text();
        if (!typed.equals(selectedFor)) {
            selectedFor = typed;
            selected = 0;
        }
        return completer.get().suggest(typed);
    }

    /** The context-sensitive hint line the footer shows while the bar is open. */
    public String footerHint() {
        return ": mise command   Tab complete  Enter run  Esc cancel";
    }

    /** Rows the bar takes at the bottom of the screen. */
    public int height() {
        return HEIGHT;
    }

    // ==================== Rendering ====================

    /** The bar itself: the input line with its right-hand hint, then the preview line. */
    public Element build() {
        CommandLineCompleter c = completer.get();
        String typed = input.text();
        Color accent = ctx.theme().accent();

        Element inputLine = row(
                Toolkit.text(":").fg(accent).bold(),
                textInput(input)
                        .placeholder(PLACEHOLDER)
                        .placeholderColor(Color.DARK_GRAY)
                        .id(PanelIds.COMMAND_LINE_INPUT)
                        .focusable(true)
                        .onKeyEvent(this::handleKey)
                        .constraint(fill()),
                Toolkit.text("mise command · ↑ history ").dim()
        );

        List<Element> preview = new ArrayList<>();
        preview.add(Toolkit.text("→ will run  ").dim());
        preview.add(Toolkit.text(c.preview(typed)).fg(accent));
        String effect = c.effect(typed);
        if (!effect.isEmpty()) {
            preview.add(Toolkit.text("  · ").dim());
            preview.add(Toolkit.text(effect).dim());
        }
        preview.add(Toolkit.text("   "));
        preview.add(Toolkit.text("Tab").fg(accent).bold());
        preview.add(Toolkit.text(" complete  ").dim());
        preview.add(Toolkit.text("Enter").fg(accent).bold());
        preview.add(Toolkit.text(" run  ").dim());
        preview.add(Toolkit.text("Esc").fg(accent).bold());
        preview.add(Toolkit.text(" cancel").dim());

        return panel(column(inputLine, row(preview.toArray(new Element[0]))))
                .rounded()
                .borderColor(accent)
                .constraint(length(HEIGHT));
    }

    /**
     * The completion list that sits just above the bar, or null when there is nothing to offer.
     * The selected row is marked and highlighted; a candidate's note sits dim to its right.
     */
    public @Nullable Element suggestionsPopup() {
        List<Suggestion> matches = suggestions();
        if (matches.isEmpty()) {
            return null;
        }
        int sel = Ui.clamp(selected, matches.size());
        Color accent = ctx.theme().accent();

        List<Element> rows = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            Suggestion s = matches.get(i);
            boolean on = i == sel;
            rows.add(row(
                    Toolkit.text(on ? "> " : "  ").fg(accent).bold(),
                    on ? Toolkit.text(s.display()).fg(accent).bold() : Toolkit.text(s.display()),
                    spacer(),
                    Toolkit.text(s.note().isEmpty() ? "" : s.note() + " ").dim()
            ));
        }
        return panel(rows.toArray(new Element[0]))
                .rounded()
                .borderColor(ctx.theme().idle())
                .constraint(length(matches.size() + 2));
    }

    // ==================== Key handling ====================

    /**
     * Tab applies the selected suggestion; Up/Down move through suggestions when there are any and
     * walk history otherwise; Enter runs; Esc closes. Every other key is consumed here so no panel
     * shortcut fires under the bar, while the input itself applies the typing.
     */
    public EventResult handleKey(KeyEvent key) {
        if (key.isCancel()) {
            close();
            return EventResult.HANDLED;
        }
        if (key.isConfirm()) {
            submit();
            return EventResult.HANDLED;
        }
        if (key.code() == KeyCode.TAB) {
            applySelected();
            return EventResult.HANDLED;
        }
        if (key.code() == KeyCode.UP) {
            move(-1);
            return EventResult.HANDLED;
        }
        if (key.code() == KeyCode.DOWN) {
            move(1);
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    private void applySelected() {
        List<Suggestion> matches = suggestions();
        if (matches.isEmpty()) {
            return;
        }
        Suggestion chosen = matches.get(Ui.clamp(selected, matches.size()));
        setText(completer.get().apply(input.text(), chosen));
    }

    private void move(int delta) {
        List<Suggestion> matches = suggestions();
        if (!matches.isEmpty()) {
            selected = Ui.clamp(selected + delta, matches.size());
        } else if (delta < 0) {
            olderCommand();
        } else {
            newerCommand();
        }
    }

    private void olderCommand() {
        if (historyIndex == 0) {
            return;
        }
        if (historyIndex == history.size()) {
            draft = input.text();
        }
        historyIndex--;
        setText(history.get(historyIndex));
    }

    private void newerCommand() {
        if (historyIndex >= history.size()) {
            return;
        }
        historyIndex++;
        setText(historyIndex == history.size() ? draft : history.get(historyIndex));
    }

    /**
     * Runs the typed command. Blank input just closes the bar. The bar closes before the command
     * is handed off, so focus is back on the panel the user came from when the command starts.
     */
    private void submit() {
        String typed = input.text();
        if (typed.isBlank()) {
            close();
            return;
        }
        remember(typed.strip());
        close();
        onRun.accept(CommandLineCompleter.tokens(typed));
    }

    /**
     * Records a command for Up/Down recall. A command identical to the last one is not stored
     * again, so repeating {@code ls} does not fill the history with copies.
     */
    private void remember(String command) {
        if (history.isEmpty() || !history.getLast().equals(command)) {
            history.add(command);
            if (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
        }
        historyIndex = history.size();
    }
}
