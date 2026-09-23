package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import com.sampong.tambo._common.model.AutoInstallStep;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The one question auto-install cannot answer for itself: the config asks for a version you do
 * not have, but you have a close one — Java 25.0.3 on disk against a {@code mise.toml} asking
 * for 25.0.4 — so is the point of this run to match the config exactly, or to avoid a download
 * you do not need? Both are reasonable, and which is right depends on why the versions drifted
 * apart, which is not in the config.
 * <p>
 * Asks once per tool rather than once for the run: a project can want its JDK pinned to the
 * letter and be perfectly happy reusing the Node it already has. The queue is walked in config
 * order and the answers are collected into one map, so the installs all start together once the
 * last question is answered instead of a dialog reopening over a running download.
 * <p>
 * Like {@link ConfirmModal} it has no focusable input of its own, so {@code GlobalKeyBindings}
 * feeds it keys while it is open.
 */
@RequiredArgsConstructor
public final class AutoInstallModal {

    private static final int WIDTH = 72;
    /** Columns the dialog's border and padding leave for text. */
    private static final int TEXT_WIDTH = WIDTH - 4;
    /** Width of the {@code "  k  "} key column in front of each option. */
    private static final int KEY_WIDTH = 5;

    @NonNull
    private final UiContext ctx;

    private boolean open;
    /** The near-misses still to be asked about, in config order; index 0 is on screen. */
    private final List<AutoInstallStep> queue = new ArrayList<>();
    private int index;
    /** Highlighted row in a {@code SUBSTITUTE} step's version list; 0 is the recommendation. */
    private int cursor;
    /** SDK name to the version the user chose; a skipped tool never gets an entry. */
    private final Map<String, String> choices = new LinkedHashMap<>();
    private @Nullable Consumer<Map<String, String>> onDecided;
    private @Nullable Runnable onCancel;
    private @Nullable String preOpenFocus;

    public boolean isOpen() {
        return open;
    }

    /**
     * Starts the walk. {@code onDecided} runs on the render thread once the last tool is
     * answered, with the chosen version per SDK; {@code onCancel} runs instead if the user
     * backs out of the whole run with Esc.
     */
    public void open(@NonNull List<AutoInstallStep> undecided,
                     @NonNull Consumer<Map<String, String>> onDecided,
                     @NonNull Runnable onCancel) {
        this.queue.clear();
        this.queue.addAll(undecided);
        this.choices.clear();
        this.index = 0;
        this.cursor = 0;
        this.onDecided = onDecided;
        this.onCancel = onCancel;
        this.preOpenFocus = ctx.focusedId();
        this.open = true;
        // No input to focus — clear focus so no panel handles keys underneath.
        ctx.clearFocus();
    }

    private void close() {
        open = false;
        onDecided = null;
        onCancel = null;
        queue.clear();
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The context-sensitive hint line the footer shows while the dialog is open. */
    public String footerHint() {
        AutoInstallStep step = current();
        if (step != null && step.status() == AutoInstallStep.Status.SUBSTITUTE) {
            return "↑/↓ choose   enter use selected   s skip   esc cancel";
        }
        return "k keep installed   d download config version   s skip   esc cancel";
    }

    /**
     * Records an answer and moves to the next tool, finishing the walk after the last one.
     * Returns true when the event was consumed; {@code GlobalKeyBindings} calls this while the
     * dialog is open.
     */
    public boolean handleKey(KeyEvent key) {
        AutoInstallStep step = current();
        if (step == null) {
            close(); // nothing left to ask — should not happen, but never trap the keyboard
            return true;
        }
        if (key.isCancel()) {
            Runnable cancelled = onCancel;
            close();
            if (cancelled != null) {
                cancelled.run();
            }
            return true;
        }
        if (step.status() == AutoInstallStep.Status.SUBSTITUTE) {
            handleSubstituteKey(key, step);
            return true;
        }
        if (key.isChar('k')) {
            choices.put(step.sdk(), step.requireInstalledVersion());
            advance();
        } else if (key.isChar('d') || key.isConfirm()) {
            choices.put(step.sdk(), step.requestedVersion());
            advance();
        } else if (key.isChar('s')) {
            advance();
        }
        return true; // modal: swallow everything else
    }

    /**
     * Offline: pick one of the installed versions to stand in for the config's. Uses the same
     * up/down and j/k as every list in the app; there is no keep/download pair here because
     * nothing can be downloaded, so the only questions are which version and whether at all.
     */
    private void handleSubstituteKey(KeyEvent key, AutoInstallStep step) {
        int size = step.candidates().size();
        if (key.code() == KeyCode.UP || key.isChar('k')) {
            cursor = Math.max(0, cursor - 1);
        } else if (key.code() == KeyCode.DOWN || key.isChar('j')) {
            cursor = Math.min(size - 1, cursor + 1);
        } else if (key.isConfirm()) {
            choices.put(step.sdk(), step.candidates().get(cursor));
            advance();
        } else if (key.isChar('s')) {
            advance();
        }
    }

    /** Moves to the next tool, or finishes and hands the answers back. */
    private void advance() {
        cursor = 0;
        index++;
        if (index < queue.size()) {
            return;
        }
        Consumer<Map<String, String>> decided = onDecided;
        Map<String, String> answers = Map.copyOf(choices);
        close();
        if (decided != null) {
            decided.accept(answers);
        }
    }

    private @Nullable AutoInstallStep current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    public Element build() {
        AutoInstallStep step = current();
        if (step == null) {
            return dialog("Auto-install", text("")).rounded().width(WIDTH);
        }
        if (step.status() == AutoInstallStep.Status.SUBSTITUTE) {
            return buildSubstitute(step);
        }
        String installed = step.requireInstalledVersion();
        String configFile = ctx.backend().projectConfigFileName();

        List<Element> content = new ArrayList<>();
        content.addAll(Ui.hanging(text(step.sdk()).bold().cyan(), step.sdk().length(),
                "  — " + configFile + " asks for a version you do not have", TEXT_WIDTH, TextElement::dim));
        content.add(text(""));
        content.add(row(
                text("  installed  ").dim(),
                text(installed).bold().fg(Color.GREEN)));
        content.add(row(
                text("  config     ").dim(),
                text(step.requestedVersion()).bold().fg(Color.YELLOW)));
        content.add(text(""));
        for (String line : Ui.wordWrap("Same major release, different build. Keep what is on disk and "
                + "point the project at it, or download exactly what the config asks for?", TEXT_WIDTH)) {
            content.add(text(line).dim());
        }
        content.add(text(""));
        content.addAll(option("k", "keep " + installed + " and pin it to this project"));
        content.addAll(option("d", "download " + step.requestedVersion() + ", as " + configFile + " says"));
        content.addAll(option("s", "skip " + step.sdk() + " — leave it alone"));
        content.add(text(""));
        content.add(row(
                text("esc cancels the whole run").dim(),
                text("   "),
                text((index + 1) + " of " + queue.size()).dim()));

        return dialog("Auto-install — " + step.sdk(), content.toArray(new Element[0]))
                .rounded().borderColor(Color.YELLOW).width(WIDTH);
    }

    /** One {@code k/d/s} answer: the key, then its description wrapped under itself. */
    private static List<Element> option(String key, String description) {
        return Ui.hanging(text("  " + key + "  ").bold().yellow(), KEY_WIDTH, description, TEXT_WIDTH, d -> d);
    }

    /** The offline question: which installed version stands in for the one the config wants. */
    private Element buildSubstitute(AutoInstallStep step) {
        String configFile = ctx.backend().projectConfigFileName();

        List<Element> content = new ArrayList<>();
        content.addAll(Ui.hanging(text(step.sdk()).bold().cyan(), step.sdk().length(),
                "  — offline, " + configFile + "'s version cannot be downloaded", TEXT_WIDTH, TextElement::dim));
        content.add(text(""));
        content.add(row(
                text("  config     ").dim(),
                text(step.requestedVersion()).bold().fg(Color.YELLOW),
                text("  not installed").dim()));
        content.add(text(""));
        for (String line : Ui.wordWrap("Use one of the versions already on disk instead? It is pinned "
                + "in " + configFile + " in place of the config's version.", TEXT_WIDTH)) {
            content.add(text(line).dim());
        }
        content.add(text(""));
        List<String> candidates = step.candidates();
        for (int i = 0; i < candidates.size(); i++) {
            boolean selected = i == cursor;
            Element version = selected
                    ? text(candidates.get(i)).bold().fg(Color.GREEN)
                    : text(candidates.get(i));
            content.add(row(
                    text(selected ? "  ▸ " : "    ").bold().yellow(),
                    version,
                    text(i == 0 ? "   closest — recommended" : "").dim()));
        }
        content.add(text(""));
        content.add(row(
                text("enter use selected · s skip · esc cancels the whole run").dim(),
                text("   "),
                text((index + 1) + " of " + queue.size()).dim()));

        return dialog("Auto-install (offline) — " + step.sdk(), content.toArray(new Element[0]))
                .rounded().borderColor(Color.YELLOW).width(WIDTH);
    }
}
