package com.sampong.tambo.tui.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sampong.tambo._common.model.InstallProgress;
import com.sampong.tambo._common.model.InstallProgress.Phase;

class UiStateTest {

    private final UiState state = new UiState();

    private static InstallProgress downloading(String tool, double fraction) {
        return new InstallProgress(tool, Phase.DOWNLOADING, fraction, null, null, null, null);
    }

    @Test
    void tryMarkBusySucceedsOnlyWhileIdle() {
        assertThat(state.tryMarkBusy("node@22")).isTrue();
        assertThat(state.tryMarkBusy("node@22")).as("already running").isFalse();
        assertThat(state.isBusy("node@22")).isTrue();
        assertThat(state.busyCount()).isEqualTo(1);

        state.clearBusy("node@22");

        assertThat(state.anyBusy()).isFalse();
        assertThat(state.tryMarkBusy("node@22")).isTrue();
    }

    @Test
    void clearBusyDropsStatusAndProgress() {
        state.tryMarkBusy("node@22");
        state.busyStatus("node@22", "some line");
        state.progress("node@22", "node@22", downloading("node@22", 0.5));

        state.clearBusy("node@22");

        assertThat(state.busyStatusFor("node@22")).isNull();
        assertThat(state.progressFor("node@22")).isNull();
        assertThat(state.allProgress()).isEmpty();
    }

    @Test
    void progressReportsOnlyTheFirstFrameOfEachPhase() {
        assertThat(state.progress("k", "nodejs", downloading(null, 0.1))).as("first reading").isTrue();
        assertThat(state.progress("k", "nodejs", downloading(null, 0.2))).as("same phase").isFalse();
        assertThat(state.progress("k", "nodejs", downloading(null, 0.3))).isFalse();
        assertThat(state.progress("k", "nodejs", InstallProgress.of(null, Phase.EXTRACTING))).as("new phase").isTrue();

        assertThat(state.progressFor("k").progress().phase()).isEqualTo(Phase.EXTRACTING);
    }

    @Test
    void oneOperationKeepsABarPerTool() {
        state.progress("upgrade:*", "node@22.12.0", downloading("node@22.12.0", 0.3));
        state.progress("upgrade:*", "python@3.14.6", downloading("python@3.14.6", 0.6));
        // A second phase for one tool must not count as a first frame for the other.
        assertThat(state.progress("upgrade:*", "python@3.14.6", downloading("python@3.14.6", 0.7))).isFalse();

        assertThat(state.progressFor("upgrade:*", "node").subject()).isEqualTo("node@22.12.0");
        assertThat(state.progressFor("upgrade:*", "python").subject()).isEqualTo("python@3.14.6");
        assertThat(state.progressFor("upgrade:*", "go")).isNull();
        assertThat(state.progressFor("other", "node")).isNull();
        assertThat(state.allProgress()).extracting(ProgressSnapshot::subject)
                .containsExactly("node@22.12.0", "python@3.14.6");
    }

    @Test
    void progressForAnOperationIsItsMostRecentReading() throws InterruptedException {
        state.progress("upgrade:*", "node@22.12.0", downloading("node@22.12.0", 0.3));
        Thread.sleep(2); // readings are ordered by System.nanoTime(); keep them apart
        state.progress("upgrade:*", "python@3.14.6", downloading("python@3.14.6", 0.6));

        assertThat(state.progressFor("upgrade:*").subject()).isEqualTo("python@3.14.6");
    }

    @Test
    void logKeepsTheNewest300Entries() {
        for (int i = 0; i < 350; i++) {
            state.addLog(LogLevel.INFO, "line " + i);
        }

        List<String> texts = new ArrayList<>();
        state.log().forEach(e -> texts.add(e.text()));
        assertThat(texts).hasSize(300);
        assertThat(texts.getFirst()).isEqualTo("line 50");
        assertThat(texts.getLast()).isEqualTo("line 349");
    }

    @Test
    void readingTheBackendBeforeItIsResolvedFailsLoudly() {
        assertThatThrownBy(state::backend).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("backend not resolved");
    }
}
