package com.sampong.tambo.tui.panel;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textArea;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;

import com.sampong.tambo.tui.LogLevel;
import com.sampong.tambo.tui.PanelIds;
import com.sampong.tambo.tui.UiContext;

/**
 * An in-app editor for mise config files ({@code ./mise.toml} or the global
 * config): a focused multi-line text area inside a dialog. Ctrl+S saves and
 * triggers a refresh so mise picks the change up; Esc closes — asking once
 * more first when there are unsaved changes.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}.
 */
public final class ConfigEditorModal {

    private static final int EDITOR_ROWS = 20;
    private static final int WIDTH = 84;

    private final UiContext ctx;

    private boolean open;
    private Path file;
    private String title = "";
    private String originalText = "";
    private final TextAreaState buffer = new TextAreaState();
    private boolean confirmDiscard;
    private boolean newFile;
    private String preOpenFocus;

    public ConfigEditorModal(UiContext ctx) {
        this.ctx = ctx;
    }

    public boolean isOpen() {
        return open;
    }

    /** Opens the editor on a config file; logs an error and stays closed when it can't be read. */
    public void open(Path file, String title) {
        boolean exists = Files.exists(file);
        String content;
        try {
            content = exists ? Files.readString(file) : "";
        } catch (IOException e) {
            ctx.state().addLog(LogLevel.ERROR, "Cannot read " + file + ": " + e.getMessage());
            return;
        }
        this.file = file;
        this.title = title;
        this.newFile = !exists;
        this.originalText = content;
        buffer.setText(content);
        buffer.moveCursorToStart();
        confirmDiscard = false;
        preOpenFocus = ctx.focusedId();
        open = true;
        ctx.focus(PanelIds.CONFIG_EDITOR);
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The context-sensitive hint line the footer shows while the editor is open. */
    public String footerHint() {
        return "type to edit   ctrl+s save & apply   esc " + (dirty() ? "discard" : "close");
    }

    public Element build() {
        Element statusLine = confirmDiscard
                ? text("Unsaved changes — Esc again to discard, Ctrl+S to save").fg(Color.RED)
                : text("ctrl+s save & apply   esc " + (dirty() ? "discard" : "close")).dim();

        String dialogTitle = "Edit " + title
                + (newFile ? " (new file)" : "")
                + (dirty() ? " •" : "");

        return dialog(dialogTitle,
                textArea(buffer)
                        .showLineNumbers()
                        .id(PanelIds.CONFIG_EDITOR)
                        .focusable(true)
                        .onKeyEvent(this::handleKey)
                        .onTextChange(t -> confirmDiscard = false)
                        .rounded()
                        .borderColor(Color.DARK_GRAY)
                        .focusedBorderColor(Color.CYAN)
                        .constraint(length(EDITOR_ROWS)),
                text(""),
                statusLine
        ).rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private boolean dirty() {
        return !buffer.text().equals(originalText);
    }

    /**
     * Handles the keys the text area itself doesn't consume: Ctrl+S (save) and
     * Esc (close, with a discard confirmation when dirty). Everything else is
     * swallowed so no global shortcut fires under the modal.
     */
    private EventResult handleKey(KeyEvent event) {
        if (event.hasCtrl() && event.isCharIgnoreCase('s')) {
            String content = buffer.text();
            Path target = file;
            close();
            ctx.actions().saveConfig(target, content);
            return EventResult.HANDLED;
        }
        if (event.isCancel()) {
            if (dirty() && !confirmDiscard) {
                confirmDiscard = true;
            } else {
                close();
            }
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }
}
