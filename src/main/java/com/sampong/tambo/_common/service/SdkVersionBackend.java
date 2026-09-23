package com.sampong.tambo._common.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.OutdatedSdk;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.model.TrustState;

/**
 * The whole of what the TUI asks of a version manager, in one shape both mise and vfox answer.
 * <p>
 * This used to cover only the install/use/list slice the two backends happened to share, and
 * everything else — tasks, env, doctor, trust, prune, upgrade, self-update — was wired straight
 * to the mise services, which is why whole panels disappeared under vfox. The contract now spans
 * every operation the UI performs, and the gaps are declared through {@link #features()} rather
 * than by a panel checking which backend is running.
 * <p>
 * Every optional operation has a default here that reports itself unsupported, so a backend
 * implements only what it genuinely has: queries default to empty, mutations to a failed
 * {@link CliResult} carrying {@link #unsupportedReason}. That keeps "vfox cannot do this" a
 * single, uniform answer the UI can render the same way everywhere, instead of each call site
 * inventing its own fallback.
 */
public interface SdkVersionBackend {

    // ==================== Identity ====================

    /** Short backend name ({@code mise} or {@code vfox}), used in command-log lines. */
    String name();

    /** The project-scope config file this backend reads, e.g. {@code mise.toml}. */
    String projectConfigFileName();

    /** What this backend can do. Everything not listed renders as unsupported. */
    Set<BackendFeature> features();

    default boolean supports(BackendFeature feature) {
        return features().contains(feature);
    }

    /**
     * One sentence explaining why {@code feature} is missing, shown wherever the UI would have
     * offered it. Backends are expected to override this with something a user can act on
     * ("vfox has no task runner — tasks come from mise") rather than leave the bare default.
     */
    default String unsupportedReason(BackendFeature feature) {
        return name() + " does not support this.";
    }

    /** Version and, where the backend has one, a health summary of its own installation. */
    BackendInfo info();

    /**
     * The user-level config file, for backends that expose one the app can edit. Null unless
     * {@link BackendFeature#GLOBAL_CONFIG} is supported — the UI offers the editor only then,
     * rather than opening a path that backend never reads.
     */
    default @Nullable Path globalConfigPath() {
        return null;
    }

    /**
     * What removing {@code plugin} will actually destroy, phrased as a question to confirm.
     * The two backends differ materially here — vfox deletes every installed version along
     * with the plugin, mise keeps them — and that difference has to reach the user, so it is
     * the backend's sentence rather than a branch at the call site.
     */
    String removePluginWarning(String plugin);

    // ==================== Queries ====================

    /** Installed/configured SDK versions. */
    List<SdkVersion> listSdks();

    /** Installable versions of one SDK, newest first. */
    List<SdkRelease> listReleases(String sdk);

    /** Every SDK this backend can install, for the catalog browser. */
    List<CatalogEntry> listCatalog();

    /** Requires {@link BackendFeature#TASKS}. */
    default List<ProjectTask> listTasks() {
        return List.of();
    }

    /** Requires {@link BackendFeature#ENV}. */
    default Map<String, String> listEnv() {
        return Map.of();
    }

    /** Requires {@link BackendFeature#UPGRADE}. */
    default List<OutdatedSdk> listOutdated() {
        return List.of();
    }

    /** Requires {@link BackendFeature#TRUST}. */
    default List<TrustState> trustStatus() {
        return List.of();
    }

    // ==================== SDK operations ====================

    /** Installs {@code sdk@version}, streaming progress through {@code onLine}. */
    CliResult install(String sdkAtVersion, Consumer<String> onLine, String cancelKey);

    /** Uninstalls an installed {@code sdk@version}. */
    CliResult uninstall(String sdkAtVersion);

    /**
     * Unpins {@code sdk[@version]} from whichever scope currently declares it, without
     * necessarily deleting the installed version.
     */
    CliResult remove(String sdkAtVersion);

    /**
     * Removes the plugin behind a bare SDK name. How destructive that is differs per backend:
     * vfox deletes every installed version along with the plugin, mise keeps installed versions
     * and drops only the plugin, so callers must confirm with the backend's own wording.
     */
    CliResult removePlugin(String plugin);

    /**
     * Pins {@code sdk@version} at project scope (installing it if needed), or global scope when
     * {@code global} is true — the latter requires {@link BackendFeature#GLOBAL_SCOPE}.
     */
    CliResult use(String sdkAtVersion, boolean global, Consumer<String> onLine, String cancelKey);

    /**
     * Pins an <em>already-installed</em> {@code sdk@version} at project or global scope without
     * installing anything.
     * <p>
     * The distinction from {@link #use} is the whole point: {@code use} means "make this the
     * version, fetching it if necessary", and under vfox that is literally
     * {@code add} + {@code install} + {@code use}. This means "adopt what is already on disk",
     * which is what the auto-install flow needs when the user answers a near-miss by keeping
     * the version they have — running {@code use} there would re-run an install for a version
     * that is already present. Not streamed: nothing is downloaded, so there is no progress to
     * report.
     */
    CliResult pin(String sdkAtVersion, boolean global);

    /**
     * Installs every tool the project config declares, in one command. Requires
     * {@link BackendFeature#BULK_INSTALL}; a backend without it is driven one tool at a time
     * through {@link #use} instead.
     */
    default CliResult installAll(Consumer<String> onLine, String cancelKey) {
        return unsupported(BackendFeature.BULK_INSTALL);
    }

    /**
     * Upgrades one SDK to the newest version its config allows, or every outdated SDK when
     * {@code sdk} is blank. Requires {@link BackendFeature#UPGRADE}.
     */
    default CliResult upgrade(String sdk, Consumer<String> onLine, String cancelKey) {
        return unsupported(BackendFeature.UPGRADE);
    }

    /** Registers a plugin without installing a version. Requires {@link BackendFeature#PLUGIN_REGISTRY}. */
    default CliResult registerPlugin(List<String> args) {
        return unsupported(BackendFeature.PLUGIN_REGISTRY);
    }

    // ==================== Project operations ====================

    /** Requires {@link BackendFeature#TASKS}. */
    default CliResult runTask(String taskName, String args, Consumer<String> onLine, String cancelKey) {
        return unsupported(BackendFeature.TASKS);
    }

    /** Requires {@link BackendFeature#TRUST}. */
    default CliResult trust() {
        return unsupported(BackendFeature.TRUST);
    }

    /** Requires {@link BackendFeature#CONFIG_VALIDATE}. */
    default CliResult validateConfig() {
        return unsupported(BackendFeature.CONFIG_VALIDATE);
    }

    // ==================== Backend maintenance ====================

    /** The full health report, streamed. Requires {@link BackendFeature#DOCTOR}. */
    default CliResult doctor(Consumer<String> onLine) {
        return unsupported(BackendFeature.DOCTOR);
    }

    /** Requires {@link BackendFeature#PRUNE}. */
    default CliResult prune(Consumer<String> onLine) {
        return unsupported(BackendFeature.PRUNE);
    }

    /** Updates the backend's own binary. Requires {@link BackendFeature#SELF_UPDATE}. */
    default CliResult selfUpdate(Consumer<String> onLine) {
        return unsupported(BackendFeature.SELF_UPDATE);
    }

    /**
     * The failed result every unsupported operation returns, carrying the backend's own
     * explanation on stderr so the command log reads like any other failure rather than like a
     * crash. Exit code 1 keeps {@link CliResult#ok()} false without colliding with a real exit
     * status the CLI might have produced.
     */
    private CliResult unsupported(BackendFeature feature) {
        return new CliResult(1, "", unsupportedReason(feature));
    }
}
