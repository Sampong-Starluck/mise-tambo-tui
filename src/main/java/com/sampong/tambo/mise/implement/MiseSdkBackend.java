package com.sampong.tambo.mise.implement;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.OutdatedSdk;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.model.TrustState;
import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo.mise.MiseMaintenanceService;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Adapts the mise services to {@link SdkVersionBackend} — a thin, no-op-logic delegate.
 * <p>
 * mise supports everything in {@link BackendFeature} except
 * {@link BackendFeature#PLUGIN_REGISTRY}: mise has no notion of registering a plugin without
 * installing a version of it, since tools are added implicitly through the registry the first
 * time one is used.
 */
@Service
@RequiredArgsConstructor
public class MiseSdkBackend implements SdkVersionBackend {

    /** mise's project-scope config filename, also what startup detection looks for. */
    public static final String PROJECT_CONFIG_FILE = "mise.toml";

    private static final Set<BackendFeature> FEATURES = EnumSet.complementOf(
            EnumSet.of(BackendFeature.PLUGIN_REGISTRY));

    @NonNull
    private final MiseQueryService query;
    @NonNull
    private final MiseToolService tools;
    @NonNull
    private final MiseMaintenanceService maintenance;

    @Override
    public String name() {
        return "mise";
    }

    @Override
    public String projectConfigFileName() {
        return PROJECT_CONFIG_FILE;
    }

    @Override
    public Set<BackendFeature> features() {
        return FEATURES;
    }

    @Override
    public String unsupportedReason(BackendFeature feature) {
        return switch (feature) {
            case PLUGIN_REGISTRY -> "mise adds tools through its registry on first use, "
                    + "so there is no separate plugin to register — press a to add an SDK.";
            default -> "mise does not support this.";
        };
    }

    @Override
    public BackendInfo info() {
        return query.info();
    }

    /** Honors {@code MISE_CONFIG_DIR} when set, as mise itself does. */
    @Override
    public Path globalConfigPath() {
        String configDir = System.getenv("MISE_CONFIG_DIR");
        return configDir != null && !configDir.isBlank()
                ? Path.of(configDir, "config.toml")
                : Path.of(System.getProperty("user.home"), ".config", "mise", "config.toml");
    }

    /** {@code mise plugins uninstall} drops the plugin but leaves installed versions on disk. */
    @Override
    public String removePluginWarning(String plugin) {
        return "Remove mise plugin " + plugin + "? (installed versions are kept)";
    }

    // ==================== Queries ====================

    @Override
    public List<SdkVersion> listSdks() {
        return query.listSdks();
    }

    @Override
    public List<SdkRelease> listReleases(String sdk) {
        return query.listReleases(sdk);
    }

    @Override
    public List<CatalogEntry> listCatalog() {
        return query.listCatalog();
    }

    @Override
    public List<ProjectTask> listTasks() {
        return query.listTasks();
    }

    @Override
    public Map<String, String> listEnv() {
        return query.listEnv();
    }

    @Override
    public List<OutdatedSdk> listOutdated() {
        return query.listOutdated();
    }

    @Override
    public List<TrustState> trustStatus() {
        return query.trustStatus();
    }

    // ==================== SDK operations ====================

    @Override
    public CliResult install(String sdkAtVersion, Consumer<String> onLine, String cancelKey) {
        return tools.install(sdkAtVersion, onLine, cancelKey);
    }

    @Override
    public CliResult uninstall(String sdkAtVersion) {
        return tools.uninstall(sdkAtVersion);
    }

    @Override
    public CliResult remove(String sdkAtVersion) {
        return tools.remove(sdkAtVersion);
    }

    @Override
    public CliResult removePlugin(String plugin) {
        return tools.removePlugin(plugin);
    }

    @Override
    public CliResult use(String sdkAtVersion, boolean global, Consumer<String> onLine, String cancelKey) {
        return tools.use(sdkAtVersion, global, onLine, cancelKey);
    }

    @Override
    public CliResult upgrade(String sdk, Consumer<String> onLine, String cancelKey) {
        return tools.upgrade(sdk, onLine, cancelKey);
    }

    // ==================== Project operations ====================

    @Override
    public CliResult runTask(String taskName, String args, Consumer<String> onLine, String cancelKey) {
        return tools.runTask(taskName, args, onLine, cancelKey);
    }

    @Override
    public CliResult trust() {
        return maintenance.trust();
    }

    @Override
    public CliResult validateConfig() {
        return maintenance.validateConfig();
    }

    // ==================== Backend maintenance ====================

    @Override
    public CliResult doctor(Consumer<String> onLine) {
        return maintenance.doctor(onLine);
    }

    @Override
    public CliResult prune(Consumer<String> onLine) {
        return maintenance.prune(onLine);
    }

    @Override
    public CliResult selfUpdate(Consumer<String> onLine) {
        return maintenance.selfUpdate(onLine);
    }
}
