package com.sampong.tambo.vfox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.testsupport.CannedResponses;
import com.sampong.tambo.testsupport.FakeVfoxCli;
import com.sampong.tambo.testsupport.Fixtures;

/**
 * Parsing of vfox's human-readable output. The fixtures are real vfox 1.0.11 output captured
 * through a pipe, ANSI colour codes and tree-drawing characters included.
 */
class VfoxSdkBackendTest {

    private final CannedResponses canned = new CannedResponses();
    private final VfoxSdkBackend backend = new VfoxSdkBackend(new FakeVfoxCli(canned),
            new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));

    @Test
    void infoTrimsTheVersionBanner() {
        canned.ok("vfox version 1.0.11\n", "-v");

        assertThat(backend.info()).isEqualTo(BackendInfo.versionOnly("vfox", "1.0.11"));
    }

    @Test
    void infoWhenVfoxIsMissing() {
        canned.respond(new CliResult(-1, "", "Failed to launch vfox"), "-v");

        assertThat(backend.info()).isEqualTo(BackendInfo.unknown("vfox"));
    }

    /** {@code vfox list} gives the tree, {@code vfox current} which version of each is active. */
    @Test
    void listSdksReadsTheTreeAndMarksActiveVersionsFromCurrent() {
        canned.ok(Fixtures.text("vfox-list.txt"), "list");
        canned.ok(Fixtures.text("vfox-current.txt"), "current");

        List<SdkVersion> sdks = backend.listSdks();

        assertThat(sdks).extracting(SdkVersion::label).containsExactly(
                "bun@1.4.3",
                "gradle",
                "java@25.0.4+7-tem", "java@17.0.20+8-tem",
                "maven@4.0.0-rc-6", "maven@3.9.16",
                "nodejs@26.6.0",
                "python@3.14.6");
        assertThat(active(sdks)).containsExactly(
                "java@25.0.4+7-tem", "maven@4.0.0-rc-6", "nodejs@26.6.0", "python@3.14.6");
    }

    @Test
    void aPluginWithNoVersionInstalledStillGetsARow() {
        canned.ok(Fixtures.text("vfox-list.txt"), "list");
        canned.ok(Fixtures.text("vfox-current.txt"), "current");

        SdkVersion gradle = backend.listSdks().stream()
                .filter(s -> s.name().equals("gradle")).findFirst().orElseThrow();

        assertThat(gradle.hasVersion()).isFalse();
        assertThat(gradle.installed()).isFalse();
    }

    private static List<String> active(List<SdkVersion> sdks) {
        List<String> active = new ArrayList<>();
        for (SdkVersion s : sdks) {
            if (s.active()) {
                active.add(s.label());
            }
        }
        return active;
    }

    @Test
    void listReleasesTakesTheVersionTokenAndInstalledMarker() {
        canned.ok(Fixtures.text("vfox-search-nodejs.txt")
                + " - 24.15.0 (LTS) [npm 11.12.1] (installed)\n", "search", "nodejs", "all");

        List<SdkRelease> releases = backend.listReleases("nodejs");

        assertThat(releases).extracting(SdkRelease::version)
                .startsWith("26.12.0", "26.11.1", "26.11.0")
                .endsWith("24.15.0")
                .doesNotContain("Available");
        assertThat(releases.getFirst().installed()).isFalse();
        assertThat(releases.getLast().installed()).isTrue();
    }

    @Test
    void listCatalogSeparatesOfficialFromCommunityPlugins() {
        canned.ok(Fixtures.text("vfox-available.txt"), "available");

        List<CatalogEntry> catalog = backend.listCatalog();

        assertThat(catalog).extracting(CatalogEntry::name).startsWith("bun", "clang", "cmake", "crystal");
        assertThat(catalog.getFirst().description()).startsWith("community — https://github.com/arfaWong/vfox-bun");
        assertThat(catalog.get(1).description()).startsWith("official — https://github.com/version-fox/vfox-clang");
    }

    @Test
    void installRegistersThePluginThenInstallsNonInteractively() {
        canned.ok("", "add", "nodejs");
        canned.ok("Install nodejs@22.11.0 success!\n", "install", "-y", "nodejs@22.11.0");
        List<String> lines = new ArrayList<>();

        CliResult result = backend.install("nodejs@22.11.0", lines::add, "nodejs@22.11.0");

        assertThat(result.ok()).isTrue();
        assertThat(canned.calls()).containsExactly(
                List.of("add", "nodejs"),
                List.of("install", "-y", "nodejs@22.11.0"));
        assertThat(lines).containsExactly("Install nodejs@22.11.0 success!");
    }

    @Test
    void useStopsWhenTheInstallFails() {
        canned.ok("", "add", "nodejs");
        canned.respond(new CliResult(1, "download failed", ""), "install", "-y", "nodejs@22.11.0");

        CliResult result = backend.use("nodejs@22.11.0", false, line -> { }, "registry:nodejs");

        assertThat(result.ok()).isFalse();
        assertThat(canned.calls()).doesNotContain(List.of("use", "-p", "nodejs@22.11.0"));
    }

    @Test
    void useAtProjectOrGlobalScope() {
        canned.ok("", "add", "nodejs");
        canned.ok("", "install", "-y", "nodejs@22.11.0");
        canned.ok("", "use", "-p", "nodejs@22.11.0");
        canned.ok("", "use", "-g", "nodejs@22.11.0");

        assertThat(backend.use("nodejs@22.11.0", false, line -> { }, "k").ok()).isTrue();
        assertThat(backend.use("nodejs@22.11.0", true, line -> { }, "k").ok()).isTrue();

        assertThat(canned.calls()).contains(List.of("use", "-p", "nodejs@22.11.0"), List.of("use", "-g", "nodejs@22.11.0"));
    }

    @Test
    void progressComesFromTheVfoxParser() {
        assertThat(backend.progressParser()).isSameAs(VfoxProgressParser.INSTANCE);
    }
}
