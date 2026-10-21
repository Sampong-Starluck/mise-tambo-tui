package com.sampong.tambo.tui.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.InstallProgress.Phase;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.service.ProgressParser;
import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo.mise.ShellActivationService.ActivationOutcome;
import com.sampong.tambo.mise.implement.MiseParsers;
import com.sampong.tambo.testsupport.CannedResponses;
import com.sampong.tambo.testsupport.FakeVfoxCli;
import com.sampong.tambo.testsupport.Fixtures;
import com.sampong.tambo.tui.state.LogEntry;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.ProgressSnapshot;
import com.sampong.tambo.tui.state.UiState;
import com.sampong.tambo.vfox.VfoxSdkBackend;

/**
 * The actions layer end to end, minus the terminal: background work runs inline on the test
 * thread and the "render thread" is the test thread too, so every effect is visible as soon as
 * the call returns. A {@link #watch} hook samples state after every render-thread task, which is
 * how the tests see progress that is cleared again by the time the install finishes.
 */
class BackendActionsTest {

    private static final AsyncTaskExecutor INLINE = new TaskExecutorAdapter(Runnable::run);

    private final UiState state = new UiState();
    private final List<Runnable> watchers = new ArrayList<>();
    private final Consumer<Runnable> renderThread = r -> {
        r.run();
        watchers.forEach(Runnable::run);
    };

    private BackendActions actions(SdkVersionBackend backend, AsyncTaskExecutor executor) {
        state.backend(backend);
        return new BackendActions(backend, () -> new ActivationOutcome(true, false, "already active"),
                new CancelRegistry(), executor, state, renderThread);
    }

    private void watch(Runnable sample) {
        watchers.add(sample);
    }

    private List<String> logTexts() {
        List<String> texts = new ArrayList<>();
        state.log().forEach(e -> texts.add(e.text()));
        return texts;
    }

    private long logLinesContaining(String text) {
        return logTexts().stream().filter(t -> t.contains(text)).count();
    }

    // ==================== The download animation pipeline ====================

    /** The real vfox backend, fed the real captured vfox install output. */
    @Test
    void vfoxInstallBecomesOneBarAndAPhaseTrailInsteadOfAFloodOfLogLines() {
        CannedResponses canned = new CannedResponses();
        canned.ok("", "add", "nodejs");
        canned.ok(Fixtures.text("vfox-install-nodejs.txt"), "install", "-y", "nodejs@22.11.0");
        BackendActions actions = actions(new VfoxSdkBackend(new FakeVfoxCli(canned), INLINE), INLINE);
        Set<Phase> phasesSeen = EnumSet.noneOf(Phase.class);
        List<Double> fractions = new ArrayList<>();
        watch(() -> {
            ProgressSnapshot p = state.progressFor("nodejs@22.11.0");
            if (p != null) {
                phasesSeen.add(p.progress().phase());
                if (p.progress().fraction() != null) {
                    fractions.add(p.progress().fraction());
                }
            }
        });

        actions.installSdk(sdk("nodejs", "22.11.0"));

        assertThat(phasesSeen).containsExactlyInAnyOrder(
                Phase.PREPARING, Phase.DOWNLOADING, Phase.VERIFYING, Phase.EXTRACTING);
        assertThat(fractions).isSortedAccordingTo(Double::compare).contains(1.0);
        // ~100 redraw frames, but only the first frame of the download reaches the log.
        assertThat(logLinesContaining("Downloading...")).isEqualTo(1);
        assertThat(logTexts()).anySatisfy(t -> assertThat(t).startsWith("[nodejs@22.11.0] Verifying checksum"));
        assertThat(logTexts()).contains("[nodejs@22.11.0] Install nodejs@22.11.0 success!", "Installed nodejs@22.11.0");
        assertThat(state.isBusy("nodejs@22.11.0")).isFalse();
        assertThat(state.allProgress()).as("cleared with the busy key").isEmpty();
    }

    /** {@code mise upgrade} of several tools reports them interleaved; each keeps its own bar. */
    @Test
    void upgradeAllTracksEachToolSeparately() {
        ScriptedBackend backend = new ScriptedBackend(MiseParsers.progress(), List.of(
                "mise by @jdx – upgrading 2 tools",
                "mise ███░░░░░░░░░░░░░ 0/2 · 3.0s",
                "  node@24.1.0  downloading  3.0s  10.0/40.0 MB · 4.0 MB/s",
                "  python@3.14.6  downloading  3.0s  5.0/20.0 MB · 2.0 MB/s",
                "mise ███████░░░░░░░░░ 0/2 · 6.0s",
                "  node@24.1.0  downloading  6.0s  22.0/40.0 MB · 4.0 MB/s",
                "  python@3.14.6  extracting  6.0s",
                "mise ✓ python@3.14.6  6.5s",
                "mise ✓ node@24.1.0  9.0s"));
        BackendActions actions = actions(backend, INLINE);
        Set<String> subjectsSeen = new HashSet<>();
        List<Integer> concurrentBars = new ArrayList<>();
        watch(() -> {
            state.allProgress().forEach(p -> subjectsSeen.add(p.subject()));
            concurrentBars.add(state.allProgress().size());
        });

        actions.upgradeAll();

        assertThat(subjectsSeen).containsExactlyInAnyOrder("node@24.1.0", "python@3.14.6");
        assertThat(concurrentBars).contains(2);
        assertThat(logTexts()).as("aggregate bar is noise").noneMatch(t -> t.contains("███"));
        assertThat(logLinesContaining("node@24.1.0  downloading")).as("first frame per tool").isEqualTo(1);
        assertThat(logLinesContaining("python@3.14.6  downloading")).isEqualTo(1);
        assertThat(logLinesContaining("python@3.14.6  extracting")).isEqualTo(1);
        assertThat(logTexts()).contains("[upgrade] mise ✓ node@24.1.0  9.0s", "Upgraded all outdated tools");
        assertThat(state.allProgress()).isEmpty();
    }

    @Test
    void outputThatIsNotProgressStillReachesTheLogAndTheRow() {
        ScriptedBackend backend = new ScriptedBackend(ProgressParser.NONE, List.of("compiling…", "linking…"));
        BackendActions actions = actions(backend, INLINE);
        List<String> rowStatus = new ArrayList<>();
        watch(() -> {
            String s = state.busyStatusFor("ruby@3.4.0");
            if (s != null) {
                rowStatus.add(s);
            }
        });

        actions.installSdk(sdk("ruby", "3.4.0"));

        assertThat(logTexts()).contains("[ruby@3.4.0] compiling…", "[ruby@3.4.0] linking…");
        assertThat(rowStatus).contains("linking…");
    }

    // ==================== Guards ====================

    @Test
    void aSecondInstallOfTheSameVersionWhileOneRunsIsIgnored() {
        List<Runnable> queued = new ArrayList<>();
        BackendActions actions = actions(new ScriptedBackend(ProgressParser.NONE, List.of()),
                new TaskExecutorAdapter(queued::add));

        actions.installSdk(sdk("node", "24.1.0"));
        actions.installSdk(sdk("node", "24.1.0"));

        assertThat(queued).hasSize(1);
        assertThat(logLinesContaining("install node@24.1.0")).isEqualTo(1);
        assertThat(state.isBusy("node@24.1.0")).isTrue();
    }

    @Test
    void offlineRefusesAnythingThatNeedsTheNetwork() {
        ScriptedBackend backend = new ScriptedBackend(ProgressParser.NONE, List.of());
        BackendActions actions = actions(backend, INLINE);
        state.offline(true);

        actions.installSdk(sdk("node", "24.1.0"));
        actions.useSdk("node@24.1.0", false);
        actions.upgradeAll();

        assertThat(backend.calls).isEmpty();
        assertThat(state.anyBusy()).isFalse();
        assertThat(logLinesContaining("Offline mode")).isEqualTo(3);
    }

    @Test
    void anUnsupportedFeatureIsRefusedWithTheBackendsReason() {
        ScriptedBackend backend = new ScriptedBackend(ProgressParser.NONE, List.of(),
                EnumSet.noneOf(BackendFeature.class));
        BackendActions actions = actions(backend, INLINE);

        actions.upgradeAll();
        actions.runTask("build", "");

        assertThat(backend.calls).isEmpty();
        assertThat(logTexts()).containsExactly("scripted does not support this.", "scripted does not support this.");
    }

    @Test
    void aCrashingBackendDoesNotLeaveTheRowBusyForever() {
        ScriptedBackend backend = new ScriptedBackend(ProgressParser.NONE, List.of()) {
            @Override
            public CliResult install(String sdkAtVersion, Consumer<String> onLine, String cancelKey) {
                throw new IllegalStateException("mise vanished");
            }
        };
        BackendActions actions = actions(backend, INLINE);

        actions.installSdk(sdk("node", "24.1.0"));

        assertThat(state.isBusy("node@24.1.0")).isFalse();
        assertThat(state.log()).anySatisfy(e -> {
            assertThat(e.level()).isEqualTo(LogLevel.ERROR);
            assertThat(e.text()).contains("mise vanished");
        });
    }

    @Test
    void aFailedInstallIsLoggedAsAnErrorWithTheCliSummary() {
        ScriptedBackend backend = new ScriptedBackend(ProgressParser.NONE, List.of(),
                EnumSet.allOf(BackendFeature.class), new CliResult(1, "", "mise ERROR  no such version\nmore"));
        BackendActions actions = actions(backend, INLINE);

        actions.installSdk(sdk("node", "99.0.0"));

        assertThat(state.log()).contains(
                new LogEntry(LogLevel.ERROR, "Install failed: node@99.0.0: mise ERROR  no such version"));
    }

    // ==================== Config save ====================

    @Test
    void saveConfigWritesTheFileAndReloads(@TempDir Path dir) throws Exception {
        ScriptedBackend backend = new ScriptedBackend(ProgressParser.NONE, List.of());
        BackendActions actions = actions(backend, INLINE);
        Path file = dir.resolve("mise.toml");
        Files.writeString(file, "[tools]\n");

        actions.saveConfig(file, "[tools]\nnode = \"24\"\n");

        assertThat(Files.readString(file)).isEqualTo("[tools]\nnode = \"24\"\n");
        assertThat(logTexts()).contains("Saved " + file, "Refreshing…");
        assertThat(state.isBusy("save:" + file)).isFalse();
    }

    @Test
    void aFailedSaveIsReportedAndLeavesTheOriginalAlone(@TempDir Path dir) throws Exception {
        BackendActions actions = actions(new ScriptedBackend(ProgressParser.NONE, List.of()), INLINE);
        Path blocker = dir.resolve("not-a-dir");
        Files.writeString(blocker, "a file where the parent directory should be");

        actions.saveConfig(blocker.resolve("mise.toml"), "[tools]\n");

        assertThat(logTexts()).anySatisfy(t -> assertThat(t).startsWith("Could not save "));
        assertThat(Files.readString(blocker)).startsWith("a file where");
    }

    // ==================== Fakes ====================

    private static SdkVersion sdk(String name, String version) {
        return new SdkVersion(name, version, null, null, null, null, false, false);
    }

    /**
     * A backend whose streaming operations replay {@code output} line by line and then return
     * {@code result}. Records which mutating calls were made.
     */
    private static class ScriptedBackend implements SdkVersionBackend {

        final List<String> calls = new ArrayList<>();
        private final ProgressParser parser;
        private final List<String> output;
        private final Set<BackendFeature> features;
        private final CliResult result;

        ScriptedBackend(ProgressParser parser, List<String> output) {
            this(parser, output, EnumSet.allOf(BackendFeature.class));
        }

        ScriptedBackend(ProgressParser parser, List<String> output, Set<BackendFeature> features) {
            this(parser, output, features, new CliResult(0, "", ""));
        }

        ScriptedBackend(ProgressParser parser, List<String> output, Set<BackendFeature> features, CliResult result) {
            this.parser = parser;
            this.output = output;
            this.features = features;
            this.result = result;
        }

        private CliResult replay(String call, Consumer<String> onLine) {
            calls.add(call);
            output.forEach(onLine);
            return result;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public String projectConfigFileName() {
            return "mise.toml";
        }

        @Override
        public Set<BackendFeature> features() {
            return features;
        }

        @Override
        public ProgressParser progressParser() {
            return parser;
        }

        @Override
        public BackendInfo info() {
            return BackendInfo.versionOnly("scripted", "1.0");
        }

        @Override
        public String removePluginWarning(String plugin) {
            return "Remove " + plugin + "?";
        }

        @Override
        public List<SdkVersion> listSdks() {
            return List.of();
        }

        @Override
        public List<SdkRelease> listReleases(String sdk) {
            return List.of();
        }

        @Override
        public List<CatalogEntry> listCatalog() {
            return List.of();
        }

        @Override
        public CliResult install(String sdkAtVersion, Consumer<String> onLine, String cancelKey) {
            return replay("install " + sdkAtVersion, onLine);
        }

        @Override
        public CliResult uninstall(String sdkAtVersion) {
            calls.add("uninstall " + sdkAtVersion);
            return result;
        }

        @Override
        public CliResult remove(String sdkAtVersion) {
            calls.add("remove " + sdkAtVersion);
            return result;
        }

        @Override
        public CliResult removePlugin(String plugin) {
            calls.add("remove-plugin " + plugin);
            return result;
        }

        @Override
        public CliResult use(String sdkAtVersion, boolean global, Consumer<String> onLine, String cancelKey) {
            return replay("use " + sdkAtVersion, onLine);
        }

        @Override
        public CliResult pin(String sdkAtVersion, boolean global) {
            calls.add("pin " + sdkAtVersion);
            return result;
        }

        @Override
        public CliResult upgrade(String sdk, Consumer<String> onLine, String cancelKey) {
            return replay("upgrade " + sdk, onLine);
        }

        @Override
        public CliResult runTask(String taskName, String args, Consumer<String> onLine, String cancelKey) {
            return replay("run " + taskName, onLine);
        }

        @Override
        public CliResult exec(List<String> args, Consumer<String> onLine, String cancelKey) {
            return replay("exec " + String.join(" ", args), onLine);
        }
    }
}
