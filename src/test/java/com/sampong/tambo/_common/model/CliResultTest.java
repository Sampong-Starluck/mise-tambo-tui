package com.sampong.tambo._common.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CliResultTest {

    @Test
    void okMeansExitCodeZero() {
        assertThat(new CliResult(0, "", "").ok()).isTrue();
        assertThat(new CliResult(1, "", "").ok()).isFalse();
        assertThat(new CliResult(-1, "", "").ok()).isFalse();
    }

    @Test
    void summaryPrefersTheFirstNonBlankStderrLine() {
        CliResult r = new CliResult(1, "stdout line", "\n  \nmise ERROR  no such tool\nmore detail\n");

        assertThat(r.summaryLine()).isEqualTo("mise ERROR  no such tool");
    }

    @Test
    void summaryFallsBackToStdout() {
        assertThat(new CliResult(1, "\n  first\nsecond", "   ").summaryLine()).isEqualTo("first");
        assertThat(new CliResult(1, "", "").summaryLine()).isEmpty();
    }
}
