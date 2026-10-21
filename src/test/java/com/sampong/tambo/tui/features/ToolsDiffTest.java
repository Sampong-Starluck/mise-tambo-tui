package com.sampong.tambo.tui.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ToolsDiffTest {

    @Test
    void reportsAVersionChange() {
        String before = "[tools]\nnode = \"22.9.0\"\n";
        String after = "[tools]\nnode = \"22.11.0\"\n";

        assertThat(ToolsDiff.changes(before, after)).containsExactly("node 22.9.0 → 22.11.0");
    }

    @Test
    void reportsAddedAndRemovedToolsWithSignsAndNameOrder() {
        String before = "[tools]\nnode = \"22\"\ngradle = \"8.10\"\n";
        String after = "[tools]\nnode = \"23\"\nmaven = \"3.9.9\"\n";

        assertThat(ToolsDiff.changes(before, after)).containsExactly(
                "− gradle 8.10",
                "+ maven 3.9.9",
                "node 22 → 23");
    }

    @Test
    void unquotesBackendNamesThatContainColonsAndSlashes() {
        String before = "[tools]\n\"aqua:cli/cli\" = \"1.0\"\n";
        String after = "[tools]\n\"aqua:cli/cli\" = \"2.0\"\n";

        assertThat(ToolsDiff.changes(before, after)).containsExactly("aqua:cli/cli 1.0 → 2.0");
    }

    @Test
    void joinsArrayVersionsWithCommas() {
        String before = "[tools]\n";
        String after = "[tools]\npython = [\"3.12\", \"3.11\"]\n";

        assertThat(ToolsDiff.changes(before, after)).containsExactly("+ python 3.12, 3.11");
        assertThat(ToolsDiff.changes(after, "[tools]\npython = [\"3.13\"]\n"))
                .containsExactly("python 3.12, 3.11 → 3.13");
    }

    @Test
    void takesTheVersionOfAnInlineTable() {
        String before = "[tools]\njava = { version = \"21\", os = [\"linux\"] }\n";
        String after = "[tools]\njava = { version = \"25\", os = [\"linux\"] }\n";

        assertThat(ToolsDiff.changes(before, after)).containsExactly("java 21 → 25");
        assertThat(ToolsDiff.changes(null, "[tools]\njava = { os = [\"linux\"] }\n"))
                .containsExactly("+ java");
    }

    @Test
    void ignoresCommentsAndBlankLines() {
        String before = "[tools]\n# node = \"18\"\n\nnode = \"22\" # pinned for the CI image\n";
        String after = "[tools]\n\n# node = \"99\"\nnode = \"22\"\n";

        assertThat(ToolsDiff.changes(before, after)).isEmpty();
    }

    @Test
    void keepsHashesInsideQuotedVersions() {
        assertThat(ToolsDiff.changes(null, "[tools]\n\"npm:x\" = \"1#2\" # real comment\n"))
                .containsExactly("+ npm:x 1#2");
    }

    @Test
    void readsAMultiLineArray() {
        String after = "[tools]\npython = [\n  \"3.12\",\n  \"3.11\", # older\n]\nnode = \"22\"\n";

        assertThat(ToolsDiff.changes(null, after)).containsExactly(
                "+ node 22",
                "+ python 3.12, 3.11");
    }

    @Test
    void ignoresOtherSections() {
        String before = "[settings]\nnode = \"1\"\n[env]\nFOO = \"bar\"\n[tools]\nnode = \"22\"\n";
        String after = "[tools]\nnode = \"22\"\n[[plugins]]\nnode = \"2\"\n[env]\ngradle = \"9\"\n";

        assertThat(ToolsDiff.changes(before, after)).isEmpty();
    }

    @Test
    void returnsToToolsAfterAnotherSection() {
        String after = "[settings]\nexperimental = true\n[tools]\nnode = \"22\"\n[env]\nX = \"1\"\n";

        assertThat(ToolsDiff.changes(null, after)).containsExactly("+ node 22");
    }

    @Test
    void treatsANullSideAsAFileThatDidNotExist() {
        String content = "[tools]\nnode = \"22\"\ngradle = \"8.10\"\n";

        assertThat(ToolsDiff.changes(null, content)).containsExactly("+ gradle 8.10", "+ node 22");
        assertThat(ToolsDiff.changes(content, null)).containsExactly("− gradle 8.10", "− node 22");
        assertThat(ToolsDiff.changes(null, null)).isEmpty();
    }

    @Test
    void summaryJoinsChangesWithSemicolons() {
        String before = "[tools]\nnode = \"22\"\ngradle = \"8.10\"\n";
        String after = "[tools]\nnode = \"23\"\nmaven = \"3.9.9\"\n";

        assertThat(ToolsDiff.summary(before, after))
                .isEqualTo("− gradle 8.10; + maven 3.9.9; node 22 → 23");
    }

    @Test
    void summaryIsEmptyWhenNothingChanged() {
        String content = "[tools]\nnode = \"22\"\n";

        assertThat(ToolsDiff.summary(content, content)).isEmpty();
        assertThat(ToolsDiff.summary(null, null)).isEmpty();
    }

    @Test
    void summaryFallsBackToEditedWhenOnlyOtherSectionsChanged() {
        String before = "[settings]\nexperimental = false\n[tools]\nnode = \"22\"\n";
        String after = "[settings]\nexperimental = true\n[tools]\nnode = \"22\"\n";

        assertThat(ToolsDiff.summary(before, after)).isEqualTo("edited");
    }

    @Test
    void summaryReportsANewlyCreatedFileWithTools() {
        assertThat(ToolsDiff.summary(null, "[tools]\nnode = \"22\"\n")).isEqualTo("+ node 22");
    }

    @Test
    void changesAreEmptyForAFileWithNoToolsTable() {
        assertThat(ToolsDiff.changes("", "# nothing here\n")).isEqualTo(List.of());
    }
}
