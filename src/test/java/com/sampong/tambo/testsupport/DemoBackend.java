package com.sampong.tambo.testsupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.OutdatedSdk;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.service.SdkVersionBackend;

/**
 * A mise-shaped backend that answers from a fixed, believable project — the one the redesign
 * mockups show (java, node, python, maven, go, terraform) — and records every mutating call
 * instead of running anything. For rendering whole screens headlessly.
 */
public final class DemoBackend implements SdkVersionBackend {

    public final List<String> calls = new ArrayList<>();
    private List<SdkVersion> sdks = new ArrayList<>(List.of(
            sdk("java", "21.0.5", true, true),
            sdk("node", "22.11.0", true, true),
            sdk("node", "20.18.0", true, false),
            sdk("python", "3.13.0", true, true),
            sdk("maven", "3.9.9", true, true),
            sdk("go", "1.23.3", false, false),
            sdk("terraform", "1.9.8", true, true)));

    private static SdkVersion sdk(String name, String version, boolean installed, boolean active) {
        return new SdkVersion(name, version, version, "~/.local/share/mise/installs/" + name + "/" + version,
                "mise.toml", "~/code/shop-api/mise.toml", installed, active);
    }

    /** Replaces the tool list, e.g. with an empty one for the empty-state screen. */
    public DemoBackend withSdks(List<SdkVersion> replacement) {
        this.sdks = new ArrayList<>(replacement);
        return this;
    }

    @Override
    public String name() {
        return "mise";
    }

    @Override
    public String projectConfigFileName() {
        return "mise.toml";
    }

    @Override
    public Set<BackendFeature> features() {
        return EnumSet.complementOf(EnumSet.of(BackendFeature.PLUGIN_REGISTRY));
    }

    @Override
    public BackendInfo info() {
        return BackendInfo.versionOnly("mise", "2026.10.4");
    }

    @Override
    public Path globalConfigPath() {
        return Path.of("build-nonexistent", "global-config.toml");
    }

    @Override
    public String removePluginWarning(String plugin) {
        return "Remove the " + plugin + " plugin?";
    }

    @Override
    public List<SdkVersion> listSdks() {
        return sdks;
    }

    @Override
    public List<SdkRelease> listReleases(String sdk) {
        return List.of(SdkRelease.of("23.1.0"), SdkRelease.of("23.0.0"), SdkRelease.of("22.12.0"),
                SdkRelease.installed("22.11.0"), SdkRelease.of("22.10.0"), SdkRelease.installed("20.18.0"));
    }

    @Override
    public List<CatalogEntry> listCatalog() {
        return List.of(CatalogEntry.of("python", "Python programming language"),
                CatalogEntry.of("node", "Node.js JavaScript runtime"),
                CatalogEntry.of("java", "Java development kit"));
    }

    @Override
    public List<ProjectTask> listTasks() {
        return List.of();
    }

    @Override
    public Map<String, String> listEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", "~/.local/share/mise/installs/java/21.0.5");
        env.put("NODE_ENV", "development");
        return env;
    }

    @Override
    public List<OutdatedSdk> listOutdated() {
        return List.of(new OutdatedSdk("java", "21.0.5", "21.0.6"),
                new OutdatedSdk("node", "22.11.0", "23.1.0"),
                new OutdatedSdk("terraform", "1.9.8", "1.10.2"));
    }

    @Override
    public CliResult install(String sdkAtVersion, Consumer<String> onLine, String cancelKey) {
        return record("install " + sdkAtVersion);
    }

    @Override
    public CliResult uninstall(String sdkAtVersion) {
        return record("uninstall " + sdkAtVersion);
    }

    @Override
    public CliResult remove(String sdkAtVersion) {
        return record("remove " + sdkAtVersion);
    }

    @Override
    public CliResult removePlugin(String plugin) {
        return record("remove-plugin " + plugin);
    }

    @Override
    public CliResult use(String sdkAtVersion, boolean global, Consumer<String> onLine, String cancelKey) {
        return record("use " + (global ? "-g " : "") + sdkAtVersion);
    }

    @Override
    public CliResult pin(String sdkAtVersion, boolean global) {
        return record("pin " + (global ? "-g " : "") + sdkAtVersion);
    }

    @Override
    public CliResult upgrade(String sdk, Consumer<String> onLine, String cancelKey) {
        return record("upgrade " + sdk);
    }

    @Override
    public CliResult exec(List<String> args, Consumer<String> onLine, String cancelKey) {
        return record("exec " + String.join(" ", args));
    }

    private CliResult record(String call) {
        calls.add(call);
        return new CliResult(0, "", "");
    }
}
