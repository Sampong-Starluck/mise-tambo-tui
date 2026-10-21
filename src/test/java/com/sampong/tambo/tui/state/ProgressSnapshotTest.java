package com.sampong.tambo.tui.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import com.sampong.tambo._common.model.InstallProgress;
import com.sampong.tambo._common.model.InstallProgress.Phase;

class ProgressSnapshotTest {

    private static final long SECOND = 1_000_000_000L;

    private static InstallProgress downloading(double fraction, Double rate) {
        return new InstallProgress("node@22.11.0", Phase.DOWNLOADING, fraction, rate, null, null, null);
    }

    @Test
    void extrapolatesAtTheReportedRate() {
        ProgressSnapshot s = ProgressSnapshot.first("node@22.11.0", downloading(0.20, 0.10), 0);

        assertThat(s.fractionAt(0)).isCloseTo(0.20, within(1e-9));
        assertThat(s.fractionAt(3 * SECOND)).isCloseTo(0.50, within(1e-9));
    }

    @Test
    void extrapolationStopsShortOfFull() {
        ProgressSnapshot s = ProgressSnapshot.first("node@22.11.0", downloading(0.90, 0.10), 0);

        assertThat(s.fractionAt(60 * SECOND)).isEqualTo(0.99);
    }

    @Test
    void readingOfOneHundredPercentIsShownAsFull() {
        ProgressSnapshot s = ProgressSnapshot.first("node@22.11.0", downloading(1.0, 0.10), 0);

        assertThat(s.fractionAt(5 * SECOND)).isEqualTo(1.0);
    }

    @Test
    void withoutARateTheBarHoldsStill() {
        ProgressSnapshot s = ProgressSnapshot.first("nodejs", downloading(0.40, null), 0);

        assertThat(s.fractionAt(10 * SECOND)).isEqualTo(0.40);
    }

    @Test
    void indeterminatePhaseHasNoFraction() {
        ProgressSnapshot s = ProgressSnapshot.first("nodejs", InstallProgress.of(null, Phase.EXTRACTING), 0);

        assertThat(s.fractionAt(SECOND)).isNull();
    }

    @Test
    void aLaggingReadingPausesTheBarInsteadOfPullingItBack() {
        ProgressSnapshot s = ProgressSnapshot.first("node@22.11.0", downloading(0.20, 0.10), 0);
        // Extrapolated to 0.50 by t=3s, but the next real reading only says 0.45.
        ProgressSnapshot next = s.advance(downloading(0.45, 0.10), 3 * SECOND);

        assertThat(next.baseFraction()).isCloseTo(0.50, within(1e-9));
        assertThat(next.fractionAt(3 * SECOND)).isCloseTo(0.50, within(1e-9));
    }

    @Test
    void aLeadingReadingIsTakenAsIs() {
        ProgressSnapshot s = ProgressSnapshot.first("node@22.11.0", downloading(0.20, 0.10), 0);
        ProgressSnapshot next = s.advance(downloading(0.60, 0.10), 3 * SECOND);

        assertThat(next.baseFraction()).isEqualTo(0.60);
    }

    @Test
    void aNewPhaseStartsFromItsOwnReading() {
        ProgressSnapshot s = ProgressSnapshot.first("node@22.11.0", downloading(0.95, 0.10), 0);
        ProgressSnapshot next = s.advance(InstallProgress.of("node@22.11.0", Phase.EXTRACTING), SECOND);

        assertThat(next.progress().phase()).isEqualTo(Phase.EXTRACTING);
        assertThat(next.baseFraction()).isNull();
    }

    @Test
    void toolNameDropsTheVersion() {
        assertThat(snapshot("node@22.11.0").toolName()).isEqualTo("node");
        assertThat(snapshot("nodejs").toolName()).isEqualTo("nodejs");
        assertThat(snapshot("npm:@scope/pkg@1.0.0").toolName()).isEqualTo("npm:@scope/pkg");
        assertThat(snapshot("@scope").toolName()).isEqualTo("@scope");
    }

    private static ProgressSnapshot snapshot(String subject) {
        return ProgressSnapshot.first(subject, InstallProgress.of(null, Phase.PREPARING), 0);
    }
}
