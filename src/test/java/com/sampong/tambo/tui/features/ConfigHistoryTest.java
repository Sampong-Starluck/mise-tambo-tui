package com.sampong.tambo.tui.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigHistoryTest {

    private static final String OLD = "[tools]\nnode = \"22.9.0\"\n";
    private static final String NEW = "[tools]\nnode = \"22.11.0\"\n";

    @TempDir
    Path dir;

    private final ConfigHistory history = new ConfigHistory(
            Clock.fixed(Instant.parse("2026-10-21T12:04:30Z"), ZoneOffset.UTC));

    /** Mimics the lead's flow: the file is written by the app, then recorded. */
    private boolean save(Path file, String before, String after) throws IOException {
        Files.writeString(file, after);
        return history.record(file, "mise.toml", before, after);
    }

    @Test
    void undoRestoresThePreviousContent() throws IOException {
        Path file = dir.resolve("mise.toml");
        Files.writeString(file, OLD);
        save(file, OLD, NEW);

        ConfigHistory.Result result = history.undo();

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isEqualTo("Undid: node 22.9.0 → 22.11.0 (mise.toml)");
        assertThat(Files.readString(file)).isEqualTo(OLD);
        assertThat(history.canUndo()).isFalse();
        assertThat(history.canRedo()).isTrue();
    }

    @Test
    void redoReappliesTheChange() throws IOException {
        Path file = dir.resolve("mise.toml");
        Files.writeString(file, OLD);
        save(file, OLD, NEW);
        history.undo();

        ConfigHistory.Result result = history.redo();

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isEqualTo("Redid: node 22.9.0 → 22.11.0 (mise.toml)");
        assertThat(Files.readString(file)).isEqualTo(NEW);
        assertThat(history.canRedo()).isFalse();
        assertThat(history.canUndo()).isTrue();
    }

    @Test
    void undoIsRefusedWhenTheFileChangedSinceAndKeepsTheChange() throws IOException {
        Path file = dir.resolve("mise.toml");
        Files.writeString(file, OLD);
        save(file, OLD, NEW);
        Files.writeString(file, "[tools]\nnode = \"24\"\n"); // edited in another editor

        ConfigHistory.Result result = history.undo();

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).isEqualTo("mise.toml changed since — undo skipped");
        assertThat(Files.readString(file)).isEqualTo("[tools]\nnode = \"24\"\n");
        assertThat(history.canUndo()).as("change kept for a later try").isTrue();
    }

    @Test
    void redoIsRefusedWhenTheFileChangedSince() throws IOException {
        Path file = dir.resolve("mise.toml");
        Files.writeString(file, OLD);
        save(file, OLD, NEW);
        history.undo();
        Files.writeString(file, "edited\n");

        ConfigHistory.Result result = history.redo();

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).isEqualTo("mise.toml changed since — redo skipped");
        assertThat(Files.readString(file)).isEqualTo("edited\n");
        assertThat(history.canRedo()).isTrue();
    }

    @Test
    void aNoOpRecordIsIgnored() {
        assertThat(history.record(dir.resolve("mise.toml"), "mise.toml", OLD, OLD)).isFalse();
        assertThat(history.record(dir.resolve("mise.toml"), "mise.toml", null, null)).isFalse();

        assertThat(history.canUndo()).isFalse();
        assertThat(history.undo().message()).isEqualTo("Nothing to undo");
    }

    @Test
    void aNewRecordClearsTheRedoStack() throws IOException {
        Path file = dir.resolve("mise.toml");
        Files.writeString(file, OLD);
        save(file, OLD, NEW);
        history.undo();
        assertThat(history.canRedo()).isTrue();

        save(file, OLD, "[tools]\nnode = \"23\"\n");

        assertThat(history.canRedo()).isFalse();
        assertThat(history.redo().message()).isEqualTo("Nothing to redo");
    }

    @Test
    void undoOfACreatedFileDeletesIt() throws IOException {
        Path file = dir.resolve("mise.toml");
        save(file, null, NEW);

        ConfigHistory.Result result = history.undo();

        assertThat(result.ok()).isTrue();
        assertThat(file).doesNotExist();
        assertThat(history.redo().ok()).isTrue();
        assertThat(Files.readString(file)).isEqualTo(NEW);
    }

    @Test
    void theUndoStackIsCappedAtFifty() {
        Path file = dir.resolve("mise.toml");
        for (int i = 0; i < 60; i++) {
            history.record(file, "mise.toml", "v" + i, "v" + (i + 1));
        }

        assertThat(history.recent(100)).hasSize(50);
        assertThat(history.recent(1).get(0).before()).isEqualTo("v59");
        assertThat(history.recent(100).get(49).before()).as("oldest kept").isEqualTo("v10");
    }

    @Test
    void recentIsNewestFirstAndBounded() {
        Path file = dir.resolve("mise.toml");
        history.record(file, "mise.toml", "a", "b");
        history.record(file, "global", "c", "d");

        assertThat(history.recent(2)).extracting(ConfigHistory.Change::scope)
                .containsExactly("global", "mise.toml");
        assertThat(history.recent(1)).extracting(ConfigHistory.Change::scope).containsExactly("global");
        assertThat(history.recent(0)).isEmpty();
    }

    @Test
    void lineFormatsTheJobsViewRow() {
        Path file = dir.resolve("mise.toml");
        history.record(file, "mise.toml", OLD, NEW);

        String line = ConfigHistory.line(history.recent(1).get(0));

        assertThat(line).isEqualTo("12:04  mise.toml   node 22.9.0 → 22.11.0");
    }

    @Test
    void lineHasTheScopeColumnPaddedToNineCharacters() {
        history.record(dir.resolve("g"), "global", "[tools]\n", "[tools]\nnode = \"22\"\n");

        assertThat(ConfigHistory.line(history.recent(1).get(0)))
                .isEqualTo("12:04  global      + node 22");
    }

    @Test
    void emptyStacksReportNothingToDo() {
        assertThat(history.undo().ok()).isFalse();
        assertThat(history.undo().message()).isEqualTo("Nothing to undo");
        assertThat(history.redo().message()).isEqualTo("Nothing to redo");
    }

    @Test
    void readReturnsContentOrNullWhenMissing() throws IOException {
        Path file = dir.resolve("mise.toml");
        assertThat(ConfigHistory.read(file)).isNull();

        Files.writeString(file, NEW);
        assertThat(ConfigHistory.read(file)).isEqualTo(NEW);
    }
}
