package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.tui.features.Clipboard;
import com.sampong.tambo.tui.state.Job;
import com.sampong.tambo.tui.state.JobTracker;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.ProgressSnapshot;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The {@code w} jobs view: every background install, upgrade and removal, with the selected job's
 * output beside it. Modelled on yazi's task manager — a list of work to glance at, and one row to
 * drill into when something goes wrong.
 * <p>
 * It is a full-screen view rather than a dialog: the layout shows it in place of the panel body
 * while it is open, because a list of jobs and the output of one of them need the room. Like
 * {@link ConfirmModal} it has no focusable element of its own, so the global key handler feeds it
 * keys through {@link #handleKey}. Focus is saved on open and restored on close, as {@link HelpOverlay}
 * does.
 * <p>
 * Keys it does not use — {@code z}, {@code Z}, {@code q} and the rest — are returned as unhandled
 * so the global bindings still work while the view is up.
 */
@RequiredArgsConstructor
public final class JobsView implements Modal {

    private static final int STATE_WIDTH = 12;
    private static final int TIME_WIDTH = 6;
    private static final String QUEUED_TEXT = "waits for a free slot (" + JobTracker.CONCURRENCY + " at a time)";

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final JobTracker jobs;
    /** Called with a queued or running job when the user asks to cancel it. */
    @NonNull
    private final Consumer<Job> onCancel;
    /** The config undo history as display lines, newest first; supplied by the caller. */
    @NonNull
    private final Supplier<List<String>> recentChanges;

    private boolean open;
    private @Nullable String preOpenFocus;
    /**
     * The selected job's id, not its row index: a job moves up the list when it starts, so an
     * index would drift under the cursor. 0 means nothing has been selected yet (ids start at 1).
     */
    private long selectedId;

    @Override
    public boolean isOpen() {
        return open;
    }

    public void open() {
        preOpenFocus = ctx.focusedId();
        ctx.clearFocus();
        open = true;
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    public String footerHint() {
        return "JOBS  r retry  c cancel job  l log  z undo last change  Esc back";
    }

    // ==================== Rendering ====================

    public Element build() {
        List<Job> ordered = jobs.ordered();
        Job selected = selected(ordered);
        return column(
                jobsPanel(ordered, selected).constraint(fill(3)),
                row(outputPanel(selected).constraint(fill(3)), changesPanel().constraint(fill(2)))
                        .constraint(fill(2))
        );
    }

    private Panel jobsPanel(List<Job> ordered, @Nullable Job selected) {
        long now = jobs.now();
        ListElement<?> body = list()
                .highlightColor(ctx.theme().accent())
                .highlightSymbol("> ")
                .displayOnly()
                .scrollbar(ScrollBarPolicy.AS_NEEDED);
        if (ordered.isEmpty()) {
            body.add(text("No jobs yet — installs and upgrades show up here.").dim());
        } else {
            for (Job job : ordered) {
                body.add(jobRow(job, now));
            }
        }
        body.selected(selected == null ? 0 : ordered.indexOf(selected));

        Element header = row(
                text("STATE").dim().constraint(length(STATE_WIDTH)),
                text("JOB").dim().constraint(fill(2)),
                text("PROGRESS").dim().constraint(fill(3)),
                text("TIME").dim().constraint(length(TIME_WIDTH))
        ).spacing(1).constraint(length(1));

        return panel(" Jobs (w) ", column(header, body.constraint(fill())))
                .rounded()
                .borderColor(ctx.theme().accent());
    }

    private Row jobRow(Job job, long now) {
        return row(
                stateCell(job).constraint(length(STATE_WIDTH)),
                text(job.label()).constraint(fill(2)),
                progressCell(job).constraint(fill(3)),
                text(JobTracker.elapsed(job, now)).dim().constraint(length(TIME_WIDTH))
        ).spacing(1);
    }

    /** The state glyph and word; the spinner is read from the clock, so it animates on each frame. */
    private static TextElement stateCell(Job job) {
        return switch (job.state()) {
            case RUNNING -> text(Ui.spinner() + " running").fg(Color.CYAN);
            case QUEUED -> text("➜ queued").dim();
            case FAILED -> text("✗ failed").fg(Color.RED);
            case DONE -> text("✓ done").fg(Color.GREEN);
            case CANCELLED -> text("⊘ cancelled").dim();
        };
    }

    private TextElement progressCell(Job job) {
        return switch (job.state()) {
            case RUNNING -> runningCell(job);
            case QUEUED -> text(QUEUED_TEXT).dim();
            case FAILED -> job.error() != null
                    ? text(job.error()).fg(Color.RED)
                    : text("failed").fg(Color.RED);
            case DONE -> text(job.note() != null ? job.note() : "").dim();
            case CANCELLED -> text("cancelled").dim();
        };
    }

    private TextElement runningCell(Job job) {
        ProgressSnapshot progress = ctx.state().progressFor(job.busyKey());
        return progress != null
                ? text(ProgressBar.inline(progress))
                : text("working…").dim();
    }

    /** The selected job's command, its output, and — when it failed — how to act on that. */
    private Panel outputPanel(@Nullable Job selected) {
        if (selected == null) {
            ListElement<?> empty = list().displayOnly();
            empty.add(text("Nothing selected.").dim());
            return panel(" output ", empty.constraint(fill())).rounded().borderColor(ctx.theme().idle());
        }

        ListElement<?> body = list()
                .displayOnly()
                .autoScroll()
                .scrollbar(ScrollBarPolicy.AS_NEEDED);
        body.add(text(selected.command()).dim());
        List<String> lines = selected.output();
        for (String line : lines) {
            body.add(isError(line) ? text(line).fg(Color.RED) : text(line));
        }
        boolean failed = selected.state() == Job.State.FAILED;
        if (failed) {
            body.add(tryLine(selected));
        }
        // Pinning the selection to the last row is what keeps the tail — and the "Try:" line — in
        // view as output streams in; autoScroll follows the selection.
        body.selected(lines.size() + (failed ? 1 : 0));

        return panel(" " + selected.label() + " · output ", body.constraint(fill()))
                .rounded()
                .borderColor(failed ? Color.RED : ctx.theme().idle());
    }

    /** The one-line action hint shown under a failure; {@code r} appears only when a retry exists. */
    private static Row tryLine(Job job) {
        Row line = row(text("Try:  ").dim());
        if (job.retry() != null) {
            line.add(key("r"), text(" retry   ").dim());
        }
        line.add(key("l"), text(" full log   ").dim(), key("y"), text(" copy error").dim());
        return line;
    }

    private static TextElement key(String key) {
        return text(key).bold().fg(Color.CYAN);
    }

    /** Lines an install prints when it goes wrong; matched loosely, because tools differ in case. */
    private static boolean isError(String line) {
        return line.contains("ERROR") || line.contains("error");
    }

    private Panel changesPanel() {
        List<String> changes = recentChanges.get();
        ListElement<?> body = list().displayOnly().scrollbar(ScrollBarPolicy.AS_NEEDED);
        if (changes.isEmpty()) {
            body.add(text("No config changes yet").dim());
        } else {
            for (String change : changes) {
                body.add(text(change));
            }
        }
        // The footer is split by hand: the pane is narrow, and a long line would clip rather than wrap.
        Element footer = column(
                text("Undo restores the config file.").dim().constraint(length(1)),
                text("Installs and removals of downloads").dim().constraint(length(1)),
                text("can't be undone.").dim().constraint(length(1))
        );
        return panel(" Recent changes · z undo · Z redo ", column(body.constraint(fill()), footer))
                .rounded()
                .borderColor(ctx.theme().idle());
    }

    // ==================== Key handling ====================

    /**
     * Handles a key while the view is open. Returns true when the key was used. Keys this view
     * does not own return false so the global bindings still see them.
     */
    public boolean handleKey(KeyEvent key) {
        if (key.isCancel() || key.isChar('w')) {
            close();
            return true;
        }
        List<Job> ordered = jobs.ordered();
        Job selected = selected(ordered);

        if (Ui.isNavKey(key)) {
            if (selected != null) {
                int next = Ui.applyNav(key, ordered.indexOf(selected), ordered.size());
                selectedId = ordered.get(next).id();
            }
            return true;
        }
        if (key.isChar('r')) {
            // Only a failed job with a retry claims r; otherwise it falls through to the global
            // refresh, which is what r means everywhere else.
            if (selected == null || selected.state() != Job.State.FAILED || selected.retry() == null) {
                return false;
            }
            Runnable retry = selected.retry();
            // Dismissing first means the failure does not linger beside the run that replaces it.
            jobs.dismiss(selected.id());
            retry.run();
            return true;
        }
        if (key.isChar('c')) {
            // Consumed even when there is nothing to cancel: c means cancel-the-job here, and must
            // not fall through to a Tools panel's cancel behind the view.
            if (selected != null && selected.isActive()) {
                onCancel.accept(selected);
            }
            return true;
        }
        if (key.isChar('y')) {
            if (selected != null) {
                String text = selected.error() != null ? selected.error() : selected.lastOutput();
                if (text != null) {
                    Clipboard.copy(text);
                    ctx.state().addLog(LogLevel.INFO, "Copied error");
                }
            }
            return true;
        }
        if (key.isChar('l')) {
            close();
            ctx.focus(PanelIds.LOG);
            return true;
        }
        if (key.isChar('x')) {
            if (selected != null && selected.isFinished()) {
                jobs.dismiss(selected.id());
            }
            return true;
        }
        return false;
    }

    /**
     * The job the cursor is on. Falls back to the top of the list when the remembered job has
     * gone — dismissed, or evicted — so the cursor never points at nothing while jobs exist.
     */
    private @Nullable Job selected(List<Job> ordered) {
        if (ordered.isEmpty()) {
            return null;
        }
        for (Job job : ordered) {
            if (job.id() == selectedId) {
                return job;
            }
        }
        return ordered.getFirst();
    }

    // ==================== Header summary ====================

    /**
     * The app header's activity line, e.g. {@code ⠹ 2 running · 1 queued · ✗ 1 failed}. Zero
     * counts are left out; an empty string means there is nothing to say.
     */
    public static String headerSummary(JobTracker jobs) {
        List<String> parts = new ArrayList<>();
        if (jobs.running() > 0) {
            parts.add(Ui.spinner() + " " + jobs.running() + " running");
        }
        if (jobs.queued() > 0) {
            parts.add(jobs.queued() + " queued");
        }
        if (jobs.failed() > 0) {
            parts.add("✗ " + jobs.failed() + " failed");
        }
        return String.join(" · ", parts);
    }
}
