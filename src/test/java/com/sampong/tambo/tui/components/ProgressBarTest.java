package com.sampong.tambo.tui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sampong.tambo._common.model.InstallProgress;
import com.sampong.tambo._common.model.InstallProgress.Phase;
import com.sampong.tambo.tui.state.ProgressSnapshot;

class ProgressBarTest {

    /** One slider step, matching {@code ProgressBar}'s animation speed. */
    private static final long STEP = 90_000_000L;

    @ParameterizedTest
    @CsvSource({
            "0.0,  ░░░░░░░░░░",
            "0.5,  █████░░░░░",
            "0.21, ██▏░░░░░░░",
            "0.99, █████████▉",
            "1.0,  ██████████",
    })
    void filledBarIsSmoothedToEighths(double fraction, String expected) {
        assertThat(ProgressBar.track(fraction, 10, 0)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.5, 0.0, 0.013, 0.333, 0.5, 0.875, 0.999, 1.0, 1.7})
    void barIsAlwaysExactlyItsWidth(double fraction) {
        assertThat(ProgressBar.track(fraction, 10, 0)).hasSize(10);
        assertThat(ProgressBar.track(fraction, ProgressBar.DETAIL_WIDTH, 0)).hasSize(ProgressBar.DETAIL_WIDTH);
    }

    @Test
    void sliderBouncesBetweenTheEnds() {
        assertThat(ProgressBar.track(null, 10, 0)).isEqualTo("███░░░░░░░");
        assertThat(ProgressBar.track(null, 10, STEP)).isEqualTo("░███░░░░░░");
        assertThat(ProgressBar.track(null, 10, 7 * STEP)).isEqualTo("░░░░░░░███");
        assertThat(ProgressBar.track(null, 10, 8 * STEP)).as("turned around").isEqualTo("░░░░░░███░");
        assertThat(ProgressBar.track(null, 10, 14 * STEP)).as("full cycle").isEqualTo("███░░░░░░░");
    }

    @Test
    void sliderStaysInsideTheTrack() {
        Set<String> frames = new HashSet<>();
        for (int step = 0; step < 40; step++) {
            String frame = ProgressBar.track(null, 10, step * STEP);
            assertThat(frame).hasSize(10).contains("███");
            assertThat(frame.chars().filter(c -> c == '█').count()).isEqualTo(3);
            frames.add(frame);
        }
        assertThat(frames).as("every position visited").hasSize(8);
    }

    @Test
    void inlineShowsPercentAndSpeedWhileDownloading() {
        ProgressSnapshot s = snapshot("node@22.11.0",
                new InstallProgress("node@22.11.0", Phase.DOWNLOADING, 0.5, null, "17.1/34.2 MB", "3.4 MB/s", null));

        assertThat(ProgressBar.inline(s)).isEqualTo("█████░░░░░  50%  3.4 MB/s");
    }

    @Test
    void inlineNamesThePhaseWhenThereIsNoFraction() {
        ProgressSnapshot s = snapshot("nodejs", InstallProgress.of(null, Phase.EXTRACTING));

        assertThat(ProgressBar.inline(s)).endsWith("  extracting").hasSize(ProgressBar.ROW_WIDTH + "  extracting".length());
    }

    @Test
    void compactLeadsWithTheSubject() {
        ProgressSnapshot s = snapshot("nodejs",
                new InstallProgress(null, Phase.DOWNLOADING, 0.5, null, null, null, null));

        assertThat(ProgressBar.compact(s)).isEqualTo("nodejs ████░░░░  50%");
    }

    @Test
    void wideOmitsTheFigures() {
        ProgressSnapshot s = snapshot("nodejs",
                new InstallProgress(null, Phase.DOWNLOADING, 0.25, null, "1/4 MB", "1 MB/s", "3s"));

        assertThat(ProgressBar.wide(s)).isEqualTo("██████" + "░".repeat(18) + "  25%");
    }

    /** A snapshot taken now with no rate, so the render-time clock cannot move it. */
    private static ProgressSnapshot snapshot(String subject, InstallProgress progress) {
        return new ProgressSnapshot(subject, progress, progress.fraction(), System.nanoTime());
    }
}
