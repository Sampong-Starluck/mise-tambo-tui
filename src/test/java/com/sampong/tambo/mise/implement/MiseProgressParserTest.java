package com.sampong.tambo.mise.implement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sampong.tambo._common.model.InstallProgress;
import com.sampong.tambo._common.model.InstallProgress.Phase;
import com.sampong.tambo.testsupport.Fixtures;

class MiseProgressParserTest {

    private final MiseProgressParser parser = MiseProgressParser.INSTANCE;

    @Test
    void downloadSnapshotGivesFractionRateAndFigures() {
        InstallProgress p = parser.parse("  node@22.11.0  downloading  6.0s  17.4/34.2 MB · 3.4 MB/s");

        assertThat(p).isNotNull();
        assertThat(p.tool()).isEqualTo("node@22.11.0");
        assertThat(p.phase()).isEqualTo(Phase.DOWNLOADING);
        assertThat(p.fraction()).isCloseTo(17.4 / 34.2, within(1e-9));
        assertThat(p.fractionPerSecond()).isCloseTo(3.4 / 34.2, within(1e-9));
        assertThat(p.transferred()).isEqualTo("17.4/34.2 MB");
        assertThat(p.speed()).isEqualTo("3.4 MB/s");
        assertThat(p.eta()).isNull();
    }

    @Test
    void phaseWithoutFiguresIsIndeterminate() {
        InstallProgress p = parser.parse("  node@22.11.0  extracting  12.0s");

        assertThat(p).isEqualTo(InstallProgress.of("node@22.11.0", Phase.EXTRACTING));
    }

    @Test
    void mixedUnitsAreNormalisedBeforeDividing() {
        InstallProgress p = parser.parse("  go@1.25.0  downloading  1.0s  512 kB/2.0 MB · 1.0 MB/s");

        assertThat(p).isNotNull();
        assertThat(p.fraction()).isCloseTo(0.256, within(1e-9));
        assertThat(p.fractionPerSecond()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void binaryPrefixesUse1024() {
        InstallProgress p = parser.parse("  go@1.25.0  downloading  1.0s  1.0/4.0 GiB · 512 MiB/s");

        assertThat(p).isNotNull();
        assertThat(p.fraction()).isCloseTo(0.25, within(1e-9));
        assertThat(p.fractionPerSecond()).isCloseTo(0.125, within(1e-9));
    }

    @Test
    void unknownTotalKeepsSpeedButNoFraction() {
        InstallProgress p = parser.parse("  ripgrep@14.1.1  downloading  2.0s  3.1 MB · 1.2 MB/s");

        assertThat(p).isNotNull();
        assertThat(p.fraction()).isNull();
        assertThat(p.fractionPerSecond()).isNull();
        assertThat(p.speed()).isEqualTo("1.2 MB/s");
    }

    @Test
    void doneNeverExceedsOne() {
        InstallProgress p = parser.parse("  node@22.11.0  downloading  9.0s  34.3/34.2 MB · 3.4 MB/s");

        assertThat(p).isNotNull();
        assertThat(p.fraction()).isEqualTo(1.0);
    }

    @Test
    void phaseWordsMapOntoPhases() {
        assertThat(phaseOf("downloading")).isEqualTo(Phase.DOWNLOADING);
        assertThat(phaseOf("extracting")).isEqualTo(Phase.EXTRACTING);
        assertThat(phaseOf("verifying checksum")).isEqualTo(Phase.VERIFYING);
        assertThat(phaseOf("resolving")).isEqualTo(Phase.PREPARING);
        assertThat(phaseOf("installing")).isEqualTo(Phase.INSTALLING);
        assertThat(phaseOf("running postinstall")).isEqualTo(Phase.INSTALLING);
    }

    private Phase phaseOf(String word) {
        InstallProgress p = parser.parse("  node@22.11.0  " + word + "  1.0s");
        assertThat(p).as(word).isNotNull();
        return p.phase();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mise by @jdx – installing 1 tool",
            "mise node@22.11.0 v22.11.0",
            "mise ✓ node@22.11.0  19.2s  node-v22.11.0-win-x64.zip",
            "mise WARN  node installed but not activated — it is not in any config file.",
            "  mise use node",
            "",
    })
    void ordinaryOutputIsNotProgress(String line) {
        assertThat(parser.parse(line)).isNull();
        assertThat(parser.isNoise(line)).isFalse();
    }

    @Test
    void aggregateBarIsNoise() {
        assertThat(parser.isNoise("mise ██████░░░░░░░░░░ 0/1 · 6.0s")).isTrue();
        assertThat(parser.isNoise("mise ████████████████ 1/1 · installed 1 tool in 19.2s")).isTrue();
    }

    /** The whole captured {@code mise install node@22.11.0}, line by line as tambo receives it. */
    @Test
    void realInstallCaptureReadsAsDownloadThenExtract() {
        List<InstallProgress> progress = new ArrayList<>();
        List<String> output = new ArrayList<>();
        int noise = 0;
        for (String line : Fixtures.streamedLines("mise-install-node.txt")) {
            if (line.isBlank()) {
                continue;
            }
            if (parser.isNoise(line)) {
                noise++;
                continue;
            }
            InstallProgress p = parser.parse(line);
            if (p != null) {
                progress.add(p);
            } else {
                output.add(line);
            }
        }

        assertThat(noise).as("aggregate bar lines").isEqualTo(7);
        assertThat(progress).extracting(InstallProgress::phase).containsExactly(
                Phase.DOWNLOADING, Phase.DOWNLOADING, Phase.DOWNLOADING,
                Phase.EXTRACTING, Phase.EXTRACTING, Phase.EXTRACTING);
        assertThat(progress).allSatisfy(p -> assertThat(p.tool()).isEqualTo("node@22.11.0"));
        assertThat(progress.subList(0, 3)).extracting(InstallProgress::fraction)
                .isSortedAccordingTo(Double::compare)
                .allSatisfy(f -> assertThat(f).isBetween(0.0, 1.0));
        assertThat(output).contains("mise ✓ node@22.11.0  19.2s  node-v22.11.0-win-x64.zip");
    }
}
