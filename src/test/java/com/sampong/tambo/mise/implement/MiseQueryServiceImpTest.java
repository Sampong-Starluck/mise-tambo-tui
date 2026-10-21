package com.sampong.tambo.mise.implement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.OutdatedSdk;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.model.TrustState;
import com.sampong.tambo.config.TestMappers;
import com.sampong.tambo.testsupport.CannedResponses;
import com.sampong.tambo.testsupport.FakeMiseCli;

/**
 * Parsing of mise's {@code -J} JSON and plain-text reports, through the production Jackson 3
 * mapper. The JSON mirrors the shape of real mise 2026.9 output, trimmed and with neutral paths.
 */
class MiseQueryServiceImpTest {

    private static final String LS_JSON = """
            {
              "node": [
                {
                  "version": "24.1.0",
                  "requested_version": "24",
                  "install_path": "/home/dev/.local/share/mise/installs/node/24.1.0",
                  "source": { "type": "mise.toml", "path": "/work/app/mise.toml" },
                  "installed": true,
                  "active": true
                },
                {
                  "version": "22.11.0",
                  "install_path": "/home/dev/.local/share/mise/installs/node/22.11.0",
                  "installed": true,
                  "active": false
                }
              ],
              "bun": [
                {
                  "version": "1.4.3",
                  "requested_version": "latest",
                  "install_path": "/home/dev/.local/share/mise/installs/bun/1.4.3",
                  "source": { "type": "mise.toml", "path": "/work/app/mise.toml" },
                  "installed": false,
                  "active": false,
                  "some_future_field": { "ignored": true }
                }
              ]
            }
            """;

    private final CannedResponses canned = new CannedResponses();

    private MiseQueryServiceImp service(String... launchArgs) {
        return new MiseQueryServiceImp(FakeMiseCli.launchedWith(canned, launchArgs), TestMappers.appMapper(),
                new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
    }

    // ==================== ls ====================

    @Test
    void listSdksParsesAndSortsByNameThenVersion() {
        canned.ok(LS_JSON, "ls", "-J");

        List<SdkVersion> sdks = service().listSdks();

        assertThat(sdks).extracting(SdkVersion::label).containsExactly("bun@1.4.3", "node@22.11.0", "node@24.1.0");
        SdkVersion node24 = sdks.getLast();
        assertThat(node24.requested()).isEqualTo("24");
        assertThat(node24.sourceType()).isEqualTo("mise.toml");
        assertThat(node24.sourcePath()).isEqualTo("/work/app/mise.toml");
        assertThat(node24.installed()).isTrue();
        assertThat(node24.active()).isTrue();
        assertThat(sdks.get(1).sourceType()).as("no source block").isNull();
    }

    @Test
    void offlineHidesWhatIsNotInstalled() {
        canned.ok(LS_JSON, "ls", "-J");

        assertThat(service("--offline").listSdks()).extracting(SdkVersion::name).containsOnly("node");
    }

    @Test
    void aNullFlagReadsAsFalseRatherThanFailingTheListing() {
        canned.ok("""
                { "go": [ { "version": "1.25.0", "installed": null, "active": null } ] }
                """, "ls", "-J");

        List<SdkVersion> sdks = service().listSdks();

        assertThat(sdks).singleElement().satisfies(go -> {
            assertThat(go.installed()).isFalse();
            assertThat(go.active()).isFalse();
        });
    }

    @Test
    void brokenOrFailedOutputGivesAnEmptyListNotAnError() {
        canned.ok("{ not json", "ls", "-J");
        assertThat(service().listSdks()).isEmpty();

        canned.respond(new CliResult(1, LS_JSON, "boom"), "ls", "-J");
        assertThat(service().listSdks()).isEmpty();
    }

    // ==================== tasks ====================

    @Test
    void listTasksToleratesMissingAndNullFields() {
        canned.ok("""
                [
                  {
                    "name": "compile",
                    "aliases": ["c"],
                    "description": "Maven task for compile project",
                    "source": "/work/app/mise.toml",
                    "depends": [],
                    "daemons": null,
                    "tools": {},
                    "timeout": null,
                    "run": ["mvn compile"]
                  },
                  { "name": "lint" },
                  { "description": "nameless entries are skipped" }
                ]
                """, "tasks", "ls", "-J");

        List<ProjectTask> tasks = service().listTasks();

        assertThat(tasks).extracting(ProjectTask::name).containsExactly("compile", "lint");
        assertThat(tasks.getFirst().aliases()).containsExactly("c");
        assertThat(tasks.getFirst().run()).containsExactly("mvn compile");
        ProjectTask lint = tasks.get(1);
        assertThat(lint.aliases()).isEmpty();
        assertThat(lint.depends()).isEmpty();
        assertThat(lint.run()).isEmpty();
    }

    // ==================== registry ====================

    @Test
    void listCatalogReadsShortNamesBackendsAndAliases() {
        canned.ok("""
                [
                  {
                    "short": "1password",
                    "backends": ["vfox:mise-plugins/vfox-1password", "aqua:1password/cli"],
                    "bins": ["op"],
                    "description": "Password manager developed by AgileBits Inc",
                    "aliases": ["1password-cli", "op"]
                  },
                  { "short": "7z", "backends": ["aqua:ip7z/7zip"] },
                  { "backends": ["no short name — skipped"] }
                ]
                """, "registry", "-J");

        List<CatalogEntry> catalog = service().listCatalog();

        assertThat(catalog).extracting(CatalogEntry::name).containsExactly("1password", "7z");
        assertThat(catalog.getFirst().aliases()).containsExactly("1password-cli", "op");
        assertThat(catalog.get(1).aliases()).isEmpty();
        assertThat(catalog.get(1).description()).isNull();
    }

    // ==================== outdated ====================

    @Test
    void listOutdatedFallsBackToTheMapKeyForAName() {
        canned.ok("""
                {
                  "vfox": { "name": "vfox", "requested": "latest", "current": "1.0.12", "bump": null, "latest": "1.0.13" },
                  "node": { "current": "22.11.0", "latest": "22.12.0" }
                }
                """, "outdated", "-J");

        assertThat(service().listOutdated()).containsExactly(
                new OutdatedSdk("vfox", "1.0.12", "1.0.13"),
                new OutdatedSdk("node", "22.11.0", "22.12.0"));
    }

    @Test
    void offlineNeverAsksWhatIsOutdated() {
        assertThat(service("--offline").listOutdated()).isEmpty();
        assertThat(canned.calls()).isEmpty();
    }

    // ==================== env ====================

    @Test
    void listEnvComesBackSortedByName() {
        canned.ok("""
                { "PATH": "/a:/b", "JAVA_HOME": "/jdk", "MISE_SHELL": "bash" }
                """, "env", "-J");

        assertThat(service().listEnv().keySet()).containsExactly("JAVA_HOME", "MISE_SHELL", "PATH");
    }

    // ==================== trust ====================

    @Test
    void trustStatusSplitsWindowsPathsAtTheLastColon() {
        canned.ok("""
                C:\\Users\\dev: trusted
                C:\\Users\\dev\\work\\app: untrusted
                /home/dev/other: ignored
                mise WARN  something unrelated
                """, "trust", "--show");

        assertThat(service().trustStatus()).containsExactly(
                new TrustState("C:\\Users\\dev", true),
                new TrustState("C:\\Users\\dev\\work\\app", false),
                new TrustState("/home/dev/other", false));
    }

    // ==================== doctor ====================

    @Test
    void infoReadsVersionShimsAndConfigFilesFromDoctor() {
        canned.ok("""
                version: 2026.9.18 windows-x64 (2026-09-30)
                activated: yes
                shims_on_path: yes
                self_update_available: yes

                dirs:
                  cache: ~/.cache/mise
                  config: ~/.config/mise

                config_files:
                  ~/.config/mise/config.toml
                  ~/mise.toml
                  ~/work/app/mise.toml

                env_files:
                  (none)
                """, "doctor");

        BackendInfo info = service().info();

        assertThat(info.name()).isEqualTo("mise");
        assertThat(info.version()).isEqualTo("2026.9.18 windows-x64 (2026-09-30)");
        assertThat(info.activated()).isTrue();
        assertThat(info.shimsOnPath()).isTrue();
        assertThat(info.configFileCount()).isEqualTo(3);
        assertThat(info.reportsHealth()).isTrue();
    }

    @Test
    void infoWithoutDoctorOutputIsUnknownButStillAHealthReport() {
        canned.respond(new CliResult(1, "", "mise: command failed"), "doctor");

        BackendInfo info = service().info();

        assertThat(info.version()).isEqualTo("unknown");
        assertThat(info.configFileCount()).isZero();
    }

    // ==================== ls-remote ====================

    @Test
    void listReleasesIsNewestFirstAfterLatestAndMarksWhatIsInstalled() {
        canned.ok(LS_JSON, "ls", "-J");
        canned.ok("22.10.0\n22.11.0\n24.0.0\n24.1.0\n", "ls-remote", "node");

        List<SdkRelease> releases = service().listReleases("node");

        assertThat(releases.getFirst()).isEqualTo(SdkRelease.latestAlias());
        assertThat(releases.subList(1, releases.size())).containsExactly(
                new SdkRelease("24.1.0", true, false),
                new SdkRelease("24.0.0", false, false),
                new SdkRelease("22.11.0", true, false),
                new SdkRelease("22.10.0", false, false));
    }
}
