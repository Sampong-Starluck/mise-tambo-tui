package com.sampong.tambo.tui.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class FuzzyTest {

    @Test
    void emptyQueryMatchesEverythingNeutrally() {
        assertThat(Fuzzy.score(null, "node")).isZero();
        assertThat(Fuzzy.score("", "node")).isZero();
    }

    @Test
    void queryMustBeASubsequence() {
        assertThat(Fuzzy.score("nde", "node")).isPositive();
        assertThat(Fuzzy.score("NODE", "nodejs")).as("case-insensitive").isPositive();
        assertThat(Fuzzy.score("edon", "node")).isEqualTo(-1);
        assertThat(Fuzzy.score("node", "")).isEqualTo(-1);
        assertThat(Fuzzy.score("node", null)).isEqualTo(-1);
    }

    @Test
    void consecutiveAndBoundaryHitsOutrankScatteredOnes() {
        assertThat(Fuzzy.score("go", "go")).isGreaterThan(Fuzzy.score("go", "gradle-tool"));
        assertThat(Fuzzy.score("nod", "nodejs")).as("consecutive").isGreaterThan(Fuzzy.score("nod", "anxoxd"));
        assertThat(Fuzzy.score("tool", "my-tool")).as("word boundary").isGreaterThan(Fuzzy.score("tool", "mytool"));
    }

    @Test
    void shorterCandidatesWinTies() {
        assertThat(Fuzzy.score("node", "node")).isGreaterThan(Fuzzy.score("node", "node-build-extra-tools"));
    }

    @Test
    void filterRanksPrimaryHitsAboveSecondaryOnes() {
        record Entry(String name, String description) {
        }
        List<Entry> entries = List.of(
                new Entry("deno", "A JavaScript runtime"),
                new Entry("go", "The Go language"),
                new Entry("bun", "Fast JavaScript runtime and bundler"),
                new Entry("node", "Node.js JavaScript runtime"));
        Function<Entry, String> name = Entry::name;
        Function<Entry, String> description = Entry::description;

        List<Entry> hits = Fuzzy.filter("node", entries, name, description);

        assertThat(hits).extracting(Entry::name).first().isEqualTo("node");
        assertThat(Fuzzy.filter("javascript", entries, name, description))
                .extracting(Entry::name).containsExactlyInAnyOrder("deno", "bun", "node");
        assertThat(Fuzzy.filter("javascript", entries, name, null)).isEmpty();
    }

    @Test
    void blankQueryKeepsTheOriginalOrder() {
        List<String> items = List.of("zig", "bun", "node");

        assertThat(Fuzzy.filter("  ", items, Function.identity(), null)).isSameAs(items);
    }
}
