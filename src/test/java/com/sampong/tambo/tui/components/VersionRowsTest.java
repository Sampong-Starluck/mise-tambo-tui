package com.sampong.tambo.tui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.components.VersionRows.Kind;
import com.sampong.tambo.tui.components.VersionRows.VersionRow;

class VersionRowsTest {

    private static final String NODE = "node";

    private static SdkVersion installed(String name, String version, boolean active) {
        return new SdkVersion(name, version, null, null, null, null, true, active);
    }

    private static SdkVersion pinned(String name, String version, boolean active, String sourceType, String sourcePath) {
        return new SdkVersion(name, version, null, null, sourceType, sourcePath, true, active);
    }

    private static List<String> versionsOf(List<VersionRow> rows) {
        return rows.stream().filter(r -> r.kind() == Kind.VERSION).map(VersionRow::version).toList();
    }

    private static List<String> textsOf(List<VersionRow> rows) {
        return rows.stream().map(VersionRow::text).toList();
    }

    @Test
    void groupsInstalledUnderHeaderThenAvailableUnderCountedHeader() {
        List<SdkVersion> sdks = List.of(installed(NODE, "22.11.0", true));
        List<SdkRelease> releases = List.of(SdkRelease.of("23.0.0"), SdkRelease.of("22.9.0"));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, releases, null);

        assertThat(rows.get(0).kind()).isEqualTo(Kind.HEADER);
        assertThat(rows.get(0).text()).isEqualTo("INSTALLED");
        assertThat(rows.get(1).version()).isEqualTo("22.11.0");
        assertThat(rows.get(2).text()).isEqualTo("AVAILABLE · 2");
        assertThat(rows.get(2).kind()).isEqualTo(Kind.HEADER);
        assertThat(versionsOf(rows)).containsExactly("22.11.0", "23.0.0", "22.9.0");
    }

    @Test
    void activeVersionLeadsInstalledList() {
        List<SdkVersion> sdks = List.of(
                installed(NODE, "22.11.0", false),
                installed(NODE, "20.18.0", true),
                installed(NODE, "22.9.0", false));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, null, null);

        assertThat(versionsOf(rows)).containsExactly("20.18.0", "22.11.0", "22.9.0");
        assertThat(rows.get(1).active()).isTrue();
        assertThat(rows.get(1).installed()).isTrue();
    }

    @Test
    void installedVersionsSortNumericallyNotLexically() {
        List<SdkVersion> sdks = List.of(
                installed(NODE, "22.9.0", false),
                installed(NODE, "20.18.0", false),
                installed(NODE, "22.11.0", false));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, null, null);

        // Lexically "22.9.0" > "22.11.0", which is the bug this guards against.
        assertThat(versionsOf(rows)).containsExactly("22.11.0", "22.9.0", "20.18.0");
    }

    @Test
    void comparatorIsNumericAwareAndHandlesPreReleases() {
        assertThat(VersionRows.compareVersions("22.11.0", "22.9.0")).isPositive();
        assertThat(VersionRows.compareVersions("22.9.0", "20.18.0")).isPositive();
        assertThat(VersionRows.compareVersions("22.0.1", "22.0")).isPositive();
        assertThat(VersionRows.compareVersions("1.0.0-rc1", "1.0.0")).isNegative();
        assertThat(VersionRows.compareVersions("v20.1.0", "20.1.0")).isZero();
    }

    @Test
    void projectTagForActiveOrConfigPinnedVersion() {
        List<SdkVersion> sdks = List.of(
                pinned(NODE, "22.11.0", true, "mise.toml", "C:/work/app/mise.toml"));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, null, null);

        assertThat(rows.get(1).tag()).isEqualTo("project");
    }

    @Test
    void globalTagForVersionPinnedByUserConfig() {
        List<SdkVersion> sdks = List.of(
                pinned(NODE, "22.11.0", true, "global", null),
                pinned(NODE, "20.18.0", false, "config", "C:\\Users\\Sampong\\.config\\mise\\config.toml"));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, null, null);

        assertThat(rows.get(1).tag()).as("global beats active").isEqualTo("global");
        assertThat(rows.get(2).tag()).isEqualTo("global");
    }

    @Test
    void plainInstalledVersionHasNoTag() {
        List<SdkVersion> sdks = List.of(installed(NODE, "20.18.0", false));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, null, null);

        assertThat(rows.get(1).tag()).isEmpty();
    }

    @Test
    void latestAliasIsSkippedAndLatestReleaseIsTagged() {
        List<SdkVersion> sdks = List.of(installed(NODE, "22.9.0", true));
        List<SdkRelease> releases = List.of(
                SdkRelease.latestAlias(),
                SdkRelease.of("22.11.0"),
                SdkRelease.of("22.9.0"));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, releases, "22.11.0");

        assertThat(textsOf(rows)).doesNotContain("latest");
        assertThat(rows.stream().filter(r -> r.kind() == Kind.HEADER).map(VersionRow::text).toList())
                .containsExactly("INSTALLED", "AVAILABLE · 1");
        VersionRow available = rows.get(rows.size() - 1);
        assertThat(available.version()).isEqualTo("22.11.0");
        assertThat(available.tag()).isEqualTo("latest");
        assertThat(available.newer()).as("newer than the active 22.9.0").isTrue();
    }

    @Test
    void installedVersionsAreExcludedFromAvailable() {
        List<SdkVersion> sdks = List.of(
                installed(NODE, "22.9.0", true),
                installed(NODE, "20.18.0", false));
        List<SdkRelease> releases = List.of(
                SdkRelease.of("22.11.0"),
                SdkRelease.of("22.9.0"),
                SdkRelease.installed("20.18.0"),
                SdkRelease.of("18.20.0"));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, releases, null);

        assertThat(rows.stream().filter(r -> r.kind() == Kind.HEADER).map(VersionRow::text).toList())
                .containsExactly("INSTALLED", "AVAILABLE · 2");
        assertThat(versionsOf(rows)).containsExactly("22.9.0", "20.18.0", "22.11.0", "18.20.0");
        VersionRow available = rows.get(rows.size() - 1);
        assertThat(available.installed()).isFalse();
        assertThat(available.active()).isFalse();
    }

    @Test
    void nullReleasesShowLoadingNoteUnderInstalled() {
        List<SdkVersion> sdks = List.of(installed(NODE, "22.9.0", true));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, null, null);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(2).kind()).isEqualTo(Kind.NOTE);
        assertThat(rows.get(2).text()).isEqualTo("loading versions…");
    }

    @Test
    void emptyReleasesShowNoOtherVersionsNote() {
        List<SdkVersion> sdks = List.of(installed(NODE, "22.9.0", true));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, List.of(SdkRelease.latestAlias()), null);

        assertThat(rows.get(rows.size() - 1).kind()).isEqualTo(Kind.NOTE);
        assertThat(rows.get(rows.size() - 1).text()).isEqualTo("no other versions found");
        assertThat(textsOf(rows)).doesNotContain("AVAILABLE · 0");
    }

    @Test
    void onlyTheRequestedToolIsListed() {
        List<SdkVersion> sdks = List.of(
                installed(NODE, "22.9.0", true),
                installed("go", "1.23.0", true));

        List<VersionRow> rows = VersionRows.build("go", sdks, null, null);

        assertThat(versionsOf(rows)).containsExactly("1.23.0");
    }

    @Test
    void entriesWithoutAVersionAreNotListed() {
        List<SdkVersion> sdks = List.of(new SdkVersion(NODE, "", null, null, null, null, false, false));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, null, null);

        assertThat(versionsOf(rows)).isEmpty();
    }

    @Test
    void newerMarkerOnlyOnTheLatestAboveTheActiveVersion() {
        List<SdkVersion> sdks = List.of(installed(NODE, "22.11.0", true));
        List<SdkRelease> releases = List.of(SdkRelease.of("22.11.0"), SdkRelease.of("22.9.0"));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, releases, "22.11.0");

        assertThat(rows.get(1).newer()).as("already active, nothing newer").isFalse();
        assertThat(rows.stream().filter(VersionRow::newer)).isEmpty();
    }

    @Test
    void selectableSkipsHeadersAndNotes() {
        List<SdkVersion> sdks = List.of(installed(NODE, "22.9.0", true));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, null, null);

        // INSTALLED header, the one version, then the loading note: only index 1 is selectable.
        assertThat(VersionRows.selectable(rows)).containsExactly(1);
    }

    @Test
    void selectableListsEveryVersionRowInOrder() {
        List<SdkVersion> sdks = List.of(installed(NODE, "22.9.0", true), installed(NODE, "20.18.0", false));
        List<SdkRelease> releases = List.of(SdkRelease.latestAlias(), SdkRelease.of("23.0.0"));

        List<VersionRow> rows = VersionRows.build(NODE, sdks, releases, null);

        assertThat(VersionRows.selectable(rows)).containsExactly(1, 2, 4);
        assertThat(VersionRows.selectable(rows).stream().map(rows::get).allMatch(r -> r.kind() == Kind.VERSION))
                .isTrue();
    }

    @Test
    void versionPinnedButNotOnDiskIsTaggedMissingNotInstalled() {
        SdkVersion missing = new SdkVersion("go", "1.23.3", null, null, "mise.toml", "mise.toml", false, false);

        VersionRow row = VersionRows.build("go", List.of(missing), List.of(), null).stream()
                .filter(r -> r.kind() == VersionRows.Kind.VERSION)
                .findFirst().orElseThrow();

        assertThat(row.installed()).isFalse();
        assertThat(row.tag()).isEqualTo(VersionRows.MISSING);
    }
}
