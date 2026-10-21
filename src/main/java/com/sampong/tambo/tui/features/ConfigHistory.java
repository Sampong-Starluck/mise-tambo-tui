package com.sampong.tambo.tui.features;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.util.AtomicFileWriter;

import lombok.NonNull;

/**
 * Undo and redo for changes to config files, in the spirit of lazygit's undo or lazy.nvim's
 * lockfile restore.
 * <p>
 * The app reads a config before and after each mutating operation and {@link #record}s the pair.
 * Undo writes the old text back; redo writes the new text again. Only the file is restored —
 * anything a {@code mise install} downloaded stays on disk, because re-downloading would be slow
 * and nothing in the config depends on it.
 * <p>
 * Undo refuses when the file no longer holds the text this app wrote, since the user may have
 * edited it in another editor and silently overwriting that would lose their work.
 * <p>
 * Not thread-safe: it is touched from the render thread only.
 */
public final class ConfigHistory {

    /** Enough to back out a session of experiments, few enough that the Jobs view stays readable. */
    private static final int CAP = 50;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * One recorded change. {@code before} and {@code after} are the full file text, with
     * {@code null} meaning the file did not exist.
     */
    public record Change(Path file, String scope, @Nullable String before, @Nullable String after,
            String summary, LocalTime at) {
    }

    /** The outcome of an undo or redo, with a message suitable for the status line. */
    public record Result(boolean ok, String message) {
    }

    private final Clock clock;
    private final Deque<Change> undoStack = new ArrayDeque<>();
    private final Deque<Change> redoStack = new ArrayDeque<>();

    public ConfigHistory(@NonNull Clock clock) {
        this.clock = clock;
    }

    public ConfigHistory() {
        this(Clock.systemDefaultZone());
    }

    /**
     * The file's text as UTF-8, or {@code null} when it is missing or cannot be read. Both cases
     * are treated the same by the history — "no file" is a state it can record and restore.
     */
    public static @Nullable String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Remembers a change so it can be undone. Returns {@code false}, and records nothing, when the
     * text did not actually change. A new change invalidates anything that was undone, so the redo
     * stack is cleared.
     *
     * @param scope a short label for the Jobs view, such as {@code "mise.toml"} or {@code "global"}
     */
    public boolean record(@NonNull Path file, @NonNull String scope,
            @Nullable String before, @Nullable String after) {
        if (Objects.equals(before, after)) {
            return false;
        }
        undoStack.push(new Change(file, scope, before, after,
                ToolsDiff.summary(before, after), LocalTime.now(clock)));
        redoStack.clear();
        while (undoStack.size() > CAP) {
            undoStack.removeLast();
        }
        return true;
    }

    /**
     * Puts the newest change's {@code before} text back. Refused — and the change kept for a later
     * try — when the file no longer matches what this app wrote.
     */
    public Result undo() {
        Change change = undoStack.peek();
        if (change == null) {
            return new Result(false, "Nothing to undo");
        }
        String current = read(change.file());
        if (!Objects.equals(current, change.after())) {
            return new Result(false, change.scope() + " changed since — undo skipped");
        }
        try {
            apply(change.file(), change.before());
        } catch (IOException | RuntimeException e) {
            return new Result(false, "Undo failed: " + messageOf(e));
        }
        undoStack.pop();
        redoStack.push(change);
        return new Result(true, "Undid: " + change.summary() + " (" + change.scope() + ")");
    }

    /** Re-applies the newest undone change's {@code after} text. The mirror of {@link #undo()}. */
    public Result redo() {
        Change change = redoStack.peek();
        if (change == null) {
            return new Result(false, "Nothing to redo");
        }
        String current = read(change.file());
        if (!Objects.equals(current, change.before())) {
            return new Result(false, change.scope() + " changed since — redo skipped");
        }
        try {
            apply(change.file(), change.after());
        } catch (IOException | RuntimeException e) {
            return new Result(false, "Redo failed: " + messageOf(e));
        }
        redoStack.pop();
        undoStack.push(change);
        return new Result(true, "Redid: " + change.summary() + " (" + change.scope() + ")");
    }

    /** The newest {@code max} changes, newest first, for the Jobs view's "Recent changes". */
    public List<Change> recent(int max) {
        return undoStack.stream().limit(Math.max(0, max)).toList();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** One Jobs-view row, e.g. {@code "12:04  mise.toml   node 22.9.0 → 22.11.0"}. */
    public static String line(Change c) {
        return String.format("%s  %-9s   %s", c.at().format(HH_MM), c.scope(), c.summary());
    }

    /** Writes {@code content}, or removes the file when it is {@code null} (the state before it was created). */
    private static void apply(Path file, @Nullable String content) throws IOException {
        if (content == null) {
            Files.deleteIfExists(file);
        } else {
            AtomicFileWriter.writeString(file, content);
        }
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
