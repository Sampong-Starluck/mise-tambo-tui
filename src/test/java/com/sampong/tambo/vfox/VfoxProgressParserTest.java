package com.sampong.tambo.vfox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sampong.tambo._common.model.InstallProgress;
import com.sampong.tambo._common.model.InstallProgress.Phase;
import com.sampong.tambo.testsupport.Fixtures;

class VfoxProgressParserTest {

    private final VfoxProgressParser parser = VfoxProgressParser.INSTANCE;

    @Test
    void downloadFrameGivesPercentSpeedAndEta() {
        InstallProgress p = parser.parse("Downloading...   7% [=>                                    ] (4.7 MB/s) [0s:6s]");

        assertThat(p).isEqualTo(new InstallProgress(null, Phase.DOWNLOADING, 0.07, null, null, "4.7 MB/s", "6s"));
    }

    @Test
    void finalFrameHasNoEta() {
        InstallProgress p = parser.parse("Downloading... 100% [======================================] (4.0 MB/s)");

        assertThat(p).isNotNull();
        assertThat(p.fraction()).isEqualTo(1.0);
        assertThat(p.eta()).isNull();
    }

    @Test
    void stepsAroundTheDownloadAreIndeterminatePhases() {
        assertThat(parser.parse("Preinstalling nodejs@22.11.0...")).isEqualTo(InstallProgress.of(null, Phase.PREPARING));
        assertThat(parser.parse("Verifying checksum 905373a0...")).isEqualTo(InstallProgress.of(null, Phase.VERIFYING));
        assertThat(parser.parse("Unpacking C:\\vfox\\cache\\node.zip...")).isEqualTo(InstallProgress.of(null, Phase.EXTRACTING));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Install nodejs@22.11.0 success!",
            "Please use `vfox use nodejs@22.11.0` to use it.",
            "Downloading the plugin index",
            "",
    })
    void ordinaryOutputIsNotProgress(String line) {
        assertThat(parser.parse(line)).isNull();
        assertThat(parser.isNoise(line)).isFalse();
    }

    /**
     * The whole captured {@code vfox install nodejs@22.11.0}: the bar is redrawn with bare
     * {@code \r}s, which the reader splits into one line per frame.
     */
    @Test
    void realInstallCaptureReadsAsEveryFrame() {
        List<InstallProgress> progress = new ArrayList<>();
        List<String> output = new ArrayList<>();
        for (String line : Fixtures.streamedLines("vfox-install-nodejs.txt")) {
            if (line.isBlank()) {
                continue; // vfox clears the line with spaces between frames
            }
            InstallProgress p = parser.parse(line);
            if (p != null) {
                progress.add(p);
            } else {
                output.add(line);
            }
        }

        assertThat(progress).extracting(InstallProgress::phase).first().isEqualTo(Phase.PREPARING);
        assertThat(progress).extracting(InstallProgress::phase).endsWith(Phase.VERIFYING, Phase.EXTRACTING);
        List<Double> fractions = progress.stream()
                .filter(p -> p.phase() == Phase.DOWNLOADING)
                .map(InstallProgress::fraction)
                .toList();
        assertThat(fractions).hasSizeGreaterThan(90).isSortedAccordingTo(Double::compare);
        assertThat(fractions.getLast()).isEqualTo(1.0);
        assertThat(output).containsExactly(
                "Install nodejs@22.11.0 success!",
                "Please use `vfox use nodejs@22.11.0` to use it.");
    }
}
